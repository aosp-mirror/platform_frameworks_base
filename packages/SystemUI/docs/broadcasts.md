# Using SystemUI's BroadcastDispatcher

## What is this dispatcher?

This is an internal dispatcher class for global broadcasts that SystemUI components want to receive. The dispatcher consolidates most `BroadcastReceiver` that exist in SystemUI by merging the `IntentFilter` and subscribing a single `BroadcastReceiver` per user with the system.

## Why use the dispatcher?

Having a single `BroadcastReceiver` in SystemUI improves the multi dispatch situation that occurs whenever many classes are filtering for the same intent action. In particular:
* All supported `BroadcastReceiver` will be aggregated into one single receiver per user.
* Whenever there is a broadcast, the number of IPC calls from `system_server` into SystemUI will be reduced to one per user (plus one for `USER_ALL`). This is meaninful for actions that are filtered by `BroadcastReceiver` in multiple classes.
*There could be more than one per user in the case of unsupported filters.*
* The dispatcher immediately moves out of the main thread upon broadcast, giving back control to `system_server`. This improves the total dispatch time for broadcasts and prevents from timing out.
* The dispatcher significantly reduces time spent in main thread by handling most operations in a background thread and only using the main thread for subscribing/unsubscribind and dispatching where appropriate.

## Should I use the dispatcher?

The dispatcher supports `BroadcastReceiver` dynamic subscriptions in the following cases:

* The `IntentFilter` contains at least one action.
* The `IntentFilter` may or may not contain categories.
* The `IntentFilter` **does not** contain data types, data schemes, data authorities or data paths.
* The broadcast **is not** gated behind a permission.

Additionally, the dispatcher supports the following:

* Subscriptions can be done in any thread.
* Broadcasts will be dispatched on the main thread (same as `system_server`) by default but a `Handler` can be specified for dispatching
* A `UserHandle` can be provided to filter the broadcasts by user.
* Flags (see [`Context#RegisterReceiverFlags`](/core/java/android/content/Context.java)) can be passed for the registration. By default, this will be `Context#RECEIVER_EXPORTED`.

If introducing a new `BroadcastReceiver` (not declared in `AndroidManifest`) that satisfies the constraints above, use the dispatcher to reduce the load on `system_server`.

Additionally, if listening to some broadcast is latency critical (beyond 100ms of latency), consider registering with Context instead.

### A note on sticky broadcasts

Sticky broadcasts are those that have been sent using `Context#sendStickyBroadcast` or `Context#sendStickyBroadcastAsUser`. In general they behave like regular broadcasts, but they are also cached (they may be replaced later) to provide the following two features:
 * They may be returned by `Context#registerReceiver` if the broadcast is matched by the `IntentFilter`. In case that multiple cached broadcast match the filter, any one of those may be returned.
 * All cached sticky broadcasts that match the filter will be sent to the just registered `BroadcastReceiver#onReceive`.

Sticky broadcasts are `@Deprecated` since API 24 and the general recommendation is to use regular broadcasts and API that allows to retrieve last known state.

Because of this and in order to provide the necessary optimizations, `BroadcastDispatcher` does not offer support for sticky intents:

* Do not use the dispatcher to obtain the last broadcast (by passing a null `BroadcastReceiver`). `BroadcastDispatcher#registerReceiver` **does not** return the last sticky Intent.
* Do not expect cached sticky broadcasts to be delivered on registration. This may happen but it's not guaranteed.

## How do I use the dispatcher?

Acquire the dispatcher by using `@Inject` to obtain a `BroadcastDispatcher`. Then, use the following methods in that instance. 

### Subscribe

```kotlin
/**
 * Register a receiver for broadcast with the dispatcher
 *
 * @param receiver A receiver to dispatch the [Intent]
 * @param filter A filter to determine what broadcasts should be dispatched to this receiver.
 *               It will only take into account actions and categories for filtering. It must
 *               have at least one action.
 * @param executor An executor to dispatch [BroadcastReceiver.onReceive]. Pass null to use an
 *                 executor in the main thread (default).
 * @param user A user handle to determine which broadcast should be dispatched to this receiver.
 *             Pass `null` to use the user of the context (system user in SystemUI).
 * @param flags Flags to use when registering the receiver. [Context.RECEIVER_EXPORTED] by
 *              default.             
 * @throws IllegalArgumentException if the filter has other constraints that are not actions or
 *                                  categories or the filter has no actions.
 */
@JvmOverloads
open fun registerReceiver(
    receiver: BroadcastReceiver,
    filter: IntentFilter,
    executor: Executor? = null,
    user: UserHandle? = null,
    @Context.RegisterReceiverFlags flags: Int = Context.RECEIVER_EXPORTED
)
```

All subscriptions are done with the same overloaded method. As specified in the doc, in order to pass a `UserHandle` with the default `Executor`, pass `null` for the `Executor`.

In the same way as with `Context`, subscribing the same `BroadcastReceiver` for the same user using different filters will result on two subscriptions, not in replacing the filter.

### Unsubscribe

There are two methods to unsubscribe a given `BroadcastReceiver`. One that will remove it for all users and another where the user can be specified. This allows using separate subscriptions of the same receiver for different users and manipulating them separately.

```kotlin
/**
    * Unregister receiver for all users.
    * <br>
    * This will remove every registration of [receiver], not those done just with [UserHandle.ALL].
    *
    * @param receiver The receiver to unregister. It will be unregistered for all users.
    */
fun unregisterReceiver(BroadcastReceiver)

/**
    * Unregister receiver for a particular user.
    *
    * @param receiver The receiver to unregister. It will be unregistered for all users.
    * @param user The user associated to the registered [receiver]. It can be [UserHandle.ALL].
    */
fun unregisterReceiverForUser(BroadcastReceiver, UserHandle)
```

Unregistering can be done even if the `BroadcastReceiver` has never been registered with `BroadcastDispatcher`. In that case, it is a No-Op.
