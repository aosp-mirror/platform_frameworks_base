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

import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.MergedConfiguration;
import android.view.IWindowSession;

import java.util.HashMap;

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
        State(SurfaceControl sc, WindowManager.LayoutParams p, int displayId,
                IBinder inputChannelToken) {
            mSurfaceControl = sc;
            mParams.copyFrom(p);
            mDisplayId = displayId;
            mInputChannelToken = inputChannelToken;
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
    private final SurfaceControl mRootSurface;
    private final Configuration mConfiguration;
    private final IWindowSession mRealWm;
    private final IBinder mHostInputToken;

    private int mForceHeight = -1;
    private int mForceWidth = -1;

    public WindowlessWindowManager(Configuration c, SurfaceControl rootSurface,
            IBinder hostInputToken) {
        mRootSurface = rootSurface;
        mConfiguration = new Configuration(c);
        mRealWm = WindowManagerGlobal.getWindowSession();
        mHostInputToken = hostInputToken;
    }

    protected void setConfiguration(Configuration configuration) {
        mConfiguration.setTo(configuration);
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

    /**
     * IWindowSession implementation.
     */
    @Override
    public int addToDisplay(IWindow window, int seq, WindowManager.LayoutParams attrs,
            int viewVisibility, int displayId, Rect outFrame, Rect outContentInsets,
            Rect outStableInsets,
            DisplayCutout.ParcelableWrapper outDisplayCutout, InputChannel outInputChannel,
            InsetsState outInsetsState) {
        final SurfaceControl.Builder b = new SurfaceControl.Builder(mSurfaceSession)
                .setParent(mRootSurface)
                .setFormat(attrs.format)
                .setName(attrs.getTitle().toString());
        final SurfaceControl sc = b.build();

        if (((attrs.inputFeatures &
                WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL) == 0)) {
            try {
                mRealWm.grantInputChannel(displayId, sc, window, mHostInputToken, attrs.flags,
                        outInputChannel);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to grant input to surface: ", e);
            }
        }

        final State state = new State(sc, attrs, displayId,
                outInputChannel != null ? outInputChannel.getToken() : null);
        synchronized (this) {
            mStateForWindow.put(window.asBinder(), state);
        }

        return WindowManagerGlobal.ADD_OKAY | WindowManagerGlobal.ADD_FLAG_APP_VISIBLE;
    }

    @Override
    public int addToDisplayWithoutInputChannel(android.view.IWindow window, int seq,
            android.view.WindowManager.LayoutParams attrs, int viewVisibility, int layerStackId,
            android.graphics.Rect outContentInsets, android.graphics.Rect outStableInsets,
            android.view.InsetsState insetsState) {
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
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            t.remove(state.mSurfaceControl).apply();
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

    /** @hide */
    protected SurfaceControl getSurfaceControl(View rootView) {
        final State s = mStateForWindow.get(rootView.getViewRootImpl().mWindow.asBinder());
        if (s == null) {
            return null;
        }
        return s.mSurfaceControl;
    }

    @Override
    public int relayout(IWindow window, int seq, WindowManager.LayoutParams inAttrs,
            int requestedWidth, int requestedHeight, int viewFlags, int flags, long frameNumber,
            Rect outFrame, Rect outContentInsets, Rect outVisibleInsets,
            Rect outStableInsets, Rect outBackdropFrame,
            DisplayCutout.ParcelableWrapper cutout, MergedConfiguration mergedConfiguration,
            SurfaceControl outSurfaceControl, InsetsState outInsetsState,
            Point outSurfaceSize, SurfaceControl outBLASTSurfaceControl) {
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

        final Rect surfaceInsets = attrs.surfaceInsets;
        int width = surfaceInsets != null ?
                attrs.width + surfaceInsets.left + surfaceInsets.right : attrs.width;
        int height = surfaceInsets != null ?
                attrs.height + surfaceInsets.top + surfaceInsets.bottom : attrs.height;

        t.setBufferSize(sc, width, height)
            .setOpaque(sc, isOpaque(attrs));
        if (viewFlags == View.VISIBLE) {
            t.show(sc);
        } else {
            t.hide(sc);
        }
        t.apply();
        outSurfaceControl.copyFrom(sc);
        outFrame.set(0, 0, attrs.width, attrs.height);

        mergedConfiguration.setConfiguration(mConfiguration, mConfiguration);

        if ((attrChanges & WindowManager.LayoutParams.FLAGS_CHANGED) != 0
                && state.mInputChannelToken != null) {
            try {
                mRealWm.updateInputChannel(state.mInputChannelToken, state.mDisplayId, sc,
                        attrs.flags);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to update surface input channel: ", e);
            }
        }

        return 0;
    }

    @Override
    public void prepareToReplaceWindows(android.os.IBinder appToken, boolean childrenOnly) {
    }

    @Override
    public boolean outOfMemory(android.view.IWindow window) {
        return false;
    }

    @Override
    public void setTransparentRegion(android.view.IWindow window, android.graphics.Region region) {
    }

    @Override
    public void setInsets(android.view.IWindow window, int touchableInsets,
            android.graphics.Rect contentInsets, android.graphics.Rect visibleInsets,
            android.graphics.Region touchableRegion) {
    }

    @Override
    public void getDisplayFrame(android.view.IWindow window,
            android.graphics.Rect outDisplayFrame) {
    }

    @Override
    public void finishDrawing(android.view.IWindow window,
            android.view.SurfaceControl.Transaction postDrawTransaction) {
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
    public void setInTouchMode(boolean showFocus) {
    }

    @Override
    public boolean getInTouchMode() {
        return false;
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
    public void reparentDisplayContent(android.view.IWindow window, android.view.SurfaceControl sc,
            int displayId) {
    }

    @Override
    public void updateDisplayContentLocation(android.view.IWindow window, int x, int y,
            int displayId) {
    }

    @Override
    public void updateTapExcludeRegion(android.view.IWindow window,
            android.graphics.Region region) {
    }

    @Override
    public void insetsModified(android.view.IWindow window, android.view.InsetsState state) {
    }

    @Override
    public void reportSystemGestureExclusionChanged(android.view.IWindow window,
            java.util.List<android.graphics.Rect> exclusionRects) {
    }

    @Override
    public void grantInputChannel(int displayId, SurfaceControl surface, IWindow window,
            IBinder hostInputToken, int flags, InputChannel outInputChannel) {
    }

    @Override
    public void updateInputChannel(IBinder channelToken, int displayId, SurfaceControl surface,
            int flags) {
    }

    @Override
    public android.os.IBinder asBinder() {
        return null;
    }
}
