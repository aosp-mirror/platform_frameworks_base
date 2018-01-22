/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.volume;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.qs.AutoSizingList;

/**
 * Limited height list of devices.
 */
public class OutputChooserLayout extends LinearLayout {
    private static final String TAG = "OutputChooserLayout";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final H mHandler = new H();
    private final Adapter mAdapter = new Adapter();

    private String mTag;
    private Callback mCallback;
    private boolean mItemsVisible = true;
    private AutoSizingList mItemList;
    private View mEmpty;
    private TextView mEmptyText;
    private TextView mTitle;

    private Item[] mItems;

    public OutputChooserLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mTag = TAG;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mItemList = findViewById(android.R.id.list);
        mItemList.setVisibility(GONE);
        mItemList.setAdapter(mAdapter);
        mEmpty = findViewById(android.R.id.empty);
        mEmpty.setVisibility(GONE);
        mEmptyText = mEmpty.findViewById(R.id.empty_text);
        mTitle = findViewById(R.id.title);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        FontSizeUtils.updateFontSize(mEmptyText, R.dimen.qs_detail_empty_text_size);
        int count = mItemList.getChildCount();
        for (int i = 0; i < count; i++) {
            View item = mItemList.getChildAt(i);
            FontSizeUtils.updateFontSize(item, R.id.empty_text,
                    R.dimen.qs_detail_item_primary_text_size);
            FontSizeUtils.updateFontSize(item, android.R.id.summary,
                    R.dimen.qs_detail_item_secondary_text_size);
            FontSizeUtils.updateFontSize(item, android.R.id.title,
                    R.dimen.qs_detail_header_text_size);
        }
    }

    public void setTitle(int title) {
            mTitle.setText(title);
    }

    public void setEmptyState(String text) {
        mEmptyText.setText(text);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (DEBUG) Log.d(mTag, "onAttachedToWindow");
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (DEBUG) Log.d(mTag, "onDetachedFromWindow");
        mCallback = null;
    }

    public void setCallback(Callback callback) {
        mHandler.removeMessages(H.SET_CALLBACK);
        mHandler.obtainMessage(H.SET_CALLBACK, callback).sendToTarget();
    }

    public void setItems(Item[] items) {
        mHandler.removeMessages(H.SET_ITEMS);
        mHandler.obtainMessage(H.SET_ITEMS, items).sendToTarget();
    }

    public void setItemsVisible(boolean visible) {
        mHandler.removeMessages(H.SET_ITEMS_VISIBLE);
        mHandler.obtainMessage(H.SET_ITEMS_VISIBLE, visible ? 1 : 0, 0).sendToTarget();
    }

    private void handleSetCallback(Callback callback) {
        mCallback = callback;
    }

    private void handleSetItems(Item[] items) {
        final int itemCount = items != null ? items.length : 0;
        mEmpty.setVisibility(itemCount == 0 ? VISIBLE : GONE);
        mItemList.setVisibility(itemCount == 0 ? GONE : VISIBLE);
        mItems = items;
        mAdapter.notifyDataSetChanged();
    }

    private void handleSetItemsVisible(boolean visible) {
        if (mItemsVisible == visible) return;
        mItemsVisible = visible;
        for (int i = 0; i < mItemList.getChildCount(); i++) {
            mItemList.getChildAt(i).setVisibility(mItemsVisible ? VISIBLE : INVISIBLE);
        }
    }

    private class Adapter extends BaseAdapter {

        @Override
        public int getCount() {
            return mItems != null ? mItems.length : 0;
        }

        @Override
        public Object getItem(int position) {
            return mItems[position];
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            final Item item = mItems[position];
            if (view == null) {
                view = LayoutInflater.from(mContext).inflate(R.layout.output_chooser_item, parent,
                        false);
            }
            view.setVisibility(mItemsVisible ? VISIBLE : INVISIBLE);
            final ImageView iv = view.findViewById(android.R.id.icon);
            if (item.icon != null) {
                iv.setImageDrawable(item.icon);
            } else {
                iv.setImageResource(item.iconResId);
            }
            final TextView title = view.findViewById(android.R.id.title);
            title.setText(item.line1);
            final TextView summary =  view.findViewById(android.R.id.summary);
            final boolean twoLines = !TextUtils.isEmpty(item.line2);
            title.setMaxLines(twoLines ? 1 : 2);
            summary.setVisibility(twoLines ? VISIBLE : GONE);
            summary.setText(twoLines ? item.line2 : null);
            view.setOnClickListener(v -> {
                if (mCallback != null) {
                    mCallback.onDetailItemClick(item);
                }
            });

            final ImageView icon2 = view.findViewById(android.R.id.icon2);
            if (item.canDisconnect) {
                icon2.setImageResource(R.drawable.ic_qs_cancel);
                icon2.setVisibility(VISIBLE);
                icon2.setClickable(true);
                icon2.setOnClickListener(v -> {
                    if (mCallback != null) {
                        mCallback.onDetailItemDisconnect(item);
                    }
                });
            } else if (item.icon2 != -1) {
                icon2.setVisibility(VISIBLE);
                icon2.setImageResource(item.icon2);
                icon2.setClickable(false);
            } else {
                icon2.setVisibility(GONE);
            }

            return view;
        }
    };

    private class H extends Handler {
        private static final int SET_ITEMS = 1;
        private static final int SET_CALLBACK = 2;
        private static final int SET_ITEMS_VISIBLE = 3;

        public H() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == SET_ITEMS) {
                handleSetItems((Item[]) msg.obj);
            } else if (msg.what == SET_CALLBACK) {
                handleSetCallback((OutputChooserLayout.Callback) msg.obj);
            } else if (msg.what == SET_ITEMS_VISIBLE) {
                handleSetItemsVisible(msg.arg1 != 0);
            }
        }
    }

    public static class Item {
        public static int DEVICE_TYPE_BT = 1;
        public static int DEVICE_TYPE_MEDIA_ROUTER = 2;
        public int iconResId;
        public Drawable icon;
        public Drawable overlay;
        public CharSequence line1;
        public CharSequence line2;
        public Object tag;
        public boolean canDisconnect;
        public int icon2 = -1;
        public int deviceType = 0;
    }

    public interface Callback {
        void onDetailItemClick(Item item);
        void onDetailItemDisconnect(Item item);
    }
}
