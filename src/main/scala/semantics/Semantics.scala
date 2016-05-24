import scalaz._
/**
 * This is where the interface of a language's semantics is defined. By defining
 * the semantics of a language, you get an abstract abstract machine for free
 * (but you might need to adapt existing lattices to support values from your
 * language).
 *
 * Semantics should be defined as small-step operational semantics. To define a
 * semantics, you have to implement the Semantics trait. You'll need to
 * specialize it on the type of expression of your language (e.g., for ANF,
 * ANFSemantics specializes on ANFExp). To do so, you need to define what
 * actions should be taken when:
 *   1. Evaluating an expression e (stepEval)
 *   2. Continuing evaluation when a value v has been reached (stepKont)
 *
 * To have a simple overview of how semantics should be defined, look at the
 * ANFSemantics.scala, as it defines semantics of ANF Scheme, a very lightweight
 * language. A more complex definition resides in SchemeSemantics.scala.
 */

trait Semantics[Exp, Abs, Addr, Time] {
  implicit def abs: JoinLattice[Abs]
  implicit def addr: Address[Addr]
  implicit def exp: Expression[Exp]
  implicit def time: Timestamp[Time]
  /**
   * Defines what actions should be taken when an expression e needs to be
   * evaluated, in environment env with store store
   */
  def stepEval(e: Exp, env: Environment[Addr], store: Store[Addr, Abs], t: Time): Set[Action[Exp, Abs, Addr]]
  /**
   * Defines what actions should be taken when a value v has been reached, and
   * the topmost frame is frame
   */
  def stepKont(v: Abs, frame: Frame, store: Store[Addr, Abs], t: Time): Set[Action[Exp, Abs, Addr]]

  /**
   * Defines how to parse a program
   */
  def parse(program: String): Exp

  /** Defines the elements in the initial environment */
  def initialEnv: Iterable[(String, Addr)] = List()

  /** Defines the initial store */
  def initialStore: Iterable[(Addr, Abs)] = List()
}

/**
 * The different kinds of effects that can be generated by the semantics
 */
trait EffectKind
case object WriteEffect extends EffectKind
case object ReadEffect extends EffectKind
object EffectKind {
  implicit val isMonoid: Monoid[EffectKind] = new Monoid[EffectKind] {
    def zero: EffectKind = ReadEffect
    def append(x: EffectKind, y: => EffectKind): EffectKind = x match {
      case ReadEffect => y
      case WriteEffect => WriteEffect
    }
  }
}

abstract class Effect[Addr : Address] {
  val kind: EffectKind
  val target: Addr
}

case class EffectReadVariable[Addr : Address](target: Addr)
    extends Effect[Addr] {
  val kind = ReadEffect
  override def toString = s"R$target"
}
case class EffectReadConsCar[Addr : Address](target: Addr)
    extends Effect[Addr] {
  val kind = ReadEffect
  override def toString = s"Rcar($target)"
}
case class EffectReadConsCdr[Addr : Address](target: Addr)
    extends Effect[Addr] {
  val kind = ReadEffect
  override def toString = s"Rcdr($target)"
}
case class EffectReadVector[Addr : Address](target: Addr)
    extends Effect[Addr] {
  val kind = ReadEffect
  override def toString = s"Rvec($target)"
}
case class EffectWriteVariable[Addr : Address](target: Addr)
    extends Effect[Addr] {
  val kind = WriteEffect
  override def toString = s"W$target"
}
case class EffectWriteConsCar[Addr : Address](target: Addr)
    extends Effect[Addr] {
  val kind = WriteEffect
  override def toString = s"Wcar($target)"
}
case class EffectWriteConsCdr[Addr : Address](target: Addr)
    extends Effect[Addr] {
  val kind = WriteEffect
  override def toString = s"Wcdr($target)"
}
case class EffectWriteVector[Addr : Address](target: Addr)
    extends Effect[Addr] {
  val kind = WriteEffect
  override def toString = s"Wvec($target)"
}
case class EffectAcquire[Addr : Address](target: Addr)
    extends Effect[Addr] {
  val kind = WriteEffect
  override def toString = s"Acq$target"
}
case class EffectRelease[Addr : Address](target: Addr)
    extends Effect[Addr] {
  val kind = WriteEffect
  override def toString = s"Rel$target"
}

/**
 * The different kinds of actions that can be taken by the abstract machine
 */
abstract class Action[Exp : Expression, Abs : JoinLattice, Addr : Address]
/**
 * A value is reached by the interpreter. As a result, a continuation will be
 * popped with the given reached value.
 */
case class ActionReachedValue[Exp : Expression, Abs : JoinLattice, Addr : Address]
  (v: Abs, store: Store[Addr, Abs], effects: Set[Effect[Addr]] = Set[Effect[Addr]]())
    extends Action[Exp, Abs, Addr]
/**
 * A frame needs to be pushed on the stack, and the interpretation continues by
 * evaluating expression e in environment env
 */
case class ActionPush[Exp : Expression, Abs : JoinLattice, Addr : Address]
  (e: Exp, frame: Frame, env: Environment[Addr], store: Store[Addr, Abs],
    effects: Set[Effect[Addr]] = Set[Effect[Addr]]())
    extends Action[Exp, Abs, Addr]
/**
 * Evaluation continues with expression e in environment env
 */
case class ActionEval[Exp : Expression, Abs : JoinLattice, Addr : Address]
  (e: Exp, env: Environment[Addr], store: Store[Addr, Abs],
    effects: Set[Effect[Addr]] = Set[Effect[Addr]]())
    extends Action[Exp, Abs, Addr]
/**
 * Similar to ActionEval, but only used when stepping inside a function's body
 * (clo is therefore the function stepped into). The expressions and values of
 * the arguments should also be provided, as they can be needed by the abstract
 * machine.
 */
case class ActionStepIn[Exp : Expression, Abs : JoinLattice, Addr : Address]
  (fexp: Exp, clo: (Exp, Environment[Addr]), e: Exp,
    env: Environment[Addr], store: Store[Addr, Abs], argsv: List[(Exp, Abs)],
    effects: Set[Effect[Addr]] = Set[Effect[Addr]]())
    extends Action[Exp, Abs, Addr]
/**
 * An error has been reached
 */
case class ActionError[Exp : Expression, Abs : JoinLattice, Addr : Address]
  (reason: String) extends Action[Exp, Abs, Addr]
/**
 * Spawns a new thread that evaluates expression e in environment ρ. The current
 * thread continues its execution by performing action act.
 */
case class ActionSpawn[TID : ThreadIdentifier, Exp : Expression, Abs : JoinLattice, Addr : Address]
  (t: TID, e: Exp, env: Environment[Addr], act: Action[Exp, Abs, Addr],
    effects: Set[Effect[Addr]] = Set[Effect[Addr]]())
    extends Action[Exp, Abs, Addr]
/**
 * Waits for the execution of a thread, with tid as its identifier.
 */
case class ActionJoin[Exp : Expression, Abs : JoinLattice, Addr : Address]
  (tid: Abs, store: Store[Addr, Abs], effects: Set[Effect[Addr]] = Set[Effect[Addr]]())
    extends Action[Exp, Abs, Addr]

/**
 * Base class for semantics that define some helper methods
 */
abstract class BaseSemantics[Exp : Expression, Abs : JoinLattice, Addr : Address, Time : Timestamp]
    extends Semantics[Exp, Abs, Addr, Time] {
  /* wtf scala */
  def abs = implicitly[JoinLattice[Abs]]
  def addr = implicitly[Address[Addr]]
  def exp = implicitly[Expression[Exp]]
  def time = implicitly[Timestamp[Time]]

  /**
   * Binds arguments in the environment and store. Arguments are given as a list
   * of triple, where each triple is made of:
   *   - the name of the argument
   *   - the expression evaluated to get the argument's value
   *   - the value of the argument
   */
  protected def bindArgs(l: List[(String, (Exp, Abs))], env: Environment[Addr], store: Store[Addr, Abs], t: Time): (Environment[Addr], Store[Addr, Abs]) =
    l.foldLeft((env, store))({ case ((env, store), (name, (exp, value))) => {
      val a = addr.variable(name, value, t)
      (env.extend(name, a), store.extend(a, value))
    }})
}
