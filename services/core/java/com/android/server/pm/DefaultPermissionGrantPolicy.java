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

package com.android.server.pm;

import android.Manifest;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.DownloadManager;
import android.app.admin.DevicePolicyManager;
import android.companion.CompanionDeviceManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal.PackagesProvider;
import android.content.pm.PackageManagerInternal.SyncAdapterPackagesProvider;
import android.content.pm.PackageParser;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.print.PrintManager;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Telephony.Sms.Intents;
import android.telephony.TelephonyManager;
import android.security.Credentials;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.Xml;
import com.android.internal.util.XmlUtils;
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

import static android.os.Process.FIRST_APPLICATION_UID;

/**
 * This class is the policy for granting runtime permissions to
 * platform components and default handlers in the system such
 * that the device is usable out-of-the-box. For example, the
 * shell UID is a part of the system and the Phone app should
 * have phone related permission by default.
 */
final class DefaultPermissionGrantPolicy {
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

    private final PackageManagerService mService;
    private final Handler mHandler;

    private PackagesProvider mLocationPackagesProvider;
    private PackagesProvider mVoiceInteractionPackagesProvider;
    private PackagesProvider mSmsAppPackagesProvider;
    private PackagesProvider mDialerAppPackagesProvider;
    private PackagesProvider mSimCallManagerPackagesProvider;
    private SyncAdapterPackagesProvider mSyncAdapterPackagesProvider;

    private ArrayMap<String, List<DefaultPermissionGrant>> mGrantExceptions;

    public DefaultPermissionGrantPolicy(PackageManagerService service) {
        mService = service;
        mHandler = new Handler(mService.mHandlerThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == MSG_READ_DEFAULT_PERMISSION_EXCEPTIONS) {
                    synchronized (mService.mPackages) {
                        if (mGrantExceptions == null) {
                            mGrantExceptions = readDefaultPermissionExceptionsLPw();
                        }
                    }
                }
            }
        };
    }

    public void setLocationPackagesProviderLPw(PackagesProvider provider) {
        mLocationPackagesProvider = provider;
    }

    public void setVoiceInteractionPackagesProviderLPw(PackagesProvider provider) {
        mVoiceInteractionPackagesProvider = provider;
    }

    public void setSmsAppPackagesProviderLPw(PackagesProvider provider) {
        mSmsAppPackagesProvider = provider;
    }

    public void setDialerAppPackagesProviderLPw(PackagesProvider provider) {
        mDialerAppPackagesProvider = provider;
    }

    public void setSimCallManagerPackagesProviderLPw(PackagesProvider provider) {
        mSimCallManagerPackagesProvider = provider;
    }

    public void setSyncAdapterPackagesProviderLPw(SyncAdapterPackagesProvider provider) {
        mSyncAdapterPackagesProvider = provider;
    }

    public void grantDefaultPermissions(int userId) {
        if (mService.hasSystemFeature(PackageManager.FEATURE_EMBEDDED, 0)) {
            grantAllRuntimePermissions(userId);
        } else {
            grantPermissionsToSysComponentsAndPrivApps(userId);
            grantDefaultSystemHandlerPermissions(userId);
            grantDefaultPermissionExceptions(userId);
        }
    }

    private void grantRuntimePermissionsForPackageLocked(int userId, PackageParser.Package pkg) {
        Set<String> permissions = new ArraySet<>();
        for (String permission :  pkg.requestedPermissions) {
            BasePermission bp = mService.mSettings.mPermissions.get(permission);
            if (bp != null && bp.isRuntime()) {
                permissions.add(permission);
            }
        }
        if (!permissions.isEmpty()) {
            grantRuntimePermissionsLPw(pkg, permissions, true, userId);
        }
    }

    private void grantAllRuntimePermissions(int userId) {
        Log.i(TAG, "Granting all runtime permissions for user " + userId);
        synchronized (mService.mPackages) {
            for (PackageParser.Package pkg : mService.mPackages.values()) {
                grantRuntimePermissionsForPackageLocked(userId, pkg);
            }
        }
    }

    public void scheduleReadDefaultPermissionExceptions() {
        mHandler.sendEmptyMessage(MSG_READ_DEFAULT_PERMISSION_EXCEPTIONS);
    }

    private void grantPermissionsToSysComponentsAndPrivApps(int userId) {
        Log.i(TAG, "Granting permissions to platform components for user " + userId);

        synchronized (mService.mPackages) {
            for (PackageParser.Package pkg : mService.mPackages.values()) {
                if (!isSysComponentOrPersistentPlatformSignedPrivAppLPr(pkg)
                        || !doesPackageSupportRuntimePermissions(pkg)
                        || pkg.requestedPermissions.isEmpty()) {
                    continue;
                }
                grantRuntimePermissionsForPackageLocked(userId, pkg);
            }
        }
    }

    private void grantDefaultSystemHandlerPermissions(int userId) {
        Log.i(TAG, "Granting permissions to default platform handlers for user " + userId);

        final PackagesProvider locationPackagesProvider;
        final PackagesProvider voiceInteractionPackagesProvider;
        final PackagesProvider smsAppPackagesProvider;
        final PackagesProvider dialerAppPackagesProvider;
        final PackagesProvider simCallManagerPackagesProvider;
        final SyncAdapterPackagesProvider syncAdapterPackagesProvider;

        synchronized (mService.mPackages) {
            locationPackagesProvider = mLocationPackagesProvider;
            voiceInteractionPackagesProvider = mVoiceInteractionPackagesProvider;
            smsAppPackagesProvider = mSmsAppPackagesProvider;
            dialerAppPackagesProvider = mDialerAppPackagesProvider;
            simCallManagerPackagesProvider = mSimCallManagerPackagesProvider;
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
        String[] contactsSyncAdapterPackages = (syncAdapterPackagesProvider != null) ?
                syncAdapterPackagesProvider.getPackages(ContactsContract.AUTHORITY, userId) : null;
        String[] calendarSyncAdapterPackages = (syncAdapterPackagesProvider != null) ?
                syncAdapterPackagesProvider.getPackages(CalendarContract.AUTHORITY, userId) : null;

        synchronized (mService.mPackages) {
            // Installer
            PackageParser.Package installerPackage = getSystemPackageLPr(
                    mService.mRequiredInstallerPackage);
            if (installerPackage != null
                    && doesPackageSupportRuntimePermissions(installerPackage)) {
                grantRuntimePermissionsLPw(installerPackage, STORAGE_PERMISSIONS, true, userId);
            }

            // Verifier
            PackageParser.Package verifierPackage = getSystemPackageLPr(
                    mService.mRequiredVerifierPackage);
            if (verifierPackage != null
                    && doesPackageSupportRuntimePermissions(verifierPackage)) {
                grantRuntimePermissionsLPw(verifierPackage, STORAGE_PERMISSIONS, true, userId);
                grantRuntimePermissionsLPw(verifierPackage, PHONE_PERMISSIONS, false, userId);
                grantRuntimePermissionsLPw(verifierPackage, SMS_PERMISSIONS, false, userId);
            }

            // SetupWizard
            PackageParser.Package setupPackage = getSystemPackageLPr(
                    mService.mSetupWizardPackage);
            if (setupPackage != null
                    && doesPackageSupportRuntimePermissions(setupPackage)) {
                grantRuntimePermissionsLPw(setupPackage, PHONE_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(setupPackage, CONTACTS_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(setupPackage, LOCATION_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(setupPackage, CAMERA_PERMISSIONS, userId);
            }

            // Camera
            Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            PackageParser.Package cameraPackage = getDefaultSystemHandlerActivityPackageLPr(
                    cameraIntent, userId);
            if (cameraPackage != null
                    && doesPackageSupportRuntimePermissions(cameraPackage)) {
                grantRuntimePermissionsLPw(cameraPackage, CAMERA_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(cameraPackage, MICROPHONE_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(cameraPackage, STORAGE_PERMISSIONS, userId);
            }

            // Media provider
            PackageParser.Package mediaStorePackage = getDefaultProviderAuthorityPackageLPr(
                    MediaStore.AUTHORITY, userId);
            if (mediaStorePackage != null) {
                grantRuntimePermissionsLPw(mediaStorePackage, STORAGE_PERMISSIONS, true, userId);
            }

            // Downloads provider
            PackageParser.Package downloadsPackage = getDefaultProviderAuthorityPackageLPr(
                    "downloads", userId);
            if (downloadsPackage != null) {
                grantRuntimePermissionsLPw(downloadsPackage, STORAGE_PERMISSIONS, true, userId);
            }

            // Downloads UI
            Intent downloadsUiIntent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
            PackageParser.Package downloadsUiPackage = getDefaultSystemHandlerActivityPackageLPr(
                    downloadsUiIntent, userId);
            if (downloadsUiPackage != null
                    && doesPackageSupportRuntimePermissions(downloadsUiPackage)) {
                grantRuntimePermissionsLPw(downloadsUiPackage, STORAGE_PERMISSIONS, true, userId);
            }

            // Storage provider
            PackageParser.Package storagePackage = getDefaultProviderAuthorityPackageLPr(
                    "com.android.externalstorage.documents", userId);
            if (storagePackage != null) {
                grantRuntimePermissionsLPw(storagePackage, STORAGE_PERMISSIONS, true, userId);
            }

            // CertInstaller
            Intent certInstallerIntent = new Intent(Credentials.INSTALL_ACTION);
            PackageParser.Package certInstallerPackage = getDefaultSystemHandlerActivityPackageLPr(
                    certInstallerIntent, userId);
            if (certInstallerPackage != null
                    && doesPackageSupportRuntimePermissions(certInstallerPackage)) {
                grantRuntimePermissionsLPw(certInstallerPackage, STORAGE_PERMISSIONS, true, userId);
            }

            // Dialer
            if (dialerAppPackageNames == null) {
                Intent dialerIntent = new Intent(Intent.ACTION_DIAL);
                PackageParser.Package dialerPackage = getDefaultSystemHandlerActivityPackageLPr(
                        dialerIntent, userId);
                if (dialerPackage != null) {
                    grantDefaultPermissionsToDefaultSystemDialerAppLPr(dialerPackage, userId);
                }
            } else {
                for (String dialerAppPackageName : dialerAppPackageNames) {
                    PackageParser.Package dialerPackage = getSystemPackageLPr(dialerAppPackageName);
                    if (dialerPackage != null) {
                        grantDefaultPermissionsToDefaultSystemDialerAppLPr(dialerPackage, userId);
                    }
                }
            }

            // Sim call manager
            if (simCallManagerPackageNames != null) {
                for (String simCallManagerPackageName : simCallManagerPackageNames) {
                    PackageParser.Package simCallManagerPackage =
                            getSystemPackageLPr(simCallManagerPackageName);
                    if (simCallManagerPackage != null) {
                        grantDefaultPermissionsToDefaultSimCallManagerLPr(simCallManagerPackage,
                                userId);
                    }
                }
            }

            // SMS
            if (smsAppPackageNames == null) {
                Intent smsIntent = new Intent(Intent.ACTION_MAIN);
                smsIntent.addCategory(Intent.CATEGORY_APP_MESSAGING);
                PackageParser.Package smsPackage = getDefaultSystemHandlerActivityPackageLPr(
                        smsIntent, userId);
                if (smsPackage != null) {
                   grantDefaultPermissionsToDefaultSystemSmsAppLPr(smsPackage, userId);
                }
            } else {
                for (String smsPackageName : smsAppPackageNames) {
                    PackageParser.Package smsPackage = getSystemPackageLPr(smsPackageName);
                    if (smsPackage != null) {
                        grantDefaultPermissionsToDefaultSystemSmsAppLPr(smsPackage, userId);
                    }
                }
            }

            // Cell Broadcast Receiver
            Intent cbrIntent = new Intent(Intents.SMS_CB_RECEIVED_ACTION);
            PackageParser.Package cbrPackage =
                    getDefaultSystemHandlerActivityPackageLPr(cbrIntent, userId);
            if (cbrPackage != null && doesPackageSupportRuntimePermissions(cbrPackage)) {
                grantRuntimePermissionsLPw(cbrPackage, SMS_PERMISSIONS, userId);
            }

            // Carrier Provisioning Service
            Intent carrierProvIntent = new Intent(Intents.SMS_CARRIER_PROVISION_ACTION);
            PackageParser.Package carrierProvPackage =
                    getDefaultSystemHandlerServicePackageLPr(carrierProvIntent, userId);
            if (carrierProvPackage != null && doesPackageSupportRuntimePermissions(carrierProvPackage)) {
                grantRuntimePermissionsLPw(carrierProvPackage, SMS_PERMISSIONS, false, userId);
            }

            // Calendar
            Intent calendarIntent = new Intent(Intent.ACTION_MAIN);
            calendarIntent.addCategory(Intent.CATEGORY_APP_CALENDAR);
            PackageParser.Package calendarPackage = getDefaultSystemHandlerActivityPackageLPr(
                    calendarIntent, userId);
            if (calendarPackage != null
                    && doesPackageSupportRuntimePermissions(calendarPackage)) {
                grantRuntimePermissionsLPw(calendarPackage, CALENDAR_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(calendarPackage, CONTACTS_PERMISSIONS, userId);
            }

            // Calendar provider
            PackageParser.Package calendarProviderPackage = getDefaultProviderAuthorityPackageLPr(
                    CalendarContract.AUTHORITY, userId);
            if (calendarProviderPackage != null) {
                grantRuntimePermissionsLPw(calendarProviderPackage, CONTACTS_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(calendarProviderPackage, CALENDAR_PERMISSIONS,
                        true, userId);
                grantRuntimePermissionsLPw(calendarProviderPackage, STORAGE_PERMISSIONS, userId);
            }

            // Calendar provider sync adapters
            List<PackageParser.Package> calendarSyncAdapters = getHeadlessSyncAdapterPackagesLPr(
                    calendarSyncAdapterPackages, userId);
            final int calendarSyncAdapterCount = calendarSyncAdapters.size();
            for (int i = 0; i < calendarSyncAdapterCount; i++) {
                PackageParser.Package calendarSyncAdapter = calendarSyncAdapters.get(i);
                if (doesPackageSupportRuntimePermissions(calendarSyncAdapter)) {
                    grantRuntimePermissionsLPw(calendarSyncAdapter, CALENDAR_PERMISSIONS, userId);
                }
            }

            // Contacts
            Intent contactsIntent = new Intent(Intent.ACTION_MAIN);
            contactsIntent.addCategory(Intent.CATEGORY_APP_CONTACTS);
            PackageParser.Package contactsPackage = getDefaultSystemHandlerActivityPackageLPr(
                    contactsIntent, userId);
            if (contactsPackage != null
                    && doesPackageSupportRuntimePermissions(contactsPackage)) {
                grantRuntimePermissionsLPw(contactsPackage, CONTACTS_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(contactsPackage, PHONE_PERMISSIONS, userId);
            }

            // Contacts provider sync adapters
            List<PackageParser.Package> contactsSyncAdapters = getHeadlessSyncAdapterPackagesLPr(
                    contactsSyncAdapterPackages, userId);
            final int contactsSyncAdapterCount = contactsSyncAdapters.size();
            for (int i = 0; i < contactsSyncAdapterCount; i++) {
                PackageParser.Package contactsSyncAdapter = contactsSyncAdapters.get(i);
                if (doesPackageSupportRuntimePermissions(contactsSyncAdapter)) {
                    grantRuntimePermissionsLPw(contactsSyncAdapter, CONTACTS_PERMISSIONS, userId);
                }
            }

            // Contacts provider
            PackageParser.Package contactsProviderPackage = getDefaultProviderAuthorityPackageLPr(
                    ContactsContract.AUTHORITY, userId);
            if (contactsProviderPackage != null) {
                grantRuntimePermissionsLPw(contactsProviderPackage, CONTACTS_PERMISSIONS,
                        true, userId);
                grantRuntimePermissionsLPw(contactsProviderPackage, PHONE_PERMISSIONS,
                        true, userId);
                grantRuntimePermissionsLPw(contactsProviderPackage, STORAGE_PERMISSIONS, userId);
            }

            // Device provisioning
            Intent deviceProvisionIntent = new Intent(
                    DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE);
            PackageParser.Package deviceProvisionPackage =
                    getDefaultSystemHandlerActivityPackageLPr(deviceProvisionIntent, userId);
            if (deviceProvisionPackage != null
                    && doesPackageSupportRuntimePermissions(deviceProvisionPackage)) {
                grantRuntimePermissionsLPw(deviceProvisionPackage, CONTACTS_PERMISSIONS, userId);
            }

            // Maps
            Intent mapsIntent = new Intent(Intent.ACTION_MAIN);
            mapsIntent.addCategory(Intent.CATEGORY_APP_MAPS);
            PackageParser.Package mapsPackage = getDefaultSystemHandlerActivityPackageLPr(
                    mapsIntent, userId);
            if (mapsPackage != null
                    && doesPackageSupportRuntimePermissions(mapsPackage)) {
                grantRuntimePermissionsLPw(mapsPackage, LOCATION_PERMISSIONS, userId);
            }

            // Gallery
            Intent galleryIntent = new Intent(Intent.ACTION_MAIN);
            galleryIntent.addCategory(Intent.CATEGORY_APP_GALLERY);
            PackageParser.Package galleryPackage = getDefaultSystemHandlerActivityPackageLPr(
                    galleryIntent, userId);
            if (galleryPackage != null
                    && doesPackageSupportRuntimePermissions(galleryPackage)) {
                grantRuntimePermissionsLPw(galleryPackage, STORAGE_PERMISSIONS, userId);
            }

            // Email
            Intent emailIntent = new Intent(Intent.ACTION_MAIN);
            emailIntent.addCategory(Intent.CATEGORY_APP_EMAIL);
            PackageParser.Package emailPackage = getDefaultSystemHandlerActivityPackageLPr(
                    emailIntent, userId);
            if (emailPackage != null
                    && doesPackageSupportRuntimePermissions(emailPackage)) {
                grantRuntimePermissionsLPw(emailPackage, CONTACTS_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(emailPackage, CALENDAR_PERMISSIONS, userId);
            }

            // Browser
            PackageParser.Package browserPackage = null;
            String defaultBrowserPackage = mService.getDefaultBrowserPackageName(userId);
            if (defaultBrowserPackage != null) {
                browserPackage = getPackageLPr(defaultBrowserPackage);
            }
            if (browserPackage == null) {
                Intent browserIntent = new Intent(Intent.ACTION_MAIN);
                browserIntent.addCategory(Intent.CATEGORY_APP_BROWSER);
                browserPackage = getDefaultSystemHandlerActivityPackageLPr(
                        browserIntent, userId);
            }
            if (browserPackage != null
                    && doesPackageSupportRuntimePermissions(browserPackage)) {
                grantRuntimePermissionsLPw(browserPackage, LOCATION_PERMISSIONS, userId);
            }

            // Voice interaction
            if (voiceInteractPackageNames != null) {
                for (String voiceInteractPackageName : voiceInteractPackageNames) {
                    PackageParser.Package voiceInteractPackage = getSystemPackageLPr(
                            voiceInteractPackageName);
                    if (voiceInteractPackage != null
                            && doesPackageSupportRuntimePermissions(voiceInteractPackage)) {
                        grantRuntimePermissionsLPw(voiceInteractPackage,
                                CONTACTS_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(voiceInteractPackage,
                                CALENDAR_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(voiceInteractPackage,
                                MICROPHONE_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(voiceInteractPackage,
                                PHONE_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(voiceInteractPackage,
                                SMS_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(voiceInteractPackage,
                                LOCATION_PERMISSIONS, userId);
                    }
                }
            }

            if (ActivityManager.isLowRamDeviceStatic()) {
                // Allow voice search on low-ram devices
                Intent globalSearchIntent = new Intent("android.search.action.GLOBAL_SEARCH");
                PackageParser.Package globalSearchPickerPackage =
                    getDefaultSystemHandlerActivityPackageLPr(globalSearchIntent, userId);

                if (globalSearchPickerPackage != null
                        && doesPackageSupportRuntimePermissions(globalSearchPickerPackage)) {
                    grantRuntimePermissionsLPw(globalSearchPickerPackage,
                        MICROPHONE_PERMISSIONS, true, userId);
                    grantRuntimePermissionsLPw(globalSearchPickerPackage,
                        LOCATION_PERMISSIONS, true, userId);
                }
            }

            // Voice recognition
            Intent voiceRecoIntent = new Intent("android.speech.RecognitionService");
            voiceRecoIntent.addCategory(Intent.CATEGORY_DEFAULT);
            PackageParser.Package voiceRecoPackage = getDefaultSystemHandlerServicePackageLPr(
                    voiceRecoIntent, userId);
            if (voiceRecoPackage != null
                    && doesPackageSupportRuntimePermissions(voiceRecoPackage)) {
                grantRuntimePermissionsLPw(voiceRecoPackage, MICROPHONE_PERMISSIONS, userId);
            }

            // Location
            if (locationPackageNames != null) {
                for (String packageName : locationPackageNames) {
                    PackageParser.Package locationPackage = getSystemPackageLPr(packageName);
                    if (locationPackage != null
                            && doesPackageSupportRuntimePermissions(locationPackage)) {
                        grantRuntimePermissionsLPw(locationPackage, CONTACTS_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(locationPackage, CALENDAR_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(locationPackage, MICROPHONE_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(locationPackage, PHONE_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(locationPackage, SMS_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(locationPackage, LOCATION_PERMISSIONS,
                                true, userId);
                        grantRuntimePermissionsLPw(locationPackage, CAMERA_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(locationPackage, SENSORS_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(locationPackage, STORAGE_PERMISSIONS, userId);
                    }
                }
            }

            // Music
            Intent musicIntent = new Intent(Intent.ACTION_VIEW);
            musicIntent.addCategory(Intent.CATEGORY_DEFAULT);
            musicIntent.setDataAndType(Uri.fromFile(new File("foo.mp3")),
                    AUDIO_MIME_TYPE);
            PackageParser.Package musicPackage = getDefaultSystemHandlerActivityPackageLPr(
                    musicIntent, userId);
            if (musicPackage != null
                    && doesPackageSupportRuntimePermissions(musicPackage)) {
                grantRuntimePermissionsLPw(musicPackage, STORAGE_PERMISSIONS, userId);
            }

            // Home
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            homeIntent.addCategory(Intent.CATEGORY_LAUNCHER_APP);
            PackageParser.Package homePackage = getDefaultSystemHandlerActivityPackageLPr(
                    homeIntent, userId);
            if (homePackage != null
                    && doesPackageSupportRuntimePermissions(homePackage)) {
                grantRuntimePermissionsLPw(homePackage, LOCATION_PERMISSIONS, false, userId);
            }

            // Watches
            if (mService.hasSystemFeature(PackageManager.FEATURE_WATCH, 0)) {
                // Home application on watches
                Intent wearHomeIntent = new Intent(Intent.ACTION_MAIN);
                wearHomeIntent.addCategory(Intent.CATEGORY_HOME_MAIN);

                PackageParser.Package wearHomePackage = getDefaultSystemHandlerActivityPackageLPr(
                        wearHomeIntent, userId);

                if (wearHomePackage != null
                        && doesPackageSupportRuntimePermissions(wearHomePackage)) {
                    grantRuntimePermissionsLPw(wearHomePackage, CONTACTS_PERMISSIONS, false,
                            userId);
                    grantRuntimePermissionsLPw(wearHomePackage, PHONE_PERMISSIONS, true, userId);
                    grantRuntimePermissionsLPw(wearHomePackage, MICROPHONE_PERMISSIONS, false,
                            userId);
                    grantRuntimePermissionsLPw(wearHomePackage, LOCATION_PERMISSIONS, false,
                            userId);
                }

                // Fitness tracking on watches
                Intent trackIntent = new Intent(ACTION_TRACK);
                PackageParser.Package trackPackage = getDefaultSystemHandlerActivityPackageLPr(
                        trackIntent, userId);
                if (trackPackage != null
                        && doesPackageSupportRuntimePermissions(trackPackage)) {
                    grantRuntimePermissionsLPw(trackPackage, SENSORS_PERMISSIONS, false, userId);
                    grantRuntimePermissionsLPw(trackPackage, LOCATION_PERMISSIONS, false, userId);
                }
            }

            // Print Spooler
            PackageParser.Package printSpoolerPackage = getSystemPackageLPr(
                    PrintManager.PRINT_SPOOLER_PACKAGE_NAME);
            if (printSpoolerPackage != null
                    && doesPackageSupportRuntimePermissions(printSpoolerPackage)) {
                grantRuntimePermissionsLPw(printSpoolerPackage, LOCATION_PERMISSIONS, true, userId);
            }

            // EmergencyInfo
            Intent emergencyInfoIntent = new Intent(TelephonyManager.ACTION_EMERGENCY_ASSISTANCE);
            PackageParser.Package emergencyInfoPckg = getDefaultSystemHandlerActivityPackageLPr(
                    emergencyInfoIntent, userId);
            if (emergencyInfoPckg != null
                    && doesPackageSupportRuntimePermissions(emergencyInfoPckg)) {
                grantRuntimePermissionsLPw(emergencyInfoPckg, CONTACTS_PERMISSIONS, true, userId);
                grantRuntimePermissionsLPw(emergencyInfoPckg, PHONE_PERMISSIONS, true, userId);
            }

            // NFC Tag viewer
            Intent nfcTagIntent = new Intent(Intent.ACTION_VIEW);
            nfcTagIntent.setType("vnd.android.cursor.item/ndef_msg");
            PackageParser.Package nfcTagPkg = getDefaultSystemHandlerActivityPackageLPr(
                    nfcTagIntent, userId);
            if (nfcTagPkg != null
                    && doesPackageSupportRuntimePermissions(nfcTagPkg)) {
                grantRuntimePermissionsLPw(nfcTagPkg, CONTACTS_PERMISSIONS, false, userId);
                grantRuntimePermissionsLPw(nfcTagPkg, PHONE_PERMISSIONS, false, userId);
            }

            // Storage Manager
            Intent storageManagerIntent = new Intent(StorageManager.ACTION_MANAGE_STORAGE);
            PackageParser.Package storageManagerPckg = getDefaultSystemHandlerActivityPackageLPr(
                    storageManagerIntent, userId);
            if (storageManagerPckg != null
                    && doesPackageSupportRuntimePermissions(storageManagerPckg)) {
                grantRuntimePermissionsLPw(storageManagerPckg, STORAGE_PERMISSIONS, true, userId);
            }

            // Companion devices
            PackageParser.Package companionDeviceDiscoveryPackage = getSystemPackageLPr(
                    CompanionDeviceManager.COMPANION_DEVICE_DISCOVERY_PACKAGE_NAME);
            if (companionDeviceDiscoveryPackage != null
                    && doesPackageSupportRuntimePermissions(companionDeviceDiscoveryPackage)) {
                grantRuntimePermissionsLPw(companionDeviceDiscoveryPackage,
                        LOCATION_PERMISSIONS, true, userId);
            }

            // Ringtone Picker
            Intent ringtonePickerIntent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
            PackageParser.Package ringtonePickerPackage =
                    getDefaultSystemHandlerActivityPackageLPr(ringtonePickerIntent, userId);
            if (ringtonePickerPackage != null
                    && doesPackageSupportRuntimePermissions(ringtonePickerPackage)) {
                grantRuntimePermissionsLPw(ringtonePickerPackage,
                        STORAGE_PERMISSIONS, true, userId);
            }

            mService.mSettings.onDefaultRuntimePermissionsGrantedLPr(userId);
        }
    }

    private void grantDefaultPermissionsToDefaultSystemDialerAppLPr(
            PackageParser.Package dialerPackage, int userId) {
        if (doesPackageSupportRuntimePermissions(dialerPackage)) {
            boolean isPhonePermFixed =
                    mService.hasSystemFeature(PackageManager.FEATURE_WATCH, 0);
            grantRuntimePermissionsLPw(
                    dialerPackage, PHONE_PERMISSIONS, isPhonePermFixed, userId);
            grantRuntimePermissionsLPw(dialerPackage, CONTACTS_PERMISSIONS, userId);
            grantRuntimePermissionsLPw(dialerPackage, SMS_PERMISSIONS, userId);
            grantRuntimePermissionsLPw(dialerPackage, MICROPHONE_PERMISSIONS, userId);
            grantRuntimePermissionsLPw(dialerPackage, CAMERA_PERMISSIONS, userId);
        }
    }

    private void grantDefaultPermissionsToDefaultSystemSmsAppLPr(
            PackageParser.Package smsPackage, int userId) {
        if (doesPackageSupportRuntimePermissions(smsPackage)) {
            grantRuntimePermissionsLPw(smsPackage, PHONE_PERMISSIONS, userId);
            grantRuntimePermissionsLPw(smsPackage, CONTACTS_PERMISSIONS, userId);
            grantRuntimePermissionsLPw(smsPackage, SMS_PERMISSIONS, userId);
            grantRuntimePermissionsLPw(smsPackage, STORAGE_PERMISSIONS, userId);
            grantRuntimePermissionsLPw(smsPackage, MICROPHONE_PERMISSIONS, userId);
            grantRuntimePermissionsLPw(smsPackage, CAMERA_PERMISSIONS, userId);
        }
    }

    public void grantDefaultPermissionsToDefaultSmsAppLPr(String packageName, int userId) {
        Log.i(TAG, "Granting permissions to default sms app for user:" + userId);
        if (packageName == null) {
            return;
        }
        PackageParser.Package smsPackage = getPackageLPr(packageName);
        if (smsPackage != null && doesPackageSupportRuntimePermissions(smsPackage)) {
            grantRuntimePermissionsLPw(smsPackage, PHONE_PERMISSIONS, false, true, userId);
            grantRuntimePermissionsLPw(smsPackage, CONTACTS_PERMISSIONS, false, true, userId);
            grantRuntimePermissionsLPw(smsPackage, SMS_PERMISSIONS, false, true, userId);
            grantRuntimePermissionsLPw(smsPackage, STORAGE_PERMISSIONS, false, true, userId);
            grantRuntimePermissionsLPw(smsPackage, MICROPHONE_PERMISSIONS, false, true, userId);
            grantRuntimePermissionsLPw(smsPackage, CAMERA_PERMISSIONS, false, true, userId);
        }
    }

    public void grantDefaultPermissionsToDefaultDialerAppLPr(String packageName, int userId) {
        Log.i(TAG, "Granting permissions to default dialer app for user:" + userId);
        if (packageName == null) {
            return;
        }
        PackageParser.Package dialerPackage = getPackageLPr(packageName);
        if (dialerPackage != null
                && doesPackageSupportRuntimePermissions(dialerPackage)) {
            grantRuntimePermissionsLPw(dialerPackage, PHONE_PERMISSIONS, false, true, userId);
            grantRuntimePermissionsLPw(dialerPackage, CONTACTS_PERMISSIONS, false, true, userId);
            grantRuntimePermissionsLPw(dialerPackage, SMS_PERMISSIONS, false, true, userId);
            grantRuntimePermissionsLPw(dialerPackage, MICROPHONE_PERMISSIONS, false, true, userId);
            grantRuntimePermissionsLPw(dialerPackage, CAMERA_PERMISSIONS, false, true, userId);
        }
    }

    private void grantDefaultPermissionsToDefaultSimCallManagerLPr(
            PackageParser.Package simCallManagerPackage, int userId) {
        Log.i(TAG, "Granting permissions to sim call manager for user:" + userId);
        if (doesPackageSupportRuntimePermissions(simCallManagerPackage)) {
            grantRuntimePermissionsLPw(simCallManagerPackage, PHONE_PERMISSIONS, userId);
            grantRuntimePermissionsLPw(simCallManagerPackage, MICROPHONE_PERMISSIONS, userId);
        }
    }

    public void grantDefaultPermissionsToDefaultSimCallManagerLPr(String packageName, int userId) {
        if (packageName == null) {
            return;
        }
        PackageParser.Package simCallManagerPackage = getPackageLPr(packageName);
        if (simCallManagerPackage != null) {
            grantDefaultPermissionsToDefaultSimCallManagerLPr(simCallManagerPackage, userId);
        }
    }

    public void grantDefaultPermissionsToEnabledCarrierAppsLPr(String[] packageNames, int userId) {
        Log.i(TAG, "Granting permissions to enabled carrier apps for user:" + userId);
        if (packageNames == null) {
            return;
        }
        for (String packageName : packageNames) {
            PackageParser.Package carrierPackage = getSystemPackageLPr(packageName);
            if (carrierPackage != null
                    && doesPackageSupportRuntimePermissions(carrierPackage)) {
                grantRuntimePermissionsLPw(carrierPackage, PHONE_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(carrierPackage, LOCATION_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(carrierPackage, SMS_PERMISSIONS, userId);
            }
        }
    }

    public void grantDefaultPermissionsToEnabledImsServicesLPr(String[] packageNames, int userId) {
        Log.i(TAG, "Granting permissions to enabled ImsServices for user:" + userId);
        if (packageNames == null) {
            return;
        }
        for (String packageName : packageNames) {
            PackageParser.Package imsServicePackage = getSystemPackageLPr(packageName);
            if (imsServicePackage != null
                    && doesPackageSupportRuntimePermissions(imsServicePackage)) {
                grantRuntimePermissionsLPw(imsServicePackage, PHONE_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(imsServicePackage, MICROPHONE_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(imsServicePackage, LOCATION_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(imsServicePackage, CAMERA_PERMISSIONS, userId);
            }
        }
    }

    public void grantDefaultPermissionsToDefaultBrowserLPr(String packageName, int userId) {
        Log.i(TAG, "Granting permissions to default browser for user:" + userId);
        if (packageName == null) {
            return;
        }
        PackageParser.Package browserPackage = getSystemPackageLPr(packageName);
        if (browserPackage != null
                && doesPackageSupportRuntimePermissions(browserPackage)) {
            grantRuntimePermissionsLPw(browserPackage, LOCATION_PERMISSIONS, false, false, userId);
        }
    }

    private PackageParser.Package getDefaultSystemHandlerActivityPackageLPr(
            Intent intent, int userId) {
        ResolveInfo handler = mService.resolveIntent(intent,
                intent.resolveType(mService.mContext.getContentResolver()), DEFAULT_FLAGS, userId);
        if (handler == null || handler.activityInfo == null) {
            return null;
        }
        ActivityInfo activityInfo = handler.activityInfo;
        if (activityInfo.packageName.equals(mService.mResolveActivity.packageName)
                && activityInfo.name.equals(mService.mResolveActivity.name)) {
            return null;
        }
        return getSystemPackageLPr(handler.activityInfo.packageName);
    }

    private PackageParser.Package getDefaultSystemHandlerServicePackageLPr(
            Intent intent, int userId) {
        List<ResolveInfo> handlers = mService.queryIntentServices(intent,
                intent.resolveType(mService.mContext.getContentResolver()), DEFAULT_FLAGS, userId)
                .getList();
        if (handlers == null) {
            return null;
        }
        final int handlerCount = handlers.size();
        for (int i = 0; i < handlerCount; i++) {
            ResolveInfo handler = handlers.get(i);
            PackageParser.Package handlerPackage = getSystemPackageLPr(
                    handler.serviceInfo.packageName);
            if (handlerPackage != null) {
                return handlerPackage;
            }
        }
        return null;
    }

    private List<PackageParser.Package> getHeadlessSyncAdapterPackagesLPr(
            String[] syncAdapterPackageNames, int userId) {
        List<PackageParser.Package> syncAdapterPackages = new ArrayList<>();

        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        for (String syncAdapterPackageName : syncAdapterPackageNames) {
            homeIntent.setPackage(syncAdapterPackageName);

            ResolveInfo homeActivity = mService.resolveIntent(homeIntent,
                    homeIntent.resolveType(mService.mContext.getContentResolver()), DEFAULT_FLAGS,
                    userId);
            if (homeActivity != null) {
                continue;
            }

            PackageParser.Package syncAdapterPackage = getSystemPackageLPr(syncAdapterPackageName);
            if (syncAdapterPackage != null) {
                syncAdapterPackages.add(syncAdapterPackage);
            }
        }

        return syncAdapterPackages;
    }

    private PackageParser.Package getDefaultProviderAuthorityPackageLPr(
            String authority, int userId) {
        ProviderInfo provider = mService.resolveContentProvider(authority, DEFAULT_FLAGS, userId);
        if (provider != null) {
            return getSystemPackageLPr(provider.packageName);
        }
        return null;
    }

    private PackageParser.Package getPackageLPr(String packageName) {
        return mService.mPackages.get(packageName);
    }

    private PackageParser.Package getSystemPackageLPr(String packageName) {
        PackageParser.Package pkg = getPackageLPr(packageName);
        if (pkg != null && pkg.isSystemApp()) {
            return !isSysComponentOrPersistentPlatformSignedPrivAppLPr(pkg) ? pkg : null;
        }
        return null;
    }

    private void grantRuntimePermissionsLPw(PackageParser.Package pkg, Set<String> permissions,
            int userId) {
        grantRuntimePermissionsLPw(pkg, permissions, false, false, userId);
    }

    private void grantRuntimePermissionsLPw(PackageParser.Package pkg, Set<String> permissions,
            boolean systemFixed, int userId) {
        grantRuntimePermissionsLPw(pkg, permissions, systemFixed, false, userId);
    }

    private void grantRuntimePermissionsLPw(PackageParser.Package pkg, Set<String> permissions,
            boolean systemFixed, boolean isDefaultPhoneOrSms, int userId) {
        if (pkg.requestedPermissions.isEmpty()) {
            return;
        }

        List<String> requestedPermissions = pkg.requestedPermissions;
        Set<String> grantablePermissions = null;

        // If this is the default Phone or SMS app we grant permissions regardless
        // whether the version on the system image declares the permission as used since
        // selecting the app as the default Phone or SMS the user makes a deliberate
        // choice to grant this app the permissions needed to function. For all other
        // apps, (default grants on first boot and user creation) we don't grant default
        // permissions if the version on the system image does not declare them.
        if (!isDefaultPhoneOrSms && pkg.isUpdatedSystemApp()) {
            PackageSetting sysPs = mService.mSettings.getDisabledSystemPkgLPr(pkg.packageName);
            if (sysPs != null && sysPs.pkg != null) {
                if (sysPs.pkg.requestedPermissions.isEmpty()) {
                    return;
                }
                if (!requestedPermissions.equals(sysPs.pkg.requestedPermissions)) {
                    grantablePermissions = new ArraySet<>(requestedPermissions);
                    requestedPermissions = sysPs.pkg.requestedPermissions;
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
                final int flags = mService.getPermissionFlags(permission, pkg.packageName, userId);

                // If any flags are set to the permission, then it is either set in
                // its current state by the system or device/profile owner or the user.
                // In all these cases we do not want to clobber the current state.
                // Unless the caller wants to override user choices. The override is
                // to make sure we can grant the needed permission to the default
                // sms and phone apps after the user chooses this in the UI.
                if (flags == 0 || isDefaultPhoneOrSms) {
                    // Never clobber policy or system.
                    final int fixedFlags = PackageManager.FLAG_PERMISSION_SYSTEM_FIXED
                            | PackageManager.FLAG_PERMISSION_POLICY_FIXED;
                    if ((flags & fixedFlags) != 0) {
                        continue;
                    }

                    mService.grantRuntimePermission(pkg.packageName, permission, userId);
                    if (DEBUG) {
                        Log.i(TAG, "Granted " + (systemFixed ? "fixed " : "not fixed ")
                                + permission + " to default handler " + pkg.packageName);
                    }

                    int newFlags = PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT;
                    if (systemFixed) {
                        newFlags |= PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
                    }

                    mService.updatePermissionFlags(permission, pkg.packageName,
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
                    mService.updatePermissionFlags(permission, pkg.packageName,
                            PackageManager.FLAG_PERMISSION_SYSTEM_FIXED, 0, userId);
                }
            }
        }
    }

    private boolean isSysComponentOrPersistentPlatformSignedPrivAppLPr(PackageParser.Package pkg) {
        if (UserHandle.getAppId(pkg.applicationInfo.uid) < FIRST_APPLICATION_UID) {
            return true;
        }
        if (!pkg.isPrivilegedApp()) {
            return false;
        }
        PackageSetting sysPkg = mService.mSettings.getDisabledSystemPkgLPr(pkg.packageName);
        if (sysPkg != null && sysPkg.pkg != null) {
            if ((sysPkg.pkg.applicationInfo.flags & ApplicationInfo.FLAG_PERSISTENT) == 0) {
                return false;
            }
        } else if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_PERSISTENT) == 0) {
            return false;
        }
        return PackageManagerService.compareSignatures(mService.mPlatformPackage.mSignatures,
                pkg.mSignatures) == PackageManager.SIGNATURE_MATCH;
    }

    private void grantDefaultPermissionExceptions(int userId) {
        synchronized (mService.mPackages) {
            mHandler.removeMessages(MSG_READ_DEFAULT_PERMISSION_EXCEPTIONS);

            if (mGrantExceptions == null) {
                mGrantExceptions = readDefaultPermissionExceptionsLPw();
            }

            // mGrantExceptions is null only before the first read and then
            // it serves as a cache of the default grants that should be
            // performed for every user. If there is an entry then the app
            // is on the system image and supports runtime permissions.
            Set<String> permissions = null;
            final int exceptionCount = mGrantExceptions.size();
            for (int i = 0; i < exceptionCount; i++) {
                String packageName = mGrantExceptions.keyAt(i);
                PackageParser.Package pkg = getSystemPackageLPr(packageName);
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
                    grantRuntimePermissionsLPw(pkg, permissions,
                            permissionGrant.fixed, userId);
                }
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
        return ret.isEmpty() ? null : ret.toArray(new File[0]);
    }

    private @NonNull ArrayMap<String, List<DefaultPermissionGrant>>
            readDefaultPermissionExceptionsLPw() {
        File[] files = getDefaultPermissionFiles();
        if (files == null) {
            return new ArrayMap<>(0);
        }

        ArrayMap<String, List<DefaultPermissionGrant>> grantExceptions = new ArrayMap<>();

        // Iterate over the files in the directory and scan .xml files
        for (File file : files) {
            if (!file.getPath().endsWith(".xml")) {
                Slog.i(TAG, "Non-xml file " + file + " in " + file.getParent() + " directory, ignoring");
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
                    PackageParser.Package pkg = getSystemPackageLPr(packageName);
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
