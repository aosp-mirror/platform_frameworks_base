/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.graphics;

import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.os.Trace;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.util.Log;
import android.view.IGraphicsStats;
import android.view.IGraphicsStatsCallback;

import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastPrintWriter;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.TimeZone;

/**
 * This service's job is to collect aggregate rendering profile data. It
 * does this by allowing rendering processes to request an ashmem buffer
 * to place their stats into.
 *
 * Buffers are rotated on a daily (in UTC) basis and only the 3 most-recent days
 * are kept.
 *
 * The primary consumer of this is incident reports and automated metric checking. It is not
 * intended for end-developer consumption, for that we have gfxinfo.
 *
 * Buffer rotation process:
 * 1) Alarm fires
 * 2) onRotateGraphicsStatsBuffer() is sent to all active processes
 * 3) Upon receiving the callback, the process will stop using the previous ashmem buffer and
 *    request a new one.
 * 4) When that request is received we now know that the ashmem region is no longer in use so
 *    it gets queued up for saving to disk and a new ashmem region is created and returned
 *    for the process to use.
 *
 *  @hide */
public class GraphicsStatsService extends IGraphicsStats.Stub {
    public static final String GRAPHICS_STATS_SERVICE = "graphicsstats";

    private static final String TAG = "GraphicsStatsService";

    private static final int SAVE_BUFFER = 1;
    private static final int DELETE_OLD = 2;

    private static final int AID_STATSD = 1066; // Statsd uid is set to 1066 forever.

    // This isn't static because we need this to happen after registerNativeMethods, however
    // the class is loaded (and thus static ctor happens) before that occurs.
    private final int mAshmemSize = nGetAshmemSize();
    private final byte[] mZeroData = new byte[mAshmemSize];

    private final Context mContext;
    private final AppOpsManager mAppOps;
    private final AlarmManager mAlarmManager;
    private final Object mLock = new Object();
    private ArrayList<ActiveBuffer> mActive = new ArrayList<>();
    private File mGraphicsStatsDir;
    private final Object mFileAccessLock = new Object();
    private Handler mWriteOutHandler;
    private boolean mRotateIsScheduled = false;

    public GraphicsStatsService(Context context) {
        mContext = context;
        mAppOps = context.getSystemService(AppOpsManager.class);
        mAlarmManager = context.getSystemService(AlarmManager.class);
        File systemDataDir = new File(Environment.getDataDirectory(), "system");
        mGraphicsStatsDir = new File(systemDataDir, "graphicsstats");
        mGraphicsStatsDir.mkdirs();
        if (!mGraphicsStatsDir.exists()) {
            throw new IllegalStateException("Graphics stats directory does not exist: "
                    + mGraphicsStatsDir.getAbsolutePath());
        }
        HandlerThread bgthread = new HandlerThread("GraphicsStats-disk",
                Process.THREAD_PRIORITY_BACKGROUND);
        bgthread.start();

        mWriteOutHandler = new Handler(bgthread.getLooper(), new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                switch (msg.what) {
                    case SAVE_BUFFER:
                        saveBuffer((HistoricalBuffer) msg.obj);
                        break;
                    case DELETE_OLD:
                        deleteOldBuffers();
                        break;
                }
                return true;
            }
        });
        nativeInit();
    }

    /**
     * Current rotation policy is to rotate at midnight UTC. We don't specify RTC_WAKEUP because
     * rotation can be delayed if there's otherwise no activity. However exact is used because
     * we don't want the system to delay it by TOO much.
     */
    private void scheduleRotateLocked() {
        if (mRotateIsScheduled) {
            return;
        }
        mRotateIsScheduled = true;
        Calendar calendar = normalizeDate(System.currentTimeMillis());
        calendar.add(Calendar.DATE, 1);
        mAlarmManager.setExact(AlarmManager.RTC, calendar.getTimeInMillis(), TAG, this::onAlarm,
                mWriteOutHandler);
    }

    private void onAlarm() {
        // We need to make a copy since some of the callbacks won't be proxy and thus
        // can result in a re-entrant acquisition of mLock that would result in a modification
        // of mActive during iteration.
        ActiveBuffer[] activeCopy;
        synchronized (mLock) {
            mRotateIsScheduled = false;
            scheduleRotateLocked();
            activeCopy = mActive.toArray(new ActiveBuffer[0]);
        }
        for (ActiveBuffer active : activeCopy) {
            try {
                active.mCallback.onRotateGraphicsStatsBuffer();
            } catch (RemoteException e) {
                Log.w(TAG, String.format("Failed to notify '%s' (pid=%d) to rotate buffers",
                        active.mInfo.mPackageName, active.mPid), e);
            }
        }
        // Give a few seconds for everyone to rotate before doing the cleanup
        mWriteOutHandler.sendEmptyMessageDelayed(DELETE_OLD, 10000);
    }

    @Override
    public ParcelFileDescriptor requestBufferForProcess(String packageName,
            IGraphicsStatsCallback token) throws RemoteException {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        ParcelFileDescriptor pfd = null;
        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            mAppOps.checkPackage(uid, packageName);
            PackageInfo info = mContext.getPackageManager().getPackageInfoAsUser(
                    packageName,
                    0,
                    UserHandle.getUserId(uid));
            synchronized (mLock) {
                pfd = requestBufferForProcessLocked(token, uid, pid, packageName,
                        info.getLongVersionCode());
            }
        } catch (PackageManager.NameNotFoundException ex) {
            throw new RemoteException("Unable to find package: '" + packageName + "'");
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
        return pfd;
    }

    // If lastFullDay is true, pullGraphicsStats returns stats for the last complete day/24h period
    // that does not include today. If lastFullDay is false, pullGraphicsStats returns stats for the
    // current day.
    // This method is invoked from native code only.
    @SuppressWarnings({"UnusedDeclaration"})
    private void pullGraphicsStats(boolean lastFullDay, long pulledData) throws RemoteException {
        int uid = Binder.getCallingUid();

        // DUMP and PACKAGE_USAGE_STATS permissions are required to invoke this method.
        // TODO: remove exception for statsd daemon after required permissions are granted. statsd
        // TODO: should have these permissions granted by data/etc/platform.xml, but it does not.
        if (uid != AID_STATSD) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new FastPrintWriter(sw);
            if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw)) {
                pw.flush();
                throw new RemoteException(sw.toString());
            }
        }

        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            pullGraphicsStatsImpl(lastFullDay, pulledData);
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    private void pullGraphicsStatsImpl(boolean lastFullDay, long pulledData) {
        long targetDay;
        if (lastFullDay) {
            // Get stats from yesterday. Stats stay constant, because the day is over.
            targetDay = normalizeDate(System.currentTimeMillis() - 86400000).getTimeInMillis();
        } else {
            // Get stats from today. Stats may change as more apps are run today.
            targetDay = normalizeDate(System.currentTimeMillis()).getTimeInMillis();
        }

        // Find active buffers for targetDay.
        ArrayList<HistoricalBuffer> buffers;
        synchronized (mLock) {
            buffers = new ArrayList<>(mActive.size());
            for (int i = 0; i < mActive.size(); i++) {
                ActiveBuffer buffer = mActive.get(i);
                if (buffer.mInfo.mStartTime == targetDay) {
                    try {
                        buffers.add(new HistoricalBuffer(buffer));
                    } catch (IOException ex) {
                        // Ignore
                    }
                }
            }
        }

        // Dump active and historic buffers for targetDay in a serialized
        // GraphicsStatsServiceDumpProto proto.
        long dump = nCreateDump(-1, true);
        try {
            synchronized (mFileAccessLock) {
                HashSet<File> skipList = dumpActiveLocked(dump, buffers);
                buffers.clear();
                String subPath = String.format("%d", targetDay);
                File dateDir = new File(mGraphicsStatsDir, subPath);
                if (dateDir.exists()) {
                    for (File pkg : dateDir.listFiles()) {
                        for (File version : pkg.listFiles()) {
                            File data = new File(version, "total");
                            if (skipList.contains(data)) {
                                continue;
                            }
                            nAddToDump(dump, data.getAbsolutePath());
                        }
                    }
                }
            }
        } finally {
            nFinishDumpInMemory(dump, pulledData, lastFullDay);
        }
    }

    private ParcelFileDescriptor requestBufferForProcessLocked(IGraphicsStatsCallback token,
            int uid, int pid, String packageName, long versionCode) throws RemoteException {
        ActiveBuffer buffer = fetchActiveBuffersLocked(token, uid, pid, packageName, versionCode);
        scheduleRotateLocked();
        return buffer.getPfd();
    }

    private Calendar normalizeDate(long timestamp) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(timestamp);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    private File pathForApp(BufferInfo info) {
        String subPath = String.format("%d/%s/%d/total",
                normalizeDate(info.mStartTime).getTimeInMillis(), info.mPackageName,
                info.mVersionCode);
        return new File(mGraphicsStatsDir, subPath);
    }

    private void saveBuffer(HistoricalBuffer buffer) {
        if (Trace.isTagEnabled(Trace.TRACE_TAG_SYSTEM_SERVER)) {
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER,
                    "saving graphicsstats for " + buffer.mInfo.mPackageName);
        }
        synchronized (mFileAccessLock) {
            File path = pathForApp(buffer.mInfo);
            File parent = path.getParentFile();
            parent.mkdirs();
            if (!parent.exists()) {
                Log.w(TAG, "Unable to create path: '" + parent.getAbsolutePath() + "'");
                return;
            }
            nSaveBuffer(path.getAbsolutePath(), buffer.mInfo.mUid, buffer.mInfo.mPackageName,
                    buffer.mInfo.mVersionCode, buffer.mInfo.mStartTime, buffer.mInfo.mEndTime,
                    buffer.mData);
        }
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
    }

    private void deleteRecursiveLocked(File file) {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                deleteRecursiveLocked(child);
            }
        }
        if (!file.delete()) {
            Log.w(TAG, "Failed to delete '" + file.getAbsolutePath() + "'!");
        }
    }

    private void deleteOldBuffers() {
        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "deleting old graphicsstats buffers");
        synchronized (mFileAccessLock) {
            File[] files = mGraphicsStatsDir.listFiles();
            if (files == null || files.length <= 3) {
                return;
            }
            long[] sortedDates = new long[files.length];
            for (int i = 0; i < files.length; i++) {
                try {
                    sortedDates[i] = Long.parseLong(files[i].getName());
                } catch (NumberFormatException ex) {
                    // Skip unrecognized folders
                }
            }
            if (sortedDates.length <= 3) {
                return;
            }
            Arrays.sort(sortedDates);
            for (int i = 0; i < sortedDates.length - 3; i++) {
                deleteRecursiveLocked(new File(mGraphicsStatsDir, Long.toString(sortedDates[i])));
            }
        }
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
    }

    private void addToSaveQueue(ActiveBuffer buffer) {
        try {
            HistoricalBuffer data = new HistoricalBuffer(buffer);
            Message.obtain(mWriteOutHandler, SAVE_BUFFER, data).sendToTarget();
        } catch (IOException e) {
            Log.w(TAG, "Failed to copy graphicsstats from " + buffer.mInfo.mPackageName, e);
        }
        buffer.closeAllBuffers();
    }

    private void processDied(ActiveBuffer buffer) {
        synchronized (mLock) {
            mActive.remove(buffer);
        }
        addToSaveQueue(buffer);
    }

    private ActiveBuffer fetchActiveBuffersLocked(IGraphicsStatsCallback token, int uid, int pid,
            String packageName, long versionCode) throws RemoteException {
        int size = mActive.size();
        long today = normalizeDate(System.currentTimeMillis()).getTimeInMillis();
        for (int i = 0; i < size; i++) {
            ActiveBuffer buffer = mActive.get(i);
            if (buffer.mPid == pid
                    && buffer.mUid == uid) {
                // If the buffer is too old we remove it and return a new one
                if (buffer.mInfo.mStartTime < today) {
                    buffer.binderDied();
                    break;
                } else {
                    return buffer;
                }
            }
        }
        // Didn't find one, need to create it
        try {
            ActiveBuffer buffers = new ActiveBuffer(token, uid, pid, packageName, versionCode);
            mActive.add(buffers);
            return buffers;
        } catch (IOException ex) {
            throw new RemoteException("Failed to allocate space");
        }
    }

    private HashSet<File> dumpActiveLocked(long dump, ArrayList<HistoricalBuffer> buffers) {
        HashSet<File> skipFiles = new HashSet<>(buffers.size());
        for (int i = 0; i < buffers.size(); i++) {
            HistoricalBuffer buffer = buffers.get(i);
            File path = pathForApp(buffer.mInfo);
            skipFiles.add(path);
            nAddToDump(dump, path.getAbsolutePath(), buffer.mInfo.mUid, buffer.mInfo.mPackageName,
                    buffer.mInfo.mVersionCode,  buffer.mInfo.mStartTime, buffer.mInfo.mEndTime,
                    buffer.mData);
        }
        return skipFiles;
    }

    private void dumpHistoricalLocked(long dump, HashSet<File> skipFiles) {
        for (File date : mGraphicsStatsDir.listFiles()) {
            for (File pkg : date.listFiles()) {
                for (File version : pkg.listFiles()) {
                    File data = new File(version, "total");
                    if (skipFiles.contains(data)) {
                        continue;
                    }
                    nAddToDump(dump, data.getAbsolutePath());
                }
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, fout)) return;
        boolean dumpProto = false;
        for (String str : args) {
            if ("--proto".equals(str)) {
                dumpProto = true;
                break;
            }
        }
        ArrayList<HistoricalBuffer> buffers;
        synchronized (mLock) {
            buffers = new ArrayList<>(mActive.size());
            for (int i = 0; i < mActive.size(); i++) {
                try {
                    buffers.add(new HistoricalBuffer(mActive.get(i)));
                } catch (IOException ex) {
                    // Ignore
                }
            }
        }
        long dump = nCreateDump(fd.getInt$(), dumpProto);
        try {
            synchronized (mFileAccessLock) {
                HashSet<File> skipList = dumpActiveLocked(dump, buffers);
                buffers.clear();
                dumpHistoricalLocked(dump, skipList);
            }
        } finally {
            nFinishDump(dump);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        nativeDestructor();
    }

    private native void nativeInit();
    private static native void nativeDestructor();

    private static native int nGetAshmemSize();
    private static native long nCreateDump(int outFd, boolean isProto);
    private static native void nAddToDump(long dump, String path, int uid, String packageName,
            long versionCode, long startTime, long endTime, byte[] data);
    private static native void nAddToDump(long dump, String path);
    private static native void nFinishDump(long dump);
    private static native void nFinishDumpInMemory(long dump, long pulledData, boolean lastFullDay);
    private static native void nSaveBuffer(String path, int uid, String packageName,
            long versionCode, long startTime, long endTime, byte[] data);

    private final class BufferInfo {
        final int mUid;
        final String mPackageName;
        final long mVersionCode;
        long mStartTime;
        long mEndTime;

        BufferInfo(int uid, String packageName, long versionCode, long startTime) {
            this.mUid = uid;
            this.mPackageName = packageName;
            this.mVersionCode = versionCode;
            this.mStartTime = startTime;
        }
    }

    private final class ActiveBuffer implements DeathRecipient {
        final BufferInfo mInfo;
        final int mUid;
        final int mPid;
        final IGraphicsStatsCallback mCallback;
        final IBinder mToken;
        SharedMemory mProcessBuffer;
        ByteBuffer mMapping;

        ActiveBuffer(IGraphicsStatsCallback token, int uid, int pid, String packageName,
                long versionCode)
                throws RemoteException, IOException {
            mInfo = new BufferInfo(uid, packageName, versionCode, System.currentTimeMillis());
            mUid = uid;
            mPid = pid;
            mCallback = token;
            mToken = mCallback.asBinder();
            mToken.linkToDeath(this, 0);
            try {
                mProcessBuffer = SharedMemory.create("GFXStats-" + pid, mAshmemSize);
                mMapping = mProcessBuffer.mapReadWrite();
            } catch (ErrnoException ex) {
                ex.rethrowAsIOException();
            }
            mMapping.position(0);
            mMapping.put(mZeroData, 0, mAshmemSize);
        }

        @Override
        public void binderDied() {
            mToken.unlinkToDeath(this, 0);
            processDied(this);
        }

        void closeAllBuffers() {
            if (mMapping != null) {
                SharedMemory.unmap(mMapping);
                mMapping = null;
            }
            if (mProcessBuffer != null) {
                mProcessBuffer.close();
                mProcessBuffer = null;
            }
        }

        ParcelFileDescriptor getPfd() {
            try {
                return mProcessBuffer.getFdDup();
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to get PFD from memory file", ex);
            }
        }

        void readBytes(byte[] buffer, int count) throws IOException  {
            if (mMapping == null) {
                throw new IOException("SharedMemory has been deactivated");
            }
            mMapping.position(0);
            mMapping.get(buffer, 0, count);
        }
    }

    private final class HistoricalBuffer {
        final BufferInfo mInfo;
        final byte[] mData = new byte[mAshmemSize];
        HistoricalBuffer(ActiveBuffer active) throws IOException {
            mInfo = active.mInfo;
            mInfo.mEndTime = System.currentTimeMillis();
            active.readBytes(mData, mAshmemSize);
        }
    }
}
