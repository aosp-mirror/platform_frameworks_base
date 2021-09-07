/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.wm;

import static android.accessibilityservice.AccessibilityTrace.FLAGS_MAGNIFICATION_CALLBACK;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK;
import static android.os.Build.IS_USER;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_MAGNIFICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;

import static com.android.server.accessibility.AccessibilityTraceFileProto.ENTRY;
import static com.android.server.accessibility.AccessibilityTraceFileProto.MAGIC_NUMBER;
import static com.android.server.accessibility.AccessibilityTraceFileProto.MAGIC_NUMBER_H;
import static com.android.server.accessibility.AccessibilityTraceFileProto.MAGIC_NUMBER_L;
import static com.android.server.accessibility.AccessibilityTraceProto.ACCESSIBILITY_SERVICE;
import static com.android.server.accessibility.AccessibilityTraceProto.CALENDAR_TIME;
import static com.android.server.accessibility.AccessibilityTraceProto.CALLING_PARAMS;
import static com.android.server.accessibility.AccessibilityTraceProto.CALLING_PKG;
import static com.android.server.accessibility.AccessibilityTraceProto.CALLING_STACKS;
import static com.android.server.accessibility.AccessibilityTraceProto.ELAPSED_REALTIME_NANOS;
import static com.android.server.accessibility.AccessibilityTraceProto.LOGGING_TYPE;
import static com.android.server.accessibility.AccessibilityTraceProto.PROCESS_NAME;
import static com.android.server.accessibility.AccessibilityTraceProto.THREAD_ID_NAME;
import static com.android.server.accessibility.AccessibilityTraceProto.WHERE;
import static com.android.server.accessibility.AccessibilityTraceProto.WINDOW_MANAGER_SERVICE;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowTracing.WINSCOPE_EXT;
import static com.android.server.wm.utils.RegionUtils.forEachRect;

import android.accessibilityservice.AccessibilityTrace;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.graphics.BLASTBufferQueue;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PorterDuff.Mode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedValue;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.InsetsSource;
import android.view.MagnificationSpec;
import android.view.Surface;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;
import android.view.ViewConfiguration;
import android.view.WindowInfo;
import android.view.WindowManager;
import android.view.WindowManagerPolicyConstants;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import com.android.internal.R;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.TraceBuffer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.WindowManagerInternal.AccessibilityControllerInternal;
import com.android.server.wm.WindowManagerInternal.MagnificationCallbacks;
import com.android.server.wm.WindowManagerInternal.WindowsForAccessibilityCallback;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * This class contains the accessibility related logic of the window manager.
 */
final class AccessibilityController {
    private static final String TAG = AccessibilityController.class.getSimpleName();

    private static final Object STATIC_LOCK = new Object();
    static AccessibilityControllerInternalImpl
            getAccessibilityControllerInternal(WindowManagerService service) {
        return AccessibilityControllerInternalImpl.getInstance(service);
    }

    private final AccessibilityControllerInternalImpl mAccessibilityTracing;
    private final WindowManagerService mService;
    private static final Rect EMPTY_RECT = new Rect();
    private static final float[] sTempFloats = new float[9];

    private SparseArray<DisplayMagnifier> mDisplayMagnifiers = new SparseArray<>();
    private SparseArray<WindowsForAccessibilityObserver> mWindowsForAccessibilityObserver =
            new SparseArray<>();

    // Set to true if initializing window population complete.
    private boolean mAllObserversInitialized = true;

    AccessibilityController(WindowManagerService service) {
        mService = service;
        mAccessibilityTracing =
                AccessibilityController.getAccessibilityControllerInternal(service);
    }

    boolean setMagnificationCallbacks(int displayId, MagnificationCallbacks callbacks) {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
            mAccessibilityTracing.logTrace(
                    TAG + ".setMagnificationCallbacks",
                    FLAGS_MAGNIFICATION_CALLBACK,
                    "displayId=" + displayId + "; callbacks={" + callbacks + "}");
        }
        boolean result = false;
        if (callbacks != null) {
            if (mDisplayMagnifiers.get(displayId) != null) {
                throw new IllegalStateException("Magnification callbacks already set!");
            }
            final DisplayContent dc = mService.mRoot.getDisplayContent(displayId);
            if (dc != null) {
                final Display display = dc.getDisplay();
                if (display != null && display.getType() != Display.TYPE_OVERLAY) {
                    mDisplayMagnifiers.put(displayId, new DisplayMagnifier(
                            mService, dc, display, callbacks));
                    result = true;
                }
            }
        } else {
            final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
            if (displayMagnifier == null) {
                throw new IllegalStateException("Magnification callbacks already cleared!");
            }
            displayMagnifier.destroy();
            mDisplayMagnifiers.remove(displayId);
            result = true;
        }
        return result;
    }

    /**
     * Sets a callback for observing which windows are touchable for the purposes
     * of accessibility on specified display. When a display is reparented and becomes
     * an embedded one, the {@link WindowsForAccessibilityCallback#onDisplayReparented(int)}
     * will notify the accessibility framework to remove the un-used window observer of
     * this embedded display.
     *
     * @param displayId The logical display id.
     * @param callback The callback.
     * @return {@code false} if display id is not valid or an embedded display when the callback
     * isn't null.
     */
    boolean setWindowsForAccessibilityCallback(int displayId,
            WindowsForAccessibilityCallback callback) {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK)) {
            mAccessibilityTracing.logTrace(
                    TAG + ".setWindowsForAccessibilityCallback",
                    FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK,
                    "displayId=" + displayId + "; callback={" + callback + "}");
        }

        if (callback != null) {
            final DisplayContent dc = mService.mRoot.getDisplayContentOrCreate(displayId);
            if (dc == null) {
                return false;
            }

            WindowsForAccessibilityObserver observer =
                    mWindowsForAccessibilityObserver.get(displayId);
            if (isEmbeddedDisplay(dc)) {
                // If this display is an embedded one, its window observer should have been set from
                // window manager after setting its parent window. But if its window observer is
                // empty, that means this mapping didn't be set, and needs to do this again.
                // This happened when accessibility window observer is disabled and enabled again.
                if (observer == null) {
                    handleWindowObserverOfEmbeddedDisplay(displayId, dc.getParentWindow());
                }
                return false;
            } else if (observer != null) {
                final String errorMessage = "Windows for accessibility callback of display "
                        + displayId + " already set!";
                Slog.e(TAG, errorMessage);
                if (Build.IS_DEBUGGABLE) {
                    throw new IllegalStateException(errorMessage);
                }
                removeObserversForEmbeddedChildDisplays(observer);
                mWindowsForAccessibilityObserver.remove(displayId);
            }
            observer = new WindowsForAccessibilityObserver(mService, displayId, callback);
            mWindowsForAccessibilityObserver.put(displayId, observer);
            mAllObserversInitialized &= observer.mInitialized;
        } else {
            final WindowsForAccessibilityObserver windowsForA11yObserver =
                    mWindowsForAccessibilityObserver.get(displayId);
            if (windowsForA11yObserver == null) {
                final String errorMessage = "Windows for accessibility callback of display "
                        + displayId + " already cleared!";
                Slog.e(TAG, errorMessage);
                if (Build.IS_DEBUGGABLE) {
                    throw new IllegalStateException(errorMessage);
                }
            }
            removeObserversForEmbeddedChildDisplays(windowsForA11yObserver);
            mWindowsForAccessibilityObserver.remove(displayId);
        }
        return true;
    }

    void performComputeChangedWindowsNot(int displayId, boolean forceSend) {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK)) {
            mAccessibilityTracing.logTrace(
                    TAG + ".performComputeChangedWindowsNot",
                    FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK,
                    "displayId=" + displayId + "; forceSend=" + forceSend);
        }
        WindowsForAccessibilityObserver observer = null;
        synchronized (mService.mGlobalLock) {
            final WindowsForAccessibilityObserver windowsForA11yObserver =
                    mWindowsForAccessibilityObserver.get(displayId);
            if (windowsForA11yObserver != null) {
                observer = windowsForA11yObserver;
            }
        }
        if (observer != null) {
            observer.performComputeChangedWindows(forceSend);
        }
    }

    void setMagnificationSpec(int displayId, MagnificationSpec spec) {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK
                | FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK)) {
            mAccessibilityTracing.logTrace(TAG + ".setMagnificationSpec",
                    FLAGS_MAGNIFICATION_CALLBACK | FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK,
                    "displayId=" + displayId + "; spec={" + spec + "}");
        }
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.setMagnificationSpec(spec);
        }
        final WindowsForAccessibilityObserver windowsForA11yObserver =
                mWindowsForAccessibilityObserver.get(displayId);
        if (windowsForA11yObserver != null) {
            windowsForA11yObserver.scheduleComputeChangedWindows();
        }
    }

    void getMagnificationRegion(int displayId, Region outMagnificationRegion) {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
            mAccessibilityTracing.logTrace(TAG + ".getMagnificationRegion",
                    FLAGS_MAGNIFICATION_CALLBACK,
                    "displayId=" + displayId + "; outMagnificationRegion={" + outMagnificationRegion
                            + "}");
        }
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.getMagnificationRegion(outMagnificationRegion);
        }
    }

    void onRectangleOnScreenRequested(int displayId, Rect rectangle) {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
            mAccessibilityTracing.logTrace(
                    TAG + ".onRectangleOnScreenRequested",
                    FLAGS_MAGNIFICATION_CALLBACK,
                    "displayId=" + displayId + "; rectangle={" + rectangle + "}");
        }
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.onRectangleOnScreenRequested(rectangle);
        }
        // Not relevant for the window observer.
    }

    void onWindowLayersChanged(int displayId) {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK
                | FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK)) {
            mAccessibilityTracing.logTrace(TAG + ".onWindowLayersChanged",
                    FLAGS_MAGNIFICATION_CALLBACK | FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK,
                    "displayId=" + displayId);
        }
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.onWindowLayersChanged();
        }
        final WindowsForAccessibilityObserver windowsForA11yObserver =
                mWindowsForAccessibilityObserver.get(displayId);
        if (windowsForA11yObserver != null) {
            windowsForA11yObserver.scheduleComputeChangedWindows();
        }
    }

    void onDisplaySizeChanged(DisplayContent displayContent) {

        if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK
                | FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK)) {
            mAccessibilityTracing.logTrace(TAG + ".onRotationChanged",
                    FLAGS_MAGNIFICATION_CALLBACK | FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK,
                    "displayContent={" + displayContent + "}");
        }
        final int displayId = displayContent.getDisplayId();
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.onDisplaySizeChanged(displayContent);
        }
        final WindowsForAccessibilityObserver windowsForA11yObserver =
                mWindowsForAccessibilityObserver.get(displayId);
        if (windowsForA11yObserver != null) {
            windowsForA11yObserver.scheduleComputeChangedWindows();
        }
    }

    void onAppWindowTransition(int displayId, int transition) {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
            mAccessibilityTracing.logTrace(TAG + ".onAppWindowTransition",
                    FLAGS_MAGNIFICATION_CALLBACK,
                    "displayId=" + displayId + "; transition=" + transition);
        }
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.onAppWindowTransition(displayId, transition);
        }
        // Not relevant for the window observer.
    }

    void onWindowTransition(WindowState windowState, int transition) {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK
                | FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK)) {
            mAccessibilityTracing.logTrace(TAG + ".onWindowTransition",
                    FLAGS_MAGNIFICATION_CALLBACK | FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK,
                    "windowState={" + windowState + "}; transition=" + transition);
        }
        final int displayId = windowState.getDisplayId();
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.onWindowTransition(windowState, transition);
        }
        final WindowsForAccessibilityObserver windowsForA11yObserver =
                mWindowsForAccessibilityObserver.get(displayId);
        if (windowsForA11yObserver != null) {
            windowsForA11yObserver.scheduleComputeChangedWindows();
        }
    }

    void onWindowFocusChangedNot(int displayId) {
        // Not relevant for the display magnifier.
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK)) {
            mAccessibilityTracing.logTrace(TAG + ".onWindowFocusChangedNot",
                    FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK, "displayId=" + displayId);
        }
        WindowsForAccessibilityObserver observer = null;
        synchronized (mService.mGlobalLock) {
            final WindowsForAccessibilityObserver windowsForA11yObserver =
                    mWindowsForAccessibilityObserver.get(displayId);
            if (windowsForA11yObserver != null) {
                observer = windowsForA11yObserver;
            }
        }
        if (observer != null) {
            observer.performComputeChangedWindows(false);
        }
        // Since we abandon initializing observers if no window has focus, make sure all observers
        // are initialized.
        sendCallbackToUninitializedObserversIfNeeded();
    }

    private void sendCallbackToUninitializedObserversIfNeeded() {
        List<WindowsForAccessibilityObserver> unInitializedObservers;
        synchronized (mService.mGlobalLock) {
            if (mAllObserversInitialized) {
                return;
            }
            if (mService.mRoot.getTopFocusedDisplayContent().mCurrentFocus == null) {
                return;
            }
            unInitializedObservers = new ArrayList<>();
            for (int i = mWindowsForAccessibilityObserver.size() - 1; i >= 0; --i) {
                final WindowsForAccessibilityObserver observer =
                        mWindowsForAccessibilityObserver.valueAt(i);
                if (!observer.mInitialized) {
                    unInitializedObservers.add(observer);
                }
            }
            // Reset the flag to record the new added observer.
            mAllObserversInitialized = true;
        }

        boolean areAllObserversInitialized = true;
        for (int i = unInitializedObservers.size() - 1; i >= 0; --i) {
            final  WindowsForAccessibilityObserver observer = unInitializedObservers.get(i);
            observer.performComputeChangedWindows(true);
            areAllObserversInitialized &= observer.mInitialized;
        }
        synchronized (mService.mGlobalLock) {
            mAllObserversInitialized &= areAllObserversInitialized;
        }
    }

    /**
     * Called when the location or the size of the window is changed. Moving the window to
     * another display is also taken into consideration.
     * @param displayIds the display ids of displays when the situation happens.
     */
    void onSomeWindowResizedOrMoved(int... displayIds) {
        onSomeWindowResizedOrMovedWithCallingUid(Binder.getCallingUid(), displayIds);
    }

    void onSomeWindowResizedOrMovedWithCallingUid(int callingUid, int... displayIds) {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK)) {
            mAccessibilityTracing.logTrace(TAG + ".onSomeWindowResizedOrMoved",
                    FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK,
                    "displayIds={" + displayIds.toString() + "}", "".getBytes(), callingUid);
        }
        // Not relevant for the display magnifier.
        for (int i = 0; i < displayIds.length; i++) {
            final WindowsForAccessibilityObserver windowsForA11yObserver =
                    mWindowsForAccessibilityObserver.get(displayIds[i]);
            if (windowsForA11yObserver != null) {
                windowsForA11yObserver.scheduleComputeChangedWindows();
            }
        }
    }

    void drawMagnifiedRegionBorderIfNeeded(int displayId, SurfaceControl.Transaction t) {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
            mAccessibilityTracing.logTrace(
                    TAG + ".drawMagnifiedRegionBorderIfNeeded",
                    FLAGS_MAGNIFICATION_CALLBACK,
                    "displayId=" + displayId + "; transaction={" + t + "}");
        }
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.drawMagnifiedRegionBorderIfNeeded(t);
        }
        // Not relevant for the window observer.
    }

    MagnificationSpec getMagnificationSpecForWindow(WindowState windowState) {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
            mAccessibilityTracing.logTrace(TAG + ".getMagnificationSpecForWindow",
                    FLAGS_MAGNIFICATION_CALLBACK,
                    "windowState={" + windowState + "}");
        }
        final int displayId = windowState.getDisplayId();
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            return displayMagnifier.getMagnificationSpecForWindow(windowState);
        }
        return null;
    }

    boolean hasCallbacks() {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK
                | FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK)) {
            mAccessibilityTracing.logTrace(TAG + ".hasCallbacks",
                    FLAGS_MAGNIFICATION_CALLBACK | FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK);
        }
        return (mDisplayMagnifiers.size() > 0
                || mWindowsForAccessibilityObserver.size() > 0);
    }

    void setForceShowMagnifiableBounds(int displayId, boolean show) {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
            mAccessibilityTracing.logTrace(TAG + ".setForceShowMagnifiableBounds",
                    FLAGS_MAGNIFICATION_CALLBACK, "displayId=" + displayId + "; show=" + show);
        }
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.setForceShowMagnifiableBounds(show);
            displayMagnifier.showMagnificationBoundsIfNeeded();
        }
    }

    void handleWindowObserverOfEmbeddedDisplay(int embeddedDisplayId,
            WindowState parentWindow) {
        handleWindowObserverOfEmbeddedDisplay(
                embeddedDisplayId, parentWindow, Binder.getCallingUid());
    }

    void handleWindowObserverOfEmbeddedDisplay(
            int embeddedDisplayId, WindowState parentWindow, int callingUid) {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK)) {
            mAccessibilityTracing.logTrace(TAG + ".handleWindowObserverOfEmbeddedDisplay",
                    FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK,
                    "embeddedDisplayId=" + embeddedDisplayId + "; parentWindowState={"
                    + parentWindow + "}", "".getBytes(), callingUid);
        }
        if (embeddedDisplayId == Display.DEFAULT_DISPLAY || parentWindow == null) {
            return;
        }
        mService.mH.sendMessage(PooledLambda.obtainMessage(
                AccessibilityController::updateWindowObserverOfEmbeddedDisplay,
                this, embeddedDisplayId, parentWindow));
    }

    private void updateWindowObserverOfEmbeddedDisplay(int embeddedDisplayId,
            WindowState parentWindow) {
        final WindowsForAccessibilityObserver windowsForA11yObserver;

        synchronized (mService.mGlobalLock) {
            // Finds the parent display of this embedded display
            WindowState candidate = parentWindow;
            while (candidate != null) {
                parentWindow = candidate;
                candidate = parentWindow.getDisplayContent().getParentWindow();
            }
            final int parentDisplayId = parentWindow.getDisplayId();
            // Uses the observer of parent display
            windowsForA11yObserver = mWindowsForAccessibilityObserver.get(parentDisplayId);
        }

        if (windowsForA11yObserver != null) {
            windowsForA11yObserver.notifyDisplayReparented(embeddedDisplayId);
            windowsForA11yObserver.addEmbeddedDisplay(embeddedDisplayId);
            synchronized (mService.mGlobalLock) {
                // Replaces the observer of embedded display to the one of parent display
                mWindowsForAccessibilityObserver.put(embeddedDisplayId, windowsForA11yObserver);
            }
        }
    }

    void onImeSurfaceShownChanged(WindowState windowState, boolean shown) {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
            mAccessibilityTracing.logTrace(TAG + ".onImeSurfaceShownChanged",
                    FLAGS_MAGNIFICATION_CALLBACK, "windowState=" + windowState + ";shown=" + shown);
        }
        final int displayId = windowState.getDisplayId();
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.onImeSurfaceShownChanged(shown);
        }
    }

    private static void populateTransformationMatrix(WindowState windowState,
            Matrix outMatrix) {
        windowState.getTransformationMatrix(sTempFloats, outMatrix);
    }

    void dump(PrintWriter pw, String prefix) {
        for (int i = 0; i < mDisplayMagnifiers.size(); i++) {
            final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.valueAt(i);
            if (displayMagnifier != null) {
                displayMagnifier.dump(pw, prefix
                        + "Magnification display# " + mDisplayMagnifiers.keyAt(i));
            }
        }
        pw.println(prefix
                + "mWindowsForAccessibilityObserver=" + mWindowsForAccessibilityObserver);
    }

    private void removeObserversForEmbeddedChildDisplays(WindowsForAccessibilityObserver
            observerOfParentDisplay) {
        final IntArray embeddedDisplayIdList =
                observerOfParentDisplay.getAndClearEmbeddedDisplayIdList();

        for (int index = 0; index < embeddedDisplayIdList.size(); index++) {
            final int embeddedDisplayId = embeddedDisplayIdList.get(index);
            mWindowsForAccessibilityObserver.remove(embeddedDisplayId);
        }
    }

    private static boolean isEmbeddedDisplay(DisplayContent dc) {
        final Display display = dc.getDisplay();

        return display.getType() == Display.TYPE_VIRTUAL && dc.getParentWindow() != null;
    }

    /**
     * This class encapsulates the functionality related to display magnification.
     */
    private static final class DisplayMagnifier {

        private static final String LOG_TAG = TAG_WITH_CLASS_NAME ? "DisplayMagnifier" : TAG_WM;

        private static final boolean DEBUG_WINDOW_TRANSITIONS = false;
        private static final boolean DEBUG_DISPLAY_SIZE = false;
        private static final boolean DEBUG_LAYERS = false;
        private static final boolean DEBUG_RECTANGLE_REQUESTED = false;
        private static final boolean DEBUG_VIEWPORT_WINDOW = false;

        private final Rect mTempRect1 = new Rect();
        private final Rect mTempRect2 = new Rect();

        private final Region mTempRegion1 = new Region();
        private final Region mTempRegion2 = new Region();
        private final Region mTempRegion3 = new Region();
        private final Region mTempRegion4 = new Region();

        private final Context mDisplayContext;
        private final WindowManagerService mService;
        private final MagnifiedViewport mMagnifedViewport;
        private final Handler mHandler;
        private final DisplayContent mDisplayContent;
        private final Display mDisplay;
        private final AccessibilityControllerInternalImpl mAccessibilityTracing;

        private final MagnificationCallbacks mCallbacks;

        private final long mLongAnimationDuration;

        private boolean mForceShowMagnifiableBounds = false;

        DisplayMagnifier(WindowManagerService windowManagerService,
                DisplayContent displayContent,
                Display display,
                MagnificationCallbacks callbacks) {
            mDisplayContext = windowManagerService.mContext.createDisplayContext(display);
            mService = windowManagerService;
            mCallbacks = callbacks;
            mDisplayContent = displayContent;
            mDisplay = display;
            mHandler = new MyHandler(mService.mH.getLooper());
            mMagnifedViewport = new MagnifiedViewport();
            mAccessibilityTracing =
                    AccessibilityController.getAccessibilityControllerInternal(mService);
            mLongAnimationDuration = mDisplayContext.getResources().getInteger(
                    com.android.internal.R.integer.config_longAnimTime);
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".DisplayMagnifier.constructor",
                        FLAGS_MAGNIFICATION_CALLBACK,
                        "windowManagerService={" + windowManagerService + "}; displayContent={"
                                + displayContent + "}; display={" + display + "}; callbacks={"
                                + callbacks + "}");
            }
        }

        void setMagnificationSpec(MagnificationSpec spec) {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".setMagnificationSpec",
                        FLAGS_MAGNIFICATION_CALLBACK, "spec={" + spec + "}");
            }
            mMagnifedViewport.updateMagnificationSpec(spec);
            mMagnifedViewport.recomputeBounds();

            mService.applyMagnificationSpecLocked(mDisplay.getDisplayId(), spec);
            mService.scheduleAnimationLocked();
        }

        void setForceShowMagnifiableBounds(boolean show) {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".setForceShowMagnifiableBounds",
                        FLAGS_MAGNIFICATION_CALLBACK, "show=" + show);
            }
            mForceShowMagnifiableBounds = show;
            mMagnifedViewport.setMagnifiedRegionBorderShown(show, true);
        }

        boolean isForceShowingMagnifiableBounds() {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".isForceShowingMagnifiableBounds",
                        FLAGS_MAGNIFICATION_CALLBACK);
            }
            return mForceShowMagnifiableBounds;
        }

        void onRectangleOnScreenRequested(Rect rectangle) {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".onRectangleOnScreenRequested",
                        FLAGS_MAGNIFICATION_CALLBACK, "rectangle={" + rectangle + "}");
            }
            if (DEBUG_RECTANGLE_REQUESTED) {
                Slog.i(LOG_TAG, "Rectangle on screen requested: " + rectangle);
            }
            if (!mMagnifedViewport.isMagnifying()) {
                return;
            }
            Rect magnifiedRegionBounds = mTempRect2;
            mMagnifedViewport.getMagnifiedFrameInContentCoords(magnifiedRegionBounds);
            if (magnifiedRegionBounds.contains(rectangle)) {
                return;
            }
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = rectangle.left;
            args.argi2 = rectangle.top;
            args.argi3 = rectangle.right;
            args.argi4 = rectangle.bottom;
            mHandler.obtainMessage(MyHandler.MESSAGE_NOTIFY_RECTANGLE_ON_SCREEN_REQUESTED,
                    args).sendToTarget();
        }

        void onWindowLayersChanged() {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(
                        LOG_TAG + ".onWindowLayersChanged", FLAGS_MAGNIFICATION_CALLBACK);
            }
            if (DEBUG_LAYERS) {
                Slog.i(LOG_TAG, "Layers changed.");
            }
            mMagnifedViewport.recomputeBounds();
            mService.scheduleAnimationLocked();
        }

        void onDisplaySizeChanged(DisplayContent displayContent) {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".onDisplaySizeChanged",
                        FLAGS_MAGNIFICATION_CALLBACK, "displayContent={" + displayContent + "}");
            }
            if (DEBUG_DISPLAY_SIZE) {
                final int rotation = displayContent.getRotation();
                Slog.i(LOG_TAG, "Rotation: " + Surface.rotationToString(rotation)
                        + " displayId: " + displayContent.getDisplayId());
            }
            mMagnifedViewport.onDisplaySizeChanged();
            mHandler.sendEmptyMessage(MyHandler.MESSAGE_NOTIFY_DISPLAY_SIZE_CHANGED);
        }

        void onAppWindowTransition(int displayId, int transition) {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".onAppWindowTransition",
                        FLAGS_MAGNIFICATION_CALLBACK,
                        "displayId=" + displayId + "; transition=" + transition);
            }
            if (DEBUG_WINDOW_TRANSITIONS) {
                Slog.i(LOG_TAG, "Window transition: "
                        + AppTransition.appTransitionOldToString(transition)
                        + " displayId: " + displayId);
            }
            final boolean magnifying = mMagnifedViewport.isMagnifying();
            if (magnifying) {
                switch (transition) {
                    case WindowManager.TRANSIT_OLD_ACTIVITY_OPEN:
                    case WindowManager.TRANSIT_OLD_TASK_FRAGMENT_OPEN:
                    case WindowManager.TRANSIT_OLD_TASK_OPEN:
                    case WindowManager.TRANSIT_OLD_TASK_TO_FRONT:
                    case WindowManager.TRANSIT_OLD_WALLPAPER_OPEN:
                    case WindowManager.TRANSIT_OLD_WALLPAPER_CLOSE:
                    case WindowManager.TRANSIT_OLD_WALLPAPER_INTRA_OPEN: {
                        mHandler.sendEmptyMessage(MyHandler.MESSAGE_NOTIFY_USER_CONTEXT_CHANGED);
                    }
                }
            }
        }

        void onWindowTransition(WindowState windowState, int transition) {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".onWindowTransition",
                        FLAGS_MAGNIFICATION_CALLBACK,
                        "windowState={" + windowState + "}; transition=" + transition);
            }
            if (DEBUG_WINDOW_TRANSITIONS) {
                Slog.i(LOG_TAG, "Window transition: "
                        + AppTransition.appTransitionOldToString(transition)
                        + " displayId: " + windowState.getDisplayId());
            }
            final boolean magnifying = mMagnifedViewport.isMagnifying();
            final int type = windowState.mAttrs.type;
            switch (transition) {
                case WindowManagerPolicy.TRANSIT_ENTER:
                case WindowManagerPolicy.TRANSIT_SHOW: {
                    if (!magnifying) {
                        break;
                    }
                    switch (type) {
                        case WindowManager.LayoutParams.TYPE_APPLICATION:
                        case WindowManager.LayoutParams.TYPE_DRAWN_APPLICATION:
                        case WindowManager.LayoutParams.TYPE_APPLICATION_PANEL:
                        case WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA:
                        case WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL:
                        case WindowManager.LayoutParams.TYPE_APPLICATION_ABOVE_SUB_PANEL:
                        case WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG:
                        case WindowManager.LayoutParams.TYPE_SEARCH_BAR:
                        case WindowManager.LayoutParams.TYPE_PHONE:
                        case WindowManager.LayoutParams.TYPE_SYSTEM_ALERT:
                        case WindowManager.LayoutParams.TYPE_TOAST:
                        case WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY:
                        case WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:
                        case WindowManager.LayoutParams.TYPE_PRIORITY_PHONE:
                        case WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG:
                        case WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG:
                        case WindowManager.LayoutParams.TYPE_SYSTEM_ERROR:
                        case WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY:
                        case WindowManager.LayoutParams.TYPE_QS_DIALOG:
                        case WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL: {
                            Rect magnifiedRegionBounds = mTempRect2;
                            mMagnifedViewport.getMagnifiedFrameInContentCoords(
                                    magnifiedRegionBounds);
                            Rect touchableRegionBounds = mTempRect1;
                            windowState.getTouchableRegion(mTempRegion1);
                            mTempRegion1.getBounds(touchableRegionBounds);
                            if (!magnifiedRegionBounds.intersect(touchableRegionBounds)) {
                                mCallbacks.onRectangleOnScreenRequested(
                                        touchableRegionBounds.left,
                                        touchableRegionBounds.top,
                                        touchableRegionBounds.right,
                                        touchableRegionBounds.bottom);
                            }
                        } break;
                    } break;
                }
            }
        }

        void onImeSurfaceShownChanged(boolean shown) {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".onImeSurfaceShownChanged",
                        FLAGS_MAGNIFICATION_CALLBACK, "shown=" + shown);
            }
            mHandler.obtainMessage(MyHandler.MESSAGE_NOTIFY_IME_WINDOW_VISIBILITY_CHANGED,
                    shown ? 1 : 0, 0).sendToTarget();
        }

        MagnificationSpec getMagnificationSpecForWindow(WindowState windowState) {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".getMagnificationSpecForWindow",
                        FLAGS_MAGNIFICATION_CALLBACK, "windowState={" + windowState + "}");
            }
            MagnificationSpec spec = mMagnifedViewport.getMagnificationSpec();
            if (spec != null && !spec.isNop()) {
                if (!windowState.shouldMagnify()) {
                    return null;
                }
            }
            return spec;
        }

        void getMagnificationRegion(Region outMagnificationRegion) {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".getMagnificationRegion",
                        FLAGS_MAGNIFICATION_CALLBACK,
                        "outMagnificationRegion={" + outMagnificationRegion + "}");
            }
            // Make sure we're working with the most current bounds
            mMagnifedViewport.recomputeBounds();
            mMagnifedViewport.getMagnificationRegion(outMagnificationRegion);
        }

        void destroy() {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".destroy", FLAGS_MAGNIFICATION_CALLBACK);
            }
            mMagnifedViewport.destroyWindow();
        }

        // Can be called outside of a surface transaction
        void showMagnificationBoundsIfNeeded() {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".showMagnificationBoundsIfNeeded",
                        FLAGS_MAGNIFICATION_CALLBACK);
            }
            mHandler.obtainMessage(MyHandler.MESSAGE_SHOW_MAGNIFIED_REGION_BOUNDS_IF_NEEDED)
                    .sendToTarget();
        }

        void drawMagnifiedRegionBorderIfNeeded(SurfaceControl.Transaction t) {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".drawMagnifiedRegionBorderIfNeeded",
                        FLAGS_MAGNIFICATION_CALLBACK, "transition={" + t + "}");
            }
            mMagnifedViewport.drawWindowIfNeeded(t);
        }

        void dump(PrintWriter pw, String prefix) {
            mMagnifedViewport.dump(pw, prefix);
        }

        private final class MagnifiedViewport {

            private final SparseArray<WindowState> mTempWindowStates =
                    new SparseArray<WindowState>();

            private final RectF mTempRectF = new RectF();

            private final Point mScreenSize = new Point();

            private final Matrix mTempMatrix = new Matrix();

            private final Region mMagnificationRegion = new Region();
            private final Region mOldMagnificationRegion = new Region();

            private final Path mCircularPath;

            private final MagnificationSpec mMagnificationSpec = new MagnificationSpec();

            private final float mBorderWidth;
            private final int mHalfBorderWidth;
            private final int mDrawBorderInset;

            private final ViewportWindow mWindow;

            private boolean mFullRedrawNeeded;
            private int mTempLayer = 0;

            MagnifiedViewport() {
                mBorderWidth = mDisplayContext.getResources().getDimension(
                        com.android.internal.R.dimen.accessibility_magnification_indicator_width);
                mHalfBorderWidth = (int) Math.ceil(mBorderWidth / 2);
                mDrawBorderInset = (int) mBorderWidth / 2;
                mWindow = new ViewportWindow(mDisplayContext);

                if (mDisplayContext.getResources().getConfiguration().isScreenRound()) {
                    mCircularPath = new Path();

                    getDisplaySizeLocked(mScreenSize);
                    final int centerXY = mScreenSize.x / 2;
                    mCircularPath.addCircle(centerXY, centerXY, centerXY, Path.Direction.CW);
                } else {
                    mCircularPath = null;
                }

                recomputeBounds();
            }

            void getMagnificationRegion(@NonNull Region outMagnificationRegion) {
                outMagnificationRegion.set(mMagnificationRegion);
            }

            void updateMagnificationSpec(MagnificationSpec spec) {
                if (spec != null) {
                    mMagnificationSpec.initialize(spec.scale, spec.offsetX, spec.offsetY);
                } else {
                    mMagnificationSpec.clear();
                }
                // If this message is pending we are in a rotation animation and do not want
                // to show the border. We will do so when the pending message is handled.
                if (!mHandler.hasMessages(
                        MyHandler.MESSAGE_SHOW_MAGNIFIED_REGION_BOUNDS_IF_NEEDED)) {
                    setMagnifiedRegionBorderShown(
                            isMagnifying() || isForceShowingMagnifiableBounds(), true);
                }
            }

            void recomputeBounds() {
                getDisplaySizeLocked(mScreenSize);
                final int screenWidth = mScreenSize.x;
                final int screenHeight = mScreenSize.y;

                mMagnificationRegion.set(0, 0, 0, 0);
                final Region availableBounds = mTempRegion1;
                availableBounds.set(0, 0, screenWidth, screenHeight);

                if (mCircularPath != null) {
                    availableBounds.setPath(mCircularPath, availableBounds);
                }

                Region nonMagnifiedBounds = mTempRegion4;
                nonMagnifiedBounds.set(0, 0, 0, 0);

                SparseArray<WindowState> visibleWindows = mTempWindowStates;
                visibleWindows.clear();
                populateWindowsOnScreen(visibleWindows);

                final int visibleWindowCount = visibleWindows.size();
                for (int i = visibleWindowCount - 1; i >= 0; i--) {
                    WindowState windowState = visibleWindows.valueAt(i);
                    final int windowType = windowState.mAttrs.type;
                    if (isExcludedWindowType(windowType)
                            || ((windowState.mAttrs.privateFlags
                            & PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY) != 0)) {
                        continue;
                    }

                    // Consider the touchable portion of the window
                    Matrix matrix = mTempMatrix;
                    populateTransformationMatrix(windowState, matrix);
                    Region touchableRegion = mTempRegion3;
                    windowState.getTouchableRegion(touchableRegion);
                    Rect touchableFrame = mTempRect1;
                    touchableRegion.getBounds(touchableFrame);
                    RectF windowFrame = mTempRectF;
                    windowFrame.set(touchableFrame);
                    windowFrame.offset(-windowState.getFrame().left,
                            -windowState.getFrame().top);
                    matrix.mapRect(windowFrame);
                    Region windowBounds = mTempRegion2;
                    windowBounds.set((int) windowFrame.left, (int) windowFrame.top,
                            (int) windowFrame.right, (int) windowFrame.bottom);
                    // Only update new regions
                    Region portionOfWindowAlreadyAccountedFor = mTempRegion3;
                    portionOfWindowAlreadyAccountedFor.set(mMagnificationRegion);
                    portionOfWindowAlreadyAccountedFor.op(nonMagnifiedBounds, Region.Op.UNION);
                    windowBounds.op(portionOfWindowAlreadyAccountedFor, Region.Op.DIFFERENCE);

                    if (windowState.shouldMagnify()) {
                        mMagnificationRegion.op(windowBounds, Region.Op.UNION);
                        mMagnificationRegion.op(availableBounds, Region.Op.INTERSECT);
                    } else {
                        nonMagnifiedBounds.op(windowBounds, Region.Op.UNION);
                        availableBounds.op(windowBounds, Region.Op.DIFFERENCE);
                    }

                    // If the navigation bar window doesn't have touchable region, count
                    // navigation bar insets into nonMagnifiedBounds. It happens when
                    // navigation mode is gestural.
                    if (isUntouchableNavigationBar(windowState, mTempRegion3)) {
                        final Rect navBarInsets = getNavBarInsets(mDisplayContent);
                        nonMagnifiedBounds.op(navBarInsets, Region.Op.UNION);
                        availableBounds.op(navBarInsets, Region.Op.DIFFERENCE);
                    }

                    // Count letterbox into nonMagnifiedBounds
                    if (windowState.areAppWindowBoundsLetterboxed()) {
                        Region letterboxBounds = getLetterboxBounds(windowState);
                        nonMagnifiedBounds.op(letterboxBounds, Region.Op.UNION);
                        availableBounds.op(letterboxBounds, Region.Op.DIFFERENCE);
                    }

                    // Update accounted bounds
                    Region accountedBounds = mTempRegion2;
                    accountedBounds.set(mMagnificationRegion);
                    accountedBounds.op(nonMagnifiedBounds, Region.Op.UNION);
                    accountedBounds.op(0, 0, screenWidth, screenHeight, Region.Op.INTERSECT);

                    if (accountedBounds.isRect()) {
                        Rect accountedFrame = mTempRect1;
                        accountedBounds.getBounds(accountedFrame);
                        if (accountedFrame.width() == screenWidth
                                && accountedFrame.height() == screenHeight) {
                            break;
                        }
                    }
                }

                visibleWindows.clear();

                mMagnificationRegion.op(mDrawBorderInset, mDrawBorderInset,
                        screenWidth - mDrawBorderInset, screenHeight - mDrawBorderInset,
                        Region.Op.INTERSECT);

                final boolean magnifiedChanged =
                        !mOldMagnificationRegion.equals(mMagnificationRegion);
                if (magnifiedChanged) {
                    mWindow.setBounds(mMagnificationRegion);
                    final Rect dirtyRect = mTempRect1;
                    if (mFullRedrawNeeded) {
                        mFullRedrawNeeded = false;
                        dirtyRect.set(mDrawBorderInset, mDrawBorderInset,
                                screenWidth - mDrawBorderInset,
                                screenHeight - mDrawBorderInset);
                        mWindow.invalidate(dirtyRect);
                    } else {
                        final Region dirtyRegion = mTempRegion3;
                        dirtyRegion.set(mMagnificationRegion);
                        dirtyRegion.op(mOldMagnificationRegion, Region.Op.XOR);
                        dirtyRegion.getBounds(dirtyRect);
                        mWindow.invalidate(dirtyRect);
                    }

                    mOldMagnificationRegion.set(mMagnificationRegion);
                    final SomeArgs args = SomeArgs.obtain();
                    args.arg1 = Region.obtain(mMagnificationRegion);
                    mHandler.obtainMessage(
                            MyHandler.MESSAGE_NOTIFY_MAGNIFICATION_REGION_CHANGED, args)
                            .sendToTarget();
                }
            }

            private boolean isExcludedWindowType(int windowType) {
                return windowType == TYPE_MAGNIFICATION_OVERLAY
                        // Omit the touch region to avoid the cut out of the magnification
                        // bounds because nav bar panel is unmagnifiable.
                        || windowType == TYPE_NAVIGATION_BAR_PANEL
                        // Omit the touch region of window magnification to avoid the cut out of the
                        // magnification and the magnified center of window magnification could be
                        // in the bounds
                        || windowType == TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY;
            }

            void onDisplaySizeChanged() {
                // If we are showing the magnification border, hide it immediately so
                // the user does not see strange artifacts during display size changed caused by
                // rotation or folding/unfolding the device. In the rotation case, the screenshot
                // used for rotation already has the border. After the rotation is complete
                // we will show the border.
                if (isMagnifying() || isForceShowingMagnifiableBounds()) {
                    setMagnifiedRegionBorderShown(false, false);
                    final long delay = (long) (mLongAnimationDuration
                            * mService.getWindowAnimationScaleLocked());
                    Message message = mHandler.obtainMessage(
                            MyHandler.MESSAGE_SHOW_MAGNIFIED_REGION_BOUNDS_IF_NEEDED);
                    mHandler.sendMessageDelayed(message, delay);
                }
                recomputeBounds();
                mWindow.updateSize();
            }

            void setMagnifiedRegionBorderShown(boolean shown, boolean animate) {
                if (shown) {
                    mFullRedrawNeeded = true;
                    mOldMagnificationRegion.set(0, 0, 0, 0);
                }
                mWindow.setShown(shown, animate);
            }

            void getMagnifiedFrameInContentCoords(Rect rect) {
                MagnificationSpec spec = mMagnificationSpec;
                mMagnificationRegion.getBounds(rect);
                rect.offset((int) -spec.offsetX, (int) -spec.offsetY);
                rect.scale(1.0f / spec.scale);
            }

            boolean isMagnifying() {
                return mMagnificationSpec.scale > 1.0f;
            }

            MagnificationSpec getMagnificationSpec() {
                return mMagnificationSpec;
            }

            void drawWindowIfNeeded(SurfaceControl.Transaction t) {
                recomputeBounds();
                mWindow.drawIfNeeded(t);
            }

            void destroyWindow() {
                mWindow.releaseSurface();
            }

            private void populateWindowsOnScreen(SparseArray<WindowState> outWindows) {
                mTempLayer = 0;
                mDisplayContent.forAllWindows((w) -> {
                    if (w.isOnScreen() && w.isVisible()
                            && (w.mAttrs.alpha != 0)) {
                        mTempLayer++;
                        outWindows.put(mTempLayer, w);
                    }
                }, false /* traverseTopToBottom */ );
            }

            private void getDisplaySizeLocked(Point outSize) {
                final Rect bounds =
                        mDisplayContent.getConfiguration().windowConfiguration.getBounds();
                outSize.set(bounds.width(), bounds.height());
            }

            void dump(PrintWriter pw, String prefix) {
                mWindow.dump(pw, prefix);
            }

            private final class ViewportWindow {
                private static final String SURFACE_TITLE = "Magnification Overlay";

                private final Region mBounds = new Region();
                private final Rect mDirtyRect = new Rect();
                private final Paint mPaint = new Paint();

                private final SurfaceControl mSurfaceControl;
                private final BLASTBufferQueue mBlastBufferQueue;
                private final Surface mSurface;

                private final AnimationController mAnimationController;

                private boolean mShown;
                private int mAlpha;

                private boolean mInvalidated;

                ViewportWindow(Context context) {
                    SurfaceControl surfaceControl = null;
                    try {
                        surfaceControl = mDisplayContent
                                .makeOverlay()
                                .setName(SURFACE_TITLE)
                                .setBLASTLayer()
                                .setFormat(PixelFormat.TRANSLUCENT)
                                .setCallsite("ViewportWindow")
                                .build();
                    } catch (OutOfResourcesException oore) {
                        /* ignore */
                    }
                    mSurfaceControl = surfaceControl;
                    mDisplay.getRealSize(mScreenSize);
                    mBlastBufferQueue = new BLASTBufferQueue(SURFACE_TITLE, mSurfaceControl,
                            mScreenSize.x, mScreenSize.y, PixelFormat.RGBA_8888);

                    final SurfaceControl.Transaction t = mService.mTransactionFactory.get();
                    final int layer =
                            mService.mPolicy.getWindowLayerFromTypeLw(TYPE_MAGNIFICATION_OVERLAY) *
                                    WindowManagerPolicyConstants.TYPE_LAYER_MULTIPLIER;
                    t.setLayer(mSurfaceControl, layer).setPosition(mSurfaceControl, 0, 0);
                    InputMonitor.setTrustedOverlayInputInfo(mSurfaceControl, t,
                            mDisplayContent.getDisplayId(), "Magnification Overlay");
                    t.apply();
                    mSurface = mBlastBufferQueue.createSurface();

                    mAnimationController = new AnimationController(context,
                            mService.mH.getLooper());

                    TypedValue typedValue = new TypedValue();
                    context.getTheme().resolveAttribute(R.attr.colorActivatedHighlight,
                            typedValue, true);
                    final int borderColor = context.getColor(typedValue.resourceId);

                    mPaint.setStyle(Paint.Style.STROKE);
                    mPaint.setStrokeWidth(mBorderWidth);
                    mPaint.setColor(borderColor);

                    mInvalidated = true;
                }

                void setShown(boolean shown, boolean animate) {
                    synchronized (mService.mGlobalLock) {
                        if (mShown == shown) {
                            return;
                        }
                        mShown = shown;
                        mAnimationController.onFrameShownStateChanged(shown, animate);
                        if (DEBUG_VIEWPORT_WINDOW) {
                            Slog.i(LOG_TAG, "ViewportWindow shown: " + mShown);
                        }
                    }
                }

                @SuppressWarnings("unused")
                // Called reflectively from an animator.
                int getAlpha() {
                    synchronized (mService.mGlobalLock) {
                        return mAlpha;
                    }
                }

                void setAlpha(int alpha) {
                    synchronized (mService.mGlobalLock) {
                        if (mAlpha == alpha) {
                            return;
                        }
                        mAlpha = alpha;
                        invalidate(null);
                        if (DEBUG_VIEWPORT_WINDOW) {
                            Slog.i(LOG_TAG, "ViewportWindow set alpha: " + alpha);
                        }
                    }
                }

                void setBounds(Region bounds) {
                    synchronized (mService.mGlobalLock) {
                        if (mBounds.equals(bounds)) {
                            return;
                        }
                        mBounds.set(bounds);
                        invalidate(mDirtyRect);
                        if (DEBUG_VIEWPORT_WINDOW) {
                            Slog.i(LOG_TAG, "ViewportWindow set bounds: " + bounds);
                        }
                    }
                }

                void updateSize() {
                    synchronized (mService.mGlobalLock) {
                        getDisplaySizeLocked(mScreenSize);
                        mBlastBufferQueue.update(mSurfaceControl, mScreenSize.x, mScreenSize.y,
                                PixelFormat.RGBA_8888);
                        invalidate(mDirtyRect);
                    }
                }

                void invalidate(Rect dirtyRect) {
                    if (dirtyRect != null) {
                        mDirtyRect.set(dirtyRect);
                    } else {
                        mDirtyRect.setEmpty();
                    }
                    mInvalidated = true;
                    mService.scheduleAnimationLocked();
                }

                void drawIfNeeded(SurfaceControl.Transaction t) {
                    synchronized (mService.mGlobalLock) {
                        if (!mInvalidated) {
                            return;
                        }
                        mInvalidated = false;
                        if (mAlpha > 0) {
                            Canvas canvas = null;
                            try {
                                // Empty dirty rectangle means unspecified.
                                if (mDirtyRect.isEmpty()) {
                                    mBounds.getBounds(mDirtyRect);
                                }
                                mDirtyRect.inset(-mHalfBorderWidth, -mHalfBorderWidth);
                                canvas = mSurface.lockCanvas(mDirtyRect);
                                if (DEBUG_VIEWPORT_WINDOW) {
                                    Slog.i(LOG_TAG, "Dirty rect: " + mDirtyRect);
                                }
                            } catch (IllegalArgumentException iae) {
                                /* ignore */
                            } catch (Surface.OutOfResourcesException oore) {
                                /* ignore */
                            }
                            if (canvas == null) {
                                return;
                            }
                            if (DEBUG_VIEWPORT_WINDOW) {
                                Slog.i(LOG_TAG, "Bounds: " + mBounds);
                            }
                            canvas.drawColor(Color.TRANSPARENT, Mode.CLEAR);
                            mPaint.setAlpha(mAlpha);
                            Path path = mBounds.getBoundaryPath();
                            canvas.drawPath(path, mPaint);

                            mSurface.unlockCanvasAndPost(canvas);
                            t.show(mSurfaceControl);
                        } else {
                            t.hide(mSurfaceControl);
                        }
                    }
                }

                void releaseSurface() {
                    if (mBlastBufferQueue != null) {
                        mBlastBufferQueue.destroy();
                    }
                    mService.mTransactionFactory.get().remove(mSurfaceControl).apply();
                    mSurface.release();
                }

                void dump(PrintWriter pw, String prefix) {
                    pw.println(prefix
                            + " mBounds= " + mBounds
                            + " mDirtyRect= " + mDirtyRect
                            + " mWidth= " + mScreenSize.x
                            + " mHeight= " + mScreenSize.y);
                }

                private final class AnimationController extends Handler {
                    private static final String PROPERTY_NAME_ALPHA = "alpha";

                    private static final int MIN_ALPHA = 0;
                    private static final int MAX_ALPHA = 255;

                    private static final int MSG_FRAME_SHOWN_STATE_CHANGED = 1;

                    private final ValueAnimator mShowHideFrameAnimator;

                    AnimationController(Context context, Looper looper) {
                        super(looper);
                        mShowHideFrameAnimator = ObjectAnimator.ofInt(ViewportWindow.this,
                                PROPERTY_NAME_ALPHA, MIN_ALPHA, MAX_ALPHA);

                        Interpolator interpolator = new DecelerateInterpolator(2.5f);
                        final long longAnimationDuration = context.getResources().getInteger(
                                com.android.internal.R.integer.config_longAnimTime);

                        mShowHideFrameAnimator.setInterpolator(interpolator);
                        mShowHideFrameAnimator.setDuration(longAnimationDuration);
                    }

                    void onFrameShownStateChanged(boolean shown, boolean animate) {
                        obtainMessage(MSG_FRAME_SHOWN_STATE_CHANGED,
                                shown ? 1 : 0, animate ? 1 : 0).sendToTarget();
                    }

                    @Override
                    public void handleMessage(Message message) {
                        switch (message.what) {
                            case MSG_FRAME_SHOWN_STATE_CHANGED: {
                                final boolean shown = message.arg1 == 1;
                                final boolean animate = message.arg2 == 1;

                                if (animate) {
                                    if (mShowHideFrameAnimator.isRunning()) {
                                        mShowHideFrameAnimator.reverse();
                                    } else {
                                        if (shown) {
                                            mShowHideFrameAnimator.start();
                                        } else {
                                            mShowHideFrameAnimator.reverse();
                                        }
                                    }
                                } else {
                                    mShowHideFrameAnimator.cancel();
                                    if (shown) {
                                        setAlpha(MAX_ALPHA);
                                    } else {
                                        setAlpha(MIN_ALPHA);
                                    }
                                }
                            } break;
                        }
                    }
                }
            }
        }

        private class MyHandler extends Handler {
            public static final int MESSAGE_NOTIFY_MAGNIFICATION_REGION_CHANGED = 1;
            public static final int MESSAGE_NOTIFY_RECTANGLE_ON_SCREEN_REQUESTED = 2;
            public static final int MESSAGE_NOTIFY_USER_CONTEXT_CHANGED = 3;
            public static final int MESSAGE_NOTIFY_DISPLAY_SIZE_CHANGED = 4;
            public static final int MESSAGE_SHOW_MAGNIFIED_REGION_BOUNDS_IF_NEEDED = 5;
            public static final int MESSAGE_NOTIFY_IME_WINDOW_VISIBILITY_CHANGED = 6;

            MyHandler(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MESSAGE_NOTIFY_MAGNIFICATION_REGION_CHANGED: {
                        final SomeArgs args = (SomeArgs) message.obj;
                        final Region magnifiedBounds = (Region) args.arg1;
                        mCallbacks.onMagnificationRegionChanged(magnifiedBounds);
                        magnifiedBounds.recycle();
                    } break;

                    case MESSAGE_NOTIFY_RECTANGLE_ON_SCREEN_REQUESTED: {
                        SomeArgs args = (SomeArgs) message.obj;
                        final int left = args.argi1;
                        final int top = args.argi2;
                        final int right = args.argi3;
                        final int bottom = args.argi4;
                        mCallbacks.onRectangleOnScreenRequested(left, top, right, bottom);
                        args.recycle();
                    } break;

                    case MESSAGE_NOTIFY_USER_CONTEXT_CHANGED: {
                        mCallbacks.onUserContextChanged();
                    } break;

                    case MESSAGE_NOTIFY_DISPLAY_SIZE_CHANGED: {
                        mCallbacks.onDisplaySizeChanged();
                    } break;

                    case MESSAGE_SHOW_MAGNIFIED_REGION_BOUNDS_IF_NEEDED : {
                        synchronized (mService.mGlobalLock) {
                            if (mMagnifedViewport.isMagnifying()
                                    || isForceShowingMagnifiableBounds()) {
                                mMagnifedViewport.setMagnifiedRegionBorderShown(true, true);
                                mService.scheduleAnimationLocked();
                            }
                        }
                    } break;

                    case MESSAGE_NOTIFY_IME_WINDOW_VISIBILITY_CHANGED: {
                        final boolean shown = message.arg1 == 1;
                        mCallbacks.onImeWindowVisibilityChanged(shown);
                    } break;
                }
            }
        }
    }

    static boolean isUntouchableNavigationBar(WindowState windowState,
            Region touchableRegion) {
        if (windowState.mAttrs.type != WindowManager.LayoutParams.TYPE_NAVIGATION_BAR) {
            return false;
        }

        // Gets the touchable region.
        windowState.getTouchableRegion(touchableRegion);

        return touchableRegion.isEmpty();
    }

    static Rect getNavBarInsets(DisplayContent displayContent) {
        final InsetsSource source = displayContent.getInsetsStateController().getRawInsetsState()
                .peekSource(ITYPE_NAVIGATION_BAR);
        return source != null ? source.getFrame() : EMPTY_RECT;
    }

    static Region getLetterboxBounds(WindowState windowState) {
        final ActivityRecord appToken = windowState.mActivityRecord;
        if (appToken == null) {
            return new Region();
        }
        final Rect letterboxInsets = appToken.getLetterboxInsets();
        final Rect nonLetterboxRect = windowState.getBounds();
        nonLetterboxRect.inset(letterboxInsets);
        final Region letterboxBounds = new Region();
        letterboxBounds.set(windowState.getBounds());
        letterboxBounds.op(nonLetterboxRect, Region.Op.DIFFERENCE);
        return letterboxBounds;
    }

    /**
     * This class encapsulates the functionality related to computing the windows
     * reported for accessibility purposes. These windows are all windows a sighted
     * user can see on the screen.
     */
    private static final class WindowsForAccessibilityObserver {
        private static final String LOG_TAG = TAG_WITH_CLASS_NAME ?
                "WindowsForAccessibilityObserver" : TAG_WM;

        private static final boolean DEBUG = false;

        private final SparseArray<WindowState> mTempWindowStates = new SparseArray<>();

        private final Set<IBinder> mTempBinderSet = new ArraySet<>();

        private final RectF mTempRectF = new RectF();

        private final Matrix mTempMatrix = new Matrix();

        private final Point mTempPoint = new Point();

        private final Region mTempRegion = new Region();

        private final Region mTempRegion1 = new Region();

        private final WindowManagerService mService;

        private final Handler mHandler;

        private final AccessibilityControllerInternalImpl mAccessibilityTracing;

        private final WindowsForAccessibilityCallback mCallback;

        private final int mDisplayId;

        private final long mRecurringAccessibilityEventsIntervalMillis;

        private final IntArray mEmbeddedDisplayIdList = new IntArray(0);

        // Set to true if initializing window population complete.
        private boolean mInitialized;

        WindowsForAccessibilityObserver(WindowManagerService windowManagerService,
                int displayId,
                WindowsForAccessibilityCallback callback) {
            mService = windowManagerService;
            mCallback = callback;
            mDisplayId = displayId;
            mHandler = new MyHandler(mService.mH.getLooper());
            mAccessibilityTracing =
                    AccessibilityController.getAccessibilityControllerInternal(mService);
            mRecurringAccessibilityEventsIntervalMillis = ViewConfiguration
                    .getSendRecurringAccessibilityEventsInterval();
            computeChangedWindows(true);
        }

        void performComputeChangedWindows(boolean forceSend) {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".performComputeChangedWindows",
                        FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK, "forceSend=" + forceSend);
            }
            mHandler.removeMessages(MyHandler.MESSAGE_COMPUTE_CHANGED_WINDOWS);
            computeChangedWindows(forceSend);
        }

        void scheduleComputeChangedWindows() {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".scheduleComputeChangedWindows",
                        FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK);
            }
            if (!mHandler.hasMessages(MyHandler.MESSAGE_COMPUTE_CHANGED_WINDOWS)) {
                mHandler.sendEmptyMessageDelayed(MyHandler.MESSAGE_COMPUTE_CHANGED_WINDOWS,
                        mRecurringAccessibilityEventsIntervalMillis);
            }
        }

        IntArray getAndClearEmbeddedDisplayIdList() {
            final IntArray returnedArray = new IntArray(mEmbeddedDisplayIdList.size());
            returnedArray.addAll(mEmbeddedDisplayIdList);
            mEmbeddedDisplayIdList.clear();

            return returnedArray;
        }

        void addEmbeddedDisplay(int displayId) {
            if (displayId == mDisplayId) {
                return;
            }
            mEmbeddedDisplayIdList.add(displayId);
        }

        void notifyDisplayReparented(int embeddedDisplayId) {
            // Notifies the A11y framework the display is reparented and
            // becomes an embedded display for removing the un-used
            // displayWindowObserver of this embedded one.
            mCallback.onDisplayReparented(embeddedDisplayId);
        }

        boolean shellRootIsAbove(WindowState windowState, ShellRoot shellRoot) {
            int wsLayer = mService.mPolicy.getWindowLayerLw(windowState);
            int shellLayer = mService.mPolicy.getWindowLayerFromTypeLw(shellRoot.getWindowType(),
                    true);
            return shellLayer >= wsLayer;
        }

        int addShellRootsIfAbove(WindowState windowState, ArrayList<ShellRoot> shellRoots,
                int shellRootIndex, List<WindowInfo> windows, Set<IBinder> addedWindows,
                Region unaccountedSpace, boolean focusedWindowAdded) {
            while (shellRootIndex < shellRoots.size()
                    && shellRootIsAbove(windowState, shellRoots.get(shellRootIndex))) {
                ShellRoot shellRoot = shellRoots.get(shellRootIndex);
                shellRootIndex++;
                final WindowInfo info = shellRoot.getWindowInfo();
                if (info == null) {
                    continue;
                }

                info.layer = addedWindows.size();
                windows.add(info);
                addedWindows.add(info.token);
                unaccountedSpace.op(info.regionInScreen, unaccountedSpace,
                        Region.Op.REVERSE_DIFFERENCE);
                if (unaccountedSpace.isEmpty() && focusedWindowAdded) {
                    break;
                }
            }
            return shellRootIndex;
        }

        private ArrayList<ShellRoot> getSortedShellRoots(
                SparseArray<ShellRoot> originalShellRoots) {
            ArrayList<ShellRoot> sortedShellRoots = new ArrayList<>(originalShellRoots.size());
            for (int i = originalShellRoots.size() - 1; i >= 0; --i) {
                sortedShellRoots.add(originalShellRoots.valueAt(i));
            }

            sortedShellRoots.sort((left, right) ->
                    mService.mPolicy.getWindowLayerFromTypeLw(right.getWindowType(), true)
                            - mService.mPolicy.getWindowLayerFromTypeLw(left.getWindowType(),
                            true));

            return sortedShellRoots;
        }

        /**
         * Check if windows have changed, and send them to the accessibility subsystem if they have.
         *
         * @param forceSend Send the windows the accessibility even if they haven't changed.
         */
        void computeChangedWindows(boolean forceSend) {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".computeChangedWindows",
                        FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK, "forceSend=" + forceSend);
            }
            if (DEBUG) {
                Slog.i(LOG_TAG, "computeChangedWindows()");
            }

            List<WindowInfo> windows = new ArrayList<>();
            final int topFocusedDisplayId;
            IBinder topFocusedWindowToken = null;

            synchronized (mService.mGlobalLock) {
                // If there is a recents animation running, then use the animation target as the
                // top window state. Otherwise,do not send the windows if there is no top focus as
                // the window manager is still looking for where to put it. We will do the work when
                // we get a focus change callback.
                final RecentsAnimationController controller =
                        mService.getRecentsAnimationController();
                final WindowState topFocusedWindowState = controller != null
                        ? controller.getTargetAppMainWindow()
                        : getTopFocusWindow();
                if (topFocusedWindowState == null) {
                    if (DEBUG) {
                        Slog.d(LOG_TAG, "top focused window is null, compute it again later");
                    }
                    return;
                }

                final DisplayContent dc = mService.mRoot.getDisplayContent(mDisplayId);
                if (dc == null) {
                    //It should not happen because it is created while adding the callback.
                    Slog.w(LOG_TAG, "display content is null, should be created later");
                    return;
                }
                final Display display = dc.getDisplay();
                display.getRealSize(mTempPoint);
                final int screenWidth = mTempPoint.x;
                final int screenHeight = mTempPoint.y;

                Region unaccountedSpace = mTempRegion;
                unaccountedSpace.set(0, 0, screenWidth, screenHeight);

                final SparseArray<WindowState> visibleWindows = mTempWindowStates;
                populateVisibleWindowsOnScreen(visibleWindows);
                Set<IBinder> addedWindows = mTempBinderSet;
                addedWindows.clear();

                boolean focusedWindowAdded = false;

                final int visibleWindowCount = visibleWindows.size();
                ArrayList<TaskFragment> skipRemainingWindowsForTaskFragments = new ArrayList<>();

                ArrayList<ShellRoot> shellRoots = getSortedShellRoots(dc.mShellRoots);

                // Iterate until we figure out what is touchable for the entire screen.
                int shellRootIndex = 0;
                for (int i = visibleWindowCount - 1; i >= 0; i--) {
                    final WindowState windowState = visibleWindows.valueAt(i);
                    int prevShellRootIndex = shellRootIndex;
                    shellRootIndex = addShellRootsIfAbove(windowState, shellRoots, shellRootIndex,
                            windows, addedWindows, unaccountedSpace, focusedWindowAdded);

                    // If a Shell Root was added, it could have accounted for all the space already.
                    if (shellRootIndex > prevShellRootIndex && unaccountedSpace.isEmpty()
                            && focusedWindowAdded) {
                        break;
                    }

                    final Region regionInScreen = new Region();
                    computeWindowRegionInScreen(windowState, regionInScreen);

                    if (windowMattersToAccessibility(windowState, regionInScreen, unaccountedSpace,
                            skipRemainingWindowsForTaskFragments)) {
                        addPopulatedWindowInfo(windowState, regionInScreen, windows, addedWindows);
                        updateUnaccountedSpace(windowState, regionInScreen, unaccountedSpace,
                                skipRemainingWindowsForTaskFragments);
                        focusedWindowAdded |= windowState.isFocused();
                    } else if (isUntouchableNavigationBar(windowState, mTempRegion1)) {
                        // If this widow is navigation bar without touchable region, accounting the
                        // region of navigation bar inset because all touch events from this region
                        // would be received by launcher, i.e. this region is a un-touchable one
                        // for the application.
                        unaccountedSpace.op(getNavBarInsets(dc), unaccountedSpace,
                                Region.Op.REVERSE_DIFFERENCE);
                    }

                    if (unaccountedSpace.isEmpty() && focusedWindowAdded) {
                        break;
                    }
                }

                // Remove child/parent references to windows that were not added.
                final int windowCount = windows.size();
                for (int i = 0; i < windowCount; i++) {
                    WindowInfo window = windows.get(i);
                    if (!addedWindows.contains(window.parentToken)) {
                        window.parentToken = null;
                    }
                    if (window.childTokens != null) {
                        final int childTokenCount = window.childTokens.size();
                        for (int j = childTokenCount - 1; j >= 0; j--) {
                            if (!addedWindows.contains(window.childTokens.get(j))) {
                                window.childTokens.remove(j);
                            }
                        }
                        // Leave the child token list if empty.
                    }
                }

                visibleWindows.clear();
                addedWindows.clear();

                // Gets the top focused display Id and window token for supporting multi-display.
                // If this top focused display is an embedded one, using its parent display as the
                // top focused display.
                final DisplayContent topFocusedDisplayContent =
                        mService.mRoot.getTopFocusedDisplayContent();
                topFocusedDisplayId = isEmbeddedDisplay(topFocusedDisplayContent) ? mDisplayId
                        : topFocusedDisplayContent.getDisplayId();
                topFocusedWindowToken = topFocusedWindowState.mClient.asBinder();
            }
            mCallback.onWindowsForAccessibilityChanged(forceSend, topFocusedDisplayId,
                    topFocusedWindowToken, windows);

            // Recycle the windows as we do not need them.
            clearAndRecycleWindows(windows);
            mInitialized = true;
        }

        private boolean windowMattersToAccessibility(WindowState windowState,
                Region regionInScreen, Region unaccountedSpace,
                ArrayList<TaskFragment> skipRemainingWindowsForTaskFragments) {
            final RecentsAnimationController controller = mService.getRecentsAnimationController();
            if (controller != null && controller.shouldIgnoreForAccessibility(windowState)) {
                return false;
            }

            if (windowState.isFocused()) {
                return true;
            }

            // If the window is part of a task that we're finished with - ignore.
            final TaskFragment taskFragment = windowState.getTaskFragment();
            if (taskFragment != null
                    && skipRemainingWindowsForTaskFragments.contains(taskFragment)) {
                return false;
            }

            // Ignore non-touchable windows, except the split-screen divider, which is
            // occasionally non-touchable but still useful for identifying split-screen
            // mode.
            if (((windowState.mAttrs.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0)
                    && (windowState.mAttrs.type != TYPE_DOCK_DIVIDER)) {
                return false;
            }

            // If the window is completely covered by other windows - ignore.
            if (unaccountedSpace.quickReject(regionInScreen)) {
                return false;
            }

            // Add windows of certain types not covered by modal windows.
            if (isReportedWindowType(windowState.mAttrs.type)) {
                return true;
            }

            return false;
        }

        private void updateUnaccountedSpace(WindowState windowState, Region regionInScreen,
                Region unaccountedSpace,
                ArrayList<TaskFragment> skipRemainingWindowsForTaskFragments) {
            if (windowState.mAttrs.type
                    != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {

                // Account for the space this window takes if the window
                // is not an accessibility overlay which does not change
                // the reported windows.
                unaccountedSpace.op(regionInScreen, unaccountedSpace,
                        Region.Op.REVERSE_DIFFERENCE);

                // If a window is modal it prevents other windows from being touched
                if ((windowState.mAttrs.flags & (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)) == 0) {
                    if (!windowState.hasTapExcludeRegion()) {
                        // Account for all space in the task, whether the windows in it are
                        // touchable or not. The modal window blocks all touches from the task's
                        // area.
                        unaccountedSpace.op(windowState.getDisplayFrame(), unaccountedSpace,
                                Region.Op.REVERSE_DIFFERENCE);
                    } else {
                        // If a window has tap exclude region, we need to account it.
                        final Region displayRegion = new Region(windowState.getDisplayFrame());
                        final Region tapExcludeRegion = new Region();
                        windowState.getTapExcludeRegion(tapExcludeRegion);
                        displayRegion.op(tapExcludeRegion, displayRegion,
                                Region.Op.REVERSE_DIFFERENCE);
                        unaccountedSpace.op(displayRegion, unaccountedSpace,
                                Region.Op.REVERSE_DIFFERENCE);
                    }

                    final TaskFragment taskFragment = windowState.getTaskFragment();
                    if (taskFragment != null) {
                        // If the window is associated with a particular task, we can skip the
                        // rest of the windows for that task.
                        skipRemainingWindowsForTaskFragments.add(taskFragment);
                    } else if (!windowState.hasTapExcludeRegion()) {
                        // If the window is not associated with a particular task, then it is
                        // globally modal. In this case we can skip all remaining windows when
                        // it doesn't has tap exclude region.
                        unaccountedSpace.setEmpty();
                    }
                }

                // Account for the space of letterbox.
                if (windowState.areAppWindowBoundsLetterboxed()) {
                    unaccountedSpace.op(getLetterboxBounds(windowState), unaccountedSpace,
                            Region.Op.REVERSE_DIFFERENCE);
                }
            }
        }

        private void computeWindowRegionInScreen(WindowState windowState, Region outRegion) {
            // Get the touchable frame.
            Region touchableRegion = mTempRegion1;
            windowState.getTouchableRegion(touchableRegion);

            // Map the frame to get what appears on the screen.
            Matrix matrix = mTempMatrix;
            populateTransformationMatrix(windowState, matrix);

            forEachRect(touchableRegion, rect -> {
                // Move to origin as all transforms are captured by the matrix.
                RectF windowFrame = mTempRectF;
                windowFrame.set(rect);
                windowFrame.offset(-windowState.getFrame().left, -windowState.getFrame().top);

                matrix.mapRect(windowFrame);

                // Union all rects.
                outRegion.union(new Rect((int) windowFrame.left, (int) windowFrame.top,
                        (int) windowFrame.right, (int) windowFrame.bottom));
            });
        }

        private static void addPopulatedWindowInfo(WindowState windowState, Region regionInScreen,
                List<WindowInfo> out, Set<IBinder> tokenOut) {
            final WindowInfo window = windowState.getWindowInfo();
            window.regionInScreen.set(regionInScreen);
            window.layer = tokenOut.size();
            out.add(window);
            tokenOut.add(window.token);
        }

        private static void clearAndRecycleWindows(List<WindowInfo> windows) {
            final int windowCount = windows.size();
            for (int i = windowCount - 1; i >= 0; i--) {
                windows.remove(i).recycle();
            }
        }

        private static boolean isReportedWindowType(int windowType) {
            return (windowType != WindowManager.LayoutParams.TYPE_WALLPAPER
                    && windowType != WindowManager.LayoutParams.TYPE_BOOT_PROGRESS
                    && windowType != WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY
                    && windowType != WindowManager.LayoutParams.TYPE_DRAG
                    && windowType != WindowManager.LayoutParams.TYPE_INPUT_CONSUMER
                    && windowType != WindowManager.LayoutParams.TYPE_POINTER
                    && windowType != TYPE_MAGNIFICATION_OVERLAY
                    && windowType != WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY
                    && windowType != WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY
                    && windowType != WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION);
        }

        private void populateVisibleWindowsOnScreen(SparseArray<WindowState> outWindows) {
            final List<WindowState> tempWindowStatesList = new ArrayList<>();
            final DisplayContent dc = mService.mRoot.getDisplayContent(mDisplayId);
            if (dc == null) {
                return;
            }

            dc.forAllWindows(w -> {
                if (w.isVisible()) {
                    tempWindowStatesList.add(w);
                }
            }, false /* traverseTopToBottom */);
            // Insert the re-parented windows in another display below their parents in
            // default display.
            mService.mRoot.forAllWindows(w -> {
                final WindowState parentWindow = findRootDisplayParentWindow(w);
                if (parentWindow == null) {
                    return;
                }

                if (w.isVisible() && tempWindowStatesList.contains(parentWindow)) {
                    tempWindowStatesList.add(tempWindowStatesList.lastIndexOf(parentWindow), w);
                }
            }, false /* traverseTopToBottom */);
            for (int i = 0; i < tempWindowStatesList.size(); i++) {
                outWindows.put(i, tempWindowStatesList.get(i));
            }
        }

        private WindowState findRootDisplayParentWindow(WindowState win) {
            WindowState displayParentWindow = win.getDisplayContent().getParentWindow();
            if (displayParentWindow == null) {
                return null;
            }
            WindowState candidate = displayParentWindow;
            while (candidate != null) {
                displayParentWindow = candidate;
                candidate = displayParentWindow.getDisplayContent().getParentWindow();
            }
            return displayParentWindow;
        }

        private WindowState getTopFocusWindow() {
            return mService.mRoot.getTopFocusedDisplayContent().mCurrentFocus;
        }

        @Override
        public String toString() {
            return "WindowsForAccessibilityObserver{"
                    + "mDisplayId=" + mDisplayId
                    + ", mEmbeddedDisplayIdList="
                    + Arrays.toString(mEmbeddedDisplayIdList.toArray())
                    + ", mInitialized=" + mInitialized
                    + '}';
        }

        private class MyHandler extends Handler {
            public static final int MESSAGE_COMPUTE_CHANGED_WINDOWS = 1;

            public MyHandler(Looper looper) {
                super(looper, null, false);
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MESSAGE_COMPUTE_CHANGED_WINDOWS: {
                        computeChangedWindows(false);
                    } break;
                }
            }
        }
    }

    private static final class AccessibilityControllerInternalImpl
            implements AccessibilityControllerInternal {

        private static AccessibilityControllerInternalImpl sInstance;
        static AccessibilityControllerInternalImpl getInstance(WindowManagerService service) {
            synchronized (STATIC_LOCK) {
                if (sInstance == null) {
                    sInstance = new AccessibilityControllerInternalImpl(service);
                }
                return sInstance;
            }
        }

        private final AccessibilityTracing mTracing;
        private volatile long mEnabledTracingFlags;

        private AccessibilityControllerInternalImpl(WindowManagerService service) {
            mTracing = AccessibilityTracing.getInstance(service);
            mEnabledTracingFlags = 0L;
        }

        @Override
        public void startTrace(long loggingTypes) {
            mEnabledTracingFlags = loggingTypes;
            mTracing.startTrace();
        }

        @Override
        public void stopTrace() {
            mTracing.stopTrace();
            mEnabledTracingFlags = 0L;
        }

        @Override
        public boolean isAccessibilityTracingEnabled() {
            return mTracing.isEnabled();
        }

        boolean isTracingEnabled(long flags) {
            return (flags & mEnabledTracingFlags) != 0L;
        }

        void logTrace(String where, long loggingTypes) {
            logTrace(where, loggingTypes, "");
        }

        void logTrace(String where, long loggingTypes, String callingParams) {
            logTrace(where, loggingTypes, callingParams, "".getBytes(), Binder.getCallingUid());
        }

        void logTrace(String where, long loggingTypes, String callingParams, byte[] a11yDump,
                int callingUid) {
            mTracing.logState(where, loggingTypes, callingParams, a11yDump, callingUid,
                    new HashSet<String>(Arrays.asList("logTrace")));
        }

        @Override
        public void logTrace(String where, long loggingTypes, String callingParams, byte[] a11yDump,
                int callingUid, StackTraceElement[] stackTrace, Set<String> ignoreStackEntries) {
            mTracing.logState(where, loggingTypes, callingParams, a11yDump, callingUid, stackTrace,
                    ignoreStackEntries);
        }

        @Override
        public void logTrace(String where, long loggingTypes, String callingParams, byte[] a11yDump,
                int callingUid, StackTraceElement[] callStack, long timeStamp, int processId,
                long threadId, Set<String> ignoreStackEntries) {
            mTracing.logState(where, loggingTypes, callingParams, a11yDump, callingUid, callStack,
                    timeStamp, processId, threadId, ignoreStackEntries);
        }
    }

    private static final class AccessibilityTracing {
        private static AccessibilityTracing sInstance;
        static AccessibilityTracing getInstance(WindowManagerService service) {
            synchronized (STATIC_LOCK) {
                if (sInstance == null) {
                    sInstance = new AccessibilityTracing(service);
                }
                return sInstance;
            }
        }

        private static final int BUFFER_CAPACITY = 1024 * 1024 * 12;
        private static final String TRACE_FILENAME = "/data/misc/a11ytrace/a11y_trace" + WINSCOPE_EXT;
        private static final String TAG = "AccessibilityTracing";
        private static final long MAGIC_NUMBER_VALUE =
                ((long) MAGIC_NUMBER_H << 32) | MAGIC_NUMBER_L;

        private final Object mLock = new Object();
        private final WindowManagerService mService;
        private final File mTraceFile;
        private final TraceBuffer mBuffer;
        private final LogHandler mHandler;
        private volatile boolean mEnabled;

        AccessibilityTracing(WindowManagerService service) {
            mService = service;
            mTraceFile = new File(TRACE_FILENAME);
            mBuffer = new TraceBuffer(BUFFER_CAPACITY);
            HandlerThread workThread = new HandlerThread(TAG);
            workThread.start();
            mHandler = new LogHandler(workThread.getLooper());
        }

        /**
         * Start the trace.
         */
        void startTrace() {
            if (IS_USER) {
                Slog.e(TAG, "Error: Tracing is not supported on user builds.");
                return;
            }
            synchronized (mLock) {
                mEnabled = true;
                mBuffer.resetBuffer();
            }
        }

        /**
         * Stops the trace and write the current buffer to disk
         */
        void stopTrace() {
            if (IS_USER) {
                Slog.e(TAG, "Error: Tracing is not supported on user builds.");
                return;
            }
            synchronized (mLock) {
                mEnabled = false;
                if (mEnabled) {
                    Slog.e(TAG, "Error: tracing enabled while waiting for flush.");
                    return;
                }
                writeTraceToFile();
            }
        }

        boolean isEnabled() {
            return mEnabled;
        }

        /**
         * Write an accessibility trace log entry.
         */
        void logState(String where, long loggingTypes) {
            if (!mEnabled) {
                return;
            }
            logState(where, loggingTypes, "");
        }

        /**
         * Write an accessibility trace log entry.
         */
        void logState(String where, long loggingTypes, String callingParams) {
            if (!mEnabled) {
                return;
            }
            logState(where, loggingTypes, callingParams, "".getBytes());
        }

        /**
         * Write an accessibility trace log entry.
         */
        void logState(String where, long loggingTypes, String callingParams, byte[] a11yDump) {
            if (!mEnabled) {
                return;
            }
            logState(where, loggingTypes, callingParams, a11yDump, Binder.getCallingUid(),
                    new HashSet<String>(Arrays.asList("logState")));
        }

        /**
         * Write an accessibility trace log entry.
         */
        void logState(String where, long loggingTypes, String callingParams, byte[] a11yDump,
                int callingUid, Set<String> ignoreStackEntries) {
            if (!mEnabled) {
                return;
            }
            StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
            ignoreStackEntries.add("logState");
            logState(where, loggingTypes, callingParams, a11yDump, callingUid, stackTraceElements,
                    ignoreStackEntries);
        }

        /**
         * Write an accessibility trace log entry.
         */
        void logState(String where, long loggingTypes, String callingParams, byte[] a11yDump,
                int callingUid, StackTraceElement[] stackTrace, Set<String> ignoreStackEntries) {
            if (!mEnabled) {
                return;
            }
            log(where, loggingTypes, callingParams, a11yDump, callingUid, stackTrace,
                    SystemClock.elapsedRealtimeNanos(),
                    Process.myPid() + ":" + Application.getProcessName(),
                    Thread.currentThread().getId() + ":" + Thread.currentThread().getName(),
                    ignoreStackEntries);
        }

        /**
         * Write an accessibility trace log entry.
         */
        void logState(String where, long loggingTypes, String callingParams, byte[] a11yDump,
                int callingUid, StackTraceElement[] callingStack, long timeStamp, int processId,
                long threadId, Set<String> ignoreStackEntries) {
            if (!mEnabled) {
                return;
            }
            log(where, loggingTypes, callingParams, a11yDump, callingUid, callingStack, timeStamp,
                    String.valueOf(processId), String.valueOf(threadId), ignoreStackEntries);
        }

        private  String toStackTraceString(StackTraceElement[] stackTraceElements,
                Set<String> ignoreStackEntries) {

            if (stackTraceElements == null) {
                return "";
            }

            StringBuilder stringBuilder = new StringBuilder();
            int i = 0;

            // Skip the first a few elements until after any ignoreStackEntries
            int firstMatch = -1;
            while (i < stackTraceElements.length) {
                for (String ele : ignoreStackEntries) {
                    if (stackTraceElements[i].toString().contains(ele)) {
                        // found the first stack element containing the ignorable stack entries
                        firstMatch = i;
                        break;
                    }
                }
                if (firstMatch < 0) {
                    // Haven't found the first match yet, continue
                    i++;
                } else {
                    break;
                }
            }
            int lastMatch = firstMatch;
            if (i < stackTraceElements.length) {
                i++;
                // Found the first match. Now look for the last match.
                while (i < stackTraceElements.length) {
                    for (String ele : ignoreStackEntries) {
                        if (stackTraceElements[i].toString().contains(ele)) {
                            // This is a match. Look at the next stack element.
                            lastMatch = i;
                            break;
                        }
                    }
                    if (lastMatch != i) {
                        // Found a no-match.
                        break;
                    }
                    i++;
                }
            }

            i = lastMatch + 1;
            while (i < stackTraceElements.length) {
                stringBuilder.append(stackTraceElements[i].toString()).append("\n");
                i++;
            }
            return stringBuilder.toString();
        }

        /**
         * Write the current state to the buffer
         */
        private void log(String where, long loggingTypes, String callingParams, byte[] a11yDump,
                int callingUid, StackTraceElement[] callingStack, long timeStamp,
                String processName, String threadName, Set<String> ignoreStackEntries) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = timeStamp;
            args.arg2 = loggingTypes;
            args.arg3 = where;
            args.arg4 = processName;
            args.arg5 = threadName;
            args.arg6 = ignoreStackEntries;
            args.arg7 = callingParams;
            args.arg8 = callingStack;
            args.arg9 = a11yDump;

            mHandler.obtainMessage(
                    LogHandler.MESSAGE_LOG_TRACE_ENTRY, callingUid, 0, args).sendToTarget();
        }

        /**
         * Writes the trace buffer to new file for the bugreport.
         */
        void writeTraceToFile() {
            mHandler.sendEmptyMessage(LogHandler.MESSAGE_WRITE_FILE);
        }

        private class LogHandler extends Handler {
            public static final int MESSAGE_LOG_TRACE_ENTRY = 1;
            public static final int MESSAGE_WRITE_FILE = 2;

            LogHandler(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MESSAGE_LOG_TRACE_ENTRY: {
                        final SomeArgs args = (SomeArgs) message.obj;
                        try {
                            ProtoOutputStream os = new ProtoOutputStream();
                            PackageManagerInternal pmInternal =
                                    LocalServices.getService(PackageManagerInternal.class);

                            long tokenOuter = os.start(ENTRY);

                            long reportedTimeStampNanos = (long) args.arg1;
                            long currentElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
                            long timeDiffNanos =
                                    currentElapsedRealtimeNanos - reportedTimeStampNanos;
                            long currentTimeMillis = (new Date()).getTime();
                            long reportedTimeMillis =
                                    currentTimeMillis - (long) (timeDiffNanos / 1000000);
                            SimpleDateFormat fm = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

                            os.write(ELAPSED_REALTIME_NANOS, reportedTimeStampNanos);
                            os.write(CALENDAR_TIME, fm.format(reportedTimeMillis).toString());

                            long loggingTypes = (long) args.arg2;
                            List<String> loggingTypeNames =
                                    AccessibilityTrace.getNamesOfLoggingTypes(loggingTypes);

                            for (String type : loggingTypeNames) {
                                os.write(LOGGING_TYPE, type);
                            }
                            os.write(WHERE, (String) args.arg3);
                            os.write(PROCESS_NAME, (String) args.arg4);
                            os.write(THREAD_ID_NAME, (String) args.arg5);
                            os.write(CALLING_PKG, pmInternal.getNameForUid(message.arg1));
                            os.write(CALLING_PARAMS, (String) args.arg7);

                            String callingStack = toStackTraceString(
                                    (StackTraceElement[]) args.arg8, (Set<String>) args.arg6);

                            os.write(CALLING_STACKS, callingStack);
                            os.write(ACCESSIBILITY_SERVICE, (byte[]) args.arg9);

                            long tokenInner = os.start(WINDOW_MANAGER_SERVICE);
                            synchronized (mService.mGlobalLock) {
                                mService.dumpDebugLocked(os, WindowTraceLogLevel.ALL);
                            }
                            os.end(tokenInner);

                            os.end(tokenOuter);
                            synchronized (mLock) {
                                mBuffer.add(os);
                            }
                        } catch (Exception e) {
                            Slog.e(TAG, "Exception while tracing state", e);
                        }
                        break;
                    }
                    case MESSAGE_WRITE_FILE: {
                        synchronized (mLock) {
                            writeTraceToFileInternal();
                        }
                        break;
                    }
                }
            }
        }

        /**
         * Writes the trace buffer to disk.
         */
        private void writeTraceToFileInternal() {
            try {
                ProtoOutputStream proto = new ProtoOutputStream();
                proto.write(MAGIC_NUMBER, MAGIC_NUMBER_VALUE);
                mBuffer.writeTraceToFile(mTraceFile, proto);
            } catch (IOException e) {
                Slog.e(TAG, "Unable to write buffer to file", e);
            }
        }
    }
}
