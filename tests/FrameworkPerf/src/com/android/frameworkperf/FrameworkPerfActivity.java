/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.frameworkperf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;

/**
 * So you thought sync used up your battery life.
 */
public class FrameworkPerfActivity extends Activity {
    static final String TAG = "Perf";

    final Handler mHandler = new Handler();

    TextView mLog;
    PowerManager.WakeLock mPartialWakeLock;

    long mMaxRunTime = 5000;
    boolean mStarted;

    final TestRunner mRunner = new TestRunner();

    final Op[] mOpPairs = new Op[] {
            new MethodCallOp(), new NoOp(),
            new MethodCallOp(), new CpuOp(),
            new MethodCallOp(), new SchedulerOp(),
            new MethodCallOp(), new GcOp(),
            new MethodCallOp(), new CreateFileOp(),
            new MethodCallOp(), new CreateWriteFileOp(),
            new MethodCallOp(), new CreateWriteSyncFileOp(),
            new MethodCallOp(), new WriteFileOp(),
            new MethodCallOp(), new ReadFileOp(),
            new SchedulerOp(), new SchedulerOp(),
            new GcOp(), new NoOp(),
            new IpcOp(), new NoOp(),
            new IpcOp(), new CpuOp(),
            new IpcOp(), new SchedulerOp(),
            new IpcOp(), new GcOp(),
            new IpcOp(), new CreateFileOp(),
            new IpcOp(), new CreateWriteFileOp(),
            new IpcOp(), new CreateWriteSyncFileOp(),
            new IpcOp(), new WriteFileOp(),
            new IpcOp(), new ReadFileOp(),
            new CreateFileOp(), new NoOp(),
            new CreateWriteFileOp(), new NoOp(),
            new CreateWriteSyncFileOp(), new NoOp(),
            new WriteFileOp(), new NoOp(),
            new ReadFileOp(), new NoOp(),
            new WriteFileOp(), new CreateWriteFileOp(),
            new ReadFileOp(), new CreateWriteFileOp(),
            new WriteFileOp(), new CreateWriteSyncFileOp(),
            new ReadFileOp(), new CreateWriteSyncFileOp(),
            new WriteFileOp(), new WriteFileOp(),
            new WriteFileOp(), new ReadFileOp(),
            new ReadFileOp(), new WriteFileOp(),
            new ReadFileOp(), new ReadFileOp(),
            new ParseXmlResOp(), new NoOp(),
            new ParseLargeXmlResOp(), new NoOp(),
            new LayoutInflaterOp(), new NoOp(),
            new LayoutInflaterLargeOp(), new NoOp(),
            new LoadSmallBitmapOp(), new NoOp(),
            new LoadLargeBitmapOp(), new NoOp(),
            new LoadSmallScaledBitmapOp(), new NoOp(),
            new LoadLargeScaledBitmapOp(), new NoOp(),
    };

    final Op[] mAvailOps = new Op[] {
            new NoOp(),
            new CpuOp(),
            new SchedulerOp(),
            new MethodCallOp(),
            new IpcOp(),
            new ParseXmlResOp(),
            new ParseLargeXmlResOp(),
            new LoadSmallBitmapOp(),
            new LoadLargeBitmapOp(),
            new LoadSmallScaledBitmapOp(),
            new LoadLargeScaledBitmapOp(),
    };
    
    int mCurOpIndex = 0;

    class RunResult {
        final String name;
        final String fgLongName;
        final String bgLongName;
        final long fgTime;
        final long fgOps;
        final long bgTime;
        final long bgOps;

        RunResult(TestRunner op) {
            name = op.getName();
            fgLongName = op.getForegroundLongName();
            bgLongName = op.getBackgroundLongName();
            fgTime = op.getForegroundTime();
            fgOps = op.getForegroundOps();
            bgTime = op.getBackgroundTime();
            bgOps = op.getBackgroundOps();
        }

        float getFgMsPerOp() {
            return fgOps != 0 ? (fgTime / (float)fgOps) : 0;
        }

        float getBgMsPerOp() {
            return bgOps != 0 ? (bgTime / (float)bgOps) : 0;
        }
    }

    final ArrayList<RunResult> mResults = new ArrayList<RunResult>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set the layout for this activity.  You can find it
        // in res/layout/hello_activity.xml
        setContentView(R.layout.main);

        findViewById(R.id.start).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startRunning();
            }
        });
        findViewById(R.id.stop).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopRunning();
            }
        });
        mLog = (TextView)findViewById(R.id.log);

        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        mPartialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Scheduler");
        mPartialWakeLock.setReferenceCounted(false);
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopRunning();
        if (mPartialWakeLock.isHeld()) {
            mPartialWakeLock.release();
        }
    }

    void startCurOp() {
        mRunner.run(mHandler, mOpPairs[mCurOpIndex], mOpPairs[mCurOpIndex+1], new Runnable() {
            @Override public void run() {
                RunResult result = new RunResult(mRunner);
                log(String.format("%s: fg=%d*%gms/op (%dms) / bg=%d*%gms/op (%dms)",
                        result.name, result.fgOps, result.getFgMsPerOp(), result.fgTime,
                        result.bgOps, result.getBgMsPerOp(), result.bgTime));
                mResults.add(result);
                if (!mStarted) {
                    log("Stop");
                    stopRunning();
                    return;
                }
                mCurOpIndex+=2;
                if (mCurOpIndex >= mOpPairs.length) {
                    log("Finished");
                    stopRunning();
                    return;
                }
                startCurOp();
            }
        });
    }

    void startRunning() {
        if (!mStarted) {
            log("Start");
            mStarted = true;
            updateWakeLock();
            startService(new Intent(this, SchedulerService.class));
            mCurOpIndex = 0;
            mResults.clear();
            startCurOp();
        }
    }

    void stopRunning() {
        if (mStarted) {
            mStarted = false;
            updateWakeLock();
            stopService(new Intent(this, SchedulerService.class));
            for (int i=0; i<mResults.size(); i++) {
                RunResult result = mResults.get(i);
                float fgMsPerOp = result.getFgMsPerOp();
                float bgMsPerOp = result.getBgMsPerOp();
                String fgMsPerOpStr = fgMsPerOp != 0 ? Float.toString(fgMsPerOp) : "";
                String bgMsPerOpStr = bgMsPerOp != 0 ? Float.toString(bgMsPerOp) : "";
                Log.i(TAG, "\t" + result.name + "\t" + result.fgOps
                        + "\t" + result.getFgMsPerOp() + "\t" + result.fgTime
                        + "\t" + result.fgLongName + "\t" + result.bgOps
                        + "\t" + result.getBgMsPerOp() + "\t" + result.bgTime
                        + "\t" + result.bgLongName);
            }
        }
    }

    void updateWakeLock() {
        if (mStarted) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (!mPartialWakeLock.isHeld()) {
                mPartialWakeLock.acquire();
            }
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (mPartialWakeLock.isHeld()) {
                mPartialWakeLock.release();
            }
        }
    }

    void log(String s) {
        mLog.setText(mLog.getText() + "\n" + s);
        Log.i(TAG, s);
    }

    enum BackgroundMode {
        NOTHING,
        CPU,
        SCHEDULER
    };

    public class TestRunner {
        Handler mHandler;
        Op mForegroundOp;
        Op mBackgroundOp;
        Runnable mDoneCallback;

        RunnerThread mBackgroundThread;
        RunnerThread mForegroundThread;
        long mStartTime;

        boolean mBackgroundRunning;
        boolean mForegroundRunning;

        long mBackgroundEndTime;
        long mBackgroundOps;
        long mForegroundEndTime;
        long mForegroundOps;

        public TestRunner() {
        }

        public String getForegroundName() {
            return mForegroundOp.getName();
        }

        public String getBackgroundName() {
            return mBackgroundOp.getName();
        }

        public String getName() {
            String fgName = mForegroundOp.getName();
            String bgName = mBackgroundOp.getName();
            StringBuilder res = new StringBuilder();
            if (fgName != null) {
                res.append(fgName);
                res.append("Fg");
            }
            if (bgName != null) {
                res.append(bgName);
                res.append("Bg");
            }
            return res.toString();
        }

        public String getForegroundLongName() {
            return mForegroundOp.getLongName();
        }

        public String getBackgroundLongName() {
            return mBackgroundOp.getLongName();
        }

        public void run(Handler handler, Op foreground, Op background, Runnable doneCallback) {
            mHandler = handler;
            mForegroundOp = foreground;
            mBackgroundOp = background;
            mDoneCallback = doneCallback;
            mBackgroundThread = new RunnerThread("background", new Runnable() {
                @Override public void run() {
                    boolean running;
                    int ops = 0;
                    do {
                        running = mBackgroundOp.onRun();
                        ops++;
                    } while (evalRepeat(running, true) && running);
                    mBackgroundEndTime = SystemClock.uptimeMillis();
                    mBackgroundOps = ops * mBackgroundOp.getOpsPerRun();
                    threadFinished(false);
                }
            }, Process.THREAD_PRIORITY_BACKGROUND);
            mForegroundThread = new RunnerThread("background", new Runnable() {
                @Override public void run() {
                    boolean running;
                    int ops = 0;
                    do {
                        running = mForegroundOp.onRun();
                        ops++;
                    } while (evalRepeat(true, running) && running);
                    mForegroundEndTime = SystemClock.uptimeMillis();
                    mForegroundOps = ops * mForegroundOp.getOpsPerRun();
                    threadFinished(true);
                }
            }, Process.THREAD_PRIORITY_FOREGROUND);

            mForegroundOp.onInit(FrameworkPerfActivity.this, true);
            mBackgroundOp.onInit(FrameworkPerfActivity.this, false);

            synchronized (this) {
                mStartTime = SystemClock.uptimeMillis();
                mBackgroundRunning = true;
                mForegroundRunning = true;
            }

            mBackgroundThread.start();
            mForegroundThread.start();
        }

        public long getForegroundTime() {
            return mForegroundEndTime-mStartTime;
        }

        public long getForegroundOps() {
            return mForegroundOps;
        }

        public long getBackgroundTime() {
            return mBackgroundEndTime-mStartTime;
        }

        public long getBackgroundOps() {
            return mBackgroundOps;
        }

        private boolean evalRepeat(boolean bgRunning, boolean fgRunning) {
            synchronized (this) {
                if (!bgRunning) {
                    mBackgroundRunning = false;
                }
                if (!fgRunning) {
                    mForegroundRunning = false;
                }
                if (!mBackgroundRunning && !mForegroundRunning) {
                    return false;
                }
                long now = SystemClock.uptimeMillis();
                if (now > (mStartTime+mMaxRunTime)) {
                    return false;
                }
                return true;
            }
        }

        private void threadFinished(boolean foreground) {
            synchronized (this) {
                if (foreground) {
                    mForegroundRunning = false;
                } else {
                    mBackgroundRunning = false;
                }
                if (!mBackgroundRunning && !mForegroundRunning) {
                    mHandler.post(new Runnable() {
                        @Override public void run() {
                            mForegroundOp.onTerm(FrameworkPerfActivity.this);
                            mBackgroundOp.onTerm(FrameworkPerfActivity.this);
                            if (mDoneCallback != null) {
                                mDoneCallback.run();
                            }
                        }
                    });
                }
            }
        }
    }

    class RunnerThread extends Thread {
        private final Runnable mOp;
        private final int mPriority;

        RunnerThread(String name, Runnable op, int priority) {
            super(name);
            mOp = op;
            mPriority = priority;
        }

        public void run() {
            Process.setThreadPriority(mPriority);
            mOp.run();
        }
    }

    static public abstract class Op {
        final String mName;
        final String mLongName;

        public Op(String name, String longName) {
            mName = name;
            mLongName = longName;
        }

        public String getName() {
            return mName;
        }

        public String getLongName() {
            return mLongName;
        }

        void onInit(Context context, boolean foreground) {
        }

        abstract boolean onRun();

        void onTerm(Context context) {
        }

        int getOpsPerRun() {
            return 1;
        }
    }

    static class NoOp extends Op {
        NoOp() {
            super(null, "Nothing");
        }

        boolean onRun() {
            return false;
        }

        int getOpsPerRun() {
            return 0;
        }
    }

    static class CpuOp extends Op {
        CpuOp() {
            super("CPU", "Consume CPU");
        }

        boolean onRun() {
            return true;
        }
    }

    static class SchedulerOp extends Op {
        SchedulerOp() {
            super("Sched", "Change scheduler group");
        }

        boolean onRun() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            return true;
        }
    }

    static class GcOp extends Op {
        GcOp() {
            super("Gc", "Run garbage collector");
        }

        boolean onRun() {
            byte[] stuff = new byte[1024*1024];
            return true;
        }
    }

    static class MethodCallOp extends Op {
        MethodCallOp() {
            super("MethodCall", "Method call");
        }

        boolean onRun() {
            final int N = getOpsPerRun();
            for (int i=0; i<N; i++) {
                someFunc(i);
            }
            return true;
        }

        int someFunc(int foo) {
            return 0;
        }

        int getOpsPerRun() {
            return 500;
        }
    }

    static class IpcOp extends Op {
        PackageManager mPm;
        String mProcessName;

        IpcOp() {
            super("Ipc", "IPC to system process");
        }

        void onInit(Context context, boolean foreground) {
            mPm = context.getPackageManager();
            mProcessName = context.getApplicationInfo().processName;
        }

        boolean onRun() {
            final int N = getOpsPerRun();
            for (int i=0; i<N; i++) {
                mPm.queryContentProviders(mProcessName, Process.myUid(), 0);
            }
            return true;
        }

        int getOpsPerRun() {
            return 100;
        }
    }

    static class ParseXmlResOp extends Op {
        Context mContext;

        ParseXmlResOp() {
            super("ParseXmlRes", "Parse compiled XML resource");
        }

        void onInit(Context context, boolean foreground) {
            mContext = context;
        }

        boolean onRun() {
            SimpleInflater inf = new SimpleInflater(mContext);
            inf.inflate(R.xml.simple);
            return true;
        }
    }

    static class ParseLargeXmlResOp extends Op {
        Context mContext;

        ParseLargeXmlResOp() {
            super("ParseLargeXmlRes", "Parse large XML resource");
        }

        void onInit(Context context, boolean foreground) {
            mContext = context;
        }

        boolean onRun() {
            SimpleInflater inf = new SimpleInflater(mContext);
            inf.inflate(R.xml.simple_large);
            return true;
        }
    }

    static class LayoutInflaterOp extends Op {
        Context mContext;

        LayoutInflaterOp() {
            super("LayoutInflaterOp", "Inflate layout resource");
        }

        void onInit(Context context, boolean foreground) {
            mContext = context;
        }

        boolean onRun() {
            if (Looper.myLooper() == null) {
                Looper.prepare();
            }
            LayoutInflater inf = (LayoutInflater)mContext.getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);
            inf.inflate(R.layout.small_layout, null);
            return true;
        }
    }

    static class LayoutInflaterLargeOp extends Op {
        Context mContext;

        LayoutInflaterLargeOp() {
            super("LayoutInflaterLargeOp", "Inflate large layout resource");
        }

        void onInit(Context context, boolean foreground) {
            mContext = context;
        }

        boolean onRun() {
            if (Looper.myLooper() == null) {
                Looper.prepare();
            }
            LayoutInflater inf = (LayoutInflater)mContext.getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);
            inf.inflate(R.layout.large_layout, null);
            return true;
        }
    }

    static class LoadSmallBitmapOp extends Op {
        Context mContext;

        LoadSmallBitmapOp() {
            super("LoadSmallBitmap", "Load small raw bitmap");
        }

        void onInit(Context context, boolean foreground) {
            mContext = context;
        }

        boolean onRun() {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScreenDensity = DisplayMetrics.DENSITY_DEVICE;
            Bitmap bm = BitmapFactory.decodeResource(mContext.getResources(),
                    R.drawable.stat_sample, opts);
            bm.recycle();
            return true;
        }
    }

    static class LoadLargeBitmapOp extends Op {
        Context mContext;

        LoadLargeBitmapOp() {
            super("LoadLargeBitmap", "Load large raw bitmap");
        }

        void onInit(Context context, boolean foreground) {
            mContext = context;
        }

        boolean onRun() {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScreenDensity = DisplayMetrics.DENSITY_DEVICE;
            Bitmap bm = BitmapFactory.decodeResource(mContext.getResources(),
                    R.drawable.wallpaper_goldengate, opts);
            bm.recycle();
            return true;
        }
    }

    static class LoadSmallScaledBitmapOp extends Op {
        Context mContext;

        LoadSmallScaledBitmapOp() {
            super("LoadSmallScaledBitmap", "Load small raw bitmap that is scaled for density");
        }

        void onInit(Context context, boolean foreground) {
            mContext = context;
        }

        boolean onRun() {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScreenDensity = DisplayMetrics.DENSITY_DEVICE;
            Bitmap bm = BitmapFactory.decodeResource(mContext.getResources(),
                    R.drawable.stat_sample_scale, opts);
            bm.recycle();
            return true;
        }
    }

    static class LoadLargeScaledBitmapOp extends Op {
        Context mContext;

        LoadLargeScaledBitmapOp() {
            super("LoadLargeScaledBitmap", "Load large raw bitmap that is scaled for density");
        }

        void onInit(Context context, boolean foreground) {
            mContext = context;
        }

        boolean onRun() {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScreenDensity = DisplayMetrics.DENSITY_DEVICE;
            Bitmap bm = BitmapFactory.decodeResource(mContext.getResources(),
                    R.drawable.wallpaper_goldengate_scale, opts);
            bm.recycle();
            return true;
        }
    }

    static class CreateFileOp extends Op {
        File mFile;

        CreateFileOp() {
            super("CreateFile", "Create and delete a file");
        }

        void onInit(Context context, boolean foreground) {
            mFile = context.getFileStreamPath(foreground ? "test-fg.file" : "test-bg.file");
            mFile.delete();
        }

        boolean onRun() {
            try {
                mFile.createNewFile();
            } catch (IOException e) {
                Log.w(TAG, "Failure creating " + mFile, e);
            }
            mFile.delete();
            return true;
        }
    }

    static class CreateWriteFileOp extends Op {
        File mFile;

        CreateWriteFileOp() {
            super("CreateWriteFile", "Create, write, and delete a file");
        }

        void onInit(Context context, boolean foreground) {
            mFile = context.getFileStreamPath(foreground ? "test-fg.file" : "test-bg.file");
            mFile.delete();
        }

        boolean onRun() {
            try {
                FileOutputStream fos = new FileOutputStream(mFile);
                fos.write(1);
                fos.close();
            } catch (IOException e) {
                Log.w(TAG, "Failure creating " + mFile, e);
            }
            mFile.delete();
            return true;
        }
    }

    static class CreateWriteSyncFileOp extends Op {
        File mFile;

        CreateWriteSyncFileOp() {
            super("CreateWriteSyncFile", "Create, write, sync, and delete a file");
        }

        void onInit(Context context, boolean foreground) {
            mFile = context.getFileStreamPath(foreground ? "test-fg.file" : "test-bg.file");
            mFile.delete();
        }

        boolean onRun() {
            try {
                FileOutputStream fos = new FileOutputStream(mFile);
                fos.write(1);
                fos.flush();
                FileUtils.sync(fos);
                fos.close();
            } catch (IOException e) {
                Log.w(TAG, "Failure creating " + mFile, e);
            }
            mFile.delete();
            return true;
        }
    }

    static class WriteFileOp extends Op {
        File mFile;
        RandomAccessFile mRAF;
        byte[] mBuffer;

        WriteFileOp() {
            super("WriteFile", "Truncate and write a 64k file");
        }

        void onInit(Context context, boolean foreground) {
            mBuffer = new byte[1024*64];
            for (int i=0; i<mBuffer.length; i++) {
                mBuffer[i] = (byte)i;
            }
            mFile = context.getFileStreamPath(foreground ? "test-fg.file" : "test-bg.file");
            mFile.delete();
            try {
                mRAF = new RandomAccessFile(mFile, "rw");
            } catch (FileNotFoundException e) {
                Log.w(TAG, "Failure creating " + mFile, e);
            }
        }

        boolean onRun() {
            try {
                mRAF.seek(0);
                mRAF.setLength(0);
                mRAF.write(mBuffer);
            } catch (IOException e) {
                Log.w(TAG, "Failure writing " + mFile, e);
            }
            return true;
        }

        void onTerm(Context context) {
            try {
                mRAF.close();
            } catch (IOException e) {
                Log.w(TAG, "Failure closing " + mFile, e);
            }
            mFile.delete();
        }
    }

    static class ReadFileOp extends Op {
        File mFile;
        RandomAccessFile mRAF;
        byte[] mBuffer;

        ReadFileOp() {
            super("ReadFile", "Seek and read a 64k file");
        }

        void onInit(Context context, boolean foreground) {
            mBuffer = new byte[1024*64];
            for (int i=0; i<mBuffer.length; i++) {
                mBuffer[i] = (byte)i;
            }
            mFile = context.getFileStreamPath(foreground ? "test-fg.file" : "test-bg.file");
            mFile.delete();
            try {
                mRAF = new RandomAccessFile(mFile, "rw");
                mRAF.seek(0);
                mRAF.setLength(0);
                mRAF.write(mBuffer);
            } catch (IOException e) {
                Log.w(TAG, "Failure creating " + mFile, e);
            }
        }

        boolean onRun() {
            try {
                mRAF.seek(0);
                mRAF.read(mBuffer);
            } catch (IOException e) {
                Log.w(TAG, "Failure reading " + mFile, e);
            }
            return true;
        }

        void onTerm(Context context) {
            try {
                mRAF.close();
            } catch (IOException e) {
                Log.w(TAG, "Failure closing " + mFile, e);
            }
            mFile.delete();
        }
    }
}
