/*
 * Copyright 2015-2016 Ming-Ho Yee
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

package ca.uwaterloo.flix.runtime

import java.lang.reflect.Modifier

import ca.uwaterloo.flix.api._
import ca.uwaterloo.flix.language.ast.ExecutableAst._
import ca.uwaterloo.flix.language.ast._
import ca.uwaterloo.flix.util.InternalRuntimeException

object Interpreter {

  /**
    * Evaluates the given expression `exp0` under the given environment `env0`.
    */
  def eval(exp0: Expression, root: Root, env0: Map[String, AnyRef]): AnyRef = exp0 match {
    case Expression.Unit => Value.Unit
    case Expression.True => Value.True
    case Expression.False => Value.False
    case Expression.Char(lit) => Value.mkChar(lit)
    case Expression.Float32(lit) => Value.mkFloat32(lit)
    case Expression.Float64(lit) => Value.mkFloat64(lit)
    case Expression.Int8(lit) => Value.mkInt8(lit)
    case Expression.Int16(lit) => Value.mkInt16(lit)
    case Expression.Int32(lit) => Value.mkInt32(lit)
    case Expression.Int64(lit) => Value.mkInt64(lit)
    case Expression.BigInt(lit) => Value.mkBigInt(lit)
    case Expression.Str(lit) => Value.mkStr(lit)
    case Expression.Var(sym, _, loc) => env0.get(sym.toString) match {
      case None => throw InternalRuntimeException(s"Key '${sym.toString}' not found in environment: '${env0.mkString(",")}'.")
      case Some(v) => v
    }
    case Expression.Def(name, _, _) => eval(root.defs(name).exp, root, env0)
    case Expression.MkClosureDef(ref, freeVars, _, _) =>
      allocateClosure(ref, freeVars, env0)
    case Expression.ApplyDef(sym, args0, _, _) =>
      val args = evalArgs(args0, root, env0)
      Linker.link(sym, root).invoke(args.toArray)
    case Expression.ApplyTail(sym, _, args0, _, _) =>
      val args = evalArgs(args0, root, env0)
      Linker.link(sym, root).invoke(args.toArray)
    case Expression.ApplyHook(hook, args0, _, _) =>
      val args = evalArgs(args0, root, env0)
      hook match {
        case Ast.Hook.Unsafe(name, inv, _) =>
          inv(args.toArray)
      }
    case Expression.ApplyClosure(exp, args0, tpe, loc) =>
      val clo = eval(exp, root, env0).asInstanceOf[Value.Closure]
      val Value.Closure(name, bindings) = clo
      val args = evalArgs(args0, root, env0)
      val constant = root.defs(name)
      // Bindings for the capture variables are passed as arguments.
      val env1 = constant.formals.take(bindings.length).zip(bindings).foldLeft(env0) {
        case (macc, (formal, actual)) => macc + (formal.sym.toString -> actual)
      }
      // Now pass the actual arguments supplied by the caller.
      val env2 = constant.formals.drop(bindings.length).zip(args).foldLeft(env1) {
        case (macc, (formal, actual)) => macc + (formal.sym.toString -> actual)
      }
      eval(constant.exp, root, env2)
    case Expression.Unary(op, exp, _, _) => evalUnary(op, exp, root, env0)
    case Expression.Binary(op, exp1, exp2, _, _) => op match {
      case o: ArithmeticOperator => evalArithmetic(o, exp1, exp2, root, env0)
      case o: ComparisonOperator => evalComparison(o, exp1, exp2, root, env0)
      case o: LogicalOperator => evalLogical(o, exp1, exp2, root, env0)
      case o: BitwiseOperator => evalBitwise(o, exp1, exp2, root, env0)
    }
    case Expression.IfThenElse(exp1, exp2, exp3, tpe, _) =>
      val cond = cast2bool(eval(exp1, root, env0))
      if (cond) eval(exp2, root, env0) else eval(exp3, root, env0)
    case Expression.Let(sym, exp1, exp2, _, _) =>
      val newEnv = env0 + (sym.toString -> eval(exp1, root, env0))
      eval(exp2, root, newEnv)

    case Expression.LetRec(sym, exp1, exp2, _, _) => exp1 match {
      case Expression.MkClosureDef(ref, freeVars, _, _) =>
        // Allocate a circular closure.
        val closure = allocateClosure(ref, freeVars, env0)
        closure.bindings(sym.getStackOffset) = closure

        // Evaluate the body expression under the extended environment.
        val newEnv = env0 + (sym.toString -> closure)
        eval(exp2, root, newEnv)
      case _ => throw InternalRuntimeException("Non-closure letrec value.")
    }

    case Expression.Is(sym, tag, exp, _) => Value.mkBool(cast2tag(eval(exp, root, env0)).tag == tag)
    case Expression.Tag(name, tag, exp, _, _) => Value.mkTag(tag, eval(exp, root, env0))
    case Expression.Untag(sym, tag, exp, _, _) => cast2tag(eval(exp, root, env0)).value
    case Expression.Index(base, offset, _, _) => eval(base, root, env0).asInstanceOf[Array[AnyRef]](offset)
    case Expression.Tuple(elms, _, _) =>
      val array = new Array[AnyRef](elms.length)
      var i = 0
      while (i < array.length) {
        array(i) = eval(elms(i), root, env0)
        i = i + 1
      }
      array

    case Expression.Ref(exp, tpe, loc) =>
      val box = new Value.Box()
      val value = eval(exp, root, env0)
      box.setValue(value)
      box

    case Expression.Deref(exp, tpe, loc) =>
      val box = eval(exp, root, env0).asInstanceOf[Value.Box]
      box.getValue

    case Expression.Assign(exp1, exp2, tpe, loc) =>
      val box = eval(exp1, root, env0).asInstanceOf[Value.Box]
      val value = eval(exp2, root, env0)
      box.setValue(value)
      Value.Unit

    case Expression.Existential(params, exp, loc) => throw InternalRuntimeException(s"Unexpected expression: '$exp' at ${loc.source.format}.")
    case Expression.Universal(params, exp, loc) => throw InternalRuntimeException(s"Unexpected expression: '$exp' at ${loc.source.format}.")

    case Expression.NativeConstructor(constructor, args, tpe, loc) =>
      val values = evalArgs(args, root, env0)
      val arguments = values.toArray
      constructor.newInstance(arguments: _*).asInstanceOf[AnyRef]

    case Expression.NativeField(field, tpe, loc) =>
      val clazz = field.getDeclaringClass
      field.get(clazz)

    case Expression.NativeMethod(method, args, tpe, loc) =>
      val values = evalArgs(args, root, env0)
      if (Modifier.isStatic(method.getModifiers)) {
        val arguments = values.toArray
        method.invoke(null, arguments: _*)
      } else {
        val thisObj = values.head
        val arguments = values.tail.toArray
        method.invoke(thisObj, arguments: _*)
      }

    case Expression.UserError(_, loc) => throw UserException("User exception.", loc)

    case Expression.MatchError(_, loc) => throw MatchException("Non-exhaustive match expression.", loc)

    case Expression.SwitchError(_, loc) => throw SwitchException("Non-exhaustive switch expression.", loc)
  }

  /**
    * Applies the given unary operator `op` to the value of the expression `exp0` under the environment `env0`
    */
  private def evalUnary(op: UnaryOperator, exp0: Expression, root: Root, env0: Map[String, AnyRef]): AnyRef = {
    val v = eval(exp0, root, env0)
    op match {
      case UnaryOperator.LogicalNot => Value.mkBool(!cast2bool(v))
      case UnaryOperator.Plus => v // nop
      case UnaryOperator.Minus => exp0.tpe match {
        case Type.Float32 => Value.mkFloat32(-cast2float32(v))
        case Type.Float64 => Value.mkFloat64(-cast2float64(v))
        case Type.Int8 => Value.mkInt8(-cast2int8(v))
        case Type.Int16 => Value.mkInt16(-cast2int16(v))
        case Type.Int32 => Value.mkInt32(-cast2int32(v))
        case Type.Int64 => Value.mkInt64(-cast2int64(v))
        case Type.BigInt => Value.mkBigInt(cast2bigInt(v).negate)
        case _ => throw InternalRuntimeException(s"Can't apply UnaryOperator.$op to type ${exp0.tpe}.")
      }
      case UnaryOperator.BitwiseNegate => exp0.tpe match {
        case Type.Int8 => Value.mkInt8(~cast2int8(v))
        case Type.Int16 => Value.mkInt16(~cast2int16(v))
        case Type.Int32 => Value.mkInt32(~cast2int32(v))
        case Type.Int64 => Value.mkInt64(~cast2int64(v))
        case Type.BigInt => Value.mkBigInt(cast2bigInt(v).not)
        case _ => throw InternalRuntimeException(s"Can't apply UnaryOperator.$op to type ${exp0.tpe}.")
      }
    }
  }

  /**
    * Applies the given arithmetic operator `op` to the values of the two expressions `exp1` and `exp2` under the environment `env0`
    */
  private def evalArithmetic(op: ArithmeticOperator, exp1: Expression, exp2: Expression, root: Root, env0: Map[String, AnyRef]): AnyRef = {
    val v1 = eval(exp1, root, env0)
    val v2 = eval(exp2, root, env0)
    op match {
      case BinaryOperator.Plus => exp1.tpe match {
        case Type.Float32 => Value.mkFloat32(cast2float32(v1) + cast2float32(v2))
        case Type.Float64 => Value.mkFloat64(cast2float64(v1) + cast2float64(v2))
        case Type.Int8 => Value.mkInt8(cast2int8(v1) + cast2int8(v2))
        case Type.Int16 => Value.mkInt16(cast2int16(v1) + cast2int16(v2))
        case Type.Int32 => Value.mkInt32(cast2int32(v1) + cast2int32(v2))
        case Type.Int64 => Value.mkInt64(cast2int64(v1) + cast2int64(v2))
        case Type.BigInt => Value.mkBigInt(cast2bigInt(v1) add cast2bigInt(v2))
        case _ => throw InternalRuntimeException(s"Can't apply BinaryOperator.$op to type ${exp1.tpe}.")
      }
      case BinaryOperator.Minus => exp1.tpe match {
        case Type.Float32 => Value.mkFloat32(cast2float32(v1) - cast2float32(v2))
        case Type.Float64 => Value.mkFloat64(cast2float64(v1) - cast2float64(v2))
        case Type.Int8 => Value.mkInt8(cast2int8(v1) - cast2int8(v2))
        case Type.Int16 => Value.mkInt16(cast2int16(v1) - cast2int16(v2))
        case Type.Int32 => Value.mkInt32(cast2int32(v1) - cast2int32(v2))
        case Type.Int64 => Value.mkInt64(cast2int64(v1) - cast2int64(v2))
        case Type.BigInt => Value.mkBigInt(cast2bigInt(v1) subtract cast2bigInt(v2))
        case _ => throw InternalRuntimeException(s"Can't apply BinaryOperator.$op to type ${exp1.tpe}.")
      }
      case BinaryOperator.Times => exp1.tpe match {
        case Type.Float32 => Value.mkFloat32(cast2float32(v1) * cast2float32(v2))
        case Type.Float64 => Value.mkFloat64(cast2float64(v1) * cast2float64(v2))
        case Type.Int8 => Value.mkInt8(cast2int8(v1) * cast2int8(v2))
        case Type.Int16 => Value.mkInt16(cast2int16(v1) * cast2int16(v2))
        case Type.Int32 => Value.mkInt32(cast2int32(v1) * cast2int32(v2))
        case Type.Int64 => Value.mkInt64(cast2int64(v1) * cast2int64(v2))
        case Type.BigInt => Value.mkBigInt(cast2bigInt(v1) multiply cast2bigInt(v2))
        case _ => throw InternalRuntimeException(s"Can't apply BinaryOperator.$op to type ${exp1.tpe}.")
      }
      case BinaryOperator.Divide => exp1.tpe match {
        case Type.Float32 => Value.mkFloat32(cast2float32(v1) / cast2float32(v2))
        case Type.Float64 => Value.mkFloat64(cast2float64(v1) / cast2float64(v2))
        case Type.Int8 => Value.mkInt8(cast2int8(v1) / cast2int8(v2))
        case Type.Int16 => Value.mkInt16(cast2int16(v1) / cast2int16(v2))
        case Type.Int32 => Value.mkInt32(cast2int32(v1) / cast2int32(v2))
        case Type.Int64 => Value.mkInt64(cast2int64(v1) / cast2int64(v2))
        case Type.BigInt => Value.mkBigInt(cast2bigInt(v1) divide cast2bigInt(v2))
        case _ => throw InternalRuntimeException(s"Can't apply BinaryOperator.$op to type ${exp1.tpe}.")
      }
      case BinaryOperator.Modulo => exp1.tpe match {
        case Type.Float32 => Value.mkFloat32(cast2float32(v1) % cast2float32(v2))
        case Type.Float64 => Value.mkFloat64(cast2float64(v1) % cast2float64(v2))
        case Type.Int8 => Value.mkInt8(cast2int8(v1) % cast2int8(v2))
        case Type.Int16 => Value.mkInt16(cast2int16(v1) % cast2int16(v2))
        case Type.Int32 => Value.mkInt32(cast2int32(v1) % cast2int32(v2))
        case Type.Int64 => Value.mkInt64(cast2int64(v1) % cast2int64(v2))
        case Type.BigInt => Value.mkBigInt(cast2bigInt(v1) remainder cast2bigInt(v2))
        case _ => throw InternalRuntimeException(s"Can't apply BinaryOperator.$op to type ${exp1.tpe}.")
      }
      case BinaryOperator.Exponentiate => exp1.tpe match {
        case Type.Float32 => Value.mkFloat32(math.pow(cast2float32(v1), cast2float32(v2)).toFloat)
        case Type.Float64 => Value.mkFloat64(math.pow(cast2float64(v1), cast2float64(v2)))
        case Type.Int8 => Value.mkInt8(math.pow(cast2int8(v1), cast2int8(v2)).toByte)
        case Type.Int16 => Value.mkInt16(math.pow(cast2int16(v1), cast2int16(v2)).toShort)
        case Type.Int32 => Value.mkInt32(math.pow(cast2int32(v1), cast2int32(v2)).toInt)
        case Type.Int64 => Value.mkInt64(math.pow(cast2int64(v1), cast2int64(v2)).toLong)
        case _ => throw InternalRuntimeException(s"Can't apply BinaryOperator.$op to type ${exp1.tpe}.")
      }
    }
  }

  /**
    * Applies the given comparison operator `op` to the values of the two expressions `exp1` and `exp2` under the environment `env0`
    */
  private def evalComparison(op: ComparisonOperator, exp1: Expression, exp2: Expression, root: Root, env0: Map[String, AnyRef]): AnyRef = {
    val v1 = eval(exp1, root, env0)
    val v2 = eval(exp2, root, env0)
    op match {
      case BinaryOperator.Less => exp1.tpe match {
        case Type.Char => Value.mkBool(cast2char(v1) < cast2char(v2))
        case Type.Float32 => Value.mkBool(cast2float32(v1) < cast2float32(v2))
        case Type.Float64 => Value.mkBool(cast2float64(v1) < cast2float64(v2))
        case Type.Int8 => Value.mkBool(cast2int8(v1) < cast2int8(v2))
        case Type.Int16 => Value.mkBool(cast2int16(v1) < cast2int16(v2))
        case Type.Int32 => Value.mkBool(cast2int32(v1) < cast2int32(v2))
        case Type.Int64 => Value.mkBool(cast2int64(v1) < cast2int64(v2))
        case Type.BigInt => Value.mkBool((cast2bigInt(v1) compareTo cast2bigInt(v2)) < 0)
        case _ => throw InternalRuntimeException(s"Can't apply BinaryOperator.$op to type ${exp1.tpe}.")
      }
      case BinaryOperator.LessEqual => exp1.tpe match {
        case Type.Char => Value.mkBool(cast2char(v1) <= cast2char(v2))
        case Type.Float32 => Value.mkBool(cast2float32(v1) <= cast2float32(v2))
        case Type.Float64 => Value.mkBool(cast2float64(v1) <= cast2float64(v2))
        case Type.Int8 => Value.mkBool(cast2int8(v1) <= cast2int8(v2))
        case Type.Int16 => Value.mkBool(cast2int16(v1) <= cast2int16(v2))
        case Type.Int32 => Value.mkBool(cast2int32(v1) <= cast2int32(v2))
        case Type.Int64 => Value.mkBool(cast2int64(v1) <= cast2int64(v2))
        case Type.BigInt => Value.mkBool((cast2bigInt(v1) compareTo cast2bigInt(v2)) <= 0)
        case _ => throw InternalRuntimeException(s"Can't apply BinaryOperator.$op to type ${exp1.tpe}.")
      }
      case BinaryOperator.Greater => exp1.tpe match {
        case Type.Char => Value.mkBool(cast2char(v1) > cast2char(v2))
        case Type.Float32 => Value.mkBool(cast2float32(v1) > cast2float32(v2))
        case Type.Float64 => Value.mkBool(cast2float64(v1) > cast2float64(v2))
        case Type.Int8 => Value.mkBool(cast2int8(v1) > cast2int8(v2))
        case Type.Int16 => Value.mkBool(cast2int16(v1) > cast2int16(v2))
        case Type.Int32 => Value.mkBool(cast2int32(v1) > cast2int32(v2))
        case Type.Int64 => Value.mkBool(cast2int64(v1) > cast2int64(v2))
        case Type.BigInt => Value.mkBool((cast2bigInt(v1) compareTo cast2bigInt(v2)) > 0)
        case _ => throw InternalRuntimeException(s"Can't apply BinaryOperator.$op to type ${exp1.tpe}.")
      }
      case BinaryOperator.GreaterEqual => exp1.tpe match {
        case Type.Char => Value.mkBool(cast2char(v1) >= cast2char(v2))
        case Type.Float32 => Value.mkBool(cast2float32(v1) >= cast2float32(v2))
        case Type.Float64 => Value.mkBool(cast2float64(v1) >= cast2float64(v2))
        case Type.Int8 => Value.mkBool(cast2int8(v1) >= cast2int8(v2))
        case Type.Int16 => Value.mkBool(cast2int16(v1) >= cast2int16(v2))
        case Type.Int32 => Value.mkBool(cast2int32(v1) >= cast2int32(v2))
        case Type.Int64 => Value.mkBool(cast2int64(v1) >= cast2int64(v2))
        case Type.BigInt => Value.mkBool((cast2bigInt(v1) compareTo cast2bigInt(v2)) >= 0)
        case _ => throw InternalRuntimeException(s"Can't apply BinaryOperator.$op to type ${exp1.tpe}.")
      }
      case BinaryOperator.Equal => java.lang.Boolean.valueOf(Value.equal(v1, v2))
      case BinaryOperator.NotEqual => java.lang.Boolean.valueOf(!Value.equal(v1, v2))
    }
  }

  /**
    * Applies the given logical operator `op` to the values of the two expressions `exp1` and `exp2` under the environment `env0`
    */
  private def evalLogical(op: LogicalOperator, exp1: Expression, exp2: Expression, root: Root, env0: Map[String, AnyRef]): AnyRef = op match {
    case BinaryOperator.LogicalAnd =>
      if (cast2bool(eval(exp1, root, env0))) eval(exp2, root, env0) else Value.False
    case BinaryOperator.LogicalOr =>
      if (cast2bool(eval(exp1, root, env0))) Value.True else eval(exp2, root, env0)
  }

  /**
    * Applies the given bitwise operator `op` to the values of the two expressions `exp1` and `exp2` under the environment `env0`
    */
  private def evalBitwise(op: BitwiseOperator, exp1: Expression, exp2: Expression, root: Root, env0: Map[String, AnyRef]): AnyRef = {
    val v1 = eval(exp1, root, env0)
    val v2 = eval(exp2, root, env0)
    op match {
      case BinaryOperator.BitwiseAnd => exp1.tpe match {
        case Type.Int8 => Value.mkInt8(cast2int8(v1) & cast2int8(v2))
        case Type.Int16 => Value.mkInt16(cast2int16(v1) & cast2int16(v2))
        case Type.Int32 => Value.mkInt32(cast2int32(v1) & cast2int32(v2))
        case Type.Int64 => Value.mkInt64(cast2int64(v1) & cast2int64(v2))
        case Type.BigInt => Value.mkBigInt(cast2bigInt(v1) and cast2bigInt(v2))
        case _ => throw InternalRuntimeException(s"Can't apply BinaryOperator.$op to type ${exp1.tpe}.")
      }
      case BinaryOperator.BitwiseOr => exp1.tpe match {
        case Type.Int8 => Value.mkInt8(cast2int8(v1) | cast2int8(v2))
        case Type.Int16 => Value.mkInt16(cast2int16(v1) | cast2int16(v2))
        case Type.Int32 => Value.mkInt32(cast2int32(v1) | cast2int32(v2))
        case Type.Int64 => Value.mkInt64(cast2int64(v1) | cast2int64(v2))
        case Type.BigInt => Value.mkBigInt(cast2bigInt(v1) or cast2bigInt(v2))
        case _ => throw InternalRuntimeException(s"Can't apply BinaryOperator.$op to type ${exp1.tpe}.")
      }
      case BinaryOperator.BitwiseXor => exp1.tpe match {
        case Type.Int8 => Value.mkInt8(cast2int8(v1) ^ cast2int8(v2))
        case Type.Int16 => Value.mkInt16(cast2int16(v1) ^ cast2int16(v2))
        case Type.Int32 => Value.mkInt32(cast2int32(v1) ^ cast2int32(v2))
        case Type.Int64 => Value.mkInt64(cast2int64(v1) ^ cast2int64(v2))
        case Type.BigInt => Value.mkBigInt(cast2bigInt(v1) xor cast2bigInt(v2))
        case _ => throw InternalRuntimeException(s"Can't apply BinaryOperator.$op to type ${exp1.tpe}.")
      }
      case BinaryOperator.BitwiseLeftShift => exp1.tpe match {
        case Type.Int8 => Value.mkInt8(cast2int8(v1) << cast2int32(v2))
        case Type.Int16 => Value.mkInt16(cast2int16(v1) << cast2int32(v2))
        case Type.Int32 => Value.mkInt32(cast2int32(v1) << cast2int32(v2))
        case Type.Int64 => Value.mkInt64(cast2int64(v1) << cast2int32(v2))
        case Type.BigInt => Value.mkBigInt(cast2bigInt(v1) shiftLeft cast2int32(v2))
        case _ => throw InternalRuntimeException(s"Can't apply BinaryOperator.$op to type ${exp1.tpe}.")
      }
      case BinaryOperator.BitwiseRightShift => exp1.tpe match {
        case Type.Int8 => Value.mkInt8(cast2int8(v1) >> cast2int32(v2))
        case Type.Int16 => Value.mkInt16(cast2int16(v1) >> cast2int32(v2))
        case Type.Int32 => Value.mkInt32(cast2int32(v1) >> cast2int32(v2))
        case Type.Int64 => Value.mkInt64(cast2int64(v1) >> cast2int32(v2))
        case Type.BigInt => Value.mkBigInt(cast2bigInt(v1) shiftRight cast2int32(v2))
        case _ => throw InternalRuntimeException(s"Can't apply BinaryOperator.$op to type ${exp1.tpe}.")
      }
    }
  }

  /**
    * Evaluates the given list of expressions `exps` under the given environment `env` to a list of values.
    */
  private def evalArgs(exps: List[Expression], root: Root, env: Map[String, AnyRef]): List[AnyRef] = {
    exps.map(a => eval(a, root, env))
  }

  /**
    * Allocates a closure for the given reference `ref` with free variables `freeVars` under the given environment `env0`.
    */
  private def allocateClosure(ref: Expression.Def, freeVars: Array[ExecutableAst.FreeVar], env0: Map[String, AnyRef]): Value.Closure = {
    // Save the values of the free variables in the Value.Closure structure.
    // When the closure is called, these values will be provided at the beginning of the argument list.
    val bindings = new Array[AnyRef](freeVars.length)
    var i = 0
    while (i < bindings.length) {
      // A value might be absent from the the environment if it is recursively bound.
      env0.get(freeVars(i).sym.toString) match {
        case None => // Ok, value probably recursive.
        case Some(v) =>
          bindings(i) = v
      }
      i = i + 1
    }
    Value.Closure(ref.sym, bindings)
  }

  /////////////////////////////////////////////////////////////////////////////
  // Casts                                                                   //
  /////////////////////////////////////////////////////////////////////////////
  /**
    * Casts the given reference `ref` to a primitive boolean.
    */
  private def cast2bool(ref: Any): Boolean = ref match {
    case o: java.lang.Boolean => o.booleanValue()
    case _ => throw InternalRuntimeException(s"Unexpected non-bool value: '$ref'.")
  }

  /**
    * Casts the given reference `ref` to a primitive char.
    */
  private def cast2char(ref: AnyRef): Char = ref match {
    case o: java.lang.Character => o.charValue()
    case _ => throw InternalRuntimeException(s"Unexpected non-char value: '$ref'.")
  }

  /**
    * Casts the given reference `ref` to a Float32.
    */
  private def cast2float32(ref: AnyRef): Float = ref match {
    case o: java.lang.Float => o.floatValue()
    case _ => throw InternalRuntimeException(s"Unexpected non-float32 value: '$ref'.")
  }

  /**
    * Casts the given reference `ref` to a Float64.
    */
  private def cast2float64(ref: AnyRef): Double = ref match {
    case o: java.lang.Double => o.doubleValue()
    case _ => throw InternalRuntimeException(s"Unexpected non-float64 value: '$ref'.")
  }

  /**
    * Casts the given reference `ref` to an int8.
    */
  private def cast2int8(ref: AnyRef): Byte = ref match {
    case o: java.lang.Byte => o.byteValue()
    case _ => throw InternalRuntimeException(s"Unexpected non-int8 value: '$ref'.")
  }

  /**
    * Casts the given reference `ref` to an int16.
    */
  private def cast2int16(ref: AnyRef): Short = ref match {
    case o: java.lang.Short => o.shortValue()
    case _ => throw InternalRuntimeException(s"Unexpected non-int16 value: '$ref'.")
  }

  /**
    * Casts the given reference `ref` to an int32.
    */
  private def cast2int32(ref: AnyRef): Int = ref match {
    case o: java.lang.Integer => o.intValue()
    case _ => throw InternalRuntimeException(s"Unexpected non-int32 value: '$ref'.")
  }

  /**
    * Casts the given reference `ref` to an int64.
    */
  private def cast2int64(ref: AnyRef): Long = ref match {
    case o: java.lang.Long => o.longValue()
    case _ => throw InternalRuntimeException(s"Unexpected non-int64 value: '$ref'.")
  }

  /**
    * Casts the given reference `ref` to a java.math.BigInteger.
    */
  private def cast2bigInt(ref: AnyRef): java.math.BigInteger = ref match {
    case o: java.math.BigInteger => o
    case _ => throw InternalRuntimeException(s"Unexpected non-bigint value: '$ref'.")
  }

  /**
    * Casts the given reference `ref` to a tag.
    */
  private def cast2tag(ref: AnyRef): Value.Tag = ref match {
    case v: Value.Tag => v
    case _ => throw InternalRuntimeException(s"Unexpected non-tag value: '$ref'.")
  }

}
