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

import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.hardware.broadcastradio.V2_0.ConfigFlag;
import android.hardware.broadcastradio.V2_0.ITunerSession;
import android.hardware.broadcastradio.V2_0.ProgramListChunk;
import android.hardware.broadcastradio.V2_0.Result;
import android.hardware.radio.ITuner;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.MutableBoolean;
import android.util.MutableInt;

import com.android.internal.annotations.GuardedBy;
import com.android.server.broadcastradio.RadioEventLogger;
import com.android.server.broadcastradio.RadioServiceUserController;
import com.android.server.utils.Slogf;

import java.util.List;
import java.util.Map;
import java.util.Objects;

final class TunerSession extends ITuner.Stub {
    private static final String TAG = "BcRadio2Srv.session";
    private static final int TUNER_EVENT_LOGGER_QUEUE_SIZE = 25;

    private final Object mLock = new Object();
    private final RadioEventLogger mEventLogger;

    private final RadioModule mModule;
    private final ITunerSession mHwSession;
    final int mUserId;
    final android.hardware.radio.ITunerCallback mCallback;
    private final RadioServiceUserController mUserController;

    @GuardedBy("mLock")
    private boolean mIsClosed = false;
    @GuardedBy("mLock")
    private boolean mIsMuted = false;
    @GuardedBy("mLock")
    private ProgramInfoCache mProgramInfoCache = null;

    // necessary only for older APIs compatibility
    private RadioManager.BandConfig mDummyConfig = null;

    TunerSession(RadioModule module, ITunerSession hwSession,
            android.hardware.radio.ITunerCallback callback,
            RadioServiceUserController userController) {
        mModule = Objects.requireNonNull(module);
        mHwSession = Objects.requireNonNull(hwSession);
        mCallback = Objects.requireNonNull(callback);
        mUserController = Objects.requireNonNull(userController, "User controller can not be null");
        mUserId = mUserController.getCallingUserId();
        mEventLogger = new RadioEventLogger(TAG, TUNER_EVENT_LOGGER_QUEUE_SIZE);
    }

    @Override
    public void close() {
        mEventLogger.logRadioEvent("Close");
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
        mEventLogger.logRadioEvent("Close on error %d", error);
        synchronized (mLock) {
            if (mIsClosed) return;
            mIsClosed = true;
        }
        if (error != null) {
            try {
                mCallback.onError(error);
            } catch (RemoteException ex) {
                Slogf.w(TAG, "mCallback.onError() failed: ", ex);
            }
        }
        mModule.onTunerSessionClosed(this);
    }

    @Override
    public boolean isClosed() {
        synchronized (mLock) {
            return mIsClosed;
        }
    }

    @GuardedBy("mLock")
    private void checkNotClosedLocked() {
        if (mIsClosed) {
            throw new IllegalStateException("Tuner is closed, no further operations are allowed");
        }
    }

    @Override
    public void setConfiguration(RadioManager.BandConfig config) {
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot set configuration for HAL 2.0 client from non-current user");
            return;
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            mDummyConfig = Objects.requireNonNull(config);
        }
        Slogf.i(TAG, "Ignoring setConfiguration - not applicable for broadcastradio HAL 2.0");
        mModule.fanoutAidlCallback(cb -> cb.onConfigurationChanged(config));
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
        }
        Slogf.w(TAG, "Mute via RadioService is not implemented - please handle it via app");
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
        mEventLogger.logRadioEvent("Step with direction %s, skipSubChannel?  %s",
                directionDown ? "down" : "up", skipSubChannel ? "yes" : "no");
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot step on HAL 2.0 client from non-current user");
            return;
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            int halResult = mHwSession.step(!directionDown);
            Convert.throwOnError("step", halResult);
        }
    }

    @Override
    public void seek(boolean directionDown, boolean skipSubChannel) throws RemoteException {
        mEventLogger.logRadioEvent("Seek with direction %s, skipSubChannel? %s",
                directionDown ? "down" : "up", skipSubChannel ? "yes" : "no");
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot scan on HAL 2.0 client from non-current user");
            return;
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            int halResult = mHwSession.scan(!directionDown, skipSubChannel);
            Convert.throwOnError("step", halResult);
        }
    }

    @Override
    public void tune(ProgramSelector selector) throws RemoteException {
        mEventLogger.logRadioEvent("Tune with selector %s", selector);
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot tune on HAL 2.0 client from non-current user");
            return;
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            int halResult = mHwSession.tune(Convert.programSelectorToHal(selector));
            Convert.throwOnError("tune", halResult);
        }
    }

    @Override
    public void cancel() {
        Slogf.i(TAG, "Cancel");
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot cancel on HAL 2.0 client from non-current user");
            return;
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            Utils.maybeRethrow(mHwSession::cancel);
        }
    }

    @Override
    public void cancelAnnouncement() {
        Slogf.w(TAG,
                "Announcements control doesn't involve cancelling at the HAL level in HAL 2.0");
    }

    @Override
    public Bitmap getImage(int id) {
        mEventLogger.logRadioEvent("Get image for %d", id);
        return mModule.getImage(id);
    }

    @Override
    public boolean startBackgroundScan() {
        Slogf.w(TAG, "Explicit background scan trigger is not supported with HAL 2.0");
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG,
                    "Cannot start background scan on HAL 2.0 client from non-current user");
            return false;
        }
        mModule.fanoutAidlCallback(cb -> cb.onBackgroundScanComplete());
        return true;
    }

    @Override
    public void startProgramListUpdates(ProgramList.Filter filter) {
        mEventLogger.logRadioEvent("start programList updates %s", filter);
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG,
                    "Cannot start program list updates on HAL 2.0 client from non-current user");
            return;
        }
        // If the AIDL client provides a null filter, it wants all updates, so use the most broad
        // filter.
        if (filter == null) {
            filter = new ProgramList.Filter(new ArraySet<>(), new ArraySet<>(),
                    /* includeCategories= */ true, /* excludeModifications= */ false);
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

    void onMergedProgramListUpdateFromHal(ProgramListChunk mergedChunk) {
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
            clientUpdateChunks = mProgramInfoCache.filterAndUpdateFrom(halCache, /* purge= */ true);
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
                Slogf.w(TAG, "mCallback.onProgramListUpdated() failed: ", ex);
            }
        }
    }

    @Override
    public void stopProgramListUpdates() throws RemoteException {
        mEventLogger.logRadioEvent("Stop programList updates");
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG,
                    "Cannot stop program list updates on HAL 2.0 client from non-current user");
            return;
        }
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
        } catch (IllegalStateException | UnsupportedOperationException ex) {
            return false;
        }
    }

    @Override
    public boolean isConfigFlagSet(int flag) {
        mEventLogger.logRadioEvent("Is ConfigFlagSet for %s", ConfigFlag.toString(flag));
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
        mEventLogger.logRadioEvent("Set ConfigFlag  %s = %b", ConfigFlag.toString(flag), value);
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot set config flag for HAL 2.0 client from non-current user");
            return;
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            int halResult = mHwSession.setConfigFlag(flag, value);
            Convert.throwOnError("setConfigFlag", halResult);
        }
    }

    @Override
    public Map<String, String> setParameters(Map<String, String> parameters) {
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot set parameters for HAL 2.0 client from non-current user");
            return new ArrayMap<>();
        }
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

    void dumpInfo(IndentingPrintWriter pw) {
        pw.printf("TunerSession\n");
        pw.increaseIndent();
        pw.printf("HIDL HAL Session: %s\n", mHwSession);
        synchronized (mLock) {
            pw.printf("Is session closed? %s\n", mIsClosed ? "Yes" : "No");
            pw.printf("Is muted? %s\n", mIsMuted ? "Yes" : "No");
            pw.printf("ProgramInfoCache: %s\n", mProgramInfoCache);
            pw.printf("Config: %s\n", mDummyConfig);
        }
        pw.printf("Tuner session events:\n");
        pw.increaseIndent();
        mEventLogger.dump(pw);
        pw.decreaseIndent();
        pw.decreaseIndent();
    }
}
