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
import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import com.android.internal.R;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * A floating toolbar for showing contextual menu items.
 * This view shows as many menu item buttons as can fit in the horizontal toolbar and the
 * the remaining menu items in a vertical overflow view when the overflow button is clicked.
 * The horizontal toolbar morphs into the vertical overflow view.
 */
public final class FloatingToolbar {

    private static final MenuItem.OnMenuItemClickListener NO_OP_MENUITEM_CLICK_LISTENER =
            new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    return false;
                }
            };

    private final Context mContext;
    private final FloatingToolbarPopup mPopup;
    private final ViewGroup mMenuItemButtonsContainer;
    private final View.OnClickListener mMenuItemButtonOnClickListener =
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (v.getTag() instanceof MenuItem) {
                        mMenuItemClickListener.onMenuItemClick((MenuItem) v.getTag());
                        mPopup.dismiss();
                    }
                }
            };

    private final Rect mContentRect = new Rect();
    private final Point mCoordinates = new Point();

    private Menu mMenu;
    private List<CharSequence> mShowingTitles = new ArrayList<CharSequence>();
    private MenuItem.OnMenuItemClickListener mMenuItemClickListener = NO_OP_MENUITEM_CLICK_LISTENER;
    private View mOpenOverflowButton;

    private int mSuggestedWidth;

    /**
     * Initializes a floating toolbar.
     */
    public FloatingToolbar(Context context, Window window) {
        mContext = Preconditions.checkNotNull(context);
        mPopup = new FloatingToolbarPopup(Preconditions.checkNotNull(window.getDecorView()));
        mMenuItemButtonsContainer = createMenuButtonsContainer(context);
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
     * Sets the custom listener for invocation of menu items in this floating
     * toolbar.
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
        mSuggestedWidth = suggestedWidth;
        return this;
    }

    /**
     * Shows this floating toolbar.
     */
    public FloatingToolbar show() {
        List<MenuItem> menuItems = getVisibleAndEnabledMenuItems(mMenu);
        if (hasContentChanged(menuItems) || hasWidthChanged()) {
            mPopup.dismiss();
            layoutMenuItemButtons(menuItems);
            mShowingTitles = getMenuItemTitles(menuItems);
        }
        refreshCoordinates();
        mPopup.updateCoordinates(mCoordinates.x, mCoordinates.y);
        if (!mPopup.isShowing()) {
            mPopup.show(mCoordinates.x, mCoordinates.y);
        }
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
        mPopup.dismiss();
    }

    /**
     * Returns {@code true} if this popup is currently showing. {@code false} otherwise.
     */
    public boolean isShowing() {
        return mPopup.isShowing();
    }

    /**
     * Refreshes {@link #mCoordinates} with values based on {@link #mContentRect}.
     */
    private void refreshCoordinates() {
        int popupWidth = mPopup.getWidth();
        int popupHeight = mPopup.getHeight();
        if (!mPopup.isShowing()) {
            // Popup isn't yet shown, get estimated size from the menu item buttons container.
            mMenuItemButtonsContainer.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
            popupWidth = mMenuItemButtonsContainer.getMeasuredWidth();
            popupHeight = mMenuItemButtonsContainer.getMeasuredHeight();
        }
        int x = mContentRect.centerX() - popupWidth / 2;
        int y;
        if (shouldDisplayAtTopOfContent()) {
            y = mContentRect.top - popupHeight;
        } else {
            y = mContentRect.bottom;
        }
        mCoordinates.set(x, y);
    }

    /**
     * Returns true if this floating toolbar's menu items have been reordered or changed.
     */
    private boolean hasContentChanged(List<MenuItem> menuItems) {
        return !mShowingTitles.equals(getMenuItemTitles(menuItems));
    }

    /**
     * Returns true if there is a significant change in width of the toolbar.
     */
    private boolean hasWidthChanged() {
        int actualWidth = mPopup.getWidth();
        int difference = Math.abs(actualWidth - mSuggestedWidth);
        return difference > (actualWidth * 0.2);
    }

    /**
     * Returns true if the preferred positioning of the toolbar is above the content rect.
     */
    private boolean shouldDisplayAtTopOfContent() {
        return mContentRect.top - getMinimumOverflowHeight(mContext) > 0;
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

    private List<CharSequence> getMenuItemTitles(List<MenuItem> menuItems) {
        List<CharSequence> titles = new ArrayList<CharSequence>();
        for (MenuItem menuItem : menuItems) {
            titles.add(menuItem.getTitle());
        }
        return titles;
    }

    private void layoutMenuItemButtons(List<MenuItem> menuItems) {
        final int toolbarWidth = getAdjustedToolbarWidth(mContext, mSuggestedWidth)
                // Reserve space for the "open overflow" button.
                - getEstimatedOpenOverflowButtonWidth(mContext);

        int availableWidth = toolbarWidth;
        LinkedList<MenuItem> remainingMenuItems = new LinkedList<MenuItem>(menuItems);

        mMenuItemButtonsContainer.removeAllViews();

        boolean isFirstItem = true;
        while (!remainingMenuItems.isEmpty()) {
            final MenuItem menuItem = remainingMenuItems.peek();
            Button menuItemButton = createMenuItemButton(mContext, menuItem);

            // Adding additional left padding for the first button to even out button spacing.
            if (isFirstItem) {
                menuItemButton.setPadding(
                        2 * menuItemButton.getPaddingLeft(),
                        menuItemButton.getPaddingTop(),
                        menuItemButton.getPaddingRight(),
                        menuItemButton.getPaddingBottom());
                isFirstItem = false;
            }

            // Adding additional right padding for the last button to even out button spacing.
            if (remainingMenuItems.size() == 1) {
                menuItemButton.setPadding(
                        menuItemButton.getPaddingLeft(),
                        menuItemButton.getPaddingTop(),
                        2 * menuItemButton.getPaddingRight(),
                        menuItemButton.getPaddingBottom());
            }

            menuItemButton.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
            int menuItemButtonWidth = Math.min(menuItemButton.getMeasuredWidth(), toolbarWidth);
            if (menuItemButtonWidth <= availableWidth) {
                menuItemButton.setTag(menuItem);
                menuItemButton.setOnClickListener(mMenuItemButtonOnClickListener);
                mMenuItemButtonsContainer.addView(menuItemButton);
                menuItemButton.getLayoutParams().width = menuItemButtonWidth;
                availableWidth -= menuItemButtonWidth;
                remainingMenuItems.pop();
            } else {
                // The "open overflow" button launches the vertical overflow from the
                // floating toolbar.
                createOpenOverflowButtonIfNotExists();
                mMenuItemButtonsContainer.addView(mOpenOverflowButton);
                break;
            }
        }
        mPopup.setContentView(mMenuItemButtonsContainer);
    }

    /**
     * Creates and returns the button that opens the vertical overflow.
     */
    private void createOpenOverflowButtonIfNotExists() {
        mOpenOverflowButton = (ImageButton) LayoutInflater.from(mContext)
                .inflate(R.layout.floating_popup_open_overflow_button, null);
        mOpenOverflowButton.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // Open the overflow.
                    }
                });
    }

    /**
     * Creates and returns a floating toolbar menu buttons container.
     */
    private static ViewGroup createMenuButtonsContainer(Context context) {
        return (ViewGroup) LayoutInflater.from(context)
                .inflate(R.layout.floating_popup_container, null);
    }

    /**
     * Creates and returns a menu button for the specified menu item.
     */
    private static Button createMenuItemButton(Context context, MenuItem menuItem) {
        Button menuItemButton = (Button) LayoutInflater.from(context)
                .inflate(R.layout.floating_popup_menu_button, null);
        menuItemButton.setText(menuItem.getTitle());
        menuItemButton.setContentDescription(menuItem.getTitle());
        return menuItemButton;
    }

    private static int getMinimumOverflowHeight(Context context) {
        return context.getResources().
                getDimensionPixelSize(R.dimen.floating_toolbar_minimum_overflow_height);
    }

    private static int getEstimatedOpenOverflowButtonWidth(Context context) {
        return context.getResources()
                .getDimensionPixelSize(R.dimen.floating_toolbar_menu_button_minimum_width);
    }

    private static int getAdjustedToolbarWidth(Context context, int width) {
        if (width <= 0 || width > getScreenWidth(context)) {
            width = context.getResources()
                    .getDimensionPixelSize(R.dimen.floating_toolbar_default_width);
        }
        return width;
    }

    /**
     * Returns the device's screen width.
     */
    public static int getScreenWidth(Context context) {
        return context.getResources().getDisplayMetrics().widthPixels;
    }

    /**
     * Returns the device's screen height.
     */
    public static int getScreenHeight(Context context) {
        return context.getResources().getDisplayMetrics().heightPixels;
    }


    /**
     * A popup window used by the floating toolbar.
     */
    private static final class FloatingToolbarPopup {

        private final View mParent;
        private final PopupWindow mPopupWindow;
        private final ViewGroup mContentContainer;
        private final Animator.AnimatorListener mOnDismissEnd =
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mPopupWindow.dismiss();
                        mDismissAnimating = false;
                    }
                };
        private final AnimatorSet mGrowFadeInFromBottomAnimation;
        private final AnimatorSet mShrinkFadeOutFromBottomAnimation;

        private boolean mDismissAnimating;

        /**
         * Initializes a new floating bar popup.
         *
         * @param parent  A parent view to get the {@link View#getWindowToken()} token from.
         */
        public FloatingToolbarPopup(View parent) {
            mParent = Preconditions.checkNotNull(parent);
            mContentContainer = createContentContainer(parent.getContext());
            mPopupWindow = createPopupWindow(mContentContainer);
            mGrowFadeInFromBottomAnimation = createGrowFadeInFromBottom(mContentContainer);
            mShrinkFadeOutFromBottomAnimation =
                    createShrinkFadeOutFromBottomAnimation(mContentContainer, mOnDismissEnd);
        }

        /**
         * Shows this popup at the specified coordinates.
         * The specified coordinates may be adjusted to make sure the popup is entirely on-screen.
         * If this popup is already showing, this will be a no-op.
         */
        public void show(int x, int y) {
            if (isShowing()) {
                updateCoordinates(x, y);
                return;
            }

            mPopupWindow.showAtLocation(mParent, Gravity.NO_GRAVITY, 0, 0);
            positionOnScreen(x, y);
            growFadeInFromBottom();

            mDismissAnimating = false;
        }

        /**
         * Gets rid of this popup. If the popup isn't currently showing, this will be a no-op.
         */
        public void dismiss() {
            if (!isShowing()) {
                return;
            }

            if (mDismissAnimating) {
                // This window is already dismissing. Don't restart the animation.
                return;
            }
            mDismissAnimating = true;
            shrinkFadeOutFromBottom();
        }

        /**
         * Returns {@code true} if this popup is currently showing. {@code false} otherwise.
         */
        public boolean isShowing() {
            return mPopupWindow.isShowing() && !mDismissAnimating;
        }

        /**
         * Updates the coordinates of this popup.
         * The specified coordinates may be adjusted to make sure the popup is entirely on-screen.
         */
        public void updateCoordinates(int x, int y) {
            if (isShowing()) {
                positionOnScreen(x, y);
            }
        }

        /**
         * Sets the content of this popup.
         */
        public void setContentView(View view) {
            Preconditions.checkNotNull(view);
            mContentContainer.removeAllViews();
            mContentContainer.addView(view);
        }

        /**
         * Returns the width of this popup.
         */
        public int getWidth() {
            return mContentContainer.getWidth();
        }

        /**
         * Returns the height of this popup.
         */
        public int getHeight() {
            return mContentContainer.getHeight();
        }

        /**
         * Returns the context this popup is running in.
         */
        public Context getContext() {
            return mContentContainer.getContext();
        }

        private void positionOnScreen(int x, int y) {
            if (getWidth() == 0) {
                // content size is yet to be measured.
                mContentContainer.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
            }
            x = clamp(x, 0, getScreenWidth(getContext()) - getWidth());
            y = clamp(y, 0, getScreenHeight(getContext()) - getHeight());

            // Position the view w.r.t. the window.
            mContentContainer.setX(x);
            mContentContainer.setY(y);
        }

        /**
         * Performs the "grow and fade in from the bottom" animation on the floating popup.
         */
        private void growFadeInFromBottom() {
            setPivot();
            mGrowFadeInFromBottomAnimation.start();
        }

        /**
         * Performs the "shrink and fade out from bottom" animation on the floating popup.
         */
        private void shrinkFadeOutFromBottom() {
            setPivot();
            mShrinkFadeOutFromBottomAnimation.start();
        }

        /**
         * Sets the popup content container's pivot.
         */
        private void setPivot() {
            mContentContainer.setPivotX(mContentContainer.getMeasuredWidth() / 2);
            mContentContainer.setPivotY(mContentContainer.getMeasuredHeight());
        }

        private static ViewGroup createContentContainer(Context context) {
            return (ViewGroup) LayoutInflater.from(context)
                    .inflate(R.layout.floating_popup_container, null);
        }

        private static PopupWindow createPopupWindow(View content) {
            ViewGroup popupContentHolder = new LinearLayout(content.getContext());
            PopupWindow popupWindow = new PopupWindow(popupContentHolder);
            popupWindow.setAnimationStyle(0);
            popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            popupWindow.setWidth(getScreenWidth(content.getContext()));
            popupWindow.setHeight(getScreenHeight(content.getContext()));
            content.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            popupContentHolder.addView(content);
            return popupWindow;
        }

        /**
         * Creates a "grow and fade in from the bottom" animation for the specified view.
         *
         * @param view  The view to animate
         */
        private static AnimatorSet createGrowFadeInFromBottom(View view) {
            AnimatorSet growFadeInFromBottomAnimation =  new AnimatorSet();
            growFadeInFromBottomAnimation.playTogether(
                    ObjectAnimator.ofFloat(view, View.SCALE_X, 0.5f, 1).setDuration(125),
                    ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.5f, 1).setDuration(125),
                    ObjectAnimator.ofFloat(view, View.ALPHA, 0, 1).setDuration(75));
            return growFadeInFromBottomAnimation;
        }

        /**
         * Creates a "shrink and fade out from bottom" animation for the specified view.
         *
         * @param view  The view to animate
         * @param listener  The animation listener
         */
        private static AnimatorSet createShrinkFadeOutFromBottomAnimation(
                View view, Animator.AnimatorListener listener) {
            AnimatorSet shrinkFadeOutFromBottomAnimation =  new AnimatorSet();
            shrinkFadeOutFromBottomAnimation.playTogether(
                    ObjectAnimator.ofFloat(view, View.SCALE_Y, 1, 0.5f).setDuration(125),
                    ObjectAnimator.ofFloat(view, View.ALPHA, 1, 0).setDuration(75));
            shrinkFadeOutFromBottomAnimation.setStartDelay(150);
            shrinkFadeOutFromBottomAnimation.addListener(listener);
            return shrinkFadeOutFromBottomAnimation;
        }

        /**
         * Returns value, restricted to the range min->max (inclusive).
         * If maximum is less than minimum, the result is undefined.
         *
         * @param value  The value to clamp.
         * @param minimum  The minimum value in the range.
         * @param maximum  The maximum value in the range. Must not be less than minimum.
         */
        private static int clamp(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(value, maximum));
        }
    }
}
