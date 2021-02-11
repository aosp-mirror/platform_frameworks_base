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
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSession;
import android.content.pm.ShortcutManager;
import android.metrics.LogMaker;
import android.os.Binder;
import android.os.FileUtils;
import android.os.UserHandle;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.server.FgThread;
import com.android.server.pm.ShortcutService.DumpFilter;
import com.android.server.pm.ShortcutService.InvalidFileFormatException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * User information used by {@link ShortcutService}.
 *
 * All methods should be guarded by {@code #mService.mLock}.
 */
class ShortcutUser {
    private static final String TAG = ShortcutService.TAG;

    static final String DIRECTORY_PACKAGES = "packages";
    static final String DIRECTORY_LUANCHERS = "launchers";

    static final String TAG_ROOT = "user";
    private static final String TAG_LAUNCHER = "launcher";

    private static final String ATTR_VALUE = "value";
    private static final String ATTR_KNOWN_LOCALES = "locales";

    // Suffix "2" was added to force rescan all packages after the next OTA.
    private static final String ATTR_LAST_APP_SCAN_TIME = "last-app-scan-time2";
    private static final String ATTR_LAST_APP_SCAN_OS_FINGERPRINT = "last-app-scan-fp";
    private static final String ATTR_RESTORE_SOURCE_FINGERPRINT = "restore-from-fp";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_LAUNCHERS = "launchers";
    private static final String KEY_PACKAGES = "packages";

    static final class PackageWithUser {
        final int userId;
        final String packageName;

        private PackageWithUser(int userId, String packageName) {
            this.userId = userId;
            this.packageName = Objects.requireNonNull(packageName);
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
    final AppSearchManager mAppSearchManager;
    final Executor mExecutor;

    @UserIdInt
    private final int mUserId;

    private final ArrayMap<String, ShortcutPackage> mPackages = new ArrayMap<>();

    private final ArrayMap<PackageWithUser, ShortcutLauncher> mLaunchers = new ArrayMap<>();

    /** In-memory-cached default launcher. */
    private String mCachedLauncher;

    private String mKnownLocales;

    private long mLastAppScanTime;

    private String mLastAppScanOsFingerprint;
    private String mRestoreFromOsFingerprint;

    public ShortcutUser(ShortcutService service, int userId) {
        mService = service;
        mUserId = userId;
        mAppSearchManager = service.mContext.createContextAsUser(UserHandle.of(userId), 0)
                .getSystemService(AppSearchManager.class);
        mExecutor = FgThread.getExecutor();
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

        if (removed != null) {
            removed.removeShortcuts();
        }
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
        if (!TextUtils.isEmpty(mKnownLocales) && mKnownLocales.equals(currentLocales)) {
            return;
        }
        if (ShortcutService.DEBUG) {
            Slog.d(TAG, "Locale changed from " + mKnownLocales + " to " + currentLocales
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
                final ShortcutPackage sp = mPackages.remove(packageName);
                if (sp != null) {
                    sp.removeShortcuts();
                }
            }
        }
    }

    public void attemptToRestoreIfNeededAndSave(ShortcutService s, @NonNull String packageName,
            @UserIdInt int packageUserId) {
        forPackageItem(packageName, packageUserId, spi -> {
            spi.attemptToRestoreIfNeededAndSave();
        });
    }

    public void saveToXml(TypedXmlSerializer out, boolean forBackup)
            throws IOException, XmlPullParserException {
        out.startTag(null, TAG_ROOT);

        if (!forBackup) {
            // Don't have to back them up.
            ShortcutService.writeAttr(out, ATTR_KNOWN_LOCALES, mKnownLocales);
            ShortcutService.writeAttr(out, ATTR_LAST_APP_SCAN_TIME,
                    mLastAppScanTime);
            ShortcutService.writeAttr(out, ATTR_LAST_APP_SCAN_OS_FINGERPRINT,
                    mLastAppScanOsFingerprint);
            ShortcutService.writeAttr(out, ATTR_RESTORE_SOURCE_FINGERPRINT,
                    mRestoreFromOsFingerprint);
        } else {
            ShortcutService.writeAttr(out, ATTR_RESTORE_SOURCE_FINGERPRINT,
                    mService.injectBuildFingerprint());
        }

        if (!forBackup) {
            // Since we are not handling package deletion yet, or any single package changes, just
            // clean the directory and rewrite all the ShortcutPackageItems.
            final File root = mService.injectUserDataPath(mUserId);
            FileUtils.deleteContents(new File(root, DIRECTORY_PACKAGES));
            FileUtils.deleteContents(new File(root, DIRECTORY_LUANCHERS));
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

    private void saveShortcutPackageItem(TypedXmlSerializer out, ShortcutPackageItem spi,
            boolean forBackup) throws IOException, XmlPullParserException {
        if (forBackup) {
            if (spi.getPackageUserId() != spi.getOwnerUserId()) {
                return; // Don't save cross-user information.
            }
            spi.saveToXml(out, forBackup);
        } else {
            // Save each ShortcutPackageItem in a separate Xml file.
            final File path = getShortcutPackageItemFile(spi);
            if (ShortcutService.DEBUG) {
                Slog.d(TAG, "Saving package item " + spi.getPackageName() + " to " + path);
            }

            path.getParentFile().mkdirs();
            spi.saveToFile(path, forBackup);
        }
    }

    private File getShortcutPackageItemFile(ShortcutPackageItem spi) {
        boolean isShortcutLauncher = spi instanceof ShortcutLauncher;

        final File path = new File(mService.injectUserDataPath(mUserId),
                isShortcutLauncher ? DIRECTORY_LUANCHERS : DIRECTORY_PACKAGES);

        final String fileName;
        if (isShortcutLauncher) {
            // Package user id and owner id can have different values for ShortcutLaunchers. Adding
            // user Id to the file name to create a unique path. Owner id is used in the root path.
            fileName = spi.getPackageName() + spi.getPackageUserId() + ".xml";
        } else {
            fileName = spi.getPackageName() + ".xml";
        }

        return new File(path, fileName);
    }

    public static ShortcutUser loadFromXml(ShortcutService s, TypedXmlPullParser parser, int userId,
            boolean fromBackup) throws IOException, XmlPullParserException, InvalidFileFormatException {
        final ShortcutUser ret = new ShortcutUser(s, userId);
        boolean readShortcutItems = false;
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
            ret.mRestoreFromOsFingerprint = ShortcutService.parseStringAttribute(parser,
                    ATTR_RESTORE_SOURCE_FINGERPRINT);
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
                        case ShortcutPackage.TAG_ROOT: {
                            final ShortcutPackage shortcuts = ShortcutPackage.loadFromXml(
                                    s, ret, parser, fromBackup);
                            shortcuts.restoreParsedShortcuts(false);

                            // Don't use addShortcut(), we don't need to save the icon.
                            ret.mPackages.put(shortcuts.getPackageName(), shortcuts);
                            readShortcutItems = true;
                            continue;
                        }

                        case ShortcutLauncher.TAG_ROOT: {
                            ret.addLauncher(
                                    ShortcutLauncher.loadFromXml(parser, ret, userId, fromBackup));
                            readShortcutItems = true;
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

        if (readShortcutItems) {
            // If the shortcuts info was read from the main Xml, skip reading from individual files.
            // Data will get stored in the new format during the next call to saveToXml().
            // TODO: ret.forAllPackageItems((ShortcutPackageItem item) -> item.markDirty());
            s.scheduleSaveUser(userId);
        } else {
            final File root = s.injectUserDataPath(userId);

            forAllFilesIn(new File(root, DIRECTORY_PACKAGES), (File f) -> {
                final ShortcutPackage sp = ShortcutPackage.loadFromFile(s, ret, f, fromBackup);
                if (sp != null) {
                    ret.mPackages.put(sp.getPackageName(), sp);
                    sp.restoreParsedShortcuts(false);
                }
            });

            forAllFilesIn(new File(root, DIRECTORY_LUANCHERS), (File f) -> {
                final ShortcutLauncher sl =
                        ShortcutLauncher.loadFromFile(f, ret, userId, fromBackup);
                if (sl != null) {
                    ret.addLauncher(sl);
                }
            });
        }

        return ret;
    }

    private static void forAllFilesIn(File path, Consumer<File> callback) {
        if (!path.exists()) {
            return;
        }
        File[] list = path.listFiles();
        for (File f : list) {
            callback.accept(f);
        }
    }

    public void setCachedLauncher(String launcher) {
        mCachedLauncher = launcher;
    }

    public String getCachedLauncher() {
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

        int[] restoredLaunchers = new int[1];
        int[] restoredPackages = new int[1];
        int[] restoredShortcuts = new int[1];

        mLaunchers.clear();
        restored.forAllLaunchers(sl -> {
            // If the app is already installed and allowbackup = false, then ignore the restored
            // data.
            if (s.isPackageInstalled(sl.getPackageName(), getUserId())
                    && !s.shouldBackupApp(sl.getPackageName(), getUserId())) {
                return;
            }
            addLauncher(sl);
            restoredLaunchers[0]++;
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
            sp.restoreParsedShortcuts(true);
            addPackage(sp);
            restoredPackages[0]++;
            restoredShortcuts[0] += sp.getShortcutCount();
        });
        // Empty the launchers and packages in restored to avoid accidentally using them.
        restored.mLaunchers.clear();
        restored.mPackages.clear();

        mRestoreFromOsFingerprint = restored.mRestoreFromOsFingerprint;

        Slog.i(TAG, "Restored: L=" + restoredLaunchers[0]
                + " P=" + restoredPackages[0]
                + " S=" + restoredShortcuts[0]);
    }

    public void dump(@NonNull PrintWriter pw, @NonNull String prefix, DumpFilter filter) {
        if (filter.shouldDumpDetails()) {
            pw.print(prefix);
            pw.print("User: ");
            pw.print(mUserId);
            pw.print("  Known locales: ");
            pw.print(mKnownLocales);
            pw.print("  Last app scan: [");
            pw.print(mLastAppScanTime);
            pw.print("] ");
            pw.println(ShortcutService.formatTime(mLastAppScanTime));

            prefix += prefix + "  ";

            pw.print(prefix);
            pw.print("Last app scan FP: ");
            pw.println(mLastAppScanOsFingerprint);

            pw.print(prefix);
            pw.print("Restore from FP: ");
            pw.print(mRestoreFromOsFingerprint);
            pw.println();

            pw.print(prefix);
            pw.print("Cached launcher: ");
            pw.print(mCachedLauncher);
            pw.println();
        }

        for (int i = 0; i < mLaunchers.size(); i++) {
            ShortcutLauncher launcher = mLaunchers.valueAt(i);
            if (filter.isPackageMatch(launcher.getPackageName())) {
                launcher.dump(pw, prefix, filter);
            }
        }

        for (int i = 0; i < mPackages.size(); i++) {
            ShortcutPackage pkg = mPackages.valueAt(i);
            if (filter.isPackageMatch(pkg.getPackageName())) {
                pkg.dump(pw, prefix, filter);
            }
        }

        if (filter.shouldDumpDetails()) {
            pw.println();
            pw.print(prefix);
            pw.println("Bitmap directories: ");
            dumpDirectorySize(pw, prefix + "  ", mService.getUserBitmapFilePath(mUserId));
        }
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

    void logSharingShortcutStats(MetricsLogger logger) {
        int packageWithShareTargetsCount = 0;
        int totalSharingShortcutCount = 0;
        for (int i = 0; i < mPackages.size(); i++) {
            if (mPackages.valueAt(i).hasShareTargets()) {
                packageWithShareTargetsCount++;
                totalSharingShortcutCount += mPackages.valueAt(i).getSharingShortcutCount();
            }
        }

        final LogMaker logMaker = new LogMaker(MetricsEvent.ACTION_SHORTCUTS_CHANGED);
        logger.write(logMaker.setType(MetricsEvent.SHORTCUTS_CHANGED_USER_ID)
                .setSubtype(mUserId));
        logger.write(logMaker.setType(MetricsEvent.SHORTCUTS_CHANGED_PACKAGE_COUNT)
                .setSubtype(packageWithShareTargetsCount));
        logger.write(logMaker.setType(MetricsEvent.SHORTCUTS_CHANGED_SHORTCUT_COUNT)
                .setSubtype(totalSharingShortcutCount));
    }

    void runInAppSearch(@NonNull final AppSearchManager.SearchContext searchContext,
            @NonNull final Consumer<AppSearchResult<AppSearchSession>> callback) {
        if (mAppSearchManager == null) {
            Slog.e(TAG, "app search manager is null");
            return;
        }
        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            mAppSearchManager.createSearchSession(searchContext, mExecutor, callback);
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }
}
