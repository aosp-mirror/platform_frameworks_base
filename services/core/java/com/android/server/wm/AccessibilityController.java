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

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
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
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.MagnificationSpec;
import android.view.Surface;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;
import android.view.ViewConfiguration;
import android.view.WindowInfo;
import android.view.WindowManager;
import android.view.WindowManagerInternal.MagnificationCallbacks;
import android.view.WindowManagerInternal.WindowsForAccessibilityCallback;
import android.view.WindowManagerPolicy;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import com.android.internal.R;
import com.android.internal.os.SomeArgs;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * This class contains the accessibility related logic of the window manger.
 */
final class AccessibilityController {

    private final WindowManagerService mWindowManagerService;

    private static final float[] sTempFloats = new float[9];

    public AccessibilityController(WindowManagerService service) {
        mWindowManagerService = service;
    }

    private DisplayMagnifier mDisplayMagnifier;

    private WindowsForAccessibilityObserver mWindowsForAccessibilityObserver;

    public void setMagnificationCallbacksLocked(MagnificationCallbacks callbacks) {
        if (callbacks != null) {
            if (mDisplayMagnifier != null) {
                throw new IllegalStateException("Magnification callbacks already set!");
            }
            mDisplayMagnifier = new DisplayMagnifier(mWindowManagerService, callbacks);
        } else {
            if  (mDisplayMagnifier == null) {
                throw new IllegalStateException("Magnification callbacks already cleared!");
            }
            mDisplayMagnifier.destroyLocked();
            mDisplayMagnifier = null;
        }
    }

    public void setWindowsForAccessibilityCallback(WindowsForAccessibilityCallback callback) {
        if (callback != null) {
            if (mWindowsForAccessibilityObserver != null) {
                throw new IllegalStateException(
                        "Windows for accessibility callback already set!");
            }
            mWindowsForAccessibilityObserver = new WindowsForAccessibilityObserver(
                    mWindowManagerService, callback);
        } else {
            if (mWindowsForAccessibilityObserver == null) {
                throw new IllegalStateException(
                        "Windows for accessibility callback already cleared!");
            }
            mWindowsForAccessibilityObserver = null;
        }
    }

    public void setMagnificationSpecLocked(MagnificationSpec spec) {
        if (mDisplayMagnifier != null) {
            mDisplayMagnifier.setMagnificationSpecLocked(spec);
        }
        if (mWindowsForAccessibilityObserver != null) {
            mWindowsForAccessibilityObserver.scheduleComputeChangedWindowsLocked();
        }
    }

    public void onRectangleOnScreenRequestedLocked(Rect rectangle) {
        if (mDisplayMagnifier != null) {
            mDisplayMagnifier.onRectangleOnScreenRequestedLocked(rectangle);
        }
        // Not relevant for the window observer.
    }

    public void onWindowLayersChangedLocked() {
        if (mDisplayMagnifier != null) {
            mDisplayMagnifier.onWindowLayersChangedLocked();
        }
        if (mWindowsForAccessibilityObserver != null) {
            mWindowsForAccessibilityObserver.scheduleComputeChangedWindowsLocked();
        }
    }

    public void onRotationChangedLocked(DisplayContent displayContent, int rotation) {
        if (mDisplayMagnifier != null) {
            mDisplayMagnifier.onRotationChangedLocked(displayContent, rotation);
        }
        if (mWindowsForAccessibilityObserver != null) {
            mWindowsForAccessibilityObserver.scheduleComputeChangedWindowsLocked();
        }
    }

    public void onAppWindowTransitionLocked(WindowState windowState, int transition) {
        if (mDisplayMagnifier != null) {
            mDisplayMagnifier.onAppWindowTransitionLocked(windowState, transition);
        }
        // Not relevant for the window observer.
    }

    public void onWindowTransitionLocked(WindowState windowState, int transition) {
        if (mDisplayMagnifier != null) {
            mDisplayMagnifier.onWindowTransitionLocked(windowState, transition);
        }
        if (mWindowsForAccessibilityObserver != null) {
            mWindowsForAccessibilityObserver.scheduleComputeChangedWindowsLocked();
        }
    }

    public void onWindowFocusChangedLocked() {
        // Not relevant for the display magnifier.

        if (mWindowsForAccessibilityObserver != null) {
            mWindowsForAccessibilityObserver.scheduleComputeChangedWindowsLocked();
        }
    }


    public void onSomeWindowResizedOrMovedLocked() {
        // Not relevant for the display magnifier.

        if (mWindowsForAccessibilityObserver != null) {
            mWindowsForAccessibilityObserver.scheduleComputeChangedWindowsLocked();
        }
    }

    /** NOTE: This has to be called within a surface transaction. */
    public void drawMagnifiedRegionBorderIfNeededLocked() {
        if (mDisplayMagnifier != null) {
            mDisplayMagnifier.drawMagnifiedRegionBorderIfNeededLocked();
        }
        // Not relevant for the window observer.
    }

    public MagnificationSpec getMagnificationSpecForWindowLocked(WindowState windowState) {
        if (mDisplayMagnifier != null) {
            return mDisplayMagnifier.getMagnificationSpecForWindowLocked(windowState);
        }
        return null;
    }

    public boolean hasCallbacksLocked() {
        return (mDisplayMagnifier != null
                || mWindowsForAccessibilityObserver != null);
    }

    private static void populateTransformationMatrixLocked(WindowState windowState,
            Matrix outMatrix) {
        sTempFloats[Matrix.MSCALE_X] = windowState.mWinAnimator.mDsDx;
        sTempFloats[Matrix.MSKEW_Y] = windowState.mWinAnimator.mDtDx;
        sTempFloats[Matrix.MSKEW_X] = windowState.mWinAnimator.mDsDy;
        sTempFloats[Matrix.MSCALE_Y] = windowState.mWinAnimator.mDtDy;
        sTempFloats[Matrix.MTRANS_X] = windowState.mShownFrame.left;
        sTempFloats[Matrix.MTRANS_Y] = windowState.mShownFrame.top;
        sTempFloats[Matrix.MPERSP_0] = 0;
        sTempFloats[Matrix.MPERSP_1] = 0;
        sTempFloats[Matrix.MPERSP_2] = 1;
        outMatrix.setValues(sTempFloats);
    }

    /**
     * This class encapsulates the functionality related to display magnification.
     */
    private static final class DisplayMagnifier {

        private static final String LOG_TAG = "DisplayMagnifier";

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
        private final WindowManagerService mWindowManagerService;
        private final MagnifiedViewport mMagnifedViewport;
        private final Handler mHandler;

        private final MagnificationCallbacks mCallbacks;

        private final long mLongAnimationDuration;

        public DisplayMagnifier(WindowManagerService windowManagerService,
                MagnificationCallbacks callbacks) {
            mContext = windowManagerService.mContext;
            mWindowManagerService = windowManagerService;
            mCallbacks = callbacks;
            mHandler = new MyHandler(mWindowManagerService.mH.getLooper());
            mMagnifedViewport = new MagnifiedViewport();
            mLongAnimationDuration = mContext.getResources().getInteger(
                    com.android.internal.R.integer.config_longAnimTime);
        }

        public void setMagnificationSpecLocked(MagnificationSpec spec) {
            mMagnifedViewport.updateMagnificationSpecLocked(spec);
            mMagnifedViewport.recomputeBoundsLocked();
            mWindowManagerService.scheduleAnimationLocked();
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
            mWindowManagerService.scheduleAnimationLocked();
        }

        public void onRotationChangedLocked(DisplayContent displayContent, int rotation) {
            if (DEBUG_ROTATION) {
                Slog.i(LOG_TAG, "Rotaton: " + Surface.rotationToString(rotation)
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
                    case AppTransition.TRANSIT_ACTIVITY_OPEN:
                    case AppTransition.TRANSIT_TASK_OPEN:
                    case AppTransition.TRANSIT_TASK_TO_FRONT:
                    case AppTransition.TRANSIT_WALLPAPER_OPEN:
                    case AppTransition.TRANSIT_WALLPAPER_CLOSE:
                    case AppTransition.TRANSIT_WALLPAPER_INTRA_OPEN: {
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
                        case WindowManager.LayoutParams.TYPE_APPLICATION_PANEL:
                        case WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA:
                        case WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL:
                        case WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG:
                        case WindowManager.LayoutParams.TYPE_SEARCH_BAR:
                        case WindowManager.LayoutParams.TYPE_PHONE:
                        case WindowManager.LayoutParams.TYPE_SYSTEM_ALERT:
                        case WindowManager.LayoutParams.TYPE_TOAST:
                        case WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY:
                        case WindowManager.LayoutParams.TYPE_PRIORITY_PHONE:
                        case WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG:
                        case WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG:
                        case WindowManager.LayoutParams.TYPE_SYSTEM_ERROR:
                        case WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY:
                        case WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL:
                        case WindowManager.LayoutParams.TYPE_RECENTS_OVERLAY: {
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
                WindowManagerPolicy policy = mWindowManagerService.mPolicy;
                final int windowType = windowState.mAttrs.type;
                if (!policy.isTopLevelWindow(windowType) && windowState.mAttachedWindow != null
                        && !policy.canMagnifyWindow(windowType)) {
                    return null;
                }
                if (!policy.canMagnifyWindow(windowState.mAttrs.type)) {
                    return null;
                }
            }
            return spec;
        }

        public void destroyLocked() {
            mMagnifedViewport.destroyWindow();
        }

        /** NOTE: This has to be called within a surface transaction. */
        public void drawMagnifiedRegionBorderIfNeededLocked() {
            mMagnifedViewport.drawWindowIfNeededLocked();
        }

        private final class MagnifiedViewport {

            private static final int DEFAUTLT_BORDER_WIDTH_DIP = 5;

            private final SparseArray<WindowState> mTempWindowStates =
                    new SparseArray<WindowState>();

            private final RectF mTempRectF = new RectF();

            private final Point mTempPoint = new Point();

            private final Matrix mTempMatrix = new Matrix();

            private final Region mMagnifiedBounds = new Region();
            private final Region mOldMagnifiedBounds = new Region();

            private final MagnificationSpec mMagnificationSpec = MagnificationSpec.obtain();

            private final WindowManager mWindowManager;

            private final float mBorderWidth;
            private final int mHalfBorderWidth;
            private final int mDrawBorderInset;

            private final ViewportWindow mWindow;

            private boolean mFullRedrawNeeded;

            public MagnifiedViewport() {
                mWindowManager = (WindowManager) mContext.getSystemService(Service.WINDOW_SERVICE);
                mBorderWidth = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, DEFAUTLT_BORDER_WIDTH_DIP,
                                mContext.getResources().getDisplayMetrics());
                mHalfBorderWidth = (int) Math.ceil(mBorderWidth / 2);
                mDrawBorderInset = (int) mBorderWidth / 2;
                mWindow = new ViewportWindow(mContext);
                recomputeBoundsLocked();
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
                    setMagnifiedRegionBorderShownLocked(isMagnifyingLocked(), true);
                }
            }

            public void recomputeBoundsLocked() {
                mWindowManager.getDefaultDisplay().getRealSize(mTempPoint);
                final int screenWidth = mTempPoint.x;
                final int screenHeight = mTempPoint.y;

                Region magnifiedBounds = mMagnifiedBounds;
                magnifiedBounds.set(0, 0, 0, 0);

                Region availableBounds = mTempRegion1;
                availableBounds.set(0, 0, screenWidth, screenHeight);

                Region nonMagnifiedBounds = mTempRegion4;
                nonMagnifiedBounds.set(0, 0, 0, 0);

                SparseArray<WindowState> visibleWindows = mTempWindowStates;
                visibleWindows.clear();
                populateWindowsOnScreenLocked(visibleWindows);

                final int visibleWindowCount = visibleWindows.size();
                for (int i = visibleWindowCount - 1; i >= 0; i--) {
                    WindowState windowState = visibleWindows.valueAt(i);
                    if (windowState.mAttrs.type == WindowManager
                            .LayoutParams.TYPE_MAGNIFICATION_OVERLAY) {
                        continue;
                    }

                    Region windowBounds = mTempRegion2;
                    Matrix matrix = mTempMatrix;
                    populateTransformationMatrixLocked(windowState, matrix);
                    RectF windowFrame = mTempRectF;

                    if (mWindowManagerService.mPolicy.canMagnifyWindow(windowState.mAttrs.type)) {
                        windowFrame.set(windowState.mFrame);
                        windowFrame.offset(-windowFrame.left, -windowFrame.top);
                        matrix.mapRect(windowFrame);
                        windowBounds.set((int) windowFrame.left, (int) windowFrame.top,
                                (int) windowFrame.right, (int) windowFrame.bottom);
                        magnifiedBounds.op(windowBounds, Region.Op.UNION);
                        magnifiedBounds.op(availableBounds, Region.Op.INTERSECT);
                    } else {
                        Region touchableRegion = mTempRegion3;
                        windowState.getTouchableRegion(touchableRegion);
                        Rect touchableFrame = mTempRect1;
                        touchableRegion.getBounds(touchableFrame);
                        windowFrame.set(touchableFrame);
                        windowFrame.offset(-windowState.mFrame.left, -windowState.mFrame.top);
                        matrix.mapRect(windowFrame);
                        windowBounds.set((int) windowFrame.left, (int) windowFrame.top,
                                (int) windowFrame.right, (int) windowFrame.bottom);
                        nonMagnifiedBounds.op(windowBounds, Region.Op.UNION);
                        windowBounds.op(magnifiedBounds, Region.Op.DIFFERENCE);
                        availableBounds.op(windowBounds, Region.Op.DIFFERENCE);
                    }

                    Region accountedBounds = mTempRegion2;
                    accountedBounds.set(magnifiedBounds);
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

                magnifiedBounds.op(mDrawBorderInset, mDrawBorderInset,
                        screenWidth - mDrawBorderInset, screenHeight - mDrawBorderInset,
                        Region.Op.INTERSECT);

                if (!mOldMagnifiedBounds.equals(magnifiedBounds)) {
                    Region bounds = Region.obtain();
                    bounds.set(magnifiedBounds);
                    mHandler.obtainMessage(MyHandler.MESSAGE_NOTIFY_MAGNIFIED_BOUNDS_CHANGED,
                            bounds).sendToTarget();

                    mWindow.setBounds(magnifiedBounds);
                    Rect dirtyRect = mTempRect1;
                    if (mFullRedrawNeeded) {
                        mFullRedrawNeeded = false;
                        dirtyRect.set(mDrawBorderInset, mDrawBorderInset,
                                screenWidth - mDrawBorderInset, screenHeight - mDrawBorderInset);
                        mWindow.invalidate(dirtyRect);
                    } else {
                        Region dirtyRegion = mTempRegion3;
                        dirtyRegion.set(magnifiedBounds);
                        dirtyRegion.op(mOldMagnifiedBounds, Region.Op.UNION);
                        dirtyRegion.op(nonMagnifiedBounds, Region.Op.INTERSECT);
                        dirtyRegion.getBounds(dirtyRect);
                        mWindow.invalidate(dirtyRect);
                    }

                    mOldMagnifiedBounds.set(magnifiedBounds);
                }
            }

            public void onRotationChangedLocked() {
                // If we are magnifying, hide the magnified border window immediately so
                // the user does not see strange artifacts during rotation. The screenshot
                // used for rotation has already the border. After the rotation is complete
                // we will show the border.
                if (isMagnifyingLocked()) {
                    setMagnifiedRegionBorderShownLocked(false, false);
                    final long delay = (long) (mLongAnimationDuration
                            * mWindowManagerService.getWindowAnimationScaleLocked());
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
                    mOldMagnifiedBounds.set(0, 0, 0, 0);
                }
                mWindow.setShown(shown, animate);
            }

            public void getMagnifiedFrameInContentCoordsLocked(Rect rect) {
                MagnificationSpec spec = mMagnificationSpec;
                mMagnifiedBounds.getBounds(rect);
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
                DisplayContent displayContent = mWindowManagerService
                        .getDefaultDisplayContentLocked();
                WindowList windowList = displayContent.getWindowList();
                final int windowCount = windowList.size();
                for (int i = 0; i < windowCount; i++) {
                    WindowState windowState = windowList.get(i);
                    if ((windowState.isOnScreen() || windowState.mAttrs.type == WindowManager
                            .LayoutParams.TYPE_UNIVERSE_BACKGROUND)
                            && !windowState.mWinAnimator.mEnterAnimationPending) {
                        outWindows.put(windowState.mLayer, windowState);
                    }
                }
            }

            private final class ViewportWindow {
                private static final String SURFACE_TITLE = "Magnification Overlay";

                private final Region mBounds = new Region();
                private final Rect mDirtyRect = new Rect();
                private final Paint mPaint = new Paint();

                private final SurfaceControl mSurfaceControl;
                private final Surface mSurface = new Surface();

                private final AnimationController mAnimationController;

                private boolean mShown;
                private int mAlpha;

                private boolean mInvalidated;

                public ViewportWindow(Context context) {
                    SurfaceControl surfaceControl = null;
                    try {
                        mWindowManager.getDefaultDisplay().getRealSize(mTempPoint);
                        surfaceControl = new SurfaceControl(mWindowManagerService.mFxSession,
                                SURFACE_TITLE, mTempPoint.x, mTempPoint.y, PixelFormat.TRANSLUCENT,
                                SurfaceControl.HIDDEN);
                    } catch (OutOfResourcesException oore) {
                        /* ignore */
                    }
                    mSurfaceControl = surfaceControl;
                    mSurfaceControl.setLayerStack(mWindowManager.getDefaultDisplay()
                            .getLayerStack());
                    mSurfaceControl.setLayer(mWindowManagerService.mPolicy.windowTypeToLayerLw(
                            WindowManager.LayoutParams.TYPE_MAGNIFICATION_OVERLAY)
                            * WindowManagerService.TYPE_LAYER_MULTIPLIER);
                    mSurfaceControl.setPosition(0, 0);
                    mSurface.copyFrom(mSurfaceControl);

                    mAnimationController = new AnimationController(context,
                            mWindowManagerService.mH.getLooper());

                    TypedValue typedValue = new TypedValue();
                    context.getTheme().resolveAttribute(R.attr.colorActivatedHighlight,
                            typedValue, true);
                    final int borderColor = context.getResources().getColor(typedValue.resourceId);

                    mPaint.setStyle(Paint.Style.STROKE);
                    mPaint.setStrokeWidth(mBorderWidth);
                    mPaint.setColor(borderColor);

                    mInvalidated = true;
                }

                public void setShown(boolean shown, boolean animate) {
                    synchronized (mWindowManagerService.mWindowMap) {
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
                    synchronized (mWindowManagerService.mWindowMap) {
                        return mAlpha;
                    }
                }

                public void setAlpha(int alpha) {
                    synchronized (mWindowManagerService.mWindowMap) {
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
                    synchronized (mWindowManagerService.mWindowMap) {
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
                    synchronized (mWindowManagerService.mWindowMap) {
                        mWindowManager.getDefaultDisplay().getRealSize(mTempPoint);
                        mSurfaceControl.setSize(mTempPoint.x, mTempPoint.y);
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
                    mWindowManagerService.scheduleAnimationLocked();
                }

                /** NOTE: This has to be called within a surface transaction. */
                public void drawIfNeeded() {
                    synchronized (mWindowManagerService.mWindowMap) {
                        if (!mInvalidated) {
                            return;
                        }
                        mInvalidated = false;
                        Canvas canvas = null;
                        try {
                            // Empty dirty rectangle means unspecified.
                            if (mDirtyRect.isEmpty()) {
                                mBounds.getBounds(mDirtyRect);
                            }
                            mDirtyRect.inset(- mHalfBorderWidth, - mHalfBorderWidth);
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

                        if (mAlpha > 0) {
                            mSurfaceControl.show();
                        } else {
                            mSurfaceControl.hide();
                        }
                    }
                }

                public void releaseSurface() {
                    mSurfaceControl.release();
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
            public static final int MESSAGE_NOTIFY_MAGNIFIED_BOUNDS_CHANGED = 1;
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
                    case MESSAGE_NOTIFY_MAGNIFIED_BOUNDS_CHANGED: {
                        Region bounds = (Region) message.obj;
                        mCallbacks.onMagnifedBoundsChanged(bounds);
                        bounds.recycle();
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
                        synchronized (mWindowManagerService.mWindowMap) {
                            if (mMagnifedViewport.isMagnifyingLocked()) {
                                mMagnifedViewport.setMagnifiedRegionBorderShownLocked(true, true);
                                mWindowManagerService.scheduleAnimationLocked();
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
        private static final String LOG_TAG = "WindowsForAccessibilityObserver";

        private static final boolean DEBUG = false;

        private final SparseArray<WindowState> mTempWindowStates =
                new SparseArray<WindowState>();

        private final List<WindowInfo> mOldWindows = new ArrayList<WindowInfo>();

        private final Set<IBinder> mTempBinderSet = new ArraySet<IBinder>();

        private final RectF mTempRectF = new RectF();

        private final Matrix mTempMatrix = new Matrix();

        private final Point mTempPoint = new Point();

        private final Rect mTempRect = new Rect();

        private final Region mTempRegion = new Region();

        private final Region mTempRegion1 = new Region();

        private final Context mContext;

        private final WindowManagerService mWindowManagerService;

        private final Handler mHandler;

        private final WindowsForAccessibilityCallback mCallback;

        private final long mRecurringAccessibilityEventsIntervalMillis;

        public WindowsForAccessibilityObserver(WindowManagerService windowManagerService,
                WindowsForAccessibilityCallback callback) {
            mContext = windowManagerService.mContext;
            mWindowManagerService = windowManagerService;
            mCallback = callback;
            mHandler = new MyHandler(mWindowManagerService.mH.getLooper());
            mRecurringAccessibilityEventsIntervalMillis = ViewConfiguration
                    .getSendRecurringAccessibilityEventsInterval();
            computeChangedWindows();
        }

        public void scheduleComputeChangedWindowsLocked() {
            // If focus changed, compute changed windows immediately as the focused window
            // is used by the accessibility manager service to determine the active window.
            if (mWindowManagerService.mCurrentFocus != null
                    && mWindowManagerService.mCurrentFocus != mWindowManagerService.mLastFocus) {
                mHandler.removeMessages(MyHandler.MESSAGE_COMPUTE_CHANGED_WINDOWS);
                computeChangedWindows();
            } else if (!mHandler.hasMessages(MyHandler.MESSAGE_COMPUTE_CHANGED_WINDOWS)) {
                mHandler.sendEmptyMessageDelayed(MyHandler.MESSAGE_COMPUTE_CHANGED_WINDOWS,
                        mRecurringAccessibilityEventsIntervalMillis);
            }
        }

        public void computeChangedWindows() {
            if (DEBUG) {
                Slog.i(LOG_TAG, "computeChangedWindows()");
            }

            synchronized (mWindowManagerService.mWindowMap) {
                // Do not send the windows if there is no current focus as
                // the window manager is still looking for where to put it.
                // We will do the work when we get a focus change callback.
                if (mWindowManagerService.mCurrentFocus == null) {
                    return;
                }

                WindowManager windowManager = (WindowManager)
                        mContext.getSystemService(Context.WINDOW_SERVICE);
                windowManager.getDefaultDisplay().getRealSize(mTempPoint);
                final int screenWidth = mTempPoint.x;
                final int screenHeight = mTempPoint.y;

                Region unaccountedSpace = mTempRegion;
                unaccountedSpace.set(0, 0, screenWidth, screenHeight);

                SparseArray<WindowState> visibleWindows = mTempWindowStates;
                populateVisibleWindowsOnScreenLocked(visibleWindows);

                List<WindowInfo> windows = new ArrayList<WindowInfo>();

                Set<IBinder> addedWindows = mTempBinderSet;
                addedWindows.clear();

                boolean focusedWindowAdded = false;

                final int visibleWindowCount = visibleWindows.size();
                for (int i = visibleWindowCount - 1; i >= 0; i--) {
                    WindowState windowState = visibleWindows.valueAt(i);

                    // Compute the bounds in the screen.
                    Rect boundsInScreen = mTempRect;
                    computeWindowBoundsInScreen(windowState, boundsInScreen);

                    final int flags = windowState.mAttrs.flags;

                    // If the window is not touchable, do not report it but take into account
                    // the space it takes since the content behind it cannot be touched.
                    if ((flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) {
                        continue;
                    }

                    // If the window is completely covered by other windows - ignore.
                    if (unaccountedSpace.quickReject(boundsInScreen)) {
                        continue;
                    }

                    // Add windows of certain types not covered by modal windows.
                    if (isReportedWindowType(windowState.mAttrs.type)) {
                        // Add the window to the ones to be reported.
                        WindowInfo window = obtainPopulatedWindowInfo(windowState, boundsInScreen);
                        addedWindows.add(window.token);
                        windows.add(window);
                        if (windowState.isFocused()) {
                            focusedWindowAdded = true;
                        }
                    }

                    // Account for the space this window takes.
                    unaccountedSpace.op(boundsInScreen, unaccountedSpace,
                            Region.Op.REVERSE_DIFFERENCE);

                    // We figured out what is touchable for the entire screen - done.
                    if (unaccountedSpace.isEmpty()) {
                        break;
                    }

                    // If a window is modal, no other below can be touched - done.
                    if ((flags & (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)) == 0) {
                        break;
                    }
                }

                // Always report the focused window.
                if (!focusedWindowAdded) {
                    for (int i = visibleWindowCount - 1; i >= 0; i--) {
                        WindowState windowState = visibleWindows.valueAt(i);
                        if (windowState.isFocused()) {
                            // Compute the bounds in the screen.
                            Rect boundsInScreen = mTempRect;
                            computeWindowBoundsInScreen(windowState, boundsInScreen);

                            // Add the window to the ones to be reported.
                            WindowInfo window = obtainPopulatedWindowInfo(windowState,
                                    boundsInScreen);
                            addedWindows.add(window.token);
                            windows.add(window);
                            break;
                        }
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

                // We computed the windows and if they changed notify the client.
                boolean windowsChanged = false;
                if (mOldWindows.size() != windows.size()) {
                    // Different size means something changed.
                    windowsChanged = true;
                } else if (!mOldWindows.isEmpty() || !windows.isEmpty()) {
                    // Since we always traverse windows from high to low layer
                    // the old and new windows at the same index should be the
                    // same, otherwise something changed.
                    for (int i = 0; i < windowCount; i++) {
                        WindowInfo oldWindow = mOldWindows.get(i);
                        WindowInfo newWindow = windows.get(i);
                        // We do not care for layer changes given the window
                        // order does not change. This brings no new information
                        // to the clients.
                        if (windowChangedNoLayer(oldWindow, newWindow)) {
                            windowsChanged = true;
                            break;
                        }
                    }
                }

                if (windowsChanged) {
                    if (DEBUG) {
                        Log.i(LOG_TAG, "Windows changed:" + windows);
                    }
                    // Remember the old windows to detect changes.
                    cacheWindows(windows);
                    // Announce the change.
                    mHandler.obtainMessage(MyHandler.MESSAGE_NOTIFY_WINDOWS_CHANGED,
                            windows).sendToTarget();
                } else {
                    if (DEBUG) {
                        Log.i(LOG_TAG, "No windows changed.");
                    }
                    // Recycle the nodes as we do not need them.
                    clearAndRecycleWindows(windows);
                }
            }
        }

        private void computeWindowBoundsInScreen(WindowState windowState, Rect outBounds) {
            // Get the touchable frame.
            Region touchableRegion = mTempRegion1;
            windowState.getTouchableRegion(touchableRegion);
            Rect touchableFrame = mTempRect;
            touchableRegion.getBounds(touchableFrame);

            // Move to origin as all transforms are captured by the matrix.
            RectF windowFrame = mTempRectF;
            windowFrame.set(touchableFrame);
            windowFrame.offset(-windowState.mFrame.left, -windowState.mFrame.top);

            // Map the frame to get what appears on the screen.
            Matrix matrix = mTempMatrix;
            populateTransformationMatrixLocked(windowState, matrix);
            matrix.mapRect(windowFrame);

            // Got the bounds.
            outBounds.set((int) windowFrame.left, (int) windowFrame.top,
                    (int) windowFrame.right, (int) windowFrame.bottom);
        }

        private static WindowInfo obtainPopulatedWindowInfo(WindowState windowState,
                Rect boundsInScreen) {
            WindowInfo window = WindowInfo.obtain();
            window.type = windowState.mAttrs.type;
            window.layer = windowState.mLayer;
            window.token = windowState.mClient.asBinder();

            WindowState attachedWindow = windowState.mAttachedWindow;
            if (attachedWindow != null) {
                window.parentToken = attachedWindow.mClient.asBinder();
            }

            window.focused = windowState.isFocused();
            window.boundsInScreen.set(boundsInScreen);

            final int childCount = windowState.mChildWindows.size();
            if (childCount > 0) {
                if (window.childTokens == null) {
                    window.childTokens = new ArrayList<IBinder>();
                }
                for (int j = 0; j < childCount; j++) {
                    WindowState child = windowState.mChildWindows.get(j);
                    window.childTokens.add(child.mClient.asBinder());
                }
            }

            return window;
        }

        private void cacheWindows(List<WindowInfo> windows) {
            final int oldWindowCount = mOldWindows.size();
            for (int i = oldWindowCount - 1; i >= 0; i--) {
                mOldWindows.remove(i).recycle();
            }
            final int newWindowCount = windows.size();
            for (int i = 0; i < newWindowCount; i++) {
                WindowInfo newWindow = windows.get(i);
                mOldWindows.add(WindowInfo.obtain(newWindow));
            }
        }

        private boolean windowChangedNoLayer(WindowInfo oldWindow, WindowInfo newWindow) {
            if (oldWindow == newWindow) {
                return false;
            }
            if (oldWindow == null) {
                return true;
            }
            if (newWindow == null) {
                return true;
            }
            if (oldWindow.type != newWindow.type) {
                return true;
            }
            if (oldWindow.focused != newWindow.focused) {
                return true;
            }
            if (oldWindow.token == null) {
                if (newWindow.token != null) {
                    return true;
                }
            } else if (!oldWindow.token.equals(newWindow.token)) {
                return true;
            }
            if (oldWindow.parentToken == null) {
                if (newWindow.parentToken != null) {
                    return true;
                }
            } else if (!oldWindow.parentToken.equals(newWindow.parentToken)) {
                return true;
            }
            if (!oldWindow.boundsInScreen.equals(newWindow.boundsInScreen)) {
                return true;
            }
            if (oldWindow.childTokens != null && newWindow.childTokens != null
                    && !oldWindow.childTokens.equals(newWindow.childTokens)) {
                return true;
            }
            return false;
        }

        private void clearAndRecycleWindows(List<WindowInfo> windows) {
            final int windowCount = windows.size();
            for (int i = windowCount - 1; i >= 0; i--) {
                windows.remove(i).recycle();
            }
        }

        private static boolean isReportedWindowType(int windowType) {
            return (windowType != WindowManager.LayoutParams.TYPE_KEYGUARD_SCRIM
                    && windowType != WindowManager.LayoutParams.TYPE_WALLPAPER
                    && windowType != WindowManager.LayoutParams.TYPE_BOOT_PROGRESS
                    && windowType != WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY
                    && windowType != WindowManager.LayoutParams.TYPE_DRAG
                    && windowType != WindowManager.LayoutParams.TYPE_HIDDEN_NAV_CONSUMER
                    && windowType != WindowManager.LayoutParams.TYPE_POINTER
                    && windowType != WindowManager.LayoutParams.TYPE_UNIVERSE_BACKGROUND
                    && windowType != WindowManager.LayoutParams.TYPE_MAGNIFICATION_OVERLAY
                    && windowType != WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY
                    && windowType != WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY
                    && windowType != WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION);
        }

        private void populateVisibleWindowsOnScreenLocked(SparseArray<WindowState> outWindows) {
            DisplayContent displayContent = mWindowManagerService
                    .getDefaultDisplayContentLocked();
            WindowList windowList = displayContent.getWindowList();
            final int windowCount = windowList.size();
            for (int i = 0; i < windowCount; i++) {
                WindowState windowState = windowList.get(i);
                if (windowState.isVisibleLw()) {
                    outWindows.put(windowState.mLayer, windowState);
                }
            }
        }

        private class MyHandler extends Handler {
            public static final int MESSAGE_COMPUTE_CHANGED_WINDOWS = 1;
            public static final int MESSAGE_NOTIFY_WINDOWS_CHANGED = 2;

            public MyHandler(Looper looper) {
                super(looper, null, false);
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MESSAGE_COMPUTE_CHANGED_WINDOWS: {
                        computeChangedWindows();
                    } break;

                    case MESSAGE_NOTIFY_WINDOWS_CHANGED: {
                        List<WindowInfo> windows = (List<WindowInfo>) message.obj;
                        mCallback.onWindowsForAccessibilityChanged(windows);
                        clearAndRecycleWindows(windows);
                    } break;
                }
            }
        }
    }
}
