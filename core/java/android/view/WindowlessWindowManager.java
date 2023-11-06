/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view;

import android.annotation.Nullable;
import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.util.Log;
import android.util.MergedConfiguration;
import android.view.WindowInsets.Type.InsetsType;
import android.window.ClientWindowFrames;
import android.window.OnBackInvokedCallbackInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
* A simplistic implementation of IWindowSession. Rather than managing Surfaces
* as children of the display, it manages Surfaces as children of a given root.
*
* By parcelling the root surface, the app can offer another app content for embedding.
* @hide
*/
public class WindowlessWindowManager implements IWindowSession {
    private final static String TAG = "WindowlessWindowManager";

    private class State {
        SurfaceControl mSurfaceControl;
        final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();
        final WindowManager.LayoutParams mLastReportedParams = new WindowManager.LayoutParams();
        int mDisplayId;
        IBinder mInputChannelToken;
        Region mInputRegion;
        IWindow mClient;
        SurfaceControl mLeash;
        Rect mFrame;
        Rect mAttachedFrame;
        IBinder mInputTransferToken;

        State(SurfaceControl sc, WindowManager.LayoutParams p, int displayId, IWindow client,
                SurfaceControl leash, Rect frame) {
            mSurfaceControl = sc;
            mParams.copyFrom(p);
            mDisplayId = displayId;
            mClient = client;
            mLeash = leash;
            mFrame = frame;
        }
    };

    /**
     * Used to store SurfaceControl we've built for clients to
     * reconfigure them if relayout is called.
     */
    final HashMap<IBinder, State> mStateForWindow = new HashMap<IBinder, State>();

    public interface ResizeCompleteCallback {
        public void finished(SurfaceControl.Transaction completion);
    }

    final HashMap<IBinder, ResizeCompleteCallback> mResizeCompletionForWindow =
        new HashMap<IBinder, ResizeCompleteCallback>();

    private final SurfaceSession mSurfaceSession = new SurfaceSession();
    protected final SurfaceControl mRootSurface;
    private final Configuration mConfiguration;
    private final IWindowSession mRealWm;
    private final IBinder mHostInputToken;
    private final IBinder mInputTransferToken = new Binder();
    private InsetsState mInsetsState;
    private final ClientWindowFrames mTmpFrames = new ClientWindowFrames();
    private final MergedConfiguration mTmpConfig = new MergedConfiguration();
    private final WindowlessWindowLayout mLayout = new WindowlessWindowLayout();

    private ISurfaceControlViewHostParent mParentInterface;

    public WindowlessWindowManager(Configuration c, SurfaceControl rootSurface,
            IBinder hostInputToken) {
        mRootSurface = rootSurface;
        mConfiguration = new Configuration(c);
        mRealWm = WindowManagerGlobal.getWindowSession();
        mHostInputToken = hostInputToken;
    }

    public void setConfiguration(Configuration configuration) {
        mConfiguration.setTo(configuration);
    }

    IBinder getInputTransferToken(IBinder window) {
        synchronized (this) {
            // This can only happen if someone requested the focusGrantToken before setView was
            // called for the SCVH. In that case, use the root focusGrantToken since this will be
            // the same token sent to WMS for the root window once setView is called.
            if (mStateForWindow.isEmpty()) {
                return mInputTransferToken;
            }
            State state = mStateForWindow.get(window);
            if (state != null) {
                return state.mInputTransferToken;
            }
        }

        Log.w(TAG, "Failed to get focusGrantToken. Returning null token");
        return null;
    }

    /**
     * Utility API.
     */
    void setCompletionCallback(IBinder window, ResizeCompleteCallback callback) {
        if (mResizeCompletionForWindow.get(window) != null) {
            Log.w(TAG, "Unsupported overlapping resizes");
        }
        mResizeCompletionForWindow.put(window, callback);
    }

    protected void setTouchRegion(IBinder window, @Nullable Region region) {
        State state;
        synchronized (this) {
            // Do everything while locked so that we synchronize with relayout. This should be a
            // very infrequent operation.
            state = mStateForWindow.get(window);
            if (state == null) {
                return;
            }
            if (Objects.equals(region, state.mInputRegion)) {
                return;
            }
            state.mInputRegion = region != null ? new Region(region) : null;
            if (state.mInputChannelToken != null) {
                try {
                    mRealWm.updateInputChannel(state.mInputChannelToken, state.mDisplayId,
                            state.mSurfaceControl, state.mParams.flags, state.mParams.privateFlags,
                            state.mParams.inputFeatures, state.mInputRegion);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to update surface input channel: ", e);
                }
            }
        }
    }

    protected SurfaceControl getParentSurface(IWindow window, WindowManager.LayoutParams attrs) {
        // If this is the first window, the state map is empty and the parent surface is the
        // root. Otherwise, the parent surface is in the state map.
        synchronized (this) {
            if (mStateForWindow.isEmpty()) {
                return mRootSurface;
            }
            return mStateForWindow.get(attrs.token).mLeash;
        }
    }

    /**
     * IWindowSession implementation.
     */
    @Override
    public int addToDisplay(IWindow window, WindowManager.LayoutParams attrs,
            int viewVisibility, int displayId, @InsetsType int requestedVisibleTypes,
            InputChannel outInputChannel, InsetsState outInsetsState,
            InsetsSourceControl.Array outActiveControls, Rect outAttachedFrame,
            float[] outSizeCompatScale) {
        final SurfaceControl leash = new SurfaceControl.Builder(mSurfaceSession)
                .setName(attrs.getTitle().toString() + "Leash")
                .setCallsite("WindowlessWindowManager.addToDisplay")
                .setParent(getParentSurface(window, attrs))
                .build();

        final SurfaceControl sc = new SurfaceControl.Builder(mSurfaceSession)
                .setFormat(attrs.format)
                .setBLASTLayer()
                .setName(attrs.getTitle().toString())
                .setCallsite("WindowlessWindowManager.addToDisplay")
                .setHidden(false)
                .setParent(leash)
                .build();

        final State state = new State(sc, attrs, displayId, window, leash, /* frame= */ new Rect());
        synchronized (this) {
            State parentState = mStateForWindow.get(attrs.token);
            if (parentState != null) {
                state.mAttachedFrame = parentState.mFrame;
            }

            // Give the first window the mFocusGrantToken since that's the token the host can use
            // to give focus to the embedded.
            if (mStateForWindow.isEmpty()) {
                state.mInputTransferToken = mInputTransferToken;
            } else {
                state.mInputTransferToken = new Binder();
            }

            mStateForWindow.put(window.asBinder(), state);
        }

        if (state.mAttachedFrame == null) {
            outAttachedFrame.set(0, 0, -1, -1);
        } else {
            outAttachedFrame.set(state.mAttachedFrame);
        }
        outSizeCompatScale[0] = 1f;

        if (((attrs.inputFeatures &
                WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL) == 0)) {
            try {
                if (mRealWm instanceof IWindowSession.Stub) {
                    mRealWm.grantInputChannel(displayId,
                            new SurfaceControl(sc, "WindowlessWindowManager.addToDisplay"),
                            window.asBinder(), mHostInputToken, attrs.flags, attrs.privateFlags,
                            attrs.inputFeatures, attrs.type,
                            attrs.token, state.mInputTransferToken, attrs.getTitle().toString(),
                            outInputChannel);
                } else {
                    mRealWm.grantInputChannel(displayId, sc, window.asBinder(), mHostInputToken,
                            attrs.flags, attrs.privateFlags, attrs.inputFeatures, attrs.type,
                            attrs.token, state.mInputTransferToken, attrs.getTitle().toString(),
                            outInputChannel);
                }
                state.mInputChannelToken =
                        outInputChannel != null ? outInputChannel.getToken() : null;
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to grant input to surface: ", e);
            }
        }

        final int res = WindowManagerGlobal.ADD_OKAY | WindowManagerGlobal.ADD_FLAG_APP_VISIBLE |
                        WindowManagerGlobal.ADD_FLAG_USE_BLAST;

        sendLayoutParamsToParent();
        // Include whether the window is in touch mode.
        return isInTouchModeInternal(displayId) ? res | WindowManagerGlobal.ADD_FLAG_IN_TOUCH_MODE
                : res;
    }

    /**
     * IWindowSession implementation. Currently this class doesn't need to support for multi-user.
     */
    @Override
    public int addToDisplayAsUser(IWindow window, WindowManager.LayoutParams attrs,
            int viewVisibility, int displayId, int userId, @InsetsType int requestedVisibleTypes,
            InputChannel outInputChannel, InsetsState outInsetsState,
            InsetsSourceControl.Array outActiveControls, Rect outAttachedFrame,
            float[] outSizeCompatScale) {
        return addToDisplay(window, attrs, viewVisibility, displayId, requestedVisibleTypes,
                outInputChannel, outInsetsState, outActiveControls, outAttachedFrame,
                outSizeCompatScale);
    }

    @Override
    public int addToDisplayWithoutInputChannel(android.view.IWindow window,
            android.view.WindowManager.LayoutParams attrs, int viewVisibility, int layerStackId,
            android.view.InsetsState insetsState, Rect outAttachedFrame,
            float[] outSizeCompatScale) {
        return 0;
    }

    @Override
    public void remove(android.view.IWindow window) throws RemoteException {
        mRealWm.remove(window);
        State state;
        synchronized (this) {
            state = mStateForWindow.remove(window.asBinder());
        }
        if (state == null) {
            throw new IllegalArgumentException(
                    "Invalid window token (never added or removed already)");
        }
        removeSurface(state.mSurfaceControl);
        removeSurface(state.mLeash);
    }

    /** Separate from {@link #remove} so that subclasses can put removal on a sync transaction. */
    protected void removeSurface(SurfaceControl sc) {
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            t.remove(sc).apply();
        }
    }

    private boolean isOpaque(WindowManager.LayoutParams attrs) {
        if (attrs.surfaceInsets != null && attrs.surfaceInsets.left != 0 ||
                attrs.surfaceInsets.top != 0 || attrs.surfaceInsets.right != 0 ||
                attrs.surfaceInsets.bottom != 0) {
            return false;
        }
        return !PixelFormat.formatHasAlpha(attrs.format);
    }

    private boolean isInTouchModeInternal(int displayId) {
        try {
            return WindowManagerGlobal.getWindowManagerService().isInTouchMode(displayId);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to check if the window is in touch mode", e);
        }
        return false;
    }

    /** Access to package members for SystemWindow leashing
     * @hide
     */
    protected IBinder getWindowBinder(View rootView) {
        final ViewRootImpl root = rootView.getViewRootImpl();
        if (root == null) {
            return null;
        }
        return root.mWindow.asBinder();
    }

    /** @hide */
    @Nullable
    protected SurfaceControl getSurfaceControl(View rootView) {
        final ViewRootImpl root = rootView.getViewRootImpl();
        if (root == null) {
            return null;
        }
        return getSurfaceControl(root.mWindow);
    }

    /** @hide */
    @Nullable
    protected SurfaceControl getSurfaceControl(IWindow window) {
        final State s = mStateForWindow.get(window.asBinder());
        if (s == null) {
            return null;
        }
        return s.mSurfaceControl;
    }

    @Override
    public int relayout(IWindow window, WindowManager.LayoutParams inAttrs,
            int requestedWidth, int requestedHeight, int viewFlags, int flags, int seq,
            int lastSyncSeqId, ClientWindowFrames outFrames,
            MergedConfiguration outMergedConfiguration, SurfaceControl outSurfaceControl,
            InsetsState outInsetsState, InsetsSourceControl.Array outActiveControls,
            Bundle outSyncSeqIdBundle) {
        final State state;
        synchronized (this) {
            state = mStateForWindow.get(window.asBinder());
        }
        if (state == null) {
            throw new IllegalArgumentException(
                    "Invalid window token (never added or removed already)");
        }
        SurfaceControl sc = state.mSurfaceControl;
        SurfaceControl leash = state.mLeash;
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();

        int attrChanges = 0;
        if (inAttrs != null) {
            attrChanges = state.mParams.copyFrom(inAttrs);
        }
        WindowManager.LayoutParams attrs = state.mParams;

        ClientWindowFrames frames = new ClientWindowFrames();
        frames.attachedFrame = state.mAttachedFrame;

        mLayout.computeFrames(attrs, null, null, null, WindowConfiguration.WINDOWING_MODE_UNDEFINED,
                requestedWidth, requestedHeight, 0, 0,
                frames);

        state.mFrame.set(frames.frame);
        if (outFrames != null) {
            outFrames.frame.set(frames.frame);
            outFrames.parentFrame.set(frames.parentFrame);
            outFrames.displayFrame.set(frames.displayFrame);
        }

        t.setPosition(leash, frames.frame.left, frames.frame.top);

        if (viewFlags == View.VISIBLE) {
            // TODO(b/262892794) ViewRootImpl modifies the app's rendering SurfaceControl
            // opaqueness. We shouldn't need to modify opaqueness for this SurfaceControl here or
            // in the real WindowManager.
            t.setOpaque(sc, isOpaque(attrs)).show(leash).apply();
            if (outSurfaceControl != null) {
                outSurfaceControl.copyFrom(sc, "WindowlessWindowManager.relayout");
            }
        } else {
            t.hide(leash).apply();
            if (outSurfaceControl != null) {
                outSurfaceControl.release();
            }
        }

        if (outMergedConfiguration != null) {
            outMergedConfiguration.setConfiguration(mConfiguration, mConfiguration);
        }

        final int inputChangeMask = WindowManager.LayoutParams.FLAGS_CHANGED
                | WindowManager.LayoutParams.INPUT_FEATURES_CHANGED;
        if ((attrChanges & inputChangeMask) != 0 && state.mInputChannelToken != null) {
            try {
                if (mRealWm instanceof IWindowSession.Stub) {
                    mRealWm.updateInputChannel(state.mInputChannelToken, state.mDisplayId,
                            new SurfaceControl(sc, "WindowlessWindowManager.relayout"),
                            attrs.flags, attrs.privateFlags, attrs.inputFeatures,
                            state.mInputRegion);
                } else {
                    mRealWm.updateInputChannel(state.mInputChannelToken, state.mDisplayId, sc,
                            attrs.flags, attrs.privateFlags, attrs.inputFeatures,
                            state.mInputRegion);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to update surface input channel: ", e);
            }
        }

        if (outInsetsState != null && mInsetsState != null) {
            outInsetsState.set(mInsetsState);
        }

        sendLayoutParamsToParent();
        return 0;
    }

    @Override
    public void relayoutAsync(IWindow window, WindowManager.LayoutParams inAttrs,
            int requestedWidth, int requestedHeight, int viewFlags, int flags, int seq,
            int lastSyncSeqId) {
        relayout(window, inAttrs, requestedWidth, requestedHeight, viewFlags, flags, seq,
                lastSyncSeqId, null /* outFrames */, null /* outMergedConfiguration */,
                null /* outSurfaceControl */, null /* outInsetsState */,
                null /* outActiveControls */, null /* outSyncSeqIdBundle */);
    }

    @Override
    public boolean outOfMemory(android.view.IWindow window) {
        return false;
    }

    @Override
    public void setInsets(android.view.IWindow window, int touchableInsets,
            android.graphics.Rect contentInsets, android.graphics.Rect visibleInsets,
            android.graphics.Region touchableRegion) {
        setTouchRegion(window.asBinder(), touchableRegion);
    }

    @Override
    public void clearTouchableRegion(android.view.IWindow window) {
        setTouchRegion(window.asBinder(), null);
    }

    @Override
    public void finishDrawing(android.view.IWindow window,
            android.view.SurfaceControl.Transaction postDrawTransaction, int seqId) {
        synchronized (this) {
            final ResizeCompleteCallback c =
                mResizeCompletionForWindow.get(window.asBinder());
            if (c == null) {
                // No one wanted the callback, but it wasn't necessarily unexpected.
                postDrawTransaction.apply();
                return;
            }
            c.finished(postDrawTransaction);
            mResizeCompletionForWindow.remove(window.asBinder());
        }
    }

    @Override
    public boolean performHapticFeedback(int effectId, boolean always) {
        return false;
    }

    @Override
    public void performHapticFeedbackAsync(int effectId, boolean always) {
        performHapticFeedback(effectId, always);
    }

    @Override
    public android.os.IBinder performDrag(android.view.IWindow window, int flags,
            android.view.SurfaceControl surface, int touchSource, float touchX, float touchY,
            float thumbCenterX, float thumbCenterY, android.content.ClipData data) {
        return null;
    }

    @Override
    public void reportDropResult(android.view.IWindow window, boolean consumed) {
    }

    @Override
    public void cancelDragAndDrop(android.os.IBinder dragToken, boolean skipAnimation) {
    }

    @Override
    public void dragRecipientEntered(android.view.IWindow window) {
    }

    @Override
    public void dragRecipientExited(android.view.IWindow window) {
    }

    @Override
    public void setWallpaperPosition(android.os.IBinder windowToken, float x, float y,
            float xstep, float ystep) {
    }

    @Override
    public void setWallpaperZoomOut(android.os.IBinder windowToken, float zoom) {
    }

    @Override
    public void setShouldZoomOutWallpaper(android.os.IBinder windowToken, boolean shouldZoom) {
    }

    @Override
    public void wallpaperOffsetsComplete(android.os.IBinder window) {
    }

    @Override
    public void setWallpaperDisplayOffset(android.os.IBinder windowToken, int x, int y) {
    }

    @Override
    public android.os.Bundle sendWallpaperCommand(android.os.IBinder window,
            java.lang.String action, int x, int y, int z, android.os.Bundle extras, boolean sync) {
        return null;
    }

    @Override
    public void wallpaperCommandComplete(android.os.IBinder window, android.os.Bundle result) {
    }

    @Override
    public void onRectangleOnScreenRequested(android.os.IBinder token,
            android.graphics.Rect rectangle) {
    }

    @Override
    public android.view.IWindowId getWindowId(android.os.IBinder window) {
        return null;
    }

    @Override
    public void pokeDrawLock(android.os.IBinder window) {
    }

    @Override
    public boolean startMovingTask(android.view.IWindow window, float startX, float startY) {
        return false;
    }

    @Override
    public void finishMovingTask(android.view.IWindow window) {
    }

    @Override
    public void updatePointerIcon(android.view.IWindow window) {
    }

    @Override
    public void updateTapExcludeRegion(android.view.IWindow window,
            android.graphics.Region region) {
    }

    @Override
    public void updateRequestedVisibleTypes(IWindow window,
            @InsetsType int requestedVisibleTypes)  {
    }

    @Override
    public void reportSystemGestureExclusionChanged(android.view.IWindow window,
            List<Rect> exclusionRects) {
    }

    @Override
    public void reportDecorViewGestureInterceptionChanged(IWindow window, boolean intercepted) {}

    @Override
    public void reportKeepClearAreasChanged(
            android.view.IWindow window,
            List<Rect> restrictedRects,
            List<Rect> unrestrictedRects) {}

    @Override
    public void grantInputChannel(int displayId, SurfaceControl surface, IBinder clientToken,
            IBinder hostInputToken, int flags, int privateFlags, int inputFeatures, int type,
            IBinder windowToken, IBinder focusGrantToken, String inputHandleName,
            InputChannel outInputChannel) {
    }

    @Override
    public void updateInputChannel(IBinder channelToken, int displayId, SurfaceControl surface,
            int flags, int privateFlags, int inputFeatures, Region region) {
    }

    @Override
    public android.os.IBinder asBinder() {
        return null;
    }

    @Override
    public void grantEmbeddedWindowFocus(IWindow callingWindow, IBinder targetInputToken,
                                         boolean grantFocus) {
    }

    @Override
    public void generateDisplayHash(IWindow window, Rect boundsInWindow, String hashAlgorithm,
            RemoteCallback callback) {
    }

    @Override
    public void setOnBackInvokedCallbackInfo(IWindow iWindow,
            OnBackInvokedCallbackInfo callbackInfo) throws RemoteException { }

    @Override
    public boolean dropForAccessibility(IWindow window, int x, int y) {
        return false;
    }

    public void setInsetsState(InsetsState state) {
        mInsetsState = state;
        for (State s : mStateForWindow.values()) {
            try {
                mTmpFrames.frame.set(0, 0, s.mParams.width, s.mParams.height);
                mTmpFrames.displayFrame.set(mTmpFrames.frame);
                mTmpConfig.setConfiguration(mConfiguration, mConfiguration);
                s.mClient.resized(mTmpFrames, false /* reportDraw */, mTmpConfig, state,
                        false /* forceLayout */, false /* alwaysConsumeSystemBars */, s.mDisplayId,
                        Integer.MAX_VALUE, false /* dragResizing */);
            } catch (RemoteException e) {
                // Too bad
            }
        }
    }

    @Override
    public boolean cancelDraw(IWindow window) {
        return false;
    }

    @Override
    public boolean transferEmbeddedTouchFocusToHost(IWindow window) {
        Log.e(TAG, "Received request to transferEmbeddedTouch focus on WindowlessWindowManager" +
            " we shouldn't get here!");
        return false;
    }

    @Override
    public boolean transferHostTouchGestureToEmbedded(IWindow hostWindow,
            IBinder embeddedInputToken) {
        Log.e(TAG, "Received request to transferHostTouchGestureToEmbedded on"
                + " WindowlessWindowManager. We shouldn't get here!");
        return false;
    }

    void setParentInterface(@Nullable ISurfaceControlViewHostParent parentInterface) {
        IBinder oldInterface = mParentInterface == null ? null : mParentInterface.asBinder();
        IBinder newInterface = parentInterface == null ? null : parentInterface.asBinder();
        // If the parent interface has changed, it needs to clear the last reported params so it
        // will update the new interface with the params.
        if (oldInterface != newInterface) {
            clearLastReportedParams();
        }
        mParentInterface = parentInterface;
        sendLayoutParamsToParent();
    }

    private void clearLastReportedParams() {
        WindowManager.LayoutParams emptyParam = new WindowManager.LayoutParams();
        for (State windowInfo : mStateForWindow.values()) {
            windowInfo.mLastReportedParams.copyFrom(emptyParam);
        }
    }

    private void sendLayoutParamsToParent() {
        if (mParentInterface == null) {
            return;
        }
        WindowManager.LayoutParams[] params =
                new WindowManager.LayoutParams[mStateForWindow.size()];
        int index = 0;
        boolean hasChanges = false;
        for (State windowInfo : mStateForWindow.values()) {
            int changes = windowInfo.mLastReportedParams.copyFrom(windowInfo.mParams);
            hasChanges |= (changes != 0);
            params[index++] = windowInfo.mParams;
        }

        if (hasChanges) {
            try {
                mParentInterface.updateParams(params);
            } catch (RemoteException e) {
            }
        }
    }
}
