/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.internal.widget;

import android.annotation.IdRes;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.HeaderViewListAdapter;

import java.util.ArrayList;

import com.android.internal.util.Predicate;

public class WatchHeaderListView extends ListView {
    private View mTopPanel;

    public WatchHeaderListView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public WatchHeaderListView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public WatchHeaderListView(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected HeaderViewListAdapter wrapHeaderListAdapterInternal(
            ArrayList<ListView.FixedViewInfo> headerViewInfos,
            ArrayList<ListView.FixedViewInfo> footerViewInfos,
            ListAdapter adapter) {
        return new WatchHeaderListAdapter(headerViewInfos, footerViewInfos, adapter);
    }

    @Override
    public void addView(View child, ViewGroup.LayoutParams params) {
        if (mTopPanel == null) {
            setTopPanel(child);
        } else {
            throw new IllegalStateException("WatchHeaderListView can host only one header");
        }
    }

    public void setTopPanel(View v) {
        mTopPanel = v;
        wrapAdapterIfNecessary();
    }

    @Override
    public void setAdapter(ListAdapter adapter) {
        super.setAdapter(adapter);
        wrapAdapterIfNecessary();
    }

    @Override
    protected View findViewTraversal(@IdRes int id) {
        View v = super.findViewTraversal(id);
        if (v == null && mTopPanel != null && !mTopPanel.isRootNamespace()) {
            return mTopPanel.findViewById(id);
        }
        return v;
    }

    @Override
    protected View findViewWithTagTraversal(Object tag) {
        View v = super.findViewWithTagTraversal(tag);
        if (v == null && mTopPanel != null && !mTopPanel.isRootNamespace()) {
            return mTopPanel.findViewWithTag(tag);
        }
        return v;
    }

    @Override
    protected View findViewByPredicateTraversal(Predicate<View> predicate, View childToSkip) {
        View v = super.findViewByPredicateTraversal(predicate, childToSkip);
        if (v == null && mTopPanel != null && mTopPanel != childToSkip
                && !mTopPanel.isRootNamespace()) {
            return mTopPanel.findViewByPredicate(predicate);
        }
        return v;
    }

    @Override
    public int getHeaderViewsCount() {
        return mTopPanel == null ? super.getHeaderViewsCount() : super.getHeaderViewsCount() + 1;
    }

    private void wrapAdapterIfNecessary() {
        ListAdapter adapter = getAdapter();
        if (adapter != null && mTopPanel != null) {
            if (!(adapter instanceof WatchHeaderListAdapter)) {
                wrapHeaderListAdapterInternal();
            }

            ((WatchHeaderListAdapter) getAdapter()).setTopPanel(mTopPanel);
            dispatchDataSetObserverOnChangedInternal();
        }
    }

    private static class WatchHeaderListAdapter extends HeaderViewListAdapter {
        private View mTopPanel;

        public WatchHeaderListAdapter(
                ArrayList<ListView.FixedViewInfo> headerViewInfos,
                ArrayList<ListView.FixedViewInfo> footerViewInfos,
                ListAdapter adapter) {
            super(headerViewInfos, footerViewInfos, adapter);
        }

        public void setTopPanel(View v) {
            mTopPanel = v;
        }

        private int getTopPanelCount() {
            return mTopPanel == null ? 0 : 1;
        }

        @Override
        public int getCount() {
            return super.getCount() + getTopPanelCount();
        }

        @Override
        public boolean areAllItemsEnabled() {
            return mTopPanel == null && super.areAllItemsEnabled();
        }

        @Override
        public boolean isEnabled(int position) {
            if (mTopPanel != null) {
                if (position == 0) {
                    return false;
                } else {
                    return super.isEnabled(position - 1);
                }
            }

            return super.isEnabled(position);
        }

        @Override
        public Object getItem(int position) {
            if (mTopPanel != null) {
                if (position == 0) {
                    return null;
                } else {
                    return super.getItem(position - 1);
                }
            }

            return super.getItem(position);
        }

        @Override
        public long getItemId(int position) {
            int numHeaders = getHeadersCount() + getTopPanelCount();
            if (getWrappedAdapter() != null && position >= numHeaders) {
                int adjPosition = position - numHeaders;
                int adapterCount = getWrappedAdapter().getCount();
                if (adjPosition < adapterCount) {
                    return getWrappedAdapter().getItemId(adjPosition);
                }
            }
            return -1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (mTopPanel != null) {
                if (position == 0) {
                    return mTopPanel;
                } else {
                    return super.getView(position - 1, convertView, parent);
                }
            }

            return super.getView(position, convertView, parent);
        }

        @Override
        public int getItemViewType(int position) {
            int numHeaders = getHeadersCount() + getTopPanelCount();
            if (getWrappedAdapter() != null && position >= numHeaders) {
                int adjPosition = position - numHeaders;
                int adapterCount = getWrappedAdapter().getCount();
                if (adjPosition < adapterCount) {
                    return getWrappedAdapter().getItemViewType(adjPosition);
                }
            }

            return AdapterView.ITEM_VIEW_TYPE_HEADER_OR_FOOTER;
        }
    }
}
