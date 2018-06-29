package com.wavesplatform.lang.v1.evaluator.ctx

import com.wavesplatform.lang.v1.compiler.Types.{CASETYPEREF, SINGLE, TYPE}

trait DefinedType {
  def name: String
  def typeRef: SINGLE
  def fields: List[(String, TYPE)]
}

case class CaseType(name: String, fields: List[(String, TYPE)]) extends DefinedType {
  lazy val typeRef = CASETYPEREF(name, fields)
}
