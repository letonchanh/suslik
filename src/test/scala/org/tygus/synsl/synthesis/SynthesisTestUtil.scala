package org.tygus.synsl.synthesis

import java.io.File

import org.tygus.synsl.logic.Resolver._
import org.tygus.synsl.parsing.SynslParser

import scala.io.Source

/**
  * @author Nadia Polikarpova, Ilya Sergey
  */

trait SynthesisTestUtil {

  val testSeparator = "###"
  val testExtension = "syn"
  val defExtension = "def"

  // The path starts from the project root.
  val rootDir: String = "./src/test/resources/synthesis".replace("/", File.separator)

  val synthesis: Synthesis

  def doTest(desc: String, in: String, out: String): Unit

  import synthesis._

  def getDescInputOutput(testFilePath: String): (String, String, String) = {
    val file = new File(testFilePath)
    // The path is counted from the rout
    val lines = Source.fromFile(file).getLines.toList
    val i = lines.indexWhere(_.trim.contains(testSeparator))
    val (l1, l2) = lines.splitAt(i)
    val fname = removeSuffix(file.getName, s".$testExtension")
    val dirName = file.getParentFile.getName
    val description = if (l1.isEmpty) "Testing synthesis" else l1.mkString("\n").trim
    // The first part is the description
    val desc = s"[$dirName/$fname] $description"

    val remainder = l2.tail
    // The remainder is the input and output
    val j = remainder.indexWhere(_.trim.startsWith(testSeparator))
    val (l3, l4) = remainder.splitAt(j)
    val input = l3.mkString(" ").trim
    val output = l4.tail.mkString("\n").trim
    (desc, input, output)
  }

  def synthesizeFromFile(dir: String, testName: String): Unit = {
    val (desc, in, out) = getDescInputOutput(testName)
    synthesizeFromSpec(in, out)
  }

  def synthesizeFromSpec(text: String, out: String = "nope") {
    val parser = new SynslParser
    val res = parser.parseGoal(text)
    assert(res.successful, res)

    val prog = res.get
    assert(prog.decls.nonEmpty)
    val (goals, env) = resolveProgram(prog)

    assert(goals.lengthCompare(1) == 0, "Expected a single synthesis goal")

    val goal = goals.head
    val sresult = synthesizeProc(goal, env)

    sresult match {
      case Some(rr) =>
        println("Specification:\n")
        println(s"${goal.pp}\n")
        println("Successfully synthesised:")
        val result = rr.pp
        println(s"$result")
        println("-----------------------------------------------------")
        if (out != "nope") {
          val tt = out.stripMargin.trim
          assert(result.trim == tt, s"\nThe expected output\n$tt\ndoesn't match the result:\n${result.trim}")
        }
      case None =>
        assert(false, s"Failed to synthesise:\n$sresult")
    }
  }


  def getDefs(defFiles: List[File]): String = {
    if (defFiles.isEmpty) return ""
    assert(defFiles.size == 1, "More than one file with definitions in the folder")
    val file = new File(defFiles.head.getAbsolutePath)
    Source.fromFile(file).getLines.toList.mkString("\n")
  }


  def runAllTestsFromDir(dir: String) {
    val path = List(rootDir, dir).mkString(File.separator)
    val testDir = new File(path)
    if (testDir.exists() && testDir.isDirectory) {
      // Get definitions
      val defs = getDefs(testDir.listFiles.filter(f => f.isFile && f.getName.endsWith(s".$defExtension")).toList)
      // Get specs
      val tests = testDir.listFiles.filter(f => f.isFile && f.getName.endsWith(s".$testExtension")).toList
      for (f <- tests) {
        val (desc, in, out) = getDescInputOutput(f.getAbsolutePath)
        val fullInput = List(defs, in).mkString("\n")
        doTest(desc, fullInput, out)
      }
    }
  }

  def runSingleTestFromDir(dir: String, fname: String) {
    val path = List(rootDir, dir).mkString(File.separator)
    val testDir = new File(path)
    if (testDir.exists() && testDir.isDirectory) {
      // Get definitions
      val defs = getDefs(testDir.listFiles.filter(f => f.isFile && f.getName.endsWith(s".$defExtension")).toList)
      // Get specs
      val tests = testDir.listFiles.filter(f => f.isFile && f.getName.endsWith(s".$testExtension")).toList
      tests.find(f => removeSuffix(f.getName, s".$testExtension") == fname) match {
        case Some(f) =>
          val (desc, in, out) = getDescInputOutput(f.getAbsolutePath)
          val fullInput = List(defs, in).mkString("\n")
          doTest(desc, fullInput, out)
        case None =>
          assert(false, s"No file with the name $fname found in the directory $dir.")
      }
    }
  }


  def removeSuffix(s: String, suffix: String): String = {
    if (s.endsWith(suffix)) s.substring(0, s.length - suffix.length) else s
  }

}