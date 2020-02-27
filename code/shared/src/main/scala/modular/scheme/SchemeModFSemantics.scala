package scalaam.modular.scheme

import scalaam.core.Identity.Position
import scalaam.core._
import scalaam.modular._
import scalaam.language.scheme._
import scalaam.language.sexp
import scalaam.util._

/**
 * Base definitions for a Scheme MODF analysis.
 */
trait SchemeModFSemantics extends ModAnalysis[SchemeExp]
                            with GlobalStore[SchemeExp]
                            with ReturnValue[SchemeExp]
                            with ContextSensitiveComponents[SchemeExp] {

  def debug(): Unit = {
    println("Dependencies")
    println("--------------------")
    for { (dep, comp) <- deps } {
      println(s"$dep -> $comp")
    }
    println("Store")
    println("--------------------")
    for { (addr, v) <- store } {
      if (addr.isInstanceOf[ComponentAddr] && !addr.asInstanceOf[ComponentAddr].addr.isInstanceOf[PrmAddr])
        println(s"$addr -> $v")
    }
  }

  //XXXXXXXXXXXXXXXXXXXX//
  // LEXICAL ADDRESSING //
  //XXXXXXXXXXXXXXXXXXXX//

  // Ensure that the program is translated to use lexical addresses first!
  override lazy val program = {
    val originalProgram = super.program

    // Set up initial environment and install the primitives in the global store.
    primitives.allPrimitives.foreach { p =>
      val addr = ComponentAddr(initialComponent, PrmAddr(p.name))
      store += (addr -> lattice.primitive(p))
    }

    val initialBindings = primitives.allPrimitives.map(_.name).toSet
    SchemeLexicalAddresser.translateProgram(originalProgram, initialBindings)
  }

  // Local addresses are simply made out of lexical information.
  trait LocalAddr extends Address { def idn(): Identity }
    case class VarAddr(id: Identifier)           extends LocalAddr {
      def printable = true;  def idn(): Identity =  id.idn
      override def toString = id.toString
    }
    case class PtrAddr[C](pos2: (Identity.Position, Identity.Position), c: C) extends LocalAddr {
      def printable = false; def idn(): Identity = Identity.none /* TODO */
      override def toString = s"@$pos2/$c"
    }
    case class PrmAddr(nam: String)              extends LocalAddr {
      def printable = true;  def idn(): Identity = Identity.none
      override def toString = s"#$nam"
    }

  //XXXXXXXXXXXXXXXXX//
  // ABSTRACT VALUES //
  //XXXXXXXXXXXXXXXXX//

  // Abstract values come from a Scala-AM Scheme lattice (a type lattice).
  type Prim = SchemePrimitive[Value, Addr]
  implicit val lattice: SchemeLattice[Value, Addr, Prim, Component]
  val primitives: SchemePrimitives[Value, Addr]

  
  //XXXXXXXXXXXXXXXXXXXXXXXXX//
  // COMPONENTS AND CONTEXTS //
  //XXXXXXXXXXXXXXXXXXXXXXXXX//

  // In ModF, components are function calls in some context.

  // This abstract class is parameterised by the choice of two types of components:
  // * A MainComponent type representing the main function of the program.
  // * A CallComponent type representing function calls. CallComponents must have a parent pointer and lambda expression, contain a context and may contain a name.
  // The MainComponent should be unique and can hence be an object. CallComponents can be created using the `newCallComponent` function.
  // All components used together with this Scheme MODF analysis should be viewable as SchemeComponents.
  implicit def view(c: Component): SchemeComponent
  trait SchemeComponent { def body: SchemeExp }
  trait MainComponent extends SchemeComponent {
    def body: SchemeExp = program
    override def toString: String = "main"
  }
  trait CallComponent extends SchemeComponent {
    // Requires a closure and a context and may contain a name.
    def nam: Option[String]
    def clo: lattice.Closure
    def ctx: ComponentContext
    // convenience accessors
    lazy val (lambda, parent) = clo
    lazy val body: SchemeExp = SchemeBody(lambda.body)
    override def toString: String = nam match {
      case None => s"λ@${lambda.idn} [${ctx.toString}]"
      case Some(name) => s"$name [${ctx.toString}]"
    }
  }

  type ComponentContent = Option[lattice.Closure]
  def content(cmp: Component) = view(cmp) match {
    case _ : MainComponent => None
    case call: CallComponent => Some(call.clo)
  }
  def context(cmp: Component) = view(cmp) match {
    case _ : MainComponent => None
    case call: CallComponent => Some(call.ctx)
  }

  /** Creates a new component, given a closure, context and an optional name. */
  def newComponent(clo: lattice.Closure, nam: Option[String], ctx: ComponentContext): Component

  /** Creates a new context given a closure, a list of argument values and the position of the call site. */
  def allocCtx(clo: lattice.Closure, args: List[Value], call: Position): ComponentContext

  //XXXXXXXXXXXXXXXXXXXXXXXXXX//
  // INTRA-COMPONENT ANALYSIS //
  //XXXXXXXXXXXXXXXXXXXXXXXXXX//

  // Extensions to the intraAnalysis.
  trait SchemeModFSemanticsIntra extends super.IntraAnalysis with GlobalStoreIntra with ReturnResultIntra {
    // variable lookup: use the global store
    protected def lookupVariable(lex: LexicalRef): Value = readAddr(resolveAddr(lex))
    protected def    setVariable(lex: LexicalRef, vlu: Value): Unit = writeAddr(resolveAddr(lex), vlu)
    protected def defineVariable( id: Identifier, vlu: Value): Unit = writeAddr(    VarAddr( id), vlu)
    // resolve a lexical address to the corresponding address in the store
    private def resolveAddr(lex: LexicalRef): Addr = lex match {
      case  LocalRef(identifier) => ComponentAddr(component,VarAddr(identifier))
      case GlobalRef(identifier) => ComponentAddr(initialComponent,VarAddr(identifier))
      case   PrimRef(      name) => ComponentAddr(initialComponent,PrmAddr(name))
      case NonLocalRef(identifier,scp) =>
        val cmp = resolveParent(component,scp)
        ComponentAddr(cmp,VarAddr(identifier))
    }
    @scala.annotation.tailrec
    private def resolveParent(cmp: Component, scp: Int): Component =
      if (scp == 0) { cmp } else resolveParent(cmp.asInstanceOf[CallComponent].parent, scp - 1)
    protected def applyFun(fexp: SchemeExp, fval: Value, args: List[(SchemeExp,Value)], cll: Position): Value =
      if(args.forall(_._2 != lattice.bottom)) {
        val fromClosures = applyClosures(fval,args, cll)
        val fromPrimitives = applyPrimitives(fexp,fval,args)
        lattice.join(fromClosures,fromPrimitives)
      } else {
        lattice.bottom
      }
    // TODO[minor]: use foldMap instead of foldLeft
    private def applyClosures(fun: Value, args: List[(SchemeExp,Value)], cll: Position): Value = {
      val arity = args.length
      val closures = lattice.getClosures(fun)
      closures.foldLeft(lattice.bottom)((acc,clo) => lattice.join(acc, clo match {
        case (clo@(SchemeLambda(prs,_,_),_), nam) if prs.length == arity =>
          val argVals = args.map(_._2)
          val context = allocCtx(clo,argVals, cll)
          val component = newComponent(clo,nam,context)
          bindArgs(component, prs, argVals)
          call(component)
        case (clo@(SchemeVarArgLambda(prs,vararg,_,_),_), nam) if prs.length < arity =>
          val (fixedArgs,varArgs) = args.splitAt(prs.length)
          val fixedArgVals = fixedArgs.map(_._2)
          val varArgVal = allocateList(varArgs)
          val context = allocCtx(clo, fixedArgVals :+ varArgVal, cll)
          val component = newComponent(clo,nam,context)
          bindArgs(component,prs,fixedArgVals)
          bindArg(component,vararg,varArgVal)
          call(component)
        case _ => lattice.bottom
      }))
    }
    protected def allocateList(elms: List[(SchemeExp,Value)]): Value = elms match {
      case Nil                => lattice.nil
      case (exp,vlu) :: rest  => allocateCons(exp)(vlu,allocateList(rest))
    }
    protected def allocateCons(pairExp: SchemeExp)(car: Value, cdr: Value): Value = {
      val pair = lattice.cons(car,cdr)
      val addr = allocAddr(PtrAddr((pairExp.idn.pos, (-1, 0)),()))
      writeAddr(addr,pair)
      lattice.pointer(addr)
    }
    // protected def append(appendExp: SchemeExp)(l1: (SchemeExp, Value), l2: (SchemeExp, Value)): Value = {
    //   val appendPrim = lattice.primitive(primitives.PrimitiveDefs.Append)
    //   applyFun(appendExp, appendPrim, List(l1,l2))
    // }
    private def bindArg(component: Component, par: Identifier, arg: Value): Unit =
      writeAddr(VarAddr(par),arg,component)
    private def bindArgs(component: Component, pars: List[Identifier], args: List[Value]): Unit =
      pars.zip(args).foreach { case (par,arg) => bindArg(component,par,arg) }

    private val allocator: SchemeAllocator[Addr] = new SchemeAllocator[Addr] {
      def pointer[C](exp: (Identity.Position, Identity.Position), c: C): Addr = allocAddr(PtrAddr(exp,c))
    }
    // TODO[minor]: use foldMap instead of foldLeft
    private def applyPrimitives(fexp: SchemeExp, fval: Value, args: List[(SchemeExp,Value)]): Value =
      lattice.getPrimitives(fval).foldLeft(lattice.bottom)((acc,prm) => lattice.join(acc,
        prm.call(fexp.idn.pos, args.map({ case (exp, arg) => (exp.idn.pos, arg) }), StoreAdapter, allocator) match {
          case MayFailSuccess((vlu,_))  => vlu
          case MayFailBoth((vlu,_),_)   => vlu
          case MayFailError(_)          => lattice.bottom
        }))
    // primitives glue code
    // TODO[maybe]: while this should be sound, it might be more precise to not immediately write every value update to the global store ...
    case object StoreAdapter extends Store[Addr,Value] {
      def lookup(a: Addr): Option[Value] = Some(readAddr(a))
      def extend(a: Addr, v: Value): Store[Addr, Value] = { writeAddr(a,v) ; this }
      // all the other operations should not be used by the primitives ...
      def content                               = throw new Exception("Operation not allowed!")
      def keys                                  = throw new Exception("Operation not allowed!")
      def restrictTo(a: Set[Addr])              = throw new Exception("Operation not allowed!")
      def forall(p: ((Addr, Value)) => Boolean) = throw new Exception("Operation not allowed!")
      def join(that: Store[Addr, Value])        = throw new Exception("Operation not allowed!")
      def subsumes(that: Store[Addr, Value])    = throw new Exception("Operation not allowed!")
    }
    // evaluation helpers
    protected def evalLiteralValue(literal: sexp.Value): Value = literal match {
      case sexp.ValueInteger(n)   => lattice.number(n)
      case sexp.ValueReal(r)      => lattice.real(r)
      case sexp.ValueBoolean(b)   => lattice.bool(b)
      case sexp.ValueString(s)    => lattice.string(s)
      case sexp.ValueCharacter(c) => lattice.char(c)
      case sexp.ValueSymbol(s)    => lattice.symbol(s)
      case sexp.ValueNil          => lattice.nil
      case _ => throw new Exception(s"Unsupported Scheme literal: $literal")
    }
    // The current component serves as the lexical environment of the closure.
    protected def newClosure(lambda: SchemeLambdaExp, name: Option[String]): Value =
      lattice.closure((lambda, component), name)

    // other helpers
    protected def conditional[M : Monoid](prd: Value, csq: => M, alt: => M): M = {
      val csqVal = if (lattice.isTrue(prd)) csq else Monoid[M].zero
      val altVal = if (lattice.isFalse(prd)) alt else Monoid[M].zero
      Monoid[M].append(csqVal,altVal)
    }
  }
}

trait StandardSchemeModFSemantics extends SchemeModFSemantics {
  // Components are just normal SchemeComponents, without any extra fancy features.
  // Hence, to view a component as a SchemeComponent, the component itself can be used.
  type Component = SchemeComponent
  implicit def view(cmp: Component): SchemeComponent = cmp

  // Definition of the initial component.
  case object Main extends MainComponent
  // Definition of call components.
  case class Call(clo: lattice.Closure, nam: Option[String], ctx: ComponentContext) extends CallComponent

  lazy val initialComponent: SchemeComponent = Main
  def newComponent(clo: lattice.Closure, nam: Option[String], ctx: ComponentContext): SchemeComponent = Call(clo,nam,ctx)
}

