/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.content.pm.IPackageManager;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import com.android.internal.app.ProcessStats;
import com.android.internal.os.BackgroundThread;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.locks.ReentrantLock;

public final class ProcessStatsService {
    static final String TAG = "ProcessStatsService";
    static final boolean DEBUG = false;

    // Most data is kept in a sparse data structure: an integer array which integer
    // holds the type of the entry, and the identifier for a long array that data
    // exists in and the offset into the array to find it.  The constants below
    // define the encoding of that data in an integer.

    static final int MAX_HISTORIC_STATES = 4;   // Maximum number of historic states we will keep.
    static final String STATE_FILE_PREFIX = "state-"; // Prefix to use for state filenames.
    static final String STATE_FILE_SUFFIX = ".bin"; // Suffix to use for state filenames.
    static final String STATE_FILE_CHECKIN_SUFFIX = ".ci"; // State files that have checked in.
    static long WRITE_PERIOD = 30*60*1000;      // Write file every 30 minutes or so.
    static long COMMIT_PERIOD = 24*60*60*1000;  // Commit current stats every day.

    final Object mLock;
    final File mBaseDir;
    ProcessStats mProcessStats;
    AtomicFile mFile;
    boolean mCommitPending;
    boolean mShuttingDown;
    int mLastMemOnlyState = -1;
    boolean mMemFactorLowered;

    final ReentrantLock mWriteLock = new ReentrantLock();
    final Object mPendingWriteLock = new Object();
    AtomicFile mPendingWriteFile;
    Parcel mPendingWrite;
    boolean mPendingWriteCommitted;
    long mLastWriteTime;

    public ProcessStatsService(Object lock, File file) {
        mLock = lock;
        mBaseDir = file;
        mBaseDir.mkdirs();
        mProcessStats = new ProcessStats(true);
        updateFile();
        SystemProperties.addChangeCallback(new Runnable() {
            @Override public void run() {
                synchronized (mLock) {
                    if (mProcessStats.evaluateSystemProperties(false)) {
                        mProcessStats.mFlags |= ProcessStats.FLAG_SYSPROPS;
                        writeStateLocked(true, true);
                        mProcessStats.evaluateSystemProperties(true);
                    }
                }
            }
        });
    }

    public ProcessStats.ProcessState getProcessStateLocked(String packageName,
            int uid, String processName) {
        return mProcessStats.getProcessStateLocked(packageName, uid, processName);
    }

    public ProcessStats.ServiceState getServiceStateLocked(String packageName, int uid,
            String processName, String className) {
        final ProcessStats.PackageState as = mProcessStats.getPackageStateLocked(packageName, uid);
        ProcessStats.ServiceState ss = as.mServices.get(className);
        if (ss != null) {
            ss.makeActive();
            return ss;
        }
        final ProcessStats.ProcessState ps = mProcessStats.getProcessStateLocked(packageName,
                uid, processName);
        ss = new ProcessStats.ServiceState(mProcessStats, packageName, ps);
        as.mServices.put(className, ss);
        return ss;
    }

    public boolean isMemFactorLowered() {
        return mMemFactorLowered;
    }

    public boolean setMemFactorLocked(int memFactor, boolean screenOn, long now) {
        mMemFactorLowered = memFactor < mLastMemOnlyState;
        mLastMemOnlyState = memFactor;
        if (screenOn) {
            memFactor += ProcessStats.ADJ_SCREEN_ON;
        }
        if (memFactor != mProcessStats.mMemFactor) {
            if (mProcessStats.mMemFactor != ProcessStats.STATE_NOTHING) {
                mProcessStats.mMemFactorDurations[mProcessStats.mMemFactor]
                        += now - mProcessStats.mStartTime;
            }
            mProcessStats.mMemFactor = memFactor;
            mProcessStats.mStartTime = now;
            ArrayMap<String, SparseArray<ProcessStats.PackageState>> pmap
                    = mProcessStats.mPackages.getMap();
            for (int i=0; i<pmap.size(); i++) {
                SparseArray<ProcessStats.PackageState> uids = pmap.valueAt(i);
                for (int j=0; j<uids.size(); j++) {
                    ProcessStats.PackageState pkg = uids.valueAt(j);
                    ArrayMap<String, ProcessStats.ServiceState> services = pkg.mServices;
                    for (int k=0; k<services.size(); k++) {
                        ProcessStats.ServiceState service = services.valueAt(k);
                        if (service.isActive()) {
                            if (service.mStartedState != ProcessStats.STATE_NOTHING) {
                                service.setStarted(true, memFactor, now);
                            }
                            if (service.mBoundState != ProcessStats.STATE_NOTHING) {
                                service.setBound(true, memFactor, now);
                            }
                            if (service.mExecState != ProcessStats.STATE_NOTHING) {
                                service.setExecuting(true, memFactor, now);
                            }
                        }
                    }
                }
            }
            return true;
        }
        return false;
    }

    public int getMemFactorLocked() {
        return mProcessStats.mMemFactor != ProcessStats.STATE_NOTHING ? mProcessStats.mMemFactor : 0;
    }

    public boolean shouldWriteNowLocked(long now) {
        if (now > (mLastWriteTime+WRITE_PERIOD)) {
            if (SystemClock.elapsedRealtime()
                    > (mProcessStats.mTimePeriodStartRealtime+COMMIT_PERIOD)) {
                mCommitPending = true;
            }
            return true;
        }
        return false;
    }

    public void shutdownLocked() {
        Slog.w(TAG, "Writing process stats before shutdown...");
        mProcessStats.mFlags |= ProcessStats.FLAG_SHUTDOWN;
        writeStateSyncLocked();
        mShuttingDown = true;
    }

    public void writeStateAsyncLocked() {
        writeStateLocked(false);
    }

    public void writeStateSyncLocked() {
        writeStateLocked(true);
    }

    private void writeStateLocked(boolean sync) {
        if (mShuttingDown) {
            return;
        }
        boolean commitPending = mCommitPending;
        mCommitPending = false;
        writeStateLocked(sync, commitPending);
    }

    public void writeStateLocked(boolean sync, final boolean commit) {
        synchronized (mPendingWriteLock) {
            long now = SystemClock.uptimeMillis();
            if (mPendingWrite == null || !mPendingWriteCommitted) {
                mPendingWrite = Parcel.obtain();
                mProcessStats.mTimePeriodEndRealtime = SystemClock.elapsedRealtime();
                if (commit) {
                    mProcessStats.mFlags |= ProcessStats.FLAG_COMPLETE;
                }
                mProcessStats.writeToParcel(mPendingWrite);
                mPendingWriteFile = new AtomicFile(mFile.getBaseFile());
                mPendingWriteCommitted = commit;
            }
            if (commit) {
                mProcessStats.resetSafely();
                updateFile();
            }
            mLastWriteTime = SystemClock.uptimeMillis();
            Slog.i(TAG, "Prepared write state in " + (SystemClock.uptimeMillis()-now) + "ms");
            if (!sync) {
                BackgroundThread.getHandler().post(new Runnable() {
                    @Override public void run() {
                        performWriteState();
                    }
                });
                return;
            }
        }

        performWriteState();
    }

    private void updateFile() {
        mFile = new AtomicFile(new File(mBaseDir, STATE_FILE_PREFIX
                + mProcessStats.mTimePeriodStartClockStr + STATE_FILE_SUFFIX));
        mLastWriteTime = SystemClock.uptimeMillis();
    }

    void performWriteState() {
        if (DEBUG) Slog.d(TAG, "Performing write to " + mFile.getBaseFile());
        Parcel data;
        AtomicFile file;
        synchronized (mPendingWriteLock) {
            data = mPendingWrite;
            file = mPendingWriteFile;
            mPendingWriteCommitted = false;
            if (data == null) {
                return;
            }
            mPendingWrite = null;
            mPendingWriteFile = null;
            mWriteLock.lock();
        }

        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(data.marshall());
            stream.flush();
            file.finishWrite(stream);
            if (DEBUG) Slog.d(TAG, "Write completed successfully!");
        } catch (IOException e) {
            Slog.w(TAG, "Error writing process statistics", e);
            file.failWrite(stream);
        } finally {
            data.recycle();
            trimHistoricStatesWriteLocked();
            mWriteLock.unlock();
        }
    }

    static byte[] readFully(FileInputStream stream) throws java.io.IOException {
        int pos = 0;
        int avail = stream.available();
        byte[] data = new byte[avail];
        while (true) {
            int amt = stream.read(data, pos, data.length-pos);
            //Log.i("foo", "Read " + amt + " bytes at " + pos
            //        + " of avail " + data.length);
            if (amt <= 0) {
                //Log.i("foo", "**** FINISHED READING: pos=" + pos
                //        + " len=" + data.length);
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

    boolean readLocked(ProcessStats stats, AtomicFile file) {
        try {
            FileInputStream stream = file.openRead();

            byte[] raw = readFully(stream);
            Parcel in = Parcel.obtain();
            in.unmarshall(raw, 0, raw.length);
            in.setDataPosition(0);
            stream.close();

            stats.readFromParcel(in);
            if (stats.mReadError != null) {
                Slog.w(TAG, "Ignoring existing stats; " + stats.mReadError);
                if (DEBUG) {
                    ArrayMap<String, SparseArray<ProcessStats.ProcessState>> procMap
                            = stats.mProcesses.getMap();
                    final int NPROC = procMap.size();
                    for (int ip=0; ip<NPROC; ip++) {
                        Slog.w(TAG, "Process: " + procMap.keyAt(ip));
                        SparseArray<ProcessStats.ProcessState> uids = procMap.valueAt(ip);
                        final int NUID = uids.size();
                        for (int iu=0; iu<NUID; iu++) {
                            Slog.w(TAG, "  Uid " + uids.keyAt(iu) + ": " + uids.valueAt(iu));
                        }
                    }
                    ArrayMap<String, SparseArray<ProcessStats.PackageState>> pkgMap
                            = stats.mPackages.getMap();
                    final int NPKG = pkgMap.size();
                    for (int ip=0; ip<NPKG; ip++) {
                        Slog.w(TAG, "Package: " + pkgMap.keyAt(ip));
                        SparseArray<ProcessStats.PackageState> uids = pkgMap.valueAt(ip);
                        final int NUID = uids.size();
                        for (int iu=0; iu<NUID; iu++) {
                            Slog.w(TAG, "  Uid: " + uids.keyAt(iu));
                            ProcessStats.PackageState pkgState = uids.valueAt(iu);
                            final int NPROCS = pkgState.mProcesses.size();
                            for (int iproc=0; iproc<NPROCS; iproc++) {
                                Slog.w(TAG, "    Process " + pkgState.mProcesses.keyAt(iproc)
                                        + ": " + pkgState.mProcesses.valueAt(iproc));
                            }
                            final int NSRVS = pkgState.mServices.size();
                            for (int isvc=0; isvc<NSRVS; isvc++) {
                                Slog.w(TAG, "    Service " + pkgState.mServices.keyAt(isvc)
                                        + ": " + pkgState.mServices.valueAt(isvc));
                            }
                        }
                    }
                }
                return false;
            }
        } catch (Throwable e) {
            stats.mReadError = "caught exception: " + e;
            Slog.e(TAG, "Error reading process statistics", e);
            return false;
        }
        return true;
    }

    private ArrayList<String> getCommittedFiles(int minNum, boolean inclAll) {
        File[] files = mBaseDir.listFiles();
        if (files == null || files.length <= minNum) {
            return null;
        }
        ArrayList<String> filesArray = new ArrayList<String>(files.length);
        String currentFile = mFile.getBaseFile().getPath();
        if (DEBUG) Slog.d(TAG, "Collecting " + files.length + " files except: " + currentFile);
        for (int i=0; i<files.length; i++) {
            File file = files[i];
            String fileStr = file.getPath();
            if (DEBUG) Slog.d(TAG, "Collecting: " + fileStr);
            if (!inclAll && fileStr.endsWith(STATE_FILE_CHECKIN_SUFFIX)) {
                if (DEBUG) Slog.d(TAG, "Skipping: already checked in");
                continue;
            }
            if (fileStr.equals(currentFile)) {
                if (DEBUG) Slog.d(TAG, "Skipping: current stats");
                continue;
            }
            filesArray.add(fileStr);
        }
        Collections.sort(filesArray);
        return filesArray;
    }

    public void trimHistoricStatesWriteLocked() {
        ArrayList<String> filesArray = getCommittedFiles(MAX_HISTORIC_STATES, true);
        if (filesArray == null) {
            return;
        }
        while (filesArray.size() > MAX_HISTORIC_STATES) {
            String file = filesArray.remove(0);
            Slog.i(TAG, "Pruning old procstats: " + file);
            (new File(file)).delete();
        }
    }

    boolean dumpFilteredProcessesCsvLocked(PrintWriter pw, String header,
            boolean sepScreenStates, int[] screenStates, boolean sepMemStates, int[] memStates,
            boolean sepProcStates, int[] procStates, long now, String reqPackage) {
        ArrayList<ProcessStats.ProcessState> procs = mProcessStats.collectProcessesLocked(
                screenStates, memStates, procStates, now, reqPackage);
        if (procs.size() > 0) {
            if (header != null) {
                pw.println(header);
            }
            ProcessStats.dumpProcessListCsv(pw, procs, sepScreenStates, screenStates,
                    sepMemStates, memStates, sepProcStates, procStates, now);
            return true;
        }
        return false;
    }

    static int[] parseStateList(String[] states, int mult, String arg, boolean[] outSep,
            String[] outError) {
        ArrayList<Integer> res = new ArrayList<Integer>();
        int lastPos = 0;
        for (int i=0; i<=arg.length(); i++) {
            char c = i < arg.length() ? arg.charAt(i) : 0;
            if (c != ',' && c != '+' && c != ' ' && c != 0) {
                continue;
            }
            boolean isSep = c == ',';
            if (lastPos == 0) {
                // We now know the type of op.
                outSep[0] = isSep;
            } else if (c != 0 && outSep[0] != isSep) {
                outError[0] = "inconsistent separators (can't mix ',' with '+')";
                return null;
            }
            if (lastPos < (i-1)) {
                String str = arg.substring(lastPos, i);
                for (int j=0; j<states.length; j++) {
                    if (str.equals(states[j])) {
                        res.add(j);
                        str = null;
                        break;
                    }
                }
                if (str != null) {
                    outError[0] = "invalid word \"" + str + "\"";
                    return null;
                }
            }
            lastPos = i + 1;
        }

        int[] finalRes = new int[res.size()];
        for (int i=0; i<res.size(); i++) {
            finalRes[i] = res.get(i) * mult;
        }
        return finalRes;
    }

    static private void dumpHelp(PrintWriter pw) {
        pw.println("Process stats (procstats) dump options:");
        pw.println("    [--checkin|-c|--csv] [--csv-screen] [--csv-proc] [--csv-mem]");
        pw.println("    [--details] [--current] [--commit] [--write] [-h] [<package.name>]");
        pw.println("  --checkin: perform a checkin: print and delete old committed states.");
        pw.println("  --c: print only state in checkin format.");
        pw.println("  --csv: output data suitable for putting in a spreadsheet.");
        pw.println("  --csv-screen: on, off.");
        pw.println("  --csv-mem: norm, mod, low, crit.");
        pw.println("  --csv-proc: pers, top, fore, vis, precept, backup,");
        pw.println("    service, home, prev, cached");
        pw.println("  --details: dump all execution details, not just summary.");
        pw.println("  --current: only dump current state.");
        pw.println("  --commit: commit current stats to disk and reset to start new stats.");
        pw.println("  --write: write current in-memory stats to disk.");
        pw.println("  --read: replace current stats with last-written stats.");
        pw.println("  -a: print everything.");
        pw.println("  -h: print this help text.");
        pw.println("  <package.name>: optional name of package to filter output by.");
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        final long now = SystemClock.uptimeMillis();

        boolean isCheckin = false;
        boolean isCompact = false;
        boolean isCsv = false;
        boolean currentOnly = false;
        boolean dumpDetails = false;
        boolean dumpAll = false;
        String reqPackage = null;
        boolean csvSepScreenStats = false;
        int[] csvScreenStats = new int[] { ProcessStats.ADJ_SCREEN_OFF, ProcessStats.ADJ_SCREEN_ON};
        boolean csvSepMemStats = false;
        int[] csvMemStats = new int[] { ProcessStats.ADJ_MEM_FACTOR_CRITICAL};
        boolean csvSepProcStats = true;
        int[] csvProcStats = ProcessStats.ALL_PROC_STATES;
        if (args != null) {
            for (int i=0; i<args.length; i++) {
                String arg = args[i];
                if ("--checkin".equals(arg)) {
                    isCheckin = true;
                } else if ("-c".equals(arg)) {
                    isCompact = true;
                } else if ("--csv".equals(arg)) {
                    isCsv = true;
                } else if ("--csv-screen".equals(arg)) {
                    i++;
                    if (i >= args.length) {
                        pw.println("Error: argument required for --csv-screen");
                        dumpHelp(pw);
                        return;
                    }
                    boolean[] sep = new boolean[1];
                    String[] error = new String[1];
                    csvScreenStats = parseStateList(ProcessStats.ADJ_SCREEN_NAMES_CSV, ProcessStats.ADJ_SCREEN_MOD,
                            args[i], sep, error);
                    if (csvScreenStats == null) {
                        pw.println("Error in \"" + args[i] + "\": " + error[0]);
                        dumpHelp(pw);
                        return;
                    }
                    csvSepScreenStats = sep[0];
                } else if ("--csv-mem".equals(arg)) {
                    i++;
                    if (i >= args.length) {
                        pw.println("Error: argument required for --csv-mem");
                        dumpHelp(pw);
                        return;
                    }
                    boolean[] sep = new boolean[1];
                    String[] error = new String[1];
                    csvMemStats = parseStateList(ProcessStats.ADJ_MEM_NAMES_CSV, 1, args[i], sep, error);
                    if (csvMemStats == null) {
                        pw.println("Error in \"" + args[i] + "\": " + error[0]);
                        dumpHelp(pw);
                        return;
                    }
                    csvSepMemStats = sep[0];
                } else if ("--csv-proc".equals(arg)) {
                    i++;
                    if (i >= args.length) {
                        pw.println("Error: argument required for --csv-proc");
                        dumpHelp(pw);
                        return;
                    }
                    boolean[] sep = new boolean[1];
                    String[] error = new String[1];
                    csvProcStats = parseStateList(ProcessStats.STATE_NAMES_CSV, 1, args[i], sep, error);
                    if (csvProcStats == null) {
                        pw.println("Error in \"" + args[i] + "\": " + error[0]);
                        dumpHelp(pw);
                        return;
                    }
                    csvSepProcStats = sep[0];
                } else if ("--details".equals(arg)) {
                    dumpDetails = true;
                } else if ("--current".equals(arg)) {
                    currentOnly = true;
                } else if ("--commit".equals(arg)) {
                    mProcessStats.mFlags |= ProcessStats.FLAG_COMPLETE;
                    writeStateLocked(true, true);
                    pw.println("Process stats committed.");
                    return;
                } else if ("--write".equals(arg)) {
                    writeStateSyncLocked();
                    pw.println("Process stats written.");
                    return;
                } else if ("--read".equals(arg)) {
                    readLocked(mProcessStats, mFile);
                    pw.println("Process stats read.");
                    return;
                } else if ("-h".equals(arg)) {
                    dumpHelp(pw);
                    return;
                } else if ("-a".equals(arg)) {
                    dumpDetails = true;
                    dumpAll = true;
                } else if (arg.length() > 0 && arg.charAt(0) == '-'){
                    pw.println("Unknown option: " + arg);
                    dumpHelp(pw);
                    return;
                } else {
                    // Not an option, last argument must be a package name.
                    try {
                        IPackageManager pm = AppGlobals.getPackageManager();
                        if (pm.getPackageUid(arg, UserHandle.getCallingUserId()) >= 0) {
                            reqPackage = arg;
                            // Include all details, since we know we are only going to
                            // be dumping a smaller set of data.  In fact only the details
                            // container per-package data, so that are needed to be able
                            // to dump anything at all when filtering by package.
                            dumpDetails = true;
                        }
                    } catch (RemoteException e) {
                    }
                    if (reqPackage == null) {
                        pw.println("Unknown package: " + arg);
                        dumpHelp(pw);
                        return;
                    }
                }
            }
        }

        if (isCsv) {
            pw.print("Processes running summed over");
            if (!csvSepScreenStats) {
                for (int i=0; i<csvScreenStats.length; i++) {
                    pw.print(" ");
                    ProcessStats.printScreenLabelCsv(pw, csvScreenStats[i]);
                }
            }
            if (!csvSepMemStats) {
                for (int i=0; i<csvMemStats.length; i++) {
                    pw.print(" ");
                    ProcessStats.printMemLabelCsv(pw, csvMemStats[i]);
                }
            }
            if (!csvSepProcStats) {
                for (int i=0; i<csvProcStats.length; i++) {
                    pw.print(" ");
                    pw.print(ProcessStats.STATE_NAMES_CSV[csvProcStats[i]]);
                }
            }
            pw.println();
            synchronized (mLock) {
                dumpFilteredProcessesCsvLocked(pw, null,
                        csvSepScreenStats, csvScreenStats, csvSepMemStats, csvMemStats,
                        csvSepProcStats, csvProcStats, now, reqPackage);
                /*
                dumpFilteredProcessesCsvLocked(pw, "Processes running while critical mem:",
                        false, new int[] {ADJ_SCREEN_OFF, ADJ_SCREEN_ON},
                        true, new int[] {ADJ_MEM_FACTOR_CRITICAL},
                        true, new int[] {STATE_PERSISTENT, STATE_TOP, STATE_FOREGROUND, STATE_VISIBLE,
                                STATE_PERCEPTIBLE, STATE_BACKUP, STATE_SERVICE, STATE_HOME,
                                STATE_PREVIOUS, STATE_CACHED},
                        now, reqPackage);
                dumpFilteredProcessesCsvLocked(pw, "Processes running over all mem:",
                        false, new int[] {ADJ_SCREEN_OFF, ADJ_SCREEN_ON},
                        false, new int[] {ADJ_MEM_FACTOR_CRITICAL, ADJ_MEM_FACTOR_LOW,
                                ADJ_MEM_FACTOR_MODERATE, ADJ_MEM_FACTOR_MODERATE},
                        true, new int[] {STATE_PERSISTENT, STATE_TOP, STATE_FOREGROUND, STATE_VISIBLE,
                                STATE_PERCEPTIBLE, STATE_BACKUP, STATE_SERVICE, STATE_HOME,
                                STATE_PREVIOUS, STATE_CACHED},
                        now, reqPackage);
                */
            }
            return;
        }

        boolean sepNeeded = false;
        if (!currentOnly || isCheckin) {
            mWriteLock.lock();
            try {
                ArrayList<String> files = getCommittedFiles(0, !isCheckin);
                if (files != null) {
                    for (int i=0; i<files.size(); i++) {
                        if (DEBUG) Slog.d(TAG, "Retrieving state: " + files.get(i));
                        try {
                            AtomicFile file = new AtomicFile(new File(files.get(i)));
                            ProcessStats processStats = new ProcessStats(false);
                            readLocked(processStats, file);
                            if (processStats.mReadError != null) {
                                if (isCheckin || isCompact) pw.print("err,");
                                pw.print("Failure reading "); pw.print(files.get(i));
                                pw.print("; "); pw.println(processStats.mReadError);
                                if (DEBUG) Slog.d(TAG, "Deleting state: " + files.get(i));
                                (new File(files.get(i))).delete();
                                continue;
                            }
                            String fileStr = file.getBaseFile().getPath();
                            boolean checkedIn = fileStr.endsWith(STATE_FILE_CHECKIN_SUFFIX);
                            if (isCheckin || isCompact) {
                                // Don't really need to lock because we uniquely own this object.
                                processStats.dumpCheckinLocked(pw, reqPackage);
                            } else {
                                if (sepNeeded) {
                                    pw.println();
                                } else {
                                    sepNeeded = true;
                                }
                                pw.print("COMMITTED STATS FROM ");
                                pw.print(processStats.mTimePeriodStartClockStr);
                                if (checkedIn) pw.print(" (checked in)");
                                pw.println(":");
                                // Don't really need to lock because we uniquely own this object.
                                if (dumpDetails) {
                                    processStats.dumpLocked(pw, reqPackage, now, dumpAll);
                                } else {
                                    processStats.dumpSummaryLocked(pw, reqPackage, now);
                                }
                            }
                            if (isCheckin) {
                                // Rename file suffix to mark that it has checked in.
                                file.getBaseFile().renameTo(new File(
                                        fileStr + STATE_FILE_CHECKIN_SUFFIX));
                            }
                        } catch (Throwable e) {
                            pw.print("**** FAILURE DUMPING STATE: "); pw.println(files.get(i));
                            e.printStackTrace(pw);
                        }
                    }
                }
            } finally {
                mWriteLock.unlock();
            }
        }
        if (!isCheckin) {
            synchronized (mLock) {
                if (isCompact) {
                    mProcessStats.dumpCheckinLocked(pw, reqPackage);
                } else {
                    if (sepNeeded) {
                        pw.println();
                        pw.println("CURRENT STATS:");
                    }
                    if (dumpDetails) {
                        mProcessStats.dumpLocked(pw, reqPackage, now, dumpAll);
                        if (dumpAll) {
                            pw.print("  mFile="); pw.println(mFile.getBaseFile());
                        }
                    } else {
                        mProcessStats.dumpSummaryLocked(pw, reqPackage, now);
                    }
                }
            }
        }
    }
}
