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

package com.android.systemui.accessibility;

import static android.view.WindowInsets.Type.systemGestures;
import static android.view.WindowManager.LayoutParams;

import static com.android.systemui.accessibility.WindowMagnificationSettings.MagnificationSize;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_MAGNIFICATION_OVERLAP;

import static java.lang.Math.abs;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiContext;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Display;
import android.view.Gravity;
import android.view.IWindow;
import android.view.IWindowSession;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.UiThread;
import androidx.core.math.MathUtils;

import com.android.app.viewcapture.ViewCaptureAwareWindowManager;
import com.android.internal.accessibility.common.MagnificationConstants;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.Flags;
import com.android.systemui.model.SysUiState;
import com.android.systemui.res.R;
import com.android.systemui.util.settings.SecureSettings;

import java.io.PrintWriter;
import java.text.NumberFormat;
import java.util.Collections;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Class to handle adding and removing a window magnification.
 */
class WindowMagnificationController implements View.OnTouchListener, SurfaceHolder.Callback,
        MirrorWindowControl.MirrorWindowDelegate, MagnificationGestureDetector.OnGestureListener,
        ComponentCallbacks {

    private static final String TAG = "WindowMagnificationController";
    @SuppressWarnings("isloggabletaglength")
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG) || Build.IS_DEBUGGABLE;
    // Delay to avoid updating state description too frequently.
    private static final int UPDATE_STATE_DESCRIPTION_DELAY_MS = 100;
    // It should be consistent with the value defined in WindowMagnificationGestureHandler.
    private static final Range<Float> A11Y_ACTION_SCALE_RANGE = new Range<>(
            MagnificationConstants.SCALE_MIN_VALUE,
            MagnificationConstants.SCALE_MAX_VALUE);
    private static final float A11Y_CHANGE_SCALE_DIFFERENCE = 1.0f;
    private static final float ANIMATION_BOUNCE_EFFECT_SCALE = 1.05f;
    private static final float[] COLOR_BLACK_ARRAY = {0f, 0f, 0f};
    private final SparseArray<Float> mMagnificationSizeScaleOptions = new SparseArray<>();

    private final Context mContext;
    private final Resources mResources;
    private final Handler mHandler;
    private final Rect mWindowBounds;
    private final int mDisplayId;
    @Surface.Rotation
    @VisibleForTesting
    int mRotation;
    private final SurfaceControl.Transaction mTransaction;

    private final WindowManager mWm;
    private final ViewCaptureAwareWindowManager mViewCaptureAwareWindowManager;

    private float mScale;
    private int mSettingsButtonIndex = MagnificationSize.DEFAULT;

    /**
     * MagnificationFrame represents the bound of {@link #mMirrorSurfaceView} and is constrained
     * by the {@link #mMagnificationFrameBoundary}.
     * We use MagnificationFrame to calculate the position of {@link #mMirrorView}.
     * We combine MagnificationFrame with {@link #mMagnificationFrameOffsetX} and
     * {@link #mMagnificationFrameOffsetY} to calculate the position of {@link #mSourceBounds}.
     */
    private final Rect mMagnificationFrame = new Rect();
    private final Rect mTmpRect = new Rect();

    /**
     * MirrorViewBounds is the bound of the {@link #mMirrorView} which displays the magnified
     * content.
     * {@link #mMirrorView}'s center is equal to {@link #mMagnificationFrame}'s center.
     */
    private final Rect mMirrorViewBounds = new Rect();

    /**
     * SourceBound is the bound of the magnified region which projects the magnified content.
     * SourceBound's center is equal to the parameters centerX and centerY in
     * {@link WindowMagnificationController#updateWindowMagnificationInternal(float, float, float)}}
     * but it is calculated from {@link #mMagnificationFrame}'s center in the runtime.
     */
    private final Rect mSourceBounds = new Rect();

    /**
     * The relation of centers between {@link #mSourceBounds} and {@link #mMagnificationFrame} is
     * calculated in {@link #calculateSourceBounds(Rect, float)} and the equations are as following:
     *      MagnificationFrame = SourceBound (e.g., centerX & centerY) + MagnificationFrameOffset
     *      SourceBound = MagnificationFrame - MagnificationFrameOffset
     */
    private int mMagnificationFrameOffsetX = 0;
    private int mMagnificationFrameOffsetY = 0;

    @Nullable private Supplier<SurfaceControlViewHost> mScvhSupplier;

    /**
     * SurfaceControlViewHost is used to control the position of the window containing
     * {@link #mMirrorView}. Using SurfaceControlViewHost instead of a regular window enables
     * changing the window's position and setting {@link #mMirrorSurface}'s geometry atomically.
     */
    private SurfaceControlViewHost mSurfaceControlViewHost;

    // The root of the mirrored content
    private SurfaceControl mMirrorSurface;

    private ImageView mDragView;
    private ImageView mCloseView;
    private View mLeftDrag;
    private View mTopDrag;
    private View mRightDrag;
    private View mBottomDrag;
    private ImageView mTopLeftCornerView;
    private ImageView mTopRightCornerView;
    private ImageView mBottomLeftCornerView;
    private ImageView mBottomRightCornerView;
    private final Configuration mConfiguration;

    @NonNull
    private final WindowMagnifierCallback mWindowMagnifierCallback;

    private final View.OnLayoutChangeListener mMirrorViewLayoutChangeListener;
    private final View.OnLayoutChangeListener mMirrorSurfaceViewLayoutChangeListener;
    private final Runnable mMirrorViewRunnable;
    private final Runnable mUpdateStateDescriptionRunnable;
    private final Runnable mWindowInsetChangeRunnable;
    // MirrorView is the mirror window which displays the magnified content.
    private View mMirrorView;
    private View mMirrorBorderView;
    private SurfaceView mMirrorSurfaceView;
    private int mMirrorSurfaceMargin;
    private int mBorderDragSize;
    private int mOuterBorderSize;

    /**
     * How far from the right edge of the screen you need to drag the window before the button
     * repositions to the other side.
     */
    private int mButtonRepositionThresholdFromEdge;

    // The boundary of magnification frame.
    private final Rect mMagnificationFrameBoundary = new Rect();
    // The top Y of the system gesture rect at the bottom. Set to -1 if it is invalid.
    private int mSystemGestureTop = -1;
    private int mMinWindowSize;

    private final WindowMagnificationAnimationController mAnimationController;
    private final Supplier<IWindowSession> mGlobalWindowSessionSupplier;
    private final SfVsyncFrameCallbackProvider mSfVsyncFrameProvider;
    private final MagnificationGestureDetector mGestureDetector;
    private int mBounceEffectDuration;
    private final Choreographer.FrameCallback mMirrorViewGeometryVsyncCallback;
    private Locale mLocale;
    private NumberFormat mPercentFormat;
    private float mBounceEffectAnimationScale;
    private final SysUiState mSysUiState;
    // Set it to true when the view is overlapped with the gesture insets at the bottom.
    private boolean mOverlapWithGestureInsets;
    private boolean mIsDragging;

    private static final int MAX_HORIZONTAL_MOVE_ANGLE = 50;
    private static final int HORIZONTAL = 1;
    private static final int VERTICAL = 0;

    @VisibleForTesting
    static final double HORIZONTAL_LOCK_BASE =
            Math.tan(Math.toRadians(MAX_HORIZONTAL_MOVE_ANGLE));

    private boolean mAllowDiagonalScrolling = false;
    private boolean mEditSizeEnable = false;
    private boolean mSettingsPanelVisibility = false;
    @VisibleForTesting
    WindowMagnificationFrameSizePrefs mWindowMagnificationFrameSizePrefs;

    @Nullable
    private final MirrorWindowControl mMirrorWindowControl;

    WindowMagnificationController(
            @UiContext Context context,
            @NonNull Handler handler,
            @NonNull WindowMagnificationAnimationController animationController,
            MirrorWindowControl mirrorWindowControl,
            SurfaceControl.Transaction transaction,
            @NonNull WindowMagnifierCallback callback,
            SysUiState sysUiState,
            SecureSettings secureSettings,
            Supplier<SurfaceControlViewHost> scvhSupplier,
            SfVsyncFrameCallbackProvider sfVsyncFrameProvider,
            Supplier<IWindowSession> globalWindowSessionSupplier,
            ViewCaptureAwareWindowManager viewCaptureAwareWindowManager) {
        mContext = context;
        mHandler = handler;
        mAnimationController = animationController;
        mAnimationController.setOnAnimationEndRunnable(() -> {
            if (Flags.createWindowlessWindowMagnifier()) {
                notifySourceBoundsChanged();
            }
        });
        mAnimationController.setWindowMagnificationController(this);
        mWindowMagnifierCallback = callback;
        mSysUiState = sysUiState;
        mScvhSupplier = scvhSupplier;
        mConfiguration = new Configuration(context.getResources().getConfiguration());
        mWindowMagnificationFrameSizePrefs = new WindowMagnificationFrameSizePrefs(mContext);

        final Display display = mContext.getDisplay();
        mDisplayId = mContext.getDisplayId();
        mRotation = display.getRotation();

        mWm = context.getSystemService(WindowManager.class);
        mWindowBounds = new Rect(mWm.getCurrentWindowMetrics().getBounds());
        mViewCaptureAwareWindowManager = viewCaptureAwareWindowManager;

        mResources = mContext.getResources();
        mScale = secureSettings.getFloatForUser(
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE,
                mResources.getInteger(R.integer.magnification_default_scale),
                UserHandle.USER_CURRENT);
        mAllowDiagonalScrolling = secureSettings.getIntForUser(
                Settings.Secure.ACCESSIBILITY_ALLOW_DIAGONAL_SCROLLING, 1,
                UserHandle.USER_CURRENT) == 1;

        setupMagnificationSizeScaleOptions();

        setBounceEffectDuration(mResources.getInteger(
                com.android.internal.R.integer.config_shortAnimTime));
        updateDimensions();

        final Size windowFrameSize = restoreMagnificationWindowFrameSizeIfPossible();
        setMagnificationFrame(windowFrameSize.getWidth(), windowFrameSize.getHeight(),
                mWindowBounds.width() / 2, mWindowBounds.height() / 2);
        computeBounceAnimationScale();

        mMirrorWindowControl = mirrorWindowControl;
        if (mMirrorWindowControl != null) {
            mMirrorWindowControl.setWindowDelegate(this);
        }
        mTransaction = transaction;
        mGestureDetector =
                new MagnificationGestureDetector(mContext, handler, this);
        mWindowInsetChangeRunnable = this::onWindowInsetChanged;
        mGlobalWindowSessionSupplier = globalWindowSessionSupplier;
        mSfVsyncFrameProvider = sfVsyncFrameProvider;

        // Initialize listeners.
        if (Flags.createWindowlessWindowMagnifier()) {
            mMirrorViewRunnable = new Runnable() {
                final Rect mPreviousBounds = new Rect();

                @Override
                public void run() {
                    if (mMirrorView != null) {
                        if (mPreviousBounds.width() != mMirrorViewBounds.width()
                                || mPreviousBounds.height() != mMirrorViewBounds.height()) {
                            mMirrorView.setSystemGestureExclusionRects(Collections.singletonList(
                                    new Rect(0, 0, mMirrorViewBounds.width(),
                                            mMirrorViewBounds.height())));
                            mPreviousBounds.set(mMirrorViewBounds);
                        }
                        updateSystemUIStateIfNeeded();
                        mWindowMagnifierCallback.onWindowMagnifierBoundsChanged(
                                mDisplayId, mMirrorViewBounds);
                    }
                }
            };

            mMirrorSurfaceViewLayoutChangeListener =
                    (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                            mMirrorView.post(this::applyTapExcludeRegion);

            mMirrorViewGeometryVsyncCallback = null;
        } else {
            mMirrorViewRunnable = () -> {
                if (mMirrorView != null) {
                    final Rect oldViewBounds = new Rect(mMirrorViewBounds);
                    mMirrorView.getBoundsOnScreen(mMirrorViewBounds);
                    if (oldViewBounds.width() != mMirrorViewBounds.width()
                            || oldViewBounds.height() != mMirrorViewBounds.height()) {
                        mMirrorView.setSystemGestureExclusionRects(Collections.singletonList(
                                new Rect(0, 0,
                                        mMirrorViewBounds.width(), mMirrorViewBounds.height())));
                    }
                    updateSystemUIStateIfNeeded();
                    mWindowMagnifierCallback.onWindowMagnifierBoundsChanged(
                            mDisplayId, mMirrorViewBounds);
                }
            };

            mMirrorSurfaceViewLayoutChangeListener =
                    (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                            mMirrorView.post(this::applyTapExcludeRegion);

            mMirrorViewGeometryVsyncCallback =
                    l -> {
                        if (isActivated() && mMirrorSurface != null && calculateSourceBounds(
                                mMagnificationFrame, mScale)) {
                            // The final destination for the magnification surface should be at 0,0
                            // since the ViewRootImpl's position will change
                            mTmpRect.set(0, 0, mMagnificationFrame.width(),
                                    mMagnificationFrame.height());
                            mTransaction.setGeometry(mMirrorSurface, mSourceBounds, mTmpRect,
                                    Surface.ROTATION_0).apply();

                            // Notify source bounds change when the magnifier is not animating.
                            if (!mAnimationController.isAnimating()) {
                                mWindowMagnifierCallback.onSourceBoundsChanged(mDisplayId,
                                        mSourceBounds);
                            }
                        }
                    };
        }

        mMirrorViewLayoutChangeListener =
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (!mHandler.hasCallbacks(mMirrorViewRunnable)) {
                        mHandler.post(mMirrorViewRunnable);
                    }
                };

        mUpdateStateDescriptionRunnable = () -> {
            if (isActivated()) {
                mMirrorView.setStateDescription(formatStateDescription(mScale));
            }
        };
    }

    private void setupMagnificationSizeScaleOptions() {
        mMagnificationSizeScaleOptions.clear();
        mMagnificationSizeScaleOptions.put(MagnificationSize.SMALL, 1.4f);
        mMagnificationSizeScaleOptions.put(MagnificationSize.MEDIUM, 1.8f);
        mMagnificationSizeScaleOptions.put(MagnificationSize.LARGE, 2.5f);
    }

    private void updateDimensions() {
        mMirrorSurfaceMargin = mResources.getDimensionPixelSize(
                R.dimen.magnification_mirror_surface_margin);
        mBorderDragSize = mResources.getDimensionPixelSize(
                R.dimen.magnification_border_drag_size);
        mOuterBorderSize = mResources.getDimensionPixelSize(
                R.dimen.magnification_outer_border_margin);
        mButtonRepositionThresholdFromEdge =
                mResources.getDimensionPixelSize(
                        R.dimen.magnification_button_reposition_threshold_from_edge);
        mMinWindowSize = mResources.getDimensionPixelSize(
                com.android.internal.R.dimen.accessibility_window_magnifier_min_size);
    }

    private void computeBounceAnimationScale() {
        final float windowWidth = mMagnificationFrame.width() + 2 * mMirrorSurfaceMargin;
        final float visibleWindowWidth = windowWidth - 2 * mOuterBorderSize;
        final float animationScaleMax = windowWidth / visibleWindowWidth;
        mBounceEffectAnimationScale = Math.min(animationScaleMax, ANIMATION_BOUNCE_EFFECT_SCALE);
    }

    private boolean updateSystemGestureInsetsTop() {
        final WindowMetrics windowMetrics = mWm.getCurrentWindowMetrics();
        final Insets insets = windowMetrics.getWindowInsets().getInsets(systemGestures());
        final int gestureTop =
                insets.bottom != 0 ? windowMetrics.getBounds().bottom - insets.bottom : -1;
        if (gestureTop != mSystemGestureTop) {
            mSystemGestureTop = gestureTop;
            return true;
        }
        return false;
    }

    void changeMagnificationSize(@MagnificationSize int index) {
        if (!mMagnificationSizeScaleOptions.contains(index)) {
            return;
        }
        mSettingsButtonIndex = index;
        int size = getMagnificationWindowSizeFromIndex(index);
        setWindowSize(size, size);
    }

    int getMagnificationWindowSizeFromIndex(@MagnificationSize int index) {
        final float scale = mMagnificationSizeScaleOptions.get(index, 1.0f);
        int initSize = Math.min(mWindowBounds.width(), mWindowBounds.height()) / 3;
        return (int) (initSize * scale) - (int) (initSize * scale) % 2;
    }

    int getMagnificationFrameSizeFromIndex(@MagnificationSize int index) {
        return getMagnificationWindowSizeFromIndex(index) - 2 * mMirrorSurfaceMargin;
    }

    void setEditMagnifierSizeMode(boolean enable) {
        mEditSizeEnable = enable;
        applyResourcesValues();

        if (isActivated()) {
            updateDimensions();
            applyTapExcludeRegion();
        }

        if (!enable) {
            // Keep the magnifier size when exiting edit mode
            mWindowMagnificationFrameSizePrefs.saveIndexAndSizeForCurrentDensity(
                    mSettingsButtonIndex,
                    new Size(mMagnificationFrame.width(), mMagnificationFrame.height()));
        } else {
            mSettingsButtonIndex = MagnificationSize.CUSTOM;
        }
    }

    void setDiagonalScrolling(boolean enable) {
        mAllowDiagonalScrolling = enable;
    }

    /**
     * Wraps {@link WindowMagnificationController#deleteWindowMagnification()}} with transition
     * animation. If the window magnification is enabling, it runs the animation in reverse.
     *
     * @param animationCallback Called when the transition is complete, the given arguments
     *                          are as same as current values, or the transition is interrupted
     *                          due to the new transition request.
     */
    void deleteWindowMagnification(
            @Nullable IRemoteMagnificationAnimationCallback animationCallback) {
        mAnimationController.deleteWindowMagnification(animationCallback);
    }

    /**
     * Deletes the magnification window.
     */
    void deleteWindowMagnification() {
        if (!isActivated()) {
            return;
        }

        if (mMirrorSurface != null) {
            mTransaction.remove(mMirrorSurface).apply();
            mMirrorSurface = null;
        }

        if (mMirrorSurfaceView != null) {
            mMirrorSurfaceView.removeOnLayoutChangeListener(mMirrorSurfaceViewLayoutChangeListener);
        }

        if (mMirrorView != null) {
            mHandler.removeCallbacks(mMirrorViewRunnable);
            mMirrorView.removeOnLayoutChangeListener(mMirrorViewLayoutChangeListener);
            if (!Flags.createWindowlessWindowMagnifier()) {
                mViewCaptureAwareWindowManager.removeView(mMirrorView);
            }
            mMirrorView = null;
        }

        if (mMirrorWindowControl != null) {
            mMirrorWindowControl.destroyControl();
        }

        if (mSurfaceControlViewHost != null) {
            mSurfaceControlViewHost.release();
            mSurfaceControlViewHost = null;
        }

        mMirrorViewBounds.setEmpty();
        mSourceBounds.setEmpty();
        updateSystemUIStateIfNeeded();
        setEditMagnifierSizeMode(false);

        mContext.unregisterComponentCallbacks(this);
        // Notify source bounds empty when magnification is deleted.
        mWindowMagnifierCallback.onSourceBoundsChanged(mDisplayId, new Rect());
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        final int configDiff = newConfig.diff(mConfiguration);
        mConfiguration.setTo(newConfig);
        onConfigurationChanged(configDiff);
    }

    @Override
    public void onLowMemory() {
    }

    /**
     * Called when the configuration has changed, and it updates window magnification UI.
     *
     * @param configDiff a bit mask of the differences between the configurations
     */
    void onConfigurationChanged(int configDiff) {
        if (DEBUG) {
            Log.d(TAG, "onConfigurationChanged = " + Configuration.configurationDiffToString(
                    configDiff));
        }
        if (configDiff == 0) {
            return;
        }
        if ((configDiff & ActivityInfo.CONFIG_ORIENTATION) != 0) {
            onRotate();
        }

        if ((configDiff & ActivityInfo.CONFIG_LOCALE) != 0) {
            updateAccessibilityWindowTitleIfNeeded();
        }

        boolean reCreateWindow = false;
        if ((configDiff & ActivityInfo.CONFIG_DENSITY) != 0) {
            updateDimensions();
            computeBounceAnimationScale();
            reCreateWindow = true;
        }

        if ((configDiff & ActivityInfo.CONFIG_SCREEN_SIZE) != 0) {
            reCreateWindow |= handleScreenSizeChanged();
        }

        // Recreate the window again to correct the window appearance due to density or
        // window size changed not caused by rotation.
        if (isActivated() && reCreateWindow) {
            deleteWindowMagnification();
            updateWindowMagnificationInternal(Float.NaN, Float.NaN, Float.NaN);
        }
    }

    /**
     * Calculates the magnification frame if the window bounds is changed.
     * Note that the orientation also changes the wind bounds, so it should be handled first.
     *
     * @return {@code true} if the magnification frame is changed with the new window bounds.
     */
    private boolean handleScreenSizeChanged() {
        final Rect oldWindowBounds = new Rect(mWindowBounds);
        final Rect currentWindowBounds = mWm.getCurrentWindowMetrics().getBounds();

        if (currentWindowBounds.equals(oldWindowBounds)) {
            if (DEBUG) {
                Log.d(TAG, "handleScreenSizeChanged -- window bounds is not changed");
            }
            return false;
        }
        mWindowBounds.set(currentWindowBounds);
        final Size windowFrameSize = restoreMagnificationWindowFrameSizeIfPossible();
        final float newCenterX = (getCenterX()) * mWindowBounds.width() / oldWindowBounds.width();
        final float newCenterY = (getCenterY()) * mWindowBounds.height() / oldWindowBounds.height();

        setMagnificationFrame(windowFrameSize.getWidth(), windowFrameSize.getHeight(),
                (int) newCenterX, (int) newCenterY);
        calculateMagnificationFrameBoundary();
        return true;
    }

    private void updateSystemUIStateIfNeeded() {
        updateSysUIState(false);
    }

    private void updateAccessibilityWindowTitleIfNeeded() {
        if (!isActivated()) return;
        LayoutParams params = (LayoutParams) mMirrorView.getLayoutParams();
        params.accessibilityTitle = getAccessibilityWindowTitle();
        if (Flags.createWindowlessWindowMagnifier()) {
            mSurfaceControlViewHost.relayout(params);
        } else {
            mWm.updateViewLayout(mMirrorView, params);
        }
    }

    /**
     * Keep MirrorWindow position on the screen unchanged when device rotates 90° clockwise or
     * anti-clockwise.
     */
    private void onRotate() {
        final Display display = mContext.getDisplay();
        final int oldRotation = mRotation;
        mRotation = display.getRotation();
        final int rotationDegree = getDegreeFromRotation(mRotation, oldRotation);
        if (rotationDegree == 0 || rotationDegree == 180) {
            Log.w(TAG, "onRotate -- rotate with the device. skip it");
            return;
        }
        final Rect currentWindowBounds = new Rect(mWm.getCurrentWindowMetrics().getBounds());
        if (currentWindowBounds.width() != mWindowBounds.height()
                || currentWindowBounds.height() != mWindowBounds.width()) {
            Log.w(TAG, "onRotate -- unexpected window height/width");
            return;
        }

        mWindowBounds.set(currentWindowBounds);

        // Keep MirrorWindow position on the screen unchanged when device rotates 90°
        // clockwise or anti-clockwise.

        final Matrix matrix = new Matrix();
        matrix.setRotate(rotationDegree);
        if (rotationDegree == 90) {
            matrix.postTranslate(mWindowBounds.width(), 0);
        } else if (rotationDegree == 270) {
            matrix.postTranslate(0, mWindowBounds.height());
        }

        final RectF transformedRect = new RectF(mMagnificationFrame);
        // The window frame is going to be transformed by the rotation matrix.
        transformedRect.inset(-mMirrorSurfaceMargin, -mMirrorSurfaceMargin);
        matrix.mapRect(transformedRect);
        setWindowSizeAndCenter((int) transformedRect.width(), (int) transformedRect.height(),
                (int) transformedRect.centerX(), (int) transformedRect.centerY());
    }

    /** Returns the rotation degree change of two {@link Surface.Rotation} */
    private int getDegreeFromRotation(@Surface.Rotation int newRotation,
                                      @Surface.Rotation int oldRotation) {
        return (oldRotation - newRotation + 4) % 4 * 90;
    }

    private void createMirrorWindow() {
        if (Flags.createWindowlessWindowMagnifier()) {
            createWindowlessMirrorWindow();
            return;
        }

        // The window should be the size the mirrored surface will be but also add room for the
        // border and the drag handle.
        int windowWidth = mMagnificationFrame.width() + 2 * mMirrorSurfaceMargin;
        int windowHeight = mMagnificationFrame.height() + 2 * mMirrorSurfaceMargin;

        LayoutParams params = new LayoutParams(
                windowWidth, windowHeight,
                LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY,
                LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSPARENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = mMagnificationFrame.left - mMirrorSurfaceMargin;
        params.y = mMagnificationFrame.top - mMirrorSurfaceMargin;
        params.layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        params.receiveInsetsIgnoringZOrder = true;
        params.setTitle(mContext.getString(R.string.magnification_window_title));
        params.accessibilityTitle = getAccessibilityWindowTitle();

        mMirrorView = LayoutInflater.from(mContext).inflate(R.layout.window_magnifier_view, null);
        mMirrorSurfaceView = mMirrorView.findViewById(R.id.surface_view);

        mMirrorBorderView = mMirrorView.findViewById(R.id.magnification_inner_border);

        // Allow taps to go through to the mirror SurfaceView below.
        mMirrorSurfaceView.addOnLayoutChangeListener(mMirrorSurfaceViewLayoutChangeListener);

        mMirrorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        mMirrorView.addOnLayoutChangeListener(mMirrorViewLayoutChangeListener);
        mMirrorView.setAccessibilityDelegate(new MirrorWindowA11yDelegate());
        mMirrorView.setOnApplyWindowInsetsListener((v, insets) -> {
            if (!mHandler.hasCallbacks(mWindowInsetChangeRunnable)) {
                mHandler.post(mWindowInsetChangeRunnable);
            }
            return v.onApplyWindowInsets(insets);
        });

        mViewCaptureAwareWindowManager.addView(mMirrorView, params);

        SurfaceHolder holder = mMirrorSurfaceView.getHolder();
        holder.addCallback(this);
        holder.setFormat(PixelFormat.RGBA_8888);
        addDragTouchListeners();
    }

    private void createWindowlessMirrorWindow() {
        // The window should be the size the mirrored surface will be but also add room for the
        // border and the drag handle.
        int windowWidth = mMagnificationFrame.width() + 2 * mMirrorSurfaceMargin;
        int windowHeight = mMagnificationFrame.height() + 2 * mMirrorSurfaceMargin;

        // TODO: b/335440685 - Move to TYPE_ACCESSIBILITY_OVERLAY after the issues with
        // that type preventing swipe to navigate are resolved.
        LayoutParams params = new LayoutParams(
                windowWidth, windowHeight,
                LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY,
                LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSPARENT);
        params.receiveInsetsIgnoringZOrder = true;
        params.setTitle(mContext.getString(R.string.magnification_window_title));
        params.accessibilityTitle = getAccessibilityWindowTitle();
        params.setTrustedOverlay();

        mMirrorView = LayoutInflater.from(mContext).inflate(R.layout.window_magnifier_view, null);
        mMirrorSurfaceView = mMirrorView.findViewById(R.id.surface_view);

        mMirrorBorderView = mMirrorView.findViewById(R.id.magnification_inner_border);

        // Allow taps to go through to the mirror SurfaceView below.
        mMirrorSurfaceView.addOnLayoutChangeListener(mMirrorSurfaceViewLayoutChangeListener);

        mMirrorView.addOnLayoutChangeListener(mMirrorViewLayoutChangeListener);
        mMirrorView.setAccessibilityDelegate(new MirrorWindowA11yDelegate());
        mMirrorView.setOnApplyWindowInsetsListener((v, insets) -> {
            if (!mHandler.hasCallbacks(mWindowInsetChangeRunnable)) {
                mHandler.post(mWindowInsetChangeRunnable);
            }
            return v.onApplyWindowInsets(insets);
        });

        mSurfaceControlViewHost = mScvhSupplier.get();
        mSurfaceControlViewHost.setView(mMirrorView, params);
        SurfaceControl surfaceControl = mSurfaceControlViewHost
                .getSurfacePackage().getSurfaceControl();

        int x = mMagnificationFrame.left - mMirrorSurfaceMargin;
        int y = mMagnificationFrame.top - mMirrorSurfaceMargin;
        mTransaction
                .setCrop(surfaceControl, new Rect(0, 0, windowWidth, windowHeight))
                .setPosition(surfaceControl, x, y)
                .setLayer(surfaceControl, Integer.MAX_VALUE)
                .show(surfaceControl)
                .apply();

        mMirrorViewBounds.set(x, y, x + windowWidth, y + windowHeight);

        AccessibilityManager accessibilityManager = mContext
                .getSystemService(AccessibilityManager.class);
        accessibilityManager.attachAccessibilityOverlayToDisplay(mDisplayId, surfaceControl);

        SurfaceHolder holder = mMirrorSurfaceView.getHolder();
        holder.addCallback(this);
        holder.setFormat(PixelFormat.RGBA_8888);
        addDragTouchListeners();
    }

    private void onWindowInsetChanged() {
        if (updateSystemGestureInsetsTop()) {
            updateSystemUIStateIfNeeded();
        }
    }

    private void applyTapExcludeRegion() {
        if (Flags.createWindowlessWindowMagnifier()) {
            applyTouchableRegion();
            return;
        }

        // Sometimes this can get posted and run after deleteWindowMagnification() is called.
        if (mMirrorView == null) return;

        final Region tapExcludeRegion = calculateTapExclude();
        final IWindow window = IWindow.Stub.asInterface(mMirrorView.getWindowToken());
        try {
            IWindowSession session = mGlobalWindowSessionSupplier.get();
            session.updateTapExcludeRegion(window, tapExcludeRegion);
        } catch (RemoteException e) {
        }
    }

    private Region calculateTapExclude() {
        Region regionInsideDragBorder = new Region(mBorderDragSize, mBorderDragSize,
                mMirrorView.getWidth() - mBorderDragSize,
                mMirrorView.getHeight() - mBorderDragSize);

        Region tapExcludeRegion = new Region();

        Rect dragArea = new Rect();
        mDragView.getHitRect(dragArea);

        Rect topLeftArea = new Rect();
        mTopLeftCornerView.getHitRect(topLeftArea);

        Rect topRightArea = new Rect();
        mTopRightCornerView.getHitRect(topRightArea);

        Rect bottomLeftArea = new Rect();
        mBottomLeftCornerView.getHitRect(bottomLeftArea);

        Rect bottomRightArea = new Rect();
        mBottomRightCornerView.getHitRect(bottomRightArea);

        Rect closeArea = new Rect();
        mCloseView.getHitRect(closeArea);

        // add tapExcludeRegion for Drag or close
        tapExcludeRegion.op(dragArea, Region.Op.UNION);
        tapExcludeRegion.op(topLeftArea, Region.Op.UNION);
        tapExcludeRegion.op(topRightArea, Region.Op.UNION);
        tapExcludeRegion.op(bottomLeftArea, Region.Op.UNION);
        tapExcludeRegion.op(bottomRightArea, Region.Op.UNION);
        tapExcludeRegion.op(closeArea, Region.Op.UNION);

        regionInsideDragBorder.op(tapExcludeRegion, Region.Op.DIFFERENCE);

        return regionInsideDragBorder;
    }

    private void applyTouchableRegion() {
        // Sometimes this can get posted and run after deleteWindowMagnification() is called.
        if (mMirrorView == null) return;

        var surfaceControl = mSurfaceControlViewHost.getRootSurfaceControl();
        surfaceControl.setTouchableRegion(calculateTouchableRegion());
    }

    private Region calculateTouchableRegion() {
        Region touchableRegion = new Region(0, 0, mMirrorView.getWidth(), mMirrorView.getHeight());

        Region regionInsideDragBorder = new Region(mBorderDragSize, mBorderDragSize,
                mMirrorView.getWidth() - mBorderDragSize,
                mMirrorView.getHeight() - mBorderDragSize);
        touchableRegion.op(regionInsideDragBorder, Region.Op.DIFFERENCE);

        Rect dragArea = new Rect();
        mDragView.getHitRect(dragArea);

        Rect topLeftArea = new Rect();
        mTopLeftCornerView.getHitRect(topLeftArea);

        Rect topRightArea = new Rect();
        mTopRightCornerView.getHitRect(topRightArea);

        Rect bottomLeftArea = new Rect();
        mBottomLeftCornerView.getHitRect(bottomLeftArea);

        Rect bottomRightArea = new Rect();
        mBottomRightCornerView.getHitRect(bottomRightArea);

        Rect closeArea = new Rect();
        mCloseView.getHitRect(closeArea);

        // Add touchable regions for drag and close
        touchableRegion.op(dragArea, Region.Op.UNION);
        touchableRegion.op(topLeftArea, Region.Op.UNION);
        touchableRegion.op(topRightArea, Region.Op.UNION);
        touchableRegion.op(bottomLeftArea, Region.Op.UNION);
        touchableRegion.op(bottomRightArea, Region.Op.UNION);
        touchableRegion.op(closeArea, Region.Op.UNION);

        return touchableRegion;
    }

    private String getAccessibilityWindowTitle() {
        return mResources.getString(com.android.internal.R.string.android_system_label);
    }

    private void showControls() {
        if (mMirrorWindowControl != null) {
            mMirrorWindowControl.showControl();
        }
    }

    /**
     * Sets the window frame size with given width and height in pixels without changing the
     * window center.
     *
     * @param width the window frame width in pixels
     * @param height the window frame height in pixels.
     */
    @MainThread
    private void setMagnificationFrameSize(int width, int height) {
        setWindowSize(width + 2 * mMirrorSurfaceMargin, height + 2 * mMirrorSurfaceMargin);
    }

    /**
     * Sets the window size with given width and height in pixels without changing the
     * window center. The width or the height will be clamped in the range
     * [{@link #mMinWindowSize}, screen width or height].
     *
     * @param width the window width in pixels
     * @param height the window height in pixels.
     */
    public void setWindowSize(int width, int height) {
        setWindowSizeAndCenter(width, height, Float.NaN, Float.NaN);
    }

    void setWindowSizeAndCenter(int width, int height, float centerX, float centerY) {
        width = MathUtils.clamp(width, mMinWindowSize, mWindowBounds.width());
        height = MathUtils.clamp(height, mMinWindowSize, mWindowBounds.height());

        if (Float.isNaN(centerX)) {
            centerX = mMagnificationFrame.centerX();
        }
        if (Float.isNaN(centerY)) {
            centerY = mMagnificationFrame.centerY();
        }

        final int frameWidth = width - 2 * mMirrorSurfaceMargin;
        final int frameHeight = height - 2 * mMirrorSurfaceMargin;
        setMagnificationFrame(frameWidth, frameHeight, (int) centerX, (int) centerY);
        calculateMagnificationFrameBoundary();
        // Correct the frame position to ensure it is inside the boundary.
        updateMagnificationFramePosition(0, 0);
        modifyWindowMagnification(true);
    }

    private void setMagnificationFrame(int width, int height, int centerX, int centerY) {
        mWindowMagnificationFrameSizePrefs.saveIndexAndSizeForCurrentDensity(
                mSettingsButtonIndex, new Size(width, height));

        // Sets the initial frame area for the mirror and place it to the given center on the
        // display.
        final int initX = centerX - width / 2;
        final int initY = centerY - height / 2;
        mMagnificationFrame.set(initX, initY, initX + width, initY + height);
    }

    private Size restoreMagnificationWindowFrameSizeIfPossible() {
        if (Flags.saveAndRestoreMagnificationSettingsButtons()) {
            return restoreMagnificationWindowFrameIndexAndSizeIfPossible();
        }

        if (!mWindowMagnificationFrameSizePrefs.isPreferenceSavedForCurrentDensity()) {
            return getDefaultMagnificationWindowFrameSize();
        }

        return mWindowMagnificationFrameSizePrefs.getSizeForCurrentDensity();
    }

    private Size restoreMagnificationWindowFrameIndexAndSizeIfPossible() {
        if (!mWindowMagnificationFrameSizePrefs.isPreferenceSavedForCurrentDensity()) {
            notifyWindowSizeRestored(MagnificationSize.DEFAULT);
            return getDefaultMagnificationWindowFrameSize();
        }

        // This will return DEFAULT index if the stored preference is in an invalid format.
        // Therefore, except CUSTOM, we would like to calculate the window width and height based
        // on the restored MagnificationSize index.
        int restoredIndex = mWindowMagnificationFrameSizePrefs.getIndexForCurrentDensity();
        notifyWindowSizeRestored(restoredIndex);
        if (restoredIndex == MagnificationSize.CUSTOM) {
            return mWindowMagnificationFrameSizePrefs.getSizeForCurrentDensity();
        }

        int restoredSize = getMagnificationFrameSizeFromIndex(restoredIndex);
        return new Size(restoredSize, restoredSize);
    }

    private void notifyWindowSizeRestored(@MagnificationSize int index) {
        mSettingsButtonIndex = index;
        if (isActivated()) {
            // Send the callback only if the window magnification is activated. The check is to
            // avoid updating the settings panel in the cases that window magnification is not yet
            // activated such as during the constructor initialization of this class.
            mWindowMagnifierCallback.onWindowMagnifierBoundsRestored(mDisplayId, index);
        }
    }

    private Size getDefaultMagnificationWindowFrameSize() {
        final int defaultSize = getMagnificationWindowSizeFromIndex(MagnificationSize.DEFAULT)
                - 2 * mMirrorSurfaceMargin;
        return new Size(defaultSize, defaultSize);
    }

    /**
     * This is called once the surfaceView is created so the mirrored content can be placed as a
     * child of the surfaceView.
     */
    private void createMirror() {
        mMirrorSurface = mirrorDisplay(mDisplayId);
        if (!mMirrorSurface.isValid()) {
            return;
        }
        // Set the surface of the SurfaceView to black to avoid users seeing the contents below the
        // magnifier when the mirrored surface has an alpha less than 1.
        if (Flags.addBlackBackgroundForWindowMagnifier()) {
            mTransaction.setColor(mMirrorSurfaceView.getSurfaceControl(), COLOR_BLACK_ARRAY);
        }
        mTransaction.show(mMirrorSurface)
                .reparent(mMirrorSurface, mMirrorSurfaceView.getSurfaceControl());
        modifyWindowMagnification(false);
    }

    /**
     * Mirrors a specified display. The SurfaceControl returned is the root of the mirrored
     * hierarchy.
     *
     * @param displayId The id of the display to mirror
     * @return The SurfaceControl for the root of the mirrored hierarchy.
     */
    private SurfaceControl mirrorDisplay(final int displayId) {
        try {
            SurfaceControl outSurfaceControl = new SurfaceControl();
            WindowManagerGlobal.getWindowManagerService().mirrorDisplay(displayId,
                    outSurfaceControl);
            return outSurfaceControl;
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to reach window manager", e);
        }
        return null;
    }

    private void addDragTouchListeners() {
        mDragView = mMirrorView.findViewById(R.id.drag_handle);
        mLeftDrag = mMirrorView.findViewById(R.id.left_handle);
        mTopDrag = mMirrorView.findViewById(R.id.top_handle);
        mRightDrag = mMirrorView.findViewById(R.id.right_handle);
        mBottomDrag = mMirrorView.findViewById(R.id.bottom_handle);
        mCloseView = mMirrorView.findViewById(R.id.close_button);
        mTopRightCornerView = mMirrorView.findViewById(R.id.top_right_corner);
        mTopLeftCornerView = mMirrorView.findViewById(R.id.top_left_corner);
        mBottomRightCornerView = mMirrorView.findViewById(R.id.bottom_right_corner);
        mBottomLeftCornerView = mMirrorView.findViewById(R.id.bottom_left_corner);

        mDragView.setOnTouchListener(this);
        mLeftDrag.setOnTouchListener(this);
        mTopDrag.setOnTouchListener(this);
        mRightDrag.setOnTouchListener(this);
        mBottomDrag.setOnTouchListener(this);
        mCloseView.setOnTouchListener(this);
        mTopLeftCornerView.setOnTouchListener(this);
        mTopRightCornerView.setOnTouchListener(this);
        mBottomLeftCornerView.setOnTouchListener(this);
        mBottomRightCornerView.setOnTouchListener(this);
    }

    /**
     * Modifies the placement of the mirrored content when the position or size of mMirrorView is
     * updated.
     *
     * @param computeWindowSize set to {@code true} to compute window size with
     * {@link #mMagnificationFrame}.
     */
    private void modifyWindowMagnification(boolean computeWindowSize) {
        if (Flags.createWindowlessWindowMagnifier()) {
            updateMirrorSurfaceGeometry();
            updateWindowlessMirrorViewLayout(computeWindowSize);
        } else {
            mSfVsyncFrameProvider.postFrameCallback(mMirrorViewGeometryVsyncCallback);
            updateMirrorViewLayout(computeWindowSize);
        }
    }

    /**
     * Updates {@link #mMirrorSurface}'s geometry. This modifies {@link #mTransaction} but does not
     * apply it.
     */
    @UiThread
    private void updateMirrorSurfaceGeometry() {
        if (isActivated() && mMirrorSurface != null
                && calculateSourceBounds(mMagnificationFrame, mScale)) {
            // The final destination for the magnification surface should be at 0,0
            // since the ViewRootImpl's position will change
            mTmpRect.set(0, 0, mMagnificationFrame.width(), mMagnificationFrame.height());
            mTransaction.setGeometry(mMirrorSurface, mSourceBounds, mTmpRect, Surface.ROTATION_0);

            // Notify source bounds change when the magnifier is not animating.
            if (!mAnimationController.isAnimating()) {
                notifySourceBoundsChanged();
            }
        }
    }

    private void notifySourceBoundsChanged() {
        mWindowMagnifierCallback.onSourceBoundsChanged(mDisplayId, mSourceBounds);
    }

    /**
     * Updates the position of {@link mSurfaceControlViewHost} and layout params of MirrorView based
     * on the position and size of {@link #mMagnificationFrame}.
     *
     * @param computeWindowSize set to {@code true} to compute window size with
     * {@link #mMagnificationFrame}.
     */
    @UiThread
    private void updateWindowlessMirrorViewLayout(boolean computeWindowSize) {
        if (!isActivated()) {
            return;
        }

        final int width = mMagnificationFrame.width() + 2 * mMirrorSurfaceMargin;
        final int height = mMagnificationFrame.height() + 2 * mMirrorSurfaceMargin;

        final int minX = -mOuterBorderSize;
        final int maxX = mWindowBounds.right - width + mOuterBorderSize;
        final int x = MathUtils.clamp(mMagnificationFrame.left - mMirrorSurfaceMargin, minX, maxX);

        final int minY = -mOuterBorderSize;
        final int maxY = mWindowBounds.bottom - height + mOuterBorderSize;
        final int y = MathUtils.clamp(mMagnificationFrame.top - mMirrorSurfaceMargin, minY, maxY);

        if (computeWindowSize) {
            LayoutParams params = (LayoutParams) mMirrorView.getLayoutParams();
            params.width = width;
            params.height = height;
            mSurfaceControlViewHost.relayout(params);
            mTransaction.setCrop(mSurfaceControlViewHost.getSurfacePackage().getSurfaceControl(),
                    new Rect(0, 0, width, height));
        }

        mMirrorViewBounds.set(x, y, x + width, y + height);
        mTransaction.setPosition(
                mSurfaceControlViewHost.getSurfacePackage().getSurfaceControl(), x, y);
        if (computeWindowSize) {
            mSurfaceControlViewHost.getRootSurfaceControl().applyTransactionOnDraw(mTransaction);
        } else {
            mTransaction.apply();
        }

        // If they are not dragging the handle, we can move the drag handle immediately without
        // disruption. But if they are dragging it, we avoid moving until the end of the drag.
        if (!mIsDragging) {
            mMirrorView.post(this::maybeRepositionButton);
        }

        mMirrorViewRunnable.run();
    }

    /**
     * Updates the layout params of MirrorView based on the size of {@link #mMagnificationFrame}
     * and translates MirrorView position when the view is moved close to the screen edges;
     *
     * @param computeWindowSize set to {@code true} to compute window size with
     * {@link #mMagnificationFrame}.
     */
    private void updateMirrorViewLayout(boolean computeWindowSize) {
        if (!isActivated()) {
            return;
        }
        final int maxMirrorViewX = mWindowBounds.width() - mMirrorView.getWidth();
        final int maxMirrorViewY = mWindowBounds.height() - mMirrorView.getHeight();

        LayoutParams params =
                (LayoutParams) mMirrorView.getLayoutParams();
        params.x = mMagnificationFrame.left - mMirrorSurfaceMargin;
        params.y = mMagnificationFrame.top - mMirrorSurfaceMargin;
        if (computeWindowSize) {
            params.width = mMagnificationFrame.width() + 2 * mMirrorSurfaceMargin;
            params.height = mMagnificationFrame.height() + 2 * mMirrorSurfaceMargin;
        }

        // Translates MirrorView position to make MirrorSurfaceView that is inside MirrorView
        // able to move close to the screen edges.
        final float translationX;
        final float translationY;
        if (params.x < 0) {
            translationX = Math.max(params.x, -mOuterBorderSize);
        } else if (params.x > maxMirrorViewX) {
            translationX = Math.min(params.x - maxMirrorViewX, mOuterBorderSize);
        } else {
            translationX = 0;
        }
        if (params.y < 0) {
            translationY = Math.max(params.y, -mOuterBorderSize);
        } else if (params.y > maxMirrorViewY) {
            translationY = Math.min(params.y - maxMirrorViewY, mOuterBorderSize);
        } else {
            translationY = 0;
        }
        mMirrorView.setTranslationX(translationX);
        mMirrorView.setTranslationY(translationY);
        mWm.updateViewLayout(mMirrorView, params);

        // If they are not dragging the handle, we can move the drag handle immediately without
        // disruption. But if they are dragging it, we avoid moving until the end of the drag.
        if (!mIsDragging) {
            mMirrorView.post(this::maybeRepositionButton);
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (v == mDragView
                || v == mLeftDrag
                || v == mTopDrag
                || v == mRightDrag
                || v == mBottomDrag
                || v == mTopLeftCornerView
                || v == mTopRightCornerView
                || v == mBottomLeftCornerView
                || v == mBottomRightCornerView
                || v == mCloseView) {
            return mGestureDetector.onTouch(v, event);
        }
        return false;
    }

    public void updateSysUIStateFlag() {
        updateSysUIState(true);
    }

    /**
     * Calculates the desired source bounds. This will be the area under from the center of  the
     * displayFrame, factoring in scale.
     *
     * @return {@code true} if the source bounds is changed.
     */
    private boolean calculateSourceBounds(Rect displayFrame, float scale) {
        final Rect oldSourceBounds = mTmpRect;
        oldSourceBounds.set(mSourceBounds);
        int halfWidth = displayFrame.width() / 2;
        int halfHeight = displayFrame.height() / 2;
        int left = displayFrame.left + (halfWidth - (int) (halfWidth / scale));
        int right = displayFrame.right - (halfWidth - (int) (halfWidth / scale));
        int top = displayFrame.top + (halfHeight - (int) (halfHeight / scale));
        int bottom = displayFrame.bottom - (halfHeight - (int) (halfHeight / scale));

        mSourceBounds.set(left, top, right, bottom);

        // SourceBound's center is equal to center[X,Y] but calculated from MagnificationFrame's
        // center. The relation between SourceBound and MagnificationFrame is as following:
        //          MagnificationFrame = SourceBound (center[X,Y]) + MagnificationFrameOffset
        //          SourceBound = MagnificationFrame - MagnificationFrameOffset
        mSourceBounds.offset(-mMagnificationFrameOffsetX, -mMagnificationFrameOffsetY);

        if (mSourceBounds.left < 0) {
            mSourceBounds.offsetTo(0, mSourceBounds.top);
        } else if (mSourceBounds.right > mWindowBounds.width()) {
            mSourceBounds.offsetTo(mWindowBounds.width() - mSourceBounds.width(),
                    mSourceBounds.top);
        }

        if (mSourceBounds.top < 0) {
            mSourceBounds.offsetTo(mSourceBounds.left, 0);
        } else if (mSourceBounds.bottom > mWindowBounds.height()) {
            mSourceBounds.offsetTo(mSourceBounds.left,
                    mWindowBounds.height() - mSourceBounds.height());
        }
        return !mSourceBounds.equals(oldSourceBounds);
    }

    private void calculateMagnificationFrameBoundary() {
        // Calculates width and height for magnification frame could exceed out the screen.
        // TODO : re-calculating again when scale is changed.
        // The half width of magnification frame.
        final int halfWidth = mMagnificationFrame.width() / 2;
        // The half height of magnification frame.
        final int halfHeight = mMagnificationFrame.height() / 2;
        // The scaled half width of magnified region.
        final int scaledWidth = (int) (halfWidth / mScale);
        // The scaled half height of magnified region.
        final int scaledHeight = (int) (halfHeight / mScale);

        // MagnificationFrameBoundary constrain the space of MagnificationFrame, and it also has
        // to leave enough space for SourceBound to magnify the whole screen space.
        // However, there is an offset between SourceBound and MagnificationFrame.
        // The relation between SourceBound and MagnificationFrame is as following:
        //      SourceBound = MagnificationFrame - MagnificationFrameOffset
        // Therefore, we have to adjust the exceededBoundary based on the offset.
        //
        // We have to increase the offset space for the SourceBound edges which are located in
        // the MagnificationFrame. For example, if the offsetX and offsetY are negative, which
        // means SourceBound is at right-bottom size of MagnificationFrame, the left and top
        // edges of SourceBound are located in MagnificationFrame. So, we have to leave extra
        // offset space at left and top sides and don't have to leave extra space at right and
        // bottom sides.
        final int exceededLeft = Math.max(halfWidth - scaledWidth - mMagnificationFrameOffsetX, 0);
        final int exceededRight = Math.max(halfWidth - scaledWidth + mMagnificationFrameOffsetX, 0);
        final int exceededTop = Math.max(halfHeight - scaledHeight - mMagnificationFrameOffsetY, 0);
        final int exceededBottom = Math.max(halfHeight - scaledHeight + mMagnificationFrameOffsetY,
                0);

        mMagnificationFrameBoundary.set(
                -exceededLeft,
                -exceededTop,
                mWindowBounds.width() + exceededRight,
                mWindowBounds.height() + exceededBottom);
    }

    /**
     * Calculates and sets the real position of magnification frame based on the magnified region
     * should be limited by the region of the display.
     */
    private boolean updateMagnificationFramePosition(int xOffset, int yOffset) {
        mTmpRect.set(mMagnificationFrame);
        mTmpRect.offset(xOffset, yOffset);

        if (mTmpRect.left < mMagnificationFrameBoundary.left) {
            mTmpRect.offsetTo(mMagnificationFrameBoundary.left, mTmpRect.top);
        } else if (mTmpRect.right > mMagnificationFrameBoundary.right) {
            final int leftOffset = mMagnificationFrameBoundary.right - mMagnificationFrame.width();
            mTmpRect.offsetTo(leftOffset, mTmpRect.top);
        }

        if (mTmpRect.top < mMagnificationFrameBoundary.top) {
            mTmpRect.offsetTo(mTmpRect.left, mMagnificationFrameBoundary.top);
        } else if (mTmpRect.bottom > mMagnificationFrameBoundary.bottom) {
            final int topOffset = mMagnificationFrameBoundary.bottom - mMagnificationFrame.height();
            mTmpRect.offsetTo(mTmpRect.left, topOffset);
        }

        if (!mTmpRect.equals(mMagnificationFrame)) {
            mMagnificationFrame.set(mTmpRect);
            return true;
        }
        return false;
    }

    private void updateSysUIState(boolean force) {
        final boolean overlap = isActivated() && mSystemGestureTop > 0
                && mMirrorViewBounds.bottom > mSystemGestureTop;
        if (force || overlap != mOverlapWithGestureInsets) {
            mOverlapWithGestureInsets = overlap;
            mSysUiState.setFlag(SYSUI_STATE_MAGNIFICATION_OVERLAP, mOverlapWithGestureInsets)
                    .commitUpdate(mDisplayId);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        createMirror();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
    }

    @Override
    public void move(int xOffset, int yOffset) {
        moveWindowMagnifier(xOffset, yOffset);
        mWindowMagnifierCallback.onMove(mDisplayId);
    }

    /**
     * Wraps {@link WindowMagnificationController#updateWindowMagnificationInternal(float, float,
     * float, float, float)}
     * with transition animation. If the window magnification is not enabled, the scale will start
     * from 1.0 and the center won't be changed during the animation. If animator is
     * {@code STATE_DISABLING}, the animation runs in reverse.
     *
     * @param scale   The target scale, or {@link Float#NaN} to leave unchanged.
     * @param centerX The screen-relative X coordinate around which to center for magnification,
     *                or {@link Float#NaN} to leave unchanged.
     * @param centerY The screen-relative Y coordinate around which to center for magnification,
     *                or {@link Float#NaN} to leave unchanged.
     * @param magnificationFrameOffsetRatioX Indicate the X coordinate offset
     *                                       between frame position X and centerX
     * @param magnificationFrameOffsetRatioY Indicate the Y coordinate offset
     *                                       between frame position Y and centerY
     * @param animationCallback Called when the transition is complete, the given arguments
     *                          are as same as current values, or the transition is interrupted
     *                          due to the new transition request.
     */
    public void enableWindowMagnification(float scale, float centerX, float centerY,
            float magnificationFrameOffsetRatioX, float magnificationFrameOffsetRatioY,
            @Nullable IRemoteMagnificationAnimationCallback animationCallback) {
        mAnimationController.enableWindowMagnification(scale, centerX, centerY,
                magnificationFrameOffsetRatioX, magnificationFrameOffsetRatioY, animationCallback);
    }

    /**
     * Updates window magnification status with specified parameters. If the given scale is
     * <strong>less than 1.0f</strong>, then
     * {@link WindowMagnificationController#deleteWindowMagnification()} will be called instead to
     * be consistent with the behavior of display magnification. If the given scale is
     * <strong>larger than or equal to 1.0f</strong>, and the window magnification is not activated
     * yet, window magnification will be enabled.
     *
     * @param scale   the target scale, or {@link Float#NaN} to leave unchanged
     * @param centerX the screen-relative X coordinate around which to center for magnification,
     *                or {@link Float#NaN} to leave unchanged.
     * @param centerY the screen-relative Y coordinate around which to center for magnification,
     *                or {@link Float#NaN} to leave unchanged.
     */
    void updateWindowMagnificationInternal(float scale, float centerX, float centerY) {
        updateWindowMagnificationInternal(scale, centerX, centerY, Float.NaN, Float.NaN);
    }

    /**
     * Updates window magnification status with specified parameters. If the given scale is
     * <strong>less than 1.0f</strong>, then
     * {@link WindowMagnificationController#deleteWindowMagnification()} will be called instead to
     * be consistent with the behavior of display magnification. If the given scale is
     * <strong>larger than or equal to 1.0f</strong>, and the window magnification is not activated
     * yet, window magnification will be enabled.
     * @param scale   the target scale, or {@link Float#NaN} to leave unchanged
     * @param centerX the screen-relative X coordinate around which to center for magnification,
     *                or {@link Float#NaN} to leave unchanged.
     * @param centerY the screen-relative Y coordinate around which to center for magnification,
     *                or {@link Float#NaN} to leave unchanged.
     * @param magnificationFrameOffsetRatioX Indicate the X coordinate offset
     *                                       between frame position X and centerX,
     *                                       or {@link Float#NaN} to leave unchanged.
     * @param magnificationFrameOffsetRatioY Indicate the Y coordinate offset
     *                                       between frame position Y and centerY,
     *                                       or {@link Float#NaN} to leave unchanged.
     */
    void updateWindowMagnificationInternal(float scale, float centerX, float centerY,
                float magnificationFrameOffsetRatioX, float magnificationFrameOffsetRatioY) {
        if (Float.compare(scale, 1.0f) < 0) {
            deleteWindowMagnification();
            return;
        }
        if (!isActivated()) {
            onConfigurationChanged(mResources.getConfiguration());
            mContext.registerComponentCallbacks(this);
        }

        mMagnificationFrameOffsetX = Float.isNaN(magnificationFrameOffsetRatioX)
                ? mMagnificationFrameOffsetX
                : (int) (mMagnificationFrame.width() / 2 * magnificationFrameOffsetRatioX);
        mMagnificationFrameOffsetY = Float.isNaN(magnificationFrameOffsetRatioY)
                ? mMagnificationFrameOffsetY
                : (int) (mMagnificationFrame.height() / 2 * magnificationFrameOffsetRatioY);

        // The relation of centers between SourceBound and MagnificationFrame is as following:
        // MagnificationFrame = SourceBound (e.g., centerX & centerY) + MagnificationFrameOffset
        final float newMagnificationFrameCenterX = centerX + mMagnificationFrameOffsetX;
        final float newMagnificationFrameCenterY = centerY + mMagnificationFrameOffsetY;

        final float offsetX = Float.isNaN(centerX) ? 0
                : newMagnificationFrameCenterX - mMagnificationFrame.exactCenterX();
        final float offsetY = Float.isNaN(centerY) ? 0
                : newMagnificationFrameCenterY - mMagnificationFrame.exactCenterY();
        mScale = Float.isNaN(scale) ? mScale : scale;

        calculateMagnificationFrameBoundary();
        updateMagnificationFramePosition((int) offsetX, (int) offsetY);
        if (!isActivated()) {
            createMirrorWindow();
            showControls();
            applyResourcesValues();
        } else {
            modifyWindowMagnification(false);
        }
    }

    // The magnifier is activated when the window is visible,
    // and the window is visible when it is existed.
    boolean isActivated() {
        return mMirrorView != null;
    }

    /**
     * Sets the scale of the magnified region if it's visible.
     *
     * @param scale the target scale, or {@link Float#NaN} to leave unchanged
     */
    void setScale(float scale) {
        if (mAnimationController.isAnimating() || !isActivated() || mScale == scale) {
            return;
        }

        updateWindowMagnificationInternal(scale, Float.NaN, Float.NaN);
        mHandler.removeCallbacks(mUpdateStateDescriptionRunnable);
        mHandler.postDelayed(mUpdateStateDescriptionRunnable, UPDATE_STATE_DESCRIPTION_DELAY_MS);
    }

    /**
     * Moves the window magnifier with specified offset in pixels unit.
     *
     * @param offsetX the amount in pixels to offset the window magnifier in the X direction, in
     *                current screen pixels.
     * @param offsetY the amount in pixels to offset the window magnifier in the Y direction, in
     *                current screen pixels.
     */
    void moveWindowMagnifier(float offsetX, float offsetY) {
        if (mAnimationController.isAnimating() || mMirrorSurfaceView == null) {
            return;
        }

        if (!mAllowDiagonalScrolling) {
            int direction = selectDirectionForMove(abs(offsetX), abs(offsetY));

            if (direction == HORIZONTAL) {
                offsetY = 0;
            } else {
                offsetX = 0;
            }
        }

        if (updateMagnificationFramePosition((int) offsetX, (int) offsetY)) {
            modifyWindowMagnification(false);
        }
    }

    void moveWindowMagnifierToPosition(float positionX, float positionY,
                                       IRemoteMagnificationAnimationCallback callback) {
        if (mMirrorSurfaceView == null) {
            return;
        }
        mAnimationController.moveWindowMagnifierToPosition(positionX, positionY, callback);
    }

    private int selectDirectionForMove(float diffX, float diffY) {
        int direction = 0;
        float result = diffY / diffX;

        if (result <= HORIZONTAL_LOCK_BASE) {
            direction = HORIZONTAL;  // horizontal move
        } else {
            direction = VERTICAL;  // vertical move
        }
        return direction;
    }

    /**
     * Gets the scale.
     *
     * @return {@link Float#NaN} if the window is invisible.
     */
    float getScale() {
        return isActivated() ? mScale : Float.NaN;
    }

    /**
     * Returns the screen-relative X coordinate of the center of the magnified bounds.
     *
     * @return the X coordinate. {@link Float#NaN} if the window is invisible.
     */
    float getCenterX() {
        return isActivated() ? mMagnificationFrame.exactCenterX() : Float.NaN;
    }

    /**
     * Returns the screen-relative Y coordinate of the center of the magnified bounds.
     *
     * @return the Y coordinate. {@link Float#NaN} if the window is invisible.
     */
    float getCenterY() {
        return isActivated() ? mMagnificationFrame.exactCenterY() : Float.NaN;
    }


    @VisibleForTesting
    boolean isDiagonalScrollingEnabled() {
        return mAllowDiagonalScrolling;
    }

    private CharSequence formatStateDescription(float scale) {
        // Cache the locale-appropriate NumberFormat.  Configuration locale is guaranteed
        // non-null, so the first time this is called we will always get the appropriate
        // NumberFormat, then never regenerate it unless the locale changes on the fly.
        final Locale curLocale = mContext.getResources().getConfiguration().getLocales().get(0);
        if (!curLocale.equals(mLocale)) {
            mLocale = curLocale;
            mPercentFormat = NumberFormat.getPercentInstance(curLocale);
        }
        return mPercentFormat.format(scale);
    }

    @Override
    public boolean onSingleTap(View view) {
        handleSingleTap(view);
        return true;
    }

    @Override
    public boolean onDrag(View view, float offsetX, float offsetY) {
        if (mEditSizeEnable) {
            return changeWindowSize(view, offsetX, offsetY);
        } else {
            move((int) offsetX, (int) offsetY);
        }
        return true;
    }

    private void handleSingleTap(View view) {
        int id = view.getId();
        if (id == R.id.drag_handle) {
            mWindowMagnifierCallback.onClickSettingsButton(mDisplayId);
        } else if (id == R.id.close_button) {
            setEditMagnifierSizeMode(false);
        } else {
            animateBounceEffectIfNeeded();
        }
    }

    private void applyResourcesValues() {
        // Sets the border appearance for the magnifier window
        mMirrorBorderView.setBackground(mResources.getDrawable(mEditSizeEnable
                ? R.drawable.accessibility_window_magnification_background_change
                : R.drawable.accessibility_window_magnification_background));

        // Changes the corner radius of the mMirrorSurfaceView
        mMirrorSurfaceView.setCornerRadius(
                        TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP,
                                mEditSizeEnable ? 16f : 28f,
                                mContext.getResources().getDisplayMetrics()));

        // Sets visibility of components for the magnifier window
        if (mEditSizeEnable) {
            mDragView.setVisibility(View.GONE);
            mCloseView.setVisibility(View.VISIBLE);
            mTopRightCornerView.setVisibility(View.VISIBLE);
            mTopLeftCornerView.setVisibility(View.VISIBLE);
            mBottomRightCornerView.setVisibility(View.VISIBLE);
            mBottomLeftCornerView.setVisibility(View.VISIBLE);
        } else {
            mDragView.setVisibility(View.VISIBLE);
            mCloseView.setVisibility(View.GONE);
            mTopRightCornerView.setVisibility(View.GONE);
            mTopLeftCornerView.setVisibility(View.GONE);
            mBottomRightCornerView.setVisibility(View.GONE);
            mBottomLeftCornerView.setVisibility(View.GONE);
        }
    }

    private boolean changeWindowSize(View view, float offsetX, float offsetY) {
        if (view == mLeftDrag) {
            changeMagnificationFrameSize(offsetX, 0, 0, 0);
        } else if (view == mRightDrag) {
            changeMagnificationFrameSize(0, 0, offsetX, 0);
        } else if (view == mTopDrag) {
            changeMagnificationFrameSize(0, offsetY, 0, 0);
        } else if (view == mBottomDrag) {
            changeMagnificationFrameSize(0, 0, 0, offsetY);
        } else if (view == mTopLeftCornerView) {
            changeMagnificationFrameSize(offsetX, offsetY, 0, 0);
        } else if (view == mTopRightCornerView) {
            changeMagnificationFrameSize(0, offsetY, offsetX, 0);
        } else if (view == mBottomLeftCornerView) {
            changeMagnificationFrameSize(offsetX, 0, 0, offsetY);
        } else if (view == mBottomRightCornerView) {
            changeMagnificationFrameSize(0, 0, offsetX, offsetY);
        } else {
            return false;
        }

        return true;
    }

    private void changeMagnificationFrameSize(
            float leftOffset, float topOffset, float rightOffset,
            float bottomOffset) {
        boolean bRTL = isRTL(mContext);
        final int initSize = Math.min(mWindowBounds.width(), mWindowBounds.height()) / 3;

        int maxHeightSize;
        int maxWidthSize;
        if (Flags.redesignMagnificationWindowSize()) {
            // mOuterBorderSize = transparent margin area
            // mMirrorSurfaceMargin = transparent margin area + orange border width
            // We would like to allow the width and height to be full size. Therefore, the max
            // frame size could be calculated as (window bounds - 2 * orange border width).
            maxHeightSize =
                    mWindowBounds.height() - 2 * (mMirrorSurfaceMargin - mOuterBorderSize);
            maxWidthSize  =
                    mWindowBounds.width() - 2 * (mMirrorSurfaceMargin - mOuterBorderSize);
        } else {
            maxHeightSize =
                    mWindowBounds.height() - 2 * mMirrorSurfaceMargin;
            maxWidthSize  =
                    mWindowBounds.width() - 2 * mMirrorSurfaceMargin;
        }

        Rect tempRect = new Rect();
        tempRect.set(mMagnificationFrame);

        if (bRTL) {
            tempRect.left += (int) (rightOffset);
            tempRect.right += (int) (leftOffset);
        } else {
            tempRect.right += (int) (rightOffset);
            tempRect.left += (int) (leftOffset);
        }
        tempRect.top += (int) (topOffset);
        tempRect.bottom += (int) (bottomOffset);

        if (tempRect.width() < initSize || tempRect.height() < initSize
                || tempRect.width() > maxWidthSize || tempRect.height() > maxHeightSize) {
            return;
        }
        mMagnificationFrame.set(tempRect);

        computeBounceAnimationScale();
        calculateMagnificationFrameBoundary();

        modifyWindowMagnification(true);
    }

    private static boolean isRTL(Context context) {
        Configuration config = context.getResources().getConfiguration();
        if (config == null) {
            return false;
        }
        return (config.screenLayout & Configuration.SCREENLAYOUT_LAYOUTDIR_MASK)
                == Configuration.SCREENLAYOUT_LAYOUTDIR_RTL;
    }

    @Override
    public boolean onStart(float x, float y) {
        mIsDragging = true;
        return true;
    }

    @Override
    public boolean onFinish(float x, float y) {
        maybeRepositionButton();
        mIsDragging = false;
        return false;
    }

    /** Moves the button to the opposite edge if the frame is against the edge of the screen. */
    private void maybeRepositionButton() {
        if (mMirrorView == null) return;

        final float screenEdgeX = mWindowBounds.right - mButtonRepositionThresholdFromEdge;
        final FrameLayout.LayoutParams layoutParams =
                (FrameLayout.LayoutParams) mDragView.getLayoutParams();

        final int newGravity;
        if (mMirrorViewBounds.right >= screenEdgeX) {
            newGravity = Gravity.BOTTOM | Gravity.LEFT;
        } else {
            newGravity = Gravity.BOTTOM | Gravity.RIGHT;
        }
        if (newGravity != layoutParams.gravity) {
            layoutParams.gravity = newGravity;
            mDragView.setLayoutParams(layoutParams);
            mDragView.post(this::applyTapExcludeRegion);
        }
    }

    void updateDragHandleResourcesIfNeeded(boolean settingsPanelIsShown) {
        if (!isActivated()) {
            return;
        }

        mSettingsPanelVisibility = settingsPanelIsShown;

        mDragView.setBackground(mContext.getResources().getDrawable(settingsPanelIsShown
                ? R.drawable.accessibility_window_magnification_drag_handle_background_change_inset
                : R.drawable.accessibility_window_magnification_drag_handle_background_inset));

        PorterDuffColorFilter filter = new PorterDuffColorFilter(
                mContext.getColor(settingsPanelIsShown
                        ? R.color.magnification_border_color
                        : R.color.magnification_drag_handle_stroke),
                PorterDuff.Mode.SRC_ATOP);

        mDragView.setColorFilter(filter);
    }

    private void setBounceEffectDuration(int duration) {
        mBounceEffectDuration = duration;
    }

    private void animateBounceEffectIfNeeded() {
        if (mMirrorView == null) {
            // run the animation only if the mirror view is not null
            return;
        }

        final ObjectAnimator scaleAnimator = ObjectAnimator.ofPropertyValuesHolder(mMirrorView,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1, mBounceEffectAnimationScale, 1),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1, mBounceEffectAnimationScale, 1));
        scaleAnimator.setDuration(mBounceEffectDuration);
        scaleAnimator.start();
    }

    public void dump(PrintWriter pw) {
        pw.println("WindowMagnificationController (displayId=" + mDisplayId + "):");
        pw.println("      mOverlapWithGestureInsets:" + mOverlapWithGestureInsets);
        pw.println("      mScale:" + mScale);
        pw.println("      mWindowBounds:" + mWindowBounds);
        pw.println("      mMirrorViewBounds:" + (isActivated() ? mMirrorViewBounds : "empty"));
        pw.println("      mMagnificationFrameBoundary:"
                + (isActivated() ? mMagnificationFrameBoundary : "empty"));
        pw.println("      mMagnificationFrame:"
                + (isActivated() ? mMagnificationFrame : "empty"));
        pw.println("      mSourceBounds:"
                + (mSourceBounds.isEmpty() ? "empty" : mSourceBounds));
        pw.println("      mSystemGestureTop:" + mSystemGestureTop);
        pw.println("      mMagnificationFrameOffsetX:" + mMagnificationFrameOffsetX);
        pw.println("      mMagnificationFrameOffsetY:" + mMagnificationFrameOffsetY);
    }

    private class MirrorWindowA11yDelegate extends View.AccessibilityDelegate {

        private CharSequence getClickAccessibilityActionLabel() {
            if (mEditSizeEnable) {
                // Perform click action to exit edit mode
                return mContext.getResources().getString(
                        R.string.magnification_exit_edit_mode_click_label);
            }

            return mSettingsPanelVisibility
                    ? mContext.getResources().getString(
                            R.string.magnification_close_settings_click_label)
                    : mContext.getResources().getString(
                            R.string.magnification_open_settings_click_label);
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            final AccessibilityAction clickAction = new AccessibilityAction(
                    AccessibilityAction.ACTION_CLICK.getId(), getClickAccessibilityActionLabel());
            info.addAction(clickAction);
            info.setClickable(true);

            info.addAction(
                    new AccessibilityAction(R.id.accessibility_action_zoom_in,
                            mContext.getString(R.string.accessibility_control_zoom_in)));
            info.addAction(new AccessibilityAction(R.id.accessibility_action_zoom_out,
                    mContext.getString(R.string.accessibility_control_zoom_out)));

            if (!mEditSizeEnable) {
                info.addAction(new AccessibilityAction(R.id.accessibility_action_move_up,
                        mContext.getString(R.string.accessibility_control_move_up)));
                info.addAction(new AccessibilityAction(R.id.accessibility_action_move_down,
                        mContext.getString(R.string.accessibility_control_move_down)));
                info.addAction(new AccessibilityAction(R.id.accessibility_action_move_left,
                        mContext.getString(R.string.accessibility_control_move_left)));
                info.addAction(new AccessibilityAction(R.id.accessibility_action_move_right,
                        mContext.getString(R.string.accessibility_control_move_right)));
            } else {
                if ((mMagnificationFrame.width() + 2 * mMirrorSurfaceMargin)
                        < mWindowBounds.width()) {
                    info.addAction(new AccessibilityAction(
                            R.id.accessibility_action_increase_window_width,
                            mContext.getString(
                                    R.string.accessibility_control_increase_window_width)));
                }
                if ((mMagnificationFrame.height() + 2 * mMirrorSurfaceMargin)
                        < mWindowBounds.height()) {
                    info.addAction(new AccessibilityAction(
                            R.id.accessibility_action_increase_window_height,
                            mContext.getString(
                                    R.string.accessibility_control_increase_window_height)));
                }
                if ((mMagnificationFrame.width() + 2 * mMirrorSurfaceMargin) > mMinWindowSize) {
                    info.addAction(new AccessibilityAction(
                            R.id.accessibility_action_decrease_window_width,
                            mContext.getString(
                                    R.string.accessibility_control_decrease_window_width)));
                }
                if ((mMagnificationFrame.height() + 2 * mMirrorSurfaceMargin) > mMinWindowSize) {
                    info.addAction(new AccessibilityAction(
                            R.id.accessibility_action_decrease_window_height,
                            mContext.getString(
                                    R.string.accessibility_control_decrease_window_height)));
                }
            }

            info.setContentDescription(mContext.getString(R.string.magnification_window_title));
            info.setStateDescription(formatStateDescription(getScale()));
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle args) {
            if (performA11yAction(action)) {
                return true;
            }
            return super.performAccessibilityAction(host, action, args);
        }

        private boolean performA11yAction(int action) {
            final float changeWindowSizeAmount = mContext.getResources().getFraction(
                    R.fraction.magnification_resize_window_size_amount,
                    /* base= */ 1,
                    /* pbase= */ 1);

            if (action == AccessibilityAction.ACTION_CLICK.getId()) {
                if (mEditSizeEnable) {
                    // When edit mode is enabled, click the magnifier to exit edit mode.
                    setEditMagnifierSizeMode(false);
                } else {
                    // Simulate tapping the drag view so it opens the Settings.
                    handleSingleTap(mDragView);
                }

            } else if (action == R.id.accessibility_action_zoom_in) {
                performScale(mScale + A11Y_CHANGE_SCALE_DIFFERENCE);
            } else if (action == R.id.accessibility_action_zoom_out) {
                performScale(mScale - A11Y_CHANGE_SCALE_DIFFERENCE);
            } else if (action == R.id.accessibility_action_move_up) {
                move(0, -mSourceBounds.height());
            } else if (action == R.id.accessibility_action_move_down) {
                move(0, mSourceBounds.height());
            } else if (action == R.id.accessibility_action_move_left) {
                move(-mSourceBounds.width(), 0);
            } else if (action == R.id.accessibility_action_move_right) {
                move(mSourceBounds.width(), 0);
            } else if (action == R.id.accessibility_action_increase_window_width) {
                int newFrameWidth =
                        (int) (mMagnificationFrame.width() * (1 + changeWindowSizeAmount));
                setMagnificationFrameSize(newFrameWidth, mMagnificationFrame.height());
            } else if (action == R.id.accessibility_action_increase_window_height) {
                int newFrameHeight =
                        (int) (mMagnificationFrame.height() * (1 + changeWindowSizeAmount));
                setMagnificationFrameSize(mMagnificationFrame.width(), newFrameHeight);
            } else if (action == R.id.accessibility_action_decrease_window_width) {
                int newFrameWidth =
                        (int) (mMagnificationFrame.width() * (1 - changeWindowSizeAmount));
                setMagnificationFrameSize(newFrameWidth, mMagnificationFrame.height());
            } else if (action == R.id.accessibility_action_decrease_window_height) {
                int newFrameHeight =
                        (int) (mMagnificationFrame.height() * (1 - changeWindowSizeAmount));
                setMagnificationFrameSize(mMagnificationFrame.width(), newFrameHeight);
            } else {
                return false;
            }

            mWindowMagnifierCallback.onAccessibilityActionPerformed(mDisplayId);
            return true;
        }

        private void performScale(float scale) {
            scale = A11Y_ACTION_SCALE_RANGE.clamp(scale);
            mWindowMagnifierCallback.onPerformScaleAction(
                    mDisplayId, scale, /* updatePersistence= */ true);
        }
    }

}