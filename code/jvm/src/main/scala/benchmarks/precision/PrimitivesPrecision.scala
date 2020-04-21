package scalaam.cli.benchmarks.precision

import scalaam.cli.benchmarks._
import scalaam.io.Reader

import scalaam.lattice._
import scalaam.language.scheme._
import scalaam.modular._
import scalaam.modular.scheme._
import scalaam.language.scheme.primitives.SchemePrelude

import scala.concurrent.duration._
import scalaam.util._

object PrimitivesBenchmarks {

  // Subset of SchemeBenchmarks.standard, only the interesting benchmarks
  val standard = List(
    "test/kernighanvanwyk/ack.scm",
    "test/rsa.scm",
    "test/church.scm",
    "test/mceval.scm",
    "test/sat.scm",
    "test/regex.scm",
    "test/rsa.scm",
    "test/four-in-a-row.scm",
    "test/sigscheme/mem.scm",
    "test/grid.scm",
    //    "test/sigscheme/takr.scm", // has a tendency to result in OOM in concrete mode
  )

  val benchmarks = {
    SchemeBenchmarks.gambit ++
    SchemeBenchmarks.gabriel ++
    SchemeBenchmarks.scp1_compressed ++
    SchemeBenchmarks.icp ++
    SchemeBenchmarks.ad ++
    standard
  }
  println(s"I have ${benchmarks.size} benchmarks")
}

abstract class PrimitivesComparison extends AnalysisComparison[
    ConstantPropagation.I,
    ConstantPropagation.R,
    Concrete.B,
    ConstantPropagation.C,
    ConstantPropagation.S,
    Concrete.Sym
] {
  var path: String = "<none>"

  def S_0_0(prg: SchemeExp): Analysis
  def S_CS_0(prg: SchemeExp): Analysis
  def S_2CS_0(prg: SchemeExp): Analysis
  def S_2AcyclicCS_0(prg: SchemeExp): Analysis
  def S_10CS_0(prg: SchemeExp): Analysis
  def S_10AcyclicCS_0(prg: SchemeExp): Analysis
  def S_FA_0(prg: SchemeExp): Analysis
  def S_2FA_0(prg: SchemeExp): Analysis
  def S_10FA_0(prg: SchemeExp): Analysis
  def S_CSFA_0(prg: SchemeExp): Analysis


  override def analysisTimeout() = Timeout.start(Duration(2, MINUTES)) //timeout for (non-base) analyses
  override def concreteTimeout() = Timeout.start(Duration(2, MINUTES))
  override def concreteRuns() = 2
  def baseAnalysis(prg: SchemeExp): Analysis =
    SchemeAnalyses.contextInsensitiveAnalysis(prg)
  def otherAnalyses(prg: SchemeExp) = List(
//    S_0_0(prg), // should be equivalent to base analysis
    S_CS_0(prg),
//    S_2CS_0(prg),
//    S_2AcyclicCS_0(prg),
//    S_10CS_0(prg),
//    S_10AcyclicCS_0(prg), // does not yield interesting results
//    S_FA_0(prg),
//    S_2FA_0(prg), // does not improve on FA
//    S_10FA_0(prg), // does not improve on FA
//    S_CSFA_0(prg), // does not improve on FA, but we want to include them still
  )

  def main(args: Array[String]) = runBenchmarks() // check("test/primtest.scm")

  def check(benchmark: Benchmark) = {
      val txt = Reader.loadFile(benchmark)
      val prg = SchemeParser.parse(txt)
      val con = runInterpreter(prg, path).get
      val abs = runAnalysis(baseAnalysis(prg),path).get
      val allKeys = con.keys ++ abs.keys
      val interestingKeys = allKeys.filter(_.isInstanceOf[RetAddr])
      interestingKeys.foreach { k =>
          println(s"$k -> ${abs.getOrElse(k,"⊥")} ; ${con.getOrElse(k,"⊥")} ")
      }
  }

  def runBenchmarks() = {
    PrimitivesBenchmarks.benchmarks.foreach(b => {
      System.gc()
      path = b
      runBenchmark(b)
    })
    println("Results:")
    println("Benchmark & 0 & CS & 2CS & 2ACS & 10CS & 10ACS & FA & 2FA & 10FA & CSFA & Max \\\\")
    this.results.foreach { case (b, r) =>
      val refined_0_0 = r.getOrElse("0_0", Some("T")).getOrElse("T")
      val refined_CS_0 = r.getOrElse("CS_0", Some("T")).getOrElse("T")
      val refined_2CS_0 = r.getOrElse("2CS_0", Some("T")).getOrElse("T")
      val refined_2AcyclicCS_0 = r.getOrElse("2AcyclicCS_0", Some("T")).getOrElse("T")
      val refined_10CS_0 = r.getOrElse("10CS_0", Some("T")).getOrElse("T")
      val refined_10AcyclicCS_0 = r.getOrElse("10AcyclicCS_0", Some("T")).getOrElse("T")
      val refined_FA_0 = r.getOrElse("FA_0", Some("T")).getOrElse("T")
      val refined_2FA_0 = r.getOrElse("2FA_0", Some("T")).getOrElse("T")
      val refined_10FA_0 = r.getOrElse("10FA_0", Some("T")).getOrElse("T")
      val refined_CSFA_0 = r.getOrElse("CSFA_0", Some("T")).getOrElse("T")
      val concrete = r.getOrElse("concrete", Some("T")).getOrElse("T")
      println(s"$b & $refined_0_0 & $refined_CS_0 & $refined_2CS_0 & $refined_2AcyclicCS_0 & $refined_10CS_0 & $refined_10AcyclicCS_0 & $refined_FA_0 & $refined_2FA_0 & $refined_10FA_0 & $refined_CSFA_0 & $concrete\\\\")
      }
  }
}


object PrimitivesComparisonRQ3 extends PrimitivesComparison {
  def S_0_0(prg: SchemeExp) = new ModAnalysis(prg) with StandardSchemeModFSemantics
                                                   with BigStepSemantics
                                                   with CompoundSensitivities.SeparateLowHighSensitivity.S_0_0
                                                   with ConstantPropagationDomain {
    override def toString() = "0_0"
    override val primPrecision = SchemePrelude.primNames
  }
  def S_CS_0(prg: SchemeExp) = new ModAnalysis(prg) with StandardSchemeModFSemantics
                                                    with BigStepSemantics
                                                    with CompoundSensitivities.SeparateLowHighSensitivity.S_CS_0
                                                    with ConstantPropagationDomain {
    override def toString() = "CS_0"
    override val primPrecision = SchemePrelude.primNames
  }
  def S_2CS_0(prg: SchemeExp) = new ModAnalysis(prg) with StandardSchemeModFSemantics
                                                    with BigStepSemantics
                                                    with CompoundSensitivities.SeparateLowHighSensitivity.S_2CS_0
                                                    with ConstantPropagationDomain {
    override def toString() = "2CS_0"
    override val primPrecision = SchemePrelude.primNames
  }
  def S_10CS_0(prg: SchemeExp) = new ModAnalysis(prg) with StandardSchemeModFSemantics
                                                    with BigStepSemantics
                                                    with CompoundSensitivities.SeparateLowHighSensitivity.S_10CS_0
                                                    with ConstantPropagationDomain {
    override def toString() = "10CS_0"
    override val primPrecision = SchemePrelude.primNames
  }
  def S_2AcyclicCS_0(prg: SchemeExp) = new ModAnalysis(prg) with StandardSchemeModFSemantics
                                                    with BigStepSemantics
                                                    with CompoundSensitivities.SeparateLowHighSensitivity.S_2AcyclicCS_0
                                                    with ConstantPropagationDomain {
    override def toString() = "2AcyclicCS_0"
    override val primPrecision = SchemePrelude.primNames
  }
  def S_10AcyclicCS_0(prg: SchemeExp) = new ModAnalysis(prg) with StandardSchemeModFSemantics
                                                    with BigStepSemantics
                                                    with CompoundSensitivities.SeparateLowHighSensitivity.S_10AcyclicCS_0
                                                    with ConstantPropagationDomain {
    override def toString() = "10AcyclicCS_0"
    override val primPrecision = SchemePrelude.primNames
  }
  def S_FA_0(prg: SchemeExp) = new ModAnalysis(prg) with StandardSchemeModFSemantics
                                                    with BigStepSemantics
                                                    with CompoundSensitivities.SeparateLowHighSensitivity.S_FA_0
                                                    with ConstantPropagationDomain {
    override def toString() = "FA_0"
    override val primPrecision = SchemePrelude.primNames
  }
  def S_2FA_0(prg: SchemeExp) = new ModAnalysis(prg) with StandardSchemeModFSemantics
                                                     with BigStepSemantics
                                                     with CompoundSensitivities.SeparateLowHighSensitivity.S_2FA_0
                                                     with ConstantPropagationDomain {
    override def toString() = "2FA_0"
    override val primPrecision = SchemePrelude.primNames
  }
  def S_10FA_0(prg: SchemeExp) = new ModAnalysis(prg) with StandardSchemeModFSemantics
                                                    with BigStepSemantics
                                                    with CompoundSensitivities.SeparateLowHighSensitivity.S_10FA_0
                                                    with ConstantPropagationDomain {
    override def toString() = "10FA_0"
    override val primPrecision = SchemePrelude.primNames
  }
  def S_CSFA_0(prg: SchemeExp) = new ModAnalysis(prg) with StandardSchemeModFSemantics
                                                      with BigStepSemantics
                                                      with CompoundSensitivities.SeparateLowHighSensitivity.S_CSFA_0
                                                      with ConstantPropagationDomain {
    override def toString() = "CSFA_0"
    override val primPrecision = SchemePrelude.primNames
  }
}

abstract class PrimitivesComparisonRQ4 extends PrimitivesComparison {
  def isPrim(nam: Option[String]): Boolean
  override def S_0_0(prg: SchemeExp) = new ModAnalysis(prg) with StandardSchemeModFSemantics
                                                   with BigStepSemantics
                                                   with CompoundSensitivities.SeparateLowHighSensitivity.S_0_0
                                                   with ConstantPropagationDomain {
    override def toString() = "0_0"
    override def isPrimitive(nam: Option[String]): Boolean = isPrim(nam)
  }
  def S_CS_0(prg: SchemeExp) = new ModAnalysis(prg) with StandardSchemeModFSemantics
                                                    with BigStepSemantics
                                                    with CompoundSensitivities.SeparateLowHighSensitivity.S_CS_0
                                                    with ConstantPropagationDomain {
    override def toString() = "CS_0"
    override def isPrimitive(nam: Option[String]): Boolean = isPrim(nam)
  }
  def S_2CS_0(prg: SchemeExp) = new ModAnalysis(prg) with StandardSchemeModFSemantics
                                                    with BigStepSemantics
                                                    with CompoundSensitivities.SeparateLowHighSensitivity.S_2CS_0
                                                    with ConstantPropagationDomain {
    override def toString() = "2CS_0"
    override def isPrimitive(nam: Option[String]): Boolean = isPrim(nam)
  }
  def S_10CS_0(prg: SchemeExp) = new ModAnalysis(prg) with StandardSchemeModFSemantics
                                                    with BigStepSemantics
                                                    with CompoundSensitivities.SeparateLowHighSensitivity.S_10CS_0
                                                    with ConstantPropagationDomain {
    override def toString() = "10CS_0"
    override def isPrimitive(nam: Option[String]): Boolean = isPrim(nam)
  }
  def S_2AcyclicCS_0(prg: SchemeExp) = new ModAnalysis(prg) with StandardSchemeModFSemantics
                                                    with BigStepSemantics
                                                    with CompoundSensitivities.SeparateLowHighSensitivity.S_2AcyclicCS_0
                                                    with ConstantPropagationDomain {
    override def toString() = "2AcyclicCS_0"
    override def isPrimitive(nam: Option[String]): Boolean = isPrim(nam)
  }
  def S_10AcyclicCS_0(prg: SchemeExp) = new ModAnalysis(prg) with StandardSchemeModFSemantics
                                                    with BigStepSemantics
                                                    with CompoundSensitivities.SeparateLowHighSensitivity.S_10AcyclicCS_0
                                                    with ConstantPropagationDomain {
    override def toString() = "10AcyclicCS_0"
    override def isPrimitive(nam: Option[String]): Boolean = isPrim(nam)
  }
  def S_FA_0(prg: SchemeExp) = new ModAnalysis(prg) with StandardSchemeModFSemantics
                                                    with BigStepSemantics
                                                    with CompoundSensitivities.SeparateLowHighSensitivity.S_FA_0
                                                    with ConstantPropagationDomain {
    override def toString() = "FA_0"
    override def isPrimitive(nam: Option[String]): Boolean = isPrim(nam)
  }
  def S_2FA_0(prg: SchemeExp) = new ModAnalysis(prg) with StandardSchemeModFSemantics
                                                     with BigStepSemantics
                                                     with CompoundSensitivities.SeparateLowHighSensitivity.S_2FA_0
                                                     with ConstantPropagationDomain {
    override def toString() = "2FA_0"
    override def isPrimitive(nam: Option[String]): Boolean = isPrim(nam)
  }
  def S_10FA_0(prg: SchemeExp) = new ModAnalysis(prg) with StandardSchemeModFSemantics
                                                    with BigStepSemantics
                                                    with CompoundSensitivities.SeparateLowHighSensitivity.S_10FA_0
                                                    with ConstantPropagationDomain {
    override def toString() = "10FA_0"
    override def isPrimitive(nam: Option[String]): Boolean = isPrim(nam)
  }
  def S_CSFA_0(prg: SchemeExp) = new ModAnalysis(prg) with StandardSchemeModFSemantics
                                                      with BigStepSemantics
                                                      with CompoundSensitivities.SeparateLowHighSensitivity.S_CSFA_0
                                                      with ConstantPropagationDomain {
    override def toString() = "CSFA_0"
    override def isPrimitive(nam: Option[String]): Boolean = isPrim(nam)
  }
}

object PrimitivesComparisonRQ4FullPrecision extends PrimitivesComparisonRQ4 {
  def isPrim(nam: Option[String]): Boolean = true
}

object PrimitivesComparisonRQ4AnonymousFunctions extends PrimitivesComparisonRQ4 {
  def isPrim(nam: Option[String]): Boolean = nam match {
    case Some(n) => SchemePrelude.primNames.contains(n)
    case None => true /* analyze anonymous functions with high sensitivity */
  }
}

object PrimitivesComparisonRQ4NamedFunctions extends PrimitivesComparisonRQ4 {
  def isPrim(nam: Option[String]): Boolean = nam.isDefined /* analyze named functions with high sensitivity */
}
