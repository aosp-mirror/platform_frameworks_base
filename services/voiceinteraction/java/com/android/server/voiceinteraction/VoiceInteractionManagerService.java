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

import android.Manifest;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;
import android.hardware.soundtrigger.SoundTrigger.ModuleProperties;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.voice.IVoiceInteractionService;
import android.service.voice.IVoiceInteractionSession;
import android.telephony.TelephonyManager;
import android.util.Slog;

import com.android.internal.app.IVoiceInteractionManagerService;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.server.SystemService;
import com.android.server.UiThread;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * SystemService that publishes an IVoiceInteractionManagerService.
 */
public class VoiceInteractionManagerService extends SystemService {

    static final String TAG = "VoiceInteractionManagerService";

    final Context mContext;
    final ContentResolver mResolver;
    final DatabaseHelper mDbHelper;
    final SoundTriggerHelper mSoundTriggerHelper;

    public VoiceInteractionManagerService(Context context) {
        super(context);
        mContext = context;
        mResolver = context.getContentResolver();
        mDbHelper = new DatabaseHelper(context);
        mSoundTriggerHelper = new SoundTriggerHelper(
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE));
    }

    @Override
    public void onStart() {
        publishBinderService(Context.VOICE_INTERACTION_MANAGER_SERVICE, mServiceStub);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            mServiceStub.systemRunning(isSafeMode());
        }
    }

    @Override
    public void onSwitchUser(int userHandle) {
        mServiceStub.switchUser(userHandle);
    }

    // implementation entry point and binder service
    private final VoiceInteractionManagerServiceStub mServiceStub
            = new VoiceInteractionManagerServiceStub();

    class VoiceInteractionManagerServiceStub extends IVoiceInteractionManagerService.Stub {

        VoiceInteractionManagerServiceImpl mImpl;

        private boolean mSafeMode;
        private int mCurUser;

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (RuntimeException e) {
                // The activity manager only throws security exceptions, so let's
                // log all others.
                if (!(e instanceof SecurityException)) {
                    Slog.wtf(TAG, "VoiceInteractionManagerService Crash", e);
                }
                throw e;
            }
        }

        public void systemRunning(boolean safeMode) {
            mSafeMode = safeMode;

            mPackageMonitor.register(mContext, BackgroundThread.getHandler().getLooper(),
                    UserHandle.ALL, true);
            new SettingsObserver(UiThread.getHandler());

            synchronized (this) {
                mCurUser = ActivityManager.getCurrentUser();
                switchImplementationIfNeededLocked(false);
            }
        }

        public void switchUser(int userHandle) {
            synchronized (this) {
                mCurUser = userHandle;
                switchImplementationIfNeededLocked(false);
            }
        }

        void switchImplementationIfNeededLocked(boolean force) {
            if (!mSafeMode) {
                String curService = Settings.Secure.getStringForUser(
                        mResolver, Settings.Secure.VOICE_INTERACTION_SERVICE, mCurUser);
                ComponentName serviceComponent = null;
                if (curService != null && !curService.isEmpty()) {
                    try {
                        serviceComponent = ComponentName.unflattenFromString(curService);
                    } catch (RuntimeException e) {
                        Slog.wtf(TAG, "Bad voice interaction service name " + curService, e);
                        serviceComponent = null;
                    }
                }
                if (force || mImpl == null || mImpl.mUser != mCurUser
                        || !mImpl.mComponent.equals(serviceComponent)) {
                    mSoundTriggerHelper.stopAllRecognitions();
                    if (mImpl != null) {
                        mImpl.shutdownLocked();
                    }
                    if (serviceComponent != null) {
                        mImpl = new VoiceInteractionManagerServiceImpl(mContext,
                                UiThread.getHandler(), this, mCurUser, serviceComponent);
                        mImpl.startLocked();
                    } else {
                        mImpl = null;
                    }
                }
            }
        }

        @Override
        public void startSession(IVoiceInteractionService service, Bundle args) {
            synchronized (this) {
                if (mImpl == null || mImpl.mService == null
                        || service.asBinder() != mImpl.mService.asBinder()) {
                    throw new SecurityException(
                            "Caller is not the current voice interaction service");
                }
                final int callingPid = Binder.getCallingPid();
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.startSessionLocked(callingPid, callingUid, args);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public boolean deliverNewSession(IBinder token, IVoiceInteractionSession session,
                IVoiceInteractor interactor) {
            synchronized (this) {
                if (mImpl == null) {
                    throw new SecurityException(
                            "deliverNewSession without running voice interaction service");
                }
                final int callingPid = Binder.getCallingPid();
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.deliverNewSessionLocked(callingPid, callingUid, token, session,
                            interactor);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public int startVoiceActivity(IBinder token, Intent intent, String resolvedType) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "startVoiceActivity without running voice interaction service");
                    return ActivityManager.START_CANCELED;
                }
                final int callingPid = Binder.getCallingPid();
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.startVoiceActivityLocked(callingPid, callingUid, token,
                            intent, resolvedType);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void finish(IBinder token) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "finish without running voice interaction service");
                    return;
                }
                final int callingPid = Binder.getCallingPid();
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.finishLocked(callingPid, callingUid, token);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        //----------------- Model management APIs --------------------------------//

        @Override
        public KeyphraseSoundModel getKeyphraseSoundModel(int keyphraseId) {
            synchronized (this) {
                if (mContext.checkCallingPermission(Manifest.permission.MANAGE_VOICE_KEYPHRASES)
                        != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Caller does not hold the permission "
                            + Manifest.permission.MANAGE_VOICE_KEYPHRASES);
                }
            }

            final long caller = Binder.clearCallingIdentity();
            try {
                return mDbHelper.getKeyphraseSoundModel(keyphraseId);
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        @Override
        public int updateKeyphraseSoundModel(KeyphraseSoundModel model) {
            synchronized (this) {
                if (mContext.checkCallingPermission(Manifest.permission.MANAGE_VOICE_KEYPHRASES)
                        != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Caller does not hold the permission "
                            + Manifest.permission.MANAGE_VOICE_KEYPHRASES);
                }
                if (model == null) {
                    throw new IllegalArgumentException("Model must not be null");
                }
            }

            final long caller = Binder.clearCallingIdentity();
            try {
                if (mDbHelper.updateKeyphraseSoundModel(model)) {
                    synchronized (this) {
                        // Notify the voice interaction service of a change in sound models.
                        if (mImpl != null && mImpl.mService != null) {
                            mImpl.notifySoundModelsChangedLocked();
                        }
                    }
                    return SoundTriggerHelper.STATUS_OK;
                } else {
                    return SoundTriggerHelper.STATUS_ERROR;
                }
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        @Override
        public int deleteKeyphraseSoundModel(int keyphraseId) {
            synchronized (this) {
                if (mContext.checkCallingPermission(Manifest.permission.MANAGE_VOICE_KEYPHRASES)
                        != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Caller does not hold the permission "
                            + Manifest.permission.MANAGE_VOICE_KEYPHRASES);
                }
            }

            final long caller = Binder.clearCallingIdentity();
            boolean deleted = false;
            try {
                KeyphraseSoundModel soundModel = mDbHelper.getKeyphraseSoundModel(keyphraseId);
                if (soundModel != null) {
                    deleted = mDbHelper.deleteKeyphraseSoundModel(soundModel.uuid);
                }
                return deleted ? SoundTriggerHelper.STATUS_OK : SoundTriggerHelper.STATUS_ERROR;
            } finally {
                if (deleted) {
                    synchronized (this) {
                        // Notify the voice interaction service of a change in sound models.
                        if (mImpl != null && mImpl.mService != null) {
                            mImpl.notifySoundModelsChangedLocked();
                        }
                    }
                }
                Binder.restoreCallingIdentity(caller);
            }
        }

        //----------------- SoundTrigger APIs --------------------------------//
        @Override
        public boolean isEnrolledForKeyphrase(IVoiceInteractionService service, int keyphraseId) {
            synchronized (this) {
                if (mImpl == null || mImpl.mService == null
                        || service.asBinder() != mImpl.mService.asBinder()) {
                    throw new SecurityException(
                            "Caller is not the current voice interaction service");
                }
            }

            final long caller = Binder.clearCallingIdentity();
            try {
                KeyphraseSoundModel model = mDbHelper.getKeyphraseSoundModel(keyphraseId);
                return model != null;
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        @Override
        public ModuleProperties getDspModuleProperties(IVoiceInteractionService service) {
            // Allow the call if this is the current voice interaction service.
            synchronized (this) {
                if (mImpl == null || mImpl.mService == null
                        || service == null || service.asBinder() != mImpl.mService.asBinder()) {
                    throw new SecurityException(
                            "Caller is not the current voice interaction service");
                }

                final long caller = Binder.clearCallingIdentity();
                try {
                    return mSoundTriggerHelper.moduleProperties;
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public int startRecognition(IVoiceInteractionService service, int keyphraseId,
                IRecognitionStatusCallback callback, RecognitionConfig recognitionConfig) {
            // Allow the call if this is the current voice interaction service.
            synchronized (this) {
                if (mImpl == null || mImpl.mService == null
                        || service == null || service.asBinder() != mImpl.mService.asBinder()) {
                    throw new SecurityException(
                            "Caller is not the current voice interaction service");
                }

                if (callback == null || recognitionConfig == null) {
                    throw new IllegalArgumentException("Illegal argument(s) in startRecognition");
                }
            }

            final long caller = Binder.clearCallingIdentity();
            try {
                KeyphraseSoundModel soundModel = mDbHelper.getKeyphraseSoundModel(keyphraseId);
                if (soundModel == null
                        || soundModel.uuid == null
                        || soundModel.keyphrases == null) {
                    Slog.w(TAG, "No matching sound model found in startRecognition");
                    return SoundTriggerHelper.STATUS_ERROR;
                } else {
                    return mSoundTriggerHelper.startRecognition(
                            keyphraseId, soundModel, callback, recognitionConfig);
                }
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        @Override
        public int stopRecognition(IVoiceInteractionService service, int keyphraseId,
                IRecognitionStatusCallback callback) {
            // Allow the call if this is the current voice interaction service.
            synchronized (this) {
                if (mImpl == null || mImpl.mService == null
                        || service == null || service.asBinder() != mImpl.mService.asBinder()) {
                    throw new SecurityException(
                            "Caller is not the current voice interaction service");
                }
            }

            final long caller = Binder.clearCallingIdentity();
            try {
                return mSoundTriggerHelper.stopRecognition(keyphraseId, callback);
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (mContext.checkCallingOrSelfPermission(Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump PowerManager from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
                return;
            }
            synchronized (this) {
                pw.println("VOICE INTERACTION MANAGER (dumpsys voiceinteraction)\n");
                if (mImpl == null) {
                    pw.println("  (No active implementation)");
                    return;
                }
                mImpl.dumpLocked(fd, pw, args);
            }
            mSoundTriggerHelper.dump(fd, pw, args);
        }

        class SettingsObserver extends ContentObserver {
            SettingsObserver(Handler handler) {
                super(handler);
                ContentResolver resolver = mContext.getContentResolver();
                resolver.registerContentObserver(Settings.Secure.getUriFor(
                        Settings.Secure.VOICE_INTERACTION_SERVICE), false, this);
            }

            @Override public void onChange(boolean selfChange) {
                synchronized (VoiceInteractionManagerServiceStub.this) {
                    switchImplementationIfNeededLocked(false);
                }
            }
        }

        PackageMonitor mPackageMonitor = new PackageMonitor() {
            @Override
            public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
                return super.onHandleForceStop(intent, packages, uid, doit);
            }

            @Override
            public void onHandleUserStop(Intent intent, int userHandle) {
            }

            @Override
            public void onPackageDisappeared(String packageName, int reason) {
            }

            @Override
            public void onPackageAppeared(String packageName, int reason) {
                if (mImpl != null && packageName.equals(mImpl.mComponent.getPackageName())) {
                    switchImplementationIfNeededLocked(true);
                }
            }

            @Override
            public void onPackageModified(String packageName) {
            }

            @Override
            public void onSomePackagesChanged() {
            }
        };
    }
}
