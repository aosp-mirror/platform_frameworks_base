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

import android.Manifest;
import com.android.internal.app.IAppOpsService;
import com.android.internal.app.IAppOpsCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;

/**
 * API for interacting with "application operation" tracking.  Allows you to:
 *
 * - Note when operations are happening, and find out if they are allowed for the current caller.
 * - Disallow specific apps from doing specific operations.
 * - Collect all of the current information about operations that have been executed or are not
 * being allowed.
 * - Monitor for changes in whether an operation is allowed.
 *
 * Each operation is identified by a single integer; these integers are a fixed set of
 * operations, enumerated by the OP_* constants.
 *
 * When checking operations, the result is a "mode" integer indicating the current setting
 * for the operation under that caller: MODE_ALLOWED, MODE_IGNORED (don't execute the operation but
 * fake its behavior enough so that the caller doesn't crash), MODE_ERRORED (through a
 * SecurityException back to the caller; the normal operation calls will do this for you).
 *
 * @hide
 */
public class AppOpsManager {
    final Context mContext;
    final IAppOpsService mService;
    final HashMap<Callback, IAppOpsCallback> mModeWatchers
            = new HashMap<Callback, IAppOpsCallback>();

    public static final int MODE_ALLOWED = 0;
    public static final int MODE_IGNORED = 1;
    public static final int MODE_ERRORED = 2;

    // when adding one of these:
    //  - increment _NUM_OP
    //  - add rows to sOpToSwitch, sOpNames, sOpPerms
    //  - add descriptive strings to Settings/res/values/arrays.xml
    public static final int OP_NONE = -1;
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
    public static final int OP_POST_NOTIFICATION = 11;
    public static final int OP_NEIGHBORING_CELLS = 12;
    public static final int OP_CALL_PHONE = 13;
    public static final int OP_READ_SMS = 14;
    public static final int OP_WRITE_SMS = 15;
    public static final int OP_RECEIVE_SMS = 16;
    public static final int OP_RECEIVE_EMERGECY_SMS = 17;
    public static final int OP_RECEIVE_MMS = 18;
    public static final int OP_RECEIVE_WAP_PUSH = 19;
    public static final int OP_SEND_SMS = 20;
    public static final int OP_READ_ICC_SMS = 21;
    public static final int OP_WRITE_ICC_SMS = 22;
    public static final int OP_WRITE_SETTINGS = 23;
    public static final int OP_SYSTEM_ALERT_WINDOW = 24;
    public static final int OP_ACCESS_NOTIFICATIONS = 25;
    public static final int OP_CAMERA = 26;
    public static final int OP_RECORD_AUDIO = 27;
    public static final int OP_PLAY_AUDIO = 28;
    public static final int OP_READ_CLIPBOARD = 29;
    public static final int OP_WRITE_CLIPBOARD = 30;
    /** @hide */
    public static final int _NUM_OP = 31;

    /**
     * This maps each operation to the operation that serves as the
     * switch to determine whether it is allowed.  Generally this is
     * a 1:1 mapping, but for some things (like location) that have
     * multiple low-level operations being tracked that should be
     * presented to hte user as one switch then this can be used to
     * make them all controlled by the same single operation.
     */
    private static int[] sOpToSwitch = new int[] {
            OP_COARSE_LOCATION,
            OP_COARSE_LOCATION,
            OP_COARSE_LOCATION,
            OP_VIBRATE,
            OP_READ_CONTACTS,
            OP_WRITE_CONTACTS,
            OP_READ_CALL_LOG,
            OP_WRITE_CALL_LOG,
            OP_READ_CALENDAR,
            OP_WRITE_CALENDAR,
            OP_COARSE_LOCATION,
            OP_POST_NOTIFICATION,
            OP_COARSE_LOCATION,
            OP_CALL_PHONE,
            OP_READ_SMS,
            OP_WRITE_SMS,
            OP_READ_SMS,
            OP_READ_SMS,
            OP_READ_SMS,
            OP_READ_SMS,
            OP_WRITE_SMS,
            OP_READ_SMS,
            OP_WRITE_SMS,
            OP_WRITE_SETTINGS,
            OP_SYSTEM_ALERT_WINDOW,
            OP_ACCESS_NOTIFICATIONS,
            OP_CAMERA,
            OP_RECORD_AUDIO,
            OP_PLAY_AUDIO,
            OP_READ_CLIPBOARD,
            OP_WRITE_CLIPBOARD,
    };

    /**
     * This provides a simple name for each operation to be used
     * in debug output.
     */
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
            "POST_NOTIFICATION",
            "NEIGHBORING_CELLS",
            "CALL_PHONE",
            "READ_SMS",
            "WRITE_SMS",
            "RECEIVE_SMS",
            "RECEIVE_EMERGECY_SMS",
            "RECEIVE_MMS",
            "RECEIVE_WAP_PUSH",
            "SEND_SMS",
            "READ_ICC_SMS",
            "WRITE_ICC_SMS",
            "WRITE_SETTINGS",
            "SYSTEM_ALERT_WINDOW",
            "ACCESS_NOTIFICATIONS",
            "CAMERA",
            "RECORD_AUDIO",
            "PLAY_AUDIO",
            "READ_CLIPBOARD",
            "WRITE_CLIPBOARD",
    };

    /**
     * This optionally maps a permission to an operation.  If there
     * is no permission associated with an operation, it is null.
     */
    private static String[] sOpPerms = new String[] {
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            null,
            android.Manifest.permission.VIBRATE,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.WRITE_CONTACTS,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.WRITE_CALL_LOG,
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.WRITE_CALENDAR,
            null, // no permission required for notifications
            android.Manifest.permission.ACCESS_WIFI_STATE,
            null, // neighboring cells shares the coarse location perm
            android.Manifest.permission.CALL_PHONE,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.WRITE_SMS,
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.RECEIVE_EMERGENCY_BROADCAST,
            android.Manifest.permission.RECEIVE_MMS,
            android.Manifest.permission.RECEIVE_WAP_PUSH,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.WRITE_SMS,
            android.Manifest.permission.WRITE_SETTINGS,
            android.Manifest.permission.SYSTEM_ALERT_WINDOW,
            android.Manifest.permission.ACCESS_NOTIFICATIONS,
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            null, // no permission for playing audio
            null, // no permission for reading clipboard
            null, // no permission for writing clipboard
    };

    /**
     * Retrieve the op switch that controls the given operation.
     */
    public static int opToSwitch(int op) {
        return sOpToSwitch[op];
    }

    /**
     * Retrieve a non-localized name for the operation, for debugging output.
     */
    public static String opToName(int op) {
        if (op == OP_NONE) return "NONE";
        return op < sOpNames.length ? sOpNames[op] : ("Unknown(" + op + ")");
    }

    /**
     * Retrieve the permission associated with an operation, or null if there is not one.
     */
    public static String opToPermission(int op) {
        return sOpPerms[op];
    }

    /**
     * Class holding all of the operation information associated with an app.
     */
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

    /**
     * Class holding the information about one unique operation of an application.
     */
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

    /**
     * Callback for notification of changes to operation state.
     */
    public interface Callback {
        public void opChanged(int op, String packageName);
    }

    public AppOpsManager(Context context, IAppOpsService service) {
        mContext = context;
        mService = service;
    }

    /**
     * Retrieve current operation state for all applications.
     *
     * @param ops The set of operations you are interested in, or null if you want all of them.
     */
    public List<AppOpsManager.PackageOps> getPackagesForOps(int[] ops) {
        try {
            return mService.getPackagesForOps(ops);
        } catch (RemoteException e) {
        }
        return null;
    }

    /**
     * Retrieve current operation state for one application.
     *
     * @param uid The uid of the application of interest.
     * @param packageName The name of the application of interest.
     * @param ops The set of operations you are interested in, or null if you want all of them.
     */
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

    /** @hide */
    public void resetAllModes() {
        try {
            mService.resetAllModes();
        } catch (RemoteException e) {
        }
    }

    public void startWatchingMode(int op, String packageName, final Callback callback) {
        synchronized (mModeWatchers) {
            IAppOpsCallback cb = mModeWatchers.get(callback);
            if (cb == null) {
                cb = new IAppOpsCallback.Stub() {
                    public void opChanged(int op, String packageName) {
                        callback.opChanged(op, packageName);
                    }
                };
                mModeWatchers.put(callback, cb);
            }
            try {
                mService.startWatchingMode(op, packageName, cb);
            } catch (RemoteException e) {
            }
        }
    }

    public void stopWatchingMode(Callback callback) {
        synchronized (mModeWatchers) {
            IAppOpsCallback cb = mModeWatchers.get(callback);
            if (cb != null) {
                try {
                    mService.stopWatchingMode(cb);
                } catch (RemoteException e) {
                }
            }
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
