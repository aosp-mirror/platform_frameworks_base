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

package android.app;

import com.android.internal.app.IAppOpsService;

import android.content.Context;
import android.os.Process;
import android.os.RemoteException;

/** @hide */
public class AppOpsManager {
    final Context mContext;
    final IAppOpsService mService;

    public static final int MODE_ALLOWED = 0;
    public static final int MODE_IGNORED = 1;
    public static final int MODE_ERRORED = 2;

    public static final int OP_LOCATION = 0;
    public static final int OP_GPS = 1;
    public static final int OP_VIBRATE = 2;

    public static String opToString(int op) {
        switch (op) {
            case OP_LOCATION: return "LOCATION";
            case OP_GPS: return "GPS";
            case OP_VIBRATE: return "VIBRATE";
            default: return "Unknown(" + op + ")";
        }
    }

    public AppOpsManager(Context context, IAppOpsService service) {
        mContext = context;
        mService = service;
    }

    public int noteOp(int op, int uid, String packageName) {
        try {
            int mode = mService.noteOperation(op, uid, packageName);
            if (mode == MODE_ERRORED) {
                throw new SecurityException("Operation not allowed");
            }
            return mode;
        } catch (RemoteException e) {
        }
        return MODE_IGNORED;
    }

    public int noteOpNoThrow(int op, int uid, String packageName) {
        try {
            return mService.noteOperation(op, uid, packageName);
        } catch (RemoteException e) {
        }
        return MODE_IGNORED;
    }

    public int noteOp(int op) {
        return noteOp(op, Process.myUid(), mContext.getPackageName());
    }

    public int startOp(int op, int uid, String packageName) {
        try {
            int mode = mService.startOperation(op, uid, packageName);
            if (mode == MODE_ERRORED) {
                throw new SecurityException("Operation not allowed");
            }
            return mode;
        } catch (RemoteException e) {
        }
        return MODE_IGNORED;
    }

    public int startOpNoThrow(int op, int uid, String packageName) {
        try {
            return mService.startOperation(op, uid, packageName);
        } catch (RemoteException e) {
        }
        return MODE_IGNORED;
    }

    public int startOp(int op) {
        return startOp(op, Process.myUid(), mContext.getPackageName());
    }

    public void finishOp(int op, int uid, String packageName) {
        try {
            mService.finishOperation(op, uid, packageName);
        } catch (RemoteException e) {
        }
    }

    public void finishOp(int op) {
        finishOp(op, Process.myUid(), mContext.getPackageName());
    }
}
