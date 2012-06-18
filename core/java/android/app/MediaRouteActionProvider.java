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

import com.android.internal.app.MediaRouteChooserDialogFragment;

import android.content.Context;
import android.content.ContextWrapper;
import android.media.MediaRouter;
import android.media.MediaRouter.RouteInfo;
import android.util.Log;
import android.view.ActionProvider;
import android.view.MenuItem;
import android.view.View;

import java.lang.ref.WeakReference;

public class MediaRouteActionProvider extends ActionProvider {
    private static final String TAG = "MediaRouteActionProvider";

    private Context mContext;
    private MediaRouter mRouter;
    private MenuItem mMenuItem;
    private MediaRouteButton mView;
    private int mRouteTypes;
    private View.OnClickListener mExtendedSettingsListener;
    private RouterCallback mCallback;

    public MediaRouteActionProvider(Context context) {
        super(context);
        mContext = context;
        mRouter = (MediaRouter) context.getSystemService(Context.MEDIA_ROUTER_SERVICE);
        mCallback = new RouterCallback(this);

        // Start with live audio by default.
        // TODO Update this when new route types are added; segment by API level
        // when different route types were added.
        setRouteTypes(MediaRouter.ROUTE_TYPE_LIVE_AUDIO);
    }

    public void setRouteTypes(int types) {
        if (mRouteTypes == types) return;
        if (mRouteTypes != 0) {
            mRouter.removeCallback(mCallback);
        }
        mRouteTypes = types;
        if (types != 0) {
            mRouter.addCallback(types, mCallback);
        }
        if (mView != null) {
            mView.setRouteTypes(mRouteTypes);
        }
    }

    @Override
    public View onCreateActionView() {
        throw new UnsupportedOperationException("Use onCreateActionView(MenuItem) instead.");
    }

    @Override
    public View onCreateActionView(MenuItem item) {
        if (mMenuItem != null || mView != null) {
            Log.e(TAG, "onCreateActionView: this ActionProvider is already associated " +
                    "with a menu item. Don't reuse MediaRouteActionProvider instances! " +
                    "Abandoning the old one...");
        }
        mMenuItem = item;
        mView = new MediaRouteButton(mContext);
        mView.setRouteTypes(mRouteTypes);
        mView.setExtendedSettingsClickListener(mExtendedSettingsListener);
        return mView;
    }

    @Override
    public boolean onPerformDefaultAction() {
        final FragmentManager fm = getActivity().getFragmentManager();
        // See if one is already attached to this activity.
        MediaRouteChooserDialogFragment dialogFragment =
                (MediaRouteChooserDialogFragment) fm.findFragmentByTag(
                MediaRouteChooserDialogFragment.FRAGMENT_TAG);
        if (dialogFragment != null) {
            Log.w(TAG, "onPerformDefaultAction(): Chooser dialog already showing!");
            return false;
        }

        dialogFragment = new MediaRouteChooserDialogFragment();
        dialogFragment.setExtendedSettingsClickListener(mExtendedSettingsListener);
        dialogFragment.setRouteTypes(mRouteTypes);
        dialogFragment.show(fm, MediaRouteChooserDialogFragment.FRAGMENT_TAG);
        return true;
    }

    private Activity getActivity() {
        // Gross way of unwrapping the Activity so we can get the FragmentManager
        Context context = mContext;
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            context = ((ContextWrapper) context).getBaseContext();
        }
        if (!(context instanceof Activity)) {
            throw new IllegalStateException("The MediaRouteActionProvider's Context " +
                    "is not an Activity.");
        }

        return (Activity) context;
    }

    public void setExtendedSettingsClickListener(View.OnClickListener listener) {
        mExtendedSettingsListener = listener;
        if (mView != null) {
            mView.setExtendedSettingsClickListener(listener);
        }
    }

    @Override
    public boolean overridesItemVisibility() {
        return true;
    }

    @Override
    public boolean isVisible() {
        return mRouter.getRouteCount() > 1;
    }

    private static class RouterCallback extends MediaRouter.SimpleCallback {
        private WeakReference<MediaRouteActionProvider> mAp;

        RouterCallback(MediaRouteActionProvider ap) {
            mAp = new WeakReference<MediaRouteActionProvider>(ap);
        }

        @Override
        public void onRouteAdded(MediaRouter router, RouteInfo info) {
            final MediaRouteActionProvider ap = mAp.get();
            if (ap == null) {
                router.removeCallback(this);
                return;
            }

            ap.refreshVisibility();
        }

        @Override
        public void onRouteRemoved(MediaRouter router, RouteInfo info) {
            final MediaRouteActionProvider ap = mAp.get();
            if (ap == null) {
                router.removeCallback(this);
                return;
            }

            ap.refreshVisibility();
        }
    }
}
