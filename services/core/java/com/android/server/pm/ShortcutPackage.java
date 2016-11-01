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
import android.content.res.Resources;
import android.os.PersistableBundle;
import android.text.format.Formatter;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.server.pm.ShortcutService.ShortcutOperation;
import com.android.server.pm.ShortcutService.Stats;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Package information used by {@link ShortcutService}.
 * User information used by {@link ShortcutService}.
 *
 * All methods should be guarded by {@code #mShortcutUser.mService.mLock}.
 */
class ShortcutPackage extends ShortcutPackageItem {
    private static final String TAG = ShortcutService.TAG;
    private static final String TAG_VERIFY = ShortcutService.TAG + ".verify";

    static final String TAG_ROOT = "package";
    private static final String TAG_INTENT_EXTRAS_LEGACY = "intent-extras";
    private static final String TAG_INTENT = "intent";
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
    private static final String ATTR_TITLE_RES_NAME = "titlename";
    private static final String ATTR_TEXT = "text";
    private static final String ATTR_TEXT_RES_ID = "textid";
    private static final String ATTR_TEXT_RES_NAME = "textname";
    private static final String ATTR_DISABLED_MESSAGE = "dmessage";
    private static final String ATTR_DISABLED_MESSAGE_RES_ID = "dmessageid";
    private static final String ATTR_DISABLED_MESSAGE_RES_NAME = "dmessagename";
    private static final String ATTR_INTENT_LEGACY = "intent";
    private static final String ATTR_INTENT_NO_EXTRA = "intent-base";
    private static final String ATTR_RANK = "rank";
    private static final String ATTR_TIMESTAMP = "timestamp";
    private static final String ATTR_FLAGS = "flags";
    private static final String ATTR_ICON_RES_ID = "icon-res";
    private static final String ATTR_ICON_RES_NAME = "icon-resname";
    private static final String ATTR_BITMAP_PATH = "bitmap-path";

    private static final String NAME_CATEGORIES = "categories";

    private static final String TAG_STRING_ARRAY_XMLUTILS = "string-array";
    private static final String ATTR_NAME_XMLUTILS = "name";

    private static final String KEY_DYNAMIC = "dynamic";
    private static final String KEY_MANIFEST = "manifest";
    private static final String KEY_PINNED = "pinned";
    private static final String KEY_BITMAPS = "bitmaps";
    private static final String KEY_BITMAP_BYTES = "bitmapBytes";

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

    @Nullable
    public Resources getPackageResources() {
        return mShortcutUser.mService.injectGetResourcesForApplicationAsUser(
                getPackageName(), getPackageUserId());
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
        final ShortcutService s = mShortcutUser.mService;

        deleteShortcutInner(newShortcut.getId());

        // Extract Icon and update the icon res ID and the bitmap path.
        s.saveIconAndFixUpShortcut(getPackageUserId(), newShortcut);
        s.fixUpShortcutResourceNamesAndValues(newShortcut);
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
        }

        // If it was originally pinned, the new one should be pinned too.
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
        final long now = mShortcutUser.mService.injectCurrentTimeMillis();

        boolean changed = false;
        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            final ShortcutInfo si = mShortcuts.valueAt(i);
            if (si.isDynamic()) {
                changed = true;

                si.setTimestamp(now);
                si.clearFlags(ShortcutInfo.FLAG_DYNAMIC);
                si.setRank(0); // It may still be pinned, so clear the rank.
            }
        }
        if (changed) {
            removeOrphans();
        }
    }

    /**
     * Remove a dynamic shortcut by ID.  It'll be removed from the dynamic set, but if the shortcut
     * is pinned, it'll remain as a pinned shortcut, and is still enabled.
     *
     * @return true if it's actually removed because it wasn't pinned, or false if it's still
     * pinned.
     */
    public boolean deleteDynamicWithId(@NonNull String shortcutId) {
        final ShortcutInfo removed = deleteOrDisableWithId(
                shortcutId, /* disable =*/ false, /* overrideImmutable=*/ false);
        return removed == null;
    }

    /**
     * Disable a dynamic shortcut by ID.  It'll be removed from the dynamic set, but if the shortcut
     * is pinned, it'll remain as a pinned shortcut, but will be disabled.
     *
     * @return true if it's actually removed because it wasn't pinned, or false if it's still
     * pinned.
     */
    private boolean disableDynamicWithId(@NonNull String shortcutId) {
        final ShortcutInfo disabled = deleteOrDisableWithId(
                shortcutId, /* disable =*/ true, /* overrideImmutable=*/ false);
        return disabled == null;
    }

    /**
     * Disable a dynamic shortcut by ID.  It'll be removed from the dynamic set, but if the shortcut
     * is pinned, it'll remain as a pinned shortcut but will be disabled.
     */
    public void disableWithId(@NonNull String shortcutId, String disabledMessage,
            int disabledMessageResId, boolean overrideImmutable) {
        final ShortcutInfo disabled = deleteOrDisableWithId(shortcutId, /* disable =*/ true,
                overrideImmutable);

        if (disabled != null) {
            if (disabledMessage != null) {
                disabled.setDisabledMessage(disabledMessage);
            } else if (disabledMessageResId != 0) {
                disabled.setDisabledMessageResId(disabledMessageResId);

                mShortcutUser.mService.fixUpShortcutResourceNamesAndValues(disabled);
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

            oldShortcut.setRank(0);
            oldShortcut.clearFlags(ShortcutInfo.FLAG_DYNAMIC | ShortcutInfo.FLAG_MANIFEST);
            if (disable) {
                oldShortcut.addFlags(ShortcutInfo.FLAG_DISABLED);
            }
            oldShortcut.setTimestamp(mShortcutUser.mService.injectCurrentTimeMillis());

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
     * @return false if any of the target activities are no longer enabled.
     */
    private boolean areAllActivitiesStillEnabled() {
        if (mShortcuts.size() == 0) {
            return true;
        }
        final ShortcutService s = mShortcutUser.mService;

        // Normally the number of target activities is 1 or so, so no need to use a complex
        // structure like a set.
        final ArrayList<ComponentName> checked = new ArrayList<>(4);

        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            final ShortcutInfo si = mShortcuts.valueAt(i);
            final ComponentName activity = si.getActivity();

            if (checked.contains(activity)) {
                continue; // Already checked.
            }
            checked.add(activity);

            if (!s.injectIsActivityEnabledAndExported(activity, getOwnerUserId())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Called when the package may be added or updated, or its activities may be disabled, and
     * if so, rescan the package and do the necessary stuff.
     *
     * Add case:
     * - Publish manifest shortcuts.
     *
     * Update case:
     * - Re-publish manifest shortcuts.
     * - If there are shortcuts with resources (icons or strings), update their timestamps.
     * - Disable shortcuts whose target activities are disabled.
     *
     * @return TRUE if any shortcuts have been changed.
     */
    public boolean rescanPackageIfNeeded(boolean isNewApp, boolean forceRescan) {
        final ShortcutService s = mShortcutUser.mService;
        final long start = s.injectElapsedRealtime();

        final PackageInfo pi;
        try {
            pi = mShortcutUser.mService.getPackageInfo(
                    getPackageName(), getPackageUserId());
            if (pi == null) {
                return false; // Shouldn't happen.
            }

            // Always scan the settings app, since its version code is the same for DR and MR1.
            // TODO Fix it properly: b/32554059
            final boolean isSettings = "com.android.settings".equals(getPackageName());

            if (!isNewApp && !forceRescan && !isSettings) {
                // Return if the package hasn't changed, ie:
                // - version code hasn't change
                // - lastUpdateTime hasn't change
                // - all target activities are still enabled.

                // Note, system apps timestamps do *not* change after OTAs.  (But they do
                // after an adb sync or a local flash.)
                // This means if a system app's version code doesn't change on an OTA,
                // we don't notice it's updated.  But that's fine since their version code *should*
                // really change on OTAs.
                if ((getPackageInfo().getVersionCode() == pi.versionCode)
                        && (getPackageInfo().getLastUpdateTime() == pi.lastUpdateTime)
                        && areAllActivitiesStillEnabled()) {
                    return false;
                }
            }
            if (isSettings) {
                if (ShortcutService.DEBUG) {
                    Slog.d(TAG, "Always scan settings.");
                }
            }
        } finally {
            s.logDurationStat(Stats.PACKAGE_UPDATE_CHECK, start);
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

        boolean changed = false;

        // For existing shortcuts, update timestamps if they have any resources.
        // Also check if shortcuts' activities are still main activities.  Otherwise, disable them.
        if (!isNewApp) {
            Resources publisherRes = null;

            for (int i = mShortcuts.size() - 1; i >= 0; i--) {
                final ShortcutInfo si = mShortcuts.valueAt(i);

                if (si.isDynamic()) {
                    if (!s.injectIsMainActivity(si.getActivity(), getPackageUserId())) {
                        Slog.w(TAG, String.format(
                                "%s is no longer main activity. Disabling shorcut %s.",
                                getPackageName(), si.getId()));
                        if (disableDynamicWithId(si.getId())) {
                            continue; // Actually removed.
                        }
                        // Still pinned, so fall-through and possibly update the resources.
                    }
                    changed = true;
                }

                if (si.hasAnyResources()) {
                    if (!si.isOriginallyFromManifest()) {
                        if (publisherRes == null) {
                            publisherRes = getPackageResources();
                            if (publisherRes == null) {
                                break; // Resources couldn't be loaded.
                            }
                        }

                        // If this shortcut is not from a manifest, then update all resource IDs
                        // from resource names.  (We don't allow resource strings for
                        // non-manifest at the moment, but icons can still be resources.)
                        si.lookupAndFillInResourceIds(publisherRes);
                    }
                    changed = true;
                    si.setTimestamp(s.injectCurrentTimeMillis());
                }
            }
        }

        // (Re-)publish manifest shortcut.
        changed |= publishManifestShortcuts(newManifestShortcutList);

        if (newManifestShortcutList != null) {
            changed |= pushOutExcessShortcuts();
        }

        s.verifyStates();

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

                final String id = newShortcut.getId();
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
        adjustRanks();
        return changed;
    }

    /**
     * For each target activity, make sure # of dynamic + manifest shortcuts <= max.
     * If too many, we'll remove the dynamic with the lowest ranks.
     */
    private boolean pushOutExcessShortcuts() {
        final ShortcutService service = mShortcutUser.mService;
        final int maxShortcuts = service.getMaxActivityShortcuts();

        boolean changed = false;

        final ArrayMap<ComponentName, ArrayList<ShortcutInfo>> all =
                sortShortcutsToActivities();
        for (int outer = all.size() - 1; outer >= 0; outer--) {
            final ArrayList<ShortcutInfo> list = all.valueAt(outer);
            if (list.size() <= maxShortcuts) {
                continue;
            }
            // Sort by isManifestShortcut() and getRank().
            Collections.sort(list, mShortcutTypeAndRankComparator);

            // Keep [0 .. max), and remove (as dynamic) [max .. size)
            for (int inner = list.size() - 1; inner >= maxShortcuts; inner--) {
                final ShortcutInfo shortcut = list.get(inner);

                if (shortcut.isManifestShortcut()) {
                    // This shouldn't happen -- excess shortcuts should all be non-manifest.
                    // But just in case.
                    service.wtf("Found manifest shortcuts in excess list.");
                    continue;
                }
                deleteDynamicWithId(shortcut.getId());
            }
        }

        return changed;
    }

    /**
     * To sort by isManifestShortcut() and getRank(). i.e.  manifest shortcuts come before
     * non-manifest shortcuts, then sort by rank.
     *
     * This is used to decide which dynamic shortcuts to remove when an upgraded version has more
     * manifest shortcuts than before and as a result we need to remove some of the dynamic
     * shortcuts.  We sort manifest + dynamic shortcuts by this order, and remove the ones with
     * the last ones.
     *
     * (Note the number of manifest shortcuts is always <= the max number, because if there are
     * more, ShortcutParser would ignore the rest.)
     */
    final Comparator<ShortcutInfo> mShortcutTypeAndRankComparator = (ShortcutInfo a,
            ShortcutInfo b) -> {
        if (a.isManifestShortcut() && !b.isManifestShortcut()) {
            return -1;
        }
        if (!a.isManifestShortcut() && b.isManifestShortcut()) {
            return 1;
        }
        return Integer.compare(a.getRank(), b.getRank());
    };

    /**
     * Build a list of shortcuts for each target activity and return as a map. The result won't
     * contain "floating" shortcuts because they don't belong on any activities.
     */
    private ArrayMap<ComponentName, ArrayList<ShortcutInfo>> sortShortcutsToActivities() {
        final ArrayMap<ComponentName, ArrayList<ShortcutInfo>> activitiesToShortcuts
                = new ArrayMap<>();
        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            final ShortcutInfo si = mShortcuts.valueAt(i);
            if (si.isFloating()) {
                continue; // Ignore floating shortcuts, which are not tied to any activities.
            }

            final ComponentName activity = si.getActivity();

            ArrayList<ShortcutInfo> list = activitiesToShortcuts.get(activity);
            if (list == null) {
                list = new ArrayList<>();
                activitiesToShortcuts.put(activity, list);
            }
            list.add(si);
        }
        return activitiesToShortcuts;
    }

    /** Used by {@link #enforceShortcutCountsBeforeOperation} */
    private void incrementCountForActivity(ArrayMap<ComponentName, Integer> counts,
            ComponentName cn, int increment) {
        Integer oldValue = counts.get(cn);
        if (oldValue == null) {
            oldValue = 0;
        }

        counts.put(cn, oldValue + increment);
    }

    /**
     * Called by
     * {@link android.content.pm.ShortcutManager#setDynamicShortcuts},
     * {@link android.content.pm.ShortcutManager#addDynamicShortcuts}, and
     * {@link android.content.pm.ShortcutManager#updateShortcuts} before actually performing
     * the operation to make sure the operation wouldn't result in the target activities having
     * more than the allowed number of dynamic/manifest shortcuts.
     *
     * @param newList shortcut list passed to set, add or updateShortcuts().
     * @param operation add, set or update.
     * @throws IllegalArgumentException if the operation would result in going over the max
     *                                  shortcut count for any activity.
     */
    public void enforceShortcutCountsBeforeOperation(List<ShortcutInfo> newList,
            @ShortcutOperation int operation) {
        final ShortcutService service = mShortcutUser.mService;

        // Current # of dynamic / manifest shortcuts for each activity.
        // (If it's for update, then don't count dynamic shortcuts, since they'll be replaced
        // anyway.)
        final ArrayMap<ComponentName, Integer> counts = new ArrayMap<>(4);
        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            final ShortcutInfo shortcut = mShortcuts.valueAt(i);

            if (shortcut.isManifestShortcut()) {
                incrementCountForActivity(counts, shortcut.getActivity(), 1);
            } else if (shortcut.isDynamic() && (operation != ShortcutService.OPERATION_SET)) {
                incrementCountForActivity(counts, shortcut.getActivity(), 1);
            }
        }

        for (int i = newList.size() - 1; i >= 0; i--) {
            final ShortcutInfo newShortcut = newList.get(i);
            final ComponentName newActivity = newShortcut.getActivity();
            if (newActivity == null) {
                if (operation != ShortcutService.OPERATION_UPDATE) {
                    service.wtf("Activity must not be null at this point");
                    continue; // Just ignore this invalid case.
                }
                continue; // Activity can be null for update.
            }

            final ShortcutInfo original = mShortcuts.get(newShortcut.getId());
            if (original == null) {
                if (operation == ShortcutService.OPERATION_UPDATE) {
                    continue; // When updating, ignore if there's no target.
                }
                // Add() or set(), and there's no existing shortcut with the same ID.  We're
                // simply publishing (as opposed to updating) this shortcut, so just +1.
                incrementCountForActivity(counts, newActivity, 1);
                continue;
            }
            if (original.isFloating() && (operation == ShortcutService.OPERATION_UPDATE)) {
                // Updating floating shortcuts doesn't affect the count, so ignore.
                continue;
            }

            // If it's add() or update(), then need to decrement for the previous activity.
            // Skip it for set() since it's already been taken care of by not counting the original
            // dynamic shortcuts in the first loop.
            if (operation != ShortcutService.OPERATION_SET) {
                final ComponentName oldActivity = original.getActivity();
                if (!original.isFloating()) {
                    incrementCountForActivity(counts, oldActivity, -1);
                }
            }
            incrementCountForActivity(counts, newActivity, 1);
        }

        // Then make sure none of the activities have more than the max number of shortcuts.
        for (int i = counts.size() - 1; i >= 0; i--) {
            service.enforceMaxActivityShortcuts(counts.valueAt(i));
        }
    }

    /**
     * For all the text fields, refresh the string values if they're from resources.
     */
    public void resolveResourceStrings() {
        final ShortcutService s = mShortcutUser.mService;
        boolean changed = false;

        Resources publisherRes = null;
        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            final ShortcutInfo si = mShortcuts.valueAt(i);

            if (si.hasStringResources()) {
                changed = true;

                if (publisherRes == null) {
                    publisherRes = getPackageResources();
                    if (publisherRes == null) {
                        break; // Resources couldn't be loaded.
                    }
                }

                si.resolveResourceStrings(publisherRes);
                si.setTimestamp(s.injectCurrentTimeMillis());
            }
        }
        if (changed) {
            s.packageShortcutsChanged(getPackageName(), getPackageUserId());
        }
    }

    /** Clears the implicit ranks for all shortcuts. */
    public void clearAllImplicitRanks() {
        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            final ShortcutInfo si = mShortcuts.valueAt(i);
            si.clearImplicitRankAndRankChangedFlag();
        }
    }

    /**
     * Used to sort shortcuts for rank auto-adjusting.
     */
    final Comparator<ShortcutInfo> mShortcutRankComparator = (ShortcutInfo a, ShortcutInfo b) -> {
        // First, sort by rank.
        int ret = Integer.compare(a.getRank(), b.getRank());
        if (ret != 0) {
            return ret;
        }
        // When ranks are tie, then prioritize the ones that have just been assigned new ranks.
        // e.g. when there are 3 shortcuts, "s1" "s2" and "s3" with rank 0, 1, 2 respectively,
        // adding a shortcut "s4" with rank 1 will "insert" it between "s1" and "s2", because
        // "s2" and "s4" have the same rank 1 but s4 has isRankChanged() set.
        // Similarly, updating s3's rank to 1 will insert it between s1 and s2.
        if (a.isRankChanged() != b.isRankChanged()) {
            return a.isRankChanged() ? -1 : 1;
        }
        // If they're still tie, sort by implicit rank -- i.e. preserve the order in which
        // they're passed to the API.
        ret = Integer.compare(a.getImplicitRank(), b.getImplicitRank());
        if (ret != 0) {
            return ret;
        }
        // If they're stil tie, just sort by their IDs.
        // This may happen with updateShortcuts() -- see
        // the testUpdateShortcuts_noManifestShortcuts() test.
        return a.getId().compareTo(b.getId());
    };

    /**
     * Re-calculate the ranks for all shortcuts.
     */
    public void adjustRanks() {
        final ShortcutService s = mShortcutUser.mService;
        final long now = s.injectCurrentTimeMillis();

        // First, clear ranks for floating shortcuts.
        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            final ShortcutInfo si = mShortcuts.valueAt(i);
            if (si.isFloating()) {
                if (si.getRank() != 0) {
                    si.setTimestamp(now);
                    si.setRank(0);
                }
            }
        }

        // Then adjust ranks.  Ranks are unique for each activity, so we first need to sort
        // shortcuts to each activity.
        // Then sort the shortcuts within each activity with mShortcutRankComparator, and
        // assign ranks from 0.
        final ArrayMap<ComponentName, ArrayList<ShortcutInfo>> all =
                sortShortcutsToActivities();
        for (int outer = all.size() - 1; outer >= 0; outer--) { // For each activity.
            final ArrayList<ShortcutInfo> list = all.valueAt(outer);

            // Sort by ranks and other signals.
            Collections.sort(list, mShortcutRankComparator);

            int rank = 0;

            final int size = list.size();
            for (int i = 0; i < size; i++) {
                final ShortcutInfo si = list.get(i);
                if (si.isManifestShortcut()) {
                    // Don't adjust ranks for manifest shortcuts.
                    continue;
                }
                // At this point, it must be dynamic.
                if (!si.isDynamic()) {
                    s.wtf("Non-dynamic shortcut found.");
                    continue;
                }
                final int thisRank = rank++;
                if (si.getRank() != thisRank) {
                    si.setTimestamp(now);
                    si.setRank(thisRank);
                }
            }
        }
    }

    /** @return true if there's any shortcuts that are not manifest shortcuts. */
    public boolean hasNonManifestShortcuts() {
        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            final ShortcutInfo si = mShortcuts.valueAt(i);
            if (!si.isDeclaredInManifest()) {
                return true;
            }
        }
        return false;
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
    public JSONObject dumpCheckin(boolean clear) throws JSONException {
        final JSONObject result = super.dumpCheckin(clear);

        int numDynamic = 0;
        int numPinned = 0;
        int numManifest = 0;
        int numBitmaps = 0;
        long totalBitmapSize = 0;

        final ArrayMap<String, ShortcutInfo> shortcuts = mShortcuts;
        final int size = shortcuts.size();
        for (int i = 0; i < size; i++) {
            final ShortcutInfo si = shortcuts.valueAt(i);

            if (si.isDynamic()) numDynamic++;
            if (si.isDeclaredInManifest()) numManifest++;
            if (si.isPinned()) numPinned++;

            if (si.getBitmapPath() != null) {
                numBitmaps++;
                totalBitmapSize += new File(si.getBitmapPath()).length();
            }
        }

        result.put(KEY_DYNAMIC, numDynamic);
        result.put(KEY_MANIFEST, numManifest);
        result.put(KEY_PINNED, numPinned);
        result.put(KEY_BITMAPS, numBitmaps);
        result.put(KEY_BITMAP_BYTES, totalBitmapSize);

        // TODO Log update frequency too.

        return result;
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
            if (!(si.isPinned() && si.isEnabled())) {
                return; // We only backup pinned shortcuts that are enabled.
            }
        }
        out.startTag(null, TAG_SHORTCUT);
        ShortcutService.writeAttr(out, ATTR_ID, si.getId());
        // writeAttr(out, "package", si.getPackageName()); // not needed
        ShortcutService.writeAttr(out, ATTR_ACTIVITY, si.getActivity());
        // writeAttr(out, "icon", si.getIcon());  // We don't save it.
        ShortcutService.writeAttr(out, ATTR_TITLE, si.getTitle());
        ShortcutService.writeAttr(out, ATTR_TITLE_RES_ID, si.getTitleResId());
        ShortcutService.writeAttr(out, ATTR_TITLE_RES_NAME, si.getTitleResName());
        ShortcutService.writeAttr(out, ATTR_TEXT, si.getText());
        ShortcutService.writeAttr(out, ATTR_TEXT_RES_ID, si.getTextResId());
        ShortcutService.writeAttr(out, ATTR_TEXT_RES_NAME, si.getTextResName());
        ShortcutService.writeAttr(out, ATTR_DISABLED_MESSAGE, si.getDisabledMessage());
        ShortcutService.writeAttr(out, ATTR_DISABLED_MESSAGE_RES_ID,
                si.getDisabledMessageResourceId());
        ShortcutService.writeAttr(out, ATTR_DISABLED_MESSAGE_RES_NAME,
                si.getDisabledMessageResName());
        ShortcutService.writeAttr(out, ATTR_TIMESTAMP,
                si.getLastChangedTimestamp());
        if (forBackup) {
            // Don't write icon information.  Also drop the dynamic flag.
            ShortcutService.writeAttr(out, ATTR_FLAGS,
                    si.getFlags() &
                            ~(ShortcutInfo.FLAG_HAS_ICON_FILE | ShortcutInfo.FLAG_HAS_ICON_RES
                            | ShortcutInfo.FLAG_DYNAMIC));
        } else {
            // When writing for backup, ranks shouldn't be saved, since shortcuts won't be restored
            // as dynamic.
            ShortcutService.writeAttr(out, ATTR_RANK, si.getRank());

            ShortcutService.writeAttr(out, ATTR_FLAGS, si.getFlags());
            ShortcutService.writeAttr(out, ATTR_ICON_RES_ID, si.getIconResourceId());
            ShortcutService.writeAttr(out, ATTR_ICON_RES_NAME, si.getIconResName());
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
        final Intent[] intentsNoExtras = si.getIntentsNoExtras();
        final PersistableBundle[] intentsExtras = si.getIntentPersistableExtrases();
        final int numIntents = intentsNoExtras.length;
        for (int i = 0; i < numIntents; i++) {
            out.startTag(null, TAG_INTENT);
            ShortcutService.writeAttr(out, ATTR_INTENT_NO_EXTRA, intentsNoExtras[i]);
            ShortcutService.writeTagExtra(out, TAG_EXTRAS, intentsExtras[i]);
            out.endTag(null, TAG_INTENT);
        }

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
        String titleResName;
        String text;
        int textResId;
        String textResName;
        String disabledMessage;
        int disabledMessageResId;
        String disabledMessageResName;
        Intent intentLegacy;
        PersistableBundle intentPersistableExtrasLegacy = null;
        ArrayList<Intent> intents = new ArrayList<>();
        int rank;
        PersistableBundle extras = null;
        long lastChangedTimestamp;
        int flags;
        int iconResId;
        String iconResName;
        String bitmapPath;
        ArraySet<String> categories = null;

        id = ShortcutService.parseStringAttribute(parser, ATTR_ID);
        activityComponent = ShortcutService.parseComponentNameAttribute(parser,
                ATTR_ACTIVITY);
        title = ShortcutService.parseStringAttribute(parser, ATTR_TITLE);
        titleResId = ShortcutService.parseIntAttribute(parser, ATTR_TITLE_RES_ID);
        titleResName = ShortcutService.parseStringAttribute(parser, ATTR_TITLE_RES_NAME);
        text = ShortcutService.parseStringAttribute(parser, ATTR_TEXT);
        textResId = ShortcutService.parseIntAttribute(parser, ATTR_TEXT_RES_ID);
        textResName = ShortcutService.parseStringAttribute(parser, ATTR_TEXT_RES_NAME);
        disabledMessage = ShortcutService.parseStringAttribute(parser, ATTR_DISABLED_MESSAGE);
        disabledMessageResId = ShortcutService.parseIntAttribute(parser,
                ATTR_DISABLED_MESSAGE_RES_ID);
        disabledMessageResName = ShortcutService.parseStringAttribute(parser,
                ATTR_DISABLED_MESSAGE_RES_NAME);
        intentLegacy = ShortcutService.parseIntentAttributeNoDefault(parser, ATTR_INTENT_LEGACY);
        rank = (int) ShortcutService.parseLongAttribute(parser, ATTR_RANK);
        lastChangedTimestamp = ShortcutService.parseLongAttribute(parser, ATTR_TIMESTAMP);
        flags = (int) ShortcutService.parseLongAttribute(parser, ATTR_FLAGS);
        iconResId = (int) ShortcutService.parseLongAttribute(parser, ATTR_ICON_RES_ID);
        iconResName = ShortcutService.parseStringAttribute(parser, ATTR_ICON_RES_NAME);
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
                case TAG_INTENT_EXTRAS_LEGACY:
                    intentPersistableExtrasLegacy = PersistableBundle.restoreFromXml(parser);
                    continue;
                case TAG_INTENT:
                    intents.add(parseIntent(parser));
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

        if (intentLegacy != null) {
            // For the legacy file format which supported only one intent per shortcut.
            ShortcutInfo.setIntentExtras(intentLegacy, intentPersistableExtrasLegacy);
            intents.clear();
            intents.add(intentLegacy);
        }

        return new ShortcutInfo(
                userId, id, packageName, activityComponent, /* icon =*/ null,
                title, titleResId, titleResName, text, textResId, textResName,
                disabledMessage, disabledMessageResId, disabledMessageResName,
                categories,
                intents.toArray(new Intent[intents.size()]),
                rank, extras, lastChangedTimestamp, flags,
                iconResId, iconResName, bitmapPath);
    }

    private static Intent parseIntent(XmlPullParser parser)
            throws IOException, XmlPullParserException {

        Intent intent = ShortcutService.parseIntentAttribute(parser,
                ATTR_INTENT_NO_EXTRA);

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
                case TAG_EXTRAS:
                    ShortcutInfo.setIntentExtras(intent,
                            PersistableBundle.restoreFromXml(parser));
                    continue;
            }
            throw ShortcutService.throwForInvalidTag(depth, tag);
        }
        return intent;
    }

    @VisibleForTesting
    List<ShortcutInfo> getAllShortcutsForTest() {
        return new ArrayList<>(mShortcuts.values());
    }

    @Override
    public void verifyStates() {
        super.verifyStates();

        boolean failed = false;

        final ArrayMap<ComponentName, ArrayList<ShortcutInfo>> all =
                sortShortcutsToActivities();

        // Make sure each activity won't have more than max shortcuts.
        for (int outer = all.size() - 1; outer >= 0; outer--) {
            final ArrayList<ShortcutInfo> list = all.valueAt(outer);
            if (list.size() > mShortcutUser.mService.getMaxActivityShortcuts()) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": activity " + all.keyAt(outer)
                        + " has " + all.valueAt(outer).size() + " shortcuts.");
            }

            // Sort by rank.
            Collections.sort(list, (a, b) -> Integer.compare(a.getRank(), b.getRank()));

            // Split into two arrays for each kind.
            final ArrayList<ShortcutInfo> dynamicList = new ArrayList<>(list);
            dynamicList.removeIf((si) -> !si.isDynamic());

            final ArrayList<ShortcutInfo> manifestList = new ArrayList<>(list);
            dynamicList.removeIf((si) -> !si.isManifestShortcut());

            verifyRanksSequential(dynamicList);
            verifyRanksSequential(manifestList);
        }

        // Verify each shortcut's status.
        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            final ShortcutInfo si = mShortcuts.valueAt(i);
            if (!(si.isDeclaredInManifest() || si.isDynamic() || si.isPinned())) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " is not manifest, dynamic or pinned.");
            }
            if (si.isDeclaredInManifest() && si.isDynamic()) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " is both dynamic and manifest at the same time.");
            }
            if (si.getActivity() == null) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " has null activity.");
            }
            if ((si.isDynamic() || si.isManifestShortcut()) && !si.isEnabled()) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " is not floating, but is disabled.");
            }
            if (si.isFloating() && si.getRank() != 0) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " is floating, but has rank=" + si.getRank());
            }
            if (si.getIcon() != null) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " still has an icon");
            }
            if (si.hasIconFile() && si.hasIconResource()) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " has both resource and bitmap icons");
            }
        }

        if (failed) {
            throw new IllegalStateException("See logcat for errors");
        }
    }

    private boolean verifyRanksSequential(List<ShortcutInfo> list) {
        boolean failed = false;

        for (int i = 0; i < list.size(); i++) {
            final ShortcutInfo si = list.get(i);
            if (si.getRank() != i) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " rank=" + si.getRank() + " but expected to be "+ i);
            }
        }
        return failed;
    }
}
