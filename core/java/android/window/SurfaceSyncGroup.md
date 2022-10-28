## SurfaceSyncGroup

### Overview

A generic way for data to be gathered so multiple surfaces can be synced. This is intended to be used with Views, SurfaceView, and any other surface that wants to be involved in a sync. This allows different parts of the Android system to synchronize different windows and layers themselves.

### Code

SurfaceSyncGroup is a class that manages sync requests and reports back when all participants in the sync are ready.

##### Constructor
The first step is to create a sync request. This is done by creating a new `SurfaceSyncGroup`.
There are two constructors: one that accepts a `Consumer<Transaction>` and one that's empty. The empty constructor will automatically apply the final transaction. The second constructor should only be used by ViewRootImpl. The purpose of this one is to allow the caller to get back the merged transaction without it being applied. ViewRootImpl uses it to send the transaction to WindowManagerService to be synced there. Using this one for other cases is unsafe because the caller may hold the transaction longer than expected and prevent buffers from being latched and released.

##### addToSync

The caller will invoke `addToSync` for every `SurfaceSyncGroup` that it wants included. There are a few helper methods since the most common cases are Views and SurfaceView
* `addToSync(AttachedSurfaceControl)` - This is used for syncing the root of the View, specificially the ViewRootImpl
* `addToSync(SurfaceView, Consumer<SurfaceViewFrameCallback>)` - This is to sync a SurfaceView. Since SurfaceViews are rendered by the app, the caller will be expected to provide a way to get back the buffer to sync. More details about that [below](#surfaceviewframecallback)
* `addToSync(SurfaceControlViewHost.SurfacePackage)` - This is to sync an embedded window. The host can call addToSync and pass in the SurfacePackage, where the SurfaceSyncGroup will ensure it's added to the sync.
* `addToSync(ISurfaceSyncGroup)` - This is the generic method. It can be used to sync arbitrary info. Most likely the caller will pass in a SurfaceSyncGroup object and then they are responsible for calling markSyncReady for the child SurfaceSyncGroup.

When calling addToSync with either AttachedSurfaceControl or SurfaceView, it must be called on the UI Thread. This is to ensure consistent behavior, where any UI changes done while still on the UI thread are included in this frame. The next vsync will pick up those changes and request to draw.

An additional Runnable argument can be passed in which ensures the Runnable has executed before adding the child SurfaceSyncGroup to the parent. The purpose of this Runnable is to execute any changes the caller wants and they are guaranteed to be picked up in the sync.

##### markSyncReady

When the caller has added all the `SurfaceSyncGroup` to the sync, they should call `markSyncReady()` If the caller doesn't call this, the sync will never complete since the SurfaceSyncGroup wants to give the caller a chance to add SurfaceSyncGroups before considering the sync ready. Before `markSyncReady` is called, the `SurfaceSyncGroups` can actually produce a frame, which will just be held in a transaction until all other `SurfaceSyncGroup` are ready AND `markSyncReady` has been called. Once markSyncReady has been called, you cannot add any more `SurfaceSyncGroup` to that particular SurfaceSyncGroup.

##### addTransactionToSync

This is a simple method that allows callers to add generic Transactions to the sync. The caller invokes `addTransactionToSync(Transaction)`. This can be used for any additional things that need to be included in the same SyncGroup.

##### addSyncCompleteCallback

This allows callers to receive a callback when the sync is complete. The caller will only receive a complete callback when the specific SurfaceSyncGroup it registered with is complete. This means that the SurfaceSyncGroup has been marked as ready and all children are complete. This doesn't mean the transaction has been applied since it could be passed to a parent SurfaceSyncGroup or passed to another process. This can be helpful if the caller wants to know that all the children have rendered their frame and possibly to ensure they can pace. The method takes in an Executor and a Runnable that will be invoked when the SurfaceSyncGroup has completed. The Executor is used to invoke the callback on the desired thread. You can add more than one callback.

##### SurfaceViewFrameCallback

As mentioned above, SurfaceViews are a special case because the buffers produced are handled by the app, and not the framework. Because of this, the SurfaceSyncGroup doesn't know which frame to sync. Therefore, to sync SurfaceViews, the caller must provide a way to notify the SurfaceSyncGroup that it's going to render a buffer and that this next buffer is the one to sync. The `SurfaceViewFrameCallback` has one method `onFrameStarted()`. When this is invoked, the SurfaceSyncGroup sets up a request to sync the next buffer for the SurfaceView.

#### ViewRootImpl's SurfaceSyncGroup

The most common way to use SurfaceSyncGroups is to sync multiple ViewRootImpls. The framework handles the timing and rendering of the ViewRootImpl, so it's expected that the caller doesn't need to know about this to ensure it's properly synced. This is done by the following flow.

When ViewRootImpl is added to a SurfaceSyncGroup, it's done so via `addToSync(AttachedSurfaceControl)`. The SurfaceSyncGroup code will call into ViewRootImpl, where either a new SurfaceSyncGroup is created or it returns an already active SurfaceSyncGroup. This active SurfaceSyncGroup is stored in ViewRootImpl and is tied to a rendering cycle. If multiple syncs are requested before VRI draws, they will all get back the same SurfaceSyncGroup object. This is to ensure we can make multiple changes without having to queue up several frames and causing things to get backed up. For example, if SurfaceSyncGroup1 wants to resize VRI and then SurfaceSyncGroup2 wants to change an attribute, if they are both done before a frame is drawn, then both those changes are picked up together. Once VRI starts a draw pass, it will clear the reference to the active SurfaceSyncGroup so any new changes that want to be synced are done with a new SurfaceSyncGroup object. The old active SurfaceSyncGroup is passed via lambda when the rendering is occuring. Once RenderThread completes the frame, VRI calls `SurfaceSyncGroup.addTransactionToSync(Transaction)`, passing in the transaction that contains the buffer and then `SurfaceSyncGroup.markSyncReady()` so the VRI SurfaceSyncGroup knows it's complete. This way we can start additional syncs without having to wait for the RenderThread to complete.

#### SurfaceSyncGroup added to Multiple SurfaceSyncGroups
There are cases where multiple places are trying to sync the same object. This is more common with VRI where you may have multiple changes for the same vsync rendering cycle, but different places requested the sync. Because each SurfaceSyncGroup may also contain other SurfaceSyncGroups, we need to combine everything. So for example, if SurfaceSyncGroup1 wants to sync VRI and SurfaceSyncGroup2 wants to sync VRI, but SSG-1 already contains SSG-A and SSG-2 already contains SSG-B, we need to ensure SSG-A, SSG-B, and VRI are all applied together to ensure  the contract is held. This is done by creating a tree like structure and merging parents when needed. VRI will be added to SSG-1. Then SSG-2 also requests to sync  VRI. VRI's SSG will see that it already is part of a SSG and keeps track of its last parent. The SSG will call newParentSyncGroup.add(oldParentSyncGroup). VRI is now added to both SSG-1 and SSG-2, but will only send it's transaction to SSG-2 (the one it was added to last). SSG-1 is also now added to SSG-2 so it will not apply it's transaction, but send it to SSG-2 to apply. SSG-2 will end up waiting for SSG-1 which is waiting for SSG-A so we know that SSG-2 will have the final transaction that includes everything from SSG-1 and its own children.

#### WindowManagerService Involvement

The above works fine when everything is in process. However, we don't want to expose transactions that are tied to SurfaceControls to other processes. This is because they can inject any call if they have a way to get the SurfaceControl object. Instead, use WMS as an intermediary when trying to sync cross-process. The APIs from the client perspectives don't change and the callers don't have to worry about the implementation. This is all done via the SurfaceSyncGroup code. When SurfaceSyncGroup recognizes that a process is trying to add another SurfaceSyncGroup from a different process, it will not be able to add it locally. Instead, it will call into WMS and register a new SurfaceSyncGroup that will be managed by WMS. It then notifies the other process that it should add itself to this SSG that was created in WMS. The other process will also call into WMS and add its own SSG. WMS is now the parent of both SSG from different processes. This means both processes will send their transaction to WMS, which is secure. When the original SSG calls markSyncReady, it will also mark the SSG in WMS as ready so the WMS created SSG can apply the transaction when all children have completed.


### Example

A simple example where you want to sync two windows and also include a transaction in the sync

```java
SurfaceSyncGroup syncGroup = new SurfaceSyncGroup(NAME);
SyncGroup.addSyncCompleteCallback(mMainThreadExecutor, () -> {
    Log.d(TAG, "syncComplete");
};
syncGroup.addToSync(view1.getRootSurfaceControl());
syncGroup.addToSync(view2.getRootSurfaceControl());
syncGroup.addTransactionToSync(transaction);
syncGroup.markSyncReady();
```

A SurfaceView example:

See `frameworks/base/tests/SurfaceViewSyncTest` for a working example

```java
SurfaceSyncGroup syncGroup = new SurfaceSyncGroup(NAME);
syncGroup.addSyncCompleteCallback(mMainThreadExecutor, () -> {
    Log.d(TAG, "syncComplete");
};
syncGroup.addToSync(container.getRootSurfaceControl());
syncGroup.addToSync(surfaceView, frameCallback -> {
    // Call this when the SurfaceView is ready to render a new frame with the changes.
    frameCallback.onFrameStarted()
}
syncGroup.markSyncReady();
```

A SurfaceControlViewHost example (also an cross process example):

See `frameworks/base/tests/SurfaceControlViewHostTest/.../SurfaceControlViewHostSyncTest.java` for working example

This would sync both the view resize and the embedded resize in the same frame
```java
SurfaceSyncGroup syncGroup = new SurfaceSyncGroup(NAME);
SyncGroup.addSyncCompleteCallback(mMainThreadExecutor, () -> {
    Log.d(TAG, "syncComplete");
};
syncGroup.addToSync(getWindow().getRootSurfaceControl(), () -> {
    view.getLayoutParams().width = 20;
    view.getLayoutParams().height = 40;
    view.requestLayout();
});
syncGroup.addToSync(mSurfacePackage, () -> {
    // Side channel API that's decided between the two processes
    mEmbedded.resize(20, 40);
});
syncGroup.markSyncReady();
```