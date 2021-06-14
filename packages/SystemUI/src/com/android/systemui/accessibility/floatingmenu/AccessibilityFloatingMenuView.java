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

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.util.MathUtils.constrain;
import static android.util.MathUtils.sq;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.navigationBars;

import static java.util.Objects.requireNonNull;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Insets;
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
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.animation.Animation;
import android.view.animation.OvershootInterpolator;
import android.view.animation.TranslateAnimation;
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
import java.util.Optional;

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
    private static final int MIN_WINDOW_Y = 0;

    private static final int ANIMATION_START_OFFSET = 600;
    private static final int ANIMATION_DURATION_MS = 600;
    private static final float ANIMATION_TO_X_VALUE = 0.5f;

    private boolean mIsFadeEffectEnabled;
    private boolean mIsShowing;
    private boolean mIsDownInEnlargedTouchArea;
    private boolean mIsDragging = false;
    private boolean mImeVisibility;
    @Alignment
    private int mAlignment;
    @SizeType
    private int mSizeType = SizeType.SMALL;
    @VisibleForTesting
    @ShapeType
    int mShapeType = ShapeType.OVAL;
    private int mTemporaryShapeType;
    @RadiusType
    private int mRadiusType;
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
    private final Position mPosition;
    private float mSquareScaledTouchSlop;
    private final Configuration mLastConfiguration;
    private Optional<OnDragEndListener> mOnDragEndListener = Optional.empty();
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

    /**
     * Interface for a callback to be invoked when the floating menu was dragging.
     */
    interface OnDragEndListener {

        /**
         * Called when a drag is completed.
         *
         * @param position Stores information about the position
         */
        void onDragEnd(Position position);
    }

    public AccessibilityFloatingMenuView(Context context, @NonNull Position position) {
        this(context, position, new RecyclerView(context));
    }

    @VisibleForTesting
    AccessibilityFloatingMenuView(Context context, @NonNull Position position,
            RecyclerView listView) {
        super(context);

        mListView = listView;
        mWindowManager = context.getSystemService(WindowManager.class);
        mLastConfiguration = new Configuration(getResources().getConfiguration());
        mAdapter = new AccessibilityTargetAdapter(mTargets);
        mUiHandler = createUiHandler();
        mPosition = position;
        mAlignment = transformToAlignment(mPosition.getPercentageX());
        mRadiusType = (mAlignment == Alignment.RIGHT)
                ? RadiusType.LEFT_HALF_OVAL
                : RadiusType.RIGHT_HALF_OVAL;

        updateDimensions();

        mCurrentLayoutParams = createDefaultLayoutParams();

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
                mPosition.update(transformCurrentPercentageXToEdge(),
                        calculateCurrentPercentageY());
                mAlignment = transformToAlignment(mPosition.getPercentageX());

                updateLocationWith(mPosition);

                updateInsetWith(getResources().getConfiguration().uiMode, mAlignment);

                mRadiusType = (mAlignment == Alignment.RIGHT)
                        ? RadiusType.LEFT_HALF_OVAL
                        : RadiusType.RIGHT_HALF_OVAL;
                updateRadiusWith(mSizeType, mRadiusType, mTargets.size());

                fadeOut();

                mOnDragEndListener.ifPresent(
                        onDragEndListener -> onDragEndListener.onDragEnd(mPosition));
            }
        });


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
                    mCurrentLayoutParams.x =
                            constrain(newWindowX, getMinWindowX(), getMaxWindowX());
                    mCurrentLayoutParams.y = constrain(newWindowY, MIN_WINDOW_Y, getMaxWindowY());
                    mWindowManager.updateViewLayout(this, mCurrentLayoutParams);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mIsDragging) {
                    mIsDragging = false;

                    final int minX = getMinWindowX();
                    final int maxX = getMaxWindowX();
                    final int endX = mCurrentLayoutParams.x > ((minX + maxX) / 2)
                            ? maxX : minX;
                    final int endY = mCurrentLayoutParams.y;
                    snapToLocation(endX, endY);

                    setShapeType(mTemporaryShapeType);

                    // Avoid triggering the listener of the item.
                    return true;
                }

                // Must switch the oval shape type before tapping the corresponding item in the
                // list view, otherwise it can't work on it.
                if (!isOvalShape()) {
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
        fadeIn();

        final Rect bounds = getAvailableBounds();
        if (action == R.id.action_move_top_left) {
            setShapeType(ShapeType.OVAL);
            snapToLocation(bounds.left, bounds.top);
            return true;
        }

        if (action == R.id.action_move_top_right) {
            setShapeType(ShapeType.OVAL);
            snapToLocation(bounds.right, bounds.top);
            return true;
        }

        if (action == R.id.action_move_bottom_left) {
            setShapeType(ShapeType.OVAL);
            snapToLocation(bounds.left, bounds.bottom);
            return true;
        }

        if (action == R.id.action_move_bottom_right) {
            setShapeType(ShapeType.OVAL);
            snapToLocation(bounds.right, bounds.bottom);
            return true;
        }

        if (action == R.id.action_move_to_edge_and_hide) {
            setShapeType(ShapeType.HALF_OVAL);
            return true;
        }

        if (action == R.id.action_move_out_edge_and_show) {
            setShapeType(ShapeType.OVAL);
            return true;
        }

        return super.performAccessibilityAction(action, arguments);
    }

    void show() {
        if (isShowing()) {
            return;
        }

        mIsShowing = true;
        mWindowManager.addView(this, mCurrentLayoutParams);

        setOnApplyWindowInsetsListener((view, insets) -> onWindowInsetsApplied(insets));
        setSystemGestureExclusion();
    }

    void hide() {
        if (!isShowing()) {
            return;
        }

        mIsShowing = false;
        mWindowManager.removeView(this);

        setOnApplyWindowInsetsListener(null);
        setSystemGestureExclusion();
    }

    boolean isShowing() {
        return mIsShowing;
    }

    boolean isOvalShape() {
        return mShapeType == ShapeType.OVAL;
    }

    void onTargetsChanged(List<AccessibilityTarget> newTargets) {
        fadeIn();

        mTargets.clear();
        mTargets.addAll(newTargets);
        onEnabledFeaturesChanged();

        updateRadiusWith(mSizeType, mRadiusType, mTargets.size());
        updateScrollModeWith(hasExceededMaxLayoutHeight());
        setSystemGestureExclusion();

        fadeOut();
    }

    void setSizeType(@SizeType int newSizeType) {
        fadeIn();

        mSizeType = newSizeType;

        updateItemViewWith(newSizeType);
        updateRadiusWith(newSizeType, mRadiusType, mTargets.size());

        // When the icon sized changed, the menu size and location will be impacted.
        updateLocationWith(mPosition);
        updateScrollModeWith(hasExceededMaxLayoutHeight());
        updateOffsetWith(mShapeType, mAlignment);
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

    public void setOnDragEndListener(OnDragEndListener onDragEndListener) {
        mOnDragEndListener = Optional.ofNullable(onDragEndListener);
    }

    void startTranslateXAnimation() {
        fadeIn();

        final float toXValue = (mAlignment == Alignment.RIGHT)
                ? ANIMATION_TO_X_VALUE
                : -ANIMATION_TO_X_VALUE;
        final TranslateAnimation animation =
                new TranslateAnimation(Animation.RELATIVE_TO_SELF, 0,
                        Animation.RELATIVE_TO_SELF, toXValue,
                        Animation.RELATIVE_TO_SELF, 0,
                        Animation.RELATIVE_TO_SELF, 0);
        animation.setDuration(ANIMATION_DURATION_MS);
        animation.setRepeatMode(Animation.REVERSE);
        animation.setInterpolator(new OvershootInterpolator());
        animation.setRepeatCount(Animation.INFINITE);
        animation.setStartOffset(ANIMATION_START_OFFSET);
        mListView.startAnimation(animation);
    }

    void stopTranslateXAnimation() {
        mListView.clearAnimation();

        fadeOut();
    }

    Rect getWindowLocationOnScreen() {
        final int left = mCurrentLayoutParams.x;
        final int top = mCurrentLayoutParams.y;
        return new Rect(left, top, left + getWindowWidth(), top + getWindowHeight());
    }

    void updateOpacityWith(boolean isFadeEffectEnabled, float newOpacityValue) {
        mIsFadeEffectEnabled = isFadeEffectEnabled;
        mFadeOutValue = newOpacityValue;

        mFadeOutAnimator.cancel();
        mFadeOutAnimator.setFloatValues(1.0f, mFadeOutValue);
        setAlpha(mIsFadeEffectEnabled ? mFadeOutValue : /* completely opaque */ 1.0f);
    }

    void onEnabledFeaturesChanged() {
        mAdapter.notifyDataSetChanged();
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

        final int moveEdgeId = mShapeType == ShapeType.OVAL
                ? R.id.action_move_to_edge_and_hide
                : R.id.action_move_out_edge_and_show;
        final int moveEdgeTextResId = mShapeType == ShapeType.OVAL
                ? R.string.accessibility_floating_button_action_move_to_edge_and_hide_to_half
                : R.string.accessibility_floating_button_action_move_out_edge_and_show;
        final AccessibilityAction moveToOrOutEdge =
                new AccessibilityAction(moveEdgeId, res.getString(moveEdgeTextResId));
        info.addAction(moveToOrOutEdge);
    }

    private boolean onTouched(MotionEvent event) {
        final int action = event.getAction();
        final int currentX = (int) event.getX();
        final int currentY = (int) event.getY();

        final int marginStartEnd = getMarginStartEndWith(mLastConfiguration);
        final Rect touchDelegateBounds =
                new Rect(marginStartEnd, mMargin, marginStartEnd + getLayoutWidth(),
                        mMargin + getLayoutHeight());
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

    private WindowInsets onWindowInsetsApplied(WindowInsets insets) {
        final boolean currentImeVisibility = insets.isVisible(ime());
        if (currentImeVisibility != mImeVisibility) {
            mImeVisibility = currentImeVisibility;
            updateLocationWith(mPosition);
        }

        return insets;
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
        return new Handler(requireNonNull(Looper.myLooper(), "looper must not be null"));
    }

    private void updateDimensions() {
        final Resources res = getResources();
        final DisplayMetrics dm = res.getDisplayMetrics();
        mScreenWidth = dm.widthPixels;
        mScreenHeight = dm.heightPixels;
        mMargin =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_margin);
        mInset =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_stroke_inset);

        mSquareScaledTouchSlop =
                sq(ViewConfiguration.get(getContext()).getScaledTouchSlop());

        updateItemViewDimensionsWith(mSizeType);
    }

    private void updateItemViewDimensionsWith(@SizeType int sizeType) {
        final Resources res = getResources();
        final int paddingResId =
                sizeType == SizeType.SMALL
                        ? R.dimen.accessibility_floating_menu_small_padding
                        : R.dimen.accessibility_floating_menu_large_padding;
        mPadding = res.getDimensionPixelSize(paddingResId);

        final int iconResId =
                sizeType == SizeType.SMALL
                        ? R.dimen.accessibility_floating_menu_small_width_height
                        : R.dimen.accessibility_floating_menu_large_width_height;
        mIconWidth = res.getDimensionPixelSize(iconResId);
        mIconHeight = mIconWidth;
    }

    private void updateItemViewWith(@SizeType int sizeType) {
        updateItemViewDimensionsWith(sizeType);

        mAdapter.setItemPadding(mPadding);
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
        updateListViewWith(mLastConfiguration);

        addView(mListView);
    }

    private void updateListViewWith(Configuration configuration) {
        updateMarginWith(configuration);

        final int elevation =
                getResources().getDimensionPixelSize(R.dimen.accessibility_floating_menu_elevation);
        mListView.setElevation(elevation);
    }

    private WindowManager.LayoutParams createDefaultLayoutParams() {
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.receiveInsetsIgnoringZOrder = true;
        params.windowAnimations = android.R.style.Animation_Translucent;
        params.gravity = Gravity.START | Gravity.TOP;
        params.x = (mAlignment == Alignment.RIGHT) ? getMaxWindowX() : getMinWindowX();
//        params.y = (int) (mPosition.getPercentageY() * getMaxWindowY());
        final int currentLayoutY = (int) (mPosition.getPercentageY() * getMaxWindowY());
        params.y = Math.max(MIN_WINDOW_Y, currentLayoutY - getInterval());
        updateAccessibilityTitle(params);
        return params;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mLastConfiguration.setTo(newConfig);

        final int diff = newConfig.diff(mLastConfiguration);
        if ((diff & ActivityInfo.CONFIG_LOCALE) != 0) {
            updateAccessibilityTitle(mCurrentLayoutParams);
        }

        updateDimensions();
        updateListViewWith(newConfig);
        updateItemViewWith(mSizeType);
        updateColor();
        updateStrokeWith(newConfig.uiMode, mAlignment);
        updateLocationWith(mPosition);
        updateRadiusWith(mSizeType, mRadiusType, mTargets.size());
        updateScrollModeWith(hasExceededMaxLayoutHeight());
        setSystemGestureExclusion();
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

    private int getMinWindowX() {
        return -getMarginStartEndWith(mLastConfiguration);
    }

    private int getMaxWindowX() {
        return mScreenWidth - getMarginStartEndWith(mLastConfiguration) - getLayoutWidth();
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
    private void updateLocationWith(Position position) {
        final @Alignment int alignment = transformToAlignment(position.getPercentageX());
        mCurrentLayoutParams.x = (alignment == Alignment.RIGHT) ? getMaxWindowX() : getMinWindowX();
        final int currentLayoutY = (int) (position.getPercentageY() * getMaxWindowY());
        mCurrentLayoutParams.y = Math.max(MIN_WINDOW_Y, currentLayoutY - getInterval());
        mWindowManager.updateViewLayout(this, mCurrentLayoutParams);
    }

    /**
     * Gets the moving interval to not overlap between the keyboard and menu view.
     *
     * @return the moving interval if they overlap each other, otherwise 0.
     */
    private int getInterval() {
        if (!mImeVisibility) {
            return 0;
        }

        final WindowMetrics windowMetrics = mWindowManager.getCurrentWindowMetrics();
        final Insets imeInsets = windowMetrics.getWindowInsets().getInsets(
                ime() | navigationBars());
        final int imeY = mScreenHeight - imeInsets.bottom;
        final int layoutBottomY = mCurrentLayoutParams.y + getWindowHeight();

        return layoutBottomY > imeY ? (layoutBottomY - imeY) : 0;
    }

    private void updateMarginWith(Configuration configuration) {
        // Avoid overlapping with system bars under landscape mode, update the margins of the menu
        // to align the edge of system bars.
        final int marginStartEnd = getMarginStartEndWith(configuration);
        final LayoutParams layoutParams = (FrameLayout.LayoutParams) mListView.getLayoutParams();
        layoutParams.setMargins(marginStartEnd, mMargin, marginStartEnd, mMargin);
        mListView.setLayoutParams(layoutParams);
    }

    private void updateOffsetWith(@ShapeType int shapeType, @Alignment int side) {
        final float halfWidth = getLayoutWidth() / 2.0f;
        final float offset = (shapeType == ShapeType.OVAL) ? 0 : halfWidth;
        mListView.animate().translationX(side == Alignment.RIGHT ? offset : -offset);
    }

    private void updateScrollModeWith(boolean hasExceededMaxLayoutHeight) {
        mListView.setOverScrollMode(hasExceededMaxLayoutHeight
                ? OVER_SCROLL_ALWAYS
                : OVER_SCROLL_NEVER);
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

    private void updateAccessibilityTitle(WindowManager.LayoutParams params) {
        params.accessibilityTitle = getResources().getString(
                com.android.internal.R.string.accessibility_select_shortcut_menu_title);
    }

    private void setInset(int left, int right) {
        final LayerDrawable layerDrawable = getMenuLayerDrawable();
        if (layerDrawable.getLayerInsetLeft(INDEX_MENU_ITEM) == left
                && layerDrawable.getLayerInsetRight(INDEX_MENU_ITEM) == right) {
            return;
        }

        layerDrawable.setLayerInset(INDEX_MENU_ITEM, left, 0, right, 0);
    }

    @VisibleForTesting
    boolean hasExceededMaxLayoutHeight() {
        return calculateActualLayoutHeight() > getMaxLayoutHeight();
    }

    @Alignment
    private int transformToAlignment(@FloatRange(from = 0.0, to = 1.0) float percentageX) {
        return (percentageX < 0.5f) ? Alignment.LEFT : Alignment.RIGHT;
    }

    private float transformCurrentPercentageXToEdge() {
        final float percentageX = calculateCurrentPercentageX();
        return (percentageX < 0.5) ? 0.0f : 1.0f;
    }

    private float calculateCurrentPercentageX() {
        return mCurrentLayoutParams.x / (float) getMaxWindowX();
    }

    private float calculateCurrentPercentageY() {
        return mCurrentLayoutParams.y / (float) getMaxWindowY();
    }

    private int calculateActualLayoutHeight() {
        return (mPadding + mIconHeight) * mTargets.size() + mPadding;
    }

    private int getMarginStartEndWith(Configuration configuration) {
        return configuration != null
                && configuration.orientation == ORIENTATION_PORTRAIT
                ? mMargin : 0;
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

    private int getMaxLayoutHeight() {
        return mScreenHeight - mMargin * 2;
    }

    private int getLayoutWidth() {
        return mPadding * 2 + mIconWidth;
    }

    private int getLayoutHeight() {
        return Math.min(getMaxLayoutHeight(), calculateActualLayoutHeight());
    }

    private int getWindowWidth() {
        return getMarginStartEndWith(mLastConfiguration) * 2 + getLayoutWidth();
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
