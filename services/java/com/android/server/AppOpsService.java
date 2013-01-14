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
import java.io.PrintWriter;
import java.util.HashMap;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Binder;
import android.os.Environment;
import android.os.Process;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;

import com.android.internal.app.IAppOpsService;

public class AppOpsService extends IAppOpsService.Stub {
    static final String TAG = "AppOps";

    Context mContext;
    final AtomicFile mFile;

    final SparseArray<HashMap<String, Ops>> mUidOps
            = new SparseArray<HashMap<String, Ops>>();

    final static class Ops extends SparseArray<Op> {
        public final String packageName;

        public Ops(String _packageName) {
            packageName = _packageName;
        }
    }

    final static class Op {
        public final int op;
        public int duration;
        public long time;

        public Op(int _op) {
            op = _op;
        }
    }

    public AppOpsService() {
        mFile = new AtomicFile(new File(Environment.getSecureDataDirectory(), "appops.xml"));
    }
    
    public void publish(Context context) {
        mContext = context;
        ServiceManager.addService(Context.APP_OPS_SERVICE, asBinder());
    }

    public void shutdown() {
        Slog.w(TAG, "Writing app ops before shutdown...");
    }

    @Override
    public int noteOperation(int code, int uid, String packageName) {
        uid = handleIncomingUid(uid);
        synchronized (this) {
            Op op = getOpLocked(code, uid, packageName);
            if (op == null) {
                return AppOpsManager.MODE_IGNORED;
            }
            if (op.duration == -1) {
                Slog.w(TAG, "Noting op not finished: uid " + uid + " pkg " + packageName
                        + " code " + code + " time=" + op.time + " duration=" + op.duration);
            }
            op.time = System.currentTimeMillis();
            op.duration = 0;
        }
        return AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public int startOperation(int code, int uid, String packageName) {
        uid = handleIncomingUid(uid);
        synchronized (this) {
            Op op = getOpLocked(code, uid, packageName);
            if (op == null) {
                return AppOpsManager.MODE_IGNORED;
            }
            if (op.duration == -1) {
                Slog.w(TAG, "Starting op not finished: uid " + uid + " pkg " + packageName
                        + " code " + code + " time=" + op.time + " duration=" + op.duration);
            }
            op.time = System.currentTimeMillis();
            op.duration = -1;
        }
        return AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void finishOperation(int code, int uid, String packageName) {
        uid = handleIncomingUid(uid);
        synchronized (this) {
            Op op = getOpLocked(code, uid, packageName);
            if (op == null) {
                return;
            }
            if (op.duration != -1) {
                Slog.w(TAG, "Ignoring finishing op not started: uid " + uid + " pkg " + packageName
                        + " code " + code + " time=" + op.time + " duration=" + op.duration);
                return;
            }
            op.duration = (int)(System.currentTimeMillis() - op.time);
        }
    }

    @Override
    public int noteTimedOperation(int code, int uid, String packageName, int duration) {
        uid = handleIncomingUid(uid);
        synchronized (this) {
            Op op = getOpLocked(code, uid, packageName);
            if (op == null) {
                return AppOpsManager.MODE_IGNORED;
            }
            if (op.duration == -1) {
                Slog.w(TAG, "Noting op not finished: uid " + uid + " pkg " + packageName
                        + " code " + code + " time=" + op.time + " duration=" + op.duration);
            }
            op.time = System.currentTimeMillis();
            op.duration = duration;
        }
        return AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void earlyFinishOperation(int code, int uid, String packageName) {
        uid = handleIncomingUid(uid);
        synchronized (this) {
            Op op = getOpLocked(code, uid, packageName);
            if (op == null) {
                return;
            }
            if (op.duration != -1) {
                Slog.w(TAG, "Noting timed op not finished: uid " + uid + " pkg " + packageName
                        + " code " + code + " time=" + op.time + " duration=" + op.duration);
            }
            int newDuration = (int)(System.currentTimeMillis() - op.time);
            if (newDuration < op.duration) {
                op.duration = newDuration;
            }
        }
    }

    private int handleIncomingUid(int uid) {
        if (uid == Binder.getCallingUid()) {
            return uid;
        }
        if (Binder.getCallingPid() == Process.myPid()) {
            return uid;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
        return uid;
    }

    private Op getOpLocked(int code, int uid, String packageName) {
        HashMap<String, Ops> pkgOps = mUidOps.get(uid);
        if (pkgOps == null) {
            pkgOps = new HashMap<String, Ops>();
            mUidOps.put(uid, pkgOps);
        }
        Ops ops = pkgOps.get(packageName);
        if (ops == null) {
            // This is the first time we have seen this package name under this uid,
            // so let's make sure it is valid.
            final long ident = Binder.clearCallingIdentity();
            try {
                int pkgUid = -1;
                try {
                    pkgUid = mContext.getPackageManager().getPackageUid(packageName,
                            UserHandle.getUserId(uid));
                } catch (NameNotFoundException e) {
                }
                if (pkgUid != uid) {
                    // Oops!  The package name is not valid for the uid they are calling
                    // under.  Abort.
                    Slog.w(TAG, "Bad call: specified package " + packageName
                            + " under uid " + uid + " but it is really " + pkgUid);
                    return null;
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            ops = new Ops(packageName);
            pkgOps.put(packageName, ops);
        }
        Op op = ops.get(code);
        if (op == null) {
            op = new Op(code);
            ops.put(code, op);
        }
        return op;
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

        synchronized (this) {
            pw.println("Current AppOps Service state:");
            for (int i=0; i<mUidOps.size(); i++) {
                pw.print("  Uid "); UserHandle.formatUid(pw, mUidOps.keyAt(i)); pw.println(":");
                HashMap<String, Ops> pkgOps = mUidOps.valueAt(i);
                for (Ops ops : pkgOps.values()) {
                    pw.print("    Package "); pw.print(ops.packageName); pw.println(":");
                    for (int j=0; j<ops.size(); j++) {
                        Op op = ops.valueAt(j);
                        pw.print("      "); pw.print(AppOpsManager.opToString(op.op));
                        pw.print(": time=");
                        TimeUtils.formatDuration(System.currentTimeMillis()-op.time, pw);
                        pw.print(" ago");
                        if (op.duration == -1) {
                            pw.println(" (running)");
                        } else {
                            pw.print("; duration=");
                                    TimeUtils.formatDuration(op.duration, pw);
                                    pw.println();
                        }
                    }
                }
            }
        }
    }
}
