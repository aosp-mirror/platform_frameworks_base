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
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
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
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionServiceInfo;
import android.speech.RecognitionService;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.app.IVoiceInteractionManagerService;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.server.SystemService;
import com.android.server.UiThread;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * SystemService that publishes an IVoiceInteractionManagerService.
 */
public class VoiceInteractionManagerService extends SystemService {
    static final String TAG = "VoiceInteractionManagerService";
    static final boolean DEBUG = false;

    final Context mContext;
    final ContentResolver mResolver;
    final DatabaseHelper mDbHelper;
    final SoundTriggerHelper mSoundTriggerHelper;

    public VoiceInteractionManagerService(Context context) {
        super(context);
        mContext = context;
        mResolver = context.getContentResolver();
        mDbHelper = new DatabaseHelper(context);
        mSoundTriggerHelper = new SoundTriggerHelper(context);
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
    public void onStartUser(int userHandle) {
        mServiceStub.initForUser(userHandle);
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

        public void initForUser(int userHandle) {
            if (DEBUG) Slog.i(TAG, "initForUser user=" + userHandle);
            String curInteractorStr = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.VOICE_INTERACTION_SERVICE, userHandle);
            ComponentName curRecognizer = getCurRecognizer(userHandle);
            VoiceInteractionServiceInfo curInteractorInfo = null;
            if (curInteractorStr == null && curRecognizer != null) {
                // If there is no interactor setting, that means we are upgrading
                // from an older platform version.  If the current recognizer is not
                // set or matches the preferred recognizer, then we want to upgrade
                // the user to have the default voice interaction service enabled.
                curInteractorInfo = findAvailInteractor(userHandle, curRecognizer);
                if (curInteractorInfo != null) {
                    // Looks good!  We'll apply this one.  To make it happen, we clear the
                    // recognizer so that we don't think we have anything set and will
                    // re-apply the settings.
                    curRecognizer = null;
                }
            }

            if (curRecognizer != null) {
                // If we already have at least a recognizer, then we probably want to
                // leave things as they are...  unless something has disappeared.
                IPackageManager pm = AppGlobals.getPackageManager();
                ServiceInfo interactorInfo = null;
                ServiceInfo recognizerInfo = null;
                ComponentName curInteractor = !TextUtils.isEmpty(curInteractorStr)
                        ? ComponentName.unflattenFromString(curInteractorStr) : null;
                try {
                    recognizerInfo = pm.getServiceInfo(curRecognizer, 0, userHandle);
                    if (curInteractor != null) {
                        interactorInfo = pm.getServiceInfo(curInteractor, 0, userHandle);
                    }
                } catch (RemoteException e) {
                }
                // If the apps for the currently set components still exist, then all is okay.
                if (recognizerInfo != null && (curInteractor == null || interactorInfo != null)) {
                    return;
                }
            }

            // Initializing settings, look for an interactor first.
            if (curInteractorInfo == null) {
                curInteractorInfo = findAvailInteractor(userHandle, null);
            }
            if (curInteractorInfo != null) {
                // Eventually it will be an error to not specify this.
                setCurInteractor(new ComponentName(curInteractorInfo.getServiceInfo().packageName,
                        curInteractorInfo.getServiceInfo().name), userHandle);
                if (curInteractorInfo.getRecognitionService() != null) {
                    setCurRecognizer(
                            new ComponentName(curInteractorInfo.getServiceInfo().packageName,
                                    curInteractorInfo.getRecognitionService()), userHandle);
                    return;
                }
            }

            // No voice interactor, we'll just set up a simple recognizer.
            curRecognizer = findAvailRecognizer(null, userHandle);
            if (curRecognizer != null) {
                if (curInteractorInfo == null) {
                    setCurInteractor(null, userHandle);
                }
                setCurRecognizer(curRecognizer, userHandle);
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

        VoiceInteractionServiceInfo findAvailInteractor(int userHandle, ComponentName recognizer) {
            List<ResolveInfo> available =
                    mContext.getPackageManager().queryIntentServicesAsUser(
                            new Intent(VoiceInteractionService.SERVICE_INTERFACE), 0, userHandle);
            int numAvailable = available.size();

            if (numAvailable == 0) {
                Slog.w(TAG, "no available voice interaction services found for user " + userHandle);
                return null;
            } else {
                // Find first system package.  We never want to allow third party services to
                // be automatically selected, because those require approval of the user.
                VoiceInteractionServiceInfo foundInfo = null;
                for (int i=0; i<numAvailable; i++) {
                    ServiceInfo cur = available.get(i).serviceInfo;
                    if ((cur.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM) != 0) {
                        ComponentName comp = new ComponentName(cur.packageName, cur.name);
                        try {
                            VoiceInteractionServiceInfo info = new VoiceInteractionServiceInfo(
                                    mContext.getPackageManager(), comp, userHandle);
                            if (info.getParseError() == null) {
                                if (recognizer == null || info.getServiceInfo().packageName.equals(
                                        recognizer.getPackageName())) {
                                    if (foundInfo == null) {
                                        foundInfo = info;
                                    } else {
                                        Slog.w(TAG, "More than one voice interaction service, "
                                                + "picking first "
                                                + new ComponentName(
                                                        foundInfo.getServiceInfo().packageName,
                                                        foundInfo.getServiceInfo().name)
                                                + " over "
                                                + new ComponentName(cur.packageName, cur.name));
                                    }
                                }
                            } else {
                                Slog.w(TAG, "Bad interaction service " + comp + ": "
                                        + info.getParseError());
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            Slog.w(TAG, "Failure looking up interaction service " + comp);
                        } catch (RemoteException e) {
                        }
                    }
                }

                return foundInfo;
            }
        }

        ComponentName getCurInteractor(int userHandle) {
            String curInteractor = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.VOICE_INTERACTION_SERVICE, userHandle);
            if (TextUtils.isEmpty(curInteractor)) {
                return null;
            }
            if (DEBUG) Slog.i(TAG, "getCurInteractor curInteractor=" + curInteractor
                    + " user=" + userHandle);
            return ComponentName.unflattenFromString(curInteractor);
        }

        void setCurInteractor(ComponentName comp, int userHandle) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.VOICE_INTERACTION_SERVICE,
                    comp != null ? comp.flattenToShortString() : "", userHandle);
            if (DEBUG) Slog.i(TAG, "setCurInteractor comp=" + comp
                    + " user=" + userHandle);
        }

        ComponentName findAvailRecognizer(String prefPackage, int userHandle) {
            List<ResolveInfo> available =
                    mContext.getPackageManager().queryIntentServicesAsUser(
                            new Intent(RecognitionService.SERVICE_INTERFACE), 0, userHandle);
            int numAvailable = available.size();

            if (numAvailable == 0) {
                Slog.w(TAG, "no available voice recognition services found for user " + userHandle);
                return null;
            } else {
                if (prefPackage != null) {
                    for (int i=0; i<numAvailable; i++) {
                        ServiceInfo serviceInfo = available.get(i).serviceInfo;
                        if (prefPackage.equals(serviceInfo.packageName)) {
                            return new ComponentName(serviceInfo.packageName, serviceInfo.name);
                        }
                    }
                }
                if (numAvailable > 1) {
                    Slog.w(TAG, "more than one voice recognition service found, picking first");
                }

                ServiceInfo serviceInfo = available.get(0).serviceInfo;
                return new ComponentName(serviceInfo.packageName, serviceInfo.name);
            }
        }

        ComponentName getCurRecognizer(int userHandle) {
            String curRecognizer = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.VOICE_RECOGNITION_SERVICE, userHandle);
            if (TextUtils.isEmpty(curRecognizer)) {
                return null;
            }
            if (DEBUG) Slog.i(TAG, "getCurRecognizer curRecognizer=" + curRecognizer
                    + " user=" + userHandle);
            return ComponentName.unflattenFromString(curRecognizer);
        }

        void setCurRecognizer(ComponentName comp, int userHandle) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.VOICE_RECOGNITION_SERVICE,
                    comp != null ? comp.flattenToShortString() : "", userHandle);
            if (DEBUG) Slog.i(TAG, "setCurRecognizer comp=" + comp
                    + " user=" + userHandle);
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
            public void onSomePackagesChanged() {
                int userHandle = getChangingUserId();
                if (DEBUG) Slog.i(TAG, "onSomePackagesChanged user=" + userHandle);

                ComponentName curInteractor = getCurInteractor(userHandle);
                ComponentName curRecognizer = getCurRecognizer(userHandle);
                if (curRecognizer == null) {
                    // Could a new recognizer appear when we don't have one pre-installed?
                    if (anyPackagesAppearing()) {
                        curRecognizer = findAvailRecognizer(null, userHandle);
                        if (curRecognizer != null) {
                            setCurRecognizer(curRecognizer, userHandle);
                        }
                    }
                    return;
                }

                if (curInteractor != null) {
                    int change = isPackageDisappearing(curInteractor.getPackageName());
                    if (change == PACKAGE_PERMANENT_CHANGE) {
                        // The currently set interactor is permanently gone; fall back to
                        // the default config.
                        setCurInteractor(null, userHandle);
                        setCurRecognizer(null, userHandle);
                        initForUser(userHandle);
                        return;
                    }

                    change = isPackageAppearing(curInteractor.getPackageName());
                    if (change != PACKAGE_UNCHANGED) {
                        // If current interactor is now appearing, for any reason, then
                        // restart our connection with it.
                        if (mImpl != null && curInteractor.getPackageName().equals(
                                mImpl.mComponent.getPackageName())) {
                            switchImplementationIfNeededLocked(true);
                        }
                    }
                    return;
                }

                // There is no interactor, so just deal with a simple recognizer.
                int change = isPackageDisappearing(curRecognizer.getPackageName());
                if (change == PACKAGE_PERMANENT_CHANGE
                        || change == PACKAGE_TEMPORARY_CHANGE) {
                    setCurRecognizer(findAvailRecognizer(null, userHandle), userHandle);

                } else if (isPackageModified(curRecognizer.getPackageName())) {
                    setCurRecognizer(findAvailRecognizer(curRecognizer.getPackageName(),
                            userHandle), userHandle);
                }
            }
        };
    }
}
