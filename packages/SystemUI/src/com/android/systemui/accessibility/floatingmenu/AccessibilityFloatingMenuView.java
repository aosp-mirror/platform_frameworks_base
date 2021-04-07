/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.accessibility.floatingmenu;

import static android.util.MathUtils.constrain;
import static android.util.MathUtils.sq;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.DimenRes;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Accessibility floating menu is used for the actions of accessibility features, it's also the
 * action set.
 *
 * <p>The number of items would depend on strings key
 * {@link android.provider.Settings.Secure#ACCESSIBILITY_BUTTON_TARGETS}.
 */
public class AccessibilityFloatingMenuView extends FrameLayout
        implements RecyclerView.OnItemTouchListener {
    private static final int INDEX_MENU_ITEM = 0;
    private static final int FADE_OUT_DURATION_MS = 1000;
    private static final int FADE_EFFECT_DURATION_MS = 3000;
    private static final int SNAP_TO_LOCATION_DURATION_MS = 150;
    private static final int MIN_WINDOW_X = 0;
    private static final int MIN_WINDOW_Y = 0;
    private static final float LOCATION_Y_PERCENTAGE = 0.8f;

    private boolean mIsFadeEffectEnabled;
    private boolean mIsShowing;
    private boolean mIsDownInEnlargedTouchArea;
    private boolean mIsDragging = false;
    @Alignment
    private int mAlignment = Alignment.RIGHT;
    @SizeType
    private int mSizeType = SizeType.SMALL;
    @VisibleForTesting
    @ShapeType
    int mShapeType = ShapeType.OVAL;
    private int mTemporaryShapeType;
    @RadiusType
    private int mRadiusType = RadiusType.LEFT_HALF_OVAL;
    private int mMargin;
    private int mPadding;
    private int mScreenHeight;
    private int mScreenWidth;
    private int mIconWidth;
    private int mIconHeight;
    private int mInset;
    private int mDownX;
    private int mDownY;
    private int mRelativeToPointerDownX;
    private int mRelativeToPointerDownY;
    private float mRadius;
    private float mPercentageY = LOCATION_Y_PERCENTAGE;
    private float mSquareScaledTouchSlop;
    private final RecyclerView mListView;
    private final AccessibilityTargetAdapter mAdapter;
    private float mFadeOutValue;
    private final ValueAnimator mFadeOutAnimator;
    @VisibleForTesting
    final ValueAnimator mDragAnimator;
    private final Handler mUiHandler;
    @VisibleForTesting
    final WindowManager.LayoutParams mCurrentLayoutParams;
    private final WindowManager mWindowManager;
    private final List<AccessibilityTarget> mTargets = new ArrayList<>();

    @IntDef({
            SizeType.SMALL,
            SizeType.LARGE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface SizeType {
        int SMALL = 0;
        int LARGE = 1;
    }

    @IntDef({
            ShapeType.OVAL,
            ShapeType.HALF_OVAL
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ShapeType {
        int OVAL = 0;
        int HALF_OVAL = 1;
    }

    @IntDef({
            RadiusType.LEFT_HALF_OVAL,
            RadiusType.OVAL,
            RadiusType.RIGHT_HALF_OVAL
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface RadiusType {
        int LEFT_HALF_OVAL = 0;
        int OVAL = 1;
        int RIGHT_HALF_OVAL = 2;
    }

    @IntDef({
            Alignment.LEFT,
            Alignment.RIGHT
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Alignment {
        int LEFT = 0;
        int RIGHT = 1;
    }

    public AccessibilityFloatingMenuView(Context context) {
        this(context, new RecyclerView(context));
    }

    @VisibleForTesting
    AccessibilityFloatingMenuView(Context context,
            RecyclerView listView) {
        super(context);

        mListView = listView;
        mWindowManager = context.getSystemService(WindowManager.class);
        mCurrentLayoutParams = createDefaultLayoutParams();
        mAdapter = new AccessibilityTargetAdapter(mTargets);
        mUiHandler = createUiHandler();

        mFadeOutAnimator = ValueAnimator.ofFloat(1.0f, mFadeOutValue);
        mFadeOutAnimator.setDuration(FADE_OUT_DURATION_MS);
        mFadeOutAnimator.addUpdateListener(
                (animation) -> setAlpha((float) animation.getAnimatedValue()));

        mDragAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        mDragAnimator.setDuration(SNAP_TO_LOCATION_DURATION_MS);
        mDragAnimator.setInterpolator(new OvershootInterpolator());
        mDragAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mAlignment = calculateCurrentAlignment();
                mPercentageY = calculateCurrentPercentageY();

                updateLocationWith(mAlignment, mPercentageY);
                updateMarginsWith(mAlignment);

                updateInsetWith(getResources().getConfiguration().uiMode, mAlignment);

                mRadiusType = (mAlignment == Alignment.RIGHT)
                        ? RadiusType.LEFT_HALF_OVAL
                        : RadiusType.RIGHT_HALF_OVAL;
                updateRadiusWith(mSizeType, mRadiusType, mTargets.size());

                fadeOut();
            }
        });

        updateDimensions();
        initListView();
        updateStrokeWith(getResources().getConfiguration().uiMode, mAlignment);
    }

    @Override
    public boolean onInterceptTouchEvent(@NonNull RecyclerView recyclerView,
            @NonNull MotionEvent event) {
        final int currentRawX = (int) event.getRawX();
        final int currentRawY = (int) event.getRawY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                fadeIn();

                mDownX = currentRawX;
                mDownY = currentRawY;
                mRelativeToPointerDownX = mCurrentLayoutParams.x - mDownX;
                mRelativeToPointerDownY = mCurrentLayoutParams.y - mDownY;
                mListView.animate().translationX(0);
                break;
            case MotionEvent.ACTION_MOVE:
                if (mIsDragging
                        || hasExceededTouchSlop(mDownX, mDownY, currentRawX, currentRawY)) {
                    if (!mIsDragging) {
                        mIsDragging = true;
                        setRadius(mRadius, RadiusType.OVAL);
                        setInset(0, 0);
                    }

                    mTemporaryShapeType =
                            isMovingTowardsScreenEdge(mAlignment, currentRawX, mDownX)
                                    ? ShapeType.HALF_OVAL
                                    : ShapeType.OVAL;
                    final int newWindowX = currentRawX + mRelativeToPointerDownX;
                    final int newWindowY = currentRawY + mRelativeToPointerDownY;
                    mCurrentLayoutParams.x = constrain(newWindowX, MIN_WINDOW_X, getMaxWindowX());
                    mCurrentLayoutParams.y = constrain(newWindowY, MIN_WINDOW_Y, getMaxWindowY());
                    mWindowManager.updateViewLayout(this, mCurrentLayoutParams);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mIsDragging) {
                    mIsDragging = false;

                    final int maxX = getMaxWindowX();
                    final int endX = mCurrentLayoutParams.x > ((MIN_WINDOW_X + maxX) / 2)
                            ? maxX : MIN_WINDOW_X;
                    final int endY = mCurrentLayoutParams.y;
                    snapToLocation(endX, endY);

                    setShapeType(mTemporaryShapeType);

                    // Avoid triggering the listener of the item.
                    return true;
                }

                // Must switch the oval shape type before tapping the corresponding item in the
                // list view, otherwise it can't work on it.
                if (mShapeType == ShapeType.HALF_OVAL) {
                    setShapeType(ShapeType.OVAL);

                    return true;
                }

                fadeOut();
                break;
            default: // Do nothing
        }

        // not consume all the events here because keeping the scroll behavior of list view.
        return false;
    }

    @Override
    public void onTouchEvent(@NonNull RecyclerView recyclerView, @NonNull MotionEvent motionEvent) {
        // Do Nothing
    }

    @Override
    public void onRequestDisallowInterceptTouchEvent(boolean b) {
        // Do Nothing
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        setupAccessibilityActions(info);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (super.performAccessibilityAction(action, arguments)) {
            return true;
        }

        fadeIn();

        final Rect bounds = getAvailableBounds();
        if (action == R.id.action_move_top_left) {
            snapToLocation(bounds.left, bounds.top);
            return true;
        }

        if (action == R.id.action_move_top_right) {
            snapToLocation(bounds.right, bounds.top);
            return true;
        }

        if (action == R.id.action_move_bottom_left) {
            snapToLocation(bounds.left, bounds.bottom);
            return true;
        }

        if (action == R.id.action_move_bottom_right) {
            snapToLocation(bounds.right, bounds.bottom);
            return true;
        }

        return false;
    }

    void show() {
        if (isShowing()) {
            return;
        }

        mIsShowing = true;
        mWindowManager.addView(this, mCurrentLayoutParams);
        setSystemGestureExclusion();
    }

    void hide() {
        if (!isShowing()) {
            return;
        }

        mIsShowing = false;
        mWindowManager.removeView(this);
        setSystemGestureExclusion();
    }

    boolean isShowing() {
        return mIsShowing;
    }

    void onTargetsChanged(List<AccessibilityTarget> newTargets) {
        fadeIn();

        mTargets.clear();
        mTargets.addAll(newTargets);
        mAdapter.notifyDataSetChanged();

        updateRadiusWith(mSizeType, mRadiusType, mTargets.size());
        setSystemGestureExclusion();

        fadeOut();
    }

    void setSizeType(@SizeType int newSizeType) {
        fadeIn();

        mSizeType = newSizeType;

        updateIconSizeWith(newSizeType);
        updateRadiusWith(newSizeType, mRadiusType, mTargets.size());

        // When the icon sized changed, the menu size and location will be impacted.
        updateLocationWith(mAlignment, mPercentageY);
        setSystemGestureExclusion();

        fadeOut();
    }

    void setShapeType(@ShapeType int newShapeType) {
        fadeIn();

        mShapeType = newShapeType;

        updateOffsetWith(newShapeType, mAlignment);

        setOnTouchListener(
                newShapeType == ShapeType.OVAL
                        ? null
                        : (view, event) -> onTouched(event));

        fadeOut();
    }

    void updateOpacityWith(boolean isFadeEffectEnabled, float newOpacityValue) {
        mIsFadeEffectEnabled = isFadeEffectEnabled;
        mFadeOutValue = newOpacityValue;

        mFadeOutAnimator.cancel();
        mFadeOutAnimator.setFloatValues(1.0f, mFadeOutValue);
        setAlpha(mIsFadeEffectEnabled ? mFadeOutValue : /* completely opaque */ 1.0f);
    }

    @VisibleForTesting
    void fadeIn() {
        if (!mIsFadeEffectEnabled) {
            return;
        }

        mFadeOutAnimator.cancel();
        mUiHandler.removeCallbacksAndMessages(null);
        mUiHandler.post(() -> setAlpha(/* completely opaque */ 1.0f));
    }

    @VisibleForTesting
    void fadeOut() {
        if (!mIsFadeEffectEnabled) {
            return;
        }

        mUiHandler.postDelayed(() -> mFadeOutAnimator.start(), FADE_EFFECT_DURATION_MS);
    }

    private void setupAccessibilityActions(AccessibilityNodeInfo info) {
        final Resources res = mContext.getResources();
        final AccessibilityAction moveTopLeft =
                new AccessibilityAction(R.id.action_move_top_left,
                        res.getString(
                                R.string.accessibility_floating_button_action_move_top_left));
        info.addAction(moveTopLeft);

        final AccessibilityAction moveTopRight =
                new AccessibilityAction(R.id.action_move_top_right,
                        res.getString(
                                R.string.accessibility_floating_button_action_move_top_right));
        info.addAction(moveTopRight);

        final AccessibilityAction moveBottomLeft =
                new AccessibilityAction(R.id.action_move_bottom_left,
                        res.getString(
                                R.string.accessibility_floating_button_action_move_bottom_left));
        info.addAction(moveBottomLeft);

        final AccessibilityAction moveBottomRight =
                new AccessibilityAction(R.id.action_move_bottom_right,
                        res.getString(
                                R.string.accessibility_floating_button_action_move_bottom_right));
        info.addAction(moveBottomRight);
    }

    private boolean onTouched(MotionEvent event) {
        final int action = event.getAction();
        final int currentX = (int) event.getX();
        final int currentY = (int) event.getY();

        final int menuHalfWidth = getLayoutWidth() / 2;
        final Rect touchDelegateBounds =
                new Rect(mMargin, mMargin, mMargin + menuHalfWidth, mMargin + getLayoutHeight());
        if (action == MotionEvent.ACTION_DOWN
                && touchDelegateBounds.contains(currentX, currentY)) {
            mIsDownInEnlargedTouchArea = true;
        }

        if (!mIsDownInEnlargedTouchArea) {
            return false;
        }

        if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL) {
            mIsDownInEnlargedTouchArea = false;
        }

        // In order to correspond to the correct item of list view.
        event.setLocation(currentX - mMargin, currentY - mMargin);
        return mListView.dispatchTouchEvent(event);
    }

    private boolean isMovingTowardsScreenEdge(@Alignment int side, int currentRawX, int downX) {
        return (side == Alignment.RIGHT && currentRawX > downX)
                || (side == Alignment.LEFT && downX > currentRawX);
    }

    private boolean hasExceededTouchSlop(int startX, int startY, int endX, int endY) {
        return (sq(endX - startX) + sq(endY - startY)) > mSquareScaledTouchSlop;
    }

    private void setRadius(float radius, @RadiusType int type) {
        getMenuGradientDrawable().setCornerRadii(createRadii(radius, type));
    }

    private float[] createRadii(float radius, @RadiusType int type) {
        if (type == RadiusType.LEFT_HALF_OVAL) {
            return new float[]{radius, radius, 0.0f, 0.0f, 0.0f, 0.0f, radius, radius};
        }

        if (type == RadiusType.RIGHT_HALF_OVAL) {
            return new float[]{0.0f, 0.0f, radius, radius, radius, radius, 0.0f, 0.0f};
        }

        return new float[]{radius, radius, radius, radius, radius, radius, radius, radius};
    }

    private Handler createUiHandler() {
        final Looper looper = Looper.myLooper();
        if (looper == null) {
            throw new IllegalArgumentException("looper must not be null");
        }
        return new Handler(looper);
    }

    private void updateDimensions() {
        final Resources res = getResources();
        final DisplayMetrics dm = res.getDisplayMetrics();
        mScreenWidth = dm.widthPixels;
        mScreenHeight = dm.heightPixels;
        mMargin =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_margin);
        mPadding =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_padding);
        mInset =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_stroke_inset);

        mSquareScaledTouchSlop =
                sq(ViewConfiguration.get(getContext()).getScaledTouchSlop());
    }

    private void updateIconSizeWith(@SizeType int sizeType) {
        final Resources res = getResources();
        final int iconResId =
                sizeType == SizeType.SMALL
                        ? R.dimen.accessibility_floating_menu_small_width_height
                        : R.dimen.accessibility_floating_menu_large_width_height;
        mIconWidth = res.getDimensionPixelSize(iconResId);
        mIconHeight = mIconWidth;

        mAdapter.setIconWidthHeight(mIconWidth);
        mAdapter.notifyDataSetChanged();
    }

    private void initListView() {
        final Drawable background =
                getContext().getDrawable(R.drawable.accessibility_floating_menu_background);
        final LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        final LayoutParams layoutParams =
                new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        mListView.setLayoutParams(layoutParams);
        final InstantInsetLayerDrawable layerDrawable =
                new InstantInsetLayerDrawable(new Drawable[]{background});
        mListView.setBackground(layerDrawable);
        mListView.setAdapter(mAdapter);
        mListView.setLayoutManager(layoutManager);
        mListView.addOnItemTouchListener(this);
        mListView.animate().setInterpolator(new OvershootInterpolator());
        updateListView();

        addView(mListView);
    }

    private void updateListView() {
        final int elevation =
                getResources().getDimensionPixelSize(R.dimen.accessibility_floating_menu_elevation);
        mListView.setElevation(elevation);

        updateMarginsWith(mAlignment);
    }

    private WindowManager.LayoutParams createDefaultLayoutParams() {
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.windowAnimations = android.R.style.Animation_Translucent;
        params.gravity = Gravity.START | Gravity.TOP;
        params.x = getMaxWindowX();
        params.y = (int) (getMaxWindowY() * mPercentageY);

        return params;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        updateDimensions();
        updateListView();
        updateIconSizeWith(mSizeType);
        updateColor();
        updateStrokeWith(newConfig.uiMode, mAlignment);
        updateLocationWith(mAlignment, mPercentageY);
    }

    @VisibleForTesting
    void snapToLocation(int endX, int endY) {
        mDragAnimator.cancel();
        mDragAnimator.removeAllUpdateListeners();
        mDragAnimator.addUpdateListener(anim -> onDragAnimationUpdate(anim, endX, endY));
        mDragAnimator.start();
    }

    private void onDragAnimationUpdate(ValueAnimator animator, int endX, int endY) {
        float value = (float) animator.getAnimatedValue();
        final int newX = (int) (((1 - value) * mCurrentLayoutParams.x) + (value * endX));
        final int newY = (int) (((1 - value) * mCurrentLayoutParams.y) + (value * endY));

        mCurrentLayoutParams.x = newX;
        mCurrentLayoutParams.y = newY;
        mWindowManager.updateViewLayout(this, mCurrentLayoutParams);
    }

    private int getMaxWindowX() {
        return mScreenWidth - mMargin - getLayoutWidth();
    }

    private int getMaxWindowY() {
        return mScreenHeight - getWindowHeight();
    }

    private InstantInsetLayerDrawable getMenuLayerDrawable() {
        return (InstantInsetLayerDrawable) mListView.getBackground();
    }

    private GradientDrawable getMenuGradientDrawable() {
        return (GradientDrawable) getMenuLayerDrawable().getDrawable(INDEX_MENU_ITEM);
    }

    /**
     * Updates the floating menu to be fixed at the side of the screen.
     */
    private void updateLocationWith(@Alignment int side, float percentageCurrentY) {
        mCurrentLayoutParams.x = (side == Alignment.RIGHT) ? getMaxWindowX() : MIN_WINDOW_X;
        mCurrentLayoutParams.y = (int) (percentageCurrentY * getMaxWindowY());
        mWindowManager.updateViewLayout(this, mCurrentLayoutParams);
    }

    private void updateOffsetWith(@ShapeType int shapeType, @Alignment int side) {
        final float halfWidth = getLayoutWidth() / 2.0f;
        final float offset = (shapeType == ShapeType.OVAL) ? 0 : halfWidth;
        mListView.animate().translationX(side == Alignment.RIGHT ? offset : -offset);
    }

    private void updateMarginsWith(@Alignment int side) {
        final LayoutParams layoutParams = (LayoutParams) mListView.getLayoutParams();
        final int marginLeft = (side == Alignment.LEFT) ? 0 : mMargin;
        final int marginRight = (side == Alignment.RIGHT) ? 0 : mMargin;

        if (marginLeft == layoutParams.leftMargin
                && marginRight == layoutParams.rightMargin) {
            return;
        }

        layoutParams.setMargins(marginLeft, mMargin, marginRight, mMargin);
        mListView.setLayoutParams(layoutParams);
    }

    private void updateColor() {
        final int menuColorResId = R.color.accessibility_floating_menu_background;
        getMenuGradientDrawable().setColor(getResources().getColor(menuColorResId));
    }

    private void updateStrokeWith(int uiMode, @Alignment int side) {
        updateInsetWith(uiMode, side);

        final boolean isNightMode =
                (uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES;
        final Resources res = getResources();
        final int width =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_stroke_width);
        final int strokeWidth = isNightMode ? width : 0;
        final int strokeColor = res.getColor(R.color.accessibility_floating_menu_stroke_dark);
        getMenuGradientDrawable().setStroke(strokeWidth, strokeColor);
    }

    private void updateRadiusWith(@SizeType int sizeType, @RadiusType int radiusType,
            int itemCount) {
        mRadius =
                getResources().getDimensionPixelSize(getRadiusResId(sizeType, itemCount));
        setRadius(mRadius, radiusType);
    }

    private void updateInsetWith(int uiMode, @Alignment int side) {
        final boolean isNightMode =
                (uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES;

        final int layerInset = isNightMode ? mInset : 0;
        final int insetLeft = (side == Alignment.LEFT) ? layerInset : 0;
        final int insetRight = (side == Alignment.RIGHT) ? layerInset : 0;
        setInset(insetLeft, insetRight);
    }

    private void setInset(int left, int right) {
        final LayerDrawable layerDrawable = getMenuLayerDrawable();
        if (layerDrawable.getLayerInsetLeft(INDEX_MENU_ITEM) == left
                && layerDrawable.getLayerInsetRight(INDEX_MENU_ITEM) == right) {
            return;
        }

        layerDrawable.setLayerInset(INDEX_MENU_ITEM, left, 0, right, 0);
    }

    @Alignment
    private int calculateCurrentAlignment() {
        return mCurrentLayoutParams.x >= ((MIN_WINDOW_X + getMaxWindowX()) / 2)
                ? Alignment.RIGHT
                : Alignment.LEFT;
    }

    private float calculateCurrentPercentageY() {
        return mCurrentLayoutParams.y / (float) getMaxWindowY();
    }

    private @DimenRes int getRadiusResId(@SizeType int sizeType, int itemCount) {
        return sizeType == SizeType.SMALL
                ? getSmallSizeResIdWith(itemCount)
                : getLargeSizeResIdWith(itemCount);
    }

    private int getSmallSizeResIdWith(int itemCount) {
        return itemCount > 1
                ? R.dimen.accessibility_floating_menu_small_multiple_radius
                : R.dimen.accessibility_floating_menu_small_single_radius;
    }

    private int getLargeSizeResIdWith(int itemCount) {
        return itemCount > 1
                ? R.dimen.accessibility_floating_menu_large_multiple_radius
                : R.dimen.accessibility_floating_menu_large_single_radius;
    }

    @VisibleForTesting
    Rect getAvailableBounds() {
        return new Rect(0, 0, mScreenWidth - getWindowWidth(), mScreenHeight - getWindowHeight());
    }

    private int getLayoutWidth() {
        return mPadding * 2 + mIconWidth;
    }

    private int getLayoutHeight() {
        return Math.min(mScreenHeight - mMargin * 2,
                (mPadding + mIconHeight) * mTargets.size() + mPadding);
    }

    private int getWindowWidth() {
        return mMargin + getLayoutWidth();
    }

    private int getWindowHeight() {
        return Math.min(mScreenHeight, mMargin * 2 + getLayoutHeight());
    }

    private void setSystemGestureExclusion() {
        final Rect excludeZone =
                new Rect(0, 0, getWindowWidth(), getWindowHeight());
        post(() -> setSystemGestureExclusionRects(
                mIsShowing
                        ? Collections.singletonList(excludeZone)
                        : Collections.emptyList()));
    }
}
