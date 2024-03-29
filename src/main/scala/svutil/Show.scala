

package svutil

import java.io.File
import scala.util.matching.Regex
import scala.xml._
import Color._
import Utilities._
import svn.model.{ LogEntry }

object Show extends Command {
  
  override val name = "show"
  override val description = "Show the details of a given revision"

  val REV = """^(\d+|HEAD|BASE|PREV|COMMITTED)(?:([-+])(\d+))?$""".r
  def looksLikeRevision(str: String): Boolean = REV matches str
    
  case class Options(
    revision:  Option[String] = None,
    paths:     Vector[String] = Vector.empty,
    showMsg:   Boolean        = true,
    showPaths: Boolean        = false,
    showDiff:  Boolean        = false
  )
  
  private def processCommandLine(args: Seq[String]): Options = {
    import org.sellmerfud.optparse._
    
    val parser = new OptionParser[Options] {
      banner = s"usage: $scriptName $name [<options>] [<revision>] [<path>|<url>]"
      separator("")
      separator(description)
      separator("Options:")
      
      reqd[String]("-r", "--revision=<revision>", "Repository revision of the commit to show")
        { (rev, options) =>
          if (looksLikeRevision(rev))
            options.copy(revision = Some(rev))
          else
              throw new InvalidArgumentException(" is not a valid revision argument")
        }

      bool("-m", "--message", "Show the commit message (default yes)")
        { (value, options) => options.copy(showMsg = value) }

      bool ("-p", "--paths", "Show the paths affected by the commit (default no)")
        { (value, options) => options.copy(showPaths = value) }
        
      bool ("-d", "--diff", "Show the diff output of the commit (default no)")
        { (value, options) => options.copy(showDiff = value) }
        
      flag ("-c", "--concise", "Shorthand for --no-message --no-paths --no-diff")
        { _.copy(showMsg = false, showPaths = false, showDiff = false) }
        
      flag("-h", "--help", "Show this message")
          { _ => println(help); sys.exit(0) }
    
      arg[String] { (path, options) => options.copy(paths = options.paths :+ path) }  
        
      separator("")
      separator("<revision> defaults to the current working copy revision")
      separator("if no -r<revision> is given, and first path argument looks like a revision")
      separator("then it is treated as a revision")
      separator("If no <path> is given it defaults to the current working copy directory")
    }
    
    parser.parse(args, Options())
  }
  
  private def resolveRevision(revString: String, path: String): String = {
    revString match {
      case REV(rev, null, null) =>
        rev
      case REV(rev, op, delta) =>
        val revs  = Seq(if (op == "-") s"$rev:0" else s"$rev:HEAD")
        val limit = Some(delta.toInt + 1)
        val entries = svn.log(paths = Seq(path), revisions = revs, limit = limit, includeMessage = false)
        entries.last.revision
      case _ =>
        generalError(s"Cannot resolve revision from $revString for path ($path)")
    }
  }

  private def getLogEntry(path: String, options: Options): Option[LogEntry] = {
    //  In some cases when the revision is PREV, it may not produce a log entry 
    //  even though 'svn info' would succeed.  To work around this oddity
    //  we append :0 to the revision and limit the log to 1 entry.
    val fixRev   = (rev: String) => if (rev contains ':') rev else s"$rev:0"
    svn.log(
      paths        = Seq(path),
      revisions    = options.revision.toSeq map { r => fixRev(resolveRevision(r, path)) },
      limit        = Some(1),
      includePaths = true
    ).headOption
  }
  
  override def run(args: Seq[String]): Unit = {
    val options = processCommandLine(args)
    // If no revision is given an the path argument looks like a revision
    // then treat it as a revision for the current working directory
    val finalOptions = if (options.revision.isEmpty && options.paths.nonEmpty && looksLikeRevision(options.paths.head)) {
      options.copy(revision = Some(options.paths.head), paths = options.paths.tail)
    }
    else
      options
    val path = finalOptions.paths.headOption getOrElse "."
        
    getLogEntry(path, finalOptions) foreach { log =>
      val info = svn.info(path, Some(log.revision))
      println()
      println(s"${blue(path)} (Last changed: ${yellow(info.commitRev)})")
      showCommit(log, finalOptions.showMsg, finalOptions.showPaths)
      if (finalOptions.showDiff) {
        println()
        svn.changeDiff(path, log.revision) foreach printDiffLine
      }
    }
  } 
}
