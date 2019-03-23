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
import android.hardware.broadcastradio.V2_0.IBroadcastRadio;
import android.hardware.radio.IAnnouncementListener;
import android.hardware.radio.ICloseHandle;
import android.hardware.radio.ITuner;
import android.hardware.radio.ITunerCallback;
import android.hardware.radio.RadioManager;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.IHwBinder.DeathRecipient;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class BroadcastRadioService {
    private static final String TAG = "BcRadio2Srv";

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private int mNextModuleId = 0;

    @GuardedBy("mLock")
    private final Map<String, Integer> mServiceNameToModuleIdMap = new HashMap<>();

    @GuardedBy("mLock")
    private final Map<Integer, RadioModule> mModules = new HashMap<>();

    private IServiceNotification.Stub mServiceListener = new IServiceNotification.Stub() {
        @Override
        public void onRegistration(String fqName, String serviceName, boolean preexisting) {
            Slog.v(TAG, "onRegistration(" + fqName + ", " + serviceName + ", " + preexisting + ")");
            Integer moduleId;
            synchronized (mLock) {
                // If the service has been registered before, reuse its previous module ID.
                moduleId = mServiceNameToModuleIdMap.get(serviceName);
                boolean newService = false;
                if (moduleId == null) {
                    newService = true;
                    moduleId = mNextModuleId;
                }

                RadioModule module = RadioModule.tryLoadingModule(moduleId, serviceName);
                if (module == null) {
                    return;
                }
                Slog.v(TAG, "loaded broadcast radio module " + moduleId + ": " + serviceName
                        + " (HAL 2.0)");
                mModules.put(moduleId, module);

                if (newService) {
                    mServiceNameToModuleIdMap.put(serviceName, moduleId);
                    mNextModuleId++;
                }

                try {
                    module.getService().linkToDeath(mDeathRecipient, moduleId);
                } catch (RemoteException ex) {
                    // Service has already died, so remove its entry from mModules.
                    mModules.remove(moduleId);
                }
            }
        }
    };

    private DeathRecipient mDeathRecipient = new DeathRecipient() {
        @Override
        public void serviceDied(long cookie) {
            Slog.v(TAG, "serviceDied(" + cookie + ")");
            synchronized (mLock) {
                int moduleId = (int) cookie;
                mModules.remove(moduleId);

                for (Map.Entry<String, Integer> entry : mServiceNameToModuleIdMap.entrySet()) {
                    if (entry.getValue() == moduleId) {
                        Slog.i(TAG, "service " + entry.getKey()
                                + " died; removed RadioModule with ID " + moduleId);
                        return;
                    }
                }
            }
        }
    };

    public BroadcastRadioService(int nextModuleId) {
        mNextModuleId = nextModuleId;
        try {
            IServiceManager manager = IServiceManager.getService();
            if (manager == null) {
                Slog.e(TAG, "failed to get HIDL Service Manager");
                return;
            }
            manager.registerForNotifications(IBroadcastRadio.kInterfaceName, "", mServiceListener);
        } catch (RemoteException ex) {
            Slog.e(TAG, "failed to register for service notifications: ", ex);
        }
    }

    public @NonNull Collection<RadioManager.ModuleProperties> listModules() {
        synchronized (mLock) {
            return mModules.values().stream().map(module -> module.mProperties)
                    .collect(Collectors.toList());
        }
    }

    public boolean hasModule(int id) {
        synchronized (mLock) {
            return mModules.containsKey(id);
        }
    }

    public boolean hasAnyModules() {
        synchronized (mLock) {
            return !mModules.isEmpty();
        }
    }

    public ITuner openSession(int moduleId, @Nullable RadioManager.BandConfig legacyConfig,
        boolean withAudio, @NonNull ITunerCallback callback) throws RemoteException {
        Objects.requireNonNull(callback);

        if (!withAudio) {
            throw new IllegalArgumentException("Non-audio sessions not supported with HAL 2.x");
        }

        RadioModule module = null;
        synchronized (mLock) {
            module = mModules.get(moduleId);
        }
        if (module == null) {
            throw new IllegalArgumentException("Invalid module ID");
        }

        TunerSession session = module.openSession(callback);
        if (legacyConfig != null) {
            session.setConfiguration(legacyConfig);
        }
        return session;
    }

    public ICloseHandle addAnnouncementListener(@NonNull int[] enabledTypes,
            @NonNull IAnnouncementListener listener) {
        AnnouncementAggregator aggregator = new AnnouncementAggregator(listener);
        boolean anySupported = false;
        synchronized (mLock) {
            for (RadioModule module : mModules.values()) {
                try {
                    aggregator.watchModule(module, enabledTypes);
                    anySupported = true;
                } catch (UnsupportedOperationException ex) {
                    Slog.v(TAG, "Announcements not supported for this module", ex);
                }
            }
        }
        if (!anySupported) {
            Slog.i(TAG, "There are no HAL modules that support announcements");
        }
        return aggregator;
    }
}
