package ara

import java.io.File
import java.io.PrintWriter
import scala.io.Source
import ara.Cluster._
import edu.northwestern.at.utils.math.statistics.Descriptive
import java.util.Calendar

object AraUtilities extends MTBCclusters {

  case class Config(
    val markers: File = null,
    val output: String = null)

  def main(args: Array[String]) {

    val parser = new scopt.OptionParser[Config]("java -jar ara.jar interpret") {
      opt[File]('m', "markers") required () action { (x, c) => c.copy(markers = x) } text ("Input file with mapped markers.")
      opt[String]('o', "output") required () action { (x, c) => c.copy(output = x + ".interpret.ara") } text ("Output name.")
    }

    parser.parse(args, Config()) map { config =>

      val markers = Source.fromFile(config.markers).getLines.filterNot(_.startsWith("#")).toList.dropRight(2).map {
        _ match {
          case Marker(m, c, p) => new ClusterMarker(m, c, p)
        }
      }.groupBy(_.lineage)
      //markers.foreach(pw.println)

      /** Count total number of markers per cluster. */
      val totalMarkersPerLineage = markers.mapValues(_.size)
      //totalMarkersPerLineage.toList.sortBy(_._1).foreach(println)
      //println("Total markers per lineage")
      //println(totalMarkersPerLineage.foldLeft(0)(_ + _._2))

      /** Total number of mapped reads per cluster. */
      val markerCounts = markers.map(_ match {
        case (lineage, linMarkers) => (lineage, linMarkers.map(_.count).foldLeft(0)(_ + _))
      })
      //println("markerCounts")
      //markerCounts.toList.sortBy(_._1).foreach(println)

      /** Number of present markers per cluster. */
      val linCountsPresent = markers.map(_ match {
        case (lineage, linMarkers) => (lineage, linMarkers.filter(_.isPresent).size)
      })
      //println("LinCountsPresent")
      //linCountsPresent.foreach(pw.println)

      /** Average read depth of present markers per cluster */
      val averageCov = markers.filter(_ match {
        case (lineage, linMarkers) => linCountsPresent(lineage) > 0
      }).map(_ match {
        case (lineage, linMarkers) => (lineage, linMarkers.foldLeft(0)(_ + _.count) / linCountsPresent(lineage))
      })
      //averageCov.foreach(println)

      /** Median read depth of present markers per cluster */
      def medianCov(c: String): Double = {
        val medianCovMap = markers.map(_ match {
          case (lineage, linMarkers) => {
            val arr = linMarkers.filter(_.isPresent).map(_.count.toDouble).sorted.toArray
            (lineage, Descriptive.median(arr))
          }
        })
        if (c.hasZeroMarkers || c == "MTBC") 0 else medianCovMap(c)
      }

      /**
       * Presence of cluster based on number detected markers
       * Clusters with 0 markers could be present, so function return true for these clusters.
       */
      def presence(c: String): Boolean = {
        if (c.hasZeroMarkers || c == "MTBC") true
        else if (!c.isCluster) false
        else {
          val totalMarkers = totalMarkersPerLineage(c)
          val presentMarkers = linCountsPresent(c)
          if (c.hasReference) {
            presentMarkers > (totalMarkers / 24)
          } else {
            presentMarkers > (totalMarkers / 8)
          }
        }

      }

      /** Recursive call to get tree path from leaf to root */
      def getPath(str: String): List[String] = {
        def recursive(ls: List[String]): List[String] = ls.head match {
          case c if (c.hasAncestor) => recursive(c.getAncestor :: ls)
          case _ => ls
        }
        recursive(List(str))
      }

      /** Mean coverage of of clusters in path */
      def meanCovPath(ls: List[String]): Double = {
        val covPerCluster = ls.filterNot(_.hasZeroMarkers).map(c => medianCov(c))
        if (covPerCluster.isEmpty) 0
        else (covPerCluster).foldLeft(0.toDouble)(_ + _) / covPerCluster.size
      }

      /** Split paths by present strains */
      def splitPaths(ls: List[List[String]]): List[List[String]] = {
        if (ls.isEmpty) List.empty[List[String]]
        else {
          val pathCount = ls.flatten.groupBy(identity).mapValues(_.size)
          val maxPaths = pathCount.map(_._2).max
          val separatedPaths = (1 to maxPaths).toList.map { number =>
            val path = ls.map(_.filter(pathCount(_) == number)).distinct.filterNot(_.isEmpty)
            (number -> path)
          }.toMap
          separatedPaths.flatMap(_._2).toList
        }

      }

      def childIsPresent(c: String): Boolean = {
        val children = c.children
        presence(children(0)) || presence(children(1))
      }

      def ancestorIsPresent(c: String): Boolean = {
        val ancestor = c.getAncestor
        presence(c)
      }

      /**
       *  Interpret results
       */

      val pw = new PrintWriter(config.output)

      val clusters = mtbcClusters.filter { c => presence(c) }.filterNot(c => childIsPresent(c)).filter(ancestorIsPresent(_))
      println("Possible present end cluster(s)\tMean coverage\tMedian coverage")
      clusters.sorted.foreach(c => if (c.hasZeroMarkers) println(c + "\t" + 0 + "\t" + 0) else println(c + "\t" + averageCov(c) + "\t" + medianCov(c)))
      println

      val paths = clusters.map(getPath(_)).filterNot { p =>
        val path = p.map(c => presence(c))
        path.contains(false)
      }
      println("Possible present path(s)")
      paths.foreach(p => println(p.map(c => (medianCov(c), c))))
      println
      

      /** Check possible presence of (separate) paths where each cluster has 0 markers */
      def pathWith0Markers(p: List[String]): Boolean = {
        !p.map(c => c.hasZeroMarkers).contains(false)
      }

      def removeAbsent(ls: List[List[String]]): List[(Double, List[String])] = {
        val meanCovPerPath = splitPaths(ls).map { path =>
          (meanCovPath(path), path)
        }
        println("Mean coverage per path (markers, cluster)")
        meanCovPerPath.foreach(p => println(p._1 + "\t" + p._2.map { c =>
          val markers = if (linCountsPresent.contains(c)) linCountsPresent(c) + "/" + (if (c.hasReference) totalMarkersPerLineage(c) / 3 else totalMarkersPerLineage(c)) else "0"
          (markers, c)
        }))
        println
        
        def listAbsent(ls: List[(Double, List[String])]): List[String] = {
          val absentClusters = ls.filter {
            _ match {
              case (mean, path) => {
                if (pathWith0Markers(path) && !path.equals(List("MTBC"))) {
                  val sibling = path.head.getSibling
                  val siblingPath = ls.filter(_._2.contains(sibling)).head
                  if (siblingPath != null) { // sibling path exists
                    val siblingCov = siblingPath._1
                    val ancestorCov = ls.filter(_._2.contains(path.head.getAncestor)).head._1
                    if (((ancestorCov - siblingCov) > 25)) { // If there is missing read depth >25 compared to ancestor, then path with 0 markers is present
                      println("Mean " + mean + ", path\n" + path)
                      println("SibCov: " + siblingCov)
                      println("ancCov: " + ancestorCov)
                      false
                    } else { // No missing read depth compared to ancestor, so path with 0 markers is not present
                      true
                    }
                  } else { // sibling path not exists
                    false
                  }
                } else { // Not a path with 0 markers or List("MTBC")
                  false
                }
              }
            }
          }.flatMap(_._2)
          absentClusters
        }
        val absent = listAbsent(meanCovPerPath)
        
        if (absent.isEmpty) {
          meanCovPerPath
        } else {
          println("Absent clusters with 0 markers")
          absent.foreach(println)
          println
          val presentPaths = ls.filter { p =>
            p.intersect(absent).isEmpty
          }
          removeAbsent(presentPaths)
        }
      }

      val meanCovPerPath2 = removeAbsent(paths)
      println("Mean coverage per path (markers, cluster)")
      meanCovPerPath2.foreach(p => println(p._1 + "\t" + p._2.map { c =>
        val markers = if (linCountsPresent.contains(c)) linCountsPresent(c) + "/" + (if (c.hasReference) totalMarkersPerLineage(c) / 3 else totalMarkersPerLineage(c)) else "0"
        (markers, c)
      }))
      println

      /**
       *  Print output
       */
      pw.println("# Ara Results")
      pw.println("# Command: " + args.mkString(" "))
      pw.println("# Date: " + Calendar.getInstance.getTime)
      val strains = meanCovPerPath2.filterNot(p => childIsPresent(p._2.last)).size
      pw.println("# " + strains + " present strain(s)/path(s)")

      if (meanCovPerPath2.size > 1) { // Mixed infection, estimate frequencies

        /** Get split level of path */
        def getLevel(p: List[String]): Int = p match {
          case head :: tail => head match {
            case "MTBC" => 0
            case x => 1 + getLevel(meanCovPerPath2.map(_._2).filter(_.contains(x.getAncestor)).head)
          }
          case Nil => 0
        }

        var suspicious = false // Multiple paths but no missing read coverage

        val pathNumbers = meanCovPerPath2.map {
          _ match {
            case (depth, path) => {
              if (path.head == "MTBC") { // root path
                if (path.size == 1) { // root path only contains cluster MTBC
                  val rootNodes = meanCovPerPath2.filter(p => p._2.head == "L1-L2-L3-L4" || p._2.head == "L5-L6-LB" || p._2.head == "MTBC")
                  val rootMeanCov = rootNodes.map(_._1).foldLeft(0.toDouble)(_ + _)
                  (1.toDouble, getLevel(path), rootMeanCov, rootMeanCov, path)
                } else (1.toDouble, getLevel(path), depth, depth, path)
              } else { //non-root path
                val sibling = path.head.getSibling
                val siblingPath = meanCovPerPath2.filter(_._2.contains(sibling)).head
                val ancestorCov = meanCovPerPath2.filter(_._2.contains(path.head.getAncestor)).head._1
                if (pathWith0Markers(siblingPath._2)) { // Misses coverage compared to ancestor (> 25 reads)
                  val frequency = depth / ancestorCov
                  (frequency, getLevel(path), ancestorCov, depth, path)
                } else if (pathWith0Markers(path)) { // Present path with 0 markers
                  val frequency = 1 - (siblingPath._1 / ancestorCov)
                  (frequency, getLevel(path), ancestorCov, depth, path)
                } else {
                  val totalCovSiblings = siblingPath._1 + depth
                  if ((totalCovSiblings - ancestorCov) > 25) suspicious = true
                  val frequency = depth / totalCovSiblings
                  (frequency, getLevel(path), totalCovSiblings, depth, path)
                }
              }
            }
          }
        }.sortBy(_._2)
        println("Frequency, level, totalDepth, depth, path")
        pathNumbers.foreach(println)
        println

        def getAncestralNodes(p: List[String]): List[String] = p match {
          case head :: tail => head match {
            case "MTBC" => List(head)
            case x => x :: getAncestralNodes(meanCovPerPath2.map(_._2).filter(_.contains(x.getAncestor)).head)
          }
          case Nil => Nil
        }

        /** Multiply frequencies of separate paths to ancestor */
        val frequencies = pathNumbers.filterNot(p => (p._5.last.children.map(cc => presence(cc)).contains(true))).map { p =>
          val ancestors = getAncestralNodes(p._5)
          val freqArr = ancestors.map(a => pathNumbers.filter(_._5.contains(a)).head).map(_._1)
          (p._5.last, (freqArr.foldLeft(1.toDouble)(_ * _)))
        }
        println("Frequencies")
        frequencies.foreach(println)
        println

        if (suspicious) pw.println("SUSPICIOUS") else pw.println()
        pw.println("Predicted group(s): " + frequencies.map(p => p._1 + "(" + p._2 + ")").mkString(", "))
        def printNumbers(ls: List[(Double, Int, Double, Double, List[String])]) = {
          pw.println("Mixed sample: TRUE\n")
          ls.foreach {
            _ match {
              case (freq, lvl, siblingsDepth, depth, path) => {
                val space = "\t" * lvl
                pw.println(space + (if (path.size == 1) path.head else (path.head + " -> " + path.last)))
                pw.println(space + "Mean read depth: " + depth)
                pw.println(space + "Frequency estimate: " + freq + " (" + depth + "/" + siblingsDepth + ")")
                pw.println
              }
            }
          }
        }
        printNumbers(pathNumbers)

      } else if (meanCovPerPath2.size == 1) { // Not a mixed infection

        val path = meanCovPerPath2.head._2
        val cov = meanCovPerPath2.head._1

        pw.println()
        pw.println("Predicted group(s): " + path.last)
        pw.println("Mixed sample: FALSE\n")
        pw.println(path.head + " -> " + path.last)
        pw.println("Mean read depth: " + cov)
        pw.println("Frequency estimate: 1.0" + " (" + cov + "/" + cov + ")")

      } else { // No path detected

        pw.println()
        pw.println("Predicted group(s): NONE")
        pw.println("Mixed sample: FALSE")

      }

      pw.close

    }

  }
}