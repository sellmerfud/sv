

package svutil

import java.io.{ File, FileWriter }
import scala.xml._
import scala.jdk.CollectionConverters._
import scala.xml._
import scala.util.{ Try, Success, Failure }
import com.typesafe.config._
import org.sellmerfud.optparse._
import svutil.exceptions._
import Exec.{ runCmd, ExecError }
import Color._
import Utilities._

object Bisect extends Command {
  
  override val name = "bisect"
  override val description = "Use binary search to find the commit that introduced a bug"

  private val bisectDataFile = new File("./.svn/tmp/sv_bisect_data.json")
  private val bisectLogFile  = new File("./.svn/tmp/sv_bisect_log")
  
  private case class BisectData(
    originalRev: String,                      // original working copy revision. Used to reset working copy
    maxRev:      Option[String] = None,       // maximum revision number in our list that is still being checked
    minRev:      Option[String] = None,       // minimum revision number in our list that is still being checked
    skipRevs:    Set[String]    = Set.empty,  // revisions that have been explicitly skipped
    termBad:     Option[String] = None,
    termGood:    Option[String] = None,
  ) {
    val termBadName  = termBad  getOrElse Bad.cmdName
    val termGoodName = termGood getOrElse Good.cmdName
    
    val ready = maxRev.nonEmpty && minRev.nonEmpty
  }
  
  implicit class ConfigWrapper(cfg: Config) {
    def optString(path: String): Option[String] = if (cfg.getIsNull(path)) None else Some(cfg.getString(path))
  }
  
  //  Convert our BisectData to a Config value that can be saved to disk.
  private def toConfigObject(data: BisectData): ConfigObject = {
    ConfigValueFactory.fromMap(Map(
      "originalRev" -> data.originalRev,
      "maxRev"      -> data.maxRev.getOrElse(null),
      "minRev"      -> data.minRev.getOrElse(null),
      "skipRevs"    -> ConfigValueFactory.fromIterable(data.skipRevs.asJava),
      "termBad"     -> data.termBad.getOrElse(null),
      "termGood"    -> data.termGood.getOrElse(null),
    ).asJava)
  }
  
  private def fromConfig(cfg: Config) = BisectData(
    cfg.getString("originalRev"),
    cfg.optString("maxRev"),
    cfg.optString("minRev"),
    cfg.getStringList("skipRevs").asScala.toSet,
    cfg.optString("termBad"),
    cfg.optString("termGood"),
  )

  //  Verifiy that the currrent working directory is an SVN working copy
  //  and that we are at the top of that working copy.
  private def getWorkingCopyInfo(): SvnInfo = {
    try getSvnInfo(".")
    catch {
      case ExecError(_, _) =>
        generalError(s"$scriptName $name must be run from the top of a subversion working copy directory tree.")
        
      case e: Throwable =>
        generalError("Error verifying working copy\n" + (Option(e.getMessage) getOrElse e.getClass.getName))
    }
  }
    
  private def loadBisectData(): Option[BisectData] = {
    if (bisectDataFile.isFile && bisectDataFile.canRead) {
      try {
        val config = ConfigFactory.parseFile(bisectDataFile, ConfigParseOptions.defaults.setSyntax(ConfigSyntax.JSON))
        Some(fromConfig(config))
      }
      catch {
        case e: Throwable => 
          generalError(s"Error reading bisect data ($bisectDataFile): ${e.getMessage}")
      }
    }
    else if (bisectDataFile.isFile) {
      generalError(s"Unable to read bisect data ($bisectDataFile): Check file permissions")
    }
    else
      None
  }
  
  def saveBisectData(data: BisectData): Unit = {
    try {
      val opts   = ConfigRenderOptions.concise.setJson(true).setFormatted(true)
      val writer = new FileWriter(bisectDataFile)
      writer.write(toConfigObject(data).toConfig.root.render(opts))
      writer.close
    } 
    catch {
      case e: Throwable =>
        generalError(s"Error saving bisect data ($bisectDataFile): ${e.getMessage}")
    }
  }  
  
  
  //  Load and return the bisect data or throw a general error
  //  if the data file is missing.
  private def getBisectData(): BisectData = {
    loadBisectData() getOrElse {
      generalError(s"You must first start the bisect process with '$scriptName $name ${Start.cmdName}")
    }
  }
  
  private def appendToBisectLog(msg: String): Unit = {
    // To be done.
  }
  
  
  private def logBisectRevision(revision: String, term: String): Unit = {
    val msg1st = get1stLogMessage(revision) getOrElse ""
    appendToBisectLog(s"# $term: [$revision] $msg1st")
  }
  
  private def logBisectCommand(cmdLine: Seq[String]): Unit = {
    appendToBisectLog(cmdLine.mkString(" "))
  }
  
  //  Get the list of log revisions for the working copy
  //  The most recent revision will be at the head of the list.
  private def getLogRevisions(): Seq[String] = {
    val logXML = XML.loadString(runCmd(Seq("svn", "log", "--xml", "--quiet")).mkString("\n"))
    val entries = (logXML \ "logentry") map parseLogEntry
    
    entries map (_.revision)
  }
  
  private def getWaitingStatus(data: BisectData): Option[String] = {
    val bad  = data.termBadName
    val good = data.termGoodName
    (data.maxRev, data.minRev) match {
      case (None, None)    => Some(s"status: waiting for both $good and $bad revisions")
      case (Some(_), None) => Some(s"status: waiting for a $good revision")
      case (None, Some(_)) => Some(s"status: waiting for a $bad revision")
      case _               => None
    }
  }
  
  private def getLogEntry(revision: String, withPaths: Boolean = false): Option[LogEntry] = {
    val verbose  = if (withPaths) Seq("--verbose") else Seq.empty
    val cmdLine  = Seq("svn", "log", "--xml", s"--revision=$revision", "--limit=1", ".") ++ verbose
    val logXML   = XML.loadString(runCmd(cmdLine).mkString("\n"))
    (logXML \ "logentry").headOption map parseLogEntry
  }
  
  private def get1stLogMessage(revision: String): Option[String] = {
    getLogEntry(revision) flatMap (_.msg.headOption)
  }
  
  private def performBisect(data: BisectData): Unit = {
    if (!data.ready)
      throw new IllegalStateException("performBisect() called when data not ready")

    val maxRev = data.maxRev.get
    val minRev = data.minRev.get
    val revisions = getLogRevisions()
    val maxRevIndex = revisions.indexOf(maxRev)
    val minRevIndex = revisions.indexOf(minRev)
    
    if (!(revisions contains maxRev))
      throw new IllegalStateException(s"maxRev (${data.maxRev.get}) not in revision list")
    if (!(revisions contains minRev))
      throw new IllegalStateException(s"minRev (${data.minRev.get}) not in revision list")
    
    val remainingRevs = revisions.dropWhile(_ != maxRev).tail.takeWhile(_ != minRev)
    val candidateRevs = remainingRevs filterNot data.skipRevs.contains
    
    if (candidateRevs.isEmpty) {
      if (remainingRevs.nonEmpty) {
          println("There are only skipped revisions left to test.")
          println(s"The first '${data.termBadName}' commit could be any of:")
          for (rev <- maxRev +: remainingRevs)
            println(yellow(rev))
          println("We cannot bisect more!")
      }
      else {
        println(s"There first '${data.termBadName}' commit is: ${yellow(maxRev)}")
        getLogEntry(maxRev, withPaths = true) foreach { log => showCommit(log) }
      }
    }
    else {
      import scala.math.log10
      val num     = candidateRevs.size
      val steps   = (log10(num) / log10(2)).toInt match {
        case 1 => "1 step"
        case n => s"$n steps"
      }
      val nextRev = candidateRevs(candidateRevs.size / 2)
      
      println(s"Bisecting: $num revisions left to test after this (roughly $steps)")
      updateWorkingCopy(nextRev)
    }
  }
  
  
  private def updateWorkingCopy(revision: String): Unit = {
    if (revision != getWorkingCopyInfo().commitRev) {
      val msg1st  = get1stLogMessage(revision) getOrElse ""
      println(s"Updating working copy: [${yellow(revision)}] $msg1st")      
      runCmd(Seq("svn", "update", s"--revision=$revision"))
    }
  }
    

  //  We try to log the revision for the current working copy directory.
  //  If the revision does not exist in the repo we will get an exception
  //  If the revision does exist in the repo but is not on the trunk/branch for
  //  the working copy then we simply get an empty list.
  //  For HEAD, BASE, COMMITTED, PREV we have to specify a range and limit
  //  in order for subversion to return the log entry.
  private def resolveWorkingCopyRevision(rev: String): Option[String] = {
    val revArgs = if (isInteger(rev))
      Seq(s"--revision=$rev")
    else
      Seq(s"--revision=$rev:0", "--limit=1")
    val cmdLine = Seq("svn", "log", "--xml", "--quiet") ++ revArgs
    val logNodes = Try(XML.loadString(runCmd(cmdLine).mkString("\n")) \ "logentry")
    logNodes match {
      case Success(nodes) if nodes.size == 0 => None
      case Success(nodes)                    => Some(parseLogEntry(nodes.head).revision)
      case Failure(_)                        => None
    }
  }

  private def isInteger(str: String) = str forall (_.isDigit)
  
  private case class RevisionArg(rev: String)
  
  private val revisionArgParser = (arg: String) => {
    val validRev = """^(?:\d+|HEAD|BASE|PREV|COMMITTED)$""".r
    if (!(validRev matches arg))
      throw new InvalidArgumentException(s" <revision> must be an integer or one of HEAD, BASE, PREV, COMMITTED")
    
    resolveWorkingCopyRevision(arg) match {
      case Some(rev) => RevisionArg(rev)
      case None      => throw new InvalidArgumentException(s" this revision is not part of the working copy history")
    }
  }

  private case class TermName(name: String)
  
  private val termArgParser = (arg: String) => {
    val validTerm = """^[A-Za-z][-_A-Za-z]*$""".r
    if (validTerm matches arg) {
      // The term must not match one of the standard bisect commmand names
      if (bisectCommands exists (_.cmdName == arg))
        throw new InvalidArgumentException(s" <term> cannot mask a built in bisect command name")
      else
      TermName(arg)
    }
    else
      throw new InvalidArgumentException(s" <term> must start with a letter and contain only letters, '-', or '_'")
  }
  
  private sealed trait BisectCommand {
    val cmdName: String
    def run(args: Seq[String]): Unit
  }
  
  // == Start Command ====================================================
  private case object Start extends BisectCommand {
    override val cmdName = "start"
      
    private case class Options(
      bad:      Option[String] = None,
      good:     Option[String] = None,
      termBad:  Option[String] = None,
      termGood: Option[String] = None)
        
    private def processCommandLine(args: Seq[String]): Options = {

      val parser = new OptionParser[Options] {
        val scriptPrefix = s"$scriptName $name"
        val cmdPrefix = s"$scriptPrefix $cmdName"
        
        addArgumentParser(revisionArgParser)
        addArgumentParser(termArgParser)
        
        banner = s"usage: $cmdPrefix [<options]"

        reqd[RevisionArg]("", "--bad=<revision>",    "Specify the earliest revision that contains the bug")
          { (revision, options) => options.copy(bad = Some(revision.rev)) }
        reqd[RevisionArg]("", "--good=<revision>",   "Specify the latest revision that does not contain the bug")
        { (revision, options) => options.copy(good = Some(revision.rev)) }
        reqd[TermName]("", "--term-bad=<term>",   "Specify an alternate name name for the 'bad' subcommand")
        { (term, options) => options.copy(termBad = Some(term.name)) }
        reqd[TermName]("", "--term-good=<term>",  "Specify an alternate name name for the 'good' subcommand")
        { (term, options) => options.copy(termGood = Some(term.name)) }
        
        flag("-h", "--help", "Show this message")
            { _ => println(help); throw HelpException() }
            
        separator("")
        separator(s"If you omit a bad revision, you must do so later with '$scriptPrefix bad <rev>' ")
        separator(s"If you omit a good revision, you must do so later with '$scriptPrefix good <rev>' ")
      }

      parser.parse(args, Options())
    }


    
    override def run(args: Seq[String]): Unit = {
      val options = processCommandLine(args)
      
      loadBisectData() match {
        case Some(data) =>
          System.err.println(s"$name already in progress")
          getWaitingStatus(data) foreach (s => System.err.println(s))
          System.err.println(s"\nType '$scriptName $name ${Reset.cmdName}' to reset your working copy")
          generalError(s"Type '$scriptName $name ${Reset.cmdName} --help' for more information")
          
        case None =>
          val badRev  = options.bad map (_.toInt)
          val goodRev = options.good map (_.toInt)
          
          (badRev, goodRev) match {
            case (Some(bad), Some(good)) if bad == good =>
              generalError("The 'bad' and 'good' revisions cannot be the same")
              
            case (Some(bad), Some(good)) if bad < good =>
              generalError("The 'good' revision must be an ancestor of the 'bad' revision")
              
            case _ =>
          }
          
          // save the bisect data in order to start a new session.
          val data = BisectData(
            originalRev = getWorkingCopyInfo().commitRev,
            maxRev      = options.bad,
            minRev      = options.good,
            termBad     = options.termBad,
            termGood    = options.termGood)
          saveBisectData(data)

          data.maxRev foreach (logBisectRevision(_, data.termBadName))
          data.minRev foreach (logBisectRevision(_, data.termGoodName))
          getWaitingStatus(data) foreach { status =>
            appendToBisectLog(status)
            println(status)
          }          
          
          if (data.ready)
            performBisect(data)
          
          logBisectCommand(Seq(scriptName, name, cmdName) ++ args)
      }
    }
  }
  
  // == Bad Command ==++==================================================
  private case object Bad extends BisectCommand {
    override val cmdName = "bad"
    
    private case class Options(revision: Option[String] = None)
    
    private def processCommandLine(args: Seq[String], cmdTerm: String): Options = {

      val parser = new OptionParser[Options] {
        val cmdPrefix = s"$scriptName $name $cmdTerm"
        
        addArgumentParser(revisionArgParser)
        
        banner = s"usage: $cmdPrefix [<revision>]"

        flag("-h", "--help", "Show this message")
            { _ => println(help); throw HelpException() }
            
        arg[RevisionArg] { (revision, options) => options.copy(revision = Some(revision.rev)) }  
            
        separator("")
        separator(s"Specify the earliest $cmdTerm revision")
        separator(s"The current working copy revision is used by default")
      }

      parser.parse(args, Options())
    }
    
    override def run(args: Seq[String]): Unit = {
      val data     = getBisectData()
      val options  = processCommandLine(args, data.termBadName)
      val revision = options.revision getOrElse getWorkingCopyInfo().commitRev
      val revList  = getLogRevisions()
      
      // updateWorkingCopy(revision: String)
    }
  }
  
  // == Good Command ==+==================================================
  private case object Good extends BisectCommand {
    override val cmdName = "good" 
    
    override def run(args: Seq[String]): Unit = {
      println("not yet implmented")
    }
  }
  
  // == Terms Command ====================================================
  private case object Terms extends BisectCommand {
    override val cmdName = "terms"
    
    override def run(args: Seq[String]): Unit = {
      println("not yet implmented")
    }
  }
  
  // == Skip Command ====================================================+
  private case object Skip extends BisectCommand {
    override val cmdName = "skip"
    
    override def run(args: Seq[String]): Unit = {
      println("not yet implmented")
    }
  }
  
  // == Unskip Command ====================================================+
  private case object Unskip extends BisectCommand {
    override val cmdName = "unskip"
    
    override def run(args: Seq[String]): Unit = {
      println("not yet implmented")
    }
  }
  
  // == Run Command ======================================================
  private case object Run extends BisectCommand {
    override val cmdName = "run"

    override def run(args: Seq[String]): Unit = {
      println("not yet implmented")
    }
  }
    
  // == Reset Command ====================================================
  private case object Reset extends BisectCommand {
    override val cmdName = "reset"

    private case class Options(update: Boolean = true, revision: Option[String] = None)
    
    private def processCommandLine(args: Seq[String]): Options = {

      val parser = new OptionParser[Options] {
        val cmdPrefix = s"$scriptName $name $cmdName"
        
        addArgumentParser(revisionArgParser)
        
        banner = s"usage: $cmdPrefix [<options>] [<revision>]"

        bool("", "--update",    "Update the working.  (Default is yes)")
          { (value, options) => options.copy(update = value) }
          
        flag("-h", "--help", "Show this message")
            { _ => println(help); throw HelpException() }
            
        arg[RevisionArg] { (revision, options) => options.copy(revision = Some(revision.rev)) }  
            
        separator("")
        separator(s"The default is to update your working copy to its original revision before the bisect")
        separator(s"If a <revision> is specified, then the working copy will be updated to it insstead")
        separator(s"You can also elect to keep you working copy as it is with --no-update")
      }

      parser.parse(args, Options())
    }

    override def run(args: Seq[String]): Unit = {
      val options = processCommandLine(args)
      val data    = getBisectData()
      
      if (options.update) {
        val updateRev = options.revision getOrElse data.originalRev
        updateWorkingCopy(updateRev)
      }
        
      //  Remove the data file, this will clear the bisect session
      bisectDataFile.delete()
    }
  }
  
  // == Log Command ======================================================
  private case object Log extends BisectCommand {
    override val cmdName = "log"

    override def run(args: Seq[String]): Unit = {
      println("not yet implmented")
    }
  }
    
  // == Replay Command ===================================================
  private case object Replay extends BisectCommand {
    override val cmdName = "replay"

    override def run(args: Seq[String]): Unit = {
      println("not yet implmented")
    }
  }
    
    
  private val bisectCommands = Start::Bad::Good::Terms::Skip::Unskip::Run::Log::Replay::Reset::Nil
  
  private def matchCommand(cmdName: String): List[BisectCommand] = {
    if ("""^[a-zA-Z][-a-zA-Z0-9_]*""".r matches cmdName)
      bisectCommands filter (_.cmdName startsWith cmdName)
    else
      Nil
  }
  
  def showHelp(): Nothing = {
    val sv = scriptName
    val help = s"""|Available bisect commands:
                   |$sv $name start       Start a bisect session in the current subversion
                   |                      working copy directory
                   |$sv $name bad         Mark a revision as bad  (It contains the bug)
                   |$sv $name good        Mark a revision as good  (It does not contain the bug)
                   |$sv $name terms       Show the currently defined terms for good/bad
                   |$sv $name skip        Skip a revision.  It will no longer be considered
                   |$sv $name unskip      Reinstate a previously skipped revision
                   |$sv $name run         Automate the bisect session by running a script
                   |                      for each tested revision 
                   |$sv $name log         Show the bisect log
                   |$sv $name replay      Replay the bisect session from a log file
                   |$sv $name reset       Clean up after a bisect session returning the working
                   |                      copy to its original revision
                   |
                   |Type '$sv $name <command> --help' for details on a specific command""".stripMargin
      println(help)
      throw HelpException()
  }
  
  private def getBisectCommand(cmdName: String, termBad: Option[String], termGood: Option[String]): BisectCommand = {

    val cmdList = bisectCommands ::: termBad.toList ::: termGood.toList
    
    matchCommand(cmdName) match {
      case Nil                                         => showHelp()
      case command :: Nil if Some(command) == termBad  => Bad
      case command :: Nil if Some(command) == termGood => Good
      case command :: Nil                              => command
      case commands =>
        generalError(s"$scriptName $name command '$cmdName' is ambiguous.  (${(commands map (_.cmdName)).mkString(", ")})")
    }      
  }

  // Main entry point to bisect commnad
  override def run(args: Seq[String]): Unit = {

    if (args.isEmpty || args.head == "help" || args.head == "--help")
      showHelp();
    else {
      val wcInfo  = getWorkingCopyInfo()
      val optData = loadBisectData()
      val command = getBisectCommand(args.head, optData flatMap (_.termBad), optData flatMap (_.termGood))
      command.run(args.tail)
    }
  } 
}