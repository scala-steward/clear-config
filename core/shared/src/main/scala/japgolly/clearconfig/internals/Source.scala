package japgolly.clearconfig.internals

import scalaz.{Applicative, Functor, Monad, \/, \/-, ~>}

final case class Source[F[_]](name: SourceName, prepare: F[String \/ Store[F]]) {
  override def toString: String =
    s"Source(${name.value})"

  def toSources: Sources[F] =
    Sources(Vector.empty :+ this)

  /** Expands each key query into multiple, and chooses the first that returns a result. */
  def mapKeyQueries(f: Key => List[Key])(implicit F: Monad[F]): Source[F] =
    Source(name, F.map(prepare)(_.map(_.mapKeyQueries(f)(F))))

  def trans[G[_]](t: F ~> G)(implicit G: Functor[G]): Source[G] =
    copy(prepare = G.map(t(prepare))(_.map(_ trans t)))
}

trait SourceObject {

  final def point[F[_]](name: String, store: => Store[F])(implicit F: Applicative[F]): Source[F] =
    Source[F](SourceName(name), F.point(\/-(store)))

  final def empty[F[_]](name: String)(implicit F: Applicative[F]): Source[F] =
    manual(name)()

  final def manual[F[_]](name: String)(kvs: (String, String)*)(implicit F: Applicative[F]): Source[F] =
    manual(name, kvs.toMap)

  final def manual[F[_]](name: String, kvs: Map[String, String])(implicit F: Applicative[F]): Source[F] =
    point(name, StoreObject.ofMap(kvs))

}