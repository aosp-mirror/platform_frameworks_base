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
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListPopupWindow;
import android.widget.ListView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.classifier.FalsingCollector;

/**
 * Custom user-switcher for use on the bouncer.
 */
public class KeyguardUserSwitcherPopupMenu extends ListPopupWindow {
    private Context mContext;
    private FalsingCollector mFalsingCollector;
    private int mLastHeight = -1;
    private View.OnLayoutChangeListener mLayoutListener = (v, l, t, r, b, ol, ot, or, ob) -> {
        int height = -v.getMeasuredHeight() + getAnchorView().getHeight();
        if (height != mLastHeight) {
            mLastHeight = height;
            setVerticalOffset(height);
            KeyguardUserSwitcherPopupMenu.super.show();
        }
    };

    public KeyguardUserSwitcherPopupMenu(@NonNull Context context,
            @NonNull FalsingCollector falsingCollector) {
        super(context);
        mContext = context;
        mFalsingCollector = falsingCollector;
        Resources res = mContext.getResources();
        setBackgroundDrawable(
                res.getDrawable(R.drawable.keyguard_user_switcher_popup_bg, context.getTheme()));
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

        // This will force the popupwindow to show upward instead of drop down
        listView.addOnLayoutChangeListener(mLayoutListener);

        TextView header = (TextView) LayoutInflater.from(mContext).inflate(
                R.layout.keyguard_bouncer_user_switcher_item, listView, false);
        header.setText(mContext.getResources().getString(
                R.string.accessibility_multi_user_switch_switcher));
        listView.addHeaderView(header);

        listView.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mFalsingCollector.avoidGesture();
            }
            return false;
        });
    }

    @Override
    public void dismiss() {
        getListView().removeOnLayoutChangeListener(mLayoutListener);
        super.dismiss();
    }
}
