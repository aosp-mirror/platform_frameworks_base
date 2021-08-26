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

package com.android.server.broadcastradio.hal2;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.hardware.broadcastradio.V2_0.ConfigFlag;
import android.hardware.broadcastradio.V2_0.ITunerSession;
import android.hardware.broadcastradio.V2_0.Result;
import android.hardware.radio.ITuner;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.os.RemoteException;
import android.util.MutableBoolean;
import android.util.MutableInt;
import android.util.Slog;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

class TunerSession extends ITuner.Stub {
    private static final String TAG = "BcRadio2Srv.session";
    private static final String kAudioDeviceName = "Radio tuner source";

    private final Object mLock = new Object();

    private final RadioModule mModule;
    private final ITunerSession mHwSession;
    final android.hardware.radio.ITunerCallback mCallback;
    private boolean mIsClosed = false;
    private boolean mIsMuted = false;
    private ProgramInfoCache mProgramInfoCache = null;

    // necessary only for older APIs compatibility
    private RadioManager.BandConfig mDummyConfig = null;

    TunerSession(@NonNull RadioModule module, @NonNull ITunerSession hwSession,
            @NonNull android.hardware.radio.ITunerCallback callback) {
        mModule = Objects.requireNonNull(module);
        mHwSession = Objects.requireNonNull(hwSession);
        mCallback = Objects.requireNonNull(callback);
    }

    @Override
    public void close() {
        close(null);
    }

    /**
     * Closes the TunerSession. If error is non-null, the client's onError() callback is invoked
     * first with the specified error, see {@link
     * android.hardware.radio.RadioTuner.Callback#onError}.
     *
     * @param error Optional error to send to client before session is closed.
     */
    public void close(@Nullable Integer error) {
        synchronized (mLock) {
            if (mIsClosed) return;
            if (error != null) {
                try {
                    mCallback.onError(error);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "mCallback.onError() failed: ", ex);
                }
            }
            mIsClosed = true;
            mModule.onTunerSessionClosed(this);
        }
    }

    @Override
    public boolean isClosed() {
        return mIsClosed;
    }

    private void checkNotClosedLocked() {
        if (mIsClosed) {
            throw new IllegalStateException("Tuner is closed, no further operations are allowed");
        }
    }

    @Override
    public void setConfiguration(RadioManager.BandConfig config) {
        synchronized (mLock) {
            checkNotClosedLocked();
            mDummyConfig = Objects.requireNonNull(config);
            Slog.i(TAG, "Ignoring setConfiguration - not applicable for broadcastradio HAL 2.x");
            mModule.fanoutAidlCallback(cb -> cb.onConfigurationChanged(config));
        }
    }

    @Override
    public RadioManager.BandConfig getConfiguration() {
        synchronized (mLock) {
            checkNotClosedLocked();
            return mDummyConfig;
        }
    }

    @Override
    public void setMuted(boolean mute) {
        synchronized (mLock) {
            checkNotClosedLocked();
            if (mIsMuted == mute) return;
            mIsMuted = mute;
            Slog.w(TAG, "Mute via RadioService is not implemented - please handle it via app");
        }
    }

    @Override
    public boolean isMuted() {
        synchronized (mLock) {
            checkNotClosedLocked();
            return mIsMuted;
        }
    }

    @Override
    public void step(boolean directionDown, boolean skipSubChannel) throws RemoteException {
        synchronized (mLock) {
            checkNotClosedLocked();
            int halResult = mHwSession.step(!directionDown);
            Convert.throwOnError("step", halResult);
        }
    }

    @Override
    public void scan(boolean directionDown, boolean skipSubChannel) throws RemoteException {
        synchronized (mLock) {
            checkNotClosedLocked();
            int halResult = mHwSession.scan(!directionDown, skipSubChannel);
            Convert.throwOnError("step", halResult);
        }
    }

    @Override
    public void tune(ProgramSelector selector) throws RemoteException {
        synchronized (mLock) {
            checkNotClosedLocked();
            int halResult = mHwSession.tune(Convert.programSelectorToHal(selector));
            Convert.throwOnError("tune", halResult);
        }
    }

    @Override
    public void cancel() {
        synchronized (mLock) {
            checkNotClosedLocked();
            Utils.maybeRethrow(mHwSession::cancel);
        }
    }

    @Override
    public void cancelAnnouncement() {
        Slog.i(TAG, "Announcements control doesn't involve cancelling at the HAL level in 2.x");
    }

    @Override
    public Bitmap getImage(int id) {
        return mModule.getImage(id);
    }

    @Override
    public boolean startBackgroundScan() {
        Slog.i(TAG, "Explicit background scan trigger is not supported with HAL 2.x");
        mModule.fanoutAidlCallback(cb -> cb.onBackgroundScanComplete());
        return true;
    }

    @Override
    public void startProgramListUpdates(ProgramList.Filter filter) throws RemoteException {
        // If the AIDL client provides a null filter, it wants all updates, so use the most broad
        // filter.
        if (filter == null) {
            filter = new ProgramList.Filter(new HashSet<Integer>(),
                    new HashSet<android.hardware.radio.ProgramSelector.Identifier>(), true, false);
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            mProgramInfoCache = new ProgramInfoCache(filter);
        }
        // Note: RadioModule.onTunerSessionProgramListFilterChanged() must be called without mLock
        // held since it can call getProgramListFilter() and onHalProgramInfoUpdated().
        mModule.onTunerSessionProgramListFilterChanged(this);
    }

    ProgramList.Filter getProgramListFilter() {
        synchronized (mLock) {
            return mProgramInfoCache == null ? null : mProgramInfoCache.getFilter();
        }
    }

    void onMergedProgramListUpdateFromHal(ProgramList.Chunk mergedChunk) {
        List<ProgramList.Chunk> clientUpdateChunks = null;
        synchronized (mLock) {
            if (mProgramInfoCache == null) {
                return;
            }
            clientUpdateChunks = mProgramInfoCache.filterAndApplyChunk(mergedChunk);
        }
        dispatchClientUpdateChunks(clientUpdateChunks);
    }

    void updateProgramInfoFromHalCache(ProgramInfoCache halCache) {
        List<ProgramList.Chunk> clientUpdateChunks = null;
        synchronized (mLock) {
            if (mProgramInfoCache == null) {
                return;
            }
            clientUpdateChunks = mProgramInfoCache.filterAndUpdateFrom(halCache, true);
        }
        dispatchClientUpdateChunks(clientUpdateChunks);
    }

    private void dispatchClientUpdateChunks(@Nullable List<ProgramList.Chunk> chunks) {
        if (chunks == null) {
            return;
        }
        for (ProgramList.Chunk chunk : chunks) {
            try {
                mCallback.onProgramListUpdated(chunk);
            } catch (RemoteException ex) {
                Slog.w(TAG, "mCallback.onProgramListUpdated() failed: ", ex);
            }
        }
    }

    @Override
    public void stopProgramListUpdates() throws RemoteException {
        synchronized (mLock) {
            checkNotClosedLocked();
            mProgramInfoCache = null;
        }
        // Note: RadioModule.onTunerSessionProgramListFilterChanged() must be called without mLock
        // held since it can call getProgramListFilter() and onHalProgramInfoUpdated().
        mModule.onTunerSessionProgramListFilterChanged(this);
    }

    @Override
    public boolean isConfigFlagSupported(int flag) {
        try {
            isConfigFlagSet(flag);
            return true;
        } catch (IllegalStateException ex) {
            return true;
        } catch (UnsupportedOperationException ex) {
            return false;
        }
    }

    @Override
    public boolean isConfigFlagSet(int flag) {
        Slog.v(TAG, "isConfigFlagSet " + ConfigFlag.toString(flag));
        synchronized (mLock) {
            checkNotClosedLocked();

            MutableInt halResult = new MutableInt(Result.UNKNOWN_ERROR);
            MutableBoolean flagState = new MutableBoolean(false);
            try {
                mHwSession.isConfigFlagSet(flag, (int result, boolean value) -> {
                    halResult.value = result;
                    flagState.value = value;
                });
            } catch (RemoteException ex) {
                throw new RuntimeException("Failed to check flag " + ConfigFlag.toString(flag), ex);
            }
            Convert.throwOnError("isConfigFlagSet", halResult.value);

            return flagState.value;
        }
    }

    @Override
    public void setConfigFlag(int flag, boolean value) throws RemoteException {
        Slog.v(TAG, "setConfigFlag " + ConfigFlag.toString(flag) + " = " + value);
        synchronized (mLock) {
            checkNotClosedLocked();
            int halResult = mHwSession.setConfigFlag(flag, value);
            Convert.throwOnError("setConfigFlag", halResult);
        }
    }

    @Override
    public Map<String, String> setParameters(Map<String, String> parameters) {
        synchronized (mLock) {
            checkNotClosedLocked();
            return Convert.vendorInfoFromHal(Utils.maybeRethrow(
                    () -> mHwSession.setParameters(Convert.vendorInfoToHal(parameters))));
        }
    }

    @Override
    public Map<String, String> getParameters(List<String> keys) {
        synchronized (mLock) {
            checkNotClosedLocked();
            return Convert.vendorInfoFromHal(Utils.maybeRethrow(
                    () -> mHwSession.getParameters(Convert.listToArrayList(keys))));
        }
    }
}
