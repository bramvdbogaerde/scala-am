package scalaam.machine

import scalaam.graph._
import Graph.GraphOps
import scalaam.core._
import scalaam.util.Show

/** This is an AAM-like machine, where local continuations are used (only
  * looping continuations are pushed on the kont store), and stores are not
  * stored in the states but rather in a separate map. The continuation
  * store itself is global. */
class AAMLKSS[Exp, A <: Address, V, T](val sem: Semantics[Exp, A, V, T, Exp])(
  implicit val timestamp: Timestamp[T, Exp],
  implicit val lattice: Lattice[V])
    extends MachineAbstraction[Exp, A, V, T, Exp] {

  val Action = sem.Action

  object ControlComp extends ControlComponent[Exp, A, V]
  import ControlComp._

  object Konts extends Kontinuations[Exp, T]
  import Konts._

  object LKont {
    def empty(next: KA): LKont = LKont(List.empty, next)
  }
  case class LKont(contents: List[Frame], next: KA) extends Frame {
    def isEmpty: Boolean = contents.isEmpty
    def push(frame: Frame) = LKont(frame :: contents, next)
    def get: Option[(Frame, LKont)] = contents match {
      case head :: tail => Some((head, LKont(tail, next)))
      case Nil => None
    }
    def findKonts(kstore: Store[KA, Set[LKont]]): Set[LKont] = {
      def helper(todo: Set[KA], visited: Set[KA], acc: Set[LKont]): Set[LKont] = todo.headOption match {
          case None => acc
          case Some(HaltKontAddr) => acc + (LKont.empty(HaltKontAddr))
          case Some(a) => if (visited.contains(a)) {
            helper(todo - a, visited, acc)
          } else {
            val (todo2, acc2) = kstore.lookupDefault(a, Set.empty[LKont]).foldLeft((Set.empty[KA], Set.empty[LKont]))((localAcc, lkont) =>
              if (lkont.isEmpty) {
                (localAcc._1 + lkont.next, localAcc._2)
              } else {
                (localAcc._1, localAcc._2 + lkont)
              })
            helper(todo - a ++ todo2, visited + a, acc ++ acc2)
          }
      }
      helper(Set(next), Set(), Set())
    }
  }
  implicit val lkontShow = new Show[LKont] {
    def show(lkont: LKont) = s"lkont(${lkont.contents.mkString(",")}, lkont.next)"
  }
  implicit val lkontSetLattice = Lattice.SetLattice[LKont]

  case class State(control: Control, lkont: LKont, t: T)
      extends GraphElement with SmartHash {
    override def toString = control.toString
    override def label = toString
    override def color = if (halted) { Colors.Yellow } else {
      control match {
        case _: ControlEval => Colors.Green
        case _: ControlKont => Colors.Pink
        case _: ControlError => Colors.Red
      }
    }
    override def metadata = GraphMetadataNone
    def halted: Boolean = control match {
      case ControlEval(_, _) => false
      case ControlKont(_) => lkont.next == HaltKontAddr && lkont.isEmpty
      case ControlError(_) => true
    }

    private def integrate(actions: Set[Action.A], store: Store[A, V], kstore: Store[KA, Set[LKont]]): (Set[(State, Store[A, V])], Store[KA, Set[LKont]]) = {
      actions.foldLeft((Set.empty[(State, Store[A, V])], kstore))((acc, act) => {
        val states = acc._1
        val kstore = acc._2
        act match {
          case Action.Value(v, store) =>
            (states + ((State(ControlKont(v), lkont, Timestamp[T, Exp].tick(t)), store)), kstore)
          case Action.Push(frame, e, env, store) =>
            /* TODO: Some frames should result in a stack store push (e.g., loops), and should be handled just as StepIn is. */
            (states + ((State(ControlEval(e, env), lkont.push(frame), Timestamp[T, Exp].tick(t)), store)), kstore)
          case Action.Eval(e, env, store) =>
            (states + ((State(ControlEval(e, env), lkont, Timestamp[T, Exp].tick(t)), store)), kstore)
          case Action.StepIn(fexp, _, e, env, store) =>
            val next = KontAddr(e, t)
            (states + ((State(ControlEval(e, env), LKont.empty(next), Timestamp[T, Exp].tick(t, fexp)), store)), kstore.extend(next, Set(lkont)))
          case Action.Err(err) =>
            (states + ((State(ControlError(err), lkont, Timestamp[T, Exp].tick(t)), store)), kstore)
        }
      })
    }

    def step(store: Store[A, V], kstore: Store[KA, Set[LKont]]): (Set[(State, Store[A, V])], Store[KA, Set[LKont]]) = control match {
      case ControlEval(e, env) => integrate(sem.stepEval(e, env, store, t), store, kstore)
      case ControlKont(v) =>
        /* XXX This case should be double checked */
        lkont.get match {
          case Some((frame, rest)) =>
            /* If we have a non-empty lkont, we can pop its first element */
            this.copy(lkont = rest).integrate(sem.stepKont(v, frame, store, t), store, kstore)
          case None =>
            /* Otherwise, find the next kont that has a non-empty lkont */
            val konts = lkont.findKonts(kstore)
            konts.foldLeft((Set.empty[(State, Store[A, V])], kstore))((acc, lkont) => {
              val states = acc._1
              val kstore = acc._2
              if (lkont.isEmpty && lkont.next == HaltKontAddr) {
                if (halted) {
                  /* If this is a halted state with an empty kont, we stop. */
                  /* TODO: this case might not be necessary? */
                  acc
                } else {
                  /* The kont may be empty but we still have to evaluate something */
                  (states + ((this.copy(lkont = lkont), store)), kstore)
                }
              } else {
                this.copy(lkont = lkont).step(store, kstore) match {
                  case (states2, kstore2) => (states ++ states2, kstore2)
                }
              }
            })
        }
      case ControlError(_) => (Set(), kstore)
    }
  }

  object State {
    def inject(exp: Exp, env: Iterable[(String, A)], store: Iterable[(A, V)]): (State, Store[A, V], Store[KA, Set[LKont]]) =
      (State(ControlEval(exp, Environment.initial[A](env)), LKont.empty(HaltKontAddr), Timestamp[T, Exp].initial("")), Store.initial[A, V](store), Store.empty[KA, Set[LKont]])
    implicit val stateWithKey = new WithKey[State] {
      type K = KA
      def key(st: State) = st.lkont.next
    }
  }

  type Transition = NoTransition
  val empty = new NoTransition

  class StoreMap(val content: Map[State, Store[A, V]]) {
    def add(kv: (State, Store[A, V])): StoreMap =
      new StoreMap(if (content.contains(kv._1)) {
        content + (kv._1 -> ((content(kv._1).join(kv._2))))
      } else {
        content + kv
      })
    def apply(k: State): Store[A, V] = content(k)
  }
  object StoreMap {
    def apply(kv: (State, Store[A, V])): StoreMap =
      new StoreMap(Map(kv))
  }

  def run[G](program: Exp, timeout: Timeout.T)(implicit ev: Graph[G, State, Transition]): G = {
    import scala.language.higherKinds

    @scala.annotation.tailrec
    /* An invariant is that for all states in todo, stores(state) is defined */
    def loop[VS[_]: VisitedSet](todo: Set[State], visited: VS[State], stores: StoreMap, kstore: Store[KA, Set[LKont]], graph: G): G = {
      if (todo.isEmpty || timeout.reached) {
        graph
      } else {
        /* Frontier-based semantics */
        val (graph2, successors, stores2, kstore2) = todo.foldLeft((graph, Set.empty[State], stores, kstore))((acc, state) => {
          val (graph, successors, stores, kstore) = acc
          val (statesWithStores, kstore2) = state.step(stores(state), kstore)
          val states = statesWithStores.map(_._1)
          (/* Update the graph */
            graph.addEdges(states.map(state2 => (state, empty, state2))),
            /* Update the next worklist */
            successors ++ states,
            /* Update the store map */
            statesWithStores.foldLeft(stores)((acc, stateWithStore) =>
              acc.add(stateWithStore)),
            /* Update the kstore */
            kstore2)
        })
        if (kstore2 == kstore /* TODO: add timestamping + fastEq that only checks the timestamp */) {
          loop(successors.filter(s2 => !VisitedSet[VS].contains(visited, s2)),
            VisitedSet[VS].append(visited, todo),
            stores2, kstore2, graph2)
        } else {
          loop(successors,
            VisitedSet[VS].empty[State],
            stores2, kstore2, graph2)
        }
      }
    }
    val (state, store, kstore) = State.inject(program, sem.initialEnv, sem.initialStore)
    loop(
      Set(state),
      VisitedSet.MapVisitedSet.empty[State],
      StoreMap(state -> store),
      kstore,
      Graph[G, State, Transition].empty
    )
  }
}


