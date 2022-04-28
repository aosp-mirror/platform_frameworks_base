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


import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.content.Context;
import android.media.MediaRouter;
import android.util.Log;
import android.view.View;

/**
 * Shows media route dialog as appropriate.
 * @hide
 */
public abstract class MediaRouteDialogPresenter {
    private static final String TAG = "MediaRouter";

    private static final String CHOOSER_FRAGMENT_TAG =
            "android.app.MediaRouteButton:MediaRouteChooserDialogFragment";
    private static final String CONTROLLER_FRAGMENT_TAG =
            "android.app.MediaRouteButton:MediaRouteControllerDialogFragment";

    public static DialogFragment showDialogFragment(Activity activity,
            int routeTypes, View.OnClickListener extendedSettingsClickListener) {
        final MediaRouter router = (MediaRouter)activity.getSystemService(
                Context.MEDIA_ROUTER_SERVICE);
        final FragmentManager fm = activity.getFragmentManager();

        MediaRouter.RouteInfo route = router.getSelectedRoute();
        if (route.isDefault() || !route.matchesTypes(routeTypes)) {
            if (fm.findFragmentByTag(CHOOSER_FRAGMENT_TAG) != null) {
                Log.w(TAG, "showDialog(): Route chooser dialog already showing!");
                return null;
            }
            MediaRouteChooserDialogFragment f = new MediaRouteChooserDialogFragment();
            f.setRouteTypes(routeTypes);
            f.setExtendedSettingsClickListener(extendedSettingsClickListener);
            f.show(fm, CHOOSER_FRAGMENT_TAG);
            return f;
        } else {
            if (fm.findFragmentByTag(CONTROLLER_FRAGMENT_TAG) != null) {
                Log.w(TAG, "showDialog(): Route controller dialog already showing!");
                return null;
            }
            MediaRouteControllerDialogFragment f = new MediaRouteControllerDialogFragment();
            f.show(fm, CONTROLLER_FRAGMENT_TAG);
            return f;
        }
    }

    /** Create a media route dialog as appropriate. */
    public static Dialog createDialog(Context context,
            int routeTypes, View.OnClickListener extendedSettingsClickListener) {
        int theme = MediaRouteChooserDialog.isLightTheme(context)
                ? android.R.style.Theme_DeviceDefault_Light_Dialog
                : android.R.style.Theme_DeviceDefault_Dialog;
        return createDialog(context, routeTypes, extendedSettingsClickListener, theme);
    }

    /** Create a media route dialog as appropriate. */
    public static Dialog createDialog(Context context,
            int routeTypes, View.OnClickListener extendedSettingsClickListener, int theme) {
        return createDialog(context, routeTypes, extendedSettingsClickListener, theme,
                true /* showProgressBarWhenEmpty */);
    }

    /** Create a media route dialog as appropriate. */
    public static Dialog createDialog(Context context, int routeTypes,
            View.OnClickListener extendedSettingsClickListener, int theme,
            boolean showProgressBarWhenEmpty) {
        final MediaRouter router = context.getSystemService(MediaRouter.class);

        MediaRouter.RouteInfo route = router.getSelectedRoute();
        if (route.isDefault() || !route.matchesTypes(routeTypes)) {
            final MediaRouteChooserDialog d = new MediaRouteChooserDialog(context, theme,
                    showProgressBarWhenEmpty);
            d.setRouteTypes(routeTypes);
            d.setExtendedSettingsClickListener(extendedSettingsClickListener);
            return d;
        } else {
            return new MediaRouteControllerDialog(context, theme);
        }
    }
}
