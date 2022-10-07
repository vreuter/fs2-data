package fs2.data
package esp

import pattern.{ConstructorTree, Evaluator}

import cats.Functor
import cats.syntax.all._

sealed trait Guard[T] {
  def ||(that: Guard[T]): Guard[T] =
    (this, that) match {
      case (Guard.In(v1), Guard.In(v2))       => Guard.In(v1.union(v2))
      case (Guard.NotIn(v1), Guard.NotIn(v2)) => Guard.NotIn(v1.intersect(v2))
      case (_, _)                             => Guard.Or(this, that)
    }

  def &&(that: Guard[T]): Guard[T] =
    (this, that) match {
      case (Guard.In(v1), Guard.In(v2))       => Guard.In(v1.intersect(v2))
      case (Guard.NotIn(v1), Guard.NotIn(v2)) => Guard.NotIn(v1.union(v2))
      case (_, _)                             => Guard.And(this, that)
    }

  def unary_! : Guard[T] =
    this match {
      case Guard.In(vs)      => Guard.NotIn(vs)
      case Guard.NotIn(vs)   => Guard.In(vs)
      case Guard.Or(g1, g2)  => !g1 && !g2
      case Guard.And(g1, g2) => !g1 || !g2
      case Guard.Not(g)      => g
      case _                 => Guard.Not(this)
    }
}

object Guard {

  case class In[T](values: Set[T]) extends Guard[T]
  case class NotIn[T](values: Set[T]) extends Guard[T]

  case class And[T](left: Guard[T], right: Guard[T]) extends Guard[T]
  case class Or[T](left: Guard[T], right: Guard[T]) extends Guard[T]
  case class Not[T](inner: Guard[T]) extends Guard[T]

  implicit def evaluator[T]: Evaluator[Guard[T], Tag[T]] =
    new Evaluator[Guard[T], Tag[T]] {

      override def eval(guard: Guard[T], tree: ConstructorTree[Tag[T]]): Option[Tag[T]] =
        (tree, guard) match {
          case (ConstructorTree(Tag.Name(v), _), In(values)) =>
            values.contains(v).guard[Option].as(Tag.True)
          case (ConstructorTree(Tag.Value(v), _), In(values)) =>
            values.contains(v).guard[Option].as(Tag.True)
          case (ConstructorTree(Tag.Name(v), _), NotIn(values)) =>
            values.contains(v).guard[Option].fold[Option[Tag[T]]](Some(Tag.True))(_ => None)
          case (ConstructorTree(Tag.Value(v), _), NotIn(values)) =>
            values.contains(v).guard[Option].fold[Option[Tag[T]]](Some(Tag.True))(_ => None)
          case (ConstructorTree(Tag.Open | Tag.Close, List(ConstructorTree(Tag.Name(v), _))), In(values)) =>
            values.contains(v).guard[Option].as(Tag.True)
          case (ConstructorTree(Tag.Leaf, List(ConstructorTree(Tag.Value(v), _))), In(values)) =>
            values.contains(v).guard[Option].as(Tag.True)
          case (ConstructorTree(Tag.Open | Tag.Close, List(ConstructorTree(Tag.Name(v), _))), NotIn(values)) =>
            values.contains(v).guard[Option].fold[Option[Tag[T]]](Some(Tag.True))(_ => None)
          case (ConstructorTree(Tag.Leaf, List(ConstructorTree(Tag.Value(v), _))), NotIn(values)) =>
            values.contains(v).guard[Option].fold[Option[Tag[T]]](Some(Tag.True))(_ => None)
          case (_, And(l, r)) =>
            eval(l, tree) >> eval(r, tree)
          case (_, Or(l, r)) =>
            eval(l, tree).orElse(eval(r, tree))
          case (_, Not(g)) =>
            eval(g, tree).fold[Option[Tag[T]]](Some(Tag.True))(_ => None)
          case _ =>
            None
        }

    }

  implicit object functor extends Functor[Guard] {

    override def map[A, B](fa: Guard[A])(f: A => B): Guard[B] =
      fa match {
        case In(values)    => In(values.map(f))
        case NotIn(values) => NotIn(values.map(f))
        case And(l, r)     => And(map(l)(f), map(r)(f))
        case Or(l, r)      => Or(map(l)(f), map(r)(f))
        case Not(i)        => Not(map(i)(f))
      }

  }
}
