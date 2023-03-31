

package svutil

import java.io.File
import scala.util.matching.Regex
import scala.xml._
import Exec.{ runCmd, ExecError }
import Color._
import Utilities._

object Show extends Command {
  
  override val name = "show"
  override val description = "Show the details of a revision"
  
  case class Options(
    revision:  Option[String] = None,
    path:      String         = ".",
    showMsg:   Boolean        = true,
    showPaths: Boolean        = true,
    showDiff:  Boolean        = true
  )
  
  private def processCommandLine(args: Seq[String]): Options = {
    import org.sellmerfud.optparse._
    
    val parser = new OptionParser[Options] {
      banner = s"usage: $scriptName $name [<options>] [<path>|<url>]"
      
      reqd[String]("-r", "--revision=<revision>", "Repository revision of the commit to show")
        { (rev, options) => options.copy(revision = Some(rev)) }

      bool("-m", "--message", "Show the commit message (default yes)")
        { (value, options) => options.copy(showMsg = value) }

      bool ("-p", "--paths", "Show the paths affected by the commit (default yes)")
        { (value, options) => options.copy(showPaths = value) }
        
      bool ("-d", "--diff", "Show the diff output of the commit (default yes)")
        { (value, options) => options.copy(showDiff = value) }
        
      flag ("-c", "--concise", "Shorthand for --no-message --no-paths --no-diff")
        { _.copy(showMsg = false, showPaths = false, showDiff = false) }
        
      flag("-h", "--help", "Show this message")
          { _ => println(help); sys.exit(0) }
    
      arg[String] { (path, options) => options.copy(path = path) }  
        
      separator("")
      separator("<revision> defaults to the current working copy revision")
      separator("<path> defaults to '.'")
    }
    
    parser.parse(args, Options())
  }
  
  
  private def getLogEntry(options: Options): Option[LogEntry] = {
    val revArg   = (options.revision map (r => s"--revision=$r")).toSeq
    val pathsArg = (if (options.showPaths) Some("--verbose") else None).toSeq
    val cmdLine  = Seq("svn", "log", "--xml", "--limit=1") ++ revArg ++ pathsArg :+ options.path
    val logXML   = XML.loadString(runCmd(cmdLine).mkString("\n"))
    (logXML \ "logentry").headOption map parseLogEntry
  }
  
  def looksLikeRevision(str: String): Boolean = """^(\d+|HEAD|BASE|PREV|COMMITTED)$""".r matches str
  
  override def run(args: Seq[String]): Unit = {
    val options = processCommandLine(args)
    // If no revision is given an the path argument looks like a revision
    // then treat it as a revision for the current working directory
    val finalOptions = if (options.revision.isEmpty && looksLikeRevision(options.path))
      options.copy(path = ".", revision = Some(options.path))
      else
        options
    
    getLogEntry(finalOptions) foreach { log =>
      println()
      println(blue(finalOptions.path))
      showCommit(log, finalOptions.showMsg, finalOptions.showPaths)
      if (finalOptions.showDiff)
        showChangeDiff(finalOptions.path, log.revision)
    }
  } 
}
