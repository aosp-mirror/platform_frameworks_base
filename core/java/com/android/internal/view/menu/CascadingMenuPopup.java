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
import android.os.Parcelable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.View.OnKeyListener;
import android.widget.AdapterView;
import android.widget.DropDownListView;
import android.widget.ListView;
import android.widget.MenuPopupWindow;
import android.widget.PopupWindow;
import android.widget.PopupWindow.OnDismissListener;

import com.android.internal.util.Preconditions;

/**
 * A popup for a menu which will allow multiple submenus to appear in a cascading fashion, side by
 * side.
 * @hide
 */
final class CascadingMenuPopup extends MenuPopup implements AdapterView.OnItemClickListener,
        MenuPresenter, OnKeyListener, PopupWindow.OnDismissListener,
        ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener{
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({HORIZ_POSITION_LEFT, HORIZ_POSITION_RIGHT})
    public @interface HorizPosition {}

    private static final int HORIZ_POSITION_LEFT = 0;
    private static final int HORIZ_POSITION_RIGHT = 1;

    private final Context mContext;
    private final int mMenuMaxWidth;
    private final int mPopupStyleAttr;
    private final int mPopupStyleRes;
    private final boolean mOverflowOnly;
    private final int mLayoutDirection;

    private int mDropDownGravity = Gravity.NO_GRAVITY;
    private View mAnchorView;
    private View mShownAnchorView;
    private List<DropDownListView> mListViews;
    private List<MenuPopupWindow> mPopupWindows;
    private List<int[]> mOffsets;
    private int mPreferredPosition;
    private boolean mForceShowIcon;
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
        mPreferredPosition = mLayoutDirection == View.LAYOUT_DIRECTION_RTL ? HORIZ_POSITION_LEFT :
                HORIZ_POSITION_RIGHT;
        mMenuMaxWidth = Math.max(res.getDisplayMetrics().widthPixels / 2,
                res.getDimensionPixelSize(com.android.internal.R.dimen.config_prefDialogWidth));

        mPopupWindows = new ArrayList<MenuPopupWindow>();
        mListViews = new ArrayList<DropDownListView>();
        mOffsets = new ArrayList<int[]>();
    }

    @Override
    public void setForceShowIcon(boolean forceShow) {
        mForceShowIcon = forceShow;
    }

    private MenuPopupWindow createPopupWindow() {
        MenuPopupWindow popupWindow = new MenuPopupWindow(
                mContext, null, mPopupStyleAttr, mPopupStyleRes);
        popupWindow.setOnItemClickListener(this);
        popupWindow.setOnDismissListener(this);
        popupWindow.setAnchorView(mAnchorView);
        popupWindow.setDropDownGravity(mDropDownGravity);
        popupWindow.setModal(true);
        popupWindow.setTouchModal(false);
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
            mListViews.add((DropDownListView) popupWindow.getListView());
        }

        mShownAnchorView = mAnchorView;
        if (mShownAnchorView != null) {
            final boolean addGlobalListener = mTreeObserver == null;
            mTreeObserver = mShownAnchorView.getViewTreeObserver(); // Refresh to latest
            if (addGlobalListener) mTreeObserver.addOnGlobalLayoutListener(this);
            mShownAnchorView.addOnAttachStateChangeListener(this);
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
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        MenuAdapter adapter = (MenuAdapter) parent.getAdapter();
        adapter.mAdapterMenu.performItemAction(adapter.getItem(position), 0);
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

        if (mPreferredPosition == HORIZ_POSITION_RIGHT) {
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
            popupWindow.setEnterTransition(null);

            ListView lastListView = mListViews.get(mListViews.size() - 1);
            @HorizPosition int nextMenuPosition = getNextMenuPosition(menuWidth);
            boolean showOnRight = nextMenuPosition == HORIZ_POSITION_RIGHT;
            mPreferredPosition = nextMenuPosition;

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
            mListViews.add((DropDownListView) popupWindow.getListView());
        }

        int[] offsets = {x, y};
        mOffsets.add(offsets);
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
                MenuAdapter adapter = (MenuAdapter) view.getAdapter();
                adapter.mAdapterMenu.close();
            }
        }
    }

    @Override
    public void updateMenuView(boolean cleared) {
        for (ListView view : mListViews) {
            ((MenuAdapter) view.getAdapter()).notifyDataSetChanged();
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
            if (((MenuAdapter) view.getAdapter()).mAdapterMenu.equals(subMenu)) {
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
            MenuAdapter adapter = (MenuAdapter) view.getAdapter();

            if (menuIndex == -1 && menu == adapter.mAdapterMenu) {
                menuIndex = i;
                wasSelected = view.getSelectedItem() != null;
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

            // If there's still a menu open, refocus the new leaf [sub]menu.
            if (mListViews.size() > 0) {
                mListViews.get(mListViews.size() - 1).requestFocus();
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
                if (!mTreeObserver.isAlive()) mTreeObserver =
                        mShownAnchorView.getViewTreeObserver();
                mTreeObserver.removeGlobalOnLayoutListener(this);
                mTreeObserver = null;
            }
            mShownAnchorView.removeOnAttachStateChangeListener(this);
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

    @Override
    public void onViewAttachedToWindow(View v) {
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        if (mTreeObserver != null) {
            if (!mTreeObserver.isAlive()) mTreeObserver = v.getViewTreeObserver();
            mTreeObserver.removeGlobalOnLayoutListener(this);
        }
        v.removeOnAttachStateChangeListener(this);
    }
}