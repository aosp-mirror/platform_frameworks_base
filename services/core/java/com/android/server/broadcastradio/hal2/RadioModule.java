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
import android.hardware.radio.ITuner;
import android.hardware.radio.RadioManager;
import android.hardware.broadcastradio.V2_0.AmFmRegionConfig;
import android.hardware.broadcastradio.V2_0.Announcement;
import android.hardware.broadcastradio.V2_0.IAnnouncementListener;
import android.hardware.broadcastradio.V2_0.IBroadcastRadio;
import android.hardware.broadcastradio.V2_0.ICloseHandle;
import android.hardware.broadcastradio.V2_0.ITunerSession;
import android.hardware.broadcastradio.V2_0.Result;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.util.MutableInt;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

class RadioModule {
    private static final String TAG = "BcRadio2Srv.module";

    @NonNull private final IBroadcastRadio mService;
    @NonNull public final RadioManager.ModuleProperties mProperties;

    private RadioModule(@NonNull IBroadcastRadio service,
            @NonNull RadioManager.ModuleProperties properties) {
        mProperties = Objects.requireNonNull(properties);
        mService = Objects.requireNonNull(service);
    }

    public static @Nullable RadioModule tryLoadingModule(int idx, @NonNull String fqName) {
        try {
            IBroadcastRadio service = IBroadcastRadio.getService();
            if (service == null) return null;

            Mutable<AmFmRegionConfig> amfmConfig = new Mutable<>();
            service.getAmFmRegionConfig(false, (int result, AmFmRegionConfig config) -> {
                if (result == Result.OK) amfmConfig.value = config;
            });

            RadioManager.ModuleProperties prop =
                    Convert.propertiesFromHal(idx, fqName, service.getProperties(), amfmConfig.value);

            return new RadioModule(service, prop);
        } catch (RemoteException ex) {
            Slog.e(TAG, "failed to load module " + fqName, ex);
            return null;
        }
    }

    public @NonNull TunerSession openSession(@NonNull android.hardware.radio.ITunerCallback userCb)
            throws RemoteException {
        TunerCallback cb = new TunerCallback(Objects.requireNonNull(userCb));
        Mutable<ITunerSession> hwSession = new Mutable<>();
        MutableInt halResult = new MutableInt(Result.UNKNOWN_ERROR);

        mService.openSession(cb, (int result, ITunerSession session) -> {
            hwSession.value = session;
            halResult.value = result;
        });

        Convert.throwOnError("openSession", halResult.value);
        Objects.requireNonNull(hwSession.value);

        return new TunerSession(hwSession.value, cb);
    }

    public android.hardware.radio.ICloseHandle addAnnouncementListener(@NonNull int[] enabledTypes,
            @NonNull android.hardware.radio.IAnnouncementListener listener) throws RemoteException {
        ArrayList<Byte> enabledList = new ArrayList<>();
        for (int type : enabledTypes) {
            enabledList.add((byte)type);
        }

        MutableInt halResult = new MutableInt(Result.UNKNOWN_ERROR);
        Mutable<ICloseHandle> hwCloseHandle = new Mutable<>();
        IAnnouncementListener hwListener = new IAnnouncementListener.Stub() {
            public void onListUpdated(ArrayList<Announcement> hwAnnouncements)
                    throws RemoteException {
                listener.onListUpdated(hwAnnouncements.stream().
                    map(a -> Convert.announcementFromHal(a)).collect(Collectors.toList()));
            }
        };
        mService.registerAnnouncementListener(enabledList, hwListener, (result, closeHandle) -> {
            halResult.value = result;
            hwCloseHandle.value = closeHandle;
        });
        Convert.throwOnError("addAnnouncementListener", halResult.value);

        return new android.hardware.radio.ICloseHandle.Stub() {
            public void close() {
                try {
                    hwCloseHandle.value.close();
                } catch (RemoteException ex) {
                    Slog.e(TAG, "Failed closing announcement listener", ex);
                }
            }
        };
    }
}
