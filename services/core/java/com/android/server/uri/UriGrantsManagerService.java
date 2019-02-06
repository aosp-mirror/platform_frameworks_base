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
import static android.content.pm.PackageManager.MATCH_ANY_USER;
import static android.content.pm.PackageManager.MATCH_DEBUG_TRIAGED_MISSING;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Process.ROOT_UID;
import static android.os.Process.SYSTEM_UID;
import static android.os.Process.myUid;

import static com.android.internal.util.XmlUtils.readBooleanAttribute;
import static com.android.internal.util.XmlUtils.readIntAttribute;
import static com.android.internal.util.XmlUtils.readLongAttribute;
import static com.android.internal.util.XmlUtils.writeBooleanAttribute;
import static com.android.internal.util.XmlUtils.writeIntAttribute;
import static com.android.internal.util.XmlUtils.writeLongAttribute;
import static com.android.server.uri.UriGrantsManagerService.H.PERSIST_URI_GRANTS_MSG;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.annotation.Nullable;
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
import android.content.pm.IPackageManager;
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

import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;

import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Manages uri grants. */
public class UriGrantsManagerService extends IUriGrantsManager.Stub {
    private static final boolean DEBUG = false;
    private static final String TAG = "UriGrantsManagerService";
    // Maximum number of persisted Uri grants a package is allowed
    private static final int MAX_PERSISTED_URI_GRANTS = 128;

    private final Object mLock = new Object();
    private final Context mContext;
    private final H mH;
    ActivityManagerInternal mAmInternal;
    PackageManagerInternal mPmInternal;

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
    private final SparseArray<ArrayMap<GrantUri, UriPermission>>
            mGrantedUriPermissions = new SparseArray<>();

    private UriGrantsManagerService(Context context) {
        mContext = context;
        mH = new H(IoThread.get().getLooper());
        final File systemDir = SystemServiceManager.ensureSystemDir();
        mGrantFile = new AtomicFile(new File(systemDir, "urigrants.xml"), "uri-grants");
    }

    private void start() {
        LocalServices.addService(UriGrantsManagerInternal.class, new LocalService());
    }

    void onActivityManagerInternalAdded() {
        mAmInternal = LocalServices.getService(ActivityManagerInternal.class);
    }

    public static final class Lifecycle extends SystemService {
        private final UriGrantsManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            mService = new UriGrantsManagerService(context);
        }

        @Override
        public void onStart() {
            publishBinderService(Context.URI_GRANTS_SERVICE, mService);
            mService.start();
        }

        public UriGrantsManagerService getService() {
            return mService;
        }
    }

    /**
     * @param uri This uri must NOT contain an embedded userId.
     * @param sourceUserId The userId in which the uri is to be resolved.
     * @param targetUserId The userId of the app that receives the grant.
     */
    @Override
    public void grantUriPermissionFromOwner(IBinder token, int fromUid, String targetPkg, Uri uri,
            final int modeFlags, int sourceUserId, int targetUserId) {
        targetUserId = mAmInternal.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), targetUserId, false, ALLOW_FULL_ONLY,
                "grantUriPermissionFromOwner", null);
        synchronized(mLock) {
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

            grantUriPermission(fromUid, targetPkg, new GrantUri(sourceUserId, uri, false),
                    modeFlags, owner, targetUserId);
        }
    }

    @Override
    public ParceledListSlice<android.content.UriPermission> getPersistedUriPermissions(
            String packageName, boolean incoming) {
        enforceNotIsolatedCaller("getPersistedUriPermissions");
        Preconditions.checkNotNull(packageName, "packageName");

        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        final IPackageManager pm = AppGlobals.getPackageManager();
        try {
            final int packageUid = pm.getPackageUid(packageName,
                    MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE, callingUserId);
            if (packageUid != callingUid) {
                throw new SecurityException(
                        "Package " + packageName + " does not belong to calling UID " + callingUid);
            }
        } catch (RemoteException e) {
            throw new SecurityException("Failed to verify package name ownership");
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
                        if (packageName.equals(perm.targetPkg) && perm.persistedModeFlags != 0) {
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
                        if (packageName.equals(perm.sourcePkg) && perm.persistedModeFlags != 0) {
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
            uid = getPmInternal().getPackageUid(toPackage, 0, userId);
        } else {
            enforceNotIsolatedCaller("takePersistableUriPermission");
            uid = Binder.getCallingUid();
        }

        Preconditions.checkFlagsArgument(modeFlags,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        synchronized (mLock) {
            boolean persistChanged = false;
            GrantUri grantUri = new GrantUri(userId, uri, false);

            UriPermission exactPerm = findUriPermissionLocked(uid, grantUri);
            UriPermission prefixPerm = findUriPermissionLocked(uid,
                    new GrantUri(userId, uri, true));

            final boolean exactValid = (exactPerm != null)
                    && ((modeFlags & exactPerm.persistableModeFlags) == modeFlags);
            final boolean prefixValid = (prefixPerm != null)
                    && ((modeFlags & prefixPerm.persistableModeFlags) == modeFlags);

            if (!(exactValid || prefixValid)) {
                throw new SecurityException("No persistable permission grants found for UID "
                        + uid + " and Uri " + grantUri.toSafeString());
            }

            if (exactValid) {
                persistChanged |= exactPerm.takePersistableModes(modeFlags);
            }
            if (prefixValid) {
                persistChanged |= prefixPerm.takePersistableModes(modeFlags);
            }

            persistChanged |= maybePrunePersistedUriGrants(uid);

            if (persistChanged) {
                schedulePersistUriGrants();
            }
        }
    }

    @Override
    public void clearGrantedUriPermissions(String packageName, int userId) {
        mAmInternal.enforceCallingPermission(
                CLEAR_APP_GRANTED_URI_PERMISSIONS, "clearGrantedUriPermissions");
        synchronized(mLock) {
            removeUriPermissionsForPackage(packageName, userId, true, true);
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
            uid = getPmInternal().getPackageUid(toPackage, 0, userId);
        } else {
            enforceNotIsolatedCaller("releasePersistableUriPermission");
            uid = Binder.getCallingUid();
        }

        Preconditions.checkFlagsArgument(modeFlags,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        synchronized (mLock) {
            boolean persistChanged = false;

            UriPermission exactPerm = findUriPermissionLocked(uid,
                    new GrantUri(userId, uri, false));
            UriPermission prefixPerm = findUriPermissionLocked(uid,
                    new GrantUri(userId, uri, true));
            if (exactPerm == null && prefixPerm == null && toPackage == null) {
                throw new SecurityException("No permission grants found for UID " + uid
                        + " and Uri " + uri.toSafeString());
            }

            if (exactPerm != null) {
                persistChanged |= exactPerm.releasePersistableModes(modeFlags);
                removeUriPermissionIfNeeded(exactPerm);
            }
            if (prefixPerm != null) {
                persistChanged |= prefixPerm.releasePersistableModes(modeFlags);
                removeUriPermissionIfNeeded(prefixPerm);
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
    void removeUriPermissionsForPackage(
            String packageName, int userHandle, boolean persistable, boolean targetOnly) {
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
    boolean checkAuthorityGrants(int callingUid, ProviderInfo cpi, int userId, boolean checkUser) {
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
    private boolean maybePrunePersistedUriGrants(int uid) {
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
            removeUriPermissionIfNeeded(perm);
        }

        return true;
    }

    /** Like checkGrantUriPermission, but takes an Intent. */
    NeededUriGrants checkGrantUriPermissionFromIntent(int callingUid,
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
        final IPackageManager pm = AppGlobals.getPackageManager();
        int targetUid;
        if (needed != null) {
            targetUid = needed.targetUid;
        } else {
            try {
                targetUid = pm.getPackageUid(targetPkg, MATCH_DEBUG_TRIAGED_MISSING, targetUserId);
            } catch (RemoteException ex) {
                return null;
            }
            if (targetUid < 0) {
                if (DEBUG) Slog.v(TAG, "Can't grant URI permission no uid for: " + targetPkg
                        + " on user " + targetUserId);
                return null;
            }
        }
        if (data != null) {
            GrantUri grantUri = GrantUri.resolve(contentUserHint, data);
            targetUid = checkGrantUriPermission(callingUid, targetPkg, grantUri, mode, targetUid);
            if (targetUid > 0) {
                if (needed == null) {
                    needed = new NeededUriGrants(targetPkg, targetUid, mode);
                }
                needed.add(grantUri);
            }
        }
        if (clip != null) {
            for (int i=0; i<clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) {
                    GrantUri grantUri = GrantUri.resolve(contentUserHint, uri);
                    targetUid = checkGrantUriPermission(callingUid, targetPkg,
                            grantUri, mode, targetUid);
                    if (targetUid > 0) {
                        if (needed == null) {
                            needed = new NeededUriGrants(targetPkg, targetUid, mode);
                        }
                        needed.add(grantUri);
                    }
                } else {
                    Intent clipIntent = clip.getItemAt(i).getIntent();
                    if (clipIntent != null) {
                        NeededUriGrants newNeeded = checkGrantUriPermissionFromIntent(
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

    void grantUriPermissionFromIntent(int callingUid,
            String targetPkg, Intent intent, UriPermissionOwner owner, int targetUserId) {
        NeededUriGrants needed = checkGrantUriPermissionFromIntent(callingUid, targetPkg,
                intent, intent != null ? intent.getFlags() : 0, null, targetUserId);
        if (needed == null) {
            return;
        }

        grantUriPermissionUncheckedFromIntent(needed, owner);
    }

    void readGrantedUriPermissions() {
        if (DEBUG) Slog.v(TAG, "readGrantedUriPermissions()");

        final long now = System.currentTimeMillis();

        FileInputStream fis = null;
        try {
            fis = mGrantFile.openRead();
            final XmlPullParser in = Xml.newPullParser();
            in.setInput(fis, StandardCharsets.UTF_8.name());

            int type;
            while ((type = in.next()) != END_DOCUMENT) {
                final String tag = in.getName();
                if (type == START_TAG) {
                    if (TAG_URI_GRANT.equals(tag)) {
                        final int sourceUserId;
                        final int targetUserId;
                        final int userHandle = readIntAttribute(in,
                                ATTR_USER_HANDLE, UserHandle.USER_NULL);
                        if (userHandle != UserHandle.USER_NULL) {
                            // For backwards compatibility.
                            sourceUserId = userHandle;
                            targetUserId = userHandle;
                        } else {
                            sourceUserId = readIntAttribute(in, ATTR_SOURCE_USER_ID);
                            targetUserId = readIntAttribute(in, ATTR_TARGET_USER_ID);
                        }
                        final String sourcePkg = in.getAttributeValue(null, ATTR_SOURCE_PKG);
                        final String targetPkg = in.getAttributeValue(null, ATTR_TARGET_PKG);
                        final Uri uri = Uri.parse(in.getAttributeValue(null, ATTR_URI));
                        final boolean prefix = readBooleanAttribute(in, ATTR_PREFIX);
                        final int modeFlags = readIntAttribute(in, ATTR_MODE_FLAGS);
                        final long createdTime = readLongAttribute(in, ATTR_CREATED_TIME, now);

                        // Sanity check that provider still belongs to source package
                        // Both direct boot aware and unaware packages are fine as we
                        // will do filtering at query time to avoid multiple parsing.
                        final ProviderInfo pi = getProviderInfo(uri.getAuthority(), sourceUserId,
                                MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE);
                        if (pi != null && sourcePkg.equals(pi.packageName)) {
                            int targetUid = -1;
                            try {
                                targetUid = AppGlobals.getPackageManager().getPackageUid(
                                        targetPkg, MATCH_UNINSTALLED_PACKAGES, targetUserId);
                            } catch (RemoteException e) {
                            }
                            if (targetUid != -1) {
                                final UriPermission perm = findOrCreateUriPermission(
                                        sourcePkg, targetPkg, targetUid,
                                        new GrantUri(sourceUserId, uri, prefix));
                                perm.initPersistedModes(modeFlags, createdTime);
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

    private UriPermission findOrCreateUriPermission(String sourcePkg,
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

        final String authority = grantUri.uri.getAuthority();
        final ProviderInfo pi = getProviderInfo(authority, grantUri.sourceUserId,
                MATCH_DEBUG_TRIAGED_MISSING);
        if (pi == null) {
            Slog.w(TAG, "No content provider found for grant: " + grantUri.toSafeString());
            return;
        }

        if ((modeFlags & Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) != 0) {
            grantUri.prefix = true;
        }
        final UriPermission perm = findOrCreateUriPermission(
                pi.packageName, targetPkg, targetUid, grantUri);
        perm.grantModes(modeFlags, owner);
    }

    /** Like grantUriPermissionUnchecked, but takes an Intent. */
    void grantUriPermissionUncheckedFromIntent(NeededUriGrants needed, UriPermissionOwner owner) {
        if (needed == null) {
            return;
        }
        for (int i=0; i<needed.size(); i++) {
            GrantUri grantUri = needed.get(i);
            grantUriPermissionUnchecked(needed.targetUid, needed.targetPkg,
                    grantUri, needed.flags, owner);
        }
    }

    void grantUriPermission(int callingUid, String targetPkg, GrantUri grantUri,
            final int modeFlags, UriPermissionOwner owner, int targetUserId) {
        if (targetPkg == null) {
            throw new NullPointerException("targetPkg");
        }
        int targetUid;
        final IPackageManager pm = AppGlobals.getPackageManager();
        try {
            targetUid = pm.getPackageUid(targetPkg, MATCH_DEBUG_TRIAGED_MISSING, targetUserId);
        } catch (RemoteException ex) {
            return;
        }

        targetUid = checkGrantUriPermission(callingUid, targetPkg, grantUri, modeFlags, targetUid);
        if (targetUid < 0) {
            return;
        }

        grantUriPermissionUnchecked(targetUid, targetPkg, grantUri, modeFlags, owner);
    }

    void revokeUriPermission(String targetPackage, int callingUid, GrantUri grantUri,
            final int modeFlags) {
        if (DEBUG) Slog.v(TAG, "Revoking all granted permissions to " + grantUri);

        final IPackageManager pm = AppGlobals.getPackageManager();
        final String authority = grantUri.uri.getAuthority();
        final ProviderInfo pi = getProviderInfo(authority, grantUri.sourceUserId,
                MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE);
        if (pi == null) {
            Slog.w(TAG, "No content provider found for permission revoke: "
                    + grantUri.toSafeString());
            return;
        }

        // Does the caller have this permission on the URI?
        if (!checkHoldingPermissions(pm, pi, grantUri, callingUid, modeFlags)) {
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
    private boolean checkHoldingPermissions(
            IPackageManager pm, ProviderInfo pi, GrantUri grantUri, int uid, final int modeFlags) {
        if (DEBUG) Slog.v(TAG, "checkHoldingPermissions: uri=" + grantUri + " uid=" + uid);
        if (UserHandle.getUserId(uid) != grantUri.sourceUserId) {
            if (ActivityManager.checkComponentPermission(INTERACT_ACROSS_USERS, uid, -1, true)
                    != PERMISSION_GRANTED) {
                return false;
            }
        }
        return checkHoldingPermissionsInternal(pm, pi, grantUri, uid, modeFlags, true);
    }

    private boolean checkHoldingPermissionsInternal(IPackageManager pm, ProviderInfo pi,
            GrantUri grantUri, int uid, final int modeFlags, boolean considerUidPermissions) {
        if (pi.applicationInfo.uid == uid) {
            return true;
        } else if (!pi.exported) {
            return false;
        }

        boolean readMet = (modeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0;
        boolean writeMet = (modeFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0;
        try {
            // check if target holds top-level <provider> permissions
            if (!readMet && pi.readPermission != null && considerUidPermissions
                    && (pm.checkUidPermission(pi.readPermission, uid) == PERMISSION_GRANTED)) {
                readMet = true;
            }
            if (!writeMet && pi.writePermission != null && considerUidPermissions
                    && (pm.checkUidPermission(pi.writePermission, uid) == PERMISSION_GRANTED)) {
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
                                            + " check=" + pm.checkUidPermission(pprperm, uid));
                            if (pprperm != null) {
                                if (considerUidPermissions && pm.checkUidPermission(pprperm, uid)
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
                                            + " check=" + pm.checkUidPermission(ppwperm, uid));
                            if (ppwperm != null) {
                                if (considerUidPermissions && pm.checkUidPermission(ppwperm, uid)
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

        } catch (RemoteException e) {
            return false;
        }

        return readMet && writeMet;
    }

    private void removeUriPermissionIfNeeded(UriPermission perm) {
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

    private ProviderInfo getProviderInfo(String authority, int userHandle, int pmFlags) {
        ProviderInfo pi = null;
        try {
            pi = AppGlobals.getPackageManager().resolveContentProvider(
                    authority, PackageManager.GET_URI_PERMISSION_PATTERNS | pmFlags,
                    userHandle);
        } catch (RemoteException ex) {
        }
        return pi;
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
    int checkGrantUriPermission(int callingUid, String targetPkg, GrantUri grantUri,
            final int modeFlags, int lastTargetUid) {
        if (!Intent.isAccessUriMode(modeFlags)) {
            return -1;
        }

        if (targetPkg != null) {
            if (DEBUG) Slog.v(TAG, "Checking grant " + targetPkg + " permission to " + grantUri);
        }

        final IPackageManager pm = AppGlobals.getPackageManager();

        // If this is not a content: uri, we can't do anything with it.
        if (!ContentResolver.SCHEME_CONTENT.equals(grantUri.uri.getScheme())) {
            if (DEBUG) Slog.v(TAG, "Can't grant URI permission for non-content URI: " + grantUri);
            return -1;
        }

        // Bail early if system is trying to hand out permissions directly; it
        // must always grant permissions on behalf of someone explicit.
        final int callingAppId = UserHandle.getAppId(callingUid);
        if ((callingAppId == SYSTEM_UID) || (callingAppId == ROOT_UID)) {
            if ("com.android.settings.files".equals(grantUri.uri.getAuthority())) {
                // Exempted authority for
                // 1. cropping user photos and sharing a generated license html
                //    file in Settings app
                // 2. sharing a generated license html file in TvSettings app
            } else {
                Slog.w(TAG, "For security reasons, the system cannot issue a Uri permission"
                        + " grant to " + grantUri + "; use startActivityAsCaller() instead");
                return -1;
            }
        }

        final String authority = grantUri.uri.getAuthority();
        final ProviderInfo pi = getProviderInfo(authority, grantUri.sourceUserId,
                MATCH_DEBUG_TRIAGED_MISSING);
        if (pi == null) {
            Slog.w(TAG, "No content provider found for permission check: " +
                    grantUri.uri.toSafeString());
            return -1;
        }

        int targetUid = lastTargetUid;
        if (targetUid < 0 && targetPkg != null) {
            try {
                targetUid = pm.getPackageUid(targetPkg, MATCH_DEBUG_TRIAGED_MISSING,
                        UserHandle.getUserId(callingUid));
                if (targetUid < 0) {
                    if (DEBUG) Slog.v(TAG, "Can't grant URI permission no uid for: " + targetPkg);
                    return -1;
                }
            } catch (RemoteException ex) {
                return -1;
            }
        }

        // Figure out the value returned when access is allowed
        final int allowedResult;
        if ((modeFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
                || pi.forceUriPermissions) {
            // If we're extending a persistable grant or need to force, then we need to return
            // "targetUid" so that we always create a grant data structure to
            // support take/release APIs
            allowedResult = targetUid;
        } else {
            // Otherwise, we can return "-1" to indicate that no grant data
            // structures need to be created
            allowedResult = -1;
        }

        if (targetUid >= 0) {
            // First...  does the target actually need this permission?
            if (checkHoldingPermissions(pm, pi, grantUri, targetUid, modeFlags)) {
                // No need to grant the target this permission.
                if (DEBUG) Slog.v(TAG,
                        "Target " + targetPkg + " already has full permission to " + grantUri);
                return allowedResult;
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
                return allowedResult;
            }
        }

        /* There is a special cross user grant if:
         * - The target is on another user.
         * - Apps on the current user can access the uri without any uid permissions.
         * In this case, we grant a uri permission, even if the ContentProvider does not normally
         * grant uri permissions.
         */
        boolean specialCrossUserGrant = targetUid >= 0
                && UserHandle.getUserId(targetUid) != grantUri.sourceUserId
                && checkHoldingPermissionsInternal(pm, pi, grantUri, callingUid,
                modeFlags, false /*without considering the uid permissions*/);

        // Second...  is the provider allowing granting of URI permissions?
        if (!specialCrossUserGrant) {
            if (!pi.grantUriPermissions) {
                throw new SecurityException("Provider " + pi.packageName
                        + "/" + pi.name
                        + " does not allow granting of Uri permissions (uri "
                        + grantUri + ")");
            }
            if (pi.uriPermissionPatterns != null) {
                final int N = pi.uriPermissionPatterns.length;
                boolean allowed = false;
                for (int i=0; i<N; i++) {
                    if (pi.uriPermissionPatterns[i] != null
                            && pi.uriPermissionPatterns[i].match(grantUri.uri.getPath())) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) {
                    throw new SecurityException("Provider " + pi.packageName
                            + "/" + pi.name
                            + " does not allow granting of permission to path of Uri "
                            + grantUri);
                }
            }
        }

        // Third...  does the caller itself have permission to access this uri?
        if (!checkHoldingPermissions(pm, pi, grantUri, callingUid, modeFlags)) {
            // Require they hold a strong enough Uri permission
            if (!checkUriPermission(grantUri, callingUid, modeFlags)) {
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

    /**
     * @param userId The userId in which the uri is to be resolved.
     */
    int checkGrantUriPermission(int callingUid, String targetPkg, Uri uri, int modeFlags,
            int userId) {
        return checkGrantUriPermission(callingUid, targetPkg,
                new GrantUri(userId, uri, false), modeFlags, -1);
    }

    boolean checkUriPermission(GrantUri grantUri, int uid, final int modeFlags) {
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

    private void writeGrantedUriPermissions() {
        if (DEBUG) Slog.v(TAG, "writeGrantedUriPermissions()");

        final long startTime = SystemClock.uptimeMillis();

        // Snapshot permissions so we can persist without lock
        ArrayList<UriPermission.Snapshot> persist = Lists.newArrayList();
        synchronized (this) {
            final int size = mGrantedUriPermissions.size();
            for (int i = 0; i < size; i++) {
                final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.valueAt(i);
                for (UriPermission perm : perms.values()) {
                    if (perm.persistedModeFlags != 0) {
                        persist.add(perm.snapshot());
                    }
                }
            }
        }

        FileOutputStream fos = null;
        try {
            fos = mGrantFile.startWrite(startTime);

            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);
            out.startTag(null, TAG_URI_GRANTS);
            for (UriPermission.Snapshot perm : persist) {
                out.startTag(null, TAG_URI_GRANT);
                writeIntAttribute(out, ATTR_SOURCE_USER_ID, perm.uri.sourceUserId);
                writeIntAttribute(out, ATTR_TARGET_USER_ID, perm.targetUserId);
                out.attribute(null, ATTR_SOURCE_PKG, perm.sourcePkg);
                out.attribute(null, ATTR_TARGET_PKG, perm.targetPkg);
                out.attribute(null, ATTR_URI, String.valueOf(perm.uri.uri));
                writeBooleanAttribute(out, ATTR_PREFIX, perm.uri.prefix);
                writeIntAttribute(out, ATTR_MODE_FLAGS, perm.persistedModeFlags);
                writeLongAttribute(out, ATTR_CREATED_TIME, perm.persistedCreateTime);
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
    }

    private PackageManagerInternal getPmInternal() {
        // Don't need to synchonize; worst-case scenario LocalServices will be called twice.
        if (mPmInternal == null) {
            mPmInternal = LocalServices.getService(PackageManagerInternal.class);
        }
        return mPmInternal;
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

    final class LocalService implements UriGrantsManagerInternal {

        @Override
        public void removeUriPermissionIfNeeded(UriPermission perm) {
            synchronized (mLock) {
                UriGrantsManagerService.this.removeUriPermissionIfNeeded(perm);
            }
        }

        @Override
        public void grantUriPermission(int callingUid, String targetPkg, GrantUri grantUri,
                int modeFlags, UriPermissionOwner owner, int targetUserId) {
            synchronized (mLock) {
                UriGrantsManagerService.this.grantUriPermission(
                        callingUid, targetPkg, grantUri, modeFlags, owner, targetUserId);
            }
        }

        @Override
        public void revokeUriPermission(String targetPackage, int callingUid, GrantUri grantUri,
                int modeFlags) {
            synchronized (mLock) {
                UriGrantsManagerService.this.revokeUriPermission(
                        targetPackage, callingUid, grantUri, modeFlags);
            }
        }

        @Override
        public boolean checkUriPermission(GrantUri grantUri, int uid, int modeFlags) {
            synchronized (mLock) {
                return UriGrantsManagerService.this.checkUriPermission(grantUri, uid, modeFlags);
            }
        }

        @Override
        public int checkGrantUriPermission(int callingUid, String targetPkg, GrantUri uri,
                int modeFlags, int userId) {
            synchronized (mLock) {
                return UriGrantsManagerService.this.checkGrantUriPermission(
                        callingUid, targetPkg, uri, modeFlags, userId);
            }
        }

        @Override
        public int checkGrantUriPermission(int callingUid, String targetPkg, Uri uri, int modeFlags,
                int userId) {
            enforceNotIsolatedCaller("checkGrantUriPermission");
            synchronized (mLock) {
                return UriGrantsManagerService.this.checkGrantUriPermission(
                        callingUid, targetPkg, uri, modeFlags, userId);
            }
        }

        @Override
        public NeededUriGrants checkGrantUriPermissionFromIntent(int callingUid, String targetPkg,
                Intent intent, int mode, NeededUriGrants needed, int targetUserId) {
            synchronized (mLock) {
                return UriGrantsManagerService.this.checkGrantUriPermissionFromIntent(
                        callingUid, targetPkg, intent, mode, needed, targetUserId);
            }
        }

        @Override
        public void grantUriPermissionFromIntent(int callingUid, String targetPkg, Intent intent,
                int targetUserId) {
            synchronized (mLock) {
                UriGrantsManagerService.this.grantUriPermissionFromIntent(
                        callingUid, targetPkg, intent, null, targetUserId);
            }
        }

        @Override
        public void grantUriPermissionFromIntent(int callingUid, String targetPkg, Intent intent,
                UriPermissionOwner owner, int targetUserId) {
            synchronized (mLock) {
                UriGrantsManagerService.this.grantUriPermissionFromIntent(
                        callingUid, targetPkg, intent, owner, targetUserId);
            }
        }

        @Override
        public void grantUriPermissionUncheckedFromIntent(NeededUriGrants needed,
                UriPermissionOwner owner) {
            synchronized (mLock) {
                UriGrantsManagerService.this.grantUriPermissionUncheckedFromIntent(needed, owner);
            }
        }

        @Override
        public void onSystemReady() {
            synchronized (mLock) {
                UriGrantsManagerService.this.readGrantedUriPermissions();
            }
        }

        @Override
        public void onActivityManagerInternalAdded() {
            synchronized (mLock) {
                UriGrantsManagerService.this.onActivityManagerInternalAdded();
            }
        }

        @Override
        public IBinder newUriPermissionOwner(String name) {
            enforceNotIsolatedCaller("newUriPermissionOwner");
            synchronized(mLock) {
                UriPermissionOwner owner = new UriPermissionOwner(this, name);
                return owner.getExternalToken();
            }
        }

        @Override
        public void removeUriPermissionsForPackage(String packageName, int userHandle,
                boolean persistable, boolean targetOnly) {
            synchronized(mLock) {
                UriGrantsManagerService.this.removeUriPermissionsForPackage(
                        packageName, userHandle, persistable, targetOnly);
            }
        }

        @Override
        public void revokeUriPermissionFromOwner(IBinder token, Uri uri, int mode, int userId) {
            synchronized(mLock) {
                final UriPermissionOwner owner = UriPermissionOwner.fromExternalToken(token);
                if (owner == null) {
                    throw new IllegalArgumentException("Unknown owner: " + token);
                }

                if (uri == null) {
                    owner.removeUriPermissions(mode);
                } else {
                    final boolean prefix = (mode & Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) != 0;
                    owner.removeUriPermission(new GrantUri(userId, uri, prefix), mode);
                }
            }
        }

        @Override
        public boolean checkAuthorityGrants(int callingUid, ProviderInfo cpi, int userId,
                boolean checkUser) {
            synchronized(mLock) {
                return UriGrantsManagerService.this.checkAuthorityGrants(
                        callingUid, cpi, userId, checkUser);
            }
        }

        @Override
        public void dump(PrintWriter pw, boolean dumpAll, String dumpPackage) {
            synchronized(mLock) {
                boolean needSep = false;
                boolean printedAnything = false;
                if (mGrantedUriPermissions.size() > 0) {
                    boolean printed = false;
                    int dumpUid = -2;
                    if (dumpPackage != null) {
                        try {
                            dumpUid = mContext.getPackageManager().getPackageUidAsUser(dumpPackage,
                                    MATCH_ANY_USER, 0);
                        } catch (PackageManager.NameNotFoundException e) {
                            dumpUid = -1;
                        }
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
