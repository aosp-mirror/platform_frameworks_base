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

package com.android.internal.telephony;

import android.Manifest.permission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Telephony;
import android.provider.Telephony.Sms.Intents;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.SparseArray;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Class for managing the primary application that we will deliver SMS/MMS messages to
 *
 * {@hide}
 */
public final class SmsApplication {
    static final String LOG_TAG = "SmsApplication";
    public static final String PHONE_PACKAGE_NAME = "com.android.phone";
    public static final String MMS_SERVICE_PACKAGE_NAME = "com.android.mms.service";
    public static final String TELEPHONY_PROVIDER_PACKAGE_NAME = "com.android.providers.telephony";

    private static final String SCHEME_SMS = "sms";
    private static final String SCHEME_SMSTO = "smsto";
    private static final String SCHEME_MMS = "mms";
    private static final String SCHEME_MMSTO = "mmsto";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_MULTIUSER = false;

    private static final String[] DEFAULT_APP_EXCLUSIVE_APPOPS = {
            AppOpsManager.OPSTR_READ_SMS,
            AppOpsManager.OPSTR_WRITE_SMS,
            AppOpsManager.OPSTR_RECEIVE_SMS,
            AppOpsManager.OPSTR_RECEIVE_WAP_PUSH,
            AppOpsManager.OPSTR_SEND_SMS,
            AppOpsManager.OPSTR_READ_CELL_BROADCASTS
    };

    private static SmsPackageMonitor sSmsPackageMonitor = null;

    private static SmsRoleListener sSmsRoleListener = null;

    public static class SmsApplicationData {
        /**
         * Name of this SMS app for display.
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        private String mApplicationName;

        /**
         * Package name for this SMS app.
         */
        public String mPackageName;

        /**
         * The class name of the SMS_DELIVER_ACTION receiver in this app.
         */
        private String mSmsReceiverClass;

        /**
         * The class name of the WAP_PUSH_DELIVER_ACTION receiver in this app.
         */
        private String mMmsReceiverClass;

        /**
         * The class name of the ACTION_RESPOND_VIA_MESSAGE intent in this app.
         */
        private String mRespondViaMessageClass;

        /**
         * The class name of the ACTION_SENDTO intent in this app.
         */
        private String mSendToClass;

        /**
         * The class name of the ACTION_DEFAULT_SMS_PACKAGE_CHANGED receiver in this app.
         */
        private String mSmsAppChangedReceiverClass;

        /**
         * The class name of the ACTION_EXTERNAL_PROVIDER_CHANGE receiver in this app.
         */
        private String mProviderChangedReceiverClass;

        /**
         * The class name of the SIM_FULL_ACTION receiver in this app.
         */
        private String mSimFullReceiverClass;

        /**
         * The user-id for this application
         */
        private int mUid;

        /**
         * Returns true if this SmsApplicationData is complete (all intents handled).
         * @return
         */
        public boolean isComplete() {
            return (mSmsReceiverClass != null && mMmsReceiverClass != null
                    && mRespondViaMessageClass != null && mSendToClass != null);
        }

        public SmsApplicationData(String packageName, int uid) {
            mPackageName = packageName;
            mUid = uid;
        }

        public String getApplicationName(Context context) {
            if (mApplicationName == null) {
                PackageManager pm = context.getPackageManager();
                ApplicationInfo appInfo;
                try {
                    appInfo = pm.getApplicationInfoAsUser(mPackageName, 0,
                            UserHandle.getUserHandleForUid(mUid));
                } catch (NameNotFoundException e) {
                    return null;
                }
                if (appInfo != null) {
                    CharSequence label  = pm.getApplicationLabel(appInfo);
                    mApplicationName = (label == null) ? null : label.toString();
                }
            }
            return mApplicationName;
        }

        @Override
        public String toString() {
            return " mPackageName: " + mPackageName
                    + " mSmsReceiverClass: " + mSmsReceiverClass
                    + " mMmsReceiverClass: " + mMmsReceiverClass
                    + " mRespondViaMessageClass: " + mRespondViaMessageClass
                    + " mSendToClass: " + mSendToClass
                    + " mSmsAppChangedClass: " + mSmsAppChangedReceiverClass
                    + " mProviderChangedReceiverClass: " + mProviderChangedReceiverClass
                    + " mSimFullReceiverClass: " + mSimFullReceiverClass
                    + " mUid: " + mUid;
        }
    }

    /**
     * Returns the userId of the current process, if called from a system app,
     * otherwise it returns the caller's userId
     * @return userId of the caller.
     */
    private static int getIncomingUserId() {
        int contextUserId = UserHandle.myUserId();
        final int callingUid = Binder.getCallingUid();
        if (DEBUG_MULTIUSER) {
            Log.i(LOG_TAG, "getIncomingUserHandle caller=" + callingUid + ", myuid="
                    + android.os.Process.myUid());
        }
        if (UserHandle.getAppId(callingUid)
                < android.os.Process.FIRST_APPLICATION_UID) {
            return contextUserId;
        } else {
            return UserHandle.getUserHandleForUid(callingUid).getIdentifier();
        }
    }

    /**
     * Returns the userHandle of the current process, if called from a system app,
     * otherwise it returns the caller's userHandle
     * @return userHandle of the caller.
     */
    private static UserHandle getIncomingUserHandle() {
        return UserHandle.of(getIncomingUserId());
    }

    /**
     * Returns the list of available SMS apps defined as apps that are registered for both the
     * SMS_RECEIVED_ACTION (SMS) and WAP_PUSH_RECEIVED_ACTION (MMS) broadcasts (and their broadcast
     * receivers are enabled)
     *
     * Requirements to be an SMS application:
     * Implement SMS_DELIVER_ACTION broadcast receiver.
     * Require BROADCAST_SMS permission.
     *
     * Implement WAP_PUSH_DELIVER_ACTION broadcast receiver.
     * Require BROADCAST_WAP_PUSH permission.
     *
     * Implement RESPOND_VIA_MESSAGE intent.
     * Support smsto Uri scheme.
     * Require SEND_RESPOND_VIA_MESSAGE permission.
     *
     * Implement ACTION_SENDTO intent.
     * Support smsto Uri scheme.
     */
    @UnsupportedAppUsage
    public static Collection<SmsApplicationData> getApplicationCollection(Context context) {
        return getApplicationCollectionAsUser(context, getIncomingUserId());
    }

    /**
     * Same as {@link #getApplicationCollection} but it takes a target user ID.
     */
    public static Collection<SmsApplicationData> getApplicationCollectionAsUser(Context context,
            int userId) {
        final long token = Binder.clearCallingIdentity();
        try {
            return getApplicationCollectionInternal(context, userId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static Collection<SmsApplicationData> getApplicationCollectionInternal(
            Context context, int userId) {
        PackageManager packageManager = context.getPackageManager();
        UserHandle userHandle = UserHandle.of(userId);

        // Get the list of apps registered for SMS
        Intent intent = new Intent(Intents.SMS_DELIVER_ACTION);
        if (DEBUG) {
            intent.addFlags(Intent.FLAG_DEBUG_LOG_RESOLUTION);
        }
        List<ResolveInfo> smsReceivers = packageManager.queryBroadcastReceiversAsUser(intent,
                PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                userHandle);

        HashMap<String, SmsApplicationData> receivers = new HashMap<String, SmsApplicationData>();

        // Add one entry to the map for every sms receiver (ignoring duplicate sms receivers)
        for (ResolveInfo resolveInfo : smsReceivers) {
            final ActivityInfo activityInfo = resolveInfo.activityInfo;
            if (activityInfo == null) {
                continue;
            }
            if (!permission.BROADCAST_SMS.equals(activityInfo.permission)) {
                continue;
            }
            final String packageName = activityInfo.packageName;
            if (!receivers.containsKey(packageName)) {
                final SmsApplicationData smsApplicationData = new SmsApplicationData(packageName,
                        activityInfo.applicationInfo.uid);
                smsApplicationData.mSmsReceiverClass = activityInfo.name;
                receivers.put(packageName, smsApplicationData);
            }
        }

        // Update any existing entries with mms receiver class
        intent = new Intent(Intents.WAP_PUSH_DELIVER_ACTION);
        intent.setDataAndType(null, "application/vnd.wap.mms-message");
        List<ResolveInfo> mmsReceivers = packageManager.queryBroadcastReceiversAsUser(intent,
                PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                userHandle);
        for (ResolveInfo resolveInfo : mmsReceivers) {
            final ActivityInfo activityInfo = resolveInfo.activityInfo;
            if (activityInfo == null) {
                continue;
            }
            if (!permission.BROADCAST_WAP_PUSH.equals(activityInfo.permission)) {
                continue;
            }
            final String packageName = activityInfo.packageName;
            final SmsApplicationData smsApplicationData = receivers.get(packageName);
            if (smsApplicationData != null) {
                smsApplicationData.mMmsReceiverClass = activityInfo.name;
            }
        }

        // Update any existing entries with respond via message intent class.
        intent = new Intent(TelephonyManager.ACTION_RESPOND_VIA_MESSAGE,
                Uri.fromParts(SCHEME_SMSTO, "", null));
        List<ResolveInfo> respondServices = packageManager.queryIntentServicesAsUser(intent,
                PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                UserHandle.of(userId));
        for (ResolveInfo resolveInfo : respondServices) {
            final ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            if (serviceInfo == null) {
                continue;
            }
            if (!permission.SEND_RESPOND_VIA_MESSAGE.equals(serviceInfo.permission)) {
                continue;
            }
            final String packageName = serviceInfo.packageName;
            final SmsApplicationData smsApplicationData = receivers.get(packageName);
            if (smsApplicationData != null) {
                smsApplicationData.mRespondViaMessageClass = serviceInfo.name;
            }
        }

        // Update any existing entries with supports send to.
        intent = new Intent(Intent.ACTION_SENDTO,
                Uri.fromParts(SCHEME_SMSTO, "", null));
        List<ResolveInfo> sendToActivities = packageManager.queryIntentActivitiesAsUser(intent,
                PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                userHandle);
        for (ResolveInfo resolveInfo : sendToActivities) {
            final ActivityInfo activityInfo = resolveInfo.activityInfo;
            if (activityInfo == null) {
                continue;
            }
            final String packageName = activityInfo.packageName;
            final SmsApplicationData smsApplicationData = receivers.get(packageName);
            if (smsApplicationData != null) {
                smsApplicationData.mSendToClass = activityInfo.name;
            }
        }

        // Update any existing entries with the default sms changed handler.
        intent = new Intent(Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED);
        List<ResolveInfo> smsAppChangedReceivers =
                packageManager.queryBroadcastReceiversAsUser(intent,
                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userHandle);
        if (DEBUG_MULTIUSER) {
            Log.i(LOG_TAG, "getApplicationCollectionInternal smsAppChangedActivities=" +
                    smsAppChangedReceivers);
        }
        for (ResolveInfo resolveInfo : smsAppChangedReceivers) {
            final ActivityInfo activityInfo = resolveInfo.activityInfo;
            if (activityInfo == null) {
                continue;
            }
            final String packageName = activityInfo.packageName;
            final SmsApplicationData smsApplicationData = receivers.get(packageName);
            if (DEBUG_MULTIUSER) {
                Log.i(LOG_TAG, "getApplicationCollectionInternal packageName=" +
                        packageName + " smsApplicationData: " + smsApplicationData +
                        " activityInfo.name: " + activityInfo.name);
            }
            if (smsApplicationData != null) {
                smsApplicationData.mSmsAppChangedReceiverClass = activityInfo.name;
            }
        }

        // Update any existing entries with the external provider changed handler.
        intent = new Intent(Telephony.Sms.Intents.ACTION_EXTERNAL_PROVIDER_CHANGE);
        List<ResolveInfo> providerChangedReceivers =
                packageManager.queryBroadcastReceiversAsUser(intent,
                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userHandle);
        if (DEBUG_MULTIUSER) {
            Log.i(LOG_TAG, "getApplicationCollectionInternal providerChangedActivities=" +
                    providerChangedReceivers);
        }
        for (ResolveInfo resolveInfo : providerChangedReceivers) {
            final ActivityInfo activityInfo = resolveInfo.activityInfo;
            if (activityInfo == null) {
                continue;
            }
            final String packageName = activityInfo.packageName;
            final SmsApplicationData smsApplicationData = receivers.get(packageName);
            if (DEBUG_MULTIUSER) {
                Log.i(LOG_TAG, "getApplicationCollectionInternal packageName=" +
                        packageName + " smsApplicationData: " + smsApplicationData +
                        " activityInfo.name: " + activityInfo.name);
            }
            if (smsApplicationData != null) {
                smsApplicationData.mProviderChangedReceiverClass = activityInfo.name;
            }
        }

        // Update any existing entries with the sim full handler.
        intent = new Intent(Intents.SIM_FULL_ACTION);
        List<ResolveInfo> simFullReceivers =
                packageManager.queryBroadcastReceiversAsUser(intent,
                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userHandle);
        if (DEBUG_MULTIUSER) {
            Log.i(LOG_TAG, "getApplicationCollectionInternal simFullReceivers="
                    + simFullReceivers);
        }
        for (ResolveInfo resolveInfo : simFullReceivers) {
            final ActivityInfo activityInfo = resolveInfo.activityInfo;
            if (activityInfo == null) {
                continue;
            }
            final String packageName = activityInfo.packageName;
            final SmsApplicationData smsApplicationData = receivers.get(packageName);
            if (DEBUG_MULTIUSER) {
                Log.i(LOG_TAG, "getApplicationCollectionInternal packageName="
                        + packageName + " smsApplicationData: " + smsApplicationData
                        + " activityInfo.name: " + activityInfo.name);
            }
            if (smsApplicationData != null) {
                smsApplicationData.mSimFullReceiverClass = activityInfo.name;
            }
        }

        // Remove any entries for which we did not find all required intents.
        for (ResolveInfo resolveInfo : smsReceivers) {
            final ActivityInfo activityInfo = resolveInfo.activityInfo;
            if (activityInfo == null) {
                continue;
            }
            final String packageName = activityInfo.packageName;
            final SmsApplicationData smsApplicationData = receivers.get(packageName);
            if (smsApplicationData != null) {
                if (!smsApplicationData.isComplete()) {
                    receivers.remove(packageName);
                }
            }
        }
        return receivers.values();
    }

    /**
     * Checks to see if we have a valid installed SMS application for the specified package name
     * @return Data for the specified package name or null if there isn't one
     */
    public static SmsApplicationData getApplicationForPackage(
            Collection<SmsApplicationData> applications, String packageName) {
        if (packageName == null) {
            return null;
        }
        // Is there an entry in the application list for the specified package?
        for (SmsApplicationData application : applications) {
            if (application.mPackageName.contentEquals(packageName)) {
                return application;
            }
        }
        return null;
    }

    /**
     * Get the application we will use for delivering SMS/MMS messages.
     *
     * We return the preferred sms application with the following order of preference:
     * (1) User selected SMS app (if selected, and if still valid)
     * (2) Android Messaging (if installed)
     * (3) The currently configured highest priority broadcast receiver
     * (4) Null
     */
    private static SmsApplicationData getApplication(Context context, boolean updateIfNeeded,
            int userId) {
        TelephonyManager tm = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);
        RoleManager roleManager = (RoleManager) context.getSystemService(Context.ROLE_SERVICE);
        // (b/134400042) RoleManager might be null in unit tests running older mockito versions
        // that do not support mocking final classes.
        if (!tm.isSmsCapable() && (roleManager == null || !roleManager.isRoleAvailable(
                RoleManager.ROLE_SMS))) {
            // No phone, no SMS
            return null;
        }

        Collection<SmsApplicationData> applications = getApplicationCollectionInternal(context,
                userId);
        if (DEBUG_MULTIUSER) {
            Log.i(LOG_TAG, "getApplication userId=" + userId);
        }
        // Determine which application receives the broadcast
        String defaultApplication = getDefaultSmsPackage(context, userId);
        if (DEBUG_MULTIUSER) {
            Log.i(LOG_TAG, "getApplication defaultApp=" + defaultApplication);
        }

        SmsApplicationData applicationData = null;
        if (defaultApplication != null) {
            applicationData = getApplicationForPackage(applications, defaultApplication);
        }
        if (DEBUG_MULTIUSER) {
            Log.i(LOG_TAG, "getApplication appData=" + applicationData);
        }

        // If we found a package, make sure AppOps permissions are set up correctly
        if (applicationData != null) {
            // We can only call unsafeCheckOp if we are privileged (updateIfNeeded) or if the app we
            // are checking is for our current uid. Doing this check from the unprivileged current
            // SMS app allows us to tell the current SMS app that it is not in a good state and
            // needs to ask to be the current SMS app again to work properly.
            if (updateIfNeeded || applicationData.mUid == android.os.Process.myUid()) {
                // Verify that the SMS app has permissions
                boolean appOpsFixed =
                        tryFixExclusiveSmsAppops(context, applicationData, updateIfNeeded);
                if (!appOpsFixed) {
                    // We can not return a package if permissions are not set up correctly
                    applicationData = null;
                }
            }

            // We can only verify the phone and BT app's permissions from a privileged caller
            if (applicationData != null && updateIfNeeded) {
                // Ensure this component is still configured as the preferred activity. Usually the
                // current SMS app will already be the preferred activity - but checking whether or
                // not this is true is just as expensive as reconfiguring the preferred activity so
                // we just reconfigure every time.
                grantPermissionsToSystemApps(context);
            }
        }
        if (DEBUG_MULTIUSER) {
            Log.i(LOG_TAG, "getApplication returning appData=" + applicationData);
        }
        return applicationData;
    }

    private static String getDefaultSmsPackage(Context context, int userId) {
        // RoleManager might be null in unit tests running older mockito versions that do not
        // support mocking final classes.
        RoleManager roleManager = context.getSystemService(RoleManager.class);
        if (roleManager == null) {
            return "";
        }
        return roleManager.getSmsRoleHolder(userId);
    }

    /**
     * Grants various permissions and appops, e.g. on sms app change
     */
    public static void grantPermissionsToSystemApps(Context context) {
        PackageManager packageManager = context.getPackageManager();
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);

        final String bluetoothPackageName = context.getResources()
                .getString(com.android.internal.R.string.config_systemBluetoothStack);
        // Assign permission to special system apps
        assignExclusiveSmsPermissionsToSystemApp(context, packageManager, appOps,
                PHONE_PACKAGE_NAME, true);
        assignExclusiveSmsPermissionsToSystemApp(context, packageManager, appOps,
                bluetoothPackageName, false);
        assignExclusiveSmsPermissionsToSystemApp(context, packageManager, appOps,
                MMS_SERVICE_PACKAGE_NAME, true);
        assignExclusiveSmsPermissionsToSystemApp(context, packageManager, appOps,
                TELEPHONY_PROVIDER_PACKAGE_NAME, true);
        // CellbroadcastReceiver is a mainline module thus skip signature match.
        assignExclusiveSmsPermissionsToSystemApp(context, packageManager, appOps,
                CellBroadcastUtils.getDefaultCellBroadcastReceiverPackageName(context), false);

        // Give AppOps permission to UID 1001 which contains multiple
        // apps, all of them should be able to write to telephony provider.
        // This is to allow the proxy package permission check in telephony provider
        // to pass.
        for (String opStr : DEFAULT_APP_EXCLUSIVE_APPOPS) {
            appOps.setUidMode(opStr, Process.PHONE_UID, AppOpsManager.MODE_ALLOWED);
        }
    }

    private static boolean tryFixExclusiveSmsAppops(Context context,
            SmsApplicationData applicationData, boolean updateIfNeeded) {
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        for (String opStr : DEFAULT_APP_EXCLUSIVE_APPOPS) {
            int mode = appOps.unsafeCheckOp(opStr, applicationData.mUid,
                    applicationData.mPackageName);
            if (mode != AppOpsManager.MODE_ALLOWED) {
                Log.e(LOG_TAG, applicationData.mPackageName + " lost "
                        + opStr + ": "
                        + (updateIfNeeded ? " (fixing)" : " (no permission to fix)"));
                if (updateIfNeeded) {
                    appOps.setUidMode(opStr, applicationData.mUid, AppOpsManager.MODE_ALLOWED);
                } else {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Sets the specified package as the default SMS/MMS application. The caller of this method
     * needs to have permission to set AppOps and write to secure settings.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static void setDefaultApplication(String packageName, Context context) {
        setDefaultApplicationAsUser(packageName, context, getIncomingUserId());
    }

    /**
     * Same as {@link #setDefaultApplication} but takes a target user id.
     */
    public static void setDefaultApplicationAsUser(String packageName, Context context,
            int userId) {
        TelephonyManager tm = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
        RoleManager roleManager = (RoleManager) context.getSystemService(Context.ROLE_SERVICE);
        // (b/134400042) RoleManager might be null in unit tests running older mockito versions
        // that do not support mocking final classes.
        if (!tm.isSmsCapable() && (roleManager == null || !roleManager.isRoleAvailable(
                RoleManager.ROLE_SMS))) {
            // No phone, no SMS
            return;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            setDefaultApplicationInternal(packageName, context, userId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static void setDefaultApplicationInternal(String packageName, Context context,
            int userId) {
        final UserHandle userHandle = UserHandle.of(userId);

        // Get old package name
        String oldPackageName = getDefaultSmsPackage(context, userId);

        if (DEBUG_MULTIUSER) {
            Log.i(LOG_TAG, "setDefaultApplicationInternal old=" + oldPackageName +
                    " new=" + packageName);
        }

        if (packageName != null && oldPackageName != null && packageName.equals(oldPackageName)) {
            // No change
            return;
        }

        // We only make the change if the new package is valid
        PackageManager packageManager =
                context.createContextAsUser(userHandle, 0).getPackageManager();
        Collection<SmsApplicationData> applications = getApplicationCollectionInternal(
                context, userId);
        SmsApplicationData oldAppData = oldPackageName != null ?
                getApplicationForPackage(applications, oldPackageName) : null;
        SmsApplicationData applicationData = getApplicationForPackage(applications, packageName);
        if (applicationData != null) {
            // Ignore relevant appops for the previously configured default SMS app.
            AppOpsManager appOps = (AppOpsManager)context.getSystemService(Context.APP_OPS_SERVICE);
            if (oldPackageName != null) {
                try {
                    int uid = packageManager.getPackageInfo(oldPackageName, 0).applicationInfo.uid;
                    setExclusiveAppops(oldPackageName, appOps, uid, AppOpsManager.MODE_DEFAULT);
                } catch (NameNotFoundException e) {
                    Log.w(LOG_TAG, "Old SMS package not found: " + oldPackageName);
                }
            }

            // Update the setting.
            CompletableFuture<Void> future = new CompletableFuture<>();
            Consumer<Boolean> callback = successful -> {
                if (successful) {
                    future.complete(null);
                } else {
                    future.completeExceptionally(new RuntimeException());
                }
            };
            context.getSystemService(RoleManager.class).addRoleHolderAsUser(
                    RoleManager.ROLE_SMS, applicationData.mPackageName, 0, UserHandle.of(userId),
                    AsyncTask.THREAD_POOL_EXECUTOR, callback);
            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                Log.e(LOG_TAG, "Exception while adding sms role holder " + applicationData, e);
                return;
            }

            grantPermissionsToSystemApps(context);
        }
    }

    /**
     * Broadcast action:
     * Same as {@link Intent#ACTION_DEFAULT_SMS_PACKAGE_CHANGED} but it's implicit (e.g. sent to
     * all apps) and requires
     * {@link #PERMISSION_MONITOR_DEFAULT_SMS_PACKAGE} to receive.
     */
    public static final String ACTION_DEFAULT_SMS_PACKAGE_CHANGED_INTERNAL =
            "android.provider.action.DEFAULT_SMS_PACKAGE_CHANGED_INTERNAL";

    public static final String PERMISSION_MONITOR_DEFAULT_SMS_PACKAGE =
            "android.permission.MONITOR_DEFAULT_SMS_PACKAGE";

    /**
     * Sends broadcasts on sms app change:
     * {@link Intent#ACTION_DEFAULT_SMS_PACKAGE_CHANGED}
     * {@link #ACTION_DEFAULT_SMS_PACKAGE_CHANGED_INTERNAL}
     */
    private static void broadcastSmsAppChange(Context context,
            UserHandle userHandle, @Nullable String oldPackage, @Nullable String newPackage) {
        Collection<SmsApplicationData> apps = getApplicationCollection(context);

        broadcastSmsAppChange(context, userHandle,
                getApplicationForPackage(apps, oldPackage),
                getApplicationForPackage(apps, newPackage));
    }

    private static void broadcastSmsAppChange(Context context, UserHandle userHandle,
            @Nullable SmsApplicationData oldAppData,
            @Nullable SmsApplicationData applicationData) {
        if (DEBUG_MULTIUSER) {
            Log.i(LOG_TAG, "setDefaultApplicationInternal oldAppData=" + oldAppData);
        }
        if (oldAppData != null && oldAppData.mSmsAppChangedReceiverClass != null) {
            // Notify the old sms app that it's no longer the default
            final Intent oldAppIntent =
                    new Intent(Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED);
            final ComponentName component = new ComponentName(oldAppData.mPackageName,
                    oldAppData.mSmsAppChangedReceiverClass);
            oldAppIntent.setComponent(component);
            oldAppIntent.putExtra(Intents.EXTRA_IS_DEFAULT_SMS_APP, false);
            if (DEBUG_MULTIUSER) {
                Log.i(LOG_TAG, "setDefaultApplicationInternal old=" + oldAppData.mPackageName);
            }
            context.sendBroadcastAsUser(oldAppIntent, userHandle);
        }
        // Notify the new sms app that it's now the default (if the new sms app has a receiver
        // to handle the changed default sms intent).
        if (DEBUG_MULTIUSER) {
            Log.i(LOG_TAG, "setDefaultApplicationInternal new applicationData=" +
                    applicationData);
        }
        if (applicationData != null && applicationData.mSmsAppChangedReceiverClass != null) {
            final Intent intent =
                    new Intent(Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED);
            final ComponentName component = new ComponentName(applicationData.mPackageName,
                    applicationData.mSmsAppChangedReceiverClass);
            intent.setComponent(component);
            intent.putExtra(Intents.EXTRA_IS_DEFAULT_SMS_APP, true);
            if (DEBUG_MULTIUSER) {
                Log.i(LOG_TAG, "setDefaultApplicationInternal new=" + applicationData.mPackageName);
            }
            context.sendBroadcastAsUser(intent, userHandle);
        }

        // Send an implicit broadcast for the system server.
        // (or anyone with PERMISSION_MONITOR_DEFAULT_SMS_PACKAGE, really.)
        final Intent intent =
                new Intent(ACTION_DEFAULT_SMS_PACKAGE_CHANGED_INTERNAL);
        context.sendBroadcastAsUser(intent, userHandle,
                PERMISSION_MONITOR_DEFAULT_SMS_PACKAGE);
    }

    /**
     * Assign WRITE_SMS AppOps permission to some special system apps.
     *
     * @param context The context
     * @param packageManager The package manager instance
     * @param appOps The AppOps manager instance
     * @param packageName The package name of the system app
     * @param sigatureMatch whether to check signature match
     */
    private static void assignExclusiveSmsPermissionsToSystemApp(Context context,
            PackageManager packageManager, AppOpsManager appOps, String packageName,
            boolean sigatureMatch) {
        if (packageName == null) return;
        // First check package signature matches the caller's package signature.
        // Since this class is only used internally by the system, this check makes sure
        // the package signature matches system signature.
        if (sigatureMatch) {
            final int result = packageManager.checkSignatures(context.getPackageName(),
                    packageName);
            if (result != PackageManager.SIGNATURE_MATCH) {
                Log.e(LOG_TAG, packageName + " does not have system signature");
                return;
            }
        }

        try {
            PackageInfo info = packageManager.getPackageInfo(packageName, 0);
            int mode = appOps.unsafeCheckOp(AppOpsManager.OPSTR_WRITE_SMS, info.applicationInfo.uid,
                    packageName);
            if (mode != AppOpsManager.MODE_ALLOWED) {
                Log.w(LOG_TAG, packageName + " does not have OP_WRITE_SMS:  (fixing)");
                setExclusiveAppops(packageName, appOps, info.applicationInfo.uid,
                        AppOpsManager.MODE_ALLOWED);
            }
        } catch (NameNotFoundException e) {
            // No allowlisted system app on this device
            Log.e(LOG_TAG, "Package not found: " + packageName);
        }

    }

    private static void setExclusiveAppops(String pkg, AppOpsManager appOpsManager, int uid,
            int mode) {
        for (String opStr : DEFAULT_APP_EXCLUSIVE_APPOPS) {
            appOpsManager.setUidMode(opStr, uid, mode);
        }
    }

    /**
     * Tracks package changes and ensures that the default SMS app is always configured to be the
     * preferred activity for SENDTO sms/mms intents.
     */
    private static final class SmsPackageMonitor extends PackageChangeReceiver {
        final Context mContext;

        public SmsPackageMonitor(Context context) {
            super();
            mContext = context;
        }

        @Override
        public void onPackageDisappeared() {
            onPackageChanged();
        }

        @Override
        public void onPackageAppeared() {
            onPackageChanged();
        }

        @Override
        public void onPackageModified(String packageName) {
            onPackageChanged();
        }

        private void onPackageChanged() {
            int userId;
            try {
                userId = getSendingUser().getIdentifier();
            } catch (NullPointerException e) {
                // This should never happen in prod -- unit tests will put the receiver into a
                // unusual state where the pending result is null, which produces a NPE when calling
                // getSendingUserId. Just pretend like it's the system user for testing.
                userId = UserHandle.SYSTEM.getIdentifier();
            }
            Context userContext = mContext;
            if (userId != UserHandle.SYSTEM.getIdentifier()) {
                try {
                    userContext = mContext.createPackageContextAsUser(mContext.getPackageName(), 0,
                        UserHandle.of(userId));
                } catch (NameNotFoundException nnfe) {
                    if (DEBUG_MULTIUSER) {
                        Log.w(LOG_TAG, "Unable to create package context for user " + userId);
                    }
                }
            }
            PackageManager packageManager = userContext.getPackageManager();
            // Ensure this component is still configured as the preferred activity
            ComponentName componentName = getDefaultSendToApplication(userContext, true);
            if (componentName != null) {
                configurePreferredActivity(packageManager, componentName);
            }
        }
    }

    /**
     * Tracks SMS role changes and sends broadcasts for default SMS app change.
     */
    private static final class SmsRoleListener implements OnRoleHoldersChangedListener {
        private final Context mContext;
        private final RoleManager mRoleManager;
        private final SparseArray<String> mSmsPackageNames = new SparseArray<>();

        public SmsRoleListener(@NonNull Context context) {
            mContext = context;
            mRoleManager = context.getSystemService(RoleManager.class);
            final List<UserHandle> users = context.getSystemService(UserManager.class)
                    .getUserHandles(true);
            final int usersSize = users.size();
            for (int i = 0; i < usersSize; i++) {
                final UserHandle user = users.get(i);
                mSmsPackageNames.put(user.getIdentifier(), getSmsPackageName(user));
            }
            mRoleManager.addOnRoleHoldersChangedListenerAsUser(context.getMainExecutor(), this,
                    UserHandle.ALL);
        }

        @Override
        public void onRoleHoldersChanged(@NonNull String roleName, @NonNull UserHandle user) {
            if (!Objects.equals(roleName, RoleManager.ROLE_SMS)) {
                return;
            }
            final int userId = user.getIdentifier();
            final String newSmsPackageName = getSmsPackageName(user);
            broadcastSmsAppChange(mContext, user, mSmsPackageNames.get(userId), newSmsPackageName);
            mSmsPackageNames.put(userId, newSmsPackageName);
        }

        @Nullable
        private String getSmsPackageName(@NonNull UserHandle user) {
            final List<String> roleHolders = mRoleManager.getRoleHoldersAsUser(
                    RoleManager.ROLE_SMS, user);
            return !roleHolders.isEmpty() ? roleHolders.get(0) : null;
        }
    }

    public static void initSmsPackageMonitor(Context context) {
        sSmsPackageMonitor = new SmsPackageMonitor(context);
        sSmsPackageMonitor.register(context, context.getMainLooper(), UserHandle.ALL);
        sSmsRoleListener = new SmsRoleListener(context);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private static void configurePreferredActivity(PackageManager packageManager,
            ComponentName componentName) {
        // Add the four activity preferences we want to direct to this app.
        replacePreferredActivity(packageManager, componentName, SCHEME_SMS);
        replacePreferredActivity(packageManager, componentName, SCHEME_SMSTO);
        replacePreferredActivity(packageManager, componentName, SCHEME_MMS);
        replacePreferredActivity(packageManager, componentName, SCHEME_MMSTO);
    }

    /**
     * Updates the ACTION_SENDTO activity to the specified component for the specified scheme.
     */
    private static void replacePreferredActivity(PackageManager packageManager,
            ComponentName componentName, String scheme) {
        // Build the set of existing activities that handle this scheme
        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.fromParts(scheme, "", null));
        List<ResolveInfo> resolveInfoList = packageManager.queryIntentActivities(
                intent, PackageManager.MATCH_DEFAULT_ONLY | PackageManager.GET_RESOLVED_FILTER);

        List<ComponentName> components = resolveInfoList.stream().map(info ->
                new ComponentName(info.activityInfo.packageName, info.activityInfo.name))
                .collect(Collectors.toList());

        // Update the preferred SENDTO activity for the specified scheme
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SENDTO);
        intentFilter.addCategory(Intent.CATEGORY_DEFAULT);
        intentFilter.addDataScheme(scheme);
        packageManager.replacePreferredActivity(intentFilter,
                IntentFilter.MATCH_CATEGORY_SCHEME + IntentFilter.MATCH_ADJUSTMENT_NORMAL,
                components, componentName);
    }

    /**
     * Returns SmsApplicationData for this package if this package is capable of being set as the
     * default SMS application.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static SmsApplicationData getSmsApplicationData(String packageName, Context context) {
        Collection<SmsApplicationData> applications = getApplicationCollection(context);
        return getApplicationForPackage(applications, packageName);
    }

    /**
     * Gets the default SMS application
     * @param context context from the calling app
     * @param updateIfNeeded update the default app if there is no valid default app configured.
     * @return component name of the app and class to deliver SMS messages to
     */
    @UnsupportedAppUsage
    public static ComponentName getDefaultSmsApplication(Context context, boolean updateIfNeeded) {
        return getDefaultSmsApplicationAsUser(context, updateIfNeeded, getIncomingUserHandle());
    }

    /**
     * Gets the default SMS application on a given user
     * @param context context from the calling app
     * @param updateIfNeeded update the default app if there is no valid default app configured.
     * @param userHandle target user handle
     * if {@code null} is passed in then calling package uid is used to find out target user handle.
     * @return component name of the app and class to deliver SMS messages to
     */
    public static ComponentName getDefaultSmsApplicationAsUser(Context context,
            boolean updateIfNeeded, @Nullable UserHandle userHandle) {
        if (userHandle == null) {
            userHandle = getIncomingUserHandle();
        }

        final long token = Binder.clearCallingIdentity();
        try {
            ComponentName component = null;
            SmsApplicationData smsApplicationData = getApplication(context, updateIfNeeded,
                    userHandle.getIdentifier());
            if (smsApplicationData != null) {
                component = new ComponentName(smsApplicationData.mPackageName,
                        smsApplicationData.mSmsReceiverClass);
            }
            return component;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Gets the default MMS application
     * @param context context from the calling app
     * @param updateIfNeeded update the default app if there is no valid default app configured.
     * @return component name of the app and class to deliver MMS messages to
     */
    @UnsupportedAppUsage
    public static ComponentName getDefaultMmsApplication(Context context, boolean updateIfNeeded) {
        return getDefaultMmsApplicationAsUser(context, updateIfNeeded, getIncomingUserHandle());
    }

    /**
     * Gets the default MMS application on a given user
     * @param context context from the calling app
     * @param updateIfNeeded update the default app if there is no valid default app configured.
     * @param userHandle target user handle
     * if {@code null} is passed in then calling package uid is used to find out target user handle.
     * @return component name of the app and class to deliver MMS messages to.
     */
    public static ComponentName getDefaultMmsApplicationAsUser(Context context,
            boolean updateIfNeeded, @Nullable UserHandle userHandle) {
        if (userHandle == null) {
            userHandle = getIncomingUserHandle();
        }

        final long token = Binder.clearCallingIdentity();
        try {
            ComponentName component = null;
            SmsApplicationData smsApplicationData = getApplication(context, updateIfNeeded,
                    userHandle.getIdentifier());
            if (smsApplicationData != null) {
                component = new ComponentName(smsApplicationData.mPackageName,
                        smsApplicationData.mMmsReceiverClass);
            }
            return component;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Gets the default Respond Via Message application
     * @param context context from the calling app
     * @param updateIfNeeded update the default app if there is no valid default app configured.
     * @return component name of the app and class to direct Respond Via Message intent to
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static ComponentName getDefaultRespondViaMessageApplication(Context context,
            boolean updateIfNeeded) {
        return getDefaultRespondViaMessageApplicationAsUser(context, updateIfNeeded,
                getIncomingUserHandle());
    }

    /**
     * Gets the default Respond Via Message application on a given user
     * @param context context from the calling app
     * @param updateIfNeeded update the default app if there is no valid default app configured
     * @param userHandle target user handle
     * if {@code null} is passed in then calling package uid is used to find out target user handle.
     * @return component name of the app and class to direct Respond Via Message intent to
     */
    public static ComponentName getDefaultRespondViaMessageApplicationAsUser(Context context,
            boolean updateIfNeeded, @Nullable UserHandle userHandle) {
        if (userHandle == null) {
            userHandle = getIncomingUserHandle();
        }

        final long token = Binder.clearCallingIdentity();
        try {
            ComponentName component = null;
            SmsApplicationData smsApplicationData = getApplication(context, updateIfNeeded,
                    userHandle.getIdentifier());
            if (smsApplicationData != null) {
                component = new ComponentName(smsApplicationData.mPackageName,
                        smsApplicationData.mRespondViaMessageClass);
            }
            return component;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Gets the default Send To (smsto) application.
     * <p>
     * Caller must pass in the correct user context if calling from a singleton service.
     * @param context context from the calling app
     * @param updateIfNeeded update the default app if there is no valid default app configured.
     * @return component name of the app and class to direct SEND_TO (smsto) intent to
     */
    public static ComponentName getDefaultSendToApplication(Context context,
            boolean updateIfNeeded) {
        int userId = getIncomingUserId();

        final long token = Binder.clearCallingIdentity();
        try {
            ComponentName component = null;
            SmsApplicationData smsApplicationData = getApplication(context, updateIfNeeded,
                    userId);
            if (smsApplicationData != null) {
                component = new ComponentName(smsApplicationData.mPackageName,
                        smsApplicationData.mSendToClass);
            }
            return component;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Gets the default application that handles external changes to the SmsProvider and
     * MmsProvider.
     * @param context context from the calling app
     * @param updateIfNeeded update the default app if there is no valid default app configured.
     * @return component name of the app and class to deliver change intents to
     */
    public static ComponentName getDefaultExternalTelephonyProviderChangedApplication(
            Context context, boolean updateIfNeeded) {
        return getDefaultExternalTelephonyProviderChangedApplicationAsUser(context, updateIfNeeded,
                getIncomingUserHandle());
    }

    /**
     * Gets the default application that handles external changes to the SmsProvider and
     * MmsProvider on a given user.
     * @param context context from the calling app
     * @param updateIfNeeded update the default app if there is no valid default app configured
     * @param userHandle target user handle
     * if {@code null} is passed in then calling package uid is used to find out target user handle.
     * @return component name of the app and class to deliver change intents to.
     */
    public static ComponentName getDefaultExternalTelephonyProviderChangedApplicationAsUser(
            Context context, boolean updateIfNeeded, @Nullable UserHandle userHandle) {
        if (userHandle == null) {
            userHandle = getIncomingUserHandle();
        }

        final long token = Binder.clearCallingIdentity();
        try {
            ComponentName component = null;
            SmsApplicationData smsApplicationData = getApplication(context, updateIfNeeded,
                    userHandle.getIdentifier());
            if (smsApplicationData != null
                    && smsApplicationData.mProviderChangedReceiverClass != null) {
                component = new ComponentName(smsApplicationData.mPackageName,
                        smsApplicationData.mProviderChangedReceiverClass);
            }
            return component;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Gets the default application that handles sim full event.
     * @param context context from the calling app
     * @param updateIfNeeded update the default app if there is no valid default app configured.
     * @return component name of the app and class to deliver change intents to
     */
    public static ComponentName getDefaultSimFullApplication(
            Context context, boolean updateIfNeeded) {
        return getDefaultSimFullApplicationAsUser(context, updateIfNeeded, getIncomingUserHandle());
    }

    /**
     * Gets the default application that handles sim full event on a given user.
     * @param context context from the calling app
     * @param updateIfNeeded update the default app if there is no valid default app configured
     * @param userHandle target user handle
     * if {@code null} is passed in then calling package uid is used to find out target user handle.
     * @return component name of the app and class to deliver change intents to
     */
    public static ComponentName getDefaultSimFullApplicationAsUser(Context context,
            boolean updateIfNeeded, @Nullable UserHandle userHandle) {
        if (userHandle == null) {
            userHandle = getIncomingUserHandle();
        }

        final long token = Binder.clearCallingIdentity();
        try {
            ComponentName component = null;
            SmsApplicationData smsApplicationData = getApplication(context, updateIfNeeded,
                    userHandle.getIdentifier());
            if (smsApplicationData != null
                    && smsApplicationData.mSimFullReceiverClass != null) {
                component = new ComponentName(smsApplicationData.mPackageName,
                        smsApplicationData.mSimFullReceiverClass);
            }
            return component;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Returns whether it is required to write the SMS message to SMS database for this package.
     *
     * @param packageName the name of the package to be checked
     * @param context context from the calling app
     * @return true if it is required to write SMS message to SMS database for this package.
     *
     * <p>
     * Caller must pass in the correct user context if calling from a singleton service.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static boolean shouldWriteMessageForPackage(String packageName, Context context) {
        return !shouldWriteMessageForPackageAsUser(packageName, context, getIncomingUserHandle());
    }

    /**
     * Returns whether it is required to write the SMS message to SMS database for this package.
     *
     * @param packageName the name of the package to be checked
     * @param context context from the calling app
     * @param userHandle target user handle
     * if {@code null} is passed in then calling package uid is used to find out target user handle.
     * @return true if it is required to write SMS message to SMS database for this package.
     *
     * <p>
     * Caller must pass in the correct user context if calling from a singleton service.
     */
    public static boolean shouldWriteMessageForPackageAsUser(String packageName, Context context,
            @Nullable UserHandle userHandle) {
        return !isDefaultSmsApplicationAsUser(context, packageName, userHandle);
    }

    /**
     * Check if a package is default sms app (or equivalent, like bluetooth)
     *
     * @param context context from the calling app
     * @param packageName the name of the package to be checked
     * @return true if the package is default sms app or bluetooth
     */
    @UnsupportedAppUsage
    public static boolean isDefaultSmsApplication(Context context, String packageName) {
        return isDefaultSmsApplicationAsUser(context, packageName, getIncomingUserHandle());
    }

    /**
     * Check if a package is default sms app (or equivalent, like bluetooth) on a given user.
     *
     * @param context context from the calling app
     * @param packageName the name of the package to be checked
     * @param userHandle target user handle
     * if {@code null} is passed in then calling package uid is used to find out target user handle.
     * @return true if the package is default sms app or bluetooth
     */
    public static boolean isDefaultSmsApplicationAsUser(Context context, String packageName,
            @Nullable UserHandle userHandle) {
        if (packageName == null) {
            return false;
        }

        if (userHandle == null) {
            userHandle = getIncomingUserHandle();
        }

        ComponentName component = getDefaultSmsApplicationAsUser(context, false,
                userHandle);
        if (component == null) {
            return false;
        }

        String defaultSmsPackage = component.getPackageName();
        if (defaultSmsPackage == null) {
            return false;
        }

        String bluetoothPackageName = context.getResources()
                .getString(com.android.internal.R.string.config_systemBluetoothStack);

        if (defaultSmsPackage.equals(packageName) || bluetoothPackageName.equals(packageName)) {
            return true;
        }
        return false;
    }

    /**
     * Check if a package is default mms app (or equivalent, like bluetooth)
     *
     * @param context context from the calling app
     * @param packageName the name of the package to be checked
     * @return true if the package is default mms app or bluetooth
     */
    @UnsupportedAppUsage
    public static boolean isDefaultMmsApplication(Context context, String packageName) {
        return isDefaultMmsApplicationAsUser(context, packageName, getIncomingUserHandle());
    }

    /**
     * Check if a package is default mms app (or equivalent, like bluetooth) on a given user.
     *
     * @param context context from the calling app
     * @param packageName the name of the package to be checked
     * @param userHandle target user handle
     * if {@code null} is passed in then calling package uid is used to find out target user handle.
     * @return true if the package is default mms app or bluetooth
     */
    public static boolean isDefaultMmsApplicationAsUser(Context context, String packageName,
            @Nullable UserHandle userHandle) {
        if (packageName == null) {
            return false;
        }

        if (userHandle == null) {
            userHandle = getIncomingUserHandle();
        }

        ComponentName component = getDefaultMmsApplicationAsUser(context, false,
                userHandle);
        if (component == null) {
            return false;
        }

        String defaultMmsPackage = component.getPackageName();
        if (defaultMmsPackage == null) {
            return false;
        }

        String bluetoothPackageName = context.getResources()
                .getString(com.android.internal.R.string.config_systemBluetoothStack);

        if (defaultMmsPackage.equals(packageName)|| bluetoothPackageName.equals(packageName)) {
            return true;
        }
        return false;
    }
}