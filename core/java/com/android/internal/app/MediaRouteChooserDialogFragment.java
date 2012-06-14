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
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.MediaRouteActionProvider;
import android.app.MediaRouteButton;
import android.content.Context;
import android.graphics.drawable.Drawable;
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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * This class implements the route chooser dialog for {@link MediaRouter}.
 *
 * @see MediaRouteButton
 * @see MediaRouteActionProvider
 */
public class MediaRouteChooserDialogFragment extends DialogFragment {
    private static final String TAG = "MediaRouteChooserDialogFragment";
    public static final String FRAGMENT_TAG = "android:MediaRouteChooserDialogFragment";

    private static final int[] ITEM_LAYOUTS = new int[] {
        R.layout.media_route_list_item_top_header,
        R.layout.media_route_list_item_section_header,
        R.layout.media_route_list_item
    };

    private static final int[] GROUP_ITEM_LAYOUTS = new int[] {
        R.layout.media_route_list_item_top_header,
        R.layout.media_route_list_item_checkable,
        R.layout.media_route_list_item_collapse_group
    };

    MediaRouter mRouter;
    private int mRouteTypes;

    private LayoutInflater mInflater;
    private LauncherListener mLauncherListener;
    private View.OnClickListener mExtendedSettingsListener;
    private RouteAdapter mAdapter;
    private GroupAdapter mGroupAdapter;
    private ListView mListView;

    static final RouteComparator sComparator = new RouteComparator();

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
        if (mGroupAdapter != null) {
            mRouter.removeCallback(mGroupAdapter);
            mGroupAdapter = null;
        }
        if (mAdapter != null) {
            mRouter.removeCallback(mAdapter);
            mAdapter = null;
        }
        mInflater = null;
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
        mInflater = inflater;
        final View layout = inflater.inflate(R.layout.media_route_chooser_layout, container, false);
        final View extendedSettingsButton = layout.findViewById(R.id.extended_settings);

        if (mExtendedSettingsListener != null) {
            extendedSettingsButton.setVisibility(View.VISIBLE);
            extendedSettingsButton.setOnClickListener(mExtendedSettingsListener);
        }

        final ListView list = (ListView) layout.findViewById(R.id.list);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setItemsCanFocus(true);
        list.setAdapter(mAdapter = new RouteAdapter());
        list.setItemChecked(mAdapter.getSelectedRoutePosition(), true);
        list.setOnItemClickListener(mAdapter);

        mListView = list;
        mRouter.addCallback(mRouteTypes, mAdapter);

        return layout;
    }

    void onExpandGroup(RouteGroup info) {
        mGroupAdapter = new GroupAdapter(info);
        mRouter.addCallback(mRouteTypes, mGroupAdapter);
        mListView.setAdapter(mGroupAdapter);
        mListView.setOnItemClickListener(mGroupAdapter);
        mListView.setItemsCanFocus(false);
        mListView.clearChoices();
        mListView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        mGroupAdapter.initCheckedItems();

        getDialog().setCanceledOnTouchOutside(false);
    }

    void onDoneGrouping() {
        mListView.setAdapter(mAdapter);
        mListView.setOnItemClickListener(mAdapter);
        mListView.setItemsCanFocus(true);
        mListView.clearChoices();
        mListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        mListView.setItemChecked(mAdapter.getSelectedRoutePosition(), true);

        mRouter.removeCallback(mGroupAdapter);
        mGroupAdapter = null;

        getDialog().setCanceledOnTouchOutside(true);
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        return new RouteChooserDialog(getActivity(), getTheme());
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mListView != null) {
            if (mGroupAdapter != null) {
                mGroupAdapter.initCheckedItems();
            } else {
                mListView.setItemChecked(mAdapter.getSelectedRoutePosition(), true);
            }
        }
    }

    private static class ViewHolder {
        public TextView text1;
        public TextView text2;
        public ImageView icon;
        public ImageButton expandGroupButton;
        public RouteAdapter.ExpandGroupListener expandGroupListener;
        public int position;
    }

    private class RouteAdapter extends BaseAdapter implements MediaRouter.Callback,
            ListView.OnItemClickListener {
        private static final int VIEW_TOP_HEADER = 0;
        private static final int VIEW_SECTION_HEADER = 1;
        private static final int VIEW_ROUTE = 2;

        private int mSelectedItemPosition;
        private final ArrayList<Object> mItems = new ArrayList<Object>();

        RouteAdapter() {
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
                holder.position = position;
                holder.text1 = (TextView) convertView.findViewById(R.id.text1);
                holder.text2 = (TextView) convertView.findViewById(R.id.text2);
                holder.icon = (ImageView) convertView.findViewById(R.id.icon);
                holder.expandGroupButton = (ImageButton) convertView.findViewById(
                        R.id.expand_button);
                if (holder.expandGroupButton != null) {
                    holder.expandGroupListener = new ExpandGroupListener();
                    holder.expandGroupButton.setOnClickListener(holder.expandGroupListener);
                }

                final View fview = convertView;
                final ListView list = (ListView) parent;
                final ViewHolder fholder = holder;
                convertView.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        list.performItemClick(fview, fholder.position, 0);
                    }
                });
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
                holder.position = position;
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
            Drawable icon = info.getIconDrawable();
            if (icon != null) {
                // Make sure we have a fresh drawable where it doesn't matter if we mutate it
                icon = icon.getConstantState().newDrawable(getResources());
            }
            holder.icon.setImageDrawable(icon);
            holder.icon.setVisibility(icon != null ? View.VISIBLE : View.GONE);

            RouteCategory cat = info.getCategory();
            boolean canGroup = false;
            if (cat.isGroupable()) {
                final RouteGroup group = (RouteGroup) info;
                canGroup = group.getRouteCount() > 1 ||
                        getItemViewType(position - 1) == VIEW_ROUTE ||
                        (position < getCount() - 1 && getItemViewType(position + 1) == VIEW_ROUTE);
            }
            holder.expandGroupButton.setVisibility(canGroup ? View.VISIBLE : View.GONE);
            holder.expandGroupListener.position = position;
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

        class ExpandGroupListener implements View.OnClickListener {
            int position;

            @Override
            public void onClick(View v) {
                // Assumption: this is only available for the user to click if we're presenting
                // a groupable category, where every top-level route in the category is a group.
                onExpandGroup((RouteGroup) getItem(position));
            }
        }
    }

    private class GroupAdapter extends BaseAdapter implements MediaRouter.Callback,
            ListView.OnItemClickListener {
        private static final int VIEW_HEADER = 0;
        private static final int VIEW_ROUTE = 1;
        private static final int VIEW_DONE = 2;

        private RouteGroup mPrimary;
        private RouteCategory mCategory;
        private final ArrayList<RouteInfo> mTempList = new ArrayList<RouteInfo>();
        private final ArrayList<RouteInfo> mFlatRoutes = new ArrayList<RouteInfo>();
        private boolean mIgnoreUpdates;

        public GroupAdapter(RouteGroup primary) {
            mPrimary = primary;
            mCategory = primary.getCategory();
            update();
        }

        @Override
        public int getCount() {
            return mFlatRoutes.size() + 2;
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return VIEW_HEADER;
            } else if (position == getCount() - 1) {
                return VIEW_DONE;
            }
            return VIEW_ROUTE;
        }

        void update() {
            if (mIgnoreUpdates) return;
            mFlatRoutes.clear();
            mCategory.getRoutes(mTempList);

            // Unpack groups and flatten for presentation
            final int topCount = mTempList.size();
            for (int i = 0; i < topCount; i++) {
                final RouteInfo route = mTempList.get(i);
                final RouteGroup group = route.getGroup();
                if (group == route) {
                    // This is a group, unpack it.
                    final int groupCount = group.getRouteCount();
                    for (int j = 0; j < groupCount; j++) {
                        final RouteInfo innerRoute = group.getRouteAt(j);
                        mFlatRoutes.add(innerRoute);
                    }
                } else {
                    mFlatRoutes.add(route);
                }
            }
            mTempList.clear();

            // Sort by name. This will keep the route positions relatively stable even though they
            // will be repeatedly added and removed.
            Collections.sort(mFlatRoutes, sComparator);
            notifyDataSetChanged();
        }

        void initCheckedItems() {
            if (mIgnoreUpdates) return;
            mListView.clearChoices();
            int count = mFlatRoutes.size();
            for (int i = 0; i < count; i++){
                final RouteInfo route = mFlatRoutes.get(i);
                if (route.getGroup() == mPrimary) {
                    mListView.setItemChecked(i + 1, true);
                }
            }
        }

        @Override
        public Object getItem(int position) {
            if (position == 0) {
                return mCategory;
            } else if (position == getCount() - 1) {
                return null; // Done
            }
            return mFlatRoutes.get(position - 1);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return position > 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final int viewType = getItemViewType(position);

            ViewHolder holder;
            if (convertView == null) {
                convertView = mInflater.inflate(GROUP_ITEM_LAYOUTS[viewType], parent, false);
                holder = new ViewHolder();
                holder.position = position;
                holder.text1 = (TextView) convertView.findViewById(R.id.text1);
                holder.text2 = (TextView) convertView.findViewById(R.id.text2);
                holder.icon = (ImageView) convertView.findViewById(R.id.icon);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
                holder.position = position;
            }

            if (viewType == VIEW_ROUTE) {
                bindItemView(position, holder);
            } else if (viewType == VIEW_HEADER) {
                bindHeaderView(position, holder);
            }

            return convertView;
        }

        void bindItemView(int position, ViewHolder holder) {
            RouteInfo info = (RouteInfo) getItem(position);
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
            holder.text1.setText(mCategory.getName());
        }

        @Override
        public void onRouteSelected(MediaRouter router, int type, RouteInfo info) {
        }

        @Override
        public void onRouteUnselected(MediaRouter router, int type, RouteInfo info) {
        }

        @Override
        public void onRouteAdded(MediaRouter router, RouteInfo info) {
            update();
            initCheckedItems();
        }

        @Override
        public void onRouteRemoved(MediaRouter router, RouteInfo info) {
            if (info == mPrimary) {
                // Can't keep grouping, clean it up.
                onDoneGrouping();
            } else {
                update();
                initCheckedItems();
            }
        }

        @Override
        public void onRouteChanged(MediaRouter router, RouteInfo info) {
            update();
        }

        @Override
        public void onRouteGrouped(MediaRouter router, RouteInfo info, RouteGroup group, int index) {
            update();
            initCheckedItems();
        }

        @Override
        public void onRouteUngrouped(MediaRouter router, RouteInfo info, RouteGroup group) {
            update();
            initCheckedItems();
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            if (getItemViewType(position) == VIEW_DONE) {
                onDoneGrouping();
                return;
            }

            final ListView lv = (ListView) parent;
            final RouteInfo route = mFlatRoutes.get(position - 1);
            final boolean checked = lv.isItemChecked(position);

            mIgnoreUpdates = true;
            RouteGroup oldGroup = route.getGroup();
            if (checked && oldGroup != mPrimary) {
                // Assumption: in a groupable category oldGroup will never be null.
                oldGroup.removeRoute(route);

                // If the group is now empty, remove the group too.
                if (oldGroup.getRouteCount() == 0) {
                    if (mRouter.getSelectedRoute(mRouteTypes) == oldGroup) {
                        // Old group was selected but is now empty. Select the group
                        // we're manipulating since that's where the last route went.
                        mRouter.selectRoute(mRouteTypes, mPrimary);
                    }
                    mRouter.removeRouteInt(oldGroup);
                }

                mPrimary.addRoute(route);
            } else if (!checked) {
                if (mPrimary.getRouteCount() > 1) {
                    mPrimary.removeRoute(route);

                    // In a groupable category this will add the route into its own new group.
                    mRouter.addRouteInt(route);
                } else {
                    // We're about to remove the last route.
                    // Don't let this happen, as it would be silly.
                    // Turn the checkmark back on again. Silly user!
                    lv.setItemChecked(position, true);
                }
            }
            mIgnoreUpdates = false;
            update();
            initCheckedItems();
        }
    }

    static class RouteComparator implements Comparator<RouteInfo> {
        @Override
        public int compare(RouteInfo lhs, RouteInfo rhs) {
            return lhs.getName().toString().compareTo(rhs.getName().toString());
        }
    }

    class RouteChooserDialog extends Dialog {
        public RouteChooserDialog(Context context, int theme) {
            super(context, theme);
        }

        @Override
        public void onBackPressed() {
            if (mGroupAdapter != null) {
                onDoneGrouping();
            } else {
                super.onBackPressed();
            }
        }
    }
}
