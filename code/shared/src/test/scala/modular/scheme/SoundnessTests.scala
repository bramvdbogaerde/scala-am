import org.scalatest._

import scala.concurrent.duration._
import java.util.concurrent.TimeoutException

import scalaam.core._
import scalaam.util._
import scalaam.modular._
import scalaam.modular.scheme._
import scalaam.language.scheme._
import scalaam.language.scheme.SchemeInterpreter._
import scalaam.util.Timeout

trait SchemeModFSoundnessTests extends PropSpec {
  type Benchmark = String   // a benchmark is just a file name
  type Analysis = ModAnalysis[SchemeExp] with SchemeModFSemantics
  // the table of benchmark programs to execute
  def benchmarks: List[Benchmark]
  // the analysis that is used to analyse the programs
  def analysis(b: Benchmark): Analysis
  // the timeout for the analysis of a single benchmark program (default: 1min.)
  def timeout(b: Benchmark) = Timeout.duration(Duration(1, MINUTES))
  // the actual testing code
  protected def loadFile(file: String): SchemeExp = {
    val f   = scala.io.Source.fromFile(file)
    val exp = SchemeParser.parse(f.getLines().mkString("\n"))
    f.close()
    exp
  }
  private def evalConcrete(benchmark: Benchmark, t: Timeout.T): (Option[Value], Map[Position,Set[Value]]) = {
    val program = SchemeUndefiner.undefine(List(loadFile(benchmark)))
    var posResults = Map[Position,Set[Value]]().withDefaultValue(Set())
    val interpreter = new SchemeInterpreter((p, v) => posResults += (p -> (posResults(p) + v)), false)
    try {
      val endResult = interpreter.run(program, t)
      (Some(endResult), posResults)
    } catch {
      case _ : TimeoutException =>
        alert(s"Concrete evaluation for $benchmark timed out")
        (None, posResults)
      case _ : StackOverflowError =>
        alert(s"Concrete evaluation for $benchmark ran out of stack space")
        (None, posResults)
    }
  }
  private def checkSubsumption(analysis: Analysis)(v: Set[Value], abs: analysis.Value): Boolean = {
    val lat = analysis.lattice
    v.forall {
      case Value.Undefined(_)   => true
      case Value.Unbound(_)     => true
      case Value.Clo(lam, _)    => lat.getClosures(abs).exists(_._1._1.pos == lam.pos)
      case Value.Primitive(p)   => lat.getPrimitives(abs).exists(_.name == p.name)
      case Value.Str(s)         => lat.subsumes(abs, lat.string(s))
      case Value.Symbol(s)      => lat.subsumes(abs, lat.symbol(s))
      case Value.Integer(i)     => lat.subsumes(abs, lat.number(i))
      case Value.Real(r)        => lat.subsumes(abs, lat.real(r))
      case Value.Bool(b)        => lat.subsumes(abs, lat.bool(b))
      case Value.Character(c)   => lat.subsumes(abs, lat.char(c))
      case Value.Nil            => lat.subsumes(abs, lat.nil)
      case Value.Cons(_, _)     => lat.getPointerAddresses(abs).nonEmpty
      case Value.Vector(_)      => lat.getPointerAddresses(abs).nonEmpty
      case v                    => throw new Exception(s"Unknown concrete value type: $v")
    }
  }

  private def compareResult(a: Analysis, concRes: Value) = {
    val aRes = a.store(a.ReturnAddr(a.MainComponent))
    assert(checkSubsumption(a)(Set(concRes), aRes), "the end result is not sound")
  }

  private def comparePositions(a: Analysis, concPos: Map[Position,Set[Value]]) = {
    val absPos: Map[Position, a.Value] = a.store.groupBy({_._1 match {
      case a.ComponentAddr(_, addr) => addr.pos()
      case _                        => Position.none
    }}).mapValues(_.values.foldLeft(a.lattice.bottom)((x,y) => a.lattice.join(x,y)))
    concPos.foreach { case (pos,values) =>
      assert(checkSubsumption(a)(values, absPos(pos)),
            s"intermediate result at $pos is not sound: ${absPos(pos)} does not subsume $values")
    }
  }

  benchmarks.foreach { benchmark =>
    property(s"Analysis of $benchmark is sound") {
      // run the program using a concrete interpreter
      val (cResult, cPosResults) = evalConcrete(benchmark,timeout(benchmark))
      // analyze the program using a ModF analysis
      val a = analysis(benchmark)
      a.analyze(timeout(benchmark))
      // assume that the analysis finished
      // if not, cancel the test for this benchmark
      assume(a.finished, s"Analysis of $benchmark timed out")
      // check if the final result of the analysis soundly approximates the final result of concrete evaluation
      // of course, this can only be done if the
      if (cResult.isDefined) { compareResult(a, cResult.get) }
      // check if the intermediate results at various program points are soundly approximated by the analysis
      // this can be done, regardless of whether the concrete evaluation terminated succesfully or not
      comparePositions(a, cPosResults)
    }
  }
}

trait BigStepSchemeModF extends SchemeModFSoundnessTests {
  def analysis(b: Benchmark) = new ModAnalysis(loadFile(b))
                                  with BigStepSchemeModFSemantics
                                  with ConstantPropagationDomain
                                  with NoSensitivity
}

trait SmallStepSchemeModF extends SchemeModFSoundnessTests {
  def analysis(b: Benchmark) = new ModAnalysis(loadFile(b))
                                  with SmallStepSchemeModFSemantics
                                  with ConstantPropagationDomain
                                  with NoSensitivity
}

trait SimpleBenchmarks extends SchemeModFSoundnessTests {
  def benchmarks = Benchmarks.other
}

// concrete test suites to run ...
// ... for big-step semantics
class BigStepSchemeModFSoundnessTests extends SchemeModFSoundnessTests
                                         with BigStepSchemeModF
                                         with SimpleBenchmarks
// ... for small-step semantics
class SmallStepSchemeModFSoundnessTests extends SchemeModFSoundnessTests
                                           with SmallStepSchemeModF
                                           with SimpleBenchmarks