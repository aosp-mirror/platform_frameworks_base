/*
 * Copyright (C) 2006-2007 The Android Open Source Project
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

package com.android.server.am;

import com.android.internal.app.IUsageStats;
import android.content.ComponentName;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import com.android.internal.os.PkgUsageStats;
import android.os.Process;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * This service collects the statistics associated with usage
 * of various components, like when a particular package is launched or
 * paused and aggregates events like number of time a component is launched
 * total duration of a component launch.
 */
public final class UsageStatsService extends IUsageStats.Stub {
    public static final String SERVICE_NAME = "usagestats";
    private static final boolean localLOGV = false;
    private static final String TAG = "UsageStats";
    static IUsageStats sService;
    private Context mContext;
    private String mFileName;
    final private Map<String, PkgUsageStatsExtended> mStats;
    private String mResumedPkg;
    
    private class PkgUsageStatsExtended {
        int mLaunchCount;
        long mUsageTime;
        long mChgTime;
        PkgUsageStatsExtended() {
            mLaunchCount = 0;
            mUsageTime = 0;
            mChgTime =  SystemClock.elapsedRealtime();
        }
        void updateResume() {
            mLaunchCount ++;
            mChgTime = SystemClock.elapsedRealtime();
        }
        void updatePause() {
            long currTime = SystemClock.elapsedRealtime();
            mUsageTime += (currTime - mChgTime);
            mChgTime = currTime;
        }
    }
    
    UsageStatsService(String filename) {
        mFileName = filename;
        mStats = new HashMap<String, PkgUsageStatsExtended>();
    }
    
    public void publish(Context context) {
        mContext = context;
        ServiceManager.addService(SERVICE_NAME, asBinder());
    }
    
    public static IUsageStats getService() {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(SERVICE_NAME);
        sService = asInterface(b);
        return sService;
    }
    
    public void noteResumeComponent(ComponentName componentName) {
        enforceCallingPermission();
        String pkgName;
        if ((componentName == null) ||
                ((pkgName = componentName.getPackageName()) == null)) {
            return;
        }
        if ((mResumedPkg != null) && (mResumedPkg.equalsIgnoreCase(pkgName))) {
            // Moving across activities in same package. just return
            return;
        } 
        if (localLOGV) Log.i(TAG, "started component:"+pkgName);
        PkgUsageStatsExtended pus = mStats.get(pkgName);
        if (pus == null) {
            pus = new PkgUsageStatsExtended();
            mStats.put(pkgName, pus);
        }
        pus.updateResume();
        mResumedPkg = pkgName;
    }

    public void notePauseComponent(ComponentName componentName) {
        enforceCallingPermission();
        String pkgName;
        if ((componentName == null) ||
                ((pkgName = componentName.getPackageName()) == null)) {
            return;
        }
        if ((mResumedPkg == null) || (!pkgName.equalsIgnoreCase(mResumedPkg))) {
            Log.w(TAG, "Something wrong here, Didn't expect "+pkgName+" to be paused");
            return;
        }
        if (localLOGV) Log.i(TAG, "paused component:"+pkgName);
        PkgUsageStatsExtended pus = mStats.get(pkgName);
        if (pus == null) {
            // Weird some error here
            Log.w(TAG, "No package stats for pkg:"+pkgName);
            return;
        }
        pus.updatePause();
    }
    
    public void enforceCallingPermission() {
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_DEVICE_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
    }
    
    public PkgUsageStats getPkgUsageStats(ComponentName componentName) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.PACKAGE_USAGE_STATS, null);
        String pkgName;
        if ((componentName == null) ||
                ((pkgName = componentName.getPackageName()) == null)) {
            return null;
        }
        PkgUsageStatsExtended pus = mStats.get(pkgName);
        if (pus == null) {
            return null;
        }
        return new PkgUsageStats(pkgName, pus.mLaunchCount, pus.mUsageTime);
    }
    
    public PkgUsageStats[] getAllPkgUsageStats() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.PACKAGE_USAGE_STATS, null);
        synchronized (mStats) {
            Set<String> keys = mStats.keySet();
            int size = keys.size();
            if (size <= 0) {
                return null;
            }
            PkgUsageStats retArr[] = new PkgUsageStats[size];
            int i = 0;
            for (String key: keys) {
                PkgUsageStatsExtended pus = mStats.get(key);
                retArr[i] = new PkgUsageStats(key, pus.mLaunchCount, pus.mUsageTime);
                i++;
            }
            return retArr;
        }
    }
    
    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        StringBuilder sb = new StringBuilder();
        synchronized (mStats) {
            Set<String> keys = mStats.keySet();
            for (String key: keys) {
                PkgUsageStatsExtended ps = mStats.get(key);
                sb.append("pkg="); 
                sb.append(key);
                sb.append(", launchCount=");
                sb.append(ps.mLaunchCount);
                sb.append(", usageTime=");
                sb.append(ps.mUsageTime+" ms\n");
            }
        }
        pw.write(sb.toString());
    }
}
