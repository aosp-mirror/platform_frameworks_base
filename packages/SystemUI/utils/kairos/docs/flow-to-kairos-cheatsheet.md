# From Flows to Kairos

## Key differences

* Kairos evaluates all events (`TFlow` emissions + observers) in a transaction.

* Kairos splits `Flow` APIs into two distinct types: `TFlow` and `TState`

    * `TFlow` is roughly equivalent to `SharedFlow` w/ a replay cache that
      exists for the duration of the current Kairos transaction and shared with
      `SharingStarted.WhileSubscribed()`

    * `TState` is roughly equivalent to `StateFlow` shared with
      `SharingStarted.Eagerly`, but the current value can only be queried within
      a Kairos transaction, and the value is only updated at the end of the
      transaction

* Kairos further divides `Flow` APIs based on how they internally use state:

  * **FrpTransactionScope:** APIs that internally query some state need to be
    performed within an Kairos transaction

    * this scope is available from the other scopes, and from most lambdas
      passed to other Kairos APIs

  * **FrpStateScope:** APIs that internally accumulate state in reaction to
    events need to be performed within an FRP State scope (akin to a
    `CoroutineScope`)

    * this scope is a side-effect-free subset of FrpBuildScope, and so can be
      used wherever you have an FrpBuildScope

  * **FrpBuildScope:** APIs that perform external side-effects (`Flow.collect`)
    need to be performed within an FRP Build scope (akin to a `CoroutineScope`)

    * this scope is available from `FrpNetwork.activateSpec { … }`

  * All other APIs can be used anywhere

## emptyFlow()

Use `emptyTFlow`

``` kotlin
// this TFlow emits nothing
val noEvents: TFlow<Int> = emptyTFlow
```

## map { … }

Use `TFlow.map` / `TState.map`

``` kotlin
val anInt: TState<Int> = …
val squared: TState<Int> = anInt.map { it * it }
val messages: TFlow<String> = …
val messageLengths: TFlow<Int> = messages.map { it.size }
```

## filter { … } / mapNotNull { … }

### I have a TFlow

Use `TFlow.filter` / `TFlow.mapNotNull`

``` kotlin
val messages: TFlow<String> = …
val nonEmpty: TFlow<String> = messages.filter { it.isNotEmpty() }
```

### I have a TState

Convert the `TState` to `TFlow` using `TState.stateChanges`, then use
`TFlow.filter` / `TFlow.mapNotNull`

If you need to convert back to `TState`, use `TFlow.hold(initialValue)` on the
result.

``` kotlin
tState.stateChanges.filter { … }.hold(initialValue)
```

Note that `TFlow.hold` is only available within an `FrpStateScope` in order to
track the lifetime of the state accumulation.

## combine(...) { … }

### I have TStates

Use `combine(TStates)`

``` kotlin
val someInt: TState<Int> = …
val someString: TState<String> = …
val model: TState<MyModel> = combine(someInt, someString) { i, s -> MyModel(i, s) }
```

### I have TFlows

Convert the TFlows to TStates using `TFlow.hold(initialValue)`, then use
`combine(TStates)`

If you want the behavior of Flow.combine where nothing is emitted until each
TFlow has emitted at least once, you can use filter:

``` kotlin
// null used as an example, can use a different sentinel if needed
combine(tFlowA.hold(null), tFlowB.hold(null)) { a, b ->
        a?.let { b?.let { … } }
    }
    .filterNotNull()
```

Note that `TFlow.hold` is only available within an `FrpStateScope` in order to
track the lifetime of the state accumulation.

#### Explanation

`Flow.combine` always tracks the last-emitted value of each `Flow` it's
combining. This is a form of state-accumulation; internally, it collects from
each `Flow`, tracks the latest-emitted value, and when anything changes, it
re-runs the lambda to combine the latest values.

An effect of this is that `Flow.combine` doesn't emit until each combined `Flow`
has emitted at least once. This often bites developers. As a workaround,
developers generally append `.onStart { emit(initialValue) }` to the `Flows`
that don't immediately emit.

Kairos avoids this gotcha by forcing usage of `TState` for `combine`, thus
ensuring that there is always a current value to be combined for each input.

## collect { … }

Use `observe { … }`

``` kotlin
val job: Job = tFlow.observe { println("observed: $it") }
```

Note that `observe` is only available within an `FrpBuildScope` in order to
track the lifetime of the observer. `FrpBuildScope` can only come from a
top-level `FrpNetwork.transaction { … }`, or a sub-scope created by using a
`-Latest` operator.

## sample(flow) { … }

### I want to sample a TState

Use `TState.sample()` to get the current value of a `TState`. This can be
invoked anywhere you have access to an `FrpTransactionScope`.

``` kotlin
// the lambda passed to map receives an FrpTransactionScope, so it can invoke
// sample
tFlow.map { tState.sample() }
```

#### Explanation

To keep all state-reads consistent, the current value of a TState can only be
queried within a Kairos transaction, modeled with `FrpTransactionScope`. Note
that both `FrpStateScope` and `FrpBuildScope` extend `FrpTransactionScope`.

### I want to sample a TFlow

Convert to a `TState` by using `TFlow.hold(initialValue)`, then use `sample`.

Note that `hold` is only available within an `FrpStateScope` in order to track
the lifetime of the state accumulation.

## stateIn(scope, sharingStarted, initialValue)

Use `TFlow.hold(initialValue)`. There is no need to supply a sharingStarted
argument; all states are accumulated eagerly.

``` kotlin
val ints: TFlow<Int> = …
val lastSeenInt: TState<Int> = ints.hold(initialValue = 0)
```

Note that `hold` is only available within an `FrpStateScope` in order to track
the lifetime of the state accumulation (akin to the scope parameter of
`Flow.stateIn`). `FrpStateScope` can only come from a top-level
`FrpNetwork.transaction { … }`, or a sub-scope created by using a `-Latest`
operator. Also note that `FrpBuildScope` extends `FrpStateScope`.

## distinctUntilChanged()

Use `distinctUntilChanged` like normal. This is only available for `TFlow`;
`TStates` are already `distinctUntilChanged`.

## merge(...)

### I have TFlows

Use `merge(TFlows) { … }`. The lambda argument is used to disambiguate multiple
simultaneous emissions within the same transaction.

#### Explanation

Under Kairos's rules, a `TFlow` may only emit up to once per transaction. This
means that if we are merging two or more `TFlows` that are emitting at the same
time (within the same transaction), the resulting merged `TFlow` must emit a
single value. The lambda argument allows the developer to decide what to do in
this case.

### I have TStates

If `combine` doesn't satisfy your needs, you can use `TState.stateChanges` to
convert to a `TFlow`, and then `merge`.

## conflatedCallbackFlow { … }

Use `tFlow { … }`.

As a shortcut, if you already have a `conflatedCallbackFlow { … }`, you can
convert it to a TFlow via `Flow.toTFlow()`.

Note that `tFlow` is only available within an `FrpBuildScope` in order to track
the lifetime of the input registration.

## first()

### I have a TState

Use `TState.sample`.

### I have a TFlow

Use `TFlow.nextOnly`, which works exactly like `Flow.first` but instead of
suspending it returns a `TFlow` that emits once.

The naming is intentionally different because `first` implies that it is the
first-ever value emitted from the `Flow` (which makes sense for cold `Flows`),
whereas `nextOnly` indicates that only the next value relative to the current
transaction (the one `nextOnly` is being invoked in) will be emitted.

Note that `nextOnly` is only available within an `FrpStateScope` in order to
track the lifetime of the state accumulation.

## flatMapLatest { … }

If you want to use -Latest to cancel old side-effects, similar to what the Flow
-Latest operators offer for coroutines, see `mapLatest`.

### I have a TState…

#### …and want to switch TStates

Use `TState.flatMap`

``` kotlin
val flattened = tState.flatMap { a -> getTState(a) }
```

#### …and want to switch TFlows

Use `TState<TFlow<T>>.switch()`

``` kotlin
val tFlow = tState.map { a -> getTFlow(a) }.switch()
```

### I have a TFlow…

#### …and want to switch TFlows

Use `hold` to convert to a `TState<TFlow<T>>`, then use `switch` to switch to
the latest `TFlow`.

``` kotlin
val tFlow = tFlowOfFlows.hold(emptyTFlow).switch()
```

#### …and want to switch TStates

Use `hold` to convert to a `TState<TState<T>>`, then use `flatMap` to switch to
the latest `TState`.

``` kotlin
val tState = tFlowOfStates.hold(tStateOf(initialValue)).flatMap { it }
```

## mapLatest { … } / collectLatest { … }

`FrpStateScope` and `FrpBuildScope` both provide `-Latest` operators that
automatically cancel old work when new values are emitted.

``` kotlin
val currentModel: TState<SomeModel> = …
val mapped: TState<...> = currentModel.mapLatestBuild { model ->
    effect { "new model in the house: $model" }
    model.someState.observe { "someState: $it" }
    val someData: TState<SomeInfo> =
        getBroadcasts(model.uri)
            .map { extractInfo(it) }
            .hold(initialInfo)
    …
}
```

## flowOf(...)

### I want a TState

Use `tStateOf(initialValue)`.

### I want a TFlow

Use `now.map { initialValue }`

Note that `now` is only available within an `FrpTransactionScope`.

#### Explanation

`TFlows` are not cold, and so there isn't a notion of "emit this value once
there is a collector" like there is for `Flow`. The closest analog would be
`TState`, since the initial value is retained indefinitely until there is an
observer. However, it is often useful to immediately emit a value within the
current transaction, usually when using a `flatMap` or `switch`. In these cases,
using `now` explicitly models that the emission will occur within the current
transaction.

``` kotlin
fun <T> FrpTransactionScope.tFlowOf(value: T): TFlow<T> = now.map { value }
```

## MutableStateFlow / MutableSharedFlow

Use `MutableTState(frpNetwork, initialValue)` and `MutableTFlow(frpNetwork)`.
