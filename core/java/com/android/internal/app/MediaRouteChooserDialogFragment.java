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
import android.hardware.display.DisplayManager;
import android.media.MediaRouter;
import android.media.MediaRouter.RouteCategory;
import android.media.MediaRouter.RouteGroup;
import android.media.MediaRouter.RouteInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.Checkable;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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
        R.layout.media_route_list_item,
        R.layout.media_route_list_item_checkable,
        R.layout.media_route_list_item_collapse_group
    };

    MediaRouter mRouter;
    private int mRouteTypes;

    private LayoutInflater mInflater;
    private LauncherListener mLauncherListener;
    private View.OnClickListener mExtendedSettingsListener;
    private RouteAdapter mAdapter;
    private ListView mListView;
    private SeekBar mVolumeSlider;
    private ImageView mVolumeIcon;

    final RouteComparator mComparator = new RouteComparator();
    final MediaRouterCallback mCallback = new MediaRouterCallback();
    private boolean mIgnoreSliderVolumeChanges;
    private boolean mIgnoreCallbackVolumeChanges;

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
        mRouter.addCallback(mRouteTypes, mCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        if (mLauncherListener != null) {
            mLauncherListener.onDetached(this);
        }
        if (mAdapter != null) {
            mAdapter = null;
        }
        mInflater = null;
        mRouter.removeCallback(mCallback);
        mRouter = null;
    }

    public void setExtendedSettingsClickListener(View.OnClickListener listener) {
        mExtendedSettingsListener = listener;
    }

    public void setRouteTypes(int types) {
        mRouteTypes = types;
    }

    void updateVolume() {
        if (mRouter == null) return;

        final RouteInfo selectedRoute = mRouter.getSelectedRoute(mRouteTypes);
        mVolumeIcon.setImageResource(selectedRoute == null ||
                selectedRoute.getPlaybackType() == RouteInfo.PLAYBACK_TYPE_LOCAL ?
                R.drawable.ic_audio_vol : R.drawable.ic_media_route_on_holo_dark);

        mIgnoreSliderVolumeChanges = true;

        if (selectedRoute == null ||
                selectedRoute.getVolumeHandling() == RouteInfo.PLAYBACK_VOLUME_FIXED) {
            // Disable the slider and show it at max volume.
            mVolumeSlider.setMax(1);
            mVolumeSlider.setProgress(1);
            mVolumeSlider.setEnabled(false);
        } else {
            mVolumeSlider.setEnabled(true);
            mVolumeSlider.setMax(selectedRoute.getVolumeMax());
            mVolumeSlider.setProgress(selectedRoute.getVolume());
        }

        mIgnoreSliderVolumeChanges = false;
    }

    void changeVolume(int newValue) {
        if (mIgnoreSliderVolumeChanges) return;

        final RouteInfo selectedRoute = mRouter.getSelectedRoute(mRouteTypes);
        if (selectedRoute != null &&
                selectedRoute.getVolumeHandling() == RouteInfo.PLAYBACK_VOLUME_VARIABLE) {
            final int maxVolume = selectedRoute.getVolumeMax();
            newValue = Math.max(0, Math.min(newValue, maxVolume));
            selectedRoute.requestSetVolume(newValue);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        mInflater = inflater;
        final View layout = inflater.inflate(R.layout.media_route_chooser_layout, container, false);

        mVolumeIcon = (ImageView) layout.findViewById(R.id.volume_icon);
        mVolumeSlider = (SeekBar) layout.findViewById(R.id.volume_slider);
        updateVolume();
        mVolumeSlider.setOnSeekBarChangeListener(new VolumeSliderChangeListener());

        if (mExtendedSettingsListener != null) {
            final View extendedSettingsButton = layout.findViewById(R.id.extended_settings);
            extendedSettingsButton.setVisibility(View.VISIBLE);
            extendedSettingsButton.setOnClickListener(mExtendedSettingsListener);
        }

        final ListView list = (ListView) layout.findViewById(R.id.list);
        list.setItemsCanFocus(true);
        list.setAdapter(mAdapter = new RouteAdapter());
        list.setOnItemClickListener(mAdapter);

        mListView = list;

        mAdapter.scrollToSelectedItem();

        return layout;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        return new RouteChooserDialog(getActivity(), getTheme());
    }

    private static class ViewHolder {
        public TextView text1;
        public TextView text2;
        public ImageView icon;
        public ImageButton expandGroupButton;
        public RouteAdapter.ExpandGroupListener expandGroupListener;
        public int position;
        public CheckBox check;
    }

    private class RouteAdapter extends BaseAdapter implements ListView.OnItemClickListener {
        private static final int VIEW_TOP_HEADER = 0;
        private static final int VIEW_SECTION_HEADER = 1;
        private static final int VIEW_ROUTE = 2;
        private static final int VIEW_GROUPING_ROUTE = 3;
        private static final int VIEW_GROUPING_DONE = 4;

        private int mSelectedItemPosition = -1;
        private final ArrayList<Object> mItems = new ArrayList<Object>();

        private RouteCategory mCategoryEditingGroups;
        private RouteGroup mEditingGroup;

        // Temporary lists for manipulation
        private final ArrayList<RouteInfo> mCatRouteList = new ArrayList<RouteInfo>();
        private final ArrayList<RouteInfo> mSortRouteList = new ArrayList<RouteInfo>();

        private boolean mIgnoreUpdates;

        RouteAdapter() {
            update();
        }

        void update() {
            /*
             * This is kind of wacky, but our data sets are going to be
             * fairly small on average. Ideally we should be able to do some of this stuff
             * in-place instead.
             *
             * Basic idea: each entry in mItems represents an item in the list for quick access.
             * Entries can be a RouteCategory (section header), a RouteInfo with a category of
             * mCategoryEditingGroups (a flattened RouteInfo pulled out of its group, allowing
             * the user to change the group),
             */
            if (mIgnoreUpdates) return;

            mItems.clear();

            final RouteInfo selectedRoute = mRouter.getSelectedRoute(mRouteTypes);
            mSelectedItemPosition = -1;

            List<RouteInfo> routes;
            final int catCount = mRouter.getCategoryCount();
            for (int i = 0; i < catCount; i++) {
                final RouteCategory cat = mRouter.getCategoryAt(i);
                routes = cat.getRoutes(mCatRouteList);

                if (!cat.isSystem()) {
                    mItems.add(cat);
                }

                if (cat == mCategoryEditingGroups) {
                    addGroupEditingCategoryRoutes(routes);
                } else {
                    addSelectableRoutes(selectedRoute, routes);
                }

                routes.clear();
            }

            notifyDataSetChanged();
            if (mListView != null && mSelectedItemPosition >= 0) {
                mListView.setItemChecked(mSelectedItemPosition, true);
            }
        }

        void scrollToEditingGroup() {
            if (mCategoryEditingGroups == null || mListView == null) return;

            int pos = 0;
            int bound = 0;
            final int itemCount = mItems.size();
            for (int i = 0; i < itemCount; i++) {
                final Object item = mItems.get(i);
                if (item != null && item == mCategoryEditingGroups) {
                    bound = i;
                }
                if (item == null) {
                    pos = i;
                    break; // this is always below the category header; we can stop here.
                }
            }

            mListView.smoothScrollToPosition(pos, bound);
        }

        void scrollToSelectedItem() {
            if (mListView == null || mSelectedItemPosition < 0) return;

            mListView.smoothScrollToPosition(mSelectedItemPosition);
        }

        void addSelectableRoutes(RouteInfo selectedRoute, List<RouteInfo> from) {
            final int routeCount = from.size();
            for (int j = 0; j < routeCount; j++) {
                final RouteInfo info = from.get(j);
                if (info == selectedRoute) {
                    mSelectedItemPosition = mItems.size();
                }
                mItems.add(info);
            }
        }

        void addGroupEditingCategoryRoutes(List<RouteInfo> from) {
            // Unpack groups and flatten for presentation
            // mSortRouteList will always be empty here.
            final int topCount = from.size();
            for (int i = 0; i < topCount; i++) {
                final RouteInfo route = from.get(i);
                final RouteGroup group = route.getGroup();
                if (group == route) {
                    // This is a group, unpack it.
                    final int groupCount = group.getRouteCount();
                    for (int j = 0; j < groupCount; j++) {
                        final RouteInfo innerRoute = group.getRouteAt(j);
                        mSortRouteList.add(innerRoute);
                    }
                } else {
                    mSortRouteList.add(route);
                }
            }
            // Sort by name. This will keep the route positions relatively stable even though they
            // will be repeatedly added and removed.
            Collections.sort(mSortRouteList, mComparator);

            mItems.addAll(mSortRouteList);
            mSortRouteList.clear();

            mItems.add(null); // Sentinel reserving space for the "done" button.
        }

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public int getViewTypeCount() {
            return 5;
        }

        @Override
        public int getItemViewType(int position) {
            final Object item = getItem(position);
            if (item instanceof RouteCategory) {
                return position == 0 ? VIEW_TOP_HEADER : VIEW_SECTION_HEADER;
            } else if (item == null) {
                return VIEW_GROUPING_DONE;
            } else {
                final RouteInfo info = (RouteInfo) item;
                if (info.getCategory() == mCategoryEditingGroups) {
                    return VIEW_GROUPING_ROUTE;
                }
                return VIEW_ROUTE;
            }
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            switch (getItemViewType(position)) {
                case VIEW_ROUTE:
                    return ((RouteInfo) mItems.get(position)).isEnabled();
                case VIEW_GROUPING_ROUTE:
                case VIEW_GROUPING_DONE:
                    return true;
                default:
                    return false;
            }
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
                holder.check = (CheckBox) convertView.findViewById(R.id.check);
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

            switch (viewType) {
                case VIEW_ROUTE:
                case VIEW_GROUPING_ROUTE:
                    bindItemView(position, holder);
                    break;
                case VIEW_SECTION_HEADER:
                case VIEW_TOP_HEADER:
                    bindHeaderView(position, holder);
                    break;
            }

            convertView.setActivated(position == mSelectedItemPosition);
            convertView.setEnabled(isEnabled(position));

            return convertView;
        }

        void bindItemView(int position, ViewHolder holder) {
            RouteInfo info = (RouteInfo) mItems.get(position);
            holder.text1.setText(info.getName(getActivity()));
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
            if (cat == mCategoryEditingGroups) {
                RouteGroup group = info.getGroup();
                holder.check.setEnabled(group.getRouteCount() > 1);
                holder.check.setChecked(group == mEditingGroup);
            } else {
                if (cat.isGroupable()) {
                    final RouteGroup group = (RouteGroup) info;
                    canGroup = group.getRouteCount() > 1 ||
                            getItemViewType(position - 1) == VIEW_ROUTE ||
                            (position < getCount() - 1 &&
                                    getItemViewType(position + 1) == VIEW_ROUTE);
                }
            }

            if (holder.expandGroupButton != null) {
                holder.expandGroupButton.setVisibility(canGroup ? View.VISIBLE : View.GONE);
                holder.expandGroupListener.position = position;
            }
        }

        void bindHeaderView(int position, ViewHolder holder) {
            RouteCategory cat = (RouteCategory) mItems.get(position);
            holder.text1.setText(cat.getName(getActivity()));
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            final int type = getItemViewType(position);
            if (type == VIEW_SECTION_HEADER || type == VIEW_TOP_HEADER) {
                return;
            } else if (type == VIEW_GROUPING_DONE) {
                finishGrouping();
                return;
            } else {
                final Object item = getItem(position);
                if (!(item instanceof RouteInfo)) {
                    // Oops. Stale event running around? Skip it.
                    return;
                }

                final RouteInfo route = (RouteInfo) item;
                if (type == VIEW_ROUTE) {
                    mRouter.selectRouteInt(mRouteTypes, route);
                    dismiss();
                } else if (type == VIEW_GROUPING_ROUTE) {
                    final Checkable c = (Checkable) view;
                    final boolean wasChecked = c.isChecked();

                    mIgnoreUpdates = true;
                    RouteGroup oldGroup = route.getGroup();
                    if (!wasChecked && oldGroup != mEditingGroup) {
                        // Assumption: in a groupable category oldGroup will never be null.
                        if (mRouter.getSelectedRoute(mRouteTypes) == oldGroup) {
                            // Old group was selected but is now empty. Select the group
                            // we're manipulating since that's where the last route went.
                            mRouter.selectRouteInt(mRouteTypes, mEditingGroup);
                        }
                        oldGroup.removeRoute(route);
                        mEditingGroup.addRoute(route);
                        c.setChecked(true);
                    } else if (wasChecked && mEditingGroup.getRouteCount() > 1) {
                        mEditingGroup.removeRoute(route);

                        // In a groupable category this will add
                        // the route into its own new group.
                        mRouter.addRouteInt(route);
                    }
                    mIgnoreUpdates = false;
                    update();
                }
            }
        }

        boolean isGrouping() {
            return mCategoryEditingGroups != null;
        }

        void finishGrouping() {
            mCategoryEditingGroups = null;
            mEditingGroup = null;
            getDialog().setCanceledOnTouchOutside(true);
            update();
            scrollToSelectedItem();
        }

        class ExpandGroupListener implements View.OnClickListener {
            int position;

            @Override
            public void onClick(View v) {
                // Assumption: this is only available for the user to click if we're presenting
                // a groupable category, where every top-level route in the category is a group.
                final RouteGroup group = (RouteGroup) getItem(position);
                mEditingGroup = group;
                mCategoryEditingGroups = group.getCategory();
                getDialog().setCanceledOnTouchOutside(false);
                mRouter.selectRouteInt(mRouteTypes, mEditingGroup);
                update();
                scrollToEditingGroup();
            }
        }
    }

    class MediaRouterCallback extends MediaRouter.Callback {
        @Override
        public void onRouteSelected(MediaRouter router, int type, RouteInfo info) {
            mAdapter.update();
            updateVolume();
        }

        @Override
        public void onRouteUnselected(MediaRouter router, int type, RouteInfo info) {
            mAdapter.update();
        }

        @Override
        public void onRouteAdded(MediaRouter router, RouteInfo info) {
            mAdapter.update();
        }

        @Override
        public void onRouteRemoved(MediaRouter router, RouteInfo info) {
            if (info == mAdapter.mEditingGroup) {
                mAdapter.finishGrouping();
            }
            mAdapter.update();
        }

        @Override
        public void onRouteChanged(MediaRouter router, RouteInfo info) {
            mAdapter.notifyDataSetChanged();
        }

        @Override
        public void onRouteGrouped(MediaRouter router, RouteInfo info,
                RouteGroup group, int index) {
            mAdapter.update();
        }

        @Override
        public void onRouteUngrouped(MediaRouter router, RouteInfo info, RouteGroup group) {
            mAdapter.update();
        }

        @Override
        public void onRouteVolumeChanged(MediaRouter router, RouteInfo info) {
            if (!mIgnoreCallbackVolumeChanges) {
                updateVolume();
            }
        }
    }

    class RouteComparator implements Comparator<RouteInfo> {
        @Override
        public int compare(RouteInfo lhs, RouteInfo rhs) {
            return lhs.getName(getActivity()).toString()
                    .compareTo(rhs.getName(getActivity()).toString());
        }
    }

    class RouteChooserDialog extends Dialog {
        public RouteChooserDialog(Context context, int theme) {
            super(context, theme);
        }

        @Override
        public void onBackPressed() {
            if (mAdapter != null && mAdapter.isGrouping()) {
                mAdapter.finishGrouping();
            } else {
                super.onBackPressed();
            }
        }
        
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && mVolumeSlider.isEnabled()) {
                final RouteInfo selectedRoute = mRouter.getSelectedRoute(mRouteTypes);
                if (selectedRoute != null) {
                    selectedRoute.requestUpdateVolume(-1);
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && mVolumeSlider.isEnabled()) {
                final RouteInfo selectedRoute = mRouter.getSelectedRoute(mRouteTypes);
                if (selectedRoute != null) {
                    mRouter.getSelectedRoute(mRouteTypes).requestUpdateVolume(1);
                    return true;
                }
            }
            return super.onKeyDown(keyCode, event);
        }

        public boolean onKeyUp(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && mVolumeSlider.isEnabled()) {
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && mVolumeSlider.isEnabled()) {
                return true;
            } else {
                return super.onKeyUp(keyCode, event);
            }
        }
    }

    /**
     * Implemented by the MediaRouteButton that launched this dialog
     */
    public interface LauncherListener {
        public void onDetached(MediaRouteChooserDialogFragment detachedFragment);
    }

    class VolumeSliderChangeListener implements SeekBar.OnSeekBarChangeListener {

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            changeVolume(progress);
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            mIgnoreCallbackVolumeChanges = true;
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            mIgnoreCallbackVolumeChanges = false;
            updateVolume();
        }

    }
}
