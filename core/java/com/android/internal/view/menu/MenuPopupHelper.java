/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.view.menu;

import com.android.internal.view.menu.MenuPresenter.Callback;

import android.annotation.AttrRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StyleRes;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.PopupWindow.OnDismissListener;

/**
 * Presents a menu as a small, simple popup anchored to another view.
 */
public class MenuPopupHelper implements MenuHelper {
    private static final int TOUCH_EPICENTER_SIZE_DP = 48;

    private final Context mContext;

    // Immutable cached popup menu properties.
    private final MenuBuilder mMenu;
    private final boolean mOverflowOnly;
    private final int mPopupStyleAttr;
    private final int mPopupStyleRes;

    // Mutable cached popup menu properties.
    private View mAnchorView;
    private int mDropDownGravity = Gravity.START;
    private boolean mForceShowIcon;
    private Callback mPresenterCallback;

    private MenuPopup mPopup;
    private OnDismissListener mOnDismissListener;

    public MenuPopupHelper(@NonNull Context context, @NonNull MenuBuilder menu) {
        this(context, menu, null, false, com.android.internal.R.attr.popupMenuStyle, 0);
    }

    public MenuPopupHelper(@NonNull Context context, @NonNull MenuBuilder menu,
            @NonNull View anchorView) {
        this(context, menu, anchorView, false, com.android.internal.R.attr.popupMenuStyle, 0);
    }

    public MenuPopupHelper(@NonNull Context context, @NonNull MenuBuilder menu,
            @NonNull View anchorView,
            boolean overflowOnly, @AttrRes int popupStyleAttr) {
        this(context, menu, anchorView, overflowOnly, popupStyleAttr, 0);
    }

    public MenuPopupHelper(@NonNull Context context, @NonNull MenuBuilder menu,
            @NonNull View anchorView, boolean overflowOnly, @AttrRes int popupStyleAttr,
            @StyleRes int popupStyleRes) {
        mContext = context;
        mMenu = menu;
        mAnchorView = anchorView;
        mOverflowOnly = overflowOnly;
        mPopupStyleAttr = popupStyleAttr;
        mPopupStyleRes = popupStyleRes;
    }

    public void setOnDismissListener(@Nullable OnDismissListener listener) {
        mOnDismissListener = listener;
    }

    /**
      * Sets the view to which the popup window is anchored.
      * <p>
      * Changes take effect on the next call to show().
      *
      * @param anchor the view to which the popup window should be anchored
      */
    public void setAnchorView(@NonNull View anchor) {
        mAnchorView = anchor;
    }

    /**
     * Sets whether the popup menu's adapter is forced to show icons in the
     * menu item views.
     * <p>
     * Changes take effect on the next call to show().
     *
     * @param forceShowIcon {@code true} to force icons to be shown, or
     *                  {@code false} for icons to be optionally shown
     */
    public void setForceShowIcon(boolean forceShowIcon) {
        mForceShowIcon = forceShowIcon;
        if (mPopup != null) {
            mPopup.setForceShowIcon(forceShowIcon);
        }
    }

    /**
      * Sets the alignment of the popup window relative to the anchor view.
      * <p>
      * Changes take effect on the next call to show().
      *
      * @param gravity alignment of the popup relative to the anchor
      */
    public void setGravity(int gravity) {
        mDropDownGravity = gravity;
    }

    /**
     * @return alignment of the popup relative to the anchor
     */
    public int getGravity() {
        return mDropDownGravity;
    }

    public void show() {
        if (!tryShow()) {
            throw new IllegalStateException("MenuPopupHelper cannot be used without an anchor");
        }
    }

    public void show(int x, int y) {
        if (!tryShow(x, y)) {
            throw new IllegalStateException("MenuPopupHelper cannot be used without an anchor");
        }
    }

    @NonNull
    public MenuPopup getPopup() {
        if (mPopup == null) {
            mPopup = createPopup();
        }
        return mPopup;
    }

    /**
     * Attempts to show the popup anchored to the view specified by {@link #setAnchorView(View)}.
     *
     * @return {@code true} if the popup was shown or was already showing prior to calling this
     *         method, {@code false} otherwise
     */
    public boolean tryShow() {
        if (isShowing()) {
            return true;
        }

        if (mAnchorView == null) {
            return false;
        }

        showPopup(0, 0, false, false);
        return true;
    }

    /**
     * Shows the popup menu and makes a best-effort to anchor it to the
     * specified (x,y) coordinate relative to the anchor view.
     * <p>
     * Additionally, the popup's transition epicenter (see
     * {@link android.widget.PopupWindow#setEpicenterBounds(Rect)} will be
     * centered on the specified coordinate, rather than using the bounds of
     * the anchor view.
     * <p>
     * If the popup's resolved gravity is {@link Gravity#LEFT}, this will
     * display the popup with its top-left corner at (x,y) relative to the
     * anchor view. If the resolved gravity is {@link Gravity#RIGHT}, the
     * popup's top-right corner will be at (x,y).
     * <p>
     * If the popup cannot be displayed fully on-screen, this method will
     * attempt to scroll the anchor view's ancestors and/or offset the popup
     * such that it may be displayed fully on-screen.
     *
     * @param x x coordinate relative to the anchor view
     * @param y y coordinate relative to the anchor view
     * @return {@code true} if the popup was shown or was already showing prior
     *         to calling this method, {@code false} otherwise
     */
    public boolean tryShow(int x, int y) {
        if (isShowing()) {
            return true;
        }

        if (mAnchorView == null) {
            return false;
        }

        showPopup(x, y, true, true);
        return true;
    }

    /**
     * Creates the popup and assigns cached properties.
     *
     * @return an initialized popup
     */
    @NonNull
    private MenuPopup createPopup() {
        final WindowManager windowManager = (WindowManager) mContext.getSystemService(
            Context.WINDOW_SERVICE);
        final Display display = windowManager.getDefaultDisplay();
        final Point displaySize = new Point();
        display.getRealSize(displaySize);

        final int smallestWidth = Math.min(displaySize.x, displaySize.y);
        final int minSmallestWidthCascading = mContext.getResources().getDimensionPixelSize(
            com.android.internal.R.dimen.cascading_menus_min_smallest_width);
        final boolean enableCascadingSubmenus = smallestWidth >= minSmallestWidthCascading;

        final MenuPopup popup;
        if (enableCascadingSubmenus) {
            popup = new CascadingMenuPopup(mContext, mAnchorView, mPopupStyleAttr,
                    mPopupStyleRes, mOverflowOnly);
        } else {
            popup = new StandardMenuPopup(mContext, mMenu, mAnchorView, mPopupStyleAttr,
                    mPopupStyleRes, mOverflowOnly);
        }

        // Assign immutable properties.
        popup.addMenu(mMenu);
        popup.setOnDismissListener(mInternalOnDismissListener);

        // Assign mutable properties. These may be reassigned later.
        popup.setAnchorView(mAnchorView);
        popup.setCallback(mPresenterCallback);
        popup.setForceShowIcon(mForceShowIcon);
        popup.setGravity(mDropDownGravity);

        return popup;
    }

    private void showPopup(int xOffset, int yOffset, boolean useOffsets, boolean showTitle) {
        final MenuPopup popup = getPopup();
        popup.setShowTitle(showTitle);

        if (useOffsets) {
            // If the resolved drop-down gravity is RIGHT, the popup's right
            // edge will be aligned with the anchor view. Adjust by the anchor
            // width such that the top-right corner is at the X offset.
            final int hgrav = Gravity.getAbsoluteGravity(mDropDownGravity,
                    mAnchorView.getLayoutDirection()) & Gravity.HORIZONTAL_GRAVITY_MASK;
            if (hgrav == Gravity.RIGHT) {
                xOffset += mAnchorView.getWidth();
            }

            popup.setHorizontalOffset(xOffset);
            popup.setVerticalOffset(yOffset);

            // Set the transition epicenter to be roughly finger (or mouse
            // cursor) sized and centered around the offset position. This
            // will give the appearance that the window is emerging from
            // the touch point.
            final float density = mContext.getResources().getDisplayMetrics().density;
            final int halfSize = (int) (TOUCH_EPICENTER_SIZE_DP * density / 2);
            final Rect epicenter = new Rect(xOffset - halfSize, yOffset - halfSize,
                    xOffset + halfSize, yOffset + halfSize);
            popup.setEpicenterBounds(epicenter);
        }

        popup.show();
    }

    /**
     * Dismisses the popup, if showing.
     */
    @Override
    public void dismiss() {
        if (isShowing()) {
            mPopup.dismiss();
        }
    }

    /**
     * Called after the popup has been dismissed.
     * <p>
     * <strong>Note:</strong> Subclasses should call the super implementation
     * last to ensure that any necessary tear down has occurred before the
     * listener specified by {@link #setOnDismissListener(OnDismissListener)}
     * is called.
     */
    protected void onDismiss() {
        mPopup = null;

        if (mOnDismissListener != null) {
            mOnDismissListener.onDismiss();
        }
    }

    public boolean isShowing() {
        return mPopup != null && mPopup.isShowing();
    }

    @Override
    public void setPresenterCallback(@Nullable MenuPresenter.Callback cb) {
        mPresenterCallback = cb;
        if (mPopup != null) {
            mPopup.setCallback(cb);
        }
    }

    /**
     * Listener used to proxy dismiss callbacks to the helper's owner.
     */
    private final OnDismissListener mInternalOnDismissListener = new OnDismissListener() {
        @Override
        public void onDismiss() {
            MenuPopupHelper.this.onDismiss();
        }
    };
}
