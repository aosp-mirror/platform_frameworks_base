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

import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.FileUtils;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.app.IUsageStats;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.PkgUsageStats;
import com.android.internal.util.FastXmlSerializer;

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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This service collects the statistics associated with usage
 * of various components, like when a particular package is launched or
 * paused and aggregates events like number of time a component is launched
 * total duration of a component launch.
 */
public final class UsageStatsService extends IUsageStats.Stub {
    public static final String SERVICE_NAME = "usagestats";
    private static final boolean localLOGV = false;
    private static final boolean REPORT_UNEXPECTED = false;
    private static final String TAG = "UsageStats";
    
    // Current on-disk Parcel version
    private static final int VERSION = 1008;

    private static final int CHECKIN_VERSION = 4;
    
    private static final String FILE_PREFIX = "usage-";

    private static final String FILE_HISTORY = FILE_PREFIX + "history.xml";

    private static final int FILE_WRITE_INTERVAL = 30*60*1000; //ms
    
    private static final int MAX_NUM_FILES = 5;
    
    private static final int NUM_LAUNCH_TIME_BINS = 10;
    private static final int[] LAUNCH_TIME_BINS = {
        250, 500, 750, 1000, 1500, 2000, 3000, 4000, 5000
    };
    
    static IUsageStats sService;
    private Context mContext;
    // structure used to maintain statistics since the last checkin.
    final private ArrayMap<String, PkgUsageStatsExtended> mStats;

    // Maintains the last time any component was resumed, for all time.
    final private ArrayMap<String, ArrayMap<String, Long>> mLastResumeTimes;

    // To remove last-resume time stats when a pacakge is removed.
    private PackageMonitor mPackageMonitor;

    // Lock to update package stats. Methods suffixed by SLOCK should invoked with
    // this lock held
    final Object mStatsLock;
    // Lock to write to file. Methods suffixed by FLOCK should invoked with
    // this lock held.
    final Object mFileLock;
    // Order of locks is mFileLock followed by mStatsLock to avoid deadlocks
    private String mLastResumedPkg;
    private String mLastResumedComp;
    private boolean mIsResumed;
    private File mFile;
    private AtomicFile mHistoryFile;
    private String mFileLeaf;
    private File mDir;

    private Calendar mCal; // guarded by itself

    private final AtomicInteger mLastWriteDay = new AtomicInteger(-1);
    private final AtomicLong mLastWriteElapsedTime = new AtomicLong(0);
    private final AtomicBoolean mUnforcedDiskWriteRunning = new AtomicBoolean(false);
    
    static class TimeStats {
        int count;
        int[] times = new int[NUM_LAUNCH_TIME_BINS];
        
        TimeStats() {
        }
        
        void incCount() {
            count++;
        }
        
        void add(int val) {
            final int[] bins = LAUNCH_TIME_BINS;
            for (int i=0; i<NUM_LAUNCH_TIME_BINS-1; i++) {
                if (val < bins[i]) {
                    times[i]++;
                    return;
                }
            }
            times[NUM_LAUNCH_TIME_BINS-1]++;
        }
        
        TimeStats(Parcel in) {
            count = in.readInt();
            final int[] localTimes = times;
            for (int i=0; i<NUM_LAUNCH_TIME_BINS; i++) {
                localTimes[i] = in.readInt();
            }
        }
        
        void writeToParcel(Parcel out) {
            out.writeInt(count);
            final int[] localTimes = times;
            for (int i=0; i<NUM_LAUNCH_TIME_BINS; i++) {
                out.writeInt(localTimes[i]);
            }
        }
    }
    
    private class PkgUsageStatsExtended {
        final ArrayMap<String, TimeStats> mLaunchTimes
                = new ArrayMap<String, TimeStats>();
        final ArrayMap<String, TimeStats> mFullyDrawnTimes
                = new ArrayMap<String, TimeStats>();
        int mLaunchCount;
        long mUsageTime;
        long mPausedTime;
        long mResumedTime;
        
        PkgUsageStatsExtended() {
            mLaunchCount = 0;
            mUsageTime = 0;
        }
        
        PkgUsageStatsExtended(Parcel in) {
            mLaunchCount = in.readInt();
            mUsageTime = in.readLong();
            if (localLOGV) Slog.v(TAG, "Launch count: " + mLaunchCount
                    + ", Usage time:" + mUsageTime);
            
            final int numLaunchTimeStats = in.readInt();
            if (localLOGV) Slog.v(TAG, "Reading launch times: " + numLaunchTimeStats);
            mLaunchTimes.ensureCapacity(numLaunchTimeStats);
            for (int i=0; i<numLaunchTimeStats; i++) {
                String comp = in.readString();
                if (localLOGV) Slog.v(TAG, "Component: " + comp);
                TimeStats times = new TimeStats(in);
                mLaunchTimes.put(comp, times);
            }

            final int numFullyDrawnTimeStats = in.readInt();
            if (localLOGV) Slog.v(TAG, "Reading fully drawn times: " + numFullyDrawnTimeStats);
            mFullyDrawnTimes.ensureCapacity(numFullyDrawnTimeStats);
            for (int i=0; i<numFullyDrawnTimeStats; i++) {
                String comp = in.readString();
                if (localLOGV) Slog.v(TAG, "Component: " + comp);
                TimeStats times = new TimeStats(in);
                mFullyDrawnTimes.put(comp, times);
            }
        }

        void updateResume(String comp, boolean launched) {
            if (launched) {
                mLaunchCount ++;
            }
            mResumedTime = SystemClock.elapsedRealtime();
        }
        
        void updatePause() {
            mPausedTime =  SystemClock.elapsedRealtime();
            mUsageTime += (mPausedTime - mResumedTime);
        }
        
        void addLaunchCount(String comp) {
            TimeStats times = mLaunchTimes.get(comp);
            if (times == null) {
                times = new TimeStats();
                mLaunchTimes.put(comp, times);
            }
            times.incCount();
        }
        
        void addLaunchTime(String comp, int millis) {
            TimeStats times = mLaunchTimes.get(comp);
            if (times == null) {
                times = new TimeStats();
                mLaunchTimes.put(comp, times);
            }
            times.add(millis);
        }

        void addFullyDrawnTime(String comp, int millis) {
            TimeStats times = mFullyDrawnTimes.get(comp);
            if (times == null) {
                times = new TimeStats();
                mFullyDrawnTimes.put(comp, times);
            }
            times.add(millis);
        }

        void writeToParcel(Parcel out) {
            out.writeInt(mLaunchCount);
            out.writeLong(mUsageTime);
            final int numLaunchTimeStats = mLaunchTimes.size();
            out.writeInt(numLaunchTimeStats);
            for (int i=0; i<numLaunchTimeStats; i++) {
                out.writeString(mLaunchTimes.keyAt(i));
                mLaunchTimes.valueAt(i).writeToParcel(out);
            }
            final int numFullyDrawnTimeStats = mFullyDrawnTimes.size();
            out.writeInt(numFullyDrawnTimeStats);
            for (int i=0; i<numFullyDrawnTimeStats; i++) {
                out.writeString(mFullyDrawnTimes.keyAt(i));
                mFullyDrawnTimes.valueAt(i).writeToParcel(out);
            }
        }
        
        void clear() {
            mLaunchTimes.clear();
            mFullyDrawnTimes.clear();
            mLaunchCount = 0;
            mUsageTime = 0;
        }
    }
    
    UsageStatsService(String dir) {
        mStats = new ArrayMap<String, PkgUsageStatsExtended>();
        mLastResumeTimes = new ArrayMap<String, ArrayMap<String, Long>>();
        mStatsLock = new Object();
        mFileLock = new Object();
        mDir = new File(dir);
        mCal = Calendar.getInstance(TimeZone.getTimeZone("GMT+0"));
        
        mDir.mkdir();
        
        // Remove any old usage files from previous versions.
        File parentDir = mDir.getParentFile();
        String fList[] = parentDir.list();
        if (fList != null) {
            String prefix = mDir.getName() + ".";
            int i = fList.length;
            while (i > 0) {
                i--;
                if (fList[i].startsWith(prefix)) {
                    Slog.i(TAG, "Deleting old usage file: " + fList[i]);
                    (new File(parentDir, fList[i])).delete();
                }
            }
        }
        
        // Update current stats which are binned by date
        mFileLeaf = getCurrentDateStr(FILE_PREFIX);
        mFile = new File(mDir, mFileLeaf);
        mHistoryFile = new AtomicFile(new File(mDir, FILE_HISTORY));
        readStatsFromFile();
        readHistoryStatsFromFile();
        mLastWriteElapsedTime.set(SystemClock.elapsedRealtime());
        // mCal was set by getCurrentDateStr(), want to use that same time.
        mLastWriteDay.set(mCal.get(Calendar.DAY_OF_YEAR));
    }

    /*
     * Utility method to convert date into string.
     */
    private String getCurrentDateStr(String prefix) {
        StringBuilder sb = new StringBuilder();
        synchronized (mCal) {
            mCal.setTimeInMillis(System.currentTimeMillis());
            if (prefix != null) {
                sb.append(prefix);
            }
            sb.append(mCal.get(Calendar.YEAR));
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
        }
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
                Slog.w(TAG,"Error : " + e + " reading data from file:" + newFile);
            }
        }
    }
    
    private void readStatsFLOCK(File file) throws IOException {
        Parcel in = getParcelForFile(file);
        int vers = in.readInt();
        if (vers != VERSION) {
            Slog.w(TAG, "Usage stats version changed; dropping");
            return;
        }
        int N = in.readInt();
        while (N > 0) {
            N--;
            String pkgName = in.readString();
            if (pkgName == null) {
                break;
            }
            if (localLOGV) Slog.v(TAG, "Reading package #" + N + ": " + pkgName);
            PkgUsageStatsExtended pus = new PkgUsageStatsExtended(in);
            synchronized (mStatsLock) {
                mStats.put(pkgName, pus);
            }
        }
    }

    private void readHistoryStatsFromFile() {
        synchronized (mFileLock) {
            if (mHistoryFile.getBaseFile().exists()) {
                readHistoryStatsFLOCK(mHistoryFile);
            }
        }
    }

    private void readHistoryStatsFLOCK(AtomicFile file) {
        FileInputStream fis = null;
        try {
            fis = mHistoryFile.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, null);
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG) {
                eventType = parser.next();
            }
            String tagName = parser.getName();
            if ("usage-history".equals(tagName)) {
                String pkg = null;
                do {
                    eventType = parser.next();
                    if (eventType == XmlPullParser.START_TAG) {
                        tagName = parser.getName();
                        int depth = parser.getDepth();
                        if ("pkg".equals(tagName) && depth == 2) {
                            pkg = parser.getAttributeValue(null, "name");
                        } else if ("comp".equals(tagName) && depth == 3 && pkg != null) {
                            String comp = parser.getAttributeValue(null, "name");
                            String lastResumeTimeStr = parser.getAttributeValue(null, "lrt");
                            if (comp != null && lastResumeTimeStr != null) {
                                try {
                                    long lastResumeTime = Long.parseLong(lastResumeTimeStr);
                                    synchronized (mStatsLock) {
                                        ArrayMap<String, Long> lrt = mLastResumeTimes.get(pkg);
                                        if (lrt == null) {
                                            lrt = new ArrayMap<String, Long>();
                                            mLastResumeTimes.put(pkg, lrt);
                                        }
                                        lrt.put(comp, lastResumeTime);
                                    }
                                } catch (NumberFormatException e) {
                                }
                            }
                        }
                    } else if (eventType == XmlPullParser.END_TAG) {
                        if ("pkg".equals(parser.getName())) {
                            pkg = null;
                        }
                    }
                } while (eventType != XmlPullParser.END_DOCUMENT);
            }
        } catch (XmlPullParserException e) {
            Slog.w(TAG,"Error reading history stats: " + e);
        } catch (IOException e) {
            Slog.w(TAG,"Error reading history stats: " + e);
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private ArrayList<String> getUsageStatsFileListFLOCK() {
        // Check if there are too many files in the system and delete older files
        String fList[] = mDir.list();
        if (fList == null) {
            return null;
        }
        ArrayList<String> fileList = new ArrayList<String>();
        for (String file : fList) {
            if (!file.startsWith(FILE_PREFIX)) {
                continue;
            }
            if (file.endsWith(".bak")) {
                (new File(mDir, file)).delete();
                continue;
            }
            fileList.add(file);
        }
        return fileList;
    }
    
    private void checkFileLimitFLOCK() {
        // Get all usage stats output files
        ArrayList<String> fileList = getUsageStatsFileListFLOCK();
        if (fileList == null) {
            // Strange but we dont have to delete any thing
            return;
        }
        int count = fileList.size();
        if (count <= MAX_NUM_FILES) {
            return;
        }
        // Sort files
        Collections.sort(fileList);
        count -= MAX_NUM_FILES;
        // Delete older files
        for (int i = 0; i < count; i++) {
            String fileName = fileList.get(i);
            File file = new File(mDir, fileName);
            Slog.i(TAG, "Deleting usage file : " + fileName);
            file.delete();
        }
    }

    /**
     * Conditionally start up a disk write if it's been awhile, or the
     * day has rolled over.
     *
     * This is called indirectly from user-facing actions (when
     * 'force' is false) so it tries to be quick, without writing to
     * disk directly or acquiring heavy locks.
     *
     * @params force  do an unconditional, synchronous stats flush
     *                to disk on the current thread.
     * @params forceWriteHistoryStats Force writing of historical stats.
     */
    private void writeStatsToFile(final boolean force, final boolean forceWriteHistoryStats) {
        int curDay;
        synchronized (mCal) {
            mCal.setTimeInMillis(System.currentTimeMillis());
            curDay = mCal.get(Calendar.DAY_OF_YEAR);
        }
        final boolean dayChanged = curDay != mLastWriteDay.get();

        // Determine if the day changed...  note that this will be wrong
        // if the year has changed but we are in the same day of year...
        // we can probably live with this.
        final long currElapsedTime = SystemClock.elapsedRealtime();

        // Fast common path, without taking the often-contentious
        // mFileLock.
        if (!force) {
            if (!dayChanged &&
                (currElapsedTime - mLastWriteElapsedTime.get()) < FILE_WRITE_INTERVAL) {
                // wait till the next update
                return;
            }
            if (mUnforcedDiskWriteRunning.compareAndSet(false, true)) {
                new Thread("UsageStatsService_DiskWriter") {
                    public void run() {
                        try {
                            if (localLOGV) Slog.d(TAG, "Disk writer thread starting.");
                            writeStatsToFile(true, false);
                        } finally {
                            mUnforcedDiskWriteRunning.set(false);
                            if (localLOGV) Slog.d(TAG, "Disk writer thread ending.");
                        }
                    }
                }.start();
            }
            return;
        }

        synchronized (mFileLock) {
            // Get the most recent file
            mFileLeaf = getCurrentDateStr(FILE_PREFIX);
            // Copy current file to back up
            File backupFile = null;
            if (mFile != null && mFile.exists()) {
                backupFile = new File(mFile.getPath() + ".bak");
                if (!backupFile.exists()) {
                    if (!mFile.renameTo(backupFile)) {
                        Slog.w(TAG, "Failed to persist new stats");
                        return;
                    }
                } else {
                    mFile.delete();
                }
            }

            try {
                // Write mStats to file
                writeStatsFLOCK(mFile);
                mLastWriteElapsedTime.set(currElapsedTime);
                if (dayChanged) {
                    mLastWriteDay.set(curDay);
                    // clear stats
                    synchronized (mStats) {
                        mStats.clear();
                    }
                    mFile = new File(mDir, mFileLeaf);
                    checkFileLimitFLOCK();
                }

                if (dayChanged || forceWriteHistoryStats) {
                    // Write history stats daily, or when forced (due to shutdown).
                    writeHistoryStatsFLOCK(mHistoryFile);
                }

                // Delete the backup file
                if (backupFile != null) {
                    backupFile.delete();
                }
            } catch (IOException e) {
                Slog.w(TAG, "Failed writing stats to file:" + mFile);
                if (backupFile != null) {
                    mFile.delete();
                    backupFile.renameTo(mFile);
                }
            }
        }
        if (localLOGV) Slog.d(TAG, "Dumped usage stats.");
    }

    private void writeStatsFLOCK(File file) throws IOException {
        FileOutputStream stream = new FileOutputStream(file);
        try {
            Parcel out = Parcel.obtain();
            writeStatsToParcelFLOCK(out);
            stream.write(out.marshall());
            out.recycle();
            stream.flush();
        } finally {
            FileUtils.sync(stream);
            stream.close();
        }
    }

    private void writeStatsToParcelFLOCK(Parcel out) {
        synchronized (mStatsLock) {
            out.writeInt(VERSION);
            Set<String> keys = mStats.keySet();
            out.writeInt(keys.size());
            for (String key : keys) {
                PkgUsageStatsExtended pus = mStats.get(key);
                out.writeString(key);
                pus.writeToParcel(out);
            }
        }
    }

    /** Filter out stats for any packages which aren't present anymore. */
    private void filterHistoryStats() {
        synchronized (mStatsLock) {
            IPackageManager pm = AppGlobals.getPackageManager();
            for (int i=0; i<mLastResumeTimes.size(); i++) {
                String pkg = mLastResumeTimes.keyAt(i);
                try {
                    if (pm.getPackageUid(pkg, 0) < 0) {
                        mLastResumeTimes.removeAt(i);
                        i--;
                    }
                } catch (RemoteException e) {
                }
            }
        }
    }

    private void writeHistoryStatsFLOCK(AtomicFile historyFile) {
        FileOutputStream fos = null;
        try {
            fos = historyFile.startWrite();
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, "utf-8");
            out.startDocument(null, true);
            out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            out.startTag(null, "usage-history");
            synchronized (mStatsLock) {
                for (int i=0; i<mLastResumeTimes.size(); i++) {
                    out.startTag(null, "pkg");
                    out.attribute(null, "name", mLastResumeTimes.keyAt(i));
                    ArrayMap<String, Long> comp = mLastResumeTimes.valueAt(i);
                    for (int j=0; j<comp.size(); j++) {
                        out.startTag(null, "comp");
                        out.attribute(null, "name", comp.keyAt(j));
                        out.attribute(null, "lrt", comp.valueAt(j).toString());
                        out.endTag(null, "comp");
                    }
                    out.endTag(null, "pkg");
                }
            }
            out.endTag(null, "usage-history");
            out.endDocument();

            historyFile.finishWrite(fos);
        } catch (IOException e) {
            Slog.w(TAG,"Error writing history stats" + e);
            if (fos != null) {
                historyFile.failWrite(fos);
            }
        }
    }

    public void publish(Context context) {
        mContext = context;
        ServiceManager.addService(SERVICE_NAME, asBinder());
    }

    /**
     * Start watching packages to remove stats when a package is uninstalled.
     * May only be called when the package manager is ready.
     */
    public void monitorPackages() {
        mPackageMonitor = new PackageMonitor() {
            @Override
            public void onPackageRemovedAllUsers(String packageName, int uid) {
                synchronized (mStatsLock) {
                    mLastResumeTimes.remove(packageName);
                }
            }
        };
        mPackageMonitor.register(mContext, null, true);
        filterHistoryStats();
    }

    public void shutdown() {
        if (mPackageMonitor != null) {
            mPackageMonitor.unregister();
        }
        Slog.i(TAG, "Writing usage stats before shutdown...");
        writeStatsToFile(true, true);
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
        synchronized (mStatsLock) {
            if ((componentName == null) ||
                    ((pkgName = componentName.getPackageName()) == null)) {
                return;
            }
            
            final boolean samePackage = pkgName.equals(mLastResumedPkg);
            if (mIsResumed) {
                if (mLastResumedPkg != null) {
                    // We last resumed some other package...  just pause it now
                    // to recover.
                    if (REPORT_UNEXPECTED) Slog.i(TAG, "Unexpected resume of " + pkgName
                            + " while already resumed in " + mLastResumedPkg);
                    PkgUsageStatsExtended pus = mStats.get(mLastResumedPkg);
                    if (pus != null) {
                        pus.updatePause();
                    }
                }
            }
            
            final boolean sameComp = samePackage
                    && componentName.getClassName().equals(mLastResumedComp);
            
            mIsResumed = true;
            mLastResumedPkg = pkgName;
            mLastResumedComp = componentName.getClassName();
            
            if (localLOGV) Slog.i(TAG, "started component:" + pkgName);
            PkgUsageStatsExtended pus = mStats.get(pkgName);
            if (pus == null) {
                pus = new PkgUsageStatsExtended();
                mStats.put(pkgName, pus);
            }
            pus.updateResume(mLastResumedComp, !samePackage);
            if (!sameComp) {
                pus.addLaunchCount(mLastResumedComp);
            }

            ArrayMap<String, Long> componentResumeTimes = mLastResumeTimes.get(pkgName);
            if (componentResumeTimes == null) {
                componentResumeTimes = new ArrayMap<String, Long>();
                mLastResumeTimes.put(pkgName, componentResumeTimes);
            }
            componentResumeTimes.put(mLastResumedComp, System.currentTimeMillis());
        }
    }

    public void notePauseComponent(ComponentName componentName) {
        enforceCallingPermission();
        
        synchronized (mStatsLock) {
            String pkgName;
            if ((componentName == null) ||
                    ((pkgName = componentName.getPackageName()) == null)) {
                return;
            }
            if (!mIsResumed) {
                if (REPORT_UNEXPECTED) Slog.i(TAG, "Something wrong here, didn't expect "
                        + pkgName + " to be paused");
                return;
            }
            mIsResumed = false;
            
            if (localLOGV) Slog.i(TAG, "paused component:"+pkgName);
        
            PkgUsageStatsExtended pus = mStats.get(pkgName);
            if (pus == null) {
                // Weird some error here
                Slog.i(TAG, "No package stats for pkg:"+pkgName);
                return;
            }
            pus.updatePause();
        }
        
        // Persist current data to file if needed.
        writeStatsToFile(false, false);
    }
    
    public void noteLaunchTime(ComponentName componentName, int millis) {
        enforceCallingPermission();
        String pkgName;
        if ((componentName == null) ||
                ((pkgName = componentName.getPackageName()) == null)) {
            return;
        }
        
        // Persist current data to file if needed.
        writeStatsToFile(false, false);
        
        synchronized (mStatsLock) {
            PkgUsageStatsExtended pus = mStats.get(pkgName);
            if (pus != null) {
                pus.addLaunchTime(componentName.getClassName(), millis);
            }
        }
    }
    
    public void noteFullyDrawnTime(ComponentName componentName, int millis) {
        enforceCallingPermission();
        String pkgName;
        if ((componentName == null) ||
                ((pkgName = componentName.getPackageName()) == null)) {
            return;
        }

        // Persist current data to file if needed.
        writeStatsToFile(false, false);

        synchronized (mStatsLock) {
            PkgUsageStatsExtended pus = mStats.get(pkgName);
            if (pus != null) {
                pus.addFullyDrawnTime(componentName.getClassName(), millis);
            }
        }
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
            Map<String, Long> lastResumeTimes = mLastResumeTimes.get(pkgName);
            if (pus == null && lastResumeTimes == null) {
                return null;
            }
            int launchCount = pus != null ? pus.mLaunchCount : 0;
            long usageTime = pus != null ? pus.mUsageTime : 0;
            return new PkgUsageStats(pkgName, launchCount, usageTime, lastResumeTimes);
        }
    }
    
    public PkgUsageStats[] getAllPkgUsageStats() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.PACKAGE_USAGE_STATS, null);
        synchronized (mStatsLock) {
            int size = mLastResumeTimes.size();
            if (size <= 0) {
                return null;
            }
            PkgUsageStats retArr[] = new PkgUsageStats[size];
            for (int i=0; i<size; i++) {
                String pkg = mLastResumeTimes.keyAt(i);
                long usageTime = 0;
                int launchCount = 0;

                PkgUsageStatsExtended pus = mStats.get(pkg);
                if (pus != null) {
                    usageTime = pus.mUsageTime;
                    launchCount = pus.mLaunchCount;
                }
                retArr[i] = new PkgUsageStats(pkg, launchCount, usageTime,
                        mLastResumeTimes.valueAt(i));
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
    
    private void collectDumpInfoFLOCK(PrintWriter pw, boolean isCompactOutput,
            boolean deleteAfterPrint, HashSet<String> packages) {
        List<String> fileList = getUsageStatsFileListFLOCK();
        if (fileList == null) {
            return;
        }
        Collections.sort(fileList);
        for (String file : fileList) {
            if (deleteAfterPrint && file.equalsIgnoreCase(mFileLeaf)) {
                // In this mode we don't print the current day's stats, since
                // they are incomplete.
                continue;
            }
            File dFile = new File(mDir, file);
            String dateStr = file.substring(FILE_PREFIX.length());
            if (dateStr.length() > 0 && (dateStr.charAt(0) <= '0' || dateStr.charAt(0) >= '9')) {
                // If the remainder does not start with a number, it is not a date,
                // so we should ignore it for purposes here.
                continue;
            }
            try {
                Parcel in = getParcelForFile(dFile);
                collectDumpInfoFromParcelFLOCK(in, pw, dateStr, isCompactOutput,
                        packages);
                if (deleteAfterPrint) {
                    // Delete old file after collecting info only for checkin requests
                    dFile.delete();
                }
            } catch (FileNotFoundException e) {
                Slog.w(TAG, "Failed with "+e+" when collecting dump info from file : " + file);
                return;
            } catch (IOException e) {
                Slog.w(TAG, "Failed with "+e+" when collecting dump info from file : "+file);
            }      
        }
    }
    
    private void collectDumpInfoFromParcelFLOCK(Parcel in, PrintWriter pw,
            String date, boolean isCompactOutput, HashSet<String> packages) {
        StringBuilder sb = new StringBuilder(512);
        if (isCompactOutput) {
            sb.append("D:");
            sb.append(CHECKIN_VERSION);
            sb.append(',');
        } else {
            sb.append("Date: ");
        }
        
        sb.append(date);
        
        int vers = in.readInt();
        if (vers != VERSION) {
            sb.append(" (old data version)");
            pw.println(sb.toString());
            return;
        }
        
        pw.println(sb.toString());
        int N = in.readInt();
        
        while (N > 0) {
            N--;
            String pkgName = in.readString();
            if (pkgName == null) {
                break;
            }
            sb.setLength(0);
            PkgUsageStatsExtended pus = new PkgUsageStatsExtended(in);
            if (packages != null && !packages.contains(pkgName)) {
                // This package has not been requested -- don't print
                // anything for it.
            } else if (isCompactOutput) {
                sb.append("P:");
                sb.append(pkgName);
                sb.append(',');
                sb.append(pus.mLaunchCount);
                sb.append(',');
                sb.append(pus.mUsageTime);
                sb.append('\n');
                final int NLT = pus.mLaunchTimes.size();
                for (int i=0; i<NLT; i++) {
                    sb.append("A:");
                    String activity = pus.mLaunchTimes.keyAt(i);
                    sb.append(activity);
                    TimeStats times = pus.mLaunchTimes.valueAt(i);
                    sb.append(',');
                    sb.append(times.count);
                    for (int j=0; j<NUM_LAUNCH_TIME_BINS; j++) {
                        sb.append(",");
                        sb.append(times.times[j]);
                    }
                    sb.append('\n');
                }
                final int NFDT = pus.mFullyDrawnTimes.size();
                for (int i=0; i<NFDT; i++) {
                    sb.append("A:");
                    String activity = pus.mFullyDrawnTimes.keyAt(i);
                    sb.append(activity);
                    TimeStats times = pus.mFullyDrawnTimes.valueAt(i);
                    for (int j=0; j<NUM_LAUNCH_TIME_BINS; j++) {
                        sb.append(",");
                        sb.append(times.times[j]);
                    }
                    sb.append('\n');
                }

            } else {
                sb.append("  ");
                sb.append(pkgName);
                sb.append(": ");
                sb.append(pus.mLaunchCount);
                sb.append(" times, ");
                sb.append(pus.mUsageTime);
                sb.append(" ms");
                sb.append('\n');
                final int NLT = pus.mLaunchTimes.size();
                for (int i=0; i<NLT; i++) {
                    sb.append("    ");
                    sb.append(pus.mLaunchTimes.keyAt(i));
                    TimeStats times = pus.mLaunchTimes.valueAt(i);
                    sb.append(": ");
                    sb.append(times.count);
                    sb.append(" starts");
                    int lastBin = 0;
                    for (int j=0; j<NUM_LAUNCH_TIME_BINS-1; j++) {
                        if (times.times[j] != 0) {
                            sb.append(", ");
                            sb.append(lastBin);
                            sb.append('-');
                            sb.append(LAUNCH_TIME_BINS[j]);
                            sb.append("ms=");
                            sb.append(times.times[j]);
                        }
                        lastBin = LAUNCH_TIME_BINS[j];
                    }
                    if (times.times[NUM_LAUNCH_TIME_BINS-1] != 0) {
                        sb.append(", ");
                        sb.append(">=");
                        sb.append(lastBin);
                        sb.append("ms=");
                        sb.append(times.times[NUM_LAUNCH_TIME_BINS-1]);
                    }
                    sb.append('\n');
                }
                final int NFDT = pus.mFullyDrawnTimes.size();
                for (int i=0; i<NFDT; i++) {
                    sb.append("    ");
                    sb.append(pus.mFullyDrawnTimes.keyAt(i));
                    TimeStats times = pus.mFullyDrawnTimes.valueAt(i);
                    sb.append(": fully drawn ");
                    boolean needComma = false;
                    int lastBin = 0;
                    for (int j=0; j<NUM_LAUNCH_TIME_BINS-1; j++) {
                        if (times.times[j] != 0) {
                            if (needComma) {
                                sb.append(", ");
                            } else {
                                needComma = true;
                            }
                            sb.append(lastBin);
                            sb.append('-');
                            sb.append(LAUNCH_TIME_BINS[j]);
                            sb.append("ms=");
                            sb.append(times.times[j]);
                        }
                        lastBin = LAUNCH_TIME_BINS[j];
                    }
                    if (times.times[NUM_LAUNCH_TIME_BINS-1] != 0) {
                        if (needComma) {
                            sb.append(", ");
                        }
                        sb.append(">=");
                        sb.append(lastBin);
                        sb.append("ms=");
                        sb.append(times.times[NUM_LAUNCH_TIME_BINS-1]);
                    }
                    sb.append('\n');
                }
            }
            
            pw.write(sb.toString());
        }
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
    
    /**
     * Searches array of arguments for the specified string's data
     * @param args array of argument strings
     * @param value value to search for
     * @return the string of data after the arg, or null if there is none
     */
    private static String scanArgsData(String[] args, String value) {
        if (args != null) {
            final int N = args.length;
            for (int i=0; i<N; i++) {
                if (value.equals(args[i])) {
                    i++;
                    return i < N ? args[i] : null;
                }
            }
        }
        return null;
    }
    
    @Override
    /*
     * The data persisted to file is parsed and the stats are computed. 
     */
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump UsageStats from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " without permission " + android.Manifest.permission.DUMP);
            return;
        }

        final boolean isCheckinRequest = scanArgs(args, "--checkin");
        final boolean isCompactOutput = isCheckinRequest || scanArgs(args, "-c");
        final boolean deleteAfterPrint = isCheckinRequest || scanArgs(args, "-d");
        final String rawPackages = scanArgsData(args, "--packages");
        
        // Make sure the current stats are written to the file.  This
        // doesn't need to be done if we are deleting files after printing,
        // since it that case we won't print the current stats.
        if (!deleteAfterPrint) {
            writeStatsToFile(true, false);
        }
        
        HashSet<String> packages = null;
        if (rawPackages != null) {
            if (!"*".equals(rawPackages)) {
                // A * is a wildcard to show all packages.
                String[] names = rawPackages.split(",");
                for (String n : names) {
                    if (packages == null) {
                        packages = new HashSet<String>();
                    }
                    packages.add(n);
                }
            }
        } else if (isCheckinRequest) {
            // If checkin doesn't specify any packages, then we simply won't
            // show anything.
            Slog.w(TAG, "Checkin without packages");
            return;
        }
        
        synchronized (mFileLock) {
            collectDumpInfoFLOCK(pw, isCompactOutput, deleteAfterPrint, packages);
        }
    }

}
