# Sound Trigger Middleware

## Overview
Sound Trigger Middleware is a system service that exposes sound trigger functionality (low-power
detection of acoustic events) to applications and higher-level system service.

It has the following roles:
- Isolating the soundtrigger HAL from potentially untrusted clients.
- Enforcing correct behavior of the clients.
- Enforcing correct behavior of the HAL and attempting to recover from failures.
- Enforcing permissions for using soundtrigger functionality.
- Serializing access to the HAL.
- Logging soundtrigger usage in a comprehensive and consistent manner.
- Generating a dumpsys report including current state and history of operations.
- Providing a standard interface regardless which version of the HAL is implemented and gracefully
  degrading operation whenever necessary.

## Structure

The service implementation can be divided into three main layers:

- The "bottom layer" is concerned with HAL compatibility - making all HAL versions look and behave
  the same.
- The "middle layer" is concerned with the business logic of the service.
- The "top layer" is concerned with exposing this functionality as a System Service and integrating
  with other parts of the system.

### HAL Compatibility Layer

This layer implements the `ISoundTriggerHal` interface, which is the version-agnostic representation
of the sound trigger HAL driver. It has two main implementations, `SoundTriggerHw2Compat` and
`SoundTriggerHw3Compat` responsible for adapting to V2.x and V3 HAL drivers, respectively, including
supporting their respective minor-version differences.

This layer also includes several `ISoundTriggerHal` decorators, such as `SoundTriggerHalWatchdog`
that enforces deadlines on calls into the HAL, and `SoundTriggerHalEnforcer` which enforces that
the HAL respects the expected protocol.

The decorator-based design is an effective tool for separation of aspects and modularity, thus
keeping classes relatively small and focused on one concern. It is also very effective for
testability by following dependency injection principles.

### Business Logic Layer

This layer also uses a decorator-based design for separation of concerns. The main interface being
decorated is `ISoundTriggerMiddlwareInternal`, which closely follows the external-facing AIDL
interface, `ISoundTriggerMiddlewareService`.

Each of the decorators serves a focused purpose: for example, `SoundTriggerMiddlwarePermission`
deals with enforcing permissions required for the various methods, `SoundTriggerMiddlewareLogging`
logs all API usage, `SoundTriggerMiddlewareValidation` enforces correct usage of the protocol and
isolates client errors from internal server errors.

At the bottom of this decorator stack is `SoundTriggerMiddlewareImpl` / `SoundTriggerModule`, which
are the adapter between `ISoundTriggerHal` and `ISoundTriggerMiddlwareInternal`, introducing the
notion of having separate client sessions sharing the same HAL.

### Service Layer

This layer ties everything together. It instantiates the actual system service and the decorator
stack. It also provides concrete connections to the Audio service (for negotiating sessions shared
between Audio and Sound Trigger and for notifications about audio recording) and to the various HAL
factories.

This is the only layer that makes strong assumptions about the environment instead of relying on
abstractions.

## Error Handling and Exception Conventions

We follow conventions for usage of exceptions in the service, in order to correctly and consistently
distinguish the following cases:

1. The client has done something wrong.
2. The service implementation has done something wrong.
3. The HAL has done something wrong.
4. Nobody has done anything wrong, but runtime conditions prevent an operation from being fulfilled
  as intended.

The `SoundTriggerMiddlewarePermission` class would reject any calls from unauthorized clients,
responding with the appropriate exception.

The `SoundTriggerMiddlewareValidation` class does much of this separation. By validating the
client's data and state, it would throw a relevant `RuntimeException` exception to the client
without passing the requests down to the lower layers. Once that is done, any exception thrown from
the underlying implementation can be assumed to be not the client's fault. If caught, they will be
classified according to the following rule:

- If they are `RecoverableException`s, they represent category #4 above, and will be presented to
  the client as `ServiceSpecificException`s with the same error code.
- Otherwise, they are considered an internal error (including HAL malfunction) and will be
  presented to the client as `ServiceSpecificException(Status.INTERNAL_ERROR)`.

Internally, we would throw `RecoverableException` whenever appropriate. Whenever a HAL malfunctions,
`SoundTriggerHalEnforcer` is responsible for rebooting it and throwing an exception. A HAL death is
considered a valid failure mode, and thus result in `RecoverableException(Status.DEAD_OBJECT)`,
which ends up as a `ServiceSpecificException(Status.DEAD_OBJECT)` on the client side.

## Notes About Thread Synchronization
This component has some tricky thread synchronization considerations due to its layered design and
due to the fact that it is involved in both in-bound and out-bound calls from / to
external components.

The following mutexes need to be considered:
- Typically, a one or more mutexes that exist in every layer of the sound trigger middleware stack
  to serialize access to its internal state or to external components.
- Audio Policy Service lock. This one is external - it should be assumed to be held whenever we're
  inside the `ExternalCaptureStateTracker.setCaptureState()` call stack *AND* to be acquired from
  within our calls into `AudioSessionProvider.acquireSession()` /
  `AudioSessionProvider.releaseSession()`.

To avoid potential deadlocks, a strict locking order must be ensured whenever nesting locks. The
order is:
- Upper layers of the stack, starting from the top (i.e. may not attempt to acquire a higher-layer
  mutex while a lower-layer mutex is being held) until `ISoundTriggerHw2`.
- Audio Policy Service lock.
- Lower layers of the stack, starting from `ISoundTriggerHw2` all the way down to the HAL.

In order to enforce this order, some conventions are established around when it is safe for a module
to call another module, while having its local mutex(es) held:
- Most calls (see exceptions below) originating from SoundTriggerMiddlewareService simply propagate
  down the decorator stack. It is legal to call into the next layer down while holding a local
  mutex. It is illegal to invoke a callback with a local mutex held.
- Callbacks propagate from the lower layers up to the upper layers. It is legal to hold a local
  mutex within a callback, but **not** while call to an upper layer.
- In order to be able to synchronize, despite the asynchronous nature of callbacks,
  `stopRecognition()` and `unloadModel()` work differently. They guarantee that once they return,
  the callbacks associated with them will no longer be called. This implies that they have to block
  until any pending callbacks are done processing and since these callbacks are potentially holding
  locks of higher-order mutexes, we must not be holding a local mutex while calling down. The proper
  sequence for these calls is:
  - Obtain the local lock if needed. Update/check local state as necessary.
  - Call the respective method of the delegate ("downwards"). Once it returns, not more callbacks
    related to this operation will be called.
  - Obtain the local lock if needed. Update local state as necessary. Assume that state might have
    changed while the lock has been released.
  - Release the local lock.
  - Invoke any synchronous callbacks if needed.
- Calling from `SoundTriggerMiddlewareImpl` / `SoundTriggerModule` into the audio policy service via
  `acquireSession()` / `releaseSession()` while holding the local lock is legal.
- `setCaptureState()` calls, originating from Audio Policy Service, into the lower layers of the
  stack may call into the HAL (specifically, they must invoke `stopRecognition()`, but must not
  block on callbacks. For this reason, `SoundTriggerHw2ConcurrentCaptureHandler`, which is the 
  recipient of these calls, features a buffer and an additional thread, which allows the actual
  stopping to be synchronous, as required, without having to block the call upon higher layers
  processing the callbacks.
