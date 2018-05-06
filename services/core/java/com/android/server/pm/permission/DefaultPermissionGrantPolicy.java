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
import android.app.DownloadManager;
import android.app.admin.DevicePolicyManager;
import android.companion.CompanionDeviceManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageList;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageManagerInternal.PackagesProvider;
import android.content.pm.PackageManagerInternal.SyncAdapterPackagesProvider;
import android.content.pm.PackageParser;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.print.PrintManager;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Telephony.Sms.Intents;
import android.security.Credentials;
import android.service.textclassifier.TextClassifierService;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.XmlUtils;
import com.android.server.LocalServices;
import com.android.server.pm.PackageManagerService;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
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

    private static final int DEFAULT_FLAGS =
            PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                    | PackageManager.MATCH_UNINSTALLED_PACKAGES;

    private static final String AUDIO_MIME_TYPE = "audio/mpeg";

    private static final String TAG_EXCEPTIONS = "exceptions";
    private static final String TAG_EXCEPTION = "exception";
    private static final String TAG_PERMISSION = "permission";
    private static final String ATTR_PACKAGE = "package";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_FIXED = "fixed";

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

    private static final Set<String> LOCATION_PERMISSIONS = new ArraySet<>();
    static {
        LOCATION_PERMISSIONS.add(Manifest.permission.ACCESS_FINE_LOCATION);
        LOCATION_PERMISSIONS.add(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private static final Set<String> COARSE_LOCATION_PERMISSIONS = new ArraySet<>();
    static {
        COARSE_LOCATION_PERMISSIONS.add(Manifest.permission.ACCESS_COARSE_LOCATION);
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
    private final DefaultPermissionGrantedCallback mPermissionGrantedCallback;
    public interface DefaultPermissionGrantedCallback {
        /** Callback when permissions have been granted */
        public void onDefaultRuntimePermissionsGranted(int userId);
    }

    public DefaultPermissionGrantPolicy(Context context, Looper looper,
            @Nullable DefaultPermissionGrantedCallback callback,
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
        mPermissionGrantedCallback = callback;
        mPermissionManager = permissionManager;
        mServiceInternal = LocalServices.getService(PackageManagerInternal.class);
    }

    public void setLocationPackagesProvider(PackagesProvider provider) {
        synchronized (mLock) {
            mLocationPackagesProvider = provider;
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
        grantPermissionsToSysComponentsAndPrivApps(userId);
        grantDefaultSystemHandlerPermissions(userId);
        grantDefaultPermissionExceptions(userId);
    }

    private void grantRuntimePermissionsForPackage(int userId, PackageParser.Package pkg) {
        Set<String> permissions = new ArraySet<>();
        for (String permission :  pkg.requestedPermissions) {
            final BasePermission bp = mPermissionManager.getPermission(permission);
            if (bp == null) {
                continue;
            }
            if (bp.isRuntime()) {
                permissions.add(permission);
            }
        }
        if (!permissions.isEmpty()) {
            grantRuntimePermissions(pkg, permissions, true, userId);
        }
    }

    private void grantAllRuntimePermissions(int userId) {
        Log.i(TAG, "Granting all runtime permissions for user " + userId);
        final PackageList packageList = mServiceInternal.getPackageList();
        for (String packageName : packageList.getPackageNames()) {
            final PackageParser.Package pkg = mServiceInternal.getPackage(packageName);
            if (pkg == null) {
                continue;
            }
            grantRuntimePermissionsForPackage(userId, pkg);
        }
    }

    public void scheduleReadDefaultPermissionExceptions() {
        mHandler.sendEmptyMessage(MSG_READ_DEFAULT_PERMISSION_EXCEPTIONS);
    }

    private void grantPermissionsToSysComponentsAndPrivApps(int userId) {
        Log.i(TAG, "Granting permissions to platform components for user " + userId);
        final PackageList packageList = mServiceInternal.getPackageList();
        for (String packageName : packageList.getPackageNames()) {
            final PackageParser.Package pkg = mServiceInternal.getPackage(packageName);
            if (pkg == null) {
                continue;
            }
            if (!isSysComponentOrPersistentPlatformSignedPrivApp(pkg)
                    || !doesPackageSupportRuntimePermissions(pkg)
                    || pkg.requestedPermissions.isEmpty()) {
                continue;
            }
            grantRuntimePermissionsForPackage(userId, pkg);
        }
    }

    private void grantDefaultSystemHandlerPermissions(int userId) {
        Log.i(TAG, "Granting permissions to default platform handlers for user " + userId);

        final PackagesProvider locationPackagesProvider;
        final PackagesProvider voiceInteractionPackagesProvider;
        final PackagesProvider smsAppPackagesProvider;
        final PackagesProvider dialerAppPackagesProvider;
        final PackagesProvider simCallManagerPackagesProvider;
        final PackagesProvider useOpenWifiAppPackagesProvider;
        final SyncAdapterPackagesProvider syncAdapterPackagesProvider;

        synchronized (mLock) {
            locationPackagesProvider = mLocationPackagesProvider;
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
        final String installerPackageName = mServiceInternal.getKnownPackageName(
                PackageManagerInternal.PACKAGE_INSTALLER, userId);
        PackageParser.Package installerPackage = getSystemPackage(installerPackageName);
        if (installerPackage != null
                && doesPackageSupportRuntimePermissions(installerPackage)) {
            grantRuntimePermissions(installerPackage, STORAGE_PERMISSIONS, true, userId);
        }

        // Verifier
        final String verifierPackageName = mServiceInternal.getKnownPackageName(
                PackageManagerInternal.PACKAGE_VERIFIER, userId);
        PackageParser.Package verifierPackage = getSystemPackage(verifierPackageName);
        if (verifierPackage != null
                && doesPackageSupportRuntimePermissions(verifierPackage)) {
            grantRuntimePermissions(verifierPackage, STORAGE_PERMISSIONS, true, userId);
            grantRuntimePermissions(verifierPackage, PHONE_PERMISSIONS, false, userId);
            grantRuntimePermissions(verifierPackage, SMS_PERMISSIONS, false, userId);
        }

        // SetupWizard
        final String setupWizardPackageName = mServiceInternal.getKnownPackageName(
                PackageManagerInternal.PACKAGE_SETUP_WIZARD, userId);
        PackageParser.Package setupPackage = getSystemPackage(setupWizardPackageName);
        if (setupPackage != null
                && doesPackageSupportRuntimePermissions(setupPackage)) {
            grantRuntimePermissions(setupPackage, PHONE_PERMISSIONS, userId);
            grantRuntimePermissions(setupPackage, CONTACTS_PERMISSIONS, userId);
            grantRuntimePermissions(setupPackage, LOCATION_PERMISSIONS, userId);
            grantRuntimePermissions(setupPackage, CAMERA_PERMISSIONS, userId);
        }

        // Camera
        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        PackageParser.Package cameraPackage = getDefaultSystemHandlerActivityPackage(
                cameraIntent, userId);
        if (cameraPackage != null
                && doesPackageSupportRuntimePermissions(cameraPackage)) {
            grantRuntimePermissions(cameraPackage, CAMERA_PERMISSIONS, userId);
            grantRuntimePermissions(cameraPackage, MICROPHONE_PERMISSIONS, userId);
            grantRuntimePermissions(cameraPackage, STORAGE_PERMISSIONS, userId);
        }

        // Media provider
        PackageParser.Package mediaStorePackage = getDefaultProviderAuthorityPackage(
                MediaStore.AUTHORITY, userId);
        if (mediaStorePackage != null) {
            grantRuntimePermissions(mediaStorePackage, STORAGE_PERMISSIONS, true, userId);
            grantRuntimePermissions(mediaStorePackage, PHONE_PERMISSIONS, true, userId);
        }

        // Downloads provider
        PackageParser.Package downloadsPackage = getDefaultProviderAuthorityPackage(
                "downloads", userId);
        if (downloadsPackage != null) {
            grantRuntimePermissions(downloadsPackage, STORAGE_PERMISSIONS, true, userId);
        }

        // Downloads UI
        Intent downloadsUiIntent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
        PackageParser.Package downloadsUiPackage = getDefaultSystemHandlerActivityPackage(
                downloadsUiIntent, userId);
        if (downloadsUiPackage != null
                && doesPackageSupportRuntimePermissions(downloadsUiPackage)) {
            grantRuntimePermissions(downloadsUiPackage, STORAGE_PERMISSIONS, true, userId);
        }

        // Storage provider
        PackageParser.Package storagePackage = getDefaultProviderAuthorityPackage(
                "com.android.externalstorage.documents", userId);
        if (storagePackage != null) {
            grantRuntimePermissions(storagePackage, STORAGE_PERMISSIONS, true, userId);
        }

        // Container service
        PackageParser.Package containerPackage = getSystemPackage(
                PackageManagerService.DEFAULT_CONTAINER_PACKAGE);
        if (containerPackage != null) {
            grantRuntimePermissions(containerPackage, STORAGE_PERMISSIONS, true, userId);
        }

        // CertInstaller
        Intent certInstallerIntent = new Intent(Credentials.INSTALL_ACTION);
        PackageParser.Package certInstallerPackage = getDefaultSystemHandlerActivityPackage(
                certInstallerIntent, userId);
        if (certInstallerPackage != null
                && doesPackageSupportRuntimePermissions(certInstallerPackage)) {
            grantRuntimePermissions(certInstallerPackage, STORAGE_PERMISSIONS, true, userId);
        }

        // Dialer
        if (dialerAppPackageNames == null) {
            Intent dialerIntent = new Intent(Intent.ACTION_DIAL);
            PackageParser.Package dialerPackage = getDefaultSystemHandlerActivityPackage(
                    dialerIntent, userId);
            if (dialerPackage != null) {
                grantDefaultPermissionsToDefaultSystemDialerApp(dialerPackage, userId);
            }
        } else {
            for (String dialerAppPackageName : dialerAppPackageNames) {
                PackageParser.Package dialerPackage = getSystemPackage(dialerAppPackageName);
                if (dialerPackage != null) {
                    grantDefaultPermissionsToDefaultSystemDialerApp(dialerPackage, userId);
                }
            }
        }

        // Sim call manager
        if (simCallManagerPackageNames != null) {
            for (String simCallManagerPackageName : simCallManagerPackageNames) {
                PackageParser.Package simCallManagerPackage =
                        getSystemPackage(simCallManagerPackageName);
                if (simCallManagerPackage != null) {
                    grantDefaultPermissionsToDefaultSimCallManager(simCallManagerPackage,
                            userId);
                }
            }
        }

        // Use Open Wifi
        if (useOpenWifiAppPackageNames != null) {
            for (String useOpenWifiPackageName : useOpenWifiAppPackageNames) {
                PackageParser.Package useOpenWifiPackage =
                        getSystemPackage(useOpenWifiPackageName);
                if (useOpenWifiPackage != null) {
                    grantDefaultPermissionsToDefaultSystemUseOpenWifiApp(useOpenWifiPackage,
                            userId);
                }
            }
        }

        // SMS
        if (smsAppPackageNames == null) {
            Intent smsIntent = new Intent(Intent.ACTION_MAIN);
            smsIntent.addCategory(Intent.CATEGORY_APP_MESSAGING);
            PackageParser.Package smsPackage = getDefaultSystemHandlerActivityPackage(
                    smsIntent, userId);
            if (smsPackage != null) {
               grantDefaultPermissionsToDefaultSystemSmsApp(smsPackage, userId);
            }
        } else {
            for (String smsPackageName : smsAppPackageNames) {
                PackageParser.Package smsPackage = getSystemPackage(smsPackageName);
                if (smsPackage != null) {
                    grantDefaultPermissionsToDefaultSystemSmsApp(smsPackage, userId);
                }
            }
        }

        // Cell Broadcast Receiver
        Intent cbrIntent = new Intent(Intents.SMS_CB_RECEIVED_ACTION);
        PackageParser.Package cbrPackage =
                getDefaultSystemHandlerActivityPackage(cbrIntent, userId);
        if (cbrPackage != null && doesPackageSupportRuntimePermissions(cbrPackage)) {
            grantRuntimePermissions(cbrPackage, SMS_PERMISSIONS, userId);
        }

        // Carrier Provisioning Service
        Intent carrierProvIntent = new Intent(Intents.SMS_CARRIER_PROVISION_ACTION);
        PackageParser.Package carrierProvPackage =
                getDefaultSystemHandlerServicePackage(carrierProvIntent, userId);
        if (carrierProvPackage != null
                && doesPackageSupportRuntimePermissions(carrierProvPackage)) {
            grantRuntimePermissions(carrierProvPackage, SMS_PERMISSIONS, false, userId);
        }

        // Calendar
        Intent calendarIntent = new Intent(Intent.ACTION_MAIN);
        calendarIntent.addCategory(Intent.CATEGORY_APP_CALENDAR);
        PackageParser.Package calendarPackage = getDefaultSystemHandlerActivityPackage(
                calendarIntent, userId);
        if (calendarPackage != null
                && doesPackageSupportRuntimePermissions(calendarPackage)) {
            grantRuntimePermissions(calendarPackage, CALENDAR_PERMISSIONS, userId);
            grantRuntimePermissions(calendarPackage, CONTACTS_PERMISSIONS, userId);
        }

        // Calendar provider
        PackageParser.Package calendarProviderPackage = getDefaultProviderAuthorityPackage(
                CalendarContract.AUTHORITY, userId);
        if (calendarProviderPackage != null) {
            grantRuntimePermissions(calendarProviderPackage, CONTACTS_PERMISSIONS, userId);
            grantRuntimePermissions(calendarProviderPackage, CALENDAR_PERMISSIONS,
                    true, userId);
            grantRuntimePermissions(calendarProviderPackage, STORAGE_PERMISSIONS, userId);
        }

        // Calendar provider sync adapters
        List<PackageParser.Package> calendarSyncAdapters = getHeadlessSyncAdapterPackages(
                calendarSyncAdapterPackages, userId);
        final int calendarSyncAdapterCount = calendarSyncAdapters.size();
        for (int i = 0; i < calendarSyncAdapterCount; i++) {
            PackageParser.Package calendarSyncAdapter = calendarSyncAdapters.get(i);
            if (doesPackageSupportRuntimePermissions(calendarSyncAdapter)) {
                grantRuntimePermissions(calendarSyncAdapter, CALENDAR_PERMISSIONS, userId);
            }
        }

        // Contacts
        Intent contactsIntent = new Intent(Intent.ACTION_MAIN);
        contactsIntent.addCategory(Intent.CATEGORY_APP_CONTACTS);
        PackageParser.Package contactsPackage = getDefaultSystemHandlerActivityPackage(
                contactsIntent, userId);
        if (contactsPackage != null
                && doesPackageSupportRuntimePermissions(contactsPackage)) {
            grantRuntimePermissions(contactsPackage, CONTACTS_PERMISSIONS, userId);
            grantRuntimePermissions(contactsPackage, PHONE_PERMISSIONS, userId);
        }

        // Contacts provider sync adapters
        List<PackageParser.Package> contactsSyncAdapters = getHeadlessSyncAdapterPackages(
                contactsSyncAdapterPackages, userId);
        final int contactsSyncAdapterCount = contactsSyncAdapters.size();
        for (int i = 0; i < contactsSyncAdapterCount; i++) {
            PackageParser.Package contactsSyncAdapter = contactsSyncAdapters.get(i);
            if (doesPackageSupportRuntimePermissions(contactsSyncAdapter)) {
                grantRuntimePermissions(contactsSyncAdapter, CONTACTS_PERMISSIONS, userId);
            }
        }

        // Contacts provider
        PackageParser.Package contactsProviderPackage = getDefaultProviderAuthorityPackage(
                ContactsContract.AUTHORITY, userId);
        if (contactsProviderPackage != null) {
            grantRuntimePermissions(contactsProviderPackage, CONTACTS_PERMISSIONS,
                    true, userId);
            grantRuntimePermissions(contactsProviderPackage, PHONE_PERMISSIONS,
                    true, userId);
            grantRuntimePermissions(contactsProviderPackage, STORAGE_PERMISSIONS, userId);
        }

        // Device provisioning
        Intent deviceProvisionIntent = new Intent(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE);
        PackageParser.Package deviceProvisionPackage =
                getDefaultSystemHandlerActivityPackage(deviceProvisionIntent, userId);
        if (deviceProvisionPackage != null
                && doesPackageSupportRuntimePermissions(deviceProvisionPackage)) {
            grantRuntimePermissions(deviceProvisionPackage, CONTACTS_PERMISSIONS, userId);
        }

        // Maps
        Intent mapsIntent = new Intent(Intent.ACTION_MAIN);
        mapsIntent.addCategory(Intent.CATEGORY_APP_MAPS);
        PackageParser.Package mapsPackage = getDefaultSystemHandlerActivityPackage(
                mapsIntent, userId);
        if (mapsPackage != null
                && doesPackageSupportRuntimePermissions(mapsPackage)) {
            grantRuntimePermissions(mapsPackage, LOCATION_PERMISSIONS, userId);
        }

        // Gallery
        Intent galleryIntent = new Intent(Intent.ACTION_MAIN);
        galleryIntent.addCategory(Intent.CATEGORY_APP_GALLERY);
        PackageParser.Package galleryPackage = getDefaultSystemHandlerActivityPackage(
                galleryIntent, userId);
        if (galleryPackage != null
                && doesPackageSupportRuntimePermissions(galleryPackage)) {
            grantRuntimePermissions(galleryPackage, STORAGE_PERMISSIONS, userId);
        }

        // Email
        Intent emailIntent = new Intent(Intent.ACTION_MAIN);
        emailIntent.addCategory(Intent.CATEGORY_APP_EMAIL);
        PackageParser.Package emailPackage = getDefaultSystemHandlerActivityPackage(
                emailIntent, userId);
        if (emailPackage != null
                && doesPackageSupportRuntimePermissions(emailPackage)) {
            grantRuntimePermissions(emailPackage, CONTACTS_PERMISSIONS, userId);
            grantRuntimePermissions(emailPackage, CALENDAR_PERMISSIONS, userId);
        }

        // Browser
        PackageParser.Package browserPackage = null;
        String defaultBrowserPackage = mServiceInternal.getKnownPackageName(
                PackageManagerInternal.PACKAGE_BROWSER, userId);
        if (defaultBrowserPackage != null) {
            browserPackage = getPackage(defaultBrowserPackage);
        }
        if (browserPackage == null) {
            Intent browserIntent = new Intent(Intent.ACTION_MAIN);
            browserIntent.addCategory(Intent.CATEGORY_APP_BROWSER);
            browserPackage = getDefaultSystemHandlerActivityPackage(
                    browserIntent, userId);
        }
        if (browserPackage != null
                && doesPackageSupportRuntimePermissions(browserPackage)) {
            grantRuntimePermissions(browserPackage, LOCATION_PERMISSIONS, userId);
        }

        // Voice interaction
        if (voiceInteractPackageNames != null) {
            for (String voiceInteractPackageName : voiceInteractPackageNames) {
                PackageParser.Package voiceInteractPackage = getSystemPackage(
                        voiceInteractPackageName);
                if (voiceInteractPackage != null
                        && doesPackageSupportRuntimePermissions(voiceInteractPackage)) {
                    grantRuntimePermissions(voiceInteractPackage,
                            CONTACTS_PERMISSIONS, userId);
                    grantRuntimePermissions(voiceInteractPackage,
                            CALENDAR_PERMISSIONS, userId);
                    grantRuntimePermissions(voiceInteractPackage,
                            MICROPHONE_PERMISSIONS, userId);
                    grantRuntimePermissions(voiceInteractPackage,
                            PHONE_PERMISSIONS, userId);
                    grantRuntimePermissions(voiceInteractPackage,
                            SMS_PERMISSIONS, userId);
                    grantRuntimePermissions(voiceInteractPackage,
                            LOCATION_PERMISSIONS, userId);
                }
            }
        }

        if (ActivityManager.isLowRamDeviceStatic()) {
            // Allow voice search on low-ram devices
            Intent globalSearchIntent = new Intent("android.search.action.GLOBAL_SEARCH");
            PackageParser.Package globalSearchPickerPackage =
                getDefaultSystemHandlerActivityPackage(globalSearchIntent, userId);

            if (globalSearchPickerPackage != null
                    && doesPackageSupportRuntimePermissions(globalSearchPickerPackage)) {
                grantRuntimePermissions(globalSearchPickerPackage,
                    MICROPHONE_PERMISSIONS, false, userId);
                grantRuntimePermissions(globalSearchPickerPackage,
                    LOCATION_PERMISSIONS, false, userId);
            }
        }

        // Voice recognition
        Intent voiceRecoIntent = new Intent("android.speech.RecognitionService");
        voiceRecoIntent.addCategory(Intent.CATEGORY_DEFAULT);
        PackageParser.Package voiceRecoPackage = getDefaultSystemHandlerServicePackage(
                voiceRecoIntent, userId);
        if (voiceRecoPackage != null
                && doesPackageSupportRuntimePermissions(voiceRecoPackage)) {
            grantRuntimePermissions(voiceRecoPackage, MICROPHONE_PERMISSIONS, userId);
        }

        // Location
        if (locationPackageNames != null) {
            for (String packageName : locationPackageNames) {
                PackageParser.Package locationPackage = getSystemPackage(packageName);
                if (locationPackage != null
                        && doesPackageSupportRuntimePermissions(locationPackage)) {
                    grantRuntimePermissions(locationPackage, CONTACTS_PERMISSIONS, userId);
                    grantRuntimePermissions(locationPackage, CALENDAR_PERMISSIONS, userId);
                    grantRuntimePermissions(locationPackage, MICROPHONE_PERMISSIONS, userId);
                    grantRuntimePermissions(locationPackage, PHONE_PERMISSIONS, userId);
                    grantRuntimePermissions(locationPackage, SMS_PERMISSIONS, userId);
                    grantRuntimePermissions(locationPackage, LOCATION_PERMISSIONS,
                            true, userId);
                    grantRuntimePermissions(locationPackage, CAMERA_PERMISSIONS, userId);
                    grantRuntimePermissions(locationPackage, SENSORS_PERMISSIONS, userId);
                    grantRuntimePermissions(locationPackage, STORAGE_PERMISSIONS, userId);
                }
            }
        }

        // Music
        Intent musicIntent = new Intent(Intent.ACTION_VIEW);
        musicIntent.addCategory(Intent.CATEGORY_DEFAULT);
        musicIntent.setDataAndType(Uri.fromFile(new File("foo.mp3")),
                AUDIO_MIME_TYPE);
        PackageParser.Package musicPackage = getDefaultSystemHandlerActivityPackage(
                musicIntent, userId);
        if (musicPackage != null
                && doesPackageSupportRuntimePermissions(musicPackage)) {
            grantRuntimePermissions(musicPackage, STORAGE_PERMISSIONS, userId);
        }

        // Home
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.addCategory(Intent.CATEGORY_LAUNCHER_APP);
        PackageParser.Package homePackage = getDefaultSystemHandlerActivityPackage(
                homeIntent, userId);
        if (homePackage != null
                && doesPackageSupportRuntimePermissions(homePackage)) {
            grantRuntimePermissions(homePackage, LOCATION_PERMISSIONS, false, userId);
        }

        // Watches
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH, 0)) {
            // Home application on watches
            Intent wearHomeIntent = new Intent(Intent.ACTION_MAIN);
            wearHomeIntent.addCategory(Intent.CATEGORY_HOME_MAIN);

            PackageParser.Package wearHomePackage = getDefaultSystemHandlerActivityPackage(
                    wearHomeIntent, userId);

            if (wearHomePackage != null
                    && doesPackageSupportRuntimePermissions(wearHomePackage)) {
                grantRuntimePermissions(wearHomePackage, CONTACTS_PERMISSIONS, false,
                        userId);
                grantRuntimePermissions(wearHomePackage, PHONE_PERMISSIONS, true, userId);
                grantRuntimePermissions(wearHomePackage, MICROPHONE_PERMISSIONS, false,
                        userId);
                grantRuntimePermissions(wearHomePackage, LOCATION_PERMISSIONS, false,
                        userId);
            }

            // Fitness tracking on watches
            Intent trackIntent = new Intent(ACTION_TRACK);
            PackageParser.Package trackPackage = getDefaultSystemHandlerActivityPackage(
                    trackIntent, userId);
            if (trackPackage != null
                    && doesPackageSupportRuntimePermissions(trackPackage)) {
                grantRuntimePermissions(trackPackage, SENSORS_PERMISSIONS, false, userId);
                grantRuntimePermissions(trackPackage, LOCATION_PERMISSIONS, false, userId);
            }
        }

        // Print Spooler
        PackageParser.Package printSpoolerPackage = getSystemPackage(
                PrintManager.PRINT_SPOOLER_PACKAGE_NAME);
        if (printSpoolerPackage != null
                && doesPackageSupportRuntimePermissions(printSpoolerPackage)) {
            grantRuntimePermissions(printSpoolerPackage, LOCATION_PERMISSIONS, true, userId);
        }

        // EmergencyInfo
        Intent emergencyInfoIntent = new Intent(TelephonyManager.ACTION_EMERGENCY_ASSISTANCE);
        PackageParser.Package emergencyInfoPckg = getDefaultSystemHandlerActivityPackage(
                emergencyInfoIntent, userId);
        if (emergencyInfoPckg != null
                && doesPackageSupportRuntimePermissions(emergencyInfoPckg)) {
            grantRuntimePermissions(emergencyInfoPckg, CONTACTS_PERMISSIONS, true, userId);
            grantRuntimePermissions(emergencyInfoPckg, PHONE_PERMISSIONS, true, userId);
        }

        // NFC Tag viewer
        Intent nfcTagIntent = new Intent(Intent.ACTION_VIEW);
        nfcTagIntent.setType("vnd.android.cursor.item/ndef_msg");
        PackageParser.Package nfcTagPkg = getDefaultSystemHandlerActivityPackage(
                nfcTagIntent, userId);
        if (nfcTagPkg != null
                && doesPackageSupportRuntimePermissions(nfcTagPkg)) {
            grantRuntimePermissions(nfcTagPkg, CONTACTS_PERMISSIONS, false, userId);
            grantRuntimePermissions(nfcTagPkg, PHONE_PERMISSIONS, false, userId);
        }

        // Storage Manager
        Intent storageManagerIntent = new Intent(StorageManager.ACTION_MANAGE_STORAGE);
        PackageParser.Package storageManagerPckg = getDefaultSystemHandlerActivityPackage(
                storageManagerIntent, userId);
        if (storageManagerPckg != null
                && doesPackageSupportRuntimePermissions(storageManagerPckg)) {
            grantRuntimePermissions(storageManagerPckg, STORAGE_PERMISSIONS, true, userId);
        }

        // Companion devices
        PackageParser.Package companionDeviceDiscoveryPackage = getSystemPackage(
                CompanionDeviceManager.COMPANION_DEVICE_DISCOVERY_PACKAGE_NAME);
        if (companionDeviceDiscoveryPackage != null
                && doesPackageSupportRuntimePermissions(companionDeviceDiscoveryPackage)) {
            grantRuntimePermissions(companionDeviceDiscoveryPackage,
                    LOCATION_PERMISSIONS, true, userId);
        }

        // Ringtone Picker
        Intent ringtonePickerIntent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        PackageParser.Package ringtonePickerPackage =
                getDefaultSystemHandlerActivityPackage(ringtonePickerIntent, userId);
        if (ringtonePickerPackage != null
                && doesPackageSupportRuntimePermissions(ringtonePickerPackage)) {
            grantRuntimePermissions(ringtonePickerPackage,
                    STORAGE_PERMISSIONS, true, userId);
        }

        // TextClassifier Service
        String textClassifierPackageName =
                mContext.getPackageManager().getSystemTextClassifierPackageName();
        if (!TextUtils.isEmpty(textClassifierPackageName)) {
            PackageParser.Package textClassifierPackage =
                    getSystemPackage(textClassifierPackageName);
            if (textClassifierPackage != null
                    && doesPackageSupportRuntimePermissions(textClassifierPackage)) {
                grantRuntimePermissions(textClassifierPackage, PHONE_PERMISSIONS, true, userId);
                grantRuntimePermissions(textClassifierPackage, SMS_PERMISSIONS, true, userId);
                grantRuntimePermissions(textClassifierPackage, CALENDAR_PERMISSIONS, true, userId);
                grantRuntimePermissions(textClassifierPackage, LOCATION_PERMISSIONS, true, userId);
                grantRuntimePermissions(textClassifierPackage, CONTACTS_PERMISSIONS, true, userId);
            }
        }

        // There is no real "marker" interface to identify the shared storage backup, it is
        // hardcoded in BackupManagerService.SHARED_BACKUP_AGENT_PACKAGE.
        PackageParser.Package sharedStorageBackupPackage = getSystemPackage(
                "com.android.sharedstoragebackup");
        if (sharedStorageBackupPackage != null) {
            grantRuntimePermissions(sharedStorageBackupPackage, STORAGE_PERMISSIONS, true, userId);
        }

        if (mPermissionGrantedCallback != null) {
            mPermissionGrantedCallback.onDefaultRuntimePermissionsGranted(userId);
        }
    }

    private void grantDefaultPermissionsToDefaultSystemDialerApp(
            PackageParser.Package dialerPackage, int userId) {
        if (doesPackageSupportRuntimePermissions(dialerPackage)) {
            boolean isPhonePermFixed =
                    mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH, 0);
            grantRuntimePermissions(
                    dialerPackage, PHONE_PERMISSIONS, isPhonePermFixed, userId);
            grantRuntimePermissions(dialerPackage, CONTACTS_PERMISSIONS, userId);
            grantRuntimePermissions(dialerPackage, SMS_PERMISSIONS, userId);
            grantRuntimePermissions(dialerPackage, MICROPHONE_PERMISSIONS, userId);
            grantRuntimePermissions(dialerPackage, CAMERA_PERMISSIONS, userId);
        }
    }

    private void grantDefaultPermissionsToDefaultSystemSmsApp(
            PackageParser.Package smsPackage, int userId) {
        if (doesPackageSupportRuntimePermissions(smsPackage)) {
            grantRuntimePermissions(smsPackage, PHONE_PERMISSIONS, userId);
            grantRuntimePermissions(smsPackage, CONTACTS_PERMISSIONS, userId);
            grantRuntimePermissions(smsPackage, SMS_PERMISSIONS, userId);
            grantRuntimePermissions(smsPackage, STORAGE_PERMISSIONS, userId);
            grantRuntimePermissions(smsPackage, MICROPHONE_PERMISSIONS, userId);
            grantRuntimePermissions(smsPackage, CAMERA_PERMISSIONS, userId);
        }
    }

    private void grantDefaultPermissionsToDefaultSystemUseOpenWifiApp(
            PackageParser.Package useOpenWifiPackage, int userId) {
        if (doesPackageSupportRuntimePermissions(useOpenWifiPackage)) {
            grantRuntimePermissions(useOpenWifiPackage, COARSE_LOCATION_PERMISSIONS, userId);
        }
    }

    public void grantDefaultPermissionsToDefaultSmsApp(String packageName, int userId) {
        Log.i(TAG, "Granting permissions to default sms app for user:" + userId);
        if (packageName == null) {
            return;
        }
        PackageParser.Package smsPackage = getPackage(packageName);
        if (smsPackage != null && doesPackageSupportRuntimePermissions(smsPackage)) {
            grantRuntimePermissions(smsPackage, PHONE_PERMISSIONS, false, true, userId);
            grantRuntimePermissions(smsPackage, CONTACTS_PERMISSIONS, false, true, userId);
            grantRuntimePermissions(smsPackage, SMS_PERMISSIONS, false, true, userId);
            grantRuntimePermissions(smsPackage, STORAGE_PERMISSIONS, false, true, userId);
            grantRuntimePermissions(smsPackage, MICROPHONE_PERMISSIONS, false, true, userId);
            grantRuntimePermissions(smsPackage, CAMERA_PERMISSIONS, false, true, userId);
        }
    }

    public void grantDefaultPermissionsToDefaultDialerApp(String packageName, int userId) {
        Log.i(TAG, "Granting permissions to default dialer app for user:" + userId);
        if (packageName == null) {
            return;
        }
        PackageParser.Package dialerPackage = getPackage(packageName);
        if (dialerPackage != null
                && doesPackageSupportRuntimePermissions(dialerPackage)) {
            grantRuntimePermissions(dialerPackage, PHONE_PERMISSIONS, false, true, userId);
            grantRuntimePermissions(dialerPackage, CONTACTS_PERMISSIONS, false, true, userId);
            grantRuntimePermissions(dialerPackage, SMS_PERMISSIONS, false, true, userId);
            grantRuntimePermissions(dialerPackage, MICROPHONE_PERMISSIONS, false, true, userId);
            grantRuntimePermissions(dialerPackage, CAMERA_PERMISSIONS, false, true, userId);
        }
    }

    public void grantDefaultPermissionsToDefaultUseOpenWifiApp(String packageName, int userId) {
        Log.i(TAG, "Granting permissions to default Use Open WiFi app for user:" + userId);
        if (packageName == null) {
            return;
        }
        PackageParser.Package useOpenWifiPackage = getPackage(packageName);
        if (useOpenWifiPackage != null
                && doesPackageSupportRuntimePermissions(useOpenWifiPackage)) {
            grantRuntimePermissions(
                    useOpenWifiPackage, COARSE_LOCATION_PERMISSIONS, false, true, userId);
        }
    }

    private void grantDefaultPermissionsToDefaultSimCallManager(
            PackageParser.Package simCallManagerPackage, int userId) {
        Log.i(TAG, "Granting permissions to sim call manager for user:" + userId);
        if (doesPackageSupportRuntimePermissions(simCallManagerPackage)) {
            grantRuntimePermissions(simCallManagerPackage, PHONE_PERMISSIONS, userId);
            grantRuntimePermissions(simCallManagerPackage, MICROPHONE_PERMISSIONS, userId);
        }
    }

    public void grantDefaultPermissionsToDefaultSimCallManager(String packageName, int userId) {
        if (packageName == null) {
            return;
        }
        PackageParser.Package simCallManagerPackage = getPackage(packageName);
        if (simCallManagerPackage != null) {
            grantDefaultPermissionsToDefaultSimCallManager(simCallManagerPackage, userId);
        }
    }

    public void grantDefaultPermissionsToEnabledCarrierApps(String[] packageNames, int userId) {
        Log.i(TAG, "Granting permissions to enabled carrier apps for user:" + userId);
        if (packageNames == null) {
            return;
        }
        for (String packageName : packageNames) {
            PackageParser.Package carrierPackage = getSystemPackage(packageName);
            if (carrierPackage != null
                    && doesPackageSupportRuntimePermissions(carrierPackage)) {
                grantRuntimePermissions(carrierPackage, PHONE_PERMISSIONS, userId);
                grantRuntimePermissions(carrierPackage, LOCATION_PERMISSIONS, userId);
                grantRuntimePermissions(carrierPackage, SMS_PERMISSIONS, userId);
            }
        }
    }

    public void grantDefaultPermissionsToEnabledImsServices(String[] packageNames, int userId) {
        Log.i(TAG, "Granting permissions to enabled ImsServices for user:" + userId);
        if (packageNames == null) {
            return;
        }
        for (String packageName : packageNames) {
            PackageParser.Package imsServicePackage = getSystemPackage(packageName);
            if (imsServicePackage != null
                    && doesPackageSupportRuntimePermissions(imsServicePackage)) {
                grantRuntimePermissions(imsServicePackage, PHONE_PERMISSIONS, userId);
                grantRuntimePermissions(imsServicePackage, MICROPHONE_PERMISSIONS, userId);
                grantRuntimePermissions(imsServicePackage, LOCATION_PERMISSIONS, userId);
                grantRuntimePermissions(imsServicePackage, CAMERA_PERMISSIONS, userId);
                grantRuntimePermissions(imsServicePackage, CONTACTS_PERMISSIONS, userId);
            }
        }
    }

    public void grantDefaultPermissionsToEnabledTelephonyDataServices(
            String[] packageNames, int userId) {
        Log.i(TAG, "Granting permissions to enabled data services for user:" + userId);
        if (packageNames == null) {
            return;
        }
        for (String packageName : packageNames) {
            PackageParser.Package dataServicePackage = getSystemPackage(packageName);
            if (dataServicePackage != null
                    && doesPackageSupportRuntimePermissions(dataServicePackage)) {
                // Grant these permissions as system-fixed, so that nobody can accidentally
                // break cellular data.
                grantRuntimePermissions(dataServicePackage, PHONE_PERMISSIONS, true, userId);
                grantRuntimePermissions(dataServicePackage, LOCATION_PERMISSIONS, true, userId);
            }
        }
    }

    public void revokeDefaultPermissionsFromDisabledTelephonyDataServices(
            String[] packageNames, int userId) {
        Log.i(TAG, "Revoking permissions from disabled data services for user:" + userId);
        if (packageNames == null) {
            return;
        }
        for (String packageName : packageNames) {
            PackageParser.Package dataServicePackage = getSystemPackage(packageName);
            if (dataServicePackage != null
                    && doesPackageSupportRuntimePermissions(dataServicePackage)) {
                revokeRuntimePermissions(dataServicePackage, PHONE_PERMISSIONS, true, userId);
                revokeRuntimePermissions(dataServicePackage, LOCATION_PERMISSIONS, true, userId);
            }
        }
    }

    public void grantDefaultPermissionsToActiveLuiApp(String packageName, int userId) {
        Log.i(TAG, "Granting permissions to active LUI app for user:" + userId);
        if (packageName == null) {
            return;
        }
        PackageParser.Package luiAppPackage = getSystemPackage(packageName);
        if (luiAppPackage != null
                && doesPackageSupportRuntimePermissions(luiAppPackage)) {
            grantRuntimePermissions(luiAppPackage, CAMERA_PERMISSIONS, true, userId);
        }
    }

    public void revokeDefaultPermissionsFromLuiApps(String[] packageNames, int userId) {
        Log.i(TAG, "Revoke permissions from LUI apps for user:" + userId);
        if (packageNames == null) {
            return;
        }
        for (String packageName : packageNames) {
            PackageParser.Package luiAppPackage = getSystemPackage(packageName);
            if (luiAppPackage != null
                    && doesPackageSupportRuntimePermissions(luiAppPackage)) {
                revokeRuntimePermissions(luiAppPackage, CAMERA_PERMISSIONS, true, userId);
            }
        }
    }

    public void grantDefaultPermissionsToDefaultBrowser(String packageName, int userId) {
        Log.i(TAG, "Granting permissions to default browser for user:" + userId);
        if (packageName == null) {
            return;
        }
        PackageParser.Package browserPackage = getSystemPackage(packageName);
        if (browserPackage != null
                && doesPackageSupportRuntimePermissions(browserPackage)) {
            grantRuntimePermissions(browserPackage, LOCATION_PERMISSIONS, false, false, userId);
        }
    }

    private PackageParser.Package getDefaultSystemHandlerActivityPackage(
            Intent intent, int userId) {
        ResolveInfo handler = mServiceInternal.resolveIntent(intent,
                intent.resolveType(mContext.getContentResolver()), DEFAULT_FLAGS, userId, false,
                Binder.getCallingUid());
        if (handler == null || handler.activityInfo == null) {
            return null;
        }
        if (mServiceInternal.isResolveActivityComponent(handler.activityInfo)) {
            return null;
        }
        return getSystemPackage(handler.activityInfo.packageName);
    }

    private PackageParser.Package getDefaultSystemHandlerServicePackage(
            Intent intent, int userId) {
        List<ResolveInfo> handlers = mServiceInternal.queryIntentServices(
                intent, DEFAULT_FLAGS, Binder.getCallingUid(), userId);
        if (handlers == null) {
            return null;
        }
        final int handlerCount = handlers.size();
        for (int i = 0; i < handlerCount; i++) {
            ResolveInfo handler = handlers.get(i);
            PackageParser.Package handlerPackage = getSystemPackage(
                    handler.serviceInfo.packageName);
            if (handlerPackage != null) {
                return handlerPackage;
            }
        }
        return null;
    }

    private List<PackageParser.Package> getHeadlessSyncAdapterPackages(
            String[] syncAdapterPackageNames, int userId) {
        List<PackageParser.Package> syncAdapterPackages = new ArrayList<>();

        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        for (String syncAdapterPackageName : syncAdapterPackageNames) {
            homeIntent.setPackage(syncAdapterPackageName);

            ResolveInfo homeActivity = mServiceInternal.resolveIntent(homeIntent,
                    homeIntent.resolveType(mContext.getContentResolver()), DEFAULT_FLAGS,
                    userId, false, Binder.getCallingUid());
            if (homeActivity != null) {
                continue;
            }

            PackageParser.Package syncAdapterPackage = getSystemPackage(syncAdapterPackageName);
            if (syncAdapterPackage != null) {
                syncAdapterPackages.add(syncAdapterPackage);
            }
        }

        return syncAdapterPackages;
    }

    private PackageParser.Package getDefaultProviderAuthorityPackage(
            String authority, int userId) {
        ProviderInfo provider =
                mServiceInternal.resolveContentProvider(authority, DEFAULT_FLAGS, userId);
        if (provider != null) {
            return getSystemPackage(provider.packageName);
        }
        return null;
    }

    private PackageParser.Package getPackage(String packageName) {
        return mServiceInternal.getPackage(packageName);
    }

    private PackageParser.Package getSystemPackage(String packageName) {
        PackageParser.Package pkg = getPackage(packageName);
        if (pkg != null && pkg.isSystem()) {
            return !isSysComponentOrPersistentPlatformSignedPrivApp(pkg) ? pkg : null;
        }
        return null;
    }

    private void grantRuntimePermissions(PackageParser.Package pkg, Set<String> permissions,
            int userId) {
        grantRuntimePermissions(pkg, permissions, false, false, userId);
    }

    private void grantRuntimePermissions(PackageParser.Package pkg, Set<String> permissions,
            boolean systemFixed, int userId) {
        grantRuntimePermissions(pkg, permissions, systemFixed, false, userId);
    }

    private void revokeRuntimePermissions(PackageParser.Package pkg, Set<String> permissions,
            boolean systemFixed, int userId) {
        if (pkg.requestedPermissions.isEmpty()) {
            return;
        }
        Set<String> revokablePermissions = new ArraySet<>(pkg.requestedPermissions);

        for (String permission : permissions) {
            // We can't revoke what wasn't requested.
            if (!revokablePermissions.contains(permission)) {
                continue;
            }

            final int flags = mServiceInternal.getPermissionFlagsTEMP(
                    permission, pkg.packageName, userId);

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
            mServiceInternal.revokeRuntimePermission(pkg.packageName, permission, userId, false);

            if (DEBUG) {
                Log.i(TAG, "revoked " + (systemFixed ? "fixed " : "not fixed ")
                        + permission + " to " + pkg.packageName);
            }

            // Remove the GRANTED_BY_DEFAULT flag without touching the others.
            // Note that we do not revoke FLAG_PERMISSION_SYSTEM_FIXED. That bit remains
            // sticky once set.
            mServiceInternal.updatePermissionFlagsTEMP(permission, pkg.packageName,
                    PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT, 0, userId);
        }
    }

    private void grantRuntimePermissions(PackageParser.Package pkg, Set<String> permissions,
            boolean systemFixed, boolean ignoreSystemPackage, int userId) {
        if (pkg.requestedPermissions.isEmpty()) {
            return;
        }

        List<String> requestedPermissions = pkg.requestedPermissions;
        Set<String> grantablePermissions = null;

        // In some cases, like for the Phone or SMS app, we grant permissions regardless
        // of if the version on the system image declares the permission as used since
        // selecting the app as the default for that function the user makes a deliberate
        // choice to grant this app the permissions needed to function. For all other
        // apps, (default grants on first boot and user creation) we don't grant default
        // permissions if the version on the system image does not declare them.
        if (!ignoreSystemPackage && pkg.isUpdatedSystemApp()) {
            final PackageParser.Package disabledPkg =
                    mServiceInternal.getDisabledPackage(pkg.packageName);
            if (disabledPkg != null) {
                if (disabledPkg.requestedPermissions.isEmpty()) {
                    return;
                }
                if (!requestedPermissions.equals(disabledPkg.requestedPermissions)) {
                    grantablePermissions = new ArraySet<>(requestedPermissions);
                    requestedPermissions = disabledPkg.requestedPermissions;
                }
            }
        }

        final int grantablePermissionCount = requestedPermissions.size();
        for (int i = 0; i < grantablePermissionCount; i++) {
            String permission = requestedPermissions.get(i);

            // If there is a disabled system app it may request a permission the updated
            // version ot the data partition doesn't, In this case skip the permission.
            if (grantablePermissions != null && !grantablePermissions.contains(permission)) {
                continue;
            }

            if (permissions.contains(permission)) {
                final int flags = mServiceInternal.getPermissionFlagsTEMP(
                        permission, pkg.packageName, userId);

                // If any flags are set to the permission, then it is either set in
                // its current state by the system or device/profile owner or the user.
                // In all these cases we do not want to clobber the current state.
                // Unless the caller wants to override user choices. The override is
                // to make sure we can grant the needed permission to the default
                // sms and phone apps after the user chooses this in the UI.
                if (flags == 0 || ignoreSystemPackage) {
                    // Never clobber policy fixed permissions.
                    // We must allow the grant of a system-fixed permission because
                    // system-fixed is sticky, but the permission itself may be revoked.
                    if ((flags & PackageManager.FLAG_PERMISSION_POLICY_FIXED) != 0) {
                        continue;
                    }

                    mServiceInternal.grantRuntimePermission(
                            pkg.packageName, permission, userId, false);
                    if (DEBUG) {
                        Log.i(TAG, "Granted " + (systemFixed ? "fixed " : "not fixed ")
                                + permission + " to default handler " + pkg.packageName);
                    }

                    int newFlags = PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT;
                    if (systemFixed) {
                        newFlags |= PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
                    }

                    mServiceInternal.updatePermissionFlagsTEMP(permission, pkg.packageName,
                            newFlags, newFlags, userId);
                }

                // If a component gets a permission for being the default handler A
                // and also default handler B, we grant the weaker grant form.
                if ((flags & PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT) != 0
                        && (flags & PackageManager.FLAG_PERMISSION_SYSTEM_FIXED) != 0
                        && !systemFixed) {
                    if (DEBUG) {
                        Log.i(TAG, "Granted not fixed " + permission + " to default handler "
                                + pkg.packageName);
                    }
                    mServiceInternal.updatePermissionFlagsTEMP(permission, pkg.packageName,
                            PackageManager.FLAG_PERMISSION_SYSTEM_FIXED, 0, userId);
                }
            }
        }
    }

    private boolean isSysComponentOrPersistentPlatformSignedPrivApp(PackageParser.Package pkg) {
        if (UserHandle.getAppId(pkg.applicationInfo.uid) < FIRST_APPLICATION_UID) {
            return true;
        }
        if (!pkg.isPrivileged()) {
            return false;
        }
        final PackageParser.Package disabledPkg =
                mServiceInternal.getDisabledPackage(pkg.packageName);
        if (disabledPkg != null) {
            if ((disabledPkg.applicationInfo.flags & ApplicationInfo.FLAG_PERSISTENT) == 0) {
                return false;
            }
        } else if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_PERSISTENT) == 0) {
            return false;
        }
        final String systemPackageName = mServiceInternal.getKnownPackageName(
                PackageManagerInternal.PACKAGE_SYSTEM, UserHandle.USER_SYSTEM);
        final PackageParser.Package systemPackage = getPackage(systemPackageName);
        return pkg.mSigningDetails.hasAncestorOrSelf(systemPackage.mSigningDetails)
                || systemPackage.mSigningDetails.checkCapability(pkg.mSigningDetails,
                        PackageParser.SigningDetails.CertCapabilities.PERMISSION);
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
            PackageParser.Package pkg = getSystemPackage(packageName);
            List<DefaultPermissionGrant> permissionGrants = mGrantExceptions.valueAt(i);
            final int permissionGrantCount = permissionGrants.size();
            for (int j = 0; j < permissionGrantCount; j++) {
                DefaultPermissionGrant permissionGrant = permissionGrants.get(j);
                if (permissions == null) {
                    permissions = new ArraySet<>();
                } else {
                    permissions.clear();
                }
                permissions.add(permissionGrant.name);
                grantRuntimePermissions(pkg, permissions,
                        permissionGrant.fixed, userId);
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
                    PackageParser.Package pkg = getSystemPackage(packageName);
                    if (pkg == null) {
                        Log.w(TAG, "Unknown package:" + packageName);
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }

                    // The package must support runtime permissions
                    if (!doesPackageSupportRuntimePermissions(pkg)) {
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

                DefaultPermissionGrant exception = new DefaultPermissionGrant(name, fixed);
                outPackageExceptions.add(exception);
            } else {
                Log.e(TAG, "Unknown tag " + parser.getName() + "under <exception>");
            }
        }
    }

    private static boolean doesPackageSupportRuntimePermissions(PackageParser.Package pkg) {
        return pkg.applicationInfo.targetSdkVersion > Build.VERSION_CODES.LOLLIPOP_MR1;
    }

    private static final class DefaultPermissionGrant {
        final String name;
        final boolean fixed;

        public DefaultPermissionGrant(String name, boolean fixed) {
            this.name = name;
            this.fixed = fixed;
        }
    }
}
