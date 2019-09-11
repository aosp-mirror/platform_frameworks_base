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
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.MergedConfiguration;
import android.view.IWindowSession;
import android.view.SurfaceControl;
import android.view.SurfaceSession;

import java.util.HashMap;

/**
* A simplistic implementation of IWindowSession. Rather than managing Surfaces
* as children of the display, it manages Surfaces as children of a given root.
*
* By parcelling the root surface, the app can offer another app content for embedding.
* @hide
*/
class WindowlessWindowManager implements IWindowSession {
    private final static String TAG = "WindowlessWindowManager";

    /**
     * Used to store SurfaceControl we've built for clients to
     * reconfigure them if relayout is called.
     */
    final HashMap<IBinder, SurfaceControl> mScForWindow = new HashMap<IBinder, SurfaceControl>();
    final SurfaceSession mSurfaceSession = new SurfaceSession();
    final SurfaceControl mRootSurface;
    final Configuration mConfiguration;
    IWindowSession mRealWm;

    private int mForceHeight = -1;
    private int mForceWidth = -1;

    WindowlessWindowManager(Configuration c, SurfaceControl rootSurface) {
        mRootSurface = rootSurface;
        mConfiguration = new Configuration(c);
        mRealWm = WindowManagerGlobal.getWindowSession();
    }

    public int addToDisplay(IWindow window, int seq, WindowManager.LayoutParams attrs,
            int viewVisibility, int displayId, Rect outFrame, Rect outContentInsets,
            Rect outStableInsets, Rect outOutsets,
            DisplayCutout.ParcelableWrapper outDisplayCutout, InputChannel outInputChannel,
            InsetsState outInsetsState) {
        final SurfaceControl.Builder b = new SurfaceControl.Builder(mSurfaceSession)
            .setParent(mRootSurface)
            .setName(attrs.getTitle().toString());
        final SurfaceControl sc = b.build();
        synchronized (this) {
            mScForWindow.put(window.asBinder(), sc);
        }

        if ((attrs.inputFeatures &
                WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL) == 0) {
            try {
                mRealWm.blessInputSurface(displayId, sc, outInputChannel);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to bless surface: " + e);
            }
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
    public void remove(android.view.IWindow window) {}

    private boolean isOpaque(WindowManager.LayoutParams attrs) {
        if (attrs.surfaceInsets.left != 0 || attrs.surfaceInsets.top != 0 ||
                attrs.surfaceInsets.right != 0 || attrs.surfaceInsets.bottom != 0) {
            return false;
        }
        return !PixelFormat.formatHasAlpha(attrs.format);
    }

    @Override
    public int relayout(IWindow window, int seq, WindowManager.LayoutParams attrs,
            int requestedWidth, int requestedHeight, int viewFlags, int flags, long frameNumber,
            Rect outFrame, Rect outOverscanInsets, Rect outContentInsets, Rect outVisibleInsets,
            Rect outStableInsets, Rect outsets, Rect outBackdropFrame,
            DisplayCutout.ParcelableWrapper cutout, MergedConfiguration mergedConfiguration,
            SurfaceControl outSurfaceControl, InsetsState outInsetsState) {
        SurfaceControl sc = null;
        synchronized (this) {
            sc = mScForWindow.get(window.asBinder());
        }
        if (sc == null) {
            throw new IllegalArgumentException(
                    "Invalid window token (never added or removed already)");
        }
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        t.show(sc).setBufferSize(sc,
                requestedWidth + attrs.surfaceInsets.left + attrs.surfaceInsets.right,
                requestedHeight + attrs.surfaceInsets.top + attrs.surfaceInsets.bottom)
            .setOpaque(sc, isOpaque(attrs))
            .apply();
        outSurfaceControl.copyFrom(sc);
        outFrame.set(0, 0, requestedWidth, requestedHeight);

        mergedConfiguration.setConfiguration(mConfiguration, mConfiguration);

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
    public void updateTapExcludeRegion(android.view.IWindow window, int regionId,
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
    public void blessInputSurface(int displayId, SurfaceControl surface,
            InputChannel outInputChannel) {
    }

    @Override
    public android.os.IBinder asBinder() {
        return null;
    }
}
