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
import android.hardware.broadcastradio.IBroadcastRadio;
import android.hardware.radio.IAnnouncementListener;
import android.hardware.radio.ICloseHandle;
import android.hardware.radio.ITuner;
import android.hardware.radio.ITunerCallback;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioTuner;
import android.os.IBinder;
import android.os.IServiceCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.broadcastradio.RadioServiceUserController;
import com.android.server.utils.Slogf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Broadcast radio service using BroadcastRadio AIDL HAL
 */
public final class BroadcastRadioServiceImpl {
    private static final String TAG = "BcRadioAidlSrv";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private int mNextModuleId;

    @GuardedBy("mLock")
    private final Map<String, Integer> mServiceNameToModuleIdMap = new ArrayMap<>();

    // Map from module ID to RadioModule created by mServiceListener.onRegistration().
    @GuardedBy("mLock")
    private final SparseArray<RadioModule> mModules = new SparseArray<>();

    private final IServiceCallback.Stub mServiceListener = new IServiceCallback.Stub() {
        @Override
        public void onRegistration(String name, final IBinder newBinder) {
            Slogf.i(TAG, "onRegistration for %s", name);
            Integer moduleId;
            synchronized (mLock) {
                // If the service has been registered before, reuse its previous module ID.
                moduleId = mServiceNameToModuleIdMap.get(name);
                boolean newService = false;
                if (moduleId == null) {
                    newService = true;
                    moduleId = mNextModuleId;
                }

                RadioModule radioModule =
                        RadioModule.tryLoadingModule(moduleId, name, newBinder);
                if (radioModule == null) {
                    Slogf.w(TAG, "No module %s with id %d (HAL AIDL)", name, moduleId);
                    return;
                }
                if (DEBUG) {
                    Slogf.d(TAG, "Loaded broadcast radio module %s with id %d (HAL AIDL)",
                            name, moduleId);
                }
                RadioModule prevModule = mModules.get(moduleId);
                mModules.put(moduleId, radioModule);
                if (prevModule != null) {
                    prevModule.closeSessions(RadioTuner.ERROR_HARDWARE_FAILURE);
                }

                if (newService) {
                    mServiceNameToModuleIdMap.put(name, moduleId);
                    mNextModuleId++;
                }

                try {
                    BroadcastRadioDeathRecipient deathRecipient =
                            new BroadcastRadioDeathRecipient(moduleId);
                    radioModule.getService().asBinder().linkToDeath(deathRecipient, moduleId);
                } catch (RemoteException ex) {
                    Slogf.w(TAG, "Service has already died, so remove its entry from mModules.");
                    mModules.remove(moduleId);
                }
            }
        }
    };

    private final class BroadcastRadioDeathRecipient implements IBinder.DeathRecipient {
        private final int mModuleId;

        BroadcastRadioDeathRecipient(int moduleId) {
            mModuleId = moduleId;
        }

        @Override
        public void binderDied() {
            Slogf.i(TAG, "ServiceDied for module id %d", mModuleId);
            synchronized (mLock) {
                RadioModule prevModule = mModules.removeReturnOld(mModuleId);
                if (prevModule != null) {
                    prevModule.closeSessions(RadioTuner.ERROR_HARDWARE_FAILURE);
                }

                for (Map.Entry<String, Integer> entry : mServiceNameToModuleIdMap.entrySet()) {
                    if (entry.getValue() == mModuleId) {
                        Slogf.w(TAG, "Service %s died, removed RadioModule with ID %d",
                                entry.getKey(), mModuleId);
                        return;
                    }
                }
            }
        }
    };

    /**
     * Constructs BroadcastRadioServiceImpl using AIDL HAL using the list of names of AIDL
     * BroadcastRadio HAL services {@code serviceNameList}
     */
    public BroadcastRadioServiceImpl(ArrayList<String> serviceNameList) {
        mNextModuleId = 0;
        if (DEBUG) {
            Slogf.d(TAG, "Initializing BroadcastRadioServiceImpl %s", IBroadcastRadio.DESCRIPTOR);
        }
        for (int i = 0; i < serviceNameList.size(); i++) {
            try {
                ServiceManager.registerForNotifications(serviceNameList.get(i), mServiceListener);
            } catch (RemoteException ex) {
                Slogf.e(TAG, ex, "failed to register for service notifications for service %s",
                        serviceNameList.get(i));
            }
        }
    }

    /**
     * Gets all AIDL {@link com.android.server.broadcastradio.aidl.RadioModule}.
     */
    public List<RadioManager.ModuleProperties> listModules() {
        synchronized (mLock) {
            List<RadioManager.ModuleProperties> moduleList = new ArrayList<>(mModules.size());
            for (int i = 0; i < mModules.size(); i++) {
                moduleList.add(mModules.valueAt(i).getProperties());
            }
            return moduleList;
        }
    }

    /**
     * Gets the AIDL RadioModule for the given {@code moduleId}. Null will be returned if not found.
     */
    public boolean hasModule(int id) {
        synchronized (mLock) {
            return mModules.contains(id);
        }
    }

    /**
     * Returns whether any AIDL {@link com.android.server.broadcastradio.aidl.RadioModule} exists.
     */
    public boolean hasAnyModules() {
        synchronized (mLock) {
            return mModules.size() != 0;
        }
    }

    /**
     * Opens {@link ITuner} session for the AIDL
     * {@link com.android.server.broadcastradio.aidl.RadioModule} given {@code moduleId}.
     */
    @Nullable
    public ITuner openSession(int moduleId, @Nullable RadioManager.BandConfig legacyConfig,
            boolean withAudio, ITunerCallback callback) throws RemoteException {
        if (DEBUG) {
            Slogf.d(TAG, "Open AIDL radio session");
        }
        if (!RadioServiceUserController.isCurrentOrSystemUser()) {
            Slogf.e(TAG, "Cannot open tuner on AIDL HAL client for non-current user");
            throw new IllegalStateException("Cannot open session for non-current user");
        }
        Objects.requireNonNull(callback);

        if (!withAudio) {
            throw new IllegalArgumentException("Non-audio sessions not supported with AIDL HAL");
        }

        RadioModule radioModule;
        synchronized (mLock) {
            radioModule = mModules.get(moduleId);
            if (radioModule == null) {
                Slogf.e(TAG, "Invalid module ID %d", moduleId);
                return null;
            }
        }

        TunerSession tunerSession = radioModule.openSession(callback);
        if (legacyConfig != null) {
            tunerSession.setConfiguration(legacyConfig);
        }
        return tunerSession;
    }

    /**
     * Adds AnnouncementListener for every
     * {@link com.android.server.broadcastradio.aidl.RadioModule}.
     */
    public ICloseHandle addAnnouncementListener(int[] enabledTypes,
            IAnnouncementListener listener) {
        if (DEBUG) {
            Slogf.d(TAG, "Add AnnouncementListener with enable types %s",
                    Arrays.toString(enabledTypes));
        }
        AnnouncementAggregator aggregator = new AnnouncementAggregator(listener, mLock);
        boolean anySupported = false;
        synchronized (mLock) {
            for (int i = 0; i < mModules.size(); i++) {
                try {
                    aggregator.watchModule(mModules.valueAt(i), enabledTypes);
                    anySupported = true;
                } catch (UnsupportedOperationException ex) {
                    Slogf.w(TAG, ex, "Announcements not supported for this module");
                }
            }
        }
        if (!anySupported) {
            Slogf.w(TAG, "There are no HAL modules that support announcements");
        }
        return aggregator;
    }

    /**
     * Dump state of broadcastradio service for AIDL HAL.
     *
     * @param pw The file to which {@link BroadcastRadioServiceImpl} state is dumped.
     */
    public void dumpInfo(IndentingPrintWriter pw) {
        synchronized (mLock) {
            pw.printf("Next module id available: %d\n", mNextModuleId);
            pw.printf("ServiceName to module id map:\n");

            pw.increaseIndent();
            for (Map.Entry<String, Integer> entry : mServiceNameToModuleIdMap.entrySet()) {
                pw.printf("Service name: %s, module id: %d\n", entry.getKey(), entry.getValue());
            }
            pw.decreaseIndent();

            pw.printf("Radio modules [%d]:\n", mModules.size());

            pw.increaseIndent();
            for (int i = 0; i < mModules.size(); i++) {
                pw.printf("Module id=%d:\n", mModules.keyAt(i));

                pw.increaseIndent();
                mModules.valueAt(i).dumpInfo(pw);
                pw.decreaseIndent();
            }
            pw.decreaseIndent();
        }
    }
}
