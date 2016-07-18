/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.internal.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
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
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.View.OnLayoutChangeListener;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.Transformation;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import com.android.internal.R;
import com.android.internal.util.Preconditions;

/**
 * A floating toolbar for showing contextual menu items.
 * This view shows as many menu item buttons as can fit in the horizontal toolbar and the
 * the remaining menu items in a vertical overflow view when the overflow button is clicked.
 * The horizontal toolbar morphs into the vertical overflow view.
 */
public final class FloatingToolbar {

    // This class is responsible for the public API of the floating toolbar.
    // It delegates rendering operations to the FloatingToolbarPopup.

    public static final String FLOATING_TOOLBAR_TAG = "floating_toolbar";

    private static final MenuItem.OnMenuItemClickListener NO_OP_MENUITEM_CLICK_LISTENER =
            new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    return false;
                }
            };

    private final Context mContext;
    private final Window mWindow;
    private final FloatingToolbarPopup mPopup;

    private final Rect mContentRect = new Rect();
    private final Rect mPreviousContentRect = new Rect();

    private Menu mMenu;
    private List<Object> mShowingMenuItems = new ArrayList<Object>();
    private MenuItem.OnMenuItemClickListener mMenuItemClickListener = NO_OP_MENUITEM_CLICK_LISTENER;

    private int mSuggestedWidth;
    private boolean mWidthChanged = true;

    private final OnLayoutChangeListener mOrientationChangeHandler = new OnLayoutChangeListener() {

        private final Rect mNewRect = new Rect();
        private final Rect mOldRect = new Rect();

        @Override
        public void onLayoutChange(
                View view,
                int newLeft, int newRight, int newTop, int newBottom,
                int oldLeft, int oldRight, int oldTop, int oldBottom) {
            mNewRect.set(newLeft, newRight, newTop, newBottom);
            mOldRect.set(oldLeft, oldRight, oldTop, oldBottom);
            if (mPopup.isShowing() && !mNewRect.equals(mOldRect)) {
                mWidthChanged = true;
                updateLayout();
            }
        }
    };

    /**
     * Initializes a floating toolbar.
     */
    public FloatingToolbar(Context context, Window window) {
        mContext = applyDefaultTheme(Preconditions.checkNotNull(context));
        mWindow = Preconditions.checkNotNull(window);
        mPopup = new FloatingToolbarPopup(mContext, window.getDecorView());
    }

    /**
     * Sets the menu to be shown in this floating toolbar.
     * NOTE: Call {@link #updateLayout()} or {@link #show()} to effect visual changes to the
     * toolbar.
     */
    public FloatingToolbar setMenu(Menu menu) {
        mMenu = Preconditions.checkNotNull(menu);
        return this;
    }

    /**
     * Sets the custom listener for invocation of menu items in this floating toolbar.
     */
    public FloatingToolbar setOnMenuItemClickListener(
            MenuItem.OnMenuItemClickListener menuItemClickListener) {
        if (menuItemClickListener != null) {
            mMenuItemClickListener = menuItemClickListener;
        } else {
            mMenuItemClickListener = NO_OP_MENUITEM_CLICK_LISTENER;
        }
        return this;
    }

    /**
     * Sets the content rectangle. This is the area of the interesting content that this toolbar
     * should avoid obstructing.
     * NOTE: Call {@link #updateLayout()} or {@link #show()} to effect visual changes to the
     * toolbar.
     */
    public FloatingToolbar setContentRect(Rect rect) {
        mContentRect.set(Preconditions.checkNotNull(rect));
        return this;
    }

    /**
     * Sets the suggested width of this floating toolbar.
     * The actual width will be about this size but there are no guarantees that it will be exactly
     * the suggested width.
     * NOTE: Call {@link #updateLayout()} or {@link #show()} to effect visual changes to the
     * toolbar.
     */
    public FloatingToolbar setSuggestedWidth(int suggestedWidth) {
        // Check if there's been a substantial width spec change.
        int difference = Math.abs(suggestedWidth - mSuggestedWidth);
        mWidthChanged = difference > (mSuggestedWidth * 0.2);

        mSuggestedWidth = suggestedWidth;
        return this;
    }

    /**
     * Shows this floating toolbar.
     */
    public FloatingToolbar show() {
        registerOrientationHandler();
        doShow();
        return this;
    }

    /**
     * Updates this floating toolbar to reflect recent position and view updates.
     * NOTE: This method is a no-op if the toolbar isn't showing.
     */
    public FloatingToolbar updateLayout() {
        if (mPopup.isShowing()) {
            doShow();
        }
        return this;
    }

    /**
     * Dismisses this floating toolbar.
     */
    public void dismiss() {
        unregisterOrientationHandler();
        mPopup.dismiss();
    }

    /**
     * Hides this floating toolbar. This is a no-op if the toolbar is not showing.
     * Use {@link #isHidden()} to distinguish between a hidden and a dismissed toolbar.
     */
    public void hide() {
        mPopup.hide();
    }

    /**
     * Returns {@code true} if this toolbar is currently showing. {@code false} otherwise.
     */
    public boolean isShowing() {
        return mPopup.isShowing();
    }

    /**
     * Returns {@code true} if this toolbar is currently hidden. {@code false} otherwise.
     */
    public boolean isHidden() {
        return mPopup.isHidden();
    }

    private void doShow() {
        List<MenuItem> menuItems = getVisibleAndEnabledMenuItems(mMenu);
        if (!isCurrentlyShowing(menuItems) || mWidthChanged) {
            mPopup.dismiss();
            mPopup.layoutMenuItems(menuItems, mMenuItemClickListener, mSuggestedWidth);
            mShowingMenuItems = getShowingMenuItemsReferences(menuItems);
        }
        if (!mPopup.isShowing()) {
            mPopup.show(mContentRect);
        } else if (!mPreviousContentRect.equals(mContentRect)) {
            mPopup.updateCoordinates(mContentRect);
        }
        mWidthChanged = false;
        mPreviousContentRect.set(mContentRect);
    }

    /**
     * Returns true if this floating toolbar is currently showing the specified menu items.
     */
    private boolean isCurrentlyShowing(List<MenuItem> menuItems) {
        return mShowingMenuItems.equals(getShowingMenuItemsReferences(menuItems));
    }

    /**
     * Returns the visible and enabled menu items in the specified menu.
     * This method is recursive.
     */
    private List<MenuItem> getVisibleAndEnabledMenuItems(Menu menu) {
        List<MenuItem> menuItems = new ArrayList<MenuItem>();
        for (int i = 0; (menu != null) && (i < menu.size()); i++) {
            MenuItem menuItem = menu.getItem(i);
            if (menuItem.isVisible() && menuItem.isEnabled()) {
                Menu subMenu = menuItem.getSubMenu();
                if (subMenu != null) {
                    menuItems.addAll(getVisibleAndEnabledMenuItems(subMenu));
                } else {
                    menuItems.add(menuItem);
                }
            }
        }
        return menuItems;
    }

    private List<Object> getShowingMenuItemsReferences(List<MenuItem> menuItems) {
        List<Object> references = new ArrayList<Object>();
        for (MenuItem menuItem : menuItems) {
            if (isIconOnlyMenuItem(menuItem)) {
                references.add(menuItem.getIcon());
            } else {
                references.add(menuItem.getTitle());
            }
        }
        return references;
    }

    private void registerOrientationHandler() {
        unregisterOrientationHandler();
        mWindow.getDecorView().addOnLayoutChangeListener(mOrientationChangeHandler);
    }

    private void unregisterOrientationHandler() {
        mWindow.getDecorView().removeOnLayoutChangeListener(mOrientationChangeHandler);
    }


    /**
     * A popup window used by the floating toolbar.
     *
     * This class is responsible for the rendering/animation of the floating toolbar.
     * It holds 2 panels (i.e. main panel and overflow panel) and an overflow button
     * to transition between panels.
     */
    private static final class FloatingToolbarPopup {

        /* Minimum and maximum number of items allowed in the overflow. */
        private static final int MIN_OVERFLOW_SIZE = 2;
        private static final int MAX_OVERFLOW_SIZE = 4;

        private final Context mContext;
        private final View mParent;  // Parent for the popup window.
        private final PopupWindow mPopupWindow;

        /* Margins between the popup window and it's content. */
        private final int mMarginHorizontal;
        private final int mMarginVertical;

        /* View components */
        private final ViewGroup mContentContainer;  // holds all contents.
        private final ViewGroup mMainPanel;  // holds menu items that are initially displayed.
        private final OverflowPanel mOverflowPanel;  // holds menu items hidden in the overflow.
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
        private final Rect mTmpRect = new Rect();

        private final Region mTouchableRegion = new Region();
        private final ViewTreeObserver.OnComputeInternalInsetsListener mInsetsComputer =
                new ViewTreeObserver.OnComputeInternalInsetsListener() {
                    public void onComputeInternalInsets(
                            ViewTreeObserver.InternalInsetsInfo info) {
                        info.contentInsets.setEmpty();
                        info.visibleInsets.setEmpty();
                        info.touchableRegion.set(mTouchableRegion);
                        info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo
                                .TOUCHABLE_INSETS_REGION);
                    }
                };

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

        /* Item click listeners */
        private MenuItem.OnMenuItemClickListener mOnMenuItemClickListener;
        private final View.OnClickListener mMenuItemButtonOnClickListener =
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (v.getTag() instanceof MenuItem) {
                            if (mOnMenuItemClickListener != null) {
                                mOnMenuItemClickListener.onMenuItemClick((MenuItem) v.getTag());
                            }
                        }
                    }
                };

        private boolean mOpenOverflowUpwards;  // Whether the overflow opens upwards or downwards.
        private boolean mIsOverflowOpen;

        private int mTransitionDurationScale;  // Used to scale the toolbar transition duration.

        /**
         * Initializes a new floating toolbar popup.
         *
         * @param parent  A parent view to get the {@link android.view.View#getWindowToken()} token
         *      from.
         */
        public FloatingToolbarPopup(Context context, View parent) {
            mParent = Preconditions.checkNotNull(parent);
            mContext = Preconditions.checkNotNull(context);
            mContentContainer = createContentContainer(context);
            mPopupWindow = createPopupWindow(mContentContainer);
            mMarginHorizontal = parent.getResources()
                    .getDimensionPixelSize(R.dimen.floating_toolbar_horizontal_margin);
            mMarginVertical = parent.getResources()
                    .getDimensionPixelSize(R.dimen.floating_toolbar_vertical_margin);

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
            mOverflowPanelViewHelper = new OverflowPanelViewHelper(mContext);
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

        /**
         * Lays out buttons for the specified menu items.
         * Requires a subsequent call to {@link #show()} to show the items.
         */
        public void layoutMenuItems(
                List<MenuItem> menuItems,
                MenuItem.OnMenuItemClickListener menuItemClickListener,
                int suggestedWidth) {
            mOnMenuItemClickListener = menuItemClickListener;
            cancelOverflowAnimations();
            clearPanels();
            menuItems = layoutMainPanelItems(menuItems, getAdjustedToolbarWidth(suggestedWidth));
            if (!menuItems.isEmpty()) {
                // Add remaining items to the overflow.
                layoutOverflowPanelItems(menuItems);
            }
            updatePopupSize();
        }

        /**
         * Shows this popup at the specified coordinates.
         * The specified coordinates may be adjusted to make sure the popup is entirely on-screen.
         */
        public void show(Rect contentRectOnScreen) {
            Preconditions.checkNotNull(contentRectOnScreen);

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
            // TODO: Consider to use PopupWindow.setLayoutInScreenEnabled(true) so that we can
            // specify the popup position in screen coordinates.
            mPopupWindow.showAtLocation(
                    mParent, Gravity.NO_GRAVITY, mCoordsOnWindow.x, mCoordsOnWindow.y);
            setTouchableSurfaceInsetsComputer();
            runShowAnimation();
        }

        /**
         * Gets rid of this popup. If the popup isn't currently showing, this will be a no-op.
         */
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

        /**
         * Hides this popup. This is a no-op if this popup is not showing.
         * Use {@link #isHidden()} to distinguish between a hidden and a dismissed popup.
         */
        public void hide() {
            if (!isShowing()) {
                return;
            }

            mHidden = true;
            runHideAnimation();
            setZeroTouchableSurface();
        }

        /**
         * Returns {@code true} if this popup is currently showing. {@code false} otherwise.
         */
        public boolean isShowing() {
            return !mDismissed && !mHidden;
        }

        /**
         * Returns {@code true} if this popup is currently hidden. {@code false} otherwise.
         */
        public boolean isHidden() {
            return mHidden;
        }

        /**
         * Updates the coordinates of this popup.
         * The specified coordinates may be adjusted to make sure the popup is entirely on-screen.
         * This is a no-op if this popup is not showing.
         */
        public void updateCoordinates(Rect contentRectOnScreen) {
            Preconditions.checkNotNull(contentRectOnScreen);

            if (!isShowing() || !mPopupWindow.isShowing()) {
                return;
            }

            cancelOverflowAnimations();
            refreshCoordinatesAndOverflowDirection(contentRectOnScreen);
            preparePopupContent();
            // We need to specify the position in window coordinates.
            // TODO: Consider to use PopupWindow.setLayoutInScreenEnabled(true) so that we can
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
            final int toolbarHeightWithVerticalMargin = getLineHeight(mContext) + margin;

            if (!hasOverflow()) {
                if (availableHeightAboveContent >= toolbarHeightWithVerticalMargin) {
                    // There is enough space at the top of the content.
                    y = contentRectOnScreen.top - toolbarHeightWithVerticalMargin;
                } else if (availableHeightBelowContent >= toolbarHeightWithVerticalMargin) {
                    // There is enough space at the bottom of the content.
                    y = contentRectOnScreen.bottom;
                } else if (availableHeightBelowContent >= getLineHeight(mContext)) {
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
                final int availableHeightThroughContentDown = mViewPortOnScreen.bottom -
                        contentRectOnScreen.top + toolbarHeightWithVerticalMargin;
                final int availableHeightThroughContentUp = contentRectOnScreen.bottom -
                        mViewPortOnScreen.top + toolbarHeightWithVerticalMargin;

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
                    y = contentRectOnScreen.bottom + toolbarHeightWithVerticalMargin -
                            mPopupWindow.getHeight();
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
            // and screen coordiantes, where the offset between them should be equal to the window
            // origin, and 2) we can use an arbitrary for this calculation while calculating the
            // location of the rootview is supposed to be least expensive.
            // TODO: Consider to use PopupWindow.setLayoutInScreenEnabled(true) so that we can avoid
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
            final float overflowButtonTargetX = isInRTLMode() ?
                    overflowButtonStartX + targetWidth - mOverflowButton.getWidth() :
                    overflowButtonStartX - targetWidth + mOverflowButton.getWidth();
            Animation overflowButtonAnimation = new Animation() {
                @Override
                protected void applyTransformation(float interpolatedTime, Transformation t) {
                    float overflowButtonX = overflowButtonStartX
                            + interpolatedTime * (overflowButtonTargetX - overflowButtonStartX);
                    float deltaContainerWidth = isInRTLMode() ?
                            0 :
                            mContentContainer.getWidth() - startWidth;
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
            final float overflowButtonTargetX = isInRTLMode() ?
                    overflowButtonStartX - startWidth + mOverflowButton.getWidth() :
                    overflowButtonStartX + startWidth - mOverflowButton.getWidth();
            Animation overflowButtonAnimation = new Animation() {
                @Override
                protected void applyTransformation(float interpolatedTime, Transformation t) {
                    float overflowButtonX = overflowButtonStartX
                            + interpolatedTime * (overflowButtonTargetX - overflowButtonStartX);
                    float deltaContainerWidth = isInRTLMode() ?
                            0 :
                            mContentContainer.getWidth() - startWidth;
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
                    mOverflowButton.setX(  // align right
                            containerSize.getWidth() - mOverflowButtonSize.getWidth());
                    mOverflowPanel.setX(0);  // align left
                } else {
                    mContentContainer.setX(  // align right
                            mPopupWindow.getWidth() -
                                    containerSize.getWidth() - mMarginHorizontal);
                    mMainPanel.setX(-mContentContainer.getX());  // align right
                    mOverflowButton.setX(0);  // align left
                    mOverflowPanel.setX(0);  // align left
                }

                // Update y-coordinates depending on overflow's open direction.
                if (mOpenOverflowUpwards) {
                    mContentContainer.setY(mMarginVertical);  // align top
                    mMainPanel.setY(  // align bottom
                            containerSize.getHeight() - mContentContainer.getHeight());
                    mOverflowButton.setY(  // align bottom
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
                        mContentContainer.setX(  // align right
                                mPopupWindow.getWidth() -
                                        containerSize.getWidth() - mMarginHorizontal);
                        mMainPanel.setX(0);  // align left
                        mOverflowButton.setX(  // align right
                                containerSize.getWidth() - mOverflowButtonSize.getWidth());
                        mOverflowPanel.setX(  // align right
                                containerSize.getWidth() - mOverflowPanelSize.getWidth());
                    }

                    // Update y-coordinates depending on overflow's open direction.
                    if (mOpenOverflowUpwards) {
                        mContentContainer.setY(  // align bottom
                                mMarginVertical +
                                        mOverflowPanelSize.getHeight() - containerSize.getHeight());
                        mMainPanel.setY(0);  // align top
                        mOverflowButton.setY(0);  // align top
                        mOverflowPanel.setY(  // align bottom
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
                final int maxItemSize = (suggestedHeight - mOverflowButtonSize.getHeight()) /
                        getLineHeight(mContext);
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
            Preconditions.checkNotNull(mMainPanelSize);
            final int width;
            final int height;
            if (mIsOverflowOpen) {
                Preconditions.checkNotNull(mOverflowPanelSize);
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
            Preconditions.checkNotNull(menuItems);

            int availableWidth = toolbarWidth;
            final LinkedList<MenuItem> remainingMenuItems = new LinkedList<MenuItem>(menuItems);

            mMainPanel.removeAllViews();
            mMainPanel.setPaddingRelative(0, 0, 0, 0);

            boolean isFirstItem = true;
            while (!remainingMenuItems.isEmpty()) {
                final MenuItem menuItem = remainingMenuItems.peek();
                View menuItemButton = createMenuItemButton(mContext, menuItem);

                // Adding additional start padding for the first button to even out button spacing.
                if (isFirstItem) {
                    menuItemButton.setPaddingRelative(
                            (int) (1.5 * menuItemButton.getPaddingStart()),
                            menuItemButton.getPaddingTop(),
                            menuItemButton.getPaddingEnd(),
                            menuItemButton.getPaddingBottom());
                    isFirstItem = false;
                }

                // Adding additional end padding for the last button to even out button spacing.
                if (remainingMenuItems.size() == 1) {
                    menuItemButton.setPaddingRelative(
                            menuItemButton.getPaddingStart(),
                            menuItemButton.getPaddingTop(),
                            (int) (1.5 * menuItemButton.getPaddingEnd()),
                            menuItemButton.getPaddingBottom());
                }

                menuItemButton.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
                int menuItemButtonWidth = Math.min(menuItemButton.getMeasuredWidth(), toolbarWidth);
                // Check if we can fit an item while reserving space for the overflowButton.
                boolean canFitWithOverflow =
                        menuItemButtonWidth <= availableWidth - mOverflowButtonSize.getWidth();
                boolean canFitNoOverflow =
                        remainingMenuItems.size() == 1 && menuItemButtonWidth <= availableWidth;
                if (canFitWithOverflow || canFitNoOverflow) {
                    setButtonTagAndClickListener(menuItemButton, menuItem);
                    mMainPanel.addView(menuItemButton);
                    ViewGroup.LayoutParams params = menuItemButton.getLayoutParams();
                    params.width = menuItemButtonWidth;
                    menuItemButton.setLayoutParams(params);
                    availableWidth -= menuItemButtonWidth;
                    remainingMenuItems.pop();
                } else {
                    // Reserve space for overflowButton.
                    mMainPanel.setPaddingRelative(0, 0, mOverflowButtonSize.getWidth(), 0);
                    break;
                }
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
                extension = (int) (getLineHeight(mContext) * 0.5f);
            }
            return actualSize * getLineHeight(mContext)
                    + mOverflowButtonSize.getHeight()
                    + extension;
        }

        private void setButtonTagAndClickListener(View menuItemButton, MenuItem menuItem) {
            View button = menuItemButton;
            if (isIconOnlyMenuItem(menuItem)) {
                button = menuItemButton.findViewById(R.id.floating_toolbar_menu_item_image_button);
            }
            button.setTag(menuItem);
            button.setOnClickListener(mMenuItemButtonOnClickListener);
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
                mTransitionDurationScale = (int) (Math.sqrt(w * w + h * h) /
                        mContentContainer.getContext().getResources().getDisplayMetrics().density);
            }
        }

        private ViewGroup createMainPanel() {
            ViewGroup mainPanel = new LinearLayout(mContext) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    if (isOverflowAnimating()) {
                        // Update widthMeasureSpec to make sure that this view is not clipped
                        // as we offset it's coordinates with respect to it's parent.
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
            overflowButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mIsOverflowOpen) {
                        overflowButton.setImageDrawable(mToOverflow);
                        mToOverflow.start();
                        closeOverflow();
                    } else {
                        overflowButton.setImageDrawable(mToArrow);
                        mToArrow.start();
                        openOverflow();
                    }
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
                        public int getViewTypeCount() {
                            return mOverflowPanelViewHelper.getViewTypeCount();
                        }

                        @Override
                        public int getItemViewType(int position) {
                            return mOverflowPanelViewHelper.getItemViewType(getItem(position));
                        }

                        @Override
                        public View getView(int position, View convertView, ViewGroup parent) {
                            return mOverflowPanelViewHelper.getView(
                                    getItem(position), mOverflowPanelSize.getWidth(), convertView);
                        }
                    };
            overflowPanel.setAdapter(adapter);

            overflowPanel.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    MenuItem menuItem = (MenuItem) overflowPanel.getAdapter().getItem(position);
                    if (mOnMenuItemClickListener != null) {
                        mOnMenuItemClickListener.onMenuItemClick(menuItem);
                    }
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
                    mContentContainer.post(new Runnable() {
                        @Override
                        public void run() {
                            setPanelsStatesAtRestingPosition();
                            setContentAreaAsTouchableSurface();
                        }
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

        private static int getLineHeight(Context context) {
            return context.getResources().getDimensionPixelSize(R.dimen.floating_toolbar_height);
        }

        /**
         * A custom ListView for the overflow panel.
         */
        private static final class OverflowPanel extends ListView {

            private final FloatingToolbarPopup mPopup;

            OverflowPanel(FloatingToolbarPopup popup) {
                super(Preconditions.checkNotNull(popup).mContext);
                this.mPopup = popup;
                setScrollBarDefaultDelayBeforeFade(ViewConfiguration.getScrollDefaultDelay() * 3);
                setScrollIndicators(View.SCROLL_INDICATOR_TOP | View.SCROLL_INDICATOR_BOTTOM);
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                // Update heightMeasureSpec to make sure that this view is not clipped
                // as we offset it's coordinates with respect to it's parent.
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

            private static final int NUM_OF_VIEW_TYPES = 2;
            private static final int VIEW_TYPE_STRING_TITLE = 0;
            private static final int VIEW_TYPE_ICON_ONLY = 1;

            private final TextView mStringTitleViewCalculator;
            private final View mIconOnlyViewCalculator;

            private final Context mContext;

            public OverflowPanelViewHelper(Context context) {
                mContext = Preconditions.checkNotNull(context);
                mStringTitleViewCalculator = getStringTitleView(null, 0, null);
                mIconOnlyViewCalculator = getIconOnlyView(null, 0, null);
            }

            public int getViewTypeCount() {
                return NUM_OF_VIEW_TYPES;
            }

            public View getView(MenuItem menuItem, int minimumWidth, View convertView) {
                Preconditions.checkNotNull(menuItem);
                if (getItemViewType(menuItem) == VIEW_TYPE_ICON_ONLY) {
                    return getIconOnlyView(menuItem, minimumWidth, convertView);
                }
                return getStringTitleView(menuItem, minimumWidth, convertView);
            }

            public int getItemViewType(MenuItem menuItem) {
                Preconditions.checkNotNull(menuItem);
                if (isIconOnlyMenuItem(menuItem)) {
                    return VIEW_TYPE_ICON_ONLY;
                }
                return VIEW_TYPE_STRING_TITLE;
            }

            public int calculateWidth(MenuItem menuItem) {
                final View calculator;
                if (isIconOnlyMenuItem(menuItem)) {
                    ((ImageView) mIconOnlyViewCalculator
                            .findViewById(R.id.floating_toolbar_menu_item_image_button))
                            .setImageDrawable(menuItem.getIcon());
                    calculator = mIconOnlyViewCalculator;
                } else {
                    mStringTitleViewCalculator.setText(menuItem.getTitle());
                    calculator = mStringTitleViewCalculator;
                }
                calculator.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                return calculator.getMeasuredWidth();
            }

            private TextView getStringTitleView(
                    MenuItem menuItem, int minimumWidth, View convertView) {
                TextView menuButton;
                if (convertView != null) {
                    menuButton = (TextView) convertView;
                } else {
                    menuButton = (TextView) LayoutInflater.from(mContext)
                            .inflate(R.layout.floating_popup_overflow_list_item, null);
                    menuButton.setLayoutParams(new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                if (menuItem != null) {
                    menuButton.setText(menuItem.getTitle());
                    menuButton.setContentDescription(menuItem.getTitle());
                    menuButton.setMinimumWidth(minimumWidth);
                }
                return menuButton;
            }

            private View getIconOnlyView(
                    MenuItem menuItem, int minimumWidth, View convertView) {
                View menuButton;
                if (convertView != null) {
                    menuButton = convertView;
                } else {
                    menuButton = LayoutInflater.from(mContext).inflate(
                            R.layout.floating_popup_overflow_image_list_item, null);
                    menuButton.setLayoutParams(new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                if (menuItem != null) {
                    ((ImageView) menuButton
                            .findViewById(R.id.floating_toolbar_menu_item_image_button))
                            .setImageDrawable(menuItem.getIcon());
                    menuButton.setMinimumWidth(minimumWidth);
                }
                return menuButton;
            }
        }
    }

    /**
     * @return {@code true} if the menu item does not not have a string title but has an icon.
     *   {@code false} otherwise.
     */
    private static boolean isIconOnlyMenuItem(MenuItem menuItem) {
        if (TextUtils.isEmpty(menuItem.getTitle()) && menuItem.getIcon() != null) {
            return true;
        }
        return false;
    }

    /**
     * Creates and returns a menu button for the specified menu item.
     */
    private static View createMenuItemButton(Context context, MenuItem menuItem) {
        if (isIconOnlyMenuItem(menuItem)) {
            View imageMenuItemButton = LayoutInflater.from(context)
                    .inflate(R.layout.floating_popup_menu_image_button, null);
            ((ImageButton) imageMenuItemButton
                    .findViewById(R.id.floating_toolbar_menu_item_image_button))
                    .setImageDrawable(menuItem.getIcon());
            return imageMenuItemButton;
        }

        Button menuItemButton = (Button) LayoutInflater.from(context)
                .inflate(R.layout.floating_popup_menu_button, null);
        menuItemButton.setText(menuItem.getTitle());
        menuItemButton.setContentDescription(menuItem.getTitle());
        return menuItemButton;
    }

    private static ViewGroup createContentContainer(Context context) {
        ViewGroup contentContainer = (ViewGroup) LayoutInflater.from(context)
                .inflate(R.layout.floating_popup_container, null);
        contentContainer.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contentContainer.setTag(FLOATING_TOOLBAR_TAG);
        return contentContainer;
    }

    private static PopupWindow createPopupWindow(ViewGroup content) {
        ViewGroup popupContentHolder = new LinearLayout(content.getContext());
        PopupWindow popupWindow = new PopupWindow(popupContentHolder);
        // TODO: Use .setLayoutInScreenEnabled(true) instead of .setClippingEnabled(false)
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
        int themeId = isLightTheme ? R.style.Theme_Material_Light : R.style.Theme_Material;
        a.recycle();
        return new ContextThemeWrapper(originalContext, themeId);
    }
}
