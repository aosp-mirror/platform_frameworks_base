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

import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;

/** @hide */
public class AppOpsManager {
    final Context mContext;
    final IAppOpsService mService;

    public static final int MODE_ALLOWED = 0;
    public static final int MODE_IGNORED = 1;
    public static final int MODE_ERRORED = 2;

    public static final int OP_COARSE_LOCATION = 0;
    public static final int OP_FINE_LOCATION = 1;
    public static final int OP_GPS = 2;
    public static final int OP_VIBRATE = 3;
    public static final int OP_READ_CONTACTS = 4;
    public static final int OP_WRITE_CONTACTS = 5;
    public static final int OP_READ_CALL_LOG = 6;
    public static final int OP_WRITE_CALL_LOG = 7;
    public static final int OP_READ_CALENDAR = 8;
    public static final int OP_WRITE_CALENDAR = 9;
    public static final int OP_WIFI_SCAN = 10;

    private static String[] sOpNames = new String[] {
        "COARSE_LOCATION",
        "FINE_LOCATION",
        "GPS",
        "VIBRATE",
        "READ_CONTACTS",
        "WRITE_CONTACTS",
        "READ_CALL_LOG",
        "WRITE_CALL_LOG",
        "READ_CALENDAR",
        "WRITE_CALENDAR",
        "WIFI_SCAN",
    };

    private static String[] sOpPerms = new String[] {
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.VIBRATE,
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.WRITE_CONTACTS,
        android.Manifest.permission.READ_CALL_LOG,
        android.Manifest.permission.WRITE_CALL_LOG,
        android.Manifest.permission.READ_CALENDAR,
        android.Manifest.permission.WRITE_CALENDAR,
        android.Manifest.permission.ACCESS_WIFI_STATE,
    };

    public static String opToName(int op) {
        return op < sOpNames.length ? sOpNames[op] : ("Unknown(" + op + ")");
    }

    public static String opToPermission(int op) {
        return sOpPerms[op];
    }

    public static class PackageOps implements Parcelable {
        private final String mPackageName;
        private final int mUid;
        private final List<OpEntry> mEntries;

        public PackageOps(String packageName, int uid, List<OpEntry> entries) {
            mPackageName = packageName;
            mUid = uid;
            mEntries = entries;
        }

        public String getPackageName() {
            return mPackageName;
        }

        public int getUid() {
            return mUid;
        }

        public List<OpEntry> getOps() {
            return mEntries;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(mPackageName);
            dest.writeInt(mUid);
            dest.writeInt(mEntries.size());
            for (int i=0; i<mEntries.size(); i++) {
                mEntries.get(i).writeToParcel(dest, flags);
            }
        }

        PackageOps(Parcel source) {
            mPackageName = source.readString();
            mUid = source.readInt();
            mEntries = new ArrayList<OpEntry>();
            final int N = source.readInt();
            for (int i=0; i<N; i++) {
                mEntries.add(OpEntry.CREATOR.createFromParcel(source));
            }
        }

        public static final Creator<PackageOps> CREATOR = new Creator<PackageOps>() {
            @Override public PackageOps createFromParcel(Parcel source) {
                return new PackageOps(source);
            }

            @Override public PackageOps[] newArray(int size) {
                return new PackageOps[size];
            }
        };
    }

    public static class OpEntry implements Parcelable {
        private final int mOp;
        private final int mMode;
        private final long mTime;
        private final long mRejectTime;
        private final int mDuration;

        public OpEntry(int op, int mode, long time, long rejectTime, int duration) {
            mOp = op;
            mMode = mode;
            mTime = time;
            mRejectTime = rejectTime;
            mDuration = duration;
        }

        public int getOp() {
            return mOp;
        }

        public int getMode() {
            return mMode;
        }

        public long getTime() {
            return mTime;
        }

        public long getRejectTime() {
            return mRejectTime;
        }

        public boolean isRunning() {
            return mDuration == -1;
        }

        public int getDuration() {
            return mDuration == -1 ? (int)(System.currentTimeMillis()-mTime) : mDuration;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mOp);
            dest.writeInt(mMode);
            dest.writeLong(mTime);
            dest.writeLong(mRejectTime);
            dest.writeInt(mDuration);
        }

        OpEntry(Parcel source) {
            mOp = source.readInt();
            mMode = source.readInt();
            mTime = source.readLong();
            mRejectTime = source.readLong();
            mDuration = source.readInt();
        }

        public static final Creator<OpEntry> CREATOR = new Creator<OpEntry>() {
            @Override public OpEntry createFromParcel(Parcel source) {
                return new OpEntry(source);
            }

            @Override public OpEntry[] newArray(int size) {
                return new OpEntry[size];
            }
        };
    }

    public AppOpsManager(Context context, IAppOpsService service) {
        mContext = context;
        mService = service;
    }

    public List<AppOpsManager.PackageOps> getPackagesForOps(int[] ops) {
        try {
            return mService.getPackagesForOps(ops);
        } catch (RemoteException e) {
        }
        return null;
    }

    public List<AppOpsManager.PackageOps> getOpsForPackage(int uid, String packageName, int[] ops) {
        try {
            return mService.getOpsForPackage(uid, packageName, ops);
        } catch (RemoteException e) {
        }
        return null;
    }

    public void setMode(int code, int uid, String packageName, int mode) {
        try {
            mService.setMode(code, uid, packageName, mode);
        } catch (RemoteException e) {
        }
    }

    public int checkOp(int op, int uid, String packageName) {
        try {
            int mode = mService.checkOperation(op, uid, packageName);
            if (mode == MODE_ERRORED) {
                throw new SecurityException("Operation not allowed");
            }
            return mode;
        } catch (RemoteException e) {
        }
        return MODE_IGNORED;
    }

    public int checkOpNoThrow(int op, int uid, String packageName) {
        try {
            return mService.checkOperation(op, uid, packageName);
        } catch (RemoteException e) {
        }
        return MODE_IGNORED;
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
        return noteOp(op, Process.myUid(), mContext.getBasePackageName());
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
        return startOp(op, Process.myUid(), mContext.getBasePackageName());
    }

    public void finishOp(int op, int uid, String packageName) {
        try {
            mService.finishOperation(op, uid, packageName);
        } catch (RemoteException e) {
        }
    }

    public void finishOp(int op) {
        finishOp(op, Process.myUid(), mContext.getBasePackageName());
    }
}
