/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.DisplayCutout.BOUNDS_POSITION_BOTTOM;
import static android.view.DisplayCutout.BOUNDS_POSITION_LEFT;
import static android.view.DisplayCutout.BOUNDS_POSITION_LENGTH;
import static android.view.DisplayCutout.BOUNDS_POSITION_RIGHT;
import static android.view.DisplayCutout.BOUNDS_POSITION_TOP;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.Dimension;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings.Secure;
import android.util.DisplayMetrics;
import android.util.DisplayUtils;
import android.util.Log;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayCutout.BoundsPosition;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.RoundedCorners;
import android.view.Surface;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.VisibleForTesting;

import com.android.internal.util.Preconditions;
import com.android.systemui.RegionInterceptingFrameLayout.RegionInterceptableView;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.qs.SecureSetting;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.events.PrivacyDotViewController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.ThreadFactory;
import com.android.systemui.util.settings.SecureSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * An overlay that draws screen decorations in software (e.g for rounded corners or display cutout)
 * for antialiasing and emulation purposes.
 */
@SysUISingleton
public class ScreenDecorations extends SystemUI implements Tunable {
    private static final boolean DEBUG = false;
    private static final String TAG = "ScreenDecorations";

    public static final String SIZE = "sysui_rounded_size";
    public static final String PADDING = "sysui_rounded_content_padding";
    // Provide a way for factory to disable ScreenDecorations to run the Display tests.
    private static final boolean DEBUG_DISABLE_SCREEN_DECORATIONS =
            SystemProperties.getBoolean("debug.disable_screen_decorations", false);
    private static final boolean DEBUG_SCREENSHOT_ROUNDED_CORNERS =
            SystemProperties.getBoolean("debug.screenshot_rounded_corners", false);
    private static final boolean VERBOSE = false;
    private static final boolean DEBUG_COLOR = DEBUG_SCREENSHOT_ROUNDED_CORNERS;

    private DisplayManager mDisplayManager;
    @VisibleForTesting
    protected boolean mIsRegistered;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final Executor mMainExecutor;
    private final TunerService mTunerService;
    private final SecureSettings mSecureSettings;
    private DisplayManager.DisplayListener mDisplayListener;
    private CameraAvailabilityListener mCameraListener;
    private final UserTracker mUserTracker;
    private final PrivacyDotViewController mDotViewController;
    private final ThreadFactory mThreadFactory;

    //TODO: These are piecemeal being updated to Points for now to support non-square rounded
    // corners. for now it is only supposed when reading the intrinsic size from the drawables with
    // mIsRoundedCornerMultipleRadius is set
    @VisibleForTesting
    protected Point mRoundedDefault = new Point(0, 0);
    @VisibleForTesting
    protected Point mRoundedDefaultTop = new Point(0, 0);
    @VisibleForTesting
    protected Point mRoundedDefaultBottom = new Point(0, 0);
    @VisibleForTesting
    protected View[] mOverlays;
    @Nullable
    private DisplayCutoutView[] mCutoutViews;
    //TODO:
    View mTopLeftDot;
    View mTopRightDot;
    View mBottomLeftDot;
    View mBottomRightDot;
    private float mDensity;
    private WindowManager mWindowManager;
    private int mRotation;
    private SecureSetting mColorInversionSetting;
    private DelayableExecutor mExecutor;
    private Handler mHandler;
    private boolean mPendingRotationChange;
    private boolean mIsRoundedCornerMultipleRadius;
    private int mStatusBarHeightPortrait;
    private int mStatusBarHeightLandscape;
    private Drawable mRoundedCornerDrawable;
    private Drawable mRoundedCornerDrawableTop;
    private Drawable mRoundedCornerDrawableBottom;
    private String mDisplayUniqueId;

    private CameraAvailabilityListener.CameraTransitionCallback mCameraTransitionCallback =
            new CameraAvailabilityListener.CameraTransitionCallback() {
        @Override
        public void onApplyCameraProtection(@NonNull Path protectionPath, @NonNull Rect bounds) {
            if (mCutoutViews == null) {
                Log.w(TAG, "DisplayCutoutView do not initialized");
                return;
            }
            // Show the extra protection around the front facing camera if necessary
            for (DisplayCutoutView dcv : mCutoutViews) {
                // Check Null since not all mCutoutViews[pos] be inflated at the meanwhile
                if (dcv != null) {
                    dcv.setProtection(protectionPath, bounds);
                    dcv.setShowProtection(true);
                }
            }
        }

        @Override
        public void onHideCameraProtection() {
            if (mCutoutViews == null) {
                Log.w(TAG, "DisplayCutoutView do not initialized");
                return;
            }
            // Go back to the regular anti-aliasing
            for (DisplayCutoutView dcv : mCutoutViews) {
                // Check Null since not all mCutoutViews[pos] be inflated at the meanwhile
                if (dcv != null) {
                    dcv.setShowProtection(false);
                }
            }
        }
    };

    /**
     * Converts a set of {@link Rect}s into a {@link Region}
     *
     * @hide
     */
    public static Region rectsToRegion(List<Rect> rects) {
        Region result = Region.obtain();
        if (rects != null) {
            for (Rect r : rects) {
                if (r != null && !r.isEmpty()) {
                    result.op(r, Region.Op.UNION);
                }
            }
        }
        return result;
    }

    @Inject
    public ScreenDecorations(Context context,
            @Main Executor mainExecutor,
            SecureSettings secureSettings,
            BroadcastDispatcher broadcastDispatcher,
            TunerService tunerService,
            UserTracker userTracker,
            PrivacyDotViewController dotViewController,
            ThreadFactory threadFactory) {
        super(context);
        mMainExecutor = mainExecutor;
        mSecureSettings = secureSettings;
        mBroadcastDispatcher = broadcastDispatcher;
        mTunerService = tunerService;
        mUserTracker = userTracker;
        mDotViewController = dotViewController;
        mThreadFactory = threadFactory;
    }

    @Override
    public void start() {
        if (DEBUG_DISABLE_SCREEN_DECORATIONS) {
            Log.i(TAG, "ScreenDecorations is disabled");
            return;
        }
        mHandler = mThreadFactory.buildHandlerOnNewThread("ScreenDecorations");
        mExecutor = mThreadFactory.buildDelayableExecutorOnHandler(mHandler);
        mExecutor.execute(this::startOnScreenDecorationsThread);
        mDotViewController.setUiExecutor(mExecutor);
    }

    private void startOnScreenDecorationsThread() {
        mRotation = mContext.getDisplay().getRotation();
        mDisplayUniqueId = mContext.getDisplay().getUniqueId();
        mIsRoundedCornerMultipleRadius = isRoundedCornerMultipleRadius(mContext, mDisplayUniqueId);
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        updateRoundedCornerDrawable();
        updateRoundedCornerRadii();
        setupDecorations();
        setupCameraListener();

        mDisplayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                // do nothing
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                // do nothing
            }

            @Override
            public void onDisplayChanged(int displayId) {
                final int newRotation = mContext.getDisplay().getRotation();
                if (mOverlays != null && mRotation != newRotation) {
                    // We cannot immediately update the orientation. Otherwise
                    // WindowManager is still deferring layout until it has finished dispatching
                    // the config changes, which may cause divergence between what we draw
                    // (new orientation), and where we are placed on the screen (old orientation).
                    // Instead we wait until either:
                    // - we are trying to redraw. This because WM resized our window and told us to.
                    // - the config change has been dispatched, so WM is no longer deferring layout.
                    mPendingRotationChange = true;
                    if (DEBUG) {
                        Log.i(TAG, "Rotation changed, deferring " + newRotation + ", staying at "
                                + mRotation);
                    }

                    for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
                        if (mOverlays[i] != null) {
                            mOverlays[i].getViewTreeObserver().addOnPreDrawListener(
                                    new RestartingPreDrawListener(mOverlays[i], i, newRotation));
                        }
                    }
                }
                final String newUniqueId = mContext.getDisplay().getUniqueId();
                if ((newUniqueId != null && !newUniqueId.equals(mDisplayUniqueId))
                        || (mDisplayUniqueId != null && !mDisplayUniqueId.equals(newUniqueId))) {
                    mDisplayUniqueId = newUniqueId;
                    mIsRoundedCornerMultipleRadius =
                            isRoundedCornerMultipleRadius(mContext, mDisplayUniqueId);
                    updateRoundedCornerDrawable();
                }
                updateOrientation();
            }
        };

        mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
        updateOrientation();
    }

    private void setupDecorations() {
        if (hasRoundedCorners() || shouldDrawCutout()) {
            updateStatusBarHeight();
            final DisplayCutout cutout = getCutout();
            final Rect[] bounds = cutout == null ? null : cutout.getBoundingRectsAll();
            int rotatedPos;
            for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
                rotatedPos = getBoundPositionFromRotation(i, mRotation);
                if ((bounds != null && !bounds[rotatedPos].isEmpty())
                        || shouldShowRoundedCorner(i)) {
                    createOverlay(i);
                } else {
                    removeOverlay(i);
                }
            }
            // Overlays have been created, send the dots to the controller
            //TODO: need a better way to do this
            mDotViewController.initialize(
                    mTopLeftDot, mTopRightDot, mBottomLeftDot, mBottomRightDot);
        } else {
            removeAllOverlays();
        }

        if (hasOverlays()) {
            if (mIsRegistered) {
                return;
            }
            DisplayMetrics metrics = new DisplayMetrics();
            mDisplayManager.getDisplay(DEFAULT_DISPLAY).getMetrics(metrics);
            mDensity = metrics.density;

            mExecutor.execute(() -> mTunerService.addTunable(this, SIZE));

            // Watch color inversion and invert the overlay as needed.
            if (mColorInversionSetting == null) {
                mColorInversionSetting = new SecureSetting(mSecureSettings, mHandler,
                        Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED,
                        mUserTracker.getUserId()) {
                    @Override
                    protected void handleValueChanged(int value, boolean observedChange) {
                        updateColorInversion(value);
                    }
                };
            }
            mColorInversionSetting.setListening(true);
            mColorInversionSetting.onChange(false);

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_USER_SWITCHED);
            mBroadcastDispatcher.registerReceiver(mUserSwitchIntentReceiver, filter,
                    mExecutor, UserHandle.ALL);
            mIsRegistered = true;
        } else {
            mMainExecutor.execute(() -> mTunerService.removeTunable(this));

            if (mColorInversionSetting != null) {
                mColorInversionSetting.setListening(false);
            }

            mBroadcastDispatcher.unregisterReceiver(mUserSwitchIntentReceiver);
            mIsRegistered = false;
        }
    }

    @VisibleForTesting
    DisplayCutout getCutout() {
        return mContext.getDisplay().getCutout();
    }

    @VisibleForTesting
    boolean hasOverlays() {
        if (mOverlays == null) {
            return false;
        }

        for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
            if (mOverlays[i] != null) {
                return true;
            }
        }
        mOverlays = null;
        return false;
    }

    private void removeAllOverlays() {
        if (mOverlays == null) {
            return;
        }

        for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
            if (mOverlays[i] != null) {
                removeOverlay(i);
            }
        }
        mOverlays = null;
    }

    private void removeOverlay(@BoundsPosition int pos) {
        if (mOverlays == null || mOverlays[pos] == null) {
            return;
        }
        mWindowManager.removeViewImmediate(mOverlays[pos]);
        mOverlays[pos] = null;
    }

    private void createOverlay(@BoundsPosition int pos) {
        if (mOverlays == null) {
            mOverlays = new View[BOUNDS_POSITION_LENGTH];
        }

        if (mCutoutViews == null) {
            mCutoutViews = new DisplayCutoutView[BOUNDS_POSITION_LENGTH];
        }

        if (mOverlays[pos] != null) {
            return;
        }
        mOverlays[pos] = overlayForPosition(pos);

        mCutoutViews[pos] = new DisplayCutoutView(mContext, pos, this);
        ((ViewGroup) mOverlays[pos]).addView(mCutoutViews[pos]);

        mOverlays[pos].setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        mOverlays[pos].setAlpha(0);
        mOverlays[pos].setForceDarkAllowed(false);

        updateView(pos);

        mWindowManager.addView(mOverlays[pos], getWindowLayoutParams(pos));

        mOverlays[pos].addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                mOverlays[pos].removeOnLayoutChangeListener(this);
                mOverlays[pos].animate()
                        .alpha(1)
                        .setDuration(1000)
                        .start();
            }
        });

        mOverlays[pos].getViewTreeObserver().addOnPreDrawListener(
                new ValidatingPreDrawListener(mOverlays[pos]));
    }

    /**
     * Allow overrides for top/bottom positions
     */
    private View overlayForPosition(@BoundsPosition int pos) {
        switch (pos) {
            case BOUNDS_POSITION_TOP:
            case BOUNDS_POSITION_LEFT:
                View top = LayoutInflater.from(mContext)
                        .inflate(R.layout.rounded_corners_top, null);
                mTopLeftDot = top.findViewById(R.id.privacy_dot_left_container);
                mTopRightDot = top.findViewById(R.id.privacy_dot_right_container);
                return top;
            case BOUNDS_POSITION_BOTTOM:
            case BOUNDS_POSITION_RIGHT:
                View bottom =  LayoutInflater.from(mContext)
                        .inflate(R.layout.rounded_corners_bottom, null);
                mBottomLeftDot = bottom.findViewById(R.id.privacy_dot_left_container);
                mBottomRightDot = bottom.findViewById(R.id.privacy_dot_right_container);
                return bottom;
            default:
                throw new IllegalArgumentException("Unknown bounds position");
        }
    }

    private void updateView(@BoundsPosition int pos) {
        if (mOverlays == null || mOverlays[pos] == null) {
            return;
        }

        // update rounded corner view rotation
        updateRoundedCornerView(pos, R.id.left);
        updateRoundedCornerView(pos, R.id.right);
        updateRoundedCornerSize(mRoundedDefault, mRoundedDefaultTop, mRoundedDefaultBottom);
        updateRoundedCornerImageView();

        // update cutout view rotation
        if (mCutoutViews != null && mCutoutViews[pos] != null) {
            mCutoutViews[pos].setRotation(mRotation);
        }
    }

    @VisibleForTesting
    WindowManager.LayoutParams getWindowLayoutParams(@BoundsPosition int pos) {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                getWidthLayoutParamByPos(pos),
                getHeightLayoutParamByPos(pos),
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_SLIPPERY
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                | WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;

        if (!DEBUG_SCREENSHOT_ROUNDED_CORNERS) {
            lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;
        }

        lp.setTitle(getWindowTitleByPos(pos));
        lp.gravity = getOverlayWindowGravity(pos);
        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setFitInsetsTypes(0 /* types */);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;
        return lp;
    }

    private int getWidthLayoutParamByPos(@BoundsPosition int pos) {
        final int rotatedPos = getBoundPositionFromRotation(pos, mRotation);
        return rotatedPos == BOUNDS_POSITION_TOP || rotatedPos == BOUNDS_POSITION_BOTTOM
                ? MATCH_PARENT : WRAP_CONTENT;
    }

    private int getHeightLayoutParamByPos(@BoundsPosition int pos) {
        final int rotatedPos = getBoundPositionFromRotation(pos, mRotation);
        return rotatedPos == BOUNDS_POSITION_TOP || rotatedPos == BOUNDS_POSITION_BOTTOM
                ? WRAP_CONTENT : MATCH_PARENT;
    }

    private static String getWindowTitleByPos(@BoundsPosition int pos) {
        switch (pos) {
            case BOUNDS_POSITION_LEFT:
                return "ScreenDecorOverlayLeft";
            case BOUNDS_POSITION_TOP:
                return "ScreenDecorOverlay";
            case BOUNDS_POSITION_RIGHT:
                return "ScreenDecorOverlayRight";
            case BOUNDS_POSITION_BOTTOM:
                return "ScreenDecorOverlayBottom";
            default:
                throw new IllegalArgumentException("unknown bound position: " + pos);
        }
    }

    private int getOverlayWindowGravity(@BoundsPosition int pos) {
        final int rotated = getBoundPositionFromRotation(pos, mRotation);
        switch (rotated) {
            case BOUNDS_POSITION_TOP:
                return Gravity.TOP;
            case BOUNDS_POSITION_BOTTOM:
                return Gravity.BOTTOM;
            case BOUNDS_POSITION_LEFT:
                return Gravity.LEFT;
            case BOUNDS_POSITION_RIGHT:
                return Gravity.RIGHT;
            default:
                throw new IllegalArgumentException("unknown bound position: " + pos);
        }
    }

    @VisibleForTesting
    static int getBoundPositionFromRotation(@BoundsPosition int pos, int rotation) {
        return (pos - rotation) < 0
                ? pos - rotation + DisplayCutout.BOUNDS_POSITION_LENGTH
                : pos - rotation;
    }

    private void setupCameraListener() {
        Resources res = mContext.getResources();
        boolean enabled = res.getBoolean(R.bool.config_enableDisplayCutoutProtection);
        if (enabled) {
            mCameraListener = CameraAvailabilityListener.Factory.build(mContext, mExecutor);
            mCameraListener.addTransitionCallback(mCameraTransitionCallback);
            mCameraListener.startListening();
        }
    }

    private final BroadcastReceiver mUserSwitchIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int newUserId = ActivityManager.getCurrentUser();
            if (DEBUG) {
                Log.d(TAG, "UserSwitched newUserId=" + newUserId);
            }
            // update color inversion setting to the new user
            mColorInversionSetting.setUserId(newUserId);
            updateColorInversion(mColorInversionSetting.getValue());
        }
    };

    private void updateColorInversion(int colorsInvertedValue) {
        int tint = colorsInvertedValue != 0 ? Color.WHITE : Color.BLACK;
        if (DEBUG_COLOR) {
            tint = Color.RED;
        }
        ColorStateList tintList = ColorStateList.valueOf(tint);

        if (mOverlays == null) {
            return;
        }
        for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
            if (mOverlays[i] == null) {
                continue;
            }
            final int size = ((ViewGroup) mOverlays[i]).getChildCount();
            View child;
            for (int j = 0; j < size; j++) {
                child = ((ViewGroup) mOverlays[i]).getChildAt(j);
                if (child.getId() == R.id.privacy_dot_left_container
                        || child.getId() == R.id.privacy_dot_right_container) {
                    // Exclude privacy dot from color inversion (for now?)
                    continue;
                }
                if (child instanceof ImageView) {
                    ((ImageView) child).setImageTintList(tintList);
                } else if (child instanceof DisplayCutoutView) {
                    ((DisplayCutoutView) child).setColor(tint);
                }
            }
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        if (DEBUG_DISABLE_SCREEN_DECORATIONS) {
            Log.i(TAG, "ScreenDecorations is disabled");
            return;
        }
        mExecutor.execute(() -> {
            int oldRotation = mRotation;
            mPendingRotationChange = false;
            updateOrientation();
            updateRoundedCornerRadii();
            if (DEBUG) Log.i(TAG, "onConfigChanged from rot " + oldRotation + " to " + mRotation);
            setupDecorations();
            if (mOverlays != null) {
                // Updating the layout params ensures that ViewRootImpl will call relayoutWindow(),
                // which ensures that the forced seamless rotation will end, even if we updated
                // the rotation before window manager was ready (and was still waiting for sending
                // the updated rotation).
                updateLayoutParams();
            }
        });
    }

    private void updateOrientation() {
        Preconditions.checkState(mHandler.getLooper().getThread() == Thread.currentThread(),
                "must call on " + mHandler.getLooper().getThread()
                        + ", but was " + Thread.currentThread());

        int newRotation = mContext.getDisplay().getRotation();
        if (mRotation != newRotation) {
            mDotViewController.setNewRotation(newRotation);
        }

        if (mPendingRotationChange) {
            return;
        }
        if (newRotation != mRotation) {
            mRotation = newRotation;

            if (mOverlays != null) {
                updateLayoutParams();
                for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
                    if (mOverlays[i] == null) {
                        continue;
                    }
                    updateView(i);
                }
            }
        }
    }

    private void updateStatusBarHeight() {
        mStatusBarHeightLandscape = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height_landscape);
        mStatusBarHeightPortrait = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height_portrait);
        mDotViewController.setStatusBarHeights(mStatusBarHeightPortrait, mStatusBarHeightLandscape);
    }

    private void updateRoundedCornerRadii() {
        // We should eventually move to just using the intrinsic size of the drawables since
        // they should be sized to the exact pixels they want to cover. Therefore I'm purposely not
        // upgrading all of the configs to contain (width, height) pairs. Instead assume that a
        // device configured using the single integer config value is okay with drawing the corners
        // as a square
        final int newRoundedDefault = RoundedCorners.getRoundedCornerRadius(
                mContext.getResources(), mDisplayUniqueId);
        final int newRoundedDefaultTop = RoundedCorners.getRoundedCornerTopRadius(
                mContext.getResources(), mDisplayUniqueId);
        final int newRoundedDefaultBottom = RoundedCorners.getRoundedCornerBottomRadius(
                mContext.getResources(), mDisplayUniqueId);

        final boolean changed = mRoundedDefault.x != newRoundedDefault
                        || mRoundedDefaultTop.x != newRoundedDefaultTop
                        || mRoundedDefaultBottom.x != newRoundedDefaultBottom;
        if (changed) {
            // If config_roundedCornerMultipleRadius set as true, ScreenDecorations respect the
            // (width, height) size of drawable/rounded.xml instead of rounded_corner_radius
            if (mIsRoundedCornerMultipleRadius) {
                mRoundedDefault.set(mRoundedCornerDrawable.getIntrinsicWidth(),
                        mRoundedCornerDrawable.getIntrinsicHeight());
                mRoundedDefaultTop.set(mRoundedCornerDrawableTop.getIntrinsicWidth(),
                        mRoundedCornerDrawableTop.getIntrinsicHeight());
                mRoundedDefaultBottom.set(mRoundedCornerDrawableBottom.getIntrinsicWidth(),
                        mRoundedCornerDrawableBottom.getIntrinsicHeight());
            } else {
                mRoundedDefault.set(newRoundedDefault, newRoundedDefault);
                mRoundedDefaultTop.set(newRoundedDefaultTop, newRoundedDefaultTop);
                mRoundedDefaultBottom.set(newRoundedDefaultBottom, newRoundedDefaultBottom);
            }
            onTuningChanged(SIZE, null);
        }
    }

    /**
     * Gets whether the rounded corners are multiple radii for current display.
     *
     * Loads the default config {@link R.bool#config_roundedCornerMultipleRadius} if
     * {@link com.android.internal.R.array#config_displayUniqueIdArray} is not set.
     */
    private static boolean isRoundedCornerMultipleRadius(Context context, String displayUniqueId) {
        final Resources res = context.getResources();
        final int index = DisplayUtils.getDisplayUniqueIdConfigIndex(res, displayUniqueId);
        final TypedArray array = res.obtainTypedArray(
                R.array.config_roundedCornerMultipleRadiusArray);
        boolean isMultipleRadius;
        if (index >= 0 && index < array.length()) {
            isMultipleRadius = array.getBoolean(index, false);
        } else {
            isMultipleRadius = res.getBoolean(R.bool.config_roundedCornerMultipleRadius);
        }
        array.recycle();
        return isMultipleRadius;
    }

    /**
     * Gets the rounded corner drawable for current display.
     *
     * Loads the default config {@link R.drawable#rounded} if
     * {@link com.android.internal.R.array#config_displayUniqueIdArray} is not set.
     */
    private static Drawable getRoundedCornerDrawable(Context context, String displayUniqueId) {
        final Resources res = context.getResources();
        final int index = DisplayUtils.getDisplayUniqueIdConfigIndex(res, displayUniqueId);
        final TypedArray array = res.obtainTypedArray(R.array.config_roundedCornerDrawableArray);
        Drawable drawable;
        if (index >= 0 && index < array.length()) {
            drawable = array.getDrawable(index);
        } else {
            drawable = context.getDrawable(R.drawable.rounded);
        }
        array.recycle();
        return drawable;
    }

    /**
     * Gets the rounded corner top drawable for current display.
     *
     * Loads the default config {@link R.drawable#rounded_corner_top} if
     * {@link com.android.internal.R.array#config_displayUniqueIdArray} is not set.
     */
    private static Drawable getRoundedCornerTopDrawable(Context context, String displayUniqueId) {
        final Resources res = context.getResources();
        final int index = DisplayUtils.getDisplayUniqueIdConfigIndex(res, displayUniqueId);
        final TypedArray array = res.obtainTypedArray(R.array.config_roundedCornerTopDrawableArray);
        Drawable drawable;
        if (index >= 0 && index < array.length()) {
            drawable = array.getDrawable(index);
        } else {
            drawable = context.getDrawable(R.drawable.rounded_corner_top);
        }
        array.recycle();
        return drawable;
    }

    /**
     * Gets the rounded corner bottom drawable for current display.
     *
     * Loads the default config {@link R.drawable#rounded_corner_bottom} if
     * {@link com.android.internal.R.array#config_displayUniqueIdArray} is not set.
     */
    private static Drawable getRoundedCornerBottomDrawable(
            Context context, String displayUniqueId) {
        final Resources res = context.getResources();
        final int index = DisplayUtils.getDisplayUniqueIdConfigIndex(res, displayUniqueId);
        final TypedArray array = res.obtainTypedArray(
                R.array.config_roundedCornerBottomDrawableArray);
        Drawable drawable;
        if (index >= 0 && index < array.length()) {
            drawable = array.getDrawable(index);
        } else {
            drawable = context.getDrawable(R.drawable.rounded_corner_bottom);
        }
        array.recycle();
        return drawable;
    }

    private void updateRoundedCornerView(@BoundsPosition int pos, int id) {
        final View rounded = mOverlays[pos].findViewById(id);
        if (rounded == null) {
            return;
        }
        rounded.setVisibility(View.GONE);
        if (shouldShowRoundedCorner(pos)) {
            final int gravity = getRoundedCornerGravity(pos, id == R.id.left);
            ((FrameLayout.LayoutParams) rounded.getLayoutParams()).gravity = gravity;
            setRoundedCornerOrientation(rounded, gravity);
            rounded.setVisibility(View.VISIBLE);
        }
    }

    private int getRoundedCornerGravity(@BoundsPosition int pos, boolean isStart) {
        final int rotatedPos = getBoundPositionFromRotation(pos, mRotation);
        switch (rotatedPos) {
            case BOUNDS_POSITION_LEFT:
                return isStart ? Gravity.TOP | Gravity.LEFT : Gravity.BOTTOM | Gravity.LEFT;
            case BOUNDS_POSITION_TOP:
                return isStart ? Gravity.TOP | Gravity.LEFT : Gravity.TOP | Gravity.RIGHT;
            case BOUNDS_POSITION_RIGHT:
                return isStart ? Gravity.TOP | Gravity.RIGHT : Gravity.BOTTOM | Gravity.RIGHT;
            case BOUNDS_POSITION_BOTTOM:
                return isStart ? Gravity.BOTTOM | Gravity.LEFT : Gravity.BOTTOM | Gravity.RIGHT;
            default:
                throw new IllegalArgumentException("Incorrect position: " + rotatedPos);
        }
    }

    /**
     * Configures the rounded corner drawable's view matrix based on the gravity.
     *
     * The gravity describes which corner to configure for, and the drawable we are rotating is
     * assumed to be oriented for the top-left corner of the device regardless of the target corner.
     * Therefore we need to rotate 180 degrees to get a bottom-left corner, and mirror in the x- or
     * y-axis for the top-right and bottom-left corners.
     */
    private void setRoundedCornerOrientation(View corner, int gravity) {
        corner.setRotation(0);
        corner.setScaleX(1);
        corner.setScaleY(1);
        switch (gravity) {
            case Gravity.TOP | Gravity.LEFT:
                return;
            case Gravity.TOP | Gravity.RIGHT:
                corner.setScaleX(-1); // flip X axis
                return;
            case Gravity.BOTTOM | Gravity.LEFT:
                corner.setScaleY(-1); // flip Y axis
                return;
            case Gravity.BOTTOM | Gravity.RIGHT:
                corner.setRotation(180);
                return;
            default:
                throw new IllegalArgumentException("Unsupported gravity: " + gravity);
        }
    }
    private boolean hasRoundedCorners() {
        return mRoundedDefault.x > 0
                || mRoundedDefaultBottom.x > 0
                || mRoundedDefaultTop.x > 0
                || mIsRoundedCornerMultipleRadius;
    }

    private boolean shouldShowRoundedCorner(@BoundsPosition int pos) {
        if (!hasRoundedCorners()) {
            return false;
        }

        DisplayCutout cutout = getCutout();
        // for cutout is null or cutout with only waterfall.
        final boolean emptyBoundsOrWaterfall = cutout == null || cutout.isBoundsEmpty();
        // Shows rounded corner on left and right overlays only when there is no top or bottom
        // cutout.
        final int rotatedTop = getBoundPositionFromRotation(BOUNDS_POSITION_TOP, mRotation);
        final int rotatedBottom = getBoundPositionFromRotation(BOUNDS_POSITION_BOTTOM, mRotation);
        if (emptyBoundsOrWaterfall || !cutout.getBoundingRectsAll()[rotatedTop].isEmpty()
                || !cutout.getBoundingRectsAll()[rotatedBottom].isEmpty()) {
            return pos == BOUNDS_POSITION_TOP || pos == BOUNDS_POSITION_BOTTOM;
        } else {
            return pos == BOUNDS_POSITION_LEFT || pos == BOUNDS_POSITION_RIGHT;
        }
    }

    private boolean shouldDrawCutout() {
        return shouldDrawCutout(mContext);
    }

    static boolean shouldDrawCutout(Context context) {
        return DisplayCutout.getFillBuiltInDisplayCutout(
                context.getResources(), context.getDisplay().getUniqueId());
    }

    private void updateLayoutParams() {
        if (mOverlays == null) {
            return;
        }
        for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
            if (mOverlays[i] == null) {
                continue;
            }
            mWindowManager.updateViewLayout(mOverlays[i], getWindowLayoutParams(i));
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (DEBUG_DISABLE_SCREEN_DECORATIONS) {
            Log.i(TAG, "ScreenDecorations is disabled");
            return;
        }
        mExecutor.execute(() -> {
            if (mOverlays == null) return;
            if (SIZE.equals(key)) {
                Point size = mRoundedDefault;
                Point sizeTop = mRoundedDefaultTop;
                Point sizeBottom = mRoundedDefaultBottom;
                if (newValue != null) {
                    try {
                        int s = (int) (Integer.parseInt(newValue) * mDensity);
                        size = new Point(s, s);
                    } catch (Exception e) {
                    }
                }
                updateRoundedCornerSize(size, sizeTop, sizeBottom);
            }
        });
    }

    private void updateRoundedCornerDrawable() {
        mRoundedCornerDrawable = getRoundedCornerDrawable(mContext, mDisplayUniqueId);
        mRoundedCornerDrawableTop = getRoundedCornerTopDrawable(mContext, mDisplayUniqueId);
        mRoundedCornerDrawableBottom = getRoundedCornerBottomDrawable(mContext, mDisplayUniqueId);
        updateRoundedCornerImageView();
    }

    private void updateRoundedCornerImageView() {
        final Drawable top = mRoundedCornerDrawableTop != null
                ? mRoundedCornerDrawableTop : mRoundedCornerDrawable;
        final Drawable bottom = mRoundedCornerDrawableBottom != null
                ? mRoundedCornerDrawableBottom : mRoundedCornerDrawable;

        if (mOverlays == null) {
            return;
        }
        for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
            if (mOverlays[i] == null) {
                continue;
            }
            ((ImageView) mOverlays[i].findViewById(R.id.left)).setImageDrawable(
                    isTopRoundedCorner(i, R.id.left) ? top : bottom);
            ((ImageView) mOverlays[i].findViewById(R.id.right)).setImageDrawable(
                    isTopRoundedCorner(i, R.id.right) ? top : bottom);
        }
    }

    @VisibleForTesting
    boolean isTopRoundedCorner(@BoundsPosition int pos, int id) {
        switch (pos) {
            case BOUNDS_POSITION_LEFT:
            case BOUNDS_POSITION_RIGHT:
                if (mRotation == ROTATION_270) {
                    return id == R.id.left ? false : true;
                } else {
                    return id == R.id.left ? true : false;
                }
            case BOUNDS_POSITION_TOP:
                return true;
            case BOUNDS_POSITION_BOTTOM:
                return false;
            default:
                throw new IllegalArgumentException("Unknown bounds position");
        }
    }

    private void updateRoundedCornerSize(
            Point sizeDefault,
            Point sizeTop,
            Point sizeBottom) {
        if (mOverlays == null) {
            return;
        }
        if (sizeTop.x == 0) {
            sizeTop = sizeDefault;
        }
        if (sizeBottom.x == 0) {
            sizeBottom = sizeDefault;
        }

        for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
            if (mOverlays[i] == null) {
                continue;
            }
            setSize(mOverlays[i].findViewById(R.id.left),
                    isTopRoundedCorner(i, R.id.left) ? sizeTop : sizeBottom);
            setSize(mOverlays[i].findViewById(R.id.right),
                    isTopRoundedCorner(i, R.id.right) ? sizeTop : sizeBottom);
        }
    }

    @VisibleForTesting
    protected void setSize(View view, Point pixelSize) {
        LayoutParams params = view.getLayoutParams();
        params.width = pixelSize.x;
        params.height = pixelSize.y;
        view.setLayoutParams(params);
    }

    public static class DisplayCutoutView extends View implements DisplayManager.DisplayListener,
            RegionInterceptableView {

        private static final float HIDDEN_CAMERA_PROTECTION_SCALE = 0.5f;

        private Display.Mode mDisplayMode = null;
        private final DisplayInfo mInfo = new DisplayInfo();
        private final Paint mPaint = new Paint();
        private final List<Rect> mBounds = new ArrayList();
        private final Rect mBoundingRect = new Rect();
        private final Path mBoundingPath = new Path();
        // Don't initialize these yet because they may never exist
        private RectF mProtectionRect;
        private RectF mProtectionRectOrig;
        private Path mProtectionPath;
        private Path mProtectionPathOrig;
        private Rect mTotalBounds = new Rect();
        // Whether or not to show the cutout protection path
        private boolean mShowProtection = false;

        private final int[] mLocation = new int[2];
        private final ScreenDecorations mDecorations;
        private int mColor = Color.BLACK;
        private int mRotation;
        private int mInitialPosition;
        private int mPosition;
        private float mCameraProtectionProgress = HIDDEN_CAMERA_PROTECTION_SCALE;
        private ValueAnimator mCameraProtectionAnimator;

        public DisplayCutoutView(Context context, @BoundsPosition int pos,
                ScreenDecorations decorations) {
            super(context);
            mInitialPosition = pos;
            mDecorations = decorations;
            setId(R.id.display_cutout);
            if (DEBUG) {
                getViewTreeObserver().addOnDrawListener(() -> Log.i(TAG,
                        getWindowTitleByPos(pos) + " drawn in rot " + mRotation));
            }
        }

        public void setColor(int color) {
            mColor = color;
            invalidate();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            mContext.getSystemService(DisplayManager.class).registerDisplayListener(this,
                    getHandler());
            update();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            mContext.getSystemService(DisplayManager.class).unregisterDisplayListener(this);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            getLocationOnScreen(mLocation);
            canvas.translate(-mLocation[0], -mLocation[1]);

            if (!mBoundingPath.isEmpty()) {
                mPaint.setColor(mColor);
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setAntiAlias(true);
                canvas.drawPath(mBoundingPath, mPaint);
            }
            if (mCameraProtectionProgress > HIDDEN_CAMERA_PROTECTION_SCALE
                    && !mProtectionRect.isEmpty()) {
                canvas.scale(mCameraProtectionProgress, mCameraProtectionProgress,
                        mProtectionRect.centerX(), mProtectionRect.centerY());
                canvas.drawPath(mProtectionPath, mPaint);
            }
        }

        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            Display.Mode oldMode = mDisplayMode;
            mDisplayMode = getDisplay().getMode();

            // Display mode hasn't meaningfully changed, we can ignore it
            if (!modeChanged(oldMode, mDisplayMode)) {
                return;
            }

            if (displayId == getDisplay().getDisplayId()) {
                update();
            }
        }

        private boolean modeChanged(Display.Mode oldMode, Display.Mode newMode) {
            if (oldMode == null) {
                return true;
            }

            boolean changed = false;
            changed |= oldMode.getPhysicalHeight() != newMode.getPhysicalHeight();
            changed |= oldMode.getPhysicalWidth() != newMode.getPhysicalWidth();
            // We purposely ignore refresh rate and id changes here, because we don't need to
            // invalidate for those, and they can trigger the refresh rate to increase

            return changed;
        }

        public void setRotation(int rotation) {
            mRotation = rotation;
            update();
        }

        void setProtection(Path protectionPath, Rect pathBounds) {
            if (mProtectionPathOrig == null) {
                mProtectionPathOrig = new Path();
                mProtectionPath = new Path();
            }
            mProtectionPathOrig.set(protectionPath);
            if (mProtectionRectOrig == null) {
                mProtectionRectOrig = new RectF();
                mProtectionRect = new RectF();
            }
            mProtectionRectOrig.set(pathBounds);
        }

        void setShowProtection(boolean shouldShow) {
            if (mShowProtection == shouldShow) {
                return;
            }

            mShowProtection = shouldShow;
            updateBoundingPath();
            // Delay the relayout until the end of the animation when hiding the cutout,
            // otherwise we'd clip it.
            if (mShowProtection) {
                requestLayout();
            }
            if (mCameraProtectionAnimator != null) {
                mCameraProtectionAnimator.cancel();
            }
            mCameraProtectionAnimator = ValueAnimator.ofFloat(mCameraProtectionProgress,
                    mShowProtection ? 1.0f : HIDDEN_CAMERA_PROTECTION_SCALE).setDuration(750);
            mCameraProtectionAnimator.setInterpolator(Interpolators.DECELERATE_QUINT);
            mCameraProtectionAnimator.addUpdateListener(animation -> {
                mCameraProtectionProgress = (float) animation.getAnimatedValue();
                invalidate();
            });
            mCameraProtectionAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mCameraProtectionAnimator = null;
                    if (!mShowProtection) {
                        requestLayout();
                    }
                }
            });
            mCameraProtectionAnimator.start();
        }

        private void update() {
            if (!isAttachedToWindow() || mDecorations.mPendingRotationChange) {
                return;
            }
            mPosition = getBoundPositionFromRotation(mInitialPosition, mRotation);
            requestLayout();
            getDisplay().getDisplayInfo(mInfo);
            mBounds.clear();
            mBoundingRect.setEmpty();
            mBoundingPath.reset();
            int newVisible;
            if (shouldDrawCutout(getContext()) && hasCutout()) {
                mBounds.addAll(mInfo.displayCutout.getBoundingRects());
                localBounds(mBoundingRect);
                updateGravity();
                updateBoundingPath();
                invalidate();
                newVisible = VISIBLE;
            } else {
                newVisible = GONE;
            }
            if (newVisible != getVisibility()) {
                setVisibility(newVisible);
            }
        }

        private void updateBoundingPath() {
            int lw = mInfo.logicalWidth;
            int lh = mInfo.logicalHeight;

            boolean flipped = mInfo.rotation == ROTATION_90 || mInfo.rotation == ROTATION_270;

            int dw = flipped ? lh : lw;
            int dh = flipped ? lw : lh;

            Path path = DisplayCutout.pathFromResources(
                    getResources(), getDisplay().getUniqueId(), dw, dh);
            if (path != null) {
                mBoundingPath.set(path);
            } else {
                mBoundingPath.reset();
            }
            Matrix m = new Matrix();
            transformPhysicalToLogicalCoordinates(mInfo.rotation, dw, dh, m);
            mBoundingPath.transform(m);
            if (mProtectionPathOrig != null) {
                // Reset the protection path so we don't aggregate rotations
                mProtectionPath.set(mProtectionPathOrig);
                mProtectionPath.transform(m);
                m.mapRect(mProtectionRect, mProtectionRectOrig);
            }
        }

        private static void transformPhysicalToLogicalCoordinates(@Surface.Rotation int rotation,
                @Dimension int physicalWidth, @Dimension int physicalHeight, Matrix out) {
            switch (rotation) {
                case ROTATION_0:
                    out.reset();
                    break;
                case ROTATION_90:
                    out.setRotate(270);
                    out.postTranslate(0, physicalWidth);
                    break;
                case ROTATION_180:
                    out.setRotate(180);
                    out.postTranslate(physicalWidth, physicalHeight);
                    break;
                case ROTATION_270:
                    out.setRotate(90);
                    out.postTranslate(physicalHeight, 0);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown rotation: " + rotation);
            }
        }

        private void updateGravity() {
            LayoutParams lp = getLayoutParams();
            if (lp instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
                int newGravity = getGravity(mInfo.displayCutout);
                if (flp.gravity != newGravity) {
                    flp.gravity = newGravity;
                    setLayoutParams(flp);
                }
            }
        }

        private boolean hasCutout() {
            final DisplayCutout displayCutout = mInfo.displayCutout;
            if (displayCutout == null) {
                return false;
            }

            if (mPosition == BOUNDS_POSITION_LEFT) {
                return !displayCutout.getBoundingRectLeft().isEmpty();
            } else if (mPosition == BOUNDS_POSITION_TOP) {
                return !displayCutout.getBoundingRectTop().isEmpty();
            } else if (mPosition == BOUNDS_POSITION_BOTTOM) {
                return !displayCutout.getBoundingRectBottom().isEmpty();
            } else if (mPosition == BOUNDS_POSITION_RIGHT) {
                return !displayCutout.getBoundingRectRight().isEmpty();
            }
            return false;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (mBounds.isEmpty()) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
            }

            if (mShowProtection) {
                // Make sure that our measured height encompases the protection
                mTotalBounds.union(mBoundingRect);
                mTotalBounds.union((int) mProtectionRect.left, (int) mProtectionRect.top,
                        (int) mProtectionRect.right, (int) mProtectionRect.bottom);
                setMeasuredDimension(
                        resolveSizeAndState(mTotalBounds.width(), widthMeasureSpec, 0),
                        resolveSizeAndState(mTotalBounds.height(), heightMeasureSpec, 0));
            } else {
                setMeasuredDimension(
                        resolveSizeAndState(mBoundingRect.width(), widthMeasureSpec, 0),
                        resolveSizeAndState(mBoundingRect.height(), heightMeasureSpec, 0));
            }
        }

        public static void boundsFromDirection(DisplayCutout displayCutout, int gravity,
                Rect out) {
            switch (gravity) {
                case Gravity.TOP:
                    out.set(displayCutout.getBoundingRectTop());
                    break;
                case Gravity.LEFT:
                    out.set(displayCutout.getBoundingRectLeft());
                    break;
                case Gravity.BOTTOM:
                    out.set(displayCutout.getBoundingRectBottom());
                    break;
                case Gravity.RIGHT:
                    out.set(displayCutout.getBoundingRectRight());
                    break;
                default:
                    out.setEmpty();
            }
        }

        private void localBounds(Rect out) {
            DisplayCutout displayCutout = mInfo.displayCutout;
            boundsFromDirection(displayCutout, getGravity(displayCutout), out);
        }

        private int getGravity(DisplayCutout displayCutout) {
            if (mPosition == BOUNDS_POSITION_LEFT) {
                if (!displayCutout.getBoundingRectLeft().isEmpty()) {
                    return Gravity.LEFT;
                }
            } else if (mPosition == BOUNDS_POSITION_TOP) {
                if (!displayCutout.getBoundingRectTop().isEmpty()) {
                    return Gravity.TOP;
                }
            } else if (mPosition == BOUNDS_POSITION_BOTTOM) {
                if (!displayCutout.getBoundingRectBottom().isEmpty()) {
                    return Gravity.BOTTOM;
                }
            } else if (mPosition == BOUNDS_POSITION_RIGHT) {
                if (!displayCutout.getBoundingRectRight().isEmpty()) {
                    return Gravity.RIGHT;
                }
            }
            return Gravity.NO_GRAVITY;
        }

        @Override
        public boolean shouldInterceptTouch() {
            return mInfo.displayCutout != null && getVisibility() == VISIBLE;
        }

        @Override
        public Region getInterceptRegion() {
            if (mInfo.displayCutout == null) {
                return null;
            }

            View rootView = getRootView();
            Region cutoutBounds = rectsToRegion(
                    mInfo.displayCutout.getBoundingRects());

            // Transform to window's coordinate space
            rootView.getLocationOnScreen(mLocation);
            cutoutBounds.translate(-mLocation[0], -mLocation[1]);

            // Intersect with window's frame
            cutoutBounds.op(rootView.getLeft(), rootView.getTop(), rootView.getRight(),
                    rootView.getBottom(), Region.Op.INTERSECT);

            return cutoutBounds;
        }
    }

    /**
     * A pre-draw listener, that cancels the draw and restarts the traversal with the updated
     * window attributes.
     */
    private class RestartingPreDrawListener implements ViewTreeObserver.OnPreDrawListener {

        private final View mView;
        private final int mTargetRotation;
        private final int mPosition;

        private RestartingPreDrawListener(View view, @BoundsPosition int position,
                int targetRotation) {
            mView = view;
            mTargetRotation = targetRotation;
            mPosition = position;
        }

        @Override
        public boolean onPreDraw() {
            mView.getViewTreeObserver().removeOnPreDrawListener(this);

            if (mTargetRotation == mRotation) {
                if (DEBUG) {
                    Log.i(TAG, getWindowTitleByPos(mPosition) + " already in target rot "
                            + mTargetRotation + ", allow draw without restarting it");
                }
                return true;
            }

            mPendingRotationChange = false;
            // This changes the window attributes - we need to restart the traversal for them to
            // take effect.
            updateOrientation();
            if (DEBUG) {
                Log.i(TAG, getWindowTitleByPos(mPosition)
                        + " restarting listener fired, restarting draw for rot " + mRotation);
            }
            mView.invalidate();
            return false;
        }
    }

    /**
     * A pre-draw listener, that validates that the rotation we draw in matches the displays
     * rotation before continuing the draw.
     *
     * This is to prevent a race condition, where we have not received the display changed event
     * yet, and would thus draw in an old orientation.
     */
    private class ValidatingPreDrawListener implements ViewTreeObserver.OnPreDrawListener {

        private final View mView;

        public ValidatingPreDrawListener(View view) {
            mView = view;
        }

        @Override
        public boolean onPreDraw() {
            final int displayRotation = mContext.getDisplay().getRotation();
            if (displayRotation != mRotation && !mPendingRotationChange) {
                if (DEBUG) {
                    Log.i(TAG, "Drawing rot " + mRotation + ", but display is at rot "
                            + displayRotation + ". Restarting draw");
                }
                mView.invalidate();
                return false;
            }
            return true;
        }
    }
}
