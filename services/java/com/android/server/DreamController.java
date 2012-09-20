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

package com.android.server;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.IBinder.DeathRecipient;
import android.service.dreams.IDreamService;
import android.util.Slog;
import android.view.IWindowManager;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import com.android.internal.util.DumpUtils;

import java.io.PrintWriter;
import java.util.NoSuchElementException;

/**
 * Internal controller for starting and stopping the current dream and managing related state.
 *
 * Assumes all operations (except {@link #dump}) are called from a single thread.
 */
final class DreamController {
    private static final boolean DEBUG = true;
    private static final String TAG = DreamController.class.getSimpleName();

    public interface Listener {
        void onDreamStopped(boolean wasTest);
    }

    private final Context mContext;
    private final IWindowManager mIWindowManager;
    private final DeathRecipient mDeathRecipient;
    private final ServiceConnection mServiceConnection;
    private final Listener mListener;

    private Handler mHandler;

    private ComponentName mCurrentDreamComponent;
    private IDreamService mCurrentDream;
    private Binder mCurrentDreamToken;
    private boolean mCurrentDreamIsTest;

    public DreamController(Context context, DeathRecipient deathRecipient,
            ServiceConnection serviceConnection, Listener listener) {
        mContext = context;
        mDeathRecipient = deathRecipient;
        mServiceConnection = serviceConnection;
        mListener = listener;
        mIWindowManager = WindowManagerGlobal.getWindowManagerService();
    }

    public void setHandler(Handler handler) {
        mHandler = handler;
    }

    public void dump(PrintWriter pw) {
        if (mHandler== null || pw == null) {
            return;
        }
        DumpUtils.dumpAsync(mHandler, new DumpUtils.Dump() {
            @Override
            public void dump(PrintWriter pw) {
                pw.print("  component="); pw.println(mCurrentDreamComponent);
                pw.print("  token="); pw.println(mCurrentDreamToken);
                pw.print("  dream="); pw.println(mCurrentDream);
            }
        }, pw, 200);
    }

    public void start(ComponentName dream, boolean isTest) {
        if (DEBUG) Slog.v(TAG, String.format("start(%s,%s)", dream, isTest));

        if (mCurrentDreamComponent != null ) {
            if (dream.equals(mCurrentDreamComponent) && isTest == mCurrentDreamIsTest) {
                if (DEBUG) Slog.v(TAG, "Dream is already started: " + dream);
                return;
            }
            // stop the current dream before starting a new one
            stop();
        }

        mCurrentDreamComponent = dream;
        mCurrentDreamIsTest = isTest;
        mCurrentDreamToken = new Binder();

        try {
            if (DEBUG) Slog.v(TAG, "Adding window token: " + mCurrentDreamToken
                    + " for window type: " + WindowManager.LayoutParams.TYPE_DREAM);
            mIWindowManager.addWindowToken(mCurrentDreamToken,
                    WindowManager.LayoutParams.TYPE_DREAM);
        } catch (RemoteException e) {
            Slog.w(TAG, "Unable to add window token.");
            stop();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_MAIN)
                .setComponent(mCurrentDreamComponent)
                .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra("android.dreams.TEST", mCurrentDreamIsTest);

        if (!mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE)) {
            Slog.w(TAG, "Unable to bind service");
            stop();
            return;
        }
        if (DEBUG) Slog.v(TAG, "Bound service");
    }

    public void attach(ComponentName name, IBinder dream) {
        if (DEBUG) Slog.v(TAG, String.format("attach(%s,%s)", name, dream));
        mCurrentDream = IDreamService.Stub.asInterface(dream);

        boolean linked = linkDeathRecipient(dream);
        if (!linked) {
            stop();
            return;
        }

        try {
            if (DEBUG) Slog.v(TAG, "Attaching with token:" + mCurrentDreamToken);
            mCurrentDream.attach(mCurrentDreamToken);
        } catch (Throwable ex) {
            Slog.w(TAG, "Unable to send window token to dream:" + ex);
            stop();
        }
    }

    public void stop() {
        if (DEBUG) Slog.v(TAG, "stop()");

        if (mCurrentDream != null) {
            unlinkDeathRecipient(mCurrentDream.asBinder());

            if (DEBUG) Slog.v(TAG, "Unbinding: " +  mCurrentDreamComponent + " service: " + mCurrentDream);
            mContext.unbindService(mServiceConnection);
        }
        if (mCurrentDreamToken != null) {
            removeWindowToken(mCurrentDreamToken);
        }

        final boolean wasTest = mCurrentDreamIsTest;
        mCurrentDream = null;
        mCurrentDreamToken = null;
        mCurrentDreamComponent = null;
        mCurrentDreamIsTest = false;

        if (mListener != null && mHandler != null) {
            mHandler.post(new Runnable(){
                @Override
                public void run() {
                    mListener.onDreamStopped(wasTest);
                }});
        }
    }

    public void stopSelf(IBinder token) {
        if (DEBUG) Slog.v(TAG, String.format("stopSelf(%s)", token));
        if (token == null || token != mCurrentDreamToken) {
            Slog.w(TAG, "Stop requested for non-current dream token: " + token);
        } else {
            stop();
        }
    }

    private void removeWindowToken(IBinder token) {
        if (DEBUG) Slog.v(TAG, "Removing window token: " + token);
        try {
            mIWindowManager.removeWindowToken(token);
        } catch (Throwable e) {
            Slog.w(TAG, "Error removing window token", e);
        }
    }

    private boolean linkDeathRecipient(IBinder dream) {
        if (DEBUG) Slog.v(TAG, "Linking death recipient");
        try {
            dream.linkToDeath(mDeathRecipient, 0);
            return true;
        } catch (RemoteException e) {
            Slog.w(TAG, "Unable to link death recipient",  e);
            return false;
        }
    }

    private void unlinkDeathRecipient(IBinder dream) {
        if (DEBUG) Slog.v(TAG, "Unlinking death recipient");
        try {
            dream.unlinkToDeath(mDeathRecipient, 0);
        } catch (NoSuchElementException e) {
            // we tried
        }
    }

}