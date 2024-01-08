# MediaProjection

## Locking model
`MediaProjectionManagerService` needs to have consistent lock ordering with its interactions with
`WindowManagerService` to prevent deadlock.

### TLDR
`MediaProjectionManagerService` must lock when updating its own fields.

Calls must follow the below invocation order while holding locks:

`WindowManagerService -> MediaProjectionManagerService -> DisplayManagerService`

`MediaProjectionManagerService` should never lock when calling into a service that may acquire
the `WindowManagerService` global lock (for example,
`MediaProjectionManagerService -> ActivityManagerService` may result in deadlock, since
`ActivityManagerService -> WindowManagerService`).

### Justification

`MediaProjectionManagerService` calls into `WindowManagerService` in the below cases. While handling
each invocation, `WindowManagerService` acquires its own lock:
* setting a `ContentRecordingSession`
  * starting a new `MediaProjection` recording session through
`MediaProjection#createVirtualDisplay`
  * indicating the user has granted consent to reuse the consent token

`WindowManagerService` calls into `MediaProjectionManagerService`, always while holding
`WindowManagerGlobalLock`:
* `ContentRecorder` handling various events such as resizing recorded content


Since `WindowManagerService -> MediaProjectionManagerService` is guaranteed to always hold the
`WindowManagerService` lock, we must ensure that `MediaProjectionManagerService ->
WindowManagerService` is NEVER holding the `MediaProjectionManagerService` lock.
