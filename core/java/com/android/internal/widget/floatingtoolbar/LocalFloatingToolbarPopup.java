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

package com.android.internal.widget.floatingtoolbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Size;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.animation.Transformation;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A popup window used by the floating toolbar to render menu items in the local app process.
 *
 * This class is responsible for the rendering/animation of the floating toolbar.
 * It holds 2 panels (i.e. main panel and overflow panel) and an overflow button
 * to transition between panels.
 */
public final class LocalFloatingToolbarPopup implements FloatingToolbarPopup {

    /* Minimum and maximum number of items allowed in the overflow. */
    private static final int MIN_OVERFLOW_SIZE = 2;
    private static final int MAX_OVERFLOW_SIZE = 4;

    private final Context mContext;
    private final View mParent;  // Parent for the popup window.
    private final PopupWindow mPopupWindow;

    /* Margins between the popup window and its content. */
    private final int mMarginHorizontal;
    private final int mMarginVertical;

    /* View components */
    private final ViewGroup mContentContainer;  // holds all contents.
    private final ViewGroup mMainPanel;  // holds menu items that are initially displayed.
    // holds menu items hidden in the overflow.
    private final OverflowPanel mOverflowPanel;
    private final ImageButton mOverflowButton;  // opens/closes the overflow.
    /* overflow button drawables. */
    private final Drawable mArrow;
    private final Drawable mOverflow;
    private final AnimatedVectorDrawable mToArrow;
    private final AnimatedVectorDrawable mToOverflow;

    private final OverflowPanelViewHelper mOverflowPanelViewHelper;

    /* Animation interpolators. */
    private final Interpolator mLogAccelerateInterpolator;
    private final Interpolator mFastOutSlowInInterpolator;
    private final Interpolator mLinearOutSlowInInterpolator;
    private final Interpolator mFastOutLinearInInterpolator;

    /* Animations. */
    private final AnimatorSet mShowAnimation;
    private final AnimatorSet mDismissAnimation;
    private final AnimatorSet mHideAnimation;
    private final AnimationSet mOpenOverflowAnimation;
    private final AnimationSet mCloseOverflowAnimation;
    private final Animation.AnimationListener mOverflowAnimationListener;

    private final Rect mViewPortOnScreen = new Rect();  // portion of screen we can draw in.
    private final Point mCoordsOnWindow = new Point();  // popup window coordinates.
    /* Temporary data holders. Reset values before using. */
    private final int[] mTmpCoords = new int[2];

    private final Region mTouchableRegion = new Region();
    private final ViewTreeObserver.OnComputeInternalInsetsListener mInsetsComputer =
            info -> {
                info.contentInsets.setEmpty();
                info.visibleInsets.setEmpty();
                info.touchableRegion.set(mTouchableRegion);
                info.setTouchableInsets(
                        ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
            };

    private final int mLineHeight;
    private final int mIconTextSpacing;

    /**
     * @see OverflowPanelViewHelper#preparePopupContent().
     */
    private final Runnable mPreparePopupContentRTLHelper = new Runnable() {
        @Override
        public void run() {
            setPanelsStatesAtRestingPosition();
            setContentAreaAsTouchableSurface();
            mContentContainer.setAlpha(1);
        }
    };

    private boolean mDismissed = true; // tracks whether this popup is dismissed or dismissing.
    private boolean mHidden; // tracks whether this popup is hidden or hiding.

    /* Calculated sizes for panels and overflow button. */
    private final Size mOverflowButtonSize;
    private Size mOverflowPanelSize;  // Should be null when there is no overflow.
    private Size mMainPanelSize;

    /* Menu items and click listeners */
    private final Map<MenuItemRepr, MenuItem> mMenuItems = new LinkedHashMap<>();
    private MenuItem.OnMenuItemClickListener mOnMenuItemClickListener;
    private final View.OnClickListener mMenuItemButtonOnClickListener =
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mOnMenuItemClickListener == null) {
                        return;
                    }
                    final Object tag = v.getTag();
                    if (!(tag instanceof MenuItemRepr)) {
                        return;
                    }
                    final MenuItem menuItem = mMenuItems.get((MenuItemRepr) tag);
                    if (menuItem == null) {
                        return;
                    }
                    mOnMenuItemClickListener.onMenuItemClick(menuItem);
                }
            };

    private boolean mOpenOverflowUpwards;  // Whether the overflow opens upwards or downwards.
    private boolean mIsOverflowOpen;

    private int mTransitionDurationScale;  // Used to scale the toolbar transition duration.

    private final Rect mPreviousContentRect = new Rect();
    private int mSuggestedWidth;
    private boolean mWidthChanged = true;

    /**
     * Initializes a new floating toolbar popup.
     *
     * @param parent  A parent view to get the {@link android.view.View#getWindowToken()} token
     *      from.
     */
    public LocalFloatingToolbarPopup(Context context, View parent) {
        mParent = Objects.requireNonNull(parent);
        mContext = applyDefaultTheme(context);
        mContentContainer = createContentContainer(mContext);
        mPopupWindow = createPopupWindow(mContentContainer);
        mMarginHorizontal = parent.getResources()
                .getDimensionPixelSize(R.dimen.floating_toolbar_horizontal_margin);
        mMarginVertical = parent.getResources()
                .getDimensionPixelSize(R.dimen.floating_toolbar_vertical_margin);
        mLineHeight = context.getResources()
                .getDimensionPixelSize(R.dimen.floating_toolbar_height);
        mIconTextSpacing = context.getResources()
                .getDimensionPixelSize(R.dimen.floating_toolbar_icon_text_spacing);

        // Interpolators
        mLogAccelerateInterpolator = new LogAccelerateInterpolator();
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(
                mContext, android.R.interpolator.fast_out_slow_in);
        mLinearOutSlowInInterpolator = AnimationUtils.loadInterpolator(
                mContext, android.R.interpolator.linear_out_slow_in);
        mFastOutLinearInInterpolator = AnimationUtils.loadInterpolator(
                mContext, android.R.interpolator.fast_out_linear_in);

        // Drawables. Needed for views.
        mArrow = mContext.getResources()
                .getDrawable(R.drawable.ft_avd_tooverflow, mContext.getTheme());
        mArrow.setAutoMirrored(true);
        mOverflow = mContext.getResources()
                .getDrawable(R.drawable.ft_avd_toarrow, mContext.getTheme());
        mOverflow.setAutoMirrored(true);
        mToArrow = (AnimatedVectorDrawable) mContext.getResources()
                .getDrawable(R.drawable.ft_avd_toarrow_animation, mContext.getTheme());
        mToArrow.setAutoMirrored(true);
        mToOverflow = (AnimatedVectorDrawable) mContext.getResources()
                .getDrawable(R.drawable.ft_avd_tooverflow_animation, mContext.getTheme());
        mToOverflow.setAutoMirrored(true);

        // Views
        mOverflowButton = createOverflowButton();
        mOverflowButtonSize = measure(mOverflowButton);
        mMainPanel = createMainPanel();
        mOverflowPanelViewHelper = new OverflowPanelViewHelper(mContext, mIconTextSpacing);
        mOverflowPanel = createOverflowPanel();

        // Animation. Need views.
        mOverflowAnimationListener = createOverflowAnimationListener();
        mOpenOverflowAnimation = new AnimationSet(true);
        mOpenOverflowAnimation.setAnimationListener(mOverflowAnimationListener);
        mCloseOverflowAnimation = new AnimationSet(true);
        mCloseOverflowAnimation.setAnimationListener(mOverflowAnimationListener);
        mShowAnimation = createEnterAnimation(mContentContainer);
        mDismissAnimation = createExitAnimation(
                mContentContainer,
                150,  // startDelay
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mPopupWindow.dismiss();
                        mContentContainer.removeAllViews();
                    }
                });
        mHideAnimation = createExitAnimation(
                mContentContainer,
                0,  // startDelay
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mPopupWindow.dismiss();
                    }
                });
    }

    @Override
    public boolean setOutsideTouchable(
            boolean outsideTouchable, @Nullable PopupWindow.OnDismissListener onDismiss) {
        boolean ret = false;
        if (mPopupWindow.isOutsideTouchable() ^ outsideTouchable) {
            mPopupWindow.setOutsideTouchable(outsideTouchable);
            mPopupWindow.setFocusable(!outsideTouchable);
            mPopupWindow.update();
            ret = true;
        }
        mPopupWindow.setOnDismissListener(onDismiss);
        return ret;
    }

    /**
     * Lays out buttons for the specified menu items.
     * Requires a subsequent call to {@link FloatingToolbar#show()} to show the items.
     */
    private void layoutMenuItems(
            List<MenuItem> menuItems,
            MenuItem.OnMenuItemClickListener menuItemClickListener,
            int suggestedWidth) {
        cancelOverflowAnimations();
        clearPanels();
        updateMenuItems(menuItems, menuItemClickListener);
        menuItems = layoutMainPanelItems(menuItems, getAdjustedToolbarWidth(suggestedWidth));
        if (!menuItems.isEmpty()) {
            // Add remaining items to the overflow.
            layoutOverflowPanelItems(menuItems);
        }
        updatePopupSize();
    }

    /**
     * Updates the popup's menu items without rebuilding the widget.
     * Use in place of layoutMenuItems() when the popup's views need not be reconstructed.
     *
     * @see #isLayoutRequired(List<MenuItem>)
     */
    private void updateMenuItems(
            List<MenuItem> menuItems, MenuItem.OnMenuItemClickListener menuItemClickListener) {
        mMenuItems.clear();
        for (MenuItem menuItem : menuItems) {
            mMenuItems.put(MenuItemRepr.of(menuItem), menuItem);
        }
        mOnMenuItemClickListener = menuItemClickListener;
    }

    /**
     * Returns true if this popup needs a relayout to properly render the specified menu items.
     */
    private boolean isLayoutRequired(List<MenuItem> menuItems) {
        return !MenuItemRepr.reprEquals(menuItems, mMenuItems.values());
    }

    @Override
    public void setWidthChanged(boolean widthChanged) {
        mWidthChanged = widthChanged;
    }

    @Override
    public void setSuggestedWidth(int suggestedWidth) {
        // Check if there's been a substantial width spec change.
        int difference = Math.abs(suggestedWidth - mSuggestedWidth);
        mWidthChanged = difference > (mSuggestedWidth * 0.2);
        mSuggestedWidth = suggestedWidth;
    }

    @Override
    public void show(List<MenuItem> menuItems,
            MenuItem.OnMenuItemClickListener menuItemClickListener, Rect contentRect) {
        if (isLayoutRequired(menuItems) || mWidthChanged) {
            dismiss();
            layoutMenuItems(menuItems, menuItemClickListener, mSuggestedWidth);
        } else {
            updateMenuItems(menuItems, menuItemClickListener);
        }
        if (!isShowing()) {
            show(contentRect);
        } else if (!mPreviousContentRect.equals(contentRect)) {
            updateCoordinates(contentRect);
        }
        mWidthChanged = false;
        mPreviousContentRect.set(contentRect);
    }

    private void show(Rect contentRectOnScreen) {
        Objects.requireNonNull(contentRectOnScreen);

        if (isShowing()) {
            return;
        }

        mHidden = false;
        mDismissed = false;
        cancelDismissAndHideAnimations();
        cancelOverflowAnimations();

        refreshCoordinatesAndOverflowDirection(contentRectOnScreen);
        preparePopupContent();
        // We need to specify the position in window coordinates.
        // TODO: Consider to use PopupWindow.setIsLaidOutInScreen(true) so that we can
        // specify the popup position in screen coordinates.
        mPopupWindow.showAtLocation(
                mParent, Gravity.NO_GRAVITY, mCoordsOnWindow.x, mCoordsOnWindow.y);
        setTouchableSurfaceInsetsComputer();
        runShowAnimation();
    }

    @Override
    public void dismiss() {
        if (mDismissed) {
            return;
        }

        mHidden = false;
        mDismissed = true;
        mHideAnimation.cancel();

        runDismissAnimation();
        setZeroTouchableSurface();
    }

    @Override
    public void hide() {
        if (!isShowing()) {
            return;
        }

        mHidden = true;
        runHideAnimation();
        setZeroTouchableSurface();
    }

    @Override
    public boolean isShowing() {
        return !mDismissed && !mHidden;
    }

    @Override
    public boolean isHidden() {
        return mHidden;
    }

    /**
     * Updates the coordinates of this popup.
     * The specified coordinates may be adjusted to make sure the popup is entirely on-screen.
     * This is a no-op if this popup is not showing.
     */
    private void updateCoordinates(Rect contentRectOnScreen) {
        Objects.requireNonNull(contentRectOnScreen);

        if (!isShowing() || !mPopupWindow.isShowing()) {
            return;
        }

        cancelOverflowAnimations();
        refreshCoordinatesAndOverflowDirection(contentRectOnScreen);
        preparePopupContent();
        // We need to specify the position in window coordinates.
        // TODO: Consider to use PopupWindow.setIsLaidOutInScreen(true) so that we can
        // specify the popup position in screen coordinates.
        mPopupWindow.update(
                mCoordsOnWindow.x, mCoordsOnWindow.y,
                mPopupWindow.getWidth(), mPopupWindow.getHeight());
    }

    private void refreshCoordinatesAndOverflowDirection(Rect contentRectOnScreen) {
        refreshViewPort();

        // Initialize x ensuring that the toolbar isn't rendered behind the nav bar in
        // landscape.
        final int x = Math.min(
                contentRectOnScreen.centerX() - mPopupWindow.getWidth() / 2,
                mViewPortOnScreen.right - mPopupWindow.getWidth());

        final int y;

        final int availableHeightAboveContent =
                contentRectOnScreen.top - mViewPortOnScreen.top;
        final int availableHeightBelowContent =
                mViewPortOnScreen.bottom - contentRectOnScreen.bottom;

        final int margin = 2 * mMarginVertical;
        final int toolbarHeightWithVerticalMargin = mLineHeight + margin;

        if (!hasOverflow()) {
            if (availableHeightAboveContent >= toolbarHeightWithVerticalMargin) {
                // There is enough space at the top of the content.
                y = contentRectOnScreen.top - toolbarHeightWithVerticalMargin;
            } else if (availableHeightBelowContent >= toolbarHeightWithVerticalMargin) {
                // There is enough space at the bottom of the content.
                y = contentRectOnScreen.bottom;
            } else if (availableHeightBelowContent >= mLineHeight) {
                // Just enough space to fit the toolbar with no vertical margins.
                y = contentRectOnScreen.bottom - mMarginVertical;
            } else {
                // Not enough space. Prefer to position as high as possible.
                y = Math.max(
                        mViewPortOnScreen.top,
                        contentRectOnScreen.top - toolbarHeightWithVerticalMargin);
            }
        } else {
            // Has an overflow.
            final int minimumOverflowHeightWithMargin =
                    calculateOverflowHeight(MIN_OVERFLOW_SIZE) + margin;
            final int availableHeightThroughContentDown =
                    mViewPortOnScreen.bottom - contentRectOnScreen.top
                            + toolbarHeightWithVerticalMargin;
            final int availableHeightThroughContentUp =
                    contentRectOnScreen.bottom - mViewPortOnScreen.top
                            + toolbarHeightWithVerticalMargin;

            if (availableHeightAboveContent >= minimumOverflowHeightWithMargin) {
                // There is enough space at the top of the content rect for the overflow.
                // Position above and open upwards.
                updateOverflowHeight(availableHeightAboveContent - margin);
                y = contentRectOnScreen.top - mPopupWindow.getHeight();
                mOpenOverflowUpwards = true;
            } else if (availableHeightAboveContent >= toolbarHeightWithVerticalMargin
                    && availableHeightThroughContentDown >= minimumOverflowHeightWithMargin) {
                // There is enough space at the top of the content rect for the main panel
                // but not the overflow.
                // Position above but open downwards.
                updateOverflowHeight(availableHeightThroughContentDown - margin);
                y = contentRectOnScreen.top - toolbarHeightWithVerticalMargin;
                mOpenOverflowUpwards = false;
            } else if (availableHeightBelowContent >= minimumOverflowHeightWithMargin) {
                // There is enough space at the bottom of the content rect for the overflow.
                // Position below and open downwards.
                updateOverflowHeight(availableHeightBelowContent - margin);
                y = contentRectOnScreen.bottom;
                mOpenOverflowUpwards = false;
            } else if (availableHeightBelowContent >= toolbarHeightWithVerticalMargin
                    && mViewPortOnScreen.height() >= minimumOverflowHeightWithMargin) {
                // There is enough space at the bottom of the content rect for the main panel
                // but not the overflow.
                // Position below but open upwards.
                updateOverflowHeight(availableHeightThroughContentUp - margin);
                y = contentRectOnScreen.bottom + toolbarHeightWithVerticalMargin
                        - mPopupWindow.getHeight();
                mOpenOverflowUpwards = true;
            } else {
                // Not enough space.
                // Position at the top of the view port and open downwards.
                updateOverflowHeight(mViewPortOnScreen.height() - margin);
                y = mViewPortOnScreen.top;
                mOpenOverflowUpwards = false;
            }
        }

        // We later specify the location of PopupWindow relative to the attached window.
        // The idea here is that 1) we can get the location of a View in both window coordinates
        // and screen coordinates, where the offset between them should be equal to the window
        // origin, and 2) we can use an arbitrary for this calculation while calculating the
        // location of the rootview is supposed to be least expensive.
        // TODO: Consider to use PopupWindow.setIsLaidOutInScreen(true) so that we can avoid
        // the following calculation.
        mParent.getRootView().getLocationOnScreen(mTmpCoords);
        int rootViewLeftOnScreen = mTmpCoords[0];
        int rootViewTopOnScreen = mTmpCoords[1];
        mParent.getRootView().getLocationInWindow(mTmpCoords);
        int rootViewLeftOnWindow = mTmpCoords[0];
        int rootViewTopOnWindow = mTmpCoords[1];
        int windowLeftOnScreen = rootViewLeftOnScreen - rootViewLeftOnWindow;
        int windowTopOnScreen = rootViewTopOnScreen - rootViewTopOnWindow;
        mCoordsOnWindow.set(
                Math.max(0, x - windowLeftOnScreen), Math.max(0, y - windowTopOnScreen));
    }

    /**
     * Performs the "show" animation on the floating popup.
     */
    private void runShowAnimation() {
        mShowAnimation.start();
    }

    /**
     * Performs the "dismiss" animation on the floating popup.
     */
    private void runDismissAnimation() {
        mDismissAnimation.start();
    }

    /**
     * Performs the "hide" animation on the floating popup.
     */
    private void runHideAnimation() {
        mHideAnimation.start();
    }

    private void cancelDismissAndHideAnimations() {
        mDismissAnimation.cancel();
        mHideAnimation.cancel();
    }

    private void cancelOverflowAnimations() {
        mContentContainer.clearAnimation();
        mMainPanel.animate().cancel();
        mOverflowPanel.animate().cancel();
        mToArrow.stop();
        mToOverflow.stop();
    }

    private void openOverflow() {
        final int targetWidth = mOverflowPanelSize.getWidth();
        final int targetHeight = mOverflowPanelSize.getHeight();
        final int startWidth = mContentContainer.getWidth();
        final int startHeight = mContentContainer.getHeight();
        final float startY = mContentContainer.getY();
        final float left = mContentContainer.getX();
        final float right = left + mContentContainer.getWidth();
        Animation widthAnimation = new Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, Transformation t) {
                int deltaWidth = (int) (interpolatedTime * (targetWidth - startWidth));
                setWidth(mContentContainer, startWidth + deltaWidth);
                if (isInRTLMode()) {
                    mContentContainer.setX(left);

                    // Lock the panels in place.
                    mMainPanel.setX(0);
                    mOverflowPanel.setX(0);
                } else {
                    mContentContainer.setX(right - mContentContainer.getWidth());

                    // Offset the panels' positions so they look like they're locked in place
                    // on the screen.
                    mMainPanel.setX(mContentContainer.getWidth() - startWidth);
                    mOverflowPanel.setX(mContentContainer.getWidth() - targetWidth);
                }
            }
        };
        Animation heightAnimation = new Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, Transformation t) {
                int deltaHeight = (int) (interpolatedTime * (targetHeight - startHeight));
                setHeight(mContentContainer, startHeight + deltaHeight);
                if (mOpenOverflowUpwards) {
                    mContentContainer.setY(
                            startY - (mContentContainer.getHeight() - startHeight));
                    positionContentYCoordinatesIfOpeningOverflowUpwards();
                }
            }
        };
        final float overflowButtonStartX = mOverflowButton.getX();
        final float overflowButtonTargetX =
                isInRTLMode() ? overflowButtonStartX + targetWidth - mOverflowButton.getWidth()
                        : overflowButtonStartX - targetWidth + mOverflowButton.getWidth();
        Animation overflowButtonAnimation = new Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, Transformation t) {
                float overflowButtonX = overflowButtonStartX
                        + interpolatedTime * (overflowButtonTargetX - overflowButtonStartX);
                float deltaContainerWidth =
                        isInRTLMode() ? 0 : mContentContainer.getWidth() - startWidth;
                float actualOverflowButtonX = overflowButtonX + deltaContainerWidth;
                mOverflowButton.setX(actualOverflowButtonX);
            }
        };
        widthAnimation.setInterpolator(mLogAccelerateInterpolator);
        widthAnimation.setDuration(getAdjustedDuration(250));
        heightAnimation.setInterpolator(mFastOutSlowInInterpolator);
        heightAnimation.setDuration(getAdjustedDuration(250));
        overflowButtonAnimation.setInterpolator(mFastOutSlowInInterpolator);
        overflowButtonAnimation.setDuration(getAdjustedDuration(250));
        mOpenOverflowAnimation.getAnimations().clear();
        mOpenOverflowAnimation.getAnimations().clear();
        mOpenOverflowAnimation.addAnimation(widthAnimation);
        mOpenOverflowAnimation.addAnimation(heightAnimation);
        mOpenOverflowAnimation.addAnimation(overflowButtonAnimation);
        mContentContainer.startAnimation(mOpenOverflowAnimation);
        mIsOverflowOpen = true;
        mMainPanel.animate()
                .alpha(0).withLayer()
                .setInterpolator(mLinearOutSlowInInterpolator)
                .setDuration(250)
                .start();
        mOverflowPanel.setAlpha(1); // fadeIn in 0ms.
    }

    private void closeOverflow() {
        final int targetWidth = mMainPanelSize.getWidth();
        final int startWidth = mContentContainer.getWidth();
        final float left = mContentContainer.getX();
        final float right = left + mContentContainer.getWidth();
        Animation widthAnimation = new Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, Transformation t) {
                int deltaWidth = (int) (interpolatedTime * (targetWidth - startWidth));
                setWidth(mContentContainer, startWidth + deltaWidth);
                if (isInRTLMode()) {
                    mContentContainer.setX(left);

                    // Lock the panels in place.
                    mMainPanel.setX(0);
                    mOverflowPanel.setX(0);
                } else {
                    mContentContainer.setX(right - mContentContainer.getWidth());

                    // Offset the panels' positions so they look like they're locked in place
                    // on the screen.
                    mMainPanel.setX(mContentContainer.getWidth() - targetWidth);
                    mOverflowPanel.setX(mContentContainer.getWidth() - startWidth);
                }
            }
        };
        final int targetHeight = mMainPanelSize.getHeight();
        final int startHeight = mContentContainer.getHeight();
        final float bottom = mContentContainer.getY() + mContentContainer.getHeight();
        Animation heightAnimation = new Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, Transformation t) {
                int deltaHeight = (int) (interpolatedTime * (targetHeight - startHeight));
                setHeight(mContentContainer, startHeight + deltaHeight);
                if (mOpenOverflowUpwards) {
                    mContentContainer.setY(bottom - mContentContainer.getHeight());
                    positionContentYCoordinatesIfOpeningOverflowUpwards();
                }
            }
        };
        final float overflowButtonStartX = mOverflowButton.getX();
        final float overflowButtonTargetX =
                isInRTLMode() ? overflowButtonStartX - startWidth + mOverflowButton.getWidth()
                        : overflowButtonStartX + startWidth - mOverflowButton.getWidth();
        Animation overflowButtonAnimation = new Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, Transformation t) {
                float overflowButtonX = overflowButtonStartX
                        + interpolatedTime * (overflowButtonTargetX - overflowButtonStartX);
                float deltaContainerWidth =
                        isInRTLMode() ? 0 : mContentContainer.getWidth() - startWidth;
                float actualOverflowButtonX = overflowButtonX + deltaContainerWidth;
                mOverflowButton.setX(actualOverflowButtonX);
            }
        };
        widthAnimation.setInterpolator(mFastOutSlowInInterpolator);
        widthAnimation.setDuration(getAdjustedDuration(250));
        heightAnimation.setInterpolator(mLogAccelerateInterpolator);
        heightAnimation.setDuration(getAdjustedDuration(250));
        overflowButtonAnimation.setInterpolator(mFastOutSlowInInterpolator);
        overflowButtonAnimation.setDuration(getAdjustedDuration(250));
        mCloseOverflowAnimation.getAnimations().clear();
        mCloseOverflowAnimation.addAnimation(widthAnimation);
        mCloseOverflowAnimation.addAnimation(heightAnimation);
        mCloseOverflowAnimation.addAnimation(overflowButtonAnimation);
        mContentContainer.startAnimation(mCloseOverflowAnimation);
        mIsOverflowOpen = false;
        mMainPanel.animate()
                .alpha(1).withLayer()
                .setInterpolator(mFastOutLinearInInterpolator)
                .setDuration(100)
                .start();
        mOverflowPanel.animate()
                .alpha(0).withLayer()
                .setInterpolator(mLinearOutSlowInInterpolator)
                .setDuration(150)
                .start();
    }

    /**
     * Defines the position of the floating toolbar popup panels when transition animation has
     * stopped.
     */
    private void setPanelsStatesAtRestingPosition() {
        mOverflowButton.setEnabled(true);
        mOverflowPanel.awakenScrollBars();

        if (mIsOverflowOpen) {
            // Set open state.
            final Size containerSize = mOverflowPanelSize;
            setSize(mContentContainer, containerSize);
            mMainPanel.setAlpha(0);
            mMainPanel.setVisibility(View.INVISIBLE);
            mOverflowPanel.setAlpha(1);
            mOverflowPanel.setVisibility(View.VISIBLE);
            mOverflowButton.setImageDrawable(mArrow);
            mOverflowButton.setContentDescription(mContext.getString(
                    R.string.floating_toolbar_close_overflow_description));

            // Update x-coordinates depending on RTL state.
            if (isInRTLMode()) {
                mContentContainer.setX(mMarginHorizontal);  // align left
                mMainPanel.setX(0);  // align left
                mOverflowButton.setX(// align right
                        containerSize.getWidth() - mOverflowButtonSize.getWidth());
                mOverflowPanel.setX(0);  // align left
            } else {
                mContentContainer.setX(// align right
                        mPopupWindow.getWidth() - containerSize.getWidth() - mMarginHorizontal);
                mMainPanel.setX(-mContentContainer.getX());  // align right
                mOverflowButton.setX(0);  // align left
                mOverflowPanel.setX(0);  // align left
            }

            // Update y-coordinates depending on overflow's open direction.
            if (mOpenOverflowUpwards) {
                mContentContainer.setY(mMarginVertical);  // align top
                mMainPanel.setY(// align bottom
                        containerSize.getHeight() - mContentContainer.getHeight());
                mOverflowButton.setY(// align bottom
                        containerSize.getHeight() - mOverflowButtonSize.getHeight());
                mOverflowPanel.setY(0);  // align top
            } else {
                // opens downwards.
                mContentContainer.setY(mMarginVertical);  // align top
                mMainPanel.setY(0);  // align top
                mOverflowButton.setY(0);  // align top
                mOverflowPanel.setY(mOverflowButtonSize.getHeight());  // align bottom
            }
        } else {
            // Overflow not open. Set closed state.
            final Size containerSize = mMainPanelSize;
            setSize(mContentContainer, containerSize);
            mMainPanel.setAlpha(1);
            mMainPanel.setVisibility(View.VISIBLE);
            mOverflowPanel.setAlpha(0);
            mOverflowPanel.setVisibility(View.INVISIBLE);
            mOverflowButton.setImageDrawable(mOverflow);
            mOverflowButton.setContentDescription(mContext.getString(
                    R.string.floating_toolbar_open_overflow_description));

            if (hasOverflow()) {
                // Update x-coordinates depending on RTL state.
                if (isInRTLMode()) {
                    mContentContainer.setX(mMarginHorizontal);  // align left
                    mMainPanel.setX(0);  // align left
                    mOverflowButton.setX(0);  // align left
                    mOverflowPanel.setX(0);  // align left
                } else {
                    mContentContainer.setX(// align right
                            mPopupWindow.getWidth() - containerSize.getWidth() - mMarginHorizontal);
                    mMainPanel.setX(0);  // align left
                    mOverflowButton.setX(// align right
                            containerSize.getWidth() - mOverflowButtonSize.getWidth());
                    mOverflowPanel.setX(// align right
                            containerSize.getWidth() - mOverflowPanelSize.getWidth());
                }

                // Update y-coordinates depending on overflow's open direction.
                if (mOpenOverflowUpwards) {
                    mContentContainer.setY(// align bottom
                            mMarginVertical + mOverflowPanelSize.getHeight()
                                    - containerSize.getHeight());
                    mMainPanel.setY(0);  // align top
                    mOverflowButton.setY(0);  // align top
                    mOverflowPanel.setY(// align bottom
                            containerSize.getHeight() - mOverflowPanelSize.getHeight());
                } else {
                    // opens downwards.
                    mContentContainer.setY(mMarginVertical);  // align top
                    mMainPanel.setY(0);  // align top
                    mOverflowButton.setY(0);  // align top
                    mOverflowPanel.setY(mOverflowButtonSize.getHeight());  // align bottom
                }
            } else {
                // No overflow.
                mContentContainer.setX(mMarginHorizontal);  // align left
                mContentContainer.setY(mMarginVertical);  // align top
                mMainPanel.setX(0);  // align left
                mMainPanel.setY(0);  // align top
            }
        }
    }

    private void updateOverflowHeight(int suggestedHeight) {
        if (hasOverflow()) {
            final int maxItemSize =
                    (suggestedHeight - mOverflowButtonSize.getHeight()) / mLineHeight;
            final int newHeight = calculateOverflowHeight(maxItemSize);
            if (mOverflowPanelSize.getHeight() != newHeight) {
                mOverflowPanelSize = new Size(mOverflowPanelSize.getWidth(), newHeight);
            }
            setSize(mOverflowPanel, mOverflowPanelSize);
            if (mIsOverflowOpen) {
                setSize(mContentContainer, mOverflowPanelSize);
                if (mOpenOverflowUpwards) {
                    final int deltaHeight = mOverflowPanelSize.getHeight() - newHeight;
                    mContentContainer.setY(mContentContainer.getY() + deltaHeight);
                    mOverflowButton.setY(mOverflowButton.getY() - deltaHeight);
                }
            } else {
                setSize(mContentContainer, mMainPanelSize);
            }
            updatePopupSize();
        }
    }

    private void updatePopupSize() {
        int width = 0;
        int height = 0;
        if (mMainPanelSize != null) {
            width = Math.max(width, mMainPanelSize.getWidth());
            height = Math.max(height, mMainPanelSize.getHeight());
        }
        if (mOverflowPanelSize != null) {
            width = Math.max(width, mOverflowPanelSize.getWidth());
            height = Math.max(height, mOverflowPanelSize.getHeight());
        }
        mPopupWindow.setWidth(width + mMarginHorizontal * 2);
        mPopupWindow.setHeight(height + mMarginVertical * 2);
        maybeComputeTransitionDurationScale();
    }

    private void refreshViewPort() {
        mParent.getWindowVisibleDisplayFrame(mViewPortOnScreen);
    }

    private int getAdjustedToolbarWidth(int suggestedWidth) {
        int width = suggestedWidth;
        refreshViewPort();
        int maximumWidth = mViewPortOnScreen.width() - 2 * mParent.getResources()
                .getDimensionPixelSize(R.dimen.floating_toolbar_horizontal_margin);
        if (width <= 0) {
            width = mParent.getResources()
                    .getDimensionPixelSize(R.dimen.floating_toolbar_preferred_width);
        }
        return Math.min(width, maximumWidth);
    }

    /**
     * Sets the touchable region of this popup to be zero. This means that all touch events on
     * this popup will go through to the surface behind it.
     */
    private void setZeroTouchableSurface() {
        mTouchableRegion.setEmpty();
    }

    /**
     * Sets the touchable region of this popup to be the area occupied by its content.
     */
    private void setContentAreaAsTouchableSurface() {
        Objects.requireNonNull(mMainPanelSize);
        final int width;
        final int height;
        if (mIsOverflowOpen) {
            Objects.requireNonNull(mOverflowPanelSize);
            width = mOverflowPanelSize.getWidth();
            height = mOverflowPanelSize.getHeight();
        } else {
            width = mMainPanelSize.getWidth();
            height = mMainPanelSize.getHeight();
        }
        mTouchableRegion.set(
                (int) mContentContainer.getX(),
                (int) mContentContainer.getY(),
                (int) mContentContainer.getX() + width,
                (int) mContentContainer.getY() + height);
    }

    /**
     * Make the touchable area of this popup be the area specified by mTouchableRegion.
     * This should be called after the popup window has been dismissed (dismiss/hide)
     * and is probably being re-shown with a new content root view.
     */
    private void setTouchableSurfaceInsetsComputer() {
        ViewTreeObserver viewTreeObserver = mPopupWindow.getContentView()
                .getRootView()
                .getViewTreeObserver();
        viewTreeObserver.removeOnComputeInternalInsetsListener(mInsetsComputer);
        viewTreeObserver.addOnComputeInternalInsetsListener(mInsetsComputer);
    }

    private boolean isInRTLMode() {
        return mContext.getApplicationInfo().hasRtlSupport()
                && mContext.getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_RTL;
    }

    private boolean hasOverflow() {
        return mOverflowPanelSize != null;
    }

    /**
     * Fits as many menu items in the main panel and returns a list of the menu items that
     * were not fit in.
     *
     * @return The menu items that are not included in this main panel.
     */
    public List<MenuItem> layoutMainPanelItems(
            List<MenuItem> menuItems, final int toolbarWidth) {
        Objects.requireNonNull(menuItems);

        int availableWidth = toolbarWidth;

        final LinkedList<MenuItem> remainingMenuItems = new LinkedList<>();
        // add the overflow menu items to the end of the remainingMenuItems list.
        final LinkedList<MenuItem> overflowMenuItems = new LinkedList();
        for (MenuItem menuItem : menuItems) {
            if (menuItem.getItemId() != android.R.id.textAssist
                    && menuItem.requiresOverflow()) {
                overflowMenuItems.add(menuItem);
            } else {
                remainingMenuItems.add(menuItem);
            }
        }
        remainingMenuItems.addAll(overflowMenuItems);

        mMainPanel.removeAllViews();
        mMainPanel.setPaddingRelative(0, 0, 0, 0);

        int lastGroupId = -1;
        boolean isFirstItem = true;
        while (!remainingMenuItems.isEmpty()) {
            final MenuItem menuItem = remainingMenuItems.peek();

            // if this is the first item, regardless of requiresOverflow(), it should be
            // displayed on the main panel. Otherwise all items including this one will be
            // overflow items, and should be displayed in overflow panel.
            if (!isFirstItem && menuItem.requiresOverflow()) {
                break;
            }

            final boolean showIcon = isFirstItem && menuItem.getItemId() == R.id.textAssist;
            final View menuItemButton = createMenuItemButton(
                    mContext, menuItem, mIconTextSpacing, showIcon);
            if (!showIcon && menuItemButton instanceof LinearLayout) {
                ((LinearLayout) menuItemButton).setGravity(Gravity.CENTER);
            }

            // Adding additional start padding for the first button to even out button spacing.
            if (isFirstItem) {
                menuItemButton.setPaddingRelative(
                        (int) (1.5 * menuItemButton.getPaddingStart()),
                        menuItemButton.getPaddingTop(),
                        menuItemButton.getPaddingEnd(),
                        menuItemButton.getPaddingBottom());
            }

            // Adding additional end padding for the last button to even out button spacing.
            boolean isLastItem = remainingMenuItems.size() == 1;
            if (isLastItem) {
                menuItemButton.setPaddingRelative(
                        menuItemButton.getPaddingStart(),
                        menuItemButton.getPaddingTop(),
                        (int) (1.5 * menuItemButton.getPaddingEnd()),
                        menuItemButton.getPaddingBottom());
            }

            menuItemButton.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
            final int menuItemButtonWidth = Math.min(
                    menuItemButton.getMeasuredWidth(), toolbarWidth);

            // Check if we can fit an item while reserving space for the overflowButton.
            final boolean canFitWithOverflow =
                    menuItemButtonWidth <= availableWidth - mOverflowButtonSize.getWidth();
            final boolean canFitNoOverflow =
                    isLastItem && menuItemButtonWidth <= availableWidth;
            if (canFitWithOverflow || canFitNoOverflow) {
                setButtonTagAndClickListener(menuItemButton, menuItem);
                // Set tooltips for main panel items, but not overflow items (b/35726766).
                menuItemButton.setTooltipText(menuItem.getTooltipText());
                mMainPanel.addView(menuItemButton);
                final ViewGroup.LayoutParams params = menuItemButton.getLayoutParams();
                params.width = menuItemButtonWidth;
                menuItemButton.setLayoutParams(params);
                availableWidth -= menuItemButtonWidth;
                remainingMenuItems.pop();
            } else {
                break;
            }
            lastGroupId = menuItem.getGroupId();
            isFirstItem = false;
        }

        if (!remainingMenuItems.isEmpty()) {
            // Reserve space for overflowButton.
            mMainPanel.setPaddingRelative(0, 0, mOverflowButtonSize.getWidth(), 0);
        }

        mMainPanelSize = measure(mMainPanel);
        return remainingMenuItems;
    }

    private void layoutOverflowPanelItems(List<MenuItem> menuItems) {
        ArrayAdapter<MenuItem> overflowPanelAdapter =
                (ArrayAdapter<MenuItem>) mOverflowPanel.getAdapter();
        overflowPanelAdapter.clear();
        final int size = menuItems.size();
        for (int i = 0; i < size; i++) {
            overflowPanelAdapter.add(menuItems.get(i));
        }
        mOverflowPanel.setAdapter(overflowPanelAdapter);
        if (mOpenOverflowUpwards) {
            mOverflowPanel.setY(0);
        } else {
            mOverflowPanel.setY(mOverflowButtonSize.getHeight());
        }

        int width = Math.max(getOverflowWidth(), mOverflowButtonSize.getWidth());
        int height = calculateOverflowHeight(MAX_OVERFLOW_SIZE);
        mOverflowPanelSize = new Size(width, height);
        setSize(mOverflowPanel, mOverflowPanelSize);
    }

    /**
     * Resets the content container and appropriately position it's panels.
     */
    private void preparePopupContent() {
        mContentContainer.removeAllViews();

        // Add views in the specified order so they stack up as expected.
        // Order: overflowPanel, mainPanel, overflowButton.
        if (hasOverflow()) {
            mContentContainer.addView(mOverflowPanel);
        }
        mContentContainer.addView(mMainPanel);
        if (hasOverflow()) {
            mContentContainer.addView(mOverflowButton);
        }
        setPanelsStatesAtRestingPosition();
        setContentAreaAsTouchableSurface();

        // The positioning of contents in RTL is wrong when the view is first rendered.
        // Hide the view and post a runnable to recalculate positions and render the view.
        // TODO: Investigate why this happens and fix.
        if (isInRTLMode()) {
            mContentContainer.setAlpha(0);
            mContentContainer.post(mPreparePopupContentRTLHelper);
        }
    }

    /**
     * Clears out the panels and their container. Resets their calculated sizes.
     */
    private void clearPanels() {
        mOverflowPanelSize = null;
        mMainPanelSize = null;
        mIsOverflowOpen = false;
        mMainPanel.removeAllViews();
        ArrayAdapter<MenuItem> overflowPanelAdapter =
                (ArrayAdapter<MenuItem>) mOverflowPanel.getAdapter();
        overflowPanelAdapter.clear();
        mOverflowPanel.setAdapter(overflowPanelAdapter);
        mContentContainer.removeAllViews();
    }

    private void positionContentYCoordinatesIfOpeningOverflowUpwards() {
        if (mOpenOverflowUpwards) {
            mMainPanel.setY(mContentContainer.getHeight() - mMainPanelSize.getHeight());
            mOverflowButton.setY(mContentContainer.getHeight() - mOverflowButton.getHeight());
            mOverflowPanel.setY(mContentContainer.getHeight() - mOverflowPanelSize.getHeight());
        }
    }

    private int getOverflowWidth() {
        int overflowWidth = 0;
        final int count = mOverflowPanel.getAdapter().getCount();
        for (int i = 0; i < count; i++) {
            MenuItem menuItem = (MenuItem) mOverflowPanel.getAdapter().getItem(i);
            overflowWidth =
                    Math.max(mOverflowPanelViewHelper.calculateWidth(menuItem), overflowWidth);
        }
        return overflowWidth;
    }

    private int calculateOverflowHeight(int maxItemSize) {
        // Maximum of 4 items, minimum of 2 if the overflow has to scroll.
        int actualSize = Math.min(
                MAX_OVERFLOW_SIZE,
                Math.min(
                        Math.max(MIN_OVERFLOW_SIZE, maxItemSize),
                        mOverflowPanel.getCount()));
        int extension = 0;
        if (actualSize < mOverflowPanel.getCount()) {
            // The overflow will require scrolling to get to all the items.
            // Extend the height so that part of the hidden items is displayed.
            extension = (int) (mLineHeight * 0.5f);
        }
        return actualSize * mLineHeight
                + mOverflowButtonSize.getHeight()
                + extension;
    }

    private void setButtonTagAndClickListener(View menuItemButton, MenuItem menuItem) {
        menuItemButton.setTag(MenuItemRepr.of(menuItem));
        menuItemButton.setOnClickListener(mMenuItemButtonOnClickListener);
    }

    /**
     * NOTE: Use only in android.view.animation.* animations. Do not use in android.animation.*
     * animations. See comment about this in the code.
     */
    private int getAdjustedDuration(int originalDuration) {
        if (mTransitionDurationScale < 150) {
            // For smaller transition, decrease the time.
            return Math.max(originalDuration - 50, 0);
        } else if (mTransitionDurationScale > 300) {
            // For bigger transition, increase the time.
            return originalDuration + 50;
        }

        // Scale the animation duration with getDurationScale(). This allows
        // android.view.animation.* animations to scale just like android.animation.* animations
        // when  animator duration scale is adjusted in "Developer Options".
        // For this reason, do not use this method for android.animation.* animations.
        return (int) (originalDuration * ValueAnimator.getDurationScale());
    }

    private void maybeComputeTransitionDurationScale() {
        if (mMainPanelSize != null && mOverflowPanelSize != null) {
            int w = mMainPanelSize.getWidth() - mOverflowPanelSize.getWidth();
            int h = mOverflowPanelSize.getHeight() - mMainPanelSize.getHeight();
            mTransitionDurationScale = (int) (Math.sqrt(w * w + h * h)
                    / mContentContainer.getContext().getResources().getDisplayMetrics().density);
        }
    }

    private ViewGroup createMainPanel() {
        ViewGroup mainPanel = new LinearLayout(mContext) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                if (isOverflowAnimating()) {
                    // Update widthMeasureSpec to make sure that this view is not clipped
                    // as we offset its coordinates with respect to its parent.
                    widthMeasureSpec = MeasureSpec.makeMeasureSpec(
                            mMainPanelSize.getWidth(),
                            MeasureSpec.EXACTLY);
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                // Intercept the touch event while the overflow is animating.
                return isOverflowAnimating();
            }
        };
        return mainPanel;
    }

    private ImageButton createOverflowButton() {
        final ImageButton overflowButton = (ImageButton) LayoutInflater.from(mContext)
                .inflate(R.layout.floating_popup_overflow_button, null);
        overflowButton.setImageDrawable(mOverflow);
        overflowButton.setOnClickListener(v -> {
            if (mIsOverflowOpen) {
                overflowButton.setImageDrawable(mToOverflow);
                mToOverflow.start();
                closeOverflow();
            } else {
                overflowButton.setImageDrawable(mToArrow);
                mToArrow.start();
                openOverflow();
            }
        });
        return overflowButton;
    }

    private OverflowPanel createOverflowPanel() {
        final OverflowPanel overflowPanel = new OverflowPanel(this);
        overflowPanel.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overflowPanel.setDivider(null);
        overflowPanel.setDividerHeight(0);

        final ArrayAdapter adapter =
                new ArrayAdapter<MenuItem>(mContext, 0) {
                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        return mOverflowPanelViewHelper.getView(
                                getItem(position), mOverflowPanelSize.getWidth(), convertView);
                    }
                };
        overflowPanel.setAdapter(adapter);

        overflowPanel.setOnItemClickListener((parent, view, position, id) -> {
            MenuItem menuItem = (MenuItem) overflowPanel.getAdapter().getItem(position);
            if (mOnMenuItemClickListener != null) {
                mOnMenuItemClickListener.onMenuItemClick(menuItem);
            }
        });

        return overflowPanel;
    }

    private boolean isOverflowAnimating() {
        final boolean overflowOpening = mOpenOverflowAnimation.hasStarted()
                && !mOpenOverflowAnimation.hasEnded();
        final boolean overflowClosing = mCloseOverflowAnimation.hasStarted()
                && !mCloseOverflowAnimation.hasEnded();
        return overflowOpening || overflowClosing;
    }

    private Animation.AnimationListener createOverflowAnimationListener() {
        Animation.AnimationListener listener = new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                // Disable the overflow button while it's animating.
                // It will be re-enabled when the animation stops.
                mOverflowButton.setEnabled(false);
                // Ensure both panels have visibility turned on when the overflow animation
                // starts.
                mMainPanel.setVisibility(View.VISIBLE);
                mOverflowPanel.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                // Posting this because it seems like this is called before the animation
                // actually ends.
                mContentContainer.post(() -> {
                    setPanelsStatesAtRestingPosition();
                    setContentAreaAsTouchableSurface();
                });
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        };
        return listener;
    }

    private static Size measure(View view) {
        Preconditions.checkState(view.getParent() == null);
        view.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        return new Size(view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private static void setSize(View view, int width, int height) {
        view.setMinimumWidth(width);
        view.setMinimumHeight(height);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        params = (params == null) ? new ViewGroup.LayoutParams(0, 0) : params;
        params.width = width;
        params.height = height;
        view.setLayoutParams(params);
    }

    private static void setSize(View view, Size size) {
        setSize(view, size.getWidth(), size.getHeight());
    }

    private static void setWidth(View view, int width) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        setSize(view, width, params.height);
    }

    private static void setHeight(View view, int height) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        setSize(view, params.width, height);
    }

    /**
     * A custom ListView for the overflow panel.
     */
    private static final class OverflowPanel extends ListView {

        private final LocalFloatingToolbarPopup mPopup;

        OverflowPanel(LocalFloatingToolbarPopup popup) {
            super(Objects.requireNonNull(popup).mContext);
            this.mPopup = popup;
            setScrollBarDefaultDelayBeforeFade(ViewConfiguration.getScrollDefaultDelay() * 3);
            setScrollIndicators(View.SCROLL_INDICATOR_TOP | View.SCROLL_INDICATOR_BOTTOM);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // Update heightMeasureSpec to make sure that this view is not clipped
            // as we offset it's coordinates with respect to its parent.
            int height = mPopup.mOverflowPanelSize.getHeight()
                    - mPopup.mOverflowButtonSize.getHeight();
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (mPopup.isOverflowAnimating()) {
                // Eat the touch event.
                return true;
            }
            return super.dispatchTouchEvent(ev);
        }

        @Override
        protected boolean awakenScrollBars() {
            return super.awakenScrollBars();
        }
    }

    /**
     * A custom interpolator used for various floating toolbar animations.
     */
    private static final class LogAccelerateInterpolator implements Interpolator {

        private static final int BASE = 100;
        private static final float LOGS_SCALE = 1f / computeLog(1, BASE);

        private static float computeLog(float t, int base) {
            return (float) (1 - Math.pow(base, -t));
        }

        @Override
        public float getInterpolation(float t) {
            return 1 - computeLog(1 - t, BASE) * LOGS_SCALE;
        }
    }

    /**
     * A helper for generating views for the overflow panel.
     */
    private static final class OverflowPanelViewHelper {

        private final View mCalculator;
        private final int mIconTextSpacing;
        private final int mSidePadding;

        private final Context mContext;

        OverflowPanelViewHelper(Context context, int iconTextSpacing) {
            mContext = Objects.requireNonNull(context);
            mIconTextSpacing = iconTextSpacing;
            mSidePadding = context.getResources()
                    .getDimensionPixelSize(R.dimen.floating_toolbar_overflow_side_padding);
            mCalculator = createMenuButton(null);
        }

        public View getView(MenuItem menuItem, int minimumWidth, View convertView) {
            Objects.requireNonNull(menuItem);
            if (convertView != null) {
                updateMenuItemButton(
                        convertView, menuItem, mIconTextSpacing, shouldShowIcon(menuItem));
            } else {
                convertView = createMenuButton(menuItem);
            }
            convertView.setMinimumWidth(minimumWidth);
            return convertView;
        }

        public int calculateWidth(MenuItem menuItem) {
            updateMenuItemButton(
                    mCalculator, menuItem, mIconTextSpacing, shouldShowIcon(menuItem));
            mCalculator.measure(
                    View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            return mCalculator.getMeasuredWidth();
        }

        private View createMenuButton(MenuItem menuItem) {
            View button = createMenuItemButton(
                    mContext, menuItem, mIconTextSpacing, shouldShowIcon(menuItem));
            button.setPadding(mSidePadding, 0, mSidePadding, 0);
            return button;
        }

        private boolean shouldShowIcon(MenuItem menuItem) {
            if (menuItem != null) {
                return menuItem.getGroupId() == android.R.id.textAssist;
            }
            return false;
        }
    }

    /**
     * Creates and returns a menu button for the specified menu item.
     */
    private static View createMenuItemButton(
            Context context, MenuItem menuItem, int iconTextSpacing, boolean showIcon) {
        final View menuItemButton = LayoutInflater.from(context)
                .inflate(R.layout.floating_popup_menu_button, null);
        if (menuItem != null) {
            updateMenuItemButton(menuItemButton, menuItem, iconTextSpacing, showIcon);
        }
        return menuItemButton;
    }

    /**
     * Updates the specified menu item button with the specified menu item data.
     */
    private static void updateMenuItemButton(
            View menuItemButton, MenuItem menuItem, int iconTextSpacing, boolean showIcon) {
        final TextView buttonText = menuItemButton.findViewById(
                R.id.floating_toolbar_menu_item_text);
        buttonText.setEllipsize(null);
        if (TextUtils.isEmpty(menuItem.getTitle())) {
            buttonText.setVisibility(View.GONE);
        } else {
            buttonText.setVisibility(View.VISIBLE);
            buttonText.setText(menuItem.getTitle());
        }
        final ImageView buttonIcon = menuItemButton.findViewById(
                R.id.floating_toolbar_menu_item_image);
        if (menuItem.getIcon() == null || !showIcon) {
            buttonIcon.setVisibility(View.GONE);
            if (buttonText != null) {
                buttonText.setPaddingRelative(0, 0, 0, 0);
            }
        } else {
            buttonIcon.setVisibility(View.VISIBLE);
            buttonIcon.setImageDrawable(menuItem.getIcon());
            if (buttonText != null) {
                buttonText.setPaddingRelative(iconTextSpacing, 0, 0, 0);
            }
        }
        final CharSequence contentDescription = menuItem.getContentDescription();
        if (TextUtils.isEmpty(contentDescription)) {
            menuItemButton.setContentDescription(menuItem.getTitle());
        } else {
            menuItemButton.setContentDescription(contentDescription);
        }
    }

    private static ViewGroup createContentContainer(Context context) {
        ViewGroup contentContainer = (ViewGroup) LayoutInflater.from(context)
                .inflate(R.layout.floating_popup_container, null);
        contentContainer.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contentContainer.setTag(FloatingToolbar.FLOATING_TOOLBAR_TAG);
        contentContainer.setContentDescription(FloatingToolbar.FLOATING_TOOLBAR_TAG);
        contentContainer.setClipToOutline(true);
        return contentContainer;
    }

    private static PopupWindow createPopupWindow(ViewGroup content) {
        ViewGroup popupContentHolder = new LinearLayout(content.getContext());
        PopupWindow popupWindow = new PopupWindow(popupContentHolder);
        // TODO: Use .setIsLaidOutInScreen(true) instead of .setClippingEnabled(false)
        // unless FLAG_LAYOUT_IN_SCREEN has any unintentional side-effects.
        popupWindow.setClippingEnabled(false);
        popupWindow.setWindowLayoutType(
                WindowManager.LayoutParams.TYPE_APPLICATION_ABOVE_SUB_PANEL);
        popupWindow.setAnimationStyle(0);
        popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        content.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        popupContentHolder.addView(content);
        return popupWindow;
    }

    /**
     * Creates an "appear" animation for the specified view.
     *
     * @param view  The view to animate
     */
    private static AnimatorSet createEnterAnimation(View view) {
        AnimatorSet animation = new AnimatorSet();
        animation.playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 0, 1).setDuration(150));
        return animation;
    }

    /**
     * Creates a "disappear" animation for the specified view.
     *
     * @param view  The view to animate
     * @param startDelay  The start delay of the animation
     * @param listener  The animation listener
     */
    private static AnimatorSet createExitAnimation(
            View view, int startDelay, Animator.AnimatorListener listener) {
        AnimatorSet animation =  new AnimatorSet();
        animation.playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 1, 0).setDuration(100));
        animation.setStartDelay(startDelay);
        animation.addListener(listener);
        return animation;
    }

    /**
     * Returns a re-themed context with controlled look and feel for views.
     */
    private static Context applyDefaultTheme(Context originalContext) {
        TypedArray a = originalContext.obtainStyledAttributes(new int[]{R.attr.isLightTheme});
        boolean isLightTheme = a.getBoolean(0, true);
        int themeId =
                isLightTheme ? R.style.Theme_DeviceDefault_Light : R.style.Theme_DeviceDefault;
        a.recycle();
        return new ContextThemeWrapper(originalContext, themeId);
    }

    /**
     * Represents the identity of a MenuItem that is rendered in a FloatingToolbarPopup.
     */
    @VisibleForTesting
    public static final class MenuItemRepr {

        public final int itemId;
        public final int groupId;
        @Nullable public final String title;
        @Nullable private final Drawable mIcon;

        private MenuItemRepr(
                int itemId, int groupId, @Nullable CharSequence title, @Nullable Drawable icon) {
            this.itemId = itemId;
            this.groupId = groupId;
            this.title = (title == null) ? null : title.toString();
            mIcon = icon;
        }

        /**
         * Creates an instance of MenuItemRepr for the specified menu item.
         */
        public static MenuItemRepr of(MenuItem menuItem) {
            return new MenuItemRepr(
                    menuItem.getItemId(),
                    menuItem.getGroupId(),
                    menuItem.getTitle(),
                    menuItem.getIcon());
        }

        /**
         * Returns this object's hashcode.
         */
        @Override
        public int hashCode() {
            return Objects.hash(itemId, groupId, title, mIcon);
        }

        /**
         * Returns true if this object is the same as the specified object.
         */
        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof MenuItemRepr)) {
                return false;
            }
            final MenuItemRepr other = (MenuItemRepr) o;
            return itemId == other.itemId
                    && groupId == other.groupId
                    && TextUtils.equals(title, other.title)
                    // Many Drawables (icons) do not implement equals(). Using equals() here instead
                    // of reference comparisons in case a Drawable subclass implements equals().
                    && Objects.equals(mIcon, other.mIcon);
        }

        /**
         * Returns true if the two menu item collections are the same based on MenuItemRepr.
         */
        public static boolean reprEquals(
                Collection<MenuItem> menuItems1, Collection<MenuItem> menuItems2) {
            if (menuItems1.size() != menuItems2.size()) {
                return false;
            }

            final Iterator<MenuItem> menuItems2Iter = menuItems2.iterator();
            for (MenuItem menuItem1 : menuItems1) {
                final MenuItem menuItem2 = menuItems2Iter.next();
                if (!MenuItemRepr.of(menuItem1).equals(MenuItemRepr.of(menuItem2))) {
                    return false;
                }
            }

            return true;
        }
    }
}
