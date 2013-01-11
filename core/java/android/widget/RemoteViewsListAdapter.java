/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.widget;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;

/**
 * @hide
 */
public class RemoteViewsListAdapter extends BaseAdapter {

    private Context mContext;
    private ArrayList<RemoteViews> mRemoteViewsList;
    private ArrayList<Integer> mViewTypes = new ArrayList<Integer>();
    private int mViewTypeCount;

    public RemoteViewsListAdapter(Context context, ArrayList<RemoteViews> remoteViews,
            int viewTypeCount) {
        mContext = context;
        mRemoteViewsList = remoteViews;
        mViewTypeCount = viewTypeCount;
        init();
    }

    public void setViewsList(ArrayList<RemoteViews> remoteViews) {
        mRemoteViewsList = remoteViews;
        init();
        notifyDataSetChanged();
    }

    private void init() {
        if (mRemoteViewsList == null) return;

        mViewTypes.clear();
        for (RemoteViews rv: mRemoteViewsList) {
            if (!mViewTypes.contains(rv.getLayoutId())) {
                mViewTypes.add(rv.getLayoutId());
            }
        }

        if (mViewTypes.size() > mViewTypeCount || mViewTypeCount < 1) {
            throw new RuntimeException("Invalid view type count -- view type count must be >= 1" +
                    "and must be as large as the total number of distinct view types");
        }
    }

    @Override
    public int getCount() {
        if (mRemoteViewsList != null) {
            return mRemoteViewsList.size();
        } else {
            return 0;
        }
    }

    @Override
    public Object getItem(int position) {
        return null;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (position < getCount()) {
            RemoteViews rv = mRemoteViewsList.get(position);
            rv.setIsWidgetCollectionChild(true);
            View v;
            if (convertView != null && rv != null &&
                    convertView.getId() == rv.getLayoutId()) {
                v = convertView;
                rv.reapply(mContext, v);
            } else {
                v = rv.apply(mContext, parent);
            }
            return v;
        } else {
            return null;
        }
    }

    @Override
    public int getItemViewType(int position) {
        if (position < getCount()) {
            int layoutId = mRemoteViewsList.get(position).getLayoutId();
            return mViewTypes.indexOf(layoutId);
        } else {
            return 0;
        }
    }

    public int getViewTypeCount() {
        return mViewTypeCount;
    }

    @Override
    public boolean hasStableIds() {
        return false;
    }
}
