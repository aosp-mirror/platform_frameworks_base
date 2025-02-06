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
import android.content.res.Configuration;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.modules.expresslog.Counter;
import com.android.settingslib.bluetooth.HearingAidDeviceManager;
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
    private final Observer<Integer> mHearingDeviceStatusObserver =
            this::updateHearingDeviceStatus;
    private final Observer<Integer> mHearingDeviceTargetIndexObserver =
            this::updateHearingDeviceTargetIndex;
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
        mTargetFeaturesView.setClipChildren(false);
        setLayoutParams(new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        // Avoid drawing out of bounds of the parent view
        setClipToOutline(true);

        loadLayoutResources();

        addView(mTargetFeaturesView);

        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
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
        mAdapter.setBadgeWidthHeight(mMenuViewAppearance.getBadgeIconSize());
        mAdapter.notifyDataSetChanged();
    }

    @SuppressLint("NotifyDataSetChanged")
    void onSideChanged() {
        // Badge should be on different side of Menu view's side.
        mAdapter.setBadgeOnLeftSide(!mMenuViewAppearance.isMenuOnLeftSide());
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
        mMenuAnimationController.startRadiiAnimation(mMenuViewAppearance.getMenuRadii());
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
        onSideChanged();
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
        if (animateMovement && getVisibility() == VISIBLE) {
            mMenuAnimationController.moveToPosition(position, /* animateMovement = */ true);
            // onArrivalAtPosition() is called at the end of the animation.
        } else {
            mMenuAnimationController.moveToPosition(position);
            onArrivalAtPosition(true); // no animation, so we call this immediately.
        }
    }

    void onArrivalAtPosition(boolean moveToEdgeIfTucked) {
        final PointF position = getMenuPosition();
        onBoundsInParentChanged((int) position.x, (int) position.y);

        if (isMoveToTucked() && moveToEdgeIfTucked) {
            mMenuAnimationController.moveToEdgeAndHide();
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void onSizeTypeChanged(int newSizeType) {
        mMenuAnimationController.fadeInNowIfEnabled();

        mMenuViewAppearance.setSizeType(newSizeType);

        mAdapter.setItemPadding(mMenuViewAppearance.getMenuPadding());
        mAdapter.setIconWidthHeight(mMenuViewAppearance.getMenuIconSize());
        mAdapter.setBadgeWidthHeight(mMenuViewAppearance.getBadgeIconSize());

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

        boolean shouldSendFeatureChangeNotification =
                com.android.systemui.Flags.floatingMenuNotifyTargetsChangedOnStrictDiff()
                    ? !areFeatureListsIdentical(targetFeatures, newTargetFeatures)
                    : true;
        if (mFeaturesChangeListener != null && shouldSendFeatureChangeNotification) {
            mFeaturesChangeListener.onChange(newTargetFeatures);
        }

        mMenuAnimationController.fadeOutIfEnabled();
    }

    /**
     * Returns true if the given feature lists are identical lists, i.e. the same list of {@link
     * AccessibilityTarget} (equality checked via UID) in the same order.
     */
    private boolean areFeatureListsIdentical(
            List<AccessibilityTarget> currentFeatures, List<AccessibilityTarget> newFeatures) {
        if (currentFeatures.size() != newFeatures.size()) {
            return false;
        }

        for (int i = 0; i < currentFeatures.size(); i++) {
            if (currentFeatures.get(i).getUid() != newFeatures.get(i).getUid()) {
                return false;
            }
        }

        return true;
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
        onSideChanged();
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
        if (com.android.settingslib.flags.Flags.hearingDeviceSetConnectionStatusReport()) {
            mMenuViewModel.loadHearingDeviceStatus().observeForever(mHearingDeviceStatusObserver);
            mMenuViewModel.getHearingDeviceTargetIndexData().observeForever(
                    mHearingDeviceTargetIndexObserver);
        }
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
        mMenuViewModel.getHearingDeviceStatusData().removeObserver(mHearingDeviceStatusObserver);
        mMenuViewModel.getHearingDeviceTargetIndexData().removeObserver(
                mHearingDeviceTargetIndexObserver);
        mMenuViewModel.unregisterObserversAndCallbacks();
        getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
        getViewTreeObserver().removeOnDrawListener(mSystemGestureExcludeUpdater);
    }

    void onDraggingStart() {
        final int[] insets = mMenuViewAppearance.getMenuMovingStateInsets();
        getContainerViewInsetLayer().setLayerInset(INDEX_MENU_ITEM, insets[0], insets[1], insets[2],
                insets[3]);

        mMenuAnimationController.startRadiiAnimation(
                mMenuViewAppearance.getMenuMovingStateRadii());
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

    void incrementTexMetric(String metric) {
        if (!Flags.floatingMenuDragToEdit()) {
            return;
        }
        Counter.logIncrement(metric);
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

    private void updateHearingDeviceStatus(@HearingAidDeviceManager.ConnectionStatus int status) {
        final int haStatus = mMenuViewModel.getHearingDeviceStatusData().getValue();
        final int haPosition = mMenuViewModel.getHearingDeviceTargetIndexData().getValue();
        if (haPosition >= 0) {
            mContext.getMainExecutor().execute(
                    () -> mAdapter.onHearingDeviceStatusChanged(haStatus, haPosition));
        }
    }

    private void updateHearingDeviceTargetIndex(int position) {
        final int haStatus = mMenuViewModel.getHearingDeviceStatusData().getValue();
        final int haPosition = mMenuViewModel.getHearingDeviceTargetIndexData().getValue();
        if (haPosition >= 0) {
            mContext.getMainExecutor().execute(
                    () -> mAdapter.onHearingDeviceStatusChanged(haStatus, haPosition));
        }
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
