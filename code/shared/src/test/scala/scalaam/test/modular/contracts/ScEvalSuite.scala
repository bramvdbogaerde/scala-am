package scalaam.test.modular.contracts

import scalaam.language.contracts.ScLattice.Blame
import scalaam.test.ScTests

class ScEvalSuite extends ScTests {
  trait VerifyTestBuilder {
    def named(name: String): VerifyTestBuilder
    def applied(refinements: Set[String] = Set()): VerifyTestBuilder

    /**
      * Generates a test that asserts that the result of the verification contains no blames
      */
    def safe(): Unit

    /**
      * Generates a test that asserts that the result of the verification contains a blame
      */
    def unsafe(): Unit

    def checked(f: (Set[Blame] => Unit)): Unit
  }

  case class SomeVerifyTestBuilder(var command: String) extends VerifyTestBuilder {
    private var name: String = ""
    def named(name: String): VerifyTestBuilder = {
      this.name = name
      this
    }

    def applied(refinements: Set[String] = Set()): VerifyTestBuilder = {
      this.command = s"(${this.command} OPQ)"
      this
    }

    private def analyse(f: Set[Blame] => Unit): Unit = {
      ???
    }

    def safe(): Unit = analyse { blames =>
      blames shouldEqual Set()
    }

    def unsafe(): Unit = analyse { blames =>
      assert(blames.nonEmpty)

    }
    def checked(f: (Set[Blame] => Unit)): Unit = analyse(f)
  }

  case object EmptyVerifyTestBuilder extends VerifyTestBuilder {
    def named(name: String): VerifyTestBuilder                       = this
    def applied(refinements: Set[String] = Set()): VerifyTestBuilder = this
    def safe(): Unit                                                 = ()
    def unsafe(): Unit                                               = ()
    def checked(f: (Set[Blame] => Unit)): Unit                       = ()
  }

  def verify(contract: String, expr: String): VerifyTestBuilder = {
    SomeVerifyTestBuilder(s"(mon $contract $expr)")
  }

  def verify_(_contract: String, _expr: String): VerifyTestBuilder = EmptyVerifyTestBuilder

  // An integer literal should always pass the `int?` test
  verify("int?", "5").named("flat_lit_int?").safe()

  // An opaque value can be different from an integer, so this should not be verified as safe
  verify("int?", "OPQ").named("flat_OPQ_int?").unsafe()

  // A contract from any to any should always be verified as safe
  verify("(any? ~ any?)", "(lambda (x) x)").applied().named("id_any2any").safe()

  // because we are using symbolic values with refinement sets, it should generate a blame on the domain contract
  // but not on the range contract
  verify("(int? -> int?)", "(lambda (x) x)").applied().named("id_int2int").checked { blames =>
    // TODO: check if there is a blame on the domain but not on the range
  }

  // this should be verified as safe, as the opaque value will be refined to a an even? opaque value
  verify("(int? ~ even?)", "(lambda (x) (* x 2))")
    .applied(Set("int?"))
    .named("int2even_timestwo")
    .safe()

  // With the input of 1 this recursive function can return a zero number
  verify("(any? ~ nonzero?)", "(letrec (foo (lambda (x) (if (= x 0) 1 (- (foo (- x 1)) 1)))) foo)")
    .applied()
    .unsafe()

  // The example below can only be verified if we extend our constant propagation lattice with sign information
  // the result value will always be top
  verify("(int? ~ nonzero?)", "(letrec (foo (lambda (x) (if (= x 0) 1 (+ (foo (- x 1)) 1)))) foo)")
    .applied()
    .unsafe()
}
