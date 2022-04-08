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

package com.android.systemui.wm;

import static android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.MergedConfiguration;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DragEvent;
import android.view.IScrollCaptureController;
import android.view.IWindow;
import android.view.IWindowManager;
import android.view.IWindowSession;
import android.view.IWindowSessionCallback;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;

import com.android.internal.os.IResultReceiver;

import java.util.HashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Represents the "windowing" layer of the System-UI. This layer allows system-ui components to
 * place and manipulate windows without talking to WindowManager.
 */
@Singleton
public class SystemWindows {
    private static final String TAG = "SystemWindows";

    private final SparseArray<PerDisplay> mPerDisplay = new SparseArray<>();
    final HashMap<View, SurfaceControlViewHost> mViewRoots = new HashMap<>();
    Context mContext;
    IWindowSession mSession;
    DisplayController mDisplayController;
    IWindowManager mWmService;

    private final DisplayController.OnDisplaysChangedListener mDisplayListener =
            new DisplayController.OnDisplaysChangedListener() {
                @Override
                public void onDisplayAdded(int displayId) { }

                @Override
                public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
                    PerDisplay pd = mPerDisplay.get(displayId);
                    if (pd == null) {
                        return;
                    }
                    pd.updateConfiguration(newConfig);
                }

                @Override
                public void onDisplayRemoved(int displayId) { }
            };

    @Inject
    public SystemWindows(Context context, DisplayController displayController,
            IWindowManager wmService) {
        mContext = context;
        mWmService = wmService;
        mDisplayController = displayController;
        mDisplayController.addDisplayWindowListener(mDisplayListener);
        try {
            mSession = wmService.openSession(
                    new IWindowSessionCallback.Stub() {
                        @Override
                        public void onAnimatorScaleChanged(float scale) {}
                    });
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to create layer", e);
        }
    }

    /**
     * Adds a view to system-ui window management.
     */
    public void addView(View view, WindowManager.LayoutParams attrs, int displayId,
            int windowType) {
        PerDisplay pd = mPerDisplay.get(displayId);
        if (pd == null) {
            pd = new PerDisplay(displayId);
            mPerDisplay.put(displayId, pd);
        }
        pd.addView(view, attrs, windowType);
    }

    /**
     * Removes a view from system-ui window management.
     * @param view
     */
    public void removeView(View view) {
        SurfaceControlViewHost root = mViewRoots.remove(view);
        root.release();
    }

    /**
     * Updates the layout params of a view.
     */
    public void updateViewLayout(@NonNull View view, ViewGroup.LayoutParams params) {
        SurfaceControlViewHost root = mViewRoots.get(view);
        if (root == null || !(params instanceof WindowManager.LayoutParams)) {
            return;
        }
        view.setLayoutParams(params);
        root.relayout((WindowManager.LayoutParams) params);
    }

    /**
     * Sets the touchable region of a view's window. This will be cropped to the window size.
     * @param view
     * @param region
     */
    public void setTouchableRegion(@NonNull View view, Region region) {
        SurfaceControlViewHost root = mViewRoots.get(view);
        if (root == null) {
            return;
        }
        WindowlessWindowManager wwm = root.getWindowlessWM();
        if (!(wwm instanceof SysUiWindowManager)) {
            return;
        }
        ((SysUiWindowManager) wwm).setTouchableRegionForWindow(view, region);
    }

    /**
     * Adds a root for system-ui window management with no views. Only useful for IME.
     */
    public void addRoot(int displayId, int windowType) {
        PerDisplay pd = mPerDisplay.get(displayId);
        if (pd == null) {
            pd = new PerDisplay(displayId);
            mPerDisplay.put(displayId, pd);
        }
        pd.addRoot(windowType);
    }

    /**
     * Get the IWindow token for a specific root.
     *
     * @param windowType A window type from {@link android.view.WindowManager}.
     */
    IWindow getWindow(int displayId, int windowType) {
        PerDisplay pd = mPerDisplay.get(displayId);
        if (pd == null) {
            return null;
        }
        return pd.getWindow(windowType);
    }

    /**
     * Gets the SurfaceControl associated with a root view. This is the same surface that backs the
     * ViewRootImpl.
     */
    public SurfaceControl getViewSurface(View rootView) {
        for (int i = 0; i < mPerDisplay.size(); ++i) {
            for (int iWm = 0; iWm < mPerDisplay.valueAt(i).mWwms.size(); ++iWm) {
                SurfaceControl out = mPerDisplay.valueAt(i).mWwms.valueAt(iWm)
                        .getSurfaceControlForWindow(rootView);
                if (out != null) {
                    return out;
                }
            }
        }
        return null;
    }

    private class PerDisplay {
        final int mDisplayId;
        private final SparseArray<SysUiWindowManager> mWwms = new SparseArray<>();

        PerDisplay(int displayId) {
            mDisplayId = displayId;
        }

        public void addView(View view, WindowManager.LayoutParams attrs, int windowType) {
            SysUiWindowManager wwm = addRoot(windowType);
            if (wwm == null) {
                Slog.e(TAG, "Unable to create systemui root");
                return;
            }
            final Display display = mDisplayController.getDisplay(mDisplayId);
            SurfaceControlViewHost viewRoot =
                    new SurfaceControlViewHost(mContext, display, wwm,
                            true /* useSfChoreographer */);
            attrs.flags |= FLAG_HARDWARE_ACCELERATED;
            viewRoot.setView(view, attrs);
            mViewRoots.put(view, viewRoot);

            try {
                mWmService.setShellRootAccessibilityWindow(mDisplayId, windowType,
                        viewRoot.getWindowToken());
            } catch (RemoteException e) {
                Slog.e(TAG, "Error setting accessibility window for " + mDisplayId + ":"
                        + windowType, e);
            }
        }

        SysUiWindowManager addRoot(int windowType) {
            SysUiWindowManager wwm = mWwms.get(windowType);
            if (wwm != null) {
                return wwm;
            }
            SurfaceControl rootSurface = null;
            ContainerWindow win = new ContainerWindow();
            try {
                rootSurface = mWmService.addShellRoot(mDisplayId, win, windowType);
            } catch (RemoteException e) {
            }
            if (rootSurface == null) {
                Slog.e(TAG, "Unable to get root surfacecontrol for systemui");
                return null;
            }
            Context displayContext = mDisplayController.getDisplayContext(mDisplayId);
            wwm = new SysUiWindowManager(mDisplayId, displayContext, rootSurface, win);
            mWwms.put(windowType, wwm);
            return wwm;
        }

        IWindow getWindow(int windowType) {
            SysUiWindowManager wwm = mWwms.get(windowType);
            if (wwm == null) {
                return null;
            }
            return wwm.mContainerWindow;
        }

        void updateConfiguration(Configuration configuration) {
            for (int i = 0; i < mWwms.size(); ++i) {
                mWwms.valueAt(i).updateConfiguration(configuration);
            }
        }
    }

    /**
     * A subclass of WindowlessWindowManager that provides insets to its viewroots.
     */
    public class SysUiWindowManager extends WindowlessWindowManager {
        final int mDisplayId;
        ContainerWindow mContainerWindow;
        public SysUiWindowManager(int displayId, Context ctx, SurfaceControl rootSurface,
                ContainerWindow container) {
            super(ctx.getResources().getConfiguration(), rootSurface, null /* hostInputToken */);
            mContainerWindow = container;
            mDisplayId = displayId;
        }

        @Override
        public int relayout(IWindow window, int seq, WindowManager.LayoutParams attrs,
                int requestedWidth, int requestedHeight, int viewVisibility, int flags,
                long frameNumber, Rect outFrame, Rect outOverscanInsets, Rect outContentInsets,
                Rect outVisibleInsets, Rect outStableInsets,
                DisplayCutout.ParcelableWrapper cutout, MergedConfiguration mergedConfiguration,
                SurfaceControl outSurfaceControl, InsetsState outInsetsState,
                InsetsSourceControl[] outActiveControls, Point outSurfaceSize,
                SurfaceControl outBLASTSurfaceControl) {
            int res = super.relayout(window, seq, attrs, requestedWidth, requestedHeight,
                    viewVisibility, flags, frameNumber, outFrame, outOverscanInsets,
                    outContentInsets, outVisibleInsets, outStableInsets,
                    cutout, mergedConfiguration, outSurfaceControl, outInsetsState,
                    outActiveControls, outSurfaceSize, outBLASTSurfaceControl);
            if (res != 0) {
                return res;
            }
            DisplayLayout dl = mDisplayController.getDisplayLayout(mDisplayId);
            outStableInsets.set(dl.stableInsets());
            return 0;
        }

        void updateConfiguration(Configuration configuration) {
            setConfiguration(configuration);
        }

        SurfaceControl getSurfaceControlForWindow(View rootView) {
            return getSurfaceControl(rootView);
        }

        void setTouchableRegionForWindow(View rootView, Region region) {
            IBinder token = rootView.getWindowToken();
            if (token == null) {
                return;
            }
            setTouchRegion(token, region);
        }
    }

    class ContainerWindow extends IWindow.Stub {
        ContainerWindow() {}

        @Override
        public void resized(Rect frame, Rect contentInsets, Rect visibleInsets, Rect stableInsets,
                boolean reportDraw, MergedConfiguration newMergedConfiguration, Rect backDropFrame,
                boolean forceLayout, boolean alwaysConsumeSystemBars, int displayId,
                DisplayCutout.ParcelableWrapper displayCutout) {}

        @Override
        public void locationInParentDisplayChanged(Point offset) {}

        @Override
        public void insetsChanged(InsetsState insetsState) {}

        @Override
        public void insetsControlChanged(InsetsState insetsState,
                InsetsSourceControl[] activeControls) {}

        @Override
        public void showInsets(int types, boolean fromIme) {}

        @Override
        public void hideInsets(int types, boolean fromIme) {}

        @Override
        public void moved(int newX, int newY) {}

        @Override
        public void dispatchAppVisibility(boolean visible) {}

        @Override
        public void dispatchGetNewSurface() {}

        @Override
        public void windowFocusChanged(boolean hasFocus, boolean inTouchMode) {}

        @Override
        public void executeCommand(String command, String parameters, ParcelFileDescriptor out) {}

        @Override
        public void closeSystemDialogs(String reason) {}

        @Override
        public void dispatchWallpaperOffsets(float x, float y, float xStep, float yStep,
                float zoom, boolean sync) {}

        @Override
        public void dispatchWallpaperCommand(String action, int x, int y,
                int z, Bundle extras, boolean sync) {}

        /* Drag/drop */
        @Override
        public void dispatchDragEvent(DragEvent event) {}

        @Override
        public void updatePointerIcon(float x, float y) {}

        @Override
        public void dispatchSystemUiVisibilityChanged(int seq, int globalVisibility,
                int localValue, int localChanges) {}

        @Override
        public void dispatchWindowShown() {}

        @Override
        public void requestAppKeyboardShortcuts(IResultReceiver receiver, int deviceId) {}

        @Override
        public void dispatchPointerCaptureChanged(boolean hasCapture) {}

        @Override
        public void requestScrollCapture(IScrollCaptureController controller) {
            try {
                controller.onClientUnavailable();
            } catch (RemoteException ex) {
                // ignore
            }
        }
    }
}
