# FRP Semantics

`Kairos`'s pure API is based off of the following denotational semantics
([wikipedia](https://en.wikipedia.org/wiki/Denotational_semantics)).

The semantics model `Kairos` types as time-varying values; by making `Time` a
first-class value, we can define a referentially-transparent API that allows us
to reason about the behavior of the pure `Kairos` combinators. This is
implementation-agnostic; we can compare the behavior of any implementation with
expected behavior denoted by these semantics to identify bugs.

The semantics are written in pseudo-Kotlin; places where we are deviating from
real Kotlin are noted with comments.

``` kotlin

sealed class Time : Comparable<Time> {
  object BigBang : Time()
  data class At(time: BigDecimal) : Time()
  object Infinity : Time()

  override final fun compareTo(other: Time): Int =
    when (this) {
      BigBang -> if (other === BigBang) 0 else -1
      is At -> when (other) {
        BigBang -> 1
        is At -> time.compareTo(other.time)
        Infinity -> -1
      }
      Infinity -> if (other === Infinity) 0 else 1
    }
}

typealias Transactional<T> = (Time) -> T

typealias TFlow<T> = SortedMap<Time, T>

private fun <T> SortedMap<Time, T>.pairwise(): List<Pair<Pair<Time, T>, Pair<Time<T>>>> =
  // NOTE: pretend evaluation is lazy, so that error() doesn't immediately throw
  (toList() + Pair(Time.Infinity, error("no value"))).zipWithNext()

class TState<T> internal constructor(
  internal val current: Transactional<T>,
  val stateChanges: TFlow<T>,
)

val emptyTFlow: TFlow<Nothing> = emptyMap()

fun <A, B> TFlow<A>.map(f: FrpTransactionScope.(A) -> B): TFlow<B> =
  mapValues { (t, a) -> FrpTransactionScope(t).f(a) }

fun <A> TFlow<A>.filter(f: FrpTransactionScope.(A) -> Boolean): TFlow<A> =
  filter { (t, a) -> FrpTransactionScope(t).f(a) }

fun <A> merge(
  first: TFlow<A>,
  second: TFlow<A>,
  onCoincidence: Time.(A, A) -> A,
): TFlow<A> =
  first.toMutableMap().also { result ->
    second.forEach { (t, a) ->
      result.merge(t, a) { f, s ->
        FrpTranscationScope(t).onCoincidence(f, a)
      }
    }
  }.toSortedMap()

fun <A> TState<TFlow<A>>.switch(): TFlow<A> {
  val truncated = listOf(Pair(Time.BigBang, current.invoke(Time.BigBang))) +
    stateChanges.dropWhile { (time, _) -> time < time0 }
  val events =
    truncated
      .pairwise()
      .flatMap { ((t0, sa), (t2, _)) ->
        sa.filter { (t1, a) -> t0 < t1 && t1 <= t2 }
      }
  return events.toSortedMap()
}

fun <A> TState<TFlow<A>>.switchPromptly(): TFlow<A> {
  val truncated = listOf(Pair(Time.BigBang, current.invoke(Time.BigBang))) +
    stateChanges.dropWhile { (time, _) -> time < time0 }
  val events =
    truncated
      .pairwise()
      .flatMap { ((t0, sa), (t2, _)) ->
        sa.filter { (t1, a) -> t0 <= t1 && t1 <= t2 }
      }
  return events.toSortedMap()
}

typealias GroupedTFlow<K, V> = TFlow<Map<K, V>>

fun <K, V> TFlow<Map<K, V>>.groupByKey(): GroupedTFlow<K, V> = this

fun <K, V> GroupedTFlow<K, V>.eventsForKey(key: K): TFlow<V> =
  map { m -> m[k] }.filter { it != null }.map { it!! }

fun <A, B> TState<A>.map(f: (A) -> B): TState<B> =
  TState(
    current = { t -> f(current.invoke(t)) },
    stateChanges = stateChanges.map { f(it) },
  )

fun <A, B, C> TState<A>.combineWith(
  other: TState<B>,
  f: (A, B) -> C,
): TState<C> =
  TState(
    current = { t -> f(current.invoke(t), other.current.invoke(t)) },
    stateChanges = run {
      val aChanges =
        stateChanges
          .map { a ->
            val b = other.current.sample()
            Triple(a, b, f(a, b))
          }
      val bChanges =
        other
          .stateChanges
          .map { b ->
            val a = current.sample()
            Triple(a, b, f(a, b))
          }
      merge(aChanges, bChanges) { (a, _, _), (_, b, _) ->
          Triple(a, b, f(a, b))
        }
        .map { (_, _, zipped) -> zipped }
    },
  )

fun <A> TState<TState<A>>.flatten(): TState<A> {
  val changes =
    stateChanges
      .pairwise()
      .flatMap { ((t0, oldInner), (t2, _)) ->
        val inWindow =
          oldInner
            .stateChanges
            .filter { (t1, b) -> t0 <= t1 && t1 < t2 }
        if (inWindow.firstOrNull()?.time != t0) {
          listOf(Pair(t0, oldInner.current.invoke(t0))) + inWindow
        } else {
          inWindow
        }
      }
  return TState(
    current = { t -> current.invoke(t).current.invoke(t) },
    stateChanges = changes.toSortedMap(),
  )
}

open class FrpTranscationScope internal constructor(
  internal val currentTime: Time,
) {
  val now: TFlow<Unit> =
    sortedMapOf(currentTime to Unit)

  fun <A> Transactional<A>.sample(): A =
    invoke(currentTime)

  fun <A> TState<A>.sample(): A =
    current.sample()
}

class FrpStateScope internal constructor(
  time: Time,
  internal val stopTime: Time,
): FrpTransactionScope(time) {

  fun <A, B> TFlow<A>.fold(
    initialValue: B,
    f: FrpTransactionScope.(B, A) -> B,
  ): TState<B> {
    val truncated =
      dropWhile { (t, _) -> t < currentTime }
        .takeWhile { (t, _) -> t <= stopTime }
    val folded =
      truncated
        .scan(Pair(currentTime, initialValue)) { (_, b) (t, a) ->
          Pair(t, FrpTransactionScope(t).f(a, b))
        }
    val lookup = { t1 ->
      folded.lastOrNull { (t0, _) -> t0 < t1 }?.value ?: initialValue
    }
    return TState(lookup, folded.toSortedMap())
  }

  fun <A> TFlow<A>.hold(initialValue: A): TState<A> =
    fold(initialValue) { _, a -> a }

  fun <K, V> TFlow<Map<K, Maybe<V>>>.foldMapIncrementally(
    initialValues: Map<K, V>
  ): TState<Map<K, V>> =
    fold(initialValues) { patch, map ->
      val eithers = patch.map { (k, v) ->
        if (v is Just) Left(k to v.value) else Right(k)
      }
      val adds = eithers.filterIsInstance<Left>().map { it.left }
      val removes = eithers.filterIsInstance<Right>().map { it.right }
      val removed: Map<K, V> = map - removes.toSet()
      val updated: Map<K, V> = removed + adds
      updated
    }

  fun <K : Any, V> TFlow<Map<K, Maybe<TFlow<V>>>>.mergeIncrementally(
    initialTFlows: Map<K, TFlow<V>>,
  ): TFlow<Map<K, V>> =
    foldMapIncrementally(initialTFlows).map { it.merge() }.switch()

  fun <K, A, B> TFlow<Map<K, Maybe<A>>.mapLatestStatefulForKey(
    transform: suspend FrpStateScope.(A) -> B,
  ): TFlow<Map<K, Maybe<B>>> =
    pairwise().map { ((t0, patch), (t1, _)) ->
      patch.map { (k, ma) ->
        ma.map { a ->
          FrpStateScope(t0, t1).transform(a)
        }
      }
    }
  }

}

```
