/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.SparseArray;
import java.util.List;

/**
 * Helper for monitoring the current importance of applications.
 * @hide
 */
public class AppImportanceMonitor {
    final Context mContext;

    final SparseArray<AppEntry> mApps = new SparseArray<>();

    static class AppEntry {
        final int uid;
        final SparseArray<Integer> procs = new SparseArray<>(1);
        int importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE;

        AppEntry(int _uid) {
            uid = _uid;
        }
    }

    final IProcessObserver mProcessObserver = new IProcessObserver.Stub() {
        @Override
        public void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) {
        }

        @Override
        public void onProcessStateChanged(int pid, int uid, int procState) {
            synchronized (mApps) {
                updateImportanceLocked(pid, uid,
                        ActivityManager.RunningAppProcessInfo.procStateToImportance(procState),
                        true);
            }
        }

        @Override
        public void onProcessDied(int pid, int uid) {
            synchronized (mApps) {
                updateImportanceLocked(pid, uid,
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE, true);
            }
        }
    };

    static final int MSG_UPDATE = 1;

    final Handler mHandler;

    public AppImportanceMonitor(Context context, Looper looper) {
        mContext = context;
        mHandler = new Handler(looper) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_UPDATE:
                        onImportanceChanged(msg.arg1, msg.arg2&0xffff, msg.arg2>>16);
                        break;
                    default:
                        super.handleMessage(msg);
                }
            }
        };
        ActivityManager am = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        try {
            ActivityManagerNative.getDefault().registerProcessObserver(mProcessObserver);
        } catch (RemoteException e) {
        }
        List<ActivityManager.RunningAppProcessInfo> apps = am.getRunningAppProcesses();
        if (apps != null) {
            for (int i=0; i<apps.size(); i++) {
                ActivityManager.RunningAppProcessInfo app = apps.get(i);
                updateImportanceLocked(app.uid, app.pid, app.importance, false);
            }
        }
    }

    public int getImportance(int uid) {
        AppEntry ent = mApps.get(uid);
        if (ent == null) {
            return ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE;
        }
        return ent.importance;
    }

    /**
     * Report when an app's importance changed. Called on looper given to constructor.
     */
    public void onImportanceChanged(int uid, int importance, int oldImportance) {
    }

    void updateImportanceLocked(int uid, int pid, int importance, boolean repChange) {
        AppEntry ent = mApps.get(uid);
        if (ent == null) {
            ent = new AppEntry(uid);
            mApps.put(uid, ent);
        }
        if (importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE) {
            ent.procs.remove(pid);
        } else {
            ent.procs.put(pid, importance);
        }
        updateImportanceLocked(ent, repChange);
    }

    void updateImportanceLocked(AppEntry ent, boolean repChange) {
        int appImp = ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE;
        for (int i=0; i<ent.procs.size(); i++) {
            int procImp = ent.procs.valueAt(i);
            if (procImp < appImp) {
                appImp = procImp;
            }
        }
        if (appImp != ent.importance) {
            int impCode = appImp | (ent.importance<<16);
            ent.importance = appImp;
            if (appImp >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE) {
                mApps.remove(ent.uid);
            }
            if (repChange) {
                mHandler.obtainMessage(MSG_UPDATE, ent.uid, impCode).sendToTarget();
            }
        }
    }
}
