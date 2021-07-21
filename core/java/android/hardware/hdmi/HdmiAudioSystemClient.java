/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.hardware.hdmi;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;

/**
 * HdmiAudioSystemClient represents HDMI-CEC logical device of type Audio System in the Android
 * system which acts as an audio system device such as sound bar.
 *
 * <p>HdmiAudioSystemClient provides methods that control, get information from TV/Display device
 * connected through HDMI bus.
 *
 * @hide
 */
public final class HdmiAudioSystemClient extends HdmiClient {
    private static final String TAG = "HdmiAudioSystemClient";

    private static final int REPORT_AUDIO_STATUS_INTERVAL_MS = 500;

    private final Handler mHandler;
    private boolean mCanSendAudioStatus = true;
    private boolean mPendingReportAudioStatus;

    private int mLastVolume;
    private int mLastMaxVolume;
    private boolean mLastIsMute;

    @VisibleForTesting(visibility = Visibility.PACKAGE)
    public HdmiAudioSystemClient(IHdmiControlService service) {
        this(service, null);
    }

    @VisibleForTesting(visibility = Visibility.PACKAGE)
    public HdmiAudioSystemClient(IHdmiControlService service, @Nullable Handler handler) {
        super(service);
        mHandler = handler == null ? new Handler(Looper.getMainLooper()) : handler;
    }

    /**
     * Callback interface used to get the set System Audio Mode result.
     *
     * @hide
     */
    public interface SetSystemAudioModeCallback {
        /**
         * Called when the input was changed.
         *
         * @param result the result of the set System Audio Mode
         */
        void onComplete(int result);
    }

    /** @hide */
    @Override
    public int getDeviceType() {
        return HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM;
    }

    /**
     * Sends a Report Audio Status HDMI CEC command to TV devices when necessary.
     *
     * According to HDMI CEC specification, an audio system can report its audio status when System
     * Audio Mode is on, so that the TV can display the audio status of external amplifier.
     *
     * @hide
     */
    public void sendReportAudioStatusCecCommand(boolean isMuteAdjust, int volume, int maxVolume,
            boolean isMute) {
        if (isMuteAdjust) {
            // always report audio status when it's muted/unmuted
            try {
                mService.reportAudioStatus(getDeviceType(), volume, maxVolume, isMute);
            } catch (RemoteException e) {
                // do nothing. Reporting audio status is optional.
            }
            return;
        }

        mLastVolume = volume;
        mLastMaxVolume = maxVolume;
        mLastIsMute = isMute;
        if (mCanSendAudioStatus) {
            try {
                mService.reportAudioStatus(getDeviceType(), volume, maxVolume, isMute);
                mCanSendAudioStatus = false;
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mPendingReportAudioStatus) {
                            // report audio status if there is any pending message
                            try {
                                mService.reportAudioStatus(getDeviceType(), mLastVolume,
                                        mLastMaxVolume, mLastIsMute);
                                mHandler.postDelayed(this, REPORT_AUDIO_STATUS_INTERVAL_MS);
                            }  catch (RemoteException e) {
                                mCanSendAudioStatus = true;
                            } finally {
                                mPendingReportAudioStatus = false;
                            }
                        } else {
                            mCanSendAudioStatus = true;
                        }
                    }
                }, REPORT_AUDIO_STATUS_INTERVAL_MS);
            } catch (RemoteException e) {
                // do nothing. Reporting audio status is optional.
            }
        } else {
            // if audio status cannot be sent, send it latter
            mPendingReportAudioStatus = true;
        }
    }

    /**
     * Set System Audio Mode on/off with audio system device.
     *
     * @param state true to set System Audio Mode on. False to set off.
     * @param callback callback offer the setting result.
     *
     * @hide
     */
    public void setSystemAudioMode(boolean state, @NonNull SetSystemAudioModeCallback callback) {
        // TODO(amyjojo): implement this when needed.
    }

    /**
     * When device is switching to an audio only source, this method is called to broadcast
     * a setSystemAudioMode on message to the HDMI CEC system without querying Active Source or
     * TV supporting System Audio Control or not. This is to get volume control passthrough
     * from STB even if TV does not support it.
     *
     * @hide
     */
    public void setSystemAudioModeOnForAudioOnlySource() {
        try {
            mService.setSystemAudioModeOnForAudioOnlySource();
        } catch (RemoteException e) {
            Log.d(TAG, "Failed to set System Audio Mode on for Audio Only source");
        }
    }
}
