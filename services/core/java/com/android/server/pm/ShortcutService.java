/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IShortcutService;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.ShortcutQuery;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ParceledListSlice;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.ShortcutServiceInternal.ShortcutChangeListener;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.text.TextUtils;
import android.text.format.Time;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * TODO:
 * - Make save async
 *
 * - Add Bitmap support
 *
 * - Implement updateShortcuts
 *
 * - Listen to PACKAGE_*, remove orphan info, update timestamp for icon res
 *
 * - Pinned per each launcher package (multiple launchers)
 *
 * - Dev option to reset all counts for QA (for now use "adb shell cmd shortcut reset-throttling")
 *
 * - Load config from settings
 */
public class ShortcutService extends IShortcutService.Stub {
    private static final String TAG = "ShortcutService";

    private static final boolean DEBUG = true; // STOPSHIP if true
    private static final boolean DEBUG_LOAD = true; // STOPSHIP if true

    private static final int DEFAULT_RESET_INTERVAL_SEC = 24 * 60 * 60; // 1 day
    private static final int DEFAULT_MAX_DAILY_UPDATES = 10;
    private static final int DEFAULT_MAX_SHORTCUTS_PER_APP = 5;

    private static final int SAVE_DELAY_MS = 5000; // in milliseconds.

    @VisibleForTesting
    static final String FILENAME_BASE_STATE = "shortcut_service.xml";

    @VisibleForTesting
    static final String DIRECTORY_PER_USER = "shortcut_service";

    @VisibleForTesting
    static final String FILENAME_USER_PACKAGES = "shortcuts.xml";

    private static final String DIRECTORY_BITMAPS = "bitmaps";

    private static final String TAG_ROOT = "root";
    private static final String TAG_LAST_RESET_TIME = "last_reset_time";
    private static final String ATTR_VALUE = "value";

    private final Context mContext;

    private final Object mLock = new Object();

    private final Handler mHandler;

    @GuardedBy("mLock")
    private final ArrayList<ShortcutChangeListener> mListeners = new ArrayList<>(1);

    @GuardedBy("mLock")
    private long mRawLastResetTime;

    /**
     * All the information relevant to shortcuts from a single package (per-user).
     *
     * TODO Move the persisting code to this class.
     */
    private static class PackageShortcuts {
        /**
         * All the shortcuts from the package, keyed on IDs.
         */
        final private ArrayMap<String, ShortcutInfo> mShortcuts = new ArrayMap<>();

        /**
         * # of dynamic shortcuts.
         */
        private int mDynamicShortcutCount = 0;

        /**
         * # of times the package has called rate-limited APIs.
         */
        private int mApiCallCountInner;

        /**
         * When {@link #mApiCallCountInner} was reset last time.
         */
        private long mLastResetTime;

        /**
         * @return the all shortcuts.  Note DO NOT add/remove or touch the flags of the result
         * directly, which would cause {@link #mDynamicShortcutCount} to be out of sync.
         */
        @GuardedBy("mLock")
        public ArrayMap<String, ShortcutInfo> getShortcuts() {
            return mShortcuts;
        }

        /**
         * Add a shortcut, or update one with the same ID, with taking over existing flags.
         *
         * It checks the max number of dynamic shortcuts.
         */
        @GuardedBy("mLock")
        public void updateShortcutWithCapping(@NonNull ShortcutService s,
                @NonNull ShortcutInfo newShortcut) {
            final ShortcutInfo oldShortcut = mShortcuts.get(newShortcut.getId());

            int oldFlags = 0;
            int newDynamicCount = mDynamicShortcutCount;

            if (oldShortcut != null) {
                oldFlags = oldShortcut.getFlags();
                if (oldShortcut.isDynamic()) {
                    newDynamicCount--;
                }
            }
            if (newShortcut.isDynamic()) {
                newDynamicCount++;
            }
            // Make sure there's still room.
            s.enforceMaxDynamicShortcuts(newDynamicCount);

            // Okay, make it dynamic and add.
            newShortcut.addFlags(oldFlags);

            mShortcuts.put(newShortcut.getId(), newShortcut);
            mDynamicShortcutCount = newDynamicCount;
        }

        @GuardedBy("mLock")
        public void deleteAllDynamicShortcuts() {
            ArrayList<String> removeList = null; // Lazily initialize.

            for (int i = mShortcuts.size() - 1; i >= 0; i--) {
                final ShortcutInfo si = mShortcuts.valueAt(i);

                if (!si.isDynamic()) {
                    continue;
                }
                if (si.isPinned()) {
                    // Still pinned, so don't remove; just make it non-dynamic.
                    si.clearFlags(ShortcutInfo.FLAG_DYNAMIC);
                } else {
                    if (removeList == null) {
                        removeList = new ArrayList<>();
                    }
                    removeList.add(si.getId());
                }
            }
            if (removeList != null) {
                for (int i = removeList.size() - 1 ; i >= 0; i--) {
                    mShortcuts.remove(removeList.get(i));
                }
            }
            mDynamicShortcutCount = 0;
        }

        @GuardedBy("mLock")
        public void deleteDynamicWithId(@NonNull String shortcutId) {
            final ShortcutInfo oldShortcut = mShortcuts.get(shortcutId);

            if (oldShortcut == null) {
                return;
            }
            if (oldShortcut.isDynamic()) {
                mDynamicShortcutCount--;
            }
            if (oldShortcut.isPinned()) {
                oldShortcut.clearFlags(ShortcutInfo.FLAG_DYNAMIC);
            } else {
                mShortcuts.remove(shortcutId);
            }
        }

        @GuardedBy("mLock")
        public void pinAll(List<String> shortcutIds) {
            for (int i = shortcutIds.size() - 1; i >= 0; i--) {
                final ShortcutInfo shortcut = mShortcuts.get(shortcutIds.get(i));
                if (shortcut != null) {
                    shortcut.addFlags(ShortcutInfo.FLAG_PINNED);
                }
            }
        }

        /**
         * Number of calls that the caller has made, since the last reset.
         */
        @GuardedBy("mLock")
        public int getApiCallCount(@NonNull ShortcutService s) {
            final long last = s.getLastResetTimeLocked();

            // If not reset yet, then reset.
            if (mLastResetTime < last) {
                mApiCallCountInner = 0;
                mLastResetTime = last;
            }
            return mApiCallCountInner;
        }

        /**
         * If the caller app hasn't been throttled yet, increment {@link #mApiCallCountInner}
         * and return true.  Otherwise just return false.
         */
        @GuardedBy("mLock")
        public boolean tryApiCall(@NonNull ShortcutService s) {
            if (getApiCallCount(s) >= s.mMaxDailyUpdates) {
                return false;
            }
            mApiCallCountInner++;
            return true;
        }

        @GuardedBy("mLock")
        public void resetRateLimitingForCommandLine() {
            mApiCallCountInner = 0;
            mLastResetTime = 0;
        }

        /**
         * Find all shortcuts that match {@code query}.
         */
        @GuardedBy("mLock")
        public void findAll(@NonNull List<ShortcutInfo> result,
                @Nullable Predicate<ShortcutInfo> query, int cloneFlag) {
            for (int i = 0; i < mShortcuts.size(); i++) {
                final ShortcutInfo si = mShortcuts.valueAt(i);
                if (query == null || query.test(si)) {
                    result.add(si.clone(cloneFlag));
                }
            }
        }
    }

    /**
     * User ID -> package name -> list of ShortcutInfos.
     */
    @GuardedBy("mLock")
    private final SparseArray<ArrayMap<String, PackageShortcuts>> mShortcuts =
            new SparseArray<>();

    /**
     * Max number of dynamic shortcuts that each application can have at a time.
     */
    @GuardedBy("mLock")
    private int mMaxDynamicShortcuts;

    /**
     * Max number of updating API calls that each application can make a day.
     */
    @GuardedBy("mLock")
    private int mMaxDailyUpdates;

    /**
     * Actual throttling-reset interval.  By default it's a day.
     */
    @GuardedBy("mLock")
    private long mResetInterval;

    public ShortcutService(Context context) {
        mContext = Preconditions.checkNotNull(context);
        LocalServices.addService(ShortcutServiceInternal.class, new LocalService());
        mHandler = new Handler(BackgroundThread.get().getLooper());
    }

    /**
     * System service lifecycle.
     */
    public static final class Lifecycle extends SystemService {
        final ShortcutService mService;

        public Lifecycle(Context context) {
            super(context);
            mService = new ShortcutService(context);
        }

        @Override
        public void onStart() {
            publishBinderService(Context.SHORTCUT_SERVICE, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            mService.onBootPhase(phase);
        }

        @Override
        public void onCleanupUser(int userHandle) {
            synchronized (mService.mLock) {
                mService.onCleanupUserInner(userHandle);
            }
        }

        @Override
        public void onStartUser(int userId) {
            synchronized (mService.mLock) {
                mService.onStartUserLocked(userId);
            }
        }
    }

    /** lifecycle event */
    void onBootPhase(int phase) {
        if (DEBUG) {
            Slog.d(TAG, "onBootPhase: " + phase);
        }
        switch (phase) {
            case SystemService.PHASE_LOCK_SETTINGS_READY:
                initialize();
                break;
        }
    }

    /** lifecycle event */
    void onStartUserLocked(int userId) {
        // Preload
        getUserShortcutsLocked(userId);
    }

    /** lifecycle event */
    void onCleanupUserInner(int userId) {
        // Unload
        mShortcuts.delete(userId);
    }

    /** Return the base state file name */
    private AtomicFile getBaseStateFile() {
        final File path = new File(injectSystemDataPath(), FILENAME_BASE_STATE);
        path.mkdirs();
        return new AtomicFile(path);
    }

    /**
     * Init the instance. (load the state file, etc)
     */
    private void initialize() {
        synchronized (mLock) {
            injectLoadConfigurationLocked();
            loadBaseStateLocked();
        }
    }

    // Test overrides it to inject different values.
    @VisibleForTesting
    void injectLoadConfigurationLocked() {
        mResetInterval = DEFAULT_RESET_INTERVAL_SEC * 1000L;
        mMaxDailyUpdates = DEFAULT_MAX_DAILY_UPDATES;
        mMaxDynamicShortcuts = DEFAULT_MAX_SHORTCUTS_PER_APP;
    }

    // === Persistings ===

    @Nullable
    private String parseStringAttribute(XmlPullParser parser, String attribute) {
        return parser.getAttributeValue(null, attribute);
    }

    private long parseLongAttribute(XmlPullParser parser, String attribute) {
        final String value = parseStringAttribute(parser, attribute);
        if (TextUtils.isEmpty(value)) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Error parsing long " + value);
            return 0;
        }
    }

    @Nullable
    private ComponentName parseComponentNameAttribute(XmlPullParser parser, String attribute) {
        final String value = parseStringAttribute(parser, attribute);
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        return ComponentName.unflattenFromString(value);
    }

    @Nullable
    private Intent parseIntentAttribute(XmlPullParser parser, String attribute) {
        final String value = parseStringAttribute(parser, attribute);
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        try {
            return Intent.parseUri(value, /* flags =*/ 0);
        } catch (URISyntaxException e) {
            Slog.e(TAG, "Error parsing intent", e);
            return null;
        }
    }

    private void writeTagValue(XmlSerializer out, String tag, String value) throws IOException {
        if (TextUtils.isEmpty(value)) return;

        out.startTag(null, tag);
        out.attribute(null, ATTR_VALUE, value);
        out.endTag(null, tag);
    }

    private void writeTagValue(XmlSerializer out, String tag, long value) throws IOException {
        writeTagValue(out, tag, Long.toString(value));
    }

    private void writeTagExtra(XmlSerializer out, String tag, PersistableBundle bundle)
            throws IOException, XmlPullParserException {
        if (bundle == null) return;

        out.startTag(null, tag);
        bundle.saveToXml(out);
        out.endTag(null, tag);
    }

    private void writeAttr(XmlSerializer out, String name, String value) throws IOException {
        if (TextUtils.isEmpty(value)) return;

        out.attribute(null, name, value);
    }

    private void writeAttr(XmlSerializer out, String name, long value) throws IOException {
        writeAttr(out, name, String.valueOf(value));
    }

    private void writeAttr(XmlSerializer out, String name, ComponentName comp) throws IOException {
        if (comp == null) return;
        writeAttr(out, name, comp.flattenToString());
    }

    private void writeAttr(XmlSerializer out, String name, Intent intent) throws IOException {
        if (intent == null) return;

        writeAttr(out, name, intent.toUri(/* flags =*/ 0));
    }

    @VisibleForTesting
    void saveBaseStateLocked() {
        final AtomicFile file = getBaseStateFile();
        if (DEBUG) {
            Slog.i(TAG, "Saving to " + file.getBaseFile());
        }

        FileOutputStream outs = null;
        try {
            outs = file.startWrite();

            // Write to XML
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(outs, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);
            out.startTag(null, TAG_ROOT);

            // Body.
            writeTagValue(out, TAG_LAST_RESET_TIME, mRawLastResetTime);

            // Epilogue.
            out.endTag(null, TAG_ROOT);
            out.endDocument();

            // Close.
            file.finishWrite(outs);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write to file " + file.getBaseFile(), e);
            file.failWrite(outs);
        }
    }

    private void loadBaseStateLocked() {
        mRawLastResetTime = 0;

        final AtomicFile file = getBaseStateFile();
        if (DEBUG) {
            Slog.i(TAG, "Loading from " + file.getBaseFile());
        }
        try (FileInputStream in = file.openRead()) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, StandardCharsets.UTF_8.name());

            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }
                final int depth = parser.getDepth();
                // Check the root tag
                final String tag = parser.getName();
                if (depth == 1) {
                    if (!TAG_ROOT.equals(tag)) {
                        Slog.e(TAG, "Invalid root tag: " + tag);
                        return;
                    }
                    continue;
                }
                // Assume depth == 2
                switch (tag) {
                    case TAG_LAST_RESET_TIME:
                        mRawLastResetTime = parseLongAttribute(parser, ATTR_VALUE);
                        break;
                    default:
                        Slog.e(TAG, "Invalid tag: " + tag);
                        break;
                }
            }
        } catch (FileNotFoundException e) {
            // Use the default
        } catch (IOException|XmlPullParserException e) {
            Slog.e(TAG, "Failed to read file " + file.getBaseFile(), e);

            mRawLastResetTime = 0;
        }
        // Adjust the last reset time.
        getLastResetTimeLocked();
    }

    private void saveUserLocked(@UserIdInt int userId) {
        final File path = new File(injectUserDataPath(userId), FILENAME_USER_PACKAGES);
        if (DEBUG) {
            Slog.i(TAG, "Saving to " + path);
        }
        path.mkdirs();
        final AtomicFile file = new AtomicFile(path);
        FileOutputStream outs = null;
        try {
            outs = file.startWrite();

            // Write to XML
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(outs, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);
            out.startTag(null, TAG_ROOT);

            final ArrayMap<String, PackageShortcuts> packages = getUserShortcutsLocked(userId);

            // Body.
            for (int i = 0; i < packages.size(); i++) {
                final String packageName = packages.keyAt(i);
                final PackageShortcuts shortcuts = packages.valueAt(i);

                // TODO Move this to PackageShortcuts.

                out.startTag(null, "package");

                writeAttr(out, "name", packageName);
                writeAttr(out, "dynamic-count", shortcuts.mDynamicShortcutCount);
                writeAttr(out, "call-count", shortcuts.mApiCallCountInner);
                writeAttr(out, "last-reset", shortcuts.mLastResetTime);

                final int size = shortcuts.getShortcuts().size();
                for (int j = 0; j < size; j++) {
                    saveShortcut(out, shortcuts.getShortcuts().valueAt(j));
                }

                out.endTag(null, "package");
            }

            // Epilogue.
            out.endTag(null, TAG_ROOT);
            out.endDocument();

            // Close.
            file.finishWrite(outs);
        } catch (IOException|XmlPullParserException e) {
            Slog.e(TAG, "Failed to write to file " + file.getBaseFile(), e);
            file.failWrite(outs);
        }
    }

    private void saveShortcut(XmlSerializer out, ShortcutInfo si)
            throws IOException, XmlPullParserException {
        out.startTag(null, "shortcut");
        writeAttr(out, "id", si.getId());
        // writeAttr(out, "package", si.getPackageName()); // not needed
        writeAttr(out, "activity", si.getActivityComponent());
        // writeAttr(out, "icon", si.getIcon());  // We don't save it.
        writeAttr(out, "title", si.getTitle());
        writeAttr(out, "intent", si.getIntent());
        writeAttr(out, "weight", si.getWeight());
        writeAttr(out, "timestamp", si.getLastChangedTimestamp());
        writeAttr(out, "flags", si.getFlags());
        writeAttr(out, "icon-res", si.getIconResourceId());
        writeAttr(out, "bitmap-path", si.getBitmapPath());

        writeTagExtra(out, "intent-extras", si.getIntentPersistableExtras());
        writeTagExtra(out, "extras", si.getExtras());

        out.endTag(null, "shortcut");
    }

    private static IOException throwForInvalidTag(int depth, String tag) throws IOException {
        throw new IOException(String.format("Invalid tag '%s' found at depth %d", tag, depth));
    }

    @Nullable
    private ArrayMap<String, PackageShortcuts> loadUserLocked(@UserIdInt int userId) {
        final File path = new File(injectUserDataPath(userId), FILENAME_USER_PACKAGES);
        if (DEBUG) {
            Slog.i(TAG, "Loading from " + path);
        }
        path.mkdirs();
        final AtomicFile file = new AtomicFile(path);

        final FileInputStream in;
        try {
            in = file.openRead();
        } catch (FileNotFoundException e) {
            if (DEBUG) {
                Slog.i(TAG, "Not found " + path);
            }
            return null;
        }
        final ArrayMap<String, PackageShortcuts> ret = new ArrayMap<String, PackageShortcuts>();
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, StandardCharsets.UTF_8.name());

            String packageName = null;
            PackageShortcuts shortcuts = null;

            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }
                final int depth = parser.getDepth();

                // TODO Move some of this to PackageShortcuts.

                final String tag = parser.getName();
                if (DEBUG_LOAD) {
                    Slog.d(TAG, String.format("depth=%d type=%d name=%s",
                            depth, type, tag));
                }
                switch (depth) {
                    case 1: {
                        if (TAG_ROOT.equals(tag)) {
                            continue;
                        }
                        break;
                    }
                    case 2: {
                        switch (tag) {
                            case "package":
                                packageName = parseStringAttribute(parser, "name");
                                shortcuts = new PackageShortcuts();
                                ret.put(packageName, shortcuts);

                                shortcuts.mDynamicShortcutCount =
                                        (int) parseLongAttribute(parser, "dynamic-count");
                                shortcuts.mApiCallCountInner =
                                        (int) parseLongAttribute(parser, "call-count");
                                shortcuts.mLastResetTime = parseLongAttribute(parser, "last-reset");
                                continue;
                        }
                        break;
                    }
                    case 3: {
                        switch (tag) {
                            case "shortcut":
                                final ShortcutInfo si = parseShortcut(parser, packageName);
                                shortcuts.mShortcuts.put(si.getId(), si);
                                continue;
                        }
                        break;
                    }
                }
                throwForInvalidTag(depth, tag);
            }
            return ret;
        } catch (IOException|XmlPullParserException e) {
            Slog.e(TAG, "Failed to read file " + file.getBaseFile(), e);
            return null;
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    private ShortcutInfo parseShortcut(XmlPullParser parser, String packgeName)
            throws IOException, XmlPullParserException {
        String id;
        ComponentName activityComponent;
        Icon icon;
        String title;
        Intent intent;
        PersistableBundle intentPersistableExtras = null;
        int weight;
        PersistableBundle extras = null;
        long lastChangedTimestamp;
        int flags;
        int iconRes;
        String bitmapPath;

        id = parseStringAttribute(parser, "id");
        activityComponent = parseComponentNameAttribute(parser, "activity");
        title = parseStringAttribute(parser, "title");
        intent = parseIntentAttribute(parser, "intent");
        weight = (int) parseLongAttribute(parser, "weight");
        lastChangedTimestamp = (int) parseLongAttribute(parser, "timestamp");
        flags = (int) parseLongAttribute(parser, "flags");
        iconRes = (int) parseLongAttribute(parser, "icon-res");
        bitmapPath = parseStringAttribute(parser, "bitmap-path");

        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            final int depth = parser.getDepth();
            final String tag = parser.getName();
            if (DEBUG_LOAD) {
                Slog.d(TAG, String.format("  depth=%d type=%d name=%s",
                        depth, type, tag));
            }
            switch (tag) {
                case "intent-extras":
                    intentPersistableExtras = PersistableBundle.restoreFromXml(parser);
                    continue;
                case "extras":
                    extras = PersistableBundle.restoreFromXml(parser);
                    continue;
            }
            throw throwForInvalidTag(depth, tag);
        }
        return new ShortcutInfo(
                id, packgeName, activityComponent, /* icon =*/ null, title, intent,
                intentPersistableExtras, weight, extras, lastChangedTimestamp, flags,
                iconRes, bitmapPath);
    }

    // TODO Actually make it async.
    private void scheduleSaveBaseState() {
        synchronized (mLock) {
            saveBaseStateLocked();
        }
    }

    // TODO Actually make it async.
    private void scheduleSaveUser(@UserIdInt int userId) {
        synchronized (mLock) {
            saveUserLocked(userId);
        }
    }

    /** Return the last reset time. */
    long getLastResetTimeLocked() {
        updateTimes();
        return mRawLastResetTime;
    }

    /** Return the next reset time. */
    long getNextResetTimeLocked() {
        updateTimes();
        return mRawLastResetTime + mResetInterval;
    }

    /**
     * Update the last reset time.
     */
    private void updateTimes() {

        final long now = injectCurrentTimeMillis();

        final long prevLastResetTime = mRawLastResetTime;

        if (mRawLastResetTime == 0) { // first launch.
            // TODO Randomize??
            mRawLastResetTime = now;
        } else if (now < mRawLastResetTime) {
            // Clock rewound.
            // TODO Randomize??
            mRawLastResetTime = now;
        } else {
            // TODO Do it properly.
            while ((mRawLastResetTime + mResetInterval) <= now) {
                mRawLastResetTime += mResetInterval;
            }
        }
        if (prevLastResetTime != mRawLastResetTime) {
            scheduleSaveBaseState();
        }
    }

    /** Return the per-user state. */
    @GuardedBy("mLock")
    @NonNull
    private ArrayMap<String, PackageShortcuts> getUserShortcutsLocked(@UserIdInt int userId) {
        ArrayMap<String, PackageShortcuts> userPackages = mShortcuts.get(userId);
        if (userPackages == null) {
            userPackages = loadUserLocked(userId);
            if (userPackages == null) {
                userPackages = new ArrayMap<>();
            }
            mShortcuts.put(userId, userPackages);
        }
        return userPackages;
    }

    /** Return the per-user per-package state. */
    @GuardedBy("mLock")
    @NonNull
    private PackageShortcuts getPackageShortcutsLocked(
            @NonNull String packageName, @UserIdInt int userId) {
        final ArrayMap<String, PackageShortcuts> userPackages = getUserShortcutsLocked(userId);
        PackageShortcuts shortcuts = userPackages.get(packageName);
        if (shortcuts == null) {
            shortcuts = new PackageShortcuts();
            userPackages.put(packageName, shortcuts);
        }
        return shortcuts;
    }

    // === Caller validation ===

    private boolean isCallerSystem() {
        final int callingUid = injectBinderCallingUid();
         return UserHandle.isSameApp(callingUid, Process.SYSTEM_UID);
    }

    private boolean isCallerShell() {
        final int callingUid = injectBinderCallingUid();
        return callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID;
    }

    private void enforceSystemOrShell() {
        Preconditions.checkState(isCallerSystem() || isCallerShell(),
                "Caller must be system or shell");
    }

    private void enforceShell() {
        Preconditions.checkState(isCallerShell(), "Caller must be shell");
    }

    private void verifyCaller(@NonNull String packageName, @UserIdInt int userId) {
        Preconditions.checkStringNotEmpty(packageName, "packageName");

        if (isCallerSystem()) {
            return; // no check
        }

        final int callingUid = injectBinderCallingUid();

        // Otherwise, make sure the arguments are valid.
        if (UserHandle.getUserId(callingUid) != userId) {
            throw new SecurityException("Invalid user-ID");
        }
        verifyCallingPackage(packageName);
    }

    private void verifyCallingPackage(@NonNull String packageName) {
        Preconditions.checkStringNotEmpty(packageName, "packageName");

        if (isCallerSystem()) {
            return; // no check
        }

        if (injectGetPackageUid(packageName) == injectBinderCallingUid()) {
            return; // Caller is valid.
        }
        throw new SecurityException("Caller UID= doesn't own " + packageName);
    }

    // Test overrides it.
    int injectGetPackageUid(String packageName) {
        try {

            // TODO Is MATCH_UNINSTALLED_PACKAGES correct to get SD card app info?

            return mContext.getPackageManager().getPackageUid(packageName,
                    PackageManager.MATCH_ENCRYPTION_AWARE_AND_UNAWARE
                            | PackageManager.MATCH_UNINSTALLED_PACKAGES);
        } catch (NameNotFoundException e) {
            return -1;
        }
    }

    /**
     * Throw if {@code numShortcuts} is bigger than {@link #mMaxDynamicShortcuts}.
     */
    void enforceMaxDynamicShortcuts(int numShortcuts) {
        if (numShortcuts > mMaxDynamicShortcuts) {
            throw new IllegalArgumentException("Max number of dynamic shortcuts exceeded");
        }
    }

    /**
     * - Sends a notification to LauncherApps
     * - Write to file
     */
    private void userPackageChanged(@NonNull String packageName, @UserIdInt int userId) {
        notifyListeners(packageName, userId);
        scheduleSaveUser(userId);
    }

    private void notifyListeners(@NonNull String packageName, @UserIdInt int userId) {
        final ArrayList<ShortcutChangeListener> copy;
        final List<ShortcutInfo> shortcuts = new ArrayList<>();
        synchronized (mLock) {
            copy = new ArrayList<>(mListeners);

            getPackageShortcutsLocked(packageName, userId)
                    .findAll(shortcuts, /* query =*/ null, ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO);
        }
        for (int i = copy.size() - 1; i >= 0; i--) {
            copy.get(i).onShortcutChanged(packageName, shortcuts, userId);
        }
    }

    /**
     * Clean up / validate an incoming shortcut.
     * - Make sure all mandatory fields are set.
     * - Make sure the intent's extras are persistable, and them to set
     *  {@link ShortcutInfo#mIntentPersistableExtras}.  Also clear its extras.
     * - Clear flags.
     */
    private void fixUpIncomingShortcutInfo(@NonNull ShortcutInfo shortcut) {
        Preconditions.checkNotNull(shortcut, "Null shortcut detected");
        if (shortcut.getActivityComponent() != null) {
            Preconditions.checkState(
                    shortcut.getPackageName().equals(
                            shortcut.getActivityComponent().getPackageName()),
                    "Activity package name mismatch");
        }

        shortcut.enforceMandatoryFields();

        final Intent intent = shortcut.getIntent();
        final Bundle intentExtras = intent.getExtras();
        if (intentExtras != null && intentExtras.size() > 0) {
            intent.replaceExtras((Bundle) null);

            // PersistableBundle's constructor will throw IllegalArgumentException if original
            // extras contain something not persistable.
            shortcut.setIntentPersistableExtras(new PersistableBundle(intentExtras));
        }

        // TODO Save the icon
        shortcut.setIcon(null);

        shortcut.setFlags(0);
    }

    // === APIs ===

    @Override
    public boolean setDynamicShortcuts(String packageName, ParceledListSlice shortcutInfoList,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        final List<ShortcutInfo> newShortcuts = (List<ShortcutInfo>) shortcutInfoList.getList();
        final int size = newShortcuts.size();

        synchronized (mLock) {
            final PackageShortcuts ps = getPackageShortcutsLocked(packageName, userId);

            // Throttling.
            if (!ps.tryApiCall(this)) {
                return false;
            }
            enforceMaxDynamicShortcuts(size);

            // Validate the shortcuts.
            for (int i = 0; i < size; i++) {
                fixUpIncomingShortcutInfo(newShortcuts.get(i));
            }

            // First, remove all un-pinned; dynamic shortcuts
            ps.deleteAllDynamicShortcuts();

            // Then, add/update all.  We need to make sure to take over "pinned" flag.
            for (int i = 0; i < size; i++) {
                final ShortcutInfo newShortcut = newShortcuts.get(i);
                newShortcut.addFlags(ShortcutInfo.FLAG_DYNAMIC);
                ps.updateShortcutWithCapping(this, newShortcut);
            }
        }
        userPackageChanged(packageName, userId);

        return true;
    }

    @Override
    public boolean updateShortcuts(String packageName, ParceledListSlice shortcutInfoList,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        final List<ShortcutInfo> newShortcuts = (List<ShortcutInfo>) shortcutInfoList.getList();

        synchronized (mLock) {

            if (true) {
                throw new RuntimeException("not implemented yet");
            }

            // TODO Similar to setDynamicShortcuts, but don't add new ones, and don't change flags.
            // Update non-null fields only.
        }
        userPackageChanged(packageName, userId);

        return true;
    }

    @Override
    public boolean addDynamicShortcut(String packageName, ShortcutInfo newShortcut,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        synchronized (mLock) {
            final PackageShortcuts ps = getPackageShortcutsLocked(packageName, userId);

            // Throttling.
            if (!ps.tryApiCall(this)) {
                return false;
            }

            // Validate the shortcut.
            fixUpIncomingShortcutInfo(newShortcut);

            // Add it.
            newShortcut.addFlags(ShortcutInfo.FLAG_DYNAMIC);
            ps.updateShortcutWithCapping(this, newShortcut);
        }
        userPackageChanged(packageName, userId);

        return true;
    }

    @Override
    public void deleteDynamicShortcut(String packageName, String shortcutId,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);
        Preconditions.checkStringNotEmpty(shortcutId, "shortcutId must be provided");

        synchronized (mLock) {
            getPackageShortcutsLocked(packageName, userId).deleteDynamicWithId(shortcutId);
        }
        userPackageChanged(packageName, userId);
    }

    @Override
    public void deleteAllDynamicShortcuts(String packageName, @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        synchronized (mLock) {
            getPackageShortcutsLocked(packageName, userId).deleteAllDynamicShortcuts();
        }
        userPackageChanged(packageName, userId);
    }

    @Override
    public ParceledListSlice<ShortcutInfo> getDynamicShortcuts(String packageName,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);
        synchronized (mLock) {
            return getShortcutsWithQueryLocked(
                    packageName, userId, ShortcutInfo.CLONE_REMOVE_FOR_CREATOR,
                    ShortcutInfo::isDynamic);
        }
    }

    @Override
    public ParceledListSlice<ShortcutInfo> getPinnedShortcuts(String packageName,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);
        synchronized (mLock) {
            return getShortcutsWithQueryLocked(
                    packageName, userId, ShortcutInfo.CLONE_REMOVE_FOR_CREATOR,
                    ShortcutInfo::isPinned);
        }
    }

    private ParceledListSlice<ShortcutInfo> getShortcutsWithQueryLocked(@NonNull String packageName,
            @UserIdInt int userId, int cloneFlags, @NonNull Predicate<ShortcutInfo> query) {

        final ArrayList<ShortcutInfo> ret = new ArrayList<>();

        getPackageShortcutsLocked(packageName, userId).findAll(ret, query, cloneFlags);

        return new ParceledListSlice<>(ret);
    }

    @Override
    public int getMaxDynamicShortcutCount(String packageName, @UserIdInt int userId)
            throws RemoteException {
        verifyCaller(packageName, userId);

        return mMaxDynamicShortcuts;
    }

    @Override
    public int getRemainingCallCount(String packageName, @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        synchronized (mLock) {
            return mMaxDailyUpdates
                    - getPackageShortcutsLocked(packageName, userId).getApiCallCount(this);
        }
    }

    @Override
    public long getRateLimitResetTime(String packageName, @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        synchronized (mLock) {
            return getNextResetTimeLocked();
        }
    }

    /**
     * Reset all throttling, for developer options and command line.  Only system/shell can call it.
     */
    @Override
    public void resetThrottling() {
        enforceSystemOrShell();

        resetThrottlingInner();
    }

    @VisibleForTesting
    void resetThrottlingInner() {
        synchronized (mLock) {
            mRawLastResetTime = injectCurrentTimeMillis();
        }
        scheduleSaveBaseState();
    }

    /**
     * Entry point from {@link LauncherApps}.
     */
    private class LocalService extends ShortcutServiceInternal {
        @Override
        public List<ShortcutInfo> getShortcuts(
                @NonNull String callingPackage, long changedSince,
                @Nullable String packageName, @Nullable ComponentName componentName,
                int queryFlags, int userId) {
            final ArrayList<ShortcutInfo> ret = new ArrayList<>();
            final int cloneFlag =
                    ((queryFlags & ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY) == 0)
                            ? ShortcutInfo.CLONE_REMOVE_FOR_LAUNCHER
                            : ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO;

            synchronized (mLock) {
                if (packageName != null) {
                    getShortcutsInnerLocked(packageName, changedSince, componentName, queryFlags,
                            userId, ret, cloneFlag);
                } else {
                    final ArrayMap<String, PackageShortcuts> packages =
                            getUserShortcutsLocked(userId);
                    for (int i = 0; i < packages.size(); i++) {
                        getShortcutsInnerLocked(
                                packages.keyAt(i),
                                changedSince, componentName, queryFlags, userId, ret, cloneFlag);
                    }
                }
            }
            return ret;
        }

        private void getShortcutsInnerLocked(@Nullable String packageName,long changedSince,
                @Nullable ComponentName componentName, int queryFlags,
                int userId, ArrayList<ShortcutInfo> ret, int cloneFlag) {
            getPackageShortcutsLocked(packageName, userId).findAll(ret,
                    (ShortcutInfo si) -> {
                        if (si.getLastChangedTimestamp() < changedSince) {
                            return false;
                        }
                        if (componentName != null
                                && !componentName.equals(si.getActivityComponent())) {
                            return false;
                        }
                        final boolean matchDynamic =
                                ((queryFlags & ShortcutQuery.FLAG_GET_DYNAMIC) != 0)
                                && si.isDynamic();
                        final boolean matchPinned =
                                ((queryFlags & ShortcutQuery.FLAG_GET_PINNED) != 0)
                                        && si.isPinned();
                        return matchDynamic || matchPinned;
                    }, cloneFlag);
        }

        @Override
        public List<ShortcutInfo> getShortcutInfo(
                @NonNull String callingPackage,
                @NonNull String packageName, @Nullable List<String> ids, int userId) {
            // Calling permission must be checked by LauncherAppsImpl.
            Preconditions.checkStringNotEmpty(packageName, "packageName");

            final ArrayList<ShortcutInfo> ret = new ArrayList<>(ids.size());
            final ArraySet<String> idSet = new ArraySet<>(ids);
            synchronized (mLock) {
                getPackageShortcutsLocked(packageName, userId).findAll(ret,
                        (ShortcutInfo si) -> idSet.contains(si.getId()),
                        ShortcutInfo.CLONE_REMOVE_FOR_LAUNCHER);
            }
            return ret;
        }

        @Override
        public void pinShortcuts(@NonNull String callingPackage, @NonNull String packageName,
                @NonNull List<String> shortcutIds, int userId) {
            // Calling permission must be checked by LauncherAppsImpl.
            Preconditions.checkStringNotEmpty(packageName, "packageName");
            Preconditions.checkNotNull(shortcutIds, "shortcutIds");

            synchronized (mLock) {
                getPackageShortcutsLocked(packageName, userId).pinAll(shortcutIds);
            }
            userPackageChanged(packageName, userId);
        }

        @Override
        public Intent createShortcutIntent(@NonNull String callingPackage,
                @NonNull String packageName, @NonNull String shortcutId, int userId) {
            // Calling permission must be checked by LauncherAppsImpl.
            Preconditions.checkStringNotEmpty(packageName, "packageName can't be empty");
            Preconditions.checkStringNotEmpty(shortcutId, "shortcutId can't be empty");

            synchronized (mLock) {
                final ShortcutInfo fullShortcut =
                        getPackageShortcutsLocked(packageName, userId)
                        .getShortcuts().get(shortcutId);
                if (fullShortcut == null) {
                    return null;
                } else {
                    final Intent intent = fullShortcut.getIntent();
                    final PersistableBundle extras = fullShortcut.getIntentPersistableExtras();
                    if (extras != null) {
                        intent.replaceExtras(new Bundle(extras));
                    }

                    return intent;
                }
            }
        }

        @Override
        public void addListener(@NonNull ShortcutChangeListener listener) {
            synchronized (mLock) {
                mListeners.add(Preconditions.checkNotNull(listener));
            }
        }
    }

    // === Dump ===

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump UserManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " without permission "
                    + android.Manifest.permission.DUMP);
            return;
        }
        dumpInner(pw);
    }

    @VisibleForTesting
    void dumpInner(PrintWriter pw) {
        synchronized (mLock) {
            final long now = injectCurrentTimeMillis();
            pw.print("Now: [");
            pw.print(now);
            pw.print("] ");
            pw.print(formatTime(now));
            pw.print("  Raw last reset: [");
            pw.print(mRawLastResetTime);
            pw.print("] ");
            pw.print(formatTime(mRawLastResetTime));

            final long last = getLastResetTimeLocked();
            final long next = getNextResetTimeLocked();
            pw.print("  Last reset: [");
            pw.print(last);
            pw.print("] ");
            pw.print(formatTime(last));

            pw.print("  Next reset: [");
            pw.print(next);
            pw.print("] ");
            pw.print(formatTime(next));
            pw.println();

            pw.println();

            for (int i = 0; i < mShortcuts.size(); i++) {
                dumpUserLocked(pw, mShortcuts.keyAt(i));
            }

        }
    }

    private void dumpUserLocked(PrintWriter pw, int userId) {
        pw.print("  User: ");
        pw.print(userId);
        pw.println();

        final ArrayMap<String, PackageShortcuts> packages = mShortcuts.get(userId);
        if (packages == null) {
            return;
        }
        for (int j = 0; j < packages.size(); j++) {
            dumpPackageLocked(pw, userId, packages.keyAt(j));
        }
        pw.println();
    }

    private void dumpPackageLocked(PrintWriter pw, int userId, String packageName) {
        final PackageShortcuts shortcuts = mShortcuts.get(userId).get(packageName);
        if (shortcuts == null) {
            return;
        }

        pw.print("    Package: ");
        pw.print(packageName);
        pw.println();

        pw.print("      Calls: ");
        pw.print(shortcuts.getApiCallCount(this));
        pw.println();

        // This should be after getApiCallCount(), which may update it.
        pw.print("      Last reset: [");
        pw.print(shortcuts.mLastResetTime);
        pw.print("] ");
        pw.print(formatTime(shortcuts.mLastResetTime));
        pw.println();

        pw.println("      Shortcuts:");
        final int size = shortcuts.getShortcuts().size();
        for (int i = 0; i < size; i++) {
            pw.print("        ");
            pw.println(shortcuts.getShortcuts().valueAt(i).toInsecureString());
        }
    }

    private static String formatTime(long time) {
        Time tobj = new Time();
        tobj.set(time);
        return tobj.format("%Y-%m-%d %H:%M:%S");
    }

    // === Shell support ===

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ResultReceiver resultReceiver) throws RemoteException {

        enforceShell();

        (new MyShellCommand()).exec(this, in, out, err, args, resultReceiver);
    }

    /**
     * Handle "adb shell cmd".
     */
    private class MyShellCommand extends ShellCommand {
        @Override
        public int onCommand(String cmd) {
            if (cmd == null) {
                return handleDefaultCommands(cmd);
            }
            final PrintWriter pw = getOutPrintWriter();
            switch(cmd) {
                case "reset-package-throttling":
                    return handleResetPackageThrottling();
                case "reset-throttling":
                    return handleResetThrottling();
                default:
                    return handleDefaultCommands(cmd);
            }
        }

        @Override
        public void onHelp() {
            final PrintWriter pw = getOutPrintWriter();
            pw.println("Usage: cmd shortcut COMMAND [options ...]");
            pw.println();
            pw.println("cmd shortcut reset-package-throttling [--user USER_ID] PACKAGE");
            pw.println("    Reset throttling for a package");
            pw.println();
            pw.println("cmd shortcut reset-throttling");
            pw.println("    Reset throttling for all packages and users");
            pw.println();
        }

        private int handleResetThrottling() {
            resetThrottling();
            return 0;
        }

        private int handleResetPackageThrottling() {
            final PrintWriter pw = getOutPrintWriter();

            int userId = UserHandle.USER_SYSTEM;
            String opt;
            while ((opt = getNextOption()) != null) {
                switch (opt) {
                    case "--user":
                        userId = UserHandle.parseUserArg(getNextArgRequired());
                        break;
                    default:
                        pw.println("Error: Unknown option: " + opt);
                        return 1;
                }
            }
            final String packageName = getNextArgRequired();

            synchronized (mLock) {
                getPackageShortcutsLocked(packageName, userId).resetRateLimitingForCommandLine();
                saveUserLocked(userId);
            }

            return 0;
        }
    }

    // === Unit test support ===

    // Injection point.
    long injectCurrentTimeMillis() {
        return System.currentTimeMillis();
    }

    // Injection point.
    int injectBinderCallingUid() {
        return getCallingUid();
    }

    File injectSystemDataPath() {
        return Environment.getDataSystemDirectory();
    }

    File injectUserDataPath(@UserIdInt int userId) {
        return new File(Environment.getDataSystemDeDirectory(userId), DIRECTORY_PER_USER);
    }

    @VisibleForTesting
    SparseArray<ArrayMap<String, PackageShortcuts>> getShortcutsForTest() {
        return mShortcuts;
    }

    @VisibleForTesting
    void setMaxDynamicShortcutsForTest(int max) {
        mMaxDynamicShortcuts = max;
    }

    @VisibleForTesting
    void setMaxDailyUpdatesForTest(int max) {
        mMaxDailyUpdates = max;
    }

    @VisibleForTesting
    public void setResetIntervalForTest(long interval) {
        mResetInterval = interval;
    }
}
