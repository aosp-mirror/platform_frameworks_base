/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.LocaleList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.XmlUtils;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Track if a system app is being updated for the first time after the user completed device setup.
 *
 * <p> The entire operation is being done on a background thread from {@link LocaleManagerService}.
 * If it is the first time that a system app is being updated, then it fetches the app-specific
 * locales and sends a broadcast to the newly set installer of the app. It maintains a file to store
 * the name of the apps that have been updated.
 */
public class SystemAppUpdateTracker {
    private static final String TAG = "SystemAppUpdateTracker";
    private static final String PACKAGE_XML_TAG = "package";
    private static final String ATTR_NAME = "name";
    private static final String SYSTEM_APPS_XML_TAG = "system_apps";

    private final Context mContext;
    private final LocaleManagerService mLocaleManagerService;
    private final AtomicFile mUpdatedAppsFile;

    // Lock used while writing to the file.
    private final Object mFileLock = new Object();

    // In-memory list of all the system apps that have been updated once after device setup.
    // We do not need to store the userid->packages mapping because when updating a system app on
    // one user updates for all users.
    private final Set<String> mUpdatedApps = new HashSet<>();

    SystemAppUpdateTracker(LocaleManagerService localeManagerService) {
        this(localeManagerService.mContext, localeManagerService, new AtomicFile(
                new File(Environment.getDataSystemDirectory(),
                        /* child = */ "locale_manager_service_updated_system_apps.xml")));
    }

    @VisibleForTesting
    SystemAppUpdateTracker(Context context, LocaleManagerService localeManagerService,
            AtomicFile file) {
        mContext = context;
        mLocaleManagerService = localeManagerService;
        mUpdatedAppsFile = file;
    }

    /**
     * Loads the info of updated system apps from the file.
     *
     * <p> Invoked once during device boot from {@link LocaleManagerService} by a background thread.
     */
    void init() {
        if (DEBUG) {
            Slog.d(TAG, "Loading the app info from storage. ");
        }
        loadUpdatedSystemApps();
    }

    /**
     * Reads the XML stored in the {@link #mUpdatedAppsFile} and populates it in the in-memory list
     * {@link #mUpdatedApps}.
     */
    private void loadUpdatedSystemApps() {
        if (!mUpdatedAppsFile.getBaseFile().exists()) {
            if (DEBUG) {
                Slog.d(TAG, "loadUpdatedSystemApps: File does not exist.");
            }
            return;
        }
        InputStream updatedAppNamesInputStream = null;
        try  {
            updatedAppNamesInputStream = mUpdatedAppsFile.openRead();
            readFromXml(updatedAppNamesInputStream);
        } catch (IOException | XmlPullParserException e) {
            Slog.e(TAG, "loadUpdatedSystemApps: Could not parse storage file ", e);
        } finally {
            IoUtils.closeQuietly(updatedAppNamesInputStream);
        }
    }

    /**
     * Parses the update data from the serialized XML input stream.
     */
    private void readFromXml(InputStream updateInfoInputStream)
            throws XmlPullParserException, IOException {
        final TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(updateInfoInputStream, StandardCharsets.UTF_8.name());
        XmlUtils.beginDocument(parser, SYSTEM_APPS_XML_TAG);
        int depth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, depth)) {
            if (parser.getName().equals(PACKAGE_XML_TAG)) {
                String packageName = parser.getAttributeValue(/* namespace= */ null,
                        ATTR_NAME);
                if (!TextUtils.isEmpty(packageName)) {
                    mUpdatedApps.add(packageName);
                }
            }
        }
    }

    /**
     * Sends a broadcast to the newly set installer with app-locales if it is a system app being
     * updated for the first time.
     *
     * <p><b>Note:</b> Invoked by service's common monitor
     * {@link LocaleManagerServicePackageMonitor#onPackageUpdateFinished} when a package updated.
     */
    void onPackageUpdateFinished(String packageName, int uid) {
        try {
            if ((!mUpdatedApps.contains(packageName)) && isUpdatedSystemApp(packageName)) {
                // If a system app is updated, verify that it has an installer-on-record.
                String installingPackageName = mLocaleManagerService.getInstallingPackageName(
                        packageName);
                if (installingPackageName == null) {
                    // We want to broadcast the locales info to the installer.
                    // If this app does not have an installer then do nothing.
                    return;
                }

                try {
                    int userId = UserHandle.getUserId(uid);
                    // Fetch the app-specific locales.
                    // If non-empty then send the info to the installer.
                    LocaleList appLocales = mLocaleManagerService.getApplicationLocales(
                            packageName, userId);
                    if (!appLocales.isEmpty()) {
                        // The broadcast would be sent to the newly set installer of the
                        // updated system app.
                        mLocaleManagerService.notifyInstallerOfAppWhoseLocaleChanged(packageName,
                                userId, appLocales);
                    }
                } catch (RemoteException e) {
                    if (DEBUG) {
                        Slog.d(TAG, "onPackageUpdateFinished: Error in fetching app locales");
                    }
                }
                updateBroadcastedAppsList(packageName);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Exception in onPackageUpdateFinished.", e);
        }
    }

    /**
     * Writes in-memory data {@link #mUpdatedApps} to the storage file in a synchronized manner.
     */
    private void updateBroadcastedAppsList(String packageName) {
        synchronized (mFileLock) {
            mUpdatedApps.add(packageName);
            writeUpdatedAppsFileLocked();
        }
    }

    private void writeUpdatedAppsFileLocked() {
        FileOutputStream stream = null;
        try {
            stream = mUpdatedAppsFile.startWrite();
            writeToXmlLocked(stream);
            mUpdatedAppsFile.finishWrite(stream);
        } catch (IOException e) {
            mUpdatedAppsFile.failWrite(stream);
            Slog.e(TAG, "Failed to persist the updated apps list", e);
        }
    }

    /**
     * Converts the list of updated app data into a serialized xml stream.
     */
    private void writeToXmlLocked(OutputStream stream) throws IOException {
        final TypedXmlSerializer xml = Xml.newFastSerializer();
        xml.setOutput(stream, StandardCharsets.UTF_8.name());
        xml.startDocument(/* encoding= */ null,  /* standalone= */ true);
        xml.startTag(/* namespace= */ null, SYSTEM_APPS_XML_TAG);

        for (String packageName : mUpdatedApps) {
            xml.startTag(/* namespace= */ null, PACKAGE_XML_TAG);
            xml.attribute(/* namespace= */ null, ATTR_NAME, packageName);
            xml.endTag(/* namespace= */ null, PACKAGE_XML_TAG);
        }

        xml.endTag(null, SYSTEM_APPS_XML_TAG);
        xml.endDocument();
    }

    private boolean isUpdatedSystemApp(String packageName) {
        ApplicationInfo appInfo = null;
        try {
            appInfo = mContext.getPackageManager().getApplicationInfo(packageName,
                PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY));
        } catch (PackageManager.NameNotFoundException e) {
            if (DEBUG) {
                Slog.d(TAG, "isUpdatedSystemApp: Package not found " + packageName);
            }
        }
        if (appInfo == null) {
            return false;
        }
        return (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    @VisibleForTesting
    Set<String> getUpdatedApps() {
        return mUpdatedApps;
    }
}
