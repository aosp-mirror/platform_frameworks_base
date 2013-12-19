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
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.IBinder.DeathRecipient;
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

    private final Context mContext;
    private final Handler mHandler;
    private final Listener mListener;
    private final IWindowManager mIWindowManager;

    private final Intent mDreamingStartedIntent = new Intent(Intent.ACTION_DREAMING_STARTED)
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
    private final Intent mDreamingStoppedIntent = new Intent(Intent.ACTION_DREAMING_STOPPED)
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);

    private final Intent mCloseNotificationShadeIntent = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);

    private DreamRecord mCurrentDream;

    private final Runnable mStopUnconnectedDreamRunnable = new Runnable() {
        @Override
        public void run() {
            if (mCurrentDream != null && mCurrentDream.mBound && !mCurrentDream.mConnected) {
                Slog.w(TAG, "Bound dream did not connect in the time allotted");
                stopDream();
            }
        }
    };

    public DreamController(Context context, Handler handler, Listener listener) {
        mContext = context;
        mHandler = handler;
        mListener = listener;
        mIWindowManager = WindowManagerGlobal.getWindowManagerService();
    }

    public void dump(PrintWriter pw) {
        pw.println("Dreamland:");
        if (mCurrentDream != null) {
            pw.println("  mCurrentDream:");
            pw.println("    mToken=" + mCurrentDream.mToken);
            pw.println("    mName=" + mCurrentDream.mName);
            pw.println("    mIsTest=" + mCurrentDream.mIsTest);
            pw.println("    mUserId=" + mCurrentDream.mUserId);
            pw.println("    mBound=" + mCurrentDream.mBound);
            pw.println("    mService=" + mCurrentDream.mService);
            pw.println("    mSentStartBroadcast=" + mCurrentDream.mSentStartBroadcast);
        } else {
            pw.println("  mCurrentDream: null");
        }
    }

    public void startDream(Binder token, ComponentName name, boolean isTest, int userId) {
        stopDream();

        // Close the notification shade. Don't need to send to all, but better to be explicit.
        mContext.sendBroadcastAsUser(mCloseNotificationShadeIntent, UserHandle.ALL);

        Slog.i(TAG, "Starting dream: name=" + name + ", isTest=" + isTest + ", userId=" + userId);

        mCurrentDream = new DreamRecord(token, name, isTest, userId);

        try {
            mIWindowManager.addWindowToken(token, WindowManager.LayoutParams.TYPE_DREAM);
        } catch (RemoteException ex) {
            Slog.e(TAG, "Unable to add window token for dream.", ex);
            stopDream();
            return;
        }

        Intent intent = new Intent(DreamService.SERVICE_INTERFACE);
        intent.setComponent(name);
        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            if (!mContext.bindServiceAsUser(intent, mCurrentDream,
                    Context.BIND_AUTO_CREATE, new UserHandle(userId))) {
                Slog.e(TAG, "Unable to bind dream service: " + intent);
                stopDream();
                return;
            }
        } catch (SecurityException ex) {
            Slog.e(TAG, "Unable to bind dream service: " + intent, ex);
            stopDream();
            return;
        }

        mCurrentDream.mBound = true;
        mHandler.postDelayed(mStopUnconnectedDreamRunnable, DREAM_CONNECTION_TIMEOUT);
    }

    public void stopDream() {
        if (mCurrentDream == null) {
            return;
        }

        final DreamRecord oldDream = mCurrentDream;
        mCurrentDream = null;
        Slog.i(TAG, "Stopping dream: name=" + oldDream.mName
                + ", isTest=" + oldDream.mIsTest + ", userId=" + oldDream.mUserId);

        mHandler.removeCallbacks(mStopUnconnectedDreamRunnable);

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
    }

    private void attach(IDreamService service) {
        try {
            service.asBinder().linkToDeath(mCurrentDream, 0);
            service.attach(mCurrentDream.mToken);
        } catch (RemoteException ex) {
            Slog.e(TAG, "The dream service died unexpectedly.", ex);
            stopDream();
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
        public final int mUserId;

        public boolean mBound;
        public boolean mConnected;
        public IDreamService mService;
        public boolean mSentStartBroadcast;

        public DreamRecord(Binder token, ComponentName name,
                boolean isTest, int userId) {
            mToken = token;
            mName = name;
            mIsTest = isTest;
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
                        stopDream();
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
                        stopDream();
                    }
                }
            });
        }
    }
}