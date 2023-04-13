

package svutil

import scala.util.matching.Regex
import java.io.{ File, FileWriter, PrintWriter,  FileReader, BufferedReader }
import java.util.regex.PatternSyntaxException
import java.time.LocalDateTime
import java.util.UUID
import scala.jdk.CollectionConverters._
import upickle.default.{ read, writeToOutputStream, ReadWriter => RW, macroRW, readwriter }
import scala.xml._
import org.sellmerfud.optparse._
import Color._
import Utilities._

object Stash extends Command {
  
  override val name = "stash"
  override val description = "Stash away changes to a dirty working copy"

  private sealed trait StashCommand {
    val cmdName: String
    def run(args: Seq[String]): Unit
  }

  // Values for the items in svn status
  private val UNVERSIONED = "unversioned"
  private val NORMAL      = "normal"
  private val ADDED       = "added"
  private val DELETED     = "deleted"
  private val MODIFIED    = "modified"
  
  private case class StashRef(index: Int)
    
  // A stash reference can be an integer or stash-<n>
  private val stashRefParser = (arg: String) => {
    val stashArg = """^(?:stash-)?(\d+)$""".r
    arg match {
      case stashArg(ref) => StashRef(ref.toInt)
      case _             => throw new InvalidArgumentException(s" is not a valid stash reference")
    }
  }
  
  // path for directories wil end with a slash/  (only happens for remved directories)
  // revision of the file when it was modified (for display only)
  // status will be one of: deleted, modified, added, unversioned
  private case class StashItem(path: String, revision: String, status: String, isDir: Boolean) {
    def pathDisplay = if (isDir) s"$path/" else path
    
    def statusDisplay = status match {
      case UNVERSIONED => "?" 
      case ADDED       => green("A")
      case DELETED     => red("D")
      case MODIFIED    => purple("M")
      case _           => " "
    }
    // ADDED and UNVERSIONED will have a revision of -1
    def revisionDisplay = if (revision == "-1") "" else s"[${yellow(revision)}]"
  }

  private object StashItem {
    implicit val rw: RW[StashItem] = macroRW  
  }
  
  private case class StashEntry(
    branch: String,        // branch from which the stash was created (trunk, 8.1, etc.)
    revision: String,      // commit revision of working copy root when stash was created
    description: String,
    date: LocalDateTime,   // time of creation
    patchName: String,     // name of patch file for this stash relative to .sv/stash/
    items: Seq[StashItem]) {
      
      def summary = s"${green(branch)}[${yellow(revision)}]: $description"
    }

  private object StashEntry {
    implicit val rw: RW[StashEntry] = macroRW  
  }
  
  
  //  Custom pickler for LocalDateTime
  
  implicit val localDateTimePickler: RW[LocalDateTime] = readwriter[String].bimap[LocalDateTime](
    ldt => toISODateString(ldt),
    str => parseISODate(str)
  )
  
  private def stashPath         = getDataDirectory() / "stash"
  private def stashEntriesFile  = stashPath / "stash_entries.json"
  private def createPatchName() = s"${UUID.randomUUID}.patch"

  private def loadStashEntries(): List[StashEntry] = {
    if (os.isFile(stashEntriesFile)) {
      try read[List[StashEntry]](stashEntriesFile.toIO)
      catch {
        case e: Throwable => 
          generalError(s"Error reading stash entries ($stashEntriesFile): ${e.getMessage}")
      }
    }
    else
      Nil
  }
   
  private def saveStashEntries(entries: List[StashEntry]): Unit = {
    try {
      val ostream = os.write.over.outputStream(stashEntriesFile)
      try writeToOutputStream(entries, ostream, indent = 2)
      finally ostream.close()
    }
    catch {
      case e: Throwable =>
        generalError(s"Error saving stash entries ($stashEntriesFile): ${e.getMessage}")
    }
  }  
   

  //  Update the working copy by applying a stash entry
  private def applyStash(stash: StashEntry, wcRoot: os.Path, dryRun: Boolean): Unit = {
    val pathMatch = """([ADUCG>])(\s+)(.+)""".r
    val relWcRoot = wcRoot.relativeTo(os.pwd)
    val relCwd    = os.pwd.relativeTo(wcRoot)
    val patchFile = (stashPath / stash.patchName).toString
    val cmdLine   = if (dryRun) 
      Seq("svn", "patch", "--dry-run", patchFile)
    else
      Seq("svn", "patch", patchFile)

    var lastStatus = ""
    // First apply the patch
    runCmd(cmdLine, Some(wcRoot)) foreach { 
      case pathMatch(st, space, pathString) =>
        val relPath = if (st == ">")
          pathString  // Not a path 
        else {
          val path    = os.RelPath(pathString)
          if (path.startsWith(relCwd)) path.relativeTo(relCwd) else relWcRoot / path
        }
        val color = st match {
          case "C" => red
          case "G" => purple
          case ">" if lastStatus == "C" => red
          case ">" if lastStatus == "G" => purple
          case _   => white
        }
        println(color(s"$st$space$relPath"))
        lastStatus = st
      case other =>
        println(other)
    }
    
    if (!dryRun) {
      // The working copy has been restored via the patch, but and files that were
      // unversioned when the stash was created will not appear as "added".  We must
      // run `svn revert` on each unversioned item so that it will once again become unversioned.
      val unversionedItems = stash.items filter (_.status == UNVERSIONED)
      if (unversionedItems.nonEmpty) {
        val unversionedDirs = unversionedItems filter (_.isDir)
        val canSkip = (item: StashItem) => unversionedDirs exists (d => item.path.startsWith(d.path) && item.path != d.path)
        val revertPaths = unversionedItems filterNot canSkip map (_.path)
        val cmdLine = Seq("svn", "revert", "--depth=infinity") ++ revertPaths
        runCmd(cmdLine, Some(wcRoot))        
      }
    
      //  Let the user know we finished successfully
      println(s"Updated working copy state - ${stash.summary}")
    }
  }
   

  // == Push Command ====================================================
  private case object Push extends StashCommand {
    override val cmdName = "push"
      
    // sv stash [push] [-u|--include-unversioned] [-d|--description=<desc>]
    private case class Options(
      unversioned: Boolean        = false,
      revertWorkingCopy: Boolean  = true,
      description: Option[String] = None)
        
    private def processCommandLine(args: Seq[String]): Options = {

      val parser = new OptionParser[Options] {
        val cmdPrefix = s"$scriptName $name [$cmdName]"
        
        banner = s"usage: $cmdPrefix [<options]"
        separator("")
        separator("Push working copy copy to the stash and revert the working copy")
        separator("Options:")

        flag("-u", "--unversioned", "Include unversioned files in the stash")
          { _.copy(unversioned = true) }
        
        reqd[String]("-m", "--message=<msg>",    "A short description of the stash")
          { (desc, options) => options.copy(description = Some(desc)) }
        
        flag("-n", "--no-revert",    "Do not revert the working copy.")
          { _.copy(revertWorkingCopy = false) }
        
        flag("-h", "--help", "Show this message")
            { _ => println(help); throw HelpException() }
            
      }

      parser.parse(args, Options())
    }
    
    
    private def createPatchFile(wcRoot: os.Path, patchName: String): Unit = {
      val cmdLine = Seq("svn", "diff", "--depth=infinity", "--ignore-properties", ".")
      val diffOut = runCmd(cmdLine, Some(wcRoot))
      val file    = (stashPath / patchName).toIO
      
      try {
        val writer = new PrintWriter(new FileWriter(file), true)
        diffOut foreach writer.println
        writer.close()
      }
      catch {
        case e: Throwable =>
          generalError(s"Error writing patch file ($file): ${e.getMessage}")
      }
    }
    
    private def getLogMessage1st(wcRoot: os.Path): String = {
      val cmdLine = Seq("svn", "log", "--xml", "--revision=HEAD", wcRoot.toString)
      val out     = runCmd(cmdLine)
      val entries = (XML.loadString(out.mkString("\n")) \ "logentry") map parseLogEntry
      
      entries.head.msg.headOption getOrElse ""
    }

    //  Runs `svn status` on the working copy root directory
    //  If we are not including unversioned items then we filter them out and build the list
    //
    //  If we are including unversioned items then it is a bit more complicated:
    //  `svn status` will include unversioned directories but will not include their contents
    //  So in this case we must add these unversioned directories to the working copy and then
    //  run `svn status` a second time.  The add operation is recursive so we only need to
    //  do it on the top level unversioned directories.
    //  At this point `svn status` will return all of the previously unversioned items as
    //  "added" so we must mark them as unversioned in our own item list.
    //  So this function will alter the working copy when unversioned items are being stashed.
    private def getStashItems(wcRoot: os.Path, unversioned: Boolean): List[StashItem] = {
      
      //  We always filter out entries with item status of "normal".  These have only property changes.
      //  which we do not care about.
      //  We also filter out entries with item status of "unversioned" unless the user choseen to
      //  include the unversioned files in the stash.
      val included    = (e: StatusEntry) =>
        e.itemStatus != NORMAL && (e.itemStatus != UNVERSIONED || unversioned)
        
      val toItem = (entry: StatusEntry) => {
        val isDir = os.isDir(wcRoot / os.RelPath(entry.path))
        StashItem(entry.path, entry.revision, entry.itemStatus, isDir)
      }
      
      // Get the status starting at the wcRoot to determine what is dirty and must be stashed
      def getWorkingCopyItems(): List[StashItem] = {
        getSvnStatus(".", Some(wcRoot)).entries.toList filter included map toItem
      }
      
      def addToWorkingCopy(paths: Seq[String]): Unit = {
        val cmdLine = Seq("svn", "add", "--depth=infinity", "--no-auto-props") ++ paths
        runCmd(cmdLine, Some(wcRoot))        
      }
      
      
      def fixupUnversionedItems(initialItems: List[StashItem]): List[StashItem] = {
        val unversionedPaths = (initialItems filter (_.status == UNVERSIONED) map (_.path))
        
        if (unversionedPaths.isEmpty)
          initialItems
        else {
          addToWorkingCopy(unversionedPaths)  // This recursively adds children of unversioned directories
          //  If there are no unversioned directories then no need to get the status again
          if (initialItems exists (item => item.isDir && item.status == UNVERSIONED)) {
            // Now we have unversioned items that have been added to the working-copy.
            // This time when we get the working copy status they will be reported as "added"
            // We need to detect them and set the status to "unversioned" for our item list.
            // These unversioned items will be those with paths that are equal to or start with
            // the `unversionedPaths` that we set earilier.
            
            def isUnversioned(item: StashItem) = item.status == ADDED &&
                                                 (unversionedPaths exists (prefix => item.path startsWith prefix))
            getWorkingCopyItems() map {
              case item if isUnversioned(item) => item.copy(status = UNVERSIONED)
              case item                        => item
            }
          }
          else
            initialItems
        }
      }
      
      
      // Start point of getStashItems()
      getWorkingCopyItems() match {
        case Nil                  => Nil
        case items if unversioned => fixupUnversionedItems(items)
        case items                => items
      }
    }
    
    
    override def run(args: Seq[String]): Unit = {
      val options = processCommandLine(args)
      val wcInfo  = getWorkingCopyInfo()  // Make sure the current directory is in a subversion working copy
      val wcRoot  = getWorkingCopyRoot().get  // All commands will be run from the top dir
      val items   = getStashItems(wcRoot, options.unversioned)
      
      if (items.isEmpty)
        successExit("No local changes to save")
      
      val (branch, revision) = getCurrentBranch(wcRoot.toString)
      val description        = options.description getOrElse getLogMessage1st(wcRoot)
      
      val patchName = createPatchName()
      if (!os.isDir(stashPath)) {
        try os.makeDir(stashPath)
        catch {
          case e: java.io.IOException =>
            generalError(s"Cannot create .sv/stash directory: ${e.getMessage}")
        }
      }
      
      createPatchFile(wcRoot, patchName)
      
      val stash = StashEntry(
        branch,
        revision,
        description,
        LocalDateTime.now,
        patchName,
        items)
      
      // Put the new entry at the head of the list and save
      saveStashEntries(stash :: loadStashEntries())
      
      if (options.revertWorkingCopy) {
        // Lastly we revert the working copy.
        // We will explicitly revert all entries to ensure that the --remove-added flag is honored.
        // For added/unversioned directories we do not need to revert any entries below them
        // as these entrie will be reverted recursively with their respective  directories.
        val addedAndUnversionedDirs = items filter (i => i.isDir && (i.status == ADDED || i.status == UNVERSIONED))
        val canSkip = (item: StashItem) => addedAndUnversionedDirs exists (d => item.path.startsWith(d.path) && item.path != d.path)
        val revertPaths = items filterNot canSkip map (_.path)
        val cmdLine = Seq("svn", "revert", "--remove-added", "--depth=infinity") ++ revertPaths
        runCmd(cmdLine, Some(wcRoot))
      }
        
      //  Let the user know we finished successfully
      println(s"Saved working copy state - ${stash.summary}")
    }
  }
  
  
  // == List Command ====================================================
  private case object List extends StashCommand {
    override val cmdName = "list"
      
    private def processCommandLine(args: Seq[String]): Unit = {

      val parser = new OptionParser[Unit] {
        val cmdPrefix = s"$scriptName $name $cmdName"
        
        banner = s"usage: $cmdPrefix [<options]"
        separator("")
        separator("Display stash entries")
        separator("Options:")

        flag("-h", "--help", "Show this message")
            { _ => println(help); throw HelpException() }
            
      }

      parser.parse(args, ())
    }
    
    override def run(args: Seq[String]): Unit = {
      processCommandLine(args) // User may have specified --help, -h
      getWorkingCopyInfo()     // Make sure the current directory is in a subversion working copy
      
      for ((stash, index) <- loadStashEntries().view.zipWithIndex) {
      
        println(s"stash-$index, - ${stash.summary}")  
      }
    }
  }
  
  // == Show Command ====================================================
  private case object Show extends StashCommand {
    override val cmdName = "show"
      
    private case class Options(
      stashIndex: Option[Int] = None,
      showDiff:   Boolean     = false
    )
      
    private def processCommandLine(args: Seq[String]): Options = {

      val parser = new OptionParser[Options] {
        val cmdPrefix = s"$scriptName $name $cmdName"
        
        addArgumentParser(stashRefParser)
        
        banner = s"usage: $cmdPrefix [<options]"
        separator("")
        separator("Show the details of a stash entry")
        separator("Options:")

        flag("-d", "--diff", "Display the patch file differences")
            { _.copy(showDiff = true)}
            
        flag("-h", "--help", "Show this message")
            { _ => println(help); throw HelpException() }
            
        arg[StashRef] { (ref, options) => options.copy(stashIndex = Some(ref.index)) }

        separator("")
        separator("If you omit the stash-ref, stash-0 is used by default")
      }

      parser.parse(args, Options())
    }
        
    override def run(args: Seq[String]): Unit = {
      val options    = processCommandLine(args)
      val wcInfo     = getWorkingCopyInfo()  // Make sure the current directory is in a subversion working copy
      val wcRoot     = getWorkingCopyRoot().get  // All commands will be run from the top dir
      val index      = options.stashIndex getOrElse 0
      val stashList  = loadStashEntries()
      val stash      = if (stashList.size >= index + 1)
        stashList(index)
      else
        generalError(s"stash-$index does not exist in the stash")
      
      // We show paths relative to the user current working directory
      val relStashPath = stashPath.relativeTo(os.pwd)
      val relWcRoot    = wcRoot.relativeTo(os.pwd)
      val relCwd       = os.pwd.relativeTo(wcRoot)
      println(s"stash     : ${stash.summary}")
      println(s"created   : ${purple(displayDateTime(stash.date))}")
      println(s"patch file: ${blue((relStashPath / stash.patchName).toString)}")
      println("-" * 70)
      for (item <- stash.items) {
        // If the item is in the current working directory then strip the directory
        // otherwise show it relative to the working copy root
        val itemPath    = os.RelPath(item.pathDisplay)
        val itemRelPath = if (itemPath.startsWith(relCwd)) itemPath.relativeTo(relCwd) else relWcRoot / itemPath
        println(s"${item.statusDisplay} ${itemRelPath.toString} ${item.revisionDisplay}")
      }
      if (options.showDiff) {
        println()
        os.read.lines.stream(stashPath / stash.patchName) foreach (printDiffLine(_))
      }
    }
  }
  
  // == Pop Command ====================================================
  private case object Pop extends StashCommand {
    override val cmdName = "pop"
      
    // sv stash apply [<stash-ref>]
    private case class Options(
      stashIndex: Option[Int] = None,
      dryRun:     Boolean     = false)
        
    private def processCommandLine(args: Seq[String]): Options = {

      val parser = new OptionParser[Options] {
        val cmdPrefix = s"$scriptName $name $cmdName"
        
        addArgumentParser(stashRefParser)
        
        banner = s"usage: $cmdPrefix [<options] [<stash-ref>]"
        separator("")
        separator("Remove a stash entry and apply it to the working copy")
        separator("Options:")

        flag("-n", "--dry-run", "Show the patch output but do not update the working copy",  "or remove the stash entry")
          { _.copy(dryRun = true) }
        
        flag("-h", "--help", "Show this message")
            { _ => println(help); throw HelpException() }
            
        arg[StashRef] { (ref, options) => options.copy(stashIndex = Some(ref.index)) }

        separator("")
        separator(s"If you omit the stash-ref, stash-0 is used by default")
      }

      parser.parse(args, Options())
    }
    
    override def run(args: Seq[String]): Unit = {
      val options    = processCommandLine(args)
      val wcInfo     = getWorkingCopyInfo()  // Make sure the current directory is in a subversion working copy
      val wcRoot     = getWorkingCopyRoot().get  // All commands will be run from the top dir
      val index      = options.stashIndex getOrElse 0
      val stashList  = loadStashEntries()
      val stash      = if (stashList.size >= index + 1)
        stashList(index)
      else
        generalError(s"stash-$index does not exist in the stash")
      
      applyStash(stash, wcRoot, options.dryRun)

      if (!options.dryRun) {
        // remove the patch file
        // Remove the stash entry and save
        os.remove(stashPath / stash.patchName)
        saveStashEntries(stashList filterNot (_ == stash))
        println(s"Dropped stash -  ${stash.summary}")        
      }
    }
  }
  
  // == Apply Command ====================================================
  private case object Apply extends StashCommand {
    override val cmdName = "apply"
      
    // sv stash apply [<stash-ref>]
    private case class Options(
      stashIndex: Option[Int] = None,
      dryRun:     Boolean     = false)
        
    private def processCommandLine(args: Seq[String]): Options = {

      val parser = new OptionParser[Options] {
        val cmdPrefix = s"$scriptName $name $cmdName"
        
        addArgumentParser(stashRefParser)
        
        banner = s"usage: $cmdPrefix [<options] [<stash-ref>]"
        separator("")
        separator("Apply a stash entry to the working copy")
        separator("Options:")

        flag("-n", "--dry-run", "Show the patch output but do not update the working copy")
          { _.copy(dryRun = true) }
        
        flag("-h", "--help", "Show this message")
            { _ => println(help); throw HelpException() }
            
        arg[StashRef] { (ref, options) => options.copy(stashIndex = Some(ref.index)) }

        separator("")
        separator(s"If you omit the stash-ref, stash-0 is used by default")
      }

      parser.parse(args, Options())
    }
    
    override def run(args: Seq[String]): Unit = {
      val options    = processCommandLine(args)
      val wcInfo     = getWorkingCopyInfo()  // Make sure the current directory is in a subversion working copy
      val wcRoot     = getWorkingCopyRoot().get  // All commands will be run from the top dir
      val index      = options.stashIndex getOrElse 0
      val stashList  = loadStashEntries()
      val stash      = if (stashList.size >= index + 1)
        stashList(index)
      else
        generalError(s"stash-$index does not exist in the stash")
      
      applyStash(stash, wcRoot, options.dryRun)
    }
  }
  
  
  // == Drop Command ====================================================
  private case object Drop extends StashCommand {
    override val cmdName = "drop"
      
    // sv stash apply [<stash-ref>]
    private case class Options(stashIndex: Option[Int] = None)
        
    private def processCommandLine(args: Seq[String]): Options = {

      val parser = new OptionParser[Options] {
        val cmdPrefix = s"$scriptName $name $cmdName"
        
        addArgumentParser(stashRefParser)
        
        banner = s"usage: $cmdPrefix [<options] [<stash-ref>]"
        separator("")
        separator("Remove a stash entry")
        separator("Options:")

        flag("-h", "--help", "Show this message")
            { _ => println(help); throw HelpException() }
            
        arg[StashRef] { (ref, options) => options.copy(stashIndex = Some(ref.index)) }

        separator("")
        separator(s"If you omit the stash-ref, stash-0 is used by default")
      }

      parser.parse(args, Options())
    }
    
    override def run(args: Seq[String]): Unit = {
      val options    = processCommandLine(args)
      val wcInfo     = getWorkingCopyInfo()  // Make sure the current directory is in a subversion working copy
      val wcRoot     = getWorkingCopyRoot().get  // All commands will be run from the top dir
      val index      = options.stashIndex getOrElse 0
      val stashList  = loadStashEntries()
      val stash      = if (stashList.size >= index + 1)
        stashList(index)
      else
        generalError(s"stash-$index does not exist in the stash")
      
      // remove the patch file
      // Remove the stash entry and save
      os.remove(stashPath / stash.patchName)
      saveStashEntries(stashList filterNot (_ == stash))
      println(s"Droped ${stash.summary}")
    }
  }
  
  // == Clear Command ====================================================
  private case object Clear extends StashCommand {
    override val cmdName = "clear"
      
    private def processCommandLine(args: Seq[String]): Unit = {

      val parser = new OptionParser[Unit] {
        val cmdPrefix = s"$scriptName $name $cmdName"
        
        addArgumentParser(stashRefParser)
        
        banner = s"usage: $cmdPrefix [<options]"
        separator("")
        separator(s"Remove all stash entries")
        separator("Options:")

        flag("-h", "--help", "Show this message")
            { _ => println(help); throw HelpException() }
      }

      parser.parse(args, ())
    }
    
    override def run(args: Seq[String]): Unit = {
      processCommandLine(args) // To handle --help, -h
      val wcInfo     = getWorkingCopyInfo()  // Make sure the current directory is in a subversion working copy
      val stashList  = loadStashEntries()

      // remove each patch file
      for (stash <- loadStashEntries())
        os.remove(stashPath / stash.patchName)
      // Remove the stash entries file
      os.remove(stashEntriesFile)
    }
  }
  
     
  private def showHelp(): Nothing = {
    val sv = scriptName
    val help = s"""|Stash local changes to your working copy so that you can work on something else
                   |and merge the changes back into your working copy at a later time.
                   |
                   |Available stash commands:
                   |$sv $name push    Push working copy copy to the stash and revert the working copy
                   |$sv $name pop     Remove a stash entry and apply it to the working copy
                   |$sv $name list    Display stash entries
                   |$sv $name show    Show the details of a stash entry
                   |$sv $name apply   Apply a stash entry to the working copy
                   |$sv $name drop    Remove a stash entry
                   |$sv $name clear   Remove all stash entries
                   |
                   |Type '$sv $name <command> --help' for details on a specific command""".stripMargin
      println(help)
      throw HelpException()
  }

  private val stashCommands = Push::List::Pop::Apply::Drop::Show::Clear::Nil

  private def matchCommand(cmdName: String, cmdList: List[StashCommand]): List[StashCommand] = {
    if ("""^[a-zA-Z][-a-zA-Z0-9_]*""".r matches cmdName)
      cmdList filter (_.cmdName startsWith cmdName)
    else
      Nil
  }

  private def getStashCommand(cmdName: String): StashCommand = {
    matchCommand(cmdName, stashCommands) match {
      case Nil        => showHelp()
      case cmd :: Nil => cmd
      case names      => generalError(s"$scriptName $name command '$cmdName' is ambiguous.  (${names.map(_.cmdName).mkString(", ")})")
    }
  }

  // Main entry point to bisect commnad
  override def run(args: Seq[String]): Unit = {

    if (args.nonEmpty && (args.head == "help" || args.head == "--help" || args.head == "-h"))
      showHelp();
    else if (args.isEmpty || (args.head.startsWith("-") && args.head != "--"))
      Push.run(args)
    else
      getStashCommand(args.head).run(args.tail)
  }
}
