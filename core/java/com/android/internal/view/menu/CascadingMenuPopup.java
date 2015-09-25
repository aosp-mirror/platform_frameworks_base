package com.android.internal.view.menu;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

import android.annotation.IntDef;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Parcelable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.View.OnAttachStateChangeListener;
import android.view.View.OnKeyListener;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.widget.DropDownListView;
import android.widget.FrameLayout;
import android.widget.MenuItemHoverListener;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.MenuPopupWindow;
import android.widget.MenuPopupWindow.MenuDropDownListView;
import android.widget.PopupWindow;
import android.widget.PopupWindow.OnDismissListener;
import android.widget.TextView;

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

    private static final int SUBMENU_TIMEOUT_MS = 200;

    private final Context mContext;
    private final int mMenuMaxWidth;
    private final int mPopupStyleAttr;
    private final int mPopupStyleRes;
    private final boolean mOverflowOnly;
    private final int mLayoutDirection;
    private final Handler mSubMenuHoverHandler;

    private final OnGlobalLayoutListener mGlobalLayoutListener = new OnGlobalLayoutListener() {
        @Override
        public void onGlobalLayout() {
            if (isShowing()) {
                final View anchor = mShownAnchorView;
                if (anchor == null || !anchor.isShown()) {
                    dismiss();
                } else if (isShowing()) {
                    // Recompute window sizes and positions.
                    for (MenuPopupWindow popup : mPopupWindows) {
                        popup.show();
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
        public void onItemHovered(MenuBuilder menu, int position) {
            int menuIndex = -1;
            for (int i = 0; i < mListViews.size(); i++) {
                final MenuDropDownListView view = (MenuDropDownListView) mListViews.get(i);
                final MenuAdapter adapter = toMenuAdapter(view.getAdapter());

                if (adapter.getAdapterMenu() == menu) {
                    menuIndex = i;
                    break;
                }
            }

            if (menuIndex == -1) {
                return;
            }

            final MenuDropDownListView view = (MenuDropDownListView) mListViews.get(menuIndex);
            final ListMenuItemView selectedItemView = (ListMenuItemView) view.getSelectedView();

            if (selectedItemView != null && selectedItemView.isEnabled()
                    && selectedItemView.getItemData().hasSubMenu()) {
                // If the currently selected item corresponds to a submenu, schedule to open the
                // submenu on a timeout.

                mSubMenuHoverHandler.removeCallbacksAndMessages(null);
                mSubMenuHoverHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // Make sure the submenu item is still the one selected.
                        if (view.getSelectedView() == selectedItemView
                                && selectedItemView.isEnabled()
                                && selectedItemView.getItemData().hasSubMenu()) {
                            // Close any other submenus that might be open at the current or
                            // a deeper level.
                            int nextIndex = mListViews.indexOf(view) + 1;
                            if (nextIndex < mListViews.size()) {
                                MenuAdapter nextSubMenuAdapter =
                                        toMenuAdapter(mListViews.get(nextIndex).getAdapter());
                                // Disable exit animation, to prevent overlapping fading out
                                // submenus.
                                mPopupWindows.get(nextIndex).setExitTransition(null);
                                nextSubMenuAdapter.getAdapterMenu().close();
                            }

                            // Then open the selected submenu.
                            view.performItemClick(
                                    selectedItemView,
                                    view.getSelectedItemPosition(),
                                    view.getSelectedItemId());
                        }
                    }
                }, SUBMENU_TIMEOUT_MS);
            } else if (menuIndex + 1 < mListViews.size()) {
                // If the currently selected item does NOT corresponds to a submenu, check if there
                // is a submenu already open that is one level deeper. If so, schedule to close it
                // on a timeout.

                final MenuDropDownListView nextView =
                        (MenuDropDownListView) mListViews.get(menuIndex + 1);
                final MenuAdapter nextAdapter = toMenuAdapter(nextView.getAdapter());

                mSubMenuHoverHandler.removeCallbacksAndMessages(null);
                mSubMenuHoverHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // Make sure the menu wasn't already closed by something else and that
                        // it wasn't re-hovered by the user since this was scheduled.
                        int nextMenuIndex = mListViews.indexOf(nextView);

                        if (nextMenuIndex != -1 && nextView.getSelectedView() == null) {
                            // Disable exit animation, to prevent overlapping fading out submenus.
                            for (int i = nextMenuIndex; i < mPopupWindows.size(); i++) {
                                final MenuPopupWindow popupWindow = mPopupWindows.get(i);
                                popupWindow.setExitTransition(null);
                                popupWindow.setAnimationStyle(0);
                            }
                            nextAdapter.getAdapterMenu().close();
                        }
                    }
                }, SUBMENU_TIMEOUT_MS);
            }
        }
    };

    private int mDropDownGravity = Gravity.NO_GRAVITY;
    private View mAnchorView;
    private View mShownAnchorView;
    private List<DropDownListView> mListViews;
    private List<MenuPopupWindow> mPopupWindows;
    private int mLastPosition;
    private List<Integer> mPositions;
    private List<int[]> mOffsets;
    private int mInitXOffset;
    private int mInitYOffset;
    private boolean mForceShowIcon;
    private boolean mShowTitle;
    private Callback mPresenterCallback;
    private ViewTreeObserver mTreeObserver;
    private PopupWindow.OnDismissListener mOnDismissListener;

    /**
     * Initializes a new cascading-capable menu popup.
     *
     * @param parent A parent view to get the {@link android.view.View#getWindowToken()} token from.
     */
    public CascadingMenuPopup(Context context, View anchor, int popupStyleAttr,
            int popupStyleRes, boolean overflowOnly) {
        mContext = Preconditions.checkNotNull(context);
        mAnchorView = Preconditions.checkNotNull(anchor);
        mPopupStyleAttr = popupStyleAttr;
        mPopupStyleRes = popupStyleRes;
        mOverflowOnly = overflowOnly;

        mForceShowIcon = false;

        final Resources res = context.getResources();
        final Configuration config = res.getConfiguration();
        mLayoutDirection = config.getLayoutDirection();
        mLastPosition = getInitialMenuPosition();
        mMenuMaxWidth = Math.max(res.getDisplayMetrics().widthPixels / 2,
                res.getDimensionPixelSize(com.android.internal.R.dimen.config_prefDialogWidth));

        mPopupWindows = new ArrayList<MenuPopupWindow>();
        mListViews = new ArrayList<DropDownListView>();
        mOffsets = new ArrayList<int[]>();
        mPositions = new ArrayList<Integer>();
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
        return popupWindow;
    }

    @Override
    public void show() {
        if (isShowing()) {
            return;
        }

        // Show any menus that have been added via #addMenu(MenuBuilder) but which have not yet been
        // shown.
        // In a typical use case, #addMenu(MenuBuilder) would be called once, followed by a call to
        // this #show() method -- which would actually show the popup on the screen.
        for (int i = 0; i < mPopupWindows.size(); i++) {
            MenuPopupWindow popupWindow = mPopupWindows.get(i);
            popupWindow.show();
            DropDownListView listView = (DropDownListView) popupWindow.getListView();
            mListViews.add(listView);

            MenuBuilder menu = toMenuAdapter(listView.getAdapter()).getAdapterMenu();
            if (i == 0 && mShowTitle && menu.getHeaderTitle() != null) {
                FrameLayout titleItemView =
                        (FrameLayout) LayoutInflater.from(mContext).inflate(
                                com.android.internal.R.layout.popup_menu_header_item_layout,
                                listView,
                                false);
                TextView titleView = (TextView) titleItemView.findViewById(
                        com.android.internal.R.id.title);
                titleView.setText(menu.getHeaderTitle());
                titleItemView.setEnabled(false);
                listView.addHeaderView(titleItemView, null, false);

                // Update to show the title.
                popupWindow.show();
            }
        }

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
        // Need to make another list to avoid a concurrent modification exception, as #onDismiss
        // may clear mPopupWindows while we are iterating.
        List<MenuPopupWindow> popupWindows = new ArrayList<MenuPopupWindow>(mPopupWindows);
        for (MenuPopupWindow popupWindow : popupWindows) {
            if (popupWindow != null && popupWindow.isShowing()) {
                popupWindow.dismiss();
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
        return mLayoutDirection == View.LAYOUT_DIRECTION_RTL ? HORIZ_POSITION_LEFT :
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
        ListView lastListView = mListViews.get(mListViews.size() - 1);

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
        boolean addSubMenu = mListViews.size() > 0;

        menu.addMenuPresenter(this, mContext);

        MenuPopupWindow popupWindow = createPopupWindow();

        MenuAdapter adapter = new MenuAdapter(menu, LayoutInflater.from(mContext), mOverflowOnly);
        adapter.setForceShowIcon(mForceShowIcon);

        popupWindow.setAdapter(adapter);

        int menuWidth = measureIndividualMenuWidth(adapter, null, mContext, mMenuMaxWidth);

        int x = 0;
        int y = 0;

        if (addSubMenu) {
            popupWindow.setTouchModal(false);
            popupWindow.setEnterTransition(null);

            ListView lastListView = mListViews.get(mListViews.size() - 1);
            @HorizPosition int nextMenuPosition = getNextMenuPosition(menuWidth);
            boolean showOnRight = nextMenuPosition == HORIZ_POSITION_RIGHT;
            mLastPosition = nextMenuPosition;

            int[] lastLocation = new int[2];
            lastListView.getLocationOnScreen(lastLocation);

            int[] lastOffset = mOffsets.get(mOffsets.size() - 1);

            // Note: By now, mDropDownGravity is the absolute gravity, so this should work in both
            // LTR and RTL.
            if ((mDropDownGravity & Gravity.RIGHT) == Gravity.RIGHT) {
                if (showOnRight) {
                    x = lastOffset[0] + menuWidth;
                } else {
                    x = lastOffset[0] - lastListView.getWidth();
                }
            } else {
                if (showOnRight) {
                    x = lastOffset[0] + lastListView.getWidth();
                } else {
                    x = lastOffset[0] - menuWidth;
                }
            }

            y = lastOffset[1] + lastListView.getSelectedView().getTop() -
                    lastListView.getChildAt(0).getTop();
        } else {
            x = mInitXOffset;
            y = mInitYOffset;
        }

        popupWindow.setWidth(menuWidth);
        popupWindow.setHorizontalOffset(x);
        popupWindow.setVerticalOffset(y);
        mPopupWindows.add(popupWindow);

        // NOTE: This case handles showing submenus once the CascadingMenuPopup has already
        // been shown via a call to its #show() method. If it hasn't yet been show()n, then
        // we deliberately do not yet show the popupWindow, as #show() will do that later.
        if (isShowing()) {
            popupWindow.show();
            DropDownListView listView = (DropDownListView) popupWindow.getListView();
            mListViews.add(listView);
        }

        int[] offsets = {x, y};
        mOffsets.add(offsets);
        mPositions.add(mLastPosition);
    }

    /**
     * @return {@code true} if the popup is currently showing, {@code false} otherwise.
     */
    @Override
    public boolean isShowing() {
        return mPopupWindows.size() > 0 && mPopupWindows.get(0).isShowing();
    }

    /**
     * Called when one or more of the popup windows was dismissed.
     */
    @Override
    public void onDismiss() {
        int dismissedIndex = -1;
        for (int i = 0; i < mPopupWindows.size(); i++) {
            if (!mPopupWindows.get(i).isShowing()) {
                dismissedIndex = i;
                break;
            }
        }

        if (dismissedIndex != -1) {
            for (int i = dismissedIndex; i < mListViews.size(); i++) {
                ListView view = mListViews.get(i);
                ListAdapter adapter = view.getAdapter();
                MenuAdapter menuAdapter = toMenuAdapter(adapter);
                menuAdapter.mAdapterMenu.close();
            }
        }
    }

    @Override
    public void updateMenuView(boolean cleared) {
        for (ListView view : mListViews) {
            toMenuAdapter(view.getAdapter()).notifyDataSetChanged();
        }
    }

    @Override
    public void setCallback(Callback cb) {
        mPresenterCallback = cb;
    }

    @Override
    public boolean onSubMenuSelected(SubMenuBuilder subMenu) {
        // Don't allow double-opening of the same submenu.
        for (ListView view : mListViews) {
            if (toMenuAdapter(view.getAdapter()).mAdapterMenu.equals(subMenu)) {
                // Just re-focus that one.
                view.requestFocus();
                return true;
            }
        }

        if (subMenu.hasVisibleItems()) {
            this.addMenu(subMenu);
            if (mPresenterCallback != null) {
                mPresenterCallback.onOpenSubMenu(subMenu);
            }
            return true;
        }
        return false;
    }

    @Override
    public void onCloseMenu(MenuBuilder menu, boolean allMenusAreClosing) {
        int menuIndex = -1;
        boolean wasSelected = false;

        for (int i = 0; i < mListViews.size(); i++) {
            ListView view = mListViews.get(i);
            MenuAdapter adapter = toMenuAdapter(view.getAdapter());

            if (menuIndex == -1 && menu == adapter.mAdapterMenu) {
                menuIndex = i;
                wasSelected = view.getSelectedView() != null;
            }

            // Once the menu has been found, remove it and all submenus beneath it from the
            // container view. Also remove the presenter.
            if (menuIndex != -1) {
                adapter.mAdapterMenu.removeMenuPresenter(this);
            }
        }

        // Then, actually remove the views for these [sub]menu(s) from our list of views.
        if (menuIndex != -1) {
            for (int i = menuIndex; i < mPopupWindows.size(); i++) {
                mPopupWindows.get(i).dismiss();
            }
            mPopupWindows.subList(menuIndex, mPopupWindows.size()).clear();
            mListViews.subList(menuIndex, mListViews.size()).clear();
            mOffsets.subList(menuIndex, mOffsets.size()).clear();

            mPositions.subList(menuIndex, mPositions.size()).clear();
            if (mPositions.size() > 0) {
                mLastPosition = mPositions.get(mPositions.size() - 1);
            } else {
                mLastPosition = getInitialMenuPosition();
            }
        }

        if (mListViews.size() == 0 || wasSelected) {
            dismiss();
            if (mPresenterCallback != null) {
                mPresenterCallback.onCloseMenu(menu, allMenusAreClosing);
            }
        }

        if (mPopupWindows.size() == 0) {
            if (mTreeObserver != null) {
                if (mTreeObserver.isAlive()) {
                    mTreeObserver.removeGlobalOnLayoutListener(mGlobalLayoutListener);
                }
                mTreeObserver = null;
            }
            mShownAnchorView.removeOnAttachStateChangeListener(mAttachStateChangeListener);
            // If every [sub]menu was dismissed, that means the whole thing was dismissed, so notify
            // the owner.
            mOnDismissListener.onDismiss();
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
        mDropDownGravity = Gravity.getAbsoluteGravity(dropDownGravity, mLayoutDirection);
    }

    @Override
    public void setAnchorView(View anchor) {
        mAnchorView = anchor;
    }

    @Override
    public void setOnDismissListener(OnDismissListener listener) {
        mOnDismissListener = listener;
    }

    @Override
    public ListView getListView() {
        return mListViews.size() > 0 ? mListViews.get(mListViews.size() - 1) : null;
    }

    @Override
    public void setHorizontalOffset(int x) {
        mInitXOffset = x;
    }

    @Override
    public void setVerticalOffset(int y) {
        mInitYOffset = y;
    }

    @Override
    public void setShowTitle(boolean showTitle) {
        mShowTitle = showTitle;
    }
}