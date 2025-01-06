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

typealias Events<T> = SortedMap<Time, T>

private fun <T> SortedMap<Time, T>.pairwise(): List<Pair<Pair<Time, T>, Pair<Time<T>>>> =
  // NOTE: pretend evaluation is lazy, so that error() doesn't immediately throw
  (toList() + Pair(Time.Infinity, error("no value"))).zipWithNext()

class State<T> internal constructor(
  internal val current: Transactional<T>,
  val stateChanges: Events<T>,
)

val emptyEvents: Events<Nothing> = emptyMap()

fun <A, B> Events<A>.map(f: TransactionScope.(A) -> B): Events<B> =
  mapValues { (t, a) -> TransactionScope(t).f(a) }

fun <A> Events<A>.filter(f: TransactionScope.(A) -> Boolean): Events<A> =
  filter { (t, a) -> TransactionScope(t).f(a) }

fun <A> merge(
  first: Events<A>,
  second: Events<A>,
  onCoincidence: Time.(A, A) -> A,
): Events<A> =
  first.toMutableMap().also { result ->
    second.forEach { (t, a) ->
      result.merge(t, a) { f, s ->
        TransactionScope(t).onCoincidence(f, a)
      }
    }
  }.toSortedMap()

fun <A> State<Events<A>>.switchEvents(): Events<A> {
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

fun <A> State<Events<A>>.switchEventsPromptly(): Events<A> {
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

typealias GroupedEvents<K, V> = Events<Map<K, V>>

fun <K, V> Events<Map<K, V>>.groupByKey(): GroupedEvents<K, V> = this

fun <K, V> GroupedEvents<K, V>.eventsForKey(key: K): Events<V> =
  map { m -> m[k] }.filter { it != null }.map { it!! }

fun <A, B> State<A>.map(f: (A) -> B): State<B> =
  State(
    current = { t -> f(current.invoke(t)) },
    stateChanges = stateChanges.map { f(it) },
  )

fun <A, B, C> State<A>.combineWith(
  other: State<B>,
  f: (A, B) -> C,
): State<C> =
  State(
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

fun <A> State<State<A>>.flatten(): State<A> {
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
  return State(
    current = { t -> current.invoke(t).current.invoke(t) },
    stateChanges = changes.toSortedMap(),
  )
}

open class TransactionScope internal constructor(
  internal val currentTime: Time,
) {
  val now: Events<Unit> =
    sortedMapOf(currentTime to Unit)

  fun <A> Transactional<A>.sample(): A =
    invoke(currentTime)

  fun <A> State<A>.sample(): A =
    current.sample()
}

class StateScope internal constructor(
  time: Time,
  internal val stopTime: Time,
): TransactionScope(time) {

  fun <A, B> Events<A>.foldState(
    initialValue: B,
    f: TransactionScope.(B, A) -> B,
  ): State<B> {
    val truncated =
      dropWhile { (t, _) -> t < currentTime }
        .takeWhile { (t, _) -> t <= stopTime }
    val foldStateed =
      truncated
        .scan(Pair(currentTime, initialValue)) { (_, b) (t, a) ->
          Pair(t, TransactionScope(t).f(a, b))
        }
    val lookup = { t1 ->
      foldStateed.lastOrNull { (t0, _) -> t0 < t1 }?.value ?: initialValue
    }
    return State(lookup, foldStateed.toSortedMap())
  }

  fun <A> Events<A>.holdState(initialValue: A): State<A> =
    foldState(initialValue) { _, a -> a }

  fun <K, V> Events<Map<K, Maybe<V>>>.foldStateMapIncrementally(
    initialValues: Map<K, V>
  ): State<Map<K, V>> =
    foldState(initialValues) { patch, map ->
      val eithers = patch.map { (k, v) ->
        if (v is Just) Left(k to v.value) else Right(k)
      }
      val adds = eithers.filterIsInstance<Left>().map { it.left }
      val removes = eithers.filterIsInstance<Right>().map { it.right }
      val removed: Map<K, V> = map - removes.toSet()
      val updated: Map<K, V> = removed + adds
      updated
    }

  fun <K : Any, V> Events<Map<K, Maybe<Events<V>>>>.mergeIncrementally(
    initialEventss: Map<K, Events<V>>,
  ): Events<Map<K, V>> =
    foldStateMapIncrementally(initialEventss).map { it.merge() }.switchEvents()

  fun <K, A, B> Events<Map<K, Maybe<A>>.mapLatestStatefulForKey(
    transform: suspend StateScope.(A) -> B,
  ): Events<Map<K, Maybe<B>>> =
    pairwise().map { ((t0, patch), (t1, _)) ->
      patch.map { (k, ma) ->
        ma.map { a ->
          StateScope(t0, t1).transform(a)
        }
      }
    }
  }

}

```
