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

package com.android.server.locales;

import static android.os.UserHandle.USER_NULL;

import static com.android.server.locales.LocaleManagerService.DEBUG;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.LocaleConfig;
import android.app.backup.BackupManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.HandlerThread;
import android.os.LocaleList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;

/**
 * Helper class for managing backup and restore of app-specific locales.
 */
class LocaleManagerBackupHelper {
    private static final String TAG = "LocaleManagerBkpHelper"; // must be < 23 chars

    // Tags and attributes for xml.
    private static final String LOCALES_XML_TAG = "locales";
    private static final String PACKAGE_XML_TAG = "package";
    private static final String ATTR_PACKAGE_NAME = "name";
    private static final String ATTR_LOCALES = "locales";
    private static final String ATTR_DELEGATE_SELECTOR = "delegate_selector";

    private static final String SYSTEM_BACKUP_PACKAGE_KEY = "android";
    /**
     * The name of the xml file used to persist the target package name that sets per-app locales
     * from the delegate selector.
     */
    private static final String LOCALES_FROM_DELEGATE_PREFS = "LocalesFromDelegatePrefs.xml";
    // Stage data would be deleted on reboot since it's stored in memory. So it's retained until
    // retention period OR next reboot, whichever happens earlier.
    private static final Duration STAGE_DATA_RETENTION_PERIOD = Duration.ofDays(3);

    private final LocaleManagerService mLocaleManagerService;
    private final PackageManager mPackageManager;
    private final Clock mClock;
    private final Context mContext;
    private final Object mStagedDataLock = new Object();

    // Staged data map keyed by user-id to handle multi-user scenario / work profiles. We are using
    // SparseArray because it is more memory-efficient than a HashMap.
    private final SparseArray<StagedData> mStagedData;

    // SharedPreferences to store packages whose app-locale was set by a delegate, as opposed to
    // the application setting the app-locale itself.
    private final SharedPreferences mDelegateAppLocalePackages;
    private final BroadcastReceiver mUserMonitor;
    // To determine whether an app is pre-archived, check for Intent.EXTRA_ARCHIVAL upon receiving
    // the initial PACKAGE_ADDED broadcast. If it is indeed pre-archived, perform the data
    // restoration during the second PACKAGE_ADDED broadcast, which is sent subsequently when the
    // app is installed.
    private final Set<String> mPkgsToRestore;

    LocaleManagerBackupHelper(LocaleManagerService localeManagerService,
            PackageManager packageManager, HandlerThread broadcastHandlerThread) {
        this(localeManagerService.mContext, localeManagerService, packageManager, Clock.systemUTC(),
                new SparseArray<>(), broadcastHandlerThread, null);
    }

    @VisibleForTesting LocaleManagerBackupHelper(Context context,
            LocaleManagerService localeManagerService,
            PackageManager packageManager, Clock clock, SparseArray<StagedData> stagedData,
            HandlerThread broadcastHandlerThread, SharedPreferences delegateAppLocalePackages) {
        mContext = context;
        mLocaleManagerService = localeManagerService;
        mPackageManager = packageManager;
        mClock = clock;
        mStagedData = stagedData;
        mDelegateAppLocalePackages = delegateAppLocalePackages != null ? delegateAppLocalePackages
                : createPersistedInfo();
        mPkgsToRestore = new ArraySet<>();

        mUserMonitor = new UserMonitor();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_REMOVED);
        context.registerReceiverAsUser(mUserMonitor, UserHandle.ALL, filter,
                null, broadcastHandlerThread.getThreadHandler());
    }

    @VisibleForTesting
    BroadcastReceiver getUserMonitor() {
        return mUserMonitor;
    }

    /**
     * @see LocaleManagerInternal#getBackupPayload(int userId)
     */
    public byte[] getBackupPayload(int userId) {
        if (DEBUG) {
            Slog.d(TAG, "getBackupPayload invoked for user id " + userId);
        }

        synchronized (mStagedDataLock) {
            cleanStagedDataForOldEntriesLocked();
        }

        HashMap<String, LocalesInfo> pkgStates = new HashMap<>();
        for (ApplicationInfo appInfo : mPackageManager.getInstalledApplicationsAsUser(
                PackageManager.ApplicationInfoFlags.of(0), userId)) {
            try {
                LocaleList appLocales = mLocaleManagerService.getApplicationLocales(
                        appInfo.packageName,
                        userId);
                // Backup locales and package names for per-app locales set from a delegate
                // selector only for apps which do have app-specific overrides.
                if (!appLocales.isEmpty()) {
                    if (DEBUG) {
                        Slog.d(TAG, "Add package=" + appInfo.packageName + " locales="
                                + appLocales.toLanguageTags() + " to backup payload");
                    }
                    boolean localeSetFromDelegate = false;
                    if (mDelegateAppLocalePackages != null) {
                        localeSetFromDelegate = mDelegateAppLocalePackages.getStringSet(
                                Integer.toString(userId), Collections.<String>emptySet()).contains(
                                appInfo.packageName);
                    }
                    LocalesInfo localesInfo = new LocalesInfo(appLocales.toLanguageTags(),
                            localeSetFromDelegate);
                    pkgStates.put(appInfo.packageName, localesInfo);
                }
            } catch (RemoteException | IllegalArgumentException e) {
                Slog.e(TAG, "Exception when getting locales for package: " + appInfo.packageName,
                        e);
            }
        }

        if (pkgStates.isEmpty()) {
            if (DEBUG) {
                Slog.d(TAG, "Final payload=null");
            }
            // Returning null here will ensure deletion of the entry for LMS from the backup data.
            return null;
        }

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            writeToXml(out, pkgStates);
        } catch (IOException e) {
            Slog.e(TAG, "Could not write to xml for backup ", e);
            return null;
        }

        if (DEBUG) {
            try {
                Slog.d(TAG, "Final payload=" + out.toString("UTF-8"));
            } catch (UnsupportedEncodingException e) {
                Slog.w(TAG, "Could not encode payload to UTF-8", e);
            }
        }
        return out.toByteArray();
    }

    private void cleanStagedDataForOldEntriesLocked() {
        for (int i = 0; i < mStagedData.size(); i++) {
            int userId = mStagedData.keyAt(i);
            StagedData stagedData = mStagedData.get(userId);
            if (stagedData.mCreationTimeMillis
                    < mClock.millis() - STAGE_DATA_RETENTION_PERIOD.toMillis()) {
                deleteStagedDataLocked(userId);
            }
        }
    }

    /**
     * @see LocaleManagerInternal#stageAndApplyRestoredPayload(byte[] payload, int userId)
     */
    public void stageAndApplyRestoredPayload(byte[] payload, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "stageAndApplyRestoredPayload user=" + userId + " payload="
                    + (payload != null ? new String(payload, StandardCharsets.UTF_8) : null));
        }
        if (payload == null) {
            Slog.e(TAG, "stageAndApplyRestoredPayload: no payload to restore for user " + userId);
            return;
        }

        final ByteArrayInputStream inputStream = new ByteArrayInputStream(payload);

        HashMap<String, LocalesInfo> pkgStates;
        try {
            // Parse the input blob into a list of BackupPackageState.
            final TypedXmlPullParser parser = Xml.newFastPullParser();
            parser.setInput(inputStream, StandardCharsets.UTF_8.name());

            XmlUtils.beginDocument(parser, LOCALES_XML_TAG);
            pkgStates = readFromXml(parser);
        } catch (IOException | XmlPullParserException e) {
            Slog.e(TAG, "Could not parse payload ", e);
            return;
        }

        // We need a lock here to prevent race conditions when accessing the stage file.
        // It might happen that a restore was triggered (manually using bmgr cmd) and at the same
        // time a new package is added. We want to ensure that both these operations aren't
        // performed simultaneously.
        synchronized (mStagedDataLock) {
            // Backups for apps which are yet to be installed.
            StagedData stagedData = new StagedData(mClock.millis(), new HashMap<>());

            for (String pkgName : pkgStates.keySet()) {
                LocalesInfo localesInfo = pkgStates.get(pkgName);
                // Check if the application is already installed for the concerned user.
                if (isPackageInstalledForUser(pkgName, userId)) {
                    if (mPkgsToRestore != null) {
                        mPkgsToRestore.remove(pkgName);
                    }
                    // Don't apply the restore if the locales have already been set for the app.
                    checkExistingLocalesAndApplyRestore(pkgName, localesInfo, userId);
                } else {
                    // Stage the data if the app isn't installed.
                    stagedData.mPackageStates.put(pkgName, localesInfo);
                    if (DEBUG) {
                        Slog.d(TAG, "Add locales=" + localesInfo.mLocales
                                + " fromDelegate=" + localesInfo.mSetFromDelegate
                                + " package=" + pkgName + " for lazy restore.");
                    }
                }
            }

            if (!stagedData.mPackageStates.isEmpty()) {
                mStagedData.put(userId, stagedData);
            }
        }
    }

    /**
     * Notifies the backup manager to include the "android" package in the next backup pass.
     */
    public void notifyBackupManager() {
        BackupManager.dataChanged(SYSTEM_BACKUP_PACKAGE_KEY);
    }

    /**
     * <p><b>Note:</b> This is invoked by service's common monitor
     * {@link LocaleManagerServicePackageMonitor#onPackageAddedWithExtras} when a new package is
     * added on device.
     */
    void onPackageAddedWithExtras(String packageName, int uid, Bundle extras) {
        boolean archived = false;
        if (extras != null) {
            archived = extras.getBoolean(Intent.EXTRA_ARCHIVAL, false);
            if (archived && mPkgsToRestore != null) {
                mPkgsToRestore.add(packageName);
            }
        }
        checkStageDataAndApplyRestore(packageName, uid);
    }

    /**
     * <p><b>Note:</b> This is invoked by service's common monitor
     * {@link LocaleManagerServicePackageMonitor#onPackageUpdateFinished} when a package is upgraded
     * on device.
     */
    void onPackageUpdateFinished(String packageName, int uid) {
        int userId = UserHandle.getUserId(uid);
        if (mPkgsToRestore != null && mPkgsToRestore.contains(packageName)) {
            mPkgsToRestore.remove(packageName);
            checkStageDataAndApplyRestore(packageName, uid);
        }
        cleanApplicationLocalesIfNeeded(packageName, userId);
    }

    /**
     * <p><b>Note:</b> This is invoked by service's common monitor
     * {@link LocaleManagerServicePackageMonitor#onPackageDataCleared} when a package's data
     * is cleared.
     */
    void onPackageDataCleared(String packageName, int uid) {
        try {
            notifyBackupManager();
            int userId = UserHandle.getUserId(uid);
            removePackageFromPersistedInfo(packageName, userId);
        } catch (Exception e) {
            Slog.e(TAG, "Exception in onPackageDataCleared.", e);
        }
    }

    /**
     * <p><b>Note:</b> This is invoked by service's common monitor
     * {@link LocaleManagerServicePackageMonitor#onPackageRemoved} when a package is removed
     * from device.
     */
    void onPackageRemoved(String packageName, int uid) {
        try {
            notifyBackupManager();
            int userId = UserHandle.getUserId(uid);
            removePackageFromPersistedInfo(packageName, userId);
        } catch (Exception e) {
            Slog.e(TAG, "Exception in onPackageRemoved.", e);
        }
    }

    private void checkStageDataAndApplyRestore(String packageName, int uid) {
        try {
            synchronized (mStagedDataLock) {
                cleanStagedDataForOldEntriesLocked();

                int userId = UserHandle.getUserId(uid);
                if (mStagedData.contains(userId)) {
                    if (mPkgsToRestore != null) {
                        mPkgsToRestore.remove(packageName);
                    }
                    // Perform lazy restore only if the staged data exists.
                    doLazyRestoreLocked(packageName, userId);
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Exception in onPackageAdded.", e);
        }
    }

    private boolean isPackageInstalledForUser(String packageName, int userId) {
        PackageInfo pkgInfo = null;
        try {
            pkgInfo = mContext.getPackageManager().getPackageInfoAsUser(
                    packageName, /* flags= */ 0, userId);
        } catch (PackageManager.NameNotFoundException e) {
            if (DEBUG) {
                Slog.d(TAG, "Could not get package info for " + packageName, e);
            }
        }
        return pkgInfo != null;
    }

    /**
     * Checks if locales already exist for the application and applies the restore accordingly.
     * <p>
     * The user might change the locales for an application before the restore is applied. In this
     * case, we want to keep the user settings and discard the restore.
     */
    private void checkExistingLocalesAndApplyRestore(@NonNull String pkgName,
            LocalesInfo localesInfo, int userId) {
        if (localesInfo == null) {
            Slog.w(TAG, "No locales info for " + pkgName);
            return;
        }

        try {
            LocaleList currLocales = mLocaleManagerService.getApplicationLocales(
                    pkgName,
                    userId);
            if (!currLocales.isEmpty()) {
                return;
            }
        } catch (RemoteException | IllegalArgumentException e) {
            Slog.e(TAG, "Could not check for current locales before restoring", e);
        }

        // Restore the locale immediately
        try {
            mLocaleManagerService.setApplicationLocales(pkgName, userId,
                    LocaleList.forLanguageTags(localesInfo.mLocales), localesInfo.mSetFromDelegate,
                    FrameworkStatsLog.APPLICATION_LOCALES_CHANGED__CALLER__CALLER_BACKUP_RESTORE);
            if (DEBUG) {
                Slog.d(TAG, "Restored locales=" + localesInfo.mLocales + " fromDelegate="
                        + localesInfo.mSetFromDelegate + " for package=" + pkgName);
            }
        } catch (RemoteException | IllegalArgumentException e) {
            Slog.e(TAG, "Could not restore locales for " + pkgName, e);
        }
    }

    private void deleteStagedDataLocked(@UserIdInt int userId) {
        mStagedData.remove(userId);
    }

    /**
     * Parses the backup data from the serialized xml input stream.
     */
    private @NonNull HashMap<String, LocalesInfo> readFromXml(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        HashMap<String, LocalesInfo> packageStates = new HashMap<>();
        int depth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, depth)) {
            if (parser.getName().equals(PACKAGE_XML_TAG)) {
                String packageName = parser.getAttributeValue(/* namespace= */ null,
                        ATTR_PACKAGE_NAME);
                String languageTags = parser.getAttributeValue(/* namespace= */ null, ATTR_LOCALES);
                boolean delegateSelector = parser.getAttributeBoolean(/* namespace= */ null,
                        ATTR_DELEGATE_SELECTOR, false);

                if (!TextUtils.isEmpty(packageName) && !TextUtils.isEmpty(languageTags)) {
                    LocalesInfo localesInfo = new LocalesInfo(languageTags, delegateSelector);
                    packageStates.put(packageName, localesInfo);
                }
            }
        }
        return packageStates;
    }

    /**
     * Converts the list of app backup data into a serialized xml stream.
     */
    private static void writeToXml(OutputStream stream,
            @NonNull HashMap<String, LocalesInfo> pkgStates) throws IOException {
        if (pkgStates.isEmpty()) {
            // No need to write anything at all if pkgStates is empty.
            return;
        }

        TypedXmlSerializer out = Xml.newFastSerializer();
        out.setOutput(stream, StandardCharsets.UTF_8.name());
        out.startDocument(/* encoding= */ null, /* standalone= */ true);
        out.startTag(/* namespace= */ null, LOCALES_XML_TAG);

        for (String pkg : pkgStates.keySet()) {
            out.startTag(/* namespace= */ null, PACKAGE_XML_TAG);
            out.attribute(/* namespace= */ null, ATTR_PACKAGE_NAME, pkg);
            out.attribute(/* namespace= */ null, ATTR_LOCALES, pkgStates.get(pkg).mLocales);
            out.attributeBoolean(/* namespace= */ null, ATTR_DELEGATE_SELECTOR,
                    pkgStates.get(pkg).mSetFromDelegate);
            out.endTag(/*namespace= */ null, PACKAGE_XML_TAG);
        }

        out.endTag(/* namespace= */ null, LOCALES_XML_TAG);
        out.endDocument();
    }

    static class StagedData {
        final long mCreationTimeMillis;
        final HashMap<String, LocalesInfo> mPackageStates;

        StagedData(long creationTimeMillis, HashMap<String, LocalesInfo> pkgStates) {
            mCreationTimeMillis = creationTimeMillis;
            mPackageStates = pkgStates;
        }
    }

    static class LocalesInfo {
        final String mLocales;
        final boolean mSetFromDelegate;

        LocalesInfo(String locales, boolean setFromDelegate) {
            mLocales = locales;
            mSetFromDelegate = setFromDelegate;
        }
    }

    /**
     * Broadcast listener to capture user removed event.
     *
     * <p>The stage data is deleted when a user is removed.
     */
    private final class UserMonitor extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent.getAction();
                if (action.equals(Intent.ACTION_USER_REMOVED)) {
                    final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, USER_NULL);
                    synchronized (mStagedDataLock) {
                        deleteStagedDataLocked(userId);
                        removeProfileFromPersistedInfo(userId);
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG, "Exception in user monitor.", e);
            }
        }
    }

    /**
     * Performs lazy restore from the staged data.
     *
     * <p>This is invoked by the package monitor on the package added callback.
     */
    private void doLazyRestoreLocked(String packageName, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "doLazyRestore package=" + packageName + " user=" + userId);
        }

        // Check if the package is installed indeed
        if (!isPackageInstalledForUser(packageName, userId)) {
            Slog.e(TAG, packageName + " not installed for user " + userId
                    + ". Could not restore locales from stage data");
            return;
        }

        StagedData stagedData = mStagedData.get(userId);
        for (String pkgName : stagedData.mPackageStates.keySet()) {
            LocalesInfo localesInfo = stagedData.mPackageStates.get(pkgName);

            if (pkgName.equals(packageName)) {

                checkExistingLocalesAndApplyRestore(pkgName, localesInfo, userId);

                // Remove the restored entry from the staged data list.
                stagedData.mPackageStates.remove(pkgName);

                // Remove the stage data entry for user if there are no more packages to restore.
                if (stagedData.mPackageStates.isEmpty()) {
                    mStagedData.remove(userId);
                }

                // No need to loop further after restoring locales because the staged data will
                // contain at most one entry for the newly added package.
                break;
            }
        }
    }

    SharedPreferences createPersistedInfo() {
        final File prefsFile = new File(
                Environment.getDataSystemDeDirectory(UserHandle.USER_SYSTEM),
                LOCALES_FROM_DELEGATE_PREFS);
        return mContext.createDeviceProtectedStorageContext().getSharedPreferences(prefsFile,
                Context.MODE_PRIVATE);
    }

    public SharedPreferences getPersistedInfo() {
        return mDelegateAppLocalePackages;
    }

    private void removePackageFromPersistedInfo(String packageName, @UserIdInt int userId) {
        if (mDelegateAppLocalePackages == null) {
            Slog.w(TAG, "Failed to persist data into the shared preference!");
            return;
        }

        String key = Integer.toString(userId);
        Set<String> packageNames = new ArraySet<>(
                mDelegateAppLocalePackages.getStringSet(key, new ArraySet<>()));
        if (packageNames.contains(packageName)) {
            if (DEBUG) {
                Slog.d(TAG, "remove " + packageName + " from persisted info");
            }
            packageNames.remove(packageName);
            SharedPreferences.Editor editor = mDelegateAppLocalePackages.edit();
            editor.putStringSet(key, packageNames);

            // commit and log the result.
            if (!editor.commit()) {
                Slog.e(TAG, "Failed to commit data!");
            }
        }
    }

    private void removeProfileFromPersistedInfo(@UserIdInt int userId) {
        String key = Integer.toString(userId);

        if (mDelegateAppLocalePackages == null || !mDelegateAppLocalePackages.contains(key)) {
            Slog.w(TAG, "The profile is not existed in the persisted info");
            return;
        }

        if (!mDelegateAppLocalePackages.edit().remove(key).commit()) {
            Slog.e(TAG, "Failed to commit data!");
        }
    }

    /**
     * Persists the package name of per-app locales set from a delegate selector.
     *
     * <p>This information is used when the user has set per-app locales for a specific application
     * from the delegate selector, and then the LocaleConfig of that application is removed in the
     * upgraded version, the per-app locales needs to be reset to system default locales to avoid
     * the user being unable to change system locales setting.
     */
    void persistLocalesModificationInfo(@UserIdInt int userId, String packageName,
            boolean fromDelegate, boolean emptyLocales) {
        if (mDelegateAppLocalePackages == null) {
            Slog.w(TAG, "Failed to persist data into the shared preference!");
            return;
        }

        SharedPreferences.Editor editor = mDelegateAppLocalePackages.edit();
        String user = Integer.toString(userId);
        Set<String> packageNames = new ArraySet<>(
                mDelegateAppLocalePackages.getStringSet(user, new ArraySet<>()));
        if (fromDelegate && !emptyLocales) {
            if (!packageNames.contains(packageName)) {
                if (DEBUG) {
                    Slog.d(TAG, "persist package: " + packageName);
                }
                packageNames.add(packageName);
                editor.putStringSet(user, packageNames);
            }
        } else {
            // Remove the package name if per-app locales was not set from the delegate selector
            // or they were set to empty.
            if (packageNames.contains(packageName)) {
                if (DEBUG) {
                    Slog.d(TAG, "remove package: " + packageName);
                }
                packageNames.remove(packageName);
                editor.putStringSet(user, packageNames);
            }
        }

        // commit and log the result.
        if (!editor.commit()) {
            Slog.e(TAG, "failed to commit locale setter info");
        }
    }

    boolean areLocalesSetFromDelegate(@UserIdInt int userId, String packageName) {
        if (mDelegateAppLocalePackages == null) {
            Slog.w(TAG, "Failed to persist data into the shared preference!");
            return false;
        }

        String user = Integer.toString(userId);
        Set<String> packageNames = new ArraySet<>(
                mDelegateAppLocalePackages.getStringSet(user, new ArraySet<>()));

        return packageNames.contains(packageName);
    }

    /**
     * When the user has set per-app locales for a specific application from a delegate selector,
     * and then the LocaleConfig of that application is removed in the upgraded version, the per-app
     * locales need to be removed or reset to system default locales to avoid the user being unable
     * to change system locales setting.
     */
    private void cleanApplicationLocalesIfNeeded(String packageName, int userId) {
        if (mDelegateAppLocalePackages == null) {
            Slog.w(TAG, "Failed to persist data into the shared preference!");
            return;
        }

        String user = Integer.toString(userId);
        Set<String> packageNames = new ArraySet<>(
                mDelegateAppLocalePackages.getStringSet(user, new ArraySet<>()));
        try {
            LocaleList appLocales = mLocaleManagerService.getApplicationLocales(packageName,
                    userId);
            if (appLocales.isEmpty() || !packageNames.contains(packageName)) {
                return;
            }
        } catch (RemoteException | IllegalArgumentException e) {
            Slog.e(TAG, "Exception when getting locales for " + packageName, e);
            return;
        }

        try {
            LocaleConfig localeConfig = new LocaleConfig(
                    mContext.createPackageContextAsUser(packageName, 0, UserHandle.of(userId)));
            mLocaleManagerService.removeUnsupportedAppLocales(packageName, userId, localeConfig,
                    FrameworkStatsLog
                            .APPLICATION_LOCALES_CHANGED__CALLER__CALLER_APP_UPDATE_LOCALES_CHANGE);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Can not found the package name : " + packageName + " / " + e);
        }
    }
}
