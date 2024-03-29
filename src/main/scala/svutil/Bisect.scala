

package svutil

import java.io.{ File, FileWriter, PrintWriter,  FileReader, BufferedReader }
import java.time._
import scala.xml._
import scala.xml._
import scala.util.{ Try, Success, Failure }
import upickle.default.{ read, writeToOutputStream, ReadWriter => RW, macroRW, readwriter }
import org.sellmerfud.optparse._
import Color._
import Utilities._
import svn.model.{ LogEntry }
object Bisect extends Command {
  
  override val name = "bisect"
  override val description = "Use binary search to find the commit that introduced a bug"

  // Ordering for a sequence of revisions
  // We sort them from High (most recent) to Low(least recent)
  val RevisionOrdering: Ordering[String] = Ordering.by { revision => -revision.toLong }

  private case class BisectData(
    localPath:   String,
    originalRev: String,                        // original working copy revision. Used to reset working copy
    headRev:     Option[String]   = None,       // Highest revision in working copy history
    firstRev:    Option[String]   = None,       // Lowest revision in working copy history
    maxRev:      Option[String]   = None,       // maximum revision number in our list that is still being checked
    minRev:      Option[String]   = None,       // minimum revision number in our list that is still being checked
    skipped:     Set[String]      = Set.empty,  // revisions that have been explicitly skipped
    termBad:     Option[String]   = None,
    termGood:    Option[String]   = None,
  ) {
    val termBadName  = termBad  getOrElse Bad.cmdName
    val termGoodName = termGood getOrElse Good.cmdName
    
    val isReady = maxRev.nonEmpty && minRev.nonEmpty
  }
  
  
  private object BisectData {
    implicit val rw: RW[BisectData] = macroRW  
  }
  
      
  private def bisectDataFile = getDataDirectory() / "bisect_data.json"
  private def bisectLogFile  = getDataDirectory() / "bisect_log"
    
  private def loadBisectData(): Option[BisectData] = {
    if (os.isFile(bisectDataFile)) {
      try {
        val data = read[BisectData](bisectDataFile.toIO)
        if (os.Path(data.localPath) != os.pwd)
          generalError(s"$scriptName $name must be run from the same directory where the bisect session was started: ${data.localPath}")
        Some(data)
      }
      catch {
        case e: Throwable => 
          generalError(s"Error reading bisect data ($bisectDataFile): ${e.getMessage}")
      }
    }
    else
      None
  }
  
  def saveBisectData(data: BisectData): Unit = {
    try {
      val ostream = os.write.over.outputStream(bisectDataFile)
      try writeToOutputStream(data, ostream, indent = 2)
      finally ostream.close()
    }
    catch {
      case e: Throwable =>
        generalError(s"Error saving bisect data entries ($bisectDataFile): ${e.getMessage}")
    }
  }  
    
  //  Load and return the bisect data or throw a general error
  //  if the data file is missing.
  private def getBisectData(): BisectData = {
    loadBisectData() getOrElse {
      generalError(s"You must first start a bisect session with '$scriptName $name ${Start.cmdName}'")
    }
  }
  
  private def appendToBisectLog(msg: String): Unit = {
    try os.write.append(bisectLogFile, msg + "\n")
      catch {
      case e: Throwable =>
        generalError(s"Error appending to bisect log ($bisectLogFile): ${e.getMessage}")
    }
  }
  
  private def displayBisectLog(): Unit = {
    try os.read.lines.stream(bisectLogFile) foreach (println(_))
    catch {
      case e: Throwable =>
        generalError(s"Error reading bisect log ($bisectLogFile): ${e.getMessage}")
    }
  }
    
  private def get1stLogMessage(revision: String): String = {
    svn.log(revisions = Seq(revision)).headOption map (_.msg1st) getOrElse ""
  }
  
  private def logBisectRevision(revision: String, term: String): Unit = {
    appendToBisectLog(s"# $term: [$revision] ${get1stLogMessage(revision)}")
  }
  
  // The cmdLine should start with the biscect sub command
  private def logBisectCommand(cmdLine: Seq[String]): Unit = {
    appendToBisectLog((scriptPath +: name +: cmdLine).mkString(" "))
  }
  
  // The cmdLine should start with the biscect sub command
  private def displayBisectCommand(cmdLine: Seq[String]): Unit = {
    println((scriptName +: name +: cmdLine).mkString(" "))
  }

  //  Return the lowest and hightest revision that exists in the working copy history
  private def getWorkingCopyBounds(): (String, String) = {
    (svn.log(revisions = Seq(s"HEAD:0"), limit = Some(1)).head.revision,
     svn.log(revisions = Seq(s"0:HEAD"), limit = Some(1)).head.revision)
  }
  
  //  Get the list of revisions in the working copy history between rev1 and rev2 (inclusive)
  //  the requested range
  private def getExtantRevisions(rev1: String, rev2: String): Seq[String] = {
    println(s"Fetching history from ${yellow(rev1)} to ${yellow(rev2)}")
    svn.log(revisions = Seq(s"$rev1:$rev2")).map { _.revision }
  }
  
  private def getWaitingStatus(data: BisectData): Option[String] = {
    val bad  = data.termBadName
    val good = data.termGoodName
    (data.maxRev, data.minRev) match {
      case (None, None)    => Some(s"status: waiting for both '$good' and '$bad' revisions")
      case (Some(_), None) => Some(s"status: waiting for a '$good' revision")
      case (None, Some(_)) => Some(s"status: waiting for a '$bad' revision")
      case _               => None
    }
  }
  
  private def getLogEntry(revision: String, withPaths: Boolean = false): Option[LogEntry] = {
    svn.log(
      paths        = Seq("."),
      revisions    = Seq(revision),
      limit        = Some(1),
      includePaths = withPaths,
    ).headOption
  }
  
  // Return true if the bisect is complete
  private def performBisect(data: BisectData): Boolean = {
    if (!data.isReady)
      throw new IllegalStateException("performBisect() called when data not ready")

    val maxRev = data.maxRev.get
    val minRev = data.minRev.get
    val candidateRevs  = getExtantRevisions(maxRev, minRev).drop(1).dropRight(1)
    val nonSkippedRevs = candidateRevs filterNot (r => data.skipped(r))
    
    if (nonSkippedRevs.isEmpty) {
      if (candidateRevs.nonEmpty) {
          println("\nThere are only skipped revisions left to test.")
          println(s"The first '${data.termBadName}' commit could be any of:")
          for (rev <- (maxRev +: candidateRevs))
            s"${println(yellow(rev))} ${get1stLogMessage(rev)}"
          println("We cannot bisect more!")
          true
      }
      else {
        println(s"\nThe first '${data.termBadName}' commit is: ${yellow(maxRev)}")
        getLogEntry(maxRev, withPaths = true) foreach { log => showCommit(log) }
        true
      }
    }
    else {
      import scala.math.log10
      val num     = nonSkippedRevs.size
      val steps   = (log10(num) / log10(2)).toInt match {
        case 1 => "1 step"
        case n => s"$n steps"
      }
      val nextRev = nonSkippedRevs(nonSkippedRevs.size / 2)
      
      println(s"Bisecting: $num revisions left to test after this (roughly $steps)")
      updateWorkingCopy(nextRev)
      false
    }
  }
    
  private def updateWorkingCopy(revision: String): Unit = {
    val msg1st = get1stLogMessage(revision)

    println(s"Updating working copy: [${yellow(revision)}] $msg1st")
    svn.update(revision)
  }
    

  //  We try to log the revision for the current working copy directory.
  //  If the revision does not exist in the repo we will get an exception
  //  If the revision does exist in the repo but is not in the working copy
  //  then we either get an emtpy list or we get an entry with a revision
  //  that does not match (it is the last revision where a copy was made)
  //
  //  For HEAD, BASE, COMMITTED, PREV we have to specify a range and limit
  //  in order for subversion to return the log entry.
  private def resolveWorkingCopyRevision(rev: String): Option[Long] = {
    if (rev.isNumber) {
      Try(svn.log(revisions = Seq(rev), includeMessage = false)) match {
        case Success(e +: _) if e.revision == rev => Some(rev.toLong)
        case _                                    => None
      }
    }
    else {
      Try(svn.log(revisions = Seq(s"$rev:0"), limit = Some(1), includeMessage = false)) match {
        case Success(e +: _) => Some(e.revision.toLong)
        case _               => None
      }
    }
  }

  
  // Argument Types with associated argument parsers
  // ========================================================
  private case class RevisionArg(rev: String)     // Must be a valid revison in the working copy history
    
  private val revisionArgParser = (arg: String) => {
    val validRev = """^(?:\d+|HEAD|BASE|PREV|COMMITTED)$""".r
    if (!(validRev matches arg))
      throw new InvalidArgumentException(s" <revision> must be an integer or one of HEAD, BASE, PREV, COMMITTED")
    
    resolveWorkingCopyRevision(arg) match {
      case Some(rev) => RevisionArg(rev.toString)
      case None      => throw new InvalidArgumentException(s" this revision is not part of the working copy history")
    }
  }

  private case class RevisionRangeArg(low: Long, high: Long)
  
  private val revisionRangeArgParser = (arg: String) => {
    val revPart = """\d+|HEAD|BASE|PREV|COMMITTED"""
    val validRange = s"""^($revPart)(?::($revPart))?$$""".r
    
    arg match {
      case validRange(rev1, null) =>
        resolveWorkingCopyRevision(rev1) map (r => RevisionRangeArg(r, r)) getOrElse {
          throw new InvalidArgumentException(s" is not a valid <revision>")
        }

      case validRange(rev1, rev2) =>
        // Alwasy get range in order from hightest revision to lowest revision.
        (resolveWorkingCopyRevision(rev1), resolveWorkingCopyRevision(rev2)) match {
          case (Some(r1), Some(r2)) if r1 <= r2 => RevisionRangeArg(r1, r2)
          case (Some(r1), Some(r2)) => RevisionRangeArg(r2, r1)  //  Revs reversed (low to high)
            
          case _ =>
            throw new InvalidArgumentException(s" is not a subset of the working copy history")
        }
        
      case _  =>
        throw new InvalidArgumentException(s" is not a valid <revision> or <revision>:<revision>")
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
    val description: String
    def run(args: Seq[String]): Unit
  }


  // == Help Command ====================================================
    private case object Help extends BisectCommand {
    override val cmdName = "help"
    override val description = "Display help information"
    override def run(args: Seq[String]): Unit = {
      val getCmd = (name: String)  => getBisectCommand(name, None, None)
        (args.headOption map getCmd) match {
        case Some(cmd) => cmd.run(Seq("--help"))
        case _ => showHelp()
      }
    }
  }

  // == Start Command ====================================================
  private case object Start extends BisectCommand {
    override val cmdName = "start"
    override val description = "Start a bisect session in the working copy"
      
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
        separator("")
        separator(description)
        separator("Options:")        

        reqd[RevisionArg]("", "--bad=<revision>",    "The earliest revision that contains the bug")
          { (revision, options) => options.copy(bad = Some(revision.rev)) }
        reqd[RevisionArg]("", "--good=<revision>",   "The latest revision that does not contain the bug")
        { (revision, options) => options.copy(good = Some(revision.rev)) }
        reqd[TermName]("", "--term-bad=<term>",   "An alternate name for the 'bad' subcommand")
        { (term, options) => options.copy(termBad = Some(term.name)) }
        reqd[TermName]("", "--term-good=<term>",  "An alternate name for the 'good' subcommand")
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
      val wcInfo  = svn.workingCopyInfo
      
      loadBisectData() match {
        case Some(data) =>
          System.err.println(s"$name already in progress")
          getWaitingStatus(data) foreach (s => System.err.println(s))
          System.err.println(s"\nType '$scriptName $name ${Reset.cmdName}' to reset your working copy")
          generalError(s"Type '$scriptName $name ${Reset.cmdName} --help' for more information")
          
        case None =>
          (options.bad map (_.toLong), options.good map (_.toLong)) match {
            case (Some(bad), Some(good)) if bad == good =>
              generalError("The 'bad' and 'good' revisions cannot be the same")
              
            case (Some(bad), Some(good)) if bad < good =>
              generalError("The 'good' revision must be an ancestor of the 'bad' revision")
              
            case _ =>
          }

          val (headRev, firstRev) = getWorkingCopyBounds()
          
          // save the bisect data in order to start a new session.
          val data = BisectData(
            localPath   = os.pwd.toString,
            originalRev = svn.workingCopyInfo.commitRev,
            headRev     = Some(headRev),
            firstRev    = Some(firstRev),
            maxRev      = options.bad,
            minRev      = options.good,
            termBad     = options.termBad,
            termGood    = options.termGood)
            
          saveBisectData(data)
          os.remove(bisectLogFile) // Remove any previous log file.

          appendToBisectLog("#!/usr/bin/env sh")
          appendToBisectLog(s"# $scriptName $name log file  ${displayDateTime(LocalDateTime.now)}")
          appendToBisectLog(s"# Initiated from: ${os.pwd.toString}")
          appendToBisectLog(s"# ----------------------------")
          data.maxRev foreach (r => logBisectRevision(r, data.termBadName))
          data.minRev foreach (r => logBisectRevision(r, data.termGoodName))
          getWaitingStatus(data) foreach { status =>
            appendToBisectLog(s"# $status")
            println(status)
          }          

          if (data.isReady)
            performBisect(data)
          
          logBisectCommand(cmdName +: args)
      }
    }
  }
  
  // == Bad Command ==++==================================================
  private case object Bad extends BisectCommand {
    override val cmdName = "bad"
    override val description = "Mark a revision as bad  (It contains the bug)"
    
    private case class Options(revision: Option[String] = None)
    
    private def processCommandLine(args: Seq[String], cmdTerm: String): Options = {

      val parser = new OptionParser[Options] {
        val cmdPrefix = s"$scriptName $name $cmdTerm"
        
        addArgumentParser(revisionArgParser)
        
        banner = s"usage: $cmdPrefix [<revision>]"
        separator("")
        separator(description)
        separator("Options:")

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
      val revision = options.revision getOrElse svn.workingCopyInfo.commitRev
      
      // The new bad revision can come after the existing maxRev
      // This allows the user to recheck a range of commits.
      // The new bad revision cannot be less than or equal to the minRev
      if (data.minRev.isDefined && revision.toLong <= data.minRev.get.toLong)
        println(s"'${data.termBadName}' revision must be more recent than the '${data.termGoodName}' revision")
      else {
        markBadRevision(revision)
        logBisectCommand(data.termBadName +: args)
      }
      
      getWaitingStatus(getBisectData()) foreach { status =>
        appendToBisectLog(s"# $status")
        println(status)
      }          
    }
    
    //  Returns true if the performBisect() reports that the session is complete
    //  If this revision was previously skipped, it is no longer skipped
    def markBadRevision(revision: String): Boolean = {
      val data    = getBisectData()
      val newData = data.copy(maxRev = Some(revision), skipped = data.skipped - revision)
      
      saveBisectData(newData)
      logBisectRevision(revision, newData.termBadName)
      if (newData.isReady)
        performBisect(newData)
      else
        false
    }
  }
  
  // == Good Command ==+==================================================
  private case object Good extends BisectCommand {
    override val cmdName = "good"
    override val description = "Mark a revision as good  (It does not contain the bug)"
    
    private case class Options(revision: Option[String] = None)
    
    private def processCommandLine(args: Seq[String], cmdTerm: String): Options = {

      val parser = new OptionParser[Options] {
        val cmdPrefix = s"$scriptName $name $cmdTerm"
        
        addArgumentParser(revisionArgParser)
        
        banner = s"usage: $cmdPrefix [<revision>]"
        separator("")
        separator(description)
        separator("Options:")

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
      val options  = processCommandLine(args, data.termGoodName)
      val revision = options.revision getOrElse svn.workingCopyInfo.commitRev

      // The new good revision can come before the exisiing minRev
      // This allow the user to recheck a range of commits.
      // The new good revision cannot be greater than or equal to the maxRev
      if (data.maxRev.isDefined && revision.toLong >= data.maxRev.get.toLong)
        println(s"'${data.termGoodName}' revision must be older than the '${data.termBadName}' revision")
      else {
        markGoodRevision(revision)
        logBisectCommand(data.termGoodName +: args)
      }
      
      getWaitingStatus(getBisectData()) foreach { status =>
        appendToBisectLog(s"# $status")
        println(status)
      }          
    }
    
    //  Returns true if the performBisect() reports that the session is complete
    //  If this revision was previously skipped, it is no longer skipped
    def markGoodRevision(revision: String): Boolean = {
      val data    = getBisectData()
      val newData = data.copy(minRev = Some(revision), skipped = data.skipped - revision)

      saveBisectData(newData)
      logBisectRevision(revision, newData.termGoodName)
      if (newData.isReady)
        performBisect(newData)
      else
        false
    }
  }
  
  // == Terms Command ====================================================
  private case object Terms extends BisectCommand {
    override val cmdName = "terms"
    override val description = "Show the currently defined terms for good/bad"
    
    private case class Options(termGood: Boolean = false, termBad: Boolean = false)
    
    private def processCommandLine(args: Seq[String]): Options = {

      val parser = new OptionParser[Options] {
        val cmdPrefix = s"$scriptName $name $cmdName"
        
        banner = s"usage: $cmdPrefix [--term-good|--term-bad]"
        separator("")
        separator(description)
        separator("Options:")

        flag("", "--term-good", "Display only the term for 'good'")
          { options =>
            if (options.termBad)
              throw new InvalidArgumentException(" - this command does not accept multiple options")
            options.copy(termGood = true)
          }
          
        flag("", "--term-bad", "Display only the term for 'bad'")
        { options =>
          if (options.termGood)
            throw new InvalidArgumentException(" - this command does not accept multiple options")
          options.copy(termBad = true)
        }
          
        flag("-h", "--help", "Show this message")
            { _ => println(help); throw HelpException() }
            
        separator("")
        separator(s"If no options are given then both terms are displayed")
        separator(s"The current working copy revision is used by default")
      }

      parser.parse(args, Options())
    }
    
    override def run(args: Seq[String]): Unit = {
      val data    = getBisectData()
      val options = processCommandLine(args)
      
      if (options.termGood)
        println(data.termGoodName)
      else if (options.termBad)
        println(data.termBadName)
      else {
        println(s"The term for the good state is ${blue(data.termGoodName)}")
        println(s"The term for the bad  state is ${blue(data.termBadName)}")
        getWaitingStatus(data) foreach println
      }
    }
  }
  
  private case class RevRange(low: Long, high: Long)
  
  private def rangesToSet(ranges: Seq[RevRange]): Set[String] = {
    ranges.foldLeft(Set.empty[String]) {
      case (combined, RevRange(low, high)) =>
      // Add 1 because the Set.range() function is exclusive on the high end
      combined ++ (Set.range(low, high+1) map (_.toString))
    }
  }
  
  // == Skip Command ====================================================+
  private case object Skip extends BisectCommand {
    override val cmdName = "skip"
    override val description = "Skip a revision.  It will no longer be considered"
    
    private case class Options(ranges: Seq[RevRange] = Seq.empty)
    
    private def processCommandLine(args: Seq[String]): Options = {

      val parser = new OptionParser[Options] {
        val cmdPrefix = s"$scriptName $name $cmdName"
        
        addArgumentParser(revisionRangeArgParser)
        
        banner = s"usage: $cmdPrefix [<revision>|<revision>:<revision>]..."
        separator("")
        separator(description)
        separator("Options:")

        flag("-h", "--help", "Show this message")
            { _ => println(help); throw HelpException() }
            
        arg[RevisionRangeArg] { 
          case (RevisionRangeArg(low, high), options) =>
            options.copy(ranges = options.ranges :+ RevRange(low, high))
        }
      }

      parser.parse(args, Options())
    }

    
    override def run(args: Seq[String]): Unit = {
      val options   = processCommandLine(args)
      val data      = getBisectData()
      val revisions = if (options.ranges.nonEmpty) rangesToSet(options.ranges) else Set(svn.workingCopyInfo.commitRev)
        
      markSkippedRevisions(revisions)
      logBisectCommand(cmdName +: args)
      
      getWaitingStatus(getBisectData()) foreach { status =>
        appendToBisectLog(s"# $status")
        println(status)
      }          
    }
    

    //  Returns true if the performBisect() reports that the session is complete
    def markSkippedRevisions(incomingSkips: Set[String]): Boolean = {
      val data       = getBisectData()
      val newSkipped = (incomingSkips -- data.skipped).toSeq sorted RevisionOrdering
      
      if (newSkipped.nonEmpty) {
        val newData = data.copy(skipped = data.skipped ++ incomingSkips)
        saveBisectData(newData)
        newSkipped foreach (r => logBisectRevision(r, cmdName))
        if (newData.isReady)
          performBisect(newData)
        else
          false
      }
      else
        false
    }
  }
  
  // == Unskip Command ====================================================+
  private case object Unskip extends BisectCommand {
    override val cmdName = "unskip"
    override val description = "Reinstate a previously skipped revision"
    
    private case class Options(ranges: Seq[RevRange] = Seq.empty)
    
    private def processCommandLine(args: Seq[String]): Options = {

      val parser = new OptionParser[Options] {
        val cmdPrefix = s"$scriptName $name $cmdName"
        
        addArgumentParser(revisionRangeArgParser)
        
        banner = s"usage: $cmdPrefix [<revision>|<revision>:<revision>]..."
        separator("")
        separator(description)
        separator("Options:")

        flag("-h", "--help", "Show this message")
            { _ => println(help); throw HelpException() }
            
        arg[RevisionRangeArg] {
          case (RevisionRangeArg(low, high), options) =>
            options.copy(ranges = options.ranges :+ RevRange(low, high))
        }
      }

      parser.parse(args, Options())
    }
    
    override def run(args: Seq[String]): Unit = {
      val options      = processCommandLine(args)
      val data         = getBisectData()
      val revisions    = if (options.ranges.nonEmpty) rangesToSet(options.ranges) else Set(svn.workingCopyInfo.commitRev)
        
      markUnskippedRevision(revisions)
      logBisectCommand(cmdName +: args)
            
      getWaitingStatus(getBisectData()) foreach { status =>
        appendToBisectLog(s"# $status")
        println(status)
      }          
    }
    
    //  Returns true if the performBisect() reports that the session is complete
    def markUnskippedRevision(incomingUnskips: Set[String]): Boolean = {
      val data         = getBisectData()
      val newUnskipped = (incomingUnskips intersect data.skipped).toSeq sorted RevisionOrdering
      
      if (newUnskipped.nonEmpty) {
        val newData = data.copy(skipped = data.skipped -- incomingUnskips)
        saveBisectData(newData)
        newUnskipped foreach (r => logBisectRevision(r, cmdName))
        
        if (newData.isReady)
          performBisect(newData)
        else
          false
      }
      else
        false
    }
  }
  
  // == Run Command ======================================================
  private case object Run extends BisectCommand {
    override val cmdName = "run"
    override val description = "Automate the bisect session by running a script"
    
    val cmdPrefix = s"$scriptName $name $cmdName"

    case class Options(cmdArgs: Seq[String] = Seq.empty)
    
    private def processCommandLine(args: Seq[String]): Options = {

      val parser = new OptionParser[Options] {
        
        banner = s"usage: $cmdPrefix <cmd> [<arg>...]"
        separator("")
        separator(description)
        separator("Options:")

        flag("-h", "--help", "Show this message")
            { _ => println(help); throw HelpException() }
            
        arg[String] { (cmdArg, options) => 
          options.copy(cmdArgs = options.cmdArgs :+ cmdArg)
        }
      }

      parser.parse(args, Options())
    }

    //  Run the supplied command continuously until we reach
    //  the end of the bisect session and find the target revision
    //  
    //  If the command returns:
    //  ------------------------------------------------------------
    //  0                      The current commit is good
    //  125                    The curent commit should be skipped
    //  1 - 127 (except 125)   The current commit is bad
    //  128 - 255              The bisect session should be aborted
     
    override def run(args: Seq[String]): Unit = {
      val options = processCommandLine(if (args.isEmpty) Seq("--help") else args)
      val initialData = getBisectData()

      if (options.cmdArgs.isEmpty)
        generalError("You must specify a command to run")
      
      getWaitingStatus(initialData) foreach println
      
      if (!initialData.isReady)
        generalError(s"$cmdPrefix cannot be used until a '${initialData.termGoodName}' revision and '${initialData.termBadName}' revision have been supplied")

      def runCommand(): Unit = {
        import os.Shellable._
        
        val data = getBisectData()  // Get fresh data

        println(options.cmdArgs mkString " ")
        
        val r = os.proc(options.cmdArgs).call(
          check  = false,
          stdin  = os.Inherit,
          stdout = os.Inherit,
          stderr = os.Inherit)
                
        val finished = r.exitCode match {
          case 0 =>
            displayBisectCommand(Seq(data.termGoodName))
            val complete = Good.markGoodRevision(svn.workingCopyInfo.commitRev)
            logBisectCommand(Seq(data.termGoodName))
            complete
            
          case 125 =>
            displayBisectCommand(Seq(Skip.cmdName))
            val complete = Skip.markSkippedRevisions(Set(svn.workingCopyInfo.commitRev))
            logBisectCommand(Seq(Skip.cmdName))
            complete
          
          case r if r < 128 =>
            displayBisectCommand(Seq(data.termBadName))
            val complete = Bad.markBadRevision(svn.workingCopyInfo.commitRev)
            logBisectCommand(Seq(data.termBadName))
            complete
            
          case r => generalError(s"$cmdPrefix: failed.  Command '${options.cmdArgs.head}' returned unrecoverable error code ($r)")
        }
        
        if (!finished)
          runCommand()
      }

      // Start it off
      runCommand()
    }
  }
    
  // == Reset Command ====================================================
  private case object Reset extends BisectCommand {
    override val cmdName = "reset"
    override val description = "Clean up after a bisect session"

    private case class Options(update: Boolean = true, revision: Option[String] = None)
    
    private def processCommandLine(args: Seq[String]): Options = {

      val parser = new OptionParser[Options] {
        val cmdPrefix = s"$scriptName $name $cmdName"
        
        addArgumentParser(revisionArgParser)
        
        banner = s"usage: $cmdPrefix [<options>] [<revision>]"
        separator("")
        separator(description)
        separator("Options:")

        flag("", "--no-update",    "Do not update working copy")
          { _.copy(update = false) }
          
        flag("-h", "--help", "Show this message")
            { _ => println(help); throw HelpException() }
            
        arg[RevisionArg] { (revision, options) => options.copy(revision = Some(revision.rev)) }  
            
        separator("")
        separator(s"The default is to update your working copy to its original revision before the bisect")
        separator(s"If a <revision> is specified, then the working copy will be updated to it insstead")
        separator(s"You can also elect to keep your working copy as it is with --no-update")
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
      else {
        val currentRev = svn.workingCopyInfo.commitRev
        val msg1st     = get1stLogMessage(currentRev)
        
        println(s"Working copy: [${yellow(currentRev)}] $msg1st")      
      }
        
      //  Remove the data file, this will clear the bisect session
      os.remove(bisectDataFile)
      os.remove(bisectLogFile)
    }
  }
  
  // == Log Command ======================================================
  private case object Log extends BisectCommand {
    override val cmdName = "log"
    override val description = "Show the bisect log"

    private def processCommandLine(args: Seq[String]): Unit = {

      val parser = new OptionParser[Unit] {
        val cmdPrefix = s"$scriptName $name $cmdName"
        
        banner = s"usage: $cmdPrefix [<options>]"
        separator("")
        separator(description)
        separator("Options:")

        flag("-h", "--help", "Show this message")
            { _ => println(help); throw HelpException() }
      }

      parser.parse(args, ())
    }

    override def run(args: Seq[String]): Unit = {
      processCommandLine(args)
      getBisectData() // Make sure a bisect session has been started
      displayBisectLog()
    }
  }
    
  // == Replay Command ===================================================
  private case object Replay extends BisectCommand {
    override val cmdName = "replay"
    override val description = "Replay the bisect session from a log file"
    
    private case class Options(logFile: Option[File] = None)
    
    private def processCommandLine(args: Seq[String]): Options = {

      val parser = new OptionParser[Options] {
        val cmdPrefix = s"$scriptName $name $cmdName"
        
        addArgumentParser(revisionArgParser)
        
        banner = s"usage: $cmdPrefix [<options>] <log file>"
        separator("")
        separator(description)
        separator("Options:")

        flag("-h", "--help", "Show this message")
            { _ => println(help); throw HelpException() }
            
        arg[File] { (value, options) => options.copy(logFile = Some(value)) }  
      }

      parser.parse(args, Options())
    }

    override def run(args: Seq[String]): Unit = {
      import os.Shellable._
      val options = processCommandLine(args)
      
      options.logFile match {
        case None       =>
          generalError("You must specify a log file to replay")
          
        case Some(file) if !file.exists =>
          generalError(s"File '$file' does not exist")
          
        case Some(file) =>
          val cmdLine = Seq("/bin/sh", "-c", file.toString)
          os.proc(cmdLine).call(
            check  = false,
            stdin  = os.Inherit,
            stdout = os.Inherit,
            stderr = os.Inherit)
      }
    }
  }
    
    
  private val bisectCommands = Start::Bad::Good::Terms::Skip::Unskip::Run::Log::Replay::Reset::Help::Nil
  
  private def matchCommand(cmdName: String, cmdList: List[String]): List[String] = {
    if ("""^[a-zA-Z][-a-zA-Z0-9_]*""".r matches cmdName)
      cmdList filter (_ startsWith cmdName)
    else
      Nil
  }
  
  private def showHelp(): Nothing = {
    val sv = scriptName
    val help1 = s"""|$description
                    |
                    |Available bisect commands:""".stripMargin
    val help2 = s"""|
                    |Type '$sv $name <command> --help' for details on a specific command""".stripMargin
                    
    println(help1)
    for (c <- bisectCommands)
      println(f"$sv bisect ${c.cmdName}%-8s  ${c.description}")
    println(help2)
    
    
    throw HelpException()
  }
  
  private def getBisectCommand(cmdName: String, termBad: Option[String], termGood: Option[String]): BisectCommand = {
    val cmdList = bisectCommands.map(_.cmdName) ::: termBad.toList ::: termGood.toList
    matchCommand(cmdName, cmdList) match {
      case Nil                                   =>
        println(s"'$cmdName' is not a valid $scriptName $name command")
        showHelp()
      case name :: Nil if Some(name) == termBad  => Bad
      case name :: Nil if Some(name) == termGood => Good
      case name :: Nil                           => bisectCommands.find(_.cmdName == name).get
      case names =>
        generalError(s"$scriptName $name command '$cmdName' is ambiguous.  (${names.mkString(", ")})")
    }      
  }

  // Main entry point to bisect commnad
  override def run(args: Seq[String]): Unit = {

    if (args.isEmpty || args.head == "--help" || args.head == "-h")
      showHelp();
    else {
      val (badOverride, goodOverride) = if (svn.inWorkingCopy) {
        val optData = loadBisectData()
        (optData flatMap (_.termBad), optData flatMap (_.termGood))
      }
      else
        (None, None)

      val command = getBisectCommand(args.head, badOverride, goodOverride)
      command.run(args.tail)
    }
  } 
}
