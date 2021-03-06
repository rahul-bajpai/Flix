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

package ca.uwaterloo.flix.runtime

import ca.uwaterloo.flix.language.ast.{ExecutableAst, Symbol, Time}

/**
  * A class representing the minimal model.
  *
  * @param root        the abstract syntax tree of the program.
  * @param definitions the definitions in the program.
  * @param relations   the relational facts in the model.
  * @param lattices    the lattice facts in the model.
  */
class Model(root: ExecutableAst.Root,
            time: Time,
            definitions: Map[Symbol.DefnSym, () => AnyRef],
            relations: Map[Symbol.TableSym, Iterable[List[AnyRef]]],
            lattices: Map[Symbol.TableSym, Iterable[(List[AnyRef], AnyRef)]]) {

  def getRoot: ExecutableAst.Root = root

  /**
    * Returns all the benchmark functions in the program.
    */
  def getBenchmarks: Map[Symbol.DefnSym, () => AnyRef] = {
    definitions filter {
      case (sym, _) => root.defs(sym).ann.isBenchmark
    }
  }

  /**
    * Returns all the test functions in the program.
    */
  def getTests: Map[Symbol.DefnSym, () => AnyRef] = {
    definitions filter {
      case (sym, _) => root.defs(sym).ann.isTest
    }
  }

  /**
    * Returns the fully-qualified names of all relations in the program.
    */
  def getRelationNames: Set[String] = relations.keySet.map(_.toString)

  /**
    * Returns the fully-qualified names of all lattices in the program.
    */
  def getLatticeNames: Set[String] = lattices.keySet.map(_.toString)

  def getTime: Time = time

  def getConstant(sym: Symbol.DefnSym): AnyRef = definitions.get(sym) match {
    case None => null
    case Some(fn) => fn()
  }

  def getConstant(name: String): AnyRef = getConstant(Symbol.mkDefnSym(name))

  def getRelation(name: String): Iterable[List[AnyRef]] =
    getRelationOpt(name).get

  def getRelationOpt(name: String): Option[Iterable[List[AnyRef]]] =
    relations.get(Symbol.mkTableSym(name))

  def getLattice(name: String): Iterable[(List[AnyRef], AnyRef)] =
    getLatticeOpt(name).get

  def getLatticeOpt(name: String): Option[Iterable[(List[AnyRef], AnyRef)]] =
    lattices.get(Symbol.mkTableSym(name))

}