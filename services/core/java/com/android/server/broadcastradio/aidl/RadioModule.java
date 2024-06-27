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
import android.graphics.BitmapFactory;
import android.hardware.broadcastradio.AmFmRegionConfig;
import android.hardware.broadcastradio.Announcement;
import android.hardware.broadcastradio.DabTableEntry;
import android.hardware.broadcastradio.IAnnouncementListener;
import android.hardware.broadcastradio.IBroadcastRadio;
import android.hardware.broadcastradio.ICloseHandle;
import android.hardware.broadcastradio.ITunerCallback;
import android.hardware.broadcastradio.ProgramInfo;
import android.hardware.broadcastradio.ProgramListChunk;
import android.hardware.broadcastradio.ProgramSelector;
import android.hardware.broadcastradio.VendorKeyValue;
import android.hardware.radio.RadioManager;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.broadcastradio.RadioEventLogger;
import com.android.server.broadcastradio.RadioServiceUserController;
import com.android.server.utils.Slogf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class RadioModule {
    private static final String TAG = "BcRadioAidlSrv.module";
    private static final int RADIO_EVENT_LOGGER_QUEUE_SIZE = 25;

    private final IBroadcastRadio mService;

    private final Object mLock = new Object();
    private final Handler mHandler;
    private final RadioEventLogger mLogger;
    private final RadioManager.ModuleProperties mProperties;

    /**
     * Tracks antenna state reported by HAL (if any).
     */
    @GuardedBy("mLock")
    private Boolean mAntennaConnected;

    @GuardedBy("mLock")
    private RadioManager.ProgramInfo mCurrentProgramInfo;

    @GuardedBy("mLock")
    private final ProgramInfoCache mProgramInfoCache = new ProgramInfoCache(null);

    @GuardedBy("mLock")
    private android.hardware.radio.ProgramList.Filter mUnionOfAidlProgramFilters;

    /**
     * Set of active AIDL tuner sessions created through openSession().
     */
    @GuardedBy("mLock")
    private final ArraySet<TunerSession> mAidlTunerSessions = new ArraySet<>();

    /**
     * Callback registered with the HAL to relay callbacks to AIDL clients.
     */
    private final ITunerCallback mHalTunerCallback = new ITunerCallback.Stub() {
        @Override
        public int getInterfaceVersion() {
            return this.VERSION;
        }

        @Override
        public String getInterfaceHash() {
            return this.HASH;
        }

        public void onTuneFailed(int result, ProgramSelector programSelector) {
            fireLater(() -> {
                android.hardware.radio.ProgramSelector csel =
                        ConversionUtils.programSelectorFromHalProgramSelector(programSelector);
                int tunerResult = ConversionUtils.halResultToTunerResult(result);
                synchronized (mLock) {
                    fanoutAidlCallbackLocked((cb, uid) -> {
                        if (csel != null && !ConversionUtils
                                .programSelectorMeetsSdkVersionRequirement(csel, uid)) {
                            Slogf.e(TAG, "onTuneFailed: cannot send program selector "
                                    + "requiring higher target SDK version");
                            return;
                        }
                        cb.onTuneFailed(tunerResult, csel);
                    });
                }
            });
        }

        @Override
        public void onCurrentProgramInfoChanged(ProgramInfo halProgramInfo) {
            fireLater(() -> {
                RadioManager.ProgramInfo currentProgramInfo =
                        ConversionUtils.tunedProgramInfoFromHalProgramInfo(halProgramInfo);
                Objects.requireNonNull(currentProgramInfo,
                        "Program info from AIDL HAL is invalid");
                synchronized (mLock) {
                    mCurrentProgramInfo = currentProgramInfo;
                    fanoutAidlCallbackLocked((cb, uid) -> {
                        if (!ConversionUtils.programInfoMeetsSdkVersionRequirement(
                                currentProgramInfo, uid)) {
                            Slogf.e(TAG, "onCurrentProgramInfoChanged: cannot send "
                                    + "program info requiring higher target SDK version");
                            return;
                        }
                        cb.onCurrentProgramInfoChanged(currentProgramInfo);
                    });
                }
            });
        }

        @Override
        public void onProgramListUpdated(ProgramListChunk programListChunk) {
            fireLater(() -> {
                synchronized (mLock) {
                    mProgramInfoCache.filterAndApplyChunk(programListChunk);

                    for (int i = 0; i < mAidlTunerSessions.size(); i++) {
                        mAidlTunerSessions.valueAt(i).onMergedProgramListUpdateFromHal(
                                programListChunk);
                    }
                }
            });
        }

        @Override
        public void onAntennaStateChange(boolean connected) {
            fireLater(() -> {
                synchronized (mLock) {
                    mAntennaConnected = connected;
                    fanoutAidlCallbackLocked((cb, uid) -> cb.onAntennaState(connected));
                }
            });
        }

        @Override
        public void onConfigFlagUpdated(int flag, boolean value) {
            fireLater(() -> {
                synchronized (mLock) {
                    fanoutAidlCallbackLocked((cb, uid) -> {
                        if (!ConversionUtils.configFlagMeetsSdkVersionRequirement(flag, uid)) {
                            Slogf.e(TAG, "onConfigFlagUpdated: cannot send program info "
                                    + "requiring higher target SDK version");
                            return;
                        }
                        cb.onConfigFlagUpdated(flag, value);
                    });
                }
            });
        }

        @Override
        public void onParametersUpdated(VendorKeyValue[] parameters) {
            fireLater(() -> {
                synchronized (mLock) {
                    Map<String, String> cparam =
                            ConversionUtils.vendorInfoFromHalVendorKeyValues(parameters);
                    fanoutAidlCallbackLocked((cb, uid) -> {
                        cb.onParametersUpdated(cparam);
                    });
                }
            });
        }
    };

    @VisibleForTesting
    RadioModule(IBroadcastRadio service, RadioManager.ModuleProperties properties) {
        mProperties = Objects.requireNonNull(properties, "properties cannot be null");
        mService = Objects.requireNonNull(service, "service cannot be null");
        mHandler = new Handler(Looper.getMainLooper());
        mLogger = new RadioEventLogger(TAG, RADIO_EVENT_LOGGER_QUEUE_SIZE);
    }

    @Nullable
    static RadioModule tryLoadingModule(int moduleId, String moduleName, IBinder serviceBinder) {
        try {
            Slogf.i(TAG, "Try loading module for module id = %d, module name = %s",
                    moduleId, moduleName);
            IBroadcastRadio service = IBroadcastRadio.Stub
                    .asInterface(serviceBinder);
            if (service == null) {
                Slogf.w(TAG, "Module %s is null", moduleName);
                return null;
            }

            AmFmRegionConfig amfmConfig;
            try {
                amfmConfig = service.getAmFmRegionConfig(/* full= */ false);
            } catch (RuntimeException ex) {
                Slogf.i(TAG, "Module %s does not has AMFM config", moduleName);
                amfmConfig = null;
            }

            DabTableEntry[] dabConfig;
            try {
                dabConfig = service.getDabRegionConfig();
            } catch (RuntimeException ex) {
                Slogf.i(TAG, "Module %s does not has DAB config", moduleName);
                dabConfig = null;
            }

            RadioManager.ModuleProperties prop = ConversionUtils.propertiesFromHalProperties(
                    moduleId, moduleName, service.getProperties(), amfmConfig, dabConfig);

            return new RadioModule(service, prop);
        } catch (RemoteException ex) {
            Slogf.e(TAG, ex, "Failed to load module %s", moduleName);
            return null;
        }
    }

    IBroadcastRadio getService() {
        return mService;
    }

    RadioManager.ModuleProperties getProperties() {
        return mProperties;
    }

    TunerSession openSession(android.hardware.radio.ITunerCallback userCb)
            throws RemoteException {
        mLogger.logRadioEvent("Open TunerSession");
        TunerSession tunerSession;
        Boolean antennaConnected;
        RadioManager.ProgramInfo currentProgramInfo;
        synchronized (mLock) {
            boolean isFirstTunerSession = mAidlTunerSessions.isEmpty();
            tunerSession = new TunerSession(this, mService, userCb);
            mAidlTunerSessions.add(tunerSession);
            antennaConnected = mAntennaConnected;
            currentProgramInfo = mCurrentProgramInfo;
            if (isFirstTunerSession) {
                mService.setTunerCallback(mHalTunerCallback);
            }
        }
        // Propagate state to new client.
        // Note: These callbacks are invoked while holding mLock to prevent race conditions
        // with new callbacks from the HAL.
        if (antennaConnected != null) {
            userCb.onAntennaState(antennaConnected);
        }
        if (currentProgramInfo != null) {
            userCb.onCurrentProgramInfoChanged(currentProgramInfo);
        }

        return tunerSession;
    }

    void closeSessions(int error) {
        mLogger.logRadioEvent("Close TunerSessions %d", error);
        // TunerSession.close() must be called without mAidlTunerSessions locked because
        // it can call onTunerSessionClosed(). Therefore, the contents of mAidlTunerSessions
        // are copied into a local array here.
        TunerSession[] tunerSessions;
        synchronized (mLock) {
            tunerSessions = new TunerSession[mAidlTunerSessions.size()];
            mAidlTunerSessions.toArray(tunerSessions);
        }

        for (TunerSession tunerSession : tunerSessions) {
            try {
                tunerSession.close(error);
            } catch (Exception e) {
                Slogf.e(TAG, "Failed to close TunerSession %s: %s", tunerSession, e);
            }
        }
    }

    @GuardedBy("mLock")
    @Nullable
    private android.hardware.radio.ProgramList.Filter buildUnionOfTunerSessionFiltersLocked() {
        Set<Integer> idTypes = null;
        Set<android.hardware.radio.ProgramSelector.Identifier> ids = null;
        boolean includeCategories = false;
        boolean excludeModifications = true;

        for (int i = 0; i < mAidlTunerSessions.size(); i++) {
            android.hardware.radio.ProgramList.Filter filter =
                    mAidlTunerSessions.valueAt(i).getProgramListFilter();
            if (filter == null) {
                continue;
            }

            if (idTypes == null) {
                idTypes = new ArraySet<>(filter.getIdentifierTypes());
                ids = new ArraySet<>(filter.getIdentifiers());
                includeCategories = filter.areCategoriesIncluded();
                excludeModifications = filter.areModificationsExcluded();
                continue;
            }
            if (!idTypes.isEmpty()) {
                if (filter.getIdentifierTypes().isEmpty()) {
                    idTypes.clear();
                } else {
                    idTypes.addAll(filter.getIdentifierTypes());
                }
            }

            if (!ids.isEmpty()) {
                if (filter.getIdentifiers().isEmpty()) {
                    ids.clear();
                } else {
                    ids.addAll(filter.getIdentifiers());
                }
            }

            includeCategories |= filter.areCategoriesIncluded();
            excludeModifications &= filter.areModificationsExcluded();
        }

        return idTypes == null ? null : new android.hardware.radio.ProgramList.Filter(idTypes, ids,
                includeCategories, excludeModifications);
    }

    void onTunerSessionProgramListFilterChanged(@Nullable TunerSession session) {
        synchronized (mLock) {
            onTunerSessionProgramListFilterChangedLocked(session);
        }
    }

    @GuardedBy("mLock")
    private void onTunerSessionProgramListFilterChangedLocked(@Nullable TunerSession session) {
        android.hardware.radio.ProgramList.Filter newFilter =
                buildUnionOfTunerSessionFiltersLocked();
        if (newFilter == null) {
            // If there are no AIDL clients remaining, we can stop updates from the HAL as well.
            if (mUnionOfAidlProgramFilters == null) {
                return;
            }
            mUnionOfAidlProgramFilters = null;
            try {
                mService.stopProgramListUpdates();
            } catch (RemoteException ex) {
                Slogf.e(TAG, ex, "mHalTunerSession.stopProgramListUpdates() failed");
            }
            return;
        }

        synchronized (mLock) {
            // If the HAL filter doesn't change, we can immediately send an update to the AIDL
            // client.
            if (newFilter.equals(mUnionOfAidlProgramFilters)) {
                if (session != null) {
                    session.updateProgramInfoFromHalCache(mProgramInfoCache);
                }
                return;
            }

            // Otherwise, update the HAL's filter, and AIDL clients will be updated when
            // mHalTunerCallback.onProgramListUpdated() is called.
            mUnionOfAidlProgramFilters = newFilter;
        }
        try {
            mService.startProgramListUpdates(
                    ConversionUtils.filterToHalProgramFilter(newFilter));
        } catch (RuntimeException ex) {
            throw ConversionUtils.throwOnError(ex, /* action= */ "Start Program ListUpdates");
        } catch (RemoteException ex) {
            Slogf.e(TAG, ex, "mHalTunerSession.startProgramListUpdates() failed");
        }
    }

    void onTunerSessionClosed(TunerSession tunerSession) {
        synchronized (mLock) {
            onTunerSessionsClosedLocked(tunerSession);
        }
    }

    @GuardedBy("mLock")
    private void onTunerSessionsClosedLocked(TunerSession... tunerSessions) {
        for (TunerSession tunerSession : tunerSessions) {
            mAidlTunerSessions.remove(tunerSession);
        }
        onTunerSessionProgramListFilterChanged(null);
        if (mAidlTunerSessions.isEmpty()) {
            try {
                mService.unsetTunerCallback();
            } catch (RemoteException ex) {
                Slogf.wtf(TAG, ex, "Failed to unregister HAL callback for module %d",
                        mProperties.getId());
            }
        }
    }

    // add to mHandler queue
    private void fireLater(Runnable r) {
        mHandler.post(() -> r.run());
    }

    interface AidlCallbackRunnable {
        void run(android.hardware.radio.ITunerCallback callback, int uid)
                throws RemoteException;
    }

    // Invokes runnable with each TunerSession currently open.
    void fanoutAidlCallback(AidlCallbackRunnable runnable) {
        fireLater(() -> {
            synchronized (mLock) {
                fanoutAidlCallbackLocked(runnable);
            }
        });
    }

    @GuardedBy("mLock")
    private void fanoutAidlCallbackLocked(AidlCallbackRunnable runnable) {
        int currentUserId = RadioServiceUserController.getCurrentUser();
        List<TunerSession> deadSessions = null;
        for (int i = 0; i < mAidlTunerSessions.size(); i++) {
            if (mAidlTunerSessions.valueAt(i).mUserId != currentUserId
                    && mAidlTunerSessions.valueAt(i).mUserId != UserHandle.USER_SYSTEM) {
                continue;
            }
            try {
                runnable.run(mAidlTunerSessions.valueAt(i).mCallback,
                        mAidlTunerSessions.valueAt(i).getUid());
            } catch (DeadObjectException ex) {
                // The other side died without calling close(), so just purge it from our records.
                Slogf.e(TAG, "Removing dead TunerSession");
                if (deadSessions == null) {
                    deadSessions = new ArrayList<>();
                }
                deadSessions.add(mAidlTunerSessions.valueAt(i));
            } catch (RemoteException ex) {
                Slogf.e(TAG, ex, "Failed to invoke ITunerCallback");
            }
        }
        if (deadSessions != null) {
            onTunerSessionsClosedLocked(deadSessions.toArray(
                    new TunerSession[deadSessions.size()]));
        }
    }

    android.hardware.radio.ICloseHandle addAnnouncementListener(
            android.hardware.radio.IAnnouncementListener listener,
            int[] enabledTypes) throws RemoteException {
        mLogger.logRadioEvent("Add AnnouncementListener");
        byte[] enabledList = new byte[enabledTypes.length];
        for (int index = 0; index < enabledList.length; index++) {
            enabledList[index] = (byte) enabledTypes[index];
        }

        final ICloseHandle[] hwCloseHandle = {null};
        IAnnouncementListener hwListener = new IAnnouncementListener.Stub() {
            public int getInterfaceVersion() {
                return this.VERSION;
            }

            public String getInterfaceHash() {
                return this.HASH;
            }

            public void onListUpdated(Announcement[] hwAnnouncements)
                    throws RemoteException {
                List<android.hardware.radio.Announcement> announcements =
                        new ArrayList<>(hwAnnouncements.length);
                for (int i = 0; i < hwAnnouncements.length; i++) {
                    announcements.add(
                            ConversionUtils.announcementFromHalAnnouncement(hwAnnouncements[i]));
                }
                listener.onListUpdated(announcements);
            }
        };

        try {
            hwCloseHandle[0] = mService.registerAnnouncementListener(hwListener, enabledList);
        } catch (RuntimeException ex) {
            throw ConversionUtils.throwOnError(ex, /* action= */ "AnnouncementListener");
        }

        return new android.hardware.radio.ICloseHandle.Stub() {
            public void close() {
                try {
                    hwCloseHandle[0].close();
                } catch (RemoteException ex) {
                    Slogf.e(TAG, ex, "Failed closing announcement listener");
                }
                hwCloseHandle[0] = null;
            }
        };
    }

    Bitmap getImage(int id) {
        mLogger.logRadioEvent("Get image for id = %d", id);
        if (id == 0) throw new IllegalArgumentException("Image ID is missing");

        byte[] rawImage;
        try {
            rawImage = mService.getImage(id);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }

        if (rawImage == null || rawImage.length == 0) return null;

        return BitmapFactory.decodeByteArray(rawImage, 0, rawImage.length);
    }

    void dumpInfo(IndentingPrintWriter pw) {
        pw.printf("RadioModule\n");

        pw.increaseIndent();
        synchronized (mLock) {
            pw.printf("BroadcastRadioServiceImpl: %s\n", mService);
            pw.printf("Properties: %s\n", mProperties);
            pw.printf("Antenna state: ");
            if (mAntennaConnected == null) {
                pw.printf("undetermined\n");
            } else {
                pw.printf("%s\n", mAntennaConnected ? "connected" : "not connected");
            }
            pw.printf("current ProgramInfo: %s\n", mCurrentProgramInfo);
            pw.printf("ProgramInfoCache: %s\n", mProgramInfoCache);
            pw.printf("Union of AIDL ProgramFilters: %s\n", mUnionOfAidlProgramFilters);
            pw.printf("AIDL TunerSessions [%d]:\n", mAidlTunerSessions.size());

            pw.increaseIndent();
            for (int i = 0; i < mAidlTunerSessions.size(); i++) {
                mAidlTunerSessions.valueAt(i).dumpInfo(pw);
            }
            pw.decreaseIndent();
        }
        pw.printf("Radio module events:\n");

        pw.increaseIndent();
        mLogger.dump(pw);
        pw.decreaseIndent();

        pw.decreaseIndent();
    }
}
