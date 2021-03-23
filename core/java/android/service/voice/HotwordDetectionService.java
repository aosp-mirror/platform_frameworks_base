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

package android.service.voice;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.util.Log;

import java.util.Locale;

/**
 * Implemented by an application that wants to offer detection for hotword. The system will
 * start the service after calling {@link VoiceInteractionService#setHotwordDetectionConfig}.
 *
 * @hide
 */
@SystemApi
public abstract class HotwordDetectionService extends Service {
    private static final String TAG = "HotwordDetectionService";
    // TODO (b/177502877): Set the Debug flag to false before shipping.
    private static final boolean DBG = true;

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_HOTWORD_DETECTION_SERVICE} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.voice.HotwordDetectionService";

    private Handler mHandler;

    private final IHotwordDetectionService mInterface = new IHotwordDetectionService.Stub() {
        @Override
        public void detectFromDspSource(
                ParcelFileDescriptor audioStream,
                AudioFormat audioFormat,
                long timeoutMillis,
                IDspHotwordDetectionCallback callback)
                throws RemoteException {
            if (DBG) {
                Log.d(TAG, "#detectFromDspSource");
            }
            mHandler.sendMessage(obtainMessage(HotwordDetectionService::onDetectFromDspSource,
                    HotwordDetectionService.this,
                    audioStream,
                    audioFormat,
                    timeoutMillis,
                    new DspHotwordDetectionCallback(callback)));
        }

        @Override
        public void setConfig(PersistableBundle options, SharedMemory sharedMemory)
                throws RemoteException {
            if (DBG) {
                Log.d(TAG, "#setConfig");
            }
            mHandler.sendMessage(obtainMessage(HotwordDetectionService::onUpdateState,
                    HotwordDetectionService.this,
                    options,
                    sharedMemory));
        }
    };

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = Handler.createAsync(Looper.getMainLooper());
    }

    @Override
    @Nullable
    public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Log.w(TAG, "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": "
                + intent);
        return null;
    }

    /**
     * Detect the audio data generated from Dsp.
     *
     * <p>Note: the clients are supposed to call {@code close} on the input stream when they are
     * done with the operation in order to free up resources.
     *
     * @param audioStream Stream containing audio bytes returned from DSP
     * @param audioFormat Format of the supplied audio
     * @param timeoutMillis Timeout in milliseconds for the operation to invoke the callback. If
     *                      the application fails to abide by the timeout, system will close the
     *                      microphone and cancel the operation.
     * @param callback Use {@link HotwordDetectionService#DspHotwordDetectionCallback} to return
     * the detected result.
     *
     * @hide
     */
    @SystemApi
    public void onDetectFromDspSource(
            @NonNull ParcelFileDescriptor audioStream,
            @NonNull AudioFormat audioFormat,
            @DurationMillisLong long timeoutMillis,
            @NonNull DspHotwordDetectionCallback callback) {
    }

    /**
     * Called when the {@link VoiceInteractionService#createAlwaysOnHotwordDetector(String, Locale,
     * PersistableBundle, SharedMemory, AlwaysOnHotwordDetector.Callback)} or
     * {@link AlwaysOnHotwordDetector#setHotwordDetectionServiceConfig(PersistableBundle,
     * SharedMemory)} requests an update of the hotword detection parameters.
     *
     * @param options Application configuration data provided by the
     * {@link VoiceInteractionService}. PersistableBundle does not allow any remotable objects or
     * other contents that can be used to communicate with other processes.
     * @param sharedMemory The unrestricted data blob provided by the
     * {@link VoiceInteractionService}. Use this to provide the hotword models data or other
     * such data to the trusted process.
     *
     * @hide
     */
    @SystemApi
    public void onUpdateState(@Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory) {
    }

    /**
     * Callback for returning the detected result.
     *
     * @hide
     */
    @SystemApi
    public static final class DspHotwordDetectionCallback {
        // TODO: need to make sure we don't store remote references, but not a high priority.
        private final IDspHotwordDetectionCallback mRemoteCallback;

        private DspHotwordDetectionCallback(IDspHotwordDetectionCallback remoteCallback) {
            mRemoteCallback = remoteCallback;
        }

        /**
         * Called when the detected result is valid.
         */
        public void onDetected() {
            try {
                mRemoteCallback.onDetected();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Informs the {@link AlwaysOnHotwordDetector} that the keyphrase was not detected.
         *
         * @param result Info about the second stage detection result. This is provided to
         *         the {@link AlwaysOnHotwordDetector}.
         */
        public void onRejected(@Nullable HotwordRejectedResult result) {
            try {
                mRemoteCallback.onRejected(result);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
