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

package android.widget;

import com.android.internal.view.menu.MenuBuilder;
import com.android.internal.view.menu.MenuPopupHelper;
import com.android.internal.view.menu.MenuPresenter;
import com.android.internal.view.menu.SubMenuBuilder;

import android.content.Context;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnTouchListener;
import android.widget.ListPopupWindow.ForwardingListener;

/**
 * A PopupMenu displays a {@link Menu} in a modal popup window anchored to a {@link View}.
 * The popup will appear below the anchor view if there is room, or above it if there is not.
 * If the IME is visible the popup will not overlap it until it is touched. Touching outside
 * of the popup will dismiss it.
 */
public class PopupMenu implements MenuBuilder.Callback, MenuPresenter.Callback {
    private Context mContext;
    private MenuBuilder mMenu;
    private View mAnchor;
    private MenuPopupHelper mPopup;
    private OnMenuItemClickListener mMenuItemClickListener;
    private OnDismissListener mDismissListener;
    private OnTouchListener mDragListener;

    /**
     * Callback interface used to notify the application that the menu has closed.
     */
    public interface OnDismissListener {
        /**
         * Called when the associated menu has been dismissed.
         *
         * @param menu The PopupMenu that was dismissed.
         */
        public void onDismiss(PopupMenu menu);
    }

    /**
     * Construct a new PopupMenu.
     *
     * @param context Context for the PopupMenu.
     * @param anchor Anchor view for this popup. The popup will appear below the anchor if there
     *               is room, or above it if there is not.
     */
    public PopupMenu(Context context, View anchor) {
        this(context, anchor, Gravity.NO_GRAVITY);
    }

    /**
     * Construct a new PopupMenu.
     *
     * @param context Context for the PopupMenu.
     * @param anchor Anchor view for this popup. The popup will appear below the anchor if there
     *               is room, or above it if there is not.
     * @param gravity The {@link Gravity} value for aligning the popup with its anchor
     */
    public PopupMenu(Context context, View anchor, int gravity) {
        // TODO Theme?
        mContext = context;
        mMenu = new MenuBuilder(context);
        mMenu.setCallback(this);
        mAnchor = anchor;
        mPopup = new MenuPopupHelper(context, mMenu, anchor);
        mPopup.setGravity(gravity);
        mPopup.setCallback(this);
    }

    /**
     * Returns an {@link OnTouchListener} that can be added to the anchor view
     * to implement drag-to-open behavior.
     * <p>
     * When the listener is set on a view, touching that view and dragging
     * outside of its bounds will open the popup window. Lifting will select the
     * currently touched list item.
     * <p>
     * Example usage:
     * <pre>
     * PopupMenu myPopup = new PopupMenu(context, myAnchor);
     * myAnchor.setOnTouchListener(myPopup.getDragToOpenListener());
     * </pre>
     *
     * @return a touch listener that controls drag-to-open behavior
     */
    public OnTouchListener getDragToOpenListener() {
        if (mDragListener == null) {
            mDragListener = new ForwardingListener(mAnchor) {
                @Override
                protected boolean onForwardingStarted() {
                    show();
                    return true;
                }

                @Override
                protected boolean onForwardingStopped() {
                    dismiss();
                    return true;
                }

                @Override
                public ListPopupWindow getPopup() {
                    // This will be null until show() is called.
                    return mPopup.getPopup();
                }
            };
        }

        return mDragListener;
    }

    /**
     * @return the {@link Menu} associated with this popup. Populate the returned Menu with
     * items before calling {@link #show()}.
     *
     * @see #show()
     * @see #getMenuInflater()
     */
    public Menu getMenu() {
        return mMenu;
    }

    /**
     * @return a {@link MenuInflater} that can be used to inflate menu items from XML into the
     * menu returned by {@link #getMenu()}.
     *
     * @see #getMenu()
     */
    public MenuInflater getMenuInflater() {
        return new MenuInflater(mContext);
    }

    /**
     * Inflate a menu resource into this PopupMenu. This is equivalent to calling
     * popupMenu.getMenuInflater().inflate(menuRes, popupMenu.getMenu()).
     * @param menuRes Menu resource to inflate
     */
    public void inflate(int menuRes) {
        getMenuInflater().inflate(menuRes, mMenu);
    }

    /**
     * Show the menu popup anchored to the view specified during construction.
     * @see #dismiss()
     */
    public void show() {
        mPopup.show();
    }

    /**
     * Dismiss the menu popup.
     * @see #show()
     */
    public void dismiss() {
        mPopup.dismiss();
    }

    /**
     * Set a listener that will be notified when the user selects an item from the menu.
     *
     * @param listener Listener to notify
     */
    public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {
        mMenuItemClickListener = listener;
    }

    /**
     * Set a listener that will be notified when this menu is dismissed.
     *
     * @param listener Listener to notify
     */
    public void setOnDismissListener(OnDismissListener listener) {
        mDismissListener = listener;
    }

    /**
     * @hide
     */
    public boolean onMenuItemSelected(MenuBuilder menu, MenuItem item) {
        if (mMenuItemClickListener != null) {
            return mMenuItemClickListener.onMenuItemClick(item);
        }
        return false;
    }

    /**
     * @hide
     */
    public void onCloseMenu(MenuBuilder menu, boolean allMenusAreClosing) {
        if (mDismissListener != null) {
            mDismissListener.onDismiss(this);
        }
    }

    /**
     * @hide
     */
    public boolean onOpenSubMenu(MenuBuilder subMenu) {
        if (subMenu == null) return false;

        if (!subMenu.hasVisibleItems()) {
            return true;
        }

        // Current menu will be dismissed by the normal helper, submenu will be shown in its place.
        new MenuPopupHelper(mContext, subMenu, mAnchor).show();
        return true;
    }

    /**
     * @hide
     */
    public void onCloseSubMenu(SubMenuBuilder menu) {
    }

    /**
     * @hide
     */
    public void onMenuModeChange(MenuBuilder menu) {
    }

    /**
     * Interface responsible for receiving menu item click events if the items themselves
     * do not have individual item click listeners.
     */
    public interface OnMenuItemClickListener {
        /**
         * This method will be invoked when a menu item is clicked if the item itself did
         * not already handle the event.
         *
         * @param item {@link MenuItem} that was clicked
         * @return <code>true</code> if the event was handled, <code>false</code> otherwise.
         */
        public boolean onMenuItemClick(MenuItem item);
    }
}
