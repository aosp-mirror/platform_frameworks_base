# Sound Trigger Middleware
TODO: Add component description.

## Notes about thread synchronization
This component has some tricky thread synchronization considerations due to its layered design and
due to the fact that it is involved in both in-bound and out-bound calls from / to
external components. To avoid potential deadlocks, a strict locking order must be ensured whenever
nesting locks. The order is:
- `SoundTriggerMiddlewareValidation` lock.
- Audio policy service lock. This one is external - it should be assumed to be held whenever we're
  inside the `ExternalCaptureStateTracker.setCaptureState()` call stack *AND* to be acquired from
  within our calls into `AudioSessionProvider.acquireSession()`.
- `SoundTriggerModule` lock.

This dictates careful consideration of callbacks going from `SoundTriggerModule` to
`SoundTriggerMiddlewareValidation` and especially those coming from the `setCaptureState()` path.
We always invoke those calls outside of the `SoundTriggerModule` lock, so we can lock
`SoundTriggerMiddlewareValidation`. However, in the `setCaptureState()` case, we have to use atomics
in `SoundTriggerMiddlewareValidation` and avoid the lock.
