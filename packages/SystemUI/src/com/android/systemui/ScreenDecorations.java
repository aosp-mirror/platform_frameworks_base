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

import static com.android.systemui.tuner.TunablePadding.FLAG_START;
import static com.android.systemui.tuner.TunablePadding.FLAG_END;

import android.annotation.Dimension;
import android.app.Fragment;
import android.content.Context;
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
import android.os.SystemProperties;
import android.provider.Settings.Secure;
import android.support.annotation.VisibleForTesting;
import android.util.DisplayMetrics;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

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

/**
 * An overlay that draws screen decorations in software (e.g for rounded corners or display cutout)
 * for antialiasing and emulation purposes.
 */
public class ScreenDecorations extends SystemUI implements Tunable {
    public static final String SIZE = "sysui_rounded_size";
    public static final String PADDING = "sysui_rounded_content_padding";
    private static final boolean DEBUG_SCREENSHOT_ROUNDED_CORNERS =
            SystemProperties.getBoolean("debug.screenshot_rounded_corners", false);

    private int mRoundedDefault;
    private int mRoundedDefaultTop;
    private int mRoundedDefaultBottom;
    private View mOverlay;
    private View mBottomOverlay;
    private float mDensity;
    private WindowManager mWindowManager;
    private boolean mLandscape;

    @Override
    public void start() {
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mRoundedDefault = mContext.getResources().getDimensionPixelSize(
                R.dimen.rounded_corner_radius);
        mRoundedDefaultTop = mContext.getResources().getDimensionPixelSize(
                R.dimen.rounded_corner_radius_top);
        mRoundedDefaultBottom = mContext.getResources().getDimensionPixelSize(
                R.dimen.rounded_corner_radius_bottom);
        if (hasRoundedCorners() || shouldDrawCutout()) {
            setupDecorations();
        }

        int padding = mContext.getResources().getDimensionPixelSize(
                R.dimen.rounded_corner_content_padding);
        if (padding != 0) {
            setupPadding(padding);
        }
    }

    private void setupDecorations() {
        mOverlay = LayoutInflater.from(mContext)
                .inflate(R.layout.rounded_corners, null);
        ((ViewGroup)mOverlay).addView(new DisplayCutoutView(mContext, true,
                this::updateWindowVisibilities));
        mBottomOverlay = LayoutInflater.from(mContext)
                .inflate(R.layout.rounded_corners, null);
        ((ViewGroup)mBottomOverlay).addView(new DisplayCutoutView(mContext, false,
                this::updateWindowVisibilities));

        mOverlay.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        mOverlay.setAlpha(0);

        mBottomOverlay.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        mBottomOverlay.setAlpha(0);

        updateViews();

        mWindowManager.addView(mOverlay, getWindowLayoutParams());
        mWindowManager.addView(mBottomOverlay, getBottomLayoutParams());

        DisplayMetrics metrics = new DisplayMetrics();
        mWindowManager.getDefaultDisplay().getMetrics(metrics);
        mDensity = metrics.density;

        Dependency.get(TunerService.class).addTunable(this, SIZE);

        // Watch color inversion and invert the overlay as needed.
        SecureSetting setting = new SecureSetting(mContext, Dependency.get(Dependency.MAIN_HANDLER),
                Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                int tint = value != 0 ? Color.WHITE : Color.BLACK;
                ColorStateList tintList = ColorStateList.valueOf(tint);
                ((ImageView) mOverlay.findViewById(R.id.left)).setImageTintList(tintList);
                ((ImageView) mOverlay.findViewById(R.id.right)).setImageTintList(tintList);
                ((ImageView) mBottomOverlay.findViewById(R.id.left)).setImageTintList(tintList);
                ((ImageView) mBottomOverlay.findViewById(R.id.right)).setImageTintList(tintList);
            }
        };
        setting.setListening(true);
        setting.onChange(false);

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
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        boolean newLanscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (newLanscape != mLandscape) {
            mLandscape = newLanscape;

            if (mOverlay != null) {
                updateLayoutParams();
                updateViews();
            }
        }
        if (shouldDrawCutout() && mOverlay == null) {
            setupDecorations();
        }
    }

    private void updateViews() {
        View topLeft = mOverlay.findViewById(R.id.left);
        View topRight = mOverlay.findViewById(R.id.right);
        View bottomLeft = mBottomOverlay.findViewById(R.id.left);
        View bottomRight = mBottomOverlay.findViewById(R.id.right);
        if (mLandscape) {
            // Flip corners
            View tmp = topRight;
            topRight = bottomLeft;
            bottomLeft = tmp;
        }
        updateView(topLeft, Gravity.TOP | Gravity.LEFT, 0);
        updateView(topRight, Gravity.TOP | Gravity.RIGHT, 90);
        updateView(bottomLeft, Gravity.BOTTOM | Gravity.LEFT, 270);
        updateView(bottomRight, Gravity.BOTTOM | Gravity.RIGHT, 180);

        updateWindowVisibilities();
    }

    private void updateView(View v, int gravity, int rotation) {
        ((FrameLayout.LayoutParams)v.getLayoutParams()).gravity = gravity;
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

    private void setupPadding(int padding) {
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
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        if (mLandscape) {
            lp.width = WRAP_CONTENT;
            lp.height = MATCH_PARENT;
        }
        return lp;
    }

    private WindowManager.LayoutParams getBottomLayoutParams() {
        WindowManager.LayoutParams lp = getWindowLayoutParams();
        lp.setTitle("ScreenDecorOverlayBottom");
        lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        return lp;
    }

    private void updateLayoutParams() {
        mWindowManager.updateViewLayout(mOverlay, getWindowLayoutParams());
        mWindowManager.updateViewLayout(mBottomOverlay, getBottomLayoutParams());
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
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
        private final Region mBounds = new Region();
        private final Rect mBoundingRect = new Rect();
        private final Path mBoundingPath = new Path();
        private final int[] mLocation = new int[2];
        private final boolean mStart;
        private final Runnable mVisibilityChangedListener;

        public DisplayCutoutView(Context context, boolean start,
                Runnable visibilityChangedListener) {
            super(context);
            mStart = start;
            mVisibilityChangedListener = visibilityChangedListener;
            setId(R.id.display_cutout);
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
                mPaint.setColor(Color.BLACK);
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

        private void update() {
            requestLayout();
            getDisplay().getDisplayInfo(mInfo);
            mBounds.setEmpty();
            mBoundingRect.setEmpty();
            mBoundingPath.reset();
            int newVisible;
            if (shouldDrawCutout(getContext()) && hasCutout()) {
                mBounds.set(mInfo.displayCutout.getBounds());
                localBounds(mBoundingRect);
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

        public static void boundsFromDirection(DisplayCutout displayCutout, int gravity, Rect out) {
            Region bounds = displayCutout.getBounds();
            switch (gravity) {
                case Gravity.TOP:
                    bounds.op(0, 0, Integer.MAX_VALUE, displayCutout.getSafeInsetTop(),
                            Region.Op.INTERSECT);
                    out.set(bounds.getBounds());
                    break;
                case Gravity.LEFT:
                    bounds.op(0, 0, displayCutout.getSafeInsetLeft(), Integer.MAX_VALUE,
                            Region.Op.INTERSECT);
                    out.set(bounds.getBounds());
                    break;
                case Gravity.BOTTOM:
                    bounds.op(0, displayCutout.getSafeInsetTop() + 1, Integer.MAX_VALUE,
                            Integer.MAX_VALUE, Region.Op.INTERSECT);
                    out.set(bounds.getBounds());
                    break;
                case Gravity.RIGHT:
                    bounds.op(displayCutout.getSafeInsetLeft() + 1, 0, Integer.MAX_VALUE,
                            Integer.MAX_VALUE, Region.Op.INTERSECT);
                    out.set(bounds.getBounds());
                    break;
            }
            bounds.recycle();
        }

        private void localBounds(Rect out) {
            final DisplayCutout displayCutout = mInfo.displayCutout;

            if (mStart) {
                if (displayCutout.getSafeInsetLeft() > 0) {
                    boundsFromDirection(displayCutout, Gravity.LEFT, out);
                } else if (displayCutout.getSafeInsetTop() > 0) {
                    boundsFromDirection(displayCutout, Gravity.TOP, out);
                }
            } else {
                if (displayCutout.getSafeInsetRight() > 0) {
                    boundsFromDirection(displayCutout, Gravity.RIGHT, out);
                } else if (displayCutout.getSafeInsetBottom() > 0) {
                    boundsFromDirection(displayCutout, Gravity.BOTTOM, out);
                }
            }
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

            return mInfo.displayCutout.getBounds();
        }
    }
}
