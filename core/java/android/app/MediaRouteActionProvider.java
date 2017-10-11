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

package android.app;

import android.content.Context;
import android.media.MediaRouter;
import android.media.MediaRouter.RouteInfo;
import android.util.Log;
import android.view.ActionProvider;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;

/**
 * The media route action provider displays a {@link MediaRouteButton media route button}
 * in the application's {@link ActionBar} to allow the user to select routes and
 * to control the currently selected route.
 * <p>
 * The application must specify the kinds of routes that the user should be allowed
 * to select by specifying the route types with the {@link #setRouteTypes} method.
 * </p><p>
 * Refer to {@link MediaRouteButton} for a description of the button that will
 * appear in the action bar menu.  Note that instead of disabling the button
 * when no routes are available, the action provider will instead make the
 * menu item invisible.  In this way, the button will only be visible when it
 * is possible for the user to discover and select a matching route.
 * </p>
 */
public class MediaRouteActionProvider extends ActionProvider {
    private static final String TAG = "MediaRouteActionProvider";

    private final Context mContext;
    private final MediaRouter mRouter;
    private final MediaRouterCallback mCallback;

    private int mRouteTypes;
    private MediaRouteButton mButton;
    private View.OnClickListener mExtendedSettingsListener;

    public MediaRouteActionProvider(Context context) {
        super(context);

        mContext = context;
        mRouter = (MediaRouter) context.getSystemService(Context.MEDIA_ROUTER_SERVICE);
        mCallback = new MediaRouterCallback(this);

        // Start with live audio by default.
        // TODO Update this when new route types are added; segment by API level
        // when different route types were added.
        setRouteTypes(MediaRouter.ROUTE_TYPE_LIVE_AUDIO);
    }

    /**
     * Sets the types of routes that will be shown in the media route chooser dialog
     * launched by this button.
     *
     * @param types The route types to match.
     */
    public void setRouteTypes(int types) {
        if (mRouteTypes != types) {
            // FIXME: We currently have no way of knowing whether the action provider
            // is still needed by the UI.  Unfortunately this means the action provider
            // may leak callbacks until garbage collection occurs.  This may result in
            // media route providers doing more work than necessary in the short term
            // while trying to discover routes that are no longer of interest to the
            // application.  To solve this problem, the action provider will need some
            // indication from the framework that it is being destroyed.
            if (mRouteTypes != 0) {
                mRouter.removeCallback(mCallback);
            }
            mRouteTypes = types;
            if (types != 0) {
                mRouter.addCallback(types, mCallback,
                        MediaRouter.CALLBACK_FLAG_PASSIVE_DISCOVERY);
            }
            refreshRoute();

            if (mButton != null) {
                mButton.setRouteTypes(mRouteTypes);
            }
        }
    }

    public void setExtendedSettingsClickListener(View.OnClickListener listener) {
        mExtendedSettingsListener = listener;
        if (mButton != null) {
            mButton.setExtendedSettingsClickListener(listener);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public View onCreateActionView() {
        throw new UnsupportedOperationException("Use onCreateActionView(MenuItem) instead.");
    }

    @Override
    public View onCreateActionView(MenuItem item) {
        if (mButton != null) {
            Log.e(TAG, "onCreateActionView: this ActionProvider is already associated " +
                    "with a menu item. Don't reuse MediaRouteActionProvider instances! " +
                    "Abandoning the old one...");
        }

        mButton = new MediaRouteButton(mContext);
        mButton.setRouteTypes(mRouteTypes);
        mButton.setExtendedSettingsClickListener(mExtendedSettingsListener);
        mButton.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return mButton;
    }

    @Override
    public boolean onPerformDefaultAction() {
        if (mButton != null) {
            return mButton.showDialogInternal();
        }
        return false;
    }

    @Override
    public boolean overridesItemVisibility() {
        return true;
    }

    @Override
    public boolean isVisible() {
        return mRouter.isRouteAvailable(mRouteTypes,
                MediaRouter.AVAILABILITY_FLAG_IGNORE_DEFAULT_ROUTE);
    }

    private void refreshRoute() {
        refreshVisibility();
    }

    private static class MediaRouterCallback extends MediaRouter.SimpleCallback {
        private final WeakReference<MediaRouteActionProvider> mProviderWeak;

        public MediaRouterCallback(MediaRouteActionProvider provider) {
            mProviderWeak = new WeakReference<MediaRouteActionProvider>(provider);
        }

        @Override
        public void onRouteAdded(MediaRouter router, RouteInfo info) {
            refreshRoute(router);
        }

        @Override
        public void onRouteRemoved(MediaRouter router, RouteInfo info) {
            refreshRoute(router);
        }

        @Override
        public void onRouteChanged(MediaRouter router, RouteInfo info) {
            refreshRoute(router);
        }

        private void refreshRoute(MediaRouter router) {
            MediaRouteActionProvider provider = mProviderWeak.get();
            if (provider != null) {
                provider.refreshRoute();
            } else {
                router.removeCallback(this);
            }
        }
    }
}
