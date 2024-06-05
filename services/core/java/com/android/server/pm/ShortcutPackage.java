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
import android.app.Person;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSession;
import android.app.appsearch.BatchResultCallback;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetByDocumentIdRequest;
import android.app.appsearch.PutDocumentsRequest;
import android.app.appsearch.RemoveByDocumentIdRequest;
import android.app.appsearch.ReportUsageRequest;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResults;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.LocusId;
import android.content.pm.AppSearchShortcutInfo;
import android.content.pm.AppSearchShortcutPerson;
import android.content.pm.PackageInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.PersistableBundle;
import android.os.StrictMode;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.pm.ShortcutService.DumpFilter;
import com.android.server.pm.ShortcutService.ShortcutOperation;
import com.android.server.pm.ShortcutService.Stats;

import libcore.io.IoUtils;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Package information used by {@link ShortcutService}.
 * User information used by {@link ShortcutService}.
 */
class ShortcutPackage extends ShortcutPackageItem {
    private static final String TAG = ShortcutService.TAG;
    private static final String TAG_VERIFY = ShortcutService.TAG + ".verify";

    static final String TAG_ROOT = "package";
    private static final String TAG_INTENT_EXTRAS_LEGACY = "intent-extras";
    private static final String TAG_INTENT = "intent";
    private static final String TAG_EXTRAS = "extras";
    private static final String TAG_SHORTCUT = "shortcut";
    private static final String TAG_SHARE_TARGET = "share-target";
    private static final String TAG_CATEGORIES = "categories";
    private static final String TAG_PERSON = "person";

    private static final String ATTR_NAME = "name";
    private static final String ATTR_CALL_COUNT = "call-count";
    private static final String ATTR_LAST_RESET = "last-reset";
    private static final String ATTR_SCHEMA_VERSON = "schema-version";
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
    private static final String ATTR_DISABLED_REASON = "disabled-reason";
    private static final String ATTR_INTENT_LEGACY = "intent";
    private static final String ATTR_INTENT_NO_EXTRA = "intent-base";
    private static final String ATTR_RANK = "rank";
    private static final String ATTR_TIMESTAMP = "timestamp";
    private static final String ATTR_FLAGS = "flags";
    private static final String ATTR_ICON_RES_ID = "icon-res";
    private static final String ATTR_ICON_RES_NAME = "icon-resname";
    private static final String ATTR_BITMAP_PATH = "bitmap-path";
    private static final String ATTR_ICON_URI = "icon-uri";
    private static final String ATTR_LOCUS_ID = "locus-id";
    private static final String ATTR_SPLASH_SCREEN_THEME_NAME = "splash-screen-theme-name";

    private static final String ATTR_PERSON_NAME = "name";
    private static final String ATTR_PERSON_URI = "uri";
    private static final String ATTR_PERSON_KEY = "key";
    private static final String ATTR_PERSON_IS_BOT = "is-bot";
    private static final String ATTR_PERSON_IS_IMPORTANT = "is-important";

    private static final String NAME_CATEGORIES = "categories";
    private static final String NAME_CAPABILITY = "capability";

    private static final String TAG_STRING_ARRAY_XMLUTILS = "string-array";
    private static final String TAG_MAP_XMLUTILS = "map";
    private static final String ATTR_NAME_XMLUTILS = "name";

    private static final String KEY_DYNAMIC = "dynamic";
    private static final String KEY_MANIFEST = "manifest";
    private static final String KEY_PINNED = "pinned";
    private static final String KEY_BITMAPS = "bitmaps";
    private static final String KEY_BITMAP_BYTES = "bitmapBytes";

    @VisibleForTesting
    public static final int REPORT_USAGE_BUFFER_SIZE = 3;

    private final Executor mExecutor;

    /**
     * An in-memory copy of shortcuts for this package that was loaded from xml, keyed on IDs.
     */
    @GuardedBy("mLock")
    private final ArrayMap<String, ShortcutInfo> mShortcuts = new ArrayMap<>();

    /**
     * A temporary copy of shortcuts that are to be cleared once persisted into AppSearch, keyed on
     * IDs.
     */
    @GuardedBy("mLock")
    private final ArrayMap<String, ShortcutInfo> mTransientShortcuts = new ArrayMap<>(0);

    /**
     * All the share targets from the package
     */
    @GuardedBy("mLock")
    private final ArrayList<ShareTargetInfo> mShareTargets = new ArrayList<>(0);

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

    @GuardedBy("mLock")
    private List<Long> mLastReportedTime = new ArrayList<>();

    @GuardedBy("mLock")
    private boolean mIsAppSearchSchemaUpToDate;

    private ShortcutPackage(ShortcutUser shortcutUser,
            int packageUserId, String packageName, ShortcutPackageInfo spi) {
        super(shortcutUser, packageUserId, packageName,
                spi != null ? spi : ShortcutPackageInfo.newEmpty());

        mPackageUid = shortcutUser.mService.injectGetPackageUid(packageName, packageUserId);
        mExecutor = BackgroundThread.getExecutor();
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

    private boolean isAppSearchEnabled() {
        return mShortcutUser.mService.isAppSearchEnabled();
    }

    public int getShortcutCount() {
        synchronized (mLock) {
            return mShortcuts.size();
        }
    }

    @Override
    protected boolean canRestoreAnyVersion() {
        return false;
    }

    @Override
    protected void onRestored(int restoreBlockReason) {
        // Shortcuts have been restored.
        // - Unshadow all shortcuts.
        // - Set disabled reason.
        // - Disable if needed.
        final String query = String.format("%s:-%s AND %s:%s",
                AppSearchShortcutInfo.KEY_FLAGS, ShortcutInfo.FLAG_SHADOW,
                AppSearchShortcutInfo.KEY_DISABLED_REASON, restoreBlockReason);
        forEachShortcutMutate(si -> {
            if (restoreBlockReason == ShortcutInfo.DISABLED_REASON_NOT_DISABLED
                    && !si.hasFlags(ShortcutInfo.FLAG_SHADOW)
                    && si.getDisabledReason() == restoreBlockReason) {
                return;
            }
            si.clearFlags(ShortcutInfo.FLAG_SHADOW);

            si.setDisabledReason(restoreBlockReason);
            if (restoreBlockReason != ShortcutInfo.DISABLED_REASON_NOT_DISABLED) {
                si.addFlags(ShortcutInfo.FLAG_DISABLED);
            }
        });
        // Because some launchers may not have been restored (e.g. allowBackup=false),
        // we need to re-calculate the pinned shortcuts.
        refreshPinnedFlags();
    }

    /**
     * Note this does *not* provide a correct view to the calling launcher.
     */
    @Nullable
    public ShortcutInfo findShortcutById(@Nullable final String id) {
        if (id == null) return null;
        synchronized (mLock) {
            return mShortcuts.get(id);
        }
    }

    public boolean isShortcutExistsAndInvisibleToPublisher(String id) {
        ShortcutInfo si = findShortcutById(id);
        return si != null && !si.isVisibleToPublisher();
    }

    public boolean isShortcutExistsAndVisibleToPublisher(String id) {
        ShortcutInfo si = findShortcutById(id);
        return si != null && si.isVisibleToPublisher();
    }

    private void ensureNotImmutable(@Nullable ShortcutInfo shortcut, boolean ignoreInvisible) {
        if (shortcut != null && shortcut.isImmutable()
                && (!ignoreInvisible || shortcut.isVisibleToPublisher())) {
            throw new IllegalArgumentException(
                    "Manifest shortcut ID=" + shortcut.getId()
                            + " may not be manipulated via APIs");
        }
    }

    public void ensureNotImmutable(@NonNull String id, boolean ignoreInvisible) {
        ensureNotImmutable(findShortcutById(id), ignoreInvisible);
    }

    public void ensureImmutableShortcutsNotIncludedWithIds(@NonNull List<String> shortcutIds,
            boolean ignoreInvisible) {
        for (int i = shortcutIds.size() - 1; i >= 0; i--) {
            ensureNotImmutable(shortcutIds.get(i), ignoreInvisible);
        }
    }

    public void ensureImmutableShortcutsNotIncluded(@NonNull List<ShortcutInfo> shortcuts,
            boolean ignoreInvisible) {
        for (int i = shortcuts.size() - 1; i >= 0; i--) {
            ensureNotImmutable(shortcuts.get(i).getId(), ignoreInvisible);
        }
    }

    public void ensureNoBitmapIconIfShortcutIsLongLived(@NonNull List<ShortcutInfo> shortcuts) {
        for (int i = shortcuts.size() - 1; i >= 0; i--) {
            final ShortcutInfo si = shortcuts.get(i);
            if (!si.isLongLived()) {
                continue;
            }
            final Icon icon = si.getIcon();
            if (icon != null && icon.getType() != Icon.TYPE_BITMAP
                    && icon.getType() != Icon.TYPE_ADAPTIVE_BITMAP) {
                continue;
            }
            if (icon == null && !si.hasIconFile()) {
                continue;
            }

            // TODO: Throw IllegalArgumentException instead.
            Slog.e(TAG, "Invalid icon type in shortcut " + si.getId() + ". Bitmaps are not allowed"
                    + " in long-lived shortcuts. Use Resource icons, or Uri-based icons instead.");
            return;  // Do not spam and return early.
        }
    }

    public void ensureAllShortcutsVisibleToLauncher(@NonNull List<ShortcutInfo> shortcuts) {
        for (ShortcutInfo shortcut : shortcuts) {
            if (shortcut.isExcludedFromSurfaces(ShortcutInfo.SURFACE_LAUNCHER)) {
                throw new IllegalArgumentException("Shortcut ID=" + shortcut.getId()
                        + " is hidden from launcher and may not be manipulated via APIs");
            }
        }
    }

    /**
     * Delete a shortcut by ID. This will *always* remove it even if it's immutable or invisible.
     */
    private ShortcutInfo forceDeleteShortcutInner(@NonNull String id) {
        final ShortcutInfo shortcut;
        synchronized (mLock) {
            shortcut = mShortcuts.remove(id);
            if (shortcut != null) {
                removeIcon(shortcut);
                shortcut.clearFlags(ShortcutInfo.FLAG_DYNAMIC | ShortcutInfo.FLAG_PINNED
                        | ShortcutInfo.FLAG_MANIFEST | ShortcutInfo.FLAG_CACHED_ALL);
            }
        }
        return shortcut;
    }

    /**
     * Force replace a shortcut. If there's already a shortcut with the same ID, it'll be removed,
     * even if it's invisible.
     */
    private void forceReplaceShortcutInner(@NonNull ShortcutInfo newShortcut) {
        final ShortcutService s = mShortcutUser.mService;

        forceDeleteShortcutInner(newShortcut.getId());

        // Extract Icon and update the icon res ID and the bitmap path.
        s.saveIconAndFixUpShortcutLocked(this, newShortcut);
        s.fixUpShortcutResourceNamesAndValues(newShortcut);
        ensureShortcutCountBeforePush();
        saveShortcut(newShortcut);
    }

    /**
     * Add a shortcut. If there's already a one with the same ID, it'll be removed, even if it's
     * invisible.
     *
     * It checks the max number of dynamic shortcuts.
     *
     * @return True if it replaced an existing shortcut, False otherwise.
     */
    public boolean addOrReplaceDynamicShortcut(@NonNull ShortcutInfo newShortcut) {

        Preconditions.checkArgument(newShortcut.isEnabled(),
                "add/setDynamicShortcuts() cannot publish disabled shortcuts");

        newShortcut.addFlags(ShortcutInfo.FLAG_DYNAMIC);

        final ShortcutInfo oldShortcut = findShortcutById(newShortcut.getId());
        if (oldShortcut != null) {
            // It's an update case.
            // Make sure the target is updatable. (i.e. should be mutable.)
            oldShortcut.ensureUpdatableWith(newShortcut, /*isUpdating=*/ false);

            // If it was originally pinned or cached, the new one should be pinned or cached too.
            newShortcut.addFlags(oldShortcut.getFlags()
                    & (ShortcutInfo.FLAG_PINNED | ShortcutInfo.FLAG_CACHED_ALL));
        }

        if (newShortcut.isExcludedFromSurfaces(ShortcutInfo.SURFACE_LAUNCHER)) {
            if (isAppSearchEnabled()) {
                synchronized (mLock) {
                    mTransientShortcuts.put(newShortcut.getId(), newShortcut);
                }
            }
        } else {
            forceReplaceShortcutInner(newShortcut);
        }
        return oldShortcut != null;
    }

    /**
     * Push a shortcut. If the max number of dynamic shortcuts is already reached, remove the
     * shortcut with the lowest rank before adding the new shortcut.
     *
     * Any shortcut that gets altered (removed or changed) as a result of this push operation will
     * be included and returned in changedShortcuts.
     *
     * @return True if a shortcut had to be removed to complete this operation, False otherwise.
     */
    public boolean pushDynamicShortcut(@NonNull ShortcutInfo newShortcut,
            @NonNull List<ShortcutInfo> changedShortcuts) {
        Preconditions.checkArgument(newShortcut.isEnabled(),
                "pushDynamicShortcuts() cannot publish disabled shortcuts");

        newShortcut.addFlags(ShortcutInfo.FLAG_DYNAMIC);

        changedShortcuts.clear();
        final ShortcutInfo oldShortcut = findShortcutById(newShortcut.getId());
        boolean deleted = false;

        if (oldShortcut == null || !oldShortcut.isDynamic()) {
            final ShortcutService service = mShortcutUser.mService;
            final int maxShortcuts = service.getMaxActivityShortcuts();

            final ArrayMap<ComponentName, ArrayList<ShortcutInfo>> all =
                    sortShortcutsToActivities();
            final ArrayList<ShortcutInfo> activityShortcuts = all.get(newShortcut.getActivity());

            if (activityShortcuts != null && activityShortcuts.size() > maxShortcuts) {
                // Root cause was discovered in b/233155034, so this should not be happening.
                service.wtf("Error pushing shortcut. There are already "
                        + activityShortcuts.size() + " shortcuts.");
            }
            if (activityShortcuts != null && activityShortcuts.size() == maxShortcuts) {
                // Max has reached. Delete the shortcut with lowest rank.
                // Sort by isManifestShortcut() and getRank().
                Collections.sort(activityShortcuts, mShortcutTypeAndRankComparator);

                final ShortcutInfo shortcut = activityShortcuts.get(maxShortcuts - 1);
                if (shortcut.isManifestShortcut()) {
                    // All shortcuts are manifest shortcuts and cannot be removed.
                    Slog.e(TAG, "Failed to remove manifest shortcut while pushing dynamic shortcut "
                            + newShortcut.getId());
                    return true;  // poppedShortcuts is empty which indicates a failure.
                }

                changedShortcuts.add(shortcut);
                deleted = deleteDynamicWithId(shortcut.getId(), /* ignoreInvisible =*/ true,
                        /*ignorePersistedShortcuts=*/ true) != null;
            }
        }
        if (oldShortcut != null) {
            // It's an update case.
            // Make sure the target is updatable. (i.e. should be mutable.)
            oldShortcut.ensureUpdatableWith(newShortcut, /*isUpdating=*/ false);

            // If it was originally pinned or cached, the new one should be pinned or cached too.
            newShortcut.addFlags(oldShortcut.getFlags()
                    & (ShortcutInfo.FLAG_PINNED | ShortcutInfo.FLAG_CACHED_ALL));
        }

        if (newShortcut.isExcludedFromSurfaces(ShortcutInfo.SURFACE_LAUNCHER)) {
            if (isAppSearchEnabled()) {
                synchronized (mLock) {
                    mTransientShortcuts.put(newShortcut.getId(), newShortcut);
                }
            }
        } else {
            forceReplaceShortcutInner(newShortcut);
        }
        if (isAppSearchEnabled()) {
            runAsSystem(() -> fromAppSearch().thenAccept(session ->
                    session.reportUsage(new ReportUsageRequest.Builder(
                            getPackageName(), newShortcut.getId()).build(), mExecutor, result -> {
                                    if (!result.isSuccess()) {
                                        Slog.e(TAG, "Failed to report usage via AppSearch. "
                                                + result.getErrorMessage());
                                    }
                            })));
        }
        return deleted;
    }

    private void ensureShortcutCountBeforePush() {
        final ShortcutService service = mShortcutUser.mService;
        // Ensure the total number of shortcuts doesn't exceed the hard limit per app.
        final int maxShortcutPerApp = service.getMaxAppShortcuts();
        synchronized (mLock) {
            final List<ShortcutInfo> appShortcuts = mShortcuts.values().stream().filter(si ->
                    !si.isPinned()).collect(Collectors.toList());
            if (appShortcuts.size() >= maxShortcutPerApp) {
                // Max has reached. Removes shortcuts until they fall within the hard cap.
                // Sort by isManifestShortcut(), isDynamic() and getLastChangedTimestamp().
                Collections.sort(appShortcuts, mShortcutTypeRankAndTimeComparator);

                while (appShortcuts.size() >= maxShortcutPerApp) {
                    final ShortcutInfo shortcut = appShortcuts.remove(appShortcuts.size() - 1);
                    if (shortcut.isDeclaredInManifest()) {
                        // All shortcuts are manifest shortcuts and cannot be removed.
                        throw new IllegalArgumentException(getPackageName() + " has published "
                                + appShortcuts.size() + " manifest shortcuts across different"
                                + " activities.");
                    }
                    forceDeleteShortcutInner(shortcut.getId());
                }
            }
        }
    }

    /**
     * Remove all shortcuts that aren't pinned, cached nor dynamic.
     *
     * @return List of removed shortcuts.
     */
    private List<ShortcutInfo> removeOrphans() {
        final List<ShortcutInfo> removeList = new ArrayList<>(1);
        forEachShortcut(si -> {
            if (si.isAlive()) return;
            removeList.add(si);
        });
        if (!removeList.isEmpty()) {
            for (int i = removeList.size() - 1; i >= 0; i--) {
                forceDeleteShortcutInner(removeList.get(i).getId());
            }
        }
        return removeList;
    }

    /**
     * Remove all dynamic shortcuts.
     *
     * @return List of shortcuts that actually got removed.
     */
    public List<ShortcutInfo> deleteAllDynamicShortcuts() {
        final long now = mShortcutUser.mService.injectCurrentTimeMillis();
        boolean changed = false;
        synchronized (mLock) {
            for (int i = mShortcuts.size() - 1; i >= 0; i--) {
                ShortcutInfo si = mShortcuts.valueAt(i);
                if (si.isDynamic() && si.isVisibleToPublisher()) {
                    changed = true;

                    si.setTimestamp(now);
                    si.clearFlags(ShortcutInfo.FLAG_DYNAMIC);
                    si.setRank(0); // It may still be pinned, so clear the rank.
                }
            }
        }
        removeAllShortcutsAsync();
        if (changed) {
            return removeOrphans();
        }
        return null;
    }

    /**
     * Remove a dynamic shortcut by ID.  It'll be removed from the dynamic set, but if the shortcut
     * is pinned or cached, it'll remain as a pinned or cached shortcut, and is still enabled.
     *
     * @return The deleted shortcut, or null if it was not actually removed because it is either
     * pinned or cached.
     */
    public ShortcutInfo deleteDynamicWithId(@NonNull String shortcutId, boolean ignoreInvisible,
            boolean ignorePersistedShortcuts) {
        return deleteOrDisableWithId(
                shortcutId, /* disable =*/ false, /* overrideImmutable=*/ false, ignoreInvisible,
                ShortcutInfo.DISABLED_REASON_NOT_DISABLED, ignorePersistedShortcuts);
    }

    /**
     * Disable a dynamic shortcut by ID. It'll be removed from the dynamic set, but if the shortcut
     * is pinned, it'll remain as a pinned shortcut, but will be disabled.
     *
     * @return Shortcut if the disabled shortcut got removed because it wasn't pinned. Or null if
     * it's still pinned.
     */
    private ShortcutInfo disableDynamicWithId(@NonNull String shortcutId, boolean ignoreInvisible,
            int disabledReason, boolean ignorePersistedShortcuts) {
        return deleteOrDisableWithId(shortcutId, /* disable =*/ true, /* overrideImmutable=*/ false,
                ignoreInvisible, disabledReason, ignorePersistedShortcuts);
    }

    /**
     * Remove a long lived shortcut by ID. If the shortcut is pinned, it'll remain as a pinned
     * shortcut, and is still enabled.
     *
     * @return The deleted shortcut, or null if it was not actually removed because it's pinned.
     */
    public ShortcutInfo deleteLongLivedWithId(@NonNull String shortcutId, boolean ignoreInvisible) {
        final ShortcutInfo shortcut = findShortcutById(shortcutId);
        if (shortcut != null) {
            mutateShortcut(shortcutId, null, si -> si.clearFlags(ShortcutInfo.FLAG_CACHED_ALL));
        }
        return deleteOrDisableWithId(
                shortcutId, /* disable =*/ false, /* overrideImmutable=*/ false, ignoreInvisible,
                ShortcutInfo.DISABLED_REASON_NOT_DISABLED, /*ignorePersistedShortcuts=*/ false);
    }

    /**
     * Disable a dynamic shortcut by ID.  It'll be removed from the dynamic set, but if the shortcut
     * is pinned, it'll remain as a pinned shortcut but will be disabled.
     *
     * @return Shortcut if the disabled shortcut got removed because it wasn't pinned. Or null if
     * it's still pinned.
     */
    public ShortcutInfo disableWithId(@NonNull String shortcutId, String disabledMessage,
            int disabledMessageResId, boolean overrideImmutable, boolean ignoreInvisible,
            int disabledReason) {
        final ShortcutInfo deleted = deleteOrDisableWithId(shortcutId, /* disable =*/ true,
                overrideImmutable, ignoreInvisible, disabledReason,
                /*ignorePersistedShortcuts=*/ false);

        // If disabled id still exists, it is pinned and we need to update the disabled message.
        mutateShortcut(shortcutId, null, disabled -> {
            if (disabled != null) {
                if (disabledMessage != null) {
                    disabled.setDisabledMessage(disabledMessage);
                } else if (disabledMessageResId != 0) {
                    disabled.setDisabledMessageResId(disabledMessageResId);
                    mShortcutUser.mService.fixUpShortcutResourceNamesAndValues(disabled);
                }
            }
        });

        return deleted;
    }

    @Nullable
    private ShortcutInfo deleteOrDisableWithId(@NonNull String shortcutId, boolean disable,
            boolean overrideImmutable, boolean ignoreInvisible, int disabledReason,
            boolean ignorePersistedShortcuts) {
        Preconditions.checkState(
                (disable == (disabledReason != ShortcutInfo.DISABLED_REASON_NOT_DISABLED)),
                "disable and disabledReason disagree: " + disable + " vs " + disabledReason);
        final ShortcutInfo oldShortcut = findShortcutById(shortcutId);

        if (oldShortcut == null || !oldShortcut.isEnabled()
                && (ignoreInvisible && !oldShortcut.isVisibleToPublisher())) {
            return null; // Doesn't exist or already disabled.
        }
        if (!overrideImmutable) {
            ensureNotImmutable(oldShortcut, /*ignoreInvisible=*/ true);
        }
        if (!ignorePersistedShortcuts) {
            removeShortcutAsync(shortcutId);
        }
        if (oldShortcut.isPinned() || oldShortcut.isCached()) {
            mutateShortcut(oldShortcut.getId(), oldShortcut, si -> {
                si.setRank(0);
                si.clearFlags(ShortcutInfo.FLAG_DYNAMIC | ShortcutInfo.FLAG_MANIFEST);
                if (disable) {
                    si.addFlags(ShortcutInfo.FLAG_DISABLED);
                    // Do not overwrite the disabled reason if one is already set.
                    if (si.getDisabledReason() == ShortcutInfo.DISABLED_REASON_NOT_DISABLED) {
                        si.setDisabledReason(disabledReason);
                    }
                }
                si.setTimestamp(mShortcutUser.mService.injectCurrentTimeMillis());

                // See ShortcutRequestPinProcessor.directPinShortcut().
                if (mShortcutUser.mService.isDummyMainActivity(si.getActivity())) {
                    si.setActivity(null);
                }
            });
            return null;
        } else {
            forceDeleteShortcutInner(shortcutId);
            return oldShortcut;
        }
    }

    public void enableWithId(@NonNull String shortcutId) {
        mutateShortcut(shortcutId, null, si -> {
            ensureNotImmutable(si, /*ignoreInvisible=*/ true);
            si.clearFlags(ShortcutInfo.FLAG_DISABLED);
            si.setDisabledReason(ShortcutInfo.DISABLED_REASON_NOT_DISABLED);
        });
    }

    public void updateInvisibleShortcutForPinRequestWith(@NonNull ShortcutInfo shortcut) {
        final ShortcutInfo source = findShortcutById(shortcut.getId());
        Objects.requireNonNull(source);

        mShortcutUser.mService.validateShortcutForPinRequest(shortcut);

        shortcut.addFlags(ShortcutInfo.FLAG_PINNED);

        forceReplaceShortcutInner(shortcut);

        adjustRanks();
    }

    /**
     * Called after a launcher updates the pinned set.  For each shortcut in this package,
     * set FLAG_PINNED if any launcher has pinned it.  Otherwise, clear it.
     *
     * <p>Then remove all shortcuts that are not dynamic and no longer pinned either.
     */
    public void refreshPinnedFlags() {
        final Set<String> pinnedShortcuts = new ArraySet<>();

        // First, gather the pinned set from each launcher.
        mShortcutUser.forAllLaunchers(launcherShortcuts -> {
            final ArraySet<String> pinned = launcherShortcuts.getPinnedShortcutIds(
                    getPackageName(), getPackageUserId());
            if (pinned == null || pinned.size() == 0) {
                return;
            }
            pinnedShortcuts.addAll(pinned);
        });
        // Secondly, update the pinned state if necessary.
        final List<ShortcutInfo> pinned = findAll(pinnedShortcuts);
        if (pinned != null) {
            pinned.forEach(si -> {
                if (!si.isPinned()) {
                    si.addFlags(ShortcutInfo.FLAG_PINNED);
                }
            });
        }
        forEachShortcutMutate(si -> {
            if (!pinnedShortcuts.contains(si.getId()) && si.isPinned()) {
                si.clearFlags(ShortcutInfo.FLAG_PINNED);
            }
        });
        // Then, schedule a background job to persist the pinned states.
        mShortcutUser.forAllLaunchers(ShortcutPackageItem::scheduleSave);

        // Lastly, remove the ones that are no longer pinned, cached nor dynamic.
        removeOrphans();
    }

    /**
     * Number of calls that the caller has made, since the last reset.
     *
     * <p>This takes care of the resetting the counter for foreground apps as well as after
     * locale changes.
     */
    public int getApiCallCount(boolean unlimited) {
        final ShortcutService s = mShortcutUser.mService;

        // Reset the counter if:
        // - the package is in foreground now.
        // - the package is *not* in foreground now, but was in foreground at some point
        // since the previous time it had been.
        if (s.isUidForegroundLocked(mPackageUid)
                || (mLastKnownForegroundElapsedTime
                    < s.getUidLastForegroundElapsedTimeLocked(mPackageUid))
                || unlimited) {
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
            if (ShortcutService.DEBUG || ShortcutService.DEBUG_REBOOT) {
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
    public boolean tryApiCall(boolean unlimited) {
        final ShortcutService s = mShortcutUser.mService;

        if (getApiCallCount(unlimited) >= s.mMaxUpdatesPerInterval) {
            return false;
        }
        mApiCallCount++;
        scheduleSave();
        return true;
    }

    public void resetRateLimiting() {
        if (ShortcutService.DEBUG) {
            Slog.d(TAG, "resetRateLimiting: " + getPackageName());
        }
        if (mApiCallCount > 0) {
            mApiCallCount = 0;
            scheduleSave();
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
            @Nullable Predicate<ShortcutInfo> filter, int cloneFlag) {
        findAll(result, filter, cloneFlag, null, 0, /*getPinnedByAnyLauncher=*/ false);
    }

    /**
     * Find all shortcuts that match {@code query}.
     *
     * This will also provide a "view" for each launcher -- a non-dynamic shortcut that's not pinned
     * by the calling launcher will not be included in the result, and also "isPinned" will be
     * adjusted for the caller too.
     */
    public void findAll(@NonNull List<ShortcutInfo> result,
            @Nullable Predicate<ShortcutInfo> filter, int cloneFlag,
            @Nullable String callingLauncher, int launcherUserId, boolean getPinnedByAnyLauncher) {
        if (getPackageInfo().isShadow()) {
            // Restored and the app not installed yet, so don't return any.
            return;
        }
        final ShortcutService s = mShortcutUser.mService;

        // Set of pinned shortcuts by the calling launcher.
        final ArraySet<String> pinnedByCallerSet = (callingLauncher == null) ? null
                : s.getLauncherShortcutsLocked(callingLauncher, getPackageUserId(), launcherUserId)
                        .getPinnedShortcutIds(getPackageName(), getPackageUserId());
        forEachShortcut(si -> filter(result, filter, cloneFlag, callingLauncher, pinnedByCallerSet,
                getPinnedByAnyLauncher, si));
    }

    private void filter(@NonNull final List<ShortcutInfo> result,
            @Nullable final Predicate<ShortcutInfo> query, final int cloneFlag,
            @Nullable final String callingLauncher,
            @NonNull final ArraySet<String> pinnedByCallerSet,
            final boolean getPinnedByAnyLauncher, @NonNull final ShortcutInfo si) {
        // Need to adjust PINNED flag depending on the caller.
        // Basically if the caller is a launcher (callingLauncher != null) and the launcher
        // isn't pinning it, then we need to clear PINNED for this caller.
        final boolean isPinnedByCaller = (callingLauncher == null)
                || ((pinnedByCallerSet != null) && pinnedByCallerSet.contains(si.getId()));

        if (!getPinnedByAnyLauncher) {
            if (si.isFloating() && !si.isCached()) {
                if (!isPinnedByCaller) {
                    return;
                }
            }
        }
        final ShortcutInfo clone = si.clone(cloneFlag);

        // Fix up isPinned for the caller.  Note we need to do it before the "test" callback,
        // since it may check isPinned.
        // However, if getPinnedByAnyLauncher is set, we do it after the test.
        if (!getPinnedByAnyLauncher) {
            if (!isPinnedByCaller) {
                clone.clearFlags(ShortcutInfo.FLAG_PINNED);
            }
        }
        if (query == null || query.test(clone)) {
            if (!isPinnedByCaller) {
                clone.clearFlags(ShortcutInfo.FLAG_PINNED);
            }
            result.add(clone);
        }
    }

    public void resetThrottling() {
        mApiCallCount = 0;
    }

    /**
     * Returns a list of ShortcutInfos that match the given intent filter and the category of
     * available ShareTarget definitions in this package.
     */
    public List<ShortcutManager.ShareShortcutInfo> getMatchingShareTargets(
            @NonNull final IntentFilter filter) {
        return getMatchingShareTargets(filter, null);
    }

    List<ShortcutManager.ShareShortcutInfo> getMatchingShareTargets(
            @NonNull final IntentFilter filter, @Nullable final String pkgName) {
        synchronized (mLock) {
            final List<ShareTargetInfo> matchedTargets = new ArrayList<>();
            for (int i = 0; i < mShareTargets.size(); i++) {
                final ShareTargetInfo target = mShareTargets.get(i);
                for (ShareTargetInfo.TargetData data : target.mTargetData) {
                    if (filter.hasDataType(data.mMimeType)) {
                        // Matched at least with one data type
                        matchedTargets.add(target);
                        break;
                    }
                }
            }

            if (matchedTargets.isEmpty()) {
                return new ArrayList<>();
            }

            // Get the list of all dynamic shortcuts in this package.
            final ArrayList<ShortcutInfo> shortcuts = new ArrayList<>();
            // Pass callingLauncher to ensure pinned flag marked by system ui, e.g. ShareSheet, are
            // included in the result
            findAll(shortcuts, ShortcutInfo::isNonManifestVisible,
                    ShortcutInfo.CLONE_REMOVE_FOR_APP_PREDICTION,
                    pkgName, 0, /*getPinnedByAnyLauncher=*/ false);

            final List<ShortcutManager.ShareShortcutInfo> result = new ArrayList<>();
            for (int i = 0; i < shortcuts.size(); i++) {
                final Set<String> categories = shortcuts.get(i).getCategories();
                if (categories == null || categories.isEmpty()) {
                    continue;
                }
                for (int j = 0; j < matchedTargets.size(); j++) {
                    // Shortcut must have all of share target categories
                    boolean hasAllCategories = true;
                    final ShareTargetInfo target = matchedTargets.get(j);
                    for (int q = 0; q < target.mCategories.length; q++) {
                        if (!categories.contains(target.mCategories[q])) {
                            hasAllCategories = false;
                            break;
                        }
                    }
                    if (hasAllCategories) {
                        result.add(new ShortcutManager.ShareShortcutInfo(shortcuts.get(i),
                                new ComponentName(getPackageName(), target.mTargetClass)));
                        break;
                    }
                }
            }
            return result;
        }
    }

    public boolean hasShareTargets() {
        synchronized (mLock) {
            return !mShareTargets.isEmpty();
        }
    }

    /**
     * Returns the number of shortcuts that can be used as a share target in the ShareSheet. Such
     * shortcuts must have a matching category with at least one of the defined ShareTargets from
     * the app's Xml resource.
     */
    int getSharingShortcutCount() {
        synchronized (mLock) {
            if (mShareTargets.isEmpty()) {
                return 0;
            }

            // Get the list of all dynamic shortcuts in this package
            final ArrayList<ShortcutInfo> shortcuts = new ArrayList<>();
            findAll(shortcuts, ShortcutInfo::isNonManifestVisible,
                    ShortcutInfo.CLONE_REMOVE_FOR_LAUNCHER);

            int sharingShortcutCount = 0;
            for (int i = 0; i < shortcuts.size(); i++) {
                final Set<String> categories = shortcuts.get(i).getCategories();
                if (categories == null || categories.isEmpty()) {
                    continue;
                }
                for (int j = 0; j < mShareTargets.size(); j++) {
                    // A SharingShortcut must have all of share target categories
                    boolean hasAllCategories = true;
                    final ShareTargetInfo target = mShareTargets.get(j);
                    for (int q = 0; q < target.mCategories.length; q++) {
                        if (!categories.contains(target.mCategories[q])) {
                            hasAllCategories = false;
                            break;
                        }
                    }
                    if (hasAllCategories) {
                        sharingShortcutCount++;
                        break;
                    }
                }
            }
            return sharingShortcutCount;
        }
    }

    /**
     * Return the filenames (excluding path names) of icon bitmap files from this package.
     */
    @GuardedBy("mLock")
    private ArraySet<String> getUsedBitmapFilesLocked() {
        final ArraySet<String> usedFiles = new ArraySet<>(1);
        forEachShortcut(si -> {
            if (si.getBitmapPath() != null) {
                usedFiles.add(getFileName(si.getBitmapPath()));
            }
        });
        return usedFiles;
    }

    public void cleanupDanglingBitmapFiles(@NonNull File path) {
        synchronized (mLock) {
            mShortcutBitmapSaver.waitForAllSavesLocked();
            final ArraySet<String> usedFiles = getUsedBitmapFilesLocked();

            for (File child : path.listFiles()) {
                if (!child.isFile()) {
                    continue;
                }
                final String name = child.getName();
                if (!usedFiles.contains(name)) {
                    if (ShortcutService.DEBUG) {
                        Slog.d(TAG, "Removing dangling bitmap file: " + child.getAbsolutePath());
                    }
                    child.delete();
                }
            }
        }
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
        final ShortcutService s = mShortcutUser.mService;

        // Normally the number of target activities is 1 or so, so no need to use a complex
        // structure like a set.
        final ArrayList<ComponentName> checked = new ArrayList<>(4);
        final boolean[] reject = new boolean[1];

        forEachShortcutStopWhen(si -> {
            final ComponentName activity = si.getActivity();

            if (checked.contains(activity)) {
                return false; // Already checked.
            }
            checked.add(activity);

            if ((activity != null)
                    && !s.injectIsActivityEnabledAndExported(activity, getOwnerUserId())) {
                reject[0] = true;
                return true; // Found at least 1 activity is disabled, so skip the rest.
            }
            return false;
        });
        return !reject[0];
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
        final long start = s.getStatStartTime();

        final PackageInfo pi;
        try {
            pi = mShortcutUser.mService.getPackageInfo(
                    getPackageName(), getPackageUserId());
            if (pi == null) {
                return false; // Shouldn't happen.
            }

            if (!isNewApp && !forceRescan) {
                // Return if the package hasn't changed, ie:
                // - version code hasn't change
                // - lastUpdateTime hasn't change
                // - all target activities are still enabled.

                // Note, system apps timestamps do *not* change after OTAs.  (But they do
                // after an adb sync or a local flash.)
                // This means if a system app's version code doesn't change on an OTA,
                // we don't notice it's updated.  But that's fine since their version code *should*
                // really change on OTAs.
                if ((getPackageInfo().getVersionCode() == pi.getLongVersionCode())
                        && (getPackageInfo().getLastUpdateTime() == pi.lastUpdateTime)
                        && areAllActivitiesStillEnabled()) {
                    return false;
                }
            }
        } finally {
            s.logDurationStat(Stats.PACKAGE_UPDATE_CHECK, start);
        }

        // Now prepare to publish manifest shortcuts.
        List<ShortcutInfo> newManifestShortcutList = null;
        int shareTargetSize = 0;
        synchronized (mLock) {
            try {
                shareTargetSize = mShareTargets.size();
                newManifestShortcutList = ShortcutParser.parseShortcuts(mShortcutUser.mService,
                        getPackageName(), getPackageUserId(), mShareTargets);
            } catch (IOException | XmlPullParserException e) {
                Slog.e(TAG, "Failed to load shortcuts from AndroidManifest.xml.", e);
            }
        }
        final int manifestShortcutSize = newManifestShortcutList == null ? 0
                : newManifestShortcutList.size();
        if (ShortcutService.DEBUG || ShortcutService.DEBUG_REBOOT) {
            Slog.d(TAG,
                    String.format(
                            "Package %s has %d manifest shortcut(s), and %d share target(s)",
                            getPackageName(), manifestShortcutSize, shareTargetSize));
        }

        if (isNewApp && (manifestShortcutSize == 0)) {
            // If it's a new app, and it doesn't have manifest shortcuts, then nothing to do.

            // If it's an update, then it may already have manifest shortcuts, which need to be
            // disabled.
            return false;
        }
        if (ShortcutService.DEBUG || ShortcutService.DEBUG_REBOOT) {
            Slog.d(TAG, String.format("Package %s %s, version %d -> %d", getPackageName(),
                    (isNewApp ? "added" : "updated"),
                    getPackageInfo().getVersionCode(), pi.getLongVersionCode()));
        }
        getPackageInfo().updateFromPackageInfo(pi);

        final long newVersionCode = getPackageInfo().getVersionCode();

        // See if there are any shortcuts that were prevented restoring because the app was of a
        // lower version, and re-enable them.
        {
            forEachShortcutMutate(si -> {
                if (si.getDisabledReason() != ShortcutInfo.DISABLED_REASON_VERSION_LOWER) {
                    return;
                }
                if (getPackageInfo().getBackupSourceVersionCode() > newVersionCode) {
                    if (ShortcutService.DEBUG) {
                        Slog.d(TAG,
                                String.format(
                                        "Shortcut %s require version %s, still not restored.",
                                        si.getId(),
                                        getPackageInfo().getBackupSourceVersionCode()));
                    }
                    return;
                }
                Slog.i(TAG, String.format("Restoring shortcut: %s", si.getId()));
                si.clearFlags(ShortcutInfo.FLAG_DISABLED);
                si.setDisabledReason(ShortcutInfo.DISABLED_REASON_NOT_DISABLED);
            });
        }

        // For existing shortcuts, update timestamps if they have any resources.
        // Also check if shortcuts' activities are still main activities.  Otherwise, disable them.
        if (!isNewApp) {
            final Resources publisherRes = getPackageResources();
            forEachShortcutMutate(si -> {
                // Disable dynamic shortcuts whose target activity is gone.
                if (si.isDynamic()) {
                    if (si.getActivity() == null) {
                        // Note if it's dynamic, it must have a target activity, but b/36228253.
                        s.wtf("null activity detected.");
                        // TODO Maybe remove it?
                    } else if (!s.injectIsMainActivity(si.getActivity(), getPackageUserId())) {
                        Slog.w(TAG, String.format(
                                "%s is no longer main activity. Disabling shorcut %s.",
                                getPackageName(), si.getId()));
                        if (disableDynamicWithId(si.getId(), /*ignoreInvisible*/ false,
                                ShortcutInfo.DISABLED_REASON_APP_CHANGED,
                                /*ignorePersistedShortcuts*/ false) != null) {
                            return;
                        }
                        // Still pinned, so fall-through and possibly update the resources.
                    }
                }

                if (!si.hasAnyResources() || publisherRes == null) {
                    return;
                }

                if (!si.isOriginallyFromManifest()) {
                    si.lookupAndFillInResourceIds(publisherRes);
                }

                // If this shortcut is not from a manifest, then update all resource IDs
                // from resource names.  (We don't allow resource strings for
                // non-manifest at the moment, but icons can still be resources.)
                si.setTimestamp(s.injectCurrentTimeMillis());
            });
        }

        // (Re-)publish manifest shortcut.
        publishManifestShortcuts(newManifestShortcutList);

        if (newManifestShortcutList != null) {
            pushOutExcessShortcuts();
        }

        s.verifyStates();

        // This will send a notification to the launcher, and also save .
        // TODO: List changed and removed manifest shortcuts and pass to packageShortcutsChanged()
        s.packageShortcutsChanged(this, null, null);

        return true;
    }

    private boolean publishManifestShortcuts(List<ShortcutInfo> newManifestShortcutList) {
        if (ShortcutService.DEBUG || ShortcutService.DEBUG_REBOOT) {
            Slog.d(TAG, String.format(
                    "Package %s: publishing manifest shortcuts", getPackageName()));
        }
        boolean changed = false;

        // Keep the previous IDs.
        final ArraySet<String> toDisableList = new ArraySet<>(1);
        forEachShortcut(si -> {
            if (si.isManifestShortcut()) {
                toDisableList.add(si.getId());
            }
        });

        // Publish new ones.
        if (newManifestShortcutList != null) {
            final int newListSize = newManifestShortcutList.size();

            for (int i = 0; i < newListSize; i++) {
                changed = true;

                final ShortcutInfo newShortcut = newManifestShortcutList.get(i);
                final boolean newDisabled = !newShortcut.isEnabled();

                final String id = newShortcut.getId();
                final ShortcutInfo oldShortcut = findShortcutById(id);

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
                forceReplaceShortcutInner(newShortcut); // This will clean up the old one too.

                if (!newDisabled && !toDisableList.isEmpty()) {
                    // Still alive, don't remove.
                    toDisableList.remove(id);
                }
            }
        }

        // Disable the previous manifest shortcuts that are no longer in the manifest.
        if (!toDisableList.isEmpty()) {
            if (ShortcutService.DEBUG) {
                Slog.d(TAG, String.format(
                        "Package %s: disabling %d stale shortcuts", getPackageName(),
                        toDisableList.size()));
            }
            for (int i = toDisableList.size() - 1; i >= 0; i--) {
                changed = true;

                final String id = toDisableList.valueAt(i);

                disableWithId(id, /* disable message =*/ null,
                        /* disable message resid */ 0,
                        /* overrideImmutable=*/ true, /*ignoreInvisible=*/ false,
                        ShortcutInfo.DISABLED_REASON_APP_CHANGED);
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
                deleteDynamicWithId(shortcut.getId(), /*ignoreInvisible=*/ true,
                        /*ignorePersistedShortcuts=*/ true);
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
     * To sort by isManifestShortcut(), isDynamic(), getRank() and
     * getLastChangedTimestamp(). i.e. manifest shortcuts come before non-manifest shortcuts,
     * dynamic shortcuts come before floating shortcuts, then sort by last changed timestamp.
     *
     * This is used to decide which shortcuts to remove when the total number of shortcuts retained
     * for the app exceeds the limit defined in {@link ShortcutService#getMaxAppShortcuts()}.
     *
     * (Note the number of manifest shortcuts is always <= the max number, because if there are
     * more, ShortcutParser would ignore the rest.)
     */
    final Comparator<ShortcutInfo> mShortcutTypeRankAndTimeComparator = (ShortcutInfo a,
            ShortcutInfo b) -> {
        if (a.isDeclaredInManifest() && !b.isDeclaredInManifest()) {
            return -1;
        }
        if (!a.isDeclaredInManifest() && b.isDeclaredInManifest()) {
            return 1;
        }
        if (a.isDynamic() && b.isDynamic()) {
            return Integer.compare(a.getRank(), b.getRank());
        }
        if (a.isDynamic()) {
            return -1;
        }
        if (b.isDynamic()) {
            return 1;
        }
        if (a.isCached() && b.isCached()) {
            // if both shortcuts are cached, prioritize shortcuts cached by people tile,
            if (a.hasFlags(ShortcutInfo.FLAG_CACHED_PEOPLE_TILE)
                    && !b.hasFlags(ShortcutInfo.FLAG_CACHED_PEOPLE_TILE)) {
                return -1;
            } else if (!a.hasFlags(ShortcutInfo.FLAG_CACHED_PEOPLE_TILE)
                    && b.hasFlags(ShortcutInfo.FLAG_CACHED_PEOPLE_TILE)) {
                return 1;
            }
            // followed by bubbles.
            if (a.hasFlags(ShortcutInfo.FLAG_CACHED_BUBBLES)
                    && !b.hasFlags(ShortcutInfo.FLAG_CACHED_BUBBLES)) {
                return -1;
            } else if (!a.hasFlags(ShortcutInfo.FLAG_CACHED_BUBBLES)
                    && b.hasFlags(ShortcutInfo.FLAG_CACHED_BUBBLES)) {
                return 1;
            }
        }
        if (a.isCached()) {
            return -1;
        }
        if (b.isCached()) {
            return 1;
        }
        return Long.compare(b.getLastChangedTimestamp(), a.getLastChangedTimestamp());
    };

    /**
     * Build a list of shortcuts for each target activity and return as a map. The result won't
     * contain "floating" shortcuts because they don't belong on any activities.
     */
    private ArrayMap<ComponentName, ArrayList<ShortcutInfo>> sortShortcutsToActivities() {
        final ArrayMap<ComponentName, ArrayList<ShortcutInfo>> activitiesToShortcuts
                = new ArrayMap<>();
        forEachShortcut(si -> {
            if (si.isFloating()) {
                return; // Ignore floating shortcuts, which are not tied to any activities.
            }

            final ComponentName activity = si.getActivity();
            if (activity == null) {
                mShortcutUser.mService.wtf("null activity detected.");
                return;
            }

            ArrayList<ShortcutInfo> list = activitiesToShortcuts.computeIfAbsent(activity,
                    k -> new ArrayList<>());
            list.add(si);
        });
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
        forEachShortcut(shortcut -> {
            if (shortcut.isManifestShortcut()) {
                incrementCountForActivity(counts, shortcut.getActivity(), 1);
            } else if (shortcut.isDynamic() && (operation != ShortcutService.OPERATION_SET)) {
                incrementCountForActivity(counts, shortcut.getActivity(), 1);
            }
        });

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

            final ShortcutInfo original = findShortcutById(newShortcut.getId());
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

        final Resources publisherRes = getPackageResources();
        final List<ShortcutInfo> changedShortcuts = new ArrayList<>(1);

        if (publisherRes != null) {
            forEachShortcutMutate(si -> {
                if (!si.hasStringResources()) return;
                si.resolveResourceStrings(publisherRes);
                si.setTimestamp(s.injectCurrentTimeMillis());
                changedShortcuts.add(si);
            });
        }
        if (!CollectionUtils.isEmpty(changedShortcuts)) {
            s.packageShortcutsChanged(this, changedShortcuts, null);
        }
    }

    /** Clears the implicit ranks for all shortcuts. */
    public void clearAllImplicitRanks() {
        forEachShortcutMutate(ShortcutInfo::clearImplicitRankAndRankChangedFlag);
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
        // If they're still tie, just sort by their IDs.
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
        forEachShortcutMutate(si -> {
            if (si.isFloating() && si.getRank() != 0) {
                si.setTimestamp(now);
                si.setRank(0);
            }
        });

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
                    s.wtf("Non-dynamic shortcut found. " + si.toInsecureString());
                    continue;
                }
                final int thisRank = rank++;
                if (si.getRank() != thisRank) {
                    mutateShortcut(si.getId(), si, shortcut -> {
                        shortcut.setTimestamp(now);
                        shortcut.setRank(thisRank);
                    });
                }
            }
        }
    }

    /** @return true if there's any shortcuts that are not manifest shortcuts. */
    public boolean hasNonManifestShortcuts() {
        final boolean[] condition = new boolean[1];
        forEachShortcutStopWhen(si -> {
            if (!si.isDeclaredInManifest()) {
                condition[0] = true;
                return true;
            }
            return false;
        });
        return condition[0];
    }

    void reportShortcutUsed(@NonNull final UsageStatsManagerInternal usageStatsManagerInternal,
            @NonNull final String shortcutId) {
        synchronized (mLock) {
            final long currentTS = SystemClock.elapsedRealtime();
            final ShortcutService s = mShortcutUser.mService;
            if (mLastReportedTime.isEmpty()
                    || mLastReportedTime.size() < REPORT_USAGE_BUFFER_SIZE) {
                mLastReportedTime.add(currentTS);
            } else if (currentTS - mLastReportedTime.get(0) > s.mSaveDelayMillis) {
                mLastReportedTime.remove(0);
                mLastReportedTime.add(currentTS);
            } else {
                return;
            }
            final long token = s.injectClearCallingIdentity();
            try {
                usageStatsManagerInternal.reportShortcutUsage(getPackageName(), shortcutId,
                        getUser().getUserId());
            } finally {
                s.injectRestoreCallingIdentity(token);
            }
        }
    }

    public void dump(@NonNull PrintWriter pw, @NonNull String prefix, DumpFilter filter) {
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
        pw.print(getApiCallCount(/*unlimited=*/ false));
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
        final long[] totalBitmapSize = new long[1];
        forEachShortcut(si -> {
            pw.println(si.toDumpString(prefix + "    "));
            if (si.getBitmapPath() != null) {
                final long len = new File(si.getBitmapPath()).length();
                pw.print(prefix);
                pw.print("      ");
                pw.print("bitmap size=");
                pw.println(len);

                totalBitmapSize[0] += len;
            }
        });
        pw.print(prefix);
        pw.print("  ");
        pw.print("Total bitmap size: ");
        pw.print(totalBitmapSize[0]);
        pw.print(" (");
        pw.print(Formatter.formatFileSize(mShortcutUser.mService.mContext, totalBitmapSize[0]));
        pw.println(")");

        pw.println();
        synchronized (mLock) {
            mShortcutBitmapSaver.dumpLocked(pw, "  ");
        }
    }

    public void dumpShortcuts(@NonNull PrintWriter pw, int matchFlags) {
        final boolean matchDynamic = (matchFlags & ShortcutManager.FLAG_MATCH_DYNAMIC) != 0;
        final boolean matchPinned = (matchFlags & ShortcutManager.FLAG_MATCH_PINNED) != 0;
        final boolean matchManifest = (matchFlags & ShortcutManager.FLAG_MATCH_MANIFEST) != 0;
        final boolean matchCached = (matchFlags & ShortcutManager.FLAG_MATCH_CACHED) != 0;

        final int shortcutFlags = (matchDynamic ? ShortcutInfo.FLAG_DYNAMIC : 0)
                | (matchPinned ? ShortcutInfo.FLAG_PINNED : 0)
                | (matchManifest ? ShortcutInfo.FLAG_MANIFEST : 0)
                | (matchCached ? ShortcutInfo.FLAG_CACHED_ALL : 0);

        forEachShortcut(si -> {
            if ((si.getFlags() & shortcutFlags) != 0) {
                pw.println(si.toDumpString(""));
            }
        });
    }

    @Override
    public JSONObject dumpCheckin(boolean clear) throws JSONException {
        final JSONObject result = super.dumpCheckin(clear);

        final int[] numDynamic = new int[1];
        final int[] numPinned = new int[1];
        final int[] numManifest = new int[1];
        final int[] numBitmaps = new int[1];
        final long[] totalBitmapSize = new long[1];

        forEachShortcut(si -> {
            if (si.isDynamic()) numDynamic[0]++;
            if (si.isDeclaredInManifest()) numManifest[0]++;
            if (si.isPinned()) numPinned[0]++;

            if (si.getBitmapPath() != null) {
                numBitmaps[0]++;
                totalBitmapSize[0] += new File(si.getBitmapPath()).length();
            }
        });

        result.put(KEY_DYNAMIC, numDynamic[0]);
        result.put(KEY_MANIFEST, numManifest[0]);
        result.put(KEY_PINNED, numPinned[0]);
        result.put(KEY_BITMAPS, numBitmaps[0]);
        result.put(KEY_BITMAP_BYTES, totalBitmapSize[0]);

        // TODO Log update frequency too.

        return result;
    }

    private boolean hasNoShortcut() {
        if (!isAppSearchEnabled()) {
            return getShortcutCount() == 0;
        }
        final boolean[] hasAnyShortcut = new boolean[1];
        forEachShortcutStopWhen(si -> {
            hasAnyShortcut[0] = true;
            return true;
        });
        return !hasAnyShortcut[0];
    }

    @Override
    public void saveToXml(@NonNull TypedXmlSerializer out, boolean forBackup)
            throws IOException, XmlPullParserException {
        synchronized (mLock) {
            final int size = mShortcuts.size();
            final int shareTargetSize = mShareTargets.size();

            if (hasNoShortcut() && shareTargetSize == 0 && mApiCallCount == 0) {
                return; // nothing to write.
            }

            out.startTag(null, TAG_ROOT);

            ShortcutService.writeAttr(out, ATTR_NAME, getPackageName());
            ShortcutService.writeAttr(out, ATTR_CALL_COUNT, mApiCallCount);
            ShortcutService.writeAttr(out, ATTR_LAST_RESET, mLastResetTime);
            if (!forBackup) {
                ShortcutService.writeAttr(out, ATTR_SCHEMA_VERSON, mIsAppSearchSchemaUpToDate
                        ? AppSearchShortcutInfo.SCHEMA_VERSION : 0);
            }
            getPackageInfo().saveToXml(mShortcutUser.mService, out, forBackup);

            for (int j = 0; j < size; j++) {
                saveShortcut(
                        out, mShortcuts.valueAt(j), forBackup, getPackageInfo().isBackupAllowed());
            }

            if (!forBackup) {
                for (int j = 0; j < shareTargetSize; j++) {
                    mShareTargets.get(j).saveToXml(out);
                }
            }

            out.endTag(null, TAG_ROOT);
        }
    }

    private void saveShortcut(TypedXmlSerializer out, ShortcutInfo si, boolean forBackup,
            boolean appSupportsBackup)
            throws IOException, XmlPullParserException {

        final ShortcutService s = mShortcutUser.mService;

        if (forBackup) {
            if (!(si.isPinned() && si.isEnabled())) {
                // We only backup pinned shortcuts that are enabled.
                // Note, this means, shortcuts that are restored but are blocked restore, e.g. due
                // to a lower version code, will not be ported to a new device.
                return;
            }
        }
        final boolean shouldBackupDetails =
                !forBackup // It's not backup
                || appSupportsBackup; // Or, it's a backup and app supports backup.

        // Note: at this point no shortcuts should have bitmaps pending save, but if they do,
        // just remove the bitmap.
        if (si.isIconPendingSave()) {
            removeIcon(si);
        }
        out.startTag(null, TAG_SHORTCUT);
        ShortcutService.writeAttr(out, ATTR_ID, si.getId());
        // writeAttr(out, "package", si.getPackageName()); // not needed
        ShortcutService.writeAttr(out, ATTR_ACTIVITY, si.getActivity());
        // writeAttr(out, "icon", si.getIcon());  // We don't save it.
        ShortcutService.writeAttr(out, ATTR_TITLE, si.getTitle());
        ShortcutService.writeAttr(out, ATTR_TITLE_RES_ID, si.getTitleResId());
        ShortcutService.writeAttr(out, ATTR_TITLE_RES_NAME, si.getTitleResName());
        ShortcutService.writeAttr(out, ATTR_SPLASH_SCREEN_THEME_NAME, si.getStartingThemeResName());
        ShortcutService.writeAttr(out, ATTR_TEXT, si.getText());
        ShortcutService.writeAttr(out, ATTR_TEXT_RES_ID, si.getTextResId());
        ShortcutService.writeAttr(out, ATTR_TEXT_RES_NAME, si.getTextResName());
        if (shouldBackupDetails) {
            ShortcutService.writeAttr(out, ATTR_DISABLED_MESSAGE, si.getDisabledMessage());
            ShortcutService.writeAttr(out, ATTR_DISABLED_MESSAGE_RES_ID,
                    si.getDisabledMessageResourceId());
            ShortcutService.writeAttr(out, ATTR_DISABLED_MESSAGE_RES_NAME,
                    si.getDisabledMessageResName());
        }
        ShortcutService.writeAttr(out, ATTR_DISABLED_REASON, si.getDisabledReason());
        ShortcutService.writeAttr(out, ATTR_TIMESTAMP,
                si.getLastChangedTimestamp());
        final LocusId locusId = si.getLocusId();
        if (locusId != null) {
            ShortcutService.writeAttr(out, ATTR_LOCUS_ID, si.getLocusId().getId());
        }
        if (forBackup) {
            // Don't write icon information.  Also drop the dynamic flag.

            int flags = si.getFlags() &
                    ~(ShortcutInfo.FLAG_HAS_ICON_FILE | ShortcutInfo.FLAG_HAS_ICON_RES
                            | ShortcutInfo.FLAG_ICON_FILE_PENDING_SAVE
                            | ShortcutInfo.FLAG_DYNAMIC
                            | ShortcutInfo.FLAG_HAS_ICON_URI | ShortcutInfo.FLAG_ADAPTIVE_BITMAP);
            ShortcutService.writeAttr(out, ATTR_FLAGS, flags);

            // Set the publisher version code at every backup.
            final long packageVersionCode = getPackageInfo().getVersionCode();
            if (packageVersionCode == 0) {
                s.wtf("Package version code should be available at this point.");
                // However, 0 is a valid version code, so we just go ahead with it...
            }
        } else {
            // When writing for backup, ranks shouldn't be saved, since shortcuts won't be restored
            // as dynamic.
            ShortcutService.writeAttr(out, ATTR_RANK, si.getRank());

            ShortcutService.writeAttr(out, ATTR_FLAGS, si.getFlags());
            ShortcutService.writeAttr(out, ATTR_ICON_RES_ID, si.getIconResourceId());
            ShortcutService.writeAttr(out, ATTR_ICON_RES_NAME, si.getIconResName());
            ShortcutService.writeAttr(out, ATTR_BITMAP_PATH, si.getBitmapPath());
            ShortcutService.writeAttr(out, ATTR_ICON_URI, si.getIconUri());
        }

        if (shouldBackupDetails) {
            {
                final Set<String> cat = si.getCategories();
                if (cat != null && cat.size() > 0) {
                    out.startTag(null, TAG_CATEGORIES);
                    XmlUtils.writeStringArrayXml(cat.toArray(new String[cat.size()]),
                            NAME_CATEGORIES, XmlUtils.makeTyped(out));
                    out.endTag(null, TAG_CATEGORIES);
                }
            }
            if (!forBackup) {  // Don't backup the persons field.
                final Person[] persons = si.getPersons();
                if (!ArrayUtils.isEmpty(persons)) {
                    for (int i = 0; i < persons.length; i++) {
                        final Person p = persons[i];

                        out.startTag(null, TAG_PERSON);
                        ShortcutService.writeAttr(out, ATTR_PERSON_NAME, p.getName());
                        ShortcutService.writeAttr(out, ATTR_PERSON_URI, p.getUri());
                        ShortcutService.writeAttr(out, ATTR_PERSON_KEY, p.getKey());
                        ShortcutService.writeAttr(out, ATTR_PERSON_IS_BOT, p.isBot());
                        ShortcutService.writeAttr(out, ATTR_PERSON_IS_IMPORTANT, p.isImportant());
                        out.endTag(null, TAG_PERSON);
                    }
                }
            }
            final Intent[] intentsNoExtras = si.getIntentsNoExtras();
            final PersistableBundle[] intentsExtras = si.getIntentPersistableExtrases();
            if (intentsNoExtras != null && intentsExtras != null) {
                final int numIntents = intentsNoExtras.length;
                for (int i = 0; i < numIntents; i++) {
                    out.startTag(null, TAG_INTENT);
                    ShortcutService.writeAttr(out, ATTR_INTENT_NO_EXTRA, intentsNoExtras[i]);
                    ShortcutService.writeTagExtra(out, TAG_EXTRAS, intentsExtras[i]);
                    out.endTag(null, TAG_INTENT);
                }
            }

            ShortcutService.writeTagExtra(out, TAG_EXTRAS, si.getExtras());

            final Map<String, Map<String, List<String>>> capabilityBindings =
                    si.getCapabilityBindingsInternal();
            if (capabilityBindings != null && !capabilityBindings.isEmpty()) {
                XmlUtils.writeMapXml(capabilityBindings, NAME_CAPABILITY, out);
            }
        }

        out.endTag(null, TAG_SHORTCUT);
    }

    public static ShortcutPackage loadFromFile(ShortcutService s, ShortcutUser shortcutUser,
            File path, boolean fromBackup) {

        final AtomicFile file = new AtomicFile(path);
        final FileInputStream in;
        try {
            in = file.openRead();
        } catch (FileNotFoundException e) {
            if (ShortcutService.DEBUG) {
                Slog.d(TAG, "Not found " + path);
            }
            return null;
        }

        try {
            ShortcutPackage ret = null;
            TypedXmlPullParser parser = Xml.resolvePullParser(in);

            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }
                final int depth = parser.getDepth();

                final String tag = parser.getName();
                if (ShortcutService.DEBUG_LOAD || ShortcutService.DEBUG_REBOOT) {
                    Slog.d(TAG, String.format("depth=%d type=%d name=%s", depth, type, tag));
                }
                if ((depth == 1) && TAG_ROOT.equals(tag)) {
                    ret = loadFromXml(s, shortcutUser, parser, fromBackup);
                    continue;
                }
                ShortcutService.throwForInvalidTag(depth, tag);
            }
            return ret;
        } catch (IOException | XmlPullParserException e) {
            Slog.e(TAG, "Failed to read file " + file.getBaseFile(), e);
            return null;
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    public static ShortcutPackage loadFromXml(ShortcutService s, ShortcutUser shortcutUser,
            TypedXmlPullParser parser, boolean fromBackup)
            throws IOException, XmlPullParserException {

        final String packageName = ShortcutService.parseStringAttribute(parser,
                ATTR_NAME);

        final ShortcutPackage ret = new ShortcutPackage(shortcutUser,
                shortcutUser.getUserId(), packageName);
        synchronized (ret.mLock) {
            ret.mIsAppSearchSchemaUpToDate = ShortcutService.parseIntAttribute(
                    parser, ATTR_SCHEMA_VERSON, 0) == AppSearchShortcutInfo.SCHEMA_VERSION;

            ret.mApiCallCount = ShortcutService.parseIntAttribute(parser, ATTR_CALL_COUNT);
            ret.mLastResetTime = ShortcutService.parseLongAttribute(parser, ATTR_LAST_RESET);

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
                            try {
                                final ShortcutInfo si = parseShortcut(parser, packageName,
                                        shortcutUser.getUserId(), fromBackup);
                                // Don't use addShortcut(), we don't need to save the icon.
                                ret.mShortcuts.put(si.getId(), si);
                            } catch (IOException e) {
                                // Don't ignore IO exceptions.
                                throw e;
                            } catch (Exception e) {
                                // b/246540168 malformed shortcuts should be ignored
                                Slog.e(TAG, "Failed parsing shortcut.", e);
                            }
                            continue;
                        case TAG_SHARE_TARGET:
                            ret.mShareTargets.add(ShareTargetInfo.loadFromXml(parser));
                            continue;
                    }
                }
                ShortcutService.warnForInvalidTag(depth, tag);
            }
        }
        return ret;
    }

    private static ShortcutInfo parseShortcut(TypedXmlPullParser parser, String packageName,
            @UserIdInt int userId, boolean fromBackup)
            throws IOException, XmlPullParserException {
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
        int disabledReason;
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
        String iconUri;
        final String locusIdString;
        String splashScreenThemeResName;
        int backupVersionCode;
        ArraySet<String> categories = null;
        ArrayList<Person> persons = new ArrayList<>();
        Map<String, Map<String, List<String>>> capabilityBindings = null;

        id = ShortcutService.parseStringAttribute(parser, ATTR_ID);
        activityComponent = ShortcutService.parseComponentNameAttribute(parser,
                ATTR_ACTIVITY);
        title = ShortcutService.parseStringAttribute(parser, ATTR_TITLE);
        titleResId = ShortcutService.parseIntAttribute(parser, ATTR_TITLE_RES_ID);
        titleResName = ShortcutService.parseStringAttribute(parser, ATTR_TITLE_RES_NAME);
        splashScreenThemeResName = ShortcutService.parseStringAttribute(parser,
                ATTR_SPLASH_SCREEN_THEME_NAME);
        text = ShortcutService.parseStringAttribute(parser, ATTR_TEXT);
        textResId = ShortcutService.parseIntAttribute(parser, ATTR_TEXT_RES_ID);
        textResName = ShortcutService.parseStringAttribute(parser, ATTR_TEXT_RES_NAME);
        disabledMessage = ShortcutService.parseStringAttribute(parser, ATTR_DISABLED_MESSAGE);
        disabledMessageResId = ShortcutService.parseIntAttribute(parser,
                ATTR_DISABLED_MESSAGE_RES_ID);
        disabledMessageResName = ShortcutService.parseStringAttribute(parser,
                ATTR_DISABLED_MESSAGE_RES_NAME);
        disabledReason = ShortcutService.parseIntAttribute(parser, ATTR_DISABLED_REASON);
        intentLegacy = ShortcutService.parseIntentAttributeNoDefault(parser, ATTR_INTENT_LEGACY);
        rank = (int) ShortcutService.parseLongAttribute(parser, ATTR_RANK);
        lastChangedTimestamp = ShortcutService.parseLongAttribute(parser, ATTR_TIMESTAMP);
        flags = (int) ShortcutService.parseLongAttribute(parser, ATTR_FLAGS);
        iconResId = (int) ShortcutService.parseLongAttribute(parser, ATTR_ICON_RES_ID);
        iconResName = ShortcutService.parseStringAttribute(parser, ATTR_ICON_RES_NAME);
        bitmapPath = ShortcutService.parseStringAttribute(parser, ATTR_BITMAP_PATH);
        iconUri = ShortcutService.parseStringAttribute(parser, ATTR_ICON_URI);
        locusIdString = ShortcutService.parseStringAttribute(parser, ATTR_LOCUS_ID);

        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            final int depth = parser.getDepth();
            final String tag = parser.getName();
            if (ShortcutService.DEBUG_LOAD || ShortcutService.DEBUG_REBOOT) {
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
                case TAG_PERSON:
                    persons.add(parsePerson(parser));
                    continue;
                case TAG_STRING_ARRAY_XMLUTILS:
                    if (NAME_CATEGORIES.equals(ShortcutService.parseStringAttribute(parser,
                            ATTR_NAME_XMLUTILS))) {
                        final String[] ar = XmlUtils.readThisStringArrayXml(
                                XmlUtils.makeTyped(parser), TAG_STRING_ARRAY_XMLUTILS, null);
                        categories = new ArraySet<>(ar.length);
                        for (int i = 0; i < ar.length; i++) {
                            categories.add(ar[i]);
                        }
                    }
                    continue;
                case TAG_MAP_XMLUTILS:
                    if (NAME_CAPABILITY.equals(ShortcutService.parseStringAttribute(parser,
                            ATTR_NAME_XMLUTILS))) {
                        capabilityBindings = (Map<String, Map<String, List<String>>>)
                                XmlUtils.readValueXml(parser, new String[1]);
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


        if ((disabledReason == ShortcutInfo.DISABLED_REASON_NOT_DISABLED)
                && ((flags & ShortcutInfo.FLAG_DISABLED) != 0)) {
            // We didn't used to have the disabled reason, so if a shortcut is disabled
            // and has no reason, we assume it was disabled by publisher.
            disabledReason = ShortcutInfo.DISABLED_REASON_BY_APP;
        }

        // All restored shortcuts are initially "shadow".
        if (fromBackup) {
            flags |= ShortcutInfo.FLAG_SHADOW;
        }

        final LocusId locusId = locusIdString == null ? null : new LocusId(locusIdString);

        return new ShortcutInfo(
                userId, id, packageName, activityComponent, /* icon= */ null,
                title, titleResId, titleResName, text, textResId, textResName,
                disabledMessage, disabledMessageResId, disabledMessageResName,
                categories,
                intents.toArray(new Intent[intents.size()]),
                rank, extras, lastChangedTimestamp, flags,
                iconResId, iconResName, bitmapPath, iconUri,
                disabledReason, persons.toArray(new Person[persons.size()]), locusId,
                splashScreenThemeResName, capabilityBindings);
    }

    private static Intent parseIntent(TypedXmlPullParser parser)
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
            if (ShortcutService.DEBUG_LOAD || ShortcutService.DEBUG_REBOOT) {
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

    private static Person parsePerson(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        CharSequence name = ShortcutService.parseStringAttribute(parser, ATTR_PERSON_NAME);
        String uri = ShortcutService.parseStringAttribute(parser, ATTR_PERSON_URI);
        String key = ShortcutService.parseStringAttribute(parser, ATTR_PERSON_KEY);
        boolean isBot = ShortcutService.parseBooleanAttribute(parser, ATTR_PERSON_IS_BOT);
        boolean isImportant = ShortcutService.parseBooleanAttribute(parser,
                ATTR_PERSON_IS_IMPORTANT);

        Person.Builder builder = new Person.Builder();
        builder.setName(name).setUri(uri).setKey(key).setBot(isBot).setImportant(isImportant);
        return builder.build();
    }

    @VisibleForTesting
    List<ShortcutInfo> getAllShortcutsForTest() {
        final List<ShortcutInfo> ret = new ArrayList<>(1);
        forEachShortcut(ret::add);
        return ret;
    }

    @VisibleForTesting
    List<ShareTargetInfo> getAllShareTargetsForTest() {
        synchronized (mLock) {
            return new ArrayList<>(mShareTargets);
        }
    }

    @Override
    public void verifyStates() {
        super.verifyStates();

        final boolean[] failed = new boolean[1];

        final ShortcutService s = mShortcutUser.mService;

        final ArrayMap<ComponentName, ArrayList<ShortcutInfo>> all =
                sortShortcutsToActivities();

        // Make sure each activity won't have more than max shortcuts.
        for (int outer = all.size() - 1; outer >= 0; outer--) {
            final ArrayList<ShortcutInfo> list = all.valueAt(outer);
            if (list.size() > mShortcutUser.mService.getMaxActivityShortcuts()) {
                failed[0] = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": activity " + all.keyAt(outer)
                        + " has " + all.valueAt(outer).size() + " shortcuts.");
            }

            // Sort by rank.
            Collections.sort(list, (a, b) -> Integer.compare(a.getRank(), b.getRank()));

            // Split into two arrays for each kind.
            final ArrayList<ShortcutInfo> dynamicList = new ArrayList<>(list);
            dynamicList.removeIf((si) -> !si.isDynamic());

            final ArrayList<ShortcutInfo> manifestList = new ArrayList<>(list);
            manifestList.removeIf((si) -> !si.isManifestShortcut());

            verifyRanksSequential(dynamicList);
            verifyRanksSequential(manifestList);
        }

        // Verify each shortcut's status.
        forEachShortcut(si -> {
            if (!(si.isDeclaredInManifest() || si.isDynamic() || si.isPinned() || si.isCached())) {
                failed[0] = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " is not manifest, dynamic or pinned.");
            }
            if (si.isDeclaredInManifest() && si.isDynamic()) {
                failed[0] = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " is both dynamic and manifest at the same time.");
            }
            if (si.getActivity() == null && !si.isFloating()) {
                failed[0] = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " has null activity, but not floating.");
            }
            if ((si.isDynamic() || si.isManifestShortcut()) && !si.isEnabled()) {
                failed[0] = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " is not floating, but is disabled.");
            }
            if (si.isFloating() && si.getRank() != 0) {
                failed[0] = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " is floating, but has rank=" + si.getRank());
            }
            if (si.getIcon() != null) {
                failed[0] = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " still has an icon");
            }
            if (si.hasAdaptiveBitmap() && !(si.hasIconFile() || si.hasIconUri())) {
                failed[0] = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " has adaptive bitmap but was not saved to a file nor has icon uri.");
            }
            if (si.hasIconFile() && si.hasIconResource()) {
                failed[0] = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " has both resource and bitmap icons");
            }
            if (si.hasIconFile() && si.hasIconUri()) {
                failed[0] = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " has both url and bitmap icons");
            }
            if (si.hasIconUri() && si.hasIconResource()) {
                failed[0] = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " has both url and resource icons");
            }
            if (si.isEnabled()
                    != (si.getDisabledReason() == ShortcutInfo.DISABLED_REASON_NOT_DISABLED)) {
                failed[0] = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " isEnabled() and getDisabledReason() disagree: "
                        + si.isEnabled() + " vs " + si.getDisabledReason());
            }
            if ((si.getDisabledReason() == ShortcutInfo.DISABLED_REASON_VERSION_LOWER)
                    && (getPackageInfo().getBackupSourceVersionCode()
                    == ShortcutInfo.VERSION_CODE_UNKNOWN)) {
                failed[0] = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " RESTORED_VERSION_LOWER with no backup source version code.");
            }
            if (s.isDummyMainActivity(si.getActivity())) {
                failed[0] = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " has a dummy target activity");
            }
        });

        if (failed[0]) {
            throw new IllegalStateException("See logcat for errors");
        }
    }

    void mutateShortcut(@NonNull final String id, @Nullable final ShortcutInfo shortcut,
            @NonNull final Consumer<ShortcutInfo> transform) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(transform);
        synchronized (mLock) {
            if (shortcut != null) {
                transform.accept(shortcut);
            }
            final ShortcutInfo si = findShortcutById(id);
            if (si == null) {
                return;
            }
            transform.accept(si);
            saveShortcut(si);
        }
    }

    private void saveShortcut(@NonNull final ShortcutInfo... shortcuts) {
        Objects.requireNonNull(shortcuts);
        saveShortcut(Arrays.asList(shortcuts));
    }

    private void saveShortcut(@NonNull final Collection<ShortcutInfo> shortcuts) {
        Objects.requireNonNull(shortcuts);
        synchronized (mLock) {
            for (ShortcutInfo si : shortcuts) {
                mShortcuts.put(si.getId(), si);
            }
        }
    }

    @Nullable
    List<ShortcutInfo> findAll(@NonNull final Collection<String> ids) {
        synchronized (mLock) {
            return ids.stream().map(mShortcuts::get)
                    .filter(Objects::nonNull).collect(Collectors.toList());
        }
    }

    private void forEachShortcut(@NonNull final Consumer<ShortcutInfo> cb) {
        forEachShortcutStopWhen(si -> {
            cb.accept(si);
            return false;
        });
    }

    private void forEachShortcutMutate(@NonNull final Consumer<ShortcutInfo> cb) {
        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            ShortcutInfo si = mShortcuts.valueAt(i);
            cb.accept(si);
        }
    }

    private void forEachShortcutStopWhen(
            @NonNull final Function<ShortcutInfo, Boolean> cb) {
        synchronized (mLock) {
            for (int i = mShortcuts.size() - 1; i >= 0; i--) {
                final ShortcutInfo si = mShortcuts.valueAt(i);
                if (cb.apply(si)) {
                    return;
                }
            }
        }
    }

    @NonNull
    private AndroidFuture<AppSearchSession> setupSchema(
            @NonNull final AppSearchSession session) {
        if (ShortcutService.DEBUG_REBOOT) {
            Slog.d(TAG, "Setup Schema for user=" + mShortcutUser.getUserId()
                    + " pkg=" + getPackageName());
        }
        SetSchemaRequest.Builder schemaBuilder = new SetSchemaRequest.Builder()
                .addSchemas(AppSearchShortcutPerson.SCHEMA, AppSearchShortcutInfo.SCHEMA)
                .setForceOverride(true)
                .addRequiredPermissionsForSchemaTypeVisibility(AppSearchShortcutInfo.SCHEMA_TYPE,
                        Collections.singleton(SetSchemaRequest.READ_HOME_APP_SEARCH_DATA))
                .addRequiredPermissionsForSchemaTypeVisibility(AppSearchShortcutInfo.SCHEMA_TYPE,
                        Collections.singleton(SetSchemaRequest.READ_ASSISTANT_APP_SEARCH_DATA))
                .addRequiredPermissionsForSchemaTypeVisibility(AppSearchShortcutPerson.SCHEMA_TYPE,
                        Collections.singleton(SetSchemaRequest.READ_HOME_APP_SEARCH_DATA))
                .addRequiredPermissionsForSchemaTypeVisibility(AppSearchShortcutPerson.SCHEMA_TYPE,
                        Collections.singleton(SetSchemaRequest.READ_ASSISTANT_APP_SEARCH_DATA));
        final AndroidFuture<AppSearchSession> future = new AndroidFuture<>();
        session.setSchema(
                schemaBuilder.build(), mExecutor, mShortcutUser.mExecutor, result -> {
            if (!result.isSuccess()) {
                future.completeExceptionally(
                        new IllegalArgumentException(result.getErrorMessage()));
                return;
            }
            future.complete(session);
        });
        return future;
    }

    @NonNull
    private SearchSpec getSearchSpec() {
        return new SearchSpec.Builder()
                .addFilterSchemas(AppSearchShortcutInfo.SCHEMA_TYPE)
                .addFilterNamespaces(getPackageName())
                .setTermMatch(SearchSpec.TERM_MATCH_EXACT_ONLY)
                .setResultCountPerPage(mShortcutUser.mService.getMaxActivityShortcuts())
                .build();
    }

    private boolean verifyRanksSequential(List<ShortcutInfo> list) {
        boolean failed = false;

        for (int i = 0; i < list.size(); i++) {
            final ShortcutInfo si = list.get(i);
            if (si.getRank() != i) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId()
                        + " rank=" + si.getRank() + " but expected to be " + i);
            }
        }
        return failed;
    }

    // Async Operations

    /**
     * Removes all shortcuts from AppSearch.
     */
    void removeAllShortcutsAsync() {
        if (!isAppSearchEnabled()) {
            return;
        }
        runAsSystem(() -> fromAppSearch().thenAccept(session ->
                session.remove("", getSearchSpec(), mShortcutUser.mExecutor, result -> {
                    if (!result.isSuccess()) {
                        Slog.e(TAG, "Failed to remove shortcuts from AppSearch. "
                                + result.getErrorMessage());
                    }
                })));
    }

    void getShortcutByIdsAsync(@NonNull final Set<String> ids,
            @NonNull final Consumer<List<ShortcutInfo>> cb) {
        if (!isAppSearchEnabled()) {
            cb.accept(Collections.emptyList());
            return;
        }
        runAsSystem(() -> fromAppSearch().thenAccept(session -> {
            session.getByDocumentId(new GetByDocumentIdRequest.Builder(getPackageName())
                            .addIds(ids).build(), mShortcutUser.mExecutor,
                    new BatchResultCallback<String, GenericDocument>() {
                        @Override
                        public void onResult(
                                @NonNull AppSearchBatchResult<String, GenericDocument> result) {
                            final List<ShortcutInfo> ret = result.getSuccesses().values()
                                    .stream().map(doc ->
                                            ShortcutInfo.createFromGenericDocument(
                                                    mShortcutUser.getUserId(), doc))
                                    .collect(Collectors.toList());
                            cb.accept(ret);
                        }
                        @Override
                        public void onSystemError(
                                @Nullable Throwable throwable) {
                            Slog.d(TAG, "Error retrieving shortcuts", throwable);
                        }
                    });
        }));
    }

    private void removeShortcutAsync(@NonNull final String... id) {
        Objects.requireNonNull(id);
        removeShortcutAsync(Arrays.asList(id));
    }

    private void removeShortcutAsync(@NonNull final Collection<String> ids) {
        if (!isAppSearchEnabled()) {
            return;
        }
        runAsSystem(() -> fromAppSearch().thenAccept(session ->
                session.remove(
                        new RemoveByDocumentIdRequest.Builder(getPackageName()).addIds(ids).build(),
                        mShortcutUser.mExecutor,
                        new BatchResultCallback<String, Void>() {
                            @Override
                            public void onResult(
                                    @NonNull AppSearchBatchResult<String, Void> result) {
                                if (!result.isSuccess()) {
                                    final Map<String, AppSearchResult<Void>> failures =
                                            result.getFailures();
                                    for (String key : failures.keySet()) {
                                        Slog.e(TAG, "Failed deleting " + key + ", error message:"
                                                + failures.get(key).getErrorMessage());
                                    }
                                }
                            }
                            @Override
                            public void onSystemError(@Nullable Throwable throwable) {
                                Slog.e(TAG, "Error removing shortcuts", throwable);
                            }
                        })));
    }

    @GuardedBy("mLock")
    @Override
    void scheduleSaveToAppSearchLocked() {
        final Map<String, ShortcutInfo> copy = new ArrayMap<>(mShortcuts);
        if (!mTransientShortcuts.isEmpty()) {
            copy.putAll(mTransientShortcuts);
            mTransientShortcuts.clear();
        }
        saveShortcutsAsync(copy.values().stream().filter(ShortcutInfo::usesQuota).collect(
                Collectors.toList()));
    }

    private void saveShortcutsAsync(
            @NonNull final Collection<ShortcutInfo> shortcuts) {
        Objects.requireNonNull(shortcuts);
        if (!isAppSearchEnabled() || shortcuts.isEmpty()) {
            // No need to invoke AppSearch when there's nothing to save.
            return;
        }
        if (ShortcutService.DEBUG_REBOOT) {
            Slog.d(TAG, "Saving shortcuts async for user=" + mShortcutUser.getUserId()
                    + " pkg=" + getPackageName() + " ids=" + shortcuts.stream()
                    .map(ShortcutInfo::getId).collect(Collectors.joining(",", "[", "]")));
        }
        runAsSystem(() -> fromAppSearch().thenAccept(session -> {
            if (shortcuts.isEmpty()) {
                return;
            }
            session.put(new PutDocumentsRequest.Builder()
                            .addGenericDocuments(
                                    AppSearchShortcutInfo.toGenericDocuments(shortcuts))
                            .build(),
                    mShortcutUser.mExecutor,
                    new BatchResultCallback<String, Void>() {
                        @Override
                        public void onResult(
                                @NonNull AppSearchBatchResult<String, Void> result) {
                            if (!result.isSuccess()) {
                                for (AppSearchResult<Void> k : result.getFailures().values()) {
                                    Slog.e(TAG, k.getErrorMessage());
                                }
                            }
                        }
                        @Override
                        public void onSystemError(@Nullable Throwable throwable) {
                            Slog.d(TAG, "Error persisting shortcuts", throwable);
                        }
                    });
        }));
    }

    @VisibleForTesting
    void getTopShortcutsFromPersistence(AndroidFuture<List<ShortcutInfo>> cb) {
        if (!isAppSearchEnabled()) {
            cb.complete(null);
        }
        runAsSystem(() -> fromAppSearch().thenAccept(session -> {
            SearchResults res = session.search("", getSearchSpec());
            res.getNextPage(mShortcutUser.mExecutor, results -> {
                if (!results.isSuccess()) {
                    cb.completeExceptionally(new IllegalStateException(results.getErrorMessage()));
                    return;
                }
                cb.complete(results.getResultValue().stream()
                        .map(SearchResult::getGenericDocument)
                        .map(doc -> ShortcutInfo.createFromGenericDocument(
                                mShortcutUser.getUserId(), doc))
                        .collect(Collectors.toList()));
            });
        }));
    }

    @NonNull
    private AndroidFuture<AppSearchSession> fromAppSearch() {
        final StrictMode.ThreadPolicy oldPolicy = StrictMode.getThreadPolicy();
        final AppSearchManager.SearchContext searchContext =
                new AppSearchManager.SearchContext.Builder(getPackageName()).build();
        AndroidFuture<AppSearchSession> future = null;
        try {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog() // TODO: change this to penaltyDeath to fix the call-site
                    .build());
            future = mShortcutUser.getAppSearch(searchContext);
            synchronized (mLock) {
                if (!mIsAppSearchSchemaUpToDate) {
                    future = future.thenCompose(this::setupSchema);
                }
                mIsAppSearchSchemaUpToDate = true;
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to create app search session. pkg="
                    + getPackageName() + " user=" + mShortcutUser.getUserId(), e);
            Objects.requireNonNull(future).completeExceptionally(e);
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
        return Objects.requireNonNull(future);
    }

    private void runAsSystem(@NonNull final Runnable fn) {
        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            fn.run();
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    @Override
    protected File getShortcutPackageItemFile() {
        final File path = new File(mShortcutUser.mService.injectUserDataPath(
                mShortcutUser.getUserId()), ShortcutUser.DIRECTORY_PACKAGES);
        final String fileName = getPackageName() + ".xml";
        return new File(path, fileName);
    }
}
