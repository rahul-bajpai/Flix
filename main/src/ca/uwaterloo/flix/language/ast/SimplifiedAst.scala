/*
 * Copyright 2015-2016 Magnus Madsen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.uwaterloo.flix.language.ast

import java.lang.reflect.{Constructor, Field, Method}

sealed trait SimplifiedAst

object SimplifiedAst {

  case class Root(defs: Map[Symbol.DefnSym, SimplifiedAst.Def],
                  enums: Map[Symbol.EnumSym, SimplifiedAst.Enum],
                  lattices: Map[Type, SimplifiedAst.Lattice],
                  tables: Map[Symbol.TableSym, SimplifiedAst.Table],
                  indexes: Map[Symbol.TableSym, SimplifiedAst.Index],
                  strata: List[SimplifiedAst.Stratum],
                  properties: List[SimplifiedAst.Property],
                  reachable: Set[Symbol.DefnSym],
                  time: Time) extends SimplifiedAst

  case class Constraint(cparams: List[SimplifiedAst.ConstraintParam], head: SimplifiedAst.Predicate.Head, body: List[SimplifiedAst.Predicate.Body]) extends SimplifiedAst

  case class Def(ann: Ast.Annotations, mod: Ast.Modifiers, sym: Symbol.DefnSym, formals: List[SimplifiedAst.FormalParam], exp: SimplifiedAst.Expression, isSynthetic: Boolean, tpe: Type, loc: SourceLocation) extends SimplifiedAst

  case class Enum(sym: Symbol.EnumSym, cases: Map[String, SimplifiedAst.Case], loc: SourceLocation) extends SimplifiedAst

  case class Lattice(tpe: Type, bot: SimplifiedAst.Expression, top: SimplifiedAst.Expression, leq: SimplifiedAst.Expression, lub: SimplifiedAst.Expression, glb: SimplifiedAst.Expression, loc: SourceLocation) extends SimplifiedAst

  case class Index(sym: Symbol.TableSym, indexes: Seq[Seq[Name.Ident]], loc: SourceLocation) extends SimplifiedAst

  case class Property(law: Symbol.DefnSym, defn: Symbol.DefnSym, exp: SimplifiedAst.Expression) extends SimplifiedAst

  case class Stratum(constraints: List[SimplifiedAst.Constraint]) extends SimplifiedAst

  sealed trait Table extends SimplifiedAst

  object Table {

    case class Relation(sym: Symbol.TableSym, attributes: List[SimplifiedAst.Attribute], loc: SourceLocation) extends SimplifiedAst.Table

    case class Lattice(sym: Symbol.TableSym, keys: List[SimplifiedAst.Attribute], value: SimplifiedAst.Attribute, loc: SourceLocation) extends SimplifiedAst.Table

  }

  sealed trait Expression extends SimplifiedAst {
    def tpe: Type

    def loc: SourceLocation
  }

  object Expression {

    case object Unit extends SimplifiedAst.Expression {
      final val tpe = Type.Unit
      final val loc = SourceLocation.Unknown
    }

    case object True extends SimplifiedAst.Expression {
      final val tpe = Type.Bool
      final val loc = SourceLocation.Unknown
    }

    case object False extends SimplifiedAst.Expression {
      final val tpe = Type.Bool
      final val loc = SourceLocation.Unknown
    }

    case class Char(lit: scala.Char) extends SimplifiedAst.Expression {
      final val tpe = Type.Char
      final val loc = SourceLocation.Unknown
    }

    case class Float32(lit: scala.Float) extends SimplifiedAst.Expression {
      final val tpe = Type.Float32
      final val loc = SourceLocation.Unknown
    }

    case class Float64(lit: scala.Double) extends SimplifiedAst.Expression {
      final val tpe = Type.Float64
      final val loc = SourceLocation.Unknown
    }

    case class Int8(lit: scala.Byte) extends SimplifiedAst.Expression {
      final val tpe = Type.Int8
      final val loc = SourceLocation.Unknown
    }

    case class Int16(lit: scala.Short) extends SimplifiedAst.Expression {
      final val tpe = Type.Int16
      final val loc = SourceLocation.Unknown
    }

    case class Int32(lit: scala.Int) extends SimplifiedAst.Expression {
      final val tpe = Type.Int32
      final val loc = SourceLocation.Unknown
    }

    case class Int64(lit: scala.Long) extends SimplifiedAst.Expression {
      final val tpe = Type.Int64
      final val loc = SourceLocation.Unknown
    }

    case class BigInt(lit: java.math.BigInteger) extends SimplifiedAst.Expression {
      final val tpe = Type.BigInt
      final val loc = SourceLocation.Unknown
    }

    case class Str(lit: java.lang.String) extends SimplifiedAst.Expression {
      final val tpe = Type.Str
      final val loc = SourceLocation.Unknown
    }

    case class Var(sym: Symbol.VarSym, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class Def(sym: Symbol.DefnSym, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class Lambda(args: List[SimplifiedAst.FormalParam], body: SimplifiedAst.Expression, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class Hook(hook: Ast.Hook, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class MkClosure(lambda: SimplifiedAst.Expression.Lambda, freeVars: List[FreeVar], tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class MkClosureDef(ref: SimplifiedAst.Expression.Def, freeVars: List[FreeVar], tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class ApplyDef(sym: Symbol.DefnSym, args: List[SimplifiedAst.Expression], tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class ApplyTail(sym: Symbol.DefnSym, formals: List[SimplifiedAst.FormalParam], actuals: List[SimplifiedAst.Expression], tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class ApplyHook(hook: Ast.Hook, args: List[SimplifiedAst.Expression], tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class Apply(exp: SimplifiedAst.Expression, args: List[SimplifiedAst.Expression], tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class Unary(op: UnaryOperator, exp: SimplifiedAst.Expression, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class Binary(op: BinaryOperator, exp1: SimplifiedAst.Expression, exp2: SimplifiedAst.Expression, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class IfThenElse(exp1: SimplifiedAst.Expression, exp2: SimplifiedAst.Expression, exp3: SimplifiedAst.Expression, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class Let(sym: Symbol.VarSym, exp1: SimplifiedAst.Expression, exp2: SimplifiedAst.Expression, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    // NB: After lambda lifting and closure conversion `exp1` is guaranteed to be a MkClosureDef.
    case class LetRec(sym: Symbol.VarSym, exp1: SimplifiedAst.Expression, exp2: SimplifiedAst.Expression, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class Is(sym: Symbol.EnumSym, tag: String, exp: SimplifiedAst.Expression, loc: SourceLocation) extends SimplifiedAst.Expression {
      final val tpe: Type = Type.Bool
    }

    case class Tag(sym: Symbol.EnumSym, tag: String, exp: SimplifiedAst.Expression, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class Untag(sym: Symbol.EnumSym, tag: String, exp: SimplifiedAst.Expression, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class Index(base: SimplifiedAst.Expression, offset: scala.Int, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class Tuple(elms: List[SimplifiedAst.Expression], tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class Ref(exp: SimplifiedAst.Expression, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class Deref(exp: SimplifiedAst.Expression, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class Assign(exp1: SimplifiedAst.Expression, exp2: SimplifiedAst.Expression, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class Existential(fparam: SimplifiedAst.FormalParam, exp: SimplifiedAst.Expression, loc: SourceLocation) extends SimplifiedAst.Expression {
      def tpe: Type = Type.Bool
    }

    case class Universal(fparam: SimplifiedAst.FormalParam, exp: SimplifiedAst.Expression, loc: SourceLocation) extends SimplifiedAst.Expression {
      def tpe: Type = Type.Bool
    }

    case class NativeConstructor(constructor: Constructor[_], args: List[SimplifiedAst.Expression], tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class NativeField(field: Field, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class NativeMethod(method: Method, args: List[SimplifiedAst.Expression], tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class UserError(tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class MatchError(tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

    case class SwitchError(tpe: Type, loc: SourceLocation) extends SimplifiedAst.Expression

  }

  sealed trait Pattern extends SimplifiedAst

  object Pattern {

    case class Wild(tpe: Type, loc: SourceLocation) extends SimplifiedAst.Pattern

    case class Var(sym: Symbol.VarSym, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Pattern

    case class Unit(loc: SourceLocation) extends SimplifiedAst.Pattern

    case class True(loc: SourceLocation) extends SimplifiedAst.Pattern

    case class False(loc: SourceLocation) extends SimplifiedAst.Pattern

    case class Char(lit: scala.Char, loc: SourceLocation) extends SimplifiedAst.Pattern

    case class Float32(lit: scala.Float, loc: SourceLocation) extends SimplifiedAst.Pattern

    case class Float64(lit: scala.Double, loc: SourceLocation) extends SimplifiedAst.Pattern

    case class Int8(lit: scala.Byte, loc: SourceLocation) extends SimplifiedAst.Pattern

    case class Int16(lit: scala.Short, loc: SourceLocation) extends SimplifiedAst.Pattern

    case class Int32(lit: scala.Int, loc: SourceLocation) extends SimplifiedAst.Pattern

    case class Int64(lit: scala.Long, loc: SourceLocation) extends SimplifiedAst.Pattern

    case class BigInt(lit: java.math.BigInteger, loc: SourceLocation) extends SimplifiedAst.Pattern

    case class Str(lit: java.lang.String, loc: SourceLocation) extends SimplifiedAst.Pattern

    case class Tag(sym: Symbol.EnumSym, tag: String, pat: SimplifiedAst.Pattern, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Pattern

    case class Tuple(elms: List[SimplifiedAst.Pattern], tpe: Type, loc: SourceLocation) extends SimplifiedAst.Pattern

  }

  sealed trait Predicate extends SimplifiedAst {
    def loc: SourceLocation
  }

  object Predicate {

    sealed trait Head extends SimplifiedAst.Predicate

    object Head {

      case class True(loc: SourceLocation) extends SimplifiedAst.Predicate.Head

      case class False(loc: SourceLocation) extends SimplifiedAst.Predicate.Head

      case class Positive(sym: Symbol.TableSym, terms: List[SimplifiedAst.Term.Head], loc: SourceLocation) extends SimplifiedAst.Predicate.Head

      case class Negative(sym: Symbol.TableSym, terms: List[SimplifiedAst.Term.Head], loc: SourceLocation) extends SimplifiedAst.Predicate.Head

    }

    sealed trait Body extends SimplifiedAst.Predicate

    object Body {

      case class Positive(sym: Symbol.TableSym, terms: List[SimplifiedAst.Term.Body], loc: SourceLocation) extends SimplifiedAst.Predicate.Body

      case class Negative(sym: Symbol.TableSym, terms: List[SimplifiedAst.Term.Body], loc: SourceLocation) extends SimplifiedAst.Predicate.Body

      case class Filter(sym: Symbol.DefnSym, terms: List[SimplifiedAst.Term.Body], loc: SourceLocation) extends SimplifiedAst.Predicate.Body

      case class Loop(sym: Symbol.VarSym, term: SimplifiedAst.Term.Head, loc: SourceLocation) extends SimplifiedAst.Predicate.Body

    }

  }

  object Term {

    sealed trait Head extends SimplifiedAst

    object Head {

      case class Var(sym: Symbol.VarSym, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Term.Head

      case class Lit(lit: SimplifiedAst.Expression, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Term.Head

      case class App(sym: Symbol.DefnSym, args: List[Symbol.VarSym], tpe: Type, loc: SourceLocation) extends SimplifiedAst.Term.Head

    }

    sealed trait Body extends SimplifiedAst

    object Body {

      case class Wild(tpe: Type, loc: SourceLocation) extends SimplifiedAst.Term.Body

      case class Var(sym: Symbol.VarSym, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Term.Body

      case class Lit(exp: SimplifiedAst.Expression, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Term.Body

      case class Pat(pat: SimplifiedAst.Pattern, tpe: Type, loc: SourceLocation) extends SimplifiedAst.Term.Body

    }

  }

  case class Attribute(name: String, tpe: Type) extends SimplifiedAst

  case class Case(enum: Name.Ident, tag: Name.Ident, tpe: Type) extends SimplifiedAst

  sealed trait ConstraintParam

  object ConstraintParam {

    case class HeadParam(sym: Symbol.VarSym, tpe: Type, loc: SourceLocation) extends SimplifiedAst.ConstraintParam

    case class RuleParam(sym: Symbol.VarSym, tpe: Type, loc: SourceLocation) extends SimplifiedAst.ConstraintParam

  }

  case class FormalParam(sym: Symbol.VarSym, mod: Ast.Modifiers, tpe: Type, loc: SourceLocation) extends SimplifiedAst

  case class FreeVar(sym: Symbol.VarSym, tpe: Type) extends SimplifiedAst

}
