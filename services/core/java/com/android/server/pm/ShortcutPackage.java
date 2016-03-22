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
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.os.PersistableBundle;
import android.text.format.Formatter;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Package information used by {@link ShortcutService}.
 */
class ShortcutPackage {
    private static final String TAG = ShortcutService.TAG;

    static final String TAG_ROOT = "package";
    private static final String TAG_INTENT_EXTRAS = "intent-extras";
    private static final String TAG_EXTRAS = "extras";
    private static final String TAG_SHORTCUT = "shortcut";

    private static final String ATTR_NAME = "name";
    private static final String ATTR_DYNAMIC_COUNT = "dynamic-count";
    private static final String ATTR_CALL_COUNT = "call-count";
    private static final String ATTR_LAST_RESET = "last-reset";
    private static final String ATTR_ID = "id";
    private static final String ATTR_ACTIVITY = "activity";
    private static final String ATTR_TITLE = "title";
    private static final String ATTR_INTENT = "intent";
    private static final String ATTR_WEIGHT = "weight";
    private static final String ATTR_TIMESTAMP = "timestamp";
    private static final String ATTR_FLAGS = "flags";
    private static final String ATTR_ICON_RES = "icon-res";
    private static final String ATTR_BITMAP_PATH = "bitmap-path";

    @UserIdInt
    final int mUserId;

    @NonNull
    final String mPackageName;

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
    private int mApiCallCount;

    /**
     * When {@link #mApiCallCount} was reset last time.
     */
    private long mLastResetTime;

    ShortcutPackage(int userId, String packageName) {
        mUserId = userId;
        mPackageName = packageName;
    }

    @Nullable
    public ShortcutInfo findShortcutById(String id) {
        return mShortcuts.get(id);
    }

    private ShortcutInfo deleteShortcut(@NonNull ShortcutService s,
            @NonNull String id) {
        final ShortcutInfo shortcut = mShortcuts.remove(id);
        if (shortcut != null) {
            s.removeIcon(mUserId, shortcut);
            shortcut.clearFlags(ShortcutInfo.FLAG_DYNAMIC | ShortcutInfo.FLAG_PINNED);
        }
        return shortcut;
    }

    void addShortcut(@NonNull ShortcutService s, @NonNull ShortcutInfo newShortcut) {
        deleteShortcut(s, newShortcut.getId());
        s.saveIconAndFixUpShortcut(mUserId, newShortcut);
        mShortcuts.put(newShortcut.getId(), newShortcut);
    }

    /**
     * Add a shortcut, or update one with the same ID, with taking over existing flags.
     *
     * It checks the max number of dynamic shortcuts.
     */
    public void addDynamicShortcut(@NonNull ShortcutService s,
            @NonNull ShortcutInfo newShortcut) {
        newShortcut.addFlags(ShortcutInfo.FLAG_DYNAMIC);

        final ShortcutInfo oldShortcut = mShortcuts.get(newShortcut.getId());

        final boolean wasPinned;
        final int newDynamicCount;

        if (oldShortcut == null) {
            wasPinned = false;
            newDynamicCount = mDynamicShortcutCount + 1; // adding a dynamic shortcut.
        } else {
            wasPinned = oldShortcut.isPinned();
            if (oldShortcut.isDynamic()) {
                newDynamicCount = mDynamicShortcutCount; // not adding a dynamic shortcut.
            } else {
                newDynamicCount = mDynamicShortcutCount + 1; // adding a dynamic shortcut.
            }
        }

        // Make sure there's still room.
        s.enforceMaxDynamicShortcuts(newDynamicCount);

        // Okay, make it dynamic and add.
        if (wasPinned) {
            newShortcut.addFlags(ShortcutInfo.FLAG_PINNED);
        }

        addShortcut(s, newShortcut);
        mDynamicShortcutCount = newDynamicCount;
    }

    /**
     * Remove all shortcuts that aren't pinned nor dynamic.
     */
    private void removeOrphans(@NonNull ShortcutService s) {
        ArrayList<String> removeList = null; // Lazily initialize.

        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            final ShortcutInfo si = mShortcuts.valueAt(i);

            if (si.isPinned() || si.isDynamic()) continue;

            if (removeList == null) {
                removeList = new ArrayList<>();
            }
            removeList.add(si.getId());
        }
        if (removeList != null) {
            for (int i = removeList.size() - 1; i >= 0; i--) {
                deleteShortcut(s, removeList.get(i));
            }
        }
    }

    /**
     * Remove all dynamic shortcuts.
     */
    public void deleteAllDynamicShortcuts(@NonNull ShortcutService s) {
        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            mShortcuts.valueAt(i).clearFlags(ShortcutInfo.FLAG_DYNAMIC);
        }
        removeOrphans(s);
        mDynamicShortcutCount = 0;
    }

    /**
     * Remove a dynamic shortcut by ID.
     */
    public void deleteDynamicWithId(@NonNull ShortcutService s, @NonNull String shortcutId) {
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
            deleteShortcut(s, shortcutId);
        }
    }

    /**
     * Called after a launcher updates the pinned set.  For each shortcut in this package,
     * set FLAG_PINNED if any launcher has pinned it.  Otherwise, clear it.
     *
     * <p>Then remove all shortcuts that are not dynamic and no longer pinned either.
     */
    public void refreshPinnedFlags(@NonNull ShortcutService s) {
        // First, un-pin all shortcuts
        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            mShortcuts.valueAt(i).clearFlags(ShortcutInfo.FLAG_PINNED);
        }

        // Then, for the pinned set for each launcher, set the pin flag one by one.
        final ArrayMap<String, ShortcutLauncher> launchers =
                s.getUserShortcutsLocked(mUserId).getLaunchers();

        for (int l = launchers.size() - 1; l >= 0; l--) {
            final ShortcutLauncher launcherShortcuts = launchers.valueAt(l);
            final ArraySet<String> pinned = launcherShortcuts.getPinnedShortcutIds(mPackageName);

            if (pinned == null || pinned.size() == 0) {
                continue;
            }
            for (int i = pinned.size() - 1; i >= 0; i--) {
                final ShortcutInfo si = mShortcuts.get(pinned.valueAt(i));
                if (si == null) {
                    s.wtf("Shortcut not found");
                } else {
                    si.addFlags(ShortcutInfo.FLAG_PINNED);
                }
            }
        }

        // Lastly, remove the ones that are no longer pinned nor dynamic.
        removeOrphans(s);
    }

    /**
     * Number of calls that the caller has made, since the last reset.
     */
    public int getApiCallCount(@NonNull ShortcutService s) {
        final long last = s.getLastResetTimeLocked();

        final long now = s.injectCurrentTimeMillis();
        if (ShortcutService.isClockValid(now) && mLastResetTime > now) {
            Slog.w(TAG, "Clock rewound");
            // Clock rewound.
            mLastResetTime = now;
            mApiCallCount = 0;
            return mApiCallCount;
        }

        // If not reset yet, then reset.
        if (mLastResetTime < last) {
            if (ShortcutService.DEBUG) {
                Slog.d(TAG, String.format("My last reset=%d, now=%d, last=%d: resetting",
                        mLastResetTime, now, last));
            }
            mApiCallCount = 0;
            mLastResetTime = last;
        }
        return mApiCallCount;
    }

    /**
     * If the caller app hasn't been throttled yet, increment {@link #mApiCallCount}
     * and return true.  Otherwise just return false.
     */
    public boolean tryApiCall(@NonNull ShortcutService s) {
        if (getApiCallCount(s) >= s.mMaxDailyUpdates) {
            return false;
        }
        mApiCallCount++;
        return true;
    }

    public void resetRateLimitingForCommandLine() {
        mApiCallCount = 0;
        mLastResetTime = 0;
    }

    /**
     * Find all shortcuts that match {@code query}.
     */
    public void findAll(@NonNull ShortcutService s, @NonNull List<ShortcutInfo> result,
            @Nullable Predicate<ShortcutInfo> query, int cloneFlag,
            @Nullable String callingLauncher) {

        // Set of pinned shortcuts by the calling launcher.
        final ArraySet<String> pinnedByCallerSet = (callingLauncher == null) ? null
                : s.getLauncherShortcuts(callingLauncher, mUserId)
                    .getPinnedShortcutIds(mPackageName);

        for (int i = 0; i < mShortcuts.size(); i++) {
            final ShortcutInfo si = mShortcuts.valueAt(i);

            // If it's called by non-launcher (i.e. publisher, always include -> true.
            // Otherwise, only include non-dynamic pinned one, if the calling launcher has pinned
            // it.
            final boolean isPinnedByCaller = (callingLauncher == null)
                    || ((pinnedByCallerSet != null) && pinnedByCallerSet.contains(si.getId()));
            if (!si.isDynamic()) {
                if (!si.isPinned()) {
                    s.wtf("Shortcut not pinned here");
                    continue;
                }
                if (!isPinnedByCaller) {
                    continue;
                }
            }
            final ShortcutInfo clone = si.clone(cloneFlag);
            // Fix up isPinned for the caller.  Note we need to do it before the "test" callback,
            // since it may check isPinned.
            if (!isPinnedByCaller) {
                clone.clearFlags(ShortcutInfo.FLAG_PINNED);
            }
            if (query == null || query.test(clone)) {
                result.add(clone);
            }
        }
    }

    public void resetThrottling() {
        mApiCallCount = 0;
    }

    public void dump(@NonNull ShortcutService s, @NonNull PrintWriter pw, @NonNull String prefix) {
        pw.println();

        pw.print(prefix);
        pw.print("Package: ");
        pw.print(mPackageName);
        pw.println();

        pw.print(prefix);
        pw.print("  ");
        pw.print("Calls: ");
        pw.print(getApiCallCount(s));
        pw.println();

        // This should be after getApiCallCount(), which may update it.
        pw.print(prefix);
        pw.print("  ");
        pw.print("Last reset: [");
        pw.print(mLastResetTime);
        pw.print("] ");
        pw.print(s.formatTime(mLastResetTime));
        pw.println();

        pw.println("      Shortcuts:");
        long totalBitmapSize = 0;
        final ArrayMap<String, ShortcutInfo> shortcuts = mShortcuts;
        final int size = shortcuts.size();
        for (int i = 0; i < size; i++) {
            final ShortcutInfo si = shortcuts.valueAt(i);
            pw.print("        ");
            pw.println(si.toInsecureString());
            if (si.getBitmapPath() != null) {
                final long len = new File(si.getBitmapPath()).length();
                pw.print("          ");
                pw.print("bitmap size=");
                pw.println(len);

                totalBitmapSize += len;
            }
        }
        pw.print(prefix);
        pw.print("  ");
        pw.print("Total bitmap size: ");
        pw.print(totalBitmapSize);
        pw.print(" (");
        pw.print(Formatter.formatFileSize(s.mContext, totalBitmapSize));
        pw.println(")");
    }

    public void saveToXml(@NonNull XmlSerializer out) throws IOException, XmlPullParserException {
        final int size = mShortcuts.size();

        if (size == 0 && mApiCallCount == 0) {
            return; // nothing to write.
        }

        out.startTag(null, TAG_ROOT);

        ShortcutService.writeAttr(out, ATTR_NAME, mPackageName);
        ShortcutService.writeAttr(out, ATTR_DYNAMIC_COUNT, mDynamicShortcutCount);
        ShortcutService.writeAttr(out, ATTR_CALL_COUNT, mApiCallCount);
        ShortcutService.writeAttr(out, ATTR_LAST_RESET, mLastResetTime);

        for (int j = 0; j < size; j++) {
            saveShortcut(out, mShortcuts.valueAt(j));
        }

        out.endTag(null, TAG_ROOT);
    }

    private static void saveShortcut(XmlSerializer out, ShortcutInfo si)
            throws IOException, XmlPullParserException {
        out.startTag(null, TAG_SHORTCUT);
        ShortcutService.writeAttr(out, ATTR_ID, si.getId());
        // writeAttr(out, "package", si.getPackageName()); // not needed
        ShortcutService.writeAttr(out, ATTR_ACTIVITY, si.getActivityComponent());
        // writeAttr(out, "icon", si.getIcon());  // We don't save it.
        ShortcutService.writeAttr(out, ATTR_TITLE, si.getTitle());
        ShortcutService.writeAttr(out, ATTR_INTENT, si.getIntentNoExtras());
        ShortcutService.writeAttr(out, ATTR_WEIGHT, si.getWeight());
        ShortcutService.writeAttr(out, ATTR_TIMESTAMP,
                si.getLastChangedTimestamp());
        ShortcutService.writeAttr(out, ATTR_FLAGS, si.getFlags());
        ShortcutService.writeAttr(out, ATTR_ICON_RES, si.getIconResourceId());
        ShortcutService.writeAttr(out, ATTR_BITMAP_PATH, si.getBitmapPath());

        ShortcutService.writeTagExtra(out, TAG_INTENT_EXTRAS,
                si.getIntentPersistableExtras());
        ShortcutService.writeTagExtra(out, TAG_EXTRAS, si.getExtras());

        out.endTag(null, TAG_SHORTCUT);
    }

    public static ShortcutPackage loadFromXml(XmlPullParser parser, int userId)
            throws IOException, XmlPullParserException {

        final String packageName = ShortcutService.parseStringAttribute(parser,
                ATTR_NAME);

        final ShortcutPackage ret = new ShortcutPackage(userId, packageName);

        ret.mDynamicShortcutCount =
                ShortcutService.parseIntAttribute(parser, ATTR_DYNAMIC_COUNT);
        ret.mApiCallCount =
                ShortcutService.parseIntAttribute(parser, ATTR_CALL_COUNT);
        ret.mLastResetTime =
                ShortcutService.parseLongAttribute(parser, ATTR_LAST_RESET);

        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            final int depth = parser.getDepth();
            final String tag = parser.getName();
            switch (tag) {
                case TAG_SHORTCUT:
                    final ShortcutInfo si = parseShortcut(parser, packageName);

                    // Don't use addShortcut(), we don't need to save the icon.
                    ret.mShortcuts.put(si.getId(), si);
                    continue;
            }
            throw ShortcutService.throwForInvalidTag(depth, tag);
        }
        return ret;
    }

    private static ShortcutInfo parseShortcut(XmlPullParser parser, String packageName)
            throws IOException, XmlPullParserException {
        String id;
        ComponentName activityComponent;
        // Icon icon;
        String title;
        Intent intent;
        PersistableBundle intentPersistableExtras = null;
        int weight;
        PersistableBundle extras = null;
        long lastChangedTimestamp;
        int flags;
        int iconRes;
        String bitmapPath;

        id = ShortcutService.parseStringAttribute(parser, ATTR_ID);
        activityComponent = ShortcutService.parseComponentNameAttribute(parser,
                ATTR_ACTIVITY);
        title = ShortcutService.parseStringAttribute(parser, ATTR_TITLE);
        intent = ShortcutService.parseIntentAttribute(parser, ATTR_INTENT);
        weight = (int) ShortcutService.parseLongAttribute(parser, ATTR_WEIGHT);
        lastChangedTimestamp = (int) ShortcutService.parseLongAttribute(parser,
                ATTR_TIMESTAMP);
        flags = (int) ShortcutService.parseLongAttribute(parser, ATTR_FLAGS);
        iconRes = (int) ShortcutService.parseLongAttribute(parser, ATTR_ICON_RES);
        bitmapPath = ShortcutService.parseStringAttribute(parser, ATTR_BITMAP_PATH);

        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            final int depth = parser.getDepth();
            final String tag = parser.getName();
            if (ShortcutService.DEBUG_LOAD) {
                Slog.d(TAG, String.format("  depth=%d type=%d name=%s",
                        depth, type, tag));
            }
            switch (tag) {
                case TAG_INTENT_EXTRAS:
                    intentPersistableExtras = PersistableBundle.restoreFromXml(parser);
                    continue;
                case TAG_EXTRAS:
                    extras = PersistableBundle.restoreFromXml(parser);
                    continue;
            }
            throw ShortcutService.throwForInvalidTag(depth, tag);
        }
        return new ShortcutInfo(
                id, packageName, activityComponent, /* icon =*/ null, title, intent,
                intentPersistableExtras, weight, extras, lastChangedTimestamp, flags,
                iconRes, bitmapPath);
    }
}
