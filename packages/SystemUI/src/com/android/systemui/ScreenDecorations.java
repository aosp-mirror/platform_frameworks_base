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
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemProperties;
import android.provider.Settings.Secure;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.DisplayCutout.BoundsPosition;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.LayoutInflater;
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
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.qs.SecureSetting;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * An overlay that draws screen decorations in software (e.g for rounded corners or display cutout)
 * for antialiasing and emulation purposes.
 */
@Singleton
public class ScreenDecorations extends SystemUI implements Tunable {
    private static final boolean DEBUG = false;
    private static final String TAG = "ScreenDecorations";

    public static final String SIZE = "sysui_rounded_size";
    public static final String PADDING = "sysui_rounded_content_padding";
    private static final boolean DEBUG_SCREENSHOT_ROUNDED_CORNERS =
            SystemProperties.getBoolean("debug.screenshot_rounded_corners", false);
    private static final boolean VERBOSE = false;
    private static final boolean DEBUG_COLOR = DEBUG_SCREENSHOT_ROUNDED_CORNERS;

    private DisplayManager mDisplayManager;
    @VisibleForTesting
    protected boolean mIsRegistered;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final Handler mMainHandler;
    private final TunerService mTunerService;
    private DisplayManager.DisplayListener mDisplayListener;
    private CameraAvailabilityListener mCameraListener;

    @VisibleForTesting
    protected int mRoundedDefault;
    @VisibleForTesting
    protected int mRoundedDefaultTop;
    @VisibleForTesting
    protected int mRoundedDefaultBottom;
    @VisibleForTesting
    protected View[] mOverlays;
    private DisplayCutoutView[] mCutoutViews;
    private float mDensity;
    private WindowManager mWindowManager;
    private int mRotation;
    private SecureSetting mColorInversionSetting;
    private boolean mPendingRotationChange;
    private Handler mHandler;

    private CameraAvailabilityListener.CameraTransitionCallback mCameraTransitionCallback =
            new CameraAvailabilityListener.CameraTransitionCallback() {
        @Override
        public void onApplyCameraProtection(@NonNull Path protectionPath, @NonNull Rect bounds) {
            // Show the extra protection around the front facing camera if necessary
            for (DisplayCutoutView dcv : mCutoutViews) {
                dcv.setProtection(protectionPath, bounds);
                dcv.setShowProtection(true);
            }
        }

        @Override
        public void onHideCameraProtection() {
            // Go back to the regular anti-aliasing
            for (DisplayCutoutView dcv : mCutoutViews) {
                dcv.setShowProtection(false);
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
            @Main Handler handler,
            BroadcastDispatcher broadcastDispatcher,
            TunerService tunerService) {
        super(context);
        mMainHandler = handler;
        mBroadcastDispatcher = broadcastDispatcher;
        mTunerService = tunerService;
    }

    @Override
    public void start() {
        mHandler = startHandlerThread();
        mHandler.post(this::startOnScreenDecorationsThread);
    }

    @VisibleForTesting
    Handler startHandlerThread() {
        HandlerThread thread = new HandlerThread("ScreenDecorations");
        thread.start();
        return thread.getThreadHandler();
    }

    private void startOnScreenDecorationsThread() {
        mRotation = mContext.getDisplay().getRotation();
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
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
                updateOrientation();
            }
        };

        mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
        updateOrientation();
    }

    private void setupDecorations() {
        if (hasRoundedCorners() || shouldDrawCutout()) {
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

            mMainHandler.post(() -> mTunerService.addTunable(this, SIZE));

            // Watch color inversion and invert the overlay as needed.
            if (mColorInversionSetting == null) {
                mColorInversionSetting = new SecureSetting(mContext, mHandler,
                        Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED) {
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
            mBroadcastDispatcher.registerReceiverWithHandler(mIntentReceiver, filter, mHandler);
            mIsRegistered = true;
        } else {
            mMainHandler.post(() -> mTunerService.removeTunable(this));

            if (mColorInversionSetting != null) {
                mColorInversionSetting.setListening(false);
            }

            mBroadcastDispatcher.unregisterReceiver(mIntentReceiver);
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
        mOverlays[pos] = LayoutInflater.from(mContext)
                .inflate(R.layout.rounded_corners, null);

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

    private void updateView(@BoundsPosition int pos) {
        if (mOverlays == null || mOverlays[pos] == null) {
            return;
        }

        // update rounded corner view rotation
        updateRoundedCornerView(pos, R.id.left);
        updateRoundedCornerView(pos, R.id.right);

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

    private static int getBoundPositionFromRotation(@BoundsPosition int pos, int rotation) {
        return (pos - rotation) < 0
                ? pos - rotation + DisplayCutout.BOUNDS_POSITION_LENGTH
                : pos - rotation;
    }

    private void setupCameraListener() {
        Resources res = mContext.getResources();
        boolean enabled = res.getBoolean(R.bool.config_enableDisplayCutoutProtection);
        if (enabled) {
            mCameraListener = CameraAvailabilityListener.Factory.build(mContext, mHandler::post);
            mCameraListener.addTransitionCallback(mCameraTransitionCallback);
            mCameraListener.startListening();
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_USER_SWITCHED)) {
                int newUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        ActivityManager.getCurrentUser());
                // update color inversion setting to the new user
                mColorInversionSetting.setUserId(newUserId);
                updateColorInversion(mColorInversionSetting.getValue());
            }
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
        mHandler.post(() -> {
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
        if (mPendingRotationChange) {
            return;
        }
        int newRotation = mContext.getDisplay().getRotation();
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

    private void updateRoundedCornerRadii() {
        final int newRoundedDefault = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.rounded_corner_radius);
        final int newRoundedDefaultTop = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.rounded_corner_radius_top);
        final int newRoundedDefaultBottom = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.rounded_corner_radius_bottom);

        final boolean roundedCornersChanged = mRoundedDefault != newRoundedDefault
                || mRoundedDefaultBottom != newRoundedDefaultBottom
                || mRoundedDefaultTop != newRoundedDefaultTop;

        if (roundedCornersChanged) {
            mRoundedDefault = newRoundedDefault;
            mRoundedDefaultTop = newRoundedDefaultTop;
            mRoundedDefaultBottom = newRoundedDefaultBottom;
            onTuningChanged(SIZE, null);
        }
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
            rounded.setRotation(getRoundedCornerRotation(gravity));
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

    private int getRoundedCornerRotation(int gravity) {
        switch (gravity) {
            case Gravity.TOP | Gravity.LEFT:
                return 0;
            case Gravity.TOP | Gravity.RIGHT:
                return 90;
            case Gravity.BOTTOM | Gravity.LEFT:
                return 270;
            case Gravity.BOTTOM | Gravity.RIGHT:
                return 180;
            default:
                throw new IllegalArgumentException("Unsupported gravity: " + gravity);
        }
    }

    private boolean hasRoundedCorners() {
        return mRoundedDefault > 0 || mRoundedDefaultBottom > 0 || mRoundedDefaultTop > 0;
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
        return context.getResources().getBoolean(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout);
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
        mHandler.post(() -> {
            if (mOverlays == null) return;
            if (SIZE.equals(key)) {
                int size = mRoundedDefault;
                int sizeTop = mRoundedDefaultTop;
                int sizeBottom = mRoundedDefaultBottom;
                if (newValue != null) {
                    try {
                        size = (int) (Integer.parseInt(newValue) * mDensity);
                    } catch (Exception e) {
                    }
                }

                if (sizeTop == 0) {
                    sizeTop = size;
                }
                if (sizeBottom == 0) {
                    sizeBottom = size;
                }

                for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
                    if (mOverlays[i] == null) {
                        continue;
                    }
                    setSize(mOverlays[i].findViewById(R.id.left), sizeTop);
                    setSize(mOverlays[i].findViewById(R.id.right), sizeBottom);
                }
            }
        });
    }

    private void setSize(View view, int pixelSize) {
        LayoutParams params = view.getLayoutParams();
        params.width = pixelSize;
        params.height = pixelSize;
        view.setLayoutParams(params);
    }

    public static class DisplayCutoutView extends View implements DisplayManager.DisplayListener,
            RegionInterceptableView {

        private static final float HIDDEN_CAMERA_PROTECTION_SCALE = 0.5f;

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
            if (displayId == getDisplay().getDisplayId()) {
                update();
            }
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

            mBoundingPath.set(DisplayCutout.pathFromResources(getResources(), dw, dh));
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
