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

import static com.android.server.locales.LocaleManagerService.DEBUG;

import android.annotation.NonNull;
import android.app.backup.BackupManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.LocaleList;
import android.os.RemoteException;
import android.util.Slog;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Helper class for managing backup and restore of app-specific locales.
 */
final class LocaleManagerBackupHelper {
    private static final String TAG = "LocaleManagerBkpHelper"; // must be < 23 chars
    private final LocaleManagerService mLocaleManagerService;
    private final PackageManagerInternal mPackageManagerInternal;
    private static final String SYSTEM_BACKUP_PACKAGE_KEY = "android";

    LocaleManagerBackupHelper(LocaleManagerService localeManagerService,
            PackageManagerInternal pmInternal) {
        mLocaleManagerService = localeManagerService;
        mPackageManagerInternal = pmInternal;
    }

    /**
     * @see LocaleManagerInternal#getBackupPayload(int userId)
     */
    public byte[] getBackupPayload(int userId) {
        if (DEBUG) {
            Slog.d(TAG, "getBackupPayload invoked for user id " + userId);
        }

        ArrayList<BackupPackageState> pkgStates = new ArrayList<>();
        for (ApplicationInfo appInfo : mPackageManagerInternal.getInstalledApplications(/*flags*/0,
                userId, Binder.getCallingUid())) {
            try {
                LocaleList appLocales = mLocaleManagerService.getApplicationLocales(
                        appInfo.packageName,
                        userId);
                // Backup locales only for apps which do have app-specific overrides.
                if (!appLocales.isEmpty()) {
                    if (DEBUG) {
                        Slog.d(TAG, "Add package=" + appInfo.packageName + " locales="
                                + appLocales.toLanguageTags() + " to backup payload");
                    }
                    pkgStates.add(new BackupPackageState(appInfo.packageName,
                            appLocales.toLanguageTags()));
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

    /**
     * Notifies the backup manager to include the "android" package in the next backup pass.
     */
    public void notifyBackupManager() {
        BackupManager.dataChanged(SYSTEM_BACKUP_PACKAGE_KEY);
    }


    /**
     * Converts the list of app backup data into a serialized xml stream.
     */
    private static void writeToXml(OutputStream stream,
            @NonNull ArrayList<BackupPackageState> pkgStates) throws IOException {
        TypedXmlSerializer out = Xml.newFastSerializer();
        out.setOutput(stream, StandardCharsets.UTF_8.name());
        out.startDocument(/* encoding= */ null, /* standalone= */ true);
        out.startTag(/* namespace= */ null, TAG);

        for (BackupPackageState pkgState : pkgStates) {
            out.startTag(/* namespace= */ null, "package");
            out.attribute(/* namespace= */ null, "name", pkgState.mPackageName);
            out.attribute(/* namespace= */ null, "locales", pkgState.mLanguageTags);
            out.endTag(/*namespace= */ null, "package");
        }

        out.endTag(/* namespace= */ null, TAG);
        out.endDocument();
    }

    /**
     * Data structure containing the backup data details for an app.
     */
    private static class BackupPackageState {
        final @NonNull String mPackageName;
        final @NonNull String mLanguageTags;

        BackupPackageState(@NonNull String packageNameArg, @NonNull String languageTagsArg) {
            mPackageName = packageNameArg;
            mLanguageTags = languageTagsArg;
        }
    }
}
