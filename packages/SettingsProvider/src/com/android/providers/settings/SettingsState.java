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

package com.android.providers.settings;

import static android.os.Process.FIRST_APPLICATION_UID;
import static android.os.Process.INVALID_UID;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.providers.settings.SettingsOperationProto;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.StatsLog;
import android.util.TimeUtils;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * This class contains the state for one type of settings. It is responsible
 * for saving the state asynchronously to an XML file after a mutation and
 * loading the from an XML file on construction.
 * <p>
 * This class uses the same lock as the settings provider to ensure that
 * multiple changes made by the settings provider, e,g, upgrade, bulk insert,
 * etc, are atomically persisted since the asynchronous persistence is using
 * the same lock to grab the current state to write to disk.
 * </p>
 */
final class SettingsState {
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_PERSISTENCE = false;

    private static final String LOG_TAG = "SettingsState";

    static final String SYSTEM_PACKAGE_NAME = "android";

    static final int SETTINGS_VERSION_NEW_ENCODING = 121;

    private static final long WRITE_SETTINGS_DELAY_MILLIS = 200;
    private static final long MAX_WRITE_SETTINGS_DELAY_MILLIS = 2000;

    public static final int MAX_BYTES_PER_APP_PACKAGE_UNLIMITED = -1;
    public static final int MAX_BYTES_PER_APP_PACKAGE_LIMITED = 20000;

    public static final int VERSION_UNDEFINED = -1;

    private static final String TAG_SETTINGS = "settings";
    private static final String TAG_SETTING = "setting";
    private static final String ATTR_PACKAGE = "package";
    private static final String ATTR_DEFAULT_SYS_SET = "defaultSysSet";
    private static final String ATTR_TAG = "tag";
    private static final String ATTR_TAG_BASE64 = "tagBase64";

    private static final String ATTR_VERSION = "version";
    private static final String ATTR_ID = "id";
    private static final String ATTR_NAME = "name";

    /**
     * Non-binary value will be written in this attributes.
     */
    private static final String ATTR_VALUE = "value";
    private static final String ATTR_DEFAULT_VALUE = "defaultValue";

    /**
     * KXmlSerializer won't like some characters. We encode such characters
     * in base64 and store in this attribute.
     * NOTE: A null value will have *neither* ATTR_VALUE nor ATTR_VALUE_BASE64.
     */
    private static final String ATTR_VALUE_BASE64 = "valueBase64";
    private static final String ATTR_DEFAULT_VALUE_BASE64 = "defaultValueBase64";

    // This was used in version 120 and before.
    private static final String NULL_VALUE_OLD_STYLE = "null";

    private static final int HISTORICAL_OPERATION_COUNT = 20;
    private static final String HISTORICAL_OPERATION_UPDATE = "update";
    private static final String HISTORICAL_OPERATION_DELETE = "delete";
    private static final String HISTORICAL_OPERATION_PERSIST = "persist";
    private static final String HISTORICAL_OPERATION_INITIALIZE = "initialize";
    private static final String HISTORICAL_OPERATION_RESET = "reset";

    private static final String SHELL_PACKAGE_NAME = "com.android.shell";
    private static final String ROOT_PACKAGE_NAME = "root";

    private static final String NULL_VALUE = "null";

    private static final Object sLock = new Object();

    @GuardedBy("sLock")
    private static final SparseIntArray sSystemUids = new SparseIntArray();

    @GuardedBy("sLock")
    private static Signature sSystemSignature;

    private final Object mWriteLock = new Object();

    private final Object mLock;

    private final Handler mHandler;

    @GuardedBy("mLock")
    private final Context mContext;

    @GuardedBy("mLock")
    private final ArrayMap<String, Setting> mSettings = new ArrayMap<>();

    @GuardedBy("mLock")
    private final ArrayMap<String, Integer> mPackageToMemoryUsage;

    @GuardedBy("mLock")
    private final int mMaxBytesPerAppPackage;

    @GuardedBy("mLock")
    private final File mStatePersistFile;

    @GuardedBy("mLock")
    private final String mStatePersistTag;

    private final Setting mNullSetting = new Setting(null, null, false, null, null) {
        @Override
        public boolean isNull() {
            return true;
        }
    };

    @GuardedBy("mLock")
    private final List<HistoricalOperation> mHistoricalOperations;

    @GuardedBy("mLock")
    public final int mKey;

    @GuardedBy("mLock")
    private int mVersion = VERSION_UNDEFINED;

    @GuardedBy("mLock")
    private long mLastNotWrittenMutationTimeMillis;

    @GuardedBy("mLock")
    private boolean mDirty;

    @GuardedBy("mLock")
    private boolean mWriteScheduled;

    @GuardedBy("mLock")
    private long mNextId;

    @GuardedBy("mLock")
    private int mNextHistoricalOpIdx;

    public static final int SETTINGS_TYPE_GLOBAL = 0;
    public static final int SETTINGS_TYPE_SYSTEM = 1;
    public static final int SETTINGS_TYPE_SECURE = 2;
    public static final int SETTINGS_TYPE_SSAID = 3;
    public static final int SETTINGS_TYPE_CONFIG = 4;

    public static final int SETTINGS_TYPE_MASK = 0xF0000000;
    public static final int SETTINGS_TYPE_SHIFT = 28;

    public static int makeKey(int type, int userId) {
        return (type << SETTINGS_TYPE_SHIFT) | userId;
    }

    public static int getTypeFromKey(int key) {
        return key >>> SETTINGS_TYPE_SHIFT;
    }

    public static int getUserIdFromKey(int key) {
        return key & ~SETTINGS_TYPE_MASK;
    }

    public static String settingTypeToString(int type) {
        switch (type) {
            case SETTINGS_TYPE_CONFIG: {
                return "SETTINGS_CONFIG";
            }
            case SETTINGS_TYPE_GLOBAL: {
                return "SETTINGS_GLOBAL";
            }
            case SETTINGS_TYPE_SECURE: {
                return "SETTINGS_SECURE";
            }
            case SETTINGS_TYPE_SYSTEM: {
                return "SETTINGS_SYSTEM";
            }
            case SETTINGS_TYPE_SSAID: {
                return "SETTINGS_SSAID";
            }
            default: {
                return "UNKNOWN";
            }
        }
    }

    public static String keyToString(int key) {
        return "Key[user=" + getUserIdFromKey(key) + ";type="
                + settingTypeToString(getTypeFromKey(key)) + "]";
    }

    public SettingsState(Context context, Object lock, File file, int key,
            int maxBytesPerAppPackage, Looper looper) {
        // It is important that we use the same lock as the settings provider
        // to ensure multiple mutations on this state are atomicaly persisted
        // as the async persistence should be blocked while we make changes.
        mContext = context;
        mLock = lock;
        mStatePersistFile = file;
        mStatePersistTag = "settings-" + getTypeFromKey(key) + "-" + getUserIdFromKey(key);
        mKey = key;
        mHandler = new MyHandler(looper);
        if (maxBytesPerAppPackage == MAX_BYTES_PER_APP_PACKAGE_LIMITED) {
            mMaxBytesPerAppPackage = maxBytesPerAppPackage;
            mPackageToMemoryUsage = new ArrayMap<>();
        } else {
            mMaxBytesPerAppPackage = maxBytesPerAppPackage;
            mPackageToMemoryUsage = null;
        }

        mHistoricalOperations = Build.IS_DEBUGGABLE
                ? new ArrayList<>(HISTORICAL_OPERATION_COUNT) : null;

        synchronized (mLock) {
            readStateSyncLocked();
        }
    }

    // The settings provider must hold its lock when calling here.
    @GuardedBy("mLock")
    public int getVersionLocked() {
        return mVersion;
    }

    public Setting getNullSetting() {
        return mNullSetting;
    }

    // The settings provider must hold its lock when calling here.
    @GuardedBy("mLock")
    public void setVersionLocked(int version) {
        if (version == mVersion) {
            return;
        }
        mVersion = version;

        scheduleWriteIfNeededLocked();
    }

    // The settings provider must hold its lock when calling here.
    @GuardedBy("mLock")
    public void removeSettingsForPackageLocked(String packageName) {
        boolean removedSomething = false;

        final int settingCount = mSettings.size();
        for (int i = settingCount - 1; i >= 0; i--) {
            String name = mSettings.keyAt(i);
            // Settings defined by us are never dropped.
            if (Settings.System.PUBLIC_SETTINGS.contains(name)
                    || Settings.System.PRIVATE_SETTINGS.contains(name)) {
                continue;
            }
            Setting setting = mSettings.valueAt(i);
            if (packageName.equals(setting.packageName)) {
                mSettings.removeAt(i);
                removedSomething = true;
            }
        }

        if (removedSomething) {
            scheduleWriteIfNeededLocked();
        }
    }

    // The settings provider must hold its lock when calling here.
    @GuardedBy("mLock")
    public List<String> getSettingNamesLocked() {
        ArrayList<String> names = new ArrayList<>();
        final int settingsCount = mSettings.size();
        for (int i = 0; i < settingsCount; i++) {
            String name = mSettings.keyAt(i);
            names.add(name);
        }
        return names;
    }

    // The settings provider must hold its lock when calling here.
    @GuardedBy("mLock")
    public Setting getSettingLocked(String name) {
        if (TextUtils.isEmpty(name)) {
            return mNullSetting;
        }
        Setting setting = mSettings.get(name);
        if (setting != null) {
            return new Setting(setting);
        }
        return mNullSetting;
    }

    // The settings provider must hold its lock when calling here.
    public boolean updateSettingLocked(String name, String value, String tag,
            boolean makeValue, String packageName) {
        if (!hasSettingLocked(name)) {
            return false;
        }

        return insertSettingLocked(name, value, tag, makeValue, packageName);
    }

    // The settings provider must hold its lock when calling here.
    @GuardedBy("mLock")
    public void resetSettingDefaultValueLocked(String name) {
        Setting oldSetting = getSettingLocked(name);
        if (oldSetting != null && !oldSetting.isNull() && oldSetting.getDefaultValue() != null) {
            String oldValue = oldSetting.getValue();
            String oldDefaultValue = oldSetting.getDefaultValue();
            Setting newSetting = new Setting(name, oldSetting.getValue(), null,
                    oldSetting.getPackageName(), oldSetting.getTag(), false,
                    oldSetting.getId());
            mSettings.put(name, newSetting);
            updateMemoryUsagePerPackageLocked(newSetting.getPackageName(), oldValue,
                    newSetting.getValue(), oldDefaultValue, newSetting.getDefaultValue());
            scheduleWriteIfNeededLocked();
        }
    }

    // The settings provider must hold its lock when calling here.
    @GuardedBy("mLock")
    public boolean insertSettingLocked(String name, String value, String tag,
            boolean makeDefault, String packageName) {
        return insertSettingLocked(name, value, tag, makeDefault, false, packageName);
    }

    // The settings provider must hold its lock when calling here.
    @GuardedBy("mLock")
    public boolean insertSettingLocked(String name, String value, String tag,
            boolean makeDefault, boolean forceNonSystemPackage, String packageName) {
        if (TextUtils.isEmpty(name)) {
            return false;
        }

        Setting oldState = mSettings.get(name);
        String oldValue = (oldState != null) ? oldState.value : null;
        String oldDefaultValue = (oldState != null) ? oldState.defaultValue : null;
        Setting newState;

        if (oldState != null) {
            if (!oldState.update(value, makeDefault, packageName, tag, forceNonSystemPackage)) {
                return false;
            }
            newState = oldState;
        } else {
            newState = new Setting(name, value, makeDefault, packageName, tag);
            mSettings.put(name, newState);
        }

        StatsLog.write(StatsLog.SETTING_CHANGED, name, value, newState.value, oldValue, tag,
            makeDefault, getUserIdFromKey(mKey), StatsLog.SETTING_CHANGED__REASON__UPDATED);

        addHistoricalOperationLocked(HISTORICAL_OPERATION_UPDATE, newState);

        updateMemoryUsagePerPackageLocked(packageName, oldValue, value,
                oldDefaultValue, newState.getDefaultValue());

        scheduleWriteIfNeededLocked();

        return true;
    }

    // The settings provider must hold its lock when calling here.
    public void persistSyncLocked() {
        mHandler.removeMessages(MyHandler.MSG_PERSIST_SETTINGS);
        doWriteState();
    }

    // The settings provider must hold its lock when calling here.
    @GuardedBy("mLock")
    public boolean deleteSettingLocked(String name) {
        if (TextUtils.isEmpty(name) || !hasSettingLocked(name)) {
            return false;
        }

        Setting oldState = mSettings.remove(name);

        StatsLog.write(StatsLog.SETTING_CHANGED, name, /* value= */ "", /* newValue= */ "",
            oldState.value, /* tag */ "", false, getUserIdFromKey(mKey),
            StatsLog.SETTING_CHANGED__REASON__DELETED);

        updateMemoryUsagePerPackageLocked(oldState.packageName, oldState.value,
                null, oldState.defaultValue, null);

        addHistoricalOperationLocked(HISTORICAL_OPERATION_DELETE, oldState);

        scheduleWriteIfNeededLocked();

        return true;
    }

    // The settings provider must hold its lock when calling here.
    @GuardedBy("mLock")
    public boolean resetSettingLocked(String name) {
        if (TextUtils.isEmpty(name) || !hasSettingLocked(name)) {
            return false;
        }

        Setting setting = mSettings.get(name);

        Setting oldSetting = new Setting(setting);
        String oldValue = setting.getValue();
        String oldDefaultValue = setting.getDefaultValue();

        if (!setting.reset()) {
            return false;
        }

        String newValue = setting.getValue();
        String newDefaultValue = setting.getDefaultValue();

        updateMemoryUsagePerPackageLocked(setting.packageName, oldValue,
                newValue, oldDefaultValue, newDefaultValue);

        addHistoricalOperationLocked(HISTORICAL_OPERATION_RESET, oldSetting);

        scheduleWriteIfNeededLocked();

        return true;
    }

    // The settings provider must hold its lock when calling here.
    @GuardedBy("mLock")
    public void destroyLocked(Runnable callback) {
        mHandler.removeMessages(MyHandler.MSG_PERSIST_SETTINGS);
        if (callback != null) {
            if (mDirty) {
                // Do it without a delay.
                mHandler.obtainMessage(MyHandler.MSG_PERSIST_SETTINGS,
                        callback).sendToTarget();
                return;
            }
            callback.run();
        }
    }

    @GuardedBy("mLock")
    private void addHistoricalOperationLocked(String type, Setting setting) {
        if (mHistoricalOperations == null) {
            return;
        }
        HistoricalOperation operation = new HistoricalOperation(
                SystemClock.elapsedRealtime(), type,
                setting != null ? new Setting(setting) : null);
        if (mNextHistoricalOpIdx >= mHistoricalOperations.size()) {
            mHistoricalOperations.add(operation);
        } else {
            mHistoricalOperations.set(mNextHistoricalOpIdx, operation);
        }
        mNextHistoricalOpIdx++;
        if (mNextHistoricalOpIdx >= HISTORICAL_OPERATION_COUNT) {
            mNextHistoricalOpIdx = 0;
        }
    }

    /**
     * Dump historical operations as a proto buf.
     *
     * @param proto The proto buf stream to dump to
     * @param fieldId The repeated field ID to use to save an operation to.
     */
    void dumpHistoricalOperations(@NonNull ProtoOutputStream proto, long fieldId) {
        synchronized (mLock) {
            if (mHistoricalOperations == null) {
                return;
            }

            final int operationCount = mHistoricalOperations.size();
            for (int i = 0; i < operationCount; i++) {
                int index = mNextHistoricalOpIdx - 1 - i;
                if (index < 0) {
                    index = operationCount + index;
                }
                HistoricalOperation operation = mHistoricalOperations.get(index);

                final long token = proto.start(fieldId);
                proto.write(SettingsOperationProto.TIMESTAMP, operation.mTimestamp);
                proto.write(SettingsOperationProto.OPERATION, operation.mOperation);
                if (operation.mSetting != null) {
                    // Only add the name of the setting, since we don't know the historical package
                    // and values for it so they would be misleading to add here (all we could
                    // add is what the current data is).
                    proto.write(SettingsOperationProto.SETTING, operation.mSetting.getName());
                }
                proto.end(token);
            }
        }
    }

    public void dumpHistoricalOperations(PrintWriter pw) {
        synchronized (mLock) {
            if (mHistoricalOperations == null) {
                return;
            }
            pw.println("Historical operations");
            final int operationCount = mHistoricalOperations.size();
            for (int i = 0; i < operationCount; i++) {
                int index = mNextHistoricalOpIdx - 1 - i;
                if (index < 0) {
                    index = operationCount + index;
                }
                HistoricalOperation operation = mHistoricalOperations.get(index);
                pw.print(TimeUtils.formatForLogging(operation.mTimestamp));
                pw.print(" ");
                pw.print(operation.mOperation);
                if (operation.mSetting != null) {
                    pw.print(" ");
                    // Only print the name of the setting, since we don't know the
                    // historical package and values for it so they would be misleading
                    // to print here (all we could print is what the current data is).
                    pw.print(operation.mSetting.getName());
                }
                pw.println();
            }
            pw.println();
            pw.println();
        }
    }

    @GuardedBy("mLock")
    private void updateMemoryUsagePerPackageLocked(String packageName, String oldValue,
            String newValue, String oldDefaultValue, String newDefaultValue) {
        if (mMaxBytesPerAppPackage == MAX_BYTES_PER_APP_PACKAGE_UNLIMITED) {
            return;
        }

        if (SYSTEM_PACKAGE_NAME.equals(packageName)) {
            return;
        }

        final int oldValueSize = (oldValue != null) ? oldValue.length() : 0;
        final int newValueSize = (newValue != null) ? newValue.length() : 0;
        final int oldDefaultValueSize = (oldDefaultValue != null) ? oldDefaultValue.length() : 0;
        final int newDefaultValueSize = (newDefaultValue != null) ? newDefaultValue.length() : 0;
        final int deltaSize = newValueSize + newDefaultValueSize
                - oldValueSize - oldDefaultValueSize;

        Integer currentSize = mPackageToMemoryUsage.get(packageName);
        final int newSize = Math.max((currentSize != null)
                ? currentSize + deltaSize : deltaSize, 0);

        if (newSize > mMaxBytesPerAppPackage) {
            throw new IllegalStateException("You are adding too many system settings. "
                    + "You should stop using system settings for app specific data"
                    + " package: " + packageName);
        }

        if (DEBUG) {
            Slog.i(LOG_TAG, "Settings for package: " + packageName
                    + " size: " + newSize + " bytes.");
        }

        mPackageToMemoryUsage.put(packageName, newSize);
    }

    @GuardedBy("mLock")
    private boolean hasSettingLocked(String name) {
        return mSettings.indexOfKey(name) >= 0;
    }

    @GuardedBy("mLock")
    private void scheduleWriteIfNeededLocked() {
        // If dirty then we have a write already scheduled.
        if (!mDirty) {
            mDirty = true;
            writeStateAsyncLocked();
        }
    }

    @GuardedBy("mLock")
    private void writeStateAsyncLocked() {
        final long currentTimeMillis = SystemClock.uptimeMillis();

        if (mWriteScheduled) {
            mHandler.removeMessages(MyHandler.MSG_PERSIST_SETTINGS);

            // If enough time passed, write without holding off anymore.
            final long timeSinceLastNotWrittenMutationMillis = currentTimeMillis
                    - mLastNotWrittenMutationTimeMillis;
            if (timeSinceLastNotWrittenMutationMillis >= MAX_WRITE_SETTINGS_DELAY_MILLIS) {
                mHandler.obtainMessage(MyHandler.MSG_PERSIST_SETTINGS).sendToTarget();
                return;
            }

            // Hold off a bit more as settings are frequently changing.
            final long maxDelayMillis = Math.max(mLastNotWrittenMutationTimeMillis
                    + MAX_WRITE_SETTINGS_DELAY_MILLIS - currentTimeMillis, 0);
            final long writeDelayMillis = Math.min(WRITE_SETTINGS_DELAY_MILLIS, maxDelayMillis);

            Message message = mHandler.obtainMessage(MyHandler.MSG_PERSIST_SETTINGS);
            mHandler.sendMessageDelayed(message, writeDelayMillis);
        } else {
            mLastNotWrittenMutationTimeMillis = currentTimeMillis;
            Message message = mHandler.obtainMessage(MyHandler.MSG_PERSIST_SETTINGS);
            mHandler.sendMessageDelayed(message, WRITE_SETTINGS_DELAY_MILLIS);
            mWriteScheduled = true;
        }
    }

    private void doWriteState() {
        boolean wroteState = false;
        final int version;
        final ArrayMap<String, Setting> settings;

        synchronized (mLock) {
            version = mVersion;
            settings = new ArrayMap<>(mSettings);
            mDirty = false;
            mWriteScheduled = false;
        }

        synchronized (mWriteLock) {
            if (DEBUG_PERSISTENCE) {
                Slog.i(LOG_TAG, "[PERSIST START]");
            }

            AtomicFile destination = new AtomicFile(mStatePersistFile, mStatePersistTag);
            FileOutputStream out = null;
            try {
                out = destination.startWrite();

                XmlSerializer serializer = Xml.newSerializer();
                serializer.setOutput(out, StandardCharsets.UTF_8.name());
                serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output",
                        true);
                serializer.startDocument(null, true);
                serializer.startTag(null, TAG_SETTINGS);
                serializer.attribute(null, ATTR_VERSION, String.valueOf(version));

                final int settingCount = settings.size();
                for (int i = 0; i < settingCount; i++) {
                    Setting setting = settings.valueAt(i);

                    if (setting.isTransient()) {
                        if (DEBUG_PERSISTENCE) {
                            Slog.i(LOG_TAG, "[SKIPPED PERSISTING]" + setting.getName());
                        }
                        continue;
                    }

                    writeSingleSetting(mVersion, serializer, setting.getId(), setting.getName(),
                            setting.getValue(), setting.getDefaultValue(), setting.getPackageName(),
                            setting.getTag(), setting.isDefaultFromSystem());

                    if (DEBUG_PERSISTENCE) {
                        Slog.i(LOG_TAG, "[PERSISTED]" + setting.getName() + "="
                                + setting.getValue());
                    }
                }

                serializer.endTag(null, TAG_SETTINGS);
                serializer.endDocument();
                destination.finishWrite(out);

                wroteState = true;

                if (DEBUG_PERSISTENCE) {
                    Slog.i(LOG_TAG, "[PERSIST END]");
                }
            } catch (Throwable t) {
                Slog.wtf(LOG_TAG, "Failed to write settings, restoring backup", t);
                if (t instanceof IOException) {
                    // we failed to create a directory, so log the permissions and existence
                    // state for the settings file and directory
                    logSettingsDirectoryInformation(destination.getBaseFile());
                    if (t.getMessage().contains("Couldn't create directory")) {
                        // attempt to create the directory with Files.createDirectories, which
                        // throws more informative errors than File.mkdirs.
                        Path parentPath = destination.getBaseFile().getParentFile().toPath();
                        try {
                            Files.createDirectories(parentPath);
                            Slog.i(LOG_TAG, "Successfully created " + parentPath);
                        } catch (Throwable t2) {
                            Slog.e(LOG_TAG, "Failed to write " + parentPath
                                    + " with Files.writeDirectories", t2);
                        }
                    }
                }
                destination.failWrite(out);
            } finally {
                IoUtils.closeQuietly(out);
            }
        }

        if (wroteState) {
            synchronized (mLock) {
                addHistoricalOperationLocked(HISTORICAL_OPERATION_PERSIST, null);
            }
        }
    }

    private static void logSettingsDirectoryInformation(File settingsFile) {
        File parent = settingsFile.getParentFile();
        Slog.i(LOG_TAG, "directory info for directory/file " + settingsFile
                + " with stacktrace ", new Exception());
        File ancestorDir = parent;
        while (ancestorDir != null) {
            if (!ancestorDir.exists()) {
                Slog.i(LOG_TAG, "ancestor directory " + ancestorDir
                        + " does not exist");
                ancestorDir = ancestorDir.getParentFile();
            } else {
                Slog.i(LOG_TAG, "ancestor directory " + ancestorDir
                        + " exists");
                Slog.i(LOG_TAG, "ancestor directory " + ancestorDir
                        + " permissions: r: " + ancestorDir.canRead() + " w: "
                        + ancestorDir.canWrite() + " x: " + ancestorDir.canExecute());
                File ancestorParent = ancestorDir.getParentFile();
                if (ancestorParent != null) {
                    Slog.i(LOG_TAG, "ancestor's parent directory " + ancestorParent
                            + " permissions: r: " + ancestorParent.canRead() + " w: "
                            + ancestorParent.canWrite() + " x: " + ancestorParent.canExecute());
                }
                break;
            }
        }
    }

    static void writeSingleSetting(int version, XmlSerializer serializer, String id,
            String name, String value, String defaultValue, String packageName,
            String tag, boolean defaultSysSet) throws IOException {
        if (id == null || isBinary(id) || name == null || isBinary(name)
                || packageName == null || isBinary(packageName)) {
            // This shouldn't happen.
            return;
        }
        serializer.startTag(null, TAG_SETTING);
        serializer.attribute(null, ATTR_ID, id);
        serializer.attribute(null, ATTR_NAME, name);
        setValueAttribute(ATTR_VALUE, ATTR_VALUE_BASE64,
                version, serializer, value);
        serializer.attribute(null, ATTR_PACKAGE, packageName);
        if (defaultValue != null) {
            setValueAttribute(ATTR_DEFAULT_VALUE, ATTR_DEFAULT_VALUE_BASE64,
                    version, serializer, defaultValue);
            serializer.attribute(null, ATTR_DEFAULT_SYS_SET, Boolean.toString(defaultSysSet));
            setValueAttribute(ATTR_TAG, ATTR_TAG_BASE64,
                    version, serializer, tag);
        }
        serializer.endTag(null, TAG_SETTING);
    }

    static void setValueAttribute(String attr, String attrBase64, int version,
            XmlSerializer serializer, String value) throws IOException {
        if (version >= SETTINGS_VERSION_NEW_ENCODING) {
            if (value == null) {
                // Null value -> No ATTR_VALUE nor ATTR_VALUE_BASE64.
            } else if (isBinary(value)) {
                serializer.attribute(null, attrBase64, base64Encode(value));
            } else {
                serializer.attribute(null, attr, value);
            }
        } else {
            // Old encoding.
            if (value == null) {
                serializer.attribute(null, attr, NULL_VALUE_OLD_STYLE);
            } else {
                serializer.attribute(null, attr, value);
            }
        }
    }

    private String getValueAttribute(XmlPullParser parser, String attr, String base64Attr) {
        if (mVersion >= SETTINGS_VERSION_NEW_ENCODING) {
            final String value = parser.getAttributeValue(null, attr);
            if (value != null) {
                return value;
            }
            final String base64 = parser.getAttributeValue(null, base64Attr);
            if (base64 != null) {
                return base64Decode(base64);
            }
            // null has neither ATTR_VALUE nor ATTR_VALUE_BASE64.
            return null;
        } else {
            // Old encoding.
            final String stored = parser.getAttributeValue(null, attr);
            if (NULL_VALUE_OLD_STYLE.equals(stored)) {
                return null;
            } else {
                return stored;
            }
        }
    }

    @GuardedBy("mLock")
    private void readStateSyncLocked() {
        FileInputStream in;
        try {
            in = new AtomicFile(mStatePersistFile).openRead();
        } catch (FileNotFoundException fnfe) {
            Slog.i(LOG_TAG, "No settings state " + mStatePersistFile);
            logSettingsDirectoryInformation(mStatePersistFile);
            addHistoricalOperationLocked(HISTORICAL_OPERATION_INITIALIZE, null);
            return;
        }
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, StandardCharsets.UTF_8.name());
            parseStateLocked(parser);
        } catch (XmlPullParserException | IOException e) {
            String message = "Failed parsing settings file: " + mStatePersistFile;
            Slog.wtf(LOG_TAG, message);
            throw new IllegalStateException(message, e);
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    /**
     * Uses AtomicFile to check if the file or its backup exists.
     * @param file The file to check for existence
     * @return whether the original or backup exist
     */
    public static boolean stateFileExists(File file) {
        AtomicFile stateFile = new AtomicFile(file);
        return stateFile.exists();
    }

    private void parseStateLocked(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(TAG_SETTINGS)) {
                parseSettingsLocked(parser);
            }
        }
    }

    @GuardedBy("mLock")
    private void parseSettingsLocked(XmlPullParser parser)
            throws IOException, XmlPullParserException {

        mVersion = Integer.parseInt(parser.getAttributeValue(null, ATTR_VERSION));

        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(TAG_SETTING)) {
                String id = parser.getAttributeValue(null, ATTR_ID);
                String name = parser.getAttributeValue(null, ATTR_NAME);
                String value = getValueAttribute(parser, ATTR_VALUE, ATTR_VALUE_BASE64);
                String packageName = parser.getAttributeValue(null, ATTR_PACKAGE);
                String defaultValue = getValueAttribute(parser, ATTR_DEFAULT_VALUE,
                        ATTR_DEFAULT_VALUE_BASE64);
                String tag = null;
                boolean fromSystem = false;
                if (defaultValue != null) {
                    fromSystem = Boolean.parseBoolean(parser.getAttributeValue(
                            null, ATTR_DEFAULT_SYS_SET));
                    tag = getValueAttribute(parser, ATTR_TAG, ATTR_TAG_BASE64);
                }
                mSettings.put(name, new Setting(name, value, defaultValue, packageName, tag,
                        fromSystem, id));

                if (DEBUG_PERSISTENCE) {
                    Slog.i(LOG_TAG, "[RESTORED] " + name + "=" + value);
                }
            }
        }
    }

    private final class MyHandler extends Handler {
        public static final int MSG_PERSIST_SETTINGS = 1;

        public MyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_PERSIST_SETTINGS: {
                    Runnable callback = (Runnable) message.obj;
                    doWriteState();
                    if (callback != null) {
                        callback.run();
                    }
                }
                break;
            }
        }
    }

    private class HistoricalOperation {
        final long mTimestamp;
        final String mOperation;
        final Setting mSetting;

        public HistoricalOperation(long timestamp,
                String operation, Setting setting) {
            mTimestamp = timestamp;
            mOperation = operation;
            mSetting = setting;
        }
    }

    class Setting {
        private String name;
        private String value;
        private String defaultValue;
        private String packageName;
        private String id;
        private String tag;
        // Whether the default is set by the system
        private boolean defaultFromSystem;

        public Setting(Setting other) {
            name = other.name;
            value = other.value;
            defaultValue = other.defaultValue;
            packageName = other.packageName;
            id = other.id;
            defaultFromSystem = other.defaultFromSystem;
            tag = other.tag;
        }

        public Setting(String name, String value, boolean makeDefault, String packageName,
                String tag) {
            this.name = name;
            update(value, makeDefault, packageName, tag, false);
        }

        public Setting(String name, String value, String defaultValue,
                String packageName, String tag, boolean fromSystem, String id) {
            mNextId = Math.max(mNextId, Long.parseLong(id) + 1);
            if (NULL_VALUE.equals(value)) {
                value = null;
            }
            init(name, value, tag, defaultValue, packageName, fromSystem, id);
        }

        private void init(String name, String value, String tag, String defaultValue,
                String packageName, boolean fromSystem, String id) {
            this.name = name;
            this.value = value;
            this.tag = tag;
            this.defaultValue = defaultValue;
            this.packageName = packageName;
            this.id = id;
            this.defaultFromSystem = fromSystem;
        }

        public String getName() {
            return name;
        }

        public int getKey() {
            return mKey;
        }

        public String getValue() {
            return value;
        }

        public String getTag() {
            return tag;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public String getPackageName() {
            return packageName;
        }

        public boolean isDefaultFromSystem() {
            return defaultFromSystem;
        }

        public String getId() {
            return id;
        }

        public boolean isNull() {
            return false;
        }

        /** @return whether the value changed */
        public boolean reset() {
            return update(this.defaultValue, false, packageName, null, true);
        }

        public boolean isTransient() {
            switch (getTypeFromKey(getKey())) {
                case SETTINGS_TYPE_GLOBAL:
                    return ArrayUtils.contains(Global.TRANSIENT_SETTINGS, getName());
            }
            return false;
        }

        public boolean update(String value, boolean setDefault, String packageName, String tag,
                boolean forceNonSystemPackage) {
            if (NULL_VALUE.equals(value)) {
                value = null;
            }

            final boolean callerSystem = !forceNonSystemPackage &&
                    !isNull() && isSystemPackage(mContext, packageName);
            // Settings set by the system are always defaults.
            if (callerSystem) {
                setDefault = true;
            }

            String defaultValue = this.defaultValue;
            boolean defaultFromSystem = this.defaultFromSystem;
            if (setDefault) {
                if (!Objects.equals(value, this.defaultValue)
                        && (!defaultFromSystem || callerSystem)) {
                    defaultValue = value;
                    // Default null means no default, so the tag is irrelevant
                    // since it is used to reset a settings subset their defaults.
                    // Also it is irrelevant if the system set the canonical default.
                    if (defaultValue == null) {
                        tag = null;
                        defaultFromSystem = false;
                    }
                }
                if (!defaultFromSystem && value != null) {
                    if (callerSystem) {
                        defaultFromSystem = true;
                    }
                }
            }

            // Is something gonna change?
            if (Objects.equals(value, this.value)
                    && Objects.equals(defaultValue, this.defaultValue)
                    && Objects.equals(packageName, this.packageName)
                    && Objects.equals(tag, this.tag)
                    && defaultFromSystem == this.defaultFromSystem) {
                return false;
            }

            init(name, value, tag, defaultValue, packageName, defaultFromSystem,
                    String.valueOf(mNextId++));
            return true;
        }

        public String toString() {
            return "Setting{name=" + name + " value=" + value
                    + (defaultValue != null ? " default=" + defaultValue : "")
                    + " packageName=" + packageName + " tag=" + tag
                    + " defaultFromSystem=" + defaultFromSystem + "}";
        }
    }

    /**
     * @return TRUE if a string is considered "binary" from KXML's point of view.  NOTE DO NOT
     * pass null.
     */
    public static boolean isBinary(String s) {
        if (s == null) {
            throw new NullPointerException();
        }
        // See KXmlSerializer.writeEscaped
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean allowedInXml = (c >= 0x20 && c <= 0xd7ff) || (c >= 0xe000 && c <= 0xfffd);
            if (!allowedInXml) {
                return true;
            }
        }
        return false;
    }

    private static String base64Encode(String s) {
        return Base64.encodeToString(toBytes(s), Base64.NO_WRAP);
    }

    private static String base64Decode(String s) {
        return fromBytes(Base64.decode(s, Base64.DEFAULT));
    }

    // Note the followings are basically just UTF-16 encode/decode.  But we want to preserve
    // contents as-is, even if it contains broken surrogate pairs, we do it by ourselves,
    // since I don't know how Charset would treat them.

    private static byte[] toBytes(String s) {
        final byte[] result = new byte[s.length() * 2];
        int resultIndex = 0;
        for (int i = 0; i < s.length(); ++i) {
            char ch = s.charAt(i);
            result[resultIndex++] = (byte) (ch >> 8);
            result[resultIndex++] = (byte) ch;
        }
        return result;
    }

    private static String fromBytes(byte[] bytes) {
        final StringBuffer sb = new StringBuffer(bytes.length / 2);

        final int last = bytes.length - 1;

        for (int i = 0; i < last; i += 2) {
            final char ch = (char) ((bytes[i] & 0xff) << 8 | (bytes[i + 1] & 0xff));
            sb.append(ch);
        }
        return sb.toString();
    }

    // Check if a specific package belonging to the caller is part of the system package.
    public static boolean isSystemPackage(Context context, String packageName) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        return isSystemPackage(context, packageName, callingUid, callingUserId);
    }

    // Check if a specific package, uid, and user ID are part of the system package.
    public static boolean isSystemPackage(Context context, String packageName, int uid,
            int userId) {
        synchronized (sLock) {
            if (SYSTEM_PACKAGE_NAME.equals(packageName)) {
                return true;
            }

            // Shell and Root are not considered a part of the system
            if (SHELL_PACKAGE_NAME.equals(packageName)
                    || ROOT_PACKAGE_NAME.equals(packageName)) {
                return false;
            }

            if (uid != INVALID_UID) {
                // Native services running as a special UID get a pass
                final int callingAppId = UserHandle.getAppId(uid);
                if (callingAppId < FIRST_APPLICATION_UID) {
                    sSystemUids.put(callingAppId, callingAppId);
                    return true;
                }
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                try {
                    uid = context.getPackageManager().getPackageUidAsUser(packageName, 0, userId);
                } catch (PackageManager.NameNotFoundException e) {
                    return false;
                }

                // If the system or a special system UID (like telephony), done.
                if (UserHandle.getAppId(uid) < FIRST_APPLICATION_UID) {
                    sSystemUids.put(uid, uid);
                    return true;
                }

                // If already known system component, done.
                if (sSystemUids.indexOfKey(uid) >= 0) {
                    return true;
                }

                // If SetupWizard, done.
                PackageManagerInternal packageManagerInternal = LocalServices.getService(
                        PackageManagerInternal.class);
                if (packageName.equals(packageManagerInternal.getSetupWizardPackageName())) {
                    sSystemUids.put(uid, uid);
                    return true;
                }

                // If a persistent system app, done.
                PackageInfo packageInfo;
                try {
                    packageInfo = context.getPackageManager().getPackageInfoAsUser(
                            packageName, PackageManager.GET_SIGNATURES, userId);
                    if ((packageInfo.applicationInfo.flags
                            & ApplicationInfo.FLAG_PERSISTENT) != 0
                            && (packageInfo.applicationInfo.flags
                            & ApplicationInfo.FLAG_SYSTEM) != 0) {
                        sSystemUids.put(uid, uid);
                        return true;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    return false;
                }

                // Last check if system signed.
                if (sSystemSignature == null) {
                    try {
                        sSystemSignature = context.getPackageManager().getPackageInfoAsUser(
                                SYSTEM_PACKAGE_NAME, PackageManager.GET_SIGNATURES,
                                UserHandle.USER_SYSTEM).signatures[0];
                    } catch (PackageManager.NameNotFoundException e) {
                        /* impossible */
                        return false;
                    }
                }
                if (sSystemSignature.equals(packageInfo.signatures[0])) {
                    sSystemUids.put(uid, uid);
                    return true;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

            return false;
        }
    }
}
