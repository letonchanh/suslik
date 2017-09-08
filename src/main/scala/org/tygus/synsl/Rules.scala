package org.tygus.synsl

import org.tygus.synsl.LanguageUtils.generateFreshVar
import org.tygus.synsl.language.Expressions.{Expr, Var}
import org.tygus.synsl.language.Statements
import org.tygus.synsl.logic.Specifications

/**
  * An implementation of a rule for synthesis
  *
  * @author Ilya Sergey
  */

trait Rules {

  import Specifications._
  import Statements._

  type Pre = Assertion
  type Post = Assertion

  // A continuation for synthesizing the "larger" statement from substatement
  type StmtProducer = Seq[Statement] => Statement

  abstract sealed class RuleResult
  /**
    * A result of an inductive rule application:
    * - a sequence of subgoals for the engine
    * - a producer, continuation that will combine their results into the final statement
    * the empty list of subgoals paired with an identity procuder would mean an end of synthesis
    */
  case class MoreGoals(goals: Seq[Spec], kont: StmtProducer) extends RuleResult

  /**
    * A result of a rule with no sub-goals: just return the last statement
    */
  case class LastStatement(st: Statement) extends RuleResult

  /**
    * A generic class for a deductive rule to be applied
    */
  abstract sealed class Rule {
    // Is this rule applicable at all?
    def isApplicable(spec: Spec): Boolean

    // Apply the rule and get the subgoals
    def apply(spec: Spec): RuleResult
  }

  ///////////////////////////////////////////////////////////////////
  ///////////              Specific rules                     ///////
  ///////////////////////////////////////////////////////////////////

  /*
  Empty rule: supposed to be applied at the end

                  P |- Q
      ---------------------------------------- [emp]
      Γ ; { true; P } ; { true; Q } ---> return;
  */

  object EmpRule extends Rule {

    override def toString: Ident = "[emp]"

    def isApplicable(spec: Spec): Boolean = {
      // TODO: Ignore the pure parts for now
      // TODO: Replace this by a general entailment checker

      spec.pre.sigma |- spec.post.sigma
    }

    def apply(spec: Spec): RuleResult = {
      // TODO: add value-returning statements

      LastStatement(Return(None))
    }
  }


  /*
  Read rule: create a fresh typed read

        y is fresh   Γ,y ; [y/A]{ φ ; x -> A * P } ; [y/A]{ ψ ; Q } ---> S
      ---------------------------------------------------------------------- [read]
              Γ ; { φ ; x -> A * P } ; { ψ ; Q } ---> let y := *x ; S
  */
  object ReadRule extends Rule {

    def isGhostHeaplet(spec: Spec): SFormula => Boolean = {
      case PointsTo(id, offset, a@(Var(_))) => spec.isGhost(a)
      case _ => false
    }

    override def toString: Ident = "[read]"

    def isApplicable(spec: Spec): Boolean = {
      val hs = spec.pre.sigma.findSubFormula(isGhostHeaplet(spec))
      if (hs.isEmpty) return false
      val PointsTo(_, _, a@(Var(_))) = hs.head
      val tpy = spec.getType(a)
      tpy.nonEmpty
    }

    def apply(spec: Spec): RuleResult = {
      assert(isApplicable(spec), s"The rule [read] is not applicable for the spec ${spec.pp}")

      val Spec(pre, post, gamma: Gamma) = spec
      val ghostHeaplets = spec.pre.sigma.findSubFormula(isGhostHeaplet(spec)).toList
      assert(ghostHeaplets.nonEmpty)
      val PointsTo(x, offset, a@(Var(_))) = ghostHeaplets.head
      val y = generateFreshVar(spec)

      assert(spec.getType(a).nonEmpty, s"Cannot derive a type for the ghost variable $a in spec ${spec.pp}")
      val tpy = spec.getType(a).get

      val subGoalSpec = Spec(pre.subst(a, y), post.subst(a, y), (tpy, y) :: gamma.toList)
      val kont: StmtProducer = smts => {
        assert(smts.nonEmpty, s"The rest of the program is empty")
        val rest = smts.head
        // Do not generate read for unused variables
        if (rest.usedVars.contains(y)) Load(y, tpy, Var(x), offset, rest) else rest
      }

      MoreGoals(Seq(subGoalSpec), kont)
    }
  }

  /*
  Write rule: create a new write from where it's possible

                      Γ ; { φ ; P } ; { ψ ; Q } ---> S
      -------------------------------------------------------------- [write]
       Γ ; { φ ; x -> e1 * P } ; { ψ ; x -> e2 * Q } ---> *x := e2 ; S
   */
  object WriteRule extends Rule {

    override def toString: Ident = "[write]"

    def findHeapletFor(x: Ident, off: Int, spec: Spec): SFormula => Boolean = {
      case PointsTo(y, off1, e2) =>
        x == y && off == off1 && spec.isConcrete(Var(x)) &&
            e2.vars.forall(v => spec.isConcrete(v))
      case _ => false
    }

    def isApplicable(spec: Spec): Boolean = {
      // Pre-heaplet from a canonical form
      val hs1 = spec.pre.sigma.findSubFormula(_.isInstanceOf[PointsTo])
      if (hs1.isEmpty) return false
      val PointsTo(x, o, _) = hs1.head.asInstanceOf[PointsTo]
      val hs2 = spec.post.sigma.findSubFormula(findHeapletFor(x, o, spec))

      assert(hs2.size <= 1, s"Post-condition is inconsistent:\n${spec.pp}")
      hs2.nonEmpty
    }

    def apply(spec: Spec): RuleResult = {
      assert(isApplicable(spec), s"The rule [write] is not applicable for the spec ${spec.pp}.")
      val Spec(pre, post, gamma: Gamma) = spec

      val hs1 = spec.pre.sigma.findSubFormula(_.isInstanceOf[PointsTo])
      val h1@PointsTo(x, ox, _) = hs1.head.asInstanceOf[PointsTo]
      val hs2 = spec.post.sigma.findSubFormula(findHeapletFor(x, ox, spec))
      val h2@PointsTo(_, _, e2: Expr) = hs2.head

      assert(e2.vars.forall(v => spec.isConcrete(v)),
        s"Expression ${e2.pp} contains uninstantiated ghost variables in the spec ${spec.pp}.")

      val subGoalSpec = Spec(pre.removeSubformula(_ == h1), post.removeSubformula(_ == h2), gamma)
      val kont: StmtProducer = smts => {
        assert(smts.nonEmpty, s"The rest of the program is empty")
        val rest = smts.head
        Store(Var(x), ox, e2, rest)
      }

      MoreGoals(Seq(subGoalSpec), kont)
    }

  }

  /*
  Frame rule: reduce the size of the specification
  TODO: generalize from just heaplets

        (GV(Q) / GV(P)) ∪ GV(R) = Ø
      Γ ; { φ ; P } ; { ψ ; Q } ---> S
    ---------------------------------------- [frame]
    Γ ; { φ ; P * R } ; { ψ ; Q * R } ---> S

   */
  object FrameRule extends Rule {

    override def toString: Ident = "[frame]"

    def isApplicable(spec: Spec): Boolean = {
      // Pre-heaplet from a canonical form
      val h1 = spec.pre.sigma.simpl.canonicalize.getHeadHeaplet
      // Post-heaplet from a canonical form
      val h2 = spec.post.sigma.simpl.canonicalize.getHeadHeaplet
      (h1, h2) match {
        case (Some(p1@PointsTo(x, o1, e1)), Some(p2@PointsTo(y, o2, e2)))
          if p1 == p2 => true
        case _ => false
      }
    }

    def apply(spec: Spec): RuleResult = {
      assert(isApplicable(spec), s"The rule [frame] is not applicable for the spec ${spec.pp}.")

      val Spec(pre, post, gamma: Gamma) = spec
      val Some(p1) = pre.sigma.simpl.canonicalize.getHeadHeaplet
      val Some(p2) = post.sigma.simpl.canonicalize.getHeadHeaplet

      assert(p1 == p2,
        s"Pre/posts have different head heaplets in the spec ${spec.pp}.")

      val subGoalSpec = Spec(pre.removeSubformula(_ == p1), post.removeSubformula(_ == p2), gamma)
      val kont: StmtProducer = smts => {
        assert(smts.nonEmpty, s"The rest of the program is empty")
        smts.head
      }

      MoreGoals(Seq(subGoalSpec), kont)
    }

  }


}
