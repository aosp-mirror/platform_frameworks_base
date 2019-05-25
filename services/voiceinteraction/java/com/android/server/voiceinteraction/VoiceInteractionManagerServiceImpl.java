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

import static android.app.ActivityManager.START_ASSISTANT_HIDDEN_SESSION;
import static android.app.ActivityManager.START_ASSISTANT_NOT_ACTIVE_SESSION;
import static android.app.ActivityManager.START_VOICE_HIDDEN_SESSION;
import static android.app.ActivityManager.START_VOICE_NOT_ACTIVE_SESSION;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
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
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.service.voice.IVoiceInteractionService;
import android.service.voice.IVoiceInteractionSession;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionServiceInfo;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.view.IWindowManager;

import com.android.internal.app.IVoiceActionCheckCallback;
import com.android.internal.app.IVoiceInteractionSessionShowCallback;
import com.android.internal.app.IVoiceInteractor;
import com.android.server.LocalServices;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal.ActivityTokens;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

class VoiceInteractionManagerServiceImpl implements VoiceInteractionSessionConnection.Callback {
    final static String TAG = "VoiceInteractionServiceManager";

    final static String CLOSE_REASON_VOICE_INTERACTION = "voiceinteraction";

    final boolean mValid;

    final Context mContext;
    final Handler mHandler;
    final VoiceInteractionManagerService.VoiceInteractionManagerServiceStub mServiceStub;
    final int mUser;
    final ComponentName mComponent;
    final IActivityManager mAm;
    final IActivityTaskManager mAtm;
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
                    synchronized (mServiceStub) {
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
            synchronized (mServiceStub) {
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

    VoiceInteractionManagerServiceImpl(Context context, Handler handler,
            VoiceInteractionManagerService.VoiceInteractionManagerServiceStub stub,
            int userHandle, ComponentName service) {
        mContext = context;
        mHandler = handler;
        mServiceStub = stub;
        mUser = userHandle;
        mComponent = service;
        mAm = ActivityManager.getService();
        mAtm = ActivityTaskManager.getService();
        VoiceInteractionServiceInfo info;
        try {
            info = new VoiceInteractionServiceInfo(context.getPackageManager(), service, mUser);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Voice interaction service not found: " + service, e);
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
            mActiveSession = new VoiceInteractionSessionConnection(mServiceStub,
                    mSessionComponentName, mUser, mContext, this,
                    mInfo.getServiceInfo().applicationInfo.uid, mHandler);
        }
        List<IBinder> activityTokens = null;
        if (activityToken != null) {
            activityTokens = new ArrayList<>();
            activityTokens.add(activityToken);
        } else {
            // Let's get top activities from all visible stacks
            activityTokens = LocalServices.getService(ActivityTaskManagerInternal.class)
                    .getTopVisibleActivities();
        }
        return mActiveSession.showLocked(args, flags, mDisabledShowContext, showCallback,
                activityTokens);
    }

    public void getActiveServiceSupportedActions(List<String> commands,
            IVoiceActionCheckCallback callback) {
        if (mService == null) {
            Slog.w(TAG, "Not bound to voice interaction service " + mComponent);
            try {
                callback.onComplete(null);
            } catch (RemoteException e) {
            }
            return;
        }
        try {
            mService.getActiveServiceSupportedActions(commands, callback);
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException while calling getActiveServiceSupportedActions", e);
        }
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
                return START_VOICE_NOT_ACTIVE_SESSION;
            }
            if (!mActiveSession.mShown) {
                Slog.w(TAG, "startVoiceActivity not allowed on hidden session");
                return START_VOICE_HIDDEN_SESSION;
            }
            intent = new Intent(intent);
            intent.addCategory(Intent.CATEGORY_VOICE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            return mAtm.startVoiceActivity(mComponent.getPackageName(), callingPid, callingUid,
                    intent, resolvedType, mActiveSession.mSession, mActiveSession.mInteractor,
                    0, null, null, mUser);
        } catch (RemoteException e) {
            throw new IllegalStateException("Unexpected remote error", e);
        }
    }

    public int startAssistantActivityLocked(int callingPid, int callingUid, IBinder token,
            Intent intent, String resolvedType) {
        try {
            if (mActiveSession == null || token != mActiveSession.mToken) {
                Slog.w(TAG, "startAssistantActivity does not match active session");
                return START_ASSISTANT_NOT_ACTIVE_SESSION;
            }
            if (!mActiveSession.mShown) {
                Slog.w(TAG, "startAssistantActivity not allowed on hidden session");
                return START_ASSISTANT_HIDDEN_SESSION;
            }
            intent = new Intent(intent);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            final ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchActivityType(ACTIVITY_TYPE_ASSISTANT);
            return mAtm.startAssistantActivity(mComponent.getPackageName(), callingPid, callingUid,
                    intent, resolvedType, options.toBundle(), mUser);
        } catch (RemoteException e) {
            throw new IllegalStateException("Unexpected remote error", e);
        }
    }

    public void requestDirectActionsLocked(@NonNull IBinder token, int taskId,
            @NonNull IBinder assistToken,  @Nullable RemoteCallback cancellationCallback,
            @NonNull RemoteCallback callback) {
        if (mActiveSession == null || token != mActiveSession.mToken) {
            Slog.w(TAG, "requestDirectActionsLocked does not match active session");
            callback.sendResult(null);
            return;
        }
        final ActivityTokens tokens = LocalServices.getService(
                ActivityTaskManagerInternal.class).getTopActivityForTask(taskId);
        if (tokens == null || tokens.getAssistToken() != assistToken) {
            Slog.w(TAG, "Unknown activity to query for direct actions");
            callback.sendResult(null);
        } else {
            try {
                tokens.getApplicationThread().requestDirectActions(tokens.getActivityToken(),
                        mActiveSession.mInteractor, cancellationCallback, callback);
            } catch (RemoteException e) {
                Slog.w("Unexpected remote error", e);
                callback.sendResult(null);
            }
        }
    }

    void performDirectActionLocked(@NonNull IBinder token, @NonNull String actionId,
            @Nullable Bundle arguments, int taskId, IBinder assistToken,
            @Nullable RemoteCallback cancellationCallback,
            @NonNull RemoteCallback resultCallback) {
        if (mActiveSession == null || token != mActiveSession.mToken) {
            Slog.w(TAG, "performDirectActionLocked does not match active session");
            resultCallback.sendResult(null);
            return;
        }
        final ActivityTokens tokens = LocalServices.getService(
                ActivityTaskManagerInternal.class).getTopActivityForTask(taskId);
        if (tokens == null || tokens.getAssistToken() != assistToken) {
            Slog.w(TAG, "Unknown activity to perform a direct action");
            resultCallback.sendResult(null);
        } else {
            try {
                tokens.getApplicationThread().performDirectAction(tokens.getActivityToken(),
                        actionId, arguments, cancellationCallback,
                        resultCallback);
            } catch (RemoteException e) {
                Slog.w("Unexpected remote error", e);
                resultCallback.sendResult(null);
            }
        }
    }

    public void setKeepAwakeLocked(IBinder token, boolean keepAwake) {
        try {
            if (mActiveSession == null || token != mActiveSession.mToken) {
                Slog.w(TAG, "setKeepAwake does not match active session");
                return;
            }
            mAtm.setVoiceKeepAwake(mActiveSession.mSession, keepAwake);
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

    public void finishLocked(IBinder token, boolean finishTask) {
        if (mActiveSession == null || (!finishTask && token != mActiveSession.mToken)) {
            Slog.w(TAG, "finish does not match active session");
            return;
        }
        mActiveSession.cancelLocked(finishTask);
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

    public boolean supportsLocalVoiceInteraction() {
        return mInfo.getSupportsLocalInteraction();
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
        pw.print("  mUser="); pw.println(mUser);
        pw.print("  mComponent="); pw.println(mComponent.flattenToShortString());
        pw.print("  Session service="); pw.println(mInfo.getSessionService());
        pw.println("  Service info:");
        mInfo.getServiceInfo().dump(new PrintWriterPrinter(pw), "    ");
        pw.print("  Recognition service="); pw.println(mInfo.getRecognitionService());
        pw.print("  Settings activity="); pw.println(mInfo.getSettingsActivity());
        pw.print("  Supports assist="); pw.println(mInfo.getSupportsAssist());
        pw.print("  Supports launch from keyguard=");
        pw.println(mInfo.getSupportsLaunchFromKeyguard());
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
                Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE
                | Context.BIND_INCLUDE_CAPABILITIES
                | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS, new UserHandle(mUser));
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
            mActiveSession.cancelLocked(false);
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
        synchronized (mServiceStub) {
            finishLocked(connection.mToken, false);
        }
    }

    @Override
    public void onSessionShown(VoiceInteractionSessionConnection connection) {
        mServiceStub.onSessionShown();
    }

    @Override
    public void onSessionHidden(VoiceInteractionSessionConnection connection) {
        mServiceStub.onSessionHidden();
    }
}
