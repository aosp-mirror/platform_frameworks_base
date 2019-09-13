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

import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import static com.android.systemui.tuner.TunablePadding.FLAG_END;
import static com.android.systemui.tuner.TunablePadding.FLAG_START;

import android.annotation.Dimension;
import android.app.ActivityManager;
import android.app.Fragment;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemProperties;
import android.provider.Settings.Secure;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.DisplayCutout;
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
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.fragments.FragmentHostManager.FragmentListener;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.qs.SecureSetting;
import com.android.systemui.statusbar.phone.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.tuner.TunablePadding;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;
import com.android.systemui.util.leak.RotationUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * An overlay that draws screen decorations in software (e.g for rounded corners or display cutout)
 * for antialiasing and emulation purposes.
 */
public class ScreenDecorations extends SystemUI implements Tunable {
    private static final boolean DEBUG = false;
    private static final String TAG = "ScreenDecorations";

    public static final String SIZE = "sysui_rounded_size";
    public static final String PADDING = "sysui_rounded_content_padding";
    private static final boolean DEBUG_SCREENSHOT_ROUNDED_CORNERS =
            SystemProperties.getBoolean("debug.screenshot_rounded_corners", false);
    private static final boolean VERBOSE = false;

    private DisplayManager mDisplayManager;
    private DisplayManager.DisplayListener mDisplayListener;

    @VisibleForTesting
    protected int mRoundedDefault;
    @VisibleForTesting
    protected int mRoundedDefaultTop;
    @VisibleForTesting
    protected int mRoundedDefaultBottom;
    private View mOverlay;
    private View mBottomOverlay;
    private float mDensity;
    private WindowManager mWindowManager;
    private int mRotation;
    private DisplayCutoutView mCutoutTop;
    private DisplayCutoutView mCutoutBottom;
    private SecureSetting mColorInversionSetting;
    private boolean mPendingRotationChange;
    private Handler mHandler;

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

    @Override
    public void start() {
        mHandler = startHandlerThread();
        mHandler.post(this::startOnScreenDecorationsThread);
        setupStatusBarPaddingIfNeeded();
        putComponent(ScreenDecorations.class, this);
    }

    @VisibleForTesting
    Handler startHandlerThread() {
        HandlerThread thread = new HandlerThread("ScreenDecorations");
        thread.start();
        return thread.getThreadHandler();
    }

    private void startOnScreenDecorationsThread() {
        mRotation = RotationUtils.getExactRotation(mContext);
        mWindowManager = mContext.getSystemService(WindowManager.class);
        updateRoundedCornerRadii();
        if (hasRoundedCorners() || shouldDrawCutout()) {
            setupDecorations();
        }

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
                final int newRotation = RotationUtils.getExactRotation(mContext);
                if (mOverlay != null && mBottomOverlay != null && mRotation != newRotation) {
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

                    mOverlay.getViewTreeObserver().addOnPreDrawListener(
                            new RestartingPreDrawListener(mOverlay, newRotation));
                    mBottomOverlay.getViewTreeObserver().addOnPreDrawListener(
                            new RestartingPreDrawListener(mBottomOverlay, newRotation));
                }
                updateOrientation();
            }
        };

        mDisplayManager = (DisplayManager) mContext.getSystemService(
                Context.DISPLAY_SERVICE);
        mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
        updateOrientation();
    }

    private void setupDecorations() {
        mOverlay = LayoutInflater.from(mContext)
                .inflate(R.layout.rounded_corners, null);
        mCutoutTop = new DisplayCutoutView(mContext, true,
                this::updateWindowVisibilities, this);
        ((ViewGroup) mOverlay).addView(mCutoutTop);
        mBottomOverlay = LayoutInflater.from(mContext)
                .inflate(R.layout.rounded_corners, null);
        mCutoutBottom = new DisplayCutoutView(mContext, false,
                this::updateWindowVisibilities, this);
        ((ViewGroup) mBottomOverlay).addView(mCutoutBottom);

        mOverlay.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        mOverlay.setAlpha(0);
        mOverlay.setForceDarkAllowed(false);

        mBottomOverlay.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        mBottomOverlay.setAlpha(0);
        mBottomOverlay.setForceDarkAllowed(false);

        updateViews();

        mWindowManager.addView(mOverlay, getWindowLayoutParams());
        mWindowManager.addView(mBottomOverlay, getBottomLayoutParams());

        DisplayMetrics metrics = new DisplayMetrics();
        mWindowManager.getDefaultDisplay().getMetrics(metrics);
        mDensity = metrics.density;

        Dependency.get(Dependency.MAIN_HANDLER).post(
                () -> Dependency.get(TunerService.class).addTunable(this, SIZE));

        // Watch color inversion and invert the overlay as needed.
        mColorInversionSetting = new SecureSetting(mContext, mHandler,
                Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                updateColorInversion(value);
            }
        };
        mColorInversionSetting.setListening(true);
        mColorInversionSetting.onChange(false);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiver(mIntentReceiver, filter, null /* permission */, mHandler);

        mOverlay.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft,
                    int oldTop, int oldRight, int oldBottom) {
                mOverlay.removeOnLayoutChangeListener(this);
                mOverlay.animate()
                        .alpha(1)
                        .setDuration(1000)
                        .start();
                mBottomOverlay.animate()
                        .alpha(1)
                        .setDuration(1000)
                        .start();
            }
        });

        mOverlay.getViewTreeObserver().addOnPreDrawListener(
                new ValidatingPreDrawListener(mOverlay));
        mBottomOverlay.getViewTreeObserver().addOnPreDrawListener(
                new ValidatingPreDrawListener(mBottomOverlay));
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
        ColorStateList tintList = ColorStateList.valueOf(tint);
        ((ImageView) mOverlay.findViewById(R.id.left)).setImageTintList(tintList);
        ((ImageView) mOverlay.findViewById(R.id.right)).setImageTintList(tintList);
        ((ImageView) mBottomOverlay.findViewById(R.id.left)).setImageTintList(tintList);
        ((ImageView) mBottomOverlay.findViewById(R.id.right)).setImageTintList(tintList);
        mCutoutTop.setColor(tint);
        mCutoutBottom.setColor(tint);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        mHandler.post(() -> {
            int oldRotation = mRotation;
            mPendingRotationChange = false;
            updateOrientation();
            updateRoundedCornerRadii();
            if (DEBUG) Log.i(TAG, "onConfigChanged from rot " + oldRotation + " to " + mRotation);
            if (shouldDrawCutout() && mOverlay == null) {
                setupDecorations();
            }
            if (mOverlay != null) {
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
        int newRotation = RotationUtils.getExactRotation(mContext);
        if (newRotation != mRotation) {
            mRotation = newRotation;

            if (mOverlay != null) {
                updateLayoutParams();
                updateViews();
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

    private void updateViews() {
        View topLeft = mOverlay.findViewById(R.id.left);
        View topRight = mOverlay.findViewById(R.id.right);
        View bottomLeft = mBottomOverlay.findViewById(R.id.left);
        View bottomRight = mBottomOverlay.findViewById(R.id.right);

        if (mRotation == RotationUtils.ROTATION_NONE) {
            updateView(topLeft, Gravity.TOP | Gravity.LEFT, 0);
            updateView(topRight, Gravity.TOP | Gravity.RIGHT, 90);
            updateView(bottomLeft, Gravity.BOTTOM | Gravity.LEFT, 270);
            updateView(bottomRight, Gravity.BOTTOM | Gravity.RIGHT, 180);
        } else if (mRotation == RotationUtils.ROTATION_LANDSCAPE) {
            updateView(topLeft, Gravity.TOP | Gravity.LEFT, 0);
            updateView(topRight, Gravity.BOTTOM | Gravity.LEFT, 270);
            updateView(bottomLeft, Gravity.TOP | Gravity.RIGHT, 90);
            updateView(bottomRight, Gravity.BOTTOM | Gravity.RIGHT, 180);
        } else if (mRotation == RotationUtils.ROTATION_UPSIDE_DOWN) {
            updateView(topLeft, Gravity.BOTTOM | Gravity.LEFT, 270);
            updateView(topRight, Gravity.BOTTOM | Gravity.RIGHT, 180);
            updateView(bottomLeft, Gravity.TOP | Gravity.LEFT, 0);
            updateView(bottomRight, Gravity.TOP | Gravity.RIGHT, 90);
        } else if (mRotation == RotationUtils.ROTATION_SEASCAPE) {
            updateView(topLeft, Gravity.BOTTOM | Gravity.RIGHT, 180);
            updateView(topRight, Gravity.TOP | Gravity.RIGHT, 90);
            updateView(bottomLeft, Gravity.BOTTOM | Gravity.LEFT, 270);
            updateView(bottomRight, Gravity.TOP | Gravity.LEFT, 0);
        }

        mCutoutTop.setRotation(mRotation);
        mCutoutBottom.setRotation(mRotation);

        updateWindowVisibilities();
    }

    private void updateView(View v, int gravity, int rotation) {
        ((FrameLayout.LayoutParams) v.getLayoutParams()).gravity = gravity;
        v.setRotation(rotation);
    }

    private void updateWindowVisibilities() {
        updateWindowVisibility(mOverlay);
        updateWindowVisibility(mBottomOverlay);
    }

    private void updateWindowVisibility(View overlay) {
        boolean visibleForCutout = shouldDrawCutout()
                && overlay.findViewById(R.id.display_cutout).getVisibility() == View.VISIBLE;
        boolean visibleForRoundedCorners = hasRoundedCorners();
        overlay.setVisibility(visibleForCutout || visibleForRoundedCorners
                ? View.VISIBLE : View.GONE);
    }

    private boolean hasRoundedCorners() {
        return mRoundedDefault > 0 || mRoundedDefaultBottom > 0 || mRoundedDefaultTop > 0;
    }

    private boolean shouldDrawCutout() {
        return shouldDrawCutout(mContext);
    }

    static boolean shouldDrawCutout(Context context) {
        return context.getResources().getBoolean(
                com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout);
    }


    private void setupStatusBarPaddingIfNeeded() {
        // TODO: This should be moved to a more appropriate place, as it is not related to the
        // screen decorations overlay.
        int padding = mContext.getResources().getDimensionPixelSize(
                R.dimen.rounded_corner_content_padding);
        if (padding != 0) {
            setupStatusBarPadding(padding);
        }

    }

    private void setupStatusBarPadding(int padding) {
        // Add some padding to all the content near the edge of the screen.
        StatusBar sb = getComponent(StatusBar.class);
        View statusBar = (sb != null ? sb.getStatusBarWindow() : null);
        if (statusBar != null) {
            TunablePadding.addTunablePadding(statusBar.findViewById(R.id.keyguard_header), PADDING,
                    padding, FLAG_END);

            FragmentHostManager fragmentHostManager = FragmentHostManager.get(statusBar);
            fragmentHostManager.addTagListener(CollapsedStatusBarFragment.TAG,
                    new TunablePaddingTagListener(padding, R.id.status_bar));
            fragmentHostManager.addTagListener(QS.TAG,
                    new TunablePaddingTagListener(padding, R.id.header));
        }
    }

    @VisibleForTesting
    WindowManager.LayoutParams getWindowLayoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_SLIPPERY
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS
                | WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;

        if (!DEBUG_SCREENSHOT_ROUNDED_CORNERS) {
            lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;
        }

        lp.setTitle("ScreenDecorOverlay");
        if (mRotation == RotationUtils.ROTATION_SEASCAPE
                || mRotation == RotationUtils.ROTATION_UPSIDE_DOWN) {
            lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        } else {
            lp.gravity = Gravity.TOP | Gravity.LEFT;
        }
        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        if (isLandscape(mRotation)) {
            lp.width = WRAP_CONTENT;
            lp.height = MATCH_PARENT;
        }
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;
        return lp;
    }

    private WindowManager.LayoutParams getBottomLayoutParams() {
        WindowManager.LayoutParams lp = getWindowLayoutParams();
        lp.setTitle("ScreenDecorOverlayBottom");
        if (mRotation == RotationUtils.ROTATION_SEASCAPE
                || mRotation == RotationUtils.ROTATION_UPSIDE_DOWN) {
            lp.gravity = Gravity.TOP | Gravity.LEFT;
        } else {
            lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        }
        return lp;
    }

    private void updateLayoutParams() {
        mWindowManager.updateViewLayout(mOverlay, getWindowLayoutParams());
        mWindowManager.updateViewLayout(mBottomOverlay, getBottomLayoutParams());
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        mHandler.post(() -> {
            if (mOverlay == null) return;
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

                setSize(mOverlay.findViewById(R.id.left), sizeTop);
                setSize(mOverlay.findViewById(R.id.right), sizeTop);
                setSize(mBottomOverlay.findViewById(R.id.left), sizeBottom);
                setSize(mBottomOverlay.findViewById(R.id.right), sizeBottom);
            }
        });
    }

    private void setSize(View view, int pixelSize) {
        LayoutParams params = view.getLayoutParams();
        params.width = pixelSize;
        params.height = pixelSize;
        view.setLayoutParams(params);
    }

    @VisibleForTesting
    static class TunablePaddingTagListener implements FragmentListener {

        private final int mPadding;
        private final int mId;
        private TunablePadding mTunablePadding;

        public TunablePaddingTagListener(int padding, int id) {
            mPadding = padding;
            mId = id;
        }

        @Override
        public void onFragmentViewCreated(String tag, Fragment fragment) {
            if (mTunablePadding != null) {
                mTunablePadding.destroy();
            }
            View view = fragment.getView();
            if (mId != 0) {
                view = view.findViewById(mId);
            }
            mTunablePadding = TunablePadding.addTunablePadding(view, PADDING, mPadding,
                    FLAG_START | FLAG_END);
        }
    }

    public static class DisplayCutoutView extends View implements DisplayManager.DisplayListener,
            RegionInterceptableView {

        private final DisplayInfo mInfo = new DisplayInfo();
        private final Paint mPaint = new Paint();
        private final List<Rect> mBounds = new ArrayList();
        private final Rect mBoundingRect = new Rect();
        private final Path mBoundingPath = new Path();
        private final int[] mLocation = new int[2];
        private final boolean mInitialStart;
        private final Runnable mVisibilityChangedListener;
        private final ScreenDecorations mDecorations;
        private int mColor = Color.BLACK;
        private boolean mStart;
        private int mRotation;

        public DisplayCutoutView(Context context, boolean start,
                Runnable visibilityChangedListener, ScreenDecorations decorations) {
            super(context);
            mInitialStart = start;
            mVisibilityChangedListener = visibilityChangedListener;
            mDecorations = decorations;
            setId(R.id.display_cutout);
            if (DEBUG) {
                getViewTreeObserver().addOnDrawListener(() -> Log.i(TAG,
                        (mInitialStart ? "OverlayTop" : "OverlayBottom")
                                + " drawn in rot " + mRotation));
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

        private boolean isStart() {
            final boolean flipped = (mRotation == RotationUtils.ROTATION_SEASCAPE
                    || mRotation == RotationUtils.ROTATION_UPSIDE_DOWN);
            return flipped ? !mInitialStart : mInitialStart;
        }

        private void update() {
            if (!isAttachedToWindow() || mDecorations.mPendingRotationChange) {
                return;
            }
            mStart = isStart();
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
                mVisibilityChangedListener.run();
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
            if (mStart) {
                return displayCutout.getSafeInsetLeft() > 0
                        || displayCutout.getSafeInsetTop() > 0;
            } else {
                return displayCutout.getSafeInsetRight() > 0
                        || displayCutout.getSafeInsetBottom() > 0;
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (mBounds.isEmpty()) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
            }
            setMeasuredDimension(
                    resolveSizeAndState(mBoundingRect.width(), widthMeasureSpec, 0),
                    resolveSizeAndState(mBoundingRect.height(), heightMeasureSpec, 0));
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
            if (mStart) {
                if (displayCutout.getSafeInsetLeft() > 0) {
                    return Gravity.LEFT;
                } else if (displayCutout.getSafeInsetTop() > 0) {
                    return Gravity.TOP;
                }
            } else {
                if (displayCutout.getSafeInsetRight() > 0) {
                    return Gravity.RIGHT;
                } else if (displayCutout.getSafeInsetBottom() > 0) {
                    return Gravity.BOTTOM;
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

    private boolean isLandscape(int rotation) {
        return rotation == RotationUtils.ROTATION_LANDSCAPE || rotation ==
                RotationUtils.ROTATION_SEASCAPE;
    }

    /**
     * A pre-draw listener, that cancels the draw and restarts the traversal with the updated
     * window attributes.
     */
    private class RestartingPreDrawListener implements ViewTreeObserver.OnPreDrawListener {

        private final View mView;
        private final int mTargetRotation;

        private RestartingPreDrawListener(View view, int targetRotation) {
            mView = view;
            mTargetRotation = targetRotation;
        }

        @Override
        public boolean onPreDraw() {
            mView.getViewTreeObserver().removeOnPreDrawListener(this);

            if (mTargetRotation == mRotation) {
                if (DEBUG) {
                    Log.i(TAG, (mView == mOverlay ? "OverlayTop" : "OverlayBottom")
                            + " already in target rot "
                            + mTargetRotation + ", allow draw without restarting it");
                }
                return true;
            }

            mPendingRotationChange = false;
            // This changes the window attributes - we need to restart the traversal for them to
            // take effect.
            updateOrientation();
            if (DEBUG) {
                Log.i(TAG, (mView == mOverlay ? "OverlayTop" : "OverlayBottom")
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
            final int displayRotation = RotationUtils.getExactRotation(mContext);
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
