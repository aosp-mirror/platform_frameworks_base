package com.android.internal.view.menu;

import android.content.Context;
import android.content.res.Resources;
import android.os.Parcelable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnKeyListener;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.MenuPopupWindow;
import android.widget.PopupWindow;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.PopupWindow.OnDismissListener;

import com.android.internal.util.Preconditions;

/**
 * A standard menu popup in which when a submenu is opened, it replaces its parent menu in the
 * viewport.
 */
final class StandardMenuPopup extends MenuPopup implements OnDismissListener, OnItemClickListener,
        MenuPresenter, OnKeyListener {

    private final Context mContext;
    private final LayoutInflater mInflater;
    private final MenuBuilder mMenu;
    private final MenuAdapter mAdapter;
    private final boolean mOverflowOnly;
    private final int mPopupMaxWidth;
    private final int mPopupStyleAttr;
    private final int mPopupStyleRes;

    private PopupWindow.OnDismissListener mOnDismissListener;

    private View mAnchorView;
    private MenuPopupWindow mPopup;
    private Callback mPresenterCallback;

    private ViewGroup mMeasureParent;

    /** Whether the cached content width value is valid. */
    private boolean mHasContentWidth;

    /** Cached content width. */
    private int mContentWidth;

    private int mDropDownGravity = Gravity.NO_GRAVITY;

    public StandardMenuPopup(Context context, MenuBuilder menu, View anchorView, int popupStyleAttr,
            int popupStyleRes, boolean overflowOnly) {
        mContext = Preconditions.checkNotNull(context);
        mInflater = LayoutInflater.from(context);
        mMenu = menu;
        mOverflowOnly = overflowOnly;
        mAdapter = new MenuAdapter(menu, mInflater, mOverflowOnly);
        mPopupStyleAttr = popupStyleAttr;
        mPopupStyleRes = popupStyleRes;

        final Resources res = context.getResources();
        mPopupMaxWidth = Math.max(res.getDisplayMetrics().widthPixels / 2,
                res.getDimensionPixelSize(com.android.internal.R.dimen.config_prefDialogWidth));

        mAnchorView = anchorView;

        mPopup = new MenuPopupWindow(mContext, null, mPopupStyleAttr, mPopupStyleRes);

        // Present the menu using our context, not the menu builder's context.
        menu.addMenuPresenter(this, context);
    }

    @Override
    public void setForceShowIcon(boolean forceShow) {
        mAdapter.setForceShowIcon(forceShow);
    }

    @Override
    public void setGravity(int gravity) {
        mDropDownGravity = gravity;
    }

    private boolean tryShow() {
        if (isShowing()) {
            return true;
        }

        mPopup.setOnDismissListener(this);
        mPopup.setOnItemClickListener(this);
        mPopup.setAdapter(mAdapter);
        mPopup.setModal(true);

        final View anchor = mAnchorView;
        if (anchor != null) {
            mPopup.setAnchorView(anchor);
            mPopup.setDropDownGravity(mDropDownGravity);
        } else {
            return false;
        }

        if (!mHasContentWidth) {
            mContentWidth = measureIndividualMenuWidth(
                    mAdapter, mMeasureParent, mContext, mPopupMaxWidth);
            mHasContentWidth = true;
        }

        mPopup.setContentWidth(mContentWidth);
        mPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        mPopup.show();
        mPopup.getListView().setOnKeyListener(this);
        return true;
    }

    @Override
    public void show() {
        if (!tryShow()) {
            throw new IllegalStateException("MenuPopupHelper cannot be used without an anchor");
        }
    }

    @Override
    public void dismiss() {
        if (isShowing()) {
            mPopup.dismiss();
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        MenuAdapter adapter = mAdapter;
        adapter.mAdapterMenu.performItemAction(adapter.getItem(position), 0);
    }

    @Override
    public void addMenu(MenuBuilder menu) {
        // No-op: standard implementation has only one menu which is set in the constructor.
    }

    @Override
    public boolean isShowing() {
        return mPopup != null && mPopup.isShowing();
    }

    @Override
    public void onDismiss() {
        mPopup = null;
        mMenu.close();

        mOnDismissListener.onDismiss();
    }

    @Override
    public void updateMenuView(boolean cleared) {
        mHasContentWidth = false;

        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void setCallback(Callback cb) {
        mPresenterCallback = cb;
    }

    @Override
    public boolean onSubMenuSelected(SubMenuBuilder subMenu) {
        if (subMenu.hasVisibleItems()) {
            MenuPopupHelper subPopup = new MenuPopupHelper(
                    mContext, subMenu, mAnchorView, mOverflowOnly, mPopupStyleAttr, mPopupStyleRes);
            subPopup.setCallback(mPresenterCallback);

            boolean preserveIconSpacing = false;
            final int count = subMenu.size();
            for (int i = 0; i < count; i++) {
                MenuItem childItem = subMenu.getItem(i);
                if (childItem.isVisible() && childItem.getIcon() != null) {
                    preserveIconSpacing = true;
                    break;
                }
            }
            subPopup.setForceShowIcon(preserveIconSpacing);

            if (subPopup.tryShow()) {
                if (mPresenterCallback != null) {
                    mPresenterCallback.onOpenSubMenu(subMenu);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void onCloseMenu(MenuBuilder menu, boolean allMenusAreClosing) {
        // Only care about the (sub)menu we're presenting.
        if (menu != mMenu) return;

        dismiss();
        if (mPresenterCallback != null) {
            mPresenterCallback.onCloseMenu(menu, allMenusAreClosing);
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
    public void setAnchorView(View anchor) {
        mAnchorView = anchor;
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_MENU) {
            dismiss();
            return true;
        }
        return false;
    }

    @Override
    public void setOnDismissListener(OnDismissListener listener) {
        mOnDismissListener = listener;
    }

    @Override
    public ListView getListView() {
        return mPopup.getListView();
    }
}