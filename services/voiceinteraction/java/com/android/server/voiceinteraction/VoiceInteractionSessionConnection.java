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

package com.android.server.voiceinteraction;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.service.voice.IVoiceInteractionSession;
import android.service.voice.IVoiceInteractionSessionService;
import android.service.voice.VoiceInteractionService;
import android.util.Slog;
import android.view.IWindowManager;
import android.view.WindowManager;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.os.IResultReceiver;

import java.io.PrintWriter;

final class VoiceInteractionSessionConnection implements ServiceConnection {
    final static String TAG = "VoiceInteractionServiceManager";

    final IBinder mToken = new Binder();
    final Object mLock;
    final ComponentName mSessionComponentName;
    final Intent mBindIntent;
    final int mUser;
    final Context mContext;
    final Callback mCallback;
    final int mCallingPid;
    final int mCallingUid;
    final IActivityManager mAm;
    final IWindowManager mIWindowManager;
    boolean mShown;
    Bundle mShowArgs;
    int mShowFlags;
    boolean mBound;
    boolean mFullyBound;
    boolean mCanceled;
    IVoiceInteractionSessionService mService;
    IVoiceInteractionSession mSession;
    IVoiceInteractor mInteractor;
    boolean mHaveAssistData;
    Bundle mAssistData;

    public interface Callback {
        public void sessionConnectionGone(VoiceInteractionSessionConnection connection);
    }

    final ServiceConnection mFullConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    };

    final IResultReceiver mAssistReceiver = new IResultReceiver.Stub() {
        @Override
        public void send(int resultCode, Bundle resultData) throws RemoteException {
            synchronized (mLock) {
                if (mShown) {
                    if (mSession != null) {
                        try {
                            mSession.handleAssist(resultData);
                        } catch (RemoteException e) {
                        }
                    } else {
                        mHaveAssistData = true;
                        mAssistData = resultData;
                    }
                }
            }
        }
    };

    public VoiceInteractionSessionConnection(Object lock, ComponentName component, int user,
            Context context, Callback callback, int callingPid, int callingUid) {
        mLock = lock;
        mSessionComponentName = component;
        mUser = user;
        mContext = context;
        mCallback = callback;
        mCallingPid = callingPid;
        mCallingUid = callingUid;
        mAm = ActivityManagerNative.getDefault();
        mIWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));
        mBindIntent = new Intent(VoiceInteractionService.SERVICE_INTERFACE);
        mBindIntent.setComponent(mSessionComponentName);
        mBound = mContext.bindServiceAsUser(mBindIntent, this,
                Context.BIND_AUTO_CREATE|Context.BIND_ALLOW_OOM_MANAGEMENT, new UserHandle(mUser));
        if (mBound) {
            try {
                mIWindowManager.addWindowToken(mToken,
                        WindowManager.LayoutParams.TYPE_VOICE_INTERACTION);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed adding window token", e);
            }
        } else {
            Slog.w(TAG, "Failed binding to voice interaction session service "
                    + mSessionComponentName);
        }
    }

    public boolean showLocked(Bundle args, int flags) {
        if (mBound) {
            if (!mFullyBound) {
                mFullyBound = mContext.bindServiceAsUser(mBindIntent, mFullConnection,
                        Context.BIND_AUTO_CREATE|Context.BIND_TREAT_LIKE_ACTIVITY,
                        new UserHandle(mUser));
            }
            mShown = true;
            mShowArgs = args;
            mShowFlags = flags;
            if ((flags&VoiceInteractionService.START_WITH_ASSIST) != 0) {
                try {
                    mAm.requestAssistContextExtras(0, mAssistReceiver);
                } catch (RemoteException e) {
                }
            } else {
                mHaveAssistData = false;
                mAssistData = null;
            }
            if (mSession != null) {
                try {
                    mSession.show(mShowArgs, mShowFlags);
                    mShowArgs = null;
                    mShowFlags = 0;
                } catch (RemoteException e) {
                }
                if (mHaveAssistData) {
                    try {
                        mSession.handleAssist(mAssistData);
                        mAssistData = null;
                        mHaveAssistData = false;
                    } catch (RemoteException e) {
                    }
                }
            }
            return true;
        }
        return false;
    }

    public boolean hideLocked() {
        if (mBound) {
            if (mShown) {
                mShown = false;
                mShowArgs = null;
                mShowFlags = 0;
                mHaveAssistData = false;
                mAssistData = null;
                if (mSession != null) {
                    try {
                        mSession.hide();
                    } catch (RemoteException e) {
                    }
                }
            }
            if (mFullyBound) {
                mContext.unbindService(mFullConnection);
                mFullyBound = false;
            }
            return true;
        }
        return false;
    }

    public boolean deliverNewSessionLocked(IVoiceInteractionSession session,
            IVoiceInteractor interactor) {
        mSession = session;
        mInteractor = interactor;
        if (mShown) {
            try {
                session.show(mShowArgs, mShowFlags);
                mShowArgs = null;
                mShowFlags = 0;
            } catch (RemoteException e) {
            }
            if (mHaveAssistData) {
                try {
                    session.handleAssist(mAssistData);
                    mAssistData = null;
                    mHaveAssistData = false;
                } catch (RemoteException e) {
                }
            }
        }
        return true;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        synchronized (mLock) {
            mService = IVoiceInteractionSessionService.Stub.asInterface(service);
            if (!mCanceled) {
                try {
                    mService.newSession(mToken, mShowArgs, mShowFlags);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed adding window token", e);
                }
            }
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mCallback.sessionConnectionGone(this);
        mService = null;
    }

    public void cancel() {
        mCanceled = true;
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
        if (mFullyBound) {
            mContext.unbindService(mFullConnection);
            mFullyBound = false;
        }
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mToken="); pw.println(mToken);
        pw.print(prefix); pw.print("mShown="); pw.println(mShown);
        pw.print(prefix); pw.print("mShowArgs="); pw.println(mShowArgs);
        pw.print(prefix); pw.print("mShowFlags=0x"); pw.println(Integer.toHexString(mShowFlags));
        pw.print(prefix); pw.print("mBound="); pw.println(mBound);
        if (mBound) {
            pw.print(prefix); pw.print("mService="); pw.println(mService);
            pw.print(prefix); pw.print("mSession="); pw.println(mSession);
            pw.print(prefix); pw.print("mInteractor="); pw.println(mInteractor);
        }
        pw.print(prefix); pw.print("mHaveAssistData="); pw.println(mHaveAssistData);
        if (mHaveAssistData) {
            pw.print(prefix); pw.print("mAssistData="); pw.println(mAssistData);
        }
    }
};
