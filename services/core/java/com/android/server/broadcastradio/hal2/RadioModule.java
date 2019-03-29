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
import android.graphics.BitmapFactory;
import android.hardware.broadcastradio.V2_0.AmFmRegionConfig;
import android.hardware.broadcastradio.V2_0.Announcement;
import android.hardware.broadcastradio.V2_0.DabTableEntry;
import android.hardware.broadcastradio.V2_0.IAnnouncementListener;
import android.hardware.broadcastradio.V2_0.IBroadcastRadio;
import android.hardware.broadcastradio.V2_0.ICloseHandle;
import android.hardware.broadcastradio.V2_0.ITunerSession;
import android.hardware.broadcastradio.V2_0.Result;
import android.hardware.radio.RadioManager;
import android.os.RemoteException;
import android.util.MutableInt;
import android.util.Slog;

import java.util.ArrayList;
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
            IBroadcastRadio service = IBroadcastRadio.getService(fqName);
            if (service == null) return null;

            Mutable<AmFmRegionConfig> amfmConfig = new Mutable<>();
            service.getAmFmRegionConfig(false, (result, config) -> {
                if (result == Result.OK) amfmConfig.value = config;
            });

            Mutable<List<DabTableEntry>> dabConfig = new Mutable<>();
            service.getDabRegionConfig((result, config) -> {
                if (result == Result.OK) dabConfig.value = config;
            });

            RadioManager.ModuleProperties prop = Convert.propertiesFromHal(idx, fqName,
                    service.getProperties(), amfmConfig.value, dabConfig.value);

            return new RadioModule(service, prop);
        } catch (RemoteException ex) {
            Slog.e(TAG, "failed to load module " + fqName, ex);
            return null;
        }
    }

    public @NonNull IBroadcastRadio getService() {
        return mService;
    }

    public @NonNull TunerSession openSession(@NonNull android.hardware.radio.ITunerCallback userCb)
            throws RemoteException {
        TunerCallback cb = new TunerCallback(Objects.requireNonNull(userCb));
        Mutable<ITunerSession> hwSession = new Mutable<>();
        MutableInt halResult = new MutableInt(Result.UNKNOWN_ERROR);

        synchronized (mService) {
            mService.openSession(cb, (result, session) -> {
                hwSession.value = session;
                halResult.value = result;
            });
        }

        Convert.throwOnError("openSession", halResult.value);
        Objects.requireNonNull(hwSession.value);

        return new TunerSession(this, hwSession.value, cb);
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

        synchronized (mService) {
            mService.registerAnnouncementListener(enabledList, hwListener, (result, closeHnd) -> {
                halResult.value = result;
                hwCloseHandle.value = closeHnd;
            });
        }
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

    Bitmap getImage(int id) {
        if (id == 0) throw new IllegalArgumentException("Image ID is missing");

        byte[] rawImage;
        synchronized (mService) {
            List<Byte> rawList = Utils.maybeRethrow(() -> mService.getImage(id));
            rawImage = new byte[rawList.size()];
            for (int i = 0; i < rawList.size(); i++) {
                rawImage[i] = rawList.get(i);
            }
        }

        if (rawImage == null || rawImage.length == 0) return null;

        return BitmapFactory.decodeByteArray(rawImage, 0, rawImage.length);
    }
}
