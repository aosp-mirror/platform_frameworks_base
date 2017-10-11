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

import android.annotation.MenuRes;
import android.annotation.TestApi;
import android.content.Context;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnTouchListener;

import com.android.internal.R;
import com.android.internal.view.menu.MenuBuilder;
import com.android.internal.view.menu.MenuPopupHelper;
import com.android.internal.view.menu.ShowableListMenu;

/**
 * A PopupMenu displays a {@link Menu} in a modal popup window anchored to a
 * {@link View}. The popup will appear below the anchor view if there is room,
 * or above it if there is not. If the IME is visible the popup will not
 * overlap it until it is touched. Touching outside of the popup will dismiss
 * it.
 */
public class PopupMenu {
    private final Context mContext;
    private final MenuBuilder mMenu;
    private final View mAnchor;
    private final MenuPopupHelper mPopup;

    private OnMenuItemClickListener mMenuItemClickListener;
    private OnDismissListener mOnDismissListener;
    private OnTouchListener mDragListener;

    /**
     * Constructor to create a new popup menu with an anchor view.
     *
     * @param context Context the popup menu is running in, through which it
     *        can access the current theme, resources, etc.
     * @param anchor Anchor view for this popup. The popup will appear below
     *        the anchor if there is room, or above it if there is not.
     */
    public PopupMenu(Context context, View anchor) {
        this(context, anchor, Gravity.NO_GRAVITY);
    }

    /**
     * Constructor to create a new popup menu with an anchor view and alignment
     * gravity.
     *
     * @param context Context the popup menu is running in, through which it
     *        can access the current theme, resources, etc.
     * @param anchor Anchor view for this popup. The popup will appear below
     *        the anchor if there is room, or above it if there is not.
     * @param gravity The {@link Gravity} value for aligning the popup with its
     *        anchor.
     */
    public PopupMenu(Context context, View anchor, int gravity) {
        this(context, anchor, gravity, R.attr.popupMenuStyle, 0);
    }

    /**
     * Constructor a create a new popup menu with a specific style.
     *
     * @param context Context the popup menu is running in, through which it
     *        can access the current theme, resources, etc.
     * @param anchor Anchor view for this popup. The popup will appear below
     *        the anchor if there is room, or above it if there is not.
     * @param gravity The {@link Gravity} value for aligning the popup with its
     *        anchor.
     * @param popupStyleAttr An attribute in the current theme that contains a
     *        reference to a style resource that supplies default values for
     *        the popup window. Can be 0 to not look for defaults.
     * @param popupStyleRes A resource identifier of a style resource that
     *        supplies default values for the popup window, used only if
     *        popupStyleAttr is 0 or can not be found in the theme. Can be 0
     *        to not look for defaults.
     */
    public PopupMenu(Context context, View anchor, int gravity, int popupStyleAttr,
            int popupStyleRes) {
        mContext = context;
        mAnchor = anchor;

        mMenu = new MenuBuilder(context);
        mMenu.setCallback(new MenuBuilder.Callback() {
            @Override
            public boolean onMenuItemSelected(MenuBuilder menu, MenuItem item) {
                if (mMenuItemClickListener != null) {
                    return mMenuItemClickListener.onMenuItemClick(item);
                }
                return false;
            }

            @Override
            public void onMenuModeChange(MenuBuilder menu) {
            }
        });

        mPopup = new MenuPopupHelper(context, mMenu, anchor, false, popupStyleAttr, popupStyleRes);
        mPopup.setGravity(gravity);
        mPopup.setOnDismissListener(new PopupWindow.OnDismissListener() {
            @Override
            public void onDismiss() {
                if (mOnDismissListener != null) {
                    mOnDismissListener.onDismiss(PopupMenu.this);
                }
            }
        });
    }

    /**
     * Sets the gravity used to align the popup window to its anchor view.
     * <p>
     * If the popup is showing, calling this method will take effect only
     * the next time the popup is shown.
     *
     * @param gravity the gravity used to align the popup window
     * @see #getGravity()
     */
    public void setGravity(int gravity) {
        mPopup.setGravity(gravity);
    }

    /**
     * @return the gravity used to align the popup window to its anchor view
     * @see #setGravity(int)
     */
    public int getGravity() {
        return mPopup.getGravity();
    }

    /**
     * Returns an {@link OnTouchListener} that can be added to the anchor view
     * to implement drag-to-open behavior.
     * <p>
     * When the listener is set on a view, touching that view and dragging
     * outside of its bounds will open the popup window. Lifting will select
     * the currently touched list item.
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
                public ShowableListMenu getPopup() {
                    // This will be null until show() is called.
                    return mPopup.getPopup();
                }
            };
        }

        return mDragListener;
    }

    /**
     * Returns the {@link Menu} associated with this popup. Populate the
     * returned Menu with items before calling {@link #show()}.
     *
     * @return the {@link Menu} associated with this popup
     * @see #show()
     * @see #getMenuInflater()
     */
    public Menu getMenu() {
        return mMenu;
    }

    /**
     * @return a {@link MenuInflater} that can be used to inflate menu items
     *         from XML into the menu returned by {@link #getMenu()}
     * @see #getMenu()
     */
    public MenuInflater getMenuInflater() {
        return new MenuInflater(mContext);
    }

    /**
     * Inflate a menu resource into this PopupMenu. This is equivalent to
     * calling {@code popupMenu.getMenuInflater().inflate(menuRes, popupMenu.getMenu())}.
     *
     * @param menuRes Menu resource to inflate
     */
    public void inflate(@MenuRes int menuRes) {
        getMenuInflater().inflate(menuRes, mMenu);
    }

    /**
     * Show the menu popup anchored to the view specified during construction.
     *
     * @see #dismiss()
     */
    public void show() {
        mPopup.show();
    }

    /**
     * Dismiss the menu popup.
     *
     * @see #show()
     */
    public void dismiss() {
        mPopup.dismiss();
    }

    /**
     * Sets a listener that will be notified when the user selects an item from
     * the menu.
     *
     * @param listener the listener to notify
     */
    public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {
        mMenuItemClickListener = listener;
    }

    /**
     * Sets a listener that will be notified when this menu is dismissed.
     *
     * @param listener the listener to notify
     */
    public void setOnDismissListener(OnDismissListener listener) {
        mOnDismissListener = listener;
    }

    /**
     * Interface responsible for receiving menu item click events if the items
     * themselves do not have individual item click listeners.
     */
    public interface OnMenuItemClickListener {
        /**
         * This method will be invoked when a menu item is clicked if the item
         * itself did not already handle the event.
         *
         * @param item the menu item that was clicked
         * @return {@code true} if the event was handled, {@code false}
         *         otherwise
         */
        boolean onMenuItemClick(MenuItem item);
    }

    /**
     * Callback interface used to notify the application that the menu has closed.
     */
    public interface OnDismissListener {
        /**
         * Called when the associated menu has been dismissed.
         *
         * @param menu the popup menu that was dismissed
         */
        void onDismiss(PopupMenu menu);
    }

    /**
     * Returns the {@link ListView} representing the list of menu items in the currently showing
     * menu.
     *
     * @return The view representing the list of menu items.
     * @hide
     */
    @TestApi
    public ListView getMenuListView() {
        if (!mPopup.isShowing()) {
            return null;
        }
        return mPopup.getPopup().getListView();
    }
}
