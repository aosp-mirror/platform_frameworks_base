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

import com.android.internal.view.menu.MenuBuilder.MenuAdapter;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.View.MeasureSpec;
import android.widget.AdapterView;
import android.widget.ListPopupWindow;

import java.lang.ref.WeakReference;

/**
 * @hide
 */
public class MenuPopupHelper implements AdapterView.OnItemClickListener, View.OnKeyListener {
    private static final String TAG = "MenuPopupHelper";

    private Context mContext;
    private ListPopupWindow mPopup;
    private MenuBuilder mMenu;
    private int mPopupMaxWidth;
    private WeakReference<View> mAnchorView;
    private boolean mOverflowOnly;

    public MenuPopupHelper(Context context, MenuBuilder menu) {
        this(context, menu, null, false);
    }

    public MenuPopupHelper(Context context, MenuBuilder menu, View anchorView) {
        this(context, menu, anchorView, false);
    }

    public MenuPopupHelper(Context context, MenuBuilder menu,
            View anchorView, boolean overflowOnly) {
        mContext = context;
        mMenu = menu;
        mOverflowOnly = overflowOnly;

        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        mPopupMaxWidth = metrics.widthPixels / 2;

        if (anchorView != null) {
            mAnchorView = new WeakReference<View>(anchorView);
        }
    }

    public void show() {
        // TODO Use a style from the theme here
        mPopup = new ListPopupWindow(mContext, null, 0,
                com.android.internal.R.style.Widget_Spinner);
        mPopup.setOnItemClickListener(this);

        final MenuAdapter adapter = mOverflowOnly ?
                mMenu.getOverflowMenuAdapter(MenuBuilder.TYPE_POPUP) :
                mMenu.getMenuAdapter(MenuBuilder.TYPE_POPUP);
        mPopup.setAdapter(adapter);
        mPopup.setModal(true);

        if (mAnchorView != null) {
            mPopup.setAnchorView(mAnchorView.get());
        } else if (mMenu instanceof SubMenuBuilder) {
            SubMenuBuilder subMenu = (SubMenuBuilder) mMenu;
            final MenuItemImpl itemImpl = (MenuItemImpl) subMenu.getItem();
            mPopup.setAnchorView(itemImpl.getItemView(MenuBuilder.TYPE_ACTION_BUTTON, null));
        } else {
            throw new IllegalStateException("MenuPopupHelper cannot be used without an anchor");
        }

        mPopup.setContentWidth(Math.min(measureContentWidth(adapter), mPopupMaxWidth));
        mPopup.show();
        mPopup.getListView().setOnKeyListener(this);
    }

    public void dismiss() {
        mPopup.dismiss();
        mPopup = null;
    }

    public boolean isShowing() {
        return mPopup != null && mPopup.isShowing();
    }

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        MenuItem item = null;
        if (mOverflowOnly) {
            item = mMenu.getOverflowItem(position);
        } else {
            item = mMenu.getItem(position);
        }
        mMenu.performItemAction(item, 0);
        mPopup.dismiss();
    }

    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_MENU) {
            dismiss();
            return true;
        }
        return false;
    }

    private int measureContentWidth(MenuAdapter adapter) {
        // Menus don't tend to be long, so this is more sane than it looks.
        int width = 0;
        View itemView = null;
        final int widthMeasureSpec =
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        final int heightMeasureSpec =
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        final int count = adapter.getCount();
        for (int i = 0; i < count; i++) {
            itemView = adapter.getView(i, itemView, null);
            itemView.measure(widthMeasureSpec, heightMeasureSpec);
            width = Math.max(width, itemView.getMeasuredWidth());
        }
        return width;
    }
}
