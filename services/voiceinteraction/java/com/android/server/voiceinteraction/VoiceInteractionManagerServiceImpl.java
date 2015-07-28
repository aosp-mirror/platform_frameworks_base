/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.voiceinteraction;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.service.voice.IVoiceInteractionService;
import android.service.voice.IVoiceInteractionSession;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionServiceInfo;
import android.util.Slog;
import android.view.IWindowManager;

import com.android.internal.app.IVoiceInteractionSessionShowCallback;
import com.android.internal.app.IVoiceInteractor;

import java.io.FileDescriptor;
import java.io.PrintWriter;

class VoiceInteractionManagerServiceImpl implements VoiceInteractionSessionConnection.Callback {
    final static String TAG = "VoiceInteractionServiceManager";

    final static String CLOSE_REASON_VOICE_INTERACTION = "voiceinteraction";

    final boolean mValid;

    final Context mContext;
    final Handler mHandler;
    final Object mLock;
    final int mUser;
    final ComponentName mComponent;
    final IActivityManager mAm;
    final VoiceInteractionServiceInfo mInfo;
    final ComponentName mSessionComponentName;
    final IWindowManager mIWindowManager;
    boolean mBound = false;
    IVoiceInteractionService mService;

    VoiceInteractionSessionConnection mActiveSession;
    int mDisabledShowContext;

    final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                String reason = intent.getStringExtra("reason");
                if (!CLOSE_REASON_VOICE_INTERACTION.equals(reason) && !"dream".equals(reason)) {
                    synchronized (mLock) {
                        if (mActiveSession != null && mActiveSession.mSession != null) {
                            try {
                                mActiveSession.mSession.closeSystemDialogs();
                            } catch (RemoteException e) {
                            }
                        }
                    }
                }
            }
        }
    };

    final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                mService = IVoiceInteractionService.Stub.asInterface(service);
                try {
                    mService.ready();
                } catch (RemoteException e) {
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    };

    VoiceInteractionManagerServiceImpl(Context context, Handler handler, Object lock,
            int userHandle, ComponentName service) {
        mContext = context;
        mHandler = handler;
        mLock = lock;
        mUser = userHandle;
        mComponent = service;
        mAm = ActivityManagerNative.getDefault();
        VoiceInteractionServiceInfo info;
        try {
            info = new VoiceInteractionServiceInfo(context.getPackageManager(), service);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Voice interaction service not found: " + service);
            mInfo = null;
            mSessionComponentName = null;
            mIWindowManager = null;
            mValid = false;
            return;
        }
        mInfo = info;
        if (mInfo.getParseError() != null) {
            Slog.w(TAG, "Bad voice interaction service: " + mInfo.getParseError());
            mSessionComponentName = null;
            mIWindowManager = null;
            mValid = false;
            return;
        }
        mValid = true;
        mSessionComponentName = new ComponentName(service.getPackageName(),
                mInfo.getSessionService());
        mIWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mContext.registerReceiver(mBroadcastReceiver, filter, null, handler);
    }

    public boolean showSessionLocked(Bundle args, int flags,
            IVoiceInteractionSessionShowCallback showCallback, IBinder activityToken) {
        if (mActiveSession == null) {
            mActiveSession = new VoiceInteractionSessionConnection(mLock, mSessionComponentName,
                    mUser, mContext, this, mInfo.getServiceInfo().applicationInfo.uid, mHandler);
        }
        return mActiveSession.showLocked(args, flags, mDisabledShowContext, showCallback,
                activityToken);
    }

    public boolean hideSessionLocked() {
        if (mActiveSession != null) {
            return mActiveSession.hideLocked();
        }
        return false;
    }

    public boolean deliverNewSessionLocked(IBinder token,
            IVoiceInteractionSession session, IVoiceInteractor interactor) {
        if (mActiveSession == null || token != mActiveSession.mToken) {
            Slog.w(TAG, "deliverNewSession does not match active session");
            return false;
        }
        mActiveSession.deliverNewSessionLocked(session, interactor);
        return true;
    }

    public int startVoiceActivityLocked(int callingPid, int callingUid, IBinder token,
            Intent intent, String resolvedType) {
        try {
            if (mActiveSession == null || token != mActiveSession.mToken) {
                Slog.w(TAG, "startVoiceActivity does not match active session");
                return ActivityManager.START_CANCELED;
            }
            if (!mActiveSession.mShown) {
                Slog.w(TAG, "startVoiceActivity not allowed on hidden session");
                return ActivityManager.START_CANCELED;
            }
            intent = new Intent(intent);
            intent.addCategory(Intent.CATEGORY_VOICE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            return mAm.startVoiceActivity(mComponent.getPackageName(), callingPid, callingUid,
                    intent, resolvedType, mActiveSession.mSession, mActiveSession.mInteractor,
                    0, null, null, mUser);
        } catch (RemoteException e) {
            throw new IllegalStateException("Unexpected remote error", e);
        }
    }

    public void setKeepAwakeLocked(IBinder token, boolean keepAwake) {
        try {
            if (mActiveSession == null || token != mActiveSession.mToken) {
                Slog.w(TAG, "setKeepAwake does not match active session");
                return;
            }
            mAm.setVoiceKeepAwake(mActiveSession.mSession, keepAwake);
        } catch (RemoteException e) {
            throw new IllegalStateException("Unexpected remote error", e);
        }
    }

    public void closeSystemDialogsLocked(IBinder token) {
        try {
            if (mActiveSession == null || token != mActiveSession.mToken) {
                Slog.w(TAG, "closeSystemDialogs does not match active session");
                return;
            }
            mAm.closeSystemDialogs(CLOSE_REASON_VOICE_INTERACTION);
        } catch (RemoteException e) {
            throw new IllegalStateException("Unexpected remote error", e);
        }
    }

    public void finishLocked(IBinder token) {
        if (mActiveSession == null || token != mActiveSession.mToken) {
            Slog.w(TAG, "finish does not match active session");
            return;
        }
        mActiveSession.cancelLocked();
        mActiveSession = null;
    }

    public void setDisabledShowContextLocked(int callingUid, int flags) {
        int activeUid = mInfo.getServiceInfo().applicationInfo.uid;
        if (callingUid != activeUid) {
            throw new SecurityException("Calling uid " + callingUid
                    + " does not match active uid " + activeUid);
        }
        mDisabledShowContext = flags;
    }

    public int getDisabledShowContextLocked(int callingUid) {
        int activeUid = mInfo.getServiceInfo().applicationInfo.uid;
        if (callingUid != activeUid) {
            throw new SecurityException("Calling uid " + callingUid
                    + " does not match active uid " + activeUid);
        }
        return mDisabledShowContext;
    }

    public int getUserDisabledShowContextLocked(int callingUid) {
        int activeUid = mInfo.getServiceInfo().applicationInfo.uid;
        if (callingUid != activeUid) {
            throw new SecurityException("Calling uid " + callingUid
                    + " does not match active uid " + activeUid);
        }
        return mActiveSession != null ? mActiveSession.getUserDisabledShowContextLocked() : 0;
    }

    public void dumpLocked(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!mValid) {
            pw.print("  NOT VALID: ");
            if (mInfo == null) {
                pw.println("no info");
            } else {
                pw.println(mInfo.getParseError());
            }
            return;
        }
        pw.print("  mComponent="); pw.println(mComponent.flattenToShortString());
        pw.print("  Session service="); pw.println(mInfo.getSessionService());
        pw.print("  Settings activity="); pw.println(mInfo.getSettingsActivity());
        if (mDisabledShowContext != 0) {
            pw.print("  mDisabledShowContext=");
            pw.println(Integer.toHexString(mDisabledShowContext));
        }
        pw.print("  mBound="); pw.print(mBound);  pw.print(" mService="); pw.println(mService);
        if (mActiveSession != null) {
            pw.println("  Active session:");
            mActiveSession.dump("    ", pw);
        }
    }

    void startLocked() {
        Intent intent = new Intent(VoiceInteractionService.SERVICE_INTERFACE);
        intent.setComponent(mComponent);
        mBound = mContext.bindServiceAsUser(intent, mConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE, new UserHandle(mUser));
        if (!mBound) {
            Slog.w(TAG, "Failed binding to voice interaction service " + mComponent);
        }
    }

    public void launchVoiceAssistFromKeyguard() {
        if (mService == null) {
            Slog.w(TAG, "Not bound to voice interaction service " + mComponent);
            return;
        }
        try {
            mService.launchVoiceAssistFromKeyguard();
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException while calling launchVoiceAssistFromKeyguard", e);
        }
    }

    void shutdownLocked() {
        // If there is an active session, cancel it to allow it to clean up its window and other
        // state.
        if (mActiveSession != null) {
            mActiveSession.cancelLocked();
            mActiveSession = null;
        }
        try {
            if (mService != null) {
                mService.shutdown();
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException in shutdown", e);
        }

        if (mBound) {
            mContext.unbindService(mConnection);
            mBound = false;
        }
        if (mValid) {
            mContext.unregisterReceiver(mBroadcastReceiver);
        }
    }

    void notifySoundModelsChangedLocked() {
        if (mService == null) {
            Slog.w(TAG, "Not bound to voice interaction service " + mComponent);
            return;
        }
        try {
            mService.soundModelsChanged();
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException while calling soundModelsChanged", e);
        }
    }

    @Override
    public void sessionConnectionGone(VoiceInteractionSessionConnection connection) {
        synchronized (mLock) {
            finishLocked(connection.mToken);
        }
    }
}
