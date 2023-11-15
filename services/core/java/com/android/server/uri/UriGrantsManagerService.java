/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.uri;

import static android.Manifest.permission.CLEAR_APP_GRANTED_URI_PERMISSIONS;
import static android.Manifest.permission.FORCE_PERSISTABLE_URI_PERMISSIONS;
import static android.Manifest.permission.GET_APP_GRANTED_URI_PERMISSIONS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.app.ActivityManagerInternal.ALLOW_FULL_ONLY;
import static android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
import static android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
import static android.content.pm.PackageManager.MATCH_ANY_USER;
import static android.content.pm.PackageManager.MATCH_DEBUG_TRIAGED_MISSING;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AUTO;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Process.ROOT_UID;
import static android.os.Process.SYSTEM_UID;
import static android.os.Process.myUid;

import static com.android.internal.util.XmlUtils.writeBooleanAttribute;
import static com.android.server.uri.UriGrantsManagerService.H.PERSIST_URI_GRANTS_MSG;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.GrantedUriPermission;
import android.app.IUriGrantsManager;
import android.content.ClipData;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.PathPermission;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Downloads;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;

import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/** Manages uri grants. */
public class UriGrantsManagerService extends IUriGrantsManager.Stub implements
        UriMetricsHelper.PersistentUriGrantsProvider {
    private static final boolean DEBUG = false;
    private static final String TAG = "UriGrantsManagerService";
    // Maximum number of persisted Uri grants a package is allowed
    private static final int MAX_PERSISTED_URI_GRANTS = 512;
    private static final boolean ENABLE_DYNAMIC_PERMISSIONS = true;

    private final Object mLock = new Object();
    private final H mH;
    ActivityManagerInternal mAmInternal;
    PackageManagerInternal mPmInternal;
    UriMetricsHelper mMetricsHelper;

    /** File storing persisted {@link #mGrantedUriPermissions}. */
    private final AtomicFile mGrantFile;

    /** XML constants used in {@link #mGrantFile} */
    private static final String TAG_URI_GRANTS = "uri-grants";
    private static final String TAG_URI_GRANT = "uri-grant";
    private static final String ATTR_USER_HANDLE = "userHandle";
    private static final String ATTR_SOURCE_USER_ID = "sourceUserId";
    private static final String ATTR_TARGET_USER_ID = "targetUserId";
    private static final String ATTR_SOURCE_PKG = "sourcePkg";
    private static final String ATTR_TARGET_PKG = "targetPkg";
    private static final String ATTR_URI = "uri";
    private static final String ATTR_MODE_FLAGS = "modeFlags";
    private static final String ATTR_CREATED_TIME = "createdTime";
    private static final String ATTR_PREFIX = "prefix";

    /**
     * Global set of specific {@link Uri} permissions that have been granted.
     * This optimized lookup structure maps from {@link UriPermission#targetUid}
     * to {@link UriPermission#uri} to {@link UriPermission}.
     */
    @GuardedBy("mLock")
    private final SparseArray<ArrayMap<GrantUri, UriPermission>>
            mGrantedUriPermissions = new SparseArray<>();

    private UriGrantsManagerService() {
        this(SystemServiceManager.ensureSystemDir(), "uri-grants");
    }

    private UriGrantsManagerService(File systemDir, String commitTag) {
        mH = new H(IoThread.get().getLooper());
        final File file = new File(systemDir, "urigrants.xml");
        mGrantFile = (commitTag != null) ? new AtomicFile(file, commitTag) : new AtomicFile(file);
    }

    @VisibleForTesting
    static UriGrantsManagerService createForTest(File systemDir) {
        final UriGrantsManagerService service = new UriGrantsManagerService(systemDir, null) {
            @VisibleForTesting
            protected int checkUidPermission(String permission, int uid) {
                // Tests have no permission granted
                return PackageManager.PERMISSION_DENIED;
            }

            @VisibleForTesting
            protected int checkComponentPermission(String permission, int uid, int owningUid,
                    boolean exported) {
                // Tests have no permission granted
                return PackageManager.PERMISSION_DENIED;
            }
        };
        service.mAmInternal = LocalServices.getService(ActivityManagerInternal.class);
        service.mPmInternal = LocalServices.getService(PackageManagerInternal.class);
        return service;
    }

    @VisibleForTesting
    UriGrantsManagerInternal getLocalService() {
        return new LocalService();
    }

    private void start() {
        LocalServices.addService(UriGrantsManagerInternal.class, new LocalService());
    }

    public static final class Lifecycle extends SystemService {
        private final Context mContext;
        private final UriGrantsManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            mContext = context;
            mService = new UriGrantsManagerService();
        }

        @Override
        public void onStart() {
            publishBinderService(Context.URI_GRANTS_SERVICE, mService);
            mService.mMetricsHelper = new UriMetricsHelper(mContext, mService);
            mService.start();
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == PHASE_SYSTEM_SERVICES_READY) {
                mService.mAmInternal = LocalServices.getService(ActivityManagerInternal.class);
                mService.mPmInternal = LocalServices.getService(PackageManagerInternal.class);
                mService.mMetricsHelper.registerPuller();
            }
        }

        public UriGrantsManagerService getService() {
            return mService;
        }
    }

    @VisibleForTesting
    protected int checkUidPermission(String permission, int uid) {
        try {
            return AppGlobals.getPackageManager().checkUidPermission(permission, uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @VisibleForTesting
    protected int checkComponentPermission(String permission, int uid, int owningUid,
            boolean exported) {
        return ActivityManager.checkComponentPermission(permission, uid, owningUid, exported);
    }

    /**
     * Grant uri permissions to the specified app.
     *
     * @param token An opaque owner token for tracking the permissions. See
     *              {@link UriGrantsManagerInternal#newUriPermissionOwner}.
     * @param fromUid The uid of the grantor app that has permissions to the uri. Permissions
     *                will be granted on behalf of this app.
     * @param targetPkg The package name of the grantor app that has permissions to the uri.
     *                  Permissions will be granted on behalf of this app.
     * @param uri The uri for which permissions should be granted. This uri must NOT contain an
     *            embedded userId; use {@link ContentProvider#getUriWithoutUserId(Uri)} if needed.
     * @param modeFlags The modes to grant. See {@link Intent#FLAG_GRANT_READ_URI_PERMISSION}, etc.
     * @param sourceUserId The userId in which the uri is to be resolved.
     * @param targetUserId The userId of the target app to receive the grant.
     */
    @Override
    public void grantUriPermissionFromOwner(IBinder token, int fromUid, String targetPkg,
            Uri uri, final int modeFlags, int sourceUserId, int targetUserId) {
        grantUriPermissionFromOwnerUnlocked(token, fromUid, targetPkg, uri, modeFlags, sourceUserId,
                targetUserId);
    }

    /**
     * See {@link #grantUriPermissionFromOwner(IBinder, int, String, Uri, int, int, int)}.
     */
    private void grantUriPermissionFromOwnerUnlocked(@NonNull IBinder token, int fromUid,
            @NonNull String targetPkg, @NonNull Uri uri, final int modeFlags,
            int sourceUserId, int targetUserId) {
        targetUserId = mAmInternal.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), targetUserId, false, ALLOW_FULL_ONLY,
                "grantUriPermissionFromOwner", null);

        UriPermissionOwner owner = UriPermissionOwner.fromExternalToken(token);
        if (owner == null) {
            throw new IllegalArgumentException("Unknown owner: " + token);
        }
        if (fromUid != Binder.getCallingUid()) {
            if (Binder.getCallingUid() != myUid()) {
                // Only system code can grant URI permissions on behalf
                // of other users.
                throw new SecurityException("nice try");
            }
        }
        if (targetPkg == null) {
            throw new IllegalArgumentException("null target");
        }
        if (uri == null) {
            throw new IllegalArgumentException("null uri");
        }

        grantUriPermissionUnlocked(fromUid, targetPkg, new GrantUri(sourceUserId, uri, modeFlags),
                modeFlags, owner, targetUserId);
    }

    @Override
    public ParceledListSlice<android.content.UriPermission> getUriPermissions(
            String packageName, boolean incoming, boolean persistedOnly) {
        enforceNotIsolatedCaller("getUriPermissions");
        Objects.requireNonNull(packageName, "packageName");

        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        final PackageManagerInternal pm = LocalServices.getService(PackageManagerInternal.class);
        final int packageUid = pm.getPackageUid(packageName,
                MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE, callingUserId);
        if (packageUid != callingUid) {
            throw new SecurityException(
                    "Package " + packageName + " does not belong to calling UID " + callingUid);
        }

        final ArrayList<android.content.UriPermission> result = Lists.newArrayList();
        synchronized (mLock) {
            if (incoming) {
                final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.get(
                        callingUid);
                if (perms == null) {
                    Slog.w(TAG, "No permission grants found for " + packageName);
                } else {
                    for (int j = 0; j < perms.size(); j++) {
                        final UriPermission perm = perms.valueAt(j);
                        if (packageName.equals(perm.targetPkg)
                                && (!persistedOnly || perm.persistedModeFlags != 0)) {
                            result.add(perm.buildPersistedPublicApiObject());
                        }
                    }
                }
            } else {
                final int size = mGrantedUriPermissions.size();
                for (int i = 0; i < size; i++) {
                    final ArrayMap<GrantUri, UriPermission> perms =
                            mGrantedUriPermissions.valueAt(i);
                    for (int j = 0; j < perms.size(); j++) {
                        final UriPermission perm = perms.valueAt(j);
                        if (packageName.equals(perm.sourcePkg)
                                && (!persistedOnly || perm.persistedModeFlags != 0)) {
                            result.add(perm.buildPersistedPublicApiObject());
                        }
                    }
                }
            }
        }
        return new ParceledListSlice<>(result);
    }

    @Override
    public ParceledListSlice<GrantedUriPermission> getGrantedUriPermissions(
            @Nullable String packageName, int userId) {
        mAmInternal.enforceCallingPermission(
                GET_APP_GRANTED_URI_PERMISSIONS, "getGrantedUriPermissions");

        final List<GrantedUriPermission> result = new ArrayList<>();
        synchronized (mLock) {
            final int size = mGrantedUriPermissions.size();
            for (int i = 0; i < size; i++) {
                final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.valueAt(i);
                for (int j = 0; j < perms.size(); j++) {
                    final UriPermission perm = perms.valueAt(j);
                    if ((packageName == null || packageName.equals(perm.targetPkg))
                            && perm.targetUserId == userId
                            && perm.persistedModeFlags != 0) {
                        result.add(perm.buildGrantedUriPermission());
                    }
                }
            }
        }
        return new ParceledListSlice<>(result);
    }

    /**
     * @param uri This uri must NOT contain an embedded userId.
     * @param toPackage Name of package whose uri is being granted to (if {@code null}, uses
     * calling uid)
     * @param userId The userId in which the uri is to be resolved.
     */
    @Override
    public void takePersistableUriPermission(Uri uri, final int modeFlags,
            @Nullable String toPackage, int userId) {
        final int uid;
        if (toPackage != null) {
            mAmInternal.enforceCallingPermission(FORCE_PERSISTABLE_URI_PERMISSIONS,
                    "takePersistableUriPermission");
            uid = mPmInternal.getPackageUid(toPackage, 0 /* flags */, userId);
        } else {
            enforceNotIsolatedCaller("takePersistableUriPermission");
            uid = Binder.getCallingUid();
        }

        Preconditions.checkFlagsArgument(modeFlags,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        synchronized (mLock) {
            boolean persistChanged = false;

            UriPermission exactPerm = findUriPermissionLocked(uid,
                    new GrantUri(userId, uri, 0));
            UriPermission prefixPerm = findUriPermissionLocked(uid,
                    new GrantUri(userId, uri, FLAG_GRANT_PREFIX_URI_PERMISSION));

            final boolean exactValid = (exactPerm != null)
                    && ((modeFlags & exactPerm.persistableModeFlags) == modeFlags);
            final boolean prefixValid = (prefixPerm != null)
                    && ((modeFlags & prefixPerm.persistableModeFlags) == modeFlags);

            if (!(exactValid || prefixValid)) {
                throw new SecurityException("No persistable permission grants found for UID "
                        + uid + " and Uri " + uri.toSafeString());
            }

            if (exactValid) {
                persistChanged |= exactPerm.takePersistableModes(modeFlags);
            }
            if (prefixValid) {
                persistChanged |= prefixPerm.takePersistableModes(modeFlags);
            }

            persistChanged |= maybePrunePersistedUriGrantsLocked(uid);

            if (persistChanged) {
                schedulePersistUriGrants();
            }
        }
    }

    @Override
    public void clearGrantedUriPermissions(String packageName, int userId) {
        mAmInternal.enforceCallingPermission(
                CLEAR_APP_GRANTED_URI_PERMISSIONS, "clearGrantedUriPermissions");
        synchronized (mLock) {
            removeUriPermissionsForPackageLocked(packageName, userId, true, true);
        }
    }

    /**
     * @param uri This uri must NOT contain an embedded userId.
     * @param toPackage Name of the target package whose uri is being released (if {@code null},
     * uses calling uid)
     * @param userId The userId in which the uri is to be resolved.
     */
    @Override
    public void releasePersistableUriPermission(Uri uri, final int modeFlags,
            @Nullable String toPackage, int userId) {

        final int uid;
        if (toPackage != null) {
            mAmInternal.enforceCallingPermission(FORCE_PERSISTABLE_URI_PERMISSIONS,
                    "releasePersistableUriPermission");
            uid = mPmInternal.getPackageUid(toPackage, 0 /* flags */ , userId);
        } else {
            enforceNotIsolatedCaller("releasePersistableUriPermission");
            uid = Binder.getCallingUid();
        }

        Preconditions.checkFlagsArgument(modeFlags,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        synchronized (mLock) {
            boolean persistChanged = false;

            UriPermission exactPerm = findUriPermissionLocked(uid,
                    new GrantUri(userId, uri, 0));
            UriPermission prefixPerm = findUriPermissionLocked(uid,
                    new GrantUri(userId, uri, FLAG_GRANT_PREFIX_URI_PERMISSION));
            if (exactPerm == null && prefixPerm == null && toPackage == null) {
                throw new SecurityException("No permission grants found for UID " + uid
                        + " and Uri " + uri.toSafeString());
            }

            if (exactPerm != null) {
                persistChanged |= exactPerm.releasePersistableModes(modeFlags);
                removeUriPermissionIfNeededLocked(exactPerm);
            }
            if (prefixPerm != null) {
                persistChanged |= prefixPerm.releasePersistableModes(modeFlags);
                removeUriPermissionIfNeededLocked(prefixPerm);
            }

            if (persistChanged) {
                schedulePersistUriGrants();
            }
        }
    }

    /**
     * Remove any {@link UriPermission} granted <em>from</em> or <em>to</em> the
     * given package.
     *
     * @param packageName Package name to match, or {@code null} to apply to all
     *            packages.
     * @param userHandle User to match, or {@link UserHandle#USER_ALL} to apply
     *            to all users.
     * @param persistable If persistable grants should be removed.
     * @param targetOnly When {@code true}, only remove permissions where the app is the target,
     * not source.
     */
    @GuardedBy("mLock")
    private void removeUriPermissionsForPackageLocked(String packageName, int userHandle,
            boolean persistable, boolean targetOnly) {
        if (userHandle == UserHandle.USER_ALL && packageName == null) {
            throw new IllegalArgumentException("Must narrow by either package or user");
        }

        boolean persistChanged = false;

        int N = mGrantedUriPermissions.size();
        for (int i = 0; i < N; i++) {
            final int targetUid = mGrantedUriPermissions.keyAt(i);
            final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.valueAt(i);

            // Only inspect grants matching user
            if (userHandle == UserHandle.USER_ALL
                    || userHandle == UserHandle.getUserId(targetUid)) {
                for (Iterator<UriPermission> it = perms.values().iterator(); it.hasNext();) {
                    final UriPermission perm = it.next();

                    // Only inspect grants matching package
                    if (packageName == null || (!targetOnly && perm.sourcePkg.equals(packageName))
                            || perm.targetPkg.equals(packageName)) {
                        // Hacky solution as part of fixing a security bug; ignore
                        // grants associated with DownloadManager so we don't have
                        // to immediately launch it to regrant the permissions
                        if (Downloads.Impl.AUTHORITY.equals(perm.uri.uri.getAuthority())
                                && !persistable) continue;

                        persistChanged |= perm.revokeModes(persistable
                                ? ~0 : ~Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, true);

                        // Only remove when no modes remain; any persisted grants
                        // will keep this alive.
                        if (perm.modeFlags == 0) {
                            it.remove();
                        }
                    }
                }

                if (perms.isEmpty()) {
                    mGrantedUriPermissions.remove(targetUid);
                    N--;
                    i--;
                }
            }
        }

        if (persistChanged) {
            schedulePersistUriGrants();
        }
    }

    /** Returns if the ContentProvider has granted a uri to callingUid */
    @GuardedBy("mLock")
    private boolean checkAuthorityGrantsLocked(int callingUid, ProviderInfo cpi, int userId,
            boolean checkUser) {
        final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.get(callingUid);
        if (perms != null) {
            for (int i = perms.size() - 1; i >= 0; i--) {
                GrantUri grantUri = perms.keyAt(i);
                if (grantUri.sourceUserId == userId || !checkUser) {
                    if (matchesProvider(grantUri.uri, cpi)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Returns true if the uri authority is one of the authorities specified in the provider. */
    private boolean matchesProvider(Uri uri, ProviderInfo cpi) {
        String uriAuth = uri.getAuthority();
        String cpiAuth = cpi.authority;
        if (cpiAuth.indexOf(';') == -1) {
            return cpiAuth.equals(uriAuth);
        }
        String[] cpiAuths = cpiAuth.split(";");
        int length = cpiAuths.length;
        for (int i = 0; i < length; i++) {
            if (cpiAuths[i].equals(uriAuth)) return true;
        }
        return false;
    }

    /**
     * Prune any older {@link UriPermission} for the given UID until outstanding
     * persisted grants are below {@link #MAX_PERSISTED_URI_GRANTS}.
     *
     * @return if any mutations occured that require persisting.
     */
    @GuardedBy("mLock")
    private boolean maybePrunePersistedUriGrantsLocked(int uid) {
        final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.get(uid);
        if (perms == null) return false;
        if (perms.size() < MAX_PERSISTED_URI_GRANTS) return false;

        final ArrayList<UriPermission> persisted = Lists.newArrayList();
        for (UriPermission perm : perms.values()) {
            if (perm.persistedModeFlags != 0) {
                persisted.add(perm);
            }
        }

        final int trimCount = persisted.size() - MAX_PERSISTED_URI_GRANTS;
        if (trimCount <= 0) return false;

        Collections.sort(persisted, new UriPermission.PersistedTimeComparator());
        for (int i = 0; i < trimCount; i++) {
            final UriPermission perm = persisted.get(i);

            if (DEBUG) Slog.v(TAG, "Trimming grant created at " + perm.persistedCreateTime);

            perm.releasePersistableModes(~0);
            removeUriPermissionIfNeededLocked(perm);
        }

        return true;
    }

    /** Like checkGrantUriPermission, but takes an Intent. */
    private NeededUriGrants checkGrantUriPermissionFromIntentUnlocked(int callingUid,
            String targetPkg, Intent intent, int mode, NeededUriGrants needed, int targetUserId) {
        if (DEBUG) Slog.v(TAG,
                "Checking URI perm to data=" + (intent != null ? intent.getData() : null)
                        + " clip=" + (intent != null ? intent.getClipData() : null)
                        + " from " + intent + "; flags=0x"
                        + Integer.toHexString(intent != null ? intent.getFlags() : 0));

        if (targetPkg == null) {
            throw new NullPointerException("targetPkg");
        }

        if (intent == null) {
            return null;
        }
        Uri data = intent.getData();
        ClipData clip = intent.getClipData();
        if (data == null && clip == null) {
            return null;
        }
        // Default userId for uris in the intent (if they don't specify it themselves)
        int contentUserHint = intent.getContentUserHint();
        if (contentUserHint == UserHandle.USER_CURRENT) {
            contentUserHint = UserHandle.getUserId(callingUid);
        }
        int targetUid;
        if (needed != null) {
            targetUid = needed.targetUid;
        } else {
            targetUid = mPmInternal.getPackageUid(targetPkg, MATCH_DEBUG_TRIAGED_MISSING,
                    targetUserId);
            if (targetUid < 0) {
                if (DEBUG) Slog.v(TAG, "Can't grant URI permission no uid for: " + targetPkg
                        + " on user " + targetUserId);
                return null;
            }
        }
        if (data != null) {
            GrantUri grantUri = GrantUri.resolve(contentUserHint, data, mode);
            targetUid = checkGrantUriPermissionUnlocked(callingUid, targetPkg, grantUri, mode,
                    targetUid);
            if (targetUid > 0) {
                if (needed == null) {
                    needed = new NeededUriGrants(targetPkg, targetUid, mode);
                }
                needed.uris.add(grantUri);
            }
        }
        if (clip != null) {
            for (int i=0; i<clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) {
                    GrantUri grantUri = GrantUri.resolve(contentUserHint, uri, mode);
                    targetUid = checkGrantUriPermissionUnlocked(callingUid, targetPkg,
                            grantUri, mode, targetUid);
                    if (targetUid > 0) {
                        if (needed == null) {
                            needed = new NeededUriGrants(targetPkg, targetUid, mode);
                        }
                        needed.uris.add(grantUri);
                    }
                } else {
                    Intent clipIntent = clip.getItemAt(i).getIntent();
                    if (clipIntent != null) {
                        NeededUriGrants newNeeded = checkGrantUriPermissionFromIntentUnlocked(
                                callingUid, targetPkg, clipIntent, mode, needed, targetUserId);
                        if (newNeeded != null) {
                            needed = newNeeded;
                        }
                    }
                }
            }
        }

        return needed;
    }

    @GuardedBy("mLock")
    private void readGrantedUriPermissionsLocked() {
        if (DEBUG) Slog.v(TAG, "readGrantedUriPermissions()");

        final long now = System.currentTimeMillis();

        FileInputStream fis = null;
        try {
            fis = mGrantFile.openRead();
            final TypedXmlPullParser in = Xml.resolvePullParser(fis);

            int type;
            while ((type = in.next()) != END_DOCUMENT) {
                final String tag = in.getName();
                if (type == START_TAG) {
                    if (TAG_URI_GRANT.equals(tag)) {
                        final int sourceUserId;
                        final int targetUserId;
                        final int userHandle = in.getAttributeInt(null, ATTR_USER_HANDLE,
                                UserHandle.USER_NULL);
                        if (userHandle != UserHandle.USER_NULL) {
                            // For backwards compatibility.
                            sourceUserId = userHandle;
                            targetUserId = userHandle;
                        } else {
                            sourceUserId = in.getAttributeInt(null, ATTR_SOURCE_USER_ID);
                            targetUserId = in.getAttributeInt(null, ATTR_TARGET_USER_ID);
                        }
                        final String sourcePkg = in.getAttributeValue(null, ATTR_SOURCE_PKG);
                        final String targetPkg = in.getAttributeValue(null, ATTR_TARGET_PKG);
                        final Uri uri = Uri.parse(in.getAttributeValue(null, ATTR_URI));
                        final boolean prefix = in.getAttributeBoolean(null, ATTR_PREFIX, false);
                        final int modeFlags = in.getAttributeInt(null, ATTR_MODE_FLAGS);
                        final long createdTime = in.getAttributeLong(null, ATTR_CREATED_TIME, now);

                        // Validity check that provider still belongs to source package
                        // Both direct boot aware and unaware packages are fine as we
                        // will do filtering at query time to avoid multiple parsing.
                        final ProviderInfo pi = getProviderInfo(uri.getAuthority(), sourceUserId,
                                MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE, SYSTEM_UID);
                        if (pi != null && sourcePkg.equals(pi.packageName)) {
                            int targetUid = mPmInternal.getPackageUid(
                                        targetPkg, MATCH_UNINSTALLED_PACKAGES, targetUserId);
                            if (targetUid != -1) {
                                final GrantUri grantUri = new GrantUri(sourceUserId, uri,
                                        prefix ? Intent.FLAG_GRANT_PREFIX_URI_PERMISSION : 0);
                                final UriPermission perm = findOrCreateUriPermissionLocked(
                                        sourcePkg, targetPkg, targetUid, grantUri);
                                perm.initPersistedModes(modeFlags, createdTime);
                                mPmInternal.grantImplicitAccess(
                                        targetUserId, null /* intent */,
                                        UserHandle.getAppId(targetUid),
                                        pi.applicationInfo.uid,
                                        false /* direct */, true /* retainOnUpdate */);
                            }
                        } else {
                            Slog.w(TAG, "Persisted grant for " + uri + " had source " + sourcePkg
                                    + " but instead found " + pi);
                        }
                    }
                }
            }
        } catch (FileNotFoundException e) {
            // Missing grants is okay
        } catch (IOException e) {
            Slog.wtf(TAG, "Failed reading Uri grants", e);
        } catch (XmlPullParserException e) {
            Slog.wtf(TAG, "Failed reading Uri grants", e);
        } finally {
            IoUtils.closeQuietly(fis);
        }
    }

    @GuardedBy("mLock")
    private UriPermission findOrCreateUriPermissionLocked(String sourcePkg,
            String targetPkg, int targetUid, GrantUri grantUri) {
        ArrayMap<GrantUri, UriPermission> targetUris = mGrantedUriPermissions.get(targetUid);
        if (targetUris == null) {
            targetUris = Maps.newArrayMap();
            mGrantedUriPermissions.put(targetUid, targetUris);
        }

        UriPermission perm = targetUris.get(grantUri);
        if (perm == null) {
            perm = new UriPermission(sourcePkg, targetPkg, targetUid, grantUri);
            targetUris.put(grantUri, perm);
        }

        return perm;
    }

    private void grantUriPermissionUnchecked(int targetUid, String targetPkg, GrantUri grantUri,
            final int modeFlags, UriPermissionOwner owner) {
        if (!Intent.isAccessUriMode(modeFlags)) {
            return;
        }

        // So here we are: the caller has the assumed permission to the uri, and the target doesn't.
        // Let's now give this to the target.

        if (DEBUG) Slog.v(TAG,
                "Granting " + targetPkg + "/" + targetUid + " permission to " + grantUri);

        // Unchecked call, passing the system's uid as the calling uid to the getProviderInfo
        final String authority = grantUri.uri.getAuthority();
        final ProviderInfo pi = getProviderInfo(authority, grantUri.sourceUserId,
                MATCH_DEBUG_TRIAGED_MISSING, SYSTEM_UID);
        if (pi == null) {
            Slog.w(TAG, "No content provider found for grant: " + grantUri.toSafeString());
            return;
        }

        final UriPermission perm;
        synchronized (mLock) {
            perm = findOrCreateUriPermissionLocked(pi.packageName, targetPkg, targetUid, grantUri);
        }
        perm.grantModes(modeFlags, owner);
        mPmInternal.grantImplicitAccess(UserHandle.getUserId(targetUid), null /*intent*/,
                UserHandle.getAppId(targetUid), pi.applicationInfo.uid, false /*direct*/,
                (modeFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0);
    }

    /** Like grantUriPermissionUnchecked, but takes an Intent. */
    private void grantUriPermissionUncheckedFromIntent(NeededUriGrants needed,
            UriPermissionOwner owner) {
        if (needed == null) {
            return;
        }
        final int N = needed.uris.size();
        for (int i = 0; i < N; i++) {
            grantUriPermissionUnchecked(needed.targetUid, needed.targetPkg,
                    needed.uris.valueAt(i), needed.flags, owner);
        }
    }

    private void grantUriPermissionUnlocked(int callingUid, String targetPkg, GrantUri grantUri,
            final int modeFlags, UriPermissionOwner owner, int targetUserId) {
        if (targetPkg == null) {
            throw new NullPointerException("targetPkg");
        }
        int targetUid = mPmInternal.getPackageUid(targetPkg, MATCH_DEBUG_TRIAGED_MISSING,
                targetUserId);

        targetUid = checkGrantUriPermissionUnlocked(callingUid, targetPkg, grantUri, modeFlags,
                targetUid);
        if (targetUid < 0) {
            return;
        }

        grantUriPermissionUnchecked(targetUid, targetPkg, grantUri, modeFlags, owner);
    }

    private void revokeUriPermission(String targetPackage, int callingUid, GrantUri grantUri,
            final int modeFlags) {
        if (DEBUG) Slog.v(TAG, "Revoking all granted permissions to " + grantUri);

        final String authority = grantUri.uri.getAuthority();
        final ProviderInfo pi = getProviderInfo(authority, grantUri.sourceUserId,
                MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE, callingUid);
        if (pi == null) {
            Slog.w(TAG, "No content provider found for permission revoke: "
                    + grantUri.toSafeString());
            return;
        }

        final boolean callerHoldsPermissions = checkHoldingPermissionsUnlocked(pi, grantUri,
                callingUid, modeFlags);
        synchronized (mLock) {
            revokeUriPermissionLocked(targetPackage, callingUid, grantUri, modeFlags,
                    callerHoldsPermissions);
        }
    }

    @GuardedBy("mLock")
    private void revokeUriPermissionLocked(String targetPackage, int callingUid, GrantUri grantUri,
            final int modeFlags, final boolean callerHoldsPermissions) {
        // Does the caller have this permission on the URI?
        if (!callerHoldsPermissions) {
            // If they don't have direct access to the URI, then revoke any
            // ownerless URI permissions that have been granted to them.
            final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.get(callingUid);
            if (perms != null) {
                boolean persistChanged = false;
                for (int i = perms.size()-1; i >= 0; i--) {
                    final UriPermission perm = perms.valueAt(i);
                    if (targetPackage != null && !targetPackage.equals(perm.targetPkg)) {
                        continue;
                    }
                    if (perm.uri.sourceUserId == grantUri.sourceUserId
                            && perm.uri.uri.isPathPrefixMatch(grantUri.uri)) {
                        if (DEBUG) Slog.v(TAG, "Revoking non-owned "
                                + perm.targetUid + " permission to " + perm.uri);
                        persistChanged |= perm.revokeModes(
                                modeFlags | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, false);
                        if (perm.modeFlags == 0) {
                            perms.removeAt(i);
                        }
                    }
                }
                if (perms.isEmpty()) {
                    mGrantedUriPermissions.remove(callingUid);
                }
                if (persistChanged) {
                    schedulePersistUriGrants();
                }
            }
            return;
        }

        boolean persistChanged = false;

        // Go through all of the permissions and remove any that match.
        for (int i = mGrantedUriPermissions.size()-1; i >= 0; i--) {
            final int targetUid = mGrantedUriPermissions.keyAt(i);
            final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.valueAt(i);

            for (int j = perms.size()-1; j >= 0; j--) {
                final UriPermission perm = perms.valueAt(j);
                if (targetPackage != null && !targetPackage.equals(perm.targetPkg)) {
                    continue;
                }
                if (perm.uri.sourceUserId == grantUri.sourceUserId
                        && perm.uri.uri.isPathPrefixMatch(grantUri.uri)) {
                    if (DEBUG) Slog.v(TAG,
                            "Revoking " + perm.targetUid + " permission to " + perm.uri);
                    persistChanged |= perm.revokeModes(
                            modeFlags | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                            targetPackage == null);
                    if (perm.modeFlags == 0) {
                        perms.removeAt(j);
                    }
                }
            }

            if (perms.isEmpty()) {
                mGrantedUriPermissions.removeAt(i);
            }
        }

        if (persistChanged) {
            schedulePersistUriGrants();
        }
    }

    /**
     * Determine if UID is holding permissions required to access {@link Uri} in
     * the given {@link ProviderInfo}. Final permission checking is always done
     * in {@link ContentProvider}.
     */
    private boolean checkHoldingPermissionsUnlocked(
            ProviderInfo pi, GrantUri grantUri, int uid, final int modeFlags) {
        if (DEBUG) Slog.v(TAG, "checkHoldingPermissions: uri=" + grantUri + " uid=" + uid);
        if (UserHandle.getUserId(uid) != grantUri.sourceUserId) {
            if (checkComponentPermission(INTERACT_ACROSS_USERS, uid, -1, true)
                    != PERMISSION_GRANTED) {
                return false;
            }
        }
        return checkHoldingPermissionsInternalUnlocked(pi, grantUri, uid, modeFlags, true);
    }

    private boolean checkHoldingPermissionsInternalUnlocked(ProviderInfo pi,
            GrantUri grantUri, int uid, final int modeFlags, boolean considerUidPermissions) {
        // We must never hold our local mLock in this method, since we may need
        // to call into ActivityManager for dynamic permission checks
        if (Thread.holdsLock(mLock)) {
            throw new IllegalStateException("Must never hold local mLock");
        }

        if (pi.applicationInfo.uid == uid) {
            return true;
        } else if (!pi.exported) {
            return false;
        }

        boolean readMet = (modeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0;
        boolean writeMet = (modeFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0;

        // check if target holds top-level <provider> permissions
        if (!readMet && pi.readPermission != null && considerUidPermissions
                && (checkUidPermission(pi.readPermission, uid) == PERMISSION_GRANTED)) {
            readMet = true;
        }
        if (!writeMet && pi.writePermission != null && considerUidPermissions
                && (checkUidPermission(pi.writePermission, uid) == PERMISSION_GRANTED)) {
            writeMet = true;
        }

        // track if unprotected read/write is allowed; any denied
        // <path-permission> below removes this ability
        boolean allowDefaultRead = pi.readPermission == null;
        boolean allowDefaultWrite = pi.writePermission == null;

        // check if target holds any <path-permission> that match uri
        final PathPermission[] pps = pi.pathPermissions;
        if (pps != null) {
            final String path = grantUri.uri.getPath();
            int i = pps.length;
            while (i > 0 && (!readMet || !writeMet)) {
                i--;
                PathPermission pp = pps[i];
                if (pp.match(path)) {
                    if (!readMet) {
                        final String pprperm = pp.getReadPermission();
                        if (DEBUG) Slog.v(TAG,
                                "Checking read perm for " + pprperm + " for " + pp.getPath()
                                        + ": match=" + pp.match(path)
                                        + " check=" + checkUidPermission(pprperm, uid));
                        if (pprperm != null) {
                            if (considerUidPermissions && checkUidPermission(pprperm, uid)
                                    == PERMISSION_GRANTED) {
                                readMet = true;
                            } else {
                                allowDefaultRead = false;
                            }
                        }
                    }
                    if (!writeMet) {
                        final String ppwperm = pp.getWritePermission();
                        if (DEBUG) Slog.v(TAG,
                                "Checking write perm " + ppwperm + " for " + pp.getPath()
                                        + ": match=" + pp.match(path)
                                        + " check=" + checkUidPermission(ppwperm, uid));
                        if (ppwperm != null) {
                            if (considerUidPermissions && checkUidPermission(ppwperm, uid)
                                    == PERMISSION_GRANTED) {
                                writeMet = true;
                            } else {
                                allowDefaultWrite = false;
                            }
                        }
                    }
                }
            }
        }

        // grant unprotected <provider> read/write, if not blocked by
        // <path-permission> above
        if (allowDefaultRead) readMet = true;
        if (allowDefaultWrite) writeMet = true;

        // If this provider says that grants are always required, we need to
        // consult it directly to determine if the UID has permission
        final boolean forceMet;
        if (ENABLE_DYNAMIC_PERMISSIONS && pi.forceUriPermissions) {
            final int providerUserId = UserHandle.getUserId(pi.applicationInfo.uid);
            final int clientUserId = UserHandle.getUserId(uid);
            if (providerUserId == clientUserId) {
                forceMet = (mAmInternal.checkContentProviderUriPermission(grantUri.uri,
                        providerUserId, uid, modeFlags) == PackageManager.PERMISSION_GRANTED);
            } else {
                // The provider can't track cross-user permissions, so we have
                // to assume they're always denied
                forceMet = false;
            }
        } else {
            forceMet = true;
        }

        return readMet && writeMet && forceMet;
    }

    @GuardedBy("mLock")
    private void removeUriPermissionIfNeededLocked(UriPermission perm) {
        if (perm.modeFlags != 0) {
            return;
        }
        final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.get(
                perm.targetUid);
        if (perms == null) {
            return;
        }
        if (DEBUG) Slog.v(TAG, "Removing " + perm.targetUid + " permission to " + perm.uri);

        perms.remove(perm.uri);
        if (perms.isEmpty()) {
            mGrantedUriPermissions.remove(perm.targetUid);
        }
    }

    @GuardedBy("mLock")
    private UriPermission findUriPermissionLocked(int targetUid, GrantUri grantUri) {
        final ArrayMap<GrantUri, UriPermission> targetUris = mGrantedUriPermissions.get(targetUid);
        if (targetUris != null) {
            return targetUris.get(grantUri);
        }
        return null;
    }

    private void schedulePersistUriGrants() {
        if (!mH.hasMessages(PERSIST_URI_GRANTS_MSG)) {
            mH.sendMessageDelayed(mH.obtainMessage(PERSIST_URI_GRANTS_MSG),
                    10 * DateUtils.SECOND_IN_MILLIS);
        }
    }

    private void enforceNotIsolatedCaller(String caller) {
        if (UserHandle.isIsolated(Binder.getCallingUid())) {
            throw new SecurityException("Isolated process not allowed to call " + caller);
        }
    }

    private ProviderInfo getProviderInfo(String authority, int userHandle, int pmFlags,
            int callingUid) {
        return mPmInternal.resolveContentProvider(authority,
                PackageManager.GET_URI_PERMISSION_PATTERNS | pmFlags, userHandle, callingUid);
    }

    /**
     * Check if the targetPkg can be granted permission to access uri by
     * the callingUid using the given modeFlags.  Throws a security exception
     * if callingUid is not allowed to do this.  Returns the uid of the target
     * if the URI permission grant should be performed; returns -1 if it is not
     * needed (for example targetPkg already has permission to access the URI).
     * If you already know the uid of the target, you can supply it in
     * lastTargetUid else set that to -1.
     */
    private int checkGrantUriPermissionUnlocked(int callingUid, String targetPkg, GrantUri grantUri,
            int modeFlags, int lastTargetUid) {
        if (!isContentUriWithAccessModeFlags(grantUri, modeFlags,
                /* logAction */ "grant URI permission")) {
            return -1;
        }

        if (targetPkg != null) {
            if (DEBUG) Slog.v(TAG, "Checking grant " + targetPkg + " permission to " + grantUri);
        }

        // Bail early if system is trying to hand out permissions directly; it
        // must always grant permissions on behalf of someone explicit.
        final int callingAppId = UserHandle.getAppId(callingUid);
        if ((callingAppId == SYSTEM_UID) || (callingAppId == ROOT_UID)) {
            if ("com.android.settings.files".equals(grantUri.uri.getAuthority())
                    || "com.android.settings.module_licenses".equals(grantUri.uri.getAuthority())) {
                // Exempted authority for
                // 1. cropping user photos and sharing a generated license html
                //    file in Settings app
                // 2. sharing a generated license html file in TvSettings app
                // 3. Sharing module license files from Settings app
            } else {
                Slog.w(TAG, "For security reasons, the system cannot issue a Uri permission"
                        + " grant to " + grantUri + "; use startActivityAsCaller() instead");
                return -1;
            }
        }

        final String authority = grantUri.uri.getAuthority();
        final ProviderInfo pi = getProviderInfo(authority, grantUri.sourceUserId,
                MATCH_DIRECT_BOOT_AUTO, callingUid);
        if (pi == null) {
            Slog.w(TAG, "No content provider found for permission check: " +
                    grantUri.uri.toSafeString());
            return -1;
        }

        int targetUid = lastTargetUid;
        if (targetUid < 0 && targetPkg != null) {
            targetUid = mPmInternal.getPackageUid(targetPkg, MATCH_DEBUG_TRIAGED_MISSING,
                    UserHandle.getUserId(callingUid));
            if (targetUid < 0) {
                if (DEBUG) Slog.v(TAG, "Can't grant URI permission no uid for: " + targetPkg);
                return -1;
            }
        }

        boolean targetHoldsPermission = false;
        if (targetUid >= 0) {
            // First...  does the target actually need this permission?
            if (checkHoldingPermissionsUnlocked(pi, grantUri, targetUid, modeFlags)) {
                // No need to grant the target this permission.
                if (DEBUG) Slog.v(TAG,
                        "Target " + targetPkg + " already has full permission to " + grantUri);
                targetHoldsPermission = true;
            }
        } else {
            // First...  there is no target package, so can anyone access it?
            boolean allowed = pi.exported;
            if ((modeFlags&Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                if (pi.readPermission != null) {
                    allowed = false;
                }
            }
            if ((modeFlags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                if (pi.writePermission != null) {
                    allowed = false;
                }
            }
            if (pi.pathPermissions != null) {
                final int N = pi.pathPermissions.length;
                for (int i=0; i<N; i++) {
                    if (pi.pathPermissions[i] != null
                            && pi.pathPermissions[i].match(grantUri.uri.getPath())) {
                        if ((modeFlags&Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                            if (pi.pathPermissions[i].getReadPermission() != null) {
                                allowed = false;
                            }
                        }
                        if ((modeFlags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                            if (pi.pathPermissions[i].getWritePermission() != null) {
                                allowed = false;
                            }
                        }
                        break;
                    }
                }
            }
            if (allowed) {
                targetHoldsPermission = true;
            }
        }

        if (pi.forceUriPermissions) {
            // When provider requires dynamic permission checks, the only
            // way to be safe is to issue permission grants for each item by
            // assuming no generic access
            targetHoldsPermission = false;
        }

        final boolean basicGrant = (modeFlags
                & (FLAG_GRANT_PERSISTABLE_URI_PERMISSION | FLAG_GRANT_PREFIX_URI_PERMISSION)) == 0;
        if (basicGrant && targetHoldsPermission) {
            // When caller holds permission, and this is a simple permission
            // grant, we can skip generating any bookkeeping; when any advanced
            // features have been requested, we proceed below to make sure the
            // provider supports granting permissions
            mPmInternal.grantImplicitAccess(
                    UserHandle.getUserId(targetUid), null,
                    UserHandle.getAppId(targetUid), pi.applicationInfo.uid, false /*direct*/);
            return -1;
        }

        /* There is a special cross user grant if:
         * - The target is on another user.
         * - Apps on the current user can access the uri without any uid permissions.
         * In this case, we grant a uri permission, even if the ContentProvider does not normally
         * grant uri permissions.
         */
        boolean specialCrossUserGrant = targetUid >= 0
                && UserHandle.getUserId(targetUid) != grantUri.sourceUserId
                && checkHoldingPermissionsInternalUnlocked(pi, grantUri, callingUid,
                modeFlags, false /*without considering the uid permissions*/);

        // Second...  is the provider allowing granting of URI permissions?
        boolean grantAllowed = pi.grantUriPermissions;
        if (!ArrayUtils.isEmpty(pi.uriPermissionPatterns)) {
            final int N = pi.uriPermissionPatterns.length;
            grantAllowed = false;
            for (int i = 0; i < N; i++) {
                if (pi.uriPermissionPatterns[i] != null
                        && pi.uriPermissionPatterns[i].match(grantUri.uri.getPath())) {
                    grantAllowed = true;
                    break;
                }
            }
        }
        if (!grantAllowed) {
            if (specialCrossUserGrant) {
                // We're only okay issuing basic grant access across user
                // boundaries; advanced flags are blocked here
                if (!basicGrant) {
                    throw new SecurityException("Provider " + pi.packageName
                            + "/" + pi.name
                            + " does not allow granting of advanced Uri permissions (uri "
                            + grantUri + ")");
                }
            } else {
                throw new SecurityException("Provider " + pi.packageName
                        + "/" + pi.name
                        + " does not allow granting of Uri permissions (uri "
                        + grantUri + ")");
            }
        }

        // Third...  does the caller itself have permission to access this uri?
        if (!checkHoldingPermissionsUnlocked(pi, grantUri, callingUid, modeFlags)) {
            // Require they hold a strong enough Uri permission
            final boolean res;
            synchronized (mLock) {
                res = checkUriPermissionLocked(grantUri, callingUid, modeFlags);
            }
            if (!res) {
                if (android.Manifest.permission.MANAGE_DOCUMENTS.equals(pi.readPermission)) {
                    throw new SecurityException(
                            "UID " + callingUid + " does not have permission to " + grantUri
                                    + "; you could obtain access using ACTION_OPEN_DOCUMENT "
                                    + "or related APIs");
                } else {
                    throw new SecurityException(
                            "UID " + callingUid + " does not have permission to " + grantUri);
                }
            }
        }

        return targetUid;
    }

    private boolean isContentUriWithAccessModeFlags(GrantUri grantUri, int modeFlags,
            String logAction) {
        if (!Intent.isAccessUriMode(modeFlags)) {
            if (DEBUG) Slog.v(TAG, "Mode flags are not access URI mode flags: " + modeFlags);
            return false;
        }

        if (!ContentResolver.SCHEME_CONTENT.equals(grantUri.uri.getScheme())) {
            if (DEBUG) {
                Slog.v(TAG, "Can't " + logAction + " on non-content URI: " + grantUri);
            }
            return false;
        }

        return true;
    }

    /** Check if the uid has permission to the content URI in grantUri. */
    private boolean checkContentUriPermissionFullUnlocked(GrantUri grantUri, int uid,
            int modeFlags) {
        if (uid < 0) {
            throw new IllegalArgumentException("Uid must be positive for the content URI "
                    + "permission check of " + grantUri.uri.toSafeString());
        }

        if (!isContentUriWithAccessModeFlags(grantUri, modeFlags,
                /* logAction */ "check content URI permission")) {
            throw new IllegalArgumentException("The URI must be a content URI and the mode "
                    + "flags must be at least read and/or write for the content URI permission "
                    + "check of " + grantUri.uri.toSafeString());
        }

        final int appId = UserHandle.getAppId(uid);
        if ((appId == SYSTEM_UID) || (appId == ROOT_UID)) {
            return true;
        }

        // Retrieve the URI's content provider
        final String authority = grantUri.uri.getAuthority();
        ProviderInfo pi = getProviderInfo(authority, grantUri.sourceUserId, MATCH_DIRECT_BOOT_AUTO,
                uid);

        if (pi == null) {
            Slog.w(TAG, "No content provider found for content URI permission check: "
                    + grantUri.uri.toSafeString());
            return false;
        }

        // Check if it has general permission to the URI's content provider
        if (checkHoldingPermissionsUnlocked(pi, grantUri, uid, modeFlags)) {
            return true;
        }

        // Check if it has explicitly granted permissions to the URI
        synchronized (mLock) {
            return checkUriPermissionLocked(grantUri, uid, modeFlags);
        }
    }

    /**
     * @param userId The userId in which the uri is to be resolved.
     */
    private int checkGrantUriPermissionUnlocked(int callingUid, String targetPkg, Uri uri,
            int modeFlags, int userId) {
        return checkGrantUriPermissionUnlocked(callingUid, targetPkg,
                new GrantUri(userId, uri, modeFlags), modeFlags, -1);
    }

    @GuardedBy("mLock")
    private boolean checkUriPermissionLocked(GrantUri grantUri, int uid, final int modeFlags) {
        final boolean persistable = (modeFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0;
        final int minStrength = persistable ? UriPermission.STRENGTH_PERSISTABLE
                : UriPermission.STRENGTH_OWNED;

        // Root gets to do everything.
        if (uid == 0) {
            return true;
        }

        final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.get(uid);
        if (perms == null) return false;

        // First look for exact match
        final UriPermission exactPerm = perms.get(grantUri);
        if (exactPerm != null && exactPerm.getStrength(modeFlags) >= minStrength) {
            return true;
        }

        // No exact match, look for prefixes
        final int N = perms.size();
        for (int i = 0; i < N; i++) {
            final UriPermission perm = perms.valueAt(i);
            if (perm.uri.prefix && grantUri.uri.isPathPrefixMatch(perm.uri.uri)
                    && perm.getStrength(modeFlags) >= minStrength) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if the targetPkg can be granted permission to access uri by
     * the callingUid using the given modeFlags. See {@link #checkGrantUriPermissionUnlocked}.
     *
     * @param callingUid The uid of the grantor app that has permissions to the uri.
     * @param targetPkg The package name of the granted app that needs permissions to the uri.
     * @param uri The uri for which permissions should be granted.
     * @param modeFlags The modes to grant. See {@link Intent#FLAG_GRANT_READ_URI_PERMISSION}, etc.
     * @param userId The userId in which the uri is to be resolved.
     * @return uid of the target or -1 if permission grant not required. Returns -1 if the caller
     *  does not hold INTERACT_ACROSS_USERS_FULL
     * @throws SecurityException if the grant is not allowed.
     */
    @Override
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    public int checkGrantUriPermission_ignoreNonSystem(int callingUid, String targetPkg, Uri uri,
            int modeFlags, int userId) {
        if (!isCallerIsSystemOrPrivileged()) {
            return Process.INVALID_UID;
        }
        final long origId = Binder.clearCallingIdentity();
        try {
            return checkGrantUriPermissionUnlocked(callingUid, targetPkg, uri, modeFlags,
                        userId);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private boolean isCallerIsSystemOrPrivileged() {
        final int uid = Binder.getCallingUid();
        if (uid == Process.SYSTEM_UID || uid == Process.ROOT_UID) {
            return true;
        }
        return checkComponentPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    uid, /* owningUid = */-1, /* exported = */ true)
                    == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public ArrayList<UriPermission> providePersistentUriGrants() {
        final ArrayList<UriPermission> result = new ArrayList<>();

        synchronized (mLock) {
            final int size = mGrantedUriPermissions.size();
            for (int i = 0; i < size; i++) {
                final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.valueAt(i);

                final int permissionsForPackageSize = perms.size();
                for (int j = 0; j < permissionsForPackageSize; j++) {
                    final UriPermission permission = perms.valueAt(j);

                    if (permission.persistedModeFlags != 0) {
                        result.add(permission);
                    }
                }
            }
        }

        return result;
    }

    private void writeGrantedUriPermissions() {
        if (DEBUG) Slog.v(TAG, "writeGrantedUriPermissions()");

        final long startTime = SystemClock.uptimeMillis();

        int persistentUriPermissionsCount = 0;

        // Snapshot permissions so we can persist without lock
        ArrayList<UriPermission.Snapshot> persist = Lists.newArrayList();
        synchronized (mLock) {
            final int size = mGrantedUriPermissions.size();
            for (int i = 0; i < size; i++) {
                final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.valueAt(i);

                final int permissionsForPackageSize = perms.size();
                for (int j = 0; j < permissionsForPackageSize; j++) {
                    final UriPermission permission = perms.valueAt(j);

                    if (permission.persistedModeFlags != 0) {
                        persistentUriPermissionsCount++;
                        persist.add(permission.snapshot());
                    }
                }
            }
        }

        FileOutputStream fos = null;
        try {
            fos = mGrantFile.startWrite(startTime);

            TypedXmlSerializer out = Xml.resolveSerializer(fos);
            out.startDocument(null, true);
            out.startTag(null, TAG_URI_GRANTS);
            for (UriPermission.Snapshot perm : persist) {
                out.startTag(null, TAG_URI_GRANT);
                out.attributeInt(null, ATTR_SOURCE_USER_ID, perm.uri.sourceUserId);
                out.attributeInt(null, ATTR_TARGET_USER_ID, perm.targetUserId);
                out.attributeInterned(null, ATTR_SOURCE_PKG, perm.sourcePkg);
                out.attributeInterned(null, ATTR_TARGET_PKG, perm.targetPkg);
                out.attribute(null, ATTR_URI, String.valueOf(perm.uri.uri));
                writeBooleanAttribute(out, ATTR_PREFIX, perm.uri.prefix);
                out.attributeInt(null, ATTR_MODE_FLAGS, perm.persistedModeFlags);
                out.attributeLong(null, ATTR_CREATED_TIME, perm.persistedCreateTime);
                out.endTag(null, TAG_URI_GRANT);
            }
            out.endTag(null, TAG_URI_GRANTS);
            out.endDocument();

            mGrantFile.finishWrite(fos);
        } catch (IOException e) {
            if (fos != null) {
                mGrantFile.failWrite(fos);
            }
        }

        mMetricsHelper.reportPersistentUriFlushed(persistentUriPermissionsCount);
    }

    final class H extends Handler {
        static final int PERSIST_URI_GRANTS_MSG = 1;

        public H(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case PERSIST_URI_GRANTS_MSG: {
                    writeGrantedUriPermissions();
                    break;
                }
            }
        }
    }

    private final class LocalService implements UriGrantsManagerInternal {
        @Override
        public void removeUriPermissionIfNeeded(UriPermission perm) {
            synchronized (mLock) {
                UriGrantsManagerService.this.removeUriPermissionIfNeededLocked(perm);
            }
        }

        @Override
        public void revokeUriPermission(String targetPackage, int callingUid, GrantUri grantUri,
                int modeFlags) {
            UriGrantsManagerService.this.revokeUriPermission(
                    targetPackage, callingUid, grantUri, modeFlags);
        }

        @Override
        public boolean checkUriPermission(GrantUri grantUri, int uid, int modeFlags,
                boolean isFullAccessForContentUri) {
            if (isFullAccessForContentUri) {
                return UriGrantsManagerService.this.checkContentUriPermissionFullUnlocked(grantUri,
                        uid, modeFlags);
            }
            synchronized (mLock) {
                return UriGrantsManagerService.this.checkUriPermissionLocked(grantUri, uid,
                        modeFlags);
            }
        }

        @Override
        public int checkGrantUriPermission(int callingUid, String targetPkg, Uri uri, int modeFlags,
                int userId) {
            enforceNotIsolatedCaller("checkGrantUriPermission");
            return UriGrantsManagerService.this.checkGrantUriPermissionUnlocked(
                    callingUid, targetPkg, uri, modeFlags, userId);
        }

        @Override
        public NeededUriGrants checkGrantUriPermissionFromIntent(Intent intent, int callingUid,
                String targetPkg, int targetUserId) {
            final int mode = (intent != null) ? intent.getFlags() : 0;
            return UriGrantsManagerService.this.checkGrantUriPermissionFromIntentUnlocked(
                    callingUid, targetPkg, intent, mode, null, targetUserId);
        }

        @Override
        public void grantUriPermissionUncheckedFromIntent(NeededUriGrants needed,
                UriPermissionOwner owner) {
            UriGrantsManagerService.this.grantUriPermissionUncheckedFromIntent(needed, owner);
        }

        @Override
        public void onSystemReady() {
            synchronized (mLock) {
                UriGrantsManagerService.this.readGrantedUriPermissionsLocked();
            }
        }

        @Override
        public IBinder newUriPermissionOwner(String name) {
            enforceNotIsolatedCaller("newUriPermissionOwner");
            UriPermissionOwner owner = new UriPermissionOwner(this, name);
            return owner.getExternalToken();
        }

        @Override
        public void removeUriPermissionsForPackage(String packageName, int userHandle,
                boolean persistable, boolean targetOnly) {
            synchronized (mLock) {
                UriGrantsManagerService.this.removeUriPermissionsForPackageLocked(
                        packageName, userHandle, persistable, targetOnly);
            }
        }

        @Override
        public void revokeUriPermissionFromOwner(IBinder token, Uri uri, int mode, int userId) {
            revokeUriPermissionFromOwner(token, uri, mode, userId, null, UserHandle.USER_ALL);
        }

        @Override
        public void revokeUriPermissionFromOwner(IBinder token, Uri uri, int mode, int userId,
                String targetPkg, int targetUserId) {
            final UriPermissionOwner owner = UriPermissionOwner.fromExternalToken(token);
            if (owner == null) {
                throw new IllegalArgumentException("Unknown owner: " + token);
            }
            GrantUri grantUri = uri == null ? null : new GrantUri(userId, uri, mode);
            owner.removeUriPermission(grantUri, mode, targetPkg, targetUserId);
        }

        @Override
        public boolean checkAuthorityGrants(int callingUid, ProviderInfo cpi, int userId,
                boolean checkUser) {
            synchronized (mLock) {
                return UriGrantsManagerService.this.checkAuthorityGrantsLocked(
                        callingUid, cpi, userId, checkUser);
            }
        }

        @Override
        public void dump(PrintWriter pw, boolean dumpAll, String dumpPackage) {
            synchronized (mLock) {
                boolean needSep = false;
                boolean printedAnything = false;
                if (mGrantedUriPermissions.size() > 0) {
                    boolean printed = false;
                    int dumpUid = -2;
                    if (dumpPackage != null) {
                        dumpUid = mPmInternal.getPackageUid(dumpPackage,
                                MATCH_ANY_USER, 0 /* userId */);
                    }
                    for (int i = 0; i < mGrantedUriPermissions.size(); i++) {
                        int uid = mGrantedUriPermissions.keyAt(i);
                        if (dumpUid >= -1 && UserHandle.getAppId(uid) != dumpUid) {
                            continue;
                        }
                        final ArrayMap<GrantUri, UriPermission> perms =
                                mGrantedUriPermissions.valueAt(i);
                        if (!printed) {
                            if (needSep) pw.println();
                            needSep = true;
                            pw.println("  Granted Uri Permissions:");
                            printed = true;
                            printedAnything = true;
                        }
                        pw.print("  * UID "); pw.print(uid); pw.println(" holds:");
                        for (UriPermission perm : perms.values()) {
                            pw.print("    "); pw.println(perm);
                            if (dumpAll) {
                                perm.dump(pw, "      ");
                            }
                        }
                    }
                }

                if (!printedAnything) {
                    pw.println("  (nothing)");
                }
            }
        }
    }
}
