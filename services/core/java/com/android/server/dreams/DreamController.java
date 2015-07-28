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

package com.android.server.dreams;

import com.android.internal.logging.MetricsLogger;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.IBinder.DeathRecipient;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamService;
import android.util.Slog;
import android.view.IWindowManager;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import java.io.PrintWriter;
import java.util.NoSuchElementException;

/**
 * Internal controller for starting and stopping the current dream and managing related state.
 *
 * Assumes all operations are called from the dream handler thread.
 */
final class DreamController {
    private static final String TAG = "DreamController";

    // How long we wait for a newly bound dream to create the service connection
    private static final int DREAM_CONNECTION_TIMEOUT = 5 * 1000;

    // Time to allow the dream to perform an exit transition when waking up.
    private static final int DREAM_FINISH_TIMEOUT = 5 * 1000;

    private final Context mContext;
    private final Handler mHandler;
    private final Listener mListener;
    private final IWindowManager mIWindowManager;
    private long mDreamStartTime;

    private final Intent mDreamingStartedIntent = new Intent(Intent.ACTION_DREAMING_STARTED)
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
    private final Intent mDreamingStoppedIntent = new Intent(Intent.ACTION_DREAMING_STOPPED)
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);

    private final Intent mCloseNotificationShadeIntent;

    private DreamRecord mCurrentDream;

    private final Runnable mStopUnconnectedDreamRunnable = new Runnable() {
        @Override
        public void run() {
            if (mCurrentDream != null && mCurrentDream.mBound && !mCurrentDream.mConnected) {
                Slog.w(TAG, "Bound dream did not connect in the time allotted");
                stopDream(true /*immediate*/);
            }
        }
    };

    private final Runnable mStopStubbornDreamRunnable = new Runnable() {
        @Override
        public void run() {
            Slog.w(TAG, "Stubborn dream did not finish itself in the time allotted");
            stopDream(true /*immediate*/);
        }
    };

    public DreamController(Context context, Handler handler, Listener listener) {
        mContext = context;
        mHandler = handler;
        mListener = listener;
        mIWindowManager = WindowManagerGlobal.getWindowManagerService();
        mCloseNotificationShadeIntent = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mCloseNotificationShadeIntent.putExtra("reason", "dream");
    }

    public void dump(PrintWriter pw) {
        pw.println("Dreamland:");
        if (mCurrentDream != null) {
            pw.println("  mCurrentDream:");
            pw.println("    mToken=" + mCurrentDream.mToken);
            pw.println("    mName=" + mCurrentDream.mName);
            pw.println("    mIsTest=" + mCurrentDream.mIsTest);
            pw.println("    mCanDoze=" + mCurrentDream.mCanDoze);
            pw.println("    mUserId=" + mCurrentDream.mUserId);
            pw.println("    mBound=" + mCurrentDream.mBound);
            pw.println("    mService=" + mCurrentDream.mService);
            pw.println("    mSentStartBroadcast=" + mCurrentDream.mSentStartBroadcast);
            pw.println("    mWakingGently=" + mCurrentDream.mWakingGently);
        } else {
            pw.println("  mCurrentDream: null");
        }
    }

    public void startDream(Binder token, ComponentName name,
            boolean isTest, boolean canDoze, int userId) {
        stopDream(true /*immediate*/);

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "startDream");
        try {
            // Close the notification shade. Don't need to send to all, but better to be explicit.
            mContext.sendBroadcastAsUser(mCloseNotificationShadeIntent, UserHandle.ALL);

            Slog.i(TAG, "Starting dream: name=" + name
                    + ", isTest=" + isTest + ", canDoze=" + canDoze
                    + ", userId=" + userId);

            mCurrentDream = new DreamRecord(token, name, isTest, canDoze, userId);

            mDreamStartTime = SystemClock.elapsedRealtime();
            MetricsLogger.visible(mContext,
                    mCurrentDream.mCanDoze ? MetricsLogger.DOZING : MetricsLogger.DREAMING);

            try {
                mIWindowManager.addWindowToken(token, WindowManager.LayoutParams.TYPE_DREAM);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Unable to add window token for dream.", ex);
                stopDream(true /*immediate*/);
                return;
            }

            Intent intent = new Intent(DreamService.SERVICE_INTERFACE);
            intent.setComponent(name);
            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            try {
                if (!mContext.bindServiceAsUser(intent, mCurrentDream,
                        Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                        new UserHandle(userId))) {
                    Slog.e(TAG, "Unable to bind dream service: " + intent);
                    stopDream(true /*immediate*/);
                    return;
                }
            } catch (SecurityException ex) {
                Slog.e(TAG, "Unable to bind dream service: " + intent, ex);
                stopDream(true /*immediate*/);
                return;
            }

            mCurrentDream.mBound = true;
            mHandler.postDelayed(mStopUnconnectedDreamRunnable, DREAM_CONNECTION_TIMEOUT);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
    }

    public void stopDream(boolean immediate) {
        if (mCurrentDream == null) {
            return;
        }

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "stopDream");
        try {
            if (!immediate) {
                if (mCurrentDream.mWakingGently) {
                    return; // already waking gently
                }

                if (mCurrentDream.mService != null) {
                    // Give the dream a moment to wake up and finish itself gently.
                    mCurrentDream.mWakingGently = true;
                    try {
                        mCurrentDream.mService.wakeUp();
                        mHandler.postDelayed(mStopStubbornDreamRunnable, DREAM_FINISH_TIMEOUT);
                        return;
                    } catch (RemoteException ex) {
                        // oh well, we tried, finish immediately instead
                    }
                }
            }

            final DreamRecord oldDream = mCurrentDream;
            mCurrentDream = null;
            Slog.i(TAG, "Stopping dream: name=" + oldDream.mName
                    + ", isTest=" + oldDream.mIsTest + ", canDoze=" + oldDream.mCanDoze
                    + ", userId=" + oldDream.mUserId);
            MetricsLogger.hidden(mContext,
                    oldDream.mCanDoze ? MetricsLogger.DOZING : MetricsLogger.DREAMING);
            MetricsLogger.histogram(mContext,
                    oldDream.mCanDoze ? "dozing_minutes" : "dreaming_minutes" ,
                    (int) ((SystemClock.elapsedRealtime() - mDreamStartTime) / (1000L * 60L)));

            mHandler.removeCallbacks(mStopUnconnectedDreamRunnable);
            mHandler.removeCallbacks(mStopStubbornDreamRunnable);

            if (oldDream.mSentStartBroadcast) {
                mContext.sendBroadcastAsUser(mDreamingStoppedIntent, UserHandle.ALL);
            }

            if (oldDream.mService != null) {
                // Tell the dream that it's being stopped so that
                // it can shut down nicely before we yank its window token out from
                // under it.
                try {
                    oldDream.mService.detach();
                } catch (RemoteException ex) {
                    // we don't care; this thing is on the way out
                }

                try {
                    oldDream.mService.asBinder().unlinkToDeath(oldDream, 0);
                } catch (NoSuchElementException ex) {
                    // don't care
                }
                oldDream.mService = null;
            }

            if (oldDream.mBound) {
                mContext.unbindService(oldDream);
            }

            try {
                mIWindowManager.removeWindowToken(oldDream.mToken);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Error removing window token for dream.", ex);
            }

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onDreamStopped(oldDream.mToken);
                }
            });
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
    }

    private void attach(IDreamService service) {
        try {
            service.asBinder().linkToDeath(mCurrentDream, 0);
            service.attach(mCurrentDream.mToken, mCurrentDream.mCanDoze);
        } catch (RemoteException ex) {
            Slog.e(TAG, "The dream service died unexpectedly.", ex);
            stopDream(true /*immediate*/);
            return;
        }

        mCurrentDream.mService = service;

        if (!mCurrentDream.mIsTest) {
            mContext.sendBroadcastAsUser(mDreamingStartedIntent, UserHandle.ALL);
            mCurrentDream.mSentStartBroadcast = true;
        }
    }

    /**
     * Callback interface to be implemented by the {@link DreamManagerService}.
     */
    public interface Listener {
        void onDreamStopped(Binder token);
    }

    private final class DreamRecord implements DeathRecipient, ServiceConnection {
        public final Binder mToken;
        public final ComponentName mName;
        public final boolean mIsTest;
        public final boolean mCanDoze;
        public final int mUserId;

        public boolean mBound;
        public boolean mConnected;
        public IDreamService mService;
        public boolean mSentStartBroadcast;

        public boolean mWakingGently;

        public DreamRecord(Binder token, ComponentName name,
                boolean isTest, boolean canDoze, int userId) {
            mToken = token;
            mName = name;
            mIsTest = isTest;
            mCanDoze = canDoze;
            mUserId  = userId;
        }

        // May be called on any thread.
        @Override
        public void binderDied() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mService = null;
                    if (mCurrentDream == DreamRecord.this) {
                        stopDream(true /*immediate*/);
                    }
                }
            });
        }

        // May be called on any thread.
        @Override
        public void onServiceConnected(ComponentName name, final IBinder service) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mConnected = true;
                    if (mCurrentDream == DreamRecord.this && mService == null) {
                        attach(IDreamService.Stub.asInterface(service));
                    }
                }
            });
        }

        // May be called on any thread.
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mService = null;
                    if (mCurrentDream == DreamRecord.this) {
                        stopDream(true /*immediate*/);
                    }
                }
            });
        }
    }
}