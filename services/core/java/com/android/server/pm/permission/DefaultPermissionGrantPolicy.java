/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.pm.permission;

import static android.os.Process.FIRST_APPLICATION_UID;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.DownloadManager;
import android.app.SearchManager;
import android.app.admin.DevicePolicyManager;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.media.RingtoneManager;
import android.media.midi.MidiManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.permission.PermissionManager;
import android.print.PrintManager;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Telephony.Sms.Intents;
import android.security.Credentials;
import android.speech.RecognitionService;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedXmlPullParser;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.XmlUtils;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.pm.KnownPackages;
import com.android.server.pm.permission.LegacyPermissionManagerInternal.PackagesProvider;
import com.android.server.pm.permission.LegacyPermissionManagerInternal.SyncAdapterPackagesProvider;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * This class is the policy for granting runtime permissions to
 * platform components and default handlers in the system such
 * that the device is usable out-of-the-box. For example, the
 * shell UID is a part of the system and the Phone app should
 * have phone related permission by default.
 * <p>
 * NOTE: This class is at the wrong abstraction level. It is a part of the package manager
 * service but knows about lots of higher level subsystems. The correct way to do this is
 * to have an interface defined in the package manager but have the impl next to other
 * policy stuff like PhoneWindowManager
 */
final class DefaultPermissionGrantPolicy {
    private static final String TAG = "DefaultPermGrantPolicy"; // must be <= 23 chars
    private static final boolean DEBUG = false;

    @PackageManager.ResolveInfoFlagsBits
    private static final int DEFAULT_INTENT_QUERY_FLAGS =
            PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                    | PackageManager.MATCH_UNINSTALLED_PACKAGES;

    @PackageManager.PackageInfoFlagsBits
    private static final int DEFAULT_PACKAGE_INFO_QUERY_FLAGS =
            PackageManager.MATCH_UNINSTALLED_PACKAGES
                    | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                    | PackageManager.MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS
                    | PackageManager.GET_PERMISSIONS;

    private static final String AUDIO_MIME_TYPE = "audio/mpeg";

    private static final String TAG_EXCEPTIONS = "exceptions";
    private static final String TAG_EXCEPTION = "exception";
    private static final String TAG_PERMISSION = "permission";
    private static final String ATTR_PACKAGE = "package";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_FIXED = "fixed";
    private static final String ATTR_WHITELISTED = "whitelisted";

    private static final Set<String> PHONE_PERMISSIONS = new ArraySet<>();


    static {
        PHONE_PERMISSIONS.add(Manifest.permission.READ_PHONE_STATE);
        PHONE_PERMISSIONS.add(Manifest.permission.CALL_PHONE);
        PHONE_PERMISSIONS.add(Manifest.permission.READ_CALL_LOG);
        PHONE_PERMISSIONS.add(Manifest.permission.WRITE_CALL_LOG);
        PHONE_PERMISSIONS.add(Manifest.permission.ADD_VOICEMAIL);
        PHONE_PERMISSIONS.add(Manifest.permission.USE_SIP);
        PHONE_PERMISSIONS.add(Manifest.permission.PROCESS_OUTGOING_CALLS);
    }

    private static final Set<String> CONTACTS_PERMISSIONS = new ArraySet<>();
    static {
        CONTACTS_PERMISSIONS.add(Manifest.permission.READ_CONTACTS);
        CONTACTS_PERMISSIONS.add(Manifest.permission.WRITE_CONTACTS);
        CONTACTS_PERMISSIONS.add(Manifest.permission.GET_ACCOUNTS);
    }

    private static final Set<String> ALWAYS_LOCATION_PERMISSIONS = new ArraySet<>();
    static {
        ALWAYS_LOCATION_PERMISSIONS.add(Manifest.permission.ACCESS_FINE_LOCATION);
        ALWAYS_LOCATION_PERMISSIONS.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        ALWAYS_LOCATION_PERMISSIONS.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    private static final Set<String> FOREGROUND_LOCATION_PERMISSIONS = new ArraySet<>();
    static {
        FOREGROUND_LOCATION_PERMISSIONS.add(Manifest.permission.ACCESS_FINE_LOCATION);
        FOREGROUND_LOCATION_PERMISSIONS.add(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private static final Set<String> COARSE_BACKGROUND_LOCATION_PERMISSIONS = new ArraySet<>();
    static {
        COARSE_BACKGROUND_LOCATION_PERMISSIONS.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        COARSE_BACKGROUND_LOCATION_PERMISSIONS.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    private static final Set<String> ACTIVITY_RECOGNITION_PERMISSIONS = new ArraySet<>();
    static {
        ACTIVITY_RECOGNITION_PERMISSIONS.add(Manifest.permission.ACTIVITY_RECOGNITION);
    }

    private static final Set<String> CALENDAR_PERMISSIONS = new ArraySet<>();
    static {
        CALENDAR_PERMISSIONS.add(Manifest.permission.READ_CALENDAR);
        CALENDAR_PERMISSIONS.add(Manifest.permission.WRITE_CALENDAR);
    }

    private static final Set<String> SMS_PERMISSIONS = new ArraySet<>();
    static {
        SMS_PERMISSIONS.add(Manifest.permission.SEND_SMS);
        SMS_PERMISSIONS.add(Manifest.permission.RECEIVE_SMS);
        SMS_PERMISSIONS.add(Manifest.permission.READ_SMS);
        SMS_PERMISSIONS.add(Manifest.permission.RECEIVE_WAP_PUSH);
        SMS_PERMISSIONS.add(Manifest.permission.RECEIVE_MMS);
        SMS_PERMISSIONS.add(Manifest.permission.READ_CELL_BROADCASTS);
    }

    private static final Set<String> MICROPHONE_PERMISSIONS = new ArraySet<>();
    static {
        MICROPHONE_PERMISSIONS.add(Manifest.permission.RECORD_AUDIO);
    }

    private static final Set<String> CAMERA_PERMISSIONS = new ArraySet<>();
    static {
        CAMERA_PERMISSIONS.add(Manifest.permission.CAMERA);
    }

    private static final Set<String> SENSORS_PERMISSIONS = new ArraySet<>();
    static {
        SENSORS_PERMISSIONS.add(Manifest.permission.BODY_SENSORS);
        SENSORS_PERMISSIONS.add(Manifest.permission.BODY_SENSORS_BACKGROUND);
    }

    private static final Set<String> STORAGE_PERMISSIONS = new ArraySet<>();
    static {
        STORAGE_PERMISSIONS.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        STORAGE_PERMISSIONS.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        STORAGE_PERMISSIONS.add(Manifest.permission.ACCESS_MEDIA_LOCATION);
        STORAGE_PERMISSIONS.add(Manifest.permission.READ_MEDIA_AUDIO);
        STORAGE_PERMISSIONS.add(Manifest.permission.READ_MEDIA_VIDEO);
        STORAGE_PERMISSIONS.add(Manifest.permission.READ_MEDIA_IMAGES);
    }

    private static final Set<String> NEARBY_DEVICES_PERMISSIONS = new ArraySet<>();
    static {
        NEARBY_DEVICES_PERMISSIONS.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        NEARBY_DEVICES_PERMISSIONS.add(Manifest.permission.BLUETOOTH_CONNECT);
        NEARBY_DEVICES_PERMISSIONS.add(Manifest.permission.BLUETOOTH_SCAN);
        NEARBY_DEVICES_PERMISSIONS.add(Manifest.permission.UWB_RANGING);
        NEARBY_DEVICES_PERMISSIONS.add(Manifest.permission.NEARBY_WIFI_DEVICES);
    }

    private static final Set<String> NOTIFICATION_PERMISSIONS = new ArraySet<>();
    static {
        NOTIFICATION_PERMISSIONS.add(Manifest.permission.POST_NOTIFICATIONS);
    }

    private static final int MSG_READ_DEFAULT_PERMISSION_EXCEPTIONS = 1;

    private static final String ACTION_TRACK = "com.android.fitness.TRACK";

    private final Handler mHandler;

    private PackagesProvider mLocationPackagesProvider;
    private PackagesProvider mLocationExtraPackagesProvider;
    private PackagesProvider mVoiceInteractionPackagesProvider;
    private PackagesProvider mSmsAppPackagesProvider;
    private PackagesProvider mDialerAppPackagesProvider;
    private PackagesProvider mSimCallManagerPackagesProvider;
    private PackagesProvider mUseOpenWifiAppPackagesProvider;
    private SyncAdapterPackagesProvider mSyncAdapterPackagesProvider;

    private ArrayMap<String, List<DefaultPermissionGrant>> mGrantExceptions;
    private final Context mContext;
    private final Object mLock = new Object();
    private final PackageManagerInternal mServiceInternal;

    /** Directly interact with the PackageManger */
    private final PackageManagerWrapper NO_PM_CACHE = new PackageManagerWrapper() {
        @Override
        public int getPermissionFlags(@NonNull String permission, @NonNull PackageInfo pkg,
                @NonNull UserHandle user) {
            return mContext.getPackageManager().getPermissionFlags(permission, pkg.packageName,
                    user);
        }

        @Override
        public void updatePermissionFlags(@NonNull String permission, @NonNull PackageInfo pkg,
                int flagMask, int flagValues, @NonNull UserHandle user) {
            mContext.getPackageManager().updatePermissionFlags(permission, pkg.packageName,
                    flagMask, flagValues, user);
        }

        @Override
        public void grantPermission(@NonNull String permission, @NonNull PackageInfo pkg,
                @NonNull UserHandle user) {
            mContext.getPackageManager().grantRuntimePermission(pkg.packageName, permission,
                    user);
        }

        @Override
        public void revokePermission(@NonNull String permission, @NonNull PackageInfo pkg,
                @NonNull UserHandle user) {
            mContext.getPackageManager().revokeRuntimePermission(pkg.packageName, permission,
                    user);
        }

        @Override
        public boolean isGranted(@NonNull String permission, @NonNull PackageInfo pkg,
                @NonNull UserHandle user) {
            return mContext.createContextAsUser(user, 0).getPackageManager().checkPermission(
                    permission, pkg.packageName) == PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public @Nullable PermissionInfo getPermissionInfo(@NonNull String permissionName) {
            if (permissionName == null) {
                return null;
            }

            try {
                return mContext.getPackageManager().getPermissionInfo(permissionName, 0);
            } catch (NameNotFoundException e) {
                Slog.w(TAG, "Permission not found: " + permissionName);
                return null;
            }
        }

        @Override
        public @Nullable PackageInfo getPackageInfo(@NonNull String pkg) {
            if (pkg == null) {
                return null;
            }

            try {
                return mContext.getPackageManager().getPackageInfo(pkg,
                        DEFAULT_PACKAGE_INFO_QUERY_FLAGS);
            } catch (NameNotFoundException e) {
                Slog.e(TAG, "Package not found: " + pkg);
                return null;
            }
        }
    };

    DefaultPermissionGrantPolicy(@NonNull Context context) {
        mContext = context;
        HandlerThread handlerThread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_BACKGROUND, true /*allowIo*/);
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == MSG_READ_DEFAULT_PERMISSION_EXCEPTIONS) {
                    synchronized (mLock) {
                        if (mGrantExceptions == null) {
                            mGrantExceptions = readDefaultPermissionExceptionsLocked(NO_PM_CACHE);
                        }
                    }
                }
            }
        };
        mServiceInternal = LocalServices.getService(PackageManagerInternal.class);
    }

    public void setLocationPackagesProvider(PackagesProvider provider) {
        synchronized (mLock) {
            mLocationPackagesProvider = provider;
        }
    }

    /** Sets the provider for loction extra packages. */
    public void setLocationExtraPackagesProvider(PackagesProvider provider) {
        synchronized (mLock) {
            mLocationExtraPackagesProvider = provider;
        }
    }

    public void setVoiceInteractionPackagesProvider(PackagesProvider provider) {
        synchronized (mLock) {
            mVoiceInteractionPackagesProvider = provider;
        }
    }

    public void setSmsAppPackagesProvider(PackagesProvider provider) {
        synchronized (mLock) {
            mSmsAppPackagesProvider = provider;
        }
    }

    public void setDialerAppPackagesProvider(PackagesProvider provider) {
        synchronized (mLock) {
            mDialerAppPackagesProvider = provider;
        }
    }

    public void setSimCallManagerPackagesProvider(PackagesProvider provider) {
        synchronized (mLock) {
            mSimCallManagerPackagesProvider = provider;
        }
    }

    public void setUseOpenWifiAppPackagesProvider(PackagesProvider provider) {
        synchronized (mLock) {
            mUseOpenWifiAppPackagesProvider = provider;
        }
    }

    public void setSyncAdapterPackagesProvider(SyncAdapterPackagesProvider provider) {
        synchronized (mLock) {
            mSyncAdapterPackagesProvider = provider;
        }
    }

    public void grantDefaultPermissions(int userId) {
        DelayingPackageManagerCache pm = new DelayingPackageManagerCache();

        grantPermissionsToSysComponentsAndPrivApps(pm, userId);
        grantDefaultSystemHandlerPermissions(pm, userId);
        grantSignatureAppsNotificationPermissions(pm, userId);
        grantDefaultPermissionExceptions(pm, userId);

        // Apply delayed state
        pm.apply();
    }

    private void grantSignatureAppsNotificationPermissions(PackageManagerWrapper pm, int userId) {
        Log.i(TAG, "Granting Notification permissions to platform signature apps for user "
                + userId);
        List<PackageInfo> packages = mContext.getPackageManager().getInstalledPackagesAsUser(
                DEFAULT_PACKAGE_INFO_QUERY_FLAGS, UserHandle.USER_SYSTEM);
        for (PackageInfo pkg : packages) {
            if (pkg == null || !pkg.applicationInfo.isSystemApp()
                    || !pkg.applicationInfo.isSignedWithPlatformKey()) {
                continue;
            }
            grantRuntimePermissionsForSystemPackage(pm, userId, pkg, NOTIFICATION_PERMISSIONS);
        }

    }

    private void grantRuntimePermissionsForSystemPackage(PackageManagerWrapper pm,
            int userId, PackageInfo pkg) {
        grantRuntimePermissionsForSystemPackage(pm, userId, pkg, null);
    }

    private void grantRuntimePermissionsForSystemPackage(PackageManagerWrapper pm,
            int userId, PackageInfo pkg, Set<String> filterPermissions) {
        if (ArrayUtils.isEmpty(pkg.requestedPermissions)) {
            return;
        }
        Set<String> permissions = new ArraySet<>();
        for (String permission : pkg.requestedPermissions) {
            final PermissionInfo perm = pm.getPermissionInfo(permission);
            if (perm == null
                    || (filterPermissions != null && !filterPermissions.contains(permission))) {
                continue;
            }
            if (perm.isRuntime()) {
                permissions.add(permission);
            }
        }
        if (!permissions.isEmpty()) {
            grantRuntimePermissions(pm, pkg, permissions, true /*systemFixed*/, userId);
        }
    }

    public void scheduleReadDefaultPermissionExceptions() {
        mHandler.sendEmptyMessage(MSG_READ_DEFAULT_PERMISSION_EXCEPTIONS);
    }

    private void grantPermissionsToSysComponentsAndPrivApps(DelayingPackageManagerCache pm,
            int userId) {
        Log.i(TAG, "Granting permissions to platform components for user " + userId);
        List<PackageInfo> packages = mContext.getPackageManager().getInstalledPackagesAsUser(
                DEFAULT_PACKAGE_INFO_QUERY_FLAGS, UserHandle.USER_SYSTEM);
        for (PackageInfo pkg : packages) {
            if (pkg == null) {
                continue;
            }

            // Package info is already loaded, cache it
            pm.addPackageInfo(pkg.packageName, pkg);

            if (!pm.isSysComponentOrPersistentPlatformSignedPrivApp(pkg)
                    || !doesPackageSupportRuntimePermissions(pkg)
                    || ArrayUtils.isEmpty(pkg.requestedPermissions)) {
                continue;
            }
            grantRuntimePermissionsForSystemPackage(pm, userId, pkg);
        }

        // Re-grant READ_PHONE_STATE as non-fixed to all system apps that have
        // READ_PRIVILEGED_PHONE_STATE and READ_PHONE_STATE granted -- this is to undo the fixed
        // grant from R.
        for (PackageInfo pkg : packages) {
            if (pkg == null
                    || !doesPackageSupportRuntimePermissions(pkg)
                    || ArrayUtils.isEmpty(pkg.requestedPermissions)
                    || !pm.isGranted(Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
                            pkg, UserHandle.of(userId))
                    || !pm.isGranted(Manifest.permission.READ_PHONE_STATE, pkg,
                            UserHandle.of(userId))
                    || pm.isSysComponentOrPersistentPlatformSignedPrivApp(pkg)) {
                continue;
            }

            pm.updatePermissionFlags(Manifest.permission.READ_PHONE_STATE, pkg,
                    PackageManager.FLAG_PERMISSION_SYSTEM_FIXED,
                    0,
                    UserHandle.of(userId));
        }

    }

    @SafeVarargs
    private final void grantIgnoringSystemPackage(PackageManagerWrapper pm, String packageName,
            int userId, Set<String>... permissionGroups) {
        grantPermissionsToPackage(pm, packageName, userId, true /* ignoreSystemPackage */,
                true /*whitelistRestrictedPermissions*/, permissionGroups);
    }

    @SafeVarargs
    private final void grantSystemFixedPermissionsToSystemPackage(PackageManagerWrapper pm,
            String packageName, int userId, Set<String>... permissionGroups) {
        grantPermissionsToSystemPackage(pm, packageName, userId, true /* systemFixed */,
                permissionGroups);
    }

    @SafeVarargs
    private final void grantPermissionsToSystemPackage(PackageManagerWrapper pm,
            String packageName, int userId, Set<String>... permissionGroups) {
        grantPermissionsToSystemPackage(pm, packageName, userId, false /* systemFixed */,
                permissionGroups);
    }

    @SafeVarargs
    private final void grantPermissionsToSystemPackage(PackageManagerWrapper pm, String packageName,
            int userId, boolean systemFixed, Set<String>... permissionGroups) {
        if (!pm.isSystemPackage(packageName)) {
            return;
        }
        grantPermissionsToPackage(pm, pm.getSystemPackageInfo(packageName),
                userId, systemFixed, false /* ignoreSystemPackage */,
                true /*whitelistRestrictedPermissions*/, permissionGroups);
    }

    @SafeVarargs
    private final void grantPermissionsToPackage(PackageManagerWrapper pm, String packageName,
            int userId, boolean ignoreSystemPackage, boolean whitelistRestrictedPermissions,
            Set<String>... permissionGroups) {
        grantPermissionsToPackage(pm, pm.getPackageInfo(packageName),
                userId, false /* systemFixed */, ignoreSystemPackage,
                whitelistRestrictedPermissions, permissionGroups);
    }

    @SafeVarargs
    private final void grantPermissionsToPackage(PackageManagerWrapper pm, PackageInfo packageInfo,
            int userId, boolean systemFixed, boolean ignoreSystemPackage,
            boolean whitelistRestrictedPermissions, Set<String>... permissionGroups) {
        if (packageInfo == null) {
            return;
        }
        if (doesPackageSupportRuntimePermissions(packageInfo)) {
            for (Set<String> permissionGroup : permissionGroups) {
                grantRuntimePermissions(pm, packageInfo, permissionGroup, systemFixed,
                        ignoreSystemPackage, whitelistRestrictedPermissions, userId);
            }
        }
    }

    private void grantDefaultSystemHandlerPermissions(PackageManagerWrapper pm, int userId) {
        Log.i(TAG, "Granting permissions to default platform handlers for user " + userId);

        final PackagesProvider locationPackagesProvider;
        final PackagesProvider locationExtraPackagesProvider;
        final PackagesProvider voiceInteractionPackagesProvider;
        final PackagesProvider smsAppPackagesProvider;
        final PackagesProvider dialerAppPackagesProvider;
        final PackagesProvider simCallManagerPackagesProvider;
        final PackagesProvider useOpenWifiAppPackagesProvider;
        final SyncAdapterPackagesProvider syncAdapterPackagesProvider;

        synchronized (mLock) {
            locationPackagesProvider = mLocationPackagesProvider;
            locationExtraPackagesProvider = mLocationExtraPackagesProvider;
            voiceInteractionPackagesProvider = mVoiceInteractionPackagesProvider;
            smsAppPackagesProvider = mSmsAppPackagesProvider;
            dialerAppPackagesProvider = mDialerAppPackagesProvider;
            simCallManagerPackagesProvider = mSimCallManagerPackagesProvider;
            useOpenWifiAppPackagesProvider = mUseOpenWifiAppPackagesProvider;
            syncAdapterPackagesProvider = mSyncAdapterPackagesProvider;
        }

        String[] voiceInteractPackageNames = (voiceInteractionPackagesProvider != null)
                ? voiceInteractionPackagesProvider.getPackages(userId) : null;
        String[] locationPackageNames = (locationPackagesProvider != null)
                ? locationPackagesProvider.getPackages(userId) : null;
        String[] locationExtraPackageNames = (locationExtraPackagesProvider != null)
                ? locationExtraPackagesProvider.getPackages(userId) : null;
        String[] smsAppPackageNames = (smsAppPackagesProvider != null)
                ? smsAppPackagesProvider.getPackages(userId) : null;
        String[] dialerAppPackageNames = (dialerAppPackagesProvider != null)
                ? dialerAppPackagesProvider.getPackages(userId) : null;
        String[] simCallManagerPackageNames = (simCallManagerPackagesProvider != null)
                ? simCallManagerPackagesProvider.getPackages(userId) : null;
        String[] useOpenWifiAppPackageNames = (useOpenWifiAppPackagesProvider != null)
                ? useOpenWifiAppPackagesProvider.getPackages(userId) : null;
        String[] contactsSyncAdapterPackages = (syncAdapterPackagesProvider != null) ?
                syncAdapterPackagesProvider.getPackages(ContactsContract.AUTHORITY, userId) : null;
        String[] calendarSyncAdapterPackages = (syncAdapterPackagesProvider != null) ?
                syncAdapterPackagesProvider.getPackages(CalendarContract.AUTHORITY, userId) : null;

        // PermissionController
        grantSystemFixedPermissionsToSystemPackage(pm,
                mContext.getPackageManager().getPermissionControllerPackageName(), userId,
                NOTIFICATION_PERMISSIONS);

        // Installer
        grantSystemFixedPermissionsToSystemPackage(pm,
                ArrayUtils.firstOrNull(getKnownPackages(
                        KnownPackages.PACKAGE_INSTALLER, userId)),
                userId, STORAGE_PERMISSIONS, NOTIFICATION_PERMISSIONS);

        // Verifier
        final String verifier = ArrayUtils.firstOrNull(getKnownPackages(
                KnownPackages.PACKAGE_VERIFIER, userId));
        grantSystemFixedPermissionsToSystemPackage(pm, verifier, userId, STORAGE_PERMISSIONS);
        grantPermissionsToSystemPackage(pm, verifier, userId, PHONE_PERMISSIONS, SMS_PERMISSIONS,
                NOTIFICATION_PERMISSIONS);

        // SetupWizard
        final String setupWizardPackage = ArrayUtils.firstOrNull(getKnownPackages(
                KnownPackages.PACKAGE_SETUP_WIZARD, userId));
        grantPermissionsToSystemPackage(pm, setupWizardPackage, userId, PHONE_PERMISSIONS,
                CONTACTS_PERMISSIONS, ALWAYS_LOCATION_PERMISSIONS, CAMERA_PERMISSIONS,
                NEARBY_DEVICES_PERMISSIONS);
        grantSystemFixedPermissionsToSystemPackage(pm, setupWizardPackage, userId,
                NOTIFICATION_PERMISSIONS);

        // SearchSelector
        grantPermissionsToSystemPackage(pm, getDefaultSearchSelectorPackage(), userId,
                NOTIFICATION_PERMISSIONS);

        // Captive Portal Login
        grantPermissionsToSystemPackage(pm, getDefaultCaptivePortalLoginPackage(), userId,
                NOTIFICATION_PERMISSIONS);

        // Dock Manager
        grantPermissionsToSystemPackage(pm, getDefaultDockManagerPackage(), userId,
                NOTIFICATION_PERMISSIONS);

        // Camera
        grantPermissionsToSystemPackage(pm,
                getDefaultSystemHandlerActivityPackage(pm, MediaStore.ACTION_IMAGE_CAPTURE, userId),
                userId, CAMERA_PERMISSIONS, MICROPHONE_PERMISSIONS, STORAGE_PERMISSIONS);

        // Sound recorder
        grantPermissionsToSystemPackage(pm,
                getDefaultSystemHandlerActivityPackage(pm,
                        MediaStore.Audio.Media.RECORD_SOUND_ACTION, userId),
                userId, MICROPHONE_PERMISSIONS);

        // Media provider
        grantSystemFixedPermissionsToSystemPackage(pm,
                getDefaultProviderAuthorityPackage(MediaStore.AUTHORITY, userId), userId,
                STORAGE_PERMISSIONS, NOTIFICATION_PERMISSIONS);

        // Downloads provider
        grantSystemFixedPermissionsToSystemPackage(pm,
                getDefaultProviderAuthorityPackage("downloads", userId), userId,
                STORAGE_PERMISSIONS, NOTIFICATION_PERMISSIONS);

        // Downloads UI
        grantSystemFixedPermissionsToSystemPackage(pm,
                getDefaultSystemHandlerActivityPackage(pm,
                        DownloadManager.ACTION_VIEW_DOWNLOADS, userId),
                userId, STORAGE_PERMISSIONS);

        // Storage provider
        grantSystemFixedPermissionsToSystemPackage(pm,
                getDefaultProviderAuthorityPackage("com.android.externalstorage.documents", userId),
                userId, STORAGE_PERMISSIONS);

        // CertInstaller
        grantSystemFixedPermissionsToSystemPackage(pm,
                getDefaultSystemHandlerActivityPackage(pm, Credentials.INSTALL_ACTION, userId),
                userId, STORAGE_PERMISSIONS);

        // Dialer
        if (dialerAppPackageNames == null) {
            String dialerPackage =
                    getDefaultSystemHandlerActivityPackage(pm, Intent.ACTION_DIAL, userId);
            grantDefaultPermissionsToDefaultSystemDialerApp(pm, dialerPackage, userId);
        } else {
            for (String dialerAppPackageName : dialerAppPackageNames) {
                grantDefaultPermissionsToDefaultSystemDialerApp(pm, dialerAppPackageName, userId);
            }
        }

        // Sim call manager
        if (simCallManagerPackageNames != null) {
            for (String simCallManagerPackageName : simCallManagerPackageNames) {
                grantDefaultPermissionsToDefaultSystemSimCallManager(pm,
                        simCallManagerPackageName, userId);
            }
        }

        // Use Open Wifi
        if (useOpenWifiAppPackageNames != null) {
            for (String useOpenWifiPackageName : useOpenWifiAppPackageNames) {
                grantDefaultPermissionsToDefaultSystemUseOpenWifiApp(pm,
                        useOpenWifiPackageName, userId);
            }
        }

        // SMS
        if (smsAppPackageNames == null) {
            String smsPackage = getDefaultSystemHandlerActivityPackageForCategory(pm,
                    Intent.CATEGORY_APP_MESSAGING, userId);
            grantDefaultPermissionsToDefaultSystemSmsApp(pm, smsPackage, userId);
        } else {
            for (String smsPackage : smsAppPackageNames) {
                grantDefaultPermissionsToDefaultSystemSmsApp(pm, smsPackage, userId);
            }
        }

        // Cell Broadcast Receiver
        grantSystemFixedPermissionsToSystemPackage(pm,
                getDefaultSystemHandlerActivityPackage(pm, Intents.SMS_CB_RECEIVED_ACTION, userId),
                userId, SMS_PERMISSIONS, NEARBY_DEVICES_PERMISSIONS, NOTIFICATION_PERMISSIONS);

        // Carrier Provisioning Service
        grantPermissionsToSystemPackage(pm,
                getDefaultSystemHandlerServicePackage(pm, Intents.SMS_CARRIER_PROVISION_ACTION,
                        userId),
                userId, SMS_PERMISSIONS);

        // Calendar
        grantPermissionsToSystemPackage(pm,
                getDefaultSystemHandlerActivityPackageForCategory(pm,
                        Intent.CATEGORY_APP_CALENDAR, userId),
                userId, CALENDAR_PERMISSIONS, CONTACTS_PERMISSIONS, NOTIFICATION_PERMISSIONS);

        // Calendar provider
        String calendarProvider =
                getDefaultProviderAuthorityPackage(CalendarContract.AUTHORITY, userId);
        grantPermissionsToSystemPackage(pm, calendarProvider, userId,
                CONTACTS_PERMISSIONS, STORAGE_PERMISSIONS);
        grantSystemFixedPermissionsToSystemPackage(pm, calendarProvider, userId,
                CALENDAR_PERMISSIONS);

        // Calendar provider sync adapters
        if (calendarSyncAdapterPackages != null) {
            grantPermissionToEachSystemPackage(pm,
                    getHeadlessSyncAdapterPackages(pm, calendarSyncAdapterPackages, userId),
                    userId, CALENDAR_PERMISSIONS);
        }

        // Contacts
        grantPermissionsToSystemPackage(pm,
                getDefaultSystemHandlerActivityPackageForCategory(pm,
                        Intent.CATEGORY_APP_CONTACTS, userId),
                userId, CONTACTS_PERMISSIONS, PHONE_PERMISSIONS);

        // Contacts provider sync adapters
        if (contactsSyncAdapterPackages != null) {
            grantPermissionToEachSystemPackage(pm,
                    getHeadlessSyncAdapterPackages(pm, contactsSyncAdapterPackages, userId),
                    userId, CONTACTS_PERMISSIONS);
        }

        // Contacts provider
        String contactsProviderPackage =
                getDefaultProviderAuthorityPackage(ContactsContract.AUTHORITY, userId);
        grantSystemFixedPermissionsToSystemPackage(pm, contactsProviderPackage, userId,
                CONTACTS_PERMISSIONS, PHONE_PERMISSIONS);
        grantPermissionsToSystemPackage(pm, contactsProviderPackage, userId, STORAGE_PERMISSIONS);

        // Device provisioning
        grantPermissionsToSystemPackage(pm,
                getDefaultSystemHandlerActivityPackage(pm,
                        DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, userId),
                userId, CONTACTS_PERMISSIONS, NOTIFICATION_PERMISSIONS);

        // Maps
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, 0)) {
            grantPermissionsToSystemPackage(pm,
                getDefaultSystemHandlerActivityPackageForCategory(pm,
                        Intent.CATEGORY_APP_MAPS, userId),
                userId, FOREGROUND_LOCATION_PERMISSIONS);
        }

        // Email
        grantPermissionsToSystemPackage(pm,
                getDefaultSystemHandlerActivityPackageForCategory(pm,
                        Intent.CATEGORY_APP_EMAIL, userId),
                userId, CONTACTS_PERMISSIONS, CALENDAR_PERMISSIONS);

        // Browser
        String browserPackage = ArrayUtils.firstOrNull(getKnownPackages(
                KnownPackages.PACKAGE_BROWSER, userId));
        if (browserPackage == null) {
            browserPackage = getDefaultSystemHandlerActivityPackageForCategory(pm,
                    Intent.CATEGORY_APP_BROWSER, userId);
            if (!pm.isSystemPackage(browserPackage)) {
                browserPackage = null;
            }
        }
        grantPermissionsToPackage(pm, browserPackage, userId, false /* ignoreSystemPackage */,
                true /*whitelistRestrictedPermissions*/, FOREGROUND_LOCATION_PERMISSIONS);

        // Voice interaction
        if (voiceInteractPackageNames != null) {
            for (String voiceInteractPackageName : voiceInteractPackageNames) {
                grantPermissionsToSystemPackage(pm, voiceInteractPackageName, userId,
                        CONTACTS_PERMISSIONS, CALENDAR_PERMISSIONS, MICROPHONE_PERMISSIONS,
                        PHONE_PERMISSIONS, SMS_PERMISSIONS, ALWAYS_LOCATION_PERMISSIONS,
                        NEARBY_DEVICES_PERMISSIONS, NOTIFICATION_PERMISSIONS);
            }
        }

        if (ActivityManager.isLowRamDeviceStatic()) {
            // Allow voice search on low-ram devices
            grantPermissionsToSystemPackage(pm,
                    getDefaultSystemHandlerActivityPackage(pm,
                            SearchManager.INTENT_ACTION_GLOBAL_SEARCH, userId),
                    userId, MICROPHONE_PERMISSIONS, ALWAYS_LOCATION_PERMISSIONS,
                    NOTIFICATION_PERMISSIONS);
        }

        // Voice recognition
        Intent voiceRecoIntent = new Intent(RecognitionService.SERVICE_INTERFACE)
                .addCategory(Intent.CATEGORY_DEFAULT);
        grantPermissionsToSystemPackage(pm,
                getDefaultSystemHandlerServicePackage(pm, voiceRecoIntent, userId), userId,
                MICROPHONE_PERMISSIONS);

        // Location
        if (locationPackageNames != null) {
            for (String packageName : locationPackageNames) {
                grantPermissionsToSystemPackage(pm, packageName, userId,
                        CONTACTS_PERMISSIONS, CALENDAR_PERMISSIONS, MICROPHONE_PERMISSIONS,
                        PHONE_PERMISSIONS, SMS_PERMISSIONS, CAMERA_PERMISSIONS,
                        SENSORS_PERMISSIONS, STORAGE_PERMISSIONS, NEARBY_DEVICES_PERMISSIONS,
                        NOTIFICATION_PERMISSIONS);
                grantSystemFixedPermissionsToSystemPackage(pm, packageName, userId,
                        ALWAYS_LOCATION_PERMISSIONS, ACTIVITY_RECOGNITION_PERMISSIONS);
            }
        }
        if (locationExtraPackageNames != null) {
            // Also grant location and activity recognition permission to location extra packages.
            for (String packageName : locationExtraPackageNames) {
                grantPermissionsToSystemPackage(pm, packageName, userId,
                        ALWAYS_LOCATION_PERMISSIONS, NEARBY_DEVICES_PERMISSIONS);
                grantSystemFixedPermissionsToSystemPackage(pm, packageName, userId,
                        ACTIVITY_RECOGNITION_PERMISSIONS);
            }
        }

        // Music
        Intent musicIntent = new Intent(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .setDataAndType(Uri.fromFile(new File("foo.mp3")), AUDIO_MIME_TYPE);
        grantPermissionsToSystemPackage(pm,
                getDefaultSystemHandlerActivityPackage(pm, musicIntent, userId), userId,
                STORAGE_PERMISSIONS);

        // Home
        Intent homeIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addCategory(Intent.CATEGORY_LAUNCHER_APP);
        grantPermissionsToSystemPackage(pm,
                getDefaultSystemHandlerActivityPackage(pm, homeIntent, userId), userId,
                ALWAYS_LOCATION_PERMISSIONS, NOTIFICATION_PERMISSIONS);

        // Watches
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH, 0)) {
            // Home application on watches

            String wearPackage = getDefaultSystemHandlerActivityPackageForCategory(pm,
                    Intent.CATEGORY_HOME_MAIN, userId);
            grantPermissionsToSystemPackage(pm, wearPackage, userId,
                    CONTACTS_PERMISSIONS, MICROPHONE_PERMISSIONS, ALWAYS_LOCATION_PERMISSIONS);
            grantSystemFixedPermissionsToSystemPackage(pm, wearPackage, userId, PHONE_PERMISSIONS,
                                                       ACTIVITY_RECOGNITION_PERMISSIONS);

            // Fitness tracking on watches
            if (mContext.getResources().getBoolean(R.bool.config_trackerAppNeedsPermissions)) {
                Log.d(TAG, "Wear: Skipping permission grant for Default fitness tracker app : "
                        + wearPackage);
            } else {
                grantPermissionsToSystemPackage(pm,
                    getDefaultSystemHandlerActivityPackage(pm, ACTION_TRACK, userId), userId,
                    SENSORS_PERMISSIONS);
            }
        }

        // Print Spooler
        grantSystemFixedPermissionsToSystemPackage(pm, PrintManager.PRINT_SPOOLER_PACKAGE_NAME,
                userId, ALWAYS_LOCATION_PERMISSIONS, NOTIFICATION_PERMISSIONS);

        // EmergencyInfo
        grantSystemFixedPermissionsToSystemPackage(pm,
                getDefaultSystemHandlerActivityPackage(pm,
                        TelephonyManager.ACTION_EMERGENCY_ASSISTANCE, userId),
                userId, CONTACTS_PERMISSIONS, PHONE_PERMISSIONS);

        // NFC Tag viewer
        Intent nfcTagIntent = new Intent(Intent.ACTION_VIEW)
                .setType("vnd.android.cursor.item/ndef_msg");
        grantPermissionsToSystemPackage(pm,
                getDefaultSystemHandlerActivityPackage(pm, nfcTagIntent, userId), userId,
                CONTACTS_PERMISSIONS, PHONE_PERMISSIONS);

        // Storage Manager
        grantSystemFixedPermissionsToSystemPackage(pm,
                getDefaultSystemHandlerActivityPackage(pm,
                        StorageManager.ACTION_MANAGE_STORAGE, userId),
                userId, STORAGE_PERMISSIONS);

        // Companion devices
        grantSystemFixedPermissionsToSystemPackage(pm,
                CompanionDeviceManager.COMPANION_DEVICE_DISCOVERY_PACKAGE_NAME, userId,
                ALWAYS_LOCATION_PERMISSIONS, NEARBY_DEVICES_PERMISSIONS);

        // Ringtone Picker
        grantSystemFixedPermissionsToSystemPackage(pm,
                getDefaultSystemHandlerActivityPackage(pm,
                        RingtoneManager.ACTION_RINGTONE_PICKER, userId),
                userId, STORAGE_PERMISSIONS);

        // TextClassifier Service
        for (String textClassifierPackage :
                getKnownPackages(KnownPackages.PACKAGE_SYSTEM_TEXT_CLASSIFIER, userId)) {
            grantPermissionsToSystemPackage(pm, textClassifierPackage, userId,
                    COARSE_BACKGROUND_LOCATION_PERMISSIONS, CONTACTS_PERMISSIONS);
        }

        // There is no real "marker" interface to identify the shared storage backup, it is
        // hardcoded in BackupManagerService.SHARED_BACKUP_AGENT_PACKAGE.
        grantSystemFixedPermissionsToSystemPackage(pm, "com.android.sharedstoragebackup", userId,
                STORAGE_PERMISSIONS);

        // Bluetooth MIDI Service
        grantSystemFixedPermissionsToSystemPackage(pm,
                MidiManager.BLUETOOTH_MIDI_SERVICE_PACKAGE, userId,
                NEARBY_DEVICES_PERMISSIONS);

        // Ad Service
        String commonServiceAction = "android.adservices.AD_SERVICES_COMMON_SERVICE";
        grantPermissionsToSystemPackage(pm, getDefaultSystemHandlerServicePackage(pm,
                        commonServiceAction, userId), userId, NOTIFICATION_PERMISSIONS);
    }

    private String getDefaultSystemHandlerActivityPackageForCategory(PackageManagerWrapper pm,
            String category, int userId) {
        return getDefaultSystemHandlerActivityPackage(pm,
                new Intent(Intent.ACTION_MAIN).addCategory(category), userId);
    }

    private String getDefaultSearchSelectorPackage() {
        return mContext.getString(R.string.config_defaultSearchSelectorPackageName);
    }

    private String getDefaultCaptivePortalLoginPackage() {
        return mContext.getString(R.string.config_defaultCaptivePortalLoginPackageName);
    }

    private String getDefaultDockManagerPackage() {
        return mContext.getString(R.string.config_defaultDockManagerPackageName);
    }

    @SafeVarargs
    private final void grantPermissionToEachSystemPackage(PackageManagerWrapper pm,
            ArrayList<String> packages, int userId, Set<String>... permissions) {
        if (packages == null) return;
        final int count = packages.size();
        for (int i = 0; i < count; i++) {
            grantPermissionsToSystemPackage(pm, packages.get(i), userId, permissions);
        }
    }

    private @NonNull String[] getKnownPackages(int knownPkgId, int userId) {
        return mServiceInternal.getKnownPackageNames(knownPkgId, userId);
    }

    private void grantDefaultPermissionsToDefaultSystemDialerApp(PackageManagerWrapper pm,
            String dialerPackage, int userId) {
        if (dialerPackage == null) {
            return;
        }
        boolean isPhonePermFixed =
                mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH, 0);
        if (isPhonePermFixed) {
            grantSystemFixedPermissionsToSystemPackage(pm, dialerPackage, userId,
                    PHONE_PERMISSIONS, NOTIFICATION_PERMISSIONS);
        } else {
            grantPermissionsToSystemPackage(pm, dialerPackage, userId, PHONE_PERMISSIONS);
        }
        grantPermissionsToSystemPackage(pm, dialerPackage, userId,
                CONTACTS_PERMISSIONS, SMS_PERMISSIONS, MICROPHONE_PERMISSIONS, CAMERA_PERMISSIONS,
                NOTIFICATION_PERMISSIONS);
        boolean isAndroidAutomotive =
                mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, 0);
        if (isAndroidAutomotive) {
            grantPermissionsToSystemPackage(pm, dialerPackage, userId, NEARBY_DEVICES_PERMISSIONS);
        }
    }

    private void grantDefaultPermissionsToDefaultSystemSmsApp(PackageManagerWrapper pm,
            String smsPackage, int userId) {
        grantPermissionsToSystemPackage(pm, smsPackage, userId,
                PHONE_PERMISSIONS, CONTACTS_PERMISSIONS, SMS_PERMISSIONS,
                STORAGE_PERMISSIONS, MICROPHONE_PERMISSIONS, CAMERA_PERMISSIONS,
                NOTIFICATION_PERMISSIONS);
    }

    private void grantDefaultPermissionsToDefaultSystemUseOpenWifiApp(PackageManagerWrapper pm,
            String useOpenWifiPackage, int userId) {
        grantPermissionsToSystemPackage(pm, useOpenWifiPackage, userId,
                ALWAYS_LOCATION_PERMISSIONS);
    }

    public void grantDefaultPermissionsToDefaultUseOpenWifiApp(String packageName, int userId) {
        Log.i(TAG, "Granting permissions to default Use Open WiFi app for user:" + userId);
        grantIgnoringSystemPackage(NO_PM_CACHE, packageName, userId, ALWAYS_LOCATION_PERMISSIONS);
    }

    public void grantDefaultPermissionsToDefaultSimCallManager(String packageName, int userId) {
        grantDefaultPermissionsToDefaultSimCallManager(NO_PM_CACHE, packageName, userId);
    }

    private void grantDefaultPermissionsToDefaultSimCallManager(PackageManagerWrapper pm,
            String packageName, int userId) {
        if (packageName == null) {
            return;
        }
        Log.i(TAG, "Granting permissions to sim call manager for user:" + userId);
        grantPermissionsToPackage(pm, packageName, userId, false /* ignoreSystemPackage */,
                true /*whitelistRestrictedPermissions*/, PHONE_PERMISSIONS, MICROPHONE_PERMISSIONS);
    }

    private void grantDefaultPermissionsToDefaultSystemSimCallManager(PackageManagerWrapper pm,
            String packageName, int userId) {
        if (pm.isSystemPackage(packageName)) {
            grantDefaultPermissionsToDefaultSimCallManager(pm, packageName, userId);
        }
    }

    public void grantDefaultPermissionsToEnabledCarrierApps(String[] packageNames, int userId) {
        Log.i(TAG, "Granting permissions to enabled carrier apps for user:" + userId);
        if (packageNames == null) {
            return;
        }
        for (String packageName : packageNames) {
            grantPermissionsToSystemPackage(NO_PM_CACHE, packageName, userId,
                    PHONE_PERMISSIONS, ALWAYS_LOCATION_PERMISSIONS, SMS_PERMISSIONS);
        }
    }

    public void grantDefaultPermissionsToEnabledImsServices(String[] packageNames, int userId) {
        Log.i(TAG, "Granting permissions to enabled ImsServices for user:" + userId);
        if (packageNames == null) {
            return;
        }
        for (String packageName : packageNames) {
            grantPermissionsToSystemPackage(NO_PM_CACHE, packageName, userId,
                    PHONE_PERMISSIONS, MICROPHONE_PERMISSIONS, ALWAYS_LOCATION_PERMISSIONS,
                    CAMERA_PERMISSIONS, CONTACTS_PERMISSIONS);
        }
    }

    public void grantDefaultPermissionsToEnabledTelephonyDataServices(
            String[] packageNames, int userId) {
        Log.i(TAG, "Granting permissions to enabled data services for user:" + userId);
        if (packageNames == null) {
            return;
        }
        for (String packageName : packageNames) {
            // Grant these permissions as system-fixed, so that nobody can accidentally
            // break cellular data.
            grantSystemFixedPermissionsToSystemPackage(NO_PM_CACHE, packageName, userId,
                    PHONE_PERMISSIONS, ALWAYS_LOCATION_PERMISSIONS);
        }
    }

    public void revokeDefaultPermissionsFromDisabledTelephonyDataServices(
            String[] packageNames, int userId) {
        Log.i(TAG, "Revoking permissions from disabled data services for user:" + userId);
        if (packageNames == null) {
            return;
        }
        for (String packageName : packageNames) {
            PackageInfo pkg = NO_PM_CACHE.getSystemPackageInfo(packageName);
            if (NO_PM_CACHE.isSystemPackage(pkg) && doesPackageSupportRuntimePermissions(pkg)) {
                revokeRuntimePermissions(NO_PM_CACHE, packageName, PHONE_PERMISSIONS, true,
                        userId);
                revokeRuntimePermissions(NO_PM_CACHE, packageName, ALWAYS_LOCATION_PERMISSIONS,
                        true, userId);
            }
        }
    }

    public void grantDefaultPermissionsToActiveLuiApp(String packageName, int userId) {
        Log.i(TAG, "Granting permissions to active LUI app for user:" + userId);
        grantSystemFixedPermissionsToSystemPackage(NO_PM_CACHE, packageName, userId,
                CAMERA_PERMISSIONS);
    }

    public void revokeDefaultPermissionsFromLuiApps(String[] packageNames, int userId) {
        Log.i(TAG, "Revoke permissions from LUI apps for user:" + userId);
        if (packageNames == null) {
            return;
        }
        for (String packageName : packageNames) {
            PackageInfo pkg = NO_PM_CACHE.getSystemPackageInfo(packageName);
            if (NO_PM_CACHE.isSystemPackage(pkg) && doesPackageSupportRuntimePermissions(pkg)) {
                revokeRuntimePermissions(NO_PM_CACHE, packageName, CAMERA_PERMISSIONS, true,
                        userId);
            }
        }
    }

    public void grantDefaultPermissionsToCarrierServiceApp(@NonNull String packageName,
            @UserIdInt int userId) {
        Log.i(TAG, "Grant permissions to Carrier Service app " + packageName + " for user:"
                + userId);
        grantPermissionsToPackage(NO_PM_CACHE, packageName, userId, /* ignoreSystemPackage */ false,
               /* whitelistRestricted */ true, NOTIFICATION_PERMISSIONS);
    }

    private String getDefaultSystemHandlerActivityPackage(PackageManagerWrapper pm,
            String intentAction, int userId) {
        return getDefaultSystemHandlerActivityPackage(pm, new Intent(intentAction), userId);
    }

    private String getDefaultSystemHandlerActivityPackage(PackageManagerWrapper pm, Intent intent,
            int userId) {
        ResolveInfo handler = mContext.getPackageManager().resolveActivityAsUser(
                intent, DEFAULT_INTENT_QUERY_FLAGS, userId);
        if (handler == null || handler.activityInfo == null) {
            return null;
        }
        if (mServiceInternal.isResolveActivityComponent(handler.activityInfo)) {
            return null;
        }
        String packageName = handler.activityInfo.packageName;
        return pm.isSystemPackage(packageName) ? packageName : null;
    }

    private String getDefaultSystemHandlerServicePackage(PackageManagerWrapper pm,
            String intentAction, int userId) {
        return getDefaultSystemHandlerServicePackage(pm, new Intent(intentAction), userId);
    }

    private String getDefaultSystemHandlerServicePackage(PackageManagerWrapper pm,
            Intent intent, int userId) {
        List<ResolveInfo> handlers = mContext.getPackageManager().queryIntentServicesAsUser(
                intent, DEFAULT_INTENT_QUERY_FLAGS, userId);
        if (handlers == null) {
            return null;
        }
        final int handlerCount = handlers.size();
        for (int i = 0; i < handlerCount; i++) {
            ResolveInfo handler = handlers.get(i);
            String handlerPackage = handler.serviceInfo.packageName;
            if (pm.isSystemPackage(handlerPackage)) {
                return handlerPackage;
            }
        }
        return null;
    }

    private ArrayList<String> getHeadlessSyncAdapterPackages(PackageManagerWrapper pm,
            String[] syncAdapterPackageNames, int userId) {
        ArrayList<String> syncAdapterPackages = new ArrayList<>();

        Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);

        for (String syncAdapterPackageName : syncAdapterPackageNames) {
            homeIntent.setPackage(syncAdapterPackageName);

            ResolveInfo homeActivity = mContext.getPackageManager().resolveActivityAsUser(
                    homeIntent, DEFAULT_INTENT_QUERY_FLAGS, userId);
            if (homeActivity != null) {
                continue;
            }

            if (pm.isSystemPackage(syncAdapterPackageName)) {
                syncAdapterPackages.add(syncAdapterPackageName);
            }
        }

        return syncAdapterPackages;
    }

    private String getDefaultProviderAuthorityPackage(String authority, int userId) {
        ProviderInfo provider = mContext.getPackageManager().resolveContentProviderAsUser(
                authority, DEFAULT_INTENT_QUERY_FLAGS, userId);
        if (provider != null) {
            return provider.packageName;
        }
        return null;
    }

    private void grantRuntimePermissions(PackageManagerWrapper pm, PackageInfo pkg,
            Set<String> permissions, boolean systemFixed, int userId) {
        grantRuntimePermissions(pm, pkg, permissions, systemFixed, false,
                true /*whitelistRestrictedPermissions*/, userId);
    }

    private void revokeRuntimePermissions(PackageManagerWrapper pm, String packageName,
            Set<String> permissions, boolean systemFixed, int userId) {
        PackageInfo pkg = pm.getSystemPackageInfo(packageName);
        if (pkg == null || ArrayUtils.isEmpty(pkg.requestedPermissions)) {
            return;
        }
        Set<String> revokablePermissions = new ArraySet<>(Arrays.asList(pkg.requestedPermissions));

        for (String permission : permissions) {
            // We can't revoke what wasn't requested.
            if (!revokablePermissions.contains(permission)) {
                continue;
            }

            UserHandle user = UserHandle.of(userId);
            final int flags = pm.getPermissionFlags(permission, pm.getPackageInfo(packageName),
                    user);

            // We didn't get this through the default grant policy. Move along.
            if ((flags & PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT) == 0) {
                continue;
            }
            // We aren't going to clobber device policy with a DefaultGrant.
            if ((flags & PackageManager.FLAG_PERMISSION_POLICY_FIXED) != 0) {
                continue;
            }
            // Do not revoke system fixed permissions unless caller set them that way;
            // there is no refcount for the number of sources of this, so there
            // should be at most one grantor doing SYSTEM_FIXED for any given package.
            if ((flags & PackageManager.FLAG_PERMISSION_SYSTEM_FIXED) != 0 && !systemFixed) {
                continue;
            }
            pm.revokePermission(permission, pkg, user);

            if (DEBUG) {
                Log.i(TAG, "revoked " + (systemFixed ? "fixed " : "not fixed ")
                        + permission + " to " + packageName);
            }

            // Remove the GRANTED_BY_DEFAULT flag without touching the others.
            // Note that we do not revoke FLAG_PERMISSION_SYSTEM_FIXED. That bit remains
            // sticky once set.
            pm.updatePermissionFlags(permission, pkg,
                    PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT, 0, user);
        }
    }

    /**
     * Check if a permission is already fixed or is set by the user.
     *
     * <p>A permission should not be set by the default policy if the user or other policies already
     * set the permission.
     *
     * @param flags The flags of the permission
     *
     * @return {@code true} iff the permission can be set without violating a policy of the users
     *         intention
     */
    private boolean isFixedOrUserSet(int flags) {
        return (flags & (PackageManager.FLAG_PERMISSION_USER_SET
                | PackageManager.FLAG_PERMISSION_USER_FIXED
                | PackageManager.FLAG_PERMISSION_POLICY_FIXED
                | PackageManager.FLAG_PERMISSION_SYSTEM_FIXED)) != 0;
    }

    private void grantRuntimePermissions(PackageManagerWrapper pm, PackageInfo pkg,
            Set<String> permissionsWithoutSplits, boolean systemFixed, boolean ignoreSystemPackage,
            boolean whitelistRestrictedPermissions, int userId) {
        UserHandle user = UserHandle.of(userId);
        if (pkg == null) {
            return;
        }

        String[] requestedPermissions = pkg.requestedPermissions;
        if (ArrayUtils.isEmpty(requestedPermissions)) {
            return;
        }

        // Intersect the requestedPermissions for a factory image with that of its current update
        // in case the latter one removed a <uses-permission>
        String[] requestedByNonSystemPackage = pm.getPackageInfo(pkg.packageName)
                .requestedPermissions;
        int size = requestedPermissions.length;
        for (int i = 0; i < size; i++) {
            if (!ArrayUtils.contains(requestedByNonSystemPackage, requestedPermissions[i])) {
                requestedPermissions[i] = null;
            }
        }
        requestedPermissions = ArrayUtils.filterNotNull(requestedPermissions, String[]::new);

        final ArraySet<String> permissions = new ArraySet<>(permissionsWithoutSplits);
        ApplicationInfo applicationInfo = pkg.applicationInfo;

        int newFlags = PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT;
        if (systemFixed) {
            newFlags |= PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
        }

        // Automatically attempt to grant split permissions to older APKs
        final List<PermissionManager.SplitPermissionInfo> splitPermissions =
                mContext.getSystemService(PermissionManager.class).getSplitPermissions();
        final int numSplitPerms = splitPermissions.size();
        for (int splitPermNum = 0; splitPermNum < numSplitPerms; splitPermNum++) {
            final PermissionManager.SplitPermissionInfo splitPerm =
                    splitPermissions.get(splitPermNum);

            if (applicationInfo != null
                    && applicationInfo.targetSdkVersion < splitPerm.getTargetSdk()
                    && permissionsWithoutSplits.contains(splitPerm.getSplitPermission())) {
                permissions.addAll(splitPerm.getNewPermissions());
            }
        }

        Set<String> grantablePermissions = null;

        // In some cases, like for the Phone or SMS app, we grant permissions regardless
        // of if the version on the system image declares the permission as used since
        // selecting the app as the default for that function the user makes a deliberate
        // choice to grant this app the permissions needed to function. For all other
        // apps, (default grants on first boot and user creation) we don't grant default
        // permissions if the version on the system image does not declare them.
        if (!ignoreSystemPackage
                && applicationInfo != null
                && applicationInfo.isUpdatedSystemApp()) {
            final PackageInfo disabledPkg = pm.getSystemPackageInfo(
                    mServiceInternal.getDisabledSystemPackageName(pkg.packageName));
            if (disabledPkg != null) {
                if (ArrayUtils.isEmpty(disabledPkg.requestedPermissions)) {
                    return;
                }
                if (!Arrays.equals(requestedPermissions, disabledPkg.requestedPermissions)) {
                    grantablePermissions = new ArraySet<>(Arrays.asList(requestedPermissions));
                    requestedPermissions = disabledPkg.requestedPermissions;
                }
            }
        }

        final int numRequestedPermissions = requestedPermissions.length;

        // Sort requested permissions so that all permissions that are a foreground permission (i.e.
        // permissions that have a background permission) are before their background permissions.
        final String[] sortedRequestedPermissions = new String[numRequestedPermissions];
        int numForeground = 0;
        int numOther = 0;
        for (int i = 0; i < numRequestedPermissions; i++) {
            String permission = requestedPermissions[i];
            if (pm.getBackgroundPermission(permission) != null) {
                sortedRequestedPermissions[numForeground] = permission;
                numForeground++;
            } else {
                sortedRequestedPermissions[numRequestedPermissions - 1 - numOther] =
                        permission;
                numOther++;
            }
        }

        for (int requestedPermissionNum = 0; requestedPermissionNum < numRequestedPermissions;
                requestedPermissionNum++) {
            String permission = requestedPermissions[requestedPermissionNum];

            // If there is a disabled system app it may request a permission the updated
            // version ot the data partition doesn't, In this case skip the permission.
            if (grantablePermissions != null && !grantablePermissions.contains(permission)) {
                continue;
            }

            if (permissions.contains(permission)) {
                final int flags = pm.getPermissionFlags(permission, pkg, user);

                // If we are trying to grant as system fixed and already system fixed
                // then the system can change the system fixed grant state.
                final boolean changingGrantForSystemFixed = systemFixed
                        && (flags & PackageManager.FLAG_PERMISSION_SYSTEM_FIXED) != 0;

                // Certain flags imply that the permission's current state by the system or
                // device/profile owner or the user. In these cases we do not want to clobber the
                // current state.
                //
                // Unless the caller wants to override user choices. The override is
                // to make sure we can grant the needed permission to the default
                // sms and phone apps after the user chooses this in the UI.
                if (!isFixedOrUserSet(flags) || ignoreSystemPackage
                        || changingGrantForSystemFixed) {
                    // Never clobber policy fixed permissions.
                    // We must allow the grant of a system-fixed permission because
                    // system-fixed is sticky, but the permission itself may be revoked.
                    if ((flags & PackageManager.FLAG_PERMISSION_POLICY_FIXED) != 0) {
                        continue;
                    }

                    // Preserve allowlisting flags.
                    newFlags |= (flags & PackageManager.FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT);

                    // If we are allowlisting the permission, update the exempt flag before grant.
                    if (whitelistRestrictedPermissions && pm.isPermissionRestricted(permission)) {
                        pm.updatePermissionFlags(permission, pkg,
                                PackageManager.FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT,
                                PackageManager.FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT, user);
                    }

                    // If the system tries to change a system fixed permission from one fixed
                    // state to another we need to drop the fixed flag to allow the grant.
                    if (changingGrantForSystemFixed) {
                        pm.updatePermissionFlags(permission, pkg, flags,
                                flags & ~PackageManager.FLAG_PERMISSION_SYSTEM_FIXED, user);
                    }

                    if (!pm.isGranted(permission, pkg, user)) {
                        pm.grantPermission(permission, pkg, user);
                    }

                    // clear the REVIEW_REQUIRED flag, if set
                    int flagMask = newFlags | PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED;
                    pm.updatePermissionFlags(permission, pkg, flagMask, newFlags, user);
                }

                // If a component gets a permission for being the default handler A
                // and also default handler B, we grant the weaker grant form.
                if ((flags & PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT) != 0
                        && (flags & PackageManager.FLAG_PERMISSION_SYSTEM_FIXED) != 0
                        && !systemFixed) {
                    if (DEBUG) {
                        Log.i(TAG, "Granted not fixed " + permission + " to default handler "
                                + pkg);
                    }
                    pm.updatePermissionFlags(permission, pkg,
                            PackageManager.FLAG_PERMISSION_SYSTEM_FIXED, 0, user);
                }
            }
        }
    }

    private void grantDefaultPermissionExceptions(PackageManagerWrapper pm, int userId) {
        mHandler.removeMessages(MSG_READ_DEFAULT_PERMISSION_EXCEPTIONS);

        synchronized (mLock) {
            // mGrantExceptions is null only before the first read and then
            // it serves as a cache of the default grants that should be
            // performed for every user. If there is an entry then the app
            // is on the system image and supports runtime permissions.
            if (mGrantExceptions == null) {
                mGrantExceptions = readDefaultPermissionExceptionsLocked(pm);
            }
        }

        Set<String> permissions = null;
        final int exceptionCount = mGrantExceptions.size();
        for (int i = 0; i < exceptionCount; i++) {
            String packageName = mGrantExceptions.keyAt(i);
            PackageInfo pkg = pm.getSystemPackageInfo(packageName);
            List<DefaultPermissionGrant> permissionGrants = mGrantExceptions.valueAt(i);
            final int permissionGrantCount = permissionGrants.size();
            for (int j = 0; j < permissionGrantCount; j++) {
                DefaultPermissionGrant permissionGrant = permissionGrants.get(j);
                if (!pm.isPermissionDangerous(permissionGrant.name)) {
                    Log.w(TAG, "Ignoring permission " + permissionGrant.name
                            + " which isn't dangerous");
                    continue;
                }
                if (permissions == null) {
                    permissions = new ArraySet<>();
                } else {
                    permissions.clear();
                }
                permissions.add(permissionGrant.name);


                grantRuntimePermissions(pm, pkg, permissions, permissionGrant.fixed,
                        permissionGrant.whitelisted, true /*whitelistRestrictedPermissions*/,
                        userId);
            }
        }
    }

    private File[] getDefaultPermissionFiles() {
        ArrayList<File> ret = new ArrayList<File>();
        File dir = new File(Environment.getRootDirectory(), "etc/default-permissions");
        if (dir.isDirectory() && dir.canRead()) {
            Collections.addAll(ret, dir.listFiles());
        }
        dir = new File(Environment.getVendorDirectory(), "etc/default-permissions");
        if (dir.isDirectory() && dir.canRead()) {
            Collections.addAll(ret, dir.listFiles());
        }
        dir = new File(Environment.getOdmDirectory(), "etc/default-permissions");
        if (dir.isDirectory() && dir.canRead()) {
            Collections.addAll(ret, dir.listFiles());
        }
        dir = new File(Environment.getProductDirectory(), "etc/default-permissions");
        if (dir.isDirectory() && dir.canRead()) {
            Collections.addAll(ret, dir.listFiles());
        }
        dir = new File(Environment.getSystemExtDirectory(), "etc/default-permissions");
        if (dir.isDirectory() && dir.canRead()) {
            Collections.addAll(ret, dir.listFiles());
        }
        // For IoT devices, we check the oem partition for default permissions for each app.
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_EMBEDDED, 0)) {
            dir = new File(Environment.getOemDirectory(), "etc/default-permissions");
            if (dir.isDirectory() && dir.canRead()) {
                Collections.addAll(ret, dir.listFiles());
            }
        }
        return ret.isEmpty() ? null : ret.toArray(new File[0]);
    }

    private @NonNull ArrayMap<String, List<DefaultPermissionGrant>>
            readDefaultPermissionExceptionsLocked(PackageManagerWrapper pm) {
        File[] files = getDefaultPermissionFiles();
        if (files == null) {
            return new ArrayMap<>(0);
        }

        ArrayMap<String, List<DefaultPermissionGrant>> grantExceptions = new ArrayMap<>();

        // Iterate over the files in the directory and scan .xml files
        for (File file : files) {
            if (!file.getPath().endsWith(".xml")) {
                Slog.i(TAG, "Non-xml file " + file
                        + " in " + file.getParent() + " directory, ignoring");
                continue;
            }
            if (!file.canRead()) {
                Slog.w(TAG, "Default permissions file " + file + " cannot be read");
                continue;
            }
            try (InputStream str = new FileInputStream(file)) {
                TypedXmlPullParser parser = Xml.resolvePullParser(str);
                parse(pm, parser, grantExceptions);
            } catch (XmlPullParserException | IOException e) {
                Slog.w(TAG, "Error reading default permissions file " + file, e);
            }
        }

        return grantExceptions;
    }

    private void parse(PackageManagerWrapper pm, TypedXmlPullParser parser,
            Map<String, List<DefaultPermissionGrant>> outGrantExceptions)
            throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            if (TAG_EXCEPTIONS.equals(parser.getName())) {
                parseExceptions(pm, parser, outGrantExceptions);
            } else {
                Log.e(TAG, "Unknown tag " + parser.getName());
            }
        }
    }

    private void parseExceptions(PackageManagerWrapper pm, TypedXmlPullParser parser,
            Map<String, List<DefaultPermissionGrant>> outGrantExceptions)
            throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            if (TAG_EXCEPTION.equals(parser.getName())) {
                String packageName = parser.getAttributeValue(null, ATTR_PACKAGE);

                List<DefaultPermissionGrant> packageExceptions =
                        outGrantExceptions.get(packageName);
                if (packageExceptions == null) {
                    // The package must be on the system image
                    PackageInfo packageInfo = pm.getSystemPackageInfo(packageName);

                    if (packageInfo == null) {
                        Log.w(TAG, "No such package:" + packageName);
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }

                    if (!pm.isSystemPackage(packageInfo)) {
                        Log.w(TAG, "Unknown system package:" + packageName);
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }

                    // The package must support runtime permissions
                    if (!doesPackageSupportRuntimePermissions(packageInfo)) {
                        Log.w(TAG, "Skipping non supporting runtime permissions package:"
                                + packageName);
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    packageExceptions = new ArrayList<>();
                    outGrantExceptions.put(packageName, packageExceptions);
                }

                parsePermission(parser, packageExceptions);
            } else {
                Log.e(TAG, "Unknown tag " + parser.getName() + "under <exceptions>");
            }
        }
    }

    private void parsePermission(TypedXmlPullParser parser, List<DefaultPermissionGrant>
            outPackageExceptions) throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (TAG_PERMISSION.contains(parser.getName())) {
                String name = parser.getAttributeValue(null, ATTR_NAME);
                if (name == null) {
                    Log.w(TAG, "Mandatory name attribute missing for permission tag");
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                }

                final boolean fixed =
                        parser.getAttributeBoolean(null, ATTR_FIXED, false);
                final boolean whitelisted =
                        parser.getAttributeBoolean(null, ATTR_WHITELISTED, false);

                DefaultPermissionGrant exception = new DefaultPermissionGrant(
                        name, fixed, whitelisted);
                outPackageExceptions.add(exception);
            } else {
                Log.e(TAG, "Unknown tag " + parser.getName() + "under <exception>");
            }
        }
    }

    private static boolean doesPackageSupportRuntimePermissions(PackageInfo pkg) {
        return pkg.applicationInfo != null
                && pkg.applicationInfo.targetSdkVersion > Build.VERSION_CODES.LOLLIPOP_MR1;
    }

    /**
     * A wrapper for package manager calls done by this class
     */
    private abstract class PackageManagerWrapper {
        abstract int getPermissionFlags(@NonNull String permission, @NonNull PackageInfo pkg,
                @NonNull UserHandle user);

        abstract void updatePermissionFlags(@NonNull String permission, @NonNull PackageInfo pkg,
                int flagMask, int flagValues, @NonNull UserHandle user);

        abstract void grantPermission(@NonNull String permission, @NonNull PackageInfo pkg,
                @NonNull UserHandle user);

        abstract void revokePermission(@NonNull String permission, @NonNull PackageInfo pkg,
                @NonNull UserHandle user);

        abstract boolean isGranted(@NonNull String permission, @NonNull PackageInfo pkg,
                @NonNull UserHandle user);

        abstract @Nullable PermissionInfo getPermissionInfo(@NonNull String permissionName);

        abstract @Nullable PackageInfo getPackageInfo(@NonNull String pkg);

        @Nullable PackageInfo getSystemPackageInfo(@NonNull String pkg) {
            PackageInfo pi = getPackageInfo(pkg);
            if (pi == null || !pi.applicationInfo.isSystemApp()) {
                return null;
            }
            return pi;
        }

        boolean isPermissionRestricted(@NonNull String name) {
            PermissionInfo pi = getPermissionInfo(name);
            if (pi == null) {
                return false;
            }

            return pi.isRestricted();
        }

        boolean isPermissionDangerous(@NonNull String name) {
            PermissionInfo pi = getPermissionInfo(name);
            if (pi == null) {
                return false;
            }

            return pi.getProtection() == PermissionInfo.PROTECTION_DANGEROUS;
        }

        /**
         * Return the background permission for a permission.
         *
         * @param permission The name of the foreground permission
         *
         * @return The name of the background permission or {@code null} if the permission has no
         *         background permission
         */
        @Nullable String getBackgroundPermission(@NonNull String permission) {
            PermissionInfo pi = getPermissionInfo(permission);
            if (pi == null) {
                return null;
            }

            return pi.backgroundPermission;
        }

        boolean isSystemPackage(@Nullable String packageName) {
            return isSystemPackage(getPackageInfo(packageName));
        }

        boolean isSystemPackage(@Nullable PackageInfo pkg) {
            if (pkg == null) {
                return false;
            }
            return pkg.applicationInfo.isSystemApp()
                    && !isSysComponentOrPersistentPlatformSignedPrivApp(pkg);
        }

        boolean isSysComponentOrPersistentPlatformSignedPrivApp(@NonNull PackageInfo pkg) {
            if (UserHandle.getAppId(pkg.applicationInfo.uid) < FIRST_APPLICATION_UID) {
                return true;
            }
            if (!pkg.applicationInfo.isPrivilegedApp()) {
                return false;
            }
            final PackageInfo disabledPkg = getSystemPackageInfo(
                    mServiceInternal.getDisabledSystemPackageName(pkg.applicationInfo.packageName));
            if (disabledPkg != null) {
                ApplicationInfo disabledPackageAppInfo = disabledPkg.applicationInfo;
                if (disabledPackageAppInfo != null
                        && (disabledPackageAppInfo.flags & ApplicationInfo.FLAG_PERSISTENT) == 0) {
                    return false;
                }
            } else if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_PERSISTENT) == 0) {
                return false;
            }
            return mServiceInternal.isPlatformSigned(pkg.packageName);
        }
    }

    /**
     * Do package manager calls but cache state and delay any change until {@link #apply()} is
     * called
     */
    private class DelayingPackageManagerCache extends PackageManagerWrapper {
        /** uid -> permission -> isGranted, flags */
        private SparseArray<ArrayMap<String, PermissionState>> mDelayedPermissionState =
                new SparseArray<>();
        /** userId -> context */
        private SparseArray<Context> mUserContexts = new SparseArray<>();
        /** Permission name -> info */
        private ArrayMap<String, PermissionInfo> mPermissionInfos = new ArrayMap<>();
        /** Package name -> info */
        private ArrayMap<String, PackageInfo> mPackageInfos = new ArrayMap<>();

        /**
         * Apply the cached state
         */
        void apply() {
            PackageManager.corkPackageInfoCache();
            for (int uidIdx = 0; uidIdx < mDelayedPermissionState.size(); uidIdx++) {
                for (int permIdx = 0; permIdx < mDelayedPermissionState.valueAt(uidIdx).size();
                        permIdx++) {
                    try {
                        mDelayedPermissionState.valueAt(uidIdx).valueAt(permIdx).apply();
                    } catch (IllegalArgumentException e) {
                        Slog.w(TAG, "Cannot set permission " + mDelayedPermissionState.valueAt(
                                uidIdx).keyAt(permIdx) + " of uid " + mDelayedPermissionState.keyAt(
                                uidIdx), e);
                    }
                }
            }
            PackageManager.uncorkPackageInfoCache();
        }

        void addPackageInfo(@NonNull String packageName, @NonNull PackageInfo pkg) {
            mPackageInfos.put(packageName, pkg);
        }

        private @NonNull Context createContextAsUser(@NonNull UserHandle user) {
            int index = mUserContexts.indexOfKey(user.getIdentifier());
            if (index >= 0) {
                return mUserContexts.valueAt(index);
            }

            Context uc = mContext.createContextAsUser(user, 0);

            mUserContexts.put(user.getIdentifier(), uc);

            return uc;
        }

        private @NonNull PermissionState getPermissionState(@NonNull String permission,
                @NonNull PackageInfo pkg, @NonNull UserHandle user) {
            int uid = UserHandle.getUid(user.getIdentifier(),
                    UserHandle.getAppId(pkg.applicationInfo.uid));
            int uidIdx = mDelayedPermissionState.indexOfKey(uid);

            ArrayMap<String, PermissionState> uidState;
            if (uidIdx >= 0) {
                uidState = mDelayedPermissionState.valueAt(uidIdx);
            } else {
                uidState = new ArrayMap<>();
                mDelayedPermissionState.put(uid, uidState);
            }

            int permIdx = uidState.indexOfKey(permission);

            PermissionState permState;
            if (permIdx >= 0) {
                permState = uidState.valueAt(permIdx);
            } else {
                permState = new PermissionState(permission, pkg, user);
                uidState.put(permission, permState);
            }

            return permState;
        }

        @Override
        public int getPermissionFlags(@NonNull String permission, @NonNull PackageInfo pkg,
                @NonNull UserHandle user) {
            PermissionState state = getPermissionState(permission, pkg, user);
            state.initFlags();
            return state.newFlags;
        }

        @Override
        public void updatePermissionFlags(@NonNull String permission, @NonNull PackageInfo pkg,
                int flagMask, int flagValues, @NonNull UserHandle user) {
            PermissionState state = getPermissionState(permission, pkg, user);
            state.initFlags();
            state.newFlags = (state.newFlags & ~flagMask) | (flagValues & flagMask);
        }

        @Override
        public void grantPermission(@NonNull String permission, @NonNull PackageInfo pkg,
                @NonNull UserHandle user) {
            if (PermissionManager.DEBUG_TRACE_GRANTS
                    && PermissionManager.shouldTraceGrant(
                    pkg.packageName, permission, user.getIdentifier())) {
                Log.i(PermissionManager.LOG_TAG_TRACE_GRANTS,
                        "PregrantPolicy is granting " + pkg.packageName + " "
                                + permission + " for user " + user.getIdentifier(),
                        new RuntimeException());
            }
            PermissionState state = getPermissionState(permission, pkg, user);
            state.initGranted();
            state.newGranted = true;
        }

        @Override
        public void revokePermission(@NonNull String permission, @NonNull PackageInfo pkg,
                @NonNull UserHandle user) {
            PermissionState state = getPermissionState(permission, pkg, user);
            state.initGranted();
            state.newGranted = false;
        }

        @Override
        public boolean isGranted(@NonNull String permission, @NonNull PackageInfo pkg,
                @NonNull UserHandle user) {
            PermissionState state = getPermissionState(permission, pkg, user);
            state.initGranted();
            return state.newGranted;
        }

        @Override
        public @Nullable PermissionInfo getPermissionInfo(@NonNull String permissionName) {
            int index = mPermissionInfos.indexOfKey(permissionName);
            if (index >= 0) {
                return mPermissionInfos.valueAt(index);
            }

            PermissionInfo pi = NO_PM_CACHE.getPermissionInfo(permissionName);
            mPermissionInfos.put(permissionName, pi);

            return pi;
        }

        @Override
        public @Nullable PackageInfo getPackageInfo(@NonNull String pkg) {
            int index = mPackageInfos.indexOfKey(pkg);
            if (index >= 0) {
                return mPackageInfos.valueAt(index);
            }

            PackageInfo pi = NO_PM_CACHE.getPackageInfo(pkg);
            mPackageInfos.put(pkg, pi);

            return pi;
        }

        /**
         * State of a single permission belonging to a single uid
         */
        private class PermissionState {
            private final @NonNull String mPermission;
            private final @NonNull PackageInfo mPkgRequestingPerm;
            private final @NonNull UserHandle mUser;

            /** Permission flags when the state was created */
            private @Nullable Integer mOriginalFlags;
            /** Altered permission flags or {@code null} if no change was requested */
            @Nullable Integer newFlags;

            /** Grant state when the state was created */
            private @Nullable Boolean mOriginalGranted;
            /** Altered grant state or {@code null} if no change was requested */
            @Nullable Boolean newGranted;

            private PermissionState(@NonNull String permission,
                    @NonNull PackageInfo pkgRequestingPerm, @NonNull UserHandle user) {
                mPermission = permission;
                mPkgRequestingPerm = pkgRequestingPerm;
                mUser = user;
            }

            /**
             * Apply the changes to the permission to the system
             */
            void apply() {
                if (DEBUG) {
                    Slog.i(TAG, "Granting " + mPermission + " to user " + mUser.getIdentifier()
                            + " pkg=" + mPkgRequestingPerm.packageName + " granted=" + newGranted
                            + " flags=" + Integer.toBinaryString(newFlags));
                }

                int flagsToAdd = 0;
                int flagsToRemove = 0;
                if (newFlags != null) {
                    flagsToAdd = newFlags & ~mOriginalFlags;
                    flagsToRemove = mOriginalFlags & ~newFlags;
                }

                // Need to remove e.g. SYSTEM_FIXED flags first as otherwise permission cannot be
                // changed
                if (flagsToRemove != 0) {
                    NO_PM_CACHE.updatePermissionFlags(mPermission, mPkgRequestingPerm,
                            flagsToRemove, 0, mUser);
                }

                // Need to unrestrict first as otherwise permission grants might fail
                if ((flagsToAdd & PackageManager.FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT) != 0) {
                    int newRestrictionExcemptFlags =
                            flagsToAdd & PackageManager.FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT;

                    NO_PM_CACHE.updatePermissionFlags(mPermission,
                            mPkgRequestingPerm, newRestrictionExcemptFlags, -1, mUser);
                }

                if (newGranted != null && !Objects.equals(newGranted, mOriginalGranted)) {
                    if (newGranted) {
                        NO_PM_CACHE.grantPermission(mPermission, mPkgRequestingPerm, mUser);
                    } else {
                        NO_PM_CACHE.revokePermission(mPermission, mPkgRequestingPerm, mUser);
                    }
                }

                if ((flagsToAdd & ~PackageManager.FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT) != 0) {
                    int newFlags =
                            flagsToAdd & ~PackageManager.FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT;

                    NO_PM_CACHE.updatePermissionFlags(mPermission, mPkgRequestingPerm, newFlags,
                            -1, mUser);
                }
            }

            /**
             * Load the state of the flags before first use
             */
            void initFlags() {
                if (newFlags == null) {
                    mOriginalFlags = NO_PM_CACHE.getPermissionFlags(mPermission, mPkgRequestingPerm,
                            mUser);
                    newFlags = mOriginalFlags;
                }
            }

            /**
             * Load the grant state before first use
             */
            void initGranted() {
                if (newGranted == null) {
                    // Don't call NO_PM_CACHE here so that contexts are reused
                    mOriginalGranted = createContextAsUser(mUser).getPackageManager()
                            .checkPermission(mPermission, mPkgRequestingPerm.packageName)
                            == PackageManager.PERMISSION_GRANTED;
                    newGranted = mOriginalGranted;
                }
            }
        }
    }

    private static final class DefaultPermissionGrant {
        final String name;
        final boolean fixed;
        final boolean whitelisted;

        public DefaultPermissionGrant(String name, boolean fixed,
                boolean whitelisted) {
            this.name = name;
            this.fixed = fixed;
            this.whitelisted = whitelisted;
        }
    }
}
