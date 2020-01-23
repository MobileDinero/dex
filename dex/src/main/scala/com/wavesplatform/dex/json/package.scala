package com.wavesplatform.dex

import com.wavesplatform.dex.settings.formatValue
import play.api.libs.json._

package object json {
  // TODO create a function with f
  // create an implementation with formatValue
  val stringAsDoubleFormat: Format[Double] = Format(
    Reads.StringReads.map(_.toDouble),
    Writes.StringWrites.contramap[Double](formatValue(_))
  )
}