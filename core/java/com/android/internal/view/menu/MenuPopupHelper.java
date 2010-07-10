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
import android.view.View;
import android.view.View.MeasureSpec;
import android.widget.AdapterView;
import android.widget.ListPopupWindow;

/**
 * @hide
 */
public class MenuPopupHelper implements AdapterView.OnItemClickListener {
    private static final String TAG = "MenuPopupHelper";

    private Context mContext;
    private ListPopupWindow mPopup;
    private SubMenuBuilder mSubMenu;
    private int mPopupMaxWidth;

    public MenuPopupHelper(Context context, SubMenuBuilder subMenu) {
        mContext = context;
        mSubMenu = subMenu;

        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        mPopupMaxWidth = metrics.widthPixels / 2;
    }

    public void show() {
        // TODO Use a style from the theme here
        mPopup = new ListPopupWindow(mContext, null, 0,
                com.android.internal.R.style.Widget_Spinner);
        mPopup.setOnItemClickListener(this);

        final MenuAdapter adapter = mSubMenu.getMenuAdapter(MenuBuilder.TYPE_POPUP);
        mPopup.setAdapter(adapter);
        mPopup.setModal(true);

        final MenuItemImpl itemImpl = (MenuItemImpl) mSubMenu.getItem();
        final View anchorView = itemImpl.getItemView(MenuBuilder.TYPE_ACTION_BUTTON, null);
        mPopup.setAnchorView(anchorView);

        mPopup.setContentWidth(Math.min(measureContentWidth(adapter), mPopupMaxWidth));
        mPopup.show();
    }

    public void dismiss() {
        mPopup.dismiss();
        mPopup = null;
    }

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        mSubMenu.performItemAction(mSubMenu.getItem(position), 0);
        mPopup.dismiss();
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
