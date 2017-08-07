/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.app.Dialog;
import android.content.Context;
import android.media.MediaRouter;
import android.media.MediaRouter.RouteInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.util.Comparator;

/**
 * This class implements the route chooser dialog for {@link MediaRouter}.
 * <p>
 * This dialog allows the user to choose a route that matches a given selector.
 * </p>
 *
 * @see MediaRouteButton
 * @see MediaRouteActionProvider
 *
 * TODO: Move this back into the API, as in the support library media router.
 */
public class MediaRouteChooserDialog extends Dialog {
    private final MediaRouter mRouter;
    private final MediaRouterCallback mCallback;

    private int mRouteTypes;
    private View.OnClickListener mExtendedSettingsClickListener;
    private RouteAdapter mAdapter;
    private ListView mListView;
    private Button mExtendedSettingsButton;
    private boolean mAttachedToWindow;

    public MediaRouteChooserDialog(Context context, int theme) {
        super(context, theme);

        mRouter = (MediaRouter) context.getSystemService(Context.MEDIA_ROUTER_SERVICE);
        mCallback = new MediaRouterCallback();
    }

    /**
     * Gets the media route types for filtering the routes that the user can
     * select using the media route chooser dialog.
     *
     * @return The route types.
     */
    public int getRouteTypes() {
        return mRouteTypes;
    }

    /**
     * Sets the types of routes that will be shown in the media route chooser dialog
     * launched by this button.
     *
     * @param types The route types to match.
     */
    public void setRouteTypes(int types) {
        if (mRouteTypes != types) {
            mRouteTypes = types;

            if (mAttachedToWindow) {
                mRouter.removeCallback(mCallback);
                mRouter.addCallback(types, mCallback,
                        MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
            }

            refreshRoutes();
        }
    }

    public void setExtendedSettingsClickListener(View.OnClickListener listener) {
        if (listener != mExtendedSettingsClickListener) {
            mExtendedSettingsClickListener = listener;
            updateExtendedSettingsButton();
        }
    }

    /**
     * Returns true if the route should be included in the list.
     * <p>
     * The default implementation returns true for enabled non-default routes that
     * match the route types.  Subclasses can override this method to filter routes
     * differently.
     * </p>
     *
     * @param route The route to consider, never null.
     * @return True if the route should be included in the chooser dialog.
     */
    public boolean onFilterRoute(MediaRouter.RouteInfo route) {
        return !route.isDefault() && route.isEnabled() && route.matchesTypes(mRouteTypes);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().requestFeature(Window.FEATURE_LEFT_ICON);

        setContentView(R.layout.media_route_chooser_dialog);
        setTitle(mRouteTypes == MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY
                ? R.string.media_route_chooser_title_for_remote_display
                : R.string.media_route_chooser_title);

        // Must be called after setContentView.
        getWindow().setFeatureDrawableResource(Window.FEATURE_LEFT_ICON,
                isLightTheme(getContext()) ? R.drawable.ic_media_route_off_holo_light
                    : R.drawable.ic_media_route_off_holo_dark);

        mAdapter = new RouteAdapter(getContext());
        mListView = (ListView)findViewById(R.id.media_route_list);
        mListView.setAdapter(mAdapter);
        mListView.setOnItemClickListener(mAdapter);
        mListView.setEmptyView(findViewById(android.R.id.empty));

        mExtendedSettingsButton = (Button)findViewById(R.id.media_route_extended_settings_button);
        updateExtendedSettingsButton();
    }

    private void updateExtendedSettingsButton() {
        if (mExtendedSettingsButton != null) {
            mExtendedSettingsButton.setOnClickListener(mExtendedSettingsClickListener);
            mExtendedSettingsButton.setVisibility(
                    mExtendedSettingsClickListener != null ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        mAttachedToWindow = true;
        mRouter.addCallback(mRouteTypes, mCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
        refreshRoutes();
    }

    @Override
    public void onDetachedFromWindow() {
        mAttachedToWindow = false;
        mRouter.removeCallback(mCallback);

        super.onDetachedFromWindow();
    }

    /**
     * Refreshes the list of routes that are shown in the chooser dialog.
     */
    public void refreshRoutes() {
        if (mAttachedToWindow) {
            mAdapter.update();
        }
    }

    static boolean isLightTheme(Context context) {
        TypedValue value = new TypedValue();
        return context.getTheme().resolveAttribute(R.attr.isLightTheme, value, true)
                && value.data != 0;
    }

    private final class RouteAdapter extends ArrayAdapter<MediaRouter.RouteInfo>
            implements ListView.OnItemClickListener {
        private final LayoutInflater mInflater;

        public RouteAdapter(Context context) {
            super(context, 0);
            mInflater = LayoutInflater.from(context);
        }

        public void update() {
            clear();
            final int count = mRouter.getRouteCount();
            for (int i = 0; i < count; i++) {
                MediaRouter.RouteInfo route = mRouter.getRouteAt(i);
                if (onFilterRoute(route)) {
                    add(route);
                }
            }
            sort(RouteComparator.sInstance);
            notifyDataSetChanged();
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return getItem(position).isEnabled();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = mInflater.inflate(R.layout.media_route_list_item, parent, false);
            }
            MediaRouter.RouteInfo route = getItem(position);
            TextView text1 = (TextView)view.findViewById(android.R.id.text1);
            TextView text2 = (TextView)view.findViewById(android.R.id.text2);
            text1.setText(route.getName());
            CharSequence description = route.getDescription();
            if (TextUtils.isEmpty(description)) {
                text2.setVisibility(View.GONE);
                text2.setText("");
            } else {
                text2.setVisibility(View.VISIBLE);
                text2.setText(description);
            }
            view.setEnabled(route.isEnabled());
            return view;
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            MediaRouter.RouteInfo route = getItem(position);
            if (route.isEnabled()) {
                route.select();
                dismiss();
            }
        }
    }

    private final class MediaRouterCallback extends MediaRouter.SimpleCallback {
        @Override
        public void onRouteAdded(MediaRouter router, MediaRouter.RouteInfo info) {
            refreshRoutes();
        }

        @Override
        public void onRouteRemoved(MediaRouter router, MediaRouter.RouteInfo info) {
            refreshRoutes();
        }

        @Override
        public void onRouteChanged(MediaRouter router, MediaRouter.RouteInfo info) {
            refreshRoutes();
        }

        @Override
        public void onRouteSelected(MediaRouter router, int type, RouteInfo info) {
            dismiss();
        }
    }

    private static final class RouteComparator implements Comparator<MediaRouter.RouteInfo> {
        public static final RouteComparator sInstance = new RouteComparator();

        @Override
        public int compare(MediaRouter.RouteInfo lhs, MediaRouter.RouteInfo rhs) {
            return lhs.getName().toString().compareTo(rhs.getName().toString());
        }
    }
}
