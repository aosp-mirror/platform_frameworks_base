/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.keyguard;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.drawable.ShapeDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListPopupWindow;
import android.widget.ListView;

import com.android.systemui.res.R;
import com.android.systemui.plugins.FalsingManager;

/**
 * Custom user-switcher for use on the bouncer.
 */
public class KeyguardUserSwitcherPopupMenu extends ListPopupWindow {
    private Context mContext;
    private FalsingManager mFalsingManager;

    public KeyguardUserSwitcherPopupMenu(@NonNull Context context,
            @NonNull FalsingManager falsingManager) {
        super(context);
        mContext = context;
        mFalsingManager = falsingManager;
        Resources res = mContext.getResources();
        setBackgroundDrawable(
                res.getDrawable(R.drawable.bouncer_user_switcher_popup_bg, context.getTheme()));
        setModal(true);
        setOverlapAnchor(true);
    }

    /**
      * Show the dialog.
      */
    @Override
    public void show() {
        // need to call show() first in order to construct the listView
        super.show();
        ListView listView = getListView();

        listView.setVerticalScrollBarEnabled(false);
        listView.setHorizontalScrollBarEnabled(false);

        // Creates a transparent spacer between items
        ShapeDrawable shape = new ShapeDrawable();
        shape.setAlpha(0);
        listView.setDivider(shape);
        listView.setDividerHeight(mContext.getResources().getDimensionPixelSize(
                R.dimen.bouncer_user_switcher_popup_divider_height));

        if (listView.getTag(R.id.header_footer_views_added_tag_key) == null) {
            int height = mContext.getResources().getDimensionPixelSize(
                    R.dimen.bouncer_user_switcher_popup_header_height);
            listView.addHeaderView(createSpacer(height), null, false);
            listView.addFooterView(createSpacer(height), null, false);
            listView.setTag(R.id.header_footer_views_added_tag_key, new Object());
        }

        listView.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                return mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY);
            }
            return false;
        });
        super.show();
    }

    private View createSpacer(int height) {
        return new View(mContext) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                setMeasuredDimension(1, height);
            }

            @Override
            public void draw(Canvas canvas) {
            }
        };
    }
}
