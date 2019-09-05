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

import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_MAGNIFICATION_OVERLAY;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.utils.RegionUtils.forEachRect;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.app.Service;
import android.content.Context;
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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Display;
import android.view.MagnificationSpec;
import android.view.Surface;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;
import android.view.ViewConfiguration;
import android.view.WindowInfo;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import com.android.internal.R;
import com.android.internal.os.SomeArgs;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.WindowManagerInternal.MagnificationCallbacks;
import com.android.server.wm.WindowManagerInternal.WindowsForAccessibilityCallback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * This class contains the accessibility related logic of the window manager.
 */
final class AccessibilityController {

    private final WindowManagerService mService;

    private static final float[] sTempFloats = new float[9];

    public AccessibilityController(WindowManagerService service) {
        mService = service;
    }

    private SparseArray<DisplayMagnifier> mDisplayMagnifiers = new SparseArray<>();

    private SparseArray<WindowsForAccessibilityObserver> mWindowsForAccessibilityObserver =
            new SparseArray<>();

    public boolean setMagnificationCallbacksLocked(int displayId,
            MagnificationCallbacks callbacks) {
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
            if  (displayMagnifier == null) {
                throw new IllegalStateException("Magnification callbacks already cleared!");
            }
            displayMagnifier.destroyLocked();
            mDisplayMagnifiers.remove(displayId);
            result = true;
        }
        return result;
    }

    public boolean setWindowsForAccessibilityCallbackLocked(int displayId,
            WindowsForAccessibilityCallback callback) {
        if (callback != null) {
            final DisplayContent dc = mService.mRoot.getDisplayContent(displayId);
            if (dc == null) {
                return false;
            }

            if (mWindowsForAccessibilityObserver.get(displayId) != null) {
                final Display display = dc.getDisplay();
                if (display.getType() == Display.TYPE_VIRTUAL && dc.getParentWindow() != null) {
                    // The window observer of this embedded display had been set from
                    // window manager after setting its parent window.
                    return true;
                } else {
                    throw new IllegalStateException(
                            "Windows for accessibility callback of display "
                                    + displayId + " already set!");
                }
            }
            mWindowsForAccessibilityObserver.put(displayId,
                    new WindowsForAccessibilityObserver(mService, displayId, callback));
        } else {
            final WindowsForAccessibilityObserver windowsForA11yObserver =
                    mWindowsForAccessibilityObserver.get(displayId);
            if  (windowsForA11yObserver == null) {
                throw new IllegalStateException(
                        "Windows for accessibility callback of display " + displayId
                                + " already cleared!");
            }
            mWindowsForAccessibilityObserver.remove(displayId);
        }
        return true;
    }

    public void performComputeChangedWindowsNotLocked(int displayId, boolean forceSend) {
        WindowsForAccessibilityObserver observer = null;
        synchronized (mService) {
            final WindowsForAccessibilityObserver windowsForA11yObserver =
                    mWindowsForAccessibilityObserver.get(displayId);
            if (windowsForA11yObserver != null) {
                observer = windowsForA11yObserver;
            }
        }
        if (observer != null) {
            observer.performComputeChangedWindowsNotLocked(forceSend);
        }
    }

    public void setMagnificationSpecLocked(int displayId, MagnificationSpec spec) {
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.setMagnificationSpecLocked(spec);
        }
        final WindowsForAccessibilityObserver windowsForA11yObserver =
                mWindowsForAccessibilityObserver.get(displayId);
        if (windowsForA11yObserver != null) {
            windowsForA11yObserver.scheduleComputeChangedWindowsLocked();
        }
    }

    public void getMagnificationRegionLocked(int displayId, Region outMagnificationRegion) {
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.getMagnificationRegionLocked(outMagnificationRegion);
        }
    }

    public void onRectangleOnScreenRequestedLocked(int displayId, Rect rectangle) {
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.onRectangleOnScreenRequestedLocked(rectangle);
        }
        // Not relevant for the window observer.
    }

    public void onWindowLayersChangedLocked(int displayId) {
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.onWindowLayersChangedLocked();
        }
        final WindowsForAccessibilityObserver windowsForA11yObserver =
                mWindowsForAccessibilityObserver.get(displayId);
        if (windowsForA11yObserver != null) {
            windowsForA11yObserver.scheduleComputeChangedWindowsLocked();
        }
    }

    public void onRotationChangedLocked(DisplayContent displayContent) {
        final int displayId = displayContent.getDisplayId();
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.onRotationChangedLocked(displayContent);
        }
        final WindowsForAccessibilityObserver windowsForA11yObserver =
                mWindowsForAccessibilityObserver.get(displayId);
        if (windowsForA11yObserver != null) {
            windowsForA11yObserver.scheduleComputeChangedWindowsLocked();
        }
    }

    public void onAppWindowTransitionLocked(WindowState windowState, int transition) {
        final int displayId = windowState.getDisplayId();
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.onAppWindowTransitionLocked(windowState, transition);
        }
        // Not relevant for the window observer.
    }

    public void onWindowTransitionLocked(WindowState windowState, int transition) {
        final int displayId = windowState.getDisplayId();
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.onWindowTransitionLocked(windowState, transition);
        }
        final WindowsForAccessibilityObserver windowsForA11yObserver =
                mWindowsForAccessibilityObserver.get(displayId);
        if (windowsForA11yObserver != null) {
            windowsForA11yObserver.scheduleComputeChangedWindowsLocked();
        }
    }

    public void onWindowFocusChangedNotLocked(int displayId) {
        // Not relevant for the display magnifier.

        WindowsForAccessibilityObserver observer = null;
        synchronized (mService) {
            final WindowsForAccessibilityObserver windowsForA11yObserver =
                    mWindowsForAccessibilityObserver.get(displayId);
            if (windowsForA11yObserver != null) {
                observer = windowsForA11yObserver;
            }
        }
        if (observer != null) {
            observer.performComputeChangedWindowsNotLocked(false);
        }
    }

    /**
     * Called when the location or the size of the window is changed. Moving the window to
     * another display is also taken into consideration.
     * @param displayIds the display ids of displays when the situation happens.
     */
    public void onSomeWindowResizedOrMovedLocked(int... displayIds) {
        // Not relevant for the display magnifier.
        for (int i = 0; i < displayIds.length; i++) {
            final WindowsForAccessibilityObserver windowsForA11yObserver =
                    mWindowsForAccessibilityObserver.get(displayIds[i]);
            if (windowsForA11yObserver != null) {
                windowsForA11yObserver.scheduleComputeChangedWindowsLocked();
            }
        }
    }

    /** NOTE: This has to be called within a surface transaction. */
    public void drawMagnifiedRegionBorderIfNeededLocked(int displayId) {
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.drawMagnifiedRegionBorderIfNeededLocked();
        }
        // Not relevant for the window observer.
    }

    public MagnificationSpec getMagnificationSpecForWindowLocked(WindowState windowState) {
        final int displayId = windowState.getDisplayId();
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            return displayMagnifier.getMagnificationSpecForWindowLocked(windowState);
        }
        return null;
    }

    public boolean hasCallbacksLocked() {
        return (mDisplayMagnifiers.size() > 0
                || mWindowsForAccessibilityObserver.size() > 0);
    }

    public void setForceShowMagnifiableBoundsLocked(int displayId, boolean show) {
        final DisplayMagnifier displayMagnifier = mDisplayMagnifiers.get(displayId);
        if (displayMagnifier != null) {
            displayMagnifier.setForceShowMagnifiableBoundsLocked(show);
            displayMagnifier.showMagnificationBoundsIfNeeded();
        }
    }

    public void handleWindowObserverOfEmbeddedDisplayLocked(int embeddedDisplayId,
            WindowState parentWindow) {
        if (embeddedDisplayId == Display.DEFAULT_DISPLAY || parentWindow == null) {
            return;
        }
        // Finds the parent display of this embedded display
        final int parentDisplayId;
        WindowState candidate = parentWindow;
        while (candidate != null) {
            parentWindow = candidate;
            candidate = parentWindow.getDisplayContent().getParentWindow();
        }
        parentDisplayId = parentWindow.getDisplayId();
        // Uses the observer of parent display
        final WindowsForAccessibilityObserver windowsForA11yObserver =
                mWindowsForAccessibilityObserver.get(parentDisplayId);

        if (windowsForA11yObserver != null) {
            // Replaces the observer of embedded display to the one of parent display
            mWindowsForAccessibilityObserver.put(embeddedDisplayId, windowsForA11yObserver);
        }
    }

    private static void populateTransformationMatrixLocked(WindowState windowState,
            Matrix outMatrix) {
        windowState.getTransformationMatrix(sTempFloats, outMatrix);
    }

    /**
     * This class encapsulates the functionality related to display magnification.
     */
    private static final class DisplayMagnifier {

        private static final String LOG_TAG = TAG_WITH_CLASS_NAME ? "DisplayMagnifier" : TAG_WM;

        private static final boolean DEBUG_WINDOW_TRANSITIONS = false;
        private static final boolean DEBUG_ROTATION = false;
        private static final boolean DEBUG_LAYERS = false;
        private static final boolean DEBUG_RECTANGLE_REQUESTED = false;
        private static final boolean DEBUG_VIEWPORT_WINDOW = false;

        private final Rect mTempRect1 = new Rect();
        private final Rect mTempRect2 = new Rect();

        private final Region mTempRegion1 = new Region();
        private final Region mTempRegion2 = new Region();
        private final Region mTempRegion3 = new Region();
        private final Region mTempRegion4 = new Region();

        private final Context mContext;
        private final WindowManagerService mService;
        private final MagnifiedViewport mMagnifedViewport;
        private final Handler mHandler;
        private final DisplayContent mDisplayContent;
        private final Display mDisplay;

        private final MagnificationCallbacks mCallbacks;

        private final long mLongAnimationDuration;

        private boolean mForceShowMagnifiableBounds = false;

        public DisplayMagnifier(WindowManagerService windowManagerService,
                DisplayContent displayContent,
                Display display,
                MagnificationCallbacks callbacks) {
            mContext = windowManagerService.mContext;
            mService = windowManagerService;
            mCallbacks = callbacks;
            mDisplayContent = displayContent;
            mDisplay = display;
            mHandler = new MyHandler(mService.mH.getLooper());
            mMagnifedViewport = new MagnifiedViewport();
            mLongAnimationDuration = mContext.getResources().getInteger(
                    com.android.internal.R.integer.config_longAnimTime);
        }

        public void setMagnificationSpecLocked(MagnificationSpec spec) {
            mMagnifedViewport.updateMagnificationSpecLocked(spec);
            mMagnifedViewport.recomputeBoundsLocked();

            mService.applyMagnificationSpecLocked(mDisplay.getDisplayId(), spec);
            mService.scheduleAnimationLocked();
        }

        public void setForceShowMagnifiableBoundsLocked(boolean show) {
            mForceShowMagnifiableBounds = show;
            mMagnifedViewport.setMagnifiedRegionBorderShownLocked(show, true);
        }

        public boolean isForceShowingMagnifiableBoundsLocked() {
            return mForceShowMagnifiableBounds;
        }

        public void onRectangleOnScreenRequestedLocked(Rect rectangle) {
            if (DEBUG_RECTANGLE_REQUESTED) {
                Slog.i(LOG_TAG, "Rectangle on screen requested: " + rectangle);
            }
            if (!mMagnifedViewport.isMagnifyingLocked()) {
                return;
            }
            Rect magnifiedRegionBounds = mTempRect2;
            mMagnifedViewport.getMagnifiedFrameInContentCoordsLocked(magnifiedRegionBounds);
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

        public void onWindowLayersChangedLocked() {
            if (DEBUG_LAYERS) {
                Slog.i(LOG_TAG, "Layers changed.");
            }
            mMagnifedViewport.recomputeBoundsLocked();
            mService.scheduleAnimationLocked();
        }

        public void onRotationChangedLocked(DisplayContent displayContent) {
            if (DEBUG_ROTATION) {
                final int rotation = displayContent.getRotation();
                Slog.i(LOG_TAG, "Rotation: " + Surface.rotationToString(rotation)
                        + " displayId: " + displayContent.getDisplayId());
            }
            mMagnifedViewport.onRotationChangedLocked();
            mHandler.sendEmptyMessage(MyHandler.MESSAGE_NOTIFY_ROTATION_CHANGED);
        }

        public void onAppWindowTransitionLocked(WindowState windowState, int transition) {
            if (DEBUG_WINDOW_TRANSITIONS) {
                Slog.i(LOG_TAG, "Window transition: "
                        + AppTransition.appTransitionToString(transition)
                        + " displayId: " + windowState.getDisplayId());
            }
            final boolean magnifying = mMagnifedViewport.isMagnifyingLocked();
            if (magnifying) {
                switch (transition) {
                    case WindowManager.TRANSIT_ACTIVITY_OPEN:
                    case WindowManager.TRANSIT_TASK_OPEN:
                    case WindowManager.TRANSIT_TASK_TO_FRONT:
                    case WindowManager.TRANSIT_WALLPAPER_OPEN:
                    case WindowManager.TRANSIT_WALLPAPER_CLOSE:
                    case WindowManager.TRANSIT_WALLPAPER_INTRA_OPEN: {
                        mHandler.sendEmptyMessage(MyHandler.MESSAGE_NOTIFY_USER_CONTEXT_CHANGED);
                    }
                }
            }
        }

        public void onWindowTransitionLocked(WindowState windowState, int transition) {
            if (DEBUG_WINDOW_TRANSITIONS) {
                Slog.i(LOG_TAG, "Window transition: "
                        + AppTransition.appTransitionToString(transition)
                        + " displayId: " + windowState.getDisplayId());
            }
            final boolean magnifying = mMagnifedViewport.isMagnifyingLocked();
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
                            mMagnifedViewport.getMagnifiedFrameInContentCoordsLocked(
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

        public MagnificationSpec getMagnificationSpecForWindowLocked(WindowState windowState) {
            MagnificationSpec spec = mMagnifedViewport.getMagnificationSpecLocked();
            if (spec != null && !spec.isNop()) {
                if (!windowState.shouldMagnify()) {
                    return null;
                }
            }
            return spec;
        }

        public void getMagnificationRegionLocked(Region outMagnificationRegion) {
            // Make sure we're working with the most current bounds
            mMagnifedViewport.recomputeBoundsLocked();
            mMagnifedViewport.getMagnificationRegionLocked(outMagnificationRegion);
        }

        public void destroyLocked() {
            mMagnifedViewport.destroyWindow();
        }

        // Can be called outside of a surface transaction
        public void showMagnificationBoundsIfNeeded() {
            mHandler.obtainMessage(MyHandler.MESSAGE_SHOW_MAGNIFIED_REGION_BOUNDS_IF_NEEDED)
                    .sendToTarget();
        }

        /** NOTE: This has to be called within a surface transaction. */
        public void drawMagnifiedRegionBorderIfNeededLocked() {
            mMagnifedViewport.drawWindowIfNeededLocked();
        }

        private final class MagnifiedViewport {

            private final SparseArray<WindowState> mTempWindowStates =
                    new SparseArray<WindowState>();

            private final RectF mTempRectF = new RectF();

            private final Point mTempPoint = new Point();

            private final Matrix mTempMatrix = new Matrix();

            private final Region mMagnificationRegion = new Region();
            private final Region mOldMagnificationRegion = new Region();

            private final Path mCircularPath;

            private final MagnificationSpec mMagnificationSpec = MagnificationSpec.obtain();

            private final WindowManager mWindowManager;

            private final float mBorderWidth;
            private final int mHalfBorderWidth;
            private final int mDrawBorderInset;

            private final ViewportWindow mWindow;

            private boolean mFullRedrawNeeded;
            private int mTempLayer = 0;

            public MagnifiedViewport() {
                mWindowManager = (WindowManager) mContext.getSystemService(Service.WINDOW_SERVICE);
                mBorderWidth = mContext.getResources().getDimension(
                        com.android.internal.R.dimen.accessibility_magnification_indicator_width);
                mHalfBorderWidth = (int) Math.ceil(mBorderWidth / 2);
                mDrawBorderInset = (int) mBorderWidth / 2;
                mWindow = new ViewportWindow(mContext);

                if (mContext.getResources().getConfiguration().isScreenRound()) {
                    mCircularPath = new Path();
                    mDisplay.getRealSize(mTempPoint);
                    final int centerXY = mTempPoint.x / 2;
                    mCircularPath.addCircle(centerXY, centerXY, centerXY, Path.Direction.CW);
                } else {
                    mCircularPath = null;
                }

                recomputeBoundsLocked();
            }

            public void getMagnificationRegionLocked(@NonNull Region outMagnificationRegion) {
                outMagnificationRegion.set(mMagnificationRegion);
            }

            public void updateMagnificationSpecLocked(MagnificationSpec spec) {
                if (spec != null) {
                    mMagnificationSpec.initialize(spec.scale, spec.offsetX, spec.offsetY);
                } else {
                    mMagnificationSpec.clear();
                }
                // If this message is pending we are in a rotation animation and do not want
                // to show the border. We will do so when the pending message is handled.
                if (!mHandler.hasMessages(
                        MyHandler.MESSAGE_SHOW_MAGNIFIED_REGION_BOUNDS_IF_NEEDED)) {
                    setMagnifiedRegionBorderShownLocked(
                            isMagnifyingLocked() || isForceShowingMagnifiableBoundsLocked(), true);
                }
            }

            public void recomputeBoundsLocked() {
                mDisplay.getRealSize(mTempPoint);
                final int screenWidth = mTempPoint.x;
                final int screenHeight = mTempPoint.y;

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
                populateWindowsOnScreenLocked(visibleWindows);

                final int visibleWindowCount = visibleWindows.size();
                for (int i = visibleWindowCount - 1; i >= 0; i--) {
                    WindowState windowState = visibleWindows.valueAt(i);
                    if ((windowState.mAttrs.type == TYPE_MAGNIFICATION_OVERLAY)
                            || ((windowState.mAttrs.privateFlags
                            & PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY) != 0)) {
                        continue;
                    }

                    // Consider the touchable portion of the window
                    Matrix matrix = mTempMatrix;
                    populateTransformationMatrixLocked(windowState, matrix);
                    Region touchableRegion = mTempRegion3;
                    windowState.getTouchableRegion(touchableRegion);
                    Rect touchableFrame = mTempRect1;
                    touchableRegion.getBounds(touchableFrame);
                    RectF windowFrame = mTempRectF;
                    windowFrame.set(touchableFrame);
                    windowFrame.offset(-windowState.getFrameLw().left,
                            -windowState.getFrameLw().top);
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

                    // Count letterbox into nonMagnifiedBounds
                    if (windowState.isLetterboxedForDisplayCutoutLw()) {
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
                        dirtyRegion.op(mOldMagnificationRegion, Region.Op.UNION);
                        dirtyRegion.op(nonMagnifiedBounds, Region.Op.INTERSECT);
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

            private Region getLetterboxBounds(WindowState windowState) {
                final AppWindowToken appToken = windowState.mAppToken;
                if (appToken == null) {
                    return new Region();
                }

                mDisplay.getRealSize(mTempPoint);
                final Rect letterboxInsets = appToken.getLetterboxInsets();
                final int screenWidth = mTempPoint.x;
                final int screenHeight = mTempPoint.y;
                final Rect nonLetterboxRect = mTempRect1;
                final Region letterboxBounds = mTempRegion3;
                nonLetterboxRect.set(0, 0, screenWidth, screenHeight);
                nonLetterboxRect.inset(letterboxInsets);
                letterboxBounds.set(0, 0, screenWidth, screenHeight);
                letterboxBounds.op(nonLetterboxRect, Region.Op.DIFFERENCE);
                return letterboxBounds;
            }

            public void onRotationChangedLocked() {
                // If we are showing the magnification border, hide it immediately so
                // the user does not see strange artifacts during rotation. The screenshot
                // used for rotation already has the border. After the rotation is complete
                // we will show the border.
                if (isMagnifyingLocked() || isForceShowingMagnifiableBoundsLocked()) {
                    setMagnifiedRegionBorderShownLocked(false, false);
                    final long delay = (long) (mLongAnimationDuration
                            * mService.getWindowAnimationScaleLocked());
                    Message message = mHandler.obtainMessage(
                            MyHandler.MESSAGE_SHOW_MAGNIFIED_REGION_BOUNDS_IF_NEEDED);
                    mHandler.sendMessageDelayed(message, delay);
                }
                recomputeBoundsLocked();
                mWindow.updateSize();
            }

            public void setMagnifiedRegionBorderShownLocked(boolean shown, boolean animate) {
                if (shown) {
                    mFullRedrawNeeded = true;
                    mOldMagnificationRegion.set(0, 0, 0, 0);
                }
                mWindow.setShown(shown, animate);
            }

            public void getMagnifiedFrameInContentCoordsLocked(Rect rect) {
                MagnificationSpec spec = mMagnificationSpec;
                mMagnificationRegion.getBounds(rect);
                rect.offset((int) -spec.offsetX, (int) -spec.offsetY);
                rect.scale(1.0f / spec.scale);
            }

            public boolean isMagnifyingLocked() {
                return mMagnificationSpec.scale > 1.0f;
            }

            public MagnificationSpec getMagnificationSpecLocked() {
                return mMagnificationSpec;
            }

            /** NOTE: This has to be called within a surface transaction. */
            public void drawWindowIfNeededLocked() {
                recomputeBoundsLocked();
                mWindow.drawIfNeeded();
            }

            public void destroyWindow() {
                mWindow.releaseSurface();
            }

            private void populateWindowsOnScreenLocked(SparseArray<WindowState> outWindows) {
                mTempLayer = 0;
                mDisplayContent.forAllWindows((w) -> {
                    if (w.isOnScreen() && w.isVisibleLw()
                            && (w.mAttrs.alpha != 0)
                            && !w.mWinAnimator.mEnterAnimationPending) {
                        mTempLayer++;
                        outWindows.put(mTempLayer, w);
                    }
                }, false /* traverseTopToBottom */ );
            }

            private final class ViewportWindow {
                private static final String SURFACE_TITLE = "Magnification Overlay";

                private final Region mBounds = new Region();
                private final Rect mDirtyRect = new Rect();
                private final Paint mPaint = new Paint();

                private final SurfaceControl mSurfaceControl;
                private final Surface mSurface = mService.mSurfaceFactory.get();

                private final AnimationController mAnimationController;

                private boolean mShown;
                private int mAlpha;

                private boolean mInvalidated;

                public ViewportWindow(Context context) {
                    SurfaceControl surfaceControl = null;
                    try {
                        mDisplay.getRealSize(mTempPoint);
                        surfaceControl = mDisplayContent
                                .makeOverlay()
                                .setName(SURFACE_TITLE)
                                .setBufferSize(mTempPoint.x, mTempPoint.y) // not a typo
                                .setFormat(PixelFormat.TRANSLUCENT)
                                .build();
                    } catch (OutOfResourcesException oore) {
                        /* ignore */
                    }
                    mSurfaceControl = surfaceControl;
                    mSurfaceControl.setLayer(mService.mPolicy.getWindowLayerFromTypeLw(
                            TYPE_MAGNIFICATION_OVERLAY)
                            * WindowManagerService.TYPE_LAYER_MULTIPLIER);
                    mSurfaceControl.setPosition(0, 0);
                    mSurface.copyFrom(mSurfaceControl);

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

                public void setShown(boolean shown, boolean animate) {
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
                public int getAlpha() {
                    synchronized (mService.mGlobalLock) {
                        return mAlpha;
                    }
                }

                public void setAlpha(int alpha) {
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

                public void setBounds(Region bounds) {
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

                public void updateSize() {
                    synchronized (mService.mGlobalLock) {
                        mWindowManager.getDefaultDisplay().getRealSize(mTempPoint);
                        mSurfaceControl.setBufferSize(mTempPoint.x, mTempPoint.y);
                        invalidate(mDirtyRect);
                    }
                }

                public void invalidate(Rect dirtyRect) {
                    if (dirtyRect != null) {
                        mDirtyRect.set(dirtyRect);
                    } else {
                        mDirtyRect.setEmpty();
                    }
                    mInvalidated = true;
                    mService.scheduleAnimationLocked();
                }

                /** NOTE: This has to be called within a surface transaction. */
                public void drawIfNeeded() {
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
                            mSurfaceControl.show();
                        } else {
                            mSurfaceControl.hide();
                        }
                    }
                }

                public void releaseSurface() {
                    mService.mTransactionFactory.get().remove(mSurfaceControl).apply();
                    mSurface.release();
                }

                private final class AnimationController extends Handler {
                    private static final String PROPERTY_NAME_ALPHA = "alpha";

                    private static final int MIN_ALPHA = 0;
                    private static final int MAX_ALPHA = 255;

                    private static final int MSG_FRAME_SHOWN_STATE_CHANGED = 1;

                    private final ValueAnimator mShowHideFrameAnimator;

                    public AnimationController(Context context, Looper looper) {
                        super(looper);
                        mShowHideFrameAnimator = ObjectAnimator.ofInt(ViewportWindow.this,
                                PROPERTY_NAME_ALPHA, MIN_ALPHA, MAX_ALPHA);

                        Interpolator interpolator = new DecelerateInterpolator(2.5f);
                        final long longAnimationDuration = context.getResources().getInteger(
                                com.android.internal.R.integer.config_longAnimTime);

                        mShowHideFrameAnimator.setInterpolator(interpolator);
                        mShowHideFrameAnimator.setDuration(longAnimationDuration);
                    }

                    public void onFrameShownStateChanged(boolean shown, boolean animate) {
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
            public static final int MESSAGE_NOTIFY_ROTATION_CHANGED = 4;
            public static final int MESSAGE_SHOW_MAGNIFIED_REGION_BOUNDS_IF_NEEDED = 5;

            public MyHandler(Looper looper) {
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

                    case MESSAGE_NOTIFY_ROTATION_CHANGED: {
                        final int rotation = message.arg1;
                        mCallbacks.onRotationChanged(rotation);
                    } break;

                    case MESSAGE_SHOW_MAGNIFIED_REGION_BOUNDS_IF_NEEDED : {
                        synchronized (mService.mGlobalLock) {
                            if (mMagnifedViewport.isMagnifyingLocked()
                                    || isForceShowingMagnifiableBoundsLocked()) {
                                mMagnifedViewport.setMagnifiedRegionBorderShownLocked(true, true);
                                mService.scheduleAnimationLocked();
                            }
                        }
                    } break;
                }
            }
        }
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

        private final Rect mTempRect = new Rect();

        private final Region mTempRegion = new Region();

        private final Region mTempRegion1 = new Region();

        private final Context mContext;

        private final WindowManagerService mService;

        private final Handler mHandler;

        private final WindowsForAccessibilityCallback mCallback;

        private final int mDisplayId;

        private final long mRecurringAccessibilityEventsIntervalMillis;

        public WindowsForAccessibilityObserver(WindowManagerService windowManagerService,
                int displayId,
                WindowsForAccessibilityCallback callback) {
            mContext = windowManagerService.mContext;
            mService = windowManagerService;
            mCallback = callback;
            mDisplayId = displayId;
            mHandler = new MyHandler(mService.mH.getLooper());
            mRecurringAccessibilityEventsIntervalMillis = ViewConfiguration
                    .getSendRecurringAccessibilityEventsInterval();
            computeChangedWindows(true);
        }

        public void performComputeChangedWindowsNotLocked(boolean forceSend) {
            mHandler.removeMessages(MyHandler.MESSAGE_COMPUTE_CHANGED_WINDOWS);
            computeChangedWindows(forceSend);
        }

        public void scheduleComputeChangedWindowsLocked() {
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
        public void computeChangedWindows(boolean forceSend) {
            if (DEBUG) {
                Slog.i(LOG_TAG, "computeChangedWindows()");
            }

            List<WindowInfo> windows = new ArrayList<>();
            final int topFocusedDisplayId;
            IBinder topFocusedWindowToken = null;

            synchronized (mService.mGlobalLock) {
                // Do not send the windows if there is no top focus as
                // the window manager is still looking for where to put it.
                // We will do the work when we get a focus change callback.
                final WindowState topFocusedWindowState = getTopFocusWindow();
                if (topFocusedWindowState == null) return;

                final DisplayContent dc = mService.mRoot.getDisplayContent(mDisplayId);
                if (dc == null) {
                    return;
                }
                final Display display = dc.getDisplay();
                display.getRealSize(mTempPoint);
                final int screenWidth = mTempPoint.x;
                final int screenHeight = mTempPoint.y;

                Region unaccountedSpace = mTempRegion;
                unaccountedSpace.set(0, 0, screenWidth, screenHeight);

                final SparseArray<WindowState> visibleWindows = mTempWindowStates;
                populateVisibleWindowsOnScreenLocked(visibleWindows);
                Set<IBinder> addedWindows = mTempBinderSet;
                addedWindows.clear();

                boolean focusedWindowAdded = false;

                final int visibleWindowCount = visibleWindows.size();
                HashSet<Integer> skipRemainingWindowsForTasks = new HashSet<>();

                // Iterate until we figure out what is touchable for the entire screen.
                for (int i = visibleWindowCount - 1; i >= 0; i--) {
                    final WindowState windowState = visibleWindows.valueAt(i);
                    final Region regionInScreen = new Region();
                    computeWindowRegionInScreen(windowState, regionInScreen);

                    if (windowMattersToAccessibility(windowState, regionInScreen, unaccountedSpace,
                            skipRemainingWindowsForTasks)) {
                        addPopulatedWindowInfo(windowState, regionInScreen, windows, addedWindows);
                        updateUnaccountedSpace(windowState, regionInScreen, unaccountedSpace,
                                skipRemainingWindowsForTasks);
                        focusedWindowAdded |= windowState.isFocused();
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
                topFocusedDisplayId = mService.mRoot.getTopFocusedDisplayContent().getDisplayId();
                topFocusedWindowToken = topFocusedWindowState.mClient.asBinder();
            }
            mCallback.onWindowsForAccessibilityChanged(forceSend, topFocusedDisplayId,
                    topFocusedWindowToken, windows);

            // Recycle the windows as we do not need them.
            clearAndRecycleWindows(windows);
        }

        private boolean windowMattersToAccessibility(WindowState windowState,
                Region regionInScreen, Region unaccountedSpace,
                HashSet<Integer> skipRemainingWindowsForTasks) {
            if (windowState.isFocused()) {
                return true;
            }

            // If the window is part of a task that we're finished with - ignore.
            final Task task = windowState.getTask();
            if (task != null && skipRemainingWindowsForTasks.contains(task.mTaskId)) {
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
                Region unaccountedSpace, HashSet<Integer> skipRemainingWindowsForTasks) {
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
                        unaccountedSpace.op(windowState.getDisplayFrameLw(), unaccountedSpace,
                                Region.Op.REVERSE_DIFFERENCE);
                    } else {
                        // If a window has tap exclude region, we need to account it.
                        final Region displayRegion = new Region(windowState.getDisplayFrameLw());
                        final Region tapExcludeRegion = new Region();
                        windowState.amendTapExcludeRegion(tapExcludeRegion);
                        displayRegion.op(tapExcludeRegion, displayRegion,
                                Region.Op.REVERSE_DIFFERENCE);
                        unaccountedSpace.op(displayRegion, unaccountedSpace,
                                Region.Op.REVERSE_DIFFERENCE);
                    }

                    final Task task = windowState.getTask();
                    if (task != null) {
                        // If the window is associated with a particular task, we can skip the
                        // rest of the windows for that task.
                        skipRemainingWindowsForTasks.add(task.mTaskId);
                    } else if (!windowState.hasTapExcludeRegion()) {
                        // If the window is not associated with a particular task, then it is
                        // globally modal. In this case we can skip all remaining windows when
                        // it doesn't has tap exclude region.
                        unaccountedSpace.setEmpty();
                    }
                }
            }
        }

        private void computeWindowRegionInScreen(WindowState windowState, Region outRegion) {
            // Get the touchable frame.
            Region touchableRegion = mTempRegion1;
            windowState.getTouchableRegion(touchableRegion);

            // Map the frame to get what appears on the screen.
            Matrix matrix = mTempMatrix;
            populateTransformationMatrixLocked(windowState, matrix);

            forEachRect(touchableRegion, rect -> {
                // Move to origin as all transforms are captured by the matrix.
                RectF windowFrame = mTempRectF;
                windowFrame.set(rect);
                windowFrame.offset(-windowState.getFrameLw().left, -windowState.getFrameLw().top);

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

        private void populateVisibleWindowsOnScreenLocked(SparseArray<WindowState> outWindows) {
            final List<WindowState> tempWindowStatesList = new ArrayList<>();
            final DisplayContent dc = mService.mRoot.getDisplayContent(mDisplayId);
            if (dc == null) {
                return;
            }

            dc.forAllWindows(w -> {
                if (w.isVisibleLw()) {
                    tempWindowStatesList.add(w);
                }
            }, false /* traverseTopToBottom */);
            // Insert the re-parented windows in another display on top of their parents in
            // default display.
            mService.mRoot.forAllWindows(w -> {
                final WindowState parentWindow = findRootDisplayParentWindow(w);
                if (parentWindow == null) {
                    return;
                }

                if (w.isVisibleLw() && tempWindowStatesList.contains(parentWindow)) {
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
}
