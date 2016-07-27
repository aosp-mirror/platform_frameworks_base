package com.android.internal.view.menu;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import android.annotation.AttrRes;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StyleRes;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Parcelable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.View.OnAttachStateChangeListener;
import android.view.View.OnKeyListener;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.HeaderViewListAdapter;
import android.widget.ListAdapter;
import android.widget.MenuItemHoverListener;
import android.widget.ListView;
import android.widget.MenuPopupWindow;
import android.widget.PopupWindow;
import android.widget.PopupWindow.OnDismissListener;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.util.Preconditions;

/**
 * A popup for a menu which will allow multiple submenus to appear in a cascading fashion, side by
 * side.
 * @hide
 */
final class CascadingMenuPopup extends MenuPopup implements MenuPresenter, OnKeyListener,
        PopupWindow.OnDismissListener {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({HORIZ_POSITION_LEFT, HORIZ_POSITION_RIGHT})
    public @interface HorizPosition {}

    private static final int HORIZ_POSITION_LEFT = 0;
    private static final int HORIZ_POSITION_RIGHT = 1;

    /**
     * Delay between hovering over a menu item with a mouse and receiving
     * side-effects (ex. opening a sub-menu or closing unrelated menus).
     */
    private static final int SUBMENU_TIMEOUT_MS = 200;

    private final Context mContext;
    private final int mMenuMaxWidth;
    private final int mPopupStyleAttr;
    private final int mPopupStyleRes;
    private final boolean mOverflowOnly;
    private final Handler mSubMenuHoverHandler;

    /** List of menus that were added before this popup was shown. */
    private final List<MenuBuilder> mPendingMenus = new LinkedList<>();

    /**
     * List of open menus. The first item is the root menu and each
     * subsequent item is a direct submenu of the previous item.
     */
    private final List<CascadingMenuInfo> mShowingMenus = new ArrayList<>();

    private final OnGlobalLayoutListener mGlobalLayoutListener = new OnGlobalLayoutListener() {
        @Override
        public void onGlobalLayout() {
            // Only move the popup if it's showing and non-modal. We don't want
            // to be moving around the only interactive window, since there's a
            // good chance the user is interacting with it.
            if (isShowing() && mShowingMenus.size() > 0
                    && !mShowingMenus.get(0).window.isModal()) {
                final View anchor = mShownAnchorView;
                if (anchor == null || !anchor.isShown()) {
                    dismiss();
                } else {
                    // Recompute window sizes and positions.
                    for (CascadingMenuInfo info : mShowingMenus) {
                        info.window.show();
                    }
                }
            }
        }
    };

    private final OnAttachStateChangeListener mAttachStateChangeListener =
            new OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    if (mTreeObserver != null) {
                        if (!mTreeObserver.isAlive()) {
                            mTreeObserver = v.getViewTreeObserver();
                        }
                        mTreeObserver.removeGlobalOnLayoutListener(mGlobalLayoutListener);
                    }
                    v.removeOnAttachStateChangeListener(this);
                }
            };

    private final MenuItemHoverListener mMenuItemHoverListener = new MenuItemHoverListener() {
        @Override
        public void onItemHoverExit(@NonNull MenuBuilder menu, @NonNull MenuItem item) {
            // If the mouse moves between two windows, hover enter/exit pairs
            // may be received out of order. So, instead of canceling all
            // pending runnables, only cancel runnables for the host menu.
            mSubMenuHoverHandler.removeCallbacksAndMessages(menu);
        }

        @Override
        public void onItemHoverEnter(
                @NonNull final MenuBuilder menu, @NonNull final MenuItem item) {
            // Something new was hovered, cancel all scheduled runnables.
            mSubMenuHoverHandler.removeCallbacksAndMessages(null);

            // Find the position of the hovered menu within the added menus.
            int menuIndex = -1;
            for (int i = 0, count = mShowingMenus.size(); i < count; i++) {
                if (menu == mShowingMenus.get(i).menu) {
                    menuIndex = i;
                    break;
                }
            }

            if (menuIndex == -1) {
                return;
            }

            final CascadingMenuInfo nextInfo;
            final int nextIndex = menuIndex + 1;
            if (nextIndex < mShowingMenus.size()) {
                nextInfo = mShowingMenus.get(nextIndex);
            } else {
                nextInfo = null;
            }

            final Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    // Close any other submenus that might be open at the
                    // current or a deeper level.
                    if (nextInfo != null) {
                        // Disable exit animations to prevent overlapping
                        // fading out submenus.
                        mShouldCloseImmediately = true;
                        nextInfo.menu.close(false /* closeAllMenus */);
                        mShouldCloseImmediately = false;
                    }

                    // Then open the selected submenu, if there is one.
                    if (item.isEnabled() && item.hasSubMenu()) {
                        menu.performItemAction(item, 0);
                    }
                }
            };
            final long uptimeMillis = SystemClock.uptimeMillis() + SUBMENU_TIMEOUT_MS;
            mSubMenuHoverHandler.postAtTime(runnable, menu, uptimeMillis);
        }
    };

    private int mRawDropDownGravity = Gravity.NO_GRAVITY;
    private int mDropDownGravity = Gravity.NO_GRAVITY;
    private View mAnchorView;
    private View mShownAnchorView;
    private int mLastPosition;
    private boolean mHasXOffset;
    private boolean mHasYOffset;
    private int mXOffset;
    private int mYOffset;
    private boolean mForceShowIcon;
    private boolean mShowTitle;
    private Callback mPresenterCallback;
    private ViewTreeObserver mTreeObserver;
    private PopupWindow.OnDismissListener mOnDismissListener;

    /** Whether popup menus should disable exit animations when closing. */
    private boolean mShouldCloseImmediately;

    /**
     * Initializes a new cascading-capable menu popup.
     *
     * @param anchor A parent view to get the {@link android.view.View#getWindowToken()} token from.
     */
    public CascadingMenuPopup(@NonNull Context context, @NonNull View anchor,
            @AttrRes int popupStyleAttr, @StyleRes int popupStyleRes, boolean overflowOnly) {
        mContext = Preconditions.checkNotNull(context);
        mAnchorView = Preconditions.checkNotNull(anchor);
        mPopupStyleAttr = popupStyleAttr;
        mPopupStyleRes = popupStyleRes;
        mOverflowOnly = overflowOnly;

        mForceShowIcon = false;
        mLastPosition = getInitialMenuPosition();

        final Resources res = context.getResources();
        mMenuMaxWidth = Math.max(res.getDisplayMetrics().widthPixels / 2,
                res.getDimensionPixelSize(com.android.internal.R.dimen.config_prefDialogWidth));

        mSubMenuHoverHandler = new Handler();
    }

    @Override
    public void setForceShowIcon(boolean forceShow) {
        mForceShowIcon = forceShow;
    }

    private MenuPopupWindow createPopupWindow() {
        MenuPopupWindow popupWindow = new MenuPopupWindow(
                mContext, null, mPopupStyleAttr, mPopupStyleRes);
        popupWindow.setHoverListener(mMenuItemHoverListener);
        popupWindow.setOnItemClickListener(this);
        popupWindow.setOnDismissListener(this);
        popupWindow.setAnchorView(mAnchorView);
        popupWindow.setDropDownGravity(mDropDownGravity);
        popupWindow.setModal(true);
        popupWindow.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        return popupWindow;
    }

    @Override
    public void show() {
        if (isShowing()) {
            return;
        }

        // Display all pending menus.
        for (MenuBuilder menu : mPendingMenus) {
            showMenu(menu);
        }
        mPendingMenus.clear();

        mShownAnchorView = mAnchorView;

        if (mShownAnchorView != null) {
            final boolean addGlobalListener = mTreeObserver == null;
            mTreeObserver = mShownAnchorView.getViewTreeObserver(); // Refresh to latest
            if (addGlobalListener) {
                mTreeObserver.addOnGlobalLayoutListener(mGlobalLayoutListener);
            }
            mShownAnchorView.addOnAttachStateChangeListener(mAttachStateChangeListener);
        }
    }

    @Override
    public void dismiss() {
        // Need to make another list to avoid a concurrent modification
        // exception, as #onDismiss may clear mPopupWindows while we are
        // iterating. Remove from the last added menu so that the callbacks
        // are received in order from foreground to background.
        final int length = mShowingMenus.size();
        if (length > 0) {
            final CascadingMenuInfo[] addedMenus =
                    mShowingMenus.toArray(new CascadingMenuInfo[length]);
            for (int i = length - 1; i >= 0; i--) {
                final CascadingMenuInfo info = addedMenus[i];
                if (info.window.isShowing()) {
                    info.window.dismiss();
                }
            }
        }
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_MENU) {
            dismiss();
            return true;
        }
        return false;
    }

    /**
     * Determines the proper initial menu position for the current LTR/RTL configuration.
     * @return The initial position.
     */
    @HorizPosition
    private int getInitialMenuPosition() {
        final int layoutDirection = mAnchorView.getLayoutDirection();
        return layoutDirection == View.LAYOUT_DIRECTION_RTL ? HORIZ_POSITION_LEFT :
                HORIZ_POSITION_RIGHT;
    }

    /**
     * Determines whether the next submenu (of the given width) should display on the right or on
     * the left of the most recent menu.
     *
     * @param nextMenuWidth Width of the next submenu to display.
     * @return The position to display it.
     */
    @HorizPosition
    private int getNextMenuPosition(int nextMenuWidth) {
        ListView lastListView = mShowingMenus.get(mShowingMenus.size() - 1).getListView();

        final int[] screenLocation = new int[2];
        lastListView.getLocationOnScreen(screenLocation);

        final Rect displayFrame = new Rect();
        mShownAnchorView.getWindowVisibleDisplayFrame(displayFrame);

        if (mLastPosition == HORIZ_POSITION_RIGHT) {
            final int right = screenLocation[0] + lastListView.getWidth() + nextMenuWidth;
            if (right > displayFrame.right) {
                return HORIZ_POSITION_LEFT;
            }
            return HORIZ_POSITION_RIGHT;
        } else { // LEFT
            final int left = screenLocation[0] - nextMenuWidth;
            if (left < 0) {
                return HORIZ_POSITION_RIGHT;
            }
            return HORIZ_POSITION_LEFT;
        }
    }

    @Override
    public void addMenu(MenuBuilder menu) {
        menu.addMenuPresenter(this, mContext);

        if (isShowing()) {
            showMenu(menu);
        } else {
            mPendingMenus.add(menu);
        }
    }

    /**
     * Prepares and shows the specified menu immediately.
     *
     * @param menu the menu to show
     */
    private void showMenu(@NonNull MenuBuilder menu) {
        final LayoutInflater inflater = LayoutInflater.from(mContext);
        final MenuAdapter adapter = new MenuAdapter(menu, inflater, mOverflowOnly);

        // Apply "force show icon" setting. There are 3 cases:
        // (1) This is the top level menu and icon spacing is forced. Add spacing.
        // (2) This is a submenu. Add spacing if any of the visible menu items has an icon.
        // (3) This is the top level menu and icon spacing isn't forced. Do not add spacing.
        if (!isShowing() && mForceShowIcon) {
          // Case 1
          adapter.setForceShowIcon(true);
        } else if (isShowing()) {
          // Case 2
          adapter.setForceShowIcon(MenuPopup.shouldPreserveIconSpacing(menu));
        }
        // Case 3: Else, don't allow spacing for icons (default behavior; do nothing).

        final int menuWidth = measureIndividualMenuWidth(adapter, null, mContext, mMenuMaxWidth);
        final MenuPopupWindow popupWindow = createPopupWindow();
        popupWindow.setAdapter(adapter);
        popupWindow.setContentWidth(menuWidth);
        popupWindow.setDropDownGravity(mDropDownGravity);

        final CascadingMenuInfo parentInfo;
        final View parentView;
        if (mShowingMenus.size() > 0) {
            parentInfo = mShowingMenus.get(mShowingMenus.size() - 1);
            parentView = findParentViewForSubmenu(parentInfo, menu);
        } else {
            parentInfo = null;
            parentView = null;
        }

        if (parentView != null) {
            // This menu is a cascading submenu anchored to a parent view.
            popupWindow.setTouchModal(false);
            popupWindow.setEnterTransition(null);

            final @HorizPosition int nextMenuPosition = getNextMenuPosition(menuWidth);
            final boolean showOnRight = nextMenuPosition == HORIZ_POSITION_RIGHT;
            mLastPosition = nextMenuPosition;

            final int[] tempLocation = new int[2];

            // This popup menu will be positioned relative to the top-left edge
            // of the view representing its parent menu.
            parentView.getLocationInWindow(tempLocation);
            final int parentOffsetLeft = parentInfo.window.getHorizontalOffset() + tempLocation[0];
            final int parentOffsetTop = parentInfo.window.getVerticalOffset() + tempLocation[1];

            // By now, mDropDownGravity is the resolved absolute gravity, so
            // this should work in both LTR and RTL.
            final int x;
            if ((mDropDownGravity & Gravity.RIGHT) == Gravity.RIGHT) {
                if (showOnRight) {
                    x = parentOffsetLeft + menuWidth;
                } else {
                    x = parentOffsetLeft - parentView.getWidth();
                }
            } else {
                if (showOnRight) {
                    x = parentOffsetLeft + parentView.getWidth();
                } else {
                    x = parentOffsetLeft - menuWidth;
                }
            }

            popupWindow.setHorizontalOffset(x);

            final int y = parentOffsetTop;
            popupWindow.setVerticalOffset(y);
        } else {
            if (mHasXOffset) {
                popupWindow.setHorizontalOffset(mXOffset);
            }
            if (mHasYOffset) {
                popupWindow.setVerticalOffset(mYOffset);
            }
            final Rect epicenterBounds = getEpicenterBounds();
            popupWindow.setEpicenterBounds(epicenterBounds);
        }


        final CascadingMenuInfo menuInfo = new CascadingMenuInfo(popupWindow, menu, mLastPosition);
        mShowingMenus.add(menuInfo);

        popupWindow.show();

        // If this is the root menu, show the title if requested.
        if (parentInfo == null && mShowTitle && menu.getHeaderTitle() != null) {
            final ListView listView = popupWindow.getListView();
            final FrameLayout titleItemView = (FrameLayout) inflater.inflate(
                    R.layout.popup_menu_header_item_layout, listView, false);
            final TextView titleView = (TextView) titleItemView.findViewById(R.id.title);
            titleItemView.setEnabled(false);
            titleView.setText(menu.getHeaderTitle());
            listView.addHeaderView(titleItemView, null, false);

            // Show again to update the title.
            popupWindow.show();
        }
    }

    /**
     * Returns the menu item within the specified parent menu that owns
     * specified submenu.
     *
     * @param parent the parent menu
     * @param submenu the submenu for which the index should be returned
     * @return the menu item that owns the submenu, or {@code null} if not
     *         present
     */
    private MenuItem findMenuItemForSubmenu(
            @NonNull MenuBuilder parent, @NonNull MenuBuilder submenu) {
        for (int i = 0, count = parent.size(); i < count; i++) {
            final MenuItem item = parent.getItem(i);
            if (item.hasSubMenu() && submenu == item.getSubMenu()) {
                return item;
            }
        }

        return null;
    }

    /**
     * Attempts to find the view for the menu item that owns the specified
     * submenu.
     *
     * @param parentInfo info for the parent menu
     * @param submenu the submenu whose parent view should be obtained
     * @return the parent view, or {@code null} if one could not be found
     */
    @Nullable
    private View findParentViewForSubmenu(
            @NonNull CascadingMenuInfo parentInfo, @NonNull MenuBuilder submenu) {
        final MenuItem owner = findMenuItemForSubmenu(parentInfo.menu, submenu);
        if (owner == null) {
            // Couldn't find the submenu owner.
            return null;
        }

        // The adapter may be wrapped. Adjust the index if necessary.
        final int headersCount;
        final MenuAdapter menuAdapter;
        final ListView listView = parentInfo.getListView();
        final ListAdapter listAdapter = listView.getAdapter();
        if (listAdapter instanceof HeaderViewListAdapter) {
            final HeaderViewListAdapter headerAdapter = (HeaderViewListAdapter) listAdapter;
            headersCount = headerAdapter.getHeadersCount();
            menuAdapter = (MenuAdapter) headerAdapter.getWrappedAdapter();
        } else {
            headersCount = 0;
            menuAdapter = (MenuAdapter) listAdapter;
        }

        // Find the index within the menu adapter's data set of the menu item.
        int ownerPosition = AbsListView.INVALID_POSITION;
        for (int i = 0, count = menuAdapter.getCount(); i < count; i++) {
            if (owner == menuAdapter.getItem(i)) {
                ownerPosition = i;
                break;
            }
        }
        if (ownerPosition == AbsListView.INVALID_POSITION) {
            // Couldn't find the owner within the menu adapter.
            return null;
        }

        // Adjust the index for the adapter used to display views.
        ownerPosition += headersCount;

        // Adjust the index for the visible views.
        final int ownerViewPosition = ownerPosition - listView.getFirstVisiblePosition();
        if (ownerViewPosition < 0 || ownerViewPosition >= listView.getChildCount()) {
            // Not visible on screen.
            return null;
        }

        return listView.getChildAt(ownerViewPosition);
    }

    /**
     * @return {@code true} if the popup is currently showing, {@code false} otherwise.
     */
    @Override
    public boolean isShowing() {
        return mShowingMenus.size() > 0 && mShowingMenus.get(0).window.isShowing();
    }

    /**
     * Called when one or more of the popup windows was dismissed.
     */
    @Override
    public void onDismiss() {
        // The dismiss listener doesn't pass the calling window, so walk
        // through the stack to figure out which one was just dismissed.
        CascadingMenuInfo dismissedInfo = null;
        for (int i = 0, count = mShowingMenus.size(); i < count; i++) {
            final CascadingMenuInfo info = mShowingMenus.get(i);
            if (!info.window.isShowing()) {
                dismissedInfo = info;
                break;
            }
        }

        // Close all menus starting from the dismissed menu, passing false
        // since we are manually closing only a subset of windows.
        if (dismissedInfo != null) {
            dismissedInfo.menu.close(false);
        }
    }

    @Override
    public void updateMenuView(boolean cleared) {
        for (CascadingMenuInfo info : mShowingMenus) {
            toMenuAdapter(info.getListView().getAdapter()).notifyDataSetChanged();
        }
    }

    @Override
    public void setCallback(Callback cb) {
        mPresenterCallback = cb;
    }

    @Override
    public boolean onSubMenuSelected(SubMenuBuilder subMenu) {
        // Don't allow double-opening of the same submenu.
        for (CascadingMenuInfo info : mShowingMenus) {
            if (subMenu == info.menu) {
                // Just re-focus that one.
                info.getListView().requestFocus();
                return true;
            }
        }

        if (subMenu.hasVisibleItems()) {
            addMenu(subMenu);

            if (mPresenterCallback != null) {
                mPresenterCallback.onOpenSubMenu(subMenu);
            }
            return true;
        }
        return false;
    }

    /**
     * Finds the index of the specified menu within the list of added menus.
     *
     * @param menu the menu to find
     * @return the index of the menu, or {@code -1} if not present
     */
    private int findIndexOfAddedMenu(@NonNull MenuBuilder menu) {
        for (int i = 0, count = mShowingMenus.size(); i < count; i++) {
            final CascadingMenuInfo info  = mShowingMenus.get(i);
            if (menu == info.menu) {
                return i;
            }
        }

        return -1;
    }

    @Override
    public void onCloseMenu(MenuBuilder menu, boolean allMenusAreClosing) {
        final int menuIndex = findIndexOfAddedMenu(menu);
        if (menuIndex < 0) {
            return;
        }

        // Recursively close descendant menus.
        final int nextMenuIndex = menuIndex + 1;
        if (nextMenuIndex < mShowingMenus.size()) {
            final CascadingMenuInfo childInfo = mShowingMenus.get(nextMenuIndex);
            childInfo.menu.close(false /* closeAllMenus */);
        }

        // Close the target menu.
        final CascadingMenuInfo info = mShowingMenus.remove(menuIndex);
        info.menu.removeMenuPresenter(this);
        if (mShouldCloseImmediately) {
            // Disable all exit animations.
            info.window.setExitTransition(null);
            info.window.setAnimationStyle(0);
        }
        info.window.dismiss();

        final int count = mShowingMenus.size();
        if (count > 0) {
            mLastPosition = mShowingMenus.get(count - 1).position;
        } else {
            mLastPosition = getInitialMenuPosition();
        }

        if (count == 0) {
            // This was the last window. Clean up.
            dismiss();

            if (mPresenterCallback != null) {
                mPresenterCallback.onCloseMenu(menu, true);
            }

            if (mTreeObserver != null) {
                if (mTreeObserver.isAlive()) {
                    mTreeObserver.removeGlobalOnLayoutListener(mGlobalLayoutListener);
                }
                mTreeObserver = null;
            }
            mShownAnchorView.removeOnAttachStateChangeListener(mAttachStateChangeListener);

            // If every [sub]menu was dismissed, that means the whole thing was
            // dismissed, so notify the owner.
            mOnDismissListener.onDismiss();
        } else if (allMenusAreClosing) {
            // Close all menus starting from the root. This will recursively
            // close any remaining menus, so we don't need to propagate the
            // "closeAllMenus" flag. The last window will clean up.
            final CascadingMenuInfo rootInfo = mShowingMenus.get(0);
            rootInfo.menu.close(false /* closeAllMenus */);
        }
    }

    @Override
    public boolean flagActionItems() {
        return false;
    }

    @Override
    public Parcelable onSaveInstanceState() {
        return null;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
    }

    @Override
    public void setGravity(int dropDownGravity) {
        if (mRawDropDownGravity != dropDownGravity) {
            mRawDropDownGravity = dropDownGravity;
            mDropDownGravity = Gravity.getAbsoluteGravity(
                    dropDownGravity, mAnchorView.getLayoutDirection());
        }
    }

    @Override
    public void setAnchorView(@NonNull View anchor) {
        if (mAnchorView != anchor) {
            mAnchorView = anchor;

            // Gravity resolution may have changed, update from raw gravity.
            mDropDownGravity = Gravity.getAbsoluteGravity(
                    mRawDropDownGravity, mAnchorView.getLayoutDirection());
        }
    }

    @Override
    public void setOnDismissListener(OnDismissListener listener) {
        mOnDismissListener = listener;
    }

    @Override
    public ListView getListView() {
        return mShowingMenus.isEmpty() ? null : mShowingMenus.get(mShowingMenus.size() - 1).getListView();
    }

    @Override
    public void setHorizontalOffset(int x) {
        mHasXOffset = true;
        mXOffset = x;
    }

    @Override
    public void setVerticalOffset(int y) {
        mHasYOffset = true;
        mYOffset = y;
    }

    @Override
    public void setShowTitle(boolean showTitle) {
        mShowTitle = showTitle;
    }

    private static class CascadingMenuInfo {
        public final MenuPopupWindow window;
        public final MenuBuilder menu;
        public final int position;

        public CascadingMenuInfo(@NonNull MenuPopupWindow window, @NonNull MenuBuilder menu,
                int position) {
            this.window = window;
            this.menu = menu;
            this.position = position;
        }

        public ListView getListView() {
            return window.getListView();
        }
    }
}
