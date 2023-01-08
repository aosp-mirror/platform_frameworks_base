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
        WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();
        int mDisplayId;
        IBinder mInputChannelToken;
        Region mInputRegion;
        IWindow mClient;
        State(SurfaceControl sc, WindowManager.LayoutParams p, int displayId,
              IBinder inputChannelToken, IWindow client) {
            mSurfaceControl = sc;
            mParams.copyFrom(p);
            mDisplayId = displayId;
            mInputChannelToken = inputChannelToken;
            mClient = client;
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
    private final IBinder mFocusGrantToken = new Binder();
    private InsetsState mInsetsState;
    private final ClientWindowFrames mTmpFrames = new ClientWindowFrames();
    private final MergedConfiguration mTmpConfig = new MergedConfiguration();

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

    IBinder getFocusGrantToken() {
        return mFocusGrantToken;
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
                            state.mInputRegion);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to update surface input channel: ", e);
                }
            }
        }
    }

    protected void attachToParentSurface(IWindow window, SurfaceControl.Builder b) {
        b.setParent(mRootSurface);
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
        final SurfaceControl.Builder b = new SurfaceControl.Builder(mSurfaceSession)
                .setFormat(attrs.format)
                .setBLASTLayer()
                .setName(attrs.getTitle().toString())
                .setCallsite("WindowlessWindowManager.addToDisplay");
        attachToParentSurface(window, b);
        final SurfaceControl sc = b.build();

        if (((attrs.inputFeatures &
                WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL) == 0)) {
            try {
                if (mRealWm instanceof IWindowSession.Stub) {
                    mRealWm.grantInputChannel(displayId,
                            new SurfaceControl(sc, "WindowlessWindowManager.addToDisplay"),
                            window, mHostInputToken,
                            attrs.flags, attrs.privateFlags, attrs.type, attrs.token,
                            mFocusGrantToken, attrs.getTitle().toString(), outInputChannel);
                } else {
                    mRealWm.grantInputChannel(displayId, sc, window, mHostInputToken, attrs.flags,
                            attrs.privateFlags, attrs.type, attrs.token, mFocusGrantToken,
                            attrs.getTitle().toString(), outInputChannel);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to grant input to surface: ", e);
            }
        }

        final State state = new State(sc, attrs, displayId,
            outInputChannel != null ? outInputChannel.getToken() : null, window);
        synchronized (this) {
            mStateForWindow.put(window.asBinder(), state);
        }
        outAttachedFrame.set(0, 0, -1, -1);
        outSizeCompatScale[0] = 1f;

        final int res = WindowManagerGlobal.ADD_OKAY | WindowManagerGlobal.ADD_FLAG_APP_VISIBLE |
                        WindowManagerGlobal.ADD_FLAG_USE_BLAST;

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
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();

        int attrChanges = 0;
        if (inAttrs != null) {
            attrChanges = state.mParams.copyFrom(inAttrs);
        }
        WindowManager.LayoutParams attrs = state.mParams;

        if (viewFlags == View.VISIBLE) {
            t.setOpaque(sc, isOpaque(attrs)).show(sc).apply();
            if (outSurfaceControl != null) {
                outSurfaceControl.copyFrom(sc, "WindowlessWindowManager.relayout");
            }
        } else {
            t.hide(sc).apply();
            if (outSurfaceControl != null) {
                outSurfaceControl.release();
            }
        }
        if (outFrames != null) {
            outFrames.frame.set(0, 0, attrs.width, attrs.height);
            outFrames.displayFrame.set(outFrames.frame);
        }

        if (outMergedConfiguration != null) {
            outMergedConfiguration.setConfiguration(mConfiguration, mConfiguration);
        }

        if ((attrChanges & WindowManager.LayoutParams.FLAGS_CHANGED) != 0
                && state.mInputChannelToken != null) {
            try {
                if(mRealWm instanceof IWindowSession.Stub) {
                    mRealWm.updateInputChannel(state.mInputChannelToken, state.mDisplayId,
                            new SurfaceControl(sc, "WindowlessWindowManager.relayout"),
                            attrs.flags, attrs.privateFlags, state.mInputRegion);
                } else {
                    mRealWm.updateInputChannel(state.mInputChannelToken, state.mDisplayId, sc,
                            attrs.flags, attrs.privateFlags, state.mInputRegion);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to update surface input channel: ", e);
            }
        }

        if (outInsetsState != null && mInsetsState != null) {
            outInsetsState.set(mInsetsState);
        }

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
    public void prepareToReplaceWindows(android.os.IBinder appToken, boolean childrenOnly) {
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
    public void reportKeepClearAreasChanged(android.view.IWindow window, List<Rect> restrictedRects,
            List<Rect> unrestrictedRects) {
    }

    @Override
    public void grantInputChannel(int displayId, SurfaceControl surface, IWindow window,
            IBinder hostInputToken, int flags, int privateFlags, int type,
            IBinder windowToken, IBinder focusGrantToken, String inputHandleName,
            InputChannel outInputChannel) {
    }

    @Override
    public void updateInputChannel(IBinder channelToken, int displayId, SurfaceControl surface,
            int flags, int privateFlags, Region region) {
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
}
