/*
 * Copyright 2018 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fix

import scalafix.v1._

import scala.meta._

class RewriteBackticks extends SemanticRule("RewriteBackticks") {
  override def fix(implicit doc: SemanticDocument): Patch =
    replaceFields(
      "org/http4s/HttpVersion." -> "HTTP/1.0" -> "HTTP_1_0",
      "org/http4s/HttpVersion." -> "HTTP/1.1" -> "HTTP_1_1",
      "org/http4s/HttpVersion." -> "HTTP/2.0" -> "HTTP_2_0",
    )

  def replaceFields(renames: ((String, String), String)*)(implicit doc: SemanticDocument): Patch =
    renames.map { case ((className, oldField), newField) =>
      doc.tree.collect {
        case t: Term.Name if t.symbol.displayName == oldField && t.symbol.owner.value == className =>
          Patch.renameSymbol(t.symbol, newField)
      }.asPatch
    }.foldLeft(Patch.empty)(_ + _)
}
