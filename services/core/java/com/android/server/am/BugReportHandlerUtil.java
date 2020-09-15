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

import static android.app.AppOpsManager.OP_NONE;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.app.Activity;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.BugreportManager;
import android.os.BugreportParams;
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
    private static final String INTENT_GET_BUGREPORT_HANDLER_RESPONSE =
            "com.android.internal.intent.action.GET_BUGREPORT_HANDLER_RESPONSE";

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
     * Launches a bugreport-allowlisted app to handle a bugreport.
     *
     * <p>Allows a bug report handler app to take bugreports on the user's behalf. The handler can
     * be predefined in the config, meant to be launched with the primary user. The user can
     * override this with a different (or same) handler app on possibly a different user. This is
     * useful for capturing bug reports from work profile, for instance.
     *
     * @param context Context
     * @return true if there is a bugreport-allowlisted app to handle a bugreport, or false
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
            // If user chooses which profile and which bugreport-allowlisted app in that
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

        if (getBugReportHandlerAppResponseReceivers(context, handlerApp, handlerUser).isEmpty()) {
            // Just try to launch bugreport handler app to handle bugreport request
            // because the bugreport handler app is old and not support to provide response to
            // let BugReportHandlerUtil know it is available or not.
            launchBugReportHandlerApp(context, handlerApp, handlerUser);
            return true;
        }

        Slog.i(TAG, "Getting response from bug report handler app: " + handlerApp);
        Intent intent = new Intent(INTENT_GET_BUGREPORT_HANDLER_RESPONSE);
        intent.setPackage(handlerApp);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        final long identity = Binder.clearCallingIdentity();
        try {
            // Handler app's BroadcastReceiver should call setResultCode(Activity.RESULT_OK) to
            // let BugreportHandlerResponseBroadcastReceiver know the handler app is available.
            context.sendOrderedBroadcastAsUser(intent,
                    UserHandle.of(handlerUser),
                    android.Manifest.permission.DUMP,
                    OP_NONE, /* options= */ null,
                    new BugreportHandlerResponseBroadcastReceiver(handlerApp, handlerUser),
                    /* scheduler= */ null,
                    Activity.RESULT_CANCELED,
                    /* initialData= */ null,
                    /* initialExtras= */ null);
        } catch (RuntimeException e) {
            Slog.e(TAG, "Error while trying to get response from bug report handler app.", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return true;
    }

    private static void launchBugReportHandlerApp(Context context, String handlerApp,
            int handlerUser) {
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
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
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
        // Verify the app is bugreport-allowlisted
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

    private static List<ResolveInfo> getBugReportHandlerAppResponseReceivers(Context context,
            String handlerApp, int handlerUser) {
        // Use the app package and the user id to retrieve the receiver that can provide response
        Intent intent = new Intent(INTENT_GET_BUGREPORT_HANDLER_RESPONSE);
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

    private static class BugreportHandlerResponseBroadcastReceiver extends BroadcastReceiver {
        private final String handlerApp;
        private final int handlerUser;

        BugreportHandlerResponseBroadcastReceiver(String handlerApp, int handlerUser) {
            this.handlerApp = handlerApp;
            this.handlerUser = handlerUser;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (getResultCode() == Activity.RESULT_OK) {
                // Try to launch bugreport handler app to handle bugreport request because the
                // bugreport handler app is available.
                launchBugReportHandlerApp(context, handlerApp, handlerUser);
                return;
            }

            Slog.w(TAG, "Request bug report because no response from handler app.");
            BugreportManager bugreportManager = context.getSystemService(BugreportManager.class);
            bugreportManager.requestBugreport(
                    new BugreportParams(BugreportParams.BUGREPORT_MODE_INTERACTIVE),
                    /* shareTitle= */null, /* shareDescription= */ null);
        }
    }
}
