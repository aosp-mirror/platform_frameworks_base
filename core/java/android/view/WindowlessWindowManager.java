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
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.MergedConfiguration;
import android.util.Log;
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
class WindowlessWindowManager extends IWindowSession.Default {
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
        t.show(sc).setBufferSize(sc, requestedWidth, requestedHeight).apply();
        outSurfaceControl.copyFrom(sc);
        outFrame.set(0, 0, requestedWidth, requestedHeight);

        mergedConfiguration.setConfiguration(mConfiguration, mConfiguration);

        return 0;
    }
}
