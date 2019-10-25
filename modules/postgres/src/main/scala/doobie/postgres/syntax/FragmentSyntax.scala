// Copyright (c) 2013-2018 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.postgres.syntax

import cats.Foldable
import cats.implicits._
import cats.effect.syntax.bracket._
import cats.effect.{ConcurrentEffect, LiftIO}
import doobie._
import doobie.implicits._
import doobie.postgres._
import fs2._
import fs2.io._
import fs2.text._
import java.io.StringReader

class FragmentOps(f: Fragment) {

  /**
   * Given a fragment of the form `COPY table (col, ...) FROM STDIN` construct a
   * `ConnectionIO` that inserts the values provided in `fa`, returning the number of affected
   * rows.
   */
  def copyIn[F[_]: Foldable, A](fa: F[A])(implicit ev: Text[A]): ConnectionIO[Long] = {
    // Fold with a StringBuilder and unsafeEncode to minimize allocations. Note that inserting no
    // rows is an error so we shortcut on empty input.
    // TODO: stream this rather than constructing the string in memory.
    if (fa.isEmpty) 0L.pure[ConnectionIO] else {
      val data = foldToString(fa)
      PHC.pgGetCopyAPI(PFCM.copyIn(f.query.sql, new StringReader(data)))
    }
  }

  /**
   * Given a fragment of the form `COPY table (col, ...) FROM STDIN` construct a
   * `ConnectionIO` that inserts the values provided by `stream`, returning the number of affected
   * rows. Chunks input `stream` for more efficient sending to `STDIN` with `minChunkSize`.
   */
  def copyIn[F[_], A](stream: Stream[F, A], minChunkSize: Int)(
    implicit
    F: ConcurrentEffect[F],
    ev: Text[A]): ConnectionIO[Long] = {

    val byteStream: Stream[F, Byte] =
      stream.chunkMin(minChunkSize).map(chunk => foldToString(chunk)).through(utf8Encode)

    liftToCIO(toInputStreamResource(byteStream).allocated).flatMap {
      case (stream, cleanup) =>
        PHC.pgGetCopyAPI(PFCM.copyIn(f.query.sql, stream)).guarantee(liftToCIO(cleanup))
    }
  }

  private def liftToCIO[F[_], A](ioa: F[A])(implicit F: ConcurrentEffect[F], L: LiftIO[ConnectionIO]): ConnectionIO[A] =
    L.liftIO(F.toIO(ioa))

  /** Folds given `F` to string, encoding each `A` with `Text` instance and joining resulting strings with `\n` */
  private def foldToString[F[_]: Foldable, A](fa: F[A])(implicit ev: Text[A]): String =
    fa.foldLeft(new StringBuilder) { (b, a) =>
      ev.unsafeEncode(a, b)
      b.append("\n")
    }.toString
}

trait ToFragmentOps {
  implicit def toFragmentOps(f: Fragment): FragmentOps =
    new FragmentOps(f)
}

object fragment extends ToFragmentOps
