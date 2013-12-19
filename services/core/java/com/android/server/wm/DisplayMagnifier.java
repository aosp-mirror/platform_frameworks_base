/*
 * Copyright (C) 2012 The Android Open Source Project
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
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Pools.SimplePool;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.IMagnificationCallbacks;
import android.view.MagnificationSpec;
import android.view.Surface;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import com.android.internal.R;
import com.android.internal.os.SomeArgs;

/**
 * This class is a part of the window manager and encapsulates the
 * functionality related to display magnification.
 */
final class DisplayMagnifier {
    private static final String LOG_TAG = DisplayMagnifier.class.getSimpleName();

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

    private final IMagnificationCallbacks mCallbacks;

    private final long mLongAnimationDuration;

    public DisplayMagnifier(WindowManagerService windowManagerService,
            IMagnificationCallbacks callbacks) {
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

    public void onRectangleOnScreenRequestedLocked(Rect rectangle, boolean immediate) {
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
                            try {
                                mCallbacks.onRectangleOnScreenRequested(
                                        touchableRegionBounds.left,
                                        touchableRegionBounds.top,
                                        touchableRegionBounds.right,
                                        touchableRegionBounds.bottom);
                            } catch (RemoteException re) {
                                /* ignore */
                            }
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

        private final SparseArray<WindowStateInfo> mTempWindowStateInfos =
                new SparseArray<WindowStateInfo>();

        private final float[] mTempFloats = new float[9];

        private final RectF mTempRectF = new RectF();

        private final Point mTempPoint = new Point();

        private final Matrix mTempMatrix = new Matrix();

        private final Region mMagnifiedBounds = new Region();
        private final Region mOldMagnifiedBounds = new Region();

        private final MagnificationSpec mMagnificationSpec = MagnificationSpec.obtain();

        private final WindowManager mWindowManager;

        private final int mBorderWidth;
        private final int mHalfBorderWidth;

        private final ViewportWindow mWindow;

        private boolean mFullRedrawNeeded;

        public MagnifiedViewport() {
            mWindowManager = (WindowManager) mContext.getSystemService(Service.WINDOW_SERVICE);
            mBorderWidth = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, DEFAUTLT_BORDER_WIDTH_DIP,
                            mContext.getResources().getDisplayMetrics());
            mHalfBorderWidth = (int) (mBorderWidth + 0.5) / 2;
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
            if (!mHandler.hasMessages(MyHandler.MESSAGE_SHOW_MAGNIFIED_REGION_BOUNDS_IF_NEEDED)) {
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
            nonMagnifiedBounds.set(0,  0,  0,  0);

            SparseArray<WindowStateInfo> visibleWindows = mTempWindowStateInfos;
            visibleWindows.clear();
            getWindowsOnScreenLocked(visibleWindows);

            final int visibleWindowCount = visibleWindows.size();
            for (int i = visibleWindowCount - 1; i >= 0; i--) {
                WindowStateInfo info = visibleWindows.valueAt(i);
                if (info.mWindowState.mAttrs.type == WindowManager
                        .LayoutParams.TYPE_MAGNIFICATION_OVERLAY) {
                    continue;
                }

                Region windowBounds = mTempRegion2;
                Matrix matrix = mTempMatrix;
                populateTransformationMatrix(info.mWindowState, matrix);
                RectF windowFrame = mTempRectF;

                if (mWindowManagerService.mPolicy.canMagnifyWindow(info.mWindowState.mAttrs.type)) {
                    windowFrame.set(info.mWindowState.mFrame);
                    windowFrame.offset(-windowFrame.left, -windowFrame.top);
                    matrix.mapRect(windowFrame);
                    windowBounds.set((int) windowFrame.left, (int) windowFrame.top,
                            (int) windowFrame.right, (int) windowFrame.bottom);
                    magnifiedBounds.op(windowBounds, Region.Op.UNION);
                    magnifiedBounds.op(availableBounds, Region.Op.INTERSECT);
                } else {
                    windowFrame.set(info.mTouchableRegion);
                    windowFrame.offset(-info.mWindowState.mFrame.left,
                            -info.mWindowState.mFrame.top);
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

            for (int i = visibleWindowCount - 1; i >= 0; i--) {
                WindowStateInfo info = visibleWindows.valueAt(i);
                info.recycle();
                visibleWindows.removeAt(i);
            }

            magnifiedBounds.op(mHalfBorderWidth, mHalfBorderWidth,
                    screenWidth - mHalfBorderWidth, screenHeight - mHalfBorderWidth,
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
                    dirtyRect.set(mHalfBorderWidth, mHalfBorderWidth,
                            screenWidth - mHalfBorderWidth, screenHeight - mHalfBorderWidth);
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

        private void populateTransformationMatrix(WindowState windowState, Matrix outMatrix) {
            mTempFloats[Matrix.MSCALE_X] = windowState.mWinAnimator.mDsDx;
            mTempFloats[Matrix.MSKEW_Y] = windowState.mWinAnimator.mDtDx;
            mTempFloats[Matrix.MSKEW_X] = windowState.mWinAnimator.mDsDy;
            mTempFloats[Matrix.MSCALE_Y] = windowState.mWinAnimator.mDtDy;
            mTempFloats[Matrix.MTRANS_X] = windowState.mShownFrame.left;
            mTempFloats[Matrix.MTRANS_Y] = windowState.mShownFrame.top;
            mTempFloats[Matrix.MPERSP_0] = 0;
            mTempFloats[Matrix.MPERSP_1] = 0;
            mTempFloats[Matrix.MPERSP_2] = 1;
            outMatrix.setValues(mTempFloats);
        }

        private void getWindowsOnScreenLocked(SparseArray<WindowStateInfo> outWindowStates) {
            DisplayContent displayContent = mWindowManagerService.getDefaultDisplayContentLocked();
            WindowList windowList = displayContent.getWindowList();
            final int windowCount = windowList.size();
            for (int i = 0; i < windowCount; i++) {
                WindowState windowState = windowList.get(i);
                if ((windowState.isOnScreen() || windowState.mAttrs.type == WindowManager
                        .LayoutParams.TYPE_UNIVERSE_BACKGROUND)
                        && !windowState.mWinAnimator.mEnterAnimationPending) {
                    outWindowStates.put(windowState.mLayer, WindowStateInfo.obtain(windowState));
                }
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
                        * mWindowManagerService.mWindowAnimationScale);
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
                mOldMagnifiedBounds.set(0,  0,  0,  0);
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

        private final class ViewportWindow {
            private static final String SURFACE_TITLE = "Magnification Overlay";

            private static final String PROPERTY_NAME_ALPHA = "alpha";

            private static final int MIN_ALPHA = 0;
            private static final int MAX_ALPHA = 255;

            private final Region mBounds = new Region();
            private final Rect mDirtyRect = new Rect();
            private final Paint mPaint = new Paint();

            private final ValueAnimator mShowHideFrameAnimator;
            private final SurfaceControl mSurfaceControl;
            private final Surface mSurface = new Surface();

            private boolean mShown;
            private int mAlpha;

            private boolean mInvalidated;

            public ViewportWindow(Context context) {
                SurfaceControl surfaceControl = null;
                try {
                    mWindowManager.getDefaultDisplay().getRealSize(mTempPoint);
                    surfaceControl = new SurfaceControl(mWindowManagerService.mFxSession, SURFACE_TITLE,
                            mTempPoint.x, mTempPoint.y, PixelFormat.TRANSLUCENT, SurfaceControl.HIDDEN);
                } catch (OutOfResourcesException oore) {
                    /* ignore */
                }
                mSurfaceControl = surfaceControl;
                mSurfaceControl.setLayerStack(mWindowManager.getDefaultDisplay().getLayerStack());
                mSurfaceControl.setLayer(mWindowManagerService.mPolicy.windowTypeToLayerLw(
                        WindowManager.LayoutParams.TYPE_MAGNIFICATION_OVERLAY)
                        * WindowManagerService.TYPE_LAYER_MULTIPLIER);
                mSurfaceControl.setPosition(0, 0);
                mSurface.copyFrom(mSurfaceControl);

                TypedValue typedValue = new TypedValue();
                context.getTheme().resolveAttribute(R.attr.colorActivatedHighlight,
                        typedValue, true);
                final int borderColor = context.getResources().getColor(typedValue.resourceId);

                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(mBorderWidth);
                mPaint.setColor(borderColor);

                Interpolator interpolator = new DecelerateInterpolator(2.5f);
                final long longAnimationDuration = context.getResources().getInteger(
                        com.android.internal.R.integer.config_longAnimTime);

                mShowHideFrameAnimator = ObjectAnimator.ofInt(this, PROPERTY_NAME_ALPHA,
                        MIN_ALPHA, MAX_ALPHA);
                mShowHideFrameAnimator.setInterpolator(interpolator);
                mShowHideFrameAnimator.setDuration(longAnimationDuration);
                mInvalidated = true;
            }

            public void setShown(boolean shown, boolean animate) {
                synchronized (mWindowManagerService.mWindowMap) {
                    if (mShown == shown) {
                        return;
                    }
                    mShown = shown;
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
        }
    }

    private static final class WindowStateInfo {
        private static final int MAX_POOL_SIZE = 30;

        private static final SimplePool<WindowStateInfo> sPool =
                new SimplePool<WindowStateInfo>(MAX_POOL_SIZE);

        private static final Region mTempRegion = new Region();

        public WindowState mWindowState;
        public final Rect mTouchableRegion = new Rect();

        public static WindowStateInfo obtain(WindowState windowState) {
            WindowStateInfo info = sPool.acquire();
            if (info == null) {
                info = new WindowStateInfo();
            }
            info.mWindowState = windowState;
            windowState.getTouchableRegion(mTempRegion);
            mTempRegion.getBounds(info.mTouchableRegion);
            return info;
        }

        public void recycle() {
            mWindowState = null;
            mTouchableRegion.setEmpty();
            sPool.release(this);
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
                    try {
                        mCallbacks.onMagnifedBoundsChanged(bounds);
                    } catch (RemoteException re) {
                        /* ignore */
                    } finally {
                        bounds.recycle();
                    }
                } break;
                case MESSAGE_NOTIFY_RECTANGLE_ON_SCREEN_REQUESTED: {
                    SomeArgs args = (SomeArgs) message.obj;
                    final int left = args.argi1;
                    final int top = args.argi2;
                    final int right = args.argi3;
                    final int bottom = args.argi4;
                    try {
                        mCallbacks.onRectangleOnScreenRequested(left, top, right, bottom);
                    } catch (RemoteException re) {
                        /* ignore */
                    } finally {
                        args.recycle();
                    }
                } break;
                case MESSAGE_NOTIFY_USER_CONTEXT_CHANGED: {
                    try {
                        mCallbacks.onUserContextChanged();
                    } catch (RemoteException re) {
                        /* ignore */
                    }
                } break;
                case MESSAGE_NOTIFY_ROTATION_CHANGED: {
                    final int rotation = message.arg1;
                    try {
                        mCallbacks.onRotationChanged(rotation);
                    } catch (RemoteException re) {
                        /* ignore */
                    }
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
