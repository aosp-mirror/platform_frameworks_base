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
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.Size;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.Transformation;
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

    private static final MenuItem.OnMenuItemClickListener NO_OP_MENUITEM_CLICK_LISTENER =
            new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    return false;
                }
            };

    private final Context mContext;
    private final FloatingToolbarPopup mPopup;

    private final Rect mContentRect = new Rect();
    private final Rect mPreviousContentRect = new Rect();

    private Menu mMenu;
    private List<Object> mShowingMenuItems = new ArrayList<Object>();
    private MenuItem.OnMenuItemClickListener mMenuItemClickListener = NO_OP_MENUITEM_CLICK_LISTENER;

    private int mSuggestedWidth;
    private boolean mWidthChanged = true;

    private final ComponentCallbacks mOrientationChangeHandler = new ComponentCallbacks() {
        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            if (mPopup.isShowing() && mPopup.viewPortHasChanged()) {
                mWidthChanged = true;
                updateLayout();
            }
        }

        @Override
        public void onLowMemory() {}
    };

    /**
     * Initializes a floating toolbar.
     */
    public FloatingToolbar(Context context, Window window) {
        Preconditions.checkNotNull(context);
        Preconditions.checkNotNull(window);
        mContext = applyDefaultTheme(context);
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
        mContext.unregisterComponentCallbacks(mOrientationChangeHandler);
        mContext.registerComponentCallbacks(mOrientationChangeHandler);
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
        return this;
    }

    /**
     * Updates this floating toolbar to reflect recent position and view updates.
     * NOTE: This method is a no-op if the toolbar isn't showing.
     */
    public FloatingToolbar updateLayout() {
        if (mPopup.isShowing()) {
            // show() performs all the logic we need here.
            show();
        }
        return this;
    }

    /**
     * Dismisses this floating toolbar.
     */
    public void dismiss() {
        mContext.unregisterComponentCallbacks(mOrientationChangeHandler);
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


    /**
     * A popup window used by the floating toolbar.
     *
     * This class is responsible for the rendering/animation of the floating toolbar.
     * It can hold one of 2 panels (i.e. main panel and overflow panel) at a time.
     * It delegates specific panel functionality to the appropriate panel.
     */
    private static final class FloatingToolbarPopup {

        public static final int OVERFLOW_DIRECTION_UP = 0;
        public static final int OVERFLOW_DIRECTION_DOWN = 1;

        private final Context mContext;
        private final View mParent;
        private final PopupWindow mPopupWindow;
        private final ViewGroup mContentContainer;
        private final int mMarginHorizontal;
        private final int mMarginVertical;

        private final Animation.AnimationListener mOnOverflowOpened =
                new Animation.AnimationListener() {
                    @Override
                    public void onAnimationStart(Animation animation) {}

                    @Override
                    public void onAnimationEnd(Animation animation) {
                        setOverflowPanelAsContent();
                        mOverflowPanel.fadeIn(true);
                    }

                    @Override
                    public void onAnimationRepeat(Animation animation) {}
                };
        private final Animation.AnimationListener mOnOverflowClosed =
                new Animation.AnimationListener() {
                    @Override
                    public void onAnimationStart(Animation animation) {}

                    @Override
                    public void onAnimationEnd(Animation animation) {
                        setMainPanelAsContent();
                        mMainPanel.fadeIn(true);
                    }

                    @Override
                    public void onAnimationRepeat(Animation animation) {
                    }
                };
        private final AnimatorSet mDismissAnimation;
        private final AnimatorSet mHideAnimation;
        private final AnimationSet mOpenOverflowAnimation = new AnimationSet(true);
        private final AnimationSet mCloseOverflowAnimation = new AnimationSet(true);

        private final Runnable mOpenOverflow = new Runnable() {
            @Override
            public void run() {
                openOverflow();
            }
        };
        private final Runnable mCloseOverflow = new Runnable() {
            @Override
            public void run() {
                closeOverflow();
            }
        };

        private final Rect mViewPortOnScreen = new Rect();
        private final Point mCoordsOnWindow = new Point();
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

        private boolean mDismissed = true; // tracks whether this popup is dismissed or dismissing.
        private boolean mHidden; // tracks whether this popup is hidden or hiding.

        private FloatingToolbarOverflowPanel mOverflowPanel;
        private FloatingToolbarMainPanel mMainPanel;
        private int mOverflowDirection;

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
            mMarginHorizontal = parent.getResources()
                    .getDimensionPixelSize(R.dimen.floating_toolbar_horizontal_margin);
            mMarginVertical = parent.getResources()
                    .getDimensionPixelSize(R.dimen.floating_toolbar_vertical_margin);
        }

        /**
         * Lays out buttons for the specified menu items.
         */
        public void layoutMenuItems(
                List<MenuItem> menuItems,
                MenuItem.OnMenuItemClickListener menuItemClickListener,
                int suggestedWidth) {
            Preconditions.checkNotNull(menuItems);

            mContentContainer.removeAllViews();
            if (mMainPanel == null) {
                mMainPanel = new FloatingToolbarMainPanel(mContext, mOpenOverflow);
            }
            List<MenuItem> overflowMenuItems =
                    mMainPanel.layoutMenuItems(menuItems, getToolbarWidth(suggestedWidth));
            mMainPanel.setOnMenuItemClickListener(menuItemClickListener);
            if (!overflowMenuItems.isEmpty()) {
                if (mOverflowPanel == null) {
                    mOverflowPanel =
                            new FloatingToolbarOverflowPanel(mContext, mCloseOverflow);
                }
                mOverflowPanel.setMenuItems(overflowMenuItems);
                mOverflowPanel.setOnMenuItemClickListener(menuItemClickListener);
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

            // Make sure a panel is set as the content.
            if (mContentContainer.getChildCount() == 0) {
                setMainPanelAsContent();
                // If we're yet to show the popup, set the container visibility to zero.
                // The "show" animation will make this visible.
                mContentContainer.setAlpha(0);
            }
            refreshCoordinatesAndOverflowDirection(contentRectOnScreen);
            preparePopupContent();
            // We need to specify the position in window coordinates.
            // TODO: Consider to use PopupWindow.setLayoutInScreenEnabled(true) so that we can
            // specify the popup poision in screen coordinates.
            mPopupWindow.showAtLocation(mParent, Gravity.NO_GRAVITY, mCoordsOnWindow.x,
                    mCoordsOnWindow.y);
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
            // specify the popup poision in screen coordinates.
            mPopupWindow.update(mCoordsOnWindow.x, mCoordsOnWindow.y, getWidth(), getHeight());
        }

        /**
         * Returns the width of this popup.
         */
        public int getWidth() {
            return mPopupWindow.getWidth();
        }

        /**
         * Returns the height of this popup.
         */
        public int getHeight() {
            return mPopupWindow.getHeight();
        }

        /**
         * Returns the context this popup is running in.
         */
        public Context getContext() {
            return mContext;
        }

        private void refreshCoordinatesAndOverflowDirection(Rect contentRectOnScreen) {
            refreshViewPort();

            int x = contentRectOnScreen.centerX() - getWidth() / 2;
            // Update x so that the toolbar isn't rendered behind the nav bar in landscape.
            x = Math.max(0, Math.min(x, mViewPortOnScreen.right - getWidth()));

            int y;

            int availableHeightAboveContent = contentRectOnScreen.top - mViewPortOnScreen.top;
            int availableHeightBelowContent = mViewPortOnScreen.bottom - contentRectOnScreen.bottom;

            if (mOverflowPanel == null) {  // There is no overflow.
                if (availableHeightAboveContent >= getToolbarHeightWithVerticalMargin()) {
                    // There is enough space at the top of the content.
                    y = contentRectOnScreen.top - getToolbarHeightWithVerticalMargin();
                } else if (availableHeightBelowContent >= getToolbarHeightWithVerticalMargin()) {
                    // There is enough space at the bottom of the content.
                    y = contentRectOnScreen.bottom;
                } else if (availableHeightBelowContent >= getEstimatedToolbarHeight(mContext)) {
                    // Just enough space to fit the toolbar with no vertical margins.
                    y = contentRectOnScreen.bottom - mMarginVertical;
                } else {
                    // Not enough space. Prefer to position as high as possible.
                    y = Math.max(
                            mViewPortOnScreen.top,
                            contentRectOnScreen.top - getToolbarHeightWithVerticalMargin());
                }
            } else {  // There is an overflow.
                int margin = 2 * mMarginVertical;
                int minimumOverflowHeightWithMargin = mOverflowPanel.getMinimumHeight() + margin;
                int availableHeightThroughContentDown = mViewPortOnScreen.bottom -
                        contentRectOnScreen.top + getToolbarHeightWithVerticalMargin();
                int availableHeightThroughContentUp = contentRectOnScreen.bottom -
                        mViewPortOnScreen.top + getToolbarHeightWithVerticalMargin();

                if (availableHeightAboveContent >= minimumOverflowHeightWithMargin) {
                    // There is enough space at the top of the content rect for the overflow.
                    // Position above and open upwards.
                    updateOverflowHeight(availableHeightAboveContent - margin);
                    y = contentRectOnScreen.top - getHeight();
                    mOverflowDirection = OVERFLOW_DIRECTION_UP;
                } else if (availableHeightAboveContent >= getToolbarHeightWithVerticalMargin()
                        && availableHeightThroughContentDown >= minimumOverflowHeightWithMargin) {
                    // There is enough space at the top of the content rect for the main panel
                    // but not the overflow.
                    // Position above but open downwards.
                    updateOverflowHeight(availableHeightThroughContentDown - margin);
                    y = contentRectOnScreen.top - getToolbarHeightWithVerticalMargin();
                    mOverflowDirection = OVERFLOW_DIRECTION_DOWN;
                } else if (availableHeightBelowContent >= minimumOverflowHeightWithMargin) {
                    // There is enough space at the bottom of the content rect for the overflow.
                    // Position below and open downwards.
                    updateOverflowHeight(availableHeightBelowContent - margin);
                    y = contentRectOnScreen.bottom;
                    mOverflowDirection = OVERFLOW_DIRECTION_DOWN;
                } else if (availableHeightBelowContent >= getToolbarHeightWithVerticalMargin()
                        && mViewPortOnScreen.height() >= minimumOverflowHeightWithMargin) {
                    // There is enough space at the bottom of the content rect for the main panel
                    // but not the overflow.
                    // Position below but open upwards.
                    updateOverflowHeight(availableHeightThroughContentUp - margin);
                    y = contentRectOnScreen.bottom + getToolbarHeightWithVerticalMargin() -
                            getHeight();
                    mOverflowDirection = OVERFLOW_DIRECTION_UP;
                } else {
                    // Not enough space.
                    // Position at the top of the view port and open downwards.
                    updateOverflowHeight(mViewPortOnScreen.height() - margin);
                    y = mViewPortOnScreen.top;
                    mOverflowDirection = OVERFLOW_DIRECTION_DOWN;
                }
                mOverflowPanel.setOverflowDirection(mOverflowDirection);
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
            mCoordsOnWindow.set(x - windowLeftOnScreen, y - windowTopOnScreen);
        }

        private int getToolbarHeightWithVerticalMargin() {
            return getEstimatedToolbarHeight(mContext) + mMarginVertical * 2;
        }

        /**
         * Performs the "show" animation on the floating popup.
         */
        private void runShowAnimation() {
            createEnterAnimation(mContentContainer).start();
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
            if (mOpenOverflowAnimation.hasStarted()
                    && !mOpenOverflowAnimation.hasEnded()) {
                // Remove the animation listener, stop the animation,
                // then trigger the lister explicitly so it is not posted
                // to the message queue.
                mOpenOverflowAnimation.setAnimationListener(null);
                mContentContainer.clearAnimation();
                mOnOverflowOpened.onAnimationEnd(null);
            }
            if (mCloseOverflowAnimation.hasStarted()
                    && !mCloseOverflowAnimation.hasEnded()) {
                // Remove the animation listener, stop the animation,
                // then trigger the lister explicitly so it is not posted
                // to the message queue.
                mCloseOverflowAnimation.setAnimationListener(null);
                mContentContainer.clearAnimation();
                mOnOverflowClosed.onAnimationEnd(null);
            }
        }

        /**
         * Opens the floating toolbar overflow.
         * This method should not be called if menu items have not been laid out with
         * {@link #layoutMenuItems(java.util.List, MenuItem.OnMenuItemClickListener, int)}.
         *
         * @throws IllegalStateException if called when menu items have not been laid out.
         */
        private void openOverflow() {
            Preconditions.checkState(mMainPanel != null);
            Preconditions.checkState(mOverflowPanel != null);

            mMainPanel.fadeOut(true);
            Size overflowPanelSize = mOverflowPanel.measure();
            final int targetWidth = overflowPanelSize.getWidth();
            final int targetHeight = overflowPanelSize.getHeight();
            final boolean morphUpwards = (mOverflowDirection == OVERFLOW_DIRECTION_UP);
            final int startWidth = mContentContainer.getWidth();
            final int startHeight = mContentContainer.getHeight();
            final float startY = mContentContainer.getY();
            final float left = mContentContainer.getX();
            final float right = left + mContentContainer.getWidth();
            Animation widthAnimation = new Animation() {
                @Override
                protected void applyTransformation(float interpolatedTime, Transformation t) {
                    ViewGroup.LayoutParams params = mContentContainer.getLayoutParams();
                    int deltaWidth = (int) (interpolatedTime * (targetWidth - startWidth));
                    params.width = startWidth + deltaWidth;
                    mContentContainer.setLayoutParams(params);
                    if (isRTL()) {
                        mContentContainer.setX(left);
                    } else {
                        mContentContainer.setX(right - mContentContainer.getWidth());
                    }
                }
            };
            Animation heightAnimation = new Animation() {
                @Override
                protected void applyTransformation(float interpolatedTime, Transformation t) {
                    ViewGroup.LayoutParams params = mContentContainer.getLayoutParams();
                    int deltaHeight = (int) (interpolatedTime * (targetHeight - startHeight));
                    params.height = startHeight + deltaHeight;
                    mContentContainer.setLayoutParams(params);
                    if (morphUpwards) {
                        float y = startY - (mContentContainer.getHeight() - startHeight);
                        mContentContainer.setY(y);
                    }
                }
            };
            widthAnimation.setDuration(240);
            heightAnimation.setDuration(180);
            heightAnimation.setStartOffset(60);
            mOpenOverflowAnimation.getAnimations().clear();
            mOpenOverflowAnimation.setAnimationListener(mOnOverflowOpened);
            mOpenOverflowAnimation.addAnimation(widthAnimation);
            mOpenOverflowAnimation.addAnimation(heightAnimation);
            mContentContainer.startAnimation(mOpenOverflowAnimation);
        }

        /**
         * Opens the floating toolbar overflow.
         * This method should not be called if menu items have not been laid out with
         * {@link #layoutMenuItems(java.util.List, MenuItem.OnMenuItemClickListener, int)}.
         *
         * @throws IllegalStateException if called when menu items have not been laid out.
         */
        private void closeOverflow() {
            Preconditions.checkState(mMainPanel != null);
            Preconditions.checkState(mOverflowPanel != null);

            mOverflowPanel.fadeOut(true);
            Size mainPanelSize = mMainPanel.measure();
            final int targetWidth = mainPanelSize.getWidth();
            final int targetHeight = mainPanelSize.getHeight();
            final int startWidth = mContentContainer.getWidth();
            final int startHeight = mContentContainer.getHeight();
            final float bottom = mContentContainer.getY() + mContentContainer.getHeight();
            final boolean morphedUpwards = (mOverflowDirection == OVERFLOW_DIRECTION_UP);
            final float left = mContentContainer.getX();
            final float right = left + mContentContainer.getWidth();
            Animation widthAnimation = new Animation() {
                @Override
                protected void applyTransformation(float interpolatedTime, Transformation t) {
                    ViewGroup.LayoutParams params = mContentContainer.getLayoutParams();
                    int deltaWidth = (int) (interpolatedTime * (targetWidth - startWidth));
                    params.width = startWidth + deltaWidth;
                    mContentContainer.setLayoutParams(params);
                    if (isRTL()) {
                        mContentContainer.setX(left);
                    } else {
                        mContentContainer.setX(right - mContentContainer.getWidth());
                    }
                }
            };
            Animation heightAnimation = new Animation() {
                @Override
                protected void applyTransformation(float interpolatedTime, Transformation t) {
                    ViewGroup.LayoutParams params = mContentContainer.getLayoutParams();
                    int deltaHeight = (int) (interpolatedTime * (targetHeight - startHeight));
                    params.height = startHeight + deltaHeight;
                    mContentContainer.setLayoutParams(params);
                    if (morphedUpwards) {
                        mContentContainer.setY(bottom - mContentContainer.getHeight());
                    }
                }
            };
            widthAnimation.setDuration(150);
            widthAnimation.setStartOffset(150);
            heightAnimation.setDuration(210);
            mCloseOverflowAnimation.getAnimations().clear();
            mCloseOverflowAnimation.setAnimationListener(mOnOverflowClosed);
            mCloseOverflowAnimation.addAnimation(widthAnimation);
            mCloseOverflowAnimation.addAnimation(heightAnimation);
            mContentContainer.startAnimation(mCloseOverflowAnimation);
        }

        /**
         * Prepares the content container for show and update calls.
         */
        private void preparePopupContent() {
            // Reset visibility.
            if (mMainPanel != null) {
                mMainPanel.fadeIn(false);
            }
            if (mOverflowPanel != null) {
                mOverflowPanel.fadeIn(false);
            }

            // Reset position.
            if (isMainPanelContent()) {
                positionMainPanel();
            }
            if (isOverflowPanelContent()) {
                positionOverflowPanel();
            }
        }

        private boolean isMainPanelContent() {
            return mMainPanel != null
                    && mContentContainer.getChildAt(0) == mMainPanel.getView();
        }

        private boolean isOverflowPanelContent() {
            return mOverflowPanel != null
                    && mContentContainer.getChildAt(0) == mOverflowPanel.getView();
        }

        /**
         * Sets the current content to be the main view panel.
         */
        private void setMainPanelAsContent() {
            // This should never be called if the main panel has not been initialized.
            Preconditions.checkNotNull(mMainPanel);
            mContentContainer.removeAllViews();
            Size mainPanelSize = mMainPanel.measure();
            ViewGroup.LayoutParams params = mContentContainer.getLayoutParams();
            params.width = mainPanelSize.getWidth();
            params.height = mainPanelSize.getHeight();
            mContentContainer.setLayoutParams(params);
            mContentContainer.addView(mMainPanel.getView());
            setContentAreaAsTouchableSurface();
        }

        /**
         * Sets the current content to be the overflow view panel.
         */
        private void setOverflowPanelAsContent() {
            // This should never be called if the overflow panel has not been initialized.
            Preconditions.checkNotNull(mOverflowPanel);
            mContentContainer.removeAllViews();
            Size overflowPanelSize = mOverflowPanel.measure();
            ViewGroup.LayoutParams params = mContentContainer.getLayoutParams();
            params.width = overflowPanelSize.getWidth();
            params.height = overflowPanelSize.getHeight();
            mContentContainer.setLayoutParams(params);
            mContentContainer.addView(mOverflowPanel.getView());
            setContentAreaAsTouchableSurface();
        }

        /**
         * Places the main view panel at the appropriate resting coordinates.
         */
        private void positionMainPanel() {
            Preconditions.checkNotNull(mMainPanel);
            mContentContainer.setX(mMarginHorizontal);

            float y = mMarginVertical;
            if  (mOverflowDirection == OVERFLOW_DIRECTION_UP) {
                y = getHeight()
                        - (mMainPanel.getView().getMeasuredHeight() + mMarginVertical);
            }
            mContentContainer.setY(y);
            setContentAreaAsTouchableSurface();
        }

        /**
         * Places the main view panel at the appropriate resting coordinates.
         */
        private void positionOverflowPanel() {
            Preconditions.checkNotNull(mOverflowPanel);
            float x;
            if (isRTL()) {
                x = mMarginHorizontal;
            } else {
                x = mPopupWindow.getWidth()
                    - (mOverflowPanel.getView().getMeasuredWidth() + mMarginHorizontal);
            }
            mContentContainer.setX(x);
            mContentContainer.setY(mMarginVertical);
            setContentAreaAsTouchableSurface();
        }

        private void updateOverflowHeight(int height) {
            if (mOverflowPanel != null) {
                mOverflowPanel.setSuggestedHeight(height);

                // Re-measure the popup and it's contents.
                boolean mainPanelContent = isMainPanelContent();
                boolean overflowPanelContent = isOverflowPanelContent();
                mContentContainer.removeAllViews();  // required to update popup size.
                updatePopupSize();
                // Reset the appropriate content.
                if (mainPanelContent) {
                    setMainPanelAsContent();
                }
                if (overflowPanelContent) {
                    setOverflowPanelAsContent();
                }
            }
        }

        private void updatePopupSize() {
            int width = 0;
            int height = 0;
            if (mMainPanel != null) {
                Size mainPanelSize = mMainPanel.measure();
                width = mainPanelSize.getWidth();
                height = mainPanelSize.getHeight();
            }
            if (mOverflowPanel != null) {
                Size overflowPanelSize = mOverflowPanel.measure();
                width = Math.max(width, overflowPanelSize.getWidth());
                height = Math.max(height, overflowPanelSize.getHeight());
            }
            mPopupWindow.setWidth(width + mMarginHorizontal * 2);
            mPopupWindow.setHeight(height + mMarginVertical * 2);
        }


        private void refreshViewPort() {
            mParent.getWindowVisibleDisplayFrame(mViewPortOnScreen);
        }

        private boolean viewPortHasChanged() {
            mParent.getWindowVisibleDisplayFrame(mTmpRect);
            return !mTmpRect.equals(mViewPortOnScreen);
        }

        private int getToolbarWidth(int suggestedWidth) {
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
            if (!mPopupWindow.isShowing()) {
                mContentContainer.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
            }
            int width = mContentContainer.getMeasuredWidth();
            int height = mContentContainer.getMeasuredHeight();
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

        private boolean isRTL() {
            return mContentContainer.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        }
    }

    /**
     * A widget that holds the primary menu items in the floating toolbar.
     */
    private static final class FloatingToolbarMainPanel {

        private final Context mContext;
        private final ViewGroup mContentView;
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
        private final ViewFader viewFader;
        private final Runnable mOpenOverflow;

        private View mOpenOverflowButton;
        private MenuItem.OnMenuItemClickListener mOnMenuItemClickListener;

        /**
         * Initializes a floating toolbar popup main view panel.
         *
         * @param context
         * @param openOverflow  The code that opens the toolbar popup overflow.
         */
        public FloatingToolbarMainPanel(Context context, Runnable openOverflow) {
            mContext = Preconditions.checkNotNull(context);
            mContentView = new LinearLayout(context);
            viewFader = new ViewFader(mContentView);
            mOpenOverflow = Preconditions.checkNotNull(openOverflow);
        }

        /**
         * Fits as many menu items in the main panel and returns a list of the menu items that
         * were not fit in.
         *
         * @return The menu items that are not included in this main panel.
         */
        public List<MenuItem> layoutMenuItems(List<MenuItem> menuItems, int width) {
            Preconditions.checkNotNull(menuItems);

            // Reserve space for the "open overflow" button.
            final int toolbarWidth = width - getEstimatedOpenOverflowButtonWidth(mContext);

            int availableWidth = toolbarWidth;
            final LinkedList<MenuItem> remainingMenuItems = new LinkedList<MenuItem>(menuItems);

            mContentView.removeAllViews();

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
                if (menuItemButtonWidth <= availableWidth) {
                    setButtonTagAndClickListener(menuItemButton, menuItem);
                    mContentView.addView(menuItemButton);
                    ViewGroup.LayoutParams params = menuItemButton.getLayoutParams();
                    params.width = menuItemButtonWidth;
                    menuItemButton.setLayoutParams(params);
                    availableWidth -= menuItemButtonWidth;
                    remainingMenuItems.pop();
                } else {
                    if (mOpenOverflowButton == null) {
                        mOpenOverflowButton = LayoutInflater.from(mContext)
                                .inflate(R.layout.floating_popup_open_overflow_button, null);
                        mOpenOverflowButton.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (mOpenOverflowButton != null) {
                                    mOpenOverflow.run();
                                }
                            }
                        });
                    }
                    mContentView.addView(mOpenOverflowButton);
                    break;
                }
            }
            return remainingMenuItems;
        }

        public void setOnMenuItemClickListener(MenuItem.OnMenuItemClickListener listener) {
            mOnMenuItemClickListener = listener;
        }

        public View getView() {
            return mContentView;
        }

        public void fadeIn(boolean animate) {
            viewFader.fadeIn(animate);
        }

        public void fadeOut(boolean animate) {
            viewFader.fadeOut(animate);
        }

        /**
         * Returns how big this panel's view should be.
         * This method should only be called when the view has not been attached to a parent
         * otherwise it will throw an illegal state.
         */
        public Size measure() throws IllegalStateException {
            Preconditions.checkState(mContentView.getParent() == null);
            mContentView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
            return new Size(mContentView.getMeasuredWidth(), mContentView.getMeasuredHeight());
        }

        private void setButtonTagAndClickListener(View menuItemButton, MenuItem menuItem) {
            View button = menuItemButton;
            if (isIconOnlyMenuItem(menuItem)) {
                button = menuItemButton.findViewById(R.id.floating_toolbar_menu_item_image_button);
            }
            button.setTag(menuItem);
            button.setOnClickListener(mMenuItemButtonOnClickListener);
        }
    }


    /**
     * A widget that holds the overflow items in the floating toolbar.
     */
    private static final class FloatingToolbarOverflowPanel {

        private final LinearLayout mContentView;
        private final ViewGroup mBackButtonContainer;
        private final View mBackButton;
        private final ListView mListView;
        private final TextView mListViewItemWidthCalculator;
        private final ViewFader mViewFader;
        private final Runnable mCloseOverflow;

        private MenuItem.OnMenuItemClickListener mOnMenuItemClickListener;
        private int mOverflowWidth;
        private int mSuggestedHeight;

        /**
         * Initializes a floating toolbar popup overflow view panel.
         *
         * @param context
         * @param closeOverflow  The code that closes the toolbar popup's overflow.
         */
        public FloatingToolbarOverflowPanel(Context context, Runnable closeOverflow) {
            mCloseOverflow = Preconditions.checkNotNull(closeOverflow);

            mContentView = new LinearLayout(context);
            mContentView.setOrientation(LinearLayout.VERTICAL);
            mViewFader = new ViewFader(mContentView);

            mBackButton = LayoutInflater.from(context)
                    .inflate(R.layout.floating_popup_close_overflow_button, null);
            mBackButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mCloseOverflow.run();
                }
            });
            mBackButtonContainer = new LinearLayout(context);
            mBackButtonContainer.addView(mBackButton);

            mListView = createOverflowListView();
            mListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    MenuItem menuItem = (MenuItem) mListView.getAdapter().getItem(position);
                    if (mOnMenuItemClickListener != null) {
                        mOnMenuItemClickListener.onMenuItemClick(menuItem);
                    }
                }
            });

            mContentView.addView(mListView);
            mContentView.addView(mBackButtonContainer);

            mListViewItemWidthCalculator = createOverflowMenuItemButton(context);
            mListViewItemWidthCalculator.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        /**
         * Sets the menu items to be displayed in the overflow.
         */
        public void setMenuItems(List<MenuItem> menuItems) {
            ArrayAdapter overflowListViewAdapter = (ArrayAdapter) mListView.getAdapter();
            overflowListViewAdapter.clear();
            overflowListViewAdapter.addAll(menuItems);
            setListViewHeight();
            setOverflowWidth();
        }

        public void setOnMenuItemClickListener(MenuItem.OnMenuItemClickListener listener) {
            mOnMenuItemClickListener = listener;
        }

        /**
         * Notifies the overflow of the current direction in which the overflow will be opened.
         *
         * @param overflowDirection  {@link FloatingToolbarPopup#OVERFLOW_DIRECTION_UP}
         *   or {@link FloatingToolbarPopup#OVERFLOW_DIRECTION_DOWN}.
         */
        public void setOverflowDirection(int overflowDirection) {
            mContentView.removeView(mBackButtonContainer);
            int index = (overflowDirection == FloatingToolbarPopup.OVERFLOW_DIRECTION_UP)? 1 : 0;
            mContentView.addView(mBackButtonContainer, index);
        }

        public void setSuggestedHeight(int height) {
            mSuggestedHeight = height;
            setListViewHeight();
        }

        public int getMinimumHeight() {
            return mContentView.getContext().getResources().
                    getDimensionPixelSize(R.dimen.floating_toolbar_minimum_overflow_height)
                    + getEstimatedToolbarHeight(mContentView.getContext());
        }

        /**
         * Returns the content view of the overflow.
         */
        public View getView() {
            return mContentView;
        }

        public void fadeIn(boolean animate) {
            mViewFader.fadeIn(animate);
        }

        public void fadeOut(boolean animate) {
            mViewFader.fadeOut(animate);
        }

        /**
         * Returns how big this panel's view should be.
         * This method should only be called when the view has not been attached to a parent.
         *
         * @throws IllegalStateException
         */
        public Size measure() {
            Preconditions.checkState(mContentView.getParent() == null);
            mContentView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
            return new Size(mContentView.getMeasuredWidth(), mContentView.getMeasuredHeight());
        }

        private void setListViewHeight() {
            int itemHeight = getEstimatedToolbarHeight(mContentView.getContext());
            int height = mListView.getAdapter().getCount() * itemHeight;
            int maxHeight = mContentView.getContext().getResources().
                    getDimensionPixelSize(R.dimen.floating_toolbar_maximum_overflow_height);
            int minHeight = mContentView.getContext().getResources().
                    getDimensionPixelSize(R.dimen.floating_toolbar_minimum_overflow_height);
            int suggestedListViewHeight = mSuggestedHeight - (mSuggestedHeight % itemHeight)
                    - itemHeight;  // reserve space for the back button.
            ViewGroup.LayoutParams params = mListView.getLayoutParams();
            if (suggestedListViewHeight <= 0) {
                // Invalid height. Use the maximum height available.
                params.height = Math.min(maxHeight, height);
            } else if (suggestedListViewHeight < minHeight) {
                // Height is smaller than minimum allowed. Use minimum height.
                params.height = minHeight;
            } else {
                // Use the suggested height. Cap it at the maximum available height.
                params.height = Math.min(Math.min(suggestedListViewHeight, maxHeight), height);
            }
            mListView.setLayoutParams(params);
        }

        private void setOverflowWidth() {
            mOverflowWidth = 0;
            for (int i = 0; i < mListView.getAdapter().getCount(); i++) {
                MenuItem menuItem = (MenuItem) mListView.getAdapter().getItem(i);
                Preconditions.checkNotNull(menuItem);
                mListViewItemWidthCalculator.setText(menuItem.getTitle());
                mListViewItemWidthCalculator.measure(
                        MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
                mOverflowWidth = Math.max(
                        mListViewItemWidthCalculator.getMeasuredWidth(), mOverflowWidth);
            }
        }

        private ListView createOverflowListView() {
            final Context context = mContentView.getContext();
            final ListView overflowListView = new ListView(context);
            overflowListView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            overflowListView.setDivider(null);
            overflowListView.setDividerHeight(0);

            final int viewTypeCount = 2;
            final int stringLabelViewType = 0;
            final int iconOnlyViewType = 1;
            final ArrayAdapter overflowListViewAdapter =
                    new ArrayAdapter<MenuItem>(context, 0) {
                        @Override
                        public int getViewTypeCount() {
                            return viewTypeCount;
                        }

                        @Override
                        public int getItemViewType(int position) {
                            if (isIconOnlyMenuItem(getItem(position))) {
                                return iconOnlyViewType;
                            }
                            return stringLabelViewType;
                        }

                        @Override
                        public View getView(int position, View convertView, ViewGroup parent) {
                            if (getItemViewType(position) == iconOnlyViewType) {
                                return getIconOnlyView(position, convertView);
                            }
                            return getStringTitleView(position, convertView);
                        }

                        private View getStringTitleView(int position, View convertView) {
                            TextView menuButton;
                            if (convertView != null) {
                                menuButton = (TextView) convertView;
                            } else {
                                menuButton = createOverflowMenuItemButton(context);
                            }
                            MenuItem menuItem = getItem(position);
                            menuButton.setText(menuItem.getTitle());
                            menuButton.setContentDescription(menuItem.getTitle());
                            menuButton.setMinimumWidth(mOverflowWidth);
                            return menuButton;
                        }

                        private View getIconOnlyView(int position, View convertView) {
                            View menuButton;
                            if (convertView != null) {
                                menuButton = convertView;
                            } else {
                                menuButton = LayoutInflater.from(context).inflate(
                                        R.layout.floating_popup_overflow_image_list_item, null);
                            }
                            MenuItem menuItem = getItem(position);
                            ((ImageView) menuButton
                                    .findViewById(R.id.floating_toolbar_menu_item_image_button))
                                    .setImageDrawable(menuItem.getIcon());
                            menuButton.setMinimumWidth(mOverflowWidth);
                            return menuButton;
                        }
                    };
            overflowListView.setAdapter(overflowListViewAdapter);
            return overflowListView;
        }
    }


    /**
     * A helper for fading in or out a view.
     */
    private static final class ViewFader {

        private static final int FADE_OUT_DURATION = 250;
        private static final int FADE_IN_DURATION = 150;

        private final View mView;
        private final ObjectAnimator mFadeOutAnimation;
        private final ObjectAnimator mFadeInAnimation;

        private ViewFader(View view) {
            mView = Preconditions.checkNotNull(view);
            mFadeOutAnimation = ObjectAnimator.ofFloat(view, View.ALPHA, 1, 0)
                    .setDuration(FADE_OUT_DURATION);
            mFadeInAnimation = ObjectAnimator.ofFloat(view, View.ALPHA, 0, 1)
                    .setDuration(FADE_IN_DURATION);
        }

        public void fadeIn(boolean animate) {
            cancelFadeAnimations();
            if (animate) {
                mFadeInAnimation.start();
            } else {
                mView.setAlpha(1);
            }
        }

        public void fadeOut(boolean animate) {
            cancelFadeAnimations();
            if (animate) {
                mFadeOutAnimation.start();
            } else {
                mView.setAlpha(0);
            }
        }

        private void cancelFadeAnimations() {
            mFadeInAnimation.cancel();
            mFadeOutAnimation.cancel();
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

    /**
     * Creates and returns a styled floating toolbar overflow list view item.
     */
    private static TextView createOverflowMenuItemButton(Context context) {
        return (TextView) LayoutInflater.from(context)
                .inflate(R.layout.floating_popup_overflow_list_item, null);
    }

    private static ViewGroup createContentContainer(Context context) {
        return (ViewGroup) LayoutInflater.from(context)
                .inflate(R.layout.floating_popup_container, null);
    }

    private static PopupWindow createPopupWindow(View content) {
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
        AnimatorSet animation =  new AnimatorSet();
        animation.playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 0, 1).setDuration(150),
                // Make sure that view.x is always fixed throughout the duration of this animation.
                ObjectAnimator.ofFloat(view, View.X, view.getX(), view.getX()));
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

    private static int getEstimatedToolbarHeight(Context context) {
        return context.getResources().getDimensionPixelSize(R.dimen.floating_toolbar_height);
    }

    private static int getEstimatedOpenOverflowButtonWidth(Context context) {
        return context.getResources()
                .getDimensionPixelSize(R.dimen.floating_toolbar_menu_button_minimum_width);
    }
}
