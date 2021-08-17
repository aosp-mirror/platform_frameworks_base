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

package com.android.server.wm;

import static android.app.UiModeManager.MODE_NIGHT_AUTO;
import static android.app.UiModeManager.MODE_NIGHT_CUSTOM;

import android.annotation.NonNull;
import android.os.Environment;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

/**
 * Persist configuration for each package, only persist the change if some on attributes are
 * different from the global configuration. This class only applies to packages with Activities.
 */
public class PackageConfigPersister {
    private static final String TAG = PackageConfigPersister.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final String TAG_CONFIG = "config";
    private static final String ATTR_PACKAGE_NAME = "package_name";
    private static final String ATTR_NIGHT_MODE = "night_mode";

    private static final String PACKAGE_DIRNAME = "package_configs";
    private static final String SUFFIX_FILE_NAME = "_config.xml";

    private final PersisterQueue mPersisterQueue;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final SparseArray<HashMap<String, PackageConfigRecord>> mPendingWrite =
            new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<HashMap<String, PackageConfigRecord>> mModified =
            new SparseArray<>();

    private static File getUserConfigsDir(int userId) {
        return new File(Environment.getDataSystemCeDirectory(userId), PACKAGE_DIRNAME);
    }

    PackageConfigPersister(PersisterQueue queue) {
        mPersisterQueue = queue;
    }

    @GuardedBy("mLock")
    void loadUserPackages(int userId) {
        synchronized (mLock) {
            final File userConfigsDir = getUserConfigsDir(userId);
            final File[] configFiles = userConfigsDir.listFiles();
            if (configFiles == null) {
                Slog.v(TAG, "loadPackages: empty list files from " + userConfigsDir);
                return;
            }

            for (int fileIndex = 0; fileIndex < configFiles.length; ++fileIndex) {
                final File configFile = configFiles[fileIndex];
                if (DEBUG) {
                    Slog.d(TAG, "loadPackages: userId=" + userId
                            + ", configFile=" + configFile.getName());
                }
                if (!configFile.getName().endsWith(SUFFIX_FILE_NAME)) {
                    continue;
                }

                try (InputStream is = new FileInputStream(configFile)) {
                    final TypedXmlPullParser in = Xml.resolvePullParser(is);
                    int event;
                    String packageName = null;
                    int nightMode = MODE_NIGHT_AUTO;
                    while (((event = in.next()) != XmlPullParser.END_DOCUMENT)
                            && event != XmlPullParser.END_TAG) {
                        final String name = in.getName();
                        if (event == XmlPullParser.START_TAG) {
                            if (DEBUG) {
                                Slog.d(TAG, "loadPackages: START_TAG name=" + name);
                            }
                            if (TAG_CONFIG.equals(name)) {
                                for (int attIdx = in.getAttributeCount() - 1; attIdx >= 0;
                                        --attIdx) {
                                    final String attrName = in.getAttributeName(attIdx);
                                    final String attrValue = in.getAttributeValue(attIdx);
                                    switch (attrName) {
                                        case ATTR_PACKAGE_NAME:
                                            packageName = attrValue;
                                            break;
                                        case ATTR_NIGHT_MODE:
                                            nightMode = Integer.parseInt(attrValue);
                                            break;
                                    }
                                }
                            }
                        }
                        XmlUtils.skipCurrentTag(in);
                    }
                    if (packageName != null) {
                        final PackageConfigRecord initRecord =
                                findRecordOrCreate(mModified, packageName, userId);
                        initRecord.mNightMode = nightMode;
                        if (DEBUG) {
                            Slog.d(TAG, "loadPackages: load one package " + initRecord);
                        }
                    }
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (XmlPullParserException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @GuardedBy("mLock")
    void updateConfigIfNeeded(@NonNull ConfigurationContainer container, int userId,
            String packageName) {
        synchronized (mLock) {
            final PackageConfigRecord modifiedRecord = findRecord(mModified, packageName, userId);
            if (DEBUG) {
                Slog.d(TAG,
                        "updateConfigIfNeeded record " + container + " find? " + modifiedRecord);
            }
            if (modifiedRecord != null) {
                container.setOverrideNightMode(modifiedRecord.mNightMode);
            }
        }
    }

    @GuardedBy("mLock")
    void updateFromImpl(String packageName, int userId,
            ActivityTaskManagerService.PackageConfigurationUpdaterImpl impl) {
        synchronized (mLock) {
            PackageConfigRecord record = findRecordOrCreate(mModified, packageName, userId);
            record.mNightMode = impl.getNightMode();

            if (record.isResetNightMode()) {
                removePackage(record.mName, record.mUserId);
            } else {
                final PackageConfigRecord pendingRecord =
                        findRecord(mPendingWrite, record.mName, record.mUserId);
                final PackageConfigRecord writeRecord;
                if (pendingRecord == null) {
                    writeRecord = findRecordOrCreate(mPendingWrite, record.mName,
                            record.mUserId);
                } else {
                    writeRecord = pendingRecord;
                }
                if (writeRecord.mNightMode == record.mNightMode) {
                    return;
                }
                writeRecord.mNightMode = record.mNightMode;
                if (DEBUG) {
                    Slog.d(TAG, "PackageConfigUpdater save config " + writeRecord);
                }
                mPersisterQueue.addItem(new WriteProcessItem(writeRecord), false /* flush */);
            }
        }
    }

    @GuardedBy("mLock")
    void removeUser(int userId) {
        synchronized (mLock) {
            final HashMap<String, PackageConfigRecord> modifyRecords = mModified.get(userId);
            final HashMap<String, PackageConfigRecord> writeRecords = mPendingWrite.get(userId);
            if ((modifyRecords == null || modifyRecords.size() == 0)
                    && (writeRecords == null || writeRecords.size() == 0)) {
                return;
            }
            final HashMap<String, PackageConfigRecord> tempList = new HashMap<>(modifyRecords);
            tempList.forEach((name, record) -> {
                removePackage(record.mName, record.mUserId);
            });
        }
    }

    @GuardedBy("mLock")
    void onPackageUninstall(String packageName) {
        synchronized (mLock) {
            for (int i = mModified.size() - 1; i > 0; i--) {
                final int userId = mModified.keyAt(i);
                removePackage(packageName, userId);
            }
        }
    }

    private void removePackage(String packageName, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "removePackage packageName :" + packageName + " userId " + userId);
        }
        final PackageConfigRecord record = findRecord(mPendingWrite, packageName, userId);
        if (record != null) {
            removeRecord(mPendingWrite, record);
            mPersisterQueue.removeItems(item ->
                            item.mRecord.mName == record.mName
                                    && item.mRecord.mUserId == record.mUserId,
                    WriteProcessItem.class);
        }

        final PackageConfigRecord modifyRecord = findRecord(mModified, packageName, userId);
        if (modifyRecord != null) {
            removeRecord(mModified, modifyRecord);
            mPersisterQueue.addItem(new DeletePackageItem(userId, packageName),
                    false /* flush */);
        }
    }

    // store a changed data so we don't need to get the process
    static class PackageConfigRecord {
        final String mName;
        final int mUserId;
        int mNightMode;

        PackageConfigRecord(String name, int userId) {
            mName = name;
            mUserId = userId;
        }

        boolean isResetNightMode() {
            return mNightMode == MODE_NIGHT_AUTO || mNightMode == MODE_NIGHT_CUSTOM;
        }

        @Override
        public String toString() {
            return "PackageConfigRecord package name: " + mName + " userId " + mUserId
                    + " nightMode " + mNightMode;
        }
    }

    private PackageConfigRecord findRecordOrCreate(
            SparseArray<HashMap<String, PackageConfigRecord>> list, String name, int userId) {
        HashMap<String, PackageConfigRecord> records = list.get(userId);
        if (records == null) {
            records = new HashMap<>();
            list.put(userId, records);
        }
        PackageConfigRecord record = records.get(name);
        if (record != null) {
            return record;
        }
        record = new PackageConfigRecord(name, userId);
        records.put(name, record);
        return record;
    }

    private PackageConfigRecord findRecord(SparseArray<HashMap<String, PackageConfigRecord>> list,
            String name, int userId) {
        HashMap<String, PackageConfigRecord> packages = list.get(userId);
        if (packages == null) {
            return null;
        }
        return packages.get(name);
    }

    private void removeRecord(SparseArray<HashMap<String, PackageConfigRecord>> list,
            PackageConfigRecord record) {
        final HashMap<String, PackageConfigRecord> processes = list.get(record.mUserId);
        if (processes != null) {
            processes.remove(record.mName);
        }
    }

    private static class DeletePackageItem implements PersisterQueue.WriteQueueItem {
        final int mUserId;
        final String mPackageName;

        DeletePackageItem(int userId, String packageName) {
            mUserId = userId;
            mPackageName = packageName;
        }

        @Override
        public void process() {
            File userConfigsDir = getUserConfigsDir(mUserId);
            if (!userConfigsDir.isDirectory()) {
                return;
            }
            final AtomicFile atomicFile = new AtomicFile(new File(userConfigsDir,
                    mPackageName + SUFFIX_FILE_NAME));
            if (atomicFile.exists()) {
                atomicFile.delete();
            }
        }
    }

    private class WriteProcessItem implements PersisterQueue.WriteQueueItem {
        final PackageConfigRecord mRecord;

        WriteProcessItem(PackageConfigRecord record) {
            mRecord = record;
        }

        @Override
        public void process() {
            // Write out one user.
            byte[] data = null;
            synchronized (mLock) {
                try {
                    data = saveToXml();
                } catch (Exception e) {
                }
                removeRecord(mPendingWrite, mRecord);
            }
            if (data != null) {
                // Write out xml file while not holding mService lock.
                FileOutputStream file = null;
                AtomicFile atomicFile = null;
                try {
                    File userConfigsDir = getUserConfigsDir(mRecord.mUserId);
                    if (!userConfigsDir.isDirectory() && !userConfigsDir.mkdirs()) {
                        Slog.e(TAG, "Failure creating tasks directory for user " + mRecord.mUserId
                                + ": " + userConfigsDir);
                        return;
                    }
                    atomicFile = new AtomicFile(new File(userConfigsDir,
                            mRecord.mName + SUFFIX_FILE_NAME));
                    file = atomicFile.startWrite();
                    file.write(data);
                    atomicFile.finishWrite(file);
                } catch (IOException e) {
                    if (file != null) {
                        atomicFile.failWrite(file);
                    }
                    Slog.e(TAG, "Unable to open " + atomicFile + " for persisting. " + e);
                }
            }
        }

        private byte[] saveToXml() throws IOException {
            final ByteArrayOutputStream os = new ByteArrayOutputStream();
            final TypedXmlSerializer xmlSerializer = Xml.resolveSerializer(os);

            xmlSerializer.startDocument(null, true);
            if (DEBUG) {
                Slog.d(TAG, "Writing package configuration=" + mRecord);
            }
            xmlSerializer.startTag(null, TAG_CONFIG);
            xmlSerializer.attribute(null, ATTR_PACKAGE_NAME, mRecord.mName);
            xmlSerializer.attributeInt(null, ATTR_NIGHT_MODE, mRecord.mNightMode);
            xmlSerializer.endTag(null, TAG_CONFIG);
            xmlSerializer.endDocument();
            xmlSerializer.flush();

            return os.toByteArray();
        }
    }
}
