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
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.service.voice.IVoiceInteractionService;
import android.service.voice.IVoiceInteractionSession;
import android.service.voice.IVoiceInteractionSessionService;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionServiceInfo;
import android.util.Slog;
import android.view.IWindowManager;
import android.view.WindowManager;

import com.android.internal.app.IVoiceInteractor;

import java.io.FileDescriptor;
import java.io.PrintWriter;

class VoiceInteractionManagerServiceImpl {
    final static String TAG = "VoiceInteractionServiceManager";

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

    SessionConnection mActiveSession;

    final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
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

    final class SessionConnection implements ServiceConnection {
        final IBinder mToken = new Binder();
        final Bundle mArgs;
        boolean mBound;
        IVoiceInteractionSessionService mService;
        IVoiceInteractionSession mSession;
        IVoiceInteractor mInteractor;

        SessionConnection(Bundle args) {
            mArgs = args;
            Intent serviceIntent = new Intent(VoiceInteractionService.SERVICE_INTERFACE);
            serviceIntent.setComponent(mSessionComponentName);
            mBound = mContext.bindServiceAsUser(serviceIntent, this,
                    Context.BIND_AUTO_CREATE, new UserHandle(mUser));
            if (mBound) {
                try {
                    mIWindowManager.addWindowToken(mToken,
                            WindowManager.LayoutParams.TYPE_VOICE_INTERACTION);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed adding window token", e);
                }
            } else {
                Slog.w(TAG, "Failed binding to voice interaction session service " + mComponent);
            }
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                mService = IVoiceInteractionSessionService.Stub.asInterface(service);
                if (mActiveSession == this) {
                    try {
                        mService.newSession(mToken, mArgs);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed adding window token", e);
                    }
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }

        public void cancel() {
            if (mBound) {
                if (mSession != null) {
                    try {
                        mSession.destroy();
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Voice interation session already dead");
                    }
                }
                if (mSession != null) {
                    try {
                        mAm.finishVoiceTask(mSession);
                    } catch (RemoteException e) {
                    }
                }
                mContext.unbindService(this);
                try {
                    mIWindowManager.removeWindowToken(mToken);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed removing window token", e);
                }
                mBound = false;
                mService = null;
                mSession = null;
                mInteractor = null;
            }
        }

        public void dump(String prefix, PrintWriter pw) {
            pw.print(prefix); pw.print("mToken="); pw.println(mToken);
            pw.print(prefix); pw.print("mArgs="); pw.println(mArgs);
            pw.print(prefix); pw.print("mBound="); pw.println(mBound);
            if (mBound) {
                pw.print(prefix); pw.print("mService="); pw.println(mService);
                pw.print(prefix); pw.print("mSession="); pw.println(mSession);
                pw.print(prefix); pw.print("mInteractor="); pw.println(mInteractor);
            }
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

    public void startSessionLocked(int callingPid, int callingUid, Bundle args) {
        if (mActiveSession != null) {
            mActiveSession.cancel();
            mActiveSession = null;
        }
        mActiveSession = new SessionConnection(args);
    }

    public boolean deliverNewSessionLocked(int callingPid, int callingUid, IBinder token,
            IVoiceInteractionSession session, IVoiceInteractor interactor) {
        if (mActiveSession == null || token != mActiveSession.mToken) {
            Slog.w(TAG, "deliverNewSession does not match active session");
            return false;
        }
        mActiveSession.mSession = session;
        mActiveSession.mInteractor = interactor;
        return true;
    }

    public int startVoiceActivityLocked(int callingPid, int callingUid, IBinder token,
            Intent intent, String resolvedType) {
        try {
            if (mActiveSession == null || token != mActiveSession.mToken) {
                Slog.w(TAG, "startVoiceActivity does not match active session");
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


    public void finishLocked(int callingPid, int callingUid, IBinder token) {
        if (mActiveSession == null || token != mActiveSession.mToken) {
            Slog.w(TAG, "finish does not match active session");
            return;
        }
        mActiveSession.cancel();
        mActiveSession = null;
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
                Context.BIND_AUTO_CREATE, new UserHandle(mUser));
        if (!mBound) {
            Slog.w(TAG, "Failed binding to voice interaction service " + mComponent);
        }
    }

    void shutdownLocked() {
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
        }
        try {
            mService.soundModelsChanged();
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException while calling soundModelsChanged", e);
        }
    }
}
