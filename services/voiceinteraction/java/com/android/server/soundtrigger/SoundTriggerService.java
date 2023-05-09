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

package com.android.server.soundtrigger;

import static android.Manifest.permission.BIND_SOUND_TRIGGER_DETECTION_SERVICE;
import static android.Manifest.permission.SOUNDTRIGGER_DELEGATE_IDENTITY;
import static android.content.Context.BIND_AUTO_CREATE;
import static android.content.Context.BIND_FOREGROUND_SERVICE;
import static android.content.Context.BIND_INCLUDE_CAPABILITIES;
import static android.content.pm.PackageManager.GET_META_DATA;
import static android.content.pm.PackageManager.GET_SERVICES;
import static android.content.pm.PackageManager.MATCH_DEBUG_TRIAGED_MISSING;
import static android.hardware.soundtrigger.SoundTrigger.STATUS_BAD_VALUE;
import static android.hardware.soundtrigger.SoundTrigger.STATUS_DEAD_OBJECT;
import static android.hardware.soundtrigger.SoundTrigger.STATUS_ERROR;
import static android.hardware.soundtrigger.SoundTrigger.STATUS_OK;
import static android.provider.Settings.Global.MAX_SOUND_TRIGGER_DETECTION_SERVICE_OPS_PER_DAY;
import static android.provider.Settings.Global.SOUND_TRIGGER_DETECTION_SERVICE_OP_TIMEOUT;

import static com.android.server.soundtrigger.SoundTriggerEvent.SessionEvent.Type;
import static com.android.server.utils.EventLogger.Event.ALOGW;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;
import static com.android.server.soundtrigger.DeviceStateHandler.DeviceStateListener;
import static com.android.server.soundtrigger.DeviceStateHandler.SoundTriggerDeviceState;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.PermissionChecker;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.soundtrigger.ConversionUtil;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.ModelParams;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.GenericSoundModel;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;
import android.hardware.soundtrigger.SoundTrigger.ModelParamRange;
import android.hardware.soundtrigger.SoundTrigger.ModuleProperties;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;
import android.hardware.soundtrigger.SoundTrigger.SoundModel;
import android.hardware.soundtrigger.SoundTriggerModule;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.permission.ClearCallingIdentityContext;
import android.media.permission.Identity;
import android.media.permission.IdentityContext;
import android.media.permission.PermissionUtil;
import android.media.permission.SafeCloseable;
import android.media.soundtrigger.ISoundTriggerDetectionService;
import android.media.soundtrigger.ISoundTriggerDetectionServiceClient;
import android.media.soundtrigger.SoundTriggerDetectionService;
import android.media.soundtrigger_middleware.ISoundTriggerInjection;
import android.media.soundtrigger_middleware.ISoundTriggerMiddlewareService;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.ISoundTriggerService;
import com.android.internal.app.ISoundTriggerSession;
import com.android.internal.util.DumpUtils;
import com.android.server.SoundTriggerInternal;
import com.android.server.SystemService;
import com.android.server.soundtrigger.SoundTriggerEvent.ServiceEvent;
import com.android.server.soundtrigger.SoundTriggerEvent.SessionEvent;
import com.android.server.utils.EventLogger.Event;
import com.android.server.utils.EventLogger;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * A single SystemService to manage all sound/voice-based sound models on the DSP.
 * This services provides apis to manage sound trigger-based sound models via
 * the ISoundTriggerService interface. This class also publishes a local interface encapsulating
 * the functionality provided by {@link SoundTriggerHelper} for use by
 * {@link VoiceInteractionManagerService}.
 *
 * @hide
 */
public class SoundTriggerService extends SystemService {
    private static final String TAG = "SoundTriggerService";
    private static final boolean DEBUG = true;
    private static final int SESSION_MAX_EVENT_SIZE = 128;

    private final Context mContext;
    private final Object mLock = new Object();
    private final SoundTriggerServiceStub mServiceStub;
    private final LocalSoundTriggerService mLocalSoundTriggerService;

    private ISoundTriggerMiddlewareService mMiddlewareService;
    private SoundTriggerDbHelper mDbHelper;

    private final EventLogger mServiceEventLogger = new EventLogger(256, "Service");
    private final EventLogger mDeviceEventLogger = new EventLogger(256, "Device Event");

    private final Set<EventLogger> mSessionEventLoggers = ConcurrentHashMap.newKeySet(4);
    private final Deque<EventLogger> mDetachedSessionEventLoggers = new LinkedBlockingDeque<>(4);
    private AtomicInteger mSessionIdCounter = new AtomicInteger(0);

    class SoundModelStatTracker {
        private class SoundModelStat {
            SoundModelStat() {
                mStartCount = 0;
                mTotalTimeMsec = 0;
                mLastStartTimestampMsec = 0;
                mLastStopTimestampMsec = 0;
                mIsStarted = false;
            }
            long mStartCount; // Number of times that given model started
            long mTotalTimeMsec; // Total time (msec) that given model was running since boot
            long mLastStartTimestampMsec; // SystemClock.elapsedRealtime model was last started
            long mLastStopTimestampMsec; // SystemClock.elapsedRealtime model was last stopped
            boolean mIsStarted; // true if model is currently running
        }
        private final TreeMap<UUID, SoundModelStat> mModelStats;

        SoundModelStatTracker() {
            mModelStats = new TreeMap<UUID, SoundModelStat>();
        }

        public synchronized void onStart(UUID id) {
            SoundModelStat stat = mModelStats.get(id);
            if (stat == null) {
                stat = new SoundModelStat();
                mModelStats.put(id, stat);
            }

            if (stat.mIsStarted) {
                Slog.w(TAG, "error onStart(): Model " + id + " already started");
                return;
            }

            stat.mStartCount++;
            stat.mLastStartTimestampMsec = SystemClock.elapsedRealtime();
            stat.mIsStarted = true;
        }

        public synchronized void onStop(UUID id) {
            SoundModelStat stat = mModelStats.get(id);
            if (stat == null) {
                Slog.i(TAG, "error onStop(): Model " + id + " has no stats available");
                return;
            }

            if (!stat.mIsStarted) {
                Slog.w(TAG, "error onStop(): Model " + id + " already stopped");
                return;
            }

            stat.mLastStopTimestampMsec = SystemClock.elapsedRealtime();
            stat.mTotalTimeMsec += stat.mLastStopTimestampMsec - stat.mLastStartTimestampMsec;
            stat.mIsStarted = false;
        }

        public synchronized void dump(PrintWriter pw) {
            long curTime = SystemClock.elapsedRealtime();
            pw.println("Model Stats:");
            for (Map.Entry<UUID, SoundModelStat> entry : mModelStats.entrySet()) {
                UUID uuid = entry.getKey();
                SoundModelStat stat = entry.getValue();
                long totalTimeMsec = stat.mTotalTimeMsec;
                if (stat.mIsStarted) {
                    totalTimeMsec += curTime - stat.mLastStartTimestampMsec;
                }
                pw.println(uuid + ", total_time(msec)=" + totalTimeMsec
                        + ", total_count=" + stat.mStartCount
                        + ", last_start=" + stat.mLastStartTimestampMsec
                        + ", last_stop=" + stat.mLastStopTimestampMsec);
            }
        }
    }

    private final SoundModelStatTracker mSoundModelStatTracker;
    /** Number of ops run by the {@link RemoteSoundTriggerDetectionService} per package name */
    @GuardedBy("mLock")
    private final ArrayMap<String, NumOps> mNumOpsPerPackage = new ArrayMap<>();

    private final DeviceStateHandler mDeviceStateHandler;
    private final Executor mDeviceStateHandlerExecutor = Executors.newSingleThreadExecutor();
    private PhoneCallStateHandler mPhoneCallStateHandler;

    public SoundTriggerService(Context context) {
        super(context);
        mContext = context;
        mServiceStub = new SoundTriggerServiceStub();
        mLocalSoundTriggerService = new LocalSoundTriggerService(context);
        mSoundModelStatTracker = new SoundModelStatTracker();
        mDeviceStateHandler = new DeviceStateHandler(mDeviceStateHandlerExecutor,
                mDeviceEventLogger);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.SOUND_TRIGGER_SERVICE, mServiceStub);
        publishLocalService(SoundTriggerInternal.class, mLocalSoundTriggerService);
    }

    @Override
    public void onBootPhase(int phase) {
        Slog.d(TAG, "onBootPhase: " + phase + " : " + isSafeMode());
        if (PHASE_THIRD_PARTY_APPS_CAN_START == phase) {
            mDbHelper = new SoundTriggerDbHelper(mContext);
            final PowerManager powerManager = mContext.getSystemService(PowerManager.class);
            // Hook up power state listener
            mContext.registerReceiver(
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            if (!PowerManager.ACTION_POWER_SAVE_MODE_CHANGED
                                    .equals(intent.getAction())) {
                                return;
                            }
                            mDeviceStateHandler.onPowerModeChanged(
                                    powerManager.getSoundTriggerPowerSaveMode());
                        }
                    }, new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
            // Initialize the initial power state
            // Do so after registering the listener so we ensure that we don't drop any events
            mDeviceStateHandler.onPowerModeChanged(powerManager.getSoundTriggerPowerSaveMode());

            // PhoneCallStateHandler initializes the original call state
            mPhoneCallStateHandler = new PhoneCallStateHandler(
                      mContext.getSystemService(SubscriptionManager.class),
                      mContext.getSystemService(TelephonyManager.class),
                      mDeviceStateHandler);
        }
        mMiddlewareService = ISoundTriggerMiddlewareService.Stub.asInterface(
                ServiceManager.waitForService(Context.SOUND_TRIGGER_MIDDLEWARE_SERVICE));

    }

    // Must be called with cleared binder context.
    private List<ModuleProperties> listUnderlyingModuleProperties(
            Identity originatorIdentity) {
        Identity middlemanIdentity = new Identity();
        middlemanIdentity.packageName = ActivityThread.currentOpPackageName();
        try {
            return Arrays.stream(mMiddlewareService.listModulesAsMiddleman(middlemanIdentity,
                                                                originatorIdentity))
                    .map(desc -> ConversionUtil.aidl2apiModuleDescriptor(desc))
                    .collect(Collectors.toList());
        } catch (RemoteException e) {
            throw new ServiceSpecificException(SoundTrigger.STATUS_DEAD_OBJECT);
        }
    }

    private SoundTriggerHelper newSoundTriggerHelper(
            ModuleProperties moduleProperties, EventLogger eventLogger) {
        return newSoundTriggerHelper(moduleProperties, eventLogger, false);
    }
    private SoundTriggerHelper newSoundTriggerHelper(
            ModuleProperties moduleProperties, EventLogger eventLogger, boolean isTrusted) {

        Identity middlemanIdentity = new Identity();
        middlemanIdentity.packageName = ActivityThread.currentOpPackageName();
        Identity originatorIdentity = IdentityContext.getNonNull();

        List<ModuleProperties> moduleList = listUnderlyingModuleProperties(originatorIdentity);

        // Don't fail existing CTS tests which run without a ST module
        final int moduleId = (moduleProperties != null) ?
                moduleProperties.getId() : SoundTriggerHelper.INVALID_MODULE_ID;

        if (moduleId != SoundTriggerHelper.INVALID_MODULE_ID) {
            if (!moduleList.contains(moduleProperties)) {
                throw new IllegalArgumentException("Invalid module properties");
            }
        }

        return new SoundTriggerHelper(
                mContext,
                eventLogger,
                (SoundTrigger.StatusListener statusListener) -> new SoundTriggerModule(
                        mMiddlewareService, moduleId, statusListener,
                        Looper.getMainLooper(), middlemanIdentity, originatorIdentity, isTrusted),
                moduleId,
                () -> listUnderlyingModuleProperties(originatorIdentity)
                );
    }

    // Helper to add session logger to the capacity limited detached list.
    // If we are at capacity, remove the oldest, and retry
    private void detachSessionLogger(EventLogger logger) {
        if (!mSessionEventLoggers.remove(logger)) {
            return;
        }
        // Attempt to push to the top of the queue
        while (!mDetachedSessionEventLoggers.offerFirst(logger)) {
            // Remove the oldest element, if one still exists
            mDetachedSessionEventLoggers.pollLast();
        }
    }

    class SoundTriggerServiceStub extends ISoundTriggerService.Stub {
        @Override
        public ISoundTriggerSession attachAsOriginator(@NonNull Identity originatorIdentity,
                @NonNull ModuleProperties moduleProperties,
                @NonNull IBinder client) {

            int sessionId = mSessionIdCounter.getAndIncrement();
            mServiceEventLogger.enqueue(new ServiceEvent(
                    ServiceEvent.Type.ATTACH, originatorIdentity.packageName + "#" + sessionId));
            try (SafeCloseable ignored = PermissionUtil.establishIdentityDirect(
                    originatorIdentity)) {
                var eventLogger = new EventLogger(SESSION_MAX_EVENT_SIZE,
                        "SoundTriggerSessionLogs for package: "
                        + Objects.requireNonNull(originatorIdentity.packageName)
                        + "#" + sessionId);
                return new SoundTriggerSessionStub(client,
                        newSoundTriggerHelper(moduleProperties, eventLogger), eventLogger);
            }
        }

        @Override
        public ISoundTriggerSession attachAsMiddleman(@NonNull Identity originatorIdentity,
                @NonNull Identity middlemanIdentity,
                @NonNull ModuleProperties moduleProperties,
                @NonNull IBinder client) {

            int sessionId = mSessionIdCounter.getAndIncrement();
            mServiceEventLogger.enqueue(new ServiceEvent(
                    ServiceEvent.Type.ATTACH, originatorIdentity.packageName + "#" + sessionId));
            try (SafeCloseable ignored = PermissionUtil.establishIdentityIndirect(mContext,
                    SOUNDTRIGGER_DELEGATE_IDENTITY, middlemanIdentity,
                    originatorIdentity)) {
                var eventLogger = new EventLogger(SESSION_MAX_EVENT_SIZE,
                        "SoundTriggerSessionLogs for package: "
                        + Objects.requireNonNull(originatorIdentity.packageName) + "#"
                        + sessionId);
                return new SoundTriggerSessionStub(client,
                        newSoundTriggerHelper(moduleProperties, eventLogger), eventLogger);
            }
        }

        @Override
        public List<ModuleProperties> listModuleProperties(@NonNull Identity originatorIdentity) {
            mServiceEventLogger.enqueue(new ServiceEvent(
                    ServiceEvent.Type.LIST_MODULE, originatorIdentity.packageName));
            try (SafeCloseable ignored = PermissionUtil.establishIdentityDirect(
                    originatorIdentity)) {
                return listUnderlyingModuleProperties(originatorIdentity);
            }
        }

        @Override
        public void attachInjection(@NonNull ISoundTriggerInjection injection) {
            if (PermissionChecker.checkCallingPermissionForPreflight(mContext,
                    android.Manifest.permission.MANAGE_SOUND_TRIGGER, null)
                        != PermissionChecker.PERMISSION_GRANTED) {
                throw new SecurityException();
            }
            try {
                ISoundTriggerMiddlewareService.Stub
                        .asInterface(ServiceManager
                                .waitForService(Context.SOUND_TRIGGER_MIDDLEWARE_SERVICE))
                        .attachFakeHalInjection(injection);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        @Override
        public void setInPhoneCallState(boolean isInPhoneCall) {
            Slog.i(TAG, "Overriding phone call state: " + isInPhoneCall);
            mDeviceStateHandler.onPhoneCallStateChanged(isInPhoneCall);
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
            // Event loggers
            pw.println("##Service-Wide logs:");
            mServiceEventLogger.dump(pw, /* indent = */ "  ");
            pw.println("\n##Device state logs:");
            mDeviceStateHandler.dump(pw);
            mDeviceEventLogger.dump(pw, /* indent = */ "  ");

            pw.println("\n##Active Session dumps:\n");
            for (var sessionLogger : mSessionEventLoggers) {
                sessionLogger.dump(pw, /* indent= */ "  ");
                pw.println("");
            }
            pw.println("##Detached Session dumps:\n");
            for (var sessionLogger : mDetachedSessionEventLoggers) {
                sessionLogger.dump(pw, /* indent= */ "  ");
                pw.println("");
            }
            // enrolled models
            pw.println("##Enrolled db dump:\n");
            mDbHelper.dump(pw);

            // stats
            pw.println("\n##Sound Model Stats dump:\n");
            mSoundModelStatTracker.dump(pw);
        }
    }

    class SoundTriggerSessionStub extends ISoundTriggerSession.Stub {
        private final SoundTriggerHelper mSoundTriggerHelper;
        private final DeviceStateListener mListener;
        // Used to detect client death.
        private final IBinder mClient;
        private final Identity mOriginatorIdentity;
        private final TreeMap<UUID, SoundModel> mLoadedModels = new TreeMap<>();
        private final Object mCallbacksLock = new Object();
        private final TreeMap<UUID, IRecognitionStatusCallback> mCallbacks = new TreeMap<>();
        private final EventLogger mEventLogger;

        SoundTriggerSessionStub(@NonNull IBinder client,
                SoundTriggerHelper soundTriggerHelper, EventLogger eventLogger) {
            mSoundTriggerHelper = soundTriggerHelper;
            mClient = client;
            mOriginatorIdentity = IdentityContext.getNonNull();
            mEventLogger = eventLogger;
            mSessionEventLoggers.add(mEventLogger);

            try {
                mClient.linkToDeath(() -> clientDied(), 0);
            } catch (RemoteException e) {
                clientDied();
            }
            mListener = (SoundTriggerDeviceState state)
                    -> mSoundTriggerHelper.onDeviceStateChanged(state);
            mDeviceStateHandler.registerListener(mListener);
        }

        @Override
        public int startRecognition(GenericSoundModel soundModel,
                IRecognitionStatusCallback callback,
                RecognitionConfig config, boolean runInBatterySaverMode) {
            mEventLogger.enqueue(new SessionEvent(Type.START_RECOGNITION, getUuid(soundModel)));

            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);

                if (soundModel == null) {
                    mEventLogger.enqueue(new SessionEvent(Type.START_RECOGNITION,
                                getUuid(soundModel), "Invalid sound model").printLog(ALOGW, TAG));
                    return STATUS_ERROR;
                }

                if (runInBatterySaverMode) {
                    enforceCallingPermission(Manifest.permission.SOUND_TRIGGER_RUN_IN_BATTERY_SAVER);
                }

                int ret = mSoundTriggerHelper.startGenericRecognition(soundModel.getUuid(),
                        soundModel,
                        callback, config, runInBatterySaverMode);
                if (ret == STATUS_OK) {
                    mSoundModelStatTracker.onStart(soundModel.getUuid());
                }
                return ret;
            }
        }

        @Override
        public int stopRecognition(ParcelUuid parcelUuid, IRecognitionStatusCallback callback) {
            mEventLogger.enqueue(new SessionEvent(Type.STOP_RECOGNITION, getUuid(parcelUuid)));
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
                int ret = mSoundTriggerHelper.stopGenericRecognition(parcelUuid.getUuid(),
                        callback);
                if (ret == STATUS_OK) {
                    mSoundModelStatTracker.onStop(parcelUuid.getUuid());
                }
                return ret;
            }
        }

        @Override
        public SoundTrigger.GenericSoundModel getSoundModel(ParcelUuid soundModelId) {
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
                SoundTrigger.GenericSoundModel model = mDbHelper.getGenericSoundModel(
                        soundModelId.getUuid());
                return model;
            }
        }

        @Override
        public void updateSoundModel(SoundTrigger.GenericSoundModel soundModel) {
            mEventLogger.enqueue(new SessionEvent(Type.UPDATE_MODEL, getUuid(soundModel)));
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
                mDbHelper.updateGenericSoundModel(soundModel);
            }
        }

        @Override
        public void deleteSoundModel(ParcelUuid soundModelId) {
            mEventLogger.enqueue(new SessionEvent(Type.DELETE_MODEL, getUuid(soundModelId)));
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
                // Unload the model if it is loaded.
                mSoundTriggerHelper.unloadGenericSoundModel(soundModelId.getUuid());

                // Stop tracking recognition if it is started.
                mSoundModelStatTracker.onStop(soundModelId.getUuid());

                mDbHelper.deleteGenericSoundModel(soundModelId.getUuid());
            }
        }

        @Override
        public int loadGenericSoundModel(GenericSoundModel soundModel) {
            mEventLogger.enqueue(new SessionEvent(Type.LOAD_MODEL, getUuid(soundModel)));
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
                if (soundModel == null || soundModel.getUuid() == null) {
                    mEventLogger.enqueue(new SessionEvent(Type.LOAD_MODEL,
                                getUuid(soundModel), "Invalid sound model").printLog(ALOGW, TAG));
                    return STATUS_ERROR;
                }

                synchronized (mLock) {
                    SoundModel oldModel = mLoadedModels.get(soundModel.getUuid());
                    // If the model we're loading is actually different than what we had loaded, we
                    // should unload that other model now. We don't care about return codes since we
                    // don't know if the other model is loaded.
                    if (oldModel != null && !oldModel.equals(soundModel)) {
                        mSoundTriggerHelper.unloadGenericSoundModel(soundModel.getUuid());
                        synchronized (mCallbacksLock) {
                            mCallbacks.remove(soundModel.getUuid());
                        }
                    }
                    mLoadedModels.put(soundModel.getUuid(), soundModel);
                }
                return STATUS_OK;
            }
        }

        @Override
        public int loadKeyphraseSoundModel(KeyphraseSoundModel soundModel) {
            mEventLogger.enqueue(new SessionEvent(Type.LOAD_MODEL, getUuid(soundModel)));

            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
                if (soundModel == null || soundModel.getUuid() == null) {
                    mEventLogger.enqueue(new SessionEvent(Type.LOAD_MODEL, getUuid(soundModel),
                                "Invalid sound model").printLog(ALOGW, TAG));

                    return STATUS_ERROR;
                }
                if (soundModel.getKeyphrases() == null || soundModel.getKeyphrases().length != 1) {
                    mEventLogger.enqueue(new SessionEvent(Type.LOAD_MODEL, getUuid(soundModel),
                                "Only one keyphrase supported").printLog(ALOGW, TAG));
                    return STATUS_ERROR;
                }


                synchronized (mLock) {
                    SoundModel oldModel = mLoadedModels.get(soundModel.getUuid());
                    // If the model we're loading is actually different than what we had loaded, we
                    // should unload that other model now. We don't care about return codes since we
                    // don't know if the other model is loaded.
                    if (oldModel != null && !oldModel.equals(soundModel)) {
                        mSoundTriggerHelper.unloadKeyphraseSoundModel(
                                soundModel.getKeyphrases()[0].getId());
                        synchronized (mCallbacksLock) {
                            mCallbacks.remove(soundModel.getUuid());
                        }
                    }
                    mLoadedModels.put(soundModel.getUuid(), soundModel);
                }
                return STATUS_OK;
            }
        }

        @Override
        public int startRecognitionForService(ParcelUuid soundModelId, Bundle params,
                ComponentName detectionService, SoundTrigger.RecognitionConfig config) {
            mEventLogger.enqueue(new SessionEvent(Type.START_RECOGNITION_SERVICE,
                        getUuid(soundModelId)));
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                Objects.requireNonNull(soundModelId);
                Objects.requireNonNull(detectionService);
                Objects.requireNonNull(config);

                enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
                enforceDetectionPermissions(detectionService);

                IRecognitionStatusCallback callback =
                        new RemoteSoundTriggerDetectionService(soundModelId.getUuid(), params,
                                detectionService, Binder.getCallingUserHandle(), config);

                synchronized (mLock) {
                    SoundModel soundModel = mLoadedModels.get(soundModelId.getUuid());
                    if (soundModel == null) {
                        mEventLogger.enqueue(new SessionEvent(
                                    Type.START_RECOGNITION_SERVICE,
                                    getUuid(soundModelId),
                                    "Model not loaded").printLog(ALOGW, TAG));

                        return STATUS_ERROR;
                    }
                    IRecognitionStatusCallback existingCallback = null;
                    synchronized (mCallbacksLock) {
                        existingCallback = mCallbacks.get(soundModelId.getUuid());
                    }
                    if (existingCallback != null) {
                        mEventLogger.enqueue(new SessionEvent(
                                    Type.START_RECOGNITION_SERVICE,
                                    getUuid(soundModelId),
                                    "Model already running").printLog(ALOGW, TAG));
                        return STATUS_ERROR;
                    }
                    int ret;
                    switch (soundModel.getType()) {
                        case SoundModel.TYPE_GENERIC_SOUND:
                            ret = mSoundTriggerHelper.startGenericRecognition(soundModel.getUuid(),
                                    (GenericSoundModel) soundModel, callback, config, false);
                            break;
                        default:
                            mEventLogger.enqueue(new SessionEvent(
                                        Type.START_RECOGNITION_SERVICE,
                                        getUuid(soundModelId),
                                        "Unsupported model type").printLog(ALOGW, TAG));
                            return STATUS_ERROR;
                    }

                    if (ret != STATUS_OK) {
                        mEventLogger.enqueue(new SessionEvent(
                                    Type.START_RECOGNITION_SERVICE,
                                    getUuid(soundModelId),
                                    "Model start fail").printLog(ALOGW, TAG));
                        return ret;
                    }
                    synchronized (mCallbacksLock) {
                        mCallbacks.put(soundModelId.getUuid(), callback);
                    }

                    mSoundModelStatTracker.onStart(soundModelId.getUuid());
                }
                return STATUS_OK;
            }
        }

        @Override
        public int stopRecognitionForService(ParcelUuid soundModelId) {
            mEventLogger.enqueue(new SessionEvent(Type.STOP_RECOGNITION_SERVICE,
                        getUuid(soundModelId)));

            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);

                synchronized (mLock) {
                    SoundModel soundModel = mLoadedModels.get(soundModelId.getUuid());
                    if (soundModel == null) {
                        mEventLogger.enqueue(new SessionEvent(
                                    Type.STOP_RECOGNITION_SERVICE,
                                    getUuid(soundModelId),
                                    "Model not loaded")
                                .printLog(ALOGW, TAG));

                        return STATUS_ERROR;
                    }
                    IRecognitionStatusCallback callback = null;
                    synchronized (mCallbacksLock) {
                        callback = mCallbacks.get(soundModelId.getUuid());
                    }
                    if (callback == null) {
                        mEventLogger.enqueue(new SessionEvent(
                                    Type.STOP_RECOGNITION_SERVICE,
                                    getUuid(soundModelId),
                                    "Model not running")
                                .printLog(ALOGW, TAG));
                        return STATUS_ERROR;
                    }
                    int ret;
                    switch (soundModel.getType()) {
                        case SoundModel.TYPE_GENERIC_SOUND:
                            ret = mSoundTriggerHelper.stopGenericRecognition(
                                    soundModel.getUuid(), callback);
                            break;
                        default:
                            mEventLogger.enqueue(new SessionEvent(
                                        Type.STOP_RECOGNITION_SERVICE,
                                        getUuid(soundModelId),
                                        "Unknown model type")
                                    .printLog(ALOGW, TAG));

                            return STATUS_ERROR;
                    }

                    if (ret != STATUS_OK) {
                        mEventLogger.enqueue(new SessionEvent(
                                    Type.STOP_RECOGNITION_SERVICE,
                                    getUuid(soundModelId),
                                    "Failed to stop model")
                                .printLog(ALOGW, TAG));
                        return ret;
                    }
                    synchronized (mCallbacksLock) {
                        mCallbacks.remove(soundModelId.getUuid());
                    }

                    mSoundModelStatTracker.onStop(soundModelId.getUuid());
                }
                return STATUS_OK;
            }
        }

        @Override
        public int unloadSoundModel(ParcelUuid soundModelId) {
            mEventLogger.enqueue(new SessionEvent(Type.UNLOAD_MODEL, getUuid(soundModelId)));
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);

                synchronized (mLock) {
                    SoundModel soundModel = mLoadedModels.get(soundModelId.getUuid());
                    if (soundModel == null) {
                        mEventLogger.enqueue(new SessionEvent(
                                    Type.UNLOAD_MODEL,
                                    getUuid(soundModelId),
                                    "Model not loaded")
                                .printLog(ALOGW, TAG));
                        return STATUS_ERROR;
                    }
                    int ret;
                    switch (soundModel.getType()) {
                        case SoundModel.TYPE_KEYPHRASE:
                            ret = mSoundTriggerHelper.unloadKeyphraseSoundModel(
                                    ((KeyphraseSoundModel) soundModel).getKeyphrases()[0].getId());
                            break;
                        case SoundModel.TYPE_GENERIC_SOUND:
                            ret = mSoundTriggerHelper.unloadGenericSoundModel(soundModel.getUuid());
                            break;
                        default:
                            mEventLogger.enqueue(new SessionEvent(
                                        Type.UNLOAD_MODEL,
                                        getUuid(soundModelId),
                                        "Unknown model type")
                                    .printLog(ALOGW, TAG));
                            return STATUS_ERROR;
                    }
                    if (ret != STATUS_OK) {
                        mEventLogger.enqueue(new SessionEvent(
                                    Type.UNLOAD_MODEL,
                                    getUuid(soundModelId),
                                    "Failed to unload model")
                                .printLog(ALOGW, TAG));
                        return ret;
                    }
                    mLoadedModels.remove(soundModelId.getUuid());
                    return STATUS_OK;
                }
            }
        }

        @Override
        public boolean isRecognitionActive(ParcelUuid parcelUuid) {
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
                synchronized (mCallbacksLock) {
                    IRecognitionStatusCallback callback = mCallbacks.get(parcelUuid.getUuid());
                    if (callback == null) {
                        return false;
                    }
                }
                return mSoundTriggerHelper.isRecognitionRequested(parcelUuid.getUuid());
            }
        }

        @Override
        public int getModelState(ParcelUuid soundModelId) {
            mEventLogger.enqueue(new SessionEvent(Type.GET_MODEL_STATE, getUuid(soundModelId)));
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
                int ret = STATUS_ERROR;

                synchronized (mLock) {
                    SoundModel soundModel = mLoadedModels.get(soundModelId.getUuid());
                    if (soundModel == null) {
                        mEventLogger.enqueue(new SessionEvent(
                                    Type.GET_MODEL_STATE,
                                    getUuid(soundModelId),
                                    "Model is not loaded")
                                .printLog(ALOGW, TAG));
                        return ret;
                    }
                    switch (soundModel.getType()) {
                        case SoundModel.TYPE_GENERIC_SOUND:
                            ret = mSoundTriggerHelper.getGenericModelState(soundModel.getUuid());
                            break;
                        default:
                            // SoundModel.TYPE_KEYPHRASE is not supported to increase privacy.
                            mEventLogger.enqueue(new SessionEvent(
                                        Type.GET_MODEL_STATE,
                                        getUuid(soundModelId),
                                        "Unsupported model type")
                                .printLog(ALOGW, TAG));
                            break;
                    }
                    return ret;
                }
            }
        }

        @Override
        @Nullable
        public ModuleProperties getModuleProperties() {
            mEventLogger.enqueue(new SessionEvent(Type.GET_MODULE_PROPERTIES, null));
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
                synchronized (mLock) {
                    ModuleProperties properties = mSoundTriggerHelper.getModuleProperties();
                    return properties;
                }
            }
        }

        @Override
        public int setParameter(ParcelUuid soundModelId,
                @ModelParams int modelParam, int value) {
            mEventLogger.enqueue(new SessionEvent(Type.SET_PARAMETER, getUuid(soundModelId)));
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
                synchronized (mLock) {
                    SoundModel soundModel = mLoadedModels.get(soundModelId.getUuid());
                    if (soundModel == null) {
                        mEventLogger.enqueue(new SessionEvent(
                                    Type.SET_PARAMETER,
                                    getUuid(soundModelId),
                                    "Model not loaded")
                                .printLog(ALOGW, TAG));
                        return STATUS_BAD_VALUE;
                    }
                    return mSoundTriggerHelper.setParameter(
                            soundModel.getUuid(), modelParam, value);
                }
            }
        }

        @Override
        public int getParameter(@NonNull ParcelUuid soundModelId,
                @ModelParams int modelParam)
                throws UnsupportedOperationException, IllegalArgumentException {
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
                synchronized (mLock) {
                    SoundModel soundModel = mLoadedModels.get(soundModelId.getUuid());
                    if (soundModel == null) {
                        throw new IllegalArgumentException("sound model is not loaded");
                    }
                    return mSoundTriggerHelper.getParameter(soundModel.getUuid(), modelParam);
                }
            }
        }

        @Override
        @Nullable
        public ModelParamRange queryParameter(@NonNull ParcelUuid soundModelId,
                @ModelParams int modelParam) {
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                enforceCallingPermission(Manifest.permission.MANAGE_SOUND_TRIGGER);
                synchronized (mLock) {
                    SoundModel soundModel = mLoadedModels.get(soundModelId.getUuid());
                    if (soundModel == null) {
                        return null;
                    }
                    return mSoundTriggerHelper.queryParameter(soundModel.getUuid(), modelParam);
                }
            }
        }

        private void clientDied() {
            mEventLogger.enqueue(new SessionEvent(Type.DETACH, null));
            mServiceEventLogger.enqueue(new ServiceEvent(
                        ServiceEvent.Type.DETACH, mOriginatorIdentity.packageName, "Client died")
                    .printLog(ALOGW, TAG));
            detach();
        }

        private void detach() {
            mDeviceStateHandler.unregisterListener(mListener);
            mSoundTriggerHelper.detach();
            detachSessionLogger(mEventLogger);
        }

        private void enforceCallingPermission(String permission) {
            if (PermissionUtil.checkPermissionForPreflight(mContext, mOriginatorIdentity,
                    permission) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException(
                        "Identity " + mOriginatorIdentity + " does not have permission "
                                + permission);
            }
        }

        private void enforceDetectionPermissions(ComponentName detectionService) {
            PackageManager packageManager = mContext.getPackageManager();
            String packageName = detectionService.getPackageName();
            if (packageManager.checkPermission(
                        Manifest.permission.CAPTURE_AUDIO_HOTWORD, packageName)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException(detectionService.getPackageName() + " does not have"
                        + " permission " + Manifest.permission.CAPTURE_AUDIO_HOTWORD);
            }
        }

        private UUID getUuid(ParcelUuid uuid) {
            return (uuid != null) ? uuid.getUuid() : null;
        }

        private UUID getUuid(SoundModel model) {
            return (model != null) ? model.getUuid() : null;
        }

        /**
         * Local end for a {@link SoundTriggerDetectionService}. Operations are queued up and
         * executed when the service connects.
         *
         * <p>If operations take too long they are forcefully aborted.
         *
         * <p>This also limits the amount of operations in 24 hours.
         */
        private class RemoteSoundTriggerDetectionService
                extends IRecognitionStatusCallback.Stub implements ServiceConnection {
            private static final int MSG_STOP_ALL_PENDING_OPERATIONS = 1;

            private final Object mRemoteServiceLock = new Object();

            /** UUID of the model the service is started for */
            private final @NonNull
            ParcelUuid mPuuid;
            /** Params passed into the start method for the service */
            private final @Nullable
            Bundle mParams;
            /** Component name passed when starting the service */
            private final @NonNull
            ComponentName mServiceName;
            /** User that started the service */
            private final @NonNull
            UserHandle mUser;
            /** Configuration of the recognition the service is handling */
            private final @NonNull
            RecognitionConfig mRecognitionConfig;
            /** Wake lock keeping the remote service alive */
            private final @NonNull
            PowerManager.WakeLock mRemoteServiceWakeLock;

            private final @NonNull
            Handler mHandler;

            /** Callbacks that are called by the service */
            private final @NonNull
            ISoundTriggerDetectionServiceClient mClient;

            /** Operations that are pending because the service is not yet connected */
            @GuardedBy("mRemoteServiceLock")
            private final ArrayList<Operation> mPendingOps = new ArrayList<>();
            /** Operations that have been send to the service but have no yet finished */
            @GuardedBy("mRemoteServiceLock")
            private final ArraySet<Integer> mRunningOpIds = new ArraySet<>();
            /** The number of operations executed in each of the last 24 hours */
            private final NumOps mNumOps;

            /** The service binder if connected */
            @GuardedBy("mRemoteServiceLock")
            private @Nullable
            ISoundTriggerDetectionService mService;
            /** Whether the service has been bound */
            @GuardedBy("mRemoteServiceLock")
            private boolean mIsBound;
            /** Whether the service has been destroyed */
            @GuardedBy("mRemoteServiceLock")
            private boolean mIsDestroyed;
            /**
             * Set once a final op is scheduled. No further ops can be added and the service is
             * destroyed once the op finishes.
             */
            @GuardedBy("mRemoteServiceLock")
            private boolean mDestroyOnceRunningOpsDone;

            /** Total number of operations performed by this service */
            @GuardedBy("mRemoteServiceLock")
            private int mNumTotalOpsPerformed;

            /**
             * Create a new remote sound trigger detection service. This only binds to the service
             * when operations are in flight. Each operation has a certain time it can run. Once no
             * operations are allowed to run anymore, {@link #stopAllPendingOperations() all
             * operations are aborted and stopped} and the service is disconnected.
             *
             * @param modelUuid   The UUID of the model the recognition is for
             * @param params      The params passed to each method of the service
             * @param serviceName The component name of the service
             * @param user        The user of the service
             * @param config      The configuration of the recognition
             */
            public RemoteSoundTriggerDetectionService(@NonNull UUID modelUuid,
                    @Nullable Bundle params, @NonNull ComponentName serviceName,
                    @NonNull UserHandle user, @NonNull RecognitionConfig config) {
                mPuuid = new ParcelUuid(modelUuid);
                mParams = params;
                mServiceName = serviceName;
                mUser = user;
                mRecognitionConfig = config;
                mHandler = new Handler(Looper.getMainLooper());

                PowerManager pm = ((PowerManager) mContext.getSystemService(Context.POWER_SERVICE));
                mRemoteServiceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "RemoteSoundTriggerDetectionService " + mServiceName.getPackageName() + ":"
                                + mServiceName.getClassName());

                synchronized (mLock) {
                    NumOps numOps = mNumOpsPerPackage.get(mServiceName.getPackageName());
                    if (numOps == null) {
                        numOps = new NumOps();
                        mNumOpsPerPackage.put(mServiceName.getPackageName(), numOps);
                    }
                    mNumOps = numOps;
                }

                mClient = new ISoundTriggerDetectionServiceClient.Stub() {
                    @Override
                    public void onOpFinished(int opId) {
                        final long token = Binder.clearCallingIdentity();
                        try {
                            synchronized (mRemoteServiceLock) {
                                mRunningOpIds.remove(opId);

                                if (mRunningOpIds.isEmpty() && mPendingOps.isEmpty()) {
                                    if (mDestroyOnceRunningOpsDone) {
                                        destroy();
                                    } else {
                                        disconnectLocked();
                                    }
                                }
                            }
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    }
                };
            }

            @Override
            public boolean pingBinder() {
                return !(mIsDestroyed || mDestroyOnceRunningOpsDone);
            }

            /**
             * Disconnect from the service, but allow to re-connect when new operations are
             * triggered.
             */
            @GuardedBy("mRemoteServiceLock")
            private void disconnectLocked() {
                if (mService != null) {
                    try {
                        mService.removeClient(mPuuid);
                    } catch (Exception e) {
                        Slog.e(TAG, mPuuid + ": Cannot remove client", e);

                        mEventLogger.enqueue(new EventLogger.StringEvent(mPuuid
                                + ": Cannot remove client"));

                    }

                    mService = null;
                }

                if (mIsBound) {
                    mContext.unbindService(RemoteSoundTriggerDetectionService.this);
                    mIsBound = false;

                    synchronized (mCallbacksLock) {
                        mRemoteServiceWakeLock.release();
                    }
                }
            }

            /**
             * Disconnect, do not allow to reconnect to the service. All further operations will be
             * dropped.
             */
            private void destroy() {
                mEventLogger.enqueue(new EventLogger.StringEvent(mPuuid + ": destroy"));

                synchronized (mRemoteServiceLock) {
                    disconnectLocked();

                    mIsDestroyed = true;
                }

                // The callback is removed before the flag is set
                if (!mDestroyOnceRunningOpsDone) {
                    synchronized (mCallbacksLock) {
                        mCallbacks.remove(mPuuid.getUuid());
                    }
                }
            }

            /**
             * Stop all pending operations and then disconnect for the service.
             */
            private void stopAllPendingOperations() {
                synchronized (mRemoteServiceLock) {
                    if (mIsDestroyed) {
                        return;
                    }

                    if (mService != null) {
                        int numOps = mRunningOpIds.size();
                        for (int i = 0; i < numOps; i++) {
                            try {
                                mService.onStopOperation(mPuuid, mRunningOpIds.valueAt(i));
                            } catch (Exception e) {
                                Slog.e(TAG, mPuuid + ": Could not stop operation "
                                        + mRunningOpIds.valueAt(i), e);

                                mEventLogger.enqueue(new EventLogger.StringEvent(mPuuid
                                        + ": Could not stop operation " + mRunningOpIds.valueAt(
                                        i)));

                            }
                        }

                        mRunningOpIds.clear();
                    }

                    disconnectLocked();
                }
            }

            /**
             * Verify that the service has the expected properties and then bind to the service
             */
            private void bind() {
                final long token = Binder.clearCallingIdentity();
                try {
                    Intent i = new Intent();
                    i.setComponent(mServiceName);

                    ResolveInfo ri = mContext.getPackageManager().resolveServiceAsUser(i,
                            GET_SERVICES | GET_META_DATA | MATCH_DEBUG_TRIAGED_MISSING,
                            mUser.getIdentifier());

                    if (ri == null) {
                        Slog.w(TAG, mPuuid + ": " + mServiceName + " not found");

                        mEventLogger.enqueue(new EventLogger.StringEvent(mPuuid
                                + ": " + mServiceName + " not found"));

                        return;
                    }

                    if (!BIND_SOUND_TRIGGER_DETECTION_SERVICE
                            .equals(ri.serviceInfo.permission)) {
                        Slog.w(TAG, mPuuid + ": " + mServiceName + " does not require "
                                + BIND_SOUND_TRIGGER_DETECTION_SERVICE);

                        mEventLogger.enqueue(new EventLogger.StringEvent(mPuuid
                                + ": " + mServiceName + " does not require "
                                + BIND_SOUND_TRIGGER_DETECTION_SERVICE));

                        return;
                    }

                    mIsBound = mContext.bindServiceAsUser(i, this,
                            BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE | BIND_INCLUDE_CAPABILITIES,
                            mUser);

                    if (mIsBound) {
                        mRemoteServiceWakeLock.acquire();
                    } else {
                        Slog.w(TAG, mPuuid + ": Could not bind to " + mServiceName);

                        mEventLogger.enqueue(new EventLogger.StringEvent(mPuuid
                                + ": Could not bind to " + mServiceName));

                    }
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }

            /**
             * Run an operation (i.e. send it do the service). If the service is not connected, this
             * binds the service and then runs the operation once connected.
             *
             * @param op The operation to run
             */
            private void runOrAddOperation(Operation op) {
                synchronized (mRemoteServiceLock) {
                    if (mIsDestroyed || mDestroyOnceRunningOpsDone) {
                        Slog.w(TAG,
                                mPuuid + ": Dropped operation as already destroyed or marked for "
                                        + "destruction");

                        mEventLogger.enqueue(new EventLogger.StringEvent(mPuuid
                                + ":Dropped operation as already destroyed or marked for "
                                + "destruction"));

                        op.drop();
                        return;
                    }

                    if (mService == null) {
                        mPendingOps.add(op);

                        if (!mIsBound) {
                            bind();
                        }
                    } else {
                        long currentTime = System.nanoTime();
                        mNumOps.clearOldOps(currentTime);

                        // Drop operation if too many were executed in the last 24 hours.
                        int opsAllowed = Settings.Global.getInt(mContext.getContentResolver(),
                                MAX_SOUND_TRIGGER_DETECTION_SERVICE_OPS_PER_DAY,
                                Integer.MAX_VALUE);

                        // As we currently cannot dropping an op safely, disable throttling
                        int opsAdded = mNumOps.getOpsAdded();
                        if (false && mNumOps.getOpsAdded() >= opsAllowed) {
                            try {
                                if (DEBUG || opsAllowed + 10 > opsAdded) {
                                    Slog.w(TAG,
                                            mPuuid + ": Dropped operation as too many operations "
                                                    + "were run in last 24 hours");

                                    mEventLogger.enqueue(new EventLogger.StringEvent(mPuuid
                                            + ": Dropped operation as too many operations "
                                            + "were run in last 24 hours"));

                                }

                                op.drop();
                            } catch (Exception e) {
                                Slog.e(TAG, mPuuid + ": Could not drop operation", e);

                                mEventLogger.enqueue(new EventLogger.StringEvent(mPuuid
                                        + ": Could not drop operation"));

                            }
                        } else {
                            mNumOps.addOp(currentTime);

                            // Find a free opID
                            int opId = mNumTotalOpsPerformed;
                            do {
                                mNumTotalOpsPerformed++;
                            } while (mRunningOpIds.contains(opId));

                            // Run OP
                            try {
                                if (DEBUG) Slog.v(TAG, mPuuid + ": runOp " + opId);

                                mEventLogger.enqueue(new EventLogger.StringEvent(mPuuid
                                        + ": runOp " + opId));

                                op.run(opId, mService);
                                mRunningOpIds.add(opId);
                            } catch (Exception e) {
                                Slog.e(TAG, mPuuid + ": Could not run operation " + opId, e);

                                mEventLogger.enqueue(new EventLogger.StringEvent(mPuuid
                                        + ": Could not run operation " + opId));

                            }
                        }

                        // Unbind from service if no operations are left (i.e. if the operation
                        // failed)
                        if (mPendingOps.isEmpty() && mRunningOpIds.isEmpty()) {
                            if (mDestroyOnceRunningOpsDone) {
                                destroy();
                            } else {
                                disconnectLocked();
                            }
                        } else {
                            mHandler.removeMessages(MSG_STOP_ALL_PENDING_OPERATIONS);
                            mHandler.sendMessageDelayed(obtainMessage(
                                    RemoteSoundTriggerDetectionService::stopAllPendingOperations,
                                    this)
                                            .setWhat(MSG_STOP_ALL_PENDING_OPERATIONS),
                                    Settings.Global.getLong(mContext.getContentResolver(),
                                            SOUND_TRIGGER_DETECTION_SERVICE_OP_TIMEOUT,
                                            Long.MAX_VALUE));
                        }
                    }
                }
            }

            @Override
            public void onKeyphraseDetected(SoundTrigger.KeyphraseRecognitionEvent event) {
            }

            /**
             * Create an AudioRecord enough for starting and releasing the data buffered for the event.
             *
             * @param event The event that was received
             * @return The initialized AudioRecord
             */
            private @NonNull AudioRecord createAudioRecordForEvent(
                    @NonNull SoundTrigger.GenericRecognitionEvent event)
                    throws IllegalArgumentException, UnsupportedOperationException {
                AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder();
                attributesBuilder.setInternalCapturePreset(MediaRecorder.AudioSource.HOTWORD);
                AudioAttributes attributes = attributesBuilder.build();

                AudioFormat originalFormat = event.getCaptureFormat();

                mEventLogger.enqueue(new EventLogger.StringEvent("createAudioRecordForEvent"));

                return (new AudioRecord.Builder())
                            .setAudioAttributes(attributes)
                            .setAudioFormat((new AudioFormat.Builder())
                                .setChannelMask(originalFormat.getChannelMask())
                                .setEncoding(originalFormat.getEncoding())
                                .setSampleRate(originalFormat.getSampleRate())
                                .build())
                            .setSessionId(event.getCaptureSession())
                            .build();
            }

            @Override
            public void onGenericSoundTriggerDetected(SoundTrigger.GenericRecognitionEvent event) {
                runOrAddOperation(new Operation(
                        // always execute:
                        () -> {
                            if (!mRecognitionConfig.allowMultipleTriggers) {
                                // Unregister this remoteService once op is done
                                synchronized (mCallbacksLock) {
                                    mCallbacks.remove(mPuuid.getUuid());
                                }
                                mDestroyOnceRunningOpsDone = true;
                            }
                        },
                        // execute if not throttled:
                        (opId, service) -> service.onGenericRecognitionEvent(mPuuid, opId, event),
                        // execute if throttled:
                        () -> {
                            if (event.isCaptureAvailable()) {
                                try {
                                    AudioRecord capturedData = createAudioRecordForEvent(event);
                                    capturedData.startRecording();
                                    capturedData.release();
                                } catch (IllegalArgumentException | UnsupportedOperationException e) {
                                    Slog.w(TAG, mPuuid + ": createAudioRecordForEvent(" + event
                                            + "), failed to create AudioRecord");
                                }
                            }
                        }));
            }

            private void onError(int status) {
                if (DEBUG) Slog.v(TAG, mPuuid + ": onError: " + status);

                mEventLogger.enqueue(new EventLogger.StringEvent(mPuuid
                        + ": onError: " + status));

                runOrAddOperation(
                        new Operation(
                                // always execute:
                                () -> {
                                    // Unregister this remoteService once op is done
                                    synchronized (mCallbacksLock) {
                                        mCallbacks.remove(mPuuid.getUuid());
                                    }
                                    mDestroyOnceRunningOpsDone = true;
                                },
                                // execute if not throttled:
                                (opId, service) -> service.onError(mPuuid, opId, status),
                                // nothing to do if throttled
                                null));
            }

            @Override
            public void onPreempted() {
                if (DEBUG) Slog.v(TAG, mPuuid + ": onPreempted");
                onError(STATUS_ERROR);
            }

            @Override
            public void onModuleDied() {
                if (DEBUG) Slog.v(TAG, mPuuid + ": onModuleDied");
                onError(STATUS_DEAD_OBJECT);
            }

            @Override
            public void onResumeFailed(int status) {
                if (DEBUG) Slog.v(TAG, mPuuid + ": onResumeFailed: " + status);
                onError(status);
            }

            @Override
            public void onPauseFailed(int status) {
                if (DEBUG) Slog.v(TAG, mPuuid + ": onPauseFailed: " + status);
                onError(status);
            }

            @Override
            public void onRecognitionPaused() {
            }

            @Override
            public void onRecognitionResumed() {
            }

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (DEBUG) Slog.v(TAG, mPuuid + ": onServiceConnected(" + service + ")");

                mEventLogger.enqueue(new EventLogger.StringEvent(mPuuid
                        + ": onServiceConnected(" + service + ")"));

                synchronized (mRemoteServiceLock) {
                    mService = ISoundTriggerDetectionService.Stub.asInterface(service);

                    try {
                        mService.setClient(mPuuid, mParams, mClient);
                    } catch (Exception e) {
                        Slog.e(TAG, mPuuid + ": Could not init " + mServiceName, e);
                        return;
                    }

                    while (!mPendingOps.isEmpty()) {
                        runOrAddOperation(mPendingOps.remove(0));
                    }
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (DEBUG) Slog.v(TAG, mPuuid + ": onServiceDisconnected");

                mEventLogger.enqueue(new EventLogger.StringEvent(mPuuid
                        + ": onServiceDisconnected"));

                synchronized (mRemoteServiceLock) {
                    mService = null;
                }
            }

            @Override
            public void onBindingDied(ComponentName name) {
                if (DEBUG) Slog.v(TAG, mPuuid + ": onBindingDied");

                mEventLogger.enqueue(new EventLogger.StringEvent(mPuuid
                        + ": onBindingDied"));

                synchronized (mRemoteServiceLock) {
                    destroy();
                }
            }

            @Override
            public void onNullBinding(ComponentName name) {
                Slog.w(TAG, name + " for model " + mPuuid + " returned a null binding");

                mEventLogger.enqueue(new EventLogger.StringEvent(name + " for model "
                        + mPuuid + " returned a null binding"));

                synchronized (mRemoteServiceLock) {
                    disconnectLocked();
                }
            }
        }
    }

    /**
     * Counts the number of operations added in the last 24 hours.
     */
    private static class NumOps {
        private final Object mLock = new Object();

        @GuardedBy("mLock")
        private int[] mNumOps = new int[24];
        @GuardedBy("mLock")
        private long mLastOpsHourSinceBoot;

        /**
         * Clear buckets of new hours that have elapsed since last operation.
         *
         * <p>I.e. when the last operation was triggered at 1:40 and the current operation was
         * triggered at 4:03, the buckets "2, 3, and 4" are cleared.
         *
         * @param currentTime Current elapsed time since boot in ns
         */
        void clearOldOps(long currentTime) {
            synchronized (mLock) {
                long numHoursSinceBoot = TimeUnit.HOURS.convert(currentTime, TimeUnit.NANOSECONDS);

                // Clear buckets of new hours that have elapsed since last operation
                // I.e. when the last operation was triggered at 1:40 and the current
                // operation was triggered at 4:03, the bucket "2, 3, and 4" is cleared
                if (mLastOpsHourSinceBoot != 0) {
                    for (long hour = mLastOpsHourSinceBoot + 1; hour <= numHoursSinceBoot; hour++) {
                        mNumOps[(int) (hour % 24)] = 0;
                    }
                }
            }
        }

        /**
         * Add a new operation.
         *
         * @param currentTime Current elapsed time since boot in ns
         */
        void addOp(long currentTime) {
            synchronized (mLock) {
                long numHoursSinceBoot = TimeUnit.HOURS.convert(currentTime, TimeUnit.NANOSECONDS);

                mNumOps[(int) (numHoursSinceBoot % 24)]++;
                mLastOpsHourSinceBoot = numHoursSinceBoot;
            }
        }

        /**
         * Get the total operations added in the last 24 hours.
         *
         * @return The total number of operations added in the last 24 hours
         */
        int getOpsAdded() {
            synchronized (mLock) {
                int totalOperationsInLastDay = 0;
                for (int i = 0; i < 24; i++) {
                    totalOperationsInLastDay += mNumOps[i];
                }

                return totalOperationsInLastDay;
            }
        }
    }

    /**
     * A single operation run in a {@link RemoteSoundTriggerDetectionService}.
     *
     * <p>Once the remote service is connected either setup + execute or setup + stop is executed.
     */
    private static class Operation {
        private interface ExecuteOp {
            void run(int opId, ISoundTriggerDetectionService service) throws RemoteException;
        }

        private final @Nullable Runnable mSetupOp;
        private final @NonNull ExecuteOp mExecuteOp;
        private final @Nullable Runnable mDropOp;

        private Operation(@Nullable Runnable setupOp, @NonNull ExecuteOp executeOp,
                @Nullable Runnable cancelOp) {
            mSetupOp = setupOp;
            mExecuteOp = executeOp;
            mDropOp = cancelOp;
        }

        private void setup() {
            if (mSetupOp != null) {
                mSetupOp.run();
            }
        }

        void run(int opId, @NonNull ISoundTriggerDetectionService service) throws RemoteException {
            setup();
            mExecuteOp.run(opId, service);
        }

        void drop() {
            setup();

            if (mDropOp != null) {
                mDropOp.run();
            }
        }
    }

    public final class LocalSoundTriggerService implements SoundTriggerInternal {
        private final Context mContext;
        LocalSoundTriggerService(Context context) {
            mContext = context;
        }

        private class SessionImpl implements Session {
            private final @NonNull SoundTriggerHelper mSoundTriggerHelper;
            private final @NonNull IBinder mClient;
            private final EventLogger mEventLogger;
            private final Identity mOriginatorIdentity;
            private final @NonNull DeviceStateListener mListener;

            private final SparseArray<UUID> mModelUuid = new SparseArray<>(1);

            private SessionImpl(@NonNull SoundTriggerHelper soundTriggerHelper,
                    @NonNull IBinder client,
                    @NonNull EventLogger eventLogger, @NonNull Identity originatorIdentity) {

                mSoundTriggerHelper = soundTriggerHelper;
                mClient = client;
                mOriginatorIdentity = originatorIdentity;
                mEventLogger = eventLogger;

                mSessionEventLoggers.add(mEventLogger);
                try {
                    mClient.linkToDeath(() -> clientDied(), 0);
                } catch (RemoteException e) {
                    clientDied();
                }
                mListener = (SoundTriggerDeviceState state)
                        -> mSoundTriggerHelper.onDeviceStateChanged(state);
                mDeviceStateHandler.registerListener(mListener);
            }

            @Override
            public int startRecognition(int keyphraseId, KeyphraseSoundModel soundModel,
                    IRecognitionStatusCallback listener, RecognitionConfig recognitionConfig,
                    boolean runInBatterySaverMode) {
                mModelUuid.put(keyphraseId, soundModel.getUuid());
                mEventLogger.enqueue(new SessionEvent(Type.START_RECOGNITION,
                            soundModel.getUuid()));
                return mSoundTriggerHelper.startKeyphraseRecognition(keyphraseId, soundModel,
                        listener, recognitionConfig, runInBatterySaverMode);
            }

            @Override
            public synchronized int stopRecognition(int keyphraseId,
                    IRecognitionStatusCallback listener) {
                var uuid = mModelUuid.get(keyphraseId);
                mEventLogger.enqueue(new SessionEvent(Type.STOP_RECOGNITION, uuid));
                return mSoundTriggerHelper.stopKeyphraseRecognition(keyphraseId, listener);
            }

            @Override
            public ModuleProperties getModuleProperties() {
                mEventLogger.enqueue(new SessionEvent(Type.GET_MODULE_PROPERTIES, null));
                return mSoundTriggerHelper.getModuleProperties();
            }

            @Override
            public int setParameter(int keyphraseId, @ModelParams int modelParam, int value) {
                var uuid = mModelUuid.get(keyphraseId);
                mEventLogger.enqueue(new SessionEvent(Type.SET_PARAMETER, uuid));
                return mSoundTriggerHelper.setKeyphraseParameter(keyphraseId, modelParam, value);
            }

            @Override
            public int getParameter(int keyphraseId, @ModelParams int modelParam) {
                return mSoundTriggerHelper.getKeyphraseParameter(keyphraseId, modelParam);
            }

            @Override
            @Nullable
            public ModelParamRange queryParameter(int keyphraseId, @ModelParams int modelParam) {
                return mSoundTriggerHelper.queryKeyphraseParameter(keyphraseId, modelParam);
            }

            @Override
            public void detach() {
                detachInternal();
            }

            @Override
            public int unloadKeyphraseModel(int keyphraseId) {
                var uuid = mModelUuid.get(keyphraseId);
                mEventLogger.enqueue(new SessionEvent(Type.UNLOAD_MODEL, uuid));
                return mSoundTriggerHelper.unloadKeyphraseSoundModel(keyphraseId);
            }

            private void clientDied() {
                mServiceEventLogger.enqueue(new ServiceEvent(
                            ServiceEvent.Type.DETACH, mOriginatorIdentity.packageName,
                            "Client died")
                        .printLog(ALOGW, TAG));
                detachInternal();
            }

            private void detachInternal() {
                mEventLogger.enqueue(new SessionEvent(Type.DETACH, null));
                detachSessionLogger(mEventLogger);
                mDeviceStateHandler.unregisterListener(mListener);
                mSoundTriggerHelper.detach();
            }
        }

        @Override
        public Session attach(@NonNull IBinder client, ModuleProperties underlyingModule,
                boolean isTrusted) {
            var identity = IdentityContext.getNonNull();
            int sessionId = mSessionIdCounter.getAndIncrement();
            mServiceEventLogger.enqueue(new ServiceEvent(
                        ServiceEvent.Type.ATTACH, identity.packageName + "#" + sessionId));
            var eventLogger = new EventLogger(SESSION_MAX_EVENT_SIZE,
                    "LocalSoundTriggerEventLogger for package: " +
                    identity.packageName + "#" + sessionId);

            return new SessionImpl(newSoundTriggerHelper(underlyingModule, eventLogger, isTrusted),
                    client, eventLogger, identity);
        }

        @Override
        public List<ModuleProperties> listModuleProperties(Identity originatorIdentity) {
            mServiceEventLogger.enqueue(new ServiceEvent(
                    ServiceEvent.Type.LIST_MODULE, originatorIdentity.packageName));
            try (SafeCloseable ignored = PermissionUtil.establishIdentityDirect(
                    originatorIdentity)) {
                return listUnderlyingModuleProperties(originatorIdentity);
            }
        }
    }
}
