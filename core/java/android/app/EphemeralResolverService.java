/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.app;

import android.annotation.SystemApi;
import android.app.Service;
import android.app.InstantAppResolverService.InstantAppResolutionCallback;
import android.content.Context;
import android.content.Intent;
import android.content.pm.EphemeralResolveInfo;
import android.content.pm.InstantAppResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Base class for implementing the resolver service.
 * @hide
 * @removed
 * @deprecated use InstantAppResolverService instead
 */
@Deprecated
@SystemApi
public abstract class EphemeralResolverService extends InstantAppResolverService {
    private static final boolean DEBUG_EPHEMERAL = Build.IS_DEBUGGABLE;
    private static final String TAG = "PackageManager";

    /**
     * Called to retrieve resolve info for ephemeral applications.
     *
     * @param digestPrefix The hash prefix of the ephemeral's domain.
     * @param prefixMask A mask that was applied to each digest prefix. This should
     *      be used when comparing against the digest prefixes as all bits might
     *      not be set.
     * @deprecated use {@link #onGetEphemeralResolveInfo(int[])} instead
     */
    @Deprecated
    public abstract List<EphemeralResolveInfo> onEphemeralResolveInfoList(
            int digestPrefix[], int prefix);

    /**
     * Called to retrieve resolve info for ephemeral applications.
     *
     * @param digestPrefix The hash prefix of the ephemeral's domain.
     */
    public List<EphemeralResolveInfo> onGetEphemeralResolveInfo(int digestPrefix[]) {
        return onEphemeralResolveInfoList(digestPrefix, 0xFFFFF000);
    }

    /**
     * Called to retrieve intent filters for ephemeral applications.
     *
     * @param hostName The name of the host to get intent filters for.
     */
    public EphemeralResolveInfo onGetEphemeralIntentFilter(String hostName) {
        throw new IllegalStateException("Must define");
    }

    @Override
    public Looper getLooper() {
        return super.getLooper();
    }

    @Override
    void _onGetInstantAppResolveInfo(int[] digestPrefix, String token,
            InstantAppResolutionCallback callback) {
        if (DEBUG_EPHEMERAL) {
            Log.d(TAG, "Legacy resolver; getInstantAppResolveInfo;"
                    + " prefix: " + Arrays.toString(digestPrefix));
        }
        final List<EphemeralResolveInfo> response = onGetEphemeralResolveInfo(digestPrefix);
        final int responseSize = response == null ? 0 : response.size();
        final List<InstantAppResolveInfo> resultList = new ArrayList<>(responseSize);
        for (int i = 0; i < responseSize; i++) {
            resultList.add(response.get(i).getInstantAppResolveInfo());
        }
        callback.onInstantAppResolveInfo(resultList);
    }

    @Override
    void _onGetInstantAppIntentFilter(int[] digestPrefix, String token,
            String hostName, InstantAppResolutionCallback callback) {
        if (DEBUG_EPHEMERAL) {
            Log.d(TAG, "Legacy resolver; getInstantAppIntentFilter;"
                    + " prefix: " + Arrays.toString(digestPrefix));
        }
        final EphemeralResolveInfo response = onGetEphemeralIntentFilter(hostName);
        final List<InstantAppResolveInfo> resultList = new ArrayList<>(1);
        resultList.add(response.getInstantAppResolveInfo());
        callback.onInstantAppResolveInfo(resultList);
    }
}
