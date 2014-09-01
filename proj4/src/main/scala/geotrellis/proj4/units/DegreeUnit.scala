/*
 * Copyright (c) 2014 Azavea.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package geotrellis.proj4.units

import java.text.ParseException

object DegreeUnit {

  val format = new AngleFormat(AngleFormat.ddmmssPattern, true)

  def parse(s: String): Double = try {
    format.parse(s).doubleValue
  } catch {
    case pe: ParseException => throw new NumberFormatException(pe.getMessage)
  }

  def apply(): DegreeUnit = new DegreeUnit

}

class DegreeUnit extends Unit("degree", "degrees", "deg", 1)
