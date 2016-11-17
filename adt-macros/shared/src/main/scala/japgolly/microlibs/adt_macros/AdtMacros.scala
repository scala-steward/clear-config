package japgolly.microlibs.adt_macros

import scala.reflect.macros.blackbox
import japgolly.microlibs.macro_utils.MacroUtils
import japgolly.microlibs.nonempty.{NonEmptySet, NonEmptyVector}
import AdtMacros.{AdtIso, AdtIsoSet}

object AdtMacros {

  type AdtIso[Adt, T] = (Adt => T, T => Adt, NonEmptyVector[Adt], NonEmptyVector[T])
  def adtIso[Adt, T](f: Adt => T): AdtIso[Adt, T] = macro AdtMacros.quietAdtIso[Adt, T]
  def _adtIso[Adt, T](f: Adt => T): AdtIso[Adt, T] = macro AdtMacros.debugAdtIso[Adt, T]

  type AdtIsoSet[Adt, T] = (Adt => T, T => Adt, NonEmptySet[Adt], NonEmptySet[T])
  def adtIsoSet[Adt, T](f: Adt => T): AdtIsoSet[Adt, T] = macro AdtMacros.quietAdtIsoSet[Adt, T]
  def _adtIsoSet[Adt, T](f: Adt => T): AdtIsoSet[Adt, T] = macro AdtMacros.debugAdtIsoSet[Adt, T]

  def adtValues[T]: NonEmptyVector[T] = macro AdtMacros.quietAdtValues[T]
  def _adtValues[T]: NonEmptyVector[T] = macro AdtMacros.debugAdtValues[T]

  /** Because sometimes order matters. */
  def adtValuesManual[T](vs: T*): NonEmptyVector[T] = macro AdtMacros.quietAdtValuesManual[T]
  def _adtValuesManual[T](vs: T*): NonEmptyVector[T] = macro AdtMacros.debugAdtValuesManual[T]

  def valuesForAdt[T, V](f: T => V): NonEmptyVector[V] = macro AdtMacros.quietValuesForAdt[T, V]
  def _valuesForAdt[T, V](f: T => V): NonEmptyVector[V] = macro AdtMacros.debugValuesForAdt[T, V]

  def valuesForAdtF[T, V](f: T => V): (NonEmptyVector[V], T => V) = macro AdtMacros.quietValuesForAdtF[T, V]
  def _valuesForAdtF[T, V](f: T => V): (NonEmptyVector[V], T => V) = macro AdtMacros.debugValuesForAdtF[T, V]
}

class AdtMacros(val c: blackbox.Context) extends MacroUtils with JapgollyAccess {
  import c.universe._

  def quietAdtIso[Adt: c.WeakTypeTag, T: c.WeakTypeTag](f: c.Expr[Adt => T]): c.Expr[AdtIso[Adt, T]] = implAdtIso(false)(f)
  def debugAdtIso[Adt: c.WeakTypeTag, T: c.WeakTypeTag](f: c.Expr[Adt => T]): c.Expr[AdtIso[Adt, T]] = implAdtIso(true)(f)
  def implAdtIso[Adt: c.WeakTypeTag, T: c.WeakTypeTag](debug: Boolean)(f: c.Expr[Adt => T]): c.Expr[AdtIso[Adt, T]] = {
    val Adt       = weakTypeOf[Adt]
    val T         = weakTypeOf[T]
    val fromFn    = readMacroArg_tToTree(f).toStream
    val adtTypes  = findConcreteTypesNE(Adt, LeavesOnly)
    var toCases   = Vector.empty[CaseDef]
    var toValues  = Set.empty[Any]
    var adtValues = Vector.empty[Tree]

    for (adtClass <- adtTypes) {
      val adt = determineAdtType(Adt, adtClass)

      ensureConcrete(adt)
      if (primaryConstructorParams(adt).nonEmpty)
        fail(s"$adt requires constructor params.")

      val matchingCases = fromFn.filter(adt <:< _._1.fold(_.tpe, identity))
      if (matchingCases.size != 1)
        fail(s"Found ${matchingCases.size} cases for $adt.")

      val fromCase = matchingCases.head
      val toValue = fromCase._2 match {
        case Literal(Constant(v)) => v
        case x => fail(s"Expected a constant literal, got: ${showRaw(x)} ")
      }
      if (toValues contains toValue)
        fail(s"Non-unique value encountered: $toValue")
      toValues += toValue

      val adtObj = toSelectFQN(adtClass)
      adtValues :+= adtObj
      toCases :+= cq"${fromCase._2} => $adtObj"
    }

    val impl = q"""
      val from: $Adt => $T = $f
      val to: $T => $Adt = {case ..$toCases}
      val adts = $SelectNonEmptyVector.varargs[$Adt](..$adtValues)
      val tos = $SelectNonEmptyVector.varargs[$T](..${fromFn.map(_._2)})
      assert(adts.length == tos.length)
      (from,to,adts,tos)
    """

    if (debug) println("\n" + showCode(impl) + "\n")
    c.Expr[AdtIso[Adt, T]](impl)
  }

  def quietAdtIsoSet[Adt: c.WeakTypeTag, T: c.WeakTypeTag](f: c.Expr[Adt => T]): c.Expr[AdtIsoSet[Adt, T]] = implAdtIsoSet(false)(f)
  def debugAdtIsoSet[Adt: c.WeakTypeTag, T: c.WeakTypeTag](f: c.Expr[Adt => T]): c.Expr[AdtIsoSet[Adt, T]] = implAdtIsoSet(true)(f)
  def implAdtIsoSet[Adt: c.WeakTypeTag, T: c.WeakTypeTag](debug: Boolean)(f: c.Expr[Adt => T]): c.Expr[AdtIsoSet[Adt, T]] = {
    val Adt    = weakTypeOf[Adt]
    val T      = weakTypeOf[T]
    val adtIso = implAdtIso[Adt, T](false)(f)
    val impl = q"""
      val (from,to,adtVec,toVec) = $adtIso
      val adtSet = adtVec.toNES[$Adt]
      val toSet = toVec.toNES[$T]
      assert(adtSet.forall(a => to(from(a)) == a))
      (from,to,adtSet,toSet)
    """
    if (debug) println("\n" + showCode(impl) + "\n")
    c.Expr[AdtIsoSet[Adt, T]](impl)
  }

  def quietAdtValuesManual[T: c.WeakTypeTag](vs: c.Expr[T]*): c.Expr[NonEmptyVector[T]] = implAdtValuesManual(false)(vs)
  def debugAdtValuesManual[T: c.WeakTypeTag](vs: c.Expr[T]*): c.Expr[NonEmptyVector[T]] = implAdtValuesManual(true )(vs)
  def implAdtValuesManual[T: c.WeakTypeTag](debug: Boolean)(vs: Seq[c.Expr[T]]): c.Expr[NonEmptyVector[T]] = {
    val T = weakTypeOf[T]

    val all = findConcreteTypesNE(T, LeavesOnly).map(_.fullName)
    if (all.isEmpty)
      fail(s"At least one concrete subtype of $T required.")

    var unseen = all

    /*
    v.tree match {
      case Select(This(_), name) => name isn't the FQN :(
      case t => fail("Don't know how to interpret " + showRaw(t))
    */
    val hack = """^Select\([^,]+, ([^,)]+)\)$""".r

    for (v <- vs)
      showRaw(v.tree) match {
        case hack(name) =>
          if (!all.contains(name))
            fail(s"'$name' isn't part of legal values: $all")
          if (!unseen.contains(name))
            fail(s"Duplicate value: '$name'")
          unseen -= name

        case x => fail("Don't know how to interpret " + x)
      }

    if (unseen.nonEmpty)
      fail("Not all value accounted for: " + unseen.map("\n  - " + _).mkString)

    if (vs.size != all.size)
      fail(s"Expected ${all.size} values, got ${vs.size}.")

    val addValues = vs.map(v => q"b += $v")

    val impl =
      q"""
         val b = _root_.scala.collection.immutable.Vector.newBuilder[$T]
         ..$addValues
         $SelectNonEmptyVector.force(b.result())
       """

    if (debug) println("\n" + showCode(impl) + "\n")
    c.Expr[NonEmptyVector[T]](impl)
  }

  def quietAdtValues[T: c.WeakTypeTag]: c.Expr[NonEmptyVector[T]] = implAdtValues(false)
  def debugAdtValues[T: c.WeakTypeTag]: c.Expr[NonEmptyVector[T]] = implAdtValues(true)
  def implAdtValues[T: c.WeakTypeTag](debug: Boolean): c.Expr[NonEmptyVector[T]] = {
    val T     = weakTypeOf[T]
    val types = deterministicOrderC(findConcreteTypesNE(T, LeavesOnly))
    if (types.isEmpty)
      fail(s"At least one concrete subtype of $T required.")

    val values = types.iterator.map { cs =>
      if (cs.isModuleClass)
        toSelectFQN(cs)
      else
        fail(s"Case object expected. Found: $cs")
    }.toList

    val impl = q"$SelectNonEmptyVector.varargs[$T](..$values)"

    if (debug) println("\n" + showCode(impl) + "\n")
    c.Expr[NonEmptyVector[T]](impl)
  }

  def quietValuesForAdt[T: c.WeakTypeTag, V: c.WeakTypeTag](f: c.Expr[T => V]): c.Expr[NonEmptyVector[V]] = implValuesForAdt(false)(f)
  def debugValuesForAdt[T: c.WeakTypeTag, V: c.WeakTypeTag](f: c.Expr[T => V]): c.Expr[NonEmptyVector[V]] = implValuesForAdt(true)(f)
  def implValuesForAdt[T: c.WeakTypeTag, V: c.WeakTypeTag](debug: Boolean)(f: c.Expr[T => V]): c.Expr[NonEmptyVector[V]] = {
    val T       = weakTypeOf[T]
    val V       = weakTypeOf[V]
    val valueFn = readMacroArg_tToTree(f)
    val values  = valueFn.map(_._2)

    val types = findConcreteAdtTypesNE(T, LeavesOnly)
    if (types.isEmpty)
      fail(s"At least one concrete subtype of $T required.")

    val unseen = types.filterNot(t => valueFn.exists(t <:< _._1.fold(_.tpe, identity)))
    if (unseen.nonEmpty)
      fail(s"The following types are unaccounted for: ${unseen.mkString(", ")}")

    val impl = q"$SelectNonEmptyVector.varargs[$V](..$values)"

    if (debug) println("\n" + showCode(impl) + "\n")

    if (values.size != types.size)
      fail(s"Expected ${types.size} values, got ${values.size}.")

    c.Expr[NonEmptyVector[V]](impl)
  }

  def quietValuesForAdtF[T: c.WeakTypeTag, V: c.WeakTypeTag](f: c.Expr[T => V]): c.Expr[(NonEmptyVector[V], T => V)] = implValuesForAdtF(false)(f)
  def debugValuesForAdtF[T: c.WeakTypeTag, V: c.WeakTypeTag](f: c.Expr[T => V]): c.Expr[(NonEmptyVector[V], T => V)] = implValuesForAdtF(true)(f)
  def implValuesForAdtF[T: c.WeakTypeTag, V: c.WeakTypeTag](debug: Boolean)(f: c.Expr[T => V]): c.Expr[(NonEmptyVector[V], T => V)] = {
    val nev = implValuesForAdt[T, V](false)(f)
    val impl = q"($nev, $f)"
    if (debug) println("\n" + showCode(impl) + "\n")
    c.Expr[(NonEmptyVector[V], T => V)](impl)
  }
}