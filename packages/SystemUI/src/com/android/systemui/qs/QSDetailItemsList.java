/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.qs;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;

import android.widget.TextView;
import com.android.systemui.R;

/**
 * Quick settings common detail list view with line items.
 */
public class QSDetailItemsList extends LinearLayout {
    private static final String TAG = "QSDetailItemsList";

    private ListView mListView;
    private View mEmpty;
    private TextView mEmptyText;
    private ImageView mEmptyIcon;

    public QSDetailItemsList(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mTag = TAG;
    }

    public static QSDetailItemsList convertOrInflate(Context context,
            View convertView, ViewGroup parent) {
        if (convertView instanceof QSDetailItemsList) {
            return (QSDetailItemsList) convertView;
        }
        LayoutInflater inflater = LayoutInflater.from(context);
        return (QSDetailItemsList) inflater.inflate(R.layout.qs_detail_items_list, parent, false);
    }

    public void setAdapter(ListAdapter adapter) {
        mListView.setAdapter(adapter);
    }

    public ListView getListView() {
        return mListView;
    }

    public void setEmptyState(int icon, int text) {
        mEmptyIcon.setImageResource(icon);
        mEmptyText.setText(text);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mListView = (ListView) findViewById(android.R.id.list);
        mListView.setOnTouchListener(new OnTouchListener() {
            // Setting on Touch Listener for handling the touch inside ScrollView
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // Disallow the touch request for parent scroll on touch of child view
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return false;
            }
        });
        mEmpty = findViewById(android.R.id.empty);
        mEmpty.setVisibility(GONE);
        mEmptyText = (TextView) mEmpty.findViewById(android.R.id.title);
        mEmptyIcon = (ImageView) mEmpty.findViewById(android.R.id.icon);
        mListView.setEmptyView(mEmpty);
    }
}
