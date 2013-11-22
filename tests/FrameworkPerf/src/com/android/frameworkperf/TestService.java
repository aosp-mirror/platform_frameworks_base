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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.String;
import java.util.HashMap;
import java.util.Random;

import android.util.ArrayMap;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Xml;
import android.view.LayoutInflater;

public class TestService extends Service {
    static final String TAG = "Perf";

    final static Op[] mOpPairs = new Op[] {
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
            new ObjectGcOp(), new NoOp(),
            new FinalizingGcOp(), new NoOp(),
            new PaintGcOp(), new NoOp(),
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
            new OpenXmlResOp(), new NoOp(),
            new ReadXmlAttrsOp(), new NoOp(),
            new ParseXmlResOp(), new NoOp(),
            new ParseLargeXmlResOp(), new NoOp(),
            new LayoutInflaterOp(), new NoOp(),
            new LayoutInflaterLargeOp(), new NoOp(),
            new LayoutInflaterViewOp(), new NoOp(),
            new LayoutInflaterButtonOp(), new NoOp(),
            new LayoutInflaterImageButtonOp(), new NoOp(),
            new CreateBitmapOp(), new NoOp(),
            new CreateRecycleBitmapOp(), new NoOp(),
            new LoadSmallBitmapOp(), new NoOp(),
            new LoadRecycleSmallBitmapOp(), new NoOp(),
            new LoadLargeBitmapOp(), new NoOp(),
            new LoadRecycleLargeBitmapOp(), new NoOp(),
            new LoadSmallScaledBitmapOp(), new NoOp(),
            new LoadLargeScaledBitmapOp(), new NoOp(),
    };

    final static Op[] mAvailOps = new Op[] {
            null,
            new NoOp(),
            new CpuOp(),
            new SchedulerOp(),
            new MethodCallOp(),
            new GcOp(),
            new ObjectGcOp(),
            new FinalizingGcOp(),
            new PaintGcOp(),
            new IpcOp(),
            new CreateFileOp(),
            new CreateWriteFileOp(),
            new CreateWriteSyncFileOp(),
            new WriteFileOp(),
            new ReadFileOp(),
            new OpenXmlResOp(),
            new ReadXmlAttrsOp(),
            new ParseXmlResOp(),
            new ParseLargeXmlResOp(),
            new LayoutInflaterOp(),
            new LayoutInflaterLargeOp(),
            new LayoutInflaterViewOp(),
            new LayoutInflaterButtonOp(),
            new LayoutInflaterImageButtonOp(),
            new CreateBitmapOp(),
            new CreateRecycleBitmapOp(),
            new LoadSmallBitmapOp(),
            new LoadRecycleSmallBitmapOp(),
            new LoadLargeBitmapOp(),
            new LoadRecycleLargeBitmapOp(),
            new LoadSmallScaledBitmapOp(),
            new LoadLargeScaledBitmapOp(),
            new GrowTinyHashMapOp(),
            new GrowTinyArrayMapOp(),
            new GrowSmallHashMapOp(),
            new GrowSmallArrayMapOp(),
            new GrowLargeHashMapOp(),
            new GrowLargeArrayMapOp(),
            new LookupTinyHashMapOp(),
            new LookupTinyArrayMapOp(),
            new LookupSmallHashMapOp(),
            new LookupSmallArrayMapOp(),
            new LookupLargeHashMapOp(),
            new LookupLargeArrayMapOp(),
    };

    static final int CMD_START_TEST = 1;
    static final int CMD_TERMINATE = 2;

    static final int MSG_REALLY_START = 1000;
    static final int MSG_REALLY_TERMINATE = 1001;

    static final int RES_TEST_FINISHED = 1;
    static final int RES_TERMINATED = 2;

    final Handler mHandler = new Handler() {
        @Override public void handleMessage(Message msg) {
            switch (msg.what) {
                case CMD_START_TEST: {
                    // Give a little time for things to settle down.
                    Message newMsg = Message.obtain(null, MSG_REALLY_START);
                    newMsg.obj = msg.obj;
                    newMsg.replyTo = msg.replyTo;
                    sendMessageDelayed(newMsg, 500);
                } break;
                case MSG_REALLY_START: {
                    Bundle bundle = (Bundle)msg.obj;
                    bundle.setClassLoader(getClassLoader());
                    final TestArgs args = (TestArgs)bundle.getParcelable("args");
                    final Messenger replyTo = msg.replyTo;
                    mRunner.run(this, args, new Runnable() {
                        @Override public void run() {
                            if (replyTo != null) {
                                Message msg = Message.obtain(null, RES_TEST_FINISHED);
                                Bundle bundle = new Bundle();
                                bundle.putParcelable("res", new RunResult(mRunner));
                                msg.obj = bundle;
                                try {
                                    replyTo.send(msg);
                                } catch (RemoteException e) {
                                }
                            }
                        }
                    });
                } break;
                case CMD_TERMINATE: {
                    // Give a little time for things to settle down.
                    Message newMsg = Message.obtain(null, MSG_REALLY_TERMINATE);
                    newMsg.obj = msg.obj;
                    newMsg.replyTo = msg.replyTo;
                    sendMessageDelayed(newMsg, 50);
                } break;
                case MSG_REALLY_TERMINATE: {
                    if (msg.replyTo != null) {
                        Message reply = Message.obtain(null, RES_TERMINATED);
                        try {
                            msg.replyTo.send(reply);
                        } catch (RemoteException e) {
                        }
                    }
                    terminate();
                } break;
            }
        }
    };

    final TestRunner mRunner = new TestRunner();

    @Override
    public IBinder onBind(Intent intent) {
        return (new Messenger(mHandler)).getBinder();
    }

    void terminate() {
        Runtime.getRuntime().exit(0);
    }

    enum BackgroundMode {
        NOTHING,
        CPU,
        SCHEDULER
    };

    public class TestRunner {
        Handler mHandler;
        long mMaxRunTime;
        long mMaxOps;
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

        public void run(Handler handler, TestArgs args, Runnable doneCallback) {
            mHandler = handler;
            mMaxRunTime = args.maxTime;
            mMaxOps = args.maxOps;
            if (args.combOp >= 0) {
                mForegroundOp = mOpPairs[args.combOp];
                mBackgroundOp = mOpPairs[args.combOp+1];
            } else {
                mForegroundOp = mAvailOps[args.fgOp];
                mBackgroundOp = mAvailOps[args.bgOp];
            }
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
            mForegroundThread = new RunnerThread("foreground", new Runnable() {
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

            mForegroundOp.onInit(TestService.this, true);
            mBackgroundOp.onInit(TestService.this, false);

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
                if (mMaxOps > 0) {
                    // iteration-limited case
                    if (mForegroundOps >= mMaxOps) {
                        return false;
                    }
                    mForegroundOps++;
                } else {
                    // time-limited case
                    long now = SystemClock.uptimeMillis();
                    if (now > (mStartTime+mMaxRunTime)) {
                        return false;
                    }
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
                            mForegroundOp.onTerm(TestService.this);
                            mBackgroundOp.onTerm(TestService.this);
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

    static class ObjectGcOp extends Op {
        ObjectGcOp() {
            super("ObjectGc", "Run garbage collector with simple objects");
        }

        boolean onRun() {
            Object obj = new Object();
            return true;
        }
    }

    static class FinalizingGcOp extends Op {
        class Finalizable {
            Finalizable() {}
            @Override
            protected void finalize() throws Throwable {
                super.finalize();
            }
        }

        FinalizingGcOp() {
            super("FinalizingGc", "Run garbage collector with finalizable objects");
        }

        boolean onRun() {
            Finalizable obj = new Finalizable();
            return true;
        }
    }

    static class PaintGcOp extends Op {
        PaintGcOp() {
            super("PaintGc", "Run garbage collector with Paint objects");
        }

        boolean onRun() {
            Paint p = new Paint();
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

    static class OpenXmlResOp extends Op {
        Context mContext;

        OpenXmlResOp() {
            super("OpenXmlRes", "Open (and close) an XML resource");
        }

        void onInit(Context context, boolean foreground) {
            mContext = context;
        }

        boolean onRun() {
            XmlResourceParser parser = mContext.getResources().getLayout(R.xml.simple);
            parser.close();
            return true;
        }
    }

    static class ReadXmlAttrsOp extends Op {
        Context mContext;
        XmlResourceParser mParser;
        AttributeSet mAttrs;

        ReadXmlAttrsOp() {
            super("ReadXmlAttrs", "Read attributes from an XML tag");
        }

        void onInit(Context context, boolean foreground) {
            mContext = context;
            mParser = mContext.getResources().getLayout(R.xml.simple);
            mAttrs = Xml.asAttributeSet(mParser);

            int eventType;
            try {
                // Find the first <item> tag.
                eventType = mParser.getEventType();
                String tagName;
                do {
                    if (eventType == XmlPullParser.START_TAG) {
                        tagName = mParser.getName();
                        if (tagName.equals("item")) {
                            break;
                        }
                    }
                    eventType = mParser.next();
                } while (eventType != XmlPullParser.END_DOCUMENT);
            } catch (XmlPullParserException e) {
                throw new RuntimeException("I died", e);
            } catch (IOException e) {
                throw new RuntimeException("I died", e);
            }
        }

        void onTerm(Context context) {
            mParser.close();
        }

        boolean onRun() {
            TypedArray a = mContext.obtainStyledAttributes(mAttrs,
                    com.android.internal.R.styleable.MenuItem);
            a.recycle();
            return true;
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
            super("LayoutInflater", "Inflate layout resource");
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
            super("LayoutInflaterLarge", "Inflate large layout resource");
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

    static class LayoutInflaterViewOp extends Op {
        Context mContext;

        LayoutInflaterViewOp() {
            super("LayoutInflaterView", "Inflate layout with 50 View objects");
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
            inf.inflate(R.layout.view_layout, null);
            return true;
        }
    }

    static class LayoutInflaterButtonOp extends Op {
        Context mContext;

        LayoutInflaterButtonOp() {
            super("LayoutInflaterButton", "Inflate layout with 50 Button objects");
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
            inf.inflate(R.layout.button_layout, null);
            return true;
        }
    }

    static class LayoutInflaterImageButtonOp extends Op {
        Context mContext;

        LayoutInflaterImageButtonOp() {
            super("LayoutInflaterImageButton", "Inflate layout with 50 ImageButton objects");
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
            inf.inflate(R.layout.image_button_layout, null);
            return true;
        }
    }

    static class CreateBitmapOp extends Op {
        Context mContext;

        CreateBitmapOp() {
            super("CreateBitmap", "Create a Bitmap");
        }

        void onInit(Context context, boolean foreground) {
            mContext = context;
        }

        boolean onRun() {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScreenDensity = DisplayMetrics.DENSITY_DEVICE;
            Bitmap bm = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            return true;
        }
    }

    static class CreateRecycleBitmapOp extends Op {
        Context mContext;

        CreateRecycleBitmapOp() {
            super("CreateRecycleBitmap", "Create and recycle a Bitmap");
        }

        void onInit(Context context, boolean foreground) {
            mContext = context;
        }

        boolean onRun() {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScreenDensity = DisplayMetrics.DENSITY_DEVICE;
            Bitmap bm = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            bm.recycle();
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
            return true;
        }
    }

    static class LoadRecycleSmallBitmapOp extends Op {
        Context mContext;

        LoadRecycleSmallBitmapOp() {
            super("LoadRecycleSmallBitmap", "Load and recycle small raw bitmap");
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
            return true;
        }
    }

    static class LoadRecycleLargeBitmapOp extends Op {
        Context mContext;

        LoadRecycleLargeBitmapOp() {
            super("LoadRecycleLargeBitmap", "Load and recycle large raw bitmap");
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

    static abstract class GenericMapOp extends Op {
        final int mSize;
        String[] mKeys;
        String[] mValues;

        GenericMapOp(String name, String longName, int size) {
            super(name, longName);
            mSize = size;
        }

        void onInit(Context context, boolean foreground) {
            mKeys = new String[mSize];
            mValues = new String[mSize];
            Random random = new Random(0);
            for (int i=0; i<mSize; i++) {
                int chars = random.nextInt(10);
                StringBuilder builder = new StringBuilder(chars);
                for (int j=0; j<chars; j++) {
                    builder.append('a' + random.nextInt(100));
                }
                mKeys[i] = builder.toString();
                mValues[i] = Integer.toString(i);
            }
        }

        int getOpsPerRun() {
            return mSize;
        }
    }

    static class GrowTinyHashMapOp extends GenericMapOp {
        GrowTinyHashMapOp() {
            super("GrowTinyHashMap", "Add 5 items to a HashMap", 5);
        }

        boolean onRun() {
            HashMap<String, String> map = new HashMap<String, String>();
            for (int i=0; i<mSize; i++) {
                map.put(mKeys[i], mValues[i]);
            }
            return true;
        }
    }

    static class GrowTinyArrayMapOp extends GenericMapOp {
        GrowTinyArrayMapOp() {
            super("GrowTinyArrayMap", "Add 5 items to a ArrayMap", 5);
        }

        boolean onRun() {
            ArrayMap<String, String> map = new ArrayMap<String, String>();
            for (int i=0; i<mSize; i++) {
                map.put(mKeys[i], mValues[i]);
            }
            return true;
        }
    }

    static class GrowSmallHashMapOp extends GenericMapOp {
        GrowSmallHashMapOp() {
            super("GrowSmallHashMap", "Add 100 items to a HashMap", 100);
        }

        boolean onRun() {
            HashMap<String, String> map = new HashMap<String, String>();
            for (int i=0; i<mSize; i++) {
                map.put(mKeys[i], mValues[i]);
            }
            return true;
        }
    }

    static class GrowSmallArrayMapOp extends GenericMapOp {
        GrowSmallArrayMapOp() {
            super("GrowSmallArrayMap", "Add 100 items to a ArrayMap", 100);
        }

        boolean onRun() {
            ArrayMap<String, String> map = new ArrayMap<String, String>();
            for (int i=0; i<mSize; i++) {
                map.put(mKeys[i], mValues[i]);
            }
            return true;
        }
    }

    static class GrowLargeHashMapOp extends GenericMapOp {
        GrowLargeHashMapOp() {
            super("GrowLargeHashMap", "Add 10000 items to a HashMap", 10000);
        }

        boolean onRun() {
            HashMap<String, String> map = new HashMap<String, String>();
            for (int i=0; i<mSize; i++) {
                map.put(mKeys[i], mValues[i]);
            }
            return true;
        }
    }

    static class GrowLargeArrayMapOp extends GenericMapOp {
        GrowLargeArrayMapOp() {
            super("GrowLargeArrayMap", "Add 10000 items to a ArrayMap", 10000);
        }

        boolean onRun() {
            ArrayMap<String, String> map = new ArrayMap<String, String>();
            for (int i=0; i<mSize; i++) {
                map.put(mKeys[i], mValues[i]);
            }
            return true;
        }
    }

    static class LookupTinyHashMapOp extends LookupSmallHashMapOp {
        LookupTinyHashMapOp() {
            super("LookupTinyHashMap", "Lookup items in 5 entry HashMap", 5);
        }
    }

    static class LookupTinyArrayMapOp extends LookupSmallArrayMapOp {
        LookupTinyArrayMapOp() {
            super("LookupTinyArrayMap", "Lookup items in 5 entry ArrayMap", 5);
        }
    }

    static class LookupSmallHashMapOp extends GenericMapOp {
        HashMap<String, String> mHashMap;

        LookupSmallHashMapOp() {
            super("LookupSmallHashMap", "Lookup items in 100 entry HashMap", 100);
        }

        LookupSmallHashMapOp(String name, String longname, int size) {
            super(name, longname, size);
        }

        void onInit(Context context, boolean foreground) {
            super.onInit(context, foreground);
            mHashMap = new HashMap<String, String>();
            for (int i=0; i<mSize; i++) {
                mHashMap.put(mKeys[i], mValues[i]);
            }
        }

        boolean onRun() {
            for (int i=0; i<mSize; i++) {
                mHashMap.get(mKeys[i]);
            }
            return true;
        }
    }

    static class LookupSmallArrayMapOp extends GenericMapOp {
        ArrayMap<String, String> mArrayMap;

        LookupSmallArrayMapOp() {
            super("LookupSmallArrayMap", "Lookup items in 100 entry ArrayMap", 100);
        }

        LookupSmallArrayMapOp(String name, String longname, int size) {
            super(name, longname, size);
        }

        void onInit(Context context, boolean foreground) {
            super.onInit(context, foreground);
            mArrayMap = new ArrayMap<String, String>();
            for (int i=0; i<mSize; i++) {
                mArrayMap.put(mKeys[i], mValues[i]);
            }
        }

        boolean onRun() {
            for (int i=0; i<mSize; i++) {
                mArrayMap.get(mKeys[i]);
            }
            return true;
        }
    }

    static class LookupLargeHashMapOp extends LookupSmallHashMapOp {
        LookupLargeHashMapOp() {
            super("LookupLargeHashMap", "Lookup items in 10000 entry HashMap", 10000);
        }
    }

    static class LookupLargeArrayMapOp extends LookupSmallArrayMapOp {
        LookupLargeArrayMapOp() {
            super("LookupLargeArrayMap", "Lookup items in 10000 entry ArrayMap", 10000);
        }
    }
}
