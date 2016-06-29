package jspha.tk

/**
  * Safe "unsafe effect" wrapper monad similar to Scalaz's `IO` type.
  * Values of `Tk[A]` may be pure computations returning values `A` or
  * chains of effectful computations which *eventually* return values `A`
  * if they proceed unexceptionally.
  *
  * Basic, foundational elements of these `Tk` effect-chains can be built
  * using the `Tk.eff` and `Tk.Unsafe.eff` combinators which wrap standard,
  * side-effecting, unsafe Scala functions.
  *
  * When it is time to ultimately perform your `Tk` action to receive the
  * result value you can use the function `Tk.Unsafe.perform` to run the
  * effect chain and return the value.
  */
trait Tk[E, +A] {

  def apply[R](pure: A => R, fail: E => R, run: Effect.Runner[R]): R

  def map[B](f: A => B): Tk[E, B] = Tk.map(f)(this)
  def bind[B](k: A => Tk[E, B]): Tk[E, B] = Tk.bind(k)(this)
  def flatMap[B](k: A => Tk[E, B]): Tk[E, B] = Tk.bind(k)(this)

  def forgetE[F >: E]: Tk[F, A] = Tk.forgetE(this)
  def mapE[F](f: E => F): Tk[F, A] = Tk.mapE(f)(this)
  def caught: Tk[Nothing, Either[E, A]] = Tk.caught(this)
  def caughtOption: Tk[Nothing, Option[A]] = Tk.caughtOption(this)

}

object Tk {

  import Effect.Runner

  /**
    * A `Tk` is considered to be pure if no exceptions are thrown by effects
    * within it.
    */
  type Pure[A] = Tk[Nothing, A]

  def apply[Resp](ffi: => Resp): Tk[Throwable, Resp] =
    new Tk[Throwable, Resp] {
      def apply[R](pure: Resp => R, fail: Throwable => R, run: Effect.Runner[R]) =
        run(Effect[Unit, Resp, R](_ => ffi, (), fail, pure))
    }


  def eff[Req, Resp](ffi: Req => Resp)(request: Req): Tk[Throwable, Resp] =
    new Tk[Throwable, Resp] {
      def apply[R](pure: Resp => R, fail: Throwable => R, run: Runner[R]) =
        run(Effect[Req, Resp, R](ffi, request, fail, pure))
    }

  def caught[E, A](tk: Tk[E, A]): Tk[Nothing, Either[E, A]] =
    new Tk[Nothing, Either[E, A]] {
    def apply[R](pure: Either[E, A] => R, fail: Nothing => R, run: Runner[R]) = {
      def newPure(a: A): R = pure(Right(a))
      def newFail(e: E): R = pure(Left(e))
      tk(newPure, newFail, run)
    }
  }

  def caughtOption[E, A](tk: Tk[E, A]): Tk[Nothing, Option[A]] =
    new Tk[Nothing, Option[A]] {
      def apply[R](pure: Option[A] => R, fail: Nothing => R, run: Runner[R]) = {
        def newPure(a: A): R = pure(Some(a))
        def newFail(e: E): R = pure(None)
        tk(newPure, newFail, run)
      }
    }

  def map[E, A, B](f: A => B)(fa: Tk[E, A]): Tk[E, B] = new Tk[E, B] {
    def apply[R](pure: B => R, fail: E => R, run: Runner[R]) =
      fa(f andThen pure, fail, run)
  }

  def mapE[E, A, F](f: E => F)(fa: Tk[E, A]): Tk[F, A] = new Tk[F, A] {
    def apply[R](pure: A => R, fail: F => R, run: Runner[R]) =
      fa(pure, f andThen fail, run)
  }

  def forgetE[E, A, F >: E](tk: Tk[E, A]): Tk[F, A] =
    mapE[E, A, F](_.asInstanceOf)(tk)

  def pure[E, A](a: A): Tk[E, A] = new Tk[E, A] {
    def apply[R](pure: A => R, fail: E => R, run: Runner[R]) = pure(a)
  }

  def ap[E, A, B](tf: Tk[E, A => B])(ta: Tk[E, A]): Tk[E, B] = new Tk[E, B] {
    def apply[R](pure: B => R, fail: E => R, run: Runner[R]) =
      tf(f => ta(a => pure(f(a)), fail, run), fail, run)
  }

  def bind[E, A, B](k: A => Tk[E, B])(fa: Tk[E, A]): Tk[E, B] = new Tk[E, B] {
    def apply[R](pure: B => R, fail: E => R, run: Effect.Runner[R]) =
      fa(k(_)(pure, fail, run), fail, run)
  }

  object Unsafe {

    /**
      * Create an effect of no arguments identically to `eff`.
      */
    def apply[Resp](ffi: => Resp): Tk[Nothing, Resp] =
      new Tk[Nothing, Resp] {
        def apply[R](pure: Resp => R, fail: Nothing => R, run: Runner[R]) = {
          def thrower(t: Throwable) = throw t
          run(Effect[Unit, Resp, R](_ => ffi, (), thrower, pure))
        }
      }

    /**
      * Create an effect discarding the exception handler. You must ensure
      * that the effect cannot raise exceptions. If you violate this rule
      * then you may see exceptions thrown at runtime instead of being
      * propagated.
      */
    def eff[Req, Resp](ffi: Req => Resp)(request: Req): Tk[Nothing, Resp] =
      new Tk[Nothing, Resp] {
        def apply[R](pure: Resp => R, fail: Nothing => R, run: Runner[R]) = {
          def thrower(t: Throwable) = throw t
          run(Effect[Req, Resp, R](ffi, request, thrower, pure))
        }
      }

    /**
      * Extract the result inner value, running all intermediate effects. If
      * any exceptions are encountered then they will be captured purely.
      */
    def perform[E, A](tk: Tk[E, A]): Either[E, A] =
      tk(Right(_), Left(_), Effect.perform)

    /**
      * Extract the result inner value, running all intermediate effects. If
      * any exceptions are encountered then they will now be raised.
      */
    def performThrowing[E <: Throwable, A](tk: Tk[E, A]): A = {
      def thrower(t: Throwable) = throw t
      tk(identity _, thrower, Effect.perform)
    }

    /**
      * Extract the result value, running all intermediate effects.
      */
    def performPure[A](tk: Pure[A]): A = {
      def elimNothing[R](n: Nothing): R = sys.error("Received Nothing")
      tk(identity _, elimNothing, Effect.perform)
    }

  }

}
