## SurfaceSyncer

### Overview

A generic way for data to be gathered so multiple surfaces can be synced. This is intended to be used with Views, SurfaceView, and any other surface that wants to be involved in a sync. This allows different parts of the Android system to synchronize different windows and layers themselves without having to go through WindowManagerService

### Code

SurfaceSyncer is a class that manages sync requests and reports back when all participants in the sync are ready.

##### setupSync
The first step is to create a sync request. This is done by calling `setupSync`.
There are two setupSync methods: one that accepts a `Runnable` and one that accepts a `Consumer<Transaction>`. The `setupSync(Runnable)` will automatically apply the final transaction and invokes the Runnable to notify the caller that the sync is complete. The `Runnable` can be null since not all cases care about the sync being complete. The second `setupSync(Consumer<Transaction>)` should only be used by ViewRootImpl. The purpose of this one is to allow the caller to get back the merged transaction without it being applied. ViewRootImpl uses it to send the transaction to WindowManagerService to be synced there. Using this one for other cases is unsafe because the caller may hold the transaction longer than expected and prevent buffers from being latched and released.

The setupSync returns a syncId which is used for the other APIs

##### markSyncReady

When the caller has added all the `SyncTarget` to the sync, they should call `markSyncReady(syncId)` If the caller doesn't call this, the sync will never complete since the SurfaceSyncer wants to give the caller a chance to add all the SyncTargets before considering the sync ready. Before `markSyncReady` is called, the `SyncTargets` can actually produce a frame, which will just be held in a transaction until all other `SyncTargets` are ready AND `markSyncReady` has been called. Once markSyncReady has been called, you cannot add any more `SyncTargets` to that particular syncId.

##### addToSync

The caller will invoke `addToSync` for every `SyncTarget` that it wants included. The caller must specify which syncId they want to add the `SyncTarget` too since there can be multiple syncs open at the same time. There are a few helper methods since the most common cases are Views and SurfaceView
* `addToSync(int syncId, View)` - This is used for syncing the root of the View, specificially the ViewRootImpl
* `addToSync(int syncId, SurfaceView, Consumer<SurfaceViewFrameCallback>)` - This is to sync a SurfaceView. Since SurfaceViews are rendered by the app, the caller will be expected to provide a way to get back the buffer to sync. More details about that [below](#surfaceviewframecallback)
* `addToSync(int syncId, SyncTarget)` - This is the generic method. It can be used to sync arbitrary info. The SyncTarget interface has required methods that need to be implemented to properly get the transaction to sync.

When calling addToSync with either View or SurfaceView, it must occur on the UI Thread. This is to ensure consistent behavior, where any UI changes done while still on the UI thread are included in this frame. The next vsync will pick up those changes and request to draw.

##### addTransactionToSync

This is a simple method that allows callers to add generic Transactions to the sync. The caller invokes `addTransactionToSync(int syncId, Transaction)`. This can be used for things that need to be synced with content, but aren't updating content themselves.

##### SyncTarget

This interface is used to handle syncs. The interface has two methods
* `onReadyToSync(SyncBufferCallback)` - This one must be implemented. The sync will notify the `SyncTarget` so it can invoke the `SyncBufferCallback`, letting the sync know when it's ready.
* `onSyncComplete()` - This method is optional. It's used to notify the `SyncTarget` that the entire sync is complete. This is similar to the callback sent in `setupSync`, but it's invoked to the `SyncTargets` rather than the caller who started the sync. This is used by ViewRootImpl to restore the state when the entire sync is done

When syncing ViewRootImpl, these methods are implemented already since ViewRootImpl handles the rendering requests and timing.

##### SyncBufferCallback

This interface is used to tell the sync that this SyncTarget is ready. There's only method here, `onBufferReady(Transaction)`, that sends back the transaction that contains the data to be synced, normally with a buffer.

##### SurfaceViewFrameCallback

As mentioned above, SurfaceViews are a special case because the buffers produced are handled by the app, and not the framework. Because of this, the syncer doesn't know which frame to sync. Therefore, to sync SurfaceViews, the caller must provide a way to notify the syncer that it's going to render a buffer and that this next buffer is the one to sync. The `SurfaceViewFrameCallback` has one method `onFrameStarted()`. When this is invoked, the Syncer sets up a request to sync the next buffer for the SurfaceView.


### Example

A simple example where you want to sync two windows and also include a transaction in the sync

```java
SurfaceSyncer syncer = new SurfaceSyncer();
int syncId = syncer.setupSync(() -> {
    Log.d(TAG, "syncComplete");
};
syncer.addToSync(syncId, view1);
syncer.addToSync(syncId, view2);
syncer.addTransactionToSync(syncId, transaction);
syncer.markSyncReady(syncId);
```

A SurfaceView example:
See `frameworks/base/tests/SurfaceViewSyncTest` for a working example

```java
SurfaceSyncer syncer = new SurfaceSyncer();
int syncId = syncer.setupSync(() -> {
    Log.d(TAG, "syncComplete");
};
syncer.addToSync(syncId, container);
syncer.addToSync(syncId, surfaceView, frameCallback -> {
    // Call this when the SurfaceView is ready to render a new frame with the changes.
    frameCallback.onFrameStarted()
}
syncer.markSyncReady(syncId);
```