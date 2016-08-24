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

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
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
import android.os.ShellCommand;
import android.os.UserHandle;
import android.os.storage.MountServiceInternal;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.Xml;

import com.android.internal.app.IAppOpsService;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.os.Zygote;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;

import libcore.util.EmptyArray;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

public class AppOpsService extends IAppOpsService.Stub {
    static final String TAG = "AppOps";
    static final boolean DEBUG = false;

    // Write at most every 30 minutes.
    static final long WRITE_DELAY = DEBUG ? 1000 : 30*60*1000;

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

    private final SparseArray<UidState> mUidStates = new SparseArray<>();

    /*
     * These are app op restrictions imposed per user from various parties.
     */
    private final ArrayMap<IBinder, ClientRestrictionState> mOpUserRestrictions = new ArrayMap<>();

    private static final class UidState {
        public final int uid;
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

    public final static class Ops extends SparseArray<Op> {
        public final String packageName;
        public final UidState uidState;
        public final boolean isPrivileged;

        public Ops(String _packageName, UidState _uidState, boolean _isPrivileged) {
            packageName = _packageName;
            uidState = _uidState;
            isPrivileged = _isPrivileged;
        }
    }

    public final static class Op {
        public final int uid;
        public final String packageName;
        public int proxyUid = -1;
        public String proxyPackageName;
        public final int op;
        public int mode;
        public int duration;
        public long time;
        public long rejectTime;
        public int nesting;

        public Op(int _uid, String _packageName, int _op) {
            uid = _uid;
            packageName = _packageName;
            op = _op;
            mode = AppOpsManager.opToDefaultMode(op);
        }
    }

    final SparseArray<ArrayList<Callback>> mOpModeWatchers
            = new SparseArray<ArrayList<Callback>>();
    final ArrayMap<String, ArrayList<Callback>> mPackageModeWatchers
            = new ArrayMap<String, ArrayList<Callback>>();
    final ArrayMap<IBinder, Callback> mModeWatchers
            = new ArrayMap<IBinder, Callback>();
    final SparseArray<SparseArray<Restriction>> mAudioRestrictions
            = new SparseArray<SparseArray<Restriction>>();

    public final class Callback implements DeathRecipient {
        final IAppOpsCallback mCallback;

        public Callback(IAppOpsCallback callback) {
            mCallback = callback;
            try {
                mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
            }
        }

        public void unlinkToDeath() {
            mCallback.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            stopWatchingMode(mCallback);
        }
    }

    final ArrayMap<IBinder, ClientState> mClients = new ArrayMap<IBinder, ClientState>();

    public final class ClientState extends Binder implements DeathRecipient {
        final IBinder mAppToken;
        final int mPid;
        final ArrayList<Op> mStartedOps;

        public ClientState(IBinder appToken) {
            mAppToken = appToken;
            mPid = Binder.getCallingPid();
            if (appToken instanceof Binder) {
                // For local clients, there is no reason to track them.
                mStartedOps = null;
            } else {
                mStartedOps = new ArrayList<Op>();
                try {
                    mAppToken.linkToDeath(this, 0);
                } catch (RemoteException e) {
                }
            }
        }

        @Override
        public String toString() {
            return "ClientState{" +
                    "mAppToken=" + mAppToken +
                    ", " + (mStartedOps != null ? ("pid=" + mPid) : "local") +
                    '}';
        }

        @Override
        public void binderDied() {
            synchronized (AppOpsService.this) {
                for (int i=mStartedOps.size()-1; i>=0; i--) {
                    finishOperationLocked(mStartedOps.get(i));
                }
                mClients.remove(mAppToken);
            }
        }
    }

    public AppOpsService(File storagePath, Handler handler) {
        mFile = new AtomicFile(storagePath);
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

        MountServiceInternal mountServiceInternal = LocalServices.getService(
                MountServiceInternal.class);
        mountServiceInternal.addExternalStoragePolicy(
                new MountServiceInternal.ExternalStorageMountPolicy() {
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

            boolean changed = false;

            // Remove any package state if such.
            if (uidState.pkgOps != null && uidState.pkgOps.remove(packageName) != null) {
                changed = true;
            }

            // If we just nuked the last package state check if the UID is valid.
            if (changed && uidState.pkgOps.isEmpty()
                    && getPackagesForUid(uid).length <= 0) {
                mUidStates.remove(uid);
            }

            if (changed) {
                scheduleFastWriteLocked();
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
        if (ops == null) {
            resOps = new ArrayList<AppOpsManager.OpEntry>();
            for (int j=0; j<pkgOps.size(); j++) {
                Op curOp = pkgOps.valueAt(j);
                resOps.add(new AppOpsManager.OpEntry(curOp.op, curOp.mode, curOp.time,
                        curOp.rejectTime, curOp.duration, curOp.proxyUid,
                        curOp.proxyPackageName));
            }
        } else {
            for (int j=0; j<ops.length; j++) {
                Op curOp = pkgOps.get(ops[j]);
                if (curOp != null) {
                    if (resOps == null) {
                        resOps = new ArrayList<AppOpsManager.OpEntry>();
                    }
                    resOps.add(new AppOpsManager.OpEntry(curOp.op, curOp.mode, curOp.time,
                            curOp.rejectTime, curOp.duration, curOp.proxyUid,
                            curOp.proxyPackageName));
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
            Ops pkgOps = getOpsRawLocked(uid, resolvedPackageName, false);
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

    private void pruneOp(Op op, int uid, String packageName) {
        if (op.time == 0 && op.rejectTime == 0) {
            Ops ops = getOpsRawLocked(uid, packageName, false);
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
            mContext.enforcePermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
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
        ArrayMap<Callback, ArraySet<String>> callbackSpecs = null;

        synchronized (this) {
            ArrayList<Callback> callbacks = mOpModeWatchers.get(code);
            if (callbacks != null) {
                final int callbackCount = callbacks.size();
                for (int i = 0; i < callbackCount; i++) {
                    Callback callback = callbacks.get(i);
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
                        Callback callback = callbacks.get(i);
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

        // There are components watching for mode changes such as window manager
        // and location manager which are in our process. The callbacks in these
        // components may require permissions our remote caller does not have.
        final long identity = Binder.clearCallingIdentity();
        try {
            for (int i = 0; i < callbackSpecs.size(); i++) {
                Callback callback = callbackSpecs.keyAt(i);
                ArraySet<String> reportedPackageNames = callbackSpecs.valueAt(i);
                try {
                    if (reportedPackageNames == null) {
                        callback.mCallback.opChanged(code, uid, null);
                    } else {
                        final int reportedPackageCount = reportedPackageNames.size();
                        for (int j = 0; j < reportedPackageCount; j++) {
                            String reportedPackageName = reportedPackageNames.valueAt(j);
                            callback.mCallback.opChanged(code, uid, reportedPackageName);
                        }
                    }
                } catch (RemoteException e) {
                    Log.w(TAG, "Error dispatching op op change", e);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void setMode(int code, int uid, String packageName, int mode) {
        if (Binder.getCallingPid() != Process.myPid()) {
            mContext.enforcePermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
                    Binder.getCallingPid(), Binder.getCallingUid(), null);
        }
        verifyIncomingOp(code);
        ArrayList<Callback> repCbs = null;
        code = AppOpsManager.opToSwitch(code);
        synchronized (this) {
            UidState uidState = getUidStateLocked(uid, false);
            Op op = getOpLocked(code, uid, packageName, true);
            if (op != null) {
                if (op.mode != mode) {
                    op.mode = mode;
                    ArrayList<Callback> cbs = mOpModeWatchers.get(code);
                    if (cbs != null) {
                        if (repCbs == null) {
                            repCbs = new ArrayList<Callback>();
                        }
                        repCbs.addAll(cbs);
                    }
                    cbs = mPackageModeWatchers.get(packageName);
                    if (cbs != null) {
                        if (repCbs == null) {
                            repCbs = new ArrayList<Callback>();
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
            // There are components watching for mode changes such as window manager
            // and location manager which are in our process. The callbacks in these
            // components may require permissions our remote caller does not have.
            final long identity = Binder.clearCallingIdentity();
            try {
                for (int i = 0; i < repCbs.size(); i++) {
                    try {
                        repCbs.get(i).mCallback.opChanged(code, uid, packageName);
                    } catch (RemoteException e) {
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private static HashMap<Callback, ArrayList<ChangeRec>> addCallbacks(
            HashMap<Callback, ArrayList<ChangeRec>> callbacks,
            int op, int uid, String packageName, ArrayList<Callback> cbs) {
        if (cbs == null) {
            return callbacks;
        }
        if (callbacks == null) {
            callbacks = new HashMap<>();
        }
        boolean duplicate = false;
        for (int i=0; i<cbs.size(); i++) {
            Callback cb = cbs.get(i);
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
        mContext.enforcePermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
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

        HashMap<Callback, ArrayList<ChangeRec>> callbacks = null;
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
                            if (curOp.time == 0 && curOp.rejectTime == 0) {
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
            for (Map.Entry<Callback, ArrayList<ChangeRec>> ent : callbacks.entrySet()) {
                Callback cb = ent.getKey();
                ArrayList<ChangeRec> reports = ent.getValue();
                for (int i=0; i<reports.size(); i++) {
                    ChangeRec rep = reports.get(i);
                    try {
                        cb.mCallback.opChanged(rep.op, rep.uid, rep.pkg);
                    } catch (RemoteException e) {
                    }
                }
            }
        }
    }

    @Override
    public void startWatchingMode(int op, String packageName, IAppOpsCallback callback) {
        if (callback == null) {
            return;
        }
        synchronized (this) {
            op = (op != AppOpsManager.OP_NONE) ? AppOpsManager.opToSwitch(op) : op;
            Callback cb = mModeWatchers.get(callback.asBinder());
            if (cb == null) {
                cb = new Callback(callback);
                mModeWatchers.put(callback.asBinder(), cb);
            }
            if (op != AppOpsManager.OP_NONE) {
                ArrayList<Callback> cbs = mOpModeWatchers.get(op);
                if (cbs == null) {
                    cbs = new ArrayList<Callback>();
                    mOpModeWatchers.put(op, cbs);
                }
                cbs.add(cb);
            }
            if (packageName != null) {
                ArrayList<Callback> cbs = mPackageModeWatchers.get(packageName);
                if (cbs == null) {
                    cbs = new ArrayList<Callback>();
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
            Callback cb = mModeWatchers.remove(callback.asBinder());
            if (cb != null) {
                cb.unlinkToDeath();
                for (int i=mOpModeWatchers.size()-1; i>=0; i--) {
                    ArrayList<Callback> cbs = mOpModeWatchers.valueAt(i);
                    cbs.remove(cb);
                    if (cbs.size() <= 0) {
                        mOpModeWatchers.removeAt(i);
                    }
                }
                for (int i=mPackageModeWatchers.size()-1; i>=0; i--) {
                    ArrayList<Callback> cbs = mPackageModeWatchers.valueAt(i);
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
            if (isOpRestricted(uid, code, resolvedPackageName)) {
                return AppOpsManager.MODE_IGNORED;
            }
            code = AppOpsManager.opToSwitch(code);
            UidState uidState = getUidStateLocked(uid, false);
            if (uidState != null && uidState.opModes != null) {
                final int uidMode = uidState.opModes.get(code);
                if (uidMode != AppOpsManager.MODE_ALLOWED) {
                    return uidMode;
                }
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
            Log.i(TAG, "Audio disabled for suspended package=" + packageName + " for uid=" + uid);
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
        notifyWatchersOfChange(code);
    }

    @Override
    public int checkPackage(int uid, String packageName) {
        Preconditions.checkNotNull(packageName);
        synchronized (this) {
            if (getOpsRawLocked(uid, packageName, true) != null) {
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
            Ops ops = getOpsRawLocked(uid, packageName, true);
            if (ops == null) {
                if (DEBUG) Log.d(TAG, "noteOperation: no op for code " + code + " uid " + uid
                        + " package " + packageName);
                return AppOpsManager.MODE_ERRORED;
            }
            Op op = getOpLocked(ops, code, true);
            if (isOpRestricted(uid, code, packageName)) {
                return AppOpsManager.MODE_IGNORED;
            }
            if (op.duration == -1) {
                Slog.w(TAG, "Noting op not finished: uid " + uid + " pkg " + packageName
                        + " code " + code + " time=" + op.time + " duration=" + op.duration);
            }
            op.duration = 0;
            final int switchCode = AppOpsManager.opToSwitch(code);
            UidState uidState = ops.uidState;
            // If there is a non-default per UID policy (we set UID op mode only if
            // non-default) it takes over, otherwise use the per package policy.
            if (uidState.opModes != null && uidState.opModes.indexOfKey(switchCode) >= 0) {
                final int uidMode = uidState.opModes.get(switchCode);
                if (uidMode != AppOpsManager.MODE_ALLOWED) {
                    if (DEBUG) Log.d(TAG, "noteOperation: reject #" + op.mode + " for code "
                            + switchCode + " (" + code + ") uid " + uid + " package "
                            + packageName);
                    op.rejectTime = System.currentTimeMillis();
                    return uidMode;
                }
            } else {
                final Op switchOp = switchCode != code ? getOpLocked(ops, switchCode, true) : op;
                if (switchOp.mode != AppOpsManager.MODE_ALLOWED) {
                    if (DEBUG) Log.d(TAG, "noteOperation: reject #" + op.mode + " for code "
                            + switchCode + " (" + code + ") uid " + uid + " package "
                            + packageName);
                    op.rejectTime = System.currentTimeMillis();
                    return switchOp.mode;
                }
            }
            if (DEBUG) Log.d(TAG, "noteOperation: allowing code " + code + " uid " + uid
                    + " package " + packageName);
            op.time = System.currentTimeMillis();
            op.rejectTime = 0;
            op.proxyUid = proxyUid;
            op.proxyPackageName = proxyPackageName;
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @Override
    public int startOperation(IBinder token, int code, int uid, String packageName) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return  AppOpsManager.MODE_IGNORED;
        }
        ClientState client = (ClientState)token;
        synchronized (this) {
            Ops ops = getOpsRawLocked(uid, resolvedPackageName, true);
            if (ops == null) {
                if (DEBUG) Log.d(TAG, "startOperation: no op for code " + code + " uid " + uid
                        + " package " + resolvedPackageName);
                return AppOpsManager.MODE_ERRORED;
            }
            Op op = getOpLocked(ops, code, true);
            if (isOpRestricted(uid, code, resolvedPackageName)) {
                return AppOpsManager.MODE_IGNORED;
            }
            final int switchCode = AppOpsManager.opToSwitch(code);
            UidState uidState = ops.uidState;
            if (uidState.opModes != null) {
                final int uidMode = uidState.opModes.get(switchCode);
                if (uidMode != AppOpsManager.MODE_ALLOWED) {
                    if (DEBUG) Log.d(TAG, "noteOperation: reject #" + op.mode + " for code "
                            + switchCode + " (" + code + ") uid " + uid + " package "
                            + resolvedPackageName);
                    op.rejectTime = System.currentTimeMillis();
                    return uidMode;
                }
            }
            final Op switchOp = switchCode != code ? getOpLocked(ops, switchCode, true) : op;
            if (switchOp.mode != AppOpsManager.MODE_ALLOWED) {
                if (DEBUG) Log.d(TAG, "startOperation: reject #" + op.mode + " for code "
                        + switchCode + " (" + code + ") uid " + uid + " package "
                        + resolvedPackageName);
                op.rejectTime = System.currentTimeMillis();
                return switchOp.mode;
            }
            if (DEBUG) Log.d(TAG, "startOperation: allowing code " + code + " uid " + uid
                    + " package " + resolvedPackageName);
            if (op.nesting == 0) {
                op.time = System.currentTimeMillis();
                op.rejectTime = 0;
                op.duration = -1;
            }
            op.nesting++;
            if (client.mStartedOps != null) {
                client.mStartedOps.add(op);
            }
            return AppOpsManager.MODE_ALLOWED;
        }
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
            if (client.mStartedOps != null) {
                if (!client.mStartedOps.remove(op)) {
                    throw new IllegalStateException("Operation not started: uid" + op.uid
                            + " pkg=" + op.packageName + " op=" + op.op);
                }
            }
            finishOperationLocked(op);
        }
    }

    @Override
    public int permissionToOpCode(String permission) {
        if (permission == null) {
            return AppOpsManager.OP_NONE;
        }
        return AppOpsManager.permissionToOpCode(permission);
    }

    void finishOperationLocked(Op op) {
        if (op.nesting <= 1) {
            if (op.nesting == 1) {
                op.duration = (int)(System.currentTimeMillis() - op.time);
                op.time += op.duration;
            } else {
                Slog.w(TAG, "Finishing op nesting under-run: uid " + op.uid + " pkg "
                        + op.packageName + " code " + op.op + " time=" + op.time
                        + " duration=" + op.duration + " nesting=" + op.nesting);
            }
            op.nesting = 0;
        } else {
            op.nesting--;
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

    private Ops getOpsRawLocked(int uid, String packageName, boolean edit) {
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
                            if ("media".equals(packageName)) {
                                pkgUid = Process.MEDIA_UID;
                                isPrivileged = false;
                            } else if ("audioserver".equals(packageName)) {
                                pkgUid = Process.AUDIOSERVER_UID;
                                isPrivileged = false;
                            } else if ("cameraserver".equals(packageName)) {
                                pkgUid = Process.CAMERASERVER_UID;
                                isPrivileged = false;
                            }
                        }
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Could not contact PackageManager", e);
                    }
                    if (pkgUid != uid) {
                        // Oops!  The package name is not valid for the uid they are calling
                        // under.  Abort.
                        RuntimeException ex = new RuntimeException("here");
                        ex.fillInStackTrace();
                        Slog.w(TAG, "Bad call: specified package " + packageName
                                + " under uid " + uid + " but it is really " + pkgUid, ex);
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
        Ops ops = getOpsRawLocked(uid, packageName, edit);
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
            op = new Op(ops.uidState.uid, ops.packageName, code);
            ops.put(code, op);
        }
        if (edit) {
            scheduleWriteLocked();
        }
        return op;
    }

    private boolean isOpRestricted(int uid, int code, String packageName) {
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
                        Ops ops = getOpsRawLocked(uid, packageName, true);
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
                Op op = new Op(uid, pkgName, Integer.parseInt(parser.getAttributeValue(null, "n")));
                String mode = parser.getAttributeValue(null, "m");
                if (mode != null) {
                    op.mode = Integer.parseInt(mode);
                }
                String time = parser.getAttributeValue(null, "t");
                if (time != null) {
                    op.time = Long.parseLong(time);
                }
                time = parser.getAttributeValue(null, "r");
                if (time != null) {
                    op.rejectTime = Long.parseLong(time);
                }
                String dur = parser.getAttributeValue(null, "d");
                if (dur != null) {
                    op.duration = Integer.parseInt(dur);
                }
                String proxyUid = parser.getAttributeValue(null, "pu");
                if (proxyUid != null) {
                    op.proxyUid = Integer.parseInt(proxyUid);
                }
                String proxyPackageName = parser.getAttributeValue(null, "pp");
                if (proxyPackageName != null) {
                    op.proxyPackageName = proxyPackageName;
                }

                UidState uidState = getUidStateLocked(uid, true);
                if (uidState.pkgOps == null) {
                    uidState.pkgOps = new ArrayMap<>();
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
            List<AppOpsManager.PackageOps> allOps = getPackagesForOps(null);

            FileOutputStream stream;
            try {
                stream = mFile.startWrite();
            } catch (IOException e) {
                Slog.w(TAG, "Failed to write state: " + e);
                return;
            }

            try {
                XmlSerializer out = new FastXmlSerializer();
                out.setOutput(stream, StandardCharsets.UTF_8.name());
                out.startDocument(null, true);
                out.startTag(null, "app-ops");

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
                            Ops ops = getOpsRawLocked(pkg.getUid(), pkg.getPackageName(), false);
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
                            long time = op.getTime();
                            if (time != 0) {
                                out.attribute(null, "t", Long.toString(time));
                            }
                            time = op.getRejectTime();
                            if (time != 0) {
                                out.attribute(null, "r", Long.toString(time));
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

        Shell(IAppOpsService iface, AppOpsService internal) {
            mInterface = iface;
            mInternal = internal;
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
            switch (modeStr) {
                case "allow":
                    return AppOpsManager.MODE_ALLOWED;
                case "deny":
                    return AppOpsManager.MODE_ERRORED;
                case "ignore":
                    return AppOpsManager.MODE_IGNORED;
                case "default":
                    return AppOpsManager.MODE_DEFAULT;
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
            if ("root".equals(packageName)) {
                packageUid = 0;
            } else {
                packageUid = AppGlobals.getPackageManager().getPackageUid(packageName,
                        PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
            }
            if (packageUid < 0) {
                err.println("Error: No UID for " + packageName + " in user " + userId);
                return -1;
            }
            return 0;
        }
    }

    @Override public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ResultReceiver resultReceiver) {
        (new Shell(this, this)).exec(this, in, out, err, args, resultReceiver);
    }

    static void dumpCommandHelp(PrintWriter pw) {
        pw.println("AppOps service (appops) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  set [--user <USER_ID>] <PACKAGE> <OP> <MODE>");
        pw.println("    Set the mode for a particular application and operation.");
        pw.println("  get [--user <USER_ID>] <PACKAGE> [<OP>]");
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

                    shell.mInterface.setMode(shell.op, shell.packageUid, shell.packageName, mode);
                    return 0;
                }
                case "get": {
                    int res = shell.parseUserPackageOp(false, err);
                    if (res < 0) {
                        return res;
                    }

                    List<AppOpsManager.PackageOps> ops = shell.mInterface.getOpsForPackage(
                            shell.packageUid, shell.packageName,
                            shell.op != AppOpsManager.OP_NONE ? new int[] {shell.op} : null);
                    if (ops == null || ops.size() <= 0) {
                        pw.println("No operations.");
                        return 0;
                    }
                    final long now = System.currentTimeMillis();
                    for (int i=0; i<ops.size(); i++) {
                        List<AppOpsManager.OpEntry> entries = ops.get(i).getOps();
                        for (int j=0; j<entries.size(); j++) {
                            AppOpsManager.OpEntry ent = entries.get(j);
                            pw.print(AppOpsManager.opToName(ent.getOp()));
                            pw.print(": ");
                            switch (ent.getMode()) {
                                case AppOpsManager.MODE_ALLOWED:
                                    pw.print("allow");
                                    break;
                                case AppOpsManager.MODE_IGNORED:
                                    pw.print("ignore");
                                    break;
                                case AppOpsManager.MODE_ERRORED:
                                    pw.print("deny");
                                    break;
                                case AppOpsManager.MODE_DEFAULT:
                                    pw.print("default");
                                    break;
                                default:
                                    pw.print("mode=");
                                    pw.print(ent.getMode());
                                    break;
                            }
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
                            android.Manifest.permission.UPDATE_APP_OPS_STATS,
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
                            android.Manifest.permission.UPDATE_APP_OPS_STATS,
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

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ApOps service from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

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
            boolean needSep = false;
            if (mOpModeWatchers.size() > 0) {
                needSep = true;
                pw.println("  Op mode watchers:");
                for (int i=0; i<mOpModeWatchers.size(); i++) {
                    pw.print("    Op "); pw.print(AppOpsManager.opToName(mOpModeWatchers.keyAt(i)));
                    pw.println(":");
                    ArrayList<Callback> callbacks = mOpModeWatchers.valueAt(i);
                    for (int j=0; j<callbacks.size(); j++) {
                        pw.print("      #"); pw.print(j); pw.print(": ");
                        pw.println(callbacks.get(j));
                    }
                }
            }
            if (mPackageModeWatchers.size() > 0) {
                needSep = true;
                pw.println("  Package mode watchers:");
                for (int i=0; i<mPackageModeWatchers.size(); i++) {
                    pw.print("    Pkg "); pw.print(mPackageModeWatchers.keyAt(i));
                    pw.println(":");
                    ArrayList<Callback> callbacks = mPackageModeWatchers.valueAt(i);
                    for (int j=0; j<callbacks.size(); j++) {
                        pw.print("      #"); pw.print(j); pw.print(": ");
                        pw.println(callbacks.get(j));
                    }
                }
            }
            if (mModeWatchers.size() > 0) {
                needSep = true;
                pw.println("  All mode watchers:");
                for (int i=0; i<mModeWatchers.size(); i++) {
                    pw.print("    "); pw.print(mModeWatchers.keyAt(i));
                    pw.print(" -> "); pw.println(mModeWatchers.valueAt(i));
                }
            }
            if (mClients.size() > 0) {
                needSep = true;
                pw.println("  Clients:");
                for (int i=0; i<mClients.size(); i++) {
                    pw.print("    "); pw.print(mClients.keyAt(i)); pw.println(":");
                    ClientState cs = mClients.valueAt(i);
                    pw.print("      "); pw.println(cs);
                    if (cs.mStartedOps != null && cs.mStartedOps.size() > 0) {
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
                        pw.print(": mode="); pw.println(r.mode);
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

                SparseIntArray opModes = uidState.opModes;
                if (opModes != null) {
                    final int opModeCount = opModes.size();
                    for (int j = 0; j < opModeCount; j++) {
                        final int code = opModes.keyAt(j);
                        final int mode = opModes.valueAt(j);
                        pw.print("      "); pw.print(AppOpsManager.opToName(code));
                        pw.print(": mode="); pw.println(mode);
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
                        pw.print(": mode="); pw.print(op.mode);
                        if (op.time != 0) {
                            pw.print("; time="); TimeUtils.formatDuration(now-op.time, pw);
                            pw.print(" ago");
                        }
                        if (op.rejectTime != 0) {
                            pw.print("; rejectTime="); TimeUtils.formatDuration(now-op.rejectTime, pw);
                            pw.print(" ago");
                        }
                        if (op.duration == -1) {
                            pw.print(" (running)");
                        } else if (op.duration != 0) {
                            pw.print("; duration="); TimeUtils.formatDuration(op.duration, pw);
                        }
                        pw.println();
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
            notifyWatchersOfChange(code);
        }

        if (restrictionState.isDefault()) {
            mOpUserRestrictions.remove(token);
            restrictionState.destroy();
        }
    }

    private void notifyWatchersOfChange(int code) {
        final ArrayList<Callback> clonedCallbacks;
        synchronized (this) {
            ArrayList<Callback> callbacks = mOpModeWatchers.get(code);
            if (callbacks == null) {
                return;
            }
            clonedCallbacks = new ArrayList<>(callbacks);
        }

        // There are components watching for mode changes such as window manager
        // and location manager which are in our process. The callbacks in these
        // components may require permissions our remote caller does not have.s
        final long identity = Binder.clearCallingIdentity();
        try {
            final int callbackCount = clonedCallbacks.size();
            for (int i = 0; i < callbackCount; i++) {
                Callback callback = clonedCallbacks.get(i);
                try {
                    callback.mCallback.opChanged(code, -1, null);
                } catch (RemoteException e) {
                    Log.w(TAG, "Error dispatching op op change", e);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void removeUser(int userHandle) throws RemoteException {
        checkSystemUid("removeUser");
        final int tokenCount = mOpUserRestrictions.size();
        for (int i = tokenCount - 1; i >= 0; i--) {
            ClientRestrictionState opRestrictions = mOpUserRestrictions.valueAt(i);
            opRestrictions.removeUser(userHandle);
        }
    }

    private void checkSystemUid(String function) {
        int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID) {
            throw new SecurityException(function + " must by called by the system");
        }
    }

    private static String resolvePackageName(int uid, String packageName)  {
        if (uid == 0) {
            return "root";
        } else if (uid == Process.SHELL_UID) {
            return "com.android.shell";
        } else if (uid == Process.SYSTEM_UID && packageName == null) {
            return "android";
        }
        return packageName;
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

            if (perUserRestrictions != null) {
                boolean[] userRestrictions = perUserRestrictions.get(userId);
                if (userRestrictions == null && restricted) {
                    userRestrictions = new boolean[AppOpsManager._NUM_OP];
                    perUserRestrictions.put(userId, userRestrictions);
                }
                if (userRestrictions != null && userRestrictions[code] != restricted) {
                    userRestrictions[code] = restricted;
                    if (!restricted && isDefault(userRestrictions)) {
                        perUserRestrictions.remove(userId);
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
                            perUserExcludedPackages.get(userId))) {
                        if (noExcludedPackages) {
                            perUserExcludedPackages.remove(userId);
                            if (perUserExcludedPackages.size() <= 0) {
                                perUserExcludedPackages = null;
                            }
                        } else {
                            perUserExcludedPackages.put(userId, excludedPackages);
                        }
                        changed = true;
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
                            mHandler.post(() -> notifyWatchersOfChange(changedCode));
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
