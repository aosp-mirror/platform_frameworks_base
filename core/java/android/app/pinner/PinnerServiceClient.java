/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.app.pinner;

import static android.app.Flags.FLAG_PINNER_SERVICE_CLIENT_API;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.app.pinner.IPinnerService;
import android.app.pinner.PinnedFileStat;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;

/**
 * Expose PinnerService as an interface to apps.
 * @hide
 */
@TestApi
@FlaggedApi(FLAG_PINNER_SERVICE_CLIENT_API)
public class PinnerServiceClient {
    private static String TAG = "PinnerServiceClient";
    /**
     * @hide
     */
    @TestApi
    @FlaggedApi(FLAG_PINNER_SERVICE_CLIENT_API)
    public PinnerServiceClient() {}

    /**
     * Obtain the pinned file stats used for testing infrastructure.
     * @return List of pinned files or an empty list if failed to retrieve them.
     * @throws RuntimeException on failure to retrieve stats.
     * @hide
     */
    @TestApi
    @FlaggedApi(FLAG_PINNER_SERVICE_CLIENT_API)
    public @NonNull List<PinnedFileStat> getPinnerStats() {
        IBinder binder = ServiceManager.getService("pinner");
        if (binder == null) {
            Slog.w(TAG,
                    "Failed to retrieve PinnerService. A common failure reason is due to a lack of selinux permissions.");
            return new ArrayList<>();
        }
        IPinnerService pinnerService = IPinnerService.Stub.asInterface(binder);
        if (pinnerService == null) {
            Slog.w(TAG, "Failed to cast PinnerService.");
            return new ArrayList<>();
        }
        List<PinnedFileStat> stats;
        try {
            stats = pinnerService.getPinnerStats();
        } catch (RemoteException e) {
            throw new RuntimeException("Failed to retrieve stats from PinnerService");
        }
        return stats;
    }
}
