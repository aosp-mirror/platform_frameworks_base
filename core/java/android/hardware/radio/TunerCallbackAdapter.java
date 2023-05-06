/**
 * Copyright (C) 2017 The Android Open Source Project
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

package android.hardware.radio;

import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Implements the ITunerCallback interface by forwarding calls to RadioTuner.Callback.
 */
final class TunerCallbackAdapter extends ITunerCallback.Stub {
    private static final String TAG = "BroadcastRadio.TunerCallbackAdapter";

    private final Object mLock = new Object();
    private final RadioTuner.Callback mCallback;
    private final Handler mHandler;

    @GuardedBy("mLock")
    @Nullable ProgramList mProgramList;

    // cache for deprecated methods
    @GuardedBy("mLock")
    boolean mIsAntennaConnected = true;

    @GuardedBy("mLock")
    @Nullable List<RadioManager.ProgramInfo> mLastCompleteList;

    @GuardedBy("mLock")
    private boolean mDelayedCompleteCallback;

    @GuardedBy("mLock")
    @Nullable RadioManager.ProgramInfo mCurrentProgramInfo;

    TunerCallbackAdapter(RadioTuner.Callback callback, @Nullable Handler handler) {
        mCallback = Objects.requireNonNull(callback, "Callback cannot be null");
        if (handler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        } else {
            mHandler = handler;
        }
    }

    void close() {
        synchronized (mLock) {
            if (mProgramList != null) {
                mProgramList.close();
            }
        }
    }

    void setProgramListObserver(@Nullable ProgramList programList,
            ProgramList.OnCloseListener closeListener) {
        Objects.requireNonNull(closeListener, "CloseListener cannot be null");
        synchronized (mLock) {
            if (mProgramList != null) {
                Log.w(TAG, "Previous program list observer wasn't properly closed, closing it...");
                mProgramList.close();
            }
            mProgramList = programList;
            if (programList == null) {
                return;
            }
            programList.setOnCloseListener(() -> {
                synchronized (mLock) {
                    if (mProgramList != programList) {
                        return;
                    }
                    mProgramList = null;
                    mLastCompleteList = null;
                }
                closeListener.onClose();
            });
            programList.addOnCompleteListener(() -> {
                synchronized (mLock) {
                    if (mProgramList != programList) {
                        return;
                    }
                    mLastCompleteList = programList.toList();
                    if (mDelayedCompleteCallback) {
                        Log.d(TAG, "Sending delayed onBackgroundScanComplete callback");
                        sendBackgroundScanCompleteLocked();
                    }
                }
            });
        }
    }

    @Nullable List<RadioManager.ProgramInfo> getLastCompleteList() {
        synchronized (mLock) {
            return mLastCompleteList;
        }
    }

    void clearLastCompleteList() {
        synchronized (mLock) {
            mLastCompleteList = null;
        }
    }

    @Nullable RadioManager.ProgramInfo getCurrentProgramInformation() {
        synchronized (mLock) {
            return mCurrentProgramInfo;
        }
    }

    boolean isAntennaConnected() {
        boolean isConnected;
        synchronized (mLock) {
            isConnected = mIsAntennaConnected;
        }
        return isConnected;
    }

    @Override
    public void onError(int status) {
        mHandler.post(() -> mCallback.onError(status));
    }

    @Override
    public void onTuneFailed(int status, @Nullable ProgramSelector selector) {
        mHandler.post(() -> mCallback.onTuneFailed(status, selector));

        int errorCode;
        switch (status) {
            case RadioTuner.TUNER_RESULT_CANCELED:
                errorCode = RadioTuner.ERROR_CANCELLED;
                break;
            case RadioManager.STATUS_PERMISSION_DENIED:
            case RadioManager.STATUS_DEAD_OBJECT:
                errorCode = RadioTuner.ERROR_SERVER_DIED;
                break;
            case RadioManager.STATUS_ERROR:
            case RadioManager.STATUS_NO_INIT:
            case RadioManager.STATUS_BAD_VALUE:
            case RadioManager.STATUS_INVALID_OPERATION:
            case RadioTuner.TUNER_RESULT_INTERNAL_ERROR:
            case RadioTuner.TUNER_RESULT_INVALID_ARGUMENTS:
            case RadioTuner.TUNER_RESULT_INVALID_STATE:
            case RadioTuner.TUNER_RESULT_NOT_SUPPORTED:
            case RadioTuner.TUNER_RESULT_UNKNOWN_ERROR:
                Log.i(TAG, "Got an error with no mapping to the legacy API (" + status
                        + "), doing a best-effort conversion to ERROR_SCAN_TIMEOUT");
            // fall through
            case RadioManager.STATUS_TIMED_OUT:
            case RadioTuner.TUNER_RESULT_TIMEOUT:
            default:
                errorCode = RadioTuner.ERROR_SCAN_TIMEOUT;
        }
        mHandler.post(() -> mCallback.onError(errorCode));
    }

    @Override
    public void onConfigurationChanged(RadioManager.BandConfig config) {
        mHandler.post(() -> mCallback.onConfigurationChanged(config));
    }

    @Override
    public void onCurrentProgramInfoChanged(RadioManager.ProgramInfo info) {
        if (info == null) {
            Log.e(TAG, "ProgramInfo must not be null");
            return;
        }

        synchronized (mLock) {
            mCurrentProgramInfo = info;
        }

        mHandler.post(() -> {
            mCallback.onProgramInfoChanged(info);

            RadioMetadata metadata = info.getMetadata();
            if (metadata != null) mCallback.onMetadataChanged(metadata);
        });
    }

    @Override
    public void onTrafficAnnouncement(boolean active) {
        mHandler.post(() -> mCallback.onTrafficAnnouncement(active));
    }

    @Override
    public void onEmergencyAnnouncement(boolean active) {
        mHandler.post(() -> mCallback.onEmergencyAnnouncement(active));
    }

    @Override
    public void onAntennaState(boolean connected) {
        synchronized (mLock) {
            mIsAntennaConnected = connected;
        }
        mHandler.post(() -> mCallback.onAntennaState(connected));
    }

    @Override
    public void onBackgroundScanAvailabilityChange(boolean isAvailable) {
        mHandler.post(() -> mCallback.onBackgroundScanAvailabilityChange(isAvailable));
    }

    @GuardedBy("mLock")
    private void sendBackgroundScanCompleteLocked() {
        mDelayedCompleteCallback = false;
        mHandler.post(() -> mCallback.onBackgroundScanComplete());
    }

    @Override
    public void onBackgroundScanComplete() {
        synchronized (mLock) {
            if (mLastCompleteList == null) {
                Log.i(TAG, "Got onBackgroundScanComplete callback, but the "
                        + "program list didn't get through yet. Delaying it...");
                mDelayedCompleteCallback = true;
                return;
            }
            sendBackgroundScanCompleteLocked();
        }
    }

    @Override
    public void onProgramListChanged() {
        mHandler.post(() -> mCallback.onProgramListChanged());
    }

    @Override
    public void onProgramListUpdated(ProgramList.Chunk chunk) {
        mHandler.post(() -> {
            synchronized (mLock) {
                if (mProgramList == null) {
                    return;
                }
                mProgramList.apply(Objects.requireNonNull(chunk, "Chunk cannot be null"));
            }
        });
    }

    @Override
    public void onConfigFlagUpdated(@RadioManager.ConfigFlag int flag, boolean value) {
        mHandler.post(() -> mCallback.onConfigFlagUpdated(flag, value));
    }

    @Override
    public void onParametersUpdated(Map<String, String> parameters) {
        mHandler.post(() -> mCallback.onParametersUpdated(parameters));
    }
}
