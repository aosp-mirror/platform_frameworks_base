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

package com.android.internal.app;

import com.android.internal.R;

import android.app.Activity;
import android.app.DialogFragment;
import android.app.MediaRouteActionProvider;
import android.app.MediaRouteButton;
import android.content.Context;
import android.media.MediaRouter;
import android.media.MediaRouter.RouteCategory;
import android.media.MediaRouter.RouteGroup;
import android.media.MediaRouter.RouteInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * This class implements the route chooser dialog for {@link MediaRouter}.
 *
 * @see MediaRouteButton
 * @see MediaRouteActionProvider
 */
public class MediaRouteChooserDialogFragment extends DialogFragment {
    private static final String TAG = "MediaRouteChooserDialogFragment";
    public static final String FRAGMENT_TAG = "android:MediaRouteChooserDialogFragment";

    MediaRouter mRouter;
    private int mRouteTypes;

    private LauncherListener mLauncherListener;
    private View.OnClickListener mExtendedSettingsListener;
    private RouteAdapter mAdapter;
    private ListView mListView;

    public MediaRouteChooserDialogFragment() {
        setStyle(STYLE_NO_TITLE, R.style.Theme_DeviceDefault_Dialog);
    }

    public void setLauncherListener(LauncherListener listener) {
        mLauncherListener = listener;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mRouter = (MediaRouter) activity.getSystemService(Context.MEDIA_ROUTER_SERVICE);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        if (mLauncherListener != null) {
            mLauncherListener.onDetached(this);
        }
        if (mAdapter != null) {
            mRouter.removeCallback(mAdapter);
            mAdapter = null;
        }
        mRouter = null;
    }

    /**
     * Implemented by the MediaRouteButton that launched this dialog
     */
    public interface LauncherListener {
        public void onDetached(MediaRouteChooserDialogFragment detachedFragment);
    }

    public void setExtendedSettingsClickListener(View.OnClickListener listener) {
        mExtendedSettingsListener = listener;
    }

    public void setRouteTypes(int types) {
        mRouteTypes = types;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        final View layout = inflater.inflate(R.layout.media_route_chooser_layout, container, false);
        final View extendedSettingsButton = layout.findViewById(R.id.extended_settings);

        if (mExtendedSettingsListener != null) {
            extendedSettingsButton.setVisibility(View.VISIBLE);
            extendedSettingsButton.setOnClickListener(mExtendedSettingsListener);
        }

        final ListView list = (ListView) layout.findViewById(R.id.list);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setAdapter(mAdapter = new RouteAdapter(inflater));
        list.setItemChecked(mAdapter.getSelectedRoutePosition(), true);
        list.setOnItemClickListener(mAdapter);

        mListView = list;
        mRouter.addCallback(mRouteTypes, mAdapter);

        return layout;
    }

    private static final int[] ITEM_LAYOUTS = new int[] {
        R.layout.media_route_list_item_top_header,
        R.layout.media_route_list_item_section_header,
        R.layout.media_route_list_item
    };

    private class RouteAdapter extends BaseAdapter implements MediaRouter.Callback,
            ListView.OnItemClickListener {
        private static final int VIEW_TOP_HEADER = 0;
        private static final int VIEW_SECTION_HEADER = 1;
        private static final int VIEW_ROUTE = 2;

        private int mSelectedItemPosition;
        private final ArrayList<Object> mItems = new ArrayList<Object>();
        private final LayoutInflater mInflater;

        RouteAdapter(LayoutInflater inflater) {
            mInflater = inflater;
            update();
        }

        void update() {
            // TODO this is kind of naive, but our data sets are going to be
            // fairly small on average.
            mItems.clear();

            final RouteInfo selectedRoute = mRouter.getSelectedRoute(mRouteTypes);

            final ArrayList<RouteInfo> routes = new ArrayList<RouteInfo>();
            final int catCount = mRouter.getCategoryCount();
            for (int i = 0; i < catCount; i++) {
                final RouteCategory cat = mRouter.getCategoryAt(i);
                cat.getRoutes(routes);

                mItems.add(cat);

                final int routeCount = routes.size();
                for (int j = 0; j < routeCount; j++) {
                    final RouteInfo info = routes.get(j);
                    if (info == selectedRoute) {
                        mSelectedItemPosition = mItems.size();
                    }
                    mItems.add(info);
                }
            }

            notifyDataSetChanged();
            if (mListView != null) {
                mListView.setItemChecked(mSelectedItemPosition, true);
            }
        }

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            final Object item = getItem(position);
            if (item instanceof RouteCategory) {
                return position == 0 ? VIEW_TOP_HEADER : VIEW_SECTION_HEADER;
            } else {
                return VIEW_ROUTE;
            }
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return getItemViewType(position) == VIEW_ROUTE;
        }

        @Override
        public Object getItem(int position) {
            return mItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final int viewType = getItemViewType(position);

            ViewHolder holder;
            if (convertView == null) {
                convertView = mInflater.inflate(ITEM_LAYOUTS[viewType], parent, false);
                holder = new ViewHolder();
                holder.text1 = (TextView) convertView.findViewById(R.id.text1);
                holder.text2 = (TextView) convertView.findViewById(R.id.text2);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            if (viewType == VIEW_ROUTE) {
                bindItemView(position, holder);
            } else {
                bindHeaderView(position, holder);
            }

            return convertView;
        }

        void bindItemView(int position, ViewHolder holder) {
            RouteInfo info = (RouteInfo) mItems.get(position);
            holder.text1.setText(info.getName());
            final CharSequence status = info.getStatus();
            if (TextUtils.isEmpty(status)) {
                holder.text2.setVisibility(View.GONE);
            } else {
                holder.text2.setVisibility(View.VISIBLE);
                holder.text2.setText(status);
            }
        }

        void bindHeaderView(int position, ViewHolder holder) {
            RouteCategory cat = (RouteCategory) mItems.get(position);
            holder.text1.setText(cat.getName());
        }

        public int getSelectedRoutePosition() {
            return mSelectedItemPosition;
        }

        @Override
        public void onRouteSelected(MediaRouter router, int type, RouteInfo info) {
            update();
        }

        @Override
        public void onRouteUnselected(MediaRouter router, int type, RouteInfo info) {
            update();
        }

        @Override
        public void onRouteAdded(MediaRouter router, RouteInfo info) {
            update();
        }

        @Override
        public void onRouteRemoved(MediaRouter router, RouteInfo info) {
            update();
        }

        @Override
        public void onRouteChanged(MediaRouter router, RouteInfo info) {
            notifyDataSetChanged();
        }

        @Override
        public void onRouteGrouped(MediaRouter router, RouteInfo info,
                RouteGroup group, int index) {
            update();
        }

        @Override
        public void onRouteUngrouped(MediaRouter router, RouteInfo info, RouteGroup group) {
            update();
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            ListView lv = (ListView) parent;
            final Object item = getItem(lv.getCheckedItemPosition());
            if (!(item instanceof RouteInfo)) {
                // Oops. Stale event running around? Skip it.
                return;
            }
            mRouter.selectRoute(mRouteTypes, (RouteInfo) item);
            dismiss();
        }
    }

    private static class ViewHolder {
        public TextView text1;
        public TextView text2;
    }

    private class GroupAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            // TODO Auto-generated method stub
            return 0;
        }

        @Override
        public Object getItem(int position) {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public long getItemId(int position) {
            // TODO Auto-generated method stub
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            // TODO Auto-generated method stub
            return null;
        }
    }
}
