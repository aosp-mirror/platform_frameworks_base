/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserInfo;
import android.media.AudioAttributes;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManagerInternal;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IAppOpsActiveCallback;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.android.internal.os.Zygote;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.function.pooled.PooledLambda;

import libcore.util.EmptyArray;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static android.app.AppOpsManager.UID_STATE_BACKGROUND;
import static android.app.AppOpsManager.UID_STATE_CACHED;
import static android.app.AppOpsManager.UID_STATE_FOREGROUND;
import static android.app.AppOpsManager.UID_STATE_FOREGROUND_SERVICE;
import static android.app.AppOpsManager._NUM_UID_STATE;
import static android.app.AppOpsManager.UID_STATE_PERSISTENT;
import static android.app.AppOpsManager.UID_STATE_TOP;

public class AppOpsService extends IAppOpsService.Stub {
    static final String TAG = "AppOps";
    static final boolean DEBUG = false;

    private static final int NO_VERSION = -1;
    /** Increment by one every time and add the corresponding upgrade logic in
     *  {@link #upgradeLocked(int)} below. The first version was 1 */
    private static final int CURRENT_VERSION = 1;

    // Write at most every 30 minutes.
    static final long WRITE_DELAY = DEBUG ? 1000 : 30*60*1000;

    // Constant meaning that any UID should be matched when dispatching callbacks
    private static final int UID_ANY = -2;

    // Map from process states to the uid states we track.
    private static final int[] PROCESS_STATE_TO_UID_STATE = new int[] {
        UID_STATE_PERSISTENT,           // ActivityManager.PROCESS_STATE_PERSISTENT
        UID_STATE_PERSISTENT,           // ActivityManager.PROCESS_STATE_PERSISTENT_UI
        UID_STATE_TOP,                  // ActivityManager.PROCESS_STATE_TOP
        UID_STATE_FOREGROUND_SERVICE,   // ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE
        UID_STATE_FOREGROUND,           // ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE
        UID_STATE_FOREGROUND,           // ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND
        UID_STATE_BACKGROUND,           // ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
        UID_STATE_BACKGROUND,           // ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND
        UID_STATE_BACKGROUND,           // ActivityManager.PROCESS_STATE_BACKUP
        UID_STATE_BACKGROUND,           // ActivityManager.PROCESS_STATE_SERVICE
        UID_STATE_BACKGROUND,           // ActivityManager.PROCESS_STATE_RECEIVER
        UID_STATE_CACHED,               // ActivityManager.PROCESS_STATE_TOP_SLEEPING
        UID_STATE_CACHED,               // ActivityManager.PROCESS_STATE_HEAVY_WEIGHT
        UID_STATE_CACHED,               // ActivityManager.PROCESS_STATE_HOME
        UID_STATE_CACHED,               // ActivityManager.PROCESS_STATE_LAST_ACTIVITY
        UID_STATE_CACHED,               // ActivityManager.PROCESS_STATE_CACHED_ACTIVITY
        UID_STATE_CACHED,               // ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT
        UID_STATE_CACHED,               // ActivityManager.PROCESS_STATE_CACHED_RECENT
        UID_STATE_CACHED,               // ActivityManager.PROCESS_STATE_CACHED_EMPTY
        UID_STATE_CACHED,               // ActivityManager.PROCESS_STATE_NONEXISTENT
    };

    static final String[] UID_STATE_NAMES = new String[] {
            "pers ",    // UID_STATE_PERSISTENT
            "top  ",    // UID_STATE_TOP
            "fgsvc",    // UID_STATE_FOREGROUND_SERVICE
            "fg   ",    // UID_STATE_FOREGROUND
            "bg   ",    // UID_STATE_BACKGROUND
            "cch  ",    // UID_STATE_CACHED
    };

    static final String[] UID_STATE_TIME_ATTRS = new String[] {
            "tp",       // UID_STATE_PERSISTENT
            "tt",       // UID_STATE_TOP
            "tfs",      // UID_STATE_FOREGROUND_SERVICE
            "tf",       // UID_STATE_FOREGROUND
            "tb",       // UID_STATE_BACKGROUND
            "tc",       // UID_STATE_CACHED
    };

    static final String[] UID_STATE_REJECT_ATTRS = new String[] {
            "rp",       // UID_STATE_PERSISTENT
            "rt",       // UID_STATE_TOP
            "rfs",      // UID_STATE_FOREGROUND_SERVICE
            "rf",       // UID_STATE_FOREGROUND
            "rb",       // UID_STATE_BACKGROUND
            "rc",       // UID_STATE_CACHED
    };

    static final String[] MODE_NAMES = new String[] {
            "allow",        // MODE_ALLOWED
            "ignore",       // MODE_IGNORED
            "deny",         // MODE_ERRORED
            "default",      // MODE_DEFAULT
    };

    Context mContext;
    final AtomicFile mFile;
    final Handler mHandler;

    boolean mWriteScheduled;
    boolean mFastWriteScheduled;
    final Runnable mWriteRunner = new Runnable() {
        public void run() {
            synchronized (AppOpsService.this) {
                mWriteScheduled = false;
                mFastWriteScheduled = false;
                AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
                    @Override protected Void doInBackground(Void... params) {
                        writeState();
                        return null;
                    }
                };
                task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[])null);
            }
        }
    };

    @VisibleForTesting
    final SparseArray<UidState> mUidStates = new SparseArray<>();

    /*
     * These are app op restrictions imposed per user from various parties.
     */
    private final ArrayMap<IBinder, ClientRestrictionState> mOpUserRestrictions = new ArrayMap<>();

    @VisibleForTesting
    static final class UidState {
        public final int uid;
        public int state = UID_STATE_CACHED;
        public int startNesting;
        public ArrayMap<String, Ops> pkgOps;
        public SparseIntArray opModes;

        public UidState(int uid) {
            this.uid = uid;
        }

        public void clear() {
            pkgOps = null;
            opModes = null;
        }

        public boolean isDefault() {
            return (pkgOps == null || pkgOps.isEmpty())
                    && (opModes == null || opModes.size() <= 0);
        }
    }

    final static class Ops extends SparseArray<Op> {
        final String packageName;
        final UidState uidState;
        final boolean isPrivileged;

        Ops(String _packageName, UidState _uidState, boolean _isPrivileged) {
            packageName = _packageName;
            uidState = _uidState;
            isPrivileged = _isPrivileged;
        }
    }

    final static class Op {
        final UidState uidState;
        final int uid;
        final String packageName;
        final int op;
        int proxyUid = -1;
        String proxyPackageName;
        int mode;
        int duration;
        long time[] = new long[_NUM_UID_STATE];
        long rejectTime[] = new long[_NUM_UID_STATE];
        int startNesting;
        long startRealtime;

        Op(UidState _uidState, String _packageName, int _op) {
            uidState = _uidState;
            uid = _uidState.uid;
            packageName = _packageName;
            op = _op;
            mode = AppOpsManager.opToDefaultMode(op);
        }

        boolean hasAnyTime() {
            for (int i = 0; i < AppOpsManager._NUM_UID_STATE; i++) {
                if (time[i] != 0) {
                    return true;
                }
                if (rejectTime[i] != 0) {
                    return true;
                }
            }
            return false;
        }
    }

    final SparseArray<ArraySet<ModeCallback>> mOpModeWatchers = new SparseArray<>();
    final ArrayMap<String, ArraySet<ModeCallback>> mPackageModeWatchers = new ArrayMap<>();
    final ArrayMap<IBinder, ModeCallback> mModeWatchers = new ArrayMap<>();
    final ArrayMap<IBinder, SparseArray<ActiveCallback>> mActiveWatchers = new ArrayMap<>();
    final SparseArray<SparseArray<Restriction>> mAudioRestrictions = new SparseArray<>();

    final class ModeCallback implements DeathRecipient {
        final IAppOpsCallback mCallback;
        final int mWatchingUid;
        final int mCallingUid;
        final int mCallingPid;

        ModeCallback(IAppOpsCallback callback, int watchingUid, int callingUid,
                int callingPid) {
            mCallback = callback;
            mWatchingUid = watchingUid;
            mCallingUid = callingUid;
            mCallingPid = callingPid;
            try {
                mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("ModeCallback{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" watchinguid=");
            UserHandle.formatUid(sb, mWatchingUid);
            sb.append(" from uid=");
            UserHandle.formatUid(sb, mCallingUid);
            sb.append(" pid=");
            sb.append(mCallingPid);
            sb.append('}');
            return sb.toString();
        }

        void unlinkToDeath() {
            mCallback.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            stopWatchingMode(mCallback);
        }
    }

    final class ActiveCallback implements DeathRecipient {
        final IAppOpsActiveCallback mCallback;
        final int mWatchingUid;
        final int mCallingUid;
        final int mCallingPid;

        ActiveCallback(IAppOpsActiveCallback callback, int watchingUid, int callingUid,
                int callingPid) {
            mCallback = callback;
            mWatchingUid = watchingUid;
            mCallingUid = callingUid;
            mCallingPid = callingPid;
            try {
                mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("ActiveCallback{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" watchinguid=");
            UserHandle.formatUid(sb, mWatchingUid);
            sb.append(" from uid=");
            UserHandle.formatUid(sb, mCallingUid);
            sb.append(" pid=");
            sb.append(mCallingPid);
            sb.append('}');
            return sb.toString();
        }

        void destroy() {
            mCallback.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            stopWatchingActive(mCallback);
        }
    }

    final ArrayMap<IBinder, ClientState> mClients = new ArrayMap<>();

    final class ClientState extends Binder implements DeathRecipient {
        final ArrayList<Op> mStartedOps = new ArrayList<>();
        final IBinder mAppToken;
        final int mPid;

        ClientState(IBinder appToken) {
            mAppToken = appToken;
            mPid = Binder.getCallingPid();
            // Watch only for remote processes dying
            if (!(appToken instanceof Binder)) {
                try {
                    mAppToken.linkToDeath(this, 0);
                } catch (RemoteException e) {
                    /* do nothing */
                }
            }
        }

        @Override
        public String toString() {
            return "ClientState{" +
                    "mAppToken=" + mAppToken +
                    ", " + "pid=" + mPid +
                    '}';
        }

        @Override
        public void binderDied() {
            synchronized (AppOpsService.this) {
                for (int i=mStartedOps.size()-1; i>=0; i--) {
                    finishOperationLocked(mStartedOps.get(i), /*finishNested*/ true);
                }
                mClients.remove(mAppToken);
            }
        }
    }

    public AppOpsService(File storagePath, Handler handler) {
        LockGuard.installLock(this, LockGuard.INDEX_APP_OPS);
        mFile = new AtomicFile(storagePath, "appops");
        mHandler = handler;
        readState();
    }

    public void publish(Context context) {
        mContext = context;
        ServiceManager.addService(Context.APP_OPS_SERVICE, asBinder());
    }

    public void systemReady() {
        synchronized (this) {
            boolean changed = false;
            for (int i = mUidStates.size() - 1; i >= 0; i--) {
                UidState uidState = mUidStates.valueAt(i);

                String[] packageNames = getPackagesForUid(uidState.uid);
                if (ArrayUtils.isEmpty(packageNames)) {
                    uidState.clear();
                    mUidStates.removeAt(i);
                    changed = true;
                    continue;
                }

                ArrayMap<String, Ops> pkgs = uidState.pkgOps;
                if (pkgs == null) {
                    continue;
                }

                Iterator<Ops> it = pkgs.values().iterator();
                while (it.hasNext()) {
                    Ops ops = it.next();
                    int curUid = -1;
                    try {
                        curUid = AppGlobals.getPackageManager().getPackageUid(ops.packageName,
                                PackageManager.MATCH_UNINSTALLED_PACKAGES,
                                UserHandle.getUserId(ops.uidState.uid));
                    } catch (RemoteException ignored) {
                    }
                    if (curUid != ops.uidState.uid) {
                        Slog.i(TAG, "Pruning old package " + ops.packageName
                                + "/" + ops.uidState + ": new uid=" + curUid);
                        it.remove();
                        changed = true;
                    }
                }

                if (uidState.isDefault()) {
                    mUidStates.removeAt(i);
                }
            }
            if (changed) {
                scheduleFastWriteLocked();
            }
        }

        PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        packageManagerInternal.setExternalSourcesPolicy(
                new PackageManagerInternal.ExternalSourcesPolicy() {
                    @Override
                    public int getPackageTrustedToInstallApps(String packageName, int uid) {
                        int appOpMode = checkOperation(AppOpsManager.OP_REQUEST_INSTALL_PACKAGES,
                                uid, packageName);
                        switch (appOpMode) {
                            case AppOpsManager.MODE_ALLOWED:
                                return PackageManagerInternal.ExternalSourcesPolicy.USER_TRUSTED;
                            case AppOpsManager.MODE_ERRORED:
                                return PackageManagerInternal.ExternalSourcesPolicy.USER_BLOCKED;
                            default:
                                return PackageManagerInternal.ExternalSourcesPolicy.USER_DEFAULT;
                        }
                    }
                });

        StorageManagerInternal storageManagerInternal = LocalServices.getService(
                StorageManagerInternal.class);
        storageManagerInternal.addExternalStoragePolicy(
                new StorageManagerInternal.ExternalStorageMountPolicy() {
                    @Override
                    public int getMountMode(int uid, String packageName) {
                        if (Process.isIsolated(uid)) {
                            return Zygote.MOUNT_EXTERNAL_NONE;
                        }
                        if (noteOperation(AppOpsManager.OP_READ_EXTERNAL_STORAGE, uid,
                                packageName) != AppOpsManager.MODE_ALLOWED) {
                            return Zygote.MOUNT_EXTERNAL_NONE;
                        }
                        if (noteOperation(AppOpsManager.OP_WRITE_EXTERNAL_STORAGE, uid,
                                packageName) != AppOpsManager.MODE_ALLOWED) {
                            return Zygote.MOUNT_EXTERNAL_READ;
                        }
                        return Zygote.MOUNT_EXTERNAL_WRITE;
                    }

                    @Override
                    public boolean hasExternalStorage(int uid, String packageName) {
                        final int mountMode = getMountMode(uid, packageName);
                        return mountMode == Zygote.MOUNT_EXTERNAL_READ
                                || mountMode == Zygote.MOUNT_EXTERNAL_WRITE;
                    }
                });
    }

    public void packageRemoved(int uid, String packageName) {
        synchronized (this) {
            UidState uidState = mUidStates.get(uid);
            if (uidState == null) {
                return;
            }

            Ops ops = null;

            // Remove any package state if such.
            if (uidState.pkgOps != null) {
                ops = uidState.pkgOps.remove(packageName);
            }

            // If we just nuked the last package state check if the UID is valid.
            if (ops != null && uidState.pkgOps.isEmpty()
                    && getPackagesForUid(uid).length <= 0) {
                mUidStates.remove(uid);
            }

            // Finish ops other packages started on behalf of the package.
            final int clientCount = mClients.size();
            for (int i = 0; i < clientCount; i++) {
                final ClientState client = mClients.valueAt(i);
                if (client.mStartedOps == null) {
                    continue;
                }
                final int opCount = client.mStartedOps.size();
                for (int j = opCount - 1; j >= 0; j--) {
                    final Op op = client.mStartedOps.get(j);
                    if (uid == op.uid && packageName.equals(op.packageName)) {
                        finishOperationLocked(op, /*finishNested*/ true);
                        client.mStartedOps.remove(j);
                        if (op.startNesting <= 0) {
                            scheduleOpActiveChangedIfNeededLocked(op.op,
                                    uid, packageName, false);
                        }
                    }
                }
            }

            if (ops != null) {
                scheduleFastWriteLocked();

                final int opCount = ops.size();
                for (int i = 0; i < opCount; i++) {
                    final Op op = ops.valueAt(i);
                    if (op.duration == -1) {
                        scheduleOpActiveChangedIfNeededLocked(
                                op.op, op.uid, op.packageName, false);
                    }
                }
            }
        }
    }

    public void uidRemoved(int uid) {
        synchronized (this) {
            if (mUidStates.indexOfKey(uid) >= 0) {
                mUidStates.remove(uid);
                scheduleFastWriteLocked();
            }
        }
    }

    public void updateUidProcState(int uid, int procState) {
        synchronized (this) {
            final UidState uidState = getUidStateLocked(uid, true);
            final int newState = PROCESS_STATE_TO_UID_STATE[procState];
            if (uidState != null && uidState.state != newState) {
                if (uidState.startNesting != 0) {
                    // There is some actively running operation...  need to find it
                    // and appropriately update its state.
                    final long now = System.currentTimeMillis();
                    for (int i = uidState.pkgOps.size() - 1; i >= 0; i--) {
                        final Ops ops = uidState.pkgOps.valueAt(i);
                        for (int j = ops.size() - 1; j >= 0; j--) {
                            final Op op = ops.valueAt(j);
                            if (op.startNesting > 0) {
                                op.time[uidState.state] = now;
                                op.time[newState] = now;
                            }
                        }
                    }
                }
                uidState.state = newState;
            }
        }
    }

    public void shutdown() {
        Slog.w(TAG, "Writing app ops before shutdown...");
        boolean doWrite = false;
        synchronized (this) {
            if (mWriteScheduled) {
                mWriteScheduled = false;
                doWrite = true;
            }
        }
        if (doWrite) {
            writeState();
        }
    }

    private ArrayList<AppOpsManager.OpEntry> collectOps(Ops pkgOps, int[] ops) {
        ArrayList<AppOpsManager.OpEntry> resOps = null;
        final long elapsedNow = SystemClock.elapsedRealtime();
        if (ops == null) {
            resOps = new ArrayList<>();
            for (int j=0; j<pkgOps.size(); j++) {
                Op curOp = pkgOps.valueAt(j);
                long duration = curOp.duration == -1
                        ? (elapsedNow - curOp.startRealtime)
                        : curOp.duration;
                resOps.add(new AppOpsManager.OpEntry(curOp.op, curOp.mode, curOp.time,
                        curOp.rejectTime, (int) duration, curOp.proxyUid,
                        curOp.proxyPackageName));
            }
        } else {
            for (int j=0; j<ops.length; j++) {
                Op curOp = pkgOps.get(ops[j]);
                if (curOp != null) {
                    if (resOps == null) {
                        resOps = new ArrayList<>();
                    }
                    long duration = curOp.duration == -1
                            ? (elapsedNow - curOp.startRealtime)
                            : curOp.duration;
                    resOps.add(new AppOpsManager.OpEntry(curOp.op, curOp.mode, curOp.time,
                            curOp.rejectTime, (int) duration, curOp.proxyUid,
                            curOp.proxyPackageName));
                }
            }
        }
        return resOps;
    }

    private ArrayList<AppOpsManager.OpEntry> collectOps(SparseIntArray uidOps, int[] ops) {
        ArrayList<AppOpsManager.OpEntry> resOps = null;
        if (ops == null) {
            resOps = new ArrayList<>();
            for (int j=0; j<uidOps.size(); j++) {
                resOps.add(new AppOpsManager.OpEntry(uidOps.keyAt(j), uidOps.valueAt(j),
                        0, 0, 0, -1, null));
            }
        } else {
            for (int j=0; j<ops.length; j++) {
                int index = uidOps.indexOfKey(ops[j]);
                if (index >= 0) {
                    if (resOps == null) {
                        resOps = new ArrayList<>();
                    }
                    resOps.add(new AppOpsManager.OpEntry(uidOps.keyAt(index), uidOps.valueAt(index),
                            0, 0, 0, -1, null));
                }
            }
        }
        return resOps;
    }

    @Override
    public List<AppOpsManager.PackageOps> getPackagesForOps(int[] ops) {
        mContext.enforcePermission(android.Manifest.permission.GET_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
        ArrayList<AppOpsManager.PackageOps> res = null;
        synchronized (this) {
            final int uidStateCount = mUidStates.size();
            for (int i = 0; i < uidStateCount; i++) {
                UidState uidState = mUidStates.valueAt(i);
                if (uidState.pkgOps == null || uidState.pkgOps.isEmpty()) {
                    continue;
                }
                ArrayMap<String, Ops> packages = uidState.pkgOps;
                final int packageCount = packages.size();
                for (int j = 0; j < packageCount; j++) {
                    Ops pkgOps = packages.valueAt(j);
                    ArrayList<AppOpsManager.OpEntry> resOps = collectOps(pkgOps, ops);
                    if (resOps != null) {
                        if (res == null) {
                            res = new ArrayList<AppOpsManager.PackageOps>();
                        }
                        AppOpsManager.PackageOps resPackage = new AppOpsManager.PackageOps(
                                pkgOps.packageName, pkgOps.uidState.uid, resOps);
                        res.add(resPackage);
                    }
                }
            }
        }
        return res;
    }

    @Override
    public List<AppOpsManager.PackageOps> getOpsForPackage(int uid, String packageName,
            int[] ops) {
        mContext.enforcePermission(android.Manifest.permission.GET_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return Collections.emptyList();
        }
        synchronized (this) {
            Ops pkgOps = getOpsRawLocked(uid, resolvedPackageName, false /* edit */,
                    false /* uidMismatchExpected */);
            if (pkgOps == null) {
                return null;
            }
            ArrayList<AppOpsManager.OpEntry> resOps = collectOps(pkgOps, ops);
            if (resOps == null) {
                return null;
            }
            ArrayList<AppOpsManager.PackageOps> res = new ArrayList<AppOpsManager.PackageOps>();
            AppOpsManager.PackageOps resPackage = new AppOpsManager.PackageOps(
                    pkgOps.packageName, pkgOps.uidState.uid, resOps);
            res.add(resPackage);
            return res;
        }
    }

    @Override
    public List<AppOpsManager.PackageOps> getUidOps(int uid, int[] ops) {
        mContext.enforcePermission(android.Manifest.permission.GET_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
        synchronized (this) {
            UidState uidState = getUidStateLocked(uid, false);
            if (uidState == null) {
                return null;
            }
            ArrayList<AppOpsManager.OpEntry> resOps = collectOps(uidState.opModes, ops);
            if (resOps == null) {
                return null;
            }
            ArrayList<AppOpsManager.PackageOps> res = new ArrayList<AppOpsManager.PackageOps>();
            AppOpsManager.PackageOps resPackage = new AppOpsManager.PackageOps(
                    null, uidState.uid, resOps);
            res.add(resPackage);
            return res;
        }
    }

    private void pruneOp(Op op, int uid, String packageName) {
        if (!op.hasAnyTime()) {
            Ops ops = getOpsRawLocked(uid, packageName, false /* edit */,
                    false /* uidMismatchExpected */);
            if (ops != null) {
                ops.remove(op.op);
                if (ops.size() <= 0) {
                    UidState uidState = ops.uidState;
                    ArrayMap<String, Ops> pkgOps = uidState.pkgOps;
                    if (pkgOps != null) {
                        pkgOps.remove(ops.packageName);
                        if (pkgOps.isEmpty()) {
                            uidState.pkgOps = null;
                        }
                        if (uidState.isDefault()) {
                            mUidStates.remove(uid);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void setUidMode(int code, int uid, int mode) {
        if (Binder.getCallingPid() != Process.myPid()) {
            mContext.enforcePermission(android.Manifest.permission.MANAGE_APP_OPS_MODES,
                    Binder.getCallingPid(), Binder.getCallingUid(), null);
        }
        verifyIncomingOp(code);
        code = AppOpsManager.opToSwitch(code);

        synchronized (this) {
            final int defaultMode = AppOpsManager.opToDefaultMode(code);

            UidState uidState = getUidStateLocked(uid, false);
            if (uidState == null) {
                if (mode == defaultMode) {
                    return;
                }
                uidState = new UidState(uid);
                uidState.opModes = new SparseIntArray();
                uidState.opModes.put(code, mode);
                mUidStates.put(uid, uidState);
                scheduleWriteLocked();
            } else if (uidState.opModes == null) {
                if (mode != defaultMode) {
                    uidState.opModes = new SparseIntArray();
                    uidState.opModes.put(code, mode);
                    scheduleWriteLocked();
                }
            } else {
                if (uidState.opModes.get(code) == mode) {
                    return;
                }
                if (mode == defaultMode) {
                    uidState.opModes.delete(code);
                    if (uidState.opModes.size() <= 0) {
                        uidState.opModes = null;
                    }
                } else {
                    uidState.opModes.put(code, mode);
                }
                scheduleWriteLocked();
            }
        }

        String[] uidPackageNames = getPackagesForUid(uid);
        ArrayMap<ModeCallback, ArraySet<String>> callbackSpecs = null;

        synchronized (this) {
            ArraySet<ModeCallback> callbacks = mOpModeWatchers.get(code);
            if (callbacks != null) {
                final int callbackCount = callbacks.size();
                for (int i = 0; i < callbackCount; i++) {
                    ModeCallback callback = callbacks.valueAt(i);
                    ArraySet<String> changedPackages = new ArraySet<>();
                    Collections.addAll(changedPackages, uidPackageNames);
                    callbackSpecs = new ArrayMap<>();
                    callbackSpecs.put(callback, changedPackages);
                }
            }

            for (String uidPackageName : uidPackageNames) {
                callbacks = mPackageModeWatchers.get(uidPackageName);
                if (callbacks != null) {
                    if (callbackSpecs == null) {
                        callbackSpecs = new ArrayMap<>();
                    }
                    final int callbackCount = callbacks.size();
                    for (int i = 0; i < callbackCount; i++) {
                        ModeCallback callback = callbacks.valueAt(i);
                        ArraySet<String> changedPackages = callbackSpecs.get(callback);
                        if (changedPackages == null) {
                            changedPackages = new ArraySet<>();
                            callbackSpecs.put(callback, changedPackages);
                        }
                        changedPackages.add(uidPackageName);
                    }
                }
            }
        }

        if (callbackSpecs == null) {
            return;
        }

        for (int i = 0; i < callbackSpecs.size(); i++) {
            final ModeCallback callback = callbackSpecs.keyAt(i);
            final ArraySet<String> reportedPackageNames = callbackSpecs.valueAt(i);
            if (reportedPackageNames == null) {
                mHandler.sendMessage(PooledLambda.obtainMessage(
                        AppOpsService::notifyOpChanged,
                        this, callback, code, uid, (String) null));

            } else {
                final int reportedPackageCount = reportedPackageNames.size();
                for (int j = 0; j < reportedPackageCount; j++) {
                    final String reportedPackageName = reportedPackageNames.valueAt(j);
                    mHandler.sendMessage(PooledLambda.obtainMessage(
                            AppOpsService::notifyOpChanged,
                            this, callback, code, uid, reportedPackageName));
                }
            }
        }
    }

    @Override
    public void setMode(int code, int uid, String packageName, int mode) {
        if (Binder.getCallingPid() != Process.myPid()) {
            mContext.enforcePermission(android.Manifest.permission.MANAGE_APP_OPS_MODES,
                    Binder.getCallingPid(), Binder.getCallingUid(), null);
        }
        verifyIncomingOp(code);
        ArraySet<ModeCallback> repCbs = null;
        code = AppOpsManager.opToSwitch(code);
        synchronized (this) {
            UidState uidState = getUidStateLocked(uid, false);
            Op op = getOpLocked(code, uid, packageName, true);
            if (op != null) {
                if (op.mode != mode) {
                    op.mode = mode;
                    ArraySet<ModeCallback> cbs = mOpModeWatchers.get(code);
                    if (cbs != null) {
                        if (repCbs == null) {
                            repCbs = new ArraySet<>();
                        }
                        repCbs.addAll(cbs);
                    }
                    cbs = mPackageModeWatchers.get(packageName);
                    if (cbs != null) {
                        if (repCbs == null) {
                            repCbs = new ArraySet<>();
                        }
                        repCbs.addAll(cbs);
                    }
                    if (mode == AppOpsManager.opToDefaultMode(op.op)) {
                        // If going into the default mode, prune this op
                        // if there is nothing else interesting in it.
                        pruneOp(op, uid, packageName);
                    }
                    scheduleFastWriteLocked();
                }
            }
        }
        if (repCbs != null) {
            mHandler.sendMessage(PooledLambda.obtainMessage(
                    AppOpsService::notifyOpChanged,
                    this, repCbs, code, uid, packageName));
        }
    }

    private void notifyOpChanged(ArraySet<ModeCallback> callbacks, int code,
            int uid, String packageName) {
        for (int i = 0; i < callbacks.size(); i++) {
            final ModeCallback callback = callbacks.valueAt(i);
            notifyOpChanged(callback, code, uid, packageName);
        }
    }

    private void notifyOpChanged(ModeCallback callback, int code,
            int uid, String packageName) {
        if (uid != UID_ANY && callback.mWatchingUid >= 0 && callback.mWatchingUid != uid) {
            return;
        }
        // There are components watching for mode changes such as window manager
        // and location manager which are in our process. The callbacks in these
        // components may require permissions our remote caller does not have.
        final long identity = Binder.clearCallingIdentity();
        try {
            callback.mCallback.opChanged(code, uid, packageName);
        } catch (RemoteException e) {
            /* ignore */
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static HashMap<ModeCallback, ArrayList<ChangeRec>> addCallbacks(
            HashMap<ModeCallback, ArrayList<ChangeRec>> callbacks,
            int op, int uid, String packageName, ArraySet<ModeCallback> cbs) {
        if (cbs == null) {
            return callbacks;
        }
        if (callbacks == null) {
            callbacks = new HashMap<>();
        }
        boolean duplicate = false;
        final int N = cbs.size();
        for (int i=0; i<N; i++) {
            ModeCallback cb = cbs.valueAt(i);
            ArrayList<ChangeRec> reports = callbacks.get(cb);
            if (reports == null) {
                reports = new ArrayList<>();
                callbacks.put(cb, reports);
            } else {
                final int reportCount = reports.size();
                for (int j = 0; j < reportCount; j++) {
                    ChangeRec report = reports.get(j);
                    if (report.op == op && report.pkg.equals(packageName)) {
                        duplicate = true;
                        break;
                    }
                }
            }
            if (!duplicate) {
                reports.add(new ChangeRec(op, uid, packageName));
            }
        }
        return callbacks;
    }

    static final class ChangeRec {
        final int op;
        final int uid;
        final String pkg;

        ChangeRec(int _op, int _uid, String _pkg) {
            op = _op;
            uid = _uid;
            pkg = _pkg;
        }
    }

    @Override
    public void resetAllModes(int reqUserId, String reqPackageName) {
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        mContext.enforcePermission(android.Manifest.permission.MANAGE_APP_OPS_MODES,
                callingPid, callingUid, null);
        reqUserId = ActivityManager.handleIncomingUser(callingPid, callingUid, reqUserId,
                true, true, "resetAllModes", null);

        int reqUid = -1;
        if (reqPackageName != null) {
            try {
                reqUid = AppGlobals.getPackageManager().getPackageUid(
                        reqPackageName, PackageManager.MATCH_UNINSTALLED_PACKAGES, reqUserId);
            } catch (RemoteException e) {
                /* ignore - local call */
            }
        }

        HashMap<ModeCallback, ArrayList<ChangeRec>> callbacks = null;
        synchronized (this) {
            boolean changed = false;
            for (int i = mUidStates.size() - 1; i >= 0; i--) {
                UidState uidState = mUidStates.valueAt(i);

                SparseIntArray opModes = uidState.opModes;
                if (opModes != null && (uidState.uid == reqUid || reqUid == -1)) {
                    final int uidOpCount = opModes.size();
                    for (int j = uidOpCount - 1; j >= 0; j--) {
                        final int code = opModes.keyAt(j);
                        if (AppOpsManager.opAllowsReset(code)) {
                            opModes.removeAt(j);
                            if (opModes.size() <= 0) {
                                uidState.opModes = null;
                            }
                            for (String packageName : getPackagesForUid(uidState.uid)) {
                                callbacks = addCallbacks(callbacks, code, uidState.uid, packageName,
                                        mOpModeWatchers.get(code));
                                callbacks = addCallbacks(callbacks, code, uidState.uid, packageName,
                                        mPackageModeWatchers.get(packageName));
                            }
                        }
                    }
                }

                if (uidState.pkgOps == null) {
                    continue;
                }

                if (reqUserId != UserHandle.USER_ALL
                        && reqUserId != UserHandle.getUserId(uidState.uid)) {
                    // Skip any ops for a different user
                    continue;
                }

                Map<String, Ops> packages = uidState.pkgOps;
                Iterator<Map.Entry<String, Ops>> it = packages.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Ops> ent = it.next();
                    String packageName = ent.getKey();
                    if (reqPackageName != null && !reqPackageName.equals(packageName)) {
                        // Skip any ops for a different package
                        continue;
                    }
                    Ops pkgOps = ent.getValue();
                    for (int j=pkgOps.size()-1; j>=0; j--) {
                        Op curOp = pkgOps.valueAt(j);
                        if (AppOpsManager.opAllowsReset(curOp.op)
                                && curOp.mode != AppOpsManager.opToDefaultMode(curOp.op)) {
                            curOp.mode = AppOpsManager.opToDefaultMode(curOp.op);
                            changed = true;
                            callbacks = addCallbacks(callbacks, curOp.op, curOp.uid, packageName,
                                    mOpModeWatchers.get(curOp.op));
                            callbacks = addCallbacks(callbacks, curOp.op, curOp.uid, packageName,
                                    mPackageModeWatchers.get(packageName));
                            if (!curOp.hasAnyTime()) {
                                pkgOps.removeAt(j);
                            }
                        }
                    }
                    if (pkgOps.size() == 0) {
                        it.remove();
                    }
                }
                if (uidState.isDefault()) {
                    mUidStates.remove(uidState.uid);
                }
            }

            if (changed) {
                scheduleFastWriteLocked();
            }
        }
        if (callbacks != null) {
            for (Map.Entry<ModeCallback, ArrayList<ChangeRec>> ent : callbacks.entrySet()) {
                ModeCallback cb = ent.getKey();
                ArrayList<ChangeRec> reports = ent.getValue();
                for (int i=0; i<reports.size(); i++) {
                    ChangeRec rep = reports.get(i);
                    mHandler.sendMessage(PooledLambda.obtainMessage(
                            AppOpsService::notifyOpChanged,
                            this, cb, rep.op, rep.uid, rep.pkg));
                }
            }
        }
    }

    @Override
    public void startWatchingMode(int op, String packageName, IAppOpsCallback callback) {
        int watchedUid = -1;
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.WATCH_APPOPS)
                != PackageManager.PERMISSION_GRANTED) {
            watchedUid = callingUid;
        }
        Preconditions.checkArgumentInRange(op, AppOpsManager.OP_NONE,
                AppOpsManager._NUM_OP - 1, "Invalid op code: " + op);
        if (callback == null) {
            return;
        }
        synchronized (this) {
            op = (op != AppOpsManager.OP_NONE) ? AppOpsManager.opToSwitch(op) : op;
            ModeCallback cb = mModeWatchers.get(callback.asBinder());
            if (cb == null) {
                cb = new ModeCallback(callback, watchedUid, callingUid, callingPid);
                mModeWatchers.put(callback.asBinder(), cb);
            }
            if (op != AppOpsManager.OP_NONE) {
                ArraySet<ModeCallback> cbs = mOpModeWatchers.get(op);
                if (cbs == null) {
                    cbs = new ArraySet<>();
                    mOpModeWatchers.put(op, cbs);
                }
                cbs.add(cb);
            }
            if (packageName != null) {
                ArraySet<ModeCallback> cbs = mPackageModeWatchers.get(packageName);
                if (cbs == null) {
                    cbs = new ArraySet<>();
                    mPackageModeWatchers.put(packageName, cbs);
                }
                cbs.add(cb);
            }
        }
    }

    @Override
    public void stopWatchingMode(IAppOpsCallback callback) {
        if (callback == null) {
            return;
        }
        synchronized (this) {
            ModeCallback cb = mModeWatchers.remove(callback.asBinder());
            if (cb != null) {
                cb.unlinkToDeath();
                for (int i=mOpModeWatchers.size()-1; i>=0; i--) {
                    ArraySet<ModeCallback> cbs = mOpModeWatchers.valueAt(i);
                    cbs.remove(cb);
                    if (cbs.size() <= 0) {
                        mOpModeWatchers.removeAt(i);
                    }
                }
                for (int i=mPackageModeWatchers.size()-1; i>=0; i--) {
                    ArraySet<ModeCallback> cbs = mPackageModeWatchers.valueAt(i);
                    cbs.remove(cb);
                    if (cbs.size() <= 0) {
                        mPackageModeWatchers.removeAt(i);
                    }
                }
            }
        }
    }

    @Override
    public IBinder getToken(IBinder clientToken) {
        synchronized (this) {
            ClientState cs = mClients.get(clientToken);
            if (cs == null) {
                cs = new ClientState(clientToken);
                mClients.put(clientToken, cs);
            }
            return cs;
        }
    }

    @Override
    public int checkOperation(int code, int uid, String packageName) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return AppOpsManager.MODE_IGNORED;
        }
        synchronized (this) {
            if (isOpRestrictedLocked(uid, code, resolvedPackageName)) {
                return AppOpsManager.MODE_IGNORED;
            }
            code = AppOpsManager.opToSwitch(code);
            UidState uidState = getUidStateLocked(uid, false);
            if (uidState != null && uidState.opModes != null
                    && uidState.opModes.indexOfKey(code) >= 0) {
                return uidState.opModes.get(code);
            }
            Op op = getOpLocked(code, uid, resolvedPackageName, false);
            if (op == null) {
                return AppOpsManager.opToDefaultMode(code);
            }
            return op.mode;
        }
    }

    @Override
    public int checkAudioOperation(int code, int usage, int uid, String packageName) {
        boolean suspended;
        try {
            suspended = isPackageSuspendedForUser(packageName, uid);
        } catch (IllegalArgumentException ex) {
            // Package not found.
            suspended = false;
        }

        if (suspended) {
            Slog.i(TAG, "Audio disabled for suspended package=" + packageName + " for uid=" + uid);
            return AppOpsManager.MODE_IGNORED;
        }

        synchronized (this) {
            final int mode = checkRestrictionLocked(code, usage, uid, packageName);
            if (mode != AppOpsManager.MODE_ALLOWED) {
                return mode;
            }
        }
        return checkOperation(code, uid, packageName);
    }

    private boolean isPackageSuspendedForUser(String pkg, int uid) {
        try {
            return AppGlobals.getPackageManager().isPackageSuspendedForUser(
                    pkg, UserHandle.getUserId(uid));
        } catch (RemoteException re) {
            throw new SecurityException("Could not talk to package manager service");
        }
    }

    private int checkRestrictionLocked(int code, int usage, int uid, String packageName) {
        final SparseArray<Restriction> usageRestrictions = mAudioRestrictions.get(code);
        if (usageRestrictions != null) {
            final Restriction r = usageRestrictions.get(usage);
            if (r != null && !r.exceptionPackages.contains(packageName)) {
                return r.mode;
            }
        }
        return AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void setAudioRestriction(int code, int usage, int uid, int mode,
            String[] exceptionPackages) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        mContext.enforcePermission(android.Manifest.permission.MANAGE_APP_OPS_MODES,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
        synchronized (this) {
            SparseArray<Restriction> usageRestrictions = mAudioRestrictions.get(code);
            if (usageRestrictions == null) {
                usageRestrictions = new SparseArray<Restriction>();
                mAudioRestrictions.put(code, usageRestrictions);
            }
            usageRestrictions.remove(usage);
            if (mode != AppOpsManager.MODE_ALLOWED) {
                final Restriction r = new Restriction();
                r.mode = mode;
                if (exceptionPackages != null) {
                    final int N = exceptionPackages.length;
                    r.exceptionPackages = new ArraySet<String>(N);
                    for (int i = 0; i < N; i++) {
                        final String pkg = exceptionPackages[i];
                        if (pkg != null) {
                            r.exceptionPackages.add(pkg.trim());
                        }
                    }
                }
                usageRestrictions.put(usage, r);
            }
        }

        mHandler.sendMessage(PooledLambda.obtainMessage(
                AppOpsService::notifyWatchersOfChange, this, code, UID_ANY));
    }

    @Override
    public int checkPackage(int uid, String packageName) {
        Preconditions.checkNotNull(packageName);
        synchronized (this) {
            Ops ops = getOpsRawLocked(uid, packageName, true /* edit */,
                    true /* uidMismatchExpected */);
            if (ops != null) {
                return AppOpsManager.MODE_ALLOWED;
            } else {
                return AppOpsManager.MODE_ERRORED;
            }
        }
    }

    @Override
    public int noteProxyOperation(int code, String proxyPackageName,
            int proxiedUid, String proxiedPackageName) {
        verifyIncomingOp(code);
        final int proxyUid = Binder.getCallingUid();
        String resolveProxyPackageName = resolvePackageName(proxyUid, proxyPackageName);
        if (resolveProxyPackageName == null) {
            return AppOpsManager.MODE_IGNORED;
        }
        final int proxyMode = noteOperationUnchecked(code, proxyUid,
                resolveProxyPackageName, -1, null);
        if (proxyMode != AppOpsManager.MODE_ALLOWED || Binder.getCallingUid() == proxiedUid) {
            return proxyMode;
        }
        String resolveProxiedPackageName = resolvePackageName(proxiedUid, proxiedPackageName);
        if (resolveProxiedPackageName == null) {
            return AppOpsManager.MODE_IGNORED;
        }
        return noteOperationUnchecked(code, proxiedUid, resolveProxiedPackageName,
                proxyMode, resolveProxyPackageName);
    }

    @Override
    public int noteOperation(int code, int uid, String packageName) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return AppOpsManager.MODE_IGNORED;
        }
        return noteOperationUnchecked(code, uid, resolvedPackageName, 0, null);
    }

    private int noteOperationUnchecked(int code, int uid, String packageName,
            int proxyUid, String proxyPackageName) {
        synchronized (this) {
            final Ops ops = getOpsRawLocked(uid, packageName, true /* edit */,
                    false /* uidMismatchExpected */);
            if (ops == null) {
                if (DEBUG) Slog.d(TAG, "noteOperation: no op for code " + code + " uid " + uid
                        + " package " + packageName);
                return AppOpsManager.MODE_ERRORED;
            }
            final Op op = getOpLocked(ops, code, true);
            if (isOpRestrictedLocked(uid, code, packageName)) {
                return AppOpsManager.MODE_IGNORED;
            }
            final UidState uidState = ops.uidState;
            if (op.duration == -1) {
                Slog.w(TAG, "Noting op not finished: uid " + uid + " pkg " + packageName
                        + " code " + code + " time=" + op.time[uidState.state]
                        + " duration=" + op.duration);
            }
            op.duration = 0;
            final int switchCode = AppOpsManager.opToSwitch(code);
            // If there is a non-default per UID policy (we set UID op mode only if
            // non-default) it takes over, otherwise use the per package policy.
            if (uidState.opModes != null && uidState.opModes.indexOfKey(switchCode) >= 0) {
                final int uidMode = uidState.opModes.get(switchCode);
                if (uidMode != AppOpsManager.MODE_ALLOWED) {
                    if (DEBUG) Slog.d(TAG, "noteOperation: reject #" + op.mode + " for code "
                            + switchCode + " (" + code + ") uid " + uid + " package "
                            + packageName);
                    op.rejectTime[uidState.state] = System.currentTimeMillis();
                    return uidMode;
                }
            } else {
                final Op switchOp = switchCode != code ? getOpLocked(ops, switchCode, true) : op;
                if (switchOp.mode != AppOpsManager.MODE_ALLOWED) {
                    if (DEBUG) Slog.d(TAG, "noteOperation: reject #" + op.mode + " for code "
                            + switchCode + " (" + code + ") uid " + uid + " package "
                            + packageName);
                    op.rejectTime[uidState.state] = System.currentTimeMillis();
                    return switchOp.mode;
                }
            }
            if (DEBUG) Slog.d(TAG, "noteOperation: allowing code " + code + " uid " + uid
                    + " package " + packageName);
            op.time[uidState.state] = System.currentTimeMillis();
            op.rejectTime[uidState.state] = 0;
            op.proxyUid = proxyUid;
            op.proxyPackageName = proxyPackageName;
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @Override
    public void startWatchingActive(int[] ops, IAppOpsActiveCallback callback) {
        int watchedUid = -1;
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.WATCH_APPOPS)
                != PackageManager.PERMISSION_GRANTED) {
            watchedUid = callingUid;
        }
        if (ops != null) {
            Preconditions.checkArrayElementsInRange(ops, 0,
                    AppOpsManager._NUM_OP - 1, "Invalid op code in: " + Arrays.toString(ops));
        }
        if (callback == null) {
            return;
        }
        synchronized (this) {
            SparseArray<ActiveCallback> callbacks = mActiveWatchers.get(callback.asBinder());
            if (callbacks == null) {
                callbacks = new SparseArray<>();
                mActiveWatchers.put(callback.asBinder(), callbacks);
            }
            final ActiveCallback activeCallback = new ActiveCallback(callback, watchedUid,
                    callingUid, callingPid);
            for (int op : ops) {
                callbacks.put(op, activeCallback);
            }
        }
    }

    @Override
    public void stopWatchingActive(IAppOpsActiveCallback callback) {
        if (callback == null) {
            return;
        }
        synchronized (this) {
            final SparseArray<ActiveCallback> activeCallbacks =
                    mActiveWatchers.remove(callback.asBinder());
            if (activeCallbacks == null) {
                return;
            }
            final int callbackCount = activeCallbacks.size();
            for (int i = 0; i < callbackCount; i++) {
                // Apps ops are mapped to a singleton
                if (i == 0) {
                    activeCallbacks.valueAt(i).destroy();
                }
            }
        }
    }

    @Override
    public int startOperation(IBinder token, int code, int uid, String packageName,
            boolean startIfModeDefault) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return  AppOpsManager.MODE_IGNORED;
        }
        ClientState client = (ClientState)token;
        synchronized (this) {
            final Ops ops = getOpsRawLocked(uid, resolvedPackageName, true /* edit */,
                    false /* uidMismatchExpected */);
            if (ops == null) {
                if (DEBUG) Slog.d(TAG, "startOperation: no op for code " + code + " uid " + uid
                        + " package " + resolvedPackageName);
                return AppOpsManager.MODE_ERRORED;
            }
            final Op op = getOpLocked(ops, code, true);
            if (isOpRestrictedLocked(uid, code, resolvedPackageName)) {
                return AppOpsManager.MODE_IGNORED;
            }
            final int switchCode = AppOpsManager.opToSwitch(code);
            final UidState uidState = ops.uidState;
            // If there is a non-default per UID policy (we set UID op mode only if
            // non-default) it takes over, otherwise use the per package policy.
            if (uidState.opModes != null && uidState.opModes.indexOfKey(switchCode) >= 0) {
                final int uidMode = uidState.opModes.get(switchCode);
                if (uidMode != AppOpsManager.MODE_ALLOWED
                        && (!startIfModeDefault || uidMode != AppOpsManager.MODE_DEFAULT)) {
                    if (DEBUG) Slog.d(TAG, "noteOperation: reject #" + op.mode + " for code "
                            + switchCode + " (" + code + ") uid " + uid + " package "
                            + resolvedPackageName);
                    op.rejectTime[uidState.state] = System.currentTimeMillis();
                    return uidMode;
                }
            } else {
                final Op switchOp = switchCode != code ? getOpLocked(ops, switchCode, true) : op;
                if (switchOp.mode != AppOpsManager.MODE_ALLOWED
                        && (!startIfModeDefault || switchOp.mode != AppOpsManager.MODE_DEFAULT)) {
                    if (DEBUG) Slog.d(TAG, "startOperation: reject #" + op.mode + " for code "
                            + switchCode + " (" + code + ") uid " + uid + " package "
                            + resolvedPackageName);
                    op.rejectTime[uidState.state] = System.currentTimeMillis();
                    return switchOp.mode;
                }
            }
            if (DEBUG) Slog.d(TAG, "startOperation: allowing code " + code + " uid " + uid
                    + " package " + resolvedPackageName);
            if (op.startNesting == 0) {
                op.startRealtime = SystemClock.elapsedRealtime();
                op.time[uidState.state] = System.currentTimeMillis();
                op.rejectTime[uidState.state] = 0;
                op.duration = -1;
                scheduleOpActiveChangedIfNeededLocked(code, uid, packageName, true);
            }
            op.startNesting++;
            uidState.startNesting++;
            if (client.mStartedOps != null) {
                client.mStartedOps.add(op);
            }
        }

        return AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void finishOperation(IBinder token, int code, int uid, String packageName) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return;
        }
        if (!(token instanceof ClientState)) {
            return;
        }
        ClientState client = (ClientState) token;
        synchronized (this) {
            Op op = getOpLocked(code, uid, resolvedPackageName, true);
            if (op == null) {
                return;
            }
            if (!client.mStartedOps.remove(op)) {
                // We finish ops when packages get removed to guarantee no dangling
                // started ops. However, some part of the system may asynchronously
                // finish ops for an already gone package. Hence, finishing an op
                // for a non existing package is fine and we don't log as a wtf.
                final long identity = Binder.clearCallingIdentity();
                try {
                    if (LocalServices.getService(PackageManagerInternal.class).getPackageUid(
                            resolvedPackageName, 0, UserHandle.getUserId(uid)) < 0) {
                        Slog.i(TAG, "Finishing op=" + AppOpsManager.opToName(code)
                                + " for non-existing package=" + resolvedPackageName
                                + " in uid=" + uid);
                        return;
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
                Slog.wtf(TAG, "Operation not started: uid=" + op.uid + " pkg="
                        + op.packageName + " op=" + AppOpsManager.opToName(op.op));
                return;
            }
            finishOperationLocked(op, /*finishNested*/ false);
            if (op.startNesting <= 0) {
                scheduleOpActiveChangedIfNeededLocked(code, uid, packageName, false);
            }
        }
    }

    private void scheduleOpActiveChangedIfNeededLocked(int code, int uid, String packageName,
            boolean active) {
        ArraySet<ActiveCallback> dispatchedCallbacks = null;
        final int callbackListCount = mActiveWatchers.size();
        for (int i = 0; i < callbackListCount; i++) {
            final SparseArray<ActiveCallback> callbacks = mActiveWatchers.valueAt(i);
            ActiveCallback callback = callbacks.get(code);
            if (callback != null) {
                if (callback.mWatchingUid >= 0 && callback.mWatchingUid != uid) {
                    continue;
                }
                if (dispatchedCallbacks == null) {
                    dispatchedCallbacks = new ArraySet<>();
                }
                dispatchedCallbacks.add(callback);
            }
        }
        if (dispatchedCallbacks == null) {
            return;
        }
        mHandler.sendMessage(PooledLambda.obtainMessage(
                AppOpsService::notifyOpActiveChanged,
                this, dispatchedCallbacks, code, uid, packageName, active));
    }

    private void notifyOpActiveChanged(ArraySet<ActiveCallback> callbacks,
            int code, int uid, String packageName, boolean active) {
        // There are components watching for mode changes such as window manager
        // and location manager which are in our process. The callbacks in these
        // components may require permissions our remote caller does not have.
        final long identity = Binder.clearCallingIdentity();
        try {
            final int callbackCount = callbacks.size();
            for (int i = 0; i < callbackCount; i++) {
                final ActiveCallback callback = callbacks.valueAt(i);
                try {
                    callback.mCallback.opActiveChanged(code, uid, packageName, active);
                } catch (RemoteException e) {
                    /* do nothing */
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public int permissionToOpCode(String permission) {
        if (permission == null) {
            return AppOpsManager.OP_NONE;
        }
        return AppOpsManager.permissionToOpCode(permission);
    }

    void finishOperationLocked(Op op, boolean finishNested) {
        if (op.startNesting <= 1 || finishNested) {
            if (op.startNesting == 1 || finishNested) {
                op.duration = (int)(SystemClock.elapsedRealtime() - op.startRealtime);
                op.time[op.uidState.state] = System.currentTimeMillis();
            } else {
                Slog.w(TAG, "Finishing op nesting under-run: uid " + op.uid + " pkg "
                        + op.packageName + " code " + op.op + " time=" + op.time
                        + " duration=" + op.duration + " nesting=" + op.startNesting);
            }
            if (op.startNesting >= 1) {
                op.uidState.startNesting -= op.startNesting;
            }
            op.startNesting = 0;
        } else {
            op.startNesting--;
            op.uidState.startNesting--;
        }
    }

    private void verifyIncomingUid(int uid) {
        if (uid == Binder.getCallingUid()) {
            return;
        }
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
    }

    private void verifyIncomingOp(int op) {
        if (op >= 0 && op < AppOpsManager._NUM_OP) {
            return;
        }
        throw new IllegalArgumentException("Bad operation #" + op);
    }

    private UidState getUidStateLocked(int uid, boolean edit) {
        UidState uidState = mUidStates.get(uid);
        if (uidState == null) {
            if (!edit) {
                return null;
            }
            uidState = new UidState(uid);
            mUidStates.put(uid, uidState);
        }
        return uidState;
    }

    private Ops getOpsRawLocked(int uid, String packageName, boolean edit,
            boolean uidMismatchExpected) {
        UidState uidState = getUidStateLocked(uid, edit);
        if (uidState == null) {
            return null;
        }

        if (uidState.pkgOps == null) {
            if (!edit) {
                return null;
            }
            uidState.pkgOps = new ArrayMap<>();
        }

        Ops ops = uidState.pkgOps.get(packageName);
        if (ops == null) {
            if (!edit) {
                return null;
            }
            boolean isPrivileged = false;
            // This is the first time we have seen this package name under this uid,
            // so let's make sure it is valid.
            if (uid != 0) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    int pkgUid = -1;
                    try {
                        ApplicationInfo appInfo = ActivityThread.getPackageManager()
                                .getApplicationInfo(packageName,
                                        PackageManager.MATCH_DEBUG_TRIAGED_MISSING,
                                        UserHandle.getUserId(uid));
                        if (appInfo != null) {
                            pkgUid = appInfo.uid;
                            isPrivileged = (appInfo.privateFlags
                                    & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0;
                        } else {
                            pkgUid = resolveUid(packageName);
                            if (pkgUid >= 0) {
                                isPrivileged = false;
                            }
                        }
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Could not contact PackageManager", e);
                    }
                    if (pkgUid != uid) {
                        // Oops!  The package name is not valid for the uid they are calling
                        // under.  Abort.
                        if (!uidMismatchExpected) {
                            RuntimeException ex = new RuntimeException("here");
                            ex.fillInStackTrace();
                            Slog.w(TAG, "Bad call: specified package " + packageName
                                    + " under uid " + uid + " but it is really " + pkgUid, ex);
                        }
                        return null;
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
            ops = new Ops(packageName, uidState, isPrivileged);
            uidState.pkgOps.put(packageName, ops);
        }
        return ops;
    }

    private void scheduleWriteLocked() {
        if (!mWriteScheduled) {
            mWriteScheduled = true;
            mHandler.postDelayed(mWriteRunner, WRITE_DELAY);
        }
    }

    private void scheduleFastWriteLocked() {
        if (!mFastWriteScheduled) {
            mWriteScheduled = true;
            mFastWriteScheduled = true;
            mHandler.removeCallbacks(mWriteRunner);
            mHandler.postDelayed(mWriteRunner, 10*1000);
        }
    }

    private Op getOpLocked(int code, int uid, String packageName, boolean edit) {
        Ops ops = getOpsRawLocked(uid, packageName, edit,
                false /* uidMismatchExpected */);
        if (ops == null) {
            return null;
        }
        return getOpLocked(ops, code, edit);
    }

    private Op getOpLocked(Ops ops, int code, boolean edit) {
        Op op = ops.get(code);
        if (op == null) {
            if (!edit) {
                return null;
            }
            op = new Op(ops.uidState, ops.packageName, code);
            ops.put(code, op);
        }
        if (edit) {
            scheduleWriteLocked();
        }
        return op;
    }

    private boolean isOpRestrictedLocked(int uid, int code, String packageName) {
        int userHandle = UserHandle.getUserId(uid);
        final int restrictionSetCount = mOpUserRestrictions.size();

        for (int i = 0; i < restrictionSetCount; i++) {
            // For each client, check that the given op is not restricted, or that the given
            // package is exempt from the restriction.
            ClientRestrictionState restrictionState = mOpUserRestrictions.valueAt(i);
            if (restrictionState.hasRestriction(code, packageName, userHandle)) {
                if (AppOpsManager.opAllowSystemBypassRestriction(code)) {
                    // If we are the system, bypass user restrictions for certain codes
                    synchronized (this) {
                        Ops ops = getOpsRawLocked(uid, packageName, true /* edit */,
                                false /* uidMismatchExpected */);
                        if ((ops != null) && ops.isPrivileged) {
                            return false;
                        }
                    }
                }
                return true;
            }
        }
        return false;
    }

    void readState() {
        int oldVersion = NO_VERSION;
        synchronized (mFile) {
            synchronized (this) {
                FileInputStream stream;
                try {
                    stream = mFile.openRead();
                } catch (FileNotFoundException e) {
                    Slog.i(TAG, "No existing app ops " + mFile.getBaseFile() + "; starting empty");
                    return;
                }
                boolean success = false;
                mUidStates.clear();
                try {
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(stream, StandardCharsets.UTF_8.name());
                    int type;
                    while ((type = parser.next()) != XmlPullParser.START_TAG
                            && type != XmlPullParser.END_DOCUMENT) {
                        ;
                    }

                    if (type != XmlPullParser.START_TAG) {
                        throw new IllegalStateException("no start tag found");
                    }

                    final String versionString = parser.getAttributeValue(null, "v");
                    if (versionString != null) {
                        oldVersion = Integer.parseInt(versionString);
                    }

                    int outerDepth = parser.getDepth();
                    while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                            && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                        if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                            continue;
                        }

                        String tagName = parser.getName();
                        if (tagName.equals("pkg")) {
                            readPackage(parser);
                        } else if (tagName.equals("uid")) {
                            readUidOps(parser);
                        } else {
                            Slog.w(TAG, "Unknown element under <app-ops>: "
                                    + parser.getName());
                            XmlUtils.skipCurrentTag(parser);
                        }
                    }
                    success = true;
                } catch (IllegalStateException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (NullPointerException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (NumberFormatException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (XmlPullParserException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (IOException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (IndexOutOfBoundsException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } finally {
                    if (!success) {
                        mUidStates.clear();
                    }
                    try {
                        stream.close();
                    } catch (IOException e) {
                    }
                }
            }
        }
        synchronized (this) {
            upgradeLocked(oldVersion);
        }
    }

    private void upgradeRunAnyInBackgroundLocked() {
        for (int i = 0; i < mUidStates.size(); i++) {
            final UidState uidState = mUidStates.valueAt(i);
            if (uidState == null) {
                continue;
            }
            if (uidState.opModes != null) {
                final int idx = uidState.opModes.indexOfKey(AppOpsManager.OP_RUN_IN_BACKGROUND);
                if (idx >= 0) {
                    uidState.opModes.put(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,
                            uidState.opModes.valueAt(idx));
                }
            }
            if (uidState.pkgOps == null) {
                continue;
            }
            for (int j = 0; j < uidState.pkgOps.size(); j++) {
                Ops ops = uidState.pkgOps.valueAt(j);
                if (ops != null) {
                    final Op op = ops.get(AppOpsManager.OP_RUN_IN_BACKGROUND);
                    if (op != null && op.mode != AppOpsManager.opToDefaultMode(op.op)) {
                        final Op copy = new Op(op.uidState, op.packageName,
                                AppOpsManager.OP_RUN_ANY_IN_BACKGROUND);
                        copy.mode = op.mode;
                        ops.put(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND, copy);
                    }
                }
            }
        }
    }

    private void upgradeLocked(int oldVersion) {
        if (oldVersion >= CURRENT_VERSION) {
            return;
        }
        Slog.d(TAG, "Upgrading app-ops xml from version " + oldVersion + " to " + CURRENT_VERSION);
        switch (oldVersion) {
            case NO_VERSION:
                upgradeRunAnyInBackgroundLocked();
                // fall through
            case 1:
                // for future upgrades
        }
        scheduleFastWriteLocked();
    }

    void readUidOps(XmlPullParser parser) throws NumberFormatException,
            XmlPullParserException, IOException {
        final int uid = Integer.parseInt(parser.getAttributeValue(null, "n"));
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("op")) {
                final int code = Integer.parseInt(parser.getAttributeValue(null, "n"));
                final int mode = Integer.parseInt(parser.getAttributeValue(null, "m"));
                UidState uidState = getUidStateLocked(uid, true);
                if (uidState.opModes == null) {
                    uidState.opModes = new SparseIntArray();
                }
                uidState.opModes.put(code, mode);
            } else {
                Slog.w(TAG, "Unknown element under <uid-ops>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    void readPackage(XmlPullParser parser) throws NumberFormatException,
            XmlPullParserException, IOException {
        String pkgName = parser.getAttributeValue(null, "n");
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("uid")) {
                readUid(parser, pkgName);
            } else {
                Slog.w(TAG, "Unknown element under <pkg>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    void readUid(XmlPullParser parser, String pkgName) throws NumberFormatException,
            XmlPullParserException, IOException {
        int uid = Integer.parseInt(parser.getAttributeValue(null, "n"));
        String isPrivilegedString = parser.getAttributeValue(null, "p");
        boolean isPrivileged = false;
        if (isPrivilegedString == null) {
            try {
                IPackageManager packageManager = ActivityThread.getPackageManager();
                if (packageManager != null) {
                    ApplicationInfo appInfo = ActivityThread.getPackageManager()
                            .getApplicationInfo(pkgName, 0, UserHandle.getUserId(uid));
                    if (appInfo != null) {
                        isPrivileged = (appInfo.privateFlags
                                & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0;
                    }
                } else {
                    // Could not load data, don't add to cache so it will be loaded later.
                    return;
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Could not contact PackageManager", e);
            }
        } else {
            isPrivileged = Boolean.parseBoolean(isPrivilegedString);
        }
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("op")) {
                UidState uidState = getUidStateLocked(uid, true);
                if (uidState.pkgOps == null) {
                    uidState.pkgOps = new ArrayMap<>();
                }

                Op op = new Op(uidState, pkgName,
                        Integer.parseInt(parser.getAttributeValue(null, "n")));

                for (int i = parser.getAttributeCount()-1; i >= 0; i--) {
                    final String name = parser.getAttributeName(i);
                    final String value = parser.getAttributeValue(i);
                    switch (name) {
                        case "m":
                            op.mode = Integer.parseInt(value);
                            break;
                        case "d":
                            op.duration = Integer.parseInt(value);
                            break;
                        case "pu":
                            op.proxyUid = Integer.parseInt(value);
                            break;
                        case "pp":
                            op.proxyPackageName = value;
                            break;
                        case "tp":
                            op.time[AppOpsManager.UID_STATE_PERSISTENT] = Long.parseLong(value);
                            break;
                        case "tt":
                            op.time[AppOpsManager.UID_STATE_TOP] = Long.parseLong(value);
                            break;
                        case "tfs":
                            op.time[AppOpsManager.UID_STATE_FOREGROUND_SERVICE]
                                    = Long.parseLong(value);
                            break;
                        case "tf":
                            op.time[AppOpsManager.UID_STATE_FOREGROUND] = Long.parseLong(value);
                            break;
                        case "tb":
                            op.time[AppOpsManager.UID_STATE_BACKGROUND] = Long.parseLong(value);
                            break;
                        case "tc":
                            op.time[AppOpsManager.UID_STATE_CACHED] = Long.parseLong(value);
                            break;
                        case "rp":
                            op.rejectTime[AppOpsManager.UID_STATE_PERSISTENT]
                                    = Long.parseLong(value);
                            break;
                        case "rt":
                            op.rejectTime[AppOpsManager.UID_STATE_TOP] = Long.parseLong(value);
                            break;
                        case "rfs":
                            op.rejectTime[AppOpsManager.UID_STATE_FOREGROUND_SERVICE]
                                    = Long.parseLong(value);
                            break;
                        case "rf":
                            op.rejectTime[AppOpsManager.UID_STATE_FOREGROUND]
                                    = Long.parseLong(value);
                            break;
                        case "rb":
                            op.rejectTime[AppOpsManager.UID_STATE_BACKGROUND]
                                    = Long.parseLong(value);
                            break;
                        case "rc":
                            op.rejectTime[AppOpsManager.UID_STATE_CACHED]
                                    = Long.parseLong(value);
                            break;
                        case "t":
                            // Backwards compat.
                            op.time[AppOpsManager.UID_STATE_TOP] = Long.parseLong(value);
                            break;
                        case "r":
                            // Backwards compat.
                            op.rejectTime[AppOpsManager.UID_STATE_TOP] = Long.parseLong(value);
                            break;
                        default:
                            Slog.w(TAG, "Unknown attribute in 'op' tag: " + name);
                            break;
                    }
                }

                Ops ops = uidState.pkgOps.get(pkgName);
                if (ops == null) {
                    ops = new Ops(pkgName, uidState, isPrivileged);
                    uidState.pkgOps.put(pkgName, ops);
                }
                ops.put(op.op, op);
            } else {
                Slog.w(TAG, "Unknown element under <pkg>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    void writeState() {
        synchronized (mFile) {
            FileOutputStream stream;
            try {
                stream = mFile.startWrite();
            } catch (IOException e) {
                Slog.w(TAG, "Failed to write state: " + e);
                return;
            }

            List<AppOpsManager.PackageOps> allOps = getPackagesForOps(null);

            try {
                XmlSerializer out = new FastXmlSerializer();
                out.setOutput(stream, StandardCharsets.UTF_8.name());
                out.startDocument(null, true);
                out.startTag(null, "app-ops");
                out.attribute(null, "v", String.valueOf(CURRENT_VERSION));

                final int uidStateCount = mUidStates.size();
                for (int i = 0; i < uidStateCount; i++) {
                    UidState uidState = mUidStates.valueAt(i);
                    if (uidState.opModes != null && uidState.opModes.size() > 0) {
                        out.startTag(null, "uid");
                        out.attribute(null, "n", Integer.toString(uidState.uid));
                        SparseIntArray uidOpModes = uidState.opModes;
                        final int opCount = uidOpModes.size();
                        for (int j = 0; j < opCount; j++) {
                            final int op = uidOpModes.keyAt(j);
                            final int mode = uidOpModes.valueAt(j);
                            out.startTag(null, "op");
                            out.attribute(null, "n", Integer.toString(op));
                            out.attribute(null, "m", Integer.toString(mode));
                            out.endTag(null, "op");
                        }
                        out.endTag(null, "uid");
                    }
                }

                if (allOps != null) {
                    String lastPkg = null;
                    for (int i=0; i<allOps.size(); i++) {
                        AppOpsManager.PackageOps pkg = allOps.get(i);
                        if (!pkg.getPackageName().equals(lastPkg)) {
                            if (lastPkg != null) {
                                out.endTag(null, "pkg");
                            }
                            lastPkg = pkg.getPackageName();
                            out.startTag(null, "pkg");
                            out.attribute(null, "n", lastPkg);
                        }
                        out.startTag(null, "uid");
                        out.attribute(null, "n", Integer.toString(pkg.getUid()));
                        synchronized (this) {
                            Ops ops = getOpsRawLocked(pkg.getUid(), pkg.getPackageName(),
                                    false /* edit */, false /* uidMismatchExpected */);
                            // Should always be present as the list of PackageOps is generated
                            // from Ops.
                            if (ops != null) {
                                out.attribute(null, "p", Boolean.toString(ops.isPrivileged));
                            } else {
                                out.attribute(null, "p", Boolean.toString(false));
                            }
                        }
                        List<AppOpsManager.OpEntry> ops = pkg.getOps();
                        for (int j=0; j<ops.size(); j++) {
                            AppOpsManager.OpEntry op = ops.get(j);
                            out.startTag(null, "op");
                            out.attribute(null, "n", Integer.toString(op.getOp()));
                            if (op.getMode() != AppOpsManager.opToDefaultMode(op.getOp())) {
                                out.attribute(null, "m", Integer.toString(op.getMode()));
                            }
                            for (int k = 0; k < _NUM_UID_STATE; k++) {
                                final long time = op.getTimeFor(k);
                                if (time != 0) {
                                    out.attribute(null, UID_STATE_TIME_ATTRS[k],
                                            Long.toString(time));
                                }
                                final long rejectTime = op.getRejectTimeFor(k);
                                if (rejectTime != 0) {
                                    out.attribute(null, UID_STATE_REJECT_ATTRS[k],
                                            Long.toString(rejectTime));
                                }
                            }
                            int dur = op.getDuration();
                            if (dur != 0) {
                                out.attribute(null, "d", Integer.toString(dur));
                            }
                            int proxyUid = op.getProxyUid();
                            if (proxyUid != -1) {
                                out.attribute(null, "pu", Integer.toString(proxyUid));
                            }
                            String proxyPackageName = op.getProxyPackageName();
                            if (proxyPackageName != null) {
                                out.attribute(null, "pp", proxyPackageName);
                            }
                            out.endTag(null, "op");
                        }
                        out.endTag(null, "uid");
                    }
                    if (lastPkg != null) {
                        out.endTag(null, "pkg");
                    }
                }

                out.endTag(null, "app-ops");
                out.endDocument();
                mFile.finishWrite(stream);
            } catch (IOException e) {
                Slog.w(TAG, "Failed to write state, restoring backup.", e);
                mFile.failWrite(stream);
            }
        }
    }

    static class Shell extends ShellCommand {
        final IAppOpsService mInterface;
        final AppOpsService mInternal;

        int userId = UserHandle.USER_SYSTEM;
        String packageName;
        String opStr;
        String modeStr;
        int op;
        int mode;
        int packageUid;
        int nonpackageUid;
        final static Binder sBinder = new Binder();
        IBinder mToken;

        Shell(IAppOpsService iface, AppOpsService internal) {
            mInterface = iface;
            mInternal = internal;
            try {
                mToken = mInterface.getToken(sBinder);
            } catch (RemoteException e) {
            }
        }

        @Override
        public int onCommand(String cmd) {
            return onShellCommand(this, cmd);
        }

        @Override
        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            dumpCommandHelp(pw);
        }

        private int strOpToOp(String op, PrintWriter err) {
            try {
                return AppOpsManager.strOpToOp(op);
            } catch (IllegalArgumentException e) {
            }
            try {
                return Integer.parseInt(op);
            } catch (NumberFormatException e) {
            }
            try {
                return AppOpsManager.strDebugOpToOp(op);
            } catch (IllegalArgumentException e) {
                err.println("Error: " + e.getMessage());
                return -1;
            }
        }

        int strModeToMode(String modeStr, PrintWriter err) {
            for (int i = MODE_NAMES.length - 1; i >= 0; i--) {
                if (MODE_NAMES[i].equals(modeStr)) {
                    return i;
                }
            }
            try {
                return Integer.parseInt(modeStr);
            } catch (NumberFormatException e) {
            }
            err.println("Error: Mode " + modeStr + " is not valid");
            return -1;
        }

        int parseUserOpMode(int defMode, PrintWriter err) throws RemoteException {
            userId = UserHandle.USER_CURRENT;
            opStr = null;
            modeStr = null;
            for (String argument; (argument = getNextArg()) != null;) {
                if ("--user".equals(argument)) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    if (opStr == null) {
                        opStr = argument;
                    } else if (modeStr == null) {
                        modeStr = argument;
                        break;
                    }
                }
            }
            if (opStr == null) {
                err.println("Error: Operation not specified.");
                return -1;
            }
            op = strOpToOp(opStr, err);
            if (op < 0) {
                return -1;
            }
            if (modeStr != null) {
                if ((mode=strModeToMode(modeStr, err)) < 0) {
                    return -1;
                }
            } else {
                mode = defMode;
            }
            return 0;
        }

        int parseUserPackageOp(boolean reqOp, PrintWriter err) throws RemoteException {
            userId = UserHandle.USER_CURRENT;
            packageName = null;
            opStr = null;
            for (String argument; (argument = getNextArg()) != null;) {
                if ("--user".equals(argument)) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    if (packageName == null) {
                        packageName = argument;
                    } else if (opStr == null) {
                        opStr = argument;
                        break;
                    }
                }
            }
            if (packageName == null) {
                err.println("Error: Package name not specified.");
                return -1;
            } else if (opStr == null && reqOp) {
                err.println("Error: Operation not specified.");
                return -1;
            }
            if (opStr != null) {
                op = strOpToOp(opStr, err);
                if (op < 0) {
                    return -1;
                }
            } else {
                op = AppOpsManager.OP_NONE;
            }
            if (userId == UserHandle.USER_CURRENT) {
                userId = ActivityManager.getCurrentUser();
            }
            nonpackageUid = -1;
            try {
                nonpackageUid = Integer.parseInt(packageName);
            } catch (NumberFormatException e) {
            }
            if (nonpackageUid == -1 && packageName.length() > 1 && packageName.charAt(0) == 'u'
                    && packageName.indexOf('.') < 0) {
                int i = 1;
                while (i < packageName.length() && packageName.charAt(i) >= '0'
                        && packageName.charAt(i) <= '9') {
                    i++;
                }
                if (i > 1 && i < packageName.length()) {
                    String userStr = packageName.substring(1, i);
                    try {
                        int user = Integer.parseInt(userStr);
                        char type = packageName.charAt(i);
                        i++;
                        int startTypeVal = i;
                        while (i < packageName.length() && packageName.charAt(i) >= '0'
                                && packageName.charAt(i) <= '9') {
                            i++;
                        }
                        if (i > startTypeVal) {
                            String typeValStr = packageName.substring(startTypeVal, i);
                            try {
                                int typeVal = Integer.parseInt(typeValStr);
                                if (type == 'a') {
                                    nonpackageUid = UserHandle.getUid(user,
                                            typeVal + Process.FIRST_APPLICATION_UID);
                                } else if (type == 's') {
                                    nonpackageUid = UserHandle.getUid(user, typeVal);
                                }
                            } catch (NumberFormatException e) {
                            }
                        }
                    } catch (NumberFormatException e) {
                    }
                }
            }
            if (nonpackageUid != -1) {
                packageName = null;
            } else {
                packageUid = resolveUid(packageName);
                if (packageUid < 0) {
                    packageUid = AppGlobals.getPackageManager().getPackageUid(packageName,
                            PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
                }
                if (packageUid < 0) {
                    err.println("Error: No UID for " + packageName + " in user " + userId);
                    return -1;
                }
            }
            return 0;
        }
    }

    @Override public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) {
        (new Shell(this, this)).exec(this, in, out, err, args, callback, resultReceiver);
    }

    static void dumpCommandHelp(PrintWriter pw) {
        pw.println("AppOps service (appops) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  start [--user <USER_ID>] <PACKAGE | UID> <OP> ");
        pw.println("    Starts a given operation for a particular application.");
        pw.println("  stop [--user <USER_ID>] <PACKAGE | UID> <OP> ");
        pw.println("    Stops a given operation for a particular application.");
        pw.println("  set [--user <USER_ID>] <PACKAGE | UID> <OP> <MODE>");
        pw.println("    Set the mode for a particular application and operation.");
        pw.println("  get [--user <USER_ID>] <PACKAGE | UID> [<OP>]");
        pw.println("    Return the mode for a particular application and optional operation.");
        pw.println("  query-op [--user <USER_ID>] <OP> [<MODE>]");
        pw.println("    Print all packages that currently have the given op in the given mode.");
        pw.println("  reset [--user <USER_ID>] [<PACKAGE>]");
        pw.println("    Reset the given application or all applications to default modes.");
        pw.println("  write-settings");
        pw.println("    Immediately write pending changes to storage.");
        pw.println("  read-settings");
        pw.println("    Read the last written settings, replacing current state in RAM.");
        pw.println("  options:");
        pw.println("    <PACKAGE> an Android package name.");
        pw.println("    <OP>      an AppOps operation.");
        pw.println("    <MODE>    one of allow, ignore, deny, or default");
        pw.println("    <USER_ID> the user id under which the package is installed. If --user is not");
        pw.println("              specified, the current user is assumed.");
    }

    static int onShellCommand(Shell shell, String cmd) {
        if (cmd == null) {
            return shell.handleDefaultCommands(cmd);
        }
        PrintWriter pw = shell.getOutPrintWriter();
        PrintWriter err = shell.getErrPrintWriter();
        try {
            switch (cmd) {
                case "set": {
                    int res = shell.parseUserPackageOp(true, err);
                    if (res < 0) {
                        return res;
                    }
                    String modeStr = shell.getNextArg();
                    if (modeStr == null) {
                        err.println("Error: Mode not specified.");
                        return -1;
                    }

                    final int mode = shell.strModeToMode(modeStr, err);
                    if (mode < 0) {
                        return -1;
                    }

                    if (shell.packageName != null) {
                        shell.mInterface.setMode(shell.op, shell.packageUid, shell.packageName,
                                mode);
                    } else {
                        shell.mInterface.setUidMode(shell.op, shell.nonpackageUid, mode);
                    }
                    return 0;
                }
                case "get": {
                    int res = shell.parseUserPackageOp(false, err);
                    if (res < 0) {
                        return res;
                    }

                    List<AppOpsManager.PackageOps> ops;
                    if (shell.packageName != null) {
                        ops = shell.mInterface.getOpsForPackage(
                                shell.packageUid, shell.packageName,
                                shell.op != AppOpsManager.OP_NONE ? new int[]{shell.op} : null);
                    } else {
                        ops = shell.mInterface.getUidOps(
                                shell.nonpackageUid,
                                shell.op != AppOpsManager.OP_NONE ? new int[]{shell.op} : null);
                    }
                    if (ops == null || ops.size() <= 0) {
                        pw.println("No operations.");
                        if (shell.op > AppOpsManager.OP_NONE && shell.op < AppOpsManager._NUM_OP) {
                            pw.println("Default mode: " + AppOpsManager.modeToString(
                                    AppOpsManager.opToDefaultMode(shell.op)));
                        }
                        return 0;
                    }
                    final long now = System.currentTimeMillis();
                    for (int i=0; i<ops.size(); i++) {
                        List<AppOpsManager.OpEntry> entries = ops.get(i).getOps();
                        for (int j=0; j<entries.size(); j++) {
                            AppOpsManager.OpEntry ent = entries.get(j);
                            pw.print(AppOpsManager.opToName(ent.getOp()));
                            pw.print(": ");
                            pw.print(AppOpsManager.modeToString(ent.getMode()));
                            if (ent.getTime() != 0) {
                                pw.print("; time=");
                                TimeUtils.formatDuration(now - ent.getTime(), pw);
                                pw.print(" ago");
                            }
                            if (ent.getRejectTime() != 0) {
                                pw.print("; rejectTime=");
                                TimeUtils.formatDuration(now - ent.getRejectTime(), pw);
                                pw.print(" ago");
                            }
                            if (ent.getDuration() == -1) {
                                pw.print(" (running)");
                            } else if (ent.getDuration() != 0) {
                                pw.print("; duration=");
                                TimeUtils.formatDuration(ent.getDuration(), pw);
                            }
                            pw.println();
                        }
                    }
                    return 0;
                }
                case "query-op": {
                    int res = shell.parseUserOpMode(AppOpsManager.MODE_IGNORED, err);
                    if (res < 0) {
                        return res;
                    }
                    List<AppOpsManager.PackageOps> ops = shell.mInterface.getPackagesForOps(
                            new int[] {shell.op});
                    if (ops == null || ops.size() <= 0) {
                        pw.println("No operations.");
                        return 0;
                    }
                    for (int i=0; i<ops.size(); i++) {
                        final AppOpsManager.PackageOps pkg = ops.get(i);
                        boolean hasMatch = false;
                        final List<AppOpsManager.OpEntry> entries = ops.get(i).getOps();
                        for (int j=0; j<entries.size(); j++) {
                            AppOpsManager.OpEntry ent = entries.get(j);
                            if (ent.getOp() == shell.op && ent.getMode() == shell.mode) {
                                hasMatch = true;
                                break;
                            }
                        }
                        if (hasMatch) {
                            pw.println(pkg.getPackageName());
                        }
                    }
                    return 0;
                }
                case "reset": {
                    String packageName = null;
                    int userId = UserHandle.USER_CURRENT;
                    for (String argument; (argument = shell.getNextArg()) != null;) {
                        if ("--user".equals(argument)) {
                            String userStr = shell.getNextArgRequired();
                            userId = UserHandle.parseUserArg(userStr);
                        } else {
                            if (packageName == null) {
                                packageName = argument;
                            } else {
                                err.println("Error: Unsupported argument: " + argument);
                                return -1;
                            }
                        }
                    }

                    if (userId == UserHandle.USER_CURRENT) {
                        userId = ActivityManager.getCurrentUser();
                    }

                    shell.mInterface.resetAllModes(userId, packageName);
                    pw.print("Reset all modes for: ");
                    if (userId == UserHandle.USER_ALL) {
                        pw.print("all users");
                    } else {
                        pw.print("user "); pw.print(userId);
                    }
                    pw.print(", ");
                    if (packageName == null) {
                        pw.println("all packages");
                    } else {
                        pw.print("package "); pw.println(packageName);
                    }
                    return 0;
                }
                case "write-settings": {
                    shell.mInternal.mContext.enforcePermission(
                            android.Manifest.permission.MANAGE_APP_OPS_MODES,
                            Binder.getCallingPid(), Binder.getCallingUid(), null);
                    long token = Binder.clearCallingIdentity();
                    try {
                        synchronized (shell.mInternal) {
                            shell.mInternal.mHandler.removeCallbacks(shell.mInternal.mWriteRunner);
                        }
                        shell.mInternal.writeState();
                        pw.println("Current settings written.");
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                    return 0;
                }
                case "read-settings": {
                    shell.mInternal.mContext.enforcePermission(
                            android.Manifest.permission.MANAGE_APP_OPS_MODES,
                            Binder.getCallingPid(), Binder.getCallingUid(), null);
                    long token = Binder.clearCallingIdentity();
                    try {
                        shell.mInternal.readState();
                        pw.println("Last settings read.");
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                    return 0;
                }
                case "start": {
                    int res = shell.parseUserPackageOp(true, err);
                    if (res < 0) {
                        return res;
                    }

                    if (shell.packageName != null) {
                        shell.mInterface.startOperation(shell.mToken,
                                shell.op, shell.packageUid, shell.packageName, true);
                    } else {
                        return -1;
                    }
                    return 0;
                }
                case "stop": {
                    int res = shell.parseUserPackageOp(true, err);
                    if (res < 0) {
                        return res;
                    }

                    if (shell.packageName != null) {
                        shell.mInterface.finishOperation(shell.mToken,
                                shell.op, shell.packageUid, shell.packageName);
                    } else {
                        return -1;
                    }
                    return 0;
                }
                default:
                    return shell.handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    private void dumpHelp(PrintWriter pw) {
        pw.println("AppOps service (appops) dump options:");
        pw.println("  none");
    }

    private void dumpTimesLocked(PrintWriter pw, String firstPrefix, String prefix, long[] times,
            long now, SimpleDateFormat sdf, Date date) {
        boolean hasTime = false;
        for (int i = 0; i < _NUM_UID_STATE; i++) {
            if (times[i] != 0) {
                hasTime = true;
                break;
            }
        }
        if (!hasTime) {
            return;
        }
        boolean first = true;
        for (int i = 0; i < _NUM_UID_STATE; i++) {
            if (times[i] != 0) {
                pw.print(first ? firstPrefix : prefix);
                first = false;
                pw.print(UID_STATE_NAMES[i]);
                pw.print(" = ");
                date.setTime(times[i]);
                pw.print(sdf.format(date));
                pw.print(" (");
                TimeUtils.formatDuration(times[i]-now, pw);
                pw.println(")");
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw)) return;

        if (args != null) {
            for (int i=0; i<args.length; i++) {
                String arg = args[i];
                if ("-h".equals(arg)) {
                    dumpHelp(pw);
                    return;
                } else if ("-a".equals(arg)) {
                    // dump all data
                } else if (arg.length() > 0 && arg.charAt(0) == '-'){
                    pw.println("Unknown option: " + arg);
                    return;
                } else {
                    pw.println("Unknown command: " + arg);
                    return;
                }
            }
        }

        synchronized (this) {
            pw.println("Current AppOps Service state:");
            final long now = System.currentTimeMillis();
            final long nowElapsed = SystemClock.elapsedRealtime();
            final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            final Date date = new Date();
            boolean needSep = false;
            if (mOpModeWatchers.size() > 0) {
                needSep = true;
                pw.println("  Op mode watchers:");
                for (int i=0; i<mOpModeWatchers.size(); i++) {
                    pw.print("    Op "); pw.print(AppOpsManager.opToName(mOpModeWatchers.keyAt(i)));
                    pw.println(":");
                    ArraySet<ModeCallback> callbacks = mOpModeWatchers.valueAt(i);
                    for (int j=0; j<callbacks.size(); j++) {
                        pw.print("      #"); pw.print(j); pw.print(": ");
                        pw.println(callbacks.valueAt(j));
                    }
                }
            }
            if (mPackageModeWatchers.size() > 0) {
                needSep = true;
                pw.println("  Package mode watchers:");
                for (int i=0; i<mPackageModeWatchers.size(); i++) {
                    pw.print("    Pkg "); pw.print(mPackageModeWatchers.keyAt(i));
                    pw.println(":");
                    ArraySet<ModeCallback> callbacks = mPackageModeWatchers.valueAt(i);
                    for (int j=0; j<callbacks.size(); j++) {
                        pw.print("      #"); pw.print(j); pw.print(": ");
                        pw.println(callbacks.valueAt(j));
                    }
                }
            }
            if (mModeWatchers.size() > 0) {
                needSep = true;
                pw.println("  All op mode watchers:");
                for (int i=0; i<mModeWatchers.size(); i++) {
                    pw.print("    ");
                    pw.print(Integer.toHexString(System.identityHashCode(mModeWatchers.keyAt(i))));
                    pw.print(": "); pw.println(mModeWatchers.valueAt(i));
                }
            }
            if (mActiveWatchers.size() > 0) {
                needSep = true;
                pw.println("  All op active watchers:");
                for (int i = 0; i < mActiveWatchers.size(); i++) {
                    final SparseArray<ActiveCallback> activeWatchers = mActiveWatchers.valueAt(i);
                    if (activeWatchers.size() <= 0) {
                        continue;
                    }
                    pw.print("    ");
                    pw.print(Integer.toHexString(System.identityHashCode(
                            mActiveWatchers.keyAt(i))));
                    pw.println(" ->");
                    pw.print("        [");
                    final int opCount = activeWatchers.size();
                    for (i = 0; i < opCount; i++) {
                        pw.print(AppOpsManager.opToName(activeWatchers.keyAt(i)));
                        if (i < opCount - 1) {
                            pw.print(',');
                        }
                    }
                    pw.println("]");
                    pw.print("        ");
                    pw.println(activeWatchers.valueAt(0));
                }
            }
            if (mClients.size() > 0) {
                needSep = true;
                pw.println("  Clients:");
                for (int i=0; i<mClients.size(); i++) {
                    pw.print("    "); pw.print(mClients.keyAt(i)); pw.println(":");
                    ClientState cs = mClients.valueAt(i);
                    pw.print("      "); pw.println(cs);
                    if (cs.mStartedOps.size() > 0) {
                        pw.println("      Started ops:");
                        for (int j=0; j<cs.mStartedOps.size(); j++) {
                            Op op = cs.mStartedOps.get(j);
                            pw.print("        "); pw.print("uid="); pw.print(op.uid);
                            pw.print(" pkg="); pw.print(op.packageName);
                            pw.print(" op="); pw.println(AppOpsManager.opToName(op.op));
                        }
                    }
                }
            }
            if (mAudioRestrictions.size() > 0) {
                boolean printedHeader = false;
                for (int o=0; o<mAudioRestrictions.size(); o++) {
                    final String op = AppOpsManager.opToName(mAudioRestrictions.keyAt(o));
                    final SparseArray<Restriction> restrictions = mAudioRestrictions.valueAt(o);
                    for (int i=0; i<restrictions.size(); i++) {
                        if (!printedHeader){
                            pw.println("  Audio Restrictions:");
                            printedHeader = true;
                            needSep = true;
                        }
                        final int usage = restrictions.keyAt(i);
                        pw.print("    "); pw.print(op);
                        pw.print(" usage="); pw.print(AudioAttributes.usageToString(usage));
                        Restriction r = restrictions.valueAt(i);
                        pw.print(": mode="); pw.println(MODE_NAMES[r.mode]);
                        if (!r.exceptionPackages.isEmpty()) {
                            pw.println("      Exceptions:");
                            for (int j=0; j<r.exceptionPackages.size(); j++) {
                                pw.print("        "); pw.println(r.exceptionPackages.valueAt(j));
                            }
                        }
                    }
                }
            }
            if (needSep) {
                pw.println();
            }
            for (int i=0; i<mUidStates.size(); i++) {
                UidState uidState = mUidStates.valueAt(i);

                pw.print("  Uid "); UserHandle.formatUid(pw, uidState.uid); pw.println(":");
                pw.print("    state=");
                pw.println(UID_STATE_NAMES[uidState.state]);
                if (uidState.startNesting != 0) {
                    pw.print("    startNesting=");
                    pw.println(uidState.startNesting);
                }
                needSep = true;

                SparseIntArray opModes = uidState.opModes;
                if (opModes != null) {
                    final int opModeCount = opModes.size();
                    for (int j = 0; j < opModeCount; j++) {
                        final int code = opModes.keyAt(j);
                        final int mode = opModes.valueAt(j);
                        pw.print("      "); pw.print(AppOpsManager.opToName(code));
                        pw.print(": mode="); pw.println(MODE_NAMES[mode]);
                    }
                }

                ArrayMap<String, Ops> pkgOps = uidState.pkgOps;
                if (pkgOps == null) {
                    continue;
                }

                for (Ops ops : pkgOps.values()) {
                    pw.print("    Package "); pw.print(ops.packageName); pw.println(":");
                    for (int j=0; j<ops.size(); j++) {
                        Op op = ops.valueAt(j);
                        pw.print("      "); pw.print(AppOpsManager.opToName(op.op));
                        pw.print(" ("); pw.print(MODE_NAMES[op.mode]); pw.println("): ");
                        dumpTimesLocked(pw,
                                "          Access: ",
                                "                  ", op.time, now, sdf, date);
                        dumpTimesLocked(pw,
                                "          Reject: ",
                                "                  ", op.rejectTime, now, sdf, date);
                        if (op.duration == -1) {
                            pw.print("          Running start at: ");
                            TimeUtils.formatDuration(nowElapsed-op.startRealtime, pw);
                            pw.println();
                        } else if (op.duration != 0) {
                            pw.print("          duration=");
                            TimeUtils.formatDuration(op.duration, pw);
                            pw.println();
                        }
                        if (op.startNesting != 0) {
                            pw.print("          startNesting=");
                            pw.println(op.startNesting);
                        }
                    }
                }
            }
            if (needSep) {
                pw.println();
            }

            final int userRestrictionCount = mOpUserRestrictions.size();
            for (int i = 0; i < userRestrictionCount; i++) {
                IBinder token = mOpUserRestrictions.keyAt(i);
                ClientRestrictionState restrictionState = mOpUserRestrictions.valueAt(i);
                pw.println("  User restrictions for token " + token + ":");

                final int restrictionCount = restrictionState.perUserRestrictions != null
                        ? restrictionState.perUserRestrictions.size() : 0;
                if (restrictionCount > 0) {
                    pw.println("      Restricted ops:");
                    for (int j = 0; j < restrictionCount; j++) {
                        int userId = restrictionState.perUserRestrictions.keyAt(j);
                        boolean[] restrictedOps = restrictionState.perUserRestrictions.valueAt(j);
                        if (restrictedOps == null) {
                            continue;
                        }
                        StringBuilder restrictedOpsValue = new StringBuilder();
                        restrictedOpsValue.append("[");
                        final int restrictedOpCount = restrictedOps.length;
                        for (int k = 0; k < restrictedOpCount; k++) {
                            if (restrictedOps[k]) {
                                if (restrictedOpsValue.length() > 1) {
                                    restrictedOpsValue.append(", ");
                                }
                                restrictedOpsValue.append(AppOpsManager.opToName(k));
                            }
                        }
                        restrictedOpsValue.append("]");
                        pw.print("        "); pw.print("user: "); pw.print(userId);
                                pw.print(" restricted ops: "); pw.println(restrictedOpsValue);
                    }
                }

                final int excludedPackageCount = restrictionState.perUserExcludedPackages != null
                        ? restrictionState.perUserExcludedPackages.size() : 0;
                if (excludedPackageCount > 0) {
                    pw.println("      Excluded packages:");
                    for (int j = 0; j < excludedPackageCount; j++) {
                        int userId = restrictionState.perUserExcludedPackages.keyAt(j);
                        String[] packageNames = restrictionState.perUserExcludedPackages.valueAt(j);
                        pw.print("        "); pw.print("user: "); pw.print(userId);
                                pw.print(" packages: "); pw.println(Arrays.toString(packageNames));
                    }
                }
            }
        }
    }

    private static final class Restriction {
        private static final ArraySet<String> NO_EXCEPTIONS = new ArraySet<String>();
        int mode;
        ArraySet<String> exceptionPackages = NO_EXCEPTIONS;
    }

    @Override
    public void setUserRestrictions(Bundle restrictions, IBinder token, int userHandle) {
        checkSystemUid("setUserRestrictions");
        Preconditions.checkNotNull(restrictions);
        Preconditions.checkNotNull(token);
        for (int i = 0; i < AppOpsManager._NUM_OP; i++) {
            String restriction = AppOpsManager.opToRestriction(i);
            if (restriction != null) {
                setUserRestrictionNoCheck(i, restrictions.getBoolean(restriction, false), token,
                        userHandle, null);
            }
        }
    }

    @Override
    public void setUserRestriction(int code, boolean restricted, IBinder token, int userHandle,
            String[] exceptionPackages) {
        if (Binder.getCallingPid() != Process.myPid()) {
            mContext.enforcePermission(Manifest.permission.MANAGE_APP_OPS_RESTRICTIONS,
                    Binder.getCallingPid(), Binder.getCallingUid(), null);
        }
        if (userHandle != UserHandle.getCallingUserId()) {
            if (mContext.checkCallingOrSelfPermission(Manifest.permission
                    .INTERACT_ACROSS_USERS_FULL) != PackageManager.PERMISSION_GRANTED
                && mContext.checkCallingOrSelfPermission(Manifest.permission
                    .INTERACT_ACROSS_USERS) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Need INTERACT_ACROSS_USERS_FULL or"
                        + " INTERACT_ACROSS_USERS to interact cross user ");
            }
        }
        verifyIncomingOp(code);
        Preconditions.checkNotNull(token);
        setUserRestrictionNoCheck(code, restricted, token, userHandle, exceptionPackages);
    }

    private void setUserRestrictionNoCheck(int code, boolean restricted, IBinder token,
            int userHandle, String[] exceptionPackages) {
        synchronized (AppOpsService.this) {
            ClientRestrictionState restrictionState = mOpUserRestrictions.get(token);

            if (restrictionState == null) {
                try {
                    restrictionState = new ClientRestrictionState(token);
                } catch (RemoteException e) {
                    return;
                }
                mOpUserRestrictions.put(token, restrictionState);
            }

            if (restrictionState.setRestriction(code, restricted, exceptionPackages, userHandle)) {
                mHandler.sendMessage(PooledLambda.obtainMessage(
                        AppOpsService::notifyWatchersOfChange, this, code, UID_ANY));
            }

            if (restrictionState.isDefault()) {
                mOpUserRestrictions.remove(token);
                restrictionState.destroy();
            }
        }
    }

    private void notifyWatchersOfChange(int code, int uid) {
        final ArraySet<ModeCallback> clonedCallbacks;
        synchronized (this) {
            ArraySet<ModeCallback> callbacks = mOpModeWatchers.get(code);
            if (callbacks == null) {
                return;
            }
            clonedCallbacks = new ArraySet<>(callbacks);
        }

        notifyOpChanged(clonedCallbacks,  code, uid, null);
    }

    @Override
    public void removeUser(int userHandle) throws RemoteException {
        checkSystemUid("removeUser");
        synchronized (AppOpsService.this) {
            final int tokenCount = mOpUserRestrictions.size();
            for (int i = tokenCount - 1; i >= 0; i--) {
                ClientRestrictionState opRestrictions = mOpUserRestrictions.valueAt(i);
                opRestrictions.removeUser(userHandle);
            }
            removeUidsForUserLocked(userHandle);
        }
    }

    @Override
    public boolean isOperationActive(int code, int uid, String packageName) {
        if (Binder.getCallingUid() != uid) {
            if (mContext.checkCallingOrSelfPermission(Manifest.permission.WATCH_APPOPS)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        verifyIncomingOp(code);
        final String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return false;
        }
        synchronized (AppOpsService.this) {
            for (int i = mClients.size() - 1; i >= 0; i--) {
                final ClientState client = mClients.valueAt(i);
                for (int j = client.mStartedOps.size() - 1; j >= 0; j--) {
                    final Op op = client.mStartedOps.get(j);
                    if (op.op == code && op.uid == uid) return true;
                }
            }
        }
        return false;
    }

    private void removeUidsForUserLocked(int userHandle) {
        for (int i = mUidStates.size() - 1; i >= 0; --i) {
            final int uid = mUidStates.keyAt(i);
            if (UserHandle.getUserId(uid) == userHandle) {
                mUidStates.removeAt(i);
            }
        }
    }

    private void checkSystemUid(String function) {
        int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID) {
            throw new SecurityException(function + " must by called by the system");
        }
    }

    private static String resolvePackageName(int uid, String packageName)  {
        if (uid == Process.ROOT_UID) {
            return "root";
        } else if (uid == Process.SHELL_UID) {
            return "com.android.shell";
        } else if (uid == Process.MEDIA_UID) {
            return "media";
        } else if (uid == Process.AUDIOSERVER_UID) {
            return "audioserver";
        } else if (uid == Process.CAMERASERVER_UID) {
            return "cameraserver";
        } else if (uid == Process.SYSTEM_UID && packageName == null) {
            return "android";
        }
        return packageName;
    }

    private static int resolveUid(String packageName)  {
        if (packageName == null) {
            return -1;
        }
        switch (packageName) {
            case "root":
                return Process.ROOT_UID;
            case "shell":
                return Process.SHELL_UID;
            case "media":
                return Process.MEDIA_UID;
            case "audioserver":
                return Process.AUDIOSERVER_UID;
            case "cameraserver":
                return Process.CAMERASERVER_UID;
        }
        return -1;
    }

    private static String[] getPackagesForUid(int uid) {
        String[] packageNames = null;
        try {
            packageNames = AppGlobals.getPackageManager().getPackagesForUid(uid);
        } catch (RemoteException e) {
            /* ignore - local call */
        }
        if (packageNames == null) {
            return EmptyArray.STRING;
        }
        return packageNames;
    }

    private final class ClientRestrictionState implements DeathRecipient {
        private final IBinder token;
        SparseArray<boolean[]> perUserRestrictions;
        SparseArray<String[]> perUserExcludedPackages;

        public ClientRestrictionState(IBinder token)
                throws RemoteException {
            token.linkToDeath(this, 0);
            this.token = token;
        }

        public boolean setRestriction(int code, boolean restricted,
                String[] excludedPackages, int userId) {
            boolean changed = false;

            if (perUserRestrictions == null && restricted) {
                perUserRestrictions = new SparseArray<>();
            }

            int[] users;
            if (userId == UserHandle.USER_ALL) {
                List<UserInfo> liveUsers = UserManager.get(mContext).getUsers(false);

                users = new int[liveUsers.size()];
                for (int i = 0; i < liveUsers.size(); i++) {
                    users[i] = liveUsers.get(i).id;
                }
            } else {
                users = new int[]{userId};
            }

            if (perUserRestrictions != null) {
                int numUsers = users.length;

                for (int i = 0; i < numUsers; i++) {
                    int thisUserId = users[i];

                    boolean[] userRestrictions = perUserRestrictions.get(thisUserId);
                    if (userRestrictions == null && restricted) {
                        userRestrictions = new boolean[AppOpsManager._NUM_OP];
                        perUserRestrictions.put(thisUserId, userRestrictions);
                    }
                    if (userRestrictions != null && userRestrictions[code] != restricted) {
                        userRestrictions[code] = restricted;
                        if (!restricted && isDefault(userRestrictions)) {
                            perUserRestrictions.remove(thisUserId);
                            userRestrictions = null;
                        }
                        changed = true;
                    }

                    if (userRestrictions != null) {
                        final boolean noExcludedPackages = ArrayUtils.isEmpty(excludedPackages);
                        if (perUserExcludedPackages == null && !noExcludedPackages) {
                            perUserExcludedPackages = new SparseArray<>();
                        }
                        if (perUserExcludedPackages != null && !Arrays.equals(excludedPackages,
                                perUserExcludedPackages.get(thisUserId))) {
                            if (noExcludedPackages) {
                                perUserExcludedPackages.remove(thisUserId);
                                if (perUserExcludedPackages.size() <= 0) {
                                    perUserExcludedPackages = null;
                                }
                            } else {
                                perUserExcludedPackages.put(thisUserId, excludedPackages);
                            }
                            changed = true;
                        }
                    }
                }
            }

            return changed;
        }

        public boolean hasRestriction(int restriction, String packageName, int userId) {
            if (perUserRestrictions == null) {
                return false;
            }
            boolean[] restrictions = perUserRestrictions.get(userId);
            if (restrictions == null) {
                return false;
            }
            if (!restrictions[restriction]) {
                return false;
            }
            if (perUserExcludedPackages == null) {
                return true;
            }
            String[] perUserExclusions = perUserExcludedPackages.get(userId);
            if (perUserExclusions == null) {
                return true;
            }
            return !ArrayUtils.contains(perUserExclusions, packageName);
        }

        public void removeUser(int userId) {
            if (perUserExcludedPackages != null) {
                perUserExcludedPackages.remove(userId);
                if (perUserExcludedPackages.size() <= 0) {
                    perUserExcludedPackages = null;
                }
            }
            if (perUserRestrictions != null) {
                perUserRestrictions.remove(userId);
                if (perUserRestrictions.size() <= 0) {
                    perUserRestrictions = null;
                }
            }
        }

        public boolean isDefault() {
            return perUserRestrictions == null || perUserRestrictions.size() <= 0;
        }

        @Override
        public void binderDied() {
            synchronized (AppOpsService.this) {
                mOpUserRestrictions.remove(token);
                if (perUserRestrictions == null) {
                    return;
                }
                final int userCount = perUserRestrictions.size();
                for (int i = 0; i < userCount; i++) {
                    final boolean[] restrictions = perUserRestrictions.valueAt(i);
                    final int restrictionCount = restrictions.length;
                    for (int j = 0; j < restrictionCount; j++) {
                        if (restrictions[j]) {
                            final int changedCode = j;
                            mHandler.post(() -> notifyWatchersOfChange(changedCode, UID_ANY));
                        }
                    }
                }
                destroy();
            }
        }

        public void destroy() {
            token.unlinkToDeath(this, 0);
        }

        private boolean isDefault(boolean[] array) {
            if (ArrayUtils.isEmpty(array)) {
                return true;
            }
            for (boolean value : array) {
                if (value) {
                    return false;
                }
            }
            return true;
        }
    }
}
