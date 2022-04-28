/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.settingslib.activityembedding;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.os.BuildCompat;
import androidx.window.embedding.SplitController;

import com.android.settingslib.utils.BuildCompatUtils;

/**
 * An util class collecting all common methods for the embedding activity features.
 */
public final class ActivityEmbeddingUtils {
    private static final String ACTION_SETTINGS_EMBED_DEEP_LINK_ACTIVITY =
            "android.settings.SETTINGS_EMBED_DEEP_LINK_ACTIVITY";
    private static final String PACKAGE_NAME_SETTINGS = "com.android.settings";
    private static final String TAG = "ActivityEmbeddingUtils";

    /**
     * Whether the embedding activity feature is enabled.
     *
     * <p>This returns false if the Android version is below S or if the embedding activity is not
     * enabled (unsupported devices).
     */
    public static boolean isEmbeddingActivityEnabled(Context context) {
        final boolean isEmbeddingActivityEnabled = getEmbeddingActivityComponent(context) != null;
        Log.d(TAG, "isEmbeddingActivityEnabled : " + isEmbeddingActivityEnabled);
        return isEmbeddingActivityEnabled;
    }

    /**
     * Returns a base Intent to the embedding activity (without the extras).
     *
     * <p>This returns null if the Android version is below S or if the embedding activity is not
     * enabled (unsupported devices).
     */
    public static Intent buildEmbeddingActivityBaseIntent(Context context) {
        ComponentName embeddingActivityComponentName = getEmbeddingActivityComponent(context);
        if (embeddingActivityComponentName == null) {
            return null;
        }
        return new Intent(ACTION_SETTINGS_EMBED_DEEP_LINK_ACTIVITY)
                .setComponent(embeddingActivityComponentName);
    }

    /**
     * Returns the ComponentName associated with the embedding activity.
     *
     * <p>This returns null if the Android version is below S or if the embedding activity is not
     * enabled (unsupported devices).
     */
    private static ComponentName getEmbeddingActivityComponent(Context context) {
        if (!BuildCompatUtils.isAtLeastSV2()) {
            return null;
        }
        final Intent intent = new Intent(ACTION_SETTINGS_EMBED_DEEP_LINK_ACTIVITY);
        intent.setPackage(PACKAGE_NAME_SETTINGS);
        return intent.resolveActivity(context.getPackageManager());
    }

    /**
     * Whether the current activity is embedded in the Settings app or not.
     *
     * @param activity Activity that needs the check
     */
    public static boolean isActivityEmbedded(Activity activity) {
        return SplitController.getInstance().isActivityEmbedded(activity);
    }

    /**
     * Whether the current activity should hide the navigate up button.
     *
     * @param activity          Activity that needs the check
     * @param isSecondLayerPage indicates if the activity(page) is shown in the 2nd layer of
     *                          Settings app
     */
    public static boolean shouldHideNavigateUpButton(Activity activity, boolean isSecondLayerPage) {
        if (!BuildCompat.isAtLeastT()) {
            return false;
        }

        if (!isSecondLayerPage) {
            return false;
        }

        final String shouldHideNavigateUpButton =
                Settings.Global.getString(activity.getContentResolver(),
                        "settings_hide_second_layer_page_navigate_up_button_in_two_pane");

        if (TextUtils.isEmpty(shouldHideNavigateUpButton)
                || Boolean.parseBoolean(shouldHideNavigateUpButton)) {
            return isActivityEmbedded(activity);
        }
        return false;
    }

    private ActivityEmbeddingUtils() {
    }
}
