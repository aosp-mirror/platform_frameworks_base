# From Flows to Kairos

## Key differences

* Kairos evaluates all events (`Events` emissions + observers) in a transaction.

* Kairos splits `Flow` APIs into two distinct types: `Events` and `State`

    * `Events` is roughly equivalent to `SharedFlow` w/ a replay cache that
      exists for the duration of the current Kairos transaction and shared with
      `SharingStarted.WhileSubscribed()`

    * `State` is roughly equivalent to `StateFlow` shared with
      `SharingStarted.Eagerly`, but the current value can only be queried within
      a Kairos transaction, and the value is only updated at the end of the
      transaction

* Kairos further divides `Flow` APIs based on how they internally use state:

  * **TransactionScope:** APIs that internally query some state need to be
    performed within an Kairos transaction

    * this scope is available from the other scopes, and from most lambdas
      passed to other Kairos APIs

  * **StateScope:** APIs that internally accumulate state in reaction to events
    need to be performed within a State scope (akin to a `CoroutineScope`)

    * this scope is a side-effect-free subset of BuildScope, and so can be
      used wherever you have an BuildScope

  * **BuildScope:** APIs that perform external side-effects (`Flow.collect`)
    need to be performed within a Build scope (akin to a `CoroutineScope`)

    * this scope is available from `Network.activateSpec { … }`

  * All other APIs can be used anywhere

## emptyFlow()

Use `emptyEvents`

``` kotlin
// this Events emits nothing
val noEvents: Events<Int> = emptyEvents
```

## map { … }

Use `Events.map` / `State.map`

``` kotlin
val anInt: State<Int> = …
val squared: State<Int> = anInt.map { it * it }
val messages: Events<String> = …
val messageLengths: Events<Int> = messages.map { it.size }
```

## filter { … } / mapNotNull { … }

### I have an Events

Use `Events.filter` / `Events.mapNotNull`

``` kotlin
val messages: Events<String> = …
val nonEmpty: Events<String> = messages.filter { it.isNotEmpty() }
```

### I have a State

Convert the `State` to `Events` using `State.stateChanges`, then use
`Events.filter` / `Events.mapNotNull`

If you need to convert back to `State`, use `Events.holdState(initialValue)` on
the result.

``` kotlin
state.stateChanges.filter { … }.holdState(initialValue)
```

Note that `Events.holdState` is only available within an `StateScope` in order
to track the lifetime of the state accumulation.

## combine(...) { … }

### I have States

Use `combine(States)`

``` kotlin
val someInt: State<Int> = …
val someString: State<String> = …
val model: State<MyModel> = combine(someInt, someString) { i, s -> MyModel(i, s) }
```

### I have Events

Convert the Events to States using `Events.holdState(initialValue)`, then use
`combine(States)`

If you want the behavior of Flow.combine where nothing is emitted until each
Events has emitted at least once, you can use filter:

``` kotlin
// null used as an example, can use a different sentinel if needed
combine(eventsA.holdState(null), eventsB.holdState(null)) { a, b ->
        a?.let { b?.let { … } }
    }
    .filterNotNull()
```

Note that `Events.holdState` is only available within an `StateScope` in order
to track the lifetime of the state accumulation.

#### Explanation

`Flow.combine` always tracks the last-emitted value of each `Flow` it's
combining. This is a form of state-accumulation; internally, it collects from
each `Flow`, tracks the latest-emitted value, and when anything changes, it
re-runs the lambda to combine the latest values.

An effect of this is that `Flow.combine` doesn't emit until each combined `Flow`
has emitted at least once. This often bites developers. As a workaround,
developers generally append `.onStart { emit(initialValue) }` to the `Flows`
that don't immediately emit.

Kairos avoids this gotcha by forcing usage of `State` for `combine`, thus
ensuring that there is always a current value to be combined for each input.

## collect { … }

Use `observe { … }`

``` kotlin
val job: Job = events.observe { println("observed: $it") }
```

Note that `observe` is only available within a `BuildScope` in order to track
the lifetime of the observer. `BuildScope` can only come from a top-level
`Network.transaction { … }`, or a sub-scope created by using a `-Latest`
operator.

## sample(flow) { … }

### I want to sample a State

Use `State.sample()` to get the current value of a `State`. This can be
invoked anywhere you have access to an `TransactionScope`.

``` kotlin
// the lambda passed to map receives an TransactionScope, so it can invoke
// sample
events.map { state.sample() }
```

#### Explanation

To keep all state-reads consistent, the current value of a State can only be
queried within a Kairos transaction, modeled with `TransactionScope`. Note that
both `StateScope` and `BuildScope` extend `TransactionScope`.

### I want to sample an Events

Convert to a `State` by using `Events.holdState(initialValue)`, then use `sample`.

Note that `holdState` is only available within an `StateScope` in order to track
the lifetime of the state accumulation.

## stateIn(scope, sharingStarted, initialValue)

Use `Events.holdState(initialValue)`. There is no need to supply a
sharingStarted argument; all states are accumulated eagerly.

``` kotlin
val ints: Events<Int> = …
val lastSeenInt: State<Int> = ints.holdState(initialValue = 0)
```

Note that `holdState` is only available within an `StateScope` in order to track
the lifetime of the state accumulation (akin to the scope parameter of
`Flow.stateIn`). `StateScope` can only come from a top-level
`Network.transaction { … }`, or a sub-scope created by using a `-Latest`
operator. Also note that `BuildScope` extends `StateScope`.

## distinctUntilChanged()

Use `distinctUntilChanged` like normal. This is only available for `Events`;
`States` are already `distinctUntilChanged`.

## merge(...)

### I have Eventss

Use `merge(Events) { … }`. The lambda argument is used to disambiguate multiple
simultaneous emissions within the same transaction.

#### Explanation

Under Kairos's rules, an `Events` may only emit up to once per transaction. This
means that if we are merging two or more `Events` that are emitting at the same
time (within the same transaction), the resulting merged `Events` must emit a
single value. The lambda argument allows the developer to decide what to do in
this case.

### I have States

If `combine` doesn't satisfy your needs, you can use `State.changes` to
convert to a `Events`, and then `merge`.

## conflatedCallbackFlow { … }

Use `events { … }`.

As a shortcut, if you already have a `conflatedCallbackFlow { … }`, you can
convert it to an Events via `Flow.toEvents()`.

Note that `events` is only available within a `BuildScope` in order to track the
lifetime of the input registration.

## first()

### I have a State

Use `State.sample`.

### I have an Events

Use `Events.nextOnly`, which works exactly like `Flow.first` but instead of
suspending it returns a `Events` that emits once.

The naming is intentionally different because `first` implies that it is the
first-ever value emitted from the `Flow` (which makes sense for cold `Flows`),
whereas `nextOnly` indicates that only the next value relative to the current
transaction (the one `nextOnly` is being invoked in) will be emitted.

Note that `nextOnly` is only available within an `StateScope` in order to track
the lifetime of the state accumulation.

## flatMapLatest { … }

If you want to use -Latest to cancel old side-effects, similar to what the Flow
-Latest operators offer for coroutines, see `mapLatest`.

### I have a State…

#### …and want to switch States

Use `State.flatMap`

``` kotlin
val flattened = state.flatMap { a -> gestate(a) }
```

#### …and want to switch Events

Use `State<Events<T>>.switchEvents()`

``` kotlin
val events = state.map { a -> getEvents(a) }.switchEvents()
```

### I have an Events…

#### …and want to switch Events

Use `holdState` to convert to a `State<Events<T>>`, then use `switchEvents` to
switch to the latest `Events`.

``` kotlin
val events = eventsOfFlows.holdState(emptyEvents).switchEvents()
```

#### …and want to switch States

Use `holdState` to convert to a `State<State<T>>`, then use `flatMap` to switch
to the latest `State`.

``` kotlin
val state = eventsOfStates.holdState(stateOf(initialValue)).flatMap { it }
```

## mapLatest { … } / collectLatest { … }

`StateScope` and `BuildScope` both provide `-Latest` operators that
automatically cancel old work when new values are emitted.

``` kotlin
val currentModel: State<SomeModel> = …
val mapped: State<...> = currentModel.mapLatestBuild { model ->
    effect { "new model in the house: $model" }
    model.someState.observe { "someState: $it" }
    val someData: State<SomeInfo> =
        getBroadcasts(model.uri)
            .map { extractInfo(it) }
            .holdState(initialInfo)
    …
}
```

## flowOf(...)

### I want a State

Use `stateOf(initialValue)`.

### I want an Events

Use `now.map { initialValue }`

Note that `now` is only available within an `TransactionScope`.

#### Explanation

`Events` are not cold, and so there isn't a notion of "emit this value once
there is a collector" like there is for `Flow`. The closest analog would be
`State`, since the initial value is retained indefinitely until there is an
observer. However, it is often useful to immediately emit a value within the
current transaction, usually when using a `flatMap` or `switchEvents`. In these
cases, using `now` explicitly models that the emission will occur within the
current transaction.

``` kotlin
fun <T> TransactionScope.eventsOf(value: T): Events<T> = now.map { value }
```

## MutableStateFlow / MutableSharedFlow

Use `MutableState(frpNetwork, initialValue)` and `MutableEvents(frpNetwork)`.
