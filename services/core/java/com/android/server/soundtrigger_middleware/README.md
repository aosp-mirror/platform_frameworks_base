# Sound Trigger Middleware
TODO: Add component description.

## Notes about thread synchronization
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
  stack may call into the HAL (specificall, they must invoke `stopRecognition()`, but must not block
  on callbacks. For this reason, `SoundTriggerHw2ConcurrentCaptureHandler`, which is the recipient
  of these calls, features a buffer and an additional thread, which allows the actual stopping to be
  synchronous, as required, without having to block the call upon higher layers processing the
  callbacks.
