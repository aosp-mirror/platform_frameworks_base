/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.companion.virtual.audio;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.companion.virtual.IVirtualDevice;
import android.content.Context;
import android.hardware.display.VirtualDisplay;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecordingConfiguration;
import android.os.RemoteException;

import java.io.Closeable;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * The class stores an {@link AudioCapture} for audio capturing and an {@link AudioInjection} for
 * audio injection.
 *
 * @hide
 */
@SystemApi
public final class VirtualAudioDevice implements Closeable {

    /**
     * Interface to be notified when playback or recording configuration of applications running on
     * virtual display was changed.
     *
     * @hide
     */
    @SystemApi
    public interface AudioConfigurationChangeCallback {
        /**
         * Notifies when playback configuration of applications running on virtual display was
         * changed.
         */
        void onPlaybackConfigChanged(@NonNull List<AudioPlaybackConfiguration> configs);

        /**
         * Notifies when recording configuration of applications running on virtual display was
         * changed.
         */
        void onRecordingConfigChanged(@NonNull List<AudioRecordingConfiguration> configs);
    }

    private final Context mContext;
    private final IVirtualDevice mVirtualDevice;
    private final VirtualDisplay mVirtualDisplay;
    private final AudioConfigurationChangeCallback mCallback;
    private final Executor mExecutor;
    @Nullable
    private VirtualAudioSession mOngoingSession;

    /**
     * @hide
     */
    public VirtualAudioDevice(Context context, IVirtualDevice virtualDevice,
            @NonNull VirtualDisplay virtualDisplay, @Nullable Executor executor,
            @Nullable AudioConfigurationChangeCallback callback) {
        mContext = context;
        mVirtualDevice = virtualDevice;
        mVirtualDisplay = virtualDisplay;
        mExecutor = executor;
        mCallback = callback;
    }

    /**
     * Begins injecting audio from a remote device into this device.
     *
     * @return An {@link AudioInjection} containing the injected audio.
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    @NonNull
    public AudioInjection startAudioInjection(@NonNull AudioFormat injectionFormat) {
        Objects.requireNonNull(injectionFormat, "injectionFormat must not be null");

        if (mOngoingSession != null && mOngoingSession.getAudioInjection() != null) {
            throw new IllegalStateException("Cannot start an audio injection while a session is "
                    + "ongoing. Call close() on this device first to end the previous session.");
        }
        if (mOngoingSession == null) {
            mOngoingSession = new VirtualAudioSession(mContext, mCallback, mExecutor);
        }

        try {
            mVirtualDevice.onAudioSessionStarting(mVirtualDisplay.getDisplay().getDisplayId(),
                    /* routingCallback= */ mOngoingSession,
                    /* configChangedCallback= */  mOngoingSession.getAudioConfigChangedListener());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return mOngoingSession.startAudioInjection(injectionFormat);
    }

    /**
     * Begins recording audio emanating from this device.
     *
     * <p>Note: This method does not support capturing privileged playback, which means the
     * application can opt out of capturing by {@link AudioManager#setAllowedCapturePolicy(int)}.
     *
     * @return An {@link AudioCapture} containing the recorded audio.
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    @NonNull
    public AudioCapture startAudioCapture(@NonNull AudioFormat captureFormat) {
        Objects.requireNonNull(captureFormat, "captureFormat must not be null");

        if (mOngoingSession != null && mOngoingSession.getAudioCapture() != null) {
            throw new IllegalStateException("Cannot start an audio capture while a session is "
                    + "ongoing. Call close() on this device first to end the previous session.");
        }
        if (mOngoingSession == null) {
            mOngoingSession = new VirtualAudioSession(mContext, mCallback, mExecutor);
        }

        try {
            mVirtualDevice.onAudioSessionStarting(mVirtualDisplay.getDisplay().getDisplayId(),
                    /* routingCallback= */ mOngoingSession,
                    /* configChangedCallback= */ mOngoingSession.getAudioConfigChangedListener());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return mOngoingSession.startAudioCapture(captureFormat);
    }

    /** Returns the {@link AudioCapture} instance. */
    @Nullable
    public AudioCapture getAudioCapture() {
        return mOngoingSession != null ? mOngoingSession.getAudioCapture() : null;
    }

    /** Returns the {@link AudioInjection} instance. */
    @Nullable
    public AudioInjection getAudioInjection() {
        return mOngoingSession != null ? mOngoingSession.getAudioInjection() : null;
    }

    /** Stops audio capture and injection then releases all the resources */
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    @Override
    public void close() {
        if (mOngoingSession != null) {
            mOngoingSession.close();
            mOngoingSession = null;

            try {
                mVirtualDevice.onAudioSessionEnded();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
