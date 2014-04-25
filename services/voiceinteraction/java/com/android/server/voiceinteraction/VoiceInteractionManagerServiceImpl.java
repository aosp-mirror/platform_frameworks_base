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

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.voice.IVoiceInteractionService;
import android.service.voice.IVoiceInteractionSession;
import android.service.voice.VoiceInteractionService;
import android.util.Slog;
import com.android.internal.app.IVoiceInteractor;

class VoiceInteractionManagerServiceImpl {
    final static String TAG = "VoiceInteractionServiceManager";

    final Context mContext;
    final Handler mHandler;
    final Object mLock;
    final int mUser;
    final ComponentName mComponent;
    final IActivityManager mAm;
    boolean mBound = false;
    IVoiceInteractionService mService;
    IVoiceInteractionSession mActiveSession;
    IVoiceInteractor mActiveInteractor;

    final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                mService = IVoiceInteractionService.Stub.asInterface(service);
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
    }

    public int startVoiceActivityLocked(int callingPid, int callingUid, Intent intent,
            String resolvedType, IVoiceInteractionSession session, IVoiceInteractor interactor) {
        if (session == null) {
            throw new NullPointerException("session is null");
        }
        if (interactor == null) {
            throw new NullPointerException("interactor is null");
        }
        if (mActiveSession != null) {
            // XXX cancel current session.
        }
        intent.addCategory(Intent.CATEGORY_VOICE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        mActiveSession = session;
        mActiveInteractor = interactor;
        try {
            return mAm.startVoiceActivity(mComponent.getPackageName(), callingPid, callingUid,
                    intent, resolvedType, mActiveSession, mActiveInteractor,
                    0, null, null, null, mUser);
        } catch (RemoteException e) {
            throw new IllegalStateException("Unexpected remote error", e);
        }
    }

    void startLocked() {
        Intent intent = new Intent(VoiceInteractionService.SERVICE_INTERFACE);
        intent.setComponent(mComponent);
        try {
            ServiceInfo si = mContext.getPackageManager().getServiceInfo(mComponent, 0);
            if (!android.Manifest.permission.BIND_VOICE_INTERACTION.equals(si.permission)) {
                Slog.w(TAG, "Not using voice interaction service " + mComponent
                        + ": does not require permission "
                        + android.Manifest.permission.BIND_VOICE_INTERACTION);
                return;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Unable to find voice interaction service: " + mComponent, e);
            return;
        }
        mContext.bindServiceAsUser(intent, mConnection,
                Context.BIND_AUTO_CREATE, new UserHandle(mUser));
        mBound = true;
    }

    void shutdownLocked() {
        if (mBound) {
            mContext.unbindService(mConnection);
            mBound = false;
        }
    }
}
