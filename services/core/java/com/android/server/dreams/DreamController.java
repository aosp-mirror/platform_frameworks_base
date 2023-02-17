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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.IRemoteCallback;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamService;
import android.util.Slog;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
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

    private final Intent mDreamingStartedIntent = new Intent(Intent.ACTION_DREAMING_STARTED)
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
    private final Intent mDreamingStoppedIntent = new Intent(Intent.ACTION_DREAMING_STOPPED)
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);

    private final Intent mCloseNotificationShadeIntent;

    private DreamRecord mCurrentDream;

    // Whether a dreaming started intent has been broadcast.
    private boolean mSentStartBroadcast = false;

    // When a new dream is started and there is an existing dream, the existing dream is allowed to
    // live a little longer until the new dream is started, for a smoother transition. This dream is
    // stopped as soon as the new dream is started, and this list is cleared. Usually there should
    // only be one previous dream while waiting for a new dream to start, but we store a list to
    // proof the edge case of multiple previous dreams.
    private final ArrayList<DreamRecord> mPreviousDreams = new ArrayList<>();

    public DreamController(Context context, Handler handler, Listener listener) {
        mContext = context;
        mHandler = handler;
        mListener = listener;
        mCloseNotificationShadeIntent = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mCloseNotificationShadeIntent.putExtra("reason", "dream");
    }

    public void dump(PrintWriter pw) {
        pw.println("Dreamland:");
        if (mCurrentDream != null) {
            pw.println("  mCurrentDream:");
            pw.println("    mToken=" + mCurrentDream.mToken);
            pw.println("    mName=" + mCurrentDream.mName);
            pw.println("    mIsPreviewMode=" + mCurrentDream.mIsPreviewMode);
            pw.println("    mCanDoze=" + mCurrentDream.mCanDoze);
            pw.println("    mUserId=" + mCurrentDream.mUserId);
            pw.println("    mBound=" + mCurrentDream.mBound);
            pw.println("    mService=" + mCurrentDream.mService);
            pw.println("    mWakingGently=" + mCurrentDream.mWakingGently);
        } else {
            pw.println("  mCurrentDream: null");
        }

        pw.println("  mSentStartBroadcast=" + mSentStartBroadcast);
    }

    public void startDream(Binder token, ComponentName name,
            boolean isPreviewMode, boolean canDoze, int userId, PowerManager.WakeLock wakeLock,
            ComponentName overlayComponentName, String reason) {
        Trace.traceBegin(Trace.TRACE_TAG_POWER, "startDream");
        try {
            // Close the notification shade. No need to send to all, but better to be explicit.
            mContext.sendBroadcastAsUser(mCloseNotificationShadeIntent, UserHandle.ALL);

            Slog.i(TAG, "Starting dream: name=" + name
                    + ", isPreviewMode=" + isPreviewMode + ", canDoze=" + canDoze
                    + ", userId=" + userId + ", reason='" + reason + "'");

            if (mCurrentDream != null) {
                mPreviousDreams.add(mCurrentDream);
            }
            mCurrentDream = new DreamRecord(token, name, isPreviewMode, canDoze, userId, wakeLock);

            mCurrentDream.mDreamStartTime = SystemClock.elapsedRealtime();
            MetricsLogger.visible(mContext,
                    mCurrentDream.mCanDoze ? MetricsEvent.DOZING : MetricsEvent.DREAMING);

            Intent intent = new Intent(DreamService.SERVICE_INTERFACE);
            intent.setComponent(name);
            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            intent.putExtra(DreamService.EXTRA_DREAM_OVERLAY_COMPONENT, overlayComponentName);
            try {
                if (!mContext.bindServiceAsUser(intent, mCurrentDream,
                        Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                        new UserHandle(userId))) {
                    Slog.e(TAG, "Unable to bind dream service: " + intent);
                    stopDream(true /*immediate*/, "bindService failed");
                    return;
                }
            } catch (SecurityException ex) {
                Slog.e(TAG, "Unable to bind dream service: " + intent, ex);
                stopDream(true /*immediate*/, "unable to bind service: SecExp.");
                return;
            }

            mCurrentDream.mBound = true;
            mHandler.postDelayed(mCurrentDream.mStopUnconnectedDreamRunnable,
                    DREAM_CONNECTION_TIMEOUT);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
    }

    /**
     * Stops dreaming.
     *
     * The current dream, if any, and any unstopped previous dreams are stopped. The device stops
     * dreaming.
     */
    public void stopDream(boolean immediate, String reason) {
        stopPreviousDreams();
        stopDreamInstance(immediate, reason, mCurrentDream);
    }

    /**
     * Stops the given dream instance.
     *
     * The device may still be dreaming afterwards if there are other dreams running.
     */
    private void stopDreamInstance(boolean immediate, String reason, DreamRecord dream) {
        if (dream == null) {
            return;
        }

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "stopDream");
        try {
            if (!immediate) {
                if (dream.mWakingGently) {
                    return; // already waking gently
                }

                if (dream.mService != null) {
                    // Give the dream a moment to wake up and finish itself gently.
                    dream.mWakingGently = true;
                    try {
                        dream.mStopReason = reason;
                        dream.mService.wakeUp();
                        mHandler.postDelayed(dream.mStopStubbornDreamRunnable,
                                DREAM_FINISH_TIMEOUT);
                        return;
                    } catch (RemoteException ex) {
                        // oh well, we tried, finish immediately instead
                    }
                }
            }

            Slog.i(TAG, "Stopping dream: name=" + dream.mName
                    + ", isPreviewMode=" + dream.mIsPreviewMode
                    + ", canDoze=" + dream.mCanDoze
                    + ", userId=" + dream.mUserId
                    + ", reason='" + reason + "'"
                    + (dream.mStopReason == null ? "" : "(from '"
                    + dream.mStopReason + "')"));
            MetricsLogger.hidden(mContext,
                    dream.mCanDoze ? MetricsEvent.DOZING : MetricsEvent.DREAMING);
            MetricsLogger.histogram(mContext,
                    dream.mCanDoze ? "dozing_minutes" : "dreaming_minutes",
                    (int) ((SystemClock.elapsedRealtime() - dream.mDreamStartTime) / (1000L
                            * 60L)));

            mHandler.removeCallbacks(dream.mStopUnconnectedDreamRunnable);
            mHandler.removeCallbacks(dream.mStopStubbornDreamRunnable);

            if (dream.mService != null) {
                try {
                    dream.mService.detach();
                } catch (RemoteException ex) {
                    // we don't care; this thing is on the way out
                }

                try {
                    dream.mService.asBinder().unlinkToDeath(dream, 0);
                } catch (NoSuchElementException ex) {
                    // don't care
                }
                dream.mService = null;
            }

            if (dream.mBound) {
                mContext.unbindService(dream);
            }
            dream.releaseWakeLockIfNeeded();

            // Current dream stopped, device no longer dreaming.
            if (dream == mCurrentDream) {
                mCurrentDream = null;

                if (mSentStartBroadcast) {
                    mContext.sendBroadcastAsUser(mDreamingStoppedIntent, UserHandle.ALL);
                    mSentStartBroadcast = false;
                }

                mListener.onDreamStopped(dream.mToken);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
    }

    /**
     * Stops all previous dreams, if any.
     */
    private void stopPreviousDreams() {
        if (mPreviousDreams.isEmpty()) {
            return;
        }

        // Using an iterator because mPreviousDreams is modified while the iteration is in process.
        for (final Iterator<DreamRecord> it = mPreviousDreams.iterator(); it.hasNext(); ) {
            stopDreamInstance(true /*immediate*/, "stop previous dream", it.next());
            it.remove();
        }
    }

    private void attach(IDreamService service) {
        try {
            service.asBinder().linkToDeath(mCurrentDream, 0);
            service.attach(mCurrentDream.mToken, mCurrentDream.mCanDoze,
                    mCurrentDream.mDreamingStartedCallback);
        } catch (RemoteException ex) {
            Slog.e(TAG, "The dream service died unexpectedly.", ex);
            stopDream(true /*immediate*/, "attach failed");
            return;
        }

        mCurrentDream.mService = service;

        if (!mCurrentDream.mIsPreviewMode && !mSentStartBroadcast) {
            mContext.sendBroadcastAsUser(mDreamingStartedIntent, UserHandle.ALL);
            mSentStartBroadcast = true;
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
        public final boolean mIsPreviewMode;
        public final boolean mCanDoze;
        public final int mUserId;

        public PowerManager.WakeLock mWakeLock;
        public boolean mBound;
        public boolean mConnected;
        public IDreamService mService;
        private String mStopReason;
        private long mDreamStartTime;
        public boolean mWakingGently;

        private final Runnable mStopPreviousDreamsIfNeeded = this::stopPreviousDreamsIfNeeded;
        private final Runnable mReleaseWakeLockIfNeeded = this::releaseWakeLockIfNeeded;

        private final Runnable mStopUnconnectedDreamRunnable = () -> {
            if (mBound && !mConnected) {
                Slog.w(TAG, "Bound dream did not connect in the time allotted");
                stopDream(true /*immediate*/, "slow to connect" /*reason*/);
            }
        };

        private final Runnable mStopStubbornDreamRunnable = () -> {
            Slog.w(TAG, "Stubborn dream did not finish itself in the time allotted");
            stopDream(true /*immediate*/, "slow to finish" /*reason*/);
            mStopReason = null;
        };

        private final IRemoteCallback mDreamingStartedCallback = new IRemoteCallback.Stub() {
            // May be called on any thread.
            @Override
            public void sendResult(Bundle data) {
                mHandler.post(mStopPreviousDreamsIfNeeded);
                mHandler.post(mReleaseWakeLockIfNeeded);
            }
        };

        DreamRecord(Binder token, ComponentName name, boolean isPreviewMode,
                boolean canDoze, int userId, PowerManager.WakeLock wakeLock) {
            mToken = token;
            mName = name;
            mIsPreviewMode = isPreviewMode;
            mCanDoze = canDoze;
            mUserId  = userId;
            mWakeLock = wakeLock;
            // Hold the lock while we're waiting for the service to connect and start dreaming.
            // Released after the service has started dreaming, we stop dreaming, or it timed out.
            if (mWakeLock != null) {
                mWakeLock.acquire();
            }
            mHandler.postDelayed(mReleaseWakeLockIfNeeded, 10000);
        }

        // May be called on any thread.
        @Override
        public void binderDied() {
            mHandler.post(() -> {
                mService = null;
                if (mCurrentDream == DreamRecord.this) {
                    stopDream(true /*immediate*/, "binder died");
                }
            });
        }

        // May be called on any thread.
        @Override
        public void onServiceConnected(ComponentName name, final IBinder service) {
            mHandler.post(() -> {
                mConnected = true;
                if (mCurrentDream == DreamRecord.this && mService == null) {
                    attach(IDreamService.Stub.asInterface(service));
                    // Wake lock will be released once dreaming starts.
                } else {
                    releaseWakeLockIfNeeded();
                }
            });
        }

        // May be called on any thread.
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mHandler.post(() -> {
                mService = null;
                if (mCurrentDream == DreamRecord.this) {
                    stopDream(true /*immediate*/, "service disconnected");
                }
            });
        }

        void stopPreviousDreamsIfNeeded() {
            if (mCurrentDream == DreamRecord.this) {
                stopPreviousDreams();
            }
        }

        void releaseWakeLockIfNeeded() {
            if (mWakeLock != null) {
                mWakeLock.release();
                mWakeLock = null;
                mHandler.removeCallbacks(mReleaseWakeLockIfNeeded);
            }
        }
    }
}
