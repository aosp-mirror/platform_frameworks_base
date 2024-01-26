/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.annotation.SuppressLint;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.SettingsStringUtil;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.systemui.Flags;
import com.android.systemui.util.settings.SecureSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The container view displays the accessibility features.
 */
@SuppressLint("ViewConstructor")
class MenuView extends FrameLayout implements
        ViewTreeObserver.OnComputeInternalInsetsListener, ComponentCallbacks {
    private static final int INDEX_MENU_ITEM = 0;
    private final List<AccessibilityTarget> mTargetFeatures = new ArrayList<>();
    private final AccessibilityTargetAdapter mAdapter;
    private final MenuViewModel mMenuViewModel;
    private final Rect mBoundsInParent = new Rect();
    private final RecyclerView mTargetFeaturesView;
    private final ViewTreeObserver.OnDrawListener mSystemGestureExcludeUpdater =
            this::updateSystemGestureExcludeRects;
    private final Observer<MenuFadeEffectInfo> mFadeEffectInfoObserver =
            this::onMenuFadeEffectInfoChanged;
    private final Observer<Boolean> mMoveToTuckedObserver = this::onMoveToTucked;
    private final Observer<Position> mPercentagePositionObserver = this::onPercentagePosition;
    private final Observer<Integer> mSizeTypeObserver = this::onSizeTypeChanged;
    private final Observer<List<AccessibilityTarget>> mTargetFeaturesObserver =
            this::onTargetFeaturesChanged;
    private final MenuViewAppearance mMenuViewAppearance;

    private boolean mIsMoveToTucked;

    private final MenuAnimationController mMenuAnimationController;
    private OnTargetFeaturesChangeListener mFeaturesChangeListener;
    private OnMoveToTuckedListener mMoveToTuckedListener;
    private SecureSettings mSecureSettings;

    MenuView(Context context, MenuViewModel menuViewModel, MenuViewAppearance menuViewAppearance,
            SecureSettings secureSettings) {
        super(context);

        mMenuViewModel = menuViewModel;
        mMenuViewAppearance = menuViewAppearance;
        mSecureSettings = secureSettings;
        mMenuAnimationController = new MenuAnimationController(this, menuViewAppearance);
        mAdapter = new AccessibilityTargetAdapter(mTargetFeatures);
        mTargetFeaturesView = new RecyclerView(context);
        mTargetFeaturesView.setAdapter(mAdapter);
        mTargetFeaturesView.setLayoutManager(new LinearLayoutManager(context));
        setLayoutParams(new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        // Avoid drawing out of bounds of the parent view
        setClipToOutline(true);

        loadLayoutResources();

        addView(mTargetFeaturesView);
    }

    @Override
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo inoutInfo) {
        inoutInfo.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        if (getVisibility() == VISIBLE) {
            inoutInfo.touchableRegion.union(mBoundsInParent);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        loadLayoutResources();

        mTargetFeaturesView.setOverScrollMode(mMenuViewAppearance.getMenuScrollMode());
    }

    @Override
    public void onLowMemory() {
        // Do nothing.
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        getContext().registerComponentCallbacks(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        getContext().unregisterComponentCallbacks(this);
    }

    void setOnTargetFeaturesChangeListener(OnTargetFeaturesChangeListener listener) {
        mFeaturesChangeListener = listener;
    }

    void setMoveToTuckedListener(OnMoveToTuckedListener listener) {
        mMoveToTuckedListener = listener;
    }

    void addOnItemTouchListenerToList(RecyclerView.OnItemTouchListener listener) {
        mTargetFeaturesView.addOnItemTouchListener(listener);
    }

    MenuAnimationController getMenuAnimationController() {
        return mMenuAnimationController;
    }

    @SuppressLint("NotifyDataSetChanged")
    private void onItemSizeChanged() {
        mAdapter.setItemPadding(mMenuViewAppearance.getMenuPadding());
        mAdapter.setIconWidthHeight(mMenuViewAppearance.getMenuIconSize());
        mAdapter.notifyDataSetChanged();
    }

    private void onSizeChanged() {
        mBoundsInParent.set(mBoundsInParent.left, mBoundsInParent.top,
                mBoundsInParent.left + mMenuViewAppearance.getMenuWidth(),
                mBoundsInParent.top + mMenuViewAppearance.getMenuHeight());

        final FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) getLayoutParams();
        layoutParams.height = mMenuViewAppearance.getMenuHeight();
        setLayoutParams(layoutParams);
    }

    void onEdgeChangedIfNeeded() {
        final Rect draggableBounds = mMenuViewAppearance.getMenuDraggableBounds();
        if (getTranslationX() != draggableBounds.left
                && getTranslationX() != draggableBounds.right) {
            return;
        }

        onEdgeChanged();
    }

    void onEdgeChanged() {
        final int[] insets = mMenuViewAppearance.getMenuInsets();
        getContainerViewInsetLayer().setLayerInset(INDEX_MENU_ITEM, insets[0], insets[1], insets[2],
                insets[3]);

        final GradientDrawable gradientDrawable = getContainerViewGradient();
        gradientDrawable.setStroke(mMenuViewAppearance.getMenuStrokeWidth(),
                mMenuViewAppearance.getMenuStrokeColor());
        if (Flags.floatingMenuRadiiAnimation()) {
            mMenuAnimationController.startRadiiAnimation(mMenuViewAppearance.getMenuRadii());
        } else {
            gradientDrawable.setCornerRadii(mMenuViewAppearance.getMenuRadii());
        }
    }

    void setRadii(float[] radii) {
        getContainerViewGradient().setCornerRadii(radii);
    }

    private void onMoveToTucked(boolean isMoveToTucked) {
        mIsMoveToTucked = isMoveToTucked;

        onPositionChanged();
    }

    private void onPercentagePosition(Position percentagePosition) {
        mMenuViewAppearance.setPercentagePosition(percentagePosition);

        onPositionChanged();
    }

    void onPositionChanged() {
        onPositionChanged(/* animateMovement = */ false);
    }

    void onPositionChanged(boolean animateMovement) {
        final PointF position;
        if (isMoveToTucked()) {
            position = mMenuAnimationController.getTuckedMenuPosition();
        } else {
            position = getMenuPosition();
        }

        // We can skip animating if FAB is not visible
        if (Flags.floatingMenuImeDisplacementAnimation()
                && animateMovement && getVisibility() == VISIBLE) {
            mMenuAnimationController.moveToPosition(position, /* animateMovement = */ true);
            // onArrivalAtPosition() is called at the end of the animation.
        } else {
            mMenuAnimationController.moveToPosition(position);
            onArrivalAtPosition(); // no animation, so we call this immediately.
        }
    }

    void onArrivalAtPosition() {
        final PointF position = getMenuPosition();
        onBoundsInParentChanged((int) position.x, (int) position.y);

        if (isMoveToTucked()) {
            mMenuAnimationController.moveToEdgeAndHide();
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void onSizeTypeChanged(int newSizeType) {
        mMenuAnimationController.fadeInNowIfEnabled();

        mMenuViewAppearance.setSizeType(newSizeType);

        mAdapter.setItemPadding(mMenuViewAppearance.getMenuPadding());
        mAdapter.setIconWidthHeight(mMenuViewAppearance.getMenuIconSize());
        mAdapter.notifyDataSetChanged();

        onSizeChanged();
        onEdgeChanged();
        onPositionChanged();

        mMenuAnimationController.fadeOutIfEnabled();
    }

    private void onTargetFeaturesChanged(List<AccessibilityTarget> newTargetFeatures) {
        mMenuAnimationController.fadeInNowIfEnabled();

        final List<AccessibilityTarget> targetFeatures =
                Collections.unmodifiableList(mTargetFeatures.stream().toList());
        mTargetFeatures.clear();
        mTargetFeatures.addAll(newTargetFeatures);
        mMenuViewAppearance.setTargetFeaturesSize(newTargetFeatures.size());
        mTargetFeaturesView.setOverScrollMode(mMenuViewAppearance.getMenuScrollMode());
        DiffUtil.calculateDiff(
                new MenuTargetsCallback(targetFeatures, newTargetFeatures)).dispatchUpdatesTo(
                mAdapter);

        onSizeChanged();
        onEdgeChanged();
        onPositionChanged();

        if (mFeaturesChangeListener != null) {
            mFeaturesChangeListener.onChange(newTargetFeatures);
        }

        mMenuAnimationController.fadeOutIfEnabled();
    }

    private void onMenuFadeEffectInfoChanged(MenuFadeEffectInfo fadeEffectInfo) {
        mMenuAnimationController.updateOpacityWith(fadeEffectInfo.isFadeEffectEnabled(),
                fadeEffectInfo.getOpacity());
    }

    Rect getMenuDraggableBounds() {
        return mMenuViewAppearance.getMenuDraggableBounds();
    }

    Rect getMenuDraggableBoundsExcludeIme() {
        return mMenuViewAppearance.getMenuDraggableBoundsExcludeIme();
    }

    int getMenuHeight() {
        return mMenuViewAppearance.getMenuHeight();
    }

    int getMenuWidth() {
        return mMenuViewAppearance.getMenuWidth();
    }

    PointF getMenuPosition() {
        return mMenuViewAppearance.getMenuPosition();
    }

    RecyclerView getTargetFeaturesView() {
        return mTargetFeaturesView;
    }

    void persistPositionAndUpdateEdge(Position percentagePosition) {
        mMenuViewModel.updateMenuSavingPosition(percentagePosition);
        mMenuViewAppearance.setPercentagePosition(percentagePosition);

        onEdgeChangedIfNeeded();
    }

    boolean isMoveToTucked() {
        return mIsMoveToTucked;
    }

    void updateMenuMoveToTucked(boolean isMoveToTucked) {
        mIsMoveToTucked = isMoveToTucked;
        mMenuViewModel.updateMenuMoveToTucked(isMoveToTucked);
        if (mMoveToTuckedListener != null) {
            mMoveToTuckedListener.onMoveToTuckedChanged(isMoveToTucked);
        }

        if (Flags.floatingMenuOverlapsNavBarsFlag() && !Flags.floatingMenuAnimatedTuck()) {
            if (isMoveToTucked) {
                final float halfWidth = getMenuWidth() / 2.0f;
                final boolean isOnLeftSide = mMenuAnimationController.isOnLeftSide();
                final Rect clipBounds = new Rect(
                        (int) (!isOnLeftSide ? 0 : halfWidth),
                        0,
                        (int) (!isOnLeftSide ? halfWidth : getMenuWidth()),
                        getMenuHeight()
                );
                setClipBounds(clipBounds);
            } else {
                setClipBounds(null);
            }
        }
    }


    /**
     * Uses the touch events from the parent view to identify if users clicked the extra
     * space of the menu view. If yes, will use the percentage position and update the
     * translations of the menu view to meet the effect of moving out from the edge. It’s only
     * used when the menu view is hidden to the screen edge.
     *
     * @param x the current x of the touch event from the parent {@link MenuViewLayer} of the
     * {@link MenuView}.
     * @param y the current y of the touch event from the parent {@link MenuViewLayer} of the
     * {@link MenuView}.
     * @return true if consume the touch event, otherwise false.
     */
    boolean maybeMoveOutEdgeAndShow(int x, int y) {
        // Utilizes the touch region of the parent view to implement that users could tap extra
        // the space region to show the menu from the edge.
        if (!isMoveToTucked() || !mBoundsInParent.contains(x, y)) {
            return false;
        }

        mMenuAnimationController.fadeInNowIfEnabled();

        mMenuAnimationController.moveOutEdgeAndShow();

        mMenuAnimationController.fadeOutIfEnabled();
        return true;
    }

    void show() {
        mMenuViewModel.getPercentagePositionData().observeForever(mPercentagePositionObserver);
        mMenuViewModel.getFadeEffectInfoData().observeForever(mFadeEffectInfoObserver);
        mMenuViewModel.getTargetFeaturesData().observeForever(mTargetFeaturesObserver);
        mMenuViewModel.getSizeTypeData().observeForever(mSizeTypeObserver);
        mMenuViewModel.getMoveToTuckedData().observeForever(mMoveToTuckedObserver);
        setVisibility(VISIBLE);
        mMenuViewModel.registerObserversAndCallbacks();
        getViewTreeObserver().addOnComputeInternalInsetsListener(this);
        getViewTreeObserver().addOnDrawListener(mSystemGestureExcludeUpdater);
    }

    void hide() {
        setVisibility(GONE);
        mBoundsInParent.setEmpty();
        mMenuViewModel.getPercentagePositionData().removeObserver(mPercentagePositionObserver);
        mMenuViewModel.getFadeEffectInfoData().removeObserver(mFadeEffectInfoObserver);
        mMenuViewModel.getTargetFeaturesData().removeObserver(mTargetFeaturesObserver);
        mMenuViewModel.getSizeTypeData().removeObserver(mSizeTypeObserver);
        mMenuViewModel.getMoveToTuckedData().removeObserver(mMoveToTuckedObserver);
        mMenuViewModel.unregisterObserversAndCallbacks();
        getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
        getViewTreeObserver().removeOnDrawListener(mSystemGestureExcludeUpdater);
    }

    void onDraggingStart() {
        final int[] insets = mMenuViewAppearance.getMenuMovingStateInsets();
        getContainerViewInsetLayer().setLayerInset(INDEX_MENU_ITEM, insets[0], insets[1], insets[2],
                insets[3]);

        if (Flags.floatingMenuRadiiAnimation()) {
            mMenuAnimationController.startRadiiAnimation(
                    mMenuViewAppearance.getMenuMovingStateRadii());
        } else {
            final GradientDrawable gradientDrawable = getContainerViewGradient();
            gradientDrawable.setCornerRadii(mMenuViewAppearance.getMenuMovingStateRadii());
        }
    }

    void onBoundsInParentChanged(int newLeft, int newTop) {
        mBoundsInParent.offsetTo(newLeft, newTop);
    }

    void loadLayoutResources() {
        mMenuViewAppearance.update();

        mTargetFeaturesView.setContentDescription(mMenuViewAppearance.getContentDescription());
        setBackground(mMenuViewAppearance.getMenuBackground());
        setElevation(mMenuViewAppearance.getMenuElevation());
        onItemSizeChanged();
        onSizeChanged();
        onEdgeChanged();
        onPositionChanged();
    }

    void gotoEditScreen() {
        if (!Flags.floatingMenuDragToEdit()) {
            return;
        }
        mMenuAnimationController.flingMenuThenSpringToEdge(
                getMenuPosition().x, 100f, 0f);
        mContext.startActivity(getIntentForEditScreen());
    }

    Intent getIntentForEditScreen() {
        List<String> targets = new SettingsStringUtil.ColonDelimitedSet.OfStrings(
                mSecureSettings.getStringForUser(
                        Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS,
                        UserHandle.USER_CURRENT)).stream().toList();

        Intent intent = new Intent(
                Settings.ACTION_ACCESSIBILITY_SHORTCUT_SETTINGS);
        Bundle args = new Bundle();
        Bundle fragmentArgs = new Bundle();
        fragmentArgs.putStringArray("targets", targets.toArray(new String[0]));
        args.putBundle(":settings:show_fragment_args", fragmentArgs);
        // TODO: b/318748373 - The fragment should set its own title using the targets
        args.putString(
                ":settings:show_fragment_title", "Accessibility Shortcut");
        intent.replaceExtras(args);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }

    private InstantInsetLayerDrawable getContainerViewInsetLayer() {
        return (InstantInsetLayerDrawable) getBackground();
    }

    private GradientDrawable getContainerViewGradient() {
        return (GradientDrawable) getContainerViewInsetLayer().getDrawable(INDEX_MENU_ITEM);
    }

    private void updateSystemGestureExcludeRects() {
        final ViewGroup parentView = (ViewGroup) getParent();
        parentView.setSystemGestureExclusionRects(Collections.singletonList(mBoundsInParent));
    }

    /**
     * Interface definition for the {@link AccessibilityTarget} list changes.
     */
    interface OnTargetFeaturesChangeListener {
        /**
         * Called when the list of accessibility target features was updated. This will be
         * invoked when the end of {@code onTargetFeaturesChanged}.
         *
         * @param newTargetFeatures the list related to the current accessibility features.
         */
        void onChange(List<AccessibilityTarget> newTargetFeatures);
    }

    /**
     * Interface containing a callback for when MoveToTucked changes.
     */
    interface OnMoveToTuckedListener {
        void onMoveToTuckedChanged(boolean moveToTucked);
    }
}
