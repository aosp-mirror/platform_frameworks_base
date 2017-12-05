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

package com.android.settingslib;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.net.Uri;
import android.provider.Settings.Global;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Functions to easily prepare contextual help menu option items with an intent that opens up the
 * browser to a particular URL, while taking into account the preferred language and app version.
 */
public class HelpUtils {
    private final static String TAG = HelpUtils.class.getSimpleName();

    private static final int MENU_HELP = Menu.FIRST + 100;

    /**
     * Help URL query parameter key for the preferred language.
     */
    private final static String PARAM_LANGUAGE_CODE = "hl";

    /**
     * Help URL query parameter key for the app version.
     */
    private final static String PARAM_VERSION = "version";

    // Constants for help intents.
    private static final String EXTRA_CONTEXT = "EXTRA_CONTEXT";
    private static final String EXTRA_THEME = "EXTRA_THEME";
    private static final String EXTRA_PRIMARY_COLOR = "EXTRA_PRIMARY_COLOR";
    private static final String EXTRA_BACKUP_URI = "EXTRA_BACKUP_URI";

    /**
     * Cached version code to prevent repeated calls to the package manager.
     */
    private static String sCachedVersionCode = null;

    /** Static helper that is not instantiable*/
    private HelpUtils() { }

    public static boolean prepareHelpMenuItem(Activity activity, Menu menu, String helpUri,
            String backupContext) {
        MenuItem helpItem = menu.add(0, MENU_HELP, 0, R.string.help_feedback_label);
        helpItem.setIcon(R.drawable.ic_help_actionbar);
        return prepareHelpMenuItem(activity, helpItem, helpUri, backupContext);
    }

    public static boolean prepareHelpMenuItem(Activity activity, Menu menu, int helpUriResource,
            String backupContext) {
        MenuItem helpItem = menu.add(0, MENU_HELP, 0, R.string.help_feedback_label);
        helpItem.setIcon(R.drawable.ic_help_actionbar);
        return prepareHelpMenuItem(activity, helpItem, activity.getString(helpUriResource),
                backupContext);
    }

    /**
     * Prepares the help menu item by doing the following.
     * - If the helpUrlString is empty or null, the help menu item is made invisible.
     * - Otherwise, this makes the help menu item visible and sets the intent for the help menu
     *   item to view the URL.
     *
     * @return returns whether the help menu item has been made visible.
     */
    public static boolean prepareHelpMenuItem(final Activity activity, MenuItem helpMenuItem,
            String helpUriString, String backupContext) {
        if (Global.getInt(activity.getContentResolver(), Global.DEVICE_PROVISIONED, 0) == 0) {
            return false;
        }
        if (TextUtils.isEmpty(helpUriString)) {
            // The help url string is empty or null, so set the help menu item to be invisible.
            helpMenuItem.setVisible(false);

            // return that the help menu item is not visible (i.e. false)
            return false;
        } else {
            final Intent intent = getHelpIntent(activity, helpUriString, backupContext);

            // Set the intent to the help menu item, show the help menu item in the overflow
            // menu, and make it visible.
            if (intent != null) {
                helpMenuItem.setOnMenuItemClickListener(new OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        MetricsLogger.action(activity,
                            MetricsEvent.ACTION_SETTING_HELP_AND_FEEDBACK,
                            intent.getStringExtra(EXTRA_CONTEXT));
                        try {
                            activity.startActivityForResult(intent, 0);
                        } catch (ActivityNotFoundException exc) {
                            Log.e(TAG, "No activity found for intent: " + intent);
                        }
                        return true;
                    }
                });
                helpMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                helpMenuItem.setVisible(true);
            } else {
                helpMenuItem.setVisible(false);
                return false;
            }

            // return that the help menu item is visible (i.e., true)
            return true;
        }
    }

    public static Intent getHelpIntent(Context context, String helpUriString,
            String backupContext) {
        if (Global.getInt(context.getContentResolver(), Global.DEVICE_PROVISIONED, 0) == 0) {
            return null;
        }
        // Try to handle as Intent Uri, otherwise just treat as Uri.
        try {
            Intent intent = Intent.parseUri(helpUriString,
                    Intent.URI_ANDROID_APP_SCHEME | Intent.URI_INTENT_SCHEME);
            addIntentParameters(context, intent, backupContext, true /* sendPackageName */);
            ComponentName component = intent.resolveActivity(context.getPackageManager());
            if (component != null) {
                return intent;
            } else if (intent.hasExtra(EXTRA_BACKUP_URI)) {
                // This extra contains a backup URI for when the intent isn't available.
                return getHelpIntent(context, intent.getStringExtra(EXTRA_BACKUP_URI),
                        backupContext);
            } else {
                return null;
            }
        } catch (URISyntaxException e) {
        }
        // The help url string exists, so first add in some extra query parameters.
        final Uri fullUri = uriWithAddedParameters(context, Uri.parse(helpUriString));

        // Then, create an intent that will be fired when the user
        // selects this help menu item.
        Intent intent = new Intent(Intent.ACTION_VIEW, fullUri);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        return intent;
    }

    public static void addIntentParameters(Context context, Intent intent, String backupContext,
            boolean sendPackageName) {
        if (!intent.hasExtra(EXTRA_CONTEXT)) {
            // Insert some context if none exists.
            intent.putExtra(EXTRA_CONTEXT, backupContext);
        }

        Resources resources = context.getResources();
        boolean includePackageName =
                resources.getBoolean(com.android.internal.R.bool.config_sendPackageName);

        if (sendPackageName && includePackageName) {
            String[] packageNameKey =
                    {resources.getString(com.android.internal.R.string.config_helpPackageNameKey)};
            String[] packageNameValue =
                    {resources.getString(
                            com.android.internal.R.string.config_helpPackageNameValue)};
            String helpIntentExtraKey =
                    resources.getString(com.android.internal.R.string.config_helpIntentExtraKey);
            String helpIntentNameKey =
                    resources.getString(com.android.internal.R.string.config_helpIntentNameKey);
            String feedbackIntentExtraKey =
                    resources.getString(
                            com.android.internal.R.string.config_feedbackIntentExtraKey);
            String feedbackIntentNameKey =
                    resources.getString(com.android.internal.R.string.config_feedbackIntentNameKey);
            intent.putExtra(helpIntentExtraKey, packageNameKey);
            intent.putExtra(helpIntentNameKey, packageNameValue);
            intent.putExtra(feedbackIntentExtraKey, packageNameKey);
            intent.putExtra(feedbackIntentNameKey, packageNameValue);
        }
        intent.putExtra(EXTRA_THEME, 0 /* Light theme */);
        TypedArray array = context.obtainStyledAttributes(new int[]{android.R.attr.colorPrimary});
        intent.putExtra(EXTRA_PRIMARY_COLOR, array.getColor(0, 0));
        array.recycle();
    }

    /**
     * Adds two query parameters into the Uri, namely the language code and the version code
     * of the app's package as gotten via the context.
     * @return the uri with added query parameters
     */
    private static Uri uriWithAddedParameters(Context context, Uri baseUri) {
        Uri.Builder builder = baseUri.buildUpon();

        // Add in the preferred language
        builder.appendQueryParameter(PARAM_LANGUAGE_CODE, Locale.getDefault().toString());

        // Add in the package version code
        if (sCachedVersionCode == null) {
            // There is no cached version code, so try to get it from the package manager.
            try {
                // cache the version code
                PackageInfo info = context.getPackageManager().getPackageInfo(
                        context.getPackageName(), 0);
                sCachedVersionCode = Long.toString(info.getLongVersionCode());

                // append the version code to the uri
                builder.appendQueryParameter(PARAM_VERSION, sCachedVersionCode);
            } catch (NameNotFoundException e) {
                // Cannot find the package name, so don't add in the version parameter
                // This shouldn't happen.
                Log.wtf(TAG, "Invalid package name for context", e);
            }
        } else {
            builder.appendQueryParameter(PARAM_VERSION, sCachedVersionCode);
        }

        // Build the full uri and return it
        return builder.build();
    }
}
