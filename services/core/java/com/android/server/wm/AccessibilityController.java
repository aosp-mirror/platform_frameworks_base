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
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_MAGNIFICATION_OVERLAY;
import static android.view.WindowManager.TRANSIT_FLAG_IS_RECENTS;

import static com.android.internal.util.DumpUtils.dumpSparseArrayValues;
import static com.android.server.accessibility.AccessibilityTraceFileProto.ENTRY;
import static com.android.server.accessibility.AccessibilityTraceFileProto.MAGIC_NUMBER;
import static com.android.server.accessibility.AccessibilityTraceFileProto.MAGIC_NUMBER_H;
import static com.android.server.accessibility.AccessibilityTraceFileProto.MAGIC_NUMBER_L;
import static com.android.server.accessibility.AccessibilityTraceFileProto.REAL_TO_ELAPSED_TIME_OFFSET_NANOS;
import static com.android.server.accessibility.AccessibilityTraceProto.ACCESSIBILITY_SERVICE;
import static com.android.server.accessibility.AccessibilityTraceProto.CALENDAR_TIME;
import static com.android.server.accessibility.AccessibilityTraceProto.CALLING_PARAMS;
import static com.android.server.accessibility.AccessibilityTraceProto.CALLING_PKG;
import static com.android.server.accessibility.AccessibilityTraceProto.CALLING_STACKS;
import static com.android.server.accessibility.AccessibilityTraceProto.CPU_STATS;
import static com.android.server.accessibility.AccessibilityTraceProto.ELAPSED_REALTIME_NANOS;
import static com.android.server.accessibility.AccessibilityTraceProto.LOGGING_TYPE;
import static com.android.server.accessibility.AccessibilityTraceProto.PROCESS_NAME;
import static com.android.server.accessibility.AccessibilityTraceProto.THREAD_ID_NAME;
import static com.android.server.accessibility.AccessibilityTraceProto.WHERE;
import static com.android.server.accessibility.AccessibilityTraceProto.WINDOW_MANAGER_SERVICE;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowTracingLegacy.WINSCOPE_EXT;

import android.accessibilityservice.AccessibilityTrace;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.Point;
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
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.MagnificationSpec;
import android.view.Surface;
import android.view.ViewConfiguration;
import android.view.WindowInfo;
import android.view.WindowManager;
import android.view.WindowManager.TransitionFlags;
import android.view.WindowManager.TransitionType;

import com.android.internal.os.SomeArgs;
import com.android.internal.util.TraceBuffer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.AccessibilityWindowsPopulator.AccessibilityWindow;
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
import java.util.concurrent.TimeUnit;

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

    private final SparseArray<DisplayMagnifier> mDisplayMagnifiers = new SparseArray<>();
    private final SparseArray<WindowsForAccessibilityObserver> mWindowsForAccessibilityObserver =
            new SparseArray<>();
    private SparseArray<IBinder> mFocusedWindow = new SparseArray<>();
    private int mFocusedDisplay = Display.INVALID_DISPLAY;
    private final SparseBooleanArray mIsImeVisibleArray = new SparseBooleanArray();
    // Set to true if initializing window population complete.
    private boolean mAllObserversInitialized = true;
    private final AccessibilityWindowsPopulator mAccessibilityWindowsPopulator;

    AccessibilityController(WindowManagerService service) {
        mService = service;
        mAccessibilityTracing =
                AccessibilityController.getAccessibilityControllerInternal(service);

        mAccessibilityWindowsPopulator = new AccessibilityWindowsPopulator(mService, this);
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
                    final DisplayMagnifier magnifier = new DisplayMagnifier(
                            mService, dc, display, callbacks);
                    magnifier.notifyImeWindowVisibilityChanged(
                            mIsImeVisibleArray.get(displayId, false));
                    mDisplayMagnifiers.put(displayId, magnifier);
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
     * of accessibility on specified display.
     *
     * @param displayId The logical display id.
     * @param callback The callback.
     */
    void setWindowsForAccessibilityCallback(int displayId,
            WindowsForAccessibilityCallback callback) {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK)) {
            mAccessibilityTracing.logTrace(
                    TAG + ".setWindowsForAccessibilityCallback",
                    FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK,
                    "displayId=" + displayId + "; callback={" + callback + "}");
        }

        if (callback != null) {
            WindowsForAccessibilityObserver observer =
                    mWindowsForAccessibilityObserver.get(displayId);
            if (observer != null) {
                final String errorMessage = "Windows for accessibility callback of display "
                        + displayId + " already set!";
                Slog.e(TAG, errorMessage);
                if (Build.IS_DEBUGGABLE) {
                    throw new IllegalStateException(errorMessage);
                }
                mWindowsForAccessibilityObserver.remove(displayId);
            }
            mAccessibilityWindowsPopulator.setWindowsNotification(true);
            observer = new WindowsForAccessibilityObserver(mService, displayId, callback,
                    mAccessibilityWindowsPopulator);
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
            mWindowsForAccessibilityObserver.remove(displayId);

            if (mWindowsForAccessibilityObserver.size() <= 0) {
                mAccessibilityWindowsPopulator.setWindowsNotification(false);
            }
        }
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
        mAccessibilityWindowsPopulator.setMagnificationSpec(displayId, spec);

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

    void onWMTransition(int displayId, @TransitionType int type, @TransitionFlags int flags) {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
            mAccessibilityTracing.logTrace(TAG + ".onWMTransition",
                    FLAGS_MAGNIFICATION_CALLBACK,
                    "displayId=" + displayId + "; type=" + type + "; flags=" + flags);
        }
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.onWMTransition(displayId, type, flags);
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
                    "displayIds={" + Arrays.toString(displayIds) + "}", "".getBytes(), callingUid);
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

    void recomputeMagnifiedRegionAndDrawMagnifiedRegionBorderIfNeeded(int displayId) {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
            mAccessibilityTracing.logTrace(
                    TAG + ".recomputeMagnifiedRegionAndDrawMagnifiedRegionBorderIfNeeded",
                    FLAGS_MAGNIFICATION_CALLBACK,
                    "displayId=" + displayId);
        }

        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.recomputeMagnifiedRegionAndDrawMagnifiedRegionBorderIfNeeded();
        }
        // Not relevant for the window observer.
    }

    public Pair<Matrix, MagnificationSpec> getWindowTransformationMatrixAndMagnificationSpec(
            IBinder token) {
        synchronized (mService.mGlobalLock) {
            final Matrix transformationMatrix = new Matrix();
            final MagnificationSpec magnificationSpec = new MagnificationSpec();

            final WindowState windowState = mService.mWindowMap.get(token);
            if (windowState != null) {
                windowState.getTransformationMatrix(new float[9], transformationMatrix);

                if (hasCallbacks()) {
                    final MagnificationSpec otherMagnificationSpec =
                            getMagnificationSpecForWindow(windowState);
                    if (otherMagnificationSpec != null && !otherMagnificationSpec.isNop()) {
                        magnificationSpec.setTo(otherMagnificationSpec);
                    }
                }
            }

            return new Pair<>(transformationMatrix, magnificationSpec);
        }
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

    void setFullscreenMagnificationActivated(int displayId, boolean activated) {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
            mAccessibilityTracing.logTrace(TAG + ".setFullscreenMagnificationActivated",
                    FLAGS_MAGNIFICATION_CALLBACK,
                    "displayId=" + displayId + "; activated=" + activated);
        }
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.setFullscreenMagnificationActivated(activated);
        }
    }

    void updateImeVisibilityIfNeeded(int displayId, boolean shown) {
        if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
            mAccessibilityTracing.logTrace(TAG + ".updateImeVisibilityIfNeeded",
                    FLAGS_MAGNIFICATION_CALLBACK, "displayId=" + displayId + ";shown=" + shown);
        }

        final boolean isDisplayImeVisible = mIsImeVisibleArray.get(displayId, false);
        if (isDisplayImeVisible == shown) {
            return;
        }

        mIsImeVisibleArray.put(displayId, shown);
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.notifyImeWindowVisibilityChanged(shown);
        }
    }

    private static void populateTransformationMatrix(WindowState windowState,
            Matrix outMatrix) {
        windowState.getTransformationMatrix(sTempFloats, outMatrix);
    }

    void dump(PrintWriter pw, String prefix) {
        dumpSparseArrayValues(pw, prefix, mWindowsForAccessibilityObserver,
                "windows for accessibility observer");
        mAccessibilityWindowsPopulator.dump(pw, prefix);
    }

    void onFocusChanged(InputTarget lastTarget, InputTarget newTarget) {
        if (lastTarget != null) {
            mFocusedWindow.remove(lastTarget.getDisplayId());
            final DisplayMagnifier displayMagnifier =
                    mDisplayMagnifiers.get(lastTarget.getDisplayId());
            if (displayMagnifier != null) {
                displayMagnifier.onFocusLost(lastTarget);
            }
        }
        if (newTarget != null) {
            int displayId = newTarget.getDisplayId();
            IBinder clientBinder = newTarget.getWindowToken();
            mFocusedWindow.put(displayId, clientBinder);
        }
    }

    public void onDisplayRemoved(int displayId) {
        mIsImeVisibleArray.delete(displayId);
        mFocusedWindow.remove(displayId);
    }

    public void setFocusedDisplay(int focusedDisplayId) {
        mFocusedDisplay = focusedDisplayId;
    }

    @Nullable IBinder getFocusedWindowToken() {
        return mFocusedWindow.get(mFocusedDisplay);
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
        private final Handler mHandler;
        private final DisplayContent mDisplayContent;
        private final Display mDisplay;
        private final AccessibilityControllerInternalImpl mAccessibilityTracing;

        private final MagnificationCallbacks mCallbacks;
        private final UserContextChangedNotifier mUserContextChangedNotifier;

        private final long mLongAnimationDuration;

        private boolean mIsFullscreenMagnificationActivated = false;
        private final Region mMagnificationRegion = new Region();
        private final Region mOldMagnificationRegion = new Region();

        private final MagnificationSpec mMagnificationSpec = new MagnificationSpec();

        // Following fields are used for computing magnification region
        private final Path mCircularPath;
        private int mTempLayer = 0;
        private final Point mScreenSize = new Point();
        private final SparseArray<WindowState> mTempWindowStates =
                new SparseArray<WindowState>();
        private final RectF mTempRectF = new RectF();
        private final Matrix mTempMatrix = new Matrix();

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
            mUserContextChangedNotifier = new UserContextChangedNotifier(mHandler);
            mAccessibilityTracing =
                    AccessibilityController.getAccessibilityControllerInternal(mService);
            mLongAnimationDuration = mDisplayContext.getResources().getInteger(
                    com.android.internal.R.integer.config_longAnimTime);
            if (mDisplayContext.getResources().getConfiguration().isScreenRound()) {
                mCircularPath = new Path();

                getDisplaySizeLocked(mScreenSize);
                final int centerXY = mScreenSize.x / 2;
                mCircularPath.addCircle(centerXY, centerXY, centerXY, Path.Direction.CW);
            } else {
                mCircularPath = null;
            }
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".DisplayMagnifier.constructor",
                        FLAGS_MAGNIFICATION_CALLBACK,
                        "windowManagerService={" + windowManagerService + "}; displayContent={"
                                + displayContent + "}; display={" + display + "}; callbacks={"
                                + callbacks + "}");
            }
            recomputeBounds();
        }

        void setMagnificationSpec(MagnificationSpec spec) {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".setMagnificationSpec",
                        FLAGS_MAGNIFICATION_CALLBACK, "spec={" + spec + "}");
            }
            updateMagnificationSpec(spec);
            recomputeBounds();

            mService.applyMagnificationSpecLocked(mDisplay.getDisplayId(), spec);
            mService.scheduleAnimationLocked();
        }

        void updateMagnificationSpec(MagnificationSpec spec) {
            if (spec != null) {
                mMagnificationSpec.initialize(spec.scale, spec.offsetX, spec.offsetY);
            } else {
                mMagnificationSpec.clear();
            }
        }

        void setFullscreenMagnificationActivated(boolean activated) {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".setFullscreenMagnificationActivated",
                        FLAGS_MAGNIFICATION_CALLBACK, "activated=" + activated);
            }
            mIsFullscreenMagnificationActivated = activated;
        }

        boolean isFullscreenMagnificationActivated() {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".isFullscreenMagnificationActivated",
                        FLAGS_MAGNIFICATION_CALLBACK);
            }
            return mIsFullscreenMagnificationActivated;
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

            recomputeBounds();
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
            final boolean isMagnifierActivated = isFullscreenMagnificationActivated();
            if (!isMagnifierActivated) {
                return;
            }
            switch (transition) {
                case WindowManager.TRANSIT_OLD_ACTIVITY_OPEN:
                case WindowManager.TRANSIT_OLD_TASK_FRAGMENT_OPEN:
                case WindowManager.TRANSIT_OLD_TASK_OPEN:
                case WindowManager.TRANSIT_OLD_TASK_TO_FRONT:
                case WindowManager.TRANSIT_OLD_WALLPAPER_OPEN:
                case WindowManager.TRANSIT_OLD_WALLPAPER_CLOSE:
                case WindowManager.TRANSIT_OLD_WALLPAPER_INTRA_OPEN: {
                    mUserContextChangedNotifier.onAppWindowTransition(transition);
                }
            }
        }

        void onWMTransition(int displayId, @TransitionType int type, @TransitionFlags int flags) {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".onWMTransition",
                        FLAGS_MAGNIFICATION_CALLBACK,
                        "displayId=" + displayId + "; type=" + type + "; flags=" + flags);
            }
            if (DEBUG_WINDOW_TRANSITIONS) {
                Slog.i(LOG_TAG, "Window transition: " + WindowManager.transitTypeToString(type)
                        + " displayId: " + displayId);
            }
            final boolean isMagnifierActivated = isFullscreenMagnificationActivated();
            if (!isMagnifierActivated) {
                return;
            }
            // All opening/closing situations.
            switch (type) {
                case WindowManager.TRANSIT_OPEN:
                case WindowManager.TRANSIT_TO_FRONT:
                case WindowManager.TRANSIT_CLOSE:
                case WindowManager.TRANSIT_TO_BACK:
                    mUserContextChangedNotifier.onWMTransition(type, flags);
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
            final boolean isMagnifierActivated = isFullscreenMagnificationActivated();
            if (!isMagnifierActivated || !windowState.shouldMagnify()) {
                return;
            }
            mUserContextChangedNotifier.onWindowTransition(windowState, transition);
            final int type = windowState.mAttrs.type;
            switch (transition) {
                case WindowManagerPolicy.TRANSIT_ENTER:
                case WindowManagerPolicy.TRANSIT_SHOW: {
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
                            getMagnifiedFrameInContentCoords(magnifiedRegionBounds);
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

        void onFocusLost(InputTarget target) {
            final boolean isMagnifierActivated = isFullscreenMagnificationActivated();
            if (!isMagnifierActivated) {
                return;
            }
            mUserContextChangedNotifier.onFocusLost(target);
        }

        void getMagnifiedFrameInContentCoords(Rect rect) {
            mMagnificationRegion.getBounds(rect);
            rect.offset((int) -mMagnificationSpec.offsetX, (int) -mMagnificationSpec.offsetY);
            rect.scale(1.0f / mMagnificationSpec.scale);
        }

        void notifyImeWindowVisibilityChanged(boolean shown) {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".notifyImeWindowVisibilityChanged",
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

            if (mMagnificationSpec != null && !mMagnificationSpec.isNop()) {
                if (!windowState.shouldMagnify()) {
                    return null;
                }
            }
            return mMagnificationSpec;
        }

        void getMagnificationRegion(Region outMagnificationRegion) {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".getMagnificationRegion",
                        FLAGS_MAGNIFICATION_CALLBACK,
                        "outMagnificationRegion={" + outMagnificationRegion + "}");
            }
            // Make sure we're working with the most current bounds
            recomputeBounds();
            outMagnificationRegion.set(mMagnificationRegion);
        }

        boolean isMagnifying() {
            return mMagnificationSpec.scale > 1.0f;
        }

        void destroy() {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG + ".destroy", FLAGS_MAGNIFICATION_CALLBACK);
            }
        }

        void recomputeMagnifiedRegionAndDrawMagnifiedRegionBorderIfNeeded() {
            if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                mAccessibilityTracing.logTrace(LOG_TAG
                                + ".recomputeMagnifiedRegionAndDrawMagnifiedRegionBorderIfNeeded",
                        FLAGS_MAGNIFICATION_CALLBACK);
            }
            recomputeBounds();
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
                        & PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION) != 0)
                        || ((windowState.mAttrs.privateFlags
                        & PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY) != 0)) {
                    continue;
                }

                // Consider the touchable portion of the window
                Matrix matrix = mTempMatrix;
                populateTransformationMatrix(windowState, matrix);
                Region touchableRegion = mTempRegion3;
                windowState.getTouchableRegion(touchableRegion);
                Region windowBounds = mTempRegion2;

                // For b/323366243, if using the bounds from touchableRegion.getBounds, in
                // non-magnifiable windowBounds computation, part of the non-touchableRegion
                // may be included into nonMagnifiedBounds. This will make users lose
                // the magnification control on mis-included areas.
                // Therefore, to prevent the above issue, we change to use the window exact
                // touchableRegion in magnificationRegion computation.
                // Like the original approach, the touchableRegion is in non-magnified display
                // space, so first we need to offset the region by the windowFrames bounds, then
                // apply the transform matrix to the region to get the exact region in magnified
                // display space.
                // TODO: For a long-term plan, since touchable regions provided by WindowState
                //  doesn't actually reflect the real touchable regions on display, we should
                //  delete the WindowState dependency and migrate to use the touchableRegion
                //  from WindowInfoListener data. (b/330653961)
                touchableRegion.translate(-windowState.getFrame().left,
                        -windowState.getFrame().top);
                applyMatrixToRegion(matrix, touchableRegion);
                windowBounds.set(touchableRegion);

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
                    final Rect navBarInsets = getSystemBarInsetsFrame(windowState);
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

            final boolean magnifiedChanged =
                    !mOldMagnificationRegion.equals(mMagnificationRegion);
            if (magnifiedChanged) {
                mOldMagnificationRegion.set(mMagnificationRegion);
                final SomeArgs args = SomeArgs.obtain();
                args.arg1 = Region.obtain(mMagnificationRegion);
                mHandler.obtainMessage(
                                MyHandler.MESSAGE_NOTIFY_MAGNIFICATION_REGION_CHANGED, args)
                        .sendToTarget();
            }
        }

        private Region getLetterboxBounds(WindowState windowState) {
            final ActivityRecord appToken = windowState.mActivityRecord;
            if (appToken == null) {
                return new Region();
            }

            final Rect boundsWithoutLetterbox = windowState.getBounds();
            final Rect letterboxInsets = appToken.getLetterboxInsets();

            final Rect boundsIncludingLetterbox = Rect.copyOrNull(boundsWithoutLetterbox);
            // Letterbox insets from mActivityRecord are positive, so we negate them to grow the
            // bounds to include the letterbox.
            boundsIncludingLetterbox.inset(
                    Insets.subtract(Insets.NONE, Insets.of(letterboxInsets)));

            final Region letterboxBounds = new Region();
            letterboxBounds.set(boundsIncludingLetterbox);
            letterboxBounds.op(boundsWithoutLetterbox, Region.Op.DIFFERENCE);
            return letterboxBounds;
        }

        private boolean isExcludedWindowType(int windowType) {
            return windowType == TYPE_MAGNIFICATION_OVERLAY
                    // Omit the touch region of window magnification to avoid the cut out of the
                    // magnification and the magnified center of window magnification could be
                    // in the bounds
                    || windowType == TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY;
        }

        private void applyMatrixToRegion(Matrix matrix, Region region) {
            // Since Matrix does not support mapRegion api, so we follow the Matrix#mapRect logic
            // to apply the matrix to the given region.
            // In Matrix#mapRect, the internal calculation is applying the transform matrix to
            // rect's 4 corner points with the below calculation. (see SkMatrix::mapPoints)
            //      |A B C| |x|                               Ax+By+C   Dx+Ey+F
            //      |D E F| |y| = |Ax+By+C Dx+Ey+F Gx+Hy+I| = ------- , -------
            //      |G H I| |1|                               Gx+Hy+I   Gx+Hy+I
            // For magnification usage, the matrix is created from
            // WindowState#getTransformationMatrix. We can simplify the matrix calculation to be
            //      |scale   0   trans_x| |x|
            //      |  0   scale trans_y| |y| = (scale*x + trans_x, scale*y + trans_y)
            //      |  0     0      1   | |1|
            // So, to follow the simplified matrix computation, we first scale the region with
            // matrix.scale, then translate the region with matrix.trans_x and matrix.trans_y.
            float[] transformArray = sTempFloats;
            matrix.getValues(transformArray);
            // For magnification transform matrix, the scale_x and scale_y are equal.
            region.scale(transformArray[Matrix.MSCALE_X]);
            region.translate(
                    (int) transformArray[Matrix.MTRANS_X],
                    (int) transformArray[Matrix.MTRANS_Y]);
        }

        private void populateWindowsOnScreen(SparseArray<WindowState> outWindows) {
            mTempLayer = 0;
            mDisplayContent.forAllWindows((w) -> {
                if (w.isOnScreen() && w.isVisible()
                        && (w.mAttrs.alpha != 0)) {
                    mTempLayer++;
                    outWindows.put(mTempLayer, w);
                }
            }, /* traverseTopToBottom= */ false);
        }

        private void getDisplaySizeLocked(Point outSize) {
            final Rect bounds =
                    mDisplayContent.getConfiguration().windowConfiguration.getBounds();
            outSize.set(bounds.width(), bounds.height());
        }

        private class MyHandler extends Handler {
            public static final int MESSAGE_NOTIFY_MAGNIFICATION_REGION_CHANGED = 1;
            public static final int MESSAGE_NOTIFY_USER_CONTEXT_CHANGED = 3;
            public static final int MESSAGE_NOTIFY_DISPLAY_SIZE_CHANGED = 4;
            public static final int MESSAGE_NOTIFY_IME_WINDOW_VISIBILITY_CHANGED = 5;

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

                    case MESSAGE_NOTIFY_USER_CONTEXT_CHANGED: {
                        mCallbacks.onUserContextChanged();
                    } break;

                    case MESSAGE_NOTIFY_DISPLAY_SIZE_CHANGED: {
                        mCallbacks.onDisplaySizeChanged();
                    } break;

                    case MESSAGE_NOTIFY_IME_WINDOW_VISIBILITY_CHANGED: {
                        final boolean shown = message.arg1 == 1;
                        mCallbacks.onImeWindowVisibilityChanged(shown);
                    } break;
                }
            }
        }

        private class UserContextChangedNotifier {

            private final Handler mHandler;

            private boolean mHasDelayedNotificationForRecentsToFrontTransition;

            UserContextChangedNotifier(Handler handler) {
                mHandler = handler;
            }

            void onAppWindowTransition(int transition) {
                sendUserContextChangedNotification();
            }

            // For b/324949652, if the onWMTransition callback is triggered when the finger down
            // event on navigation bar to bring the recents window to front, we'll delay the
            // notifying of the context changed, then send it if there is a following onFocusChanged
            // callback triggered. Before the onFocusChanged, if there are some other transitions
            // causing the notifying, or the recents/home window is removed, then we won't need the
            // delayed notification anymore.
            void onWMTransition(@TransitionType int type, @TransitionFlags int flags) {
                if (type == WindowManager.TRANSIT_TO_FRONT
                        && (flags & TRANSIT_FLAG_IS_RECENTS) != 0) {
                    // Delay the recents to front transition notification then send after if needed.
                    mHasDelayedNotificationForRecentsToFrontTransition = true;
                } else {
                    sendUserContextChangedNotification();
                }
            }

            void onWindowTransition(WindowState windowState, int transition) {
                // If there is a delayed notification for recents to front transition but the
                // home/recents window has been removed from screen, the delayed notification is not
                // needed anymore.
                if (transition == WindowManagerPolicy.TRANSIT_EXIT
                        && windowState.isActivityTypeHomeOrRecents()
                        && mHasDelayedNotificationForRecentsToFrontTransition) {
                    mHasDelayedNotificationForRecentsToFrontTransition = false;
                }
            }

            void onFocusLost(InputTarget target) {
                // If there is a delayed notification for recents to front transition and
                // onFocusLost is triggered, we assume that the users leave current window to
                // the home/recents window, thus we'll need to send the delayed notification.
                if (mHasDelayedNotificationForRecentsToFrontTransition) {
                    sendUserContextChangedNotification();
                }
            }

            private void sendUserContextChangedNotification() {
                // Since the context changed will be notified, the delayed notification is
                // not needed anymore.
                mHasDelayedNotificationForRecentsToFrontTransition = false;
                mHandler.sendEmptyMessage(MyHandler.MESSAGE_NOTIFY_USER_CONTEXT_CHANGED);
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

    static Rect getSystemBarInsetsFrame(WindowState win) {
        if (win == null) {
            return EMPTY_RECT;
        }
        final InsetsSourceProvider provider = win.getControllableInsetProvider();
        return provider != null ? provider.getSource().getFrame() : EMPTY_RECT;
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

        private final Set<IBinder> mTempBinderSet = new ArraySet<>();

        private final Region mTempRegion = new Region();

        private final Region mTempRegion2 = new Region();

        private final WindowManagerService mService;

        private final Handler mHandler;

        private final AccessibilityControllerInternalImpl mAccessibilityTracing;

        private final WindowsForAccessibilityCallback mCallback;

        private final int mDisplayId;

        private final long mRecurringAccessibilityEventsIntervalMillis;

        // Set to true if initializing window population complete.
        private boolean mInitialized;
        private final AccessibilityWindowsPopulator mA11yWindowsPopulator;

        WindowsForAccessibilityObserver(WindowManagerService windowManagerService,
                int displayId, WindowsForAccessibilityCallback callback,
                AccessibilityWindowsPopulator accessibilityWindowsPopulator) {
            mService = windowManagerService;
            mCallback = callback;
            mDisplayId = displayId;
            mHandler = new MyHandler(mService.mH.getLooper());
            mAccessibilityTracing =
                    AccessibilityController.getAccessibilityControllerInternal(mService);
            mRecurringAccessibilityEventsIntervalMillis = ViewConfiguration
                    .getSendRecurringAccessibilityEventsInterval();
            mA11yWindowsPopulator = accessibilityWindowsPopulator;
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

            List<WindowInfo> windows = null;
            final List<AccessibilityWindow> visibleWindows = new ArrayList<>();
            final Point screenSize = new Point();
            final int topFocusedDisplayId;
            IBinder topFocusedWindowToken = null;

            synchronized (mService.mGlobalLock) {
                final WindowState topFocusedWindowState = getTopFocusWindow();
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
                display.getRealSize(screenSize);

                mA11yWindowsPopulator.populateVisibleWindowsOnScreenLocked(
                        mDisplayId, visibleWindows);

                // Gets the top focused display Id and window token for supporting multi-display.
                topFocusedDisplayId = mService.mRoot.getTopFocusedDisplayContent().getDisplayId();
                topFocusedWindowToken = topFocusedWindowState.mClient.asBinder();
            }

            mCallback.onAccessibilityWindowsChanged(forceSend, topFocusedDisplayId,
                    topFocusedWindowToken, screenSize, visibleWindows);

            // Recycle the windows as we do not need them.
            for (final AccessibilityWindowsPopulator.AccessibilityWindow window : visibleWindows) {
                window.getWindowInfo().recycle();
            }
            mInitialized = true;
        }

        private WindowState getTopFocusWindow() {
            return mService.mRoot.getTopFocusedDisplayContent().mCurrentFocus;
        }

        @Override
        public String toString() {
            return "WindowsForAccessibilityObserver{"
                    + "mDisplayId=" + mDisplayId
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

    static final class AccessibilityControllerInternalImpl
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
        private UiChangesForAccessibilityCallbacksDispatcher mCallbacksDispatcher;
        private final Looper mLooper;

        private AccessibilityControllerInternalImpl(WindowManagerService service) {
            mLooper = service.mH.getLooper();
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

        @Override
        public void setUiChangesForAccessibilityCallbacks(
                UiChangesForAccessibilityCallbacks callbacks) {
            if (isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                logTrace(
                        TAG + ".setAccessibilityWindowManagerCallbacks",
                        FLAGS_MAGNIFICATION_CALLBACK,
                        "callbacks={" + callbacks + "}");
            }
            if (callbacks != null) {
                if (mCallbacksDispatcher != null) {
                    throw new IllegalStateException("Accessibility window manager callback already "
                            + "set!");
                }
                mCallbacksDispatcher =
                        new UiChangesForAccessibilityCallbacksDispatcher(this, mLooper,
                                callbacks);
            } else {
                if (mCallbacksDispatcher == null) {
                    throw new IllegalStateException("Accessibility window manager callback already "
                            + "cleared!");
                }
                mCallbacksDispatcher = null;
            }
        }

        public boolean hasWindowManagerEventDispatcher() {
            if (isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK
                    | FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK)) {
                logTrace(TAG + ".hasCallbacks",
                        FLAGS_MAGNIFICATION_CALLBACK | FLAGS_WINDOWS_FOR_ACCESSIBILITY_CALLBACK);
            }
            return mCallbacksDispatcher != null;
        }

        public void onRectangleOnScreenRequested(int displayId, Rect rectangle) {
            if (isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                logTrace(
                        TAG + ".onRectangleOnScreenRequested",
                        FLAGS_MAGNIFICATION_CALLBACK,
                        "rectangle={" + rectangle + "}");
            }
            if (mCallbacksDispatcher != null) {
                mCallbacksDispatcher.onRectangleOnScreenRequested(displayId, rectangle);
            }
        }

        private static final class UiChangesForAccessibilityCallbacksDispatcher {

            private static final String LOG_TAG = TAG_WITH_CLASS_NAME
                    ? "WindowManagerEventDispatcher" : TAG_WM;

            private static final boolean DEBUG_RECTANGLE_REQUESTED = false;

            private final AccessibilityControllerInternalImpl mAccessibilityTracing;

            @NonNull
            private final UiChangesForAccessibilityCallbacks mCallbacks;

            private final Handler mHandler;

            UiChangesForAccessibilityCallbacksDispatcher(
                    AccessibilityControllerInternalImpl accessibilityControllerInternal,
                    Looper looper, @NonNull UiChangesForAccessibilityCallbacks callbacks) {
                mAccessibilityTracing = accessibilityControllerInternal;
                mCallbacks = callbacks;
                mHandler = new Handler(looper);
            }

            void onRectangleOnScreenRequested(int displayId, Rect rectangle) {
                if (mAccessibilityTracing.isTracingEnabled(FLAGS_MAGNIFICATION_CALLBACK)) {
                    mAccessibilityTracing.logTrace(LOG_TAG + ".onRectangleOnScreenRequested",
                            FLAGS_MAGNIFICATION_CALLBACK, "rectangle={" + rectangle + "}");
                }
                if (DEBUG_RECTANGLE_REQUESTED) {
                    Slog.i(LOG_TAG, "Rectangle on screen requested: " + rectangle);
                }
                final Message m = PooledLambda.obtainMessage(
                        mCallbacks::onRectangleOnScreenRequested, displayId, rectangle.left,
                        rectangle.top, rectangle.right, rectangle.bottom);
                mHandler.sendMessage(m);
            }
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

        private static final int CPU_STATS_COUNT = 5;
        private static final int BUFFER_CAPACITY = 1024 * 1024 * 12;
        private static final String TRACE_FILENAME = "/data/misc/a11ytrace/a11y_trace"
                + WINSCOPE_EXT;
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
            args.argl1 = timeStamp;
            args.argl2 = loggingTypes;
            args.arg1 = where;
            args.arg2 = processName;
            args.arg3 = threadName;
            args.arg4 = ignoreStackEntries;
            args.arg5 = callingParams;
            args.arg6 = callingStack;
            args.arg7 = a11yDump;

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

                            long reportedTimeStampNanos = args.argl1;
                            long currentElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
                            long timeDiffNanos =
                                    currentElapsedRealtimeNanos - reportedTimeStampNanos;
                            long currentTimeMillis = (new Date()).getTime();
                            long reportedTimeMillis =
                                    currentTimeMillis - (long) (timeDiffNanos / 1000000);
                            SimpleDateFormat fm = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

                            os.write(ELAPSED_REALTIME_NANOS, reportedTimeStampNanos);
                            os.write(CALENDAR_TIME, fm.format(reportedTimeMillis).toString());

                            long loggingTypes = args.argl2;
                            List<String> loggingTypeNames =
                                    AccessibilityTrace.getNamesOfLoggingTypes(loggingTypes);

                            for (String type : loggingTypeNames) {
                                os.write(LOGGING_TYPE, type);
                            }
                            os.write(WHERE, (String) args.arg1);
                            os.write(PROCESS_NAME, (String) args.arg2);
                            os.write(THREAD_ID_NAME, (String) args.arg3);
                            os.write(CALLING_PKG, pmInternal.getNameForUid(message.arg1));
                            os.write(CALLING_PARAMS, (String) args.arg5);

                            String callingStack = toStackTraceString(
                                    (StackTraceElement[]) args.arg6, (Set<String>) args.arg4);

                            os.write(CALLING_STACKS, callingStack);
                            os.write(ACCESSIBILITY_SERVICE, (byte[]) args.arg7);

                            long tokenInner = os.start(WINDOW_MANAGER_SERVICE);
                            synchronized (mService.mGlobalLock) {
                                mService.dumpDebugLocked(os, WindowTracingLogLevel.ALL);
                            }
                            os.end(tokenInner);
                            os.write(CPU_STATS, printCpuStats(reportedTimeStampNanos));

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
                long timeOffsetNs =
                        TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis())
                        - SystemClock.elapsedRealtimeNanos();
                proto.write(REAL_TO_ELAPSED_TIME_OFFSET_NANOS, timeOffsetNs);
                mBuffer.writeTraceToFile(mTraceFile, proto);
            } catch (IOException e) {
                Slog.e(TAG, "Unable to write buffer to file", e);
            }
        }

        /**
         * Returns the string of CPU stats.
         */
        private String printCpuStats(long timeStampNanos) {
            Pair<String, String> stats = mService.mAmInternal.getAppProfileStatsForDebugging(
                    timeStampNanos, CPU_STATS_COUNT);

            return stats.first + stats.second;
        }
    }
}
