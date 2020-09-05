package scalaam.modular.contracts

import scalaam.core.{Address, Environment}
import scalaam.language.contracts.{ScCoProductLattice, ScExp, ScIdentifier}
import scalaam.modular.{GlobalStore, ModAnalysis, ReturnValue}
import scalaam.util.benchmarks.Timeout

trait ScModSemantics
    extends ModAnalysis[ScExp]
    with ScDomain
    with GlobalStore[ScExp]
    with ReturnValue[ScExp] {

  /**
    * This method can be overrided to implement a different strategy for allocating addresses for variables
    */
  type AllocationContext
  def allocVar(id: ScIdentifier, cmp: Component): ScVarAddr[AllocationContext]

  /**
    * The environment in which the analysis is executed
    */
  type Env = Environment[Address]

  /**
    * A base environment which can be defined by implementations of this trait
    */
  def baseEnv: Env

  /**
    * The components of this analysis are functions
    */
  override type Component = this.type

  /**
    * For convience we define the `main` function as the initial component that must be analysed
    */
  override def initialComponent: Component = ???

  /**
    * Retrieves the expression from the given component
    * @param cmp the component to extract the expression from
    * @return an expression from the soft contract language
    */
  override def expr(cmp: Component): ScExp = ???

  trait IntraScAnalysis extends IntraAnalysis with GlobalStoreIntra with ReturnResultIntra {
    def view(component: Component): ScComponent

    /**
      * Compute the body of the component under analysis
      * @return the body of the component under analysis
      */
    def fnBody: ScExp = view(component) match {
      case ScMain          => program
      case Call(_, lambda) => lambda.body
    }

    /**
      * Compute the environment of the component under analysis
      * @return the body of the component under analysis
      */
    def fnEnv: Env = view(component) match {
      case ScMain       => baseEnv
      case Call(env, _) => env // TODO: extend environment with variable bindings
    }
  }

  override def intraAnalysis(component: Component): IntraScAnalysis
}