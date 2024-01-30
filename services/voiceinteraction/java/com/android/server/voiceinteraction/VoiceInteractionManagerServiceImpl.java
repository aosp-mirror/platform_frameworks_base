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
import static android.service.voice.VoiceInteractionSession.KEY_SHOW_SESSION_ID;

import static com.android.server.policy.PhoneWindowManager.SYSTEM_DIALOG_REASON_ASSIST;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.AppGlobals;
import android.app.ApplicationExitInfo;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ServiceInfo;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.SoundTrigger;
import android.media.AudioFormat;
import android.media.permission.Identity;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SharedMemory;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.service.voice.HotwordDetector;
import android.service.voice.HotwordTrainingDataLimitEnforcer;
import android.service.voice.IMicrophoneHotwordDetectionVoiceInteractionCallback;
import android.service.voice.IVisualQueryDetectionVoiceInteractionCallback;
import android.service.voice.IVoiceInteractionService;
import android.service.voice.IVoiceInteractionSession;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionServiceInfo;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.view.IWindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IHotwordRecognitionStatusCallback;
import com.android.internal.app.IVisualQueryDetectionAttentionListener;
import com.android.internal.app.IVoiceActionCheckCallback;
import com.android.internal.app.IVoiceInteractionSessionShowCallback;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.wm.ActivityAssistInfo;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal.ActivityTokens;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

class VoiceInteractionManagerServiceImpl implements VoiceInteractionSessionConnection.Callback {
    final static String TAG = "VoiceInteractionServiceManager";
    static final boolean DEBUG = false;

    final static String CLOSE_REASON_VOICE_INTERACTION = "voiceinteraction";

    /** The delay time for retrying to request DirectActions. */
    private static final long REQUEST_DIRECT_ACTIONS_RETRY_TIME_MS = 200;
    private static final boolean SYSPROP_VISUAL_QUERY_SERVICE_ENABLED =
            SystemProperties.getBoolean("ro.hotword.visual_query_service_enabled", false);

    final boolean mValid;

    final Context mContext;
    final Handler mHandler;
    final Handler mDirectActionsHandler;
    final VoiceInteractionManagerService.VoiceInteractionManagerServiceStub mServiceStub;
    final int mUser;
    final ComponentName mComponent;
    final IActivityManager mAm;
    final IActivityTaskManager mAtm;
    final PackageManagerInternal mPackageManagerInternal;
    final VoiceInteractionServiceInfo mInfo;
    final ComponentName mSessionComponentName;
    final IWindowManager mIWindowManager;
    final ComponentName mHotwordDetectionComponentName;
    final ComponentName mVisualQueryDetectionComponentName;
    boolean mBound = false;
    IVoiceInteractionService mService;
    volatile HotwordDetectionConnection mHotwordDetectionConnection;

    VoiceInteractionSessionConnection mActiveSession;
    int mDisabledShowContext;

    final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                String reason = intent.getStringExtra("reason");
                if (!CLOSE_REASON_VOICE_INTERACTION.equals(reason)
                        && !TextUtils.equals("dream", reason)
                        && !SYSTEM_DIALOG_REASON_ASSIST.equals(reason)) {
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
            if (DEBUG) {
                Slog.d(TAG, "onServiceConnected to " + name + " for user(" + mUser + ")");
            }
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
            if (DEBUG) {
                Slog.d(TAG, "onServiceDisconnected to " + name);
            }
            synchronized (mServiceStub) {
                mService = null;
                resetHotwordDetectionConnectionLocked();
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Slog.d(TAG, "onBindingDied to " + name);
            String packageName = name.getPackageName();
            ParceledListSlice<ApplicationExitInfo> plistSlice = null;
            try {
                plistSlice = mAm.getHistoricalProcessExitReasons(packageName, 0, 1, mUser);
            } catch (RemoteException e) {
                // do nothing. The local binder so it can not throw it.
            }
            if (plistSlice == null) {
                return;
            }
            List<ApplicationExitInfo> list = plistSlice.getList();
            if (list.isEmpty()) {
                return;
            }
            // TODO(b/229956310): Refactor the logic of PackageMonitor and onBindingDied
            ApplicationExitInfo info = list.get(0);
            if (info.getReason() == ApplicationExitInfo.REASON_USER_REQUESTED
                    && info.getSubReason() == ApplicationExitInfo.SUBREASON_STOP_APP) {
                // only handle user stopped the application from the task manager
                mServiceStub.handleUserStop(packageName, mUser);
            }
        }
    };

    VoiceInteractionManagerServiceImpl(Context context, Handler handler,
            VoiceInteractionManagerService.VoiceInteractionManagerServiceStub stub,
            int userHandle, ComponentName service) {
        mContext = context;
        mHandler = handler;
        mDirectActionsHandler = new Handler(true);
        mServiceStub = stub;
        mUser = userHandle;
        mComponent = service;
        mAm = ActivityManager.getService();
        mAtm = ActivityTaskManager.getService();
        mPackageManagerInternal = Objects.requireNonNull(
                LocalServices.getService(PackageManagerInternal.class));
        VoiceInteractionServiceInfo info;
        try {
            info = new VoiceInteractionServiceInfo(context.getPackageManager(), service, mUser);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Voice interaction service not found: " + service, e);
            mInfo = null;
            mSessionComponentName = null;
            mHotwordDetectionComponentName = null;
            mVisualQueryDetectionComponentName = null;
            mIWindowManager = null;
            mValid = false;
            return;
        }
        mInfo = info;
        if (mInfo.getParseError() != null) {
            Slog.w(TAG, "Bad voice interaction service: " + mInfo.getParseError());
            mSessionComponentName = null;
            mHotwordDetectionComponentName = null;
            mVisualQueryDetectionComponentName = null;
            mIWindowManager = null;
            mValid = false;
            return;
        }
        mValid = true;
        mSessionComponentName = new ComponentName(service.getPackageName(),
                mInfo.getSessionService());
        final String hotwordDetectionServiceName = mInfo.getHotwordDetectionService();
        mHotwordDetectionComponentName = hotwordDetectionServiceName != null
                ? new ComponentName(service.getPackageName(), hotwordDetectionServiceName) : null;
        final String visualQueryDetectionServiceName = mInfo.getVisualQueryDetectionService();
        mVisualQueryDetectionComponentName = visualQueryDetectionServiceName != null ? new
                ComponentName(service.getPackageName(), visualQueryDetectionServiceName) : null;
        mIWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mContext.registerReceiver(mBroadcastReceiver, filter, null, handler,
                Context.RECEIVER_EXPORTED);
    }

    public void grantImplicitAccessLocked(int grantRecipientUid, @Nullable Intent intent) {
        final int grantRecipientAppId = UserHandle.getAppId(grantRecipientUid);
        final int grantRecipientUserId = UserHandle.getUserId(grantRecipientUid);
        final int voiceInteractionUid = mInfo.getServiceInfo().applicationInfo.uid;
        mPackageManagerInternal.grantImplicitAccess(
                grantRecipientUserId, intent, grantRecipientAppId, voiceInteractionUid,
                /* direct= */ true);
    }

    public boolean showSessionLocked(@Nullable Bundle args, int flags,
            @Nullable String attributionTag,
            @Nullable IVoiceInteractionSessionShowCallback showCallback,
            @Nullable IBinder activityToken) {
        final int sessionId = mServiceStub.getNextShowSessionId();
        final Bundle newArgs = args == null ? new Bundle() : args;
        newArgs.putInt(KEY_SHOW_SESSION_ID, sessionId);

        try {
            if (mService != null) {
                mService.prepareToShowSession(newArgs, flags);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException while calling prepareToShowSession", e);
        }

        if (mActiveSession == null) {
            mActiveSession = new VoiceInteractionSessionConnection(mServiceStub,
                    mSessionComponentName, mUser, mContext, this,
                    mInfo.getServiceInfo().applicationInfo.uid, mHandler);
        }
        if (!mActiveSession.mBound) {
            try {
                if (mService != null) {
                    Bundle failedArgs = new Bundle();
                    failedArgs.putInt(KEY_SHOW_SESSION_ID, sessionId);
                    mService.showSessionFailed(failedArgs);
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "RemoteException while calling showSessionFailed", e);
            }
        }

        List<ActivityAssistInfo> allVisibleActivities =
                LocalServices.getService(ActivityTaskManagerInternal.class)
                        .getTopVisibleActivities();

        List<ActivityAssistInfo> visibleActivities = null;
        if (activityToken != null) {
            visibleActivities = new ArrayList();
            int activitiesCount = allVisibleActivities.size();
            for (int i = 0; i < activitiesCount; i++) {
                ActivityAssistInfo info = allVisibleActivities.get(i);
                if (info.getActivityToken() == activityToken) {
                    visibleActivities.add(info);
                    break;
                }
            }
        } else {
            visibleActivities = allVisibleActivities;
        }
        return mActiveSession.showLocked(newArgs, flags, attributionTag, mDisabledShowContext,
                showCallback, visibleActivities);
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

    public int startVoiceActivityLocked(@Nullable String callingFeatureId, int callingPid,
            int callingUid, IBinder token, Intent intent, String resolvedType) {
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
            return mAtm.startVoiceActivity(mComponent.getPackageName(), callingFeatureId,
                    callingPid, callingUid, intent, resolvedType, mActiveSession.mSession,
                    mActiveSession.mInteractor, 0, null, null, mUser);
        } catch (RemoteException e) {
            throw new IllegalStateException("Unexpected remote error", e);
        }
    }

    public int startAssistantActivityLocked(@Nullable String callingFeatureId, int callingPid,
            int callingUid, IBinder token, Intent intent, String resolvedType,
            @NonNull Bundle bundle) {
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
            // TODO: make the key public hidden
            bundle.putInt("android.activity.activityType", ACTIVITY_TYPE_ASSISTANT);
            return mAtm.startAssistantActivity(mComponent.getPackageName(), callingFeatureId,
                    callingPid, callingUid, intent, resolvedType, bundle, mUser);
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
        final ActivityTokens tokens = LocalServices.getService(ActivityTaskManagerInternal.class)
                .getAttachedNonFinishingActivityForTask(taskId, null);
        if (tokens == null || tokens.getAssistToken() != assistToken) {
            Slog.w(TAG, "Unknown activity to query for direct actions");
            mDirectActionsHandler.sendMessageDelayed(PooledLambda.obtainMessage(
                    VoiceInteractionManagerServiceImpl::retryRequestDirectActions,
                    VoiceInteractionManagerServiceImpl.this, token, taskId, assistToken,
                    cancellationCallback, callback), REQUEST_DIRECT_ACTIONS_RETRY_TIME_MS);
        } else {
            grantImplicitAccessLocked(tokens.getUid(), /* intent= */ null);
            try {
                tokens.getApplicationThread().requestDirectActions(tokens.getActivityToken(),
                        mActiveSession.mInteractor, cancellationCallback, callback);
            } catch (RemoteException e) {
                Slog.w("Unexpected remote error", e);
                callback.sendResult(null);
            }
        }
    }

    private void retryRequestDirectActions(@NonNull IBinder token, int taskId,
            @NonNull IBinder assistToken,  @Nullable RemoteCallback cancellationCallback,
            @NonNull RemoteCallback callback) {
        synchronized (mServiceStub) {
            if (mActiveSession == null || token != mActiveSession.mToken) {
                Slog.w(TAG, "retryRequestDirectActions does not match active session");
                callback.sendResult(null);
                return;
            }
            final ActivityTokens tokens = LocalServices.getService(
                            ActivityTaskManagerInternal.class)
                    .getAttachedNonFinishingActivityForTask(taskId, null);
            if (tokens == null || tokens.getAssistToken() != assistToken) {
                Slog.w(TAG, "Unknown activity to query for direct actions during retrying");
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
        final ActivityTokens tokens = LocalServices.getService(ActivityTaskManagerInternal.class)
                .getAttachedNonFinishingActivityForTask(taskId, null);
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

    public ApplicationInfo getApplicationInfo() {
        return mInfo.getServiceInfo().applicationInfo;
    }

    public void startListeningVisibleActivityChangedLocked(@NonNull IBinder token) {
        if (DEBUG) {
            Slog.d(TAG, "startListeningVisibleActivityChangedLocked: token=" + token);
        }
        if (mActiveSession == null || token != mActiveSession.mToken) {
            Slog.w(TAG, "startListeningVisibleActivityChangedLocked does not match"
                    + " active session");
            return;
        }
        mActiveSession.startListeningVisibleActivityChangedLocked();
    }

    public void stopListeningVisibleActivityChangedLocked(@NonNull IBinder token) {
        if (DEBUG) {
            Slog.d(TAG, "stopListeningVisibleActivityChangedLocked: token=" + token);
        }
        if (mActiveSession == null || token != mActiveSession.mToken) {
            Slog.w(TAG, "stopListeningVisibleActivityChangedLocked does not match"
                    + " active session");
            return;
        }
        mActiveSession.stopListeningVisibleActivityChangedLocked();
    }

    public void notifyActivityDestroyedLocked(@NonNull IBinder activityToken) {
        if (DEBUG) {
            Slog.d(TAG, "notifyActivityDestroyedLocked activityToken=" + activityToken);
        }
        if (mActiveSession == null || !mActiveSession.mShown) {
            if (DEBUG) {
                Slog.d(TAG, "notifyActivityDestroyedLocked not allowed on no session or"
                        + " hidden session");
            }
            return;
        }
        mActiveSession.notifyActivityDestroyedLocked(activityToken);
    }

    public void notifyActivityEventChangedLocked(@NonNull IBinder activityToken, int type) {
        if (DEBUG) {
            Slog.d(TAG, "notifyActivityEventChangedLocked type=" + type);
        }
        if (mActiveSession == null || !mActiveSession.mShown) {
            if (DEBUG) {
                Slog.d(TAG, "notifyActivityEventChangedLocked not allowed on no session or"
                        + " hidden session");
            }
            return;
        }
        mActiveSession.notifyActivityEventChangedLocked(activityToken, type);
    }

    public void updateStateLocked(
            @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory,
            @NonNull IBinder token) {
        Slog.v(TAG, "updateStateLocked");

        if (sharedMemory != null && !sharedMemory.setProtect(OsConstants.PROT_READ)) {
            Slog.w(TAG, "Can't set sharedMemory to be read-only");
            throw new IllegalStateException("Can't set sharedMemory to be read-only");
        }

        if (mHotwordDetectionConnection == null) {
            Slog.w(TAG, "update State, but no hotword detection connection");
            throw new IllegalStateException("Hotword detection connection not found");
        }
        synchronized (mHotwordDetectionConnection.mLock) {
            mHotwordDetectionConnection.updateStateLocked(options, sharedMemory, token);
        }
    }

    private void verifyDetectorForHotwordDetectionLocked(
            @Nullable SharedMemory sharedMemory,
            IHotwordRecognitionStatusCallback callback,
            int detectorType) {
        Slog.v(TAG, "verifyDetectorForHotwordDetectionLocked");
        int voiceInteractionServiceUid = mInfo.getServiceInfo().applicationInfo.uid;
        if (mHotwordDetectionComponentName == null) {
            Slog.w(TAG, "Hotword detection service name not found");
            logDetectorCreateEventIfNeeded(callback, detectorType, false,
                    voiceInteractionServiceUid);
            throw new IllegalStateException("Hotword detection service name not found");
        }
        ServiceInfo hotwordDetectionServiceInfo = getServiceInfoLocked(
                mHotwordDetectionComponentName, mUser);
        if (hotwordDetectionServiceInfo == null) {
            Slog.w(TAG, "Hotword detection service info not found");
            logDetectorCreateEventIfNeeded(callback, detectorType, false,
                    voiceInteractionServiceUid);
            throw new IllegalStateException("Hotword detection service info not found");
        }
        if (!isIsolatedProcessLocked(hotwordDetectionServiceInfo)) {
            Slog.w(TAG, "Hotword detection service not in isolated process");
            logDetectorCreateEventIfNeeded(callback, detectorType, false,
                    voiceInteractionServiceUid);
            throw new IllegalStateException("Hotword detection service not in isolated process");
        }
        if (!Manifest.permission.BIND_HOTWORD_DETECTION_SERVICE.equals(
                hotwordDetectionServiceInfo.permission)) {
            Slog.w(TAG, "Hotword detection service does not require permission "
                    + Manifest.permission.BIND_HOTWORD_DETECTION_SERVICE);
            logDetectorCreateEventIfNeeded(callback, detectorType, false,
                    voiceInteractionServiceUid);
            throw new SecurityException("Hotword detection service does not require permission "
                    + Manifest.permission.BIND_HOTWORD_DETECTION_SERVICE);
        }
        if (mContext.getPackageManager().checkPermission(
                Manifest.permission.BIND_HOTWORD_DETECTION_SERVICE,
                mInfo.getServiceInfo().packageName) == PackageManager.PERMISSION_GRANTED) {
            Slog.w(TAG, "Voice interaction service should not hold permission "
                    + Manifest.permission.BIND_HOTWORD_DETECTION_SERVICE);
            logDetectorCreateEventIfNeeded(callback, detectorType, false,
                    voiceInteractionServiceUid);
            throw new SecurityException("Voice interaction service should not hold permission "
                    + Manifest.permission.BIND_HOTWORD_DETECTION_SERVICE);
        }

        if (sharedMemory != null && !sharedMemory.setProtect(OsConstants.PROT_READ)) {
            Slog.w(TAG, "Can't set sharedMemory to be read-only");
            logDetectorCreateEventIfNeeded(callback, detectorType, false,
                    voiceInteractionServiceUid);
            throw new IllegalStateException("Can't set sharedMemory to be read-only");
        }

        logDetectorCreateEventIfNeeded(callback, detectorType, true,
                voiceInteractionServiceUid);
    }

    private void verifyDetectorForVisualQueryDetectionLocked(@Nullable SharedMemory sharedMemory) {
        Slog.v(TAG, "verifyDetectorForVisualQueryDetectionLocked");

        if (mVisualQueryDetectionComponentName == null) {
            Slog.w(TAG, "Visual query detection service name not found");
            throw new IllegalStateException("Visual query detection service name not found");
        }
        ServiceInfo visualQueryDetectionServiceInfo = getServiceInfoLocked(
                mVisualQueryDetectionComponentName, mUser);
        if (visualQueryDetectionServiceInfo == null) {
            Slog.w(TAG, "Visual query detection service info not found");
            throw new IllegalStateException("Visual query detection service name not found");
        }
        if (!isIsolatedProcessLocked(visualQueryDetectionServiceInfo)) {
            Slog.w(TAG, "Visual query detection service not in isolated process");
            throw new IllegalStateException("Visual query detection not in isolated process");
        }
        if (!Manifest.permission.BIND_VISUAL_QUERY_DETECTION_SERVICE.equals(
                visualQueryDetectionServiceInfo.permission)) {
            Slog.w(TAG, "Visual query detection does not require permission "
                    + Manifest.permission.BIND_VISUAL_QUERY_DETECTION_SERVICE);
            throw new SecurityException("Visual query detection does not require permission "
                    + Manifest.permission.BIND_VISUAL_QUERY_DETECTION_SERVICE);
        }
        if (mContext.getPackageManager().checkPermission(
                Manifest.permission.BIND_VISUAL_QUERY_DETECTION_SERVICE,
                mInfo.getServiceInfo().packageName) == PackageManager.PERMISSION_GRANTED) {
            Slog.w(TAG, "Voice interaction service should not hold permission "
                    + Manifest.permission.BIND_VISUAL_QUERY_DETECTION_SERVICE);
            throw new SecurityException("Voice interaction service should not hold permission "
                    + Manifest.permission.BIND_VISUAL_QUERY_DETECTION_SERVICE);
        }
        if (sharedMemory != null && !sharedMemory.setProtect(OsConstants.PROT_READ)) {
            Slog.w(TAG, "Can't set sharedMemory to be read-only");
            throw new IllegalStateException("Can't set sharedMemory to be read-only");
        }
    }

    public void initAndVerifyDetectorLocked(
            @NonNull Identity voiceInteractorIdentity,
            @Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory,
            @NonNull IBinder token,
            IHotwordRecognitionStatusCallback callback,
            int detectorType) {

        if (detectorType != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
            verifyDetectorForHotwordDetectionLocked(sharedMemory, callback, detectorType);
        } else {
            verifyDetectorForVisualQueryDetectionLocked(sharedMemory);
        }
        if (SYSPROP_VISUAL_QUERY_SERVICE_ENABLED && !verifyProcessSharingLocked()) {
            Slog.w(TAG, "Sandboxed detection service not in shared isolated process");
            throw new IllegalStateException("VisualQueryDetectionService or HotworDetectionService "
                    + "not in a shared isolated process. Please make sure to set "
                    + "android:allowSharedIsolatedProcess and android:isolatedProcess to be true "
                    + "and android:externalService to be false in the manifest file");
        }

        if (mHotwordDetectionConnection == null) {
            mHotwordDetectionConnection = new HotwordDetectionConnection(mServiceStub, mContext,
                    mInfo.getServiceInfo().applicationInfo.uid, voiceInteractorIdentity,
                    mHotwordDetectionComponentName, mVisualQueryDetectionComponentName, mUser,
                    /* bindInstantServiceAllowed= */ false, detectorType,
                    (token1, detectorType1) -> {
                        try {
                            mService.detectorRemoteExceptionOccurred(token1, detectorType1);
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Fail to notify client detector remote "
                                    + "exception occurred.");
                        }
                    });
        } else if (detectorType != HotwordDetector.DETECTOR_TYPE_VISUAL_QUERY_DETECTOR) {
            // TODO: Logger events should be handled in session instead. Temporary adding the
            //  checking to prevent confusion so VisualQueryDetection events won't be logged if the
            //  connection is instantiated by the VisualQueryDetector.
            mHotwordDetectionConnection.setDetectorType(detectorType);
        }
        mHotwordDetectionConnection.createDetectorLocked(options, sharedMemory, token, callback,
                detectorType);
    }

    public void destroyDetectorLocked(IBinder token) {
        Slog.v(TAG, "destroyDetectorLocked");

        if (mHotwordDetectionConnection == null) {
            Slog.w(TAG, "destroy detector callback, but no hotword detection connection");
            return;
        }
        mHotwordDetectionConnection.destroyDetectorLocked(token);
    }

    private void logDetectorCreateEventIfNeeded(IHotwordRecognitionStatusCallback callback,
            int detectorType, boolean isCreated, int voiceInteractionServiceUid) {
        if (callback != null) {
            HotwordMetricsLogger.writeDetectorCreateEvent(detectorType, isCreated,
                    voiceInteractionServiceUid);
        }
    }

    public void shutdownHotwordDetectionServiceLocked() {
        if (DEBUG) {
            Slog.d(TAG, "shutdownHotwordDetectionServiceLocked");
        }
        if (mHotwordDetectionConnection == null) {
            Slog.w(TAG, "shutdown, but no hotword detection connection");
            return;
        }
        mHotwordDetectionConnection.cancelLocked();
        mHotwordDetectionConnection = null;
    }

    public void setVisualQueryDetectionAttentionListenerLocked(
            @Nullable IVisualQueryDetectionAttentionListener listener) {
        if (mHotwordDetectionConnection == null) {
            return;
        }
        mHotwordDetectionConnection.setVisualQueryDetectionAttentionListenerLocked(listener);
    }

    public boolean startPerceivingLocked(IVisualQueryDetectionVoiceInteractionCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "startPerceivingLocked");
        }

        if (mHotwordDetectionConnection == null) {
            // TODO: callback.onError();
            return false;
        }

        return mHotwordDetectionConnection.startPerceivingLocked(callback);
    }

    public boolean stopPerceivingLocked() {
        if (DEBUG) {
            Slog.d(TAG, "stopPerceivingLocked");
        }

        if (mHotwordDetectionConnection == null) {
            Slog.w(TAG, "stopPerceivingLocked() called but connection isn't established");
            return false;
        }

        return mHotwordDetectionConnection.stopPerceivingLocked();
    }

    public void startListeningFromMicLocked(
            AudioFormat audioFormat,
            IMicrophoneHotwordDetectionVoiceInteractionCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "startListeningFromMicLocked");
        }

        if (mHotwordDetectionConnection == null) {
            // TODO: callback.onError();
            return;
        }

        mHotwordDetectionConnection.startListeningFromMicLocked(audioFormat, callback);
    }

    public void startListeningFromExternalSourceLocked(
            ParcelFileDescriptor audioStream,
            AudioFormat audioFormat,
            @Nullable PersistableBundle options,
            @NonNull IBinder token,
            IMicrophoneHotwordDetectionVoiceInteractionCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "startListeningFromExternalSourceLocked");
        }

        if (mHotwordDetectionConnection == null) {
            // TODO: callback.onError();
            return;
        }

        if (audioStream == null) {
            Slog.w(TAG, "External source is null for hotword detector");
            throw new IllegalStateException("External source is null for hotword detector");
        }

        mHotwordDetectionConnection.startListeningFromExternalSourceLocked(audioStream, audioFormat,
                options, token, callback);
    }

    public void stopListeningFromMicLocked() {
        if (DEBUG) {
            Slog.d(TAG, "stopListeningFromMicLocked");
        }

        if (mHotwordDetectionConnection == null) {
            Slog.w(TAG, "stopListeningFromMicLocked() called but connection isn't established");
            return;
        }

        mHotwordDetectionConnection.stopListeningFromMicLocked();
    }

    public void triggerHardwareRecognitionEventForTestLocked(
            SoundTrigger.KeyphraseRecognitionEvent event,
            IHotwordRecognitionStatusCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "triggerHardwareRecognitionEventForTestLocked");
        }
        if (mHotwordDetectionConnection == null) {
            Slog.w(TAG, "triggerHardwareRecognitionEventForTestLocked() called but connection"
                    + " isn't established");
            return;
        }
        mHotwordDetectionConnection.triggerHardwareRecognitionEventForTestLocked(event, callback);
    }

    public IRecognitionStatusCallback createSoundTriggerCallbackLocked(
            Context context, IHotwordRecognitionStatusCallback callback,
            Identity voiceInteractorIdentity) {
        if (DEBUG) {
            Slog.d(TAG, "createSoundTriggerCallbackLocked");
        }
        return new HotwordDetectionConnection.SoundTriggerCallback(context, callback,
                mHotwordDetectionConnection, voiceInteractorIdentity);
    }

    private static ServiceInfo getServiceInfoLocked(@NonNull ComponentName componentName,
            int userHandle) {
        try {
            return AppGlobals.getPackageManager().getServiceInfo(componentName,
                    PackageManager.GET_META_DATA
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userHandle);
        } catch (RemoteException e) {
            if (DEBUG) {
                Slog.w(TAG, "getServiceInfoLocked RemoteException : " + e);
            }
        }
        return null;
    }

    boolean isIsolatedProcessLocked(@NonNull ServiceInfo serviceInfo) {
        return (serviceInfo.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0
                && (serviceInfo.flags & ServiceInfo.FLAG_EXTERNAL_SERVICE) == 0;
    }

    boolean verifyProcessSharingLocked() {
        // only check this if both VQDS and HDS are declared in the app
        ServiceInfo hotwordInfo = getServiceInfoLocked(mHotwordDetectionComponentName, mUser);
        ServiceInfo visualQueryInfo =
                getServiceInfoLocked(mVisualQueryDetectionComponentName, mUser);
        if (hotwordInfo == null || visualQueryInfo == null) {
            return true;
        }
        // Enforce shared isolated option is used when VisualQueryDetectionservice is enabled
        return (hotwordInfo.flags & ServiceInfo.FLAG_ALLOW_SHARED_ISOLATED_PROCESS) != 0
                && (visualQueryInfo.flags & ServiceInfo.FLAG_ALLOW_SHARED_ISOLATED_PROCESS) != 0;
    }


    void forceRestartHotwordDetector() {
        if (mHotwordDetectionConnection == null) {
            Slog.w(TAG, "Failed to force-restart hotword detection: no hotword detection active");
            return;
        }
        mHotwordDetectionConnection.forceRestart();
    }

    void setDebugHotwordLoggingLocked(boolean logging) {
        if (mHotwordDetectionConnection == null) {
            Slog.w(TAG, "Failed to set temporary debug logging: no hotword detection active");
            return;
        }
        mHotwordDetectionConnection.setDebugHotwordLoggingLocked(logging);
    }

    void resetHotwordDetectionConnectionLocked() {
        if (DEBUG) {
            Slog.d(TAG, "resetHotwordDetectionConnectionLocked");
        }
        if (mHotwordDetectionConnection == null) {
            if (DEBUG) {
                Slog.w(TAG, "reset, but no hotword detection connection");
            }
            return;
        }
        mHotwordDetectionConnection.cancelLocked();
        mHotwordDetectionConnection = null;
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
        pw.print("  Hotword detection service="); pw.println(mInfo.getHotwordDetectionService());
        pw.print("  Settings activity="); pw.println(mInfo.getSettingsActivity());
        pw.print("  Supports assist="); pw.println(mInfo.getSupportsAssist());
        pw.print("  Supports launch from keyguard=");
        pw.println(mInfo.getSupportsLaunchFromKeyguard());
        if (mDisabledShowContext != 0) {
            pw.print("  mDisabledShowContext=");
            pw.println(Integer.toHexString(mDisabledShowContext));
        }
        pw.print("  mBound="); pw.print(mBound);  pw.print(" mService="); pw.println(mService);
        if (mHotwordDetectionConnection != null) {
            pw.println("  Hotword detection connection:");
            mHotwordDetectionConnection.dump("    ", pw);
        } else {
            pw.println("  No Hotword detection connection");
        }
        if (mActiveSession != null) {
            pw.println("  Active session:");
            mActiveSession.dump("    ", pw);
        }
    }

    @VisibleForTesting
    void resetHotwordTrainingDataEgressCountForTest() {
        HotwordTrainingDataLimitEnforcer.getInstance(mContext.getApplicationContext())
                        .resetTrainingDataEgressCount();
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
        if (mHotwordDetectionConnection != null) {
            mHotwordDetectionConnection.cancelLocked();
            mHotwordDetectionConnection = null;
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
        // Notifies visibility change here can cause duplicate events, it is added to make sure
        // client always get the callback even if session is unexpectedly closed.
        mServiceStub.setSessionWindowVisible(connection.mToken, false);
    }

    interface DetectorRemoteExceptionListener {
        void onDetectorRemoteException(@NonNull IBinder token, int detectorType);
    }
}
