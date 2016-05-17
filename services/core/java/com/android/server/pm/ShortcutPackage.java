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
import android.content.pm.PackageInfo;
import android.content.pm.ShortcutInfo;
import android.os.PersistableBundle;
import android.text.format.Formatter;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
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
 * User information used by {@link ShortcutService}.
 *
 * All methods should be guarded by {@code #mShortcutUser.mService.mLock}.
 *
 * TODO Max dynamic shortcuts cap should be per activity.
 */
class ShortcutPackage extends ShortcutPackageItem {
    private static final String TAG = ShortcutService.TAG;

    static final String TAG_ROOT = "package";
    private static final String TAG_INTENT_EXTRAS = "intent-extras";
    private static final String TAG_EXTRAS = "extras";
    private static final String TAG_SHORTCUT = "shortcut";
    private static final String TAG_CATEGORIES = "categories";

    private static final String ATTR_NAME = "name";
    private static final String ATTR_CALL_COUNT = "call-count";
    private static final String ATTR_LAST_RESET = "last-reset";
    private static final String ATTR_ID = "id";
    private static final String ATTR_ACTIVITY = "activity";
    private static final String ATTR_TITLE = "title";
    private static final String ATTR_TITLE_RES_ID = "titleid";
    private static final String ATTR_TEXT = "text";
    private static final String ATTR_TEXT_RES_ID = "textid";
    private static final String ATTR_DISABLED_MESSAGE = "dmessage";
    private static final String ATTR_DISABLED_MESSAGE_RES_ID = "dmessageid";
    private static final String ATTR_INTENT = "intent";
    private static final String ATTR_RANK = "rank";
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
     * # of times the package has called rate-limited APIs.
     */
    private int mApiCallCount;

    /**
     * When {@link #mApiCallCount} was reset last time.
     */
    private long mLastResetTime;

    private final int mPackageUid;

    private long mLastKnownForegroundElapsedTime;

    private ShortcutPackage(ShortcutUser shortcutUser,
            int packageUserId, String packageName, ShortcutPackageInfo spi) {
        super(shortcutUser, packageUserId, packageName,
                spi != null ? spi : ShortcutPackageInfo.newEmpty());

        mPackageUid = shortcutUser.mService.injectGetPackageUid(packageName, packageUserId);
    }

    public ShortcutPackage(ShortcutUser shortcutUser, int packageUserId, String packageName) {
        this(shortcutUser, packageUserId, packageName, null);
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
    private void ensurePackageVersionInfo() {
        // Make sure we have the version code for the app.  We need the version code in
        // handlePackageUpdated().
        if (getPackageInfo().getVersionCode() < 0) {
            final ShortcutService s = mShortcutUser.mService;

            final PackageInfo pi = s.getPackageInfo(getPackageName(), getOwnerUserId());
            if (pi != null) {
                if (ShortcutService.DEBUG) {
                    Slog.d(TAG, String.format("Package %s version = %d", getPackageName(),
                            pi.versionCode));
                }
                getPackageInfo().updateVersionInfo(pi);
                s.scheduleSaveUser(getOwnerUserId());
            }
        }
    }

    @Override
    protected void onRestoreBlocked() {
        // Can't restore due to version/signature mismatch.  Remove all shortcuts.
        mShortcuts.clear();
    }

    @Override
    protected void onRestored() {
        // Because some launchers may not have been restored (e.g. allowBackup=false),
        // we need to re-calculate the pinned shortcuts.
        refreshPinnedFlags();
    }

    /**
     * Note this does *not* provide a correct view to the calling launcher.
     */
    @Nullable
    public ShortcutInfo findShortcutById(String id) {
        return mShortcuts.get(id);
    }

    private void ensureNotImmutable(@Nullable ShortcutInfo shortcut) {
        if (shortcut != null && shortcut.isImmutable()) {
            throw new IllegalArgumentException(
                    "Manifest shortcut ID=" + shortcut.getId()
                            + " may not be manipulated via APIs");
        }
    }

    private void ensureNotImmutable(@NonNull String id) {
        ensureNotImmutable(mShortcuts.get(id));
    }

    public void ensureImmutableShortcutsNotIncludedWithIds(@NonNull List<String> shortcutIds) {
        for (int i = shortcutIds.size() - 1; i >= 0; i--) {
            ensureNotImmutable(shortcutIds.get(i));
        }
    }

    public void ensureImmutableShortcutsNotIncluded(@NonNull List<ShortcutInfo> shortcuts) {
        for (int i = shortcuts.size() - 1; i >= 0; i--) {
            ensureNotImmutable(shortcuts.get(i).getId());
        }
    }

    private ShortcutInfo deleteShortcutInner(@NonNull String id) {
        final ShortcutInfo shortcut = mShortcuts.remove(id);
        if (shortcut != null) {
            mShortcutUser.mService.removeIcon(getPackageUserId(), shortcut);
            shortcut.clearFlags(ShortcutInfo.FLAG_DYNAMIC | ShortcutInfo.FLAG_PINNED
                    | ShortcutInfo.FLAG_MANIFEST);
        }
        return shortcut;
    }

    private void addShortcutInner(@NonNull ShortcutInfo newShortcut) {
        deleteShortcutInner(newShortcut.getId());
        mShortcutUser.mService.saveIconAndFixUpShortcut(getPackageUserId(), newShortcut);
        mShortcuts.put(newShortcut.getId(), newShortcut);
    }

    /**
     * Add a shortcut, or update one with the same ID, with taking over existing flags.
     *
     * It checks the max number of dynamic shortcuts.
     */
    public void addOrUpdateDynamicShortcut(@NonNull ShortcutInfo newShortcut) {

        Preconditions.checkArgument(newShortcut.isEnabled(),
                "add/setDynamicShortcuts() cannot publish disabled shortcuts");

        ensurePackageVersionInfo();

        newShortcut.addFlags(ShortcutInfo.FLAG_DYNAMIC);

        final ShortcutInfo oldShortcut = mShortcuts.get(newShortcut.getId());

        final boolean wasPinned;

        if (oldShortcut == null) {
            wasPinned = false;
        } else {
            // It's an update case.
            // Make sure the target is updatable. (i.e. should be mutable.)
            oldShortcut.ensureUpdatableWith(newShortcut);

            wasPinned = oldShortcut.isPinned();
            if (!oldShortcut.isEnabled()) {
                newShortcut.addFlags(ShortcutInfo.FLAG_DISABLED);
            }
        }

        // TODO Check max dynamic count.
        // mShortcutUser.mService.enforceMaxDynamicShortcuts(newDynamicCount);

        // Okay, make it dynamic and add.
        if (wasPinned) {
            newShortcut.addFlags(ShortcutInfo.FLAG_PINNED);
        }

        addShortcutInner(newShortcut);
    }

    /**
     * Remove all shortcuts that aren't pinned nor dynamic.
     */
    private void removeOrphans() {
        ArrayList<String> removeList = null; // Lazily initialize.

        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            final ShortcutInfo si = mShortcuts.valueAt(i);

            if (si.isAlive()) continue;

            if (removeList == null) {
                removeList = new ArrayList<>();
            }
            removeList.add(si.getId());
        }
        if (removeList != null) {
            for (int i = removeList.size() - 1; i >= 0; i--) {
                deleteShortcutInner(removeList.get(i));
            }
        }
    }

    /**
     * Remove all dynamic shortcuts.
     */
    public void deleteAllDynamicShortcuts() {
        boolean changed = false;
        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            final ShortcutInfo si = mShortcuts.valueAt(i);
            if (si.isDynamic()) {
                changed = true;
                si.clearFlags(ShortcutInfo.FLAG_DYNAMIC);
            }
        }
        if (changed) {
            removeOrphans();
        }
    }

    /**
     * Remove a dynamic shortcut by ID.
     */
    public void deleteDynamicWithId(@NonNull String shortcutId) {
        deleteOrDisableWithId(shortcutId, /* disable =*/ false, /* overrideImmutable=*/ false);
    }

    public void disableWithId(@NonNull String shortcutId, String disabledMessage,
            int disabledMessageResId, boolean overrideImmutable) {
        final ShortcutInfo disabled = deleteOrDisableWithId(shortcutId, /* disable =*/ true,
                overrideImmutable);

        if (disabled != null) {
            if (disabledMessage != null) {
                disabled.setDisabledMessage(disabledMessage);
            } else if (disabledMessageResId != 0) {
                disabled.setDisabledMessageResId(disabledMessageResId);
            }
        }
    }

    @Nullable
    private ShortcutInfo deleteOrDisableWithId(@NonNull String shortcutId, boolean disable,
            boolean overrideImmutable) {
        final ShortcutInfo oldShortcut = mShortcuts.get(shortcutId);

        if (oldShortcut == null || !oldShortcut.isEnabled()) {
            return null; // Doesn't exist or already disabled.
        }
        if (!overrideImmutable) {
            ensureNotImmutable(oldShortcut);
        }
        if (oldShortcut.isPinned()) {
            oldShortcut.clearFlags(ShortcutInfo.FLAG_DYNAMIC | ShortcutInfo.FLAG_MANIFEST);
            if (disable) {
                oldShortcut.addFlags(ShortcutInfo.FLAG_DISABLED);
            }
            return oldShortcut;
        } else {
            deleteShortcutInner(shortcutId);
            return null;
        }
    }

    public void enableWithId(@NonNull String shortcutId) {
        final ShortcutInfo shortcut = mShortcuts.get(shortcutId);
        if (shortcut != null) {
            ensureNotImmutable(shortcut);
            shortcut.clearFlags(ShortcutInfo.FLAG_DISABLED);
        }
    }

    /**
     * Called after a launcher updates the pinned set.  For each shortcut in this package,
     * set FLAG_PINNED if any launcher has pinned it.  Otherwise, clear it.
     *
     * <p>Then remove all shortcuts that are not dynamic and no longer pinned either.
     */
    public void refreshPinnedFlags() {
        // First, un-pin all shortcuts
        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            mShortcuts.valueAt(i).clearFlags(ShortcutInfo.FLAG_PINNED);
        }

        // Then, for the pinned set for each launcher, set the pin flag one by one.
        mShortcutUser.mService.getUserShortcutsLocked(getPackageUserId())
                .forAllLaunchers(launcherShortcuts -> {
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
        removeOrphans();
    }

    /**
     * Number of calls that the caller has made, since the last reset.
     *
     * <p>This takes care of the resetting the counter for foreground apps as well as after
     * locale changes.
     */
    public int getApiCallCount() {
        mShortcutUser.resetThrottlingIfNeeded();

        final ShortcutService s = mShortcutUser.mService;

        // Reset the counter if:
        // - the package is in foreground now.
        // - the package is *not* in foreground now, but was in foreground at some point
        // since the previous time it had been.
        if (s.isUidForegroundLocked(mPackageUid)
                || mLastKnownForegroundElapsedTime
                    < s.getUidLastForegroundElapsedTimeLocked(mPackageUid)) {
            mLastKnownForegroundElapsedTime = s.injectElapsedRealtime();
            resetRateLimiting();
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
                Slog.d(TAG, String.format("%s: last reset=%d, now=%d, last=%d: resetting",
                        getPackageName(), mLastResetTime, now, last));
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
    public boolean tryApiCall() {
        final ShortcutService s = mShortcutUser.mService;

        if (getApiCallCount() >= s.mMaxUpdatesPerInterval) {
            return false;
        }
        mApiCallCount++;
        s.scheduleSaveUser(getOwnerUserId());
        return true;
    }

    public void resetRateLimiting() {
        if (ShortcutService.DEBUG) {
            Slog.d(TAG, "resetRateLimiting: " + getPackageName());
        }
        if (mApiCallCount > 0) {
            mApiCallCount = 0;
            mShortcutUser.mService.scheduleSaveUser(getOwnerUserId());
        }
    }

    public void resetRateLimitingForCommandLineNoSaving() {
        mApiCallCount = 0;
        mLastResetTime = 0;
    }

    /**
     * Find all shortcuts that match {@code query}.
     */
    public void findAll(@NonNull List<ShortcutInfo> result,
            @Nullable Predicate<ShortcutInfo> query, int cloneFlag) {
        findAll(result, query, cloneFlag, null, 0);
    }

    /**
     * Find all shortcuts that match {@code query}.
     *
     * This will also provide a "view" for each launcher -- a non-dynamic shortcut that's not pinned
     * by the calling launcher will not be included in the result, and also "isPinned" will be
     * adjusted for the caller too.
     */
    public void findAll(@NonNull List<ShortcutInfo> result,
            @Nullable Predicate<ShortcutInfo> query, int cloneFlag,
            @Nullable String callingLauncher, int launcherUserId) {
        if (getPackageInfo().isShadow()) {
            // Restored and the app not installed yet, so don't return any.
            return;
        }

        final ShortcutService s = mShortcutUser.mService;

        // Set of pinned shortcuts by the calling launcher.
        final ArraySet<String> pinnedByCallerSet = (callingLauncher == null) ? null
                : s.getLauncherShortcutsLocked(callingLauncher, getPackageUserId(), launcherUserId)
                    .getPinnedShortcutIds(getPackageName(), getPackageUserId());

        for (int i = 0; i < mShortcuts.size(); i++) {
            final ShortcutInfo si = mShortcuts.valueAt(i);

            // Need to adjust PINNED flag depending on the caller.
            // Basically if the caller is a launcher (callingLauncher != null) and the launcher
            // isn't pinning it, then we need to clear PINNED for this caller.
            final boolean isPinnedByCaller = (callingLauncher == null)
                    || ((pinnedByCallerSet != null) && pinnedByCallerSet.contains(si.getId()));

            if (si.isFloating()) {
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
     * Return the filenames (excluding path names) of icon bitmap files from this package.
     */
    public ArraySet<String> getUsedBitmapFiles() {
        final ArraySet<String> usedFiles = new ArraySet<>(mShortcuts.size());

        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            final ShortcutInfo si = mShortcuts.valueAt(i);
            if (si.getBitmapPath() != null) {
                usedFiles.add(getFileName(si.getBitmapPath()));
            }
        }
        return usedFiles;
    }

    private static String getFileName(@NonNull String path) {
        final int sep = path.lastIndexOf(File.separatorChar);
        if (sep == -1) {
            return path;
        } else {
            return path.substring(sep + 1);
        }
    }

    /**
     * Called when the package is updated or added.
     *
     * Add case:
     * - Publish manifest shortcuts.
     *
     * Update case:
     * - Re-publish manifest shortcuts.
     * - If there are shortcuts with resources (icons or strings), update their timestamps.
     *
     * @return TRUE if any shortcuts have been changed.
     */
    public boolean handlePackageAddedOrUpdated(boolean isNewApp) {
        final PackageInfo pi = mShortcutUser.mService.getPackageInfo(
                getPackageName(), getPackageUserId());
        if (pi == null) {
            return false; // Shouldn't happen.
        }

        if (!isNewApp) {
            // Make sure the version code or last update time has changed.
            // Otherwise, nothing to do.
            if (getPackageInfo().getVersionCode() >= pi.versionCode
                    && getPackageInfo().getLastUpdateTime() >= pi.lastUpdateTime) {
                return false;
            }
        }

        // Now prepare to publish manifest shortcuts.
        List<ShortcutInfo> newManifestShortcutList = null;
        try {
            newManifestShortcutList = ShortcutParser.parseShortcuts(mShortcutUser.mService,
                    getPackageName(), getPackageUserId());
        } catch (IOException|XmlPullParserException e) {
            Slog.e(TAG, "Failed to load shortcuts from AndroidManifest.xml.", e);
        }
        final int manifestShortcutSize = newManifestShortcutList == null ? 0
                : newManifestShortcutList.size();
        if (ShortcutService.DEBUG) {
            Slog.d(TAG, String.format("Package %s has %d manifest shortcut(s)",
                    getPackageName(), manifestShortcutSize));
        }
        if (isNewApp && (manifestShortcutSize == 0)) {
            // If it's a new app, and it doesn't have manifest shortcuts, then nothing to do.

            // If it's an update, then it may already have manifest shortcuts, which need to be
            // disabled.
            return false;
        }
        if (ShortcutService.DEBUG) {
            Slog.d(TAG, String.format("Package %s %s, version %d -> %d", getPackageName(),
                    (isNewApp ? "added" : "updated"),
                    getPackageInfo().getVersionCode(), pi.versionCode));
        }

        getPackageInfo().updateVersionInfo(pi);

        final ShortcutService s = mShortcutUser.mService;

        boolean changed = false;

        // For existing shortcuts, update timestamps if they have any resources.
        if (!isNewApp) {
            for (int i = mShortcuts.size() - 1; i >= 0; i--) {
                final ShortcutInfo si = mShortcuts.valueAt(i);

                if (si.hasAnyResources()) {
                    changed = true;
                    si.setTimestamp(s.injectCurrentTimeMillis());
                }
            }
        }

        // (Re-)publish manifest shortcut.
        changed |= publishManifestShortcuts(newManifestShortcutList);

        if (changed) {
            // This will send a notification to the launcher, and also save .
            s.packageShortcutsChanged(getPackageName(), getPackageUserId());
        } else {
            // Still save the version code.
            s.scheduleSaveUser(getPackageUserId());
        }
        return changed;
    }

    private boolean publishManifestShortcuts(List<ShortcutInfo> newManifestShortcutList) {
        if (ShortcutService.DEBUG) {
            Slog.d(TAG, String.format(
                    "Package %s: publishing manifest shortcuts", getPackageName()));
        }
        boolean changed = false;

        // TODO: Check dynamic count

        // TODO: Kick out dynamic if too many

        // Keep the previous IDs.
        ArraySet<String> toDisableList = null;
        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            final ShortcutInfo si = mShortcuts.valueAt(i);

            if (si.isManifestShortcut()) {
                if (toDisableList == null) {
                    toDisableList = new ArraySet<>();
                }
                toDisableList.add(si.getId());
            }
        }

        // Publish new ones.
        if (newManifestShortcutList != null) {
            final int newListSize = newManifestShortcutList.size();

            for (int i = 0; i < newListSize; i++) {
                changed = true;

                final ShortcutInfo newShortcut = newManifestShortcutList.get(i);
                final boolean newDisabled = !newShortcut.isEnabled();

                final String id =  newShortcut.getId();
                final ShortcutInfo oldShortcut = mShortcuts.get(id);

                boolean wasPinned = false;

                if (oldShortcut != null) {
                    if (!oldShortcut.isOriginallyFromManifest()) {
                        Slog.e(TAG, "Shortcut with ID=" + newShortcut.getId()
                                + " exists but is not from AndroidManifest.xml, not updating.");
                        continue;
                    }
                    // Take over the pinned flag.
                    if (oldShortcut.isPinned()) {
                        wasPinned = true;
                        newShortcut.addFlags(ShortcutInfo.FLAG_PINNED);
                    }
                }
                if (newDisabled && !wasPinned) {
                    // If the shortcut is disabled, and it was *not* pinned, then this
                    // just doesn't have to be published.
                    // Just keep it in toDisableList, so the previous one would be removed.
                    continue;
                }
                // TODO: Check dynamic count

                // Note even if enabled=false, we still need to update all fields, so do it
                // regardless.
                addShortcutInner(newShortcut); // This will clean up the old one too.

                if (!newDisabled && toDisableList != null) {
                    // Still alive, don't remove.
                    toDisableList.remove(id);
                }
            }
        }

        // Disable the previous manifest shortcuts that are no longer in the manifest.
        if (toDisableList != null) {
            if (ShortcutService.DEBUG) {
                Slog.d(TAG, String.format(
                        "Package %s: disabling %d stale shortcuts", getPackageName(),
                        toDisableList.size()));
            }
            for (int i = toDisableList.size() - 1; i >= 0; i--) {
                changed = true;

                final String id = toDisableList.valueAt(i);

                disableWithId(id, /* disable message =*/ null, /* disable message resid */ 0,
                        /* overrideImmutable=*/ true);
            }
            removeOrphans();
        }
        return changed;
    }

    public void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
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
        pw.print(getApiCallCount());
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
        pw.print(ShortcutService.formatTime(mLastResetTime));
        pw.println();

        getPackageInfo().dump(pw, prefix + "  ");
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
        pw.print(Formatter.formatFileSize(mShortcutUser.mService.mContext, totalBitmapSize));
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
        ShortcutService.writeAttr(out, ATTR_ACTIVITY, si.getActivity());
        // writeAttr(out, "icon", si.getIcon());  // We don't save it.
        ShortcutService.writeAttr(out, ATTR_TITLE, si.getTitle());
        ShortcutService.writeAttr(out, ATTR_TITLE_RES_ID, si.getTitleResId());
        ShortcutService.writeAttr(out, ATTR_TEXT, si.getText());
        ShortcutService.writeAttr(out, ATTR_TEXT_RES_ID, si.getTextResId());
        ShortcutService.writeAttr(out, ATTR_DISABLED_MESSAGE, si.getDisabledMessage());
        ShortcutService.writeAttr(out, ATTR_DISABLED_MESSAGE_RES_ID, si.getDisabledMessageResId());
        ShortcutService.writeAttr(out, ATTR_INTENT, si.getIntentNoExtras());
        ShortcutService.writeAttr(out, ATTR_RANK, si.getRank());
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

        final ShortcutPackage ret = new ShortcutPackage(shortcutUser,
                shortcutUser.getUserId(), packageName);

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
        int titleResId;
        String text;
        int textResId;
        String disabledMessage;
        int disabledMessageResId;
        Intent intent;
        PersistableBundle intentPersistableExtras = null;
        int rank;
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
        titleResId = ShortcutService.parseIntAttribute(parser, ATTR_TITLE_RES_ID);
        text = ShortcutService.parseStringAttribute(parser, ATTR_TEXT);
        textResId = ShortcutService.parseIntAttribute(parser, ATTR_TEXT_RES_ID);
        disabledMessage = ShortcutService.parseStringAttribute(parser, ATTR_DISABLED_MESSAGE);
        disabledMessageResId = ShortcutService.parseIntAttribute(parser,
                ATTR_DISABLED_MESSAGE_RES_ID);
        intent = ShortcutService.parseIntentAttribute(parser, ATTR_INTENT);
        rank = (int) ShortcutService.parseLongAttribute(parser, ATTR_RANK);
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
                userId, id, packageName, activityComponent, /* icon =*/ null,
                title, titleResId, text, textResId, disabledMessage, disabledMessageResId,
                categories, intent,
                intentPersistableExtras, rank, extras, lastChangedTimestamp, flags,
                iconRes, bitmapPath);
    }

    @VisibleForTesting
    List<ShortcutInfo> getAllShortcutsForTest() {
        return new ArrayList<>(mShortcuts.values());
    }
}
