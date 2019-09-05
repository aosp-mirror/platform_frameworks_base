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
import android.app.ActivityManager;
import android.app.AppOpsManager;
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
import android.content.pm.PackageManagerInternal.PackagesProvider;
import android.content.pm.PackageManagerInternal.SyncAdapterPackagesProvider;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
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
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.XmlUtils;
import com.android.server.LocalServices;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
public final class DefaultPermissionGrantPolicy {
    private static final String TAG = "DefaultPermGrantPolicy"; // must be <= 23 chars
    private static final boolean DEBUG = false;

    @PackageManager.ResolveInfoFlags
    private static final int DEFAULT_INTENT_QUERY_FLAGS =
            PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                    | PackageManager.MATCH_UNINSTALLED_PACKAGES;

    @PackageManager.PackageInfoFlags
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
    }

    private static final Set<String> STORAGE_PERMISSIONS = new ArraySet<>();
    static {
        STORAGE_PERMISSIONS.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        STORAGE_PERMISSIONS.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
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
    private final PermissionManagerService mPermissionManager;

    @GuardedBy("mLock")
    private SparseIntArray mDefaultPermissionsGrantedUsers = new SparseIntArray();

    DefaultPermissionGrantPolicy(Context context, Looper looper,
            @NonNull PermissionManagerService permissionManager) {
        mContext = context;
        mHandler = new Handler(looper) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == MSG_READ_DEFAULT_PERMISSION_EXCEPTIONS) {
                    synchronized (mLock) {
                        if (mGrantExceptions == null) {
                            mGrantExceptions = readDefaultPermissionExceptionsLocked();
                        }
                    }
                }
            }
        };
        mPermissionManager = permissionManager;
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

    public boolean wereDefaultPermissionsGrantedSinceBoot(int userId) {
        synchronized (mLock) {
            return mDefaultPermissionsGrantedUsers.indexOfKey(userId) >= 0;
        }
    }

    public void grantDefaultPermissions(int userId) {
        grantPermissionsToSysComponentsAndPrivApps(userId);
        grantDefaultSystemHandlerPermissions(userId);
        grantDefaultPermissionExceptions(userId);
        synchronized (mLock) {
            mDefaultPermissionsGrantedUsers.put(userId, userId);
        }
    }

    private void grantRuntimePermissionsForSystemPackage(int userId, PackageInfo pkg) {
        Set<String> permissions = new ArraySet<>();
        for (String permission : pkg.requestedPermissions) {
            final BasePermission bp = mPermissionManager.getPermission(permission);
            if (bp == null) {
                continue;
            }
            if (bp.isRuntime()) {
                permissions.add(permission);
            }
        }
        if (!permissions.isEmpty()) {
            grantRuntimePermissions(pkg, permissions, true /*systemFixed*/, userId);
        }
    }

    public void scheduleReadDefaultPermissionExceptions() {
        mHandler.sendEmptyMessage(MSG_READ_DEFAULT_PERMISSION_EXCEPTIONS);
    }

    private void grantPermissionsToSysComponentsAndPrivApps(int userId) {
        Log.i(TAG, "Granting permissions to platform components for user " + userId);
        List<PackageInfo> packages = mContext.getPackageManager().getInstalledPackagesAsUser(
                DEFAULT_PACKAGE_INFO_QUERY_FLAGS, UserHandle.USER_SYSTEM);
        for (PackageInfo pkg : packages) {
            if (pkg == null) {
                continue;
            }
            if (!isSysComponentOrPersistentPlatformSignedPrivApp(pkg)
                    || !doesPackageSupportRuntimePermissions(pkg)
                    || ArrayUtils.isEmpty(pkg.requestedPermissions)) {
                continue;
            }
            grantRuntimePermissionsForSystemPackage(userId, pkg);
        }
    }

    @SafeVarargs
    private final void grantIgnoringSystemPackage(String packageName, int userId,
            Set<String>... permissionGroups) {
        grantPermissionsToPackage(packageName, userId, true /* ignoreSystemPackage */,
                true /*whitelistRestrictedPermissions*/, permissionGroups);
    }

    @SafeVarargs
    private final void grantSystemFixedPermissionsToSystemPackage(String packageName, int userId,
            Set<String>... permissionGroups) {
        grantPermissionsToSystemPackage(
                packageName, userId, true /* systemFixed */, permissionGroups);
    }

    @SafeVarargs
    private final void grantPermissionsToSystemPackage(
            String packageName, int userId, Set<String>... permissionGroups) {
        grantPermissionsToSystemPackage(
                packageName, userId, false /* systemFixed */, permissionGroups);
    }

    @SafeVarargs
    private final void grantPermissionsToSystemPackage(String packageName, int userId,
            boolean systemFixed, Set<String>... permissionGroups) {
        if (!isSystemPackage(packageName)) {
            return;
        }
        grantPermissionsToPackage(getSystemPackageInfo(packageName),
                userId, systemFixed, false /* ignoreSystemPackage */,
                true /*whitelistRestrictedPermissions*/, permissionGroups);
    }

    @SafeVarargs
    private final void grantPermissionsToPackage(String packageName, int userId,
            boolean ignoreSystemPackage, boolean whitelistRestrictedPermissions,
            Set<String>... permissionGroups) {
        grantPermissionsToPackage(getPackageInfo(packageName),
                userId, false /* systemFixed */, ignoreSystemPackage,
                whitelistRestrictedPermissions, permissionGroups);
    }

    @SafeVarargs
    private final void grantPermissionsToPackage(PackageInfo packageInfo, int userId,
            boolean systemFixed, boolean ignoreSystemPackage,
            boolean whitelistRestrictedPermissions, Set<String>... permissionGroups) {
        if (packageInfo == null) {
            return;
        }
        if (doesPackageSupportRuntimePermissions(packageInfo)) {
            for (Set<String> permissionGroup : permissionGroups) {
                grantRuntimePermissions(packageInfo, permissionGroup, systemFixed,
                        ignoreSystemPackage, whitelistRestrictedPermissions, userId);
            }
        }
    }

    private void grantDefaultSystemHandlerPermissions(int userId) {
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

        // Installer
        grantSystemFixedPermissionsToSystemPackage(
                getKnownPackage(PackageManagerInternal.PACKAGE_INSTALLER, userId),
                userId, STORAGE_PERMISSIONS);

        // Verifier
        final String verifier = getKnownPackage(PackageManagerInternal.PACKAGE_VERIFIER, userId);
        grantSystemFixedPermissionsToSystemPackage(verifier, userId, STORAGE_PERMISSIONS);
        grantPermissionsToSystemPackage(verifier, userId, PHONE_PERMISSIONS, SMS_PERMISSIONS);

        // SetupWizard
        grantPermissionsToSystemPackage(
                getKnownPackage(PackageManagerInternal.PACKAGE_SETUP_WIZARD, userId), userId,
                PHONE_PERMISSIONS, CONTACTS_PERMISSIONS, ALWAYS_LOCATION_PERMISSIONS,
                CAMERA_PERMISSIONS);

        // Camera
        grantPermissionsToSystemPackage(
                getDefaultSystemHandlerActivityPackage(MediaStore.ACTION_IMAGE_CAPTURE, userId),
                userId, CAMERA_PERMISSIONS, MICROPHONE_PERMISSIONS, STORAGE_PERMISSIONS);

        // Sound recorder
        grantPermissionsToSystemPackage(
                getDefaultSystemHandlerActivityPackage(
                        MediaStore.Audio.Media.RECORD_SOUND_ACTION, userId),
                userId, MICROPHONE_PERMISSIONS);

        // Media provider
        grantSystemFixedPermissionsToSystemPackage(
                getDefaultProviderAuthorityPackage(MediaStore.AUTHORITY, userId), userId,
                STORAGE_PERMISSIONS, PHONE_PERMISSIONS);

        // Downloads provider
        grantSystemFixedPermissionsToSystemPackage(
                getDefaultProviderAuthorityPackage("downloads", userId), userId,
                STORAGE_PERMISSIONS);

        // Downloads UI
        grantSystemFixedPermissionsToSystemPackage(
                getDefaultSystemHandlerActivityPackage(
                        DownloadManager.ACTION_VIEW_DOWNLOADS, userId),
                userId, STORAGE_PERMISSIONS);

        // Storage provider
        grantSystemFixedPermissionsToSystemPackage(
                getDefaultProviderAuthorityPackage("com.android.externalstorage.documents", userId),
                userId, STORAGE_PERMISSIONS);

        // CertInstaller
        grantSystemFixedPermissionsToSystemPackage(
                getDefaultSystemHandlerActivityPackage(Credentials.INSTALL_ACTION, userId), userId,
                STORAGE_PERMISSIONS);

        // Dialer
        if (dialerAppPackageNames == null) {
            String dialerPackage =
                    getDefaultSystemHandlerActivityPackage(Intent.ACTION_DIAL, userId);
            grantDefaultPermissionsToDefaultSystemDialerApp(dialerPackage, userId);
        } else {
            for (String dialerAppPackageName : dialerAppPackageNames) {
                grantDefaultPermissionsToDefaultSystemDialerApp(dialerAppPackageName, userId);
            }
        }

        // Sim call manager
        if (simCallManagerPackageNames != null) {
            for (String simCallManagerPackageName : simCallManagerPackageNames) {
                grantDefaultPermissionsToDefaultSystemSimCallManager(
                        simCallManagerPackageName, userId);
            }
        }

        // Use Open Wifi
        if (useOpenWifiAppPackageNames != null) {
            for (String useOpenWifiPackageName : useOpenWifiAppPackageNames) {
                grantDefaultPermissionsToDefaultSystemUseOpenWifiApp(
                        useOpenWifiPackageName, userId);
            }
        }

        // SMS
        if (smsAppPackageNames == null) {
            String smsPackage = getDefaultSystemHandlerActivityPackageForCategory(
                    Intent.CATEGORY_APP_MESSAGING, userId);
            grantDefaultPermissionsToDefaultSystemSmsApp(smsPackage, userId);
        } else {
            for (String smsPackage : smsAppPackageNames) {
                grantDefaultPermissionsToDefaultSystemSmsApp(smsPackage, userId);
            }
        }

        // Cell Broadcast Receiver
        grantSystemFixedPermissionsToSystemPackage(
                getDefaultSystemHandlerActivityPackage(Intents.SMS_CB_RECEIVED_ACTION, userId),
                userId, SMS_PERMISSIONS);

        // Carrier Provisioning Service
        grantPermissionsToSystemPackage(
                getDefaultSystemHandlerServicePackage(Intents.SMS_CARRIER_PROVISION_ACTION, userId),
                userId, SMS_PERMISSIONS);

        // Calendar
        grantPermissionsToSystemPackage(
                getDefaultSystemHandlerActivityPackageForCategory(
                        Intent.CATEGORY_APP_CALENDAR, userId),
                userId, CALENDAR_PERMISSIONS, CONTACTS_PERMISSIONS);

        // Calendar provider
        String calendarProvider =
                getDefaultProviderAuthorityPackage(CalendarContract.AUTHORITY, userId);
        grantPermissionsToSystemPackage(calendarProvider, userId,
                CONTACTS_PERMISSIONS, STORAGE_PERMISSIONS);
        grantSystemFixedPermissionsToSystemPackage(calendarProvider, userId, CALENDAR_PERMISSIONS);

        // Calendar provider sync adapters
        grantPermissionToEachSystemPackage(
                getHeadlessSyncAdapterPackages(calendarSyncAdapterPackages, userId),
                userId, CALENDAR_PERMISSIONS);

        // Contacts
        grantPermissionsToSystemPackage(
                getDefaultSystemHandlerActivityPackageForCategory(
                        Intent.CATEGORY_APP_CONTACTS, userId),
                userId, CONTACTS_PERMISSIONS, PHONE_PERMISSIONS);

        // Contacts provider sync adapters
        grantPermissionToEachSystemPackage(
                getHeadlessSyncAdapterPackages(contactsSyncAdapterPackages, userId),
                userId, CONTACTS_PERMISSIONS);

        // Contacts provider
        String contactsProviderPackage =
                getDefaultProviderAuthorityPackage(ContactsContract.AUTHORITY, userId);
        grantSystemFixedPermissionsToSystemPackage(contactsProviderPackage, userId,
                CONTACTS_PERMISSIONS, PHONE_PERMISSIONS);
        grantPermissionsToSystemPackage(contactsProviderPackage, userId, STORAGE_PERMISSIONS);

        // Device provisioning
        grantPermissionsToSystemPackage(
                getDefaultSystemHandlerActivityPackage(
                        DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE, userId),
                userId, CONTACTS_PERMISSIONS);

        // Maps
        grantPermissionsToSystemPackage(
                getDefaultSystemHandlerActivityPackageForCategory(Intent.CATEGORY_APP_MAPS, userId),
                userId, ALWAYS_LOCATION_PERMISSIONS);

        // Gallery
        grantPermissionsToSystemPackage(
                getDefaultSystemHandlerActivityPackageForCategory(
                        Intent.CATEGORY_APP_GALLERY, userId),
                userId, STORAGE_PERMISSIONS);

        // Email
        grantPermissionsToSystemPackage(
                getDefaultSystemHandlerActivityPackageForCategory(
                        Intent.CATEGORY_APP_EMAIL, userId),
                userId, CONTACTS_PERMISSIONS, CALENDAR_PERMISSIONS);

        // Browser
        String browserPackage = getKnownPackage(PackageManagerInternal.PACKAGE_BROWSER, userId);
        if (browserPackage == null) {
            browserPackage = getDefaultSystemHandlerActivityPackageForCategory(
                    Intent.CATEGORY_APP_BROWSER, userId);
            if (!isSystemPackage(browserPackage)) {
                browserPackage = null;
            }
        }
        grantPermissionsToPackage(browserPackage, userId, false /* ignoreSystemPackage */,
                true /*whitelistRestrictedPermissions*/, ALWAYS_LOCATION_PERMISSIONS);

        // Voice interaction
        if (voiceInteractPackageNames != null) {
            for (String voiceInteractPackageName : voiceInteractPackageNames) {
                grantPermissionsToSystemPackage(voiceInteractPackageName, userId,
                        CONTACTS_PERMISSIONS, CALENDAR_PERMISSIONS, MICROPHONE_PERMISSIONS,
                        PHONE_PERMISSIONS, SMS_PERMISSIONS, ALWAYS_LOCATION_PERMISSIONS);
            }
        }

        if (ActivityManager.isLowRamDeviceStatic()) {
            // Allow voice search on low-ram devices
            grantPermissionsToSystemPackage(
                    getDefaultSystemHandlerActivityPackage(
                            SearchManager.INTENT_ACTION_GLOBAL_SEARCH, userId),
                    userId, MICROPHONE_PERMISSIONS, ALWAYS_LOCATION_PERMISSIONS);
        }

        // Voice recognition
        Intent voiceRecoIntent = new Intent(RecognitionService.SERVICE_INTERFACE)
                .addCategory(Intent.CATEGORY_DEFAULT);
        grantPermissionsToSystemPackage(
                getDefaultSystemHandlerServicePackage(voiceRecoIntent, userId), userId,
                MICROPHONE_PERMISSIONS);

        // Location
        if (locationPackageNames != null) {
            for (String packageName : locationPackageNames) {
                grantPermissionsToSystemPackage(packageName, userId,
                        CONTACTS_PERMISSIONS, CALENDAR_PERMISSIONS, MICROPHONE_PERMISSIONS,
                        PHONE_PERMISSIONS, SMS_PERMISSIONS, CAMERA_PERMISSIONS,
                        SENSORS_PERMISSIONS, STORAGE_PERMISSIONS);
                grantSystemFixedPermissionsToSystemPackage(packageName, userId,
                        ALWAYS_LOCATION_PERMISSIONS, ACTIVITY_RECOGNITION_PERMISSIONS);
            }
        }
        if (locationExtraPackageNames != null) {
            // Also grant location permission to location extra packages.
            for (String packageName : locationExtraPackageNames) {
                grantPermissionsToSystemPackage(packageName, userId, ALWAYS_LOCATION_PERMISSIONS);
            }
        }

        // Music
        Intent musicIntent = new Intent(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .setDataAndType(Uri.fromFile(new File("foo.mp3")), AUDIO_MIME_TYPE);
        grantPermissionsToSystemPackage(
                getDefaultSystemHandlerActivityPackage(musicIntent, userId), userId,
                STORAGE_PERMISSIONS);

        // Home
        Intent homeIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addCategory(Intent.CATEGORY_LAUNCHER_APP);
        grantPermissionsToSystemPackage(
                getDefaultSystemHandlerActivityPackage(homeIntent, userId), userId,
                ALWAYS_LOCATION_PERMISSIONS);

        // Watches
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH, 0)) {
            // Home application on watches

            String wearPackage = getDefaultSystemHandlerActivityPackageForCategory(
                    Intent.CATEGORY_HOME_MAIN, userId);
            grantPermissionsToSystemPackage(wearPackage, userId,
                    CONTACTS_PERMISSIONS, MICROPHONE_PERMISSIONS, ALWAYS_LOCATION_PERMISSIONS);
            grantSystemFixedPermissionsToSystemPackage(wearPackage, userId, PHONE_PERMISSIONS);

            // Fitness tracking on watches
            grantPermissionsToSystemPackage(
                    getDefaultSystemHandlerActivityPackage(ACTION_TRACK, userId), userId,
                    SENSORS_PERMISSIONS, ALWAYS_LOCATION_PERMISSIONS);
        }

        // Print Spooler
        grantSystemFixedPermissionsToSystemPackage(PrintManager.PRINT_SPOOLER_PACKAGE_NAME, userId,
                ALWAYS_LOCATION_PERMISSIONS);

        // EmergencyInfo
        grantSystemFixedPermissionsToSystemPackage(
                getDefaultSystemHandlerActivityPackage(
                        TelephonyManager.ACTION_EMERGENCY_ASSISTANCE, userId),
                userId, CONTACTS_PERMISSIONS, PHONE_PERMISSIONS);

        // NFC Tag viewer
        Intent nfcTagIntent = new Intent(Intent.ACTION_VIEW)
                .setType("vnd.android.cursor.item/ndef_msg");
        grantPermissionsToSystemPackage(
                getDefaultSystemHandlerActivityPackage(nfcTagIntent, userId), userId,
                CONTACTS_PERMISSIONS, PHONE_PERMISSIONS);

        // Storage Manager
        grantSystemFixedPermissionsToSystemPackage(
                getDefaultSystemHandlerActivityPackage(
                        StorageManager.ACTION_MANAGE_STORAGE, userId),
                userId, STORAGE_PERMISSIONS);

        // Companion devices
        grantSystemFixedPermissionsToSystemPackage(
                CompanionDeviceManager.COMPANION_DEVICE_DISCOVERY_PACKAGE_NAME, userId,
                ALWAYS_LOCATION_PERMISSIONS);

        // Ringtone Picker
        grantSystemFixedPermissionsToSystemPackage(
                getDefaultSystemHandlerActivityPackage(
                        RingtoneManager.ACTION_RINGTONE_PICKER, userId),
                userId, STORAGE_PERMISSIONS);

        // TextClassifier Service
        String textClassifierPackageName =
                mContext.getPackageManager().getSystemTextClassifierPackageName();
        if (!TextUtils.isEmpty(textClassifierPackageName)) {
            grantPermissionsToSystemPackage(textClassifierPackageName, userId,
                    PHONE_PERMISSIONS, SMS_PERMISSIONS, CALENDAR_PERMISSIONS,
                    ALWAYS_LOCATION_PERMISSIONS, CONTACTS_PERMISSIONS);
        }

        // Atthention Service
        String attentionServicePackageName =
                mContext.getPackageManager().getAttentionServicePackageName();
        if (!TextUtils.isEmpty(attentionServicePackageName)) {
            grantPermissionsToSystemPackage(attentionServicePackageName, userId,
                    CAMERA_PERMISSIONS);
        }

        // There is no real "marker" interface to identify the shared storage backup, it is
        // hardcoded in BackupManagerService.SHARED_BACKUP_AGENT_PACKAGE.
        grantSystemFixedPermissionsToSystemPackage("com.android.sharedstoragebackup", userId,
                STORAGE_PERMISSIONS);

        // System Captions Service
        String systemCaptionsServicePackageName =
                mContext.getPackageManager().getSystemCaptionsServicePackageName();
        if (!TextUtils.isEmpty(systemCaptionsServicePackageName)) {
            grantPermissionsToSystemPackage(systemCaptionsServicePackageName, userId,
                    MICROPHONE_PERMISSIONS);
        }
    }

    private String getDefaultSystemHandlerActivityPackageForCategory(String category, int userId) {
        return getDefaultSystemHandlerActivityPackage(
                new Intent(Intent.ACTION_MAIN).addCategory(category), userId);
    }

    @SafeVarargs
    private final void grantPermissionToEachSystemPackage(
            ArrayList<String> packages, int userId, Set<String>... permissions) {
        if (packages == null) return;
        final int count = packages.size();
        for (int i = 0; i < count; i++) {
            grantPermissionsToSystemPackage(packages.get(i), userId, permissions);
        }
    }

    private String getKnownPackage(int knownPkgId, int userId) {
        return mServiceInternal.getKnownPackageName(knownPkgId, userId);
    }

    private void grantDefaultPermissionsToDefaultSystemDialerApp(
            String dialerPackage, int userId) {
        if (dialerPackage == null) {
            return;
        }
        boolean isPhonePermFixed =
                mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH, 0);
        if (isPhonePermFixed) {
            grantSystemFixedPermissionsToSystemPackage(dialerPackage, userId, PHONE_PERMISSIONS);
        } else {
            grantPermissionsToSystemPackage(dialerPackage, userId, PHONE_PERMISSIONS);
        }
        grantPermissionsToSystemPackage(dialerPackage, userId,
                CONTACTS_PERMISSIONS, SMS_PERMISSIONS, MICROPHONE_PERMISSIONS, CAMERA_PERMISSIONS);
    }

    private void grantDefaultPermissionsToDefaultSystemSmsApp(String smsPackage, int userId) {
        grantPermissionsToSystemPackage(smsPackage, userId,
                PHONE_PERMISSIONS, CONTACTS_PERMISSIONS, SMS_PERMISSIONS,
                STORAGE_PERMISSIONS, MICROPHONE_PERMISSIONS, CAMERA_PERMISSIONS);
    }

    private void grantDefaultPermissionsToDefaultSystemUseOpenWifiApp(
            String useOpenWifiPackage, int userId) {
        grantPermissionsToSystemPackage(useOpenWifiPackage, userId, ALWAYS_LOCATION_PERMISSIONS);
    }

    public void grantDefaultPermissionsToDefaultUseOpenWifiApp(String packageName, int userId) {
        Log.i(TAG, "Granting permissions to default Use Open WiFi app for user:" + userId);
        grantIgnoringSystemPackage(packageName, userId, ALWAYS_LOCATION_PERMISSIONS);
    }

    public void grantDefaultPermissionsToDefaultSimCallManager(String packageName, int userId) {
        if (packageName == null) {
            return;
        }
        Log.i(TAG, "Granting permissions to sim call manager for user:" + userId);
        grantPermissionsToPackage(packageName, userId, false /* ignoreSystemPackage */,
                true /*whitelistRestrictedPermissions*/, PHONE_PERMISSIONS, MICROPHONE_PERMISSIONS);
    }

    private void grantDefaultPermissionsToDefaultSystemSimCallManager(
            String packageName, int userId) {
        if (isSystemPackage(packageName)) {
            grantDefaultPermissionsToDefaultSimCallManager(packageName, userId);
        }
    }

    public void grantDefaultPermissionsToEnabledCarrierApps(String[] packageNames, int userId) {
        Log.i(TAG, "Granting permissions to enabled carrier apps for user:" + userId);
        if (packageNames == null) {
            return;
        }
        for (String packageName : packageNames) {
            grantPermissionsToSystemPackage(packageName, userId,
                    PHONE_PERMISSIONS, ALWAYS_LOCATION_PERMISSIONS, SMS_PERMISSIONS);
        }
    }

    public void grantDefaultPermissionsToEnabledImsServices(String[] packageNames, int userId) {
        Log.i(TAG, "Granting permissions to enabled ImsServices for user:" + userId);
        if (packageNames == null) {
            return;
        }
        for (String packageName : packageNames) {
            grantPermissionsToSystemPackage(packageName, userId,
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
            grantSystemFixedPermissionsToSystemPackage(packageName, userId,
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
            PackageInfo pkg = getSystemPackageInfo(packageName);
            if (isSystemPackage(pkg) && doesPackageSupportRuntimePermissions(pkg)) {
                revokeRuntimePermissions(packageName, PHONE_PERMISSIONS, true, userId);
                revokeRuntimePermissions(packageName, ALWAYS_LOCATION_PERMISSIONS, true, userId);
            }
        }
    }

    public void grantDefaultPermissionsToActiveLuiApp(String packageName, int userId) {
        Log.i(TAG, "Granting permissions to active LUI app for user:" + userId);
        grantSystemFixedPermissionsToSystemPackage(packageName, userId, CAMERA_PERMISSIONS);
    }

    public void revokeDefaultPermissionsFromLuiApps(String[] packageNames, int userId) {
        Log.i(TAG, "Revoke permissions from LUI apps for user:" + userId);
        if (packageNames == null) {
            return;
        }
        for (String packageName : packageNames) {
            PackageInfo pkg = getSystemPackageInfo(packageName);
            if (isSystemPackage(pkg) && doesPackageSupportRuntimePermissions(pkg)) {
                revokeRuntimePermissions(packageName, CAMERA_PERMISSIONS, true, userId);
            }
        }
    }

    public void grantDefaultPermissionsToDefaultBrowser(String packageName, int userId) {
        Log.i(TAG, "Granting permissions to default browser for user:" + userId);
        grantPermissionsToSystemPackage(packageName, userId, ALWAYS_LOCATION_PERMISSIONS);
    }

    private String getDefaultSystemHandlerActivityPackage(String intentAction, int userId) {
        return getDefaultSystemHandlerActivityPackage(new Intent(intentAction), userId);
    }

    private String getDefaultSystemHandlerActivityPackage(Intent intent, int userId) {
        ResolveInfo handler = mContext.getPackageManager().resolveActivityAsUser(
                intent, DEFAULT_INTENT_QUERY_FLAGS, userId);
        if (handler == null || handler.activityInfo == null) {
            return null;
        }
        if (mServiceInternal.isResolveActivityComponent(handler.activityInfo)) {
            return null;
        }
        String packageName = handler.activityInfo.packageName;
        return isSystemPackage(packageName) ? packageName : null;
    }

    private String getDefaultSystemHandlerServicePackage(String intentAction, int userId) {
        return getDefaultSystemHandlerServicePackage(new Intent(intentAction), userId);
    }

    private String getDefaultSystemHandlerServicePackage(
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
            if (isSystemPackage(handlerPackage)) {
                return handlerPackage;
            }
        }
        return null;
    }

    private ArrayList<String> getHeadlessSyncAdapterPackages(
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

            if (isSystemPackage(syncAdapterPackageName)) {
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

    private boolean isSystemPackage(String packageName) {
        return isSystemPackage(getPackageInfo(packageName));
    }

    private boolean isSystemPackage(PackageInfo pkg) {
        if (pkg == null) {
            return false;
        }
        return pkg.applicationInfo.isSystemApp()
                && !isSysComponentOrPersistentPlatformSignedPrivApp(pkg);
    }

    private void grantRuntimePermissions(PackageInfo pkg, Set<String> permissions,
            boolean systemFixed, int userId) {
        grantRuntimePermissions(pkg, permissions, systemFixed, false,
                true /*whitelistRestrictedPermissions*/, userId);
    }

    private void revokeRuntimePermissions(String packageName, Set<String> permissions,
            boolean systemFixed, int userId) {
        PackageInfo pkg = getSystemPackageInfo(packageName);
        if (ArrayUtils.isEmpty(pkg.requestedPermissions)) {
            return;
        }
        Set<String> revokablePermissions = new ArraySet<>(Arrays.asList(pkg.requestedPermissions));

        for (String permission : permissions) {
            // We can't revoke what wasn't requested.
            if (!revokablePermissions.contains(permission)) {
                continue;
            }

            UserHandle user = UserHandle.of(userId);
            final int flags = mContext.getPackageManager()
                    .getPermissionFlags(permission, packageName, user);

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
            mContext.getPackageManager().revokeRuntimePermission(packageName, permission, user);

            if (DEBUG) {
                Log.i(TAG, "revoked " + (systemFixed ? "fixed " : "not fixed ")
                        + permission + " to " + packageName);
            }

            // Remove the GRANTED_BY_DEFAULT flag without touching the others.
            // Note that we do not revoke FLAG_PERMISSION_SYSTEM_FIXED. That bit remains
            // sticky once set.
            mContext.getPackageManager().updatePermissionFlags(permission, packageName,
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

    /**
     * Return the background permission for a permission.
     *
     * @param permission The name of the foreground permission
     *
     * @return The name of the background permission or {@code null} if the permission has no
     *         background permission
     */
    private @Nullable String getBackgroundPermission(@NonNull String permission) {
        try {
            return mContext.getPackageManager().getPermissionInfo(permission,
                    0).backgroundPermission;
        } catch (NameNotFoundException e) {
            return null;
        }
    }

    private void grantRuntimePermissions(PackageInfo pkg, Set<String> permissionsWithoutSplits,
            boolean systemFixed, boolean ignoreSystemPackage,
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
        String[] requestedByNonSystemPackage = getPackageInfo(pkg.packageName).requestedPermissions;
        int size = requestedPermissions.length;
        for (int i = 0; i < size; i++) {
            if (!ArrayUtils.contains(requestedByNonSystemPackage, requestedPermissions[i])) {
                requestedPermissions[i] = null;
            }
        }
        requestedPermissions = ArrayUtils.filterNotNull(requestedPermissions, String[]::new);

        PackageManager pm;
        try {
            pm = mContext.createPackageContextAsUser(mContext.getPackageName(), 0,
                    user).getPackageManager();
        } catch (NameNotFoundException doesNotHappen) {
            throw new IllegalStateException(doesNotHappen);
        }

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
            final PackageInfo disabledPkg = getSystemPackageInfo(
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
            if (getBackgroundPermission(permission) != null) {
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
                final int flags = mContext.getPackageManager().getPermissionFlags(
                        permission, pkg.packageName, user);

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

                    // Preserve whitelisting flags.
                    newFlags |= (flags & PackageManager.FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT);

                    // If we are whitelisting the permission, update the exempt flag before grant.
                    if (whitelistRestrictedPermissions && isPermissionRestricted(permission)) {
                        mContext.getPackageManager().updatePermissionFlags(permission,
                                pkg.packageName,
                                PackageManager.FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT,
                                PackageManager.FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT, user);
                    }

                    // If the system tries to change a system fixed permission from one fixed
                    // state to another we need to drop the fixed flag to allow the grant.
                    if (changingGrantForSystemFixed) {
                        mContext.getPackageManager().updatePermissionFlags(permission,
                                pkg.packageName, flags,
                                flags & ~PackageManager.FLAG_PERMISSION_SYSTEM_FIXED, user);
                    }

                    if (pm.checkPermission(permission, pkg.packageName)
                            != PackageManager.PERMISSION_GRANTED) {
                        mContext.getPackageManager()
                                .grantRuntimePermission(pkg.packageName, permission, user);
                    }

                    mContext.getPackageManager().updatePermissionFlags(permission, pkg.packageName,
                            newFlags, newFlags, user);

                    int uid = UserHandle.getUid(userId,
                            UserHandle.getAppId(pkg.applicationInfo.uid));

                    List<String> fgPerms = mPermissionManager.getBackgroundPermissions()
                            .get(permission);
                    if (fgPerms != null) {
                        int numFgPerms = fgPerms.size();
                        for (int fgPermNum = 0; fgPermNum < numFgPerms; fgPermNum++) {
                            String fgPerm = fgPerms.get(fgPermNum);

                            if (pm.checkPermission(fgPerm, pkg.packageName)
                                    == PackageManager.PERMISSION_GRANTED) {
                                // Upgrade the app-op state of the fg permission to allow bg access
                                // TODO: Dont' call app ops from package manager code.
                                mContext.getSystemService(AppOpsManager.class).setUidMode(
                                        AppOpsManager.permissionToOp(fgPerm), uid,
                                        AppOpsManager.MODE_ALLOWED);

                                break;
                            }
                        }
                    }

                    String bgPerm = getBackgroundPermission(permission);
                    String op = AppOpsManager.permissionToOp(permission);
                    if (bgPerm == null) {
                        if (op != null) {
                            // TODO: Dont' call app ops from package manager code.
                            mContext.getSystemService(AppOpsManager.class).setUidMode(op, uid,
                                    AppOpsManager.MODE_ALLOWED);
                        }
                    } else {
                        int mode;
                        if (pm.checkPermission(bgPerm, pkg.packageName)
                                == PackageManager.PERMISSION_GRANTED) {
                            mode = AppOpsManager.MODE_ALLOWED;
                        } else {
                            mode = AppOpsManager.MODE_FOREGROUND;
                        }

                        mContext.getSystemService(AppOpsManager.class).setUidMode(op, uid, mode);
                    }

                    if (DEBUG) {
                        Log.i(TAG, "Granted " + (systemFixed ? "fixed " : "not fixed ")
                                + permission + " to default handler " + pkg);

                        int appOp = AppOpsManager.permissionToOpCode(permission);
                        if (appOp != AppOpsManager.OP_NONE
                                && AppOpsManager.opToDefaultMode(appOp)
                                        != AppOpsManager.MODE_ALLOWED) {
                            // Permission has a corresponding appop which is not allowed by default
                            // We must allow it as well, as it's usually checked alongside the
                            // permission
                            if (DEBUG) {
                                Log.i(TAG, "Granting OP_" + AppOpsManager.opToName(appOp)
                                        + " to " + pkg.packageName);
                            }
                            mContext.getSystemService(AppOpsManager.class).setUidMode(
                                    appOp, pkg.applicationInfo.uid, AppOpsManager.MODE_ALLOWED);
                        }
                    }
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
                    mContext.getPackageManager().updatePermissionFlags(permission, pkg.packageName,
                            PackageManager.FLAG_PERMISSION_SYSTEM_FIXED, 0, user);
                }
            }
        }
    }

    private PackageInfo getSystemPackageInfo(String pkg) {
        return getPackageInfo(pkg, PackageManager.MATCH_SYSTEM_ONLY);
    }

    private PackageInfo getPackageInfo(String pkg) {
        return getPackageInfo(pkg, 0 /* extraFlags */);
    }

    private PackageInfo getPackageInfo(String pkg,
            @PackageManager.PackageInfoFlags int extraFlags) {
        if (pkg == null) {
            return null;
        }
        try {
            return mContext.getPackageManager().getPackageInfo(pkg,
                    DEFAULT_PACKAGE_INFO_QUERY_FLAGS | extraFlags);
        } catch (NameNotFoundException e) {
            Slog.e(TAG, "PackageNot found: " + pkg, e);
            return null;
        }
    }

    private boolean isSysComponentOrPersistentPlatformSignedPrivApp(PackageInfo pkg) {
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

    private void grantDefaultPermissionExceptions(int userId) {
        mHandler.removeMessages(MSG_READ_DEFAULT_PERMISSION_EXCEPTIONS);

        synchronized (mLock) {
            // mGrantExceptions is null only before the first read and then
            // it serves as a cache of the default grants that should be
            // performed for every user. If there is an entry then the app
            // is on the system image and supports runtime permissions.
            if (mGrantExceptions == null) {
                mGrantExceptions = readDefaultPermissionExceptionsLocked();
            }
        }

        Set<String> permissions = null;
        final int exceptionCount = mGrantExceptions.size();
        for (int i = 0; i < exceptionCount; i++) {
            String packageName = mGrantExceptions.keyAt(i);
            PackageInfo pkg = getSystemPackageInfo(packageName);
            List<DefaultPermissionGrant> permissionGrants = mGrantExceptions.valueAt(i);
            final int permissionGrantCount = permissionGrants.size();
            for (int j = 0; j < permissionGrantCount; j++) {
                DefaultPermissionGrant permissionGrant = permissionGrants.get(j);
                if (!isPermissionDangerous(permissionGrant.name)) {
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


                grantRuntimePermissions(pkg, permissions, permissionGrant.fixed,
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
        dir = new File(Environment.getProductServicesDirectory(),
                "etc/default-permissions");
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
            readDefaultPermissionExceptionsLocked() {
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
            try (
                InputStream str = new BufferedInputStream(new FileInputStream(file))
            ) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(str, null);
                parse(parser, grantExceptions);
            } catch (XmlPullParserException | IOException e) {
                Slog.w(TAG, "Error reading default permissions file " + file, e);
            }
        }

        return grantExceptions;
    }

    private void parse(XmlPullParser parser, Map<String, List<DefaultPermissionGrant>>
            outGrantExceptions) throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            if (TAG_EXCEPTIONS.equals(parser.getName())) {
                parseExceptions(parser, outGrantExceptions);
            } else {
                Log.e(TAG, "Unknown tag " + parser.getName());
            }
        }
    }

    private void parseExceptions(XmlPullParser parser, Map<String, List<DefaultPermissionGrant>>
            outGrantExceptions) throws IOException, XmlPullParserException {
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
                    PackageInfo packageInfo = getSystemPackageInfo(packageName);

                    if (packageInfo == null) {
                        Log.w(TAG, "No such package:" + packageName);
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }

                    if (!isSystemPackage(packageInfo)) {
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

    private void parsePermission(XmlPullParser parser, List<DefaultPermissionGrant>
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

                final boolean fixed = XmlUtils.readBooleanAttribute(parser, ATTR_FIXED);
                final boolean whitelisted = XmlUtils.readBooleanAttribute(parser, ATTR_WHITELISTED);

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

    private boolean isPermissionRestricted(String name) {
        try {
            return mContext.getPackageManager().getPermissionInfo(name, 0).isRestricted();
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    private boolean isPermissionDangerous(String name) {
        try {
            final PermissionInfo pi = mContext.getPackageManager().getPermissionInfo(name, 0);
            return (pi.getProtection() == PermissionInfo.PROTECTION_DANGEROUS);
        } catch (NameNotFoundException e) {
            // When unknown assume it's dangerous to be on the safe side
            return true;
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
