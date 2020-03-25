/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.am;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;

import com.android.server.SystemConfig;

import java.util.List;

/**
 * Static utility methods related to BugReportHandler.
 */
public final class BugReportHandlerUtil {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "BugReportHandlerUtil" : TAG_AM;
    private static final String SHELL_APP_PACKAGE = "com.android.shell";
    private static final String INTENT_BUGREPORT_REQUESTED =
            "com.android.internal.intent.action.BUGREPORT_REQUESTED";

    /**
     * Check is BugReportHandler enabled on the device.
     *
     * @param context Context
     * @return true if BugReportHandler is enabled, or false otherwise
     */
    static boolean isBugReportHandlerEnabled(Context context) {
        return context.getResources().getBoolean(
                com.android.internal.R.bool.config_bugReportHandlerEnabled);
    }

    /**
     * Launches a bugreport-whitelisted app to handle a bugreport.
     *
     * <p>Allows a bug report handler app to take bugreports on the user's behalf. The handler can
     * be predefined in the config, meant to be launched with the primary user. The user can
     * override this with a different (or same) handler app on possibly a different user. This is
     * useful for capturing bug reports from work profile, for instance.
     *
     * @param context Context
     * @return true if there is a bugreport-whitelisted app to handle a bugreport, or false
     * otherwise
     */
    static boolean launchBugReportHandlerApp(Context context) {
        if (!isBugReportHandlerEnabled(context)) {
            return false;
        }

        String handlerApp = getCustomBugReportHandlerApp(context);
        if (isShellApp(handlerApp)) {
            return false;
        }

        int handlerUser = getCustomBugReportHandlerUser(context);
        if (!isValidBugReportHandlerApp(handlerApp)) {
            handlerApp = getDefaultBugReportHandlerApp(context);
            handlerUser = UserHandle.USER_SYSTEM;
        } else if (getBugReportHandlerAppReceivers(context, handlerApp, handlerUser).isEmpty()) {
            // It looks like the settings are outdated, reset outdated settings.
            //
            // i.e.
            // If user chooses which profile and which bugreport-whitelisted app in that
            // profile to handle a bugreport, then user remove the profile.
            // === RESULT ===
            // The chosen bugreport handler app is outdated because the profile is removed,
            // so reset the chosen app and profile
            handlerApp = getDefaultBugReportHandlerApp(context);
            handlerUser = UserHandle.USER_SYSTEM;
            resetCustomBugreportHandlerAppAndUser(context);
        }

        if (isShellApp(handlerApp) || !isValidBugReportHandlerApp(handlerApp)
                || getBugReportHandlerAppReceivers(context, handlerApp, handlerUser).isEmpty()) {
            return false;
        }

        Slog.i(TAG, "Launching bug report handler app: " + handlerApp);
        Intent intent = new Intent(INTENT_BUGREPORT_REQUESTED);
        intent.setPackage(handlerApp);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        // Send broadcast to the receiver while allowing starting activity from background
        final BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setBackgroundActivityStartsAllowed(true);
        final long identity = Binder.clearCallingIdentity();
        try {
            context.sendBroadcastAsUser(intent, UserHandle.of(handlerUser),
                    android.Manifest.permission.DUMP,
                    options.toBundle());
        } catch (RuntimeException e) {
            Slog.e(TAG, "Error while trying to launch bugreport handler app.", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return true;
    }

    private static String getCustomBugReportHandlerApp(Context context) {
        // Get the package of custom bugreport handler app
        return Settings.Global.getString(context.getContentResolver(),
                Settings.Global.CUSTOM_BUGREPORT_HANDLER_APP);
    }

    private static int getCustomBugReportHandlerUser(Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.CUSTOM_BUGREPORT_HANDLER_USER, UserHandle.USER_NULL);
    }

    private static boolean isShellApp(String app) {
        return SHELL_APP_PACKAGE.equals(app);
    }

    private static boolean isValidBugReportHandlerApp(String app) {
        return !TextUtils.isEmpty(app) && isBugreportWhitelistedApp(app);
    }

    private static boolean isBugreportWhitelistedApp(String app) {
        // Verify the app is bugreport-whitelisted
        final ArraySet<String> whitelistedApps = SystemConfig.getInstance()
                .getBugreportWhitelistedPackages();
        return whitelistedApps.contains(app);
    }

    private static List<ResolveInfo> getBugReportHandlerAppReceivers(Context context,
            String handlerApp, int handlerUser) {
        // Use the app package and the user id to retrieve the receiver that can handle a
        // broadcast of the intent.
        Intent intent = new Intent(INTENT_BUGREPORT_REQUESTED);
        intent.setPackage(handlerApp);
        return context.getPackageManager()
                .queryBroadcastReceiversAsUser(intent, PackageManager.MATCH_SYSTEM_ONLY,
                        handlerUser);
    }

    private static String getDefaultBugReportHandlerApp(Context context) {
        return context.getResources().getString(
                com.android.internal.R.string.config_defaultBugReportHandlerApp);
    }

    private static void resetCustomBugreportHandlerAppAndUser(Context context) {
        final long identity = Binder.clearCallingIdentity();
        try {
            Settings.Global.putString(context.getContentResolver(),
                    Settings.Global.CUSTOM_BUGREPORT_HANDLER_APP,
                    getDefaultBugReportHandlerApp(context));
            Settings.Global.putInt(context.getContentResolver(),
                    Settings.Global.CUSTOM_BUGREPORT_HANDLER_USER, UserHandle.USER_SYSTEM);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
