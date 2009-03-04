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
import android.os.Parcel;
import android.os.Process;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
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
    // structure used to maintain statistics since the last checkin.
    final private Map<String, PkgUsageStatsExtended> mStats;
    // Lock to update package stats. Methods suffixed by SLOCK should invoked with
    // this lock held
    final Object mStatsLock;
    // Lock to write to file. Methods suffixed by FLOCK should invoked with
    // this lock held.
    final Object mFileLock;
    // Order of locks is mFileLock followed by mStatsLock to avoid deadlocks
    private String mResumedPkg;
    private File mFile;
    //private File mBackupFile;
    private long mLastWriteRealTime;
    private int _FILE_WRITE_INTERVAL = 30*60*1000; //ms
    private static final String _PREFIX_DELIMIT=".";
    private String mFilePrefix;
    private Calendar mCal;
    private static final int  _MAX_NUM_FILES = 10;
    private long mLastTime;
    
    private class PkgUsageStatsExtended {
        int mLaunchCount;
        long mUsageTime;
        long mPausedTime;
        long mResumedTime;
        
        PkgUsageStatsExtended() {
            mLaunchCount = 0;
            mUsageTime = 0;
        }
        void updateResume() {
            mLaunchCount ++;
            mResumedTime = SystemClock.elapsedRealtime();
        }
        void updatePause() {
            mPausedTime =  SystemClock.elapsedRealtime();
            mUsageTime += (mPausedTime - mResumedTime);
        }
        void clear() {
            mLaunchCount = 0;
            mUsageTime = 0;
        }
    }
    
    UsageStatsService(String fileName) {
        mStats = new HashMap<String, PkgUsageStatsExtended>();
        mStatsLock = new Object();
        mFileLock = new Object();
        mFilePrefix = fileName;
        mCal = Calendar.getInstance();
        // Update current stats which are binned by date
        String uFileName = getCurrentDateStr(mFilePrefix);
        mFile = new File(uFileName);
        readStatsFromFile();
        mLastWriteRealTime = SystemClock.elapsedRealtime();
        mLastTime = new Date().getTime();
    }

    /*
     * Utility method to convert date into string.
     */
    private String getCurrentDateStr(String prefix) {
        mCal.setTime(new Date());
        StringBuilder sb = new StringBuilder();
        if (prefix != null) {
            sb.append(prefix);
            sb.append(".");
        }
        int mm = mCal.get(Calendar.MONTH) - Calendar.JANUARY +1;
        if (mm < 10) {
            sb.append("0");
        }
        sb.append(mm);
        int dd = mCal.get(Calendar.DAY_OF_MONTH);
        if (dd < 10) {
            sb.append("0");
        }
        sb.append(dd);
        sb.append(mCal.get(Calendar.YEAR));
        return sb.toString();
    }
    
    private Parcel getParcelForFile(File file) throws IOException {
        FileInputStream stream = new FileInputStream(file);
        byte[] raw = readFully(stream);
        Parcel in = Parcel.obtain();
        in.unmarshall(raw, 0, raw.length);
        in.setDataPosition(0);
        stream.close();
        return in;
    }
    
    private void readStatsFromFile() {
        File newFile = mFile;
        synchronized (mFileLock) {
            try {
                if (newFile.exists()) {
                    readStatsFLOCK(newFile);
                } else {
                    // Check for file limit before creating a new file
                    checkFileLimitFLOCK();
                    newFile.createNewFile();
                }
            } catch (IOException e) {
                Log.w(TAG,"Error : " + e + " reading data from file:" + newFile);
            }
        }
    }
    
    private void readStatsFLOCK(File file) throws IOException {
        Parcel in = getParcelForFile(file);
        while (in.dataAvail() > 0) {
            String pkgName = in.readString();
            PkgUsageStatsExtended pus = new PkgUsageStatsExtended();
            pus.mLaunchCount = in.readInt();
            pus.mUsageTime = in.readLong();
            synchronized (mStatsLock) {
                mStats.put(pkgName, pus);
            }
        }
    }

    private ArrayList<String> getUsageStatsFileListFLOCK() {
        File dir = getUsageFilesDir();
        if (dir == null) {
            Log.w(TAG, "Couldnt find writable directory for usage stats file");
            return null;
        }
        // Check if there are too many files in the system and delete older files
        String fList[] = dir.list();
        if (fList == null) {
            return null;
        }
        File pre = new File(mFilePrefix);
        String filePrefix = pre.getName();
        // file name followed by dot
        int prefixLen = filePrefix.length()+1;
        ArrayList<String> fileList = new ArrayList<String>();
        for (String file : fList) {
            int index = file.indexOf(filePrefix);
            if (index == -1) {
                continue;
            }
            if (file.endsWith(".bak")) {
                continue;
            }
            fileList.add(file);
        }
        return fileList;
    }
    
    private File getUsageFilesDir() {
        if (mFilePrefix == null) {
            return null;
        }
        File pre = new File(mFilePrefix);
        return new File(pre.getParent());
    }
    
    private void checkFileLimitFLOCK() {
        File dir = getUsageFilesDir();
        if (dir == null) {
            Log.w(TAG, "Couldnt find writable directory for usage stats file");
            return;
        }
        // Get all usage stats output files
        ArrayList<String> fileList = getUsageStatsFileListFLOCK();
        if (fileList == null) {
            // Strange but we dont have to delete any thing
            return;
        }
        int count = fileList.size();
        if (count <= _MAX_NUM_FILES) {
            return;
        }
        // Sort files
        Collections.sort(fileList);
        count -= _MAX_NUM_FILES;
        // Delete older files
        for (int i = 0; i < count; i++) {
            String fileName = fileList.get(i);
            File file = new File(dir, fileName);
            Log.i(TAG, "Deleting file : "+fileName);
            file.delete();
        }
    }
    
    private void writeStatsToFile() {
        synchronized (mFileLock) {
            long currTime = new Date().getTime();
            boolean dayChanged =  ((currTime - mLastTime) >= (24*60*60*1000));
            long currRealTime = SystemClock.elapsedRealtime();
            if (((currRealTime-mLastWriteRealTime) < _FILE_WRITE_INTERVAL) &&
                    (!dayChanged)) {
                // wait till the next update
                return;
            }
            // Get the most recent file
            String todayStr = getCurrentDateStr(mFilePrefix);
            // Copy current file to back up
            File backupFile =  new File(mFile.getPath() + ".bak");
            mFile.renameTo(backupFile);
            try {
                checkFileLimitFLOCK();
                mFile.createNewFile();
                // Write mStats to file
                writeStatsFLOCK();
                mLastWriteRealTime = currRealTime;
                mLastTime = currTime;
                if (dayChanged) {
                    // clear stats
                    synchronized (mStats) {
                        mStats.clear();
                    }
                    mFile = new File(todayStr);
                }
                // Delete the backup file
                if (backupFile != null) {
                    backupFile.delete();
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed writing stats to file:" + mFile);
                if (backupFile != null) {
                    backupFile.renameTo(mFile);
                }
            }
        }
    }

    private void writeStatsFLOCK() throws IOException {
        FileOutputStream stream = new FileOutputStream(mFile);
        Parcel out = Parcel.obtain();
        writeStatsToParcelFLOCK(out);
        stream.write(out.marshall());
        out.recycle();
        stream.flush();
        stream.close();
    }

    private void writeStatsToParcelFLOCK(Parcel out) {
        synchronized (mStatsLock) {
            Set<String> keys = mStats.keySet();
            for (String key : keys) {
                PkgUsageStatsExtended pus = mStats.get(key);
                out.writeString(key);
                out.writeInt(pus.mLaunchCount);
                out.writeLong(pus.mUsageTime);
            }
        }
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
        synchronized (mStatsLock) {
            PkgUsageStatsExtended pus = mStats.get(pkgName);
            if (pus == null) {
                pus = new PkgUsageStatsExtended();
                mStats.put(pkgName, pus);
            }
            pus.updateResume();
        }
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
        synchronized (mStatsLock) {
            PkgUsageStatsExtended pus = mStats.get(pkgName);
            if (pus == null) {
                // Weird some error here
                Log.w(TAG, "No package stats for pkg:"+pkgName);
                return;
            }
            pus.updatePause();
        }
        // Persist data to file
        writeStatsToFile();
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
        synchronized (mStatsLock) {
            PkgUsageStatsExtended pus = mStats.get(pkgName);
            if (pus == null) {
               return null;
            }
            return new PkgUsageStats(pkgName, pus.mLaunchCount, pus.mUsageTime);
        }
    }
    
    public PkgUsageStats[] getAllPkgUsageStats() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.PACKAGE_USAGE_STATS, null);
        synchronized (mStatsLock) {
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
    
    static byte[] readFully(FileInputStream stream) throws java.io.IOException {
        int pos = 0;
        int avail = stream.available();
        byte[] data = new byte[avail];
        while (true) {
            int amt = stream.read(data, pos, data.length-pos);
            if (amt <= 0) {
                return data;
            }
            pos += amt;
            avail = stream.available();
            if (avail > data.length-pos) {
                byte[] newData = new byte[pos+avail];
                System.arraycopy(data, 0, newData, 0, pos);
                data = newData;
            }
        }
    }
    
    private void collectDumpInfoFLOCK(PrintWriter pw, String[] args) {
        List<String> fileList = getUsageStatsFileListFLOCK();
        if (fileList == null) {
            return;
        }
        final boolean isCheckinRequest = scanArgs(args, "-c");
        Collections.sort(fileList);
        File usageFile = new File(mFilePrefix);
        String dirName = usageFile.getParent();
        File dir = new File(dirName);
        String filePrefix = usageFile.getName();
        // file name followed by dot
        int prefixLen = filePrefix.length()+1;
        String todayStr = getCurrentDateStr(null);
        for (String file : fileList) {
            File dFile = new File(dir, file);
            String dateStr = file.substring(prefixLen);
            try {
                Parcel in = getParcelForFile(dFile);
                collectDumpInfoFromParcelFLOCK(in, pw, dateStr, isCheckinRequest);
                if (isCheckinRequest && !todayStr.equalsIgnoreCase(dateStr)) {
                    // Delete old file after collecting info only for checkin requests
                    dFile.delete();
                }
            } catch (FileNotFoundException e) {
                Log.w(TAG, "Failed with "+e+" when collecting dump info from file : " + file);
                return;
            } catch (IOException e) {
                Log.w(TAG, "Failed with "+e+" when collecting dump info from file : "+file);
            }      
        }
    }
    
    private void collectDumpInfoFromParcelFLOCK(Parcel in, PrintWriter pw,
            String date, boolean isCheckinRequest) {
        StringBuilder sb = new StringBuilder();
        sb.append("Date:");
        sb.append(date);
        boolean first = true;
        while (in.dataAvail() > 0) {
            String pkgName = in.readString();
            int launchCount = in.readInt();
            long usageTime = in.readLong();
            if (isCheckinRequest) {
                if (!first) {
                    sb.append(",");
                }
                sb.append(pkgName);
                sb.append(",");
                sb.append(launchCount);
                sb.append(",");
                sb.append(usageTime);
                sb.append("ms");
            } else {
                if (first) {
                    sb.append("\n");
                }
                sb.append("pkg=");
                sb.append(pkgName);
                sb.append(", launchCount=");
                sb.append(launchCount);
                sb.append(", usageTime=");
                sb.append(usageTime);
                sb.append(" ms\n");
            }
            first = false;
        }
        pw.write(sb.toString());
    }
    
    /**
     * Searches array of arguments for the specified string
     * @param args array of argument strings
     * @param value value to search for
     * @return true if the value is contained in the array
     */
    private static boolean scanArgs(String[] args, String value) {
        if (args != null) {
            for (String arg : args) {
                if (value.equals(arg)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    @Override
    /*
     * The data persisted to file is parsed and the stats are computed. 
     */
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mFileLock) {
            collectDumpInfoFLOCK(pw, args);
        }
    }

}
