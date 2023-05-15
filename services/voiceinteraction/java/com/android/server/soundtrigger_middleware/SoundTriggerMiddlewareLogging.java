/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.soundtrigger_middleware;

import static com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareLogging.SessionEvent.Type.DETACH;
import static com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareLogging.SessionEvent.Type.FORCE_RECOGNITION;
import static com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareLogging.SessionEvent.Type.GET_MODEL_PARAMETER;
import static com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareLogging.SessionEvent.Type.LOAD_MODEL;
import static com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareLogging.SessionEvent.Type.LOAD_PHRASE_MODEL;
import static com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareLogging.SessionEvent.Type.MODEL_UNLOADED;
import static com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareLogging.SessionEvent.Type.MODULE_DIED;
import static com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareLogging.SessionEvent.Type.QUERY_MODEL_PARAMETER;
import static com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareLogging.SessionEvent.Type.RECOGNITION;
import static com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareLogging.SessionEvent.Type.RESOURCES_AVAILABLE;
import static com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareLogging.SessionEvent.Type.SET_MODEL_PARAMETER;
import static com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareLogging.SessionEvent.Type.START_RECOGNITION;
import static com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareLogging.SessionEvent.Type.STOP_RECOGNITION;
import static com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareLogging.SessionEvent.Type.UNLOAD_MODEL;
import static com.android.server.utils.EventLogger.Event.ALOGI;
import static com.android.server.utils.EventLogger.Event.ALOGW;

import android.annotation.NonNull;
import android.content.Context;
import android.media.permission.Identity;
import android.media.permission.IdentityContext;
import android.media.soundtrigger.ModelParameterRange;
import android.media.soundtrigger.PhraseRecognitionEvent;
import android.media.soundtrigger.PhraseSoundModel;
import android.media.soundtrigger.RecognitionConfig;
import android.media.soundtrigger.RecognitionStatus;
import android.media.soundtrigger.SoundModel;
import android.media.soundtrigger_middleware.ISoundTriggerCallback;
import android.media.soundtrigger_middleware.ISoundTriggerModule;
import android.media.soundtrigger_middleware.PhraseRecognitionEventSys;
import android.media.soundtrigger_middleware.RecognitionEventSys;
import android.media.soundtrigger_middleware.SoundTriggerModuleDescriptor;
import android.os.BatteryStatsInternal;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.LatencyTracker;
import com.android.server.LocalServices;
import com.android.server.utils.EventLogger;
import com.android.server.utils.EventLogger.Event;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Deque;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;


/**
 * An ISoundTriggerMiddlewareService decorator, which adds logging of all API calls (and
 * callbacks).
 *
 * All API methods should follow this structure:
 * <pre><code>
 * @Override
 * public @NonNull ReturnType someMethod(ArgType1 arg1, ArgType2 arg2) throws ExceptionType {
 *     try {
 *         ReturnType result = mDelegate.someMethod(arg1, arg2);
 *         logReturn("someMethod", result, arg1, arg2);
 *         return result;
 *     } catch (Exception e) {
 *         logException("someMethod", e, arg1, arg2);
 *         throw e;
 *     }
 * }
 * </code></pre>
 * The actual handling of these events is then done inside of
 * {@link #logReturnWithObject(Object, Identity, String, Object, Object[])},
 * {@link #logVoidReturnWithObject(Object, Identity, String, Object[])} and {@link
 * #logExceptionWithObject(Object, Identity, String, Exception, Object[])}.
 */
public class SoundTriggerMiddlewareLogging implements ISoundTriggerMiddlewareInternal, Dumpable {
    private static final String TAG = "SoundTriggerMiddlewareLogging";
    private static final int SESSION_MAX_EVENT_SIZE = 128;
    private final @NonNull ISoundTriggerMiddlewareInternal mDelegate;
    private final @NonNull LatencyTracker mLatencyTracker;
    private final @NonNull Supplier<BatteryStatsInternal> mBatteryStatsInternalSupplier;
    private final @NonNull EventLogger mServiceEventLogger = new EventLogger(256,
            "Service Events");

    private final Set<EventLogger> mSessionEventLoggers = ConcurrentHashMap.newKeySet(4);
    private final Deque<EventLogger> mDetachedSessionEventLoggers = new LinkedBlockingDeque<>(4);
    private final AtomicInteger mSessionCount = new AtomicInteger(0);


    public SoundTriggerMiddlewareLogging(@NonNull Context context,
            @NonNull ISoundTriggerMiddlewareInternal delegate) {
        this(LatencyTracker.getInstance(context),
                () -> BatteryStatsHolder.INSTANCE,
                delegate);
    }

    @VisibleForTesting
    public SoundTriggerMiddlewareLogging(@NonNull LatencyTracker latencyTracker,
            @NonNull Supplier<BatteryStatsInternal> batteryStatsInternalSupplier,
            @NonNull ISoundTriggerMiddlewareInternal delegate) {
        mDelegate = delegate;
        mLatencyTracker = latencyTracker;
        mBatteryStatsInternalSupplier = batteryStatsInternalSupplier;
    }

    @Override
    public @NonNull
    SoundTriggerModuleDescriptor[] listModules() {
        try {
            SoundTriggerModuleDescriptor[] result = mDelegate.listModules();
            var moduleSummary = Arrays.stream(result).map((descriptor) ->
                    new ModulePropertySummary(descriptor.handle,
                        descriptor.properties.implementor,
                        descriptor.properties.version)).toArray(ModulePropertySummary[]::new);

            mServiceEventLogger.enqueue(ServiceEvent.createForReturn(
                        ServiceEvent.Type.LIST_MODULE,
                        IdentityContext.get().packageName, moduleSummary).printLog(ALOGI, TAG));
            return result;
        } catch (Exception e) {
            mServiceEventLogger.enqueue(ServiceEvent.createForException(
                        ServiceEvent.Type.LIST_MODULE,
                        IdentityContext.get().packageName, e).printLog(ALOGW, TAG));
            throw e;
        }
    }

    @Override
    public @NonNull
    ISoundTriggerModule attach(int handle, ISoundTriggerCallback callback, boolean isTrusted) {
        try {
            var originatorIdentity = IdentityContext.getNonNull();
            String packageIdentification = originatorIdentity.packageName
                    + mSessionCount.getAndIncrement() + (isTrusted ? "trusted" : "");
            ModuleLogging result = new ModuleLogging();
            var eventLogger = new EventLogger(SESSION_MAX_EVENT_SIZE,
                "Session logger for: " + packageIdentification);

            var callbackWrapper = new CallbackLogging(callback, eventLogger, originatorIdentity);

            result.attach(mDelegate.attach(handle, callbackWrapper, isTrusted), eventLogger);

            mServiceEventLogger.enqueue(ServiceEvent.createForReturn(
                        ServiceEvent.Type.ATTACH,
                        packageIdentification, result, handle, callback, isTrusted)
                    .printLog(ALOGI, TAG));

            mSessionEventLoggers.add(eventLogger);
            return result;
        } catch (Exception e) {
            mServiceEventLogger.enqueue(ServiceEvent.createForException(
                        ServiceEvent.Type.ATTACH,
                        IdentityContext.get().packageName, e, handle, callback)
                    .printLog(ALOGW, TAG));
            throw e;
        }
    }

    // Override toString() in order to have the delegate's ID in it.
    @Override
    public String toString() {
        return mDelegate.toString();
    }

    private class ModuleLogging implements ISoundTriggerModule {
        private ISoundTriggerModule mDelegate;
        private EventLogger mEventLogger;

        void attach(@NonNull ISoundTriggerModule delegate, EventLogger eventLogger) {
            mDelegate = delegate;
            mEventLogger = eventLogger;
        }

        @Override
        public int loadModel(SoundModel model) throws RemoteException {
            try {
                int result = mDelegate.loadModel(model);
                mEventLogger.enqueue(SessionEvent.createForReturn(
                            LOAD_MODEL, result, model.uuid)
                        .printLog(ALOGI, TAG));
                return result;
            } catch (Exception e) {
                mEventLogger.enqueue(SessionEvent.createForReturn(
                            LOAD_MODEL, e, model.uuid)
                        .printLog(ALOGW, TAG));
                throw e;
            }
        }

        @Override
        public int loadPhraseModel(PhraseSoundModel model) throws RemoteException {
            try {
                int result = mDelegate.loadPhraseModel(model);
                mEventLogger.enqueue(SessionEvent.createForReturn(
                            LOAD_PHRASE_MODEL, result, model.common.uuid)
                        .printLog(ALOGI, TAG));
                return result;
            } catch (Exception e) {
                mEventLogger.enqueue(SessionEvent.createForException(
                            LOAD_PHRASE_MODEL, e, model.common.uuid)
                        .printLog(ALOGW, TAG));
                throw e;
            }
        }

        @Override
        public void unloadModel(int modelHandle) throws RemoteException {
            try {
                mDelegate.unloadModel(modelHandle);
                mEventLogger.enqueue(SessionEvent.createForVoid(
                            UNLOAD_MODEL, modelHandle)
                        .printLog(ALOGI, TAG));
            } catch (Exception e) {
                mEventLogger.enqueue(SessionEvent.createForException(
                            UNLOAD_MODEL, e, modelHandle)
                        .printLog(ALOGW, TAG));
                throw e;
            }
        }

        @Override
        public IBinder startRecognition(int modelHandle, RecognitionConfig config)
                throws RemoteException {
            try {
                var result = mDelegate.startRecognition(modelHandle, config);
                mEventLogger.enqueue(SessionEvent.createForReturn(
                            START_RECOGNITION, result, modelHandle, config)
                        .printLog(ALOGI, TAG));
                return result;
            } catch (Exception e) {
                mEventLogger.enqueue(SessionEvent.createForException(
                            START_RECOGNITION, e, modelHandle, config)
                        .printLog(ALOGW, TAG));
                throw e;
            }
        }

        @Override
        public void stopRecognition(int modelHandle) throws RemoteException {
            try {
                mDelegate.stopRecognition(modelHandle);
                mEventLogger.enqueue(SessionEvent.createForVoid(
                            STOP_RECOGNITION, modelHandle)
                        .printLog(ALOGI, TAG));
            } catch (Exception e) {
                mEventLogger.enqueue(SessionEvent.createForException(
                            STOP_RECOGNITION, e, modelHandle)
                        .printLog(ALOGW, TAG));
                throw e;
            }
        }

        @Override
        public void forceRecognitionEvent(int modelHandle) throws RemoteException {
            try {
                mDelegate.forceRecognitionEvent(modelHandle);
                mEventLogger.enqueue(SessionEvent.createForVoid(
                            FORCE_RECOGNITION, modelHandle)
                        .printLog(ALOGI, TAG));
            } catch (Exception e) {
                mEventLogger.enqueue(SessionEvent.createForException(
                            FORCE_RECOGNITION, e, modelHandle)
                        .printLog(ALOGW, TAG));
                throw e;
            }
        }

        @Override
        public void setModelParameter(int modelHandle, int modelParam, int value)
                throws RemoteException {
            try {
                mDelegate.setModelParameter(modelHandle, modelParam, value);
                mEventLogger.enqueue(SessionEvent.createForVoid(
                            SET_MODEL_PARAMETER, modelHandle, modelParam, value)
                        .printLog(ALOGI, TAG));
            } catch (Exception e) {
                mEventLogger.enqueue(SessionEvent.createForException(
                            SET_MODEL_PARAMETER, e, modelHandle, modelParam, value)
                        .printLog(ALOGW, TAG));
                throw e;
            }
        }

        @Override
        public int getModelParameter(int modelHandle, int modelParam) throws RemoteException {
            try {
                int result = mDelegate.getModelParameter(modelHandle, modelParam);
                mEventLogger.enqueue(SessionEvent.createForReturn(
                            GET_MODEL_PARAMETER, result, modelHandle, modelParam)
                        .printLog(ALOGI, TAG));
                return result;
            } catch (Exception e) {
                mEventLogger.enqueue(SessionEvent.createForException(
                            GET_MODEL_PARAMETER, e, modelHandle, modelParam)
                        .printLog(ALOGW, TAG));
                throw e;
            }
        }

        @Override
        public ModelParameterRange queryModelParameterSupport(int modelHandle, int modelParam)
                throws RemoteException {
            try {
                ModelParameterRange result = mDelegate.queryModelParameterSupport(modelHandle,
                        modelParam);
                mEventLogger.enqueue(SessionEvent.createForReturn(
                            QUERY_MODEL_PARAMETER, result, modelHandle, modelParam)
                        .printLog(ALOGI, TAG));
                return result;
            } catch (Exception e) {
                mEventLogger.enqueue(SessionEvent.createForException(
                            QUERY_MODEL_PARAMETER, e, modelHandle, modelParam)
                        .printLog(ALOGW, TAG));
                throw e;
            }
        }

        @Override
        public void detach() throws RemoteException {
            try {
                if (mSessionEventLoggers.remove(mEventLogger)) {
                    while (!mDetachedSessionEventLoggers.offerFirst(mEventLogger)) {
                        // Remove the oldest element, if one still exists
                        mDetachedSessionEventLoggers.pollLast();
                    }
                }
                mDelegate.detach();
                mEventLogger.enqueue(SessionEvent.createForVoid(
                            DETACH)
                        .printLog(ALOGI, TAG));
            } catch (Exception e) {
                mEventLogger.enqueue(SessionEvent.createForException(
                            DETACH, e)
                        .printLog(ALOGW, TAG));
                throw e;
            }
        }

        @Override
        public IBinder asBinder() {
            return mDelegate.asBinder();
        }

        // Override toString() in order to have the delegate's ID in it.
        @Override
        public String toString() {
            return Objects.toString(mDelegate);
        }
    }

    private class CallbackLogging implements ISoundTriggerCallback {
        private final ISoundTriggerCallback mCallbackDelegate;
        private final EventLogger mEventLogger;
        private final Identity mOriginatorIdentity;

        private CallbackLogging(ISoundTriggerCallback delegate,
                EventLogger eventLogger, Identity originatorIdentity) {
            mCallbackDelegate = Objects.requireNonNull(delegate);
            mEventLogger = Objects.requireNonNull(eventLogger);
            mOriginatorIdentity = originatorIdentity;
        }

        @Override
        public void onRecognition(int modelHandle, RecognitionEventSys event, int captureSession)
                throws RemoteException {
            try {
                mBatteryStatsInternalSupplier.get().noteWakingSoundTrigger(
                        SystemClock.elapsedRealtime(), mOriginatorIdentity.uid);
                mCallbackDelegate.onRecognition(modelHandle, event, captureSession);
                mEventLogger.enqueue(SessionEvent.createForVoid(
                            RECOGNITION, modelHandle, event, captureSession)
                        .printLog(ALOGI, TAG));
            } catch (Exception e) {
                mEventLogger.enqueue(SessionEvent.createForException(
                            RECOGNITION, e, modelHandle, event, captureSession)
                        .printLog(ALOGW, TAG));
                throw e;
            }
        }

        @Override
        public void onPhraseRecognition(int modelHandle, PhraseRecognitionEventSys event,
                int captureSession)
                throws RemoteException {
            try {
                mBatteryStatsInternalSupplier.get().noteWakingSoundTrigger(
                        SystemClock.elapsedRealtime(), mOriginatorIdentity.uid);
                startKeyphraseEventLatencyTracking(event.phraseRecognitionEvent);
                mCallbackDelegate.onPhraseRecognition(modelHandle, event, captureSession);
                mEventLogger.enqueue(SessionEvent.createForVoid(
                            RECOGNITION, modelHandle, event, captureSession)
                        .printLog(ALOGI, TAG));
            } catch (Exception e) {
                mEventLogger.enqueue(SessionEvent.createForException(
                            RECOGNITION, e, modelHandle, event, captureSession)
                        .printLog(ALOGW, TAG));
                throw e;
            }
        }

        @Override
        public void onModelUnloaded(int modelHandle) throws RemoteException {
            try {
                mCallbackDelegate.onModelUnloaded(modelHandle);
                mEventLogger.enqueue(SessionEvent.createForVoid(
                            MODEL_UNLOADED, modelHandle)
                        .printLog(ALOGI, TAG));
            } catch (Exception e) {
                mEventLogger.enqueue(SessionEvent.createForException(
                            MODEL_UNLOADED, e, modelHandle)
                        .printLog(ALOGW, TAG));
                throw e;
            }
        }

        @Override
        public void onResourcesAvailable() throws RemoteException {
            try {
                mCallbackDelegate.onResourcesAvailable();
                mEventLogger.enqueue(SessionEvent.createForVoid(
                            RESOURCES_AVAILABLE)
                        .printLog(ALOGI, TAG));
            } catch (Exception e) {
                mEventLogger.enqueue(SessionEvent.createForException(
                            RESOURCES_AVAILABLE, e)
                        .printLog(ALOGW, TAG));
                throw e;
            }
        }

        @Override
        public void onModuleDied() throws RemoteException {
            try {
                mCallbackDelegate.onModuleDied();
                mEventLogger.enqueue(SessionEvent.createForVoid(
                            MODULE_DIED)
                        .printLog(ALOGW, TAG));
            } catch (Exception e) {
                mEventLogger.enqueue(SessionEvent.createForException(
                            MODULE_DIED, e)
                        .printLog(ALOGW, TAG));
                throw e;
            }
        }

        @Override
        public IBinder asBinder() {
            return mCallbackDelegate.asBinder();
        }

        // Override toString() in order to have the delegate's ID in it.
        @Override
        public String toString() {
            return Objects.toString(mCallbackDelegate);
        }
    }

    private static class BatteryStatsHolder {
        private static final BatteryStatsInternal INSTANCE =
                LocalServices.getService(BatteryStatsInternal.class);
    }

    /**
     * Starts the latency tracking log for keyphrase hotword invocation.
     * The measurement covers from when the SoundTrigger HAL emits an event to when the
     * {@link android.service.voice.VoiceInteractionSession} system UI view is shown.
     *
     * <p>The session is only started if the {@link PhraseRecognitionEvent} has a status of
     * {@link RecognitionStatus#SUCCESS}
     */
    private void startKeyphraseEventLatencyTracking(PhraseRecognitionEvent event) {
        if (event.common.status != RecognitionStatus.SUCCESS
                || ArrayUtils.isEmpty(event.phraseExtras)) {
            return;
        }

        String latencyTrackerTag = "KeyphraseId=" + event.phraseExtras[0].id;
        // To avoid adding cancel to all the different failure modes between here and
        // showing the system UI, we defensively cancel once.
        // Either we hit the LatencyTracker timeout of 15 seconds or we defensively cancel
        // here if any error occurs.
        mLatencyTracker.onActionCancel(LatencyTracker.ACTION_SHOW_VOICE_INTERACTION);
        mLatencyTracker.onActionStart(LatencyTracker.ACTION_SHOW_VOICE_INTERACTION,
                latencyTrackerTag);
    }

    private static StringBuilder printArgs(StringBuilder builder, @NonNull Object[] args) {
        for (int i = 0; i < args.length; ++i) {
            if (i > 0) {
                builder.append(", ");
            }
            ObjectPrinter.print(builder, args[i]);
        }
        return builder;
    }

    @Override
    public void dump(PrintWriter pw) {
        // Event loggers
        pw.println("##Service-Wide logs:");
        mServiceEventLogger.dump(pw, /* indent = */ "  ");

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

        if (mDelegate instanceof Dumpable) {
            ((Dumpable) mDelegate).dump(pw);
        }
    }

    public static void printSystemLog(int type, String tag, String message, Exception e) {
        switch (type) {
            case Event.ALOGI:
                Slog.i(tag, message, e);
                break;
            case Event.ALOGE:
                Slog.e(tag, message, e);
                break;
            case Event.ALOGW:
                Slog.w(tag, message, e);
                break;
            case Event.ALOGV:
            default:
                Slog.v(tag, message, e);
        }
    }

    public static class ServiceEvent extends Event {
        private final Type mType;
        private final String mPackageName;
        private final Object mReturnValue;
        private final Object[] mParams;
        private final Exception mException;

        public enum Type {
            ATTACH,
            LIST_MODULE,
        }

        public static ServiceEvent createForException(Type type, String packageName,
                Exception exception, Object... params) {
            return new ServiceEvent(exception, type, packageName, null, params);
        }

        public static ServiceEvent createForReturn(Type type, String packageName,
                Object returnValue, Object... params) {
            return new ServiceEvent(null , type, packageName, returnValue, params);
        }

        private ServiceEvent(Exception exception, Type type, String packageName, Object returnValue,
                Object... params) {
            mException = exception;
            mType = type;
            mPackageName = packageName;
            mReturnValue = returnValue;
            mParams = params;
        }

        @Override
        public Event printLog(int type, String tag) {
            printSystemLog(type, tag, eventToString(), mException);
            return this;
        }

        @Override
        public String eventToString() {
            var sb = new StringBuilder(mType.name()).append(" [client= ");
            ObjectPrinter.print(sb, mPackageName);
            sb.append("] (");
            printArgs(sb, mParams);
            sb.append(") -> ");
            if (mException != null) {
                sb.append("ERROR: ");
                ObjectPrinter.print(sb, mException);
            } else {
                ObjectPrinter.print(sb, mReturnValue);
            }
            return sb.toString();
        }
    }

    public static class SessionEvent extends Event {
        public enum Type {
            LOAD_MODEL,
            LOAD_PHRASE_MODEL,
            START_RECOGNITION,
            STOP_RECOGNITION,
            FORCE_RECOGNITION,
            UNLOAD_MODEL,
            GET_MODEL_PARAMETER,
            SET_MODEL_PARAMETER,
            QUERY_MODEL_PARAMETER,
            DETACH,
            RECOGNITION,
            MODEL_UNLOADED,
            MODULE_DIED,
            RESOURCES_AVAILABLE,
        }

        private final Type mType;
        private final Exception mException;
        private final Object mReturnValue;
        private final Object[] mParams;

        public static SessionEvent createForException(Type type, Exception exception,
                Object... params) {
            return new SessionEvent(exception, type, null, params);
        }

        public static SessionEvent createForReturn(Type type,
                Object returnValue, Object... params) {
            return new SessionEvent(null , type, returnValue, params);
        }

        public static SessionEvent createForVoid(Type type, Object... params) {
            return new SessionEvent(null, type, null, params);
        }


        private SessionEvent(Exception exception, Type type, Object returnValue,
                Object... params) {
            mException = exception;
            mType = type;
            mReturnValue = returnValue;
            mParams = params;
        }

        @Override
        public Event printLog(int type, String tag) {
            printSystemLog(type, tag, eventToString(), mException);
            return this;
        }

        @Override
        public String eventToString() {
            var sb = new StringBuilder(mType.name());
            sb.append(" (");
            printArgs(sb, mParams);
            sb.append(")");
            if (mException != null) {
                sb.append(" -> ERROR: ");
                ObjectPrinter.print(sb, mException);
            } else if (mReturnValue != null) {
                sb.append(" -> ");
                ObjectPrinter.print(sb, mReturnValue);
            }
            return sb.toString();
        }
    }

    private static final class ModulePropertySummary {
        private int mId;
        private String mImplementor;
        private int mVersion;

        ModulePropertySummary(int id, String implementor, int version) {
            mId = id;
            mImplementor = implementor;
            mVersion = version;
        }

        @Override
       public String toString() {
           return "{Id: " + mId + ", Implementor: " + mImplementor
               + ", Version: " + mVersion + "}";
       }
    }
}
