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

package com.android.server.broadcastradio.aidl;

import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.hardware.broadcastradio.ConfigFlag;
import android.hardware.broadcastradio.IBroadcastRadio;
import android.hardware.broadcastradio.ProgramListChunk;
import android.hardware.radio.ITuner;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.os.Binder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.GuardedBy;
import com.android.server.broadcastradio.RadioEventLogger;
import com.android.server.broadcastradio.RadioServiceUserController;
import com.android.server.utils.Slogf;

import java.util.List;
import java.util.Map;
import java.util.Objects;

final class TunerSession extends ITuner.Stub {
    private static final String TAG = "BcRadioAidlSrv.session";
    private static final int TUNER_EVENT_LOGGER_QUEUE_SIZE = 25;

    private final Object mLock = new Object();

    private final RadioEventLogger mLogger;
    private final RadioModule mModule;
    final int mUserId;
    final android.hardware.radio.ITunerCallback mCallback;
    private final int mUid;
    private final IBroadcastRadio mService;
    private final RadioServiceUserController mUserController;

    @GuardedBy("mLock")
    private boolean mIsClosed;
    @GuardedBy("mLock")
    private boolean mIsMuted;
    @GuardedBy("mLock")
    private ProgramInfoCache mProgramInfoCache;

    // necessary only for older APIs compatibility
    @GuardedBy("mLock")
    private RadioManager.BandConfig mPlaceHolderConfig;

    TunerSession(RadioModule radioModule, IBroadcastRadio service,
            android.hardware.radio.ITunerCallback callback,
            RadioServiceUserController userController) {
        mModule = Objects.requireNonNull(radioModule, "radioModule cannot be null");
        mService = Objects.requireNonNull(service, "service cannot be null");
        mCallback = Objects.requireNonNull(callback, "callback cannot be null");
        mUserController = Objects.requireNonNull(userController, "User controller can not be null");
        mUserId = mUserController.getCallingUserId();
        mUid = Binder.getCallingUid();
        mLogger = new RadioEventLogger(TAG, TUNER_EVENT_LOGGER_QUEUE_SIZE);
    }

    @Override
    public void close() {
        mLogger.logRadioEvent("Close tuner");
        close(null);
    }

    /**
     * Closes the TunerSession. If error is non-null, the client's onError() callback is invoked
     * first with the specified error, see {@link
     * android.hardware.radio.RadioTuner.Callback#onError}.
     *
     * @param error Error to send to client before session is closed. If null, there is no error
     *              when closing the session.
     */
    public void close(@Nullable Integer error) {
        if (error == null) {
            mLogger.logRadioEvent("Close tuner session on error null");
        } else {
            mLogger.logRadioEvent("Close tuner session on error %d", error);
        }
        synchronized (mLock) {
            if (mIsClosed) {
                return;
            }
            mIsClosed = true;
        }
        if (error != null) {
            try {
                mCallback.onError(error);
            } catch (RemoteException ex) {
                Slogf.w(TAG, ex, "mCallback.onError(%s) failed", error);
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
            Slogf.w(TAG, "Cannot set configuration for AIDL HAL client from non-current user");
            return;
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            mPlaceHolderConfig = Objects.requireNonNull(config, "config cannot be null");
        }
        Slogf.i(TAG, "Ignoring setConfiguration - not applicable for broadcastradio HAL AIDL");
        mModule.fanoutAidlCallback((cb, mUid) -> cb.onConfigurationChanged(config));
    }

    @Override
    public RadioManager.BandConfig getConfiguration() {
        synchronized (mLock) {
            checkNotClosedLocked();
            return mPlaceHolderConfig;
        }
    }

    @Override
    public void setMuted(boolean mute) {
        synchronized (mLock) {
            checkNotClosedLocked();
            if (mIsMuted == mute) return;
            mIsMuted = mute;
        }
        Slogf.w(TAG, "Mute %b via RadioService is not implemented - please handle it via app",
                mute);
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
        mLogger.logRadioEvent("Step with direction %s, skipSubChannel?  %s",
                directionDown ? "down" : "up", skipSubChannel ? "yes" : "no");
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot step on AIDL HAL client from non-current user");
            return;
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            try {
                mService.step(!directionDown);
            } catch (RuntimeException ex) {
                throw ConversionUtils.throwOnError(ex, /* action= */ "step");
            }
        }
    }

    @Override
    public void seek(boolean directionDown, boolean skipSubChannel) throws RemoteException {
        mLogger.logRadioEvent("Seek with direction %s, skipSubChannel? %s",
                directionDown ? "down" : "up", skipSubChannel ? "yes" : "no");
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot scan on AIDL HAL client from non-current user");
            return;
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            try {
                mService.seek(!directionDown, skipSubChannel);
            } catch (RuntimeException ex) {
                throw ConversionUtils.throwOnError(ex, /* action= */ "seek");
            }
        }
    }

    @Override
    public void tune(ProgramSelector selector) throws RemoteException {
        mLogger.logRadioEvent("Tune with selector %s", selector);
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot tune on AIDL HAL client from non-current user");
            return;
        }
        android.hardware.broadcastradio.ProgramSelector hwSel =
                ConversionUtils.programSelectorToHalProgramSelector(selector);
        if (hwSel == null) {
            throw new IllegalArgumentException("tune: INVALID_ARGUMENTS for program selector");
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            try {
                mService.tune(hwSel);
            } catch (RuntimeException ex) {
                throw ConversionUtils.throwOnError(ex, /* action= */ "tune");
            }
        }
    }

    @Override
    public void cancel() {
        Slogf.i(TAG, "Cancel");
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot cancel on AIDL HAL client from non-current user");
            return;
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            try {
                mService.cancel();
            } catch (RemoteException ex) {
                Slogf.e(TAG, "Failed to cancel tuner session");
                throw ex.rethrowFromSystemServer();
            }
        }
    }

    @Override
    public void cancelAnnouncement() {
        Slogf.w(TAG, "Announcements control doesn't involve cancelling at the HAL level in AIDL");
    }

    @Override
    public Bitmap getImage(int id) {
        mLogger.logRadioEvent("Get image for %d", id);
        return mModule.getImage(id);
    }

    @Override
    public boolean startBackgroundScan() {
        Slogf.w(TAG, "Explicit background scan trigger is not supported with HAL AIDL");
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot start background scan on AIDL HAL client from non-current user");
            return false;
        }
        mModule.fanoutAidlCallback((cb, mUid) -> {
            cb.onBackgroundScanComplete();
        });
        return true;
    }

    @Override
    public void startProgramListUpdates(ProgramList.Filter filter) throws RemoteException {
        mLogger.logRadioEvent("Start programList updates %s", filter);
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG,
                    "Cannot start program list updates on AIDL HAL client from non-current user");
            return;
        }
        // If the AIDL client provides a null filter, it wants all updates, so use the most broad
        // filter.
        if (filter == null) {
            filter = new ProgramList.Filter(new ArraySet<>(), new ArraySet<>(),
                    /* includeCategories= */ true,
                    /* excludeModifications= */ false);
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            mProgramInfoCache = new ProgramInfoCache(filter);
        }
        // Note: RadioModule.onTunerSessionProgramListFilterChanged() must be called without mLock
        // held since it can call getProgramListFilter() and onHalProgramInfoUpdated().
        mModule.onTunerSessionProgramListFilterChanged(this);
    }

    int getUid() {
        return mUid;
    }

    ProgramList.Filter getProgramListFilter() {
        synchronized (mLock) {
            return mProgramInfoCache == null ? null : mProgramInfoCache.getFilter();
        }
    }

    void onMergedProgramListUpdateFromHal(ProgramListChunk mergedChunk) {
        List<ProgramList.Chunk> clientUpdateChunks;
        synchronized (mLock) {
            if (mProgramInfoCache == null) {
                return;
            }
            clientUpdateChunks = mProgramInfoCache.filterAndApplyChunk(mergedChunk);
        }
        dispatchClientUpdateChunks(clientUpdateChunks);
    }

    void updateProgramInfoFromHalCache(ProgramInfoCache halCache) {
        List<ProgramList.Chunk> clientUpdateChunks;
        synchronized (mLock) {
            if (mProgramInfoCache == null) {
                return;
            }
            clientUpdateChunks = mProgramInfoCache.filterAndUpdateFromInternal(
                    halCache, /* purge = */ true);
        }
        dispatchClientUpdateChunks(clientUpdateChunks);
    }

    private void dispatchClientUpdateChunks(@Nullable List<ProgramList.Chunk> chunks) {
        if (chunks == null) {
            return;
        }
        for (int i = 0; i < chunks.size(); i++) {
            try {
                if (!ConversionUtils.isAtLeastU(getUid())) {
                    ProgramList.Chunk downgradedChunk =
                            ConversionUtils.convertChunkToTargetSdkVersion(chunks.get(i), getUid());
                    mCallback.onProgramListUpdated(downgradedChunk);
                } else {
                    mCallback.onProgramListUpdated(chunks.get(i));
                }
            } catch (RemoteException ex) {
                Slogf.w(TAG, ex, "mCallback.onProgramListUpdated() failed");
            }
        }
    }

    @Override
    public void stopProgramListUpdates() throws RemoteException {
        mLogger.logRadioEvent("Stop programList updates");
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG,
                    "Cannot stop program list updates on AIDL HAL client from non-current user");
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
        mLogger.logRadioEvent("is ConfigFlag %s set? ", ConfigFlag.$.toString(flag));
        synchronized (mLock) {
            checkNotClosedLocked();

            try {
                return mService.isConfigFlagSet(flag);
            } catch (RuntimeException ex) {
                throw ConversionUtils.throwOnError(ex, /* action= */ "isConfigFlagSet");
            } catch (RemoteException ex) {
                throw new RuntimeException("Failed to check flag " + ConfigFlag.$.toString(flag),
                        ex);
            }
        }
    }

    @Override
    public void setConfigFlag(int flag, boolean value) throws RemoteException {
        mLogger.logRadioEvent("set ConfigFlag %s to %b ",
                ConfigFlag.$.toString(flag), value);
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot set config flag for AIDL HAL client from non-current user");
            return;
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            try {
                mService.setConfigFlag(flag, value);
            } catch (RuntimeException ex) {
                throw ConversionUtils.throwOnError(ex, /* action= */ "setConfigFlag");
            }
        }
    }

    @Override
    public Map<String, String> setParameters(Map<String, String> parameters) {
        mLogger.logRadioEvent("Set parameters ");
        if (!mUserController.isCurrentOrSystemUser()) {
            Slogf.w(TAG, "Cannot set parameters for AIDL HAL client from non-current user");
            return new ArrayMap<>();
        }
        synchronized (mLock) {
            checkNotClosedLocked();
            try {
                return ConversionUtils.vendorInfoFromHalVendorKeyValues(mService.setParameters(
                        ConversionUtils.vendorInfoToHalVendorKeyValues(parameters)));
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }
    }

    @Override
    public Map<String, String> getParameters(List<String> keys) {
        mLogger.logRadioEvent("Get parameters ");
        synchronized (mLock) {
            checkNotClosedLocked();
            try {
                return ConversionUtils.vendorInfoFromHalVendorKeyValues(
                        mService.getParameters(keys.toArray(new String[0])));
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }
    }

    void dumpInfo(IndentingPrintWriter pw) {
        pw.printf("TunerSession\n");

        pw.increaseIndent();
        synchronized (mLock) {
            pw.printf("Is session closed? %s\n", mIsClosed ? "Yes" : "No");
            pw.printf("Is muted? %s\n", mIsMuted ? "Yes" : "No");
            pw.printf("ProgramInfoCache: %s\n", mProgramInfoCache);
            pw.printf("Config: %s\n", mPlaceHolderConfig);
        }
        pw.printf("Tuner session events:\n");

        pw.increaseIndent();
        mLogger.dump(pw);
        pw.decreaseIndent();

        pw.decreaseIndent();
    }
}
