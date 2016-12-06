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
import android.content.pm.ShortcutManager;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.pm.ShortcutService.InvalidFileFormatException;

import libcore.util.Objects;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * User information used by {@link ShortcutService}.
 *
 * All methods should be guarded by {@code #mService.mLock}.
 */
class ShortcutUser {
    private static final String TAG = ShortcutService.TAG;

    static final String TAG_ROOT = "user";
    private static final String TAG_LAUNCHER = "launcher";

    private static final String ATTR_VALUE = "value";
    private static final String ATTR_KNOWN_LOCALES = "locales";

    // Suffix "2" was added to force rescan all packages after the next OTA.
    private static final String ATTR_LAST_APP_SCAN_TIME = "last-app-scan-time2";
    private static final String ATTR_LAST_APP_SCAN_OS_FINGERPRINT = "last-app-scan-fp";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_LAUNCHERS = "launchers";
    private static final String KEY_PACKAGES = "packages";

    static final class PackageWithUser {
        final int userId;
        final String packageName;

        private PackageWithUser(int userId, String packageName) {
            this.userId = userId;
            this.packageName = Preconditions.checkNotNull(packageName);
        }

        public static PackageWithUser of(int userId, String packageName) {
            return new PackageWithUser(userId, packageName);
        }

        public static PackageWithUser of(ShortcutPackageItem spi) {
            return new PackageWithUser(spi.getPackageUserId(), spi.getPackageName());
        }

        @Override
        public int hashCode() {
            return packageName.hashCode() ^ userId;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof PackageWithUser)) {
                return false;
            }
            final PackageWithUser that = (PackageWithUser) obj;

            return userId == that.userId && packageName.equals(that.packageName);
        }

        @Override
        public String toString() {
            return String.format("[Package: %d, %s]", userId, packageName);
        }
    }

    final ShortcutService mService;

    @UserIdInt
    private final int mUserId;

    private final ArrayMap<String, ShortcutPackage> mPackages = new ArrayMap<>();

    private final ArrayMap<PackageWithUser, ShortcutLauncher> mLaunchers = new ArrayMap<>();

    /**
     * Last known launcher.  It's used when the default launcher isn't set in PM -- i.e.
     * when getHomeActivitiesAsUser() return null.  We need it so that in this situation the
     * previously default launcher can still access shortcuts.
     */
    private ComponentName mLastKnownLauncher;

    /** In-memory-cached default launcher. */
    private ComponentName mCachedLauncher;

    private String mKnownLocales;

    private long mLastAppScanTime;

    private String mLastAppScanOsFingerprint;

    public ShortcutUser(ShortcutService service, int userId) {
        mService = service;
        mUserId = userId;
    }

    public int getUserId() {
        return mUserId;
    }

    public long getLastAppScanTime() {
        return mLastAppScanTime;
    }

    public void setLastAppScanTime(long lastAppScanTime) {
        mLastAppScanTime = lastAppScanTime;
    }

    public String getLastAppScanOsFingerprint() {
        return mLastAppScanOsFingerprint;
    }

    public void setLastAppScanOsFingerprint(String lastAppScanOsFingerprint) {
        mLastAppScanOsFingerprint = lastAppScanOsFingerprint;
    }

    // We don't expose this directly to non-test code because only ShortcutUser should add to/
    // remove from it.
    @VisibleForTesting
    ArrayMap<String, ShortcutPackage> getAllPackagesForTest() {
        return mPackages;
    }

    public boolean hasPackage(@NonNull String packageName) {
        return mPackages.containsKey(packageName);
    }

    private void addPackage(@NonNull ShortcutPackage p) {
        p.replaceUser(this);
        mPackages.put(p.getPackageName(), p);
    }

    public ShortcutPackage removePackage(@NonNull String packageName) {
        final ShortcutPackage removed = mPackages.remove(packageName);

        mService.cleanupBitmapsForPackage(mUserId, packageName);

        return removed;
    }

    // We don't expose this directly to non-test code because only ShortcutUser should add to/
    // remove from it.
    @VisibleForTesting
    ArrayMap<PackageWithUser, ShortcutLauncher> getAllLaunchersForTest() {
        return mLaunchers;
    }

    private void addLauncher(ShortcutLauncher launcher) {
        launcher.replaceUser(this);
        mLaunchers.put(PackageWithUser.of(launcher.getPackageUserId(),
                launcher.getPackageName()), launcher);
    }

    @Nullable
    public ShortcutLauncher removeLauncher(
            @UserIdInt int packageUserId, @NonNull String packageName) {
        return mLaunchers.remove(PackageWithUser.of(packageUserId, packageName));
    }

    @Nullable
    public ShortcutPackage getPackageShortcutsIfExists(@NonNull String packageName) {
        final ShortcutPackage ret = mPackages.get(packageName);
        if (ret != null) {
            ret.attemptToRestoreIfNeededAndSave();
        }
        return ret;
    }

    @NonNull
    public ShortcutPackage getPackageShortcuts(@NonNull String packageName) {
        ShortcutPackage ret = getPackageShortcutsIfExists(packageName);
        if (ret == null) {
            ret = new ShortcutPackage(this, mUserId, packageName);
            mPackages.put(packageName, ret);
        }
        return ret;
    }

    @NonNull
    public ShortcutLauncher getLauncherShortcuts(@NonNull String packageName,
            @UserIdInt int launcherUserId) {
        final PackageWithUser key = PackageWithUser.of(launcherUserId, packageName);
        ShortcutLauncher ret = mLaunchers.get(key);
        if (ret == null) {
            ret = new ShortcutLauncher(this, mUserId, packageName, launcherUserId);
            mLaunchers.put(key, ret);
        } else {
            ret.attemptToRestoreIfNeededAndSave();
        }
        return ret;
    }

    public void forAllPackages(Consumer<? super ShortcutPackage> callback) {
        final int size = mPackages.size();
        for (int i = 0; i < size; i++) {
            callback.accept(mPackages.valueAt(i));
        }
    }

    public void forAllLaunchers(Consumer<? super ShortcutLauncher> callback) {
        final int size = mLaunchers.size();
        for (int i = 0; i < size; i++) {
            callback.accept(mLaunchers.valueAt(i));
        }
    }

    public void forAllPackageItems(Consumer<? super ShortcutPackageItem> callback) {
        forAllLaunchers(callback);
        forAllPackages(callback);
    }

    public void forPackageItem(@NonNull String packageName, @UserIdInt int packageUserId,
            Consumer<ShortcutPackageItem> callback) {
        forAllPackageItems(spi -> {
            if ((spi.getPackageUserId() == packageUserId)
                    && spi.getPackageName().equals(packageName)) {
                callback.accept(spi);
            }
        });
    }

    /**
     * Must be called at any entry points on {@link ShortcutManager} APIs to make sure the
     * information on the package is up-to-date.
     *
     * We use broadcasts to handle locale changes and package changes, but because broadcasts
     * are asynchronous, there's a chance a publisher calls getXxxShortcuts() after a certain event
     * (e.g. system locale change) but shortcut manager hasn't finished processing the broadcast.
     *
     * So we call this method at all entry points from publishers to make sure we update all
     * relevant information.
     *
     * Similar inconsistencies can happen when the launcher fetches shortcut information, but
     * that's a less of an issue because for the launcher we report shortcut changes with
     * callbacks.
     */
    public void onCalledByPublisher(@NonNull String packageName) {
        detectLocaleChange();
        rescanPackageIfNeeded(packageName, /*forceRescan=*/ false);
    }

    private String getKnownLocales() {
        if (TextUtils.isEmpty(mKnownLocales)) {
            mKnownLocales = mService.injectGetLocaleTagsForUser(mUserId);
            mService.scheduleSaveUser(mUserId);
        }
        return mKnownLocales;
    }

    /**
     * Check to see if the system locale has changed, and if so, reset throttling
     * and update resource strings.
     */
    public void detectLocaleChange() {
        final String currentLocales = mService.injectGetLocaleTagsForUser(mUserId);
        if (getKnownLocales().equals(currentLocales)) {
            return;
        }
        if (ShortcutService.DEBUG) {
            Slog.d(TAG, "Locale changed from " + currentLocales + " to " + mKnownLocales
                    + " for user " + mUserId);
        }
        mKnownLocales = currentLocales;

        forAllPackages(pkg -> {
            pkg.resetRateLimiting();
            pkg.resolveResourceStrings();
        });

        mService.scheduleSaveUser(mUserId);
    }

    public void rescanPackageIfNeeded(@NonNull String packageName, boolean forceRescan) {
        final boolean isNewApp = !mPackages.containsKey(packageName);

        final ShortcutPackage shortcutPackage = getPackageShortcuts(packageName);

        if (!shortcutPackage.rescanPackageIfNeeded(isNewApp, forceRescan)) {
            if (isNewApp) {
                mPackages.remove(packageName);
            }
        }
    }

    public void attemptToRestoreIfNeededAndSave(ShortcutService s, @NonNull String packageName,
            @UserIdInt int packageUserId) {
        forPackageItem(packageName, packageUserId, spi -> {
            spi.attemptToRestoreIfNeededAndSave();
        });
    }

    public void saveToXml(XmlSerializer out, boolean forBackup)
            throws IOException, XmlPullParserException {
        out.startTag(null, TAG_ROOT);

        if (!forBackup) {
            // Don't have to back them up.
            ShortcutService.writeAttr(out, ATTR_KNOWN_LOCALES, mKnownLocales);
            ShortcutService.writeAttr(out, ATTR_LAST_APP_SCAN_TIME,
                    mLastAppScanTime);
            ShortcutService.writeAttr(out, ATTR_LAST_APP_SCAN_OS_FINGERPRINT,
                    mLastAppScanOsFingerprint);

            ShortcutService.writeTagValue(out, TAG_LAUNCHER, mLastKnownLauncher);
        }

        // Can't use forEachPackageItem due to the checked exceptions.
        {
            final int size = mLaunchers.size();
            for (int i = 0; i < size; i++) {
                saveShortcutPackageItem(out, mLaunchers.valueAt(i), forBackup);
            }
        }
        {
            final int size = mPackages.size();
            for (int i = 0; i < size; i++) {
                saveShortcutPackageItem(out, mPackages.valueAt(i), forBackup);
            }
        }

        out.endTag(null, TAG_ROOT);
    }

    private void saveShortcutPackageItem(XmlSerializer out,
            ShortcutPackageItem spi, boolean forBackup) throws IOException, XmlPullParserException {
        if (forBackup) {
            if (!mService.shouldBackupApp(spi.getPackageName(), spi.getPackageUserId())) {
                return; // Don't save.
            }
            if (spi.getPackageUserId() != spi.getOwnerUserId()) {
                return; // Don't save cross-user information.
            }
        }
        spi.saveToXml(out, forBackup);
    }

    public static ShortcutUser loadFromXml(ShortcutService s, XmlPullParser parser, int userId,
            boolean fromBackup) throws IOException, XmlPullParserException, InvalidFileFormatException {
        final ShortcutUser ret = new ShortcutUser(s, userId);

        try {
            ret.mKnownLocales = ShortcutService.parseStringAttribute(parser,
                    ATTR_KNOWN_LOCALES);

            // If lastAppScanTime is in the future, that means the clock went backwards.
            // Just scan all apps again.
            final long lastAppScanTime = ShortcutService.parseLongAttribute(parser,
                    ATTR_LAST_APP_SCAN_TIME);
            final long currentTime = s.injectCurrentTimeMillis();
            ret.mLastAppScanTime = lastAppScanTime < currentTime ? lastAppScanTime : 0;
            ret.mLastAppScanOsFingerprint = ShortcutService.parseStringAttribute(parser,
                    ATTR_LAST_APP_SCAN_OS_FINGERPRINT);
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
                        case TAG_LAUNCHER: {
                            ret.mLastKnownLauncher = ShortcutService.parseComponentNameAttribute(
                                    parser, ATTR_VALUE);
                            continue;
                        }
                        case ShortcutPackage.TAG_ROOT: {
                            final ShortcutPackage shortcuts = ShortcutPackage.loadFromXml(
                                    s, ret, parser, fromBackup);

                            // Don't use addShortcut(), we don't need to save the icon.
                            ret.mPackages.put(shortcuts.getPackageName(), shortcuts);
                            continue;
                        }

                        case ShortcutLauncher.TAG_ROOT: {
                            ret.addLauncher(
                                    ShortcutLauncher.loadFromXml(parser, ret, userId, fromBackup));
                            continue;
                        }
                    }
                }
                ShortcutService.warnForInvalidTag(depth, tag);
            }
        } catch (RuntimeException e) {
            throw new ShortcutService.InvalidFileFormatException(
                    "Unable to parse file", e);
        }
        return ret;
    }

    public ComponentName getLastKnownLauncher() {
        return mLastKnownLauncher;
    }

    public void setLauncher(ComponentName launcherComponent) {
        setLauncher(launcherComponent, /* allowPurgeLastKnown */ false);
    }

    /** Clears the launcher information without clearing the last known one */
    public void clearLauncher() {
        setLauncher(null);
    }

    /**
     * Clears the launcher information *with(* clearing the last known one; we do this witl
     * "cmd shortcut clear-default-launcher".
     */
    public void forceClearLauncher() {
        setLauncher(null, /* allowPurgeLastKnown */ true);
    }

    private void setLauncher(ComponentName launcherComponent, boolean allowPurgeLastKnown) {
        mCachedLauncher = launcherComponent; // Always update the in-memory cache.

        if (Objects.equal(mLastKnownLauncher, launcherComponent)) {
            return;
        }
        if (!allowPurgeLastKnown && launcherComponent == null) {
            return;
        }
        mLastKnownLauncher = launcherComponent;
        mService.scheduleSaveUser(mUserId);
    }

    public ComponentName getCachedLauncher() {
        return mCachedLauncher;
    }

    public void resetThrottling() {
        for (int i = mPackages.size() - 1; i >= 0; i--) {
            mPackages.valueAt(i).resetThrottling();
        }
    }

    public void mergeRestoredFile(ShortcutUser restored) {
        final ShortcutService s = mService;
        // Note, a restore happens only at the end of setup wizard.  At this point, no apps are
        // installed from Play Store yet, but it's still possible that system apps have already
        // published dynamic shortcuts, since some apps do so on BOOT_COMPLETED.
        // When such a system app has allowbackup=true, then we go ahead and replace all existing
        // shortcuts with the restored shortcuts.  (Then we'll re-publish manifest shortcuts later
        // in the call site.)
        // When such a system app has allowbackup=false, then we'll keep the shortcuts that have
        // already been published.  So we selectively add restored ShortcutPackages here.
        //
        // The same logic applies to launchers, but since launchers shouldn't pin shortcuts
        // without users interaction it's really not a big deal, so we just clear existing
        // ShortcutLauncher instances in mLaunchers and add all the restored ones here.

        mLaunchers.clear();
        restored.forAllLaunchers(sl -> {
            // If the app is already installed and allowbackup = false, then ignore the restored
            // data.
            if (s.isPackageInstalled(sl.getPackageName(), getUserId())
                    && !s.shouldBackupApp(sl.getPackageName(), getUserId())) {
                return;
            }
            addLauncher(sl);
        });
        restored.forAllPackages(sp -> {
            // If the app is already installed and allowbackup = false, then ignore the restored
            // data.
            if (s.isPackageInstalled(sp.getPackageName(), getUserId())
                    && !s.shouldBackupApp(sp.getPackageName(), getUserId())) {
                return;
            }

            final ShortcutPackage previous = getPackageShortcutsIfExists(sp.getPackageName());
            if (previous != null && previous.hasNonManifestShortcuts()) {
                Log.w(TAG, "Shortcuts for package " + sp.getPackageName() + " are being restored."
                        + " Existing non-manifeset shortcuts will be overwritten.");
            }
            addPackage(sp);
        });
        // Empty the launchers and packages in restored to avoid accidentally using them.
        restored.mLaunchers.clear();
        restored.mPackages.clear();
    }

    public void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
        pw.print(prefix);
        pw.print("User: ");
        pw.print(mUserId);
        pw.print("  Known locales: ");
        pw.print(mKnownLocales);
        pw.print("  Last app scan: [");
        pw.print(mLastAppScanTime);
        pw.print("] ");
        pw.print(ShortcutService.formatTime(mLastAppScanTime));
        pw.print("  Last app scan FP: ");
        pw.print(mLastAppScanOsFingerprint);
        pw.println();

        prefix += prefix + "  ";

        pw.print(prefix);
        pw.print("Cached launcher: ");
        pw.print(mCachedLauncher);
        pw.println();

        pw.print(prefix);
        pw.print("Last known launcher: ");
        pw.print(mLastKnownLauncher);
        pw.println();

        for (int i = 0; i < mLaunchers.size(); i++) {
            mLaunchers.valueAt(i).dump(pw, prefix);
        }

        for (int i = 0; i < mPackages.size(); i++) {
            mPackages.valueAt(i).dump(pw, prefix);
        }

        pw.println();
        pw.print(prefix);
        pw.println("Bitmap directories: ");
        dumpDirectorySize(pw, prefix + "  ", mService.getUserBitmapFilePath(mUserId));
    }

    private void dumpDirectorySize(@NonNull PrintWriter pw,
            @NonNull String prefix, File path) {
        int numFiles = 0;
        long size = 0;
        final File[] children = path.listFiles();
        if (children != null) {
            for (File child : path.listFiles()) {
                if (child.isFile()) {
                    numFiles++;
                    size += child.length();
                } else if (child.isDirectory()) {
                    dumpDirectorySize(pw, prefix + "  ", child);
                }
            }
        }
        pw.print(prefix);
        pw.print("Path: ");
        pw.print(path.getName());
        pw.print("/ has ");
        pw.print(numFiles);
        pw.print(" files, size=");
        pw.print(size);
        pw.print(" (");
        pw.print(Formatter.formatFileSize(mService.mContext, size));
        pw.println(")");
    }

    public JSONObject dumpCheckin(boolean clear) throws JSONException {
        final JSONObject result = new JSONObject();

        result.put(KEY_USER_ID, mUserId);

        {
            final JSONArray launchers = new JSONArray();
            for (int i = 0; i < mLaunchers.size(); i++) {
                launchers.put(mLaunchers.valueAt(i).dumpCheckin(clear));
            }
            result.put(KEY_LAUNCHERS, launchers);
        }

        {
            final JSONArray packages = new JSONArray();
            for (int i = 0; i < mPackages.size(); i++) {
                packages.put(mPackages.valueAt(i).dumpCheckin(clear));
            }
            result.put(KEY_PACKAGES, packages);
        }

        return result;
    }
}
