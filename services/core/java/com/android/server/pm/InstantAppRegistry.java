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

package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstantAppInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PermissionInfo;
import android.content.pm.SigningDetails;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.permission.PermissionManager;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.PackageUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.XmlUtils;
import com.android.server.pm.parsing.PackageInfoUtils;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.utils.Snappable;
import com.android.server.utils.SnapshotCache;
import com.android.server.utils.Watchable;
import com.android.server.utils.WatchableImpl;
import com.android.server.utils.Watched;
import com.android.server.utils.WatchedSparseArray;
import com.android.server.utils.WatchedSparseBooleanArray;
import com.android.server.utils.Watcher;

import libcore.io.IoUtils;
import libcore.util.HexEncoding;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * This class is a part of the package manager service that is responsible
 * for managing data associated with instant apps such as cached uninstalled
 * instant apps and instant apps' cookies. In addition it is responsible for
 * pruning installed instant apps and meta-data for uninstalled instant apps
 * when free space is needed.
 */
class InstantAppRegistry implements Watchable, Snappable {
    private static final boolean DEBUG = false;

    private static final String LOG_TAG = "InstantAppRegistry";

    static final long DEFAULT_INSTALLED_INSTANT_APP_MIN_CACHE_PERIOD =
            DEBUG ? 30 * 1000L /* thirty seconds */ : 7 * 24 * 60 * 60 * 1000L; /* one week */

    private static final long DEFAULT_INSTALLED_INSTANT_APP_MAX_CACHE_PERIOD =
            DEBUG ? 60 * 1000L /* one min */ : 6 * 30 * 24 * 60 * 60 * 1000L; /* six months */

    static final long DEFAULT_UNINSTALLED_INSTANT_APP_MIN_CACHE_PERIOD =
            DEBUG ? 30 * 1000L /* thirty seconds */ : 7 * 24 * 60 * 60 * 1000L; /* one week */

    private static final long DEFAULT_UNINSTALLED_INSTANT_APP_MAX_CACHE_PERIOD =
            DEBUG ? 60 * 1000L /* one min */ : 6 * 30 * 24 * 60 * 60 * 1000L; /* six months */

    private static final String INSTANT_APPS_FOLDER = "instant";
    private static final String INSTANT_APP_ICON_FILE = "icon.png";
    private static final String INSTANT_APP_COOKIE_FILE_PREFIX = "cookie_";
    private static final String INSTANT_APP_COOKIE_FILE_SIFFIX = ".dat";
    private static final String INSTANT_APP_METADATA_FILE = "metadata.xml";
    private static final String INSTANT_APP_ANDROID_ID_FILE = "android_id";

    private static final String TAG_PACKAGE = "package";
    private static final String TAG_PERMISSIONS = "permissions";
    private static final String TAG_PERMISSION = "permission";

    private static final String ATTR_LABEL = "label";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_GRANTED = "granted";

    private final PackageManagerService mService;
    private final PermissionManagerServiceInternal mPermissionManager;
    private final CookiePersistence mCookiePersistence;
    private final PackageManagerInternal mPmInternal;

    /** State for uninstalled instant apps */
    @Watched
    @GuardedBy("mService.mLock")
    private final WatchedSparseArray<List<UninstalledInstantAppState>> mUninstalledInstantApps;

    /**
     * Automatic grants for access to instant app metadata.
     * The key is the target application UID.
     * The value is a set of instant app UIDs.
     * UserID -> TargetAppId -> InstantAppId
     */
    @Watched
    @GuardedBy("mService.mLock")
    private final WatchedSparseArray<WatchedSparseArray<WatchedSparseBooleanArray>> mInstantGrants;

    /** The set of all installed instant apps. UserID -> AppID */
    @Watched
    @GuardedBy("mService.mLock")
    private final WatchedSparseArray<WatchedSparseBooleanArray> mInstalledInstantAppUids;

    /**
     * The cached snapshot
     */
    private final SnapshotCache<InstantAppRegistry> mSnapshot;

    /**
     * Watchable machinery
     */
    private final WatchableImpl mWatchable = new WatchableImpl();
    public void registerObserver(@NonNull Watcher observer) {
        mWatchable.registerObserver(observer);
    }
    public void unregisterObserver(@NonNull Watcher observer) {
        mWatchable.unregisterObserver(observer);
    }
    public boolean isRegisteredObserver(@NonNull Watcher observer) {
        return mWatchable.isRegisteredObserver(observer);
    }
    public void dispatchChange(@Nullable Watchable what) {
        mWatchable.dispatchChange(what);
    }
    /**
     * Notify listeners that this object has changed.
     */
    private void onChanged() {
        dispatchChange(this);
    }

    /** The list of observers */
    private final Watcher mObserver = new Watcher() {
            @Override
            public void onChange(@Nullable Watchable what) {
                InstantAppRegistry.this.onChanged();
            }
        };

    private SnapshotCache<InstantAppRegistry> makeCache() {
        return new SnapshotCache<InstantAppRegistry>(this, this) {
            @Override
            public InstantAppRegistry createSnapshot() {
                InstantAppRegistry s = new InstantAppRegistry(mSource);
                s.mWatchable.seal();
                return s;
            }};
    }

    public InstantAppRegistry(PackageManagerService service,
            PermissionManagerServiceInternal permissionManager,
            PackageManagerInternal pmInternal) {
        mService = service;
        mPermissionManager = permissionManager;
        mPmInternal = pmInternal;
        mCookiePersistence = new CookiePersistence(BackgroundThread.getHandler().getLooper());

        mUninstalledInstantApps = new WatchedSparseArray<List<UninstalledInstantAppState>>();
        mInstantGrants = new WatchedSparseArray<WatchedSparseArray<WatchedSparseBooleanArray>>();
        mInstalledInstantAppUids = new WatchedSparseArray<WatchedSparseBooleanArray>();

        mUninstalledInstantApps.registerObserver(mObserver);
        mInstantGrants.registerObserver(mObserver);
        mInstalledInstantAppUids.registerObserver(mObserver);
        Watchable.verifyWatchedAttributes(this, mObserver);

        mSnapshot = makeCache();
    }

    /**
     * The copy constructor is used by PackageManagerService to construct a snapshot.
     */
    private InstantAppRegistry(InstantAppRegistry r) {
        mService = r.mService;
        mPermissionManager = r.mPermissionManager;
        mPmInternal = r.mPmInternal;
        mCookiePersistence = null;

        mUninstalledInstantApps = new WatchedSparseArray<List<UninstalledInstantAppState>>(
            r.mUninstalledInstantApps);
        mInstantGrants = new WatchedSparseArray<WatchedSparseArray<WatchedSparseBooleanArray>>(
            r.mInstantGrants);
        mInstalledInstantAppUids = new WatchedSparseArray<WatchedSparseBooleanArray>(
            r.mInstalledInstantAppUids);

        // Do not register any observers.  This is a snapshot.
        mSnapshot = null;
    }

    /**
     * Return a snapshot: the value is the cached snapshot if available.
     */
    public InstantAppRegistry snapshot() {
        return mSnapshot.snapshot();
    }

    @GuardedBy("mService.mLock")
    public byte[] getInstantAppCookieLPw(@NonNull String packageName,
            @UserIdInt int userId) {
        // Only installed packages can get their own cookie
        AndroidPackage pkg = mService.mPackages.get(packageName);
        if (pkg == null) {
            return null;
        }

        byte[] pendingCookie = mCookiePersistence.getPendingPersistCookieLPr(pkg, userId);
        if (pendingCookie != null) {
            return pendingCookie;
        }
        File cookieFile = peekInstantCookieFile(packageName, userId);
        if (cookieFile != null && cookieFile.exists()) {
            try {
                return IoUtils.readFileAsByteArray(cookieFile.toString());
            } catch (IOException e) {
                Slog.w(LOG_TAG, "Error reading cookie file: " + cookieFile);
            }
        }
        return null;
    }

    @GuardedBy("mService.mLock")
    public boolean setInstantAppCookieLPw(@NonNull String packageName,
            @Nullable byte[] cookie, @UserIdInt int userId) {
        if (cookie != null && cookie.length > 0) {
            final int maxCookieSize = mService.mContext.getPackageManager()
                    .getInstantAppCookieMaxBytes();
            if (cookie.length > maxCookieSize) {
                Slog.e(LOG_TAG, "Instant app cookie for package " + packageName + " size "
                        + cookie.length + " bytes while max size is " + maxCookieSize);
                return false;
            }
        }

        // Only an installed package can set its own cookie
        AndroidPackage pkg = mService.mPackages.get(packageName);
        if (pkg == null) {
            return false;
        }

        mCookiePersistence.schedulePersistLPw(userId, pkg, cookie);
        return true;
    }

    private void persistInstantApplicationCookie(@Nullable byte[] cookie,
            @NonNull String packageName, @NonNull File cookieFile, @UserIdInt int userId) {
        synchronized (mService.mLock) {
            File appDir = getInstantApplicationDir(packageName, userId);
            if (!appDir.exists() && !appDir.mkdirs()) {
                Slog.e(LOG_TAG, "Cannot create instant app cookie directory");
                return;
            }

            if (cookieFile.exists() && !cookieFile.delete()) {
                Slog.e(LOG_TAG, "Cannot delete instant app cookie file");
            }

            // No cookie or an empty one means delete - done
            if (cookie == null || cookie.length <= 0) {
                return;
            }
        }
        try (FileOutputStream fos = new FileOutputStream(cookieFile)) {
            fos.write(cookie, 0, cookie.length);
        } catch (IOException e) {
            Slog.e(LOG_TAG, "Error writing instant app cookie file: " + cookieFile, e);
        }
    }

    public Bitmap getInstantAppIconLPw(@NonNull String packageName,
                                       @UserIdInt int userId) {
        File iconFile = new File(getInstantApplicationDir(packageName, userId),
                INSTANT_APP_ICON_FILE);
        if (iconFile.exists()) {
            return BitmapFactory.decodeFile(iconFile.toString());
        }
        return null;
    }

    public String getInstantAppAndroidIdLPw(@NonNull String packageName,
                                            @UserIdInt int userId) {
        File idFile = new File(getInstantApplicationDir(packageName, userId),
                INSTANT_APP_ANDROID_ID_FILE);
        if (idFile.exists()) {
            try {
                return IoUtils.readFileAsString(idFile.getAbsolutePath());
            } catch (IOException e) {
                Slog.e(LOG_TAG, "Failed to read instant app android id file: " + idFile, e);
            }
        }
        return generateInstantAppAndroidIdLPw(packageName, userId);
    }

    private String generateInstantAppAndroidIdLPw(@NonNull String packageName,
                                                @UserIdInt int userId) {
        byte[] randomBytes = new byte[8];
        new SecureRandom().nextBytes(randomBytes);
        String id = HexEncoding.encodeToString(randomBytes, false /* upperCase */);
        File appDir = getInstantApplicationDir(packageName, userId);
        if (!appDir.exists() && !appDir.mkdirs()) {
            Slog.e(LOG_TAG, "Cannot create instant app cookie directory");
            return id;
        }
        File idFile = new File(getInstantApplicationDir(packageName, userId),
                INSTANT_APP_ANDROID_ID_FILE);
        try (FileOutputStream fos = new FileOutputStream(idFile)) {
            fos.write(id.getBytes());
        } catch (IOException e) {
            Slog.e(LOG_TAG, "Error writing instant app android id file: " + idFile, e);
        }
        return id;

    }

    @GuardedBy("mService.mLock")
    public @Nullable List<InstantAppInfo> getInstantAppsLPr(@UserIdInt int userId) {
        List<InstantAppInfo> installedApps = getInstalledInstantApplicationsLPr(userId);
        List<InstantAppInfo> uninstalledApps = getUninstalledInstantApplicationsLPr(userId);
        if (installedApps != null) {
            if (uninstalledApps != null) {
                installedApps.addAll(uninstalledApps);
            }
            return installedApps;
        }
        return uninstalledApps;
    }

    @GuardedBy("mService.mLock")
    public void onPackageInstalledLPw(@NonNull AndroidPackage pkg, @NonNull int[] userIds) {
        PackageSetting ps = mPmInternal.getPackageSetting(pkg.getPackageName());
        if (ps == null) {
            return;
        }

        for (int userId : userIds) {
            // Ignore not installed apps
            if (mService.mPackages.get(pkg.getPackageName()) == null || !ps.getInstalled(userId)) {
                continue;
            }

            // Propagate permissions before removing any state
            propagateInstantAppPermissionsIfNeeded(pkg, userId);

            // Track instant apps
            if (ps.getInstantApp(userId)) {
                addInstantAppLPw(userId, ps.appId);
            }

            // Remove the in-memory state
            removeUninstalledInstantAppStateLPw((UninstalledInstantAppState state) ->
                            state.mInstantAppInfo.getPackageName().equals(pkg.getPackageName()),
                    userId);

            // Remove the on-disk state except the cookie
            File instantAppDir = getInstantApplicationDir(pkg.getPackageName(), userId);
            new File(instantAppDir, INSTANT_APP_METADATA_FILE).delete();
            new File(instantAppDir, INSTANT_APP_ICON_FILE).delete();

            // If app signature changed - wipe the cookie
            File currentCookieFile = peekInstantCookieFile(pkg.getPackageName(), userId);
            if (currentCookieFile == null) {
                continue;
            }

            String cookieName = currentCookieFile.getName();
            String currentCookieSha256 =
                    cookieName.substring(INSTANT_APP_COOKIE_FILE_PREFIX.length(),
                            cookieName.length() - INSTANT_APP_COOKIE_FILE_SIFFIX.length());

            // Before we used only the first signature to compute the SHA 256 but some
            // apps could be singed by multiple certs and the cert order is undefined.
            // We prefer the modern computation procedure where all certs are taken
            // into account but also allow the value from the old computation to avoid
            // data loss.
            if (pkg.getSigningDetails().checkCapability(currentCookieSha256,
                    SigningDetails.CertCapabilities.INSTALLED_DATA)) {
                return;
            }

            // For backwards compatibility we accept match based on any signature, since we may have
            // recorded only the first for multiply-signed packages
            final String[] signaturesSha256Digests = PackageUtils.computeSignaturesSha256Digests(
                    pkg.getSigningDetails().getSignatures());
            for (String s : signaturesSha256Digests) {
                if (s.equals(currentCookieSha256)) {
                    return;
                }
            }

            // Sorry, you are out of luck - different signatures - nuke data
            Slog.i(LOG_TAG, "Signature for package " + pkg.getPackageName()
                    + " changed - dropping cookie");
                // Make sure a pending write for the old signed app is cancelled
            mCookiePersistence.cancelPendingPersistLPw(pkg, userId);
            currentCookieFile.delete();
        }
    }

    @GuardedBy("mService.mLock")
    public void onPackageUninstalledLPw(@NonNull AndroidPackage pkg, @Nullable PackageSetting ps,
            @NonNull int[] userIds) {
        if (ps == null) {
            return;
        }

        for (int userId : userIds) {
            if (mService.mPackages.get(pkg.getPackageName()) != null && ps.getInstalled(userId)) {
                continue;
            }

            if (ps.getInstantApp(userId)) {
                // Add a record for an uninstalled instant app
                addUninstalledInstantAppLPw(pkg, userId);
                removeInstantAppLPw(userId, ps.appId);
            } else {
                // Deleting an app prunes all instant state such as cookie
                deleteDir(getInstantApplicationDir(pkg.getPackageName(), userId));
                mCookiePersistence.cancelPendingPersistLPw(pkg, userId);
                removeAppLPw(userId, ps.appId);
            }
        }
    }

    @GuardedBy("mService.mLock")
    public void onUserRemovedLPw(int userId) {
        mUninstalledInstantApps.remove(userId);
        mInstalledInstantAppUids.remove(userId);
        mInstantGrants.remove(userId);
        deleteDir(getInstantApplicationsDir(userId));
    }

    public boolean isInstantAccessGranted(@UserIdInt int userId, int targetAppId,
            int instantAppId) {
        final WatchedSparseArray<WatchedSparseBooleanArray> targetAppList =
                mInstantGrants.get(userId);
        if (targetAppList == null) {
            return false;
        }
        final WatchedSparseBooleanArray instantGrantList = targetAppList.get(targetAppId);
        if (instantGrantList == null) {
            return false;
        }
        return instantGrantList.get(instantAppId);
    }

    /**
     * Allows an app to see an instant app.
     *
     * @param userId the userId in which this access is being granted
     * @param intent when provided, this serves as the intent that caused
     *               this access to be granted
     * @param recipientUid the uid of the app receiving visibility
     * @param instantAppId the app ID of the instant app being made visible
     *                      to the recipient
     * @return {@code true} if access is granted.
     */
    @GuardedBy("mService.mLock")
    public boolean grantInstantAccessLPw(@UserIdInt int userId, @Nullable Intent intent,
            int recipientUid, int instantAppId) {
        if (mInstalledInstantAppUids == null) {
            return false;     // no instant apps installed; no need to grant
        }
        WatchedSparseBooleanArray instantAppList = mInstalledInstantAppUids.get(userId);
        if (instantAppList == null || !instantAppList.get(instantAppId)) {
            return false;     // instant app id isn't installed; no need to grant
        }
        if (instantAppList.get(recipientUid)) {
            return false;     // target app id is an instant app; no need to grant
        }
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            final Set<String> categories = intent.getCategories();
            if (categories != null && categories.contains(Intent.CATEGORY_BROWSABLE)) {
                return false;  // launched via VIEW/BROWSABLE intent; no need to grant
            }
        }
        WatchedSparseArray<WatchedSparseBooleanArray> targetAppList = mInstantGrants.get(userId);
        if (targetAppList == null) {
            targetAppList = new WatchedSparseArray<>();
            mInstantGrants.put(userId, targetAppList);
        }
        WatchedSparseBooleanArray instantGrantList = targetAppList.get(recipientUid);
        if (instantGrantList == null) {
            instantGrantList = new WatchedSparseBooleanArray();
            targetAppList.put(recipientUid, instantGrantList);
        }
        instantGrantList.put(instantAppId, true /*granted*/);
        return true;
    }

    @GuardedBy("mService.mLock")
    public void addInstantAppLPw(@UserIdInt int userId, int instantAppId) {
        WatchedSparseBooleanArray instantAppList = mInstalledInstantAppUids.get(userId);
        if (instantAppList == null) {
            instantAppList = new WatchedSparseBooleanArray();
            mInstalledInstantAppUids.put(userId, instantAppList);
        }
        instantAppList.put(instantAppId, true /*installed*/);
        onChanged();
    }

    @GuardedBy("mService.mLock")
    private void removeInstantAppLPw(@UserIdInt int userId, int instantAppId) {
        // remove from the installed list
        if (mInstalledInstantAppUids == null) {
            return; // no instant apps on the system
        }
        final WatchedSparseBooleanArray instantAppList = mInstalledInstantAppUids.get(userId);
        if (instantAppList == null) {
            return;
        }

        try {
            instantAppList.delete(instantAppId);

            // remove any grants
            if (mInstantGrants == null) {
                return; // no grants on the system
            }
            final WatchedSparseArray<WatchedSparseBooleanArray> targetAppList =
                    mInstantGrants.get(userId);
            if (targetAppList == null) {
                return; // no grants for this user
            }
            for (int i = targetAppList.size() - 1; i >= 0; --i) {
                targetAppList.valueAt(i).delete(instantAppId);
            }
        } finally {
            onChanged();
        }
    }

    @GuardedBy("mService.mLock")
    private void removeAppLPw(@UserIdInt int userId, int targetAppId) {
        // remove from the installed list
        if (mInstantGrants == null) {
            return; // no grants on the system
        }
        final WatchedSparseArray<WatchedSparseBooleanArray> targetAppList =
                mInstantGrants.get(userId);
        if (targetAppList == null) {
            return; // no grants for this user
        }
        targetAppList.delete(targetAppId);
        onChanged();
    }

    @GuardedBy("mService.mLock")
    private void addUninstalledInstantAppLPw(@NonNull AndroidPackage pkg,
            @UserIdInt int userId) {
        InstantAppInfo uninstalledApp = createInstantAppInfoForPackage(
                pkg, userId, false);
        if (uninstalledApp == null) {
            return;
        }
        List<UninstalledInstantAppState> uninstalledAppStates =
                mUninstalledInstantApps.get(userId);
        if (uninstalledAppStates == null) {
            uninstalledAppStates = new ArrayList<>();
            mUninstalledInstantApps.put(userId, uninstalledAppStates);
        }
        UninstalledInstantAppState uninstalledAppState = new UninstalledInstantAppState(
                uninstalledApp, System.currentTimeMillis());
        uninstalledAppStates.add(uninstalledAppState);

        writeUninstalledInstantAppMetadata(uninstalledApp, userId);
        writeInstantApplicationIconLPw(pkg, userId);
    }

    private void writeInstantApplicationIconLPw(@NonNull AndroidPackage pkg,
            @UserIdInt int userId) {
        File appDir = getInstantApplicationDir(pkg.getPackageName(), userId);
        if (!appDir.exists()) {
            return;
        }

        // TODO(b/135203078): Remove toAppInfo call? Requires significant additions/changes to PM
        Drawable icon = pkg.toAppInfoWithoutState().loadIcon(mService.mContext.getPackageManager());

        final Bitmap bitmap;
        if (icon instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) icon).getBitmap();
        } else  {
            bitmap = Bitmap.createBitmap(icon.getIntrinsicWidth(),
                    icon.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            icon.setBounds(0, 0, icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
            icon.draw(canvas);
        }

        File iconFile = new File(getInstantApplicationDir(pkg.getPackageName(), userId),
                INSTANT_APP_ICON_FILE);

        try (FileOutputStream out = new FileOutputStream(iconFile)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) {
            Slog.e(LOG_TAG, "Error writing instant app icon", e);
        }
    }

    @GuardedBy("mService.mLock")
    boolean hasInstantApplicationMetadataLPr(String packageName, int userId) {
        return hasUninstalledInstantAppStateLPr(packageName, userId)
                || hasInstantAppMetadataLPr(packageName, userId);
    }

    @GuardedBy("mService.mLock")
    public void deleteInstantApplicationMetadataLPw(@NonNull String packageName,
            @UserIdInt int userId) {
        removeUninstalledInstantAppStateLPw((UninstalledInstantAppState state) ->
                state.mInstantAppInfo.getPackageName().equals(packageName),
                userId);

        File instantAppDir = getInstantApplicationDir(packageName, userId);
        new File(instantAppDir, INSTANT_APP_METADATA_FILE).delete();
        new File(instantAppDir, INSTANT_APP_ICON_FILE).delete();
        new File(instantAppDir, INSTANT_APP_ANDROID_ID_FILE).delete();
        File cookie = peekInstantCookieFile(packageName, userId);
        if (cookie != null) {
            cookie.delete();
        }
    }

    @GuardedBy("mService.mLock")
    private void removeUninstalledInstantAppStateLPw(
            @NonNull Predicate<UninstalledInstantAppState> criteria, @UserIdInt int userId) {
        if (mUninstalledInstantApps == null) {
            return;
        }
        List<UninstalledInstantAppState> uninstalledAppStates =
                mUninstalledInstantApps.get(userId);
        if (uninstalledAppStates == null) {
            return;
        }
        final int appCount = uninstalledAppStates.size();
        for (int i = appCount - 1; i >= 0; --i) {
            UninstalledInstantAppState uninstalledAppState = uninstalledAppStates.get(i);
            if (!criteria.test(uninstalledAppState)) {
                continue;
            }
            uninstalledAppStates.remove(i);
            if (uninstalledAppStates.isEmpty()) {
                mUninstalledInstantApps.remove(userId);
                onChanged();
                return;
            }
        }
    }

    @GuardedBy("mService.mLock")
    private boolean hasUninstalledInstantAppStateLPr(String packageName, @UserIdInt int userId) {
        if (mUninstalledInstantApps == null) {
            return false;
        }
        final List<UninstalledInstantAppState> uninstalledAppStates =
                mUninstalledInstantApps.get(userId);
        if (uninstalledAppStates == null) {
            return false;
        }
        final int appCount = uninstalledAppStates.size();
        for (int i = 0; i < appCount; i++) {
            final UninstalledInstantAppState uninstalledAppState = uninstalledAppStates.get(i);
            if (packageName.equals(uninstalledAppState.mInstantAppInfo.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasInstantAppMetadataLPr(String packageName, @UserIdInt int userId) {
        final File instantAppDir = getInstantApplicationDir(packageName, userId);
        return new File(instantAppDir, INSTANT_APP_METADATA_FILE).exists()
                || new File(instantAppDir, INSTANT_APP_ICON_FILE).exists()
                || new File(instantAppDir, INSTANT_APP_ANDROID_ID_FILE).exists()
                || peekInstantCookieFile(packageName, userId) != null;
    }

    void pruneInstantApps() {
        final long maxInstalledCacheDuration = Settings.Global.getLong(
                mService.mContext.getContentResolver(),
                Settings.Global.INSTALLED_INSTANT_APP_MAX_CACHE_PERIOD,
                DEFAULT_INSTALLED_INSTANT_APP_MAX_CACHE_PERIOD);

        final long maxUninstalledCacheDuration = Settings.Global.getLong(
                mService.mContext.getContentResolver(),
                Settings.Global.UNINSTALLED_INSTANT_APP_MAX_CACHE_PERIOD,
                DEFAULT_UNINSTALLED_INSTANT_APP_MAX_CACHE_PERIOD);

        try {
            pruneInstantApps(Long.MAX_VALUE,
                    maxInstalledCacheDuration, maxUninstalledCacheDuration);
        } catch (IOException e) {
            Slog.e(LOG_TAG, "Error pruning installed and uninstalled instant apps", e);
        }
    }

    boolean pruneInstalledInstantApps(long neededSpace, long maxInstalledCacheDuration) {
        try {
            return pruneInstantApps(neededSpace, maxInstalledCacheDuration, Long.MAX_VALUE);
        } catch (IOException e) {
            Slog.e(LOG_TAG, "Error pruning installed instant apps", e);
            return false;
        }
    }

    boolean pruneUninstalledInstantApps(long neededSpace, long maxUninstalledCacheDuration) {
        try {
            return pruneInstantApps(neededSpace, Long.MAX_VALUE, maxUninstalledCacheDuration);
        } catch (IOException e) {
            Slog.e(LOG_TAG, "Error pruning uninstalled instant apps", e);
            return false;
        }
    }

    /**
     * Prunes instant apps until there is enough <code>neededSpace</code>. Both
     * installed and uninstalled instant apps are pruned that are older than
     * <code>maxInstalledCacheDuration</code> and <code>maxUninstalledCacheDuration</code>
     * respectively. All times are in milliseconds.
     *
     * @param neededSpace The space to ensure is free.
     * @param maxInstalledCacheDuration The max duration for caching installed apps in millis.
     * @param maxUninstalledCacheDuration The max duration for caching uninstalled apps in millis.
     * @return Whether enough space was freed.
     *
     * @throws IOException
     */
    private boolean pruneInstantApps(long neededSpace, long maxInstalledCacheDuration,
            long maxUninstalledCacheDuration) throws IOException {
        final StorageManager storage = mService.mContext.getSystemService(StorageManager.class);
        final File file = storage.findPathForUuid(StorageManager.UUID_PRIVATE_INTERNAL);

        if (file.getUsableSpace() >= neededSpace) {
            return true;
        }

        List<String> packagesToDelete = null;

        final int[] allUsers;
        final long now = System.currentTimeMillis();

        // Prune first installed instant apps
        synchronized (mService.mLock) {
            allUsers = mService.mUserManager.getUserIds();

            final int packageCount = mService.mPackages.size();
            for (int i = 0; i < packageCount; i++) {
                final AndroidPackage pkg = mService.mPackages.valueAt(i);
                final PackageSetting ps = mPmInternal.getPackageSetting(pkg.getPackageName());
                if (ps == null) {
                    continue;
                }

                if (now - ps.getPkgState().getLatestPackageUseTimeInMills()
                        < maxInstalledCacheDuration) {
                    continue;
                }

                boolean installedOnlyAsInstantApp = false;
                for (int userId : allUsers) {
                    if (ps.getInstalled(userId)) {
                        if (ps.getInstantApp(userId)) {
                            installedOnlyAsInstantApp = true;
                        } else {
                            installedOnlyAsInstantApp = false;
                            break;
                        }
                    }
                }
                if (installedOnlyAsInstantApp) {
                    if (packagesToDelete == null) {
                        packagesToDelete = new ArrayList<>();
                    }
                    packagesToDelete.add(pkg.getPackageName());
                }
            }

            if (packagesToDelete != null) {
                packagesToDelete.sort((String lhs, String rhs) -> {
                    final AndroidPackage lhsPkg = mService.mPackages.get(lhs);
                    final AndroidPackage rhsPkg = mService.mPackages.get(rhs);
                    if (lhsPkg == null && rhsPkg == null) {
                        return 0;
                    } else if (lhsPkg == null) {
                        return -1;
                    } else if (rhsPkg == null) {
                        return 1;
                    } else {
                        final PackageSetting lhsPs = mPmInternal.getPackageSetting(
                                lhsPkg.getPackageName());
                        if (lhsPs == null) {
                            return 0;
                        }

                        final PackageSetting rhsPs = mPmInternal.getPackageSetting(
                                rhsPkg.getPackageName());
                        if (rhsPs == null) {
                            return 0;
                        }

                        if (lhsPs.getPkgState().getLatestPackageUseTimeInMills() >
                                rhsPs.getPkgState().getLatestPackageUseTimeInMills()) {
                            return 1;
                        } else if (lhsPs.getPkgState().getLatestPackageUseTimeInMills() <
                                rhsPs.getPkgState().getLatestPackageUseTimeInMills()) {
                            return -1;
                        } else if (lhsPs.firstInstallTime > rhsPs.firstInstallTime) {
                            return 1;
                        } else {
                            return -1;
                        }
                    }
                });
            }
        }

        if (packagesToDelete != null) {
            final int packageCount = packagesToDelete.size();
            for (int i = 0; i < packageCount; i++) {
                final String packageToDelete = packagesToDelete.get(i);
                if (mService.deletePackageX(packageToDelete, PackageManager.VERSION_CODE_HIGHEST,
                        UserHandle.USER_SYSTEM, PackageManager.DELETE_ALL_USERS,
                        true /*removedBySystem*/) == PackageManager.DELETE_SUCCEEDED) {
                    if (file.getUsableSpace() >= neededSpace) {
                        return true;
                    }
                }
            }
        }

        // Prune uninstalled instant apps
        synchronized (mService.mLock) {
            // TODO: Track last used time for uninstalled instant apps for better pruning
            for (int userId : UserManagerService.getInstance().getUserIds()) {
                // Prune in-memory state
                removeUninstalledInstantAppStateLPw((UninstalledInstantAppState state) -> {
                    final long elapsedCachingMillis = System.currentTimeMillis() - state.mTimestamp;
                    return (elapsedCachingMillis > maxUninstalledCacheDuration);
                }, userId);

                // Prune on-disk state
                File instantAppsDir = getInstantApplicationsDir(userId);
                if (!instantAppsDir.exists()) {
                    continue;
                }
                File[] files = instantAppsDir.listFiles();
                if (files == null) {
                    continue;
                }
                for (File instantDir : files) {
                    if (!instantDir.isDirectory()) {
                        continue;
                    }

                    File metadataFile = new File(instantDir, INSTANT_APP_METADATA_FILE);
                    if (!metadataFile.exists()) {
                        continue;
                    }

                    final long elapsedCachingMillis = System.currentTimeMillis()
                            - metadataFile.lastModified();
                    if (elapsedCachingMillis > maxUninstalledCacheDuration) {
                        deleteDir(instantDir);
                        if (file.getUsableSpace() >= neededSpace) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    @GuardedBy("mService.mLock")
    private @Nullable List<InstantAppInfo> getInstalledInstantApplicationsLPr(
            @UserIdInt int userId) {
        List<InstantAppInfo> result = null;

        final int packageCount = mService.mPackages.size();
        for (int i = 0; i < packageCount; i++) {
            final AndroidPackage pkg = mService.mPackages.valueAt(i);
            final PackageSetting ps = mPmInternal.getPackageSetting(pkg.getPackageName());
            if (ps == null || !ps.getInstantApp(userId)) {
                continue;
            }
            final InstantAppInfo info = createInstantAppInfoForPackage(
                    pkg, userId, true);
            if (info == null) {
                continue;
            }
            if (result == null) {
                result = new ArrayList<>();
            }
            result.add(info);
        }

        return result;
    }

    private @NonNull
    InstantAppInfo createInstantAppInfoForPackage(
            @NonNull AndroidPackage pkg, @UserIdInt int userId,
            boolean addApplicationInfo) {
        PackageSetting ps = mPmInternal.getPackageSetting(pkg.getPackageName());
        if (ps == null) {
            return null;
        }
        if (!ps.getInstalled(userId)) {
            return null;
        }

        String[] requestedPermissions = new String[pkg.getRequestedPermissions().size()];
        pkg.getRequestedPermissions().toArray(requestedPermissions);

        Set<String> permissions = mPermissionManager.getGrantedPermissions(
                pkg.getPackageName(), userId);
        String[] grantedPermissions = new String[permissions.size()];
        permissions.toArray(grantedPermissions);

        // TODO(b/135203078): This may be broken due to inner mutability problems that were broken
        //  as part of moving to PackageInfoUtils. Flags couldn't be determined.
        ApplicationInfo appInfo = PackageInfoUtils.generateApplicationInfo(ps.pkg, 0,
                ps.readUserState(userId), userId, ps);
        if (addApplicationInfo) {
            return new InstantAppInfo(appInfo, requestedPermissions, grantedPermissions);
        } else {
            return new InstantAppInfo(appInfo.packageName,
                    appInfo.loadLabel(mService.mContext.getPackageManager()),
                    requestedPermissions, grantedPermissions);
        }
    }

    @GuardedBy("mService.mLock")
    private @Nullable List<InstantAppInfo> getUninstalledInstantApplicationsLPr(
            @UserIdInt int userId) {
        List<UninstalledInstantAppState> uninstalledAppStates =
                getUninstalledInstantAppStatesLPr(userId);
        if (uninstalledAppStates == null || uninstalledAppStates.isEmpty()) {
            return null;
        }

        List<InstantAppInfo> uninstalledApps = null;
        final int stateCount = uninstalledAppStates.size();
        for (int i = 0; i < stateCount; i++) {
            UninstalledInstantAppState uninstalledAppState = uninstalledAppStates.get(i);
            if (uninstalledApps == null) {
                uninstalledApps = new ArrayList<>();
            }
            uninstalledApps.add(uninstalledAppState.mInstantAppInfo);
        }
        return uninstalledApps;
    }

    private void propagateInstantAppPermissionsIfNeeded(@NonNull AndroidPackage pkg,
            @UserIdInt int userId) {
        InstantAppInfo appInfo = peekOrParseUninstalledInstantAppInfo(
                pkg.getPackageName(), userId);
        if (appInfo == null) {
            return;
        }
        if (ArrayUtils.isEmpty(appInfo.getGrantedPermissions())) {
            return;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            for (String grantedPermission : appInfo.getGrantedPermissions()) {
                final boolean propagatePermission = canPropagatePermission(grantedPermission);
                if (propagatePermission && pkg.getRequestedPermissions().contains(
                        grantedPermission)) {
                    mService.grantRuntimePermission(pkg.getPackageName(), grantedPermission,
                            userId);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean canPropagatePermission(@NonNull String permissionName) {
        final PermissionManager permissionManager = mService.mContext.getSystemService(
                PermissionManager.class);
        final PermissionInfo permissionInfo = permissionManager.getPermissionInfo(permissionName,
                0);
        return permissionInfo != null
                && (permissionInfo.getProtection() == PermissionInfo.PROTECTION_DANGEROUS
                        || (permissionInfo.getProtectionFlags()
                                & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0)
                && (permissionInfo.getProtectionFlags() & PermissionInfo.PROTECTION_FLAG_INSTANT)
                        != 0;
    }

    private @NonNull
    InstantAppInfo peekOrParseUninstalledInstantAppInfo(
            @NonNull String packageName, @UserIdInt int userId) {
        if (mUninstalledInstantApps != null) {
            List<UninstalledInstantAppState> uninstalledAppStates =
                    mUninstalledInstantApps.get(userId);
            if (uninstalledAppStates != null) {
                final int appCount = uninstalledAppStates.size();
                for (int i = 0; i < appCount; i++) {
                    UninstalledInstantAppState uninstalledAppState = uninstalledAppStates.get(i);
                    if (uninstalledAppState.mInstantAppInfo
                            .getPackageName().equals(packageName)) {
                        return uninstalledAppState.mInstantAppInfo;
                    }
                }
            }
        }

        File metadataFile = new File(getInstantApplicationDir(packageName, userId),
                INSTANT_APP_METADATA_FILE);
        UninstalledInstantAppState uninstalledAppState = parseMetadataFile(metadataFile);
        if (uninstalledAppState == null) {
            return null;
        }

        return uninstalledAppState.mInstantAppInfo;
    }

    @GuardedBy("mService.mLock")
    private @Nullable List<UninstalledInstantAppState> getUninstalledInstantAppStatesLPr(
            @UserIdInt int userId) {
        List<UninstalledInstantAppState> uninstalledAppStates = null;
        if (mUninstalledInstantApps != null) {
            uninstalledAppStates = mUninstalledInstantApps.get(userId);
            if (uninstalledAppStates != null) {
                return uninstalledAppStates;
            }
        }

        File instantAppsDir = getInstantApplicationsDir(userId);
        if (instantAppsDir.exists()) {
            File[] files = instantAppsDir.listFiles();
            if (files != null) {
                for (File instantDir : files) {
                    if (!instantDir.isDirectory()) {
                        continue;
                    }
                    File metadataFile = new File(instantDir,
                            INSTANT_APP_METADATA_FILE);
                    UninstalledInstantAppState uninstalledAppState =
                            parseMetadataFile(metadataFile);
                    if (uninstalledAppState == null) {
                        continue;
                    }
                    if (uninstalledAppStates == null) {
                        uninstalledAppStates = new ArrayList<>();
                    }
                    uninstalledAppStates.add(uninstalledAppState);
                }
            }
        }

        mUninstalledInstantApps.put(userId, uninstalledAppStates);

        return uninstalledAppStates;
    }

    private static @Nullable UninstalledInstantAppState parseMetadataFile(
            @NonNull File metadataFile) {
        if (!metadataFile.exists()) {
            return null;
        }
        FileInputStream in;
        try {
            in = new AtomicFile(metadataFile).openRead();
        } catch (FileNotFoundException fnfe) {
            Slog.i(LOG_TAG, "No instant metadata file");
            return null;
        }

        final File instantDir = metadataFile.getParentFile();
        final long timestamp = metadataFile.lastModified();
        final String packageName = instantDir.getName();

        try {
            TypedXmlPullParser parser = Xml.resolvePullParser(in);
            return new UninstalledInstantAppState(
                    parseMetadata(parser, packageName), timestamp);
        } catch (XmlPullParserException | IOException e) {
            throw new IllegalStateException("Failed parsing instant"
                    + " metadata file: " + metadataFile, e);
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    private static @NonNull File computeInstantCookieFile(@NonNull String packageName,
            @NonNull String sha256Digest, @UserIdInt int userId) {
        final File appDir = getInstantApplicationDir(packageName, userId);
        final String cookieFile = INSTANT_APP_COOKIE_FILE_PREFIX
                + sha256Digest + INSTANT_APP_COOKIE_FILE_SIFFIX;
        return new File(appDir, cookieFile);
    }

    private static @Nullable File peekInstantCookieFile(@NonNull String packageName,
            @UserIdInt int userId) {
        File appDir = getInstantApplicationDir(packageName, userId);
        if (!appDir.exists()) {
            return null;
        }
        File[] files = appDir.listFiles();
        if (files == null) {
            return null;
        }
        for (File file : files) {
            if (!file.isDirectory()
                    && file.getName().startsWith(INSTANT_APP_COOKIE_FILE_PREFIX)
                    && file.getName().endsWith(INSTANT_APP_COOKIE_FILE_SIFFIX)) {
                return file;
            }
        }
        return null;
    }

    private static @Nullable
    InstantAppInfo parseMetadata(@NonNull TypedXmlPullParser parser,
                                 @NonNull String packageName)
            throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (TAG_PACKAGE.equals(parser.getName())) {
                return parsePackage(parser, packageName);
            }
        }
        return null;
    }

    private static InstantAppInfo parsePackage(@NonNull TypedXmlPullParser parser,
                                               @NonNull String packageName)
            throws IOException, XmlPullParserException {
        String label = parser.getAttributeValue(null, ATTR_LABEL);

        List<String> outRequestedPermissions = new ArrayList<>();
        List<String> outGrantedPermissions = new ArrayList<>();

        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (TAG_PERMISSIONS.equals(parser.getName())) {
                parsePermissions(parser, outRequestedPermissions, outGrantedPermissions);
            }
        }

        String[] requestedPermissions = new String[outRequestedPermissions.size()];
        outRequestedPermissions.toArray(requestedPermissions);

        String[] grantedPermissions = new String[outGrantedPermissions.size()];
        outGrantedPermissions.toArray(grantedPermissions);

        return new InstantAppInfo(packageName, label,
                requestedPermissions, grantedPermissions);
    }

    private static void parsePermissions(@NonNull TypedXmlPullParser parser,
            @NonNull List<String> outRequestedPermissions,
            @NonNull List<String> outGrantedPermissions)
            throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser,outerDepth)) {
            if (TAG_PERMISSION.equals(parser.getName())) {
                String permission = XmlUtils.readStringAttribute(parser, ATTR_NAME);
                outRequestedPermissions.add(permission);
                if (parser.getAttributeBoolean(null, ATTR_GRANTED, false)) {
                    outGrantedPermissions.add(permission);
                }
            }
        }
    }

    private void writeUninstalledInstantAppMetadata(
            @NonNull InstantAppInfo instantApp, @UserIdInt int userId) {
        File appDir = getInstantApplicationDir(instantApp.getPackageName(), userId);
        if (!appDir.exists() && !appDir.mkdirs()) {
            return;
        }

        File metadataFile = new File(appDir, INSTANT_APP_METADATA_FILE);

        AtomicFile destination = new AtomicFile(metadataFile);
        FileOutputStream out = null;
        try {
            out = destination.startWrite();

            TypedXmlSerializer serializer = Xml.resolveSerializer(out);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startDocument(null, true);

            serializer.startTag(null, TAG_PACKAGE);
            serializer.attribute(null, ATTR_LABEL, instantApp.loadLabel(
                    mService.mContext.getPackageManager()).toString());

            serializer.startTag(null, TAG_PERMISSIONS);
            for (String permission : instantApp.getRequestedPermissions()) {
                serializer.startTag(null, TAG_PERMISSION);
                serializer.attribute(null, ATTR_NAME, permission);
                if (ArrayUtils.contains(instantApp.getGrantedPermissions(), permission)) {
                    serializer.attributeBoolean(null, ATTR_GRANTED, true);
                }
                serializer.endTag(null, TAG_PERMISSION);
            }
            serializer.endTag(null, TAG_PERMISSIONS);

            serializer.endTag(null, TAG_PACKAGE);

            serializer.endDocument();
            destination.finishWrite(out);
        } catch (Throwable t) {
            Slog.wtf(LOG_TAG, "Failed to write instant state, restoring backup", t);
            destination.failWrite(out);
        } finally {
            IoUtils.closeQuietly(out);
        }
    }

    private static @NonNull File getInstantApplicationsDir(int userId) {
        return new File(Environment.getUserSystemDirectory(userId),
                INSTANT_APPS_FOLDER);
    }

    private static @NonNull File getInstantApplicationDir(String packageName, int userId) {
        return new File(getInstantApplicationsDir(userId), packageName);
    }

    private static void deleteDir(@NonNull File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                deleteDir(file);
            }
        }
        dir.delete();
    }

    private static final class UninstalledInstantAppState {
        final InstantAppInfo mInstantAppInfo;
        final long mTimestamp;

        public UninstalledInstantAppState(InstantAppInfo instantApp,
                long timestamp) {
            mInstantAppInfo = instantApp;
            mTimestamp = timestamp;
        }
    }

    private final class CookiePersistence extends Handler {
        private static final long PERSIST_COOKIE_DELAY_MILLIS = 1000L; /* one second */

        // The cookies are cached per package name per user-id in this sparse
        // array. The caching is so that pending persistence can be canceled within
        // a short interval. To ensure we still return pending persist cookies
        // for a package that uninstalled and reinstalled while the persistence
        // was still pending, we use the package name as a key for
        // mPendingPersistCookies, since that stays stable across reinstalls.
        private final SparseArray<ArrayMap<String, SomeArgs>> mPendingPersistCookies
                = new SparseArray<>();

        public CookiePersistence(Looper looper) {
            super(looper);
        }

        public void schedulePersistLPw(@UserIdInt int userId, @NonNull AndroidPackage pkg,
                @NonNull byte[] cookie) {
            // Before we used only the first signature to compute the SHA 256 but some
            // apps could be singed by multiple certs and the cert order is undefined.
            // We prefer the modern computation procedure where all certs are taken
            // into account and delete the file derived via the legacy hash computation.
            File newCookieFile = computeInstantCookieFile(pkg.getPackageName(),
                    PackageUtils.computeSignaturesSha256Digest(
                            pkg.getSigningDetails().getSignatures()), userId);
            if (!pkg.getSigningDetails().hasSignatures()) {
                Slog.wtf(LOG_TAG, "Parsed Instant App contains no valid signatures!");
            }
            File oldCookieFile = peekInstantCookieFile(pkg.getPackageName(), userId);
            if (oldCookieFile != null && !newCookieFile.equals(oldCookieFile)) {
                oldCookieFile.delete();
            }
            cancelPendingPersistLPw(pkg, userId);
            addPendingPersistCookieLPw(userId, pkg, cookie, newCookieFile);
            sendMessageDelayed(obtainMessage(userId, pkg),
                    PERSIST_COOKIE_DELAY_MILLIS);
        }

        public @Nullable byte[] getPendingPersistCookieLPr(@NonNull AndroidPackage pkg,
                @UserIdInt int userId) {
            ArrayMap<String, SomeArgs> pendingWorkForUser =
                    mPendingPersistCookies.get(userId);
            if (pendingWorkForUser != null) {
                SomeArgs state = pendingWorkForUser.get(pkg.getPackageName());
                if (state != null) {
                    return (byte[]) state.arg1;
                }
            }
            return null;
        }

        public void cancelPendingPersistLPw(@NonNull AndroidPackage pkg,
                @UserIdInt int userId) {
            removeMessages(userId, pkg);
            SomeArgs state = removePendingPersistCookieLPr(pkg, userId);
            if (state != null) {
                state.recycle();
            }
        }

        private void addPendingPersistCookieLPw(@UserIdInt int userId,
                @NonNull AndroidPackage pkg, @NonNull byte[] cookie,
                @NonNull File cookieFile) {
            ArrayMap<String, SomeArgs> pendingWorkForUser =
                    mPendingPersistCookies.get(userId);
            if (pendingWorkForUser == null) {
                pendingWorkForUser = new ArrayMap<>();
                mPendingPersistCookies.put(userId, pendingWorkForUser);
            }
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = cookie;
            args.arg2 = cookieFile;
            pendingWorkForUser.put(pkg.getPackageName(), args);
        }

        private SomeArgs removePendingPersistCookieLPr(@NonNull AndroidPackage pkg,
                @UserIdInt int userId) {
            ArrayMap<String, SomeArgs> pendingWorkForUser =
                    mPendingPersistCookies.get(userId);
            SomeArgs state = null;
            if (pendingWorkForUser != null) {
                state = pendingWorkForUser.remove(pkg.getPackageName());
                if (pendingWorkForUser.isEmpty()) {
                    mPendingPersistCookies.remove(userId);
                }
            }
            return state;
        }

        @Override
        public void handleMessage(Message message) {
            int userId = message.what;
            AndroidPackage pkg = (AndroidPackage) message.obj;
            SomeArgs state = removePendingPersistCookieLPr(pkg, userId);
            if (state == null) {
                return;
            }
            byte[] cookie = (byte[]) state.arg1;
            File cookieFile = (File) state.arg2;
            state.recycle();
            persistInstantApplicationCookie(cookie, pkg.getPackageName(), cookieFile, userId);
        }
    }
}
