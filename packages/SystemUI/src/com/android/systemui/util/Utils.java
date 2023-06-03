/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.util;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.provider.Settings;
import android.view.DisplayCutout;

import com.android.internal.policy.SystemBarUtils;
import com.android.systemui.R;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.shared.system.QuickStepContract;

import java.util.List;
import java.util.function.Consumer;

public class Utils {

    private static Boolean sUseQsMediaPlayer = null;

    /**
     * Allows lambda iteration over a list. It is done in reverse order so it is safe
     * to add or remove items during the iteration.  Skips over null items.
     */
    public static <T> void safeForeach(List<T> list, Consumer<T> c) {
        for (int i = list.size() - 1; i >= 0; i--) {
            T item = list.get(i);
            if (item != null) {
                c.accept(item);
            }
        }
    }

    /**
     * Returns {@code true} iff the package {@code packageName} is a headless remote display
     * provider, i.e, that the package holds the privileged {@code REMOTE_DISPLAY_PROVIDER}
     * permission and that it doesn't host a launcher icon.
     */
    public static boolean isHeadlessRemoteDisplayProvider(PackageManager pm, String packageName) {
        if (pm.checkPermission(Manifest.permission.REMOTE_DISPLAY_PROVIDER, packageName)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        homeIntent.setPackage(packageName);

        return pm.queryIntentActivities(homeIntent, 0).isEmpty();
    }

    /**
     * Returns {@code true} if the navMode is that of
     * {@link android.view.WindowManagerPolicyConstants#NAV_BAR_MODE_GESTURAL} AND
     * the context is that of the default display
     */
    public static boolean isGesturalModeOnDefaultDisplay(Context context,
            DisplayTracker displayTracker, int navMode) {
        return context.getDisplayId() == displayTracker.getDefaultDisplayId()
                && QuickStepContract.isGesturalMode(navMode);
    }

    /**
     * Allow the media player to be shown in the QS area, controlled by 2 flags.
     * Off by default, but can be disabled by setting to 0
     */
    public static boolean useQsMediaPlayer(Context context) {
        // TODO(b/192412820): Replace SHOW_MEDIA_ON_QUICK_SETTINGS with compile-time value
        // Settings.Global.SHOW_MEDIA_ON_QUICK_SETTINGS can't be toggled at runtime, so simply
        // cache the first result we fetch and use that going forward. Do this to avoid unnecessary
        // binder calls which may happen on the critical path.
        if (sUseQsMediaPlayer == null) {
            int flag = Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.SHOW_MEDIA_ON_QUICK_SETTINGS, 1);
            sUseQsMediaPlayer = flag > 0;
        }
        return sUseQsMediaPlayer;
    }

    /**
     * Allow media resumption controls. Requires {@link #useQsMediaPlayer(Context)} to be enabled.
     * On by default, but can be disabled by setting to 0
     */
    public static boolean useMediaResumption(Context context) {
        int flag = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.MEDIA_CONTROLS_RESUME, 1);
        return useQsMediaPlayer(context) && flag > 0;
    }

    /**
     * Returns true if the device should use the collapsed layout for the media player when in
     * landscape (or seascape) orientation
     */
    public static boolean useCollapsedMediaInLandscape(Resources resources) {
        return resources.getBoolean(R.bool.config_quickSettingsMediaLandscapeCollapsed);
    }

    /**
     * Gets the {@link R.dimen#status_bar_header_height_keyguard}.
     */
    public static int getStatusBarHeaderHeightKeyguard(Context context) {
        final int statusBarHeight = SystemBarUtils.getStatusBarHeight(context);
        final DisplayCutout cutout = context.getDisplay().getCutout();
        final int waterfallInsetTop = cutout == null ? 0 : cutout.getWaterfallInsets().top;
        final int statusBarHeaderHeightKeyguard = context.getResources()
                .getDimensionPixelSize(R.dimen.status_bar_header_height_keyguard);
        return Math.max(statusBarHeight, statusBarHeaderHeightKeyguard + waterfallInsetTop);
    }
}
