/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.slice;

import static android.content.ContentProvider.getUriWithoutUserId;
import static android.content.ContentProvider.getUserIdFromUri;
import static android.content.ContentProvider.maybeAddUserId;

import android.Manifest.permission;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.ContentProviderHolder;
import android.app.IActivityManager;
import android.app.slice.ISliceListener;
import android.app.slice.ISliceManager;
import android.app.slice.SliceManager;
import android.app.slice.SliceSpec;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.AssistUtils;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class SliceManagerService extends ISliceManager.Stub {

    private static final String TAG = "SliceManagerService";
    private final Object mLock = new Object();

    private final Context mContext;
    private final PackageManagerInternal mPackageManagerInternal;
    private final AppOpsManager mAppOps;
    private final AssistUtils mAssistUtils;

    @GuardedBy("mLock")
    private final ArrayMap<Uri, PinnedSliceState> mPinnedSlicesByUri = new ArrayMap<>();
    @GuardedBy("mLock")
    private final ArraySet<SliceGrant> mUserGrants = new ArraySet<>();
    private final Handler mHandler;
    private final ContentObserver mObserver;

    public SliceManagerService(Context context) {
        this(context, createHandler().getLooper());
    }

    @VisibleForTesting
    SliceManagerService(Context context, Looper looper) {
        mContext = context;
        mPackageManagerInternal = Preconditions.checkNotNull(
                LocalServices.getService(PackageManagerInternal.class));
        mAppOps = context.getSystemService(AppOpsManager.class);
        mAssistUtils = new AssistUtils(context);
        mHandler = new Handler(looper);

        mObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                try {
                    getPinnedSlice(uri).onChange();
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Received change for unpinned slice " + uri, e);
                }
            }
        };
    }

    ///  ----- Lifecycle stuff -----
    private void systemReady() {
    }

    private void onUnlockUser(int userId) {
    }

    private void onStopUser(int userId) {
        synchronized (mLock) {
            mPinnedSlicesByUri.values().removeIf(s -> getUserIdFromUri(s.getUri()) == userId);
        }
    }

    ///  ----- ISliceManager stuff -----
    @Override
    public void addSliceListener(Uri uri, String pkg, ISliceListener listener, SliceSpec[] specs)
            throws RemoteException {
        verifyCaller(pkg);
        uri = maybeAddUserId(uri, Binder.getCallingUserHandle().getIdentifier());
        enforceAccess(pkg, uri);
        getOrCreatePinnedSlice(uri).addSliceListener(listener, pkg, specs);
    }

    @Override
    public void removeSliceListener(Uri uri, String pkg, ISliceListener listener)
            throws RemoteException {
        verifyCaller(pkg);
        uri = maybeAddUserId(uri, Binder.getCallingUserHandle().getIdentifier());
        enforceAccess(pkg, uri);
        if (getPinnedSlice(uri).removeSliceListener(listener)) {
            removePinnedSlice(uri);
        }
    }

    @Override
    public void pinSlice(String pkg, Uri uri, SliceSpec[] specs) throws RemoteException {
        verifyCaller(pkg);
        enforceFullAccess(pkg, "pinSlice", uri);
        uri = maybeAddUserId(uri, Binder.getCallingUserHandle().getIdentifier());
        getOrCreatePinnedSlice(uri).pin(pkg, specs);
    }

    @Override
    public void unpinSlice(String pkg, Uri uri) throws RemoteException {
        verifyCaller(pkg);
        enforceFullAccess(pkg, "unpinSlice", uri);
        uri = maybeAddUserId(uri, Binder.getCallingUserHandle().getIdentifier());
        if (getPinnedSlice(uri).unpin(pkg)) {
            removePinnedSlice(uri);
        }
    }

    @Override
    public boolean hasSliceAccess(String pkg) throws RemoteException {
        verifyCaller(pkg);
        return hasFullSliceAccess(pkg, Binder.getCallingUserHandle().getIdentifier());
    }

    @Override
    public SliceSpec[] getPinnedSpecs(Uri uri, String pkg) throws RemoteException {
        verifyCaller(pkg);
        enforceAccess(pkg, uri);
        return getPinnedSlice(uri).getSpecs();
    }

    @Override
    public int checkSlicePermission(Uri uri, String pkg, int pid, int uid) throws RemoteException {
        if (mContext.checkUriPermission(uri, pid, uid, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                == PackageManager.PERMISSION_GRANTED) {
            return SliceManager.PERMISSION_GRANTED;
        }
        if (hasFullSliceAccess(pkg, uid)) {
            return SliceManager.PERMISSION_GRANTED;
        }
        synchronized (mLock) {
            if (mUserGrants.contains(new SliceGrant(uri, pkg))) {
                return SliceManager.PERMISSION_USER_GRANTED;
            }
        }
        return SliceManager.PERMISSION_DENIED;
    }

    @Override
    public void grantPermissionFromUser(Uri uri, String pkg, String callingPkg, boolean allSlices) {
        verifyCaller(callingPkg);
        getContext().enforceCallingOrSelfPermission(permission.MANAGE_SLICE_PERMISSIONS,
                "Slice granting requires MANAGE_SLICE_PERMISSIONS");
        if (allSlices) {
            // TODO: Manage full access grants.
        } else {
            synchronized (mLock) {
                mUserGrants.add(new SliceGrant(uri, pkg));
            }
            long ident = Binder.clearCallingIdentity();
            try {
                mContext.getContentResolver().notifyChange(uri, null);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    ///  ----- internal code -----
    void removePinnedSlice(Uri uri) {
        synchronized (mLock) {
            mPinnedSlicesByUri.remove(uri).destroy();
        }
    }

    private PinnedSliceState getPinnedSlice(Uri uri) {
        synchronized (mLock) {
            PinnedSliceState manager = mPinnedSlicesByUri.get(uri);
            if (manager == null) {
                throw new IllegalStateException(String.format("Slice %s not pinned",
                        uri.toString()));
            }
            return manager;
        }
    }

    private PinnedSliceState getOrCreatePinnedSlice(Uri uri) {
        synchronized (mLock) {
            PinnedSliceState manager = mPinnedSlicesByUri.get(uri);
            if (manager == null) {
                manager = createPinnedSlice(uri);
                mPinnedSlicesByUri.put(uri, manager);
            }
            return manager;
        }
    }

    @VisibleForTesting
    protected PinnedSliceState createPinnedSlice(Uri uri) {
        return new PinnedSliceState(this, uri);
    }

    public Object getLock() {
        return mLock;
    }

    public Context getContext() {
        return mContext;
    }

    public Handler getHandler() {
        return mHandler;
    }

    private void enforceAccess(String pkg, Uri uri) throws RemoteException {
        int user = Binder.getCallingUserHandle().getIdentifier();
        // Check for default launcher/assistant.
        if (!hasFullSliceAccess(pkg, Binder.getCallingUid())) {
            try {
                // Also allow things with uri access.
                getContext().enforceUriPermission(uri, Binder.getCallingPid(),
                        Binder.getCallingUid(),
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        "Slice binding requires permission to the Uri");
            } catch (SecurityException e) {
                // Last fallback (if the calling app owns the authority, then it can have access).
                long ident = Binder.clearCallingIdentity();
                try {
                    IBinder token = new Binder();
                    IActivityManager activityManager = ActivityManager.getService();
                    ContentProviderHolder holder = null;
                    String providerName = getUriWithoutUserId(uri).getAuthority();
                    try {
                        holder = activityManager.getContentProviderExternal(
                                providerName, getUserIdFromUri(uri, user), token);
                        if (holder == null || holder.info == null
                                || !Objects.equals(holder.info.packageName, pkg)) {
                            // No more fallbacks, no access.
                            throw e;
                        }
                    } finally {
                        if (holder != null && holder.provider != null) {
                            activityManager.removeContentProviderExternal(providerName, token);
                        }
                    }
                } finally {
                    // I know, the double finally seems ugly, but seems safest for the identity.
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
        // Lastly check for any multi-userness. Any return statements above here will break this
        // important check.
        if (getUserIdFromUri(uri, user) != user) {
            getContext().enforceCallingOrSelfPermission(permission.INTERACT_ACROSS_USERS_FULL,
                    "Slice interaction across users requires INTERACT_ACROSS_USERS_FULL");
        }
    }

    private void enforceFullAccess(String pkg, String name, Uri uri) {
        int user = Binder.getCallingUserHandle().getIdentifier();
        if (!hasFullSliceAccess(pkg, user)) {
            throw new SecurityException(String.format("Call %s requires full slice access", name));
        }
        if (getUserIdFromUri(uri, user) != user) {
            getContext().enforceCallingOrSelfPermission(permission.INTERACT_ACROSS_USERS_FULL,
                    "Slice interaction across users requires INTERACT_ACROSS_USERS_FULL");
        }
    }

    private void verifyCaller(String pkg) {
        mAppOps.checkPackage(Binder.getCallingUid(), pkg);
    }

    private boolean hasFullSliceAccess(String pkg, int userId) {
        long ident = Binder.clearCallingIdentity();
        try {
            boolean ret = isDefaultHomeApp(pkg, userId) || isAssistant(pkg, userId)
                    || isGrantedFullAccess(pkg, userId);
            return ret;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private boolean isAssistant(String pkg, int userId) {
        final ComponentName cn = mAssistUtils.getAssistComponentForUser(userId);
        if (cn == null) {
            return false;
        }
        return cn.getPackageName().equals(pkg);
    }

    public void listen(Uri uri) {
        mContext.getContentResolver().registerContentObserver(uri, true, mObserver);
    }

    public void unlisten(Uri uri) {
        mContext.getContentResolver().unregisterContentObserver(mObserver);
        synchronized (mLock) {
            mPinnedSlicesByUri.forEach((u, s) -> {
                if (s.isListening()) {
                    listen(u);
                }
            });
        }
    }

    private boolean isDefaultHomeApp(String pkg, int userId) {
        String defaultHome = getDefaultHome(userId);

        return pkg != null && Objects.equals(pkg, defaultHome);
    }

    // Based on getDefaultHome in ShortcutService.
    // TODO: Unify if possible
    @VisibleForTesting
    protected String getDefaultHome(int userId) {
        final long token = Binder.clearCallingIdentity();
        try {
            final List<ResolveInfo> allHomeCandidates = new ArrayList<>();

            // Default launcher from package manager.
            final ComponentName defaultLauncher = mPackageManagerInternal
                    .getHomeActivitiesAsUser(allHomeCandidates, userId);

            ComponentName detected = null;
            if (defaultLauncher != null) {
                detected = defaultLauncher;
            }

            if (detected == null) {
                // If we reach here, that means it's the first check since the user was created,
                // and there's already multiple launchers and there's no default set.
                // Find the system one with the highest priority.
                // (We need to check the priority too because of FallbackHome in Settings.)
                // If there's no system launcher yet, then no one can access slices, until
                // the user explicitly sets one.
                final int size = allHomeCandidates.size();

                int lastPriority = Integer.MIN_VALUE;
                for (int i = 0; i < size; i++) {
                    final ResolveInfo ri = allHomeCandidates.get(i);
                    if (!ri.activityInfo.applicationInfo.isSystemApp()) {
                        continue;
                    }
                    if (ri.priority < lastPriority) {
                        continue;
                    }
                    detected = ri.activityInfo.getComponentName();
                    lastPriority = ri.priority;
                }
            }
            return detected != null ? detected.getPackageName() : null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean isGrantedFullAccess(String pkg, int userId) {
        // TODO: This will be user granted access, if we allow this through a prompt.
        return false;
    }

    private static ServiceThread createHandler() {
        ServiceThread handlerThread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_BACKGROUND, true /*allowIo*/);
        handlerThread.start();
        return handlerThread;
    }

    public static class Lifecycle extends SystemService {
        private SliceManagerService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new SliceManagerService(getContext());
            publishBinderService(Context.SLICE_SERVICE, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                mService.systemReady();
            }
        }

        @Override
        public void onUnlockUser(int userHandle) {
            mService.onUnlockUser(userHandle);
        }

        @Override
        public void onStopUser(int userHandle) {
            mService.onStopUser(userHandle);
        }
    }

    private class SliceGrant {
        private final Uri mUri;
        private final String mPkg;

        public SliceGrant(Uri uri, String pkg) {
            mUri = uri;
            mPkg = pkg;
        }

        @Override
        public int hashCode() {
            return mUri.hashCode() + mPkg.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof SliceGrant)) return false;
            SliceGrant other = (SliceGrant) obj;
            return Objects.equals(other.mUri, mUri) && Objects.equals(other.mPkg, mPkg);
        }
    }
}
