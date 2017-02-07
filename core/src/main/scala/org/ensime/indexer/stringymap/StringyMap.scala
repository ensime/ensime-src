// Copyright: 2010 - 2017 https://github.com/ensime/ensime-server/graphs
// License: http://www.gnu.org/licenses/gpl-3.0.en.html
/**
 * TypeClass (api/impl/syntax) for marshalling objects into
 * `java.util.HashMap<String,Object>` (yay, big data!).
 */
package org.ensime.indexer.stringymap

import scala.util._

import java.sql.Timestamp

import org.ensime.api.DeclaredAs
import org.ensime.indexer.{ Access, Default, Private, Protected, Public }
import shapeless._
import shapeless.labelled._

package object api {
  type StringyMap = java.util.HashMap[String, AnyRef]
  type BigResult[T] = Either[String, T] // aggregating errors doesn't add much
}

package api {
  trait BigDataFormat[T] {
    def label: String
    def toProperties(t: T): StringyMap
    def fromProperties(m: StringyMap): BigResult[T]
  }

  trait SPrimitive[T] {
    def toValue(v: T): AnyRef
    def fromValue(v: AnyRef): T
  }

  // defining really basic implementations on the companion
  object SPrimitive {
    implicit object StringSPrimitive extends SPrimitive[String] {
      def toValue(v: String): String = v
      def fromValue(v: AnyRef): String = v.asInstanceOf[String]
    }
    implicit object IntSPrimitive extends SPrimitive[Int] {
      def toValue(v: Int): java.lang.Integer = v
      def fromValue(v: AnyRef): Int = v.asInstanceOf[java.lang.Integer]
    }
    implicit object LongSPrimitive extends SPrimitive[Long] {
      def toValue(v: Long): java.lang.Long = v
      def fromValue(v: AnyRef): Long = v.asInstanceOf[java.lang.Long]
    }
    implicit def OptionSPrimitive[T](
      implicit
      p: SPrimitive[T]
    ) = new SPrimitive[Option[T]] {
      def toValue(v: Option[T]): AnyRef = v match {
        case None => null
        case Some(t) => p.toValue(t)
      }
      def fromValue(v: AnyRef): Option[T] =
        if (v == null) None
        else Some(p.fromValue(v))
    }
    implicit object TimeStampSPrimitive extends SPrimitive[Timestamp] {
      def toValue(v: Timestamp): java.lang.Long = LongSPrimitive.toValue(v.getTime)
      def fromValue(v: AnyRef): Timestamp = new Timestamp(LongSPrimitive.fromValue(v))
    }

    implicit object AccessSPrimitive extends SPrimitive[Access] {
      import org.objectweb.asm.Opcodes._

      def toValue(v: Access): java.lang.Integer =
        if (v == null) null
        else {
          val code = v match {
            case Public => ACC_PUBLIC
            case Private => ACC_PRIVATE
            case Protected => ACC_PROTECTED
            case Default => 0
          }
          IntSPrimitive.toValue(code)
        }

      def fromValue(v: AnyRef): Access = Access(IntSPrimitive.fromValue(v))
    }

    implicit object DeclaredAsSPrimitive extends SPrimitive[DeclaredAs] {
      import org.ensime.util.enums._
      private val lookup: Map[String, DeclaredAs] = implicitly[AdtToMap[DeclaredAs]].lookup
      def toValue(v: DeclaredAs): java.lang.String = if (v == null) null else StringSPrimitive.toValue(v.toString)
      def fromValue(v: AnyRef): DeclaredAs = lookup(StringSPrimitive.fromValue(v))
    }
  }
}

package object impl {
  import org.ensime.indexer.stringymap.api._

  implicit def hNilBigDataFormat[T]: BigDataFormat[HNil] = new BigDataFormat[HNil] {
    def label: String = ???
    def toProperties(t: HNil): StringyMap = new java.util.HashMap()
    def fromProperties(m: StringyMap) = Right(HNil)
  }

  implicit def hListBigDataFormat[Key <: Symbol, Value, Remaining <: HList](
    implicit
    key: Witness.Aux[Key],
    prim: SPrimitive[Value],
    remV: Lazy[BigDataFormat[Remaining]]
  ): BigDataFormat[FieldType[Key, Value] :: Remaining] =
    new BigDataFormat[FieldType[Key, Value] :: Remaining] {
      def label: String = ???

      def toProperties(t: FieldType[Key, Value] :: Remaining): StringyMap = {
        val value = prim.toValue(t.head)
        val map = remV.value.toProperties(t.tail)
        if (value != null) map.put(key.value.name, value)
        map
      }

      def fromProperties(m: StringyMap) = {
        val value = m.get(key.value.name)
        /*
        This is a pretty hacky way to handle null => Empty option case, i'd love
        to have a more typesafe way to do this.
         */
        val errorMessage = s"Missing key ${key.value.name} in $m"
        val resolved = Try(prim.fromValue(value)) match {
          case Success(v) => Right(v)
          case Failure(exc) => Left(errorMessage)
        }
        for {
          remaining <- remV.value.fromProperties(m).right
          current <- resolved.right
        } yield field[Key](current) :: remaining
      }

    }

  implicit def familyBigDataFormat[T, Repr](
    implicit
    gen: LabelledGeneric.Aux[T, Repr],
    sg: Lazy[BigDataFormat[Repr]],
    tpe: Typeable[T]
  ): BigDataFormat[T] = new BigDataFormat[T] {
    // HACK: really need a Wrapper like sjs
    // WORKAROUND: edge names cannot have dots in them
    def label: String = tpe.describe.replace(".type", "")
    def toProperties(t: T): StringyMap = {
      val map = sg.value.toProperties(gen.to(t))
      map.put("typehint", label)
      map
    }
    def fromProperties(m: StringyMap): BigResult[T] =
      sg.value.fromProperties(m).right.map(gen.from)
  }

  implicit def CNilBigDataFormat[T]: BigDataFormat[CNil] = new BigDataFormat[CNil] {
    override def label: String = ???
    override def toProperties(t: CNil): StringyMap = ???
    override def fromProperties(m: StringyMap): BigResult[CNil] = ???
  }

  implicit def CoproductBigDataFormat[Key <: Symbol, Value, Tail <: Coproduct](
    implicit
    key: Witness.Aux[Key],
    bdfh: Lazy[BigDataFormat[Value]],
    bdft: Lazy[BigDataFormat[Tail]]
  ): BigDataFormat[FieldType[Key, Value] :+: Tail] = new BigDataFormat[FieldType[Key, Value] :+: Tail] {
    override def label: String = ???

    override def toProperties(t: FieldType[Key, Value] :+: Tail): StringyMap = t match {
      case Inl(found) => bdfh.value.toProperties(found)
      case Inr(tail) => bdft.value.toProperties(tail)
    }

    override def fromProperties(m: StringyMap): BigResult[FieldType[Key, Value] :+: Tail] = {
      if (m.get("typehint") == key.value.name) {
        for {
          res <- bdfh.value.fromProperties(m).right
        } yield Inl(field[Key](res))
      } else {
        for {
          tail <- bdft.value.fromProperties(m).right
        } yield Inr(tail)
      }
    }
  }
}

package object syntax {
  import org.ensime.indexer.stringymap.api._

  implicit class RichBigResult[R](val e: BigResult[R]) extends AnyVal {
    def getOrThrowError: R = e match {
      case Left(error) => throw new IllegalArgumentException(error)
      case Right(r) => r
    }
  }

  /** Syntactic helper for serialisables. */
  implicit class RichBigDataFormat[T](val t: T) extends AnyVal {
    def label(implicit s: BigDataFormat[T]): String = s.label
    def toProperties(implicit s: BigDataFormat[T]): StringyMap = s.toProperties(t)
  }

  implicit class RichProperties(val props: StringyMap) extends AnyVal {
    def as[T](implicit s: BigDataFormat[T]): T = s.fromProperties(props).getOrThrowError
  }
}
