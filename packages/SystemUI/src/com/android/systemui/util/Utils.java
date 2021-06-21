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

import static android.view.Display.DEFAULT_DISPLAY;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.FeatureFlags;

import java.util.List;
import java.util.function.Consumer;

public class Utils {

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
     * Sets the visibility of an UI element according to the DISABLE_* flags in
     * {@link android.app.StatusBarManager}.
     */
    public static class DisableStateTracker implements CommandQueue.Callbacks,
            View.OnAttachStateChangeListener {
        private final int mMask1;
        private final int mMask2;
        private final CommandQueue mCommandQueue;
        private View mView;
        private boolean mDisabled;

        public DisableStateTracker(int disableMask, int disable2Mask, CommandQueue commandQueue) {
            mMask1 = disableMask;
            mMask2 = disable2Mask;
            mCommandQueue = commandQueue;
        }

        @Override
        public void onViewAttachedToWindow(View v) {
            mView = v;
            mCommandQueue.addCallback(this);
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            mCommandQueue.removeCallback(this);
            mView = null;
        }

        /**
         * Sets visibility of this {@link View} given the states passed from
         * {@link com.android.systemui.statusbar.CommandQueue.Callbacks#disable(int, int, int)}.
         */
        @Override
        public void disable(int displayId, int state1, int state2, boolean animate) {
            if (displayId != mView.getDisplay().getDisplayId()) {
                return;
            }
            final boolean disabled = ((state1 & mMask1) != 0) || ((state2 & mMask2) != 0);
            if (disabled == mDisabled) return;
            mDisabled = disabled;
            mView.setVisibility(disabled ? View.GONE : View.VISIBLE);
        }

        /** @return {@code true} if and only if this {@link View} is currently disabled */
        public boolean isDisabled() {
            return mDisabled;
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
    public static boolean isGesturalModeOnDefaultDisplay(Context context, int navMode) {
        return context.getDisplayId() == DEFAULT_DISPLAY
                && QuickStepContract.isGesturalMode(navMode);
    }

    /**
     * Allow the media player to be shown in the QS area, controlled by 2 flags.
     * Off by default, but can be disabled by setting to 0
     */
    public static boolean useQsMediaPlayer(Context context) {
        int flag = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.SHOW_MEDIA_ON_QUICK_SETTINGS, 1);
        return flag > 0;
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
     * Allow recommendations from smartspace to show in media controls.
     * Requires {@link #useQsMediaPlayer(Context)} to be enabled.
     * On by default, but can be disabled by setting to 0
     */
    public static boolean allowMediaRecommendations(Context context) {
        int flag = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION, 1);
        return useQsMediaPlayer(context) && flag > 0;
    }

    /**
     * Returns true if the device should use the split notification shade, based on feature flags,
     * orientation and screen width.
     */
    public static boolean shouldUseSplitNotificationShade(FeatureFlags featureFlags,
            Resources resources) {
        return featureFlags.isTwoColumnNotificationShadeEnabled()
                && resources.getBoolean(R.bool.config_use_split_notification_shade);
    }

    /**
     * Returns the color provided at the specified {@param attrIndex} in {@param a} if it exists,
     * otherwise, returns the color from the private attribute {@param privAttrId}.
     */
    public static int getPrivateAttrColorIfUnset(ContextThemeWrapper ctw, TypedArray a,
            int attrIndex, int defColor, int privAttrId) {
        // If the index is specified, use that value
        if (a.hasValue(attrIndex)) {
            return a.getColor(attrIndex, defColor);
        }

        // Otherwise fallback to the value of the private attribute
        int[] customAttrs = { privAttrId };
        a = ctw.obtainStyledAttributes(customAttrs);
        int color = a.getColor(0, defColor);
        a.recycle();
        return color;
    }

}
