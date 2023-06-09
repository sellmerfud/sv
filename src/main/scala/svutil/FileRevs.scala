

package svutil

import scala.util.matching.Regex
import java.util.regex.PatternSyntaxException
import java.time._
import scala.xml._
import scala.util.{ Try, Success, Failure }
import Color._
import Utilities._
import svn.model.SvnInfo
import svn.model.{ ListEntry }

object FileRevs extends Command {
  
  override val name = "filerevs"
  override val description = "Display commit revisions of files across tags and branches"
  
  case class Options(
    allBranches: Boolean        = false,
    allTags:     Boolean        = false,
    branches:    Vector[Regex]  = Vector.empty,
    tags:        Vector[Regex]  = Vector.empty,
    paths:       Vector[String] = Vector.empty)
  
  private def processCommandLine(args: Seq[String]): Options = {
    import org.sellmerfud.optparse._
    
    val parser = new OptionParser[Options] {
      addArgumentParser[Regex] { arg =>
        try   { new Regex(arg) }
        catch { 
          case e: PatternSyntaxException =>
            throw new InvalidArgumentException(s"\n${e.getMessage}")
        }
      }
      
      banner = s"usage: $scriptName $name [<options>] <path> | <url>..."
      separator("")
      separator(description)
      separator("Options:")
      
      optl[Regex]("-b", "--branch[=<regex>]", "Specifiy branch(es) upon which to reference paths") {
        case (Some(regex), options) => options.copy(branches = options.branches :+ regex)
        case (None, options )       => options.copy(allBranches = true)
      }
          
      optl[Regex]("-t", "--tag[=<regex>]", "Specifiy tag(s) upon which to reference paths") {
        case (Some(regex), options) => options.copy(tags = options.tags :+ regex)
        case (None, options)        => options.copy(allTags = true)
      }
        
      flag("-h", "--help", "Show this message")
          { _ => println(help); throw HelpException() }
    
      arg[String] { (path, options) => options.copy(paths = options.paths :+ path) }  
        
      separator("")
      separator("If neither --branch nor --tag is given, only the trunk revision is displayed.")
      separator("--branch and --tag may be specified multiple times.")
      separator("--branch or --tag without a <regex> will consider all branches or tags")
      separator("Use -- to separate the <path> from --branches or --tag with no <regex>")
      separator("Assumes the repository has standard /trunk, /branches, /tags structure.")
    }
    
    parser.parse(args, Options())
  }
  
  
  private def getBranches(rootUrl: String, options: Options): List[ListEntry] = {
    if (options.allBranches == false && options.branches.isEmpty)
      Nil
    else {
      val branchEntries = svn.pathList(joinPaths(rootUrl, "branches")).head.entries
      if (options.allBranches)
        branchEntries
      else
         branchEntries filter { entry => options.branches.exists(_.contains(entry.name)) }
    }
  }
  
  private def getTags(rootUrl: String, options: Options): List[ListEntry] = {
    if (options.allTags == false && options.tags.isEmpty)
      Nil
    else {
      val tagEntries = svn.pathList(joinPaths(rootUrl, "tags")).head.entries
      if (options.allTags)
        tagEntries
      else
        tagEntries filter { entry => options.tags.exists(_.contains(entry.name)) }
    }
  }
  
  private def getTrunk(rootUrl: String): ListEntry = {
    svn.pathList(rootUrl).head.entries find (_.name == "trunk") getOrElse {
      generalError(s"Cannot find repository trunk: ${joinPaths(rootUrl, "trunk")}")
    }
  }
  
  private def relativePath(info: SvnInfo): String = {
    val trunk = """\^/trunk/(.*)""".r
    val other = """\^/(?:branches|tags)/[^/]+/(.*)""".r
    (info.relativeUrl) match {
      case trunk(path) => path
      case other(path) => path
      case _           => generalError(s"Cannot determine relative path for ${info.relativeUrl}")
    }
  }
  
  private def pathPrefix(pathType: String, entry: ListEntry): String = pathType match {
    case "trunk"             => "trunk"
    case "branches" | "tags" => joinPaths(pathType, entry.name)
    case _                   => generalError(s"Invalid path type: $pathType")
  }
  
  private def maxWidth(seq: Seq[String]) = seq.foldLeft(0) ((maxLen, str) => str.length max maxLen)
  
  
  private def formatField(value: String, width: Int, rightJustify: Boolean = false): String = {
    val pad = " " * (width - value.length)
    if (rightJustify) s"$pad$value" else s"$value$pad"
  }
  
  
  // /this/is/the/users/path              
  // Location        Repo Rev  Commit Rev  Author  Date         Size
  // --------------  --------  ----------  ------  -----------  ----------
  // trunk               7601   <does not exist>
  // branches/8.1        7645
  // tags/8.1.1-GA       7625
  // 7630                7630
  
  private def showResults(rootUrl: String, pathEntry: SvnInfo, trunk: ListEntry, branches: List[ListEntry], tags: List[ListEntry]): Unit = {
    case class Result(locationName: String, info: Option[SvnInfo])
    val relPath = relativePath(pathEntry)
    val locations = ("trunk" -> trunk) :: (branches map ("branches" -> _)) ::: (tags map ("tags" -> _))

    val results = for ((pathType, location) <- locations) yield {
      val prefix    = pathPrefix(pathType, location)
      val entryPath = joinPaths(rootUrl, prefix, relPath)
      
      Try(svn.info(entryPath, Some("HEAD"))) match {
        case Success(info) => Result(prefix, Some(info))
        case Failure(e)    => Result(prefix, None)
      }
    }
    
    val Location        = "Location"
    val Revision        = "Revision"
    val Author          = "Author"
    val Date            = "Date"
    val Size            = "Size"
    val LocationWidth   = maxWidth(Location :: (results map (_.locationName)))
    val RevisionWidth   = maxWidth(Revision :: (results filter (_.info.nonEmpty) map (_.info.get.commitRev)))
    val AuthorWidth     = maxWidth(Author :: (results filter (_.info.nonEmpty) map (_.info.get.commitAuthor)))
    val DateWidth       = displayDateTime(LocalDateTime.now).length
    val SizeWidth       = 8
    val ColumnSeparator = " "
    
    // Display results
    println()
    if (pathEntry.kind == "dir")
      println(blue(s"$relPath/"))
    else
      println(blue(relPath))
    // Headers
    print(formatField(Location, LocationWidth)); print(ColumnSeparator)
    print(formatField(Revision, RevisionWidth)); print(ColumnSeparator)
    print(formatField(Author, AuthorWidth)); print(ColumnSeparator)
    print(formatField(Date, DateWidth)); print(ColumnSeparator)
    print(formatField(Size, SizeWidth)); println()
    
    print("-" * LocationWidth); print(ColumnSeparator)
    print("-" *  RevisionWidth); print(ColumnSeparator)
    print("-" *  AuthorWidth); print(ColumnSeparator)
    print("-" *  DateWidth); print(ColumnSeparator)
    print("-" *  SizeWidth); println()

    results foreach {
      case Result(locName, None) =>
        print(green(formatField(locName, LocationWidth))); print(ColumnSeparator)
        println(red("<does not exist>"))
        
      case Result(locName, Some(info)) =>
        val date = displayDateTime(info.commitDate)
        val size = info.size map (_.toString) getOrElse "n/a"
        print(green(formatField(locName, LocationWidth))); print(ColumnSeparator)
        print(yellow(formatField(info.commitRev, RevisionWidth, true))); print(ColumnSeparator)
        print(cyan(formatField(info.commitAuthor, AuthorWidth))); print(ColumnSeparator)
        print(purple(formatField(date, DateWidth))); print(ColumnSeparator)
        print(formatField(size, SizeWidth, true)); println()
    }
  }
  
  
  
  override def run(args: Seq[String]): Unit = {
    val options = processCommandLine(args)
    
    if (options.paths.isEmpty)
      generalError("At least one path must be specified.")
      
    val pathList = svn.infoList(options.paths filterNot (_.trim == ""))
    
    // First make sure all paths are rooted in the same repository
    if ((pathList map (_.repoUUID)).distinct.size != 1)
      generalError("All paths must refer to the same repository.")
    
    val rootUrl  = pathList.head.rootUrl
    val trunk    = getTrunk(rootUrl)
    val tags     = getTags(rootUrl, options)
    val branches = getBranches(rootUrl, options)
    
    for (pathEntry <- pathList)
      showResults(rootUrl, pathEntry, trunk, branches, tags)
  } 
}
