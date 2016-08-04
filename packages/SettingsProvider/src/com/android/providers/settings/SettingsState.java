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

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.Xml;
import com.android.internal.annotations.GuardedBy;
import libcore.io.IoUtils;
import libcore.util.Objects;
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
import java.util.ArrayList;
import java.util.List;

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

    static final int SETTINGS_VERSION_NEW_ENCODING = 121;

    private static final long WRITE_SETTINGS_DELAY_MILLIS = 200;
    private static final long MAX_WRITE_SETTINGS_DELAY_MILLIS = 2000;

    public static final int MAX_BYTES_PER_APP_PACKAGE_UNLIMITED = -1;
    public static final int MAX_BYTES_PER_APP_PACKAGE_LIMITED = 20000;

    public static final String SYSTEM_PACKAGE_NAME = "android";

    public static final int VERSION_UNDEFINED = -1;

    private static final String TAG_SETTINGS = "settings";
    private static final String TAG_SETTING = "setting";
    private static final String ATTR_PACKAGE = "package";

    private static final String ATTR_VERSION = "version";
    private static final String ATTR_ID = "id";
    private static final String ATTR_NAME = "name";

    /** Non-binary value will be written in this attribute. */
    private static final String ATTR_VALUE = "value";

    /**
     * KXmlSerializer won't like some characters.  We encode such characters in base64 and
     * store in this attribute.
     * NOTE: A null value will have NEITHER ATTR_VALUE nor ATTR_VALUE_BASE64.
     */
    private static final String ATTR_VALUE_BASE64 = "valueBase64";

    // This was used in version 120 and before.
    private static final String NULL_VALUE_OLD_STYLE = "null";

    private static final int HISTORICAL_OPERATION_COUNT = 20;
    private static final String HISTORICAL_OPERATION_UPDATE = "update";
    private static final String HISTORICAL_OPERATION_DELETE = "delete";
    private static final String HISTORICAL_OPERATION_PERSIST = "persist";
    private static final String HISTORICAL_OPERATION_INITIALIZE = "initialize";

    private final Object mLock;

    private final Handler mHandler;

    @GuardedBy("mLock")
    private final ArrayMap<String, Setting> mSettings = new ArrayMap<>();

    @GuardedBy("mLock")
    private final ArrayMap<String, Integer> mPackageToMemoryUsage;

    @GuardedBy("mLock")
    private final int mMaxBytesPerAppPackage;

    @GuardedBy("mLock")
    private final File mStatePersistFile;

    private final Setting mNullSetting = new Setting(null, null, null) {
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

    public SettingsState(Object lock, File file, int key, int maxBytesPerAppPackage,
            Looper looper) {
        // It is important that we use the same lock as the settings provider
        // to ensure multiple mutations on this state are atomicaly persisted
        // as the async persistence should be blocked while we make changes.
        mLock = lock;
        mStatePersistFile = file;
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
    public int getVersionLocked() {
        return mVersion;
    }

    public Setting getNullSetting() {
        return mNullSetting;
    }

    // The settings provider must hold its lock when calling here.
    public void setVersionLocked(int version) {
        if (version == mVersion) {
            return;
        }
        mVersion = version;

        scheduleWriteIfNeededLocked();
    }

    // The settings provider must hold its lock when calling here.
    public void onPackageRemovedLocked(String packageName) {
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
    public boolean updateSettingLocked(String name, String value, String packageName) {
        if (!hasSettingLocked(name)) {
            return false;
        }

        return insertSettingLocked(name, value, packageName);
    }

    // The settings provider must hold its lock when calling here.
    public boolean insertSettingLocked(String name, String value, String packageName) {
        if (TextUtils.isEmpty(name)) {
            return false;
        }

        Setting oldState = mSettings.get(name);
        String oldValue = (oldState != null) ? oldState.value : null;
        Setting newState;

        if (oldState != null) {
            if (!oldState.update(value, packageName)) {
                return false;
            }
            newState = oldState;
        } else {
            newState = new Setting(name, value, packageName);
            mSettings.put(name, newState);
        }

        addHistoricalOperationLocked(HISTORICAL_OPERATION_UPDATE, newState);

        updateMemoryUsagePerPackageLocked(packageName, oldValue, value);

        scheduleWriteIfNeededLocked();

        return true;
    }

    // The settings provider must hold its lock when calling here.
    public void persistSyncLocked() {
        mHandler.removeMessages(MyHandler.MSG_PERSIST_SETTINGS);
        doWriteState();
    }

    // The settings provider must hold its lock when calling here.
    public boolean deleteSettingLocked(String name) {
        if (TextUtils.isEmpty(name) || !hasSettingLocked(name)) {
            return false;
        }

        Setting oldState = mSettings.remove(name);

        updateMemoryUsagePerPackageLocked(oldState.packageName, oldState.value, null);

        addHistoricalOperationLocked(HISTORICAL_OPERATION_DELETE, oldState);

        scheduleWriteIfNeededLocked();

        return true;
    }

    // The settings provider must hold its lock when calling here.
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
                    pw.print("  ");
                    pw.print(operation.mSetting);
                }
                pw.println();
            }
            pw.println();
            pw.println();
        }
    }

    private void updateMemoryUsagePerPackageLocked(String packageName, String oldValue,
            String newValue) {
        if (mMaxBytesPerAppPackage == MAX_BYTES_PER_APP_PACKAGE_UNLIMITED) {
            return;
        }

        if (SYSTEM_PACKAGE_NAME.equals(packageName)) {
            return;
        }

        final int oldValueSize = (oldValue != null) ? oldValue.length() : 0;
        final int newValueSize = (newValue != null) ? newValue.length() : 0;
        final int deltaSize = newValueSize - oldValueSize;

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

    private boolean hasSettingLocked(String name) {
        return mSettings.indexOfKey(name) >= 0;
    }

    private void scheduleWriteIfNeededLocked() {
        // If dirty then we have a write already scheduled.
        if (!mDirty) {
            mDirty = true;
            writeStateAsyncLocked();
        }
    }

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
        if (DEBUG_PERSISTENCE) {
            Slog.i(LOG_TAG, "[PERSIST START]");
        }

        AtomicFile destination = new AtomicFile(mStatePersistFile);

        final int version;
        final ArrayMap<String, Setting> settings;

        synchronized (mLock) {
            version = mVersion;
            settings = new ArrayMap<>(mSettings);
            mDirty = false;
            mWriteScheduled = false;
        }

        FileOutputStream out = null;
        try {
            out = destination.startWrite();

            XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(out, StandardCharsets.UTF_8.name());
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_SETTINGS);
            serializer.attribute(null, ATTR_VERSION, String.valueOf(version));

            final int settingCount = settings.size();
            for (int i = 0; i < settingCount; i++) {
                Setting setting = settings.valueAt(i);

                writeSingleSetting(mVersion, serializer, setting.getId(), setting.getName(),
                        setting.getValue(), setting.getPackageName());

                if (DEBUG_PERSISTENCE) {
                    Slog.i(LOG_TAG, "[PERSISTED]" + setting.getName() + "=" + setting.getValue());
                }
            }

            serializer.endTag(null, TAG_SETTINGS);
            serializer.endDocument();
            destination.finishWrite(out);

            synchronized (mLock) {
                addHistoricalOperationLocked(HISTORICAL_OPERATION_PERSIST, null);
            }

            if (DEBUG_PERSISTENCE) {
                Slog.i(LOG_TAG, "[PERSIST END]");
            }
        } catch (Throwable t) {
            Slog.wtf(LOG_TAG, "Failed to write settings, restoring backup", t);
            destination.failWrite(out);
        } finally {
            IoUtils.closeQuietly(out);
        }
    }

    static void writeSingleSetting(int version, XmlSerializer serializer, String id,
            String name, String value, String packageName) throws IOException {
        if (id == null || isBinary(id) || name == null || isBinary(name)
                || packageName == null || isBinary(packageName)) {
            // This shouldn't happen.
            return;
        }
        serializer.startTag(null, TAG_SETTING);
        serializer.attribute(null, ATTR_ID, id);
        serializer.attribute(null, ATTR_NAME, name);
        setValueAttribute(version, serializer, value);
        serializer.attribute(null, ATTR_PACKAGE, packageName);
        serializer.endTag(null, TAG_SETTING);
    }

    static void setValueAttribute(int version, XmlSerializer serializer, String value)
            throws IOException {
        if (version >= SETTINGS_VERSION_NEW_ENCODING) {
            if (value == null) {
                // Null value -> No ATTR_VALUE nor ATTR_VALUE_BASE64.
            } else if (isBinary(value)) {
                serializer.attribute(null, ATTR_VALUE_BASE64, base64Encode(value));
            } else {
                serializer.attribute(null, ATTR_VALUE, value);
            }
        } else {
            // Old encoding.
            if (value == null) {
                serializer.attribute(null, ATTR_VALUE, NULL_VALUE_OLD_STYLE);
            } else {
                serializer.attribute(null, ATTR_VALUE, value);
            }
        }
    }

    private String getValueAttribute(XmlPullParser parser) {
        if (mVersion >= SETTINGS_VERSION_NEW_ENCODING) {
            final String value = parser.getAttributeValue(null, ATTR_VALUE);
            if (value != null) {
                return value;
            }
            final String base64 = parser.getAttributeValue(null, ATTR_VALUE_BASE64);
            if (base64 != null) {
                return base64Decode(base64);
            }
            // null has neither ATTR_VALUE nor ATTR_VALUE_BASE64.
            return null;
        } else {
            // Old encoding.
            final String stored = parser.getAttributeValue(null, ATTR_VALUE);
            if (NULL_VALUE_OLD_STYLE.equals(stored)) {
                return null;
            } else {
                return stored;
            }
        }
    }

    private void readStateSyncLocked() {
        FileInputStream in;
        if (!mStatePersistFile.exists()) {
            Slog.i(LOG_TAG, "No settings state " + mStatePersistFile);
            addHistoricalOperationLocked(HISTORICAL_OPERATION_INITIALIZE, null);
            return;
        }
        try {
            in = new AtomicFile(mStatePersistFile).openRead();
        } catch (FileNotFoundException fnfe) {
            String message = "No settings state " + mStatePersistFile;
            Slog.wtf(LOG_TAG, message);
            Slog.i(LOG_TAG, message);
            return;
        }
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, StandardCharsets.UTF_8.name());
            parseStateLocked(parser);
        } catch (XmlPullParserException | IOException e) {
            String message = "Failed parsing settings file: " + mStatePersistFile;
            Slog.wtf(LOG_TAG, message);
            throw new IllegalStateException(message , e);
        } finally {
            IoUtils.closeQuietly(in);
        }
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
                String value = getValueAttribute(parser);
                String packageName = parser.getAttributeValue(null, ATTR_PACKAGE);
                mSettings.put(name, new Setting(name, value, packageName, id));

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
        private String packageName;
        private String id;

        public Setting(Setting other) {
            name = other.name;
            value = other.value;
            packageName = other.packageName;
            id = other.id;
        }

        public Setting(String name, String value, String packageName) {
            init(name, value, packageName, String.valueOf(mNextId++));
        }

        public Setting(String name, String value, String packageName, String id) {
            mNextId = Math.max(mNextId, Long.valueOf(id) + 1);
            init(name, value, packageName, id);
        }

        private void init(String name, String value, String packageName, String id) {
            this.name = name;
            this.value = value;
            this.packageName = packageName;
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public int getkey() {
            return mKey;
        }

        public String getValue() {
            return value;
        }

        public String getPackageName() {
            return packageName;
        }

        public String getId() {
            return id;
        }

        public boolean isNull() {
            return false;
        }

        public boolean update(String value, String packageName) {
            if (Objects.equal(value, this.value)) {
                return false;
            }
            this.value = value;
            this.packageName = packageName;
            this.id = String.valueOf(mNextId++);
            return true;
        }

        public String toString() {
            return "Setting{name=" + value + " from " + packageName + "}";
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
}
