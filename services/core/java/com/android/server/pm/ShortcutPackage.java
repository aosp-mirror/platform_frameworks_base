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

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Package information used by {@link ShortcutService}.
 */
class ShortcutPackage extends ShortcutPackageItem {
    private static final String TAG = ShortcutService.TAG;

    static final String TAG_ROOT = "package";
    private static final String TAG_INTENT_EXTRAS = "intent-extras";
    private static final String TAG_EXTRAS = "extras";
    private static final String TAG_SHORTCUT = "shortcut";
    private static final String TAG_CATEGORIES = "categories";

    private static final String ATTR_NAME = "name";
    private static final String ATTR_DYNAMIC_COUNT = "dynamic-count";
    private static final String ATTR_CALL_COUNT = "call-count";
    private static final String ATTR_LAST_RESET = "last-reset";
    private static final String ATTR_ID = "id";
    private static final String ATTR_ACTIVITY = "activity";
    private static final String ATTR_TITLE = "title";
    private static final String ATTR_TEXT = "text";
    private static final String ATTR_INTENT = "intent";
    private static final String ATTR_WEIGHT = "weight";
    private static final String ATTR_TIMESTAMP = "timestamp";
    private static final String ATTR_FLAGS = "flags";
    private static final String ATTR_ICON_RES = "icon-res";
    private static final String ATTR_BITMAP_PATH = "bitmap-path";

    private static final String NAME_CATEGORIES = "categories";

    private static final String TAG_STRING_ARRAY_XMLUTILS = "string-array";
    private static final String ATTR_NAME_XMLUTILS = "name";

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

    private final int mPackageUid;

    private long mLastKnownForegroundElapsedTime;

    private ShortcutPackage(ShortcutService s, ShortcutUser shortcutUser,
            int packageUserId, String packageName, ShortcutPackageInfo spi) {
        super(shortcutUser, packageUserId, packageName,
                spi != null ? spi : ShortcutPackageInfo.newEmpty());

        mPackageUid = s.injectGetPackageUid(packageName, packageUserId);
    }

    public ShortcutPackage(ShortcutService s, ShortcutUser shortcutUser,
            int packageUserId, String packageName) {
        this(s, shortcutUser, packageUserId, packageName, null);
    }

    @Override
    public int getOwnerUserId() {
        // For packages, always owner user == package user.
        return getPackageUserId();
    }

    public int getPackageUid() {
        return mPackageUid;
    }

    /**
     * Called when a shortcut is about to be published.  At this point we know the publisher package
     * exists (as opposed to Launcher trying to fetch shortcuts from a non-existent package), so
     * we do some initialization for the package.
     */
    private void onShortcutPublish(ShortcutService s) {
        // Make sure we have the version code for the app.  We need the version code in
        // handlePackageUpdated().
        if (getPackageInfo().getVersionCode() < 0) {
            final int versionCode = s.getApplicationVersionCode(getPackageName(), getOwnerUserId());
            if (ShortcutService.DEBUG) {
                Slog.d(TAG, String.format("Package %s version = %d", getPackageName(),
                        versionCode));
            }
            if (versionCode >= 0) {
                getPackageInfo().setVersionCode(versionCode);
                s.scheduleSaveUser(getOwnerUserId());
            }
        }
    }

    @Override
    protected void onRestoreBlocked(ShortcutService s) {
        // Can't restore due to version/signature mismatch.  Remove all shortcuts.
        mShortcuts.clear();
    }

    @Override
    protected void onRestored(ShortcutService s) {
        // Because some launchers may not have been restored (e.g. allowBackup=false),
        // we need to re-calculate the pinned shortcuts.
        refreshPinnedFlags(s);
    }

    /**
     * Note this does *not* provide a correct view to the calling launcher.
     */
    @Nullable
    public ShortcutInfo findShortcutById(String id) {
        return mShortcuts.get(id);
    }

    private ShortcutInfo deleteShortcut(@NonNull ShortcutService s,
            @NonNull String id) {
        final ShortcutInfo shortcut = mShortcuts.remove(id);
        if (shortcut != null) {
            s.removeIcon(getPackageUserId(), shortcut);
            shortcut.clearFlags(ShortcutInfo.FLAG_DYNAMIC | ShortcutInfo.FLAG_PINNED);
        }
        return shortcut;
    }

    void addShortcut(@NonNull ShortcutService s, @NonNull ShortcutInfo newShortcut) {
        deleteShortcut(s, newShortcut.getId());
        s.saveIconAndFixUpShortcut(getPackageUserId(), newShortcut);
        mShortcuts.put(newShortcut.getId(), newShortcut);
    }

    /**
     * Add a shortcut, or update one with the same ID, with taking over existing flags.
     *
     * It checks the max number of dynamic shortcuts.
     */
    public void addDynamicShortcut(@NonNull ShortcutService s,
            @NonNull ShortcutInfo newShortcut) {

        onShortcutPublish(s);

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
        s.getUserShortcutsLocked(getPackageUserId()).forAllLaunchers(launcherShortcuts -> {
            final ArraySet<String> pinned = launcherShortcuts.getPinnedShortcutIds(
                    getPackageName(), getPackageUserId());

            if (pinned == null || pinned.size() == 0) {
                return;
            }
            for (int i = pinned.size() - 1; i >= 0; i--) {
                final String id = pinned.valueAt(i);
                final ShortcutInfo si = mShortcuts.get(id);
                if (si == null) {
                    // This happens if a launcher pinned shortcuts from this package, then backup&
                    // restored, but this package doesn't allow backing up.
                    // In that case the launcher ends up having a dangling pinned shortcuts.
                    // That's fine, when the launcher is restored, we'll fix it.
                    continue;
                }
                si.addFlags(ShortcutInfo.FLAG_PINNED);
            }
        });

        // Lastly, remove the ones that are no longer pinned nor dynamic.
        removeOrphans(s);
    }

    /**
     * Number of calls that the caller has made, since the last reset.
     *
     * <p>This takes care of the resetting the counter for foreground apps as well as after
     * locale changes.
     */
    public int getApiCallCount(@NonNull ShortcutService s) {
        mShortcutUser.resetThrottlingIfNeeded(s);

        // Reset the counter if:
        // - the package is in foreground now.
        // - the package is *not* in foreground now, but was in foreground at some point
        // since the previous time it had been.
        if (s.isUidForegroundLocked(mPackageUid)
                || mLastKnownForegroundElapsedTime
                    < s.getUidLastForegroundElapsedTimeLocked(mPackageUid)) {
            mLastKnownForegroundElapsedTime = s.injectElapsedRealtime();
            resetRateLimiting(s);
        }

        // Note resetThrottlingIfNeeded() and resetRateLimiting() will set 0 to mApiCallCount,
        // but we just can't return 0 at this point, because we may have to update
        // mLastResetTime.

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
     *
     * <p>This takes care of the resetting the counter for foreground apps as well as after
     * locale changes, which is done internally by {@link #getApiCallCount}.
     */
    public boolean tryApiCall(@NonNull ShortcutService s) {
        if (getApiCallCount(s) >= s.mMaxUpdatesPerInterval) {
            return false;
        }
        mApiCallCount++;
        s.scheduleSaveUser(getOwnerUserId());
        return true;
    }

    public void resetRateLimiting(@NonNull ShortcutService s) {
        if (ShortcutService.DEBUG) {
            Slog.d(TAG, "resetRateLimiting: " + getPackageName());
        }
        if (mApiCallCount > 0) {
            mApiCallCount = 0;
            s.scheduleSaveUser(getOwnerUserId());
        }
    }

    public void resetRateLimitingForCommandLineNoSaving() {
        mApiCallCount = 0;
        mLastResetTime = 0;
    }

    /**
     * Find all shortcuts that match {@code query}.
     */
    public void findAll(@NonNull ShortcutService s, @NonNull List<ShortcutInfo> result,
            @Nullable Predicate<ShortcutInfo> query, int cloneFlag) {
        findAll(s, result, query, cloneFlag, null, 0);
    }

    /**
     * Find all shortcuts that match {@code query}.
     *
     * This will also provide a "view" for each launcher -- a non-dynamic shortcut that's not pinned
     * by the calling launcher will not be included in the result, and also "isPinned" will be
     * adjusted for the caller too.
     */
    public void findAll(@NonNull ShortcutService s, @NonNull List<ShortcutInfo> result,
            @Nullable Predicate<ShortcutInfo> query, int cloneFlag,
            @Nullable String callingLauncher, int launcherUserId) {
        if (getPackageInfo().isShadow()) {
            // Restored and the app not installed yet, so don't return any.
            return;
        }

        // Set of pinned shortcuts by the calling launcher.
        final ArraySet<String> pinnedByCallerSet = (callingLauncher == null) ? null
                : s.getLauncherShortcutsLocked(callingLauncher, getPackageUserId(), launcherUserId)
                    .getPinnedShortcutIds(getPackageName(), getPackageUserId());

        for (int i = 0; i < mShortcuts.size(); i++) {
            final ShortcutInfo si = mShortcuts.valueAt(i);

            // If it's called by non-launcher (i.e. publisher, always include -> true.
            // Otherwise, only include non-dynamic pinned one, if the calling launcher has pinned
            // it.
            final boolean isPinnedByCaller = (callingLauncher == null)
                    || ((pinnedByCallerSet != null) && pinnedByCallerSet.contains(si.getId()));
            if (!si.isDynamic()) {
                if (!si.isPinned()) {
                    s.wtf("Shortcut not pinned: package " + getPackageName()
                            + ", user=" + getPackageUserId() + ", id=" + si.getId());
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

    /**
     * Called when the package is updated.  If there are shortcuts with resource icons, update
     * their timestamps.
     */
    public void handlePackageUpdated(ShortcutService s, int newVersionCode) {
        if (getPackageInfo().getVersionCode() >= newVersionCode) {
            // Version hasn't changed; nothing to do.
            return;
        }
        if (ShortcutService.DEBUG) {
            Slog.d(TAG, String.format("Package %s updated, version %d -> %d", getPackageName(),
                    getPackageInfo().getVersionCode(), newVersionCode));
        }

        getPackageInfo().setVersionCode(newVersionCode);

        boolean changed = false;
        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            final ShortcutInfo si = mShortcuts.valueAt(i);

            if (si.hasIconResource()) {
                changed = true;
                si.setTimestamp(s.injectCurrentTimeMillis());
            }
        }
        if (changed) {
            // This will send a notification to the launcher, and also save .
            s.packageShortcutsChanged(getPackageName(), getPackageUserId());
        } else {
            // Still save the version code.
            s.scheduleSaveUser(getPackageUserId());
        }
    }

    public void dump(@NonNull ShortcutService s, @NonNull PrintWriter pw, @NonNull String prefix) {
        pw.println();

        pw.print(prefix);
        pw.print("Package: ");
        pw.print(getPackageName());
        pw.print("  UID: ");
        pw.print(mPackageUid);
        pw.println();

        pw.print(prefix);
        pw.print("  ");
        pw.print("Calls: ");
        pw.print(getApiCallCount(s));
        pw.println();

        // getApiCallCount() may have updated mLastKnownForegroundElapsedTime.
        pw.print(prefix);
        pw.print("  ");
        pw.print("Last known FG: ");
        pw.print(mLastKnownForegroundElapsedTime);
        pw.println();

        // This should be after getApiCallCount(), which may update it.
        pw.print(prefix);
        pw.print("  ");
        pw.print("Last reset: [");
        pw.print(mLastResetTime);
        pw.print("] ");
        pw.print(s.formatTime(mLastResetTime));
        pw.println();

        getPackageInfo().dump(s, pw, prefix + "  ");
        pw.println();

        pw.print(prefix);
        pw.println("  Shortcuts:");
        long totalBitmapSize = 0;
        final ArrayMap<String, ShortcutInfo> shortcuts = mShortcuts;
        final int size = shortcuts.size();
        for (int i = 0; i < size; i++) {
            final ShortcutInfo si = shortcuts.valueAt(i);
            pw.print(prefix);
            pw.print("    ");
            pw.println(si.toInsecureString());
            if (si.getBitmapPath() != null) {
                final long len = new File(si.getBitmapPath()).length();
                pw.print(prefix);
                pw.print("      ");
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

    @Override
    public void saveToXml(@NonNull XmlSerializer out, boolean forBackup)
            throws IOException, XmlPullParserException {
        final int size = mShortcuts.size();

        if (size == 0 && mApiCallCount == 0) {
            return; // nothing to write.
        }

        out.startTag(null, TAG_ROOT);

        ShortcutService.writeAttr(out, ATTR_NAME, getPackageName());
        ShortcutService.writeAttr(out, ATTR_DYNAMIC_COUNT, mDynamicShortcutCount);
        ShortcutService.writeAttr(out, ATTR_CALL_COUNT, mApiCallCount);
        ShortcutService.writeAttr(out, ATTR_LAST_RESET, mLastResetTime);
        getPackageInfo().saveToXml(out);

        for (int j = 0; j < size; j++) {
            saveShortcut(out, mShortcuts.valueAt(j), forBackup);
        }

        out.endTag(null, TAG_ROOT);
    }

    private static void saveShortcut(XmlSerializer out, ShortcutInfo si, boolean forBackup)
            throws IOException, XmlPullParserException {
        if (forBackup) {
            if (!si.isPinned()) {
                return; // Backup only pinned icons.
            }
        }
        out.startTag(null, TAG_SHORTCUT);
        ShortcutService.writeAttr(out, ATTR_ID, si.getId());
        // writeAttr(out, "package", si.getPackageName()); // not needed
        ShortcutService.writeAttr(out, ATTR_ACTIVITY, si.getActivityComponent());
        // writeAttr(out, "icon", si.getIcon());  // We don't save it.
        ShortcutService.writeAttr(out, ATTR_TITLE, si.getTitle());
        ShortcutService.writeAttr(out, ATTR_TEXT, si.getText());
        ShortcutService.writeAttr(out, ATTR_INTENT, si.getIntentNoExtras());
        ShortcutService.writeAttr(out, ATTR_WEIGHT, si.getWeight());
        ShortcutService.writeAttr(out, ATTR_TIMESTAMP,
                si.getLastChangedTimestamp());
        if (forBackup) {
            // Don't write icon information.  Also drop the dynamic flag.
            ShortcutService.writeAttr(out, ATTR_FLAGS,
                    si.getFlags() &
                            ~(ShortcutInfo.FLAG_HAS_ICON_FILE | ShortcutInfo.FLAG_HAS_ICON_RES
                            | ShortcutInfo.FLAG_DYNAMIC));
        } else {
            ShortcutService.writeAttr(out, ATTR_FLAGS, si.getFlags());
            ShortcutService.writeAttr(out, ATTR_ICON_RES, si.getIconResourceId());
            ShortcutService.writeAttr(out, ATTR_BITMAP_PATH, si.getBitmapPath());
        }

        {
            final Set<String> cat = si.getCategories();
            if (cat != null && cat.size() > 0) {
                out.startTag(null, TAG_CATEGORIES);
                XmlUtils.writeStringArrayXml(cat.toArray(new String[cat.size()]),
                        NAME_CATEGORIES, out);
                out.endTag(null, TAG_CATEGORIES);
            }
        }

        ShortcutService.writeTagExtra(out, TAG_INTENT_EXTRAS,
                si.getIntentPersistableExtras());
        ShortcutService.writeTagExtra(out, TAG_EXTRAS, si.getExtras());

        out.endTag(null, TAG_SHORTCUT);
    }

    public static ShortcutPackage loadFromXml(ShortcutService s, ShortcutUser shortcutUser,
            XmlPullParser parser, boolean fromBackup)
            throws IOException, XmlPullParserException {

        final String packageName = ShortcutService.parseStringAttribute(parser,
                ATTR_NAME);

        final ShortcutPackage ret = new ShortcutPackage(s, shortcutUser,
                shortcutUser.getUserId(), packageName);

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
            if (depth == outerDepth + 1) {
                switch (tag) {
                    case ShortcutPackageInfo.TAG_ROOT:
                        ret.getPackageInfo().loadFromXml(parser, fromBackup);
                        continue;
                    case TAG_SHORTCUT:
                        final ShortcutInfo si = parseShortcut(parser, packageName,
                                shortcutUser.getUserId());

                        // Don't use addShortcut(), we don't need to save the icon.
                        ret.mShortcuts.put(si.getId(), si);
                        continue;
                }
            }
            ShortcutService.warnForInvalidTag(depth, tag);
        }
        return ret;
    }

    private static ShortcutInfo parseShortcut(XmlPullParser parser, String packageName,
            @UserIdInt int userId) throws IOException, XmlPullParserException {
        String id;
        ComponentName activityComponent;
        // Icon icon;
        String title;
        String text;
        Intent intent;
        PersistableBundle intentPersistableExtras = null;
        int weight;
        PersistableBundle extras = null;
        long lastChangedTimestamp;
        int flags;
        int iconRes;
        String bitmapPath;
        ArraySet<String> categories = null;

        id = ShortcutService.parseStringAttribute(parser, ATTR_ID);
        activityComponent = ShortcutService.parseComponentNameAttribute(parser,
                ATTR_ACTIVITY);
        title = ShortcutService.parseStringAttribute(parser, ATTR_TITLE);
        text = ShortcutService.parseStringAttribute(parser, ATTR_TEXT);
        intent = ShortcutService.parseIntentAttribute(parser, ATTR_INTENT);
        weight = (int) ShortcutService.parseLongAttribute(parser, ATTR_WEIGHT);
        lastChangedTimestamp = ShortcutService.parseLongAttribute(parser, ATTR_TIMESTAMP);
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
                case TAG_CATEGORIES:
                    // This just contains string-array.
                    continue;
                case TAG_STRING_ARRAY_XMLUTILS:
                    if (NAME_CATEGORIES.equals(ShortcutService.parseStringAttribute(parser,
                            ATTR_NAME_XMLUTILS))) {
                        final String[] ar = XmlUtils.readThisStringArrayXml(
                                parser, TAG_STRING_ARRAY_XMLUTILS, null);
                        categories = new ArraySet<>(ar.length);
                        for (int i = 0; i < ar.length; i++) {
                            categories.add(ar[i]);
                        }
                    }
                    continue;
            }
            throw ShortcutService.throwForInvalidTag(depth, tag);
        }

        return new ShortcutInfo(
                userId, id, packageName, activityComponent, /* icon =*/ null, title, text,
                categories, intent,
                intentPersistableExtras, weight, extras, lastChangedTimestamp, flags,
                iconRes, bitmapPath);
    }

    @VisibleForTesting
    List<ShortcutInfo> getAllShortcutsForTest() {
        return new ArrayList<>(mShortcuts.values());
    }
}
