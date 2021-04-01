/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.policy;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.AppOpsManagerInternal;
import android.app.SyncNotedAppOp;
import android.content.AttributionSource;
import android.location.LocationManagerInternal;
import android.os.IBinder;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.function.HeptFunction;
import com.android.internal.util.function.HexFunction;
import com.android.internal.util.function.OctFunction;
import com.android.internal.util.function.QuadFunction;
import com.android.internal.util.function.TriFunction;
import com.android.server.LocalServices;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class defines policy for special behaviors around app ops.
 */
public final class AppOpsPolicy implements AppOpsManagerInternal.CheckOpsDelegate {
    @NonNull
    private final Object mLock = new Object();

    /**
     * The locking policy around the location tags is a bit special. Since we want to
     * avoid grabbing the lock on every op note we are taking the approach where the
     * read and write are being done via a thread-safe data structure such that the
     * lookup/insert are single thread-safe calls. When we update the cached state we
     * use a lock to ensure the update's lookup and store calls are done atomically,
     * so multiple writers would not interleave. The tradeoff is we make is that the
     * concurrent data structure would use boxing/unboxing of integers but this is
     * preferred to locking.
     */
    @GuardedBy("mLock - writes only - see above")
    @NonNull
    private final ConcurrentHashMap<Integer, ArrayMap<String, ArraySet<String>>> mLocationTags =
            new ConcurrentHashMap<>();

    public AppOpsPolicy() {
        final LocationManagerInternal locationManagerInternal = LocalServices.getService(
                LocationManagerInternal.class);
        locationManagerInternal.setOnProviderLocationTagsChangeListener((providerTagInfo) -> {
            synchronized (mLock) {
                final int uid = providerTagInfo.getUid();
                // We make a copy of the per UID state to limit our mutation to one
                // operation in the underlying concurrent data structure.
                ArrayMap<String, ArraySet<String>> uidTags = mLocationTags.get(uid);
                if (uidTags != null) {
                    uidTags = new ArrayMap<>(uidTags);
                }

                final String packageName = providerTagInfo.getPackageName();
                ArraySet<String> packageTags = (uidTags != null) ? uidTags.get(packageName) : null;
                if (packageTags != null) {
                    packageTags = new ArraySet<>(packageTags);
                }

                final Set<String> providerTags = providerTagInfo.getTags();
                if (providerTags != null && !providerTags.isEmpty()) {
                    if (packageTags != null) {
                        packageTags.clear();
                        packageTags.addAll(providerTags);
                    } else {
                        packageTags = new ArraySet<>(providerTags);
                    }
                    if (uidTags == null) {
                        uidTags = new ArrayMap<>();
                    }
                    uidTags.put(packageName, packageTags);
                    mLocationTags.put(uid, uidTags);
                } else if (uidTags != null) {
                    uidTags.remove(packageName);
                    if (!uidTags.isEmpty()) {
                        mLocationTags.put(uid, uidTags);
                    } else {
                        mLocationTags.remove(uid);
                    }
                }
            }
        });
    }

    @Override
    public int checkOperation(int code, int uid, String packageName, boolean raw,
            QuadFunction<Integer, Integer, String, Boolean, Integer> superImpl) {
        return superImpl.apply(code, uid, packageName, raw);
    }

    @Override
    public int checkAudioOperation(int code, int usage, int uid, String packageName,
            QuadFunction<Integer, Integer, Integer, String, Integer> superImpl) {
        return superImpl.apply(code, usage, uid, packageName);
    }

    @Override
    public SyncNotedAppOp noteOperation(int code, int uid, @Nullable String packageName,
            @Nullable String attributionTag, boolean shouldCollectAsyncNotedOp, @Nullable
            String message, boolean shouldCollectMessage, @NonNull HeptFunction<Integer, Integer,
                    String, String, Boolean, String, Boolean, SyncNotedAppOp> superImpl) {
        return superImpl.apply(resolveOpCode(code, uid, packageName, attributionTag), uid,
                packageName, attributionTag, shouldCollectAsyncNotedOp,
                message, shouldCollectMessage);
    }

    @Override
    public SyncNotedAppOp noteProxyOperation(int code, @NonNull AttributionSource attributionSource,
            boolean shouldCollectAsyncNotedOp, @Nullable String message,
            boolean shouldCollectMessage, boolean skipProxyOperation, @NonNull HexFunction<Integer,
                    AttributionSource, Boolean, String, Boolean, Boolean,
            SyncNotedAppOp> superImpl) {
        return superImpl.apply(resolveOpCode(code, attributionSource.getUid(),
                attributionSource.getPackageName(), attributionSource.getAttributionTag()),
                attributionSource, shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                skipProxyOperation);
    }

    @Override
    public SyncNotedAppOp startProxyOperation(IBinder token, int code,
            @NonNull AttributionSource attributionSource, boolean startIfModeDefault,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            boolean skipProxyOperation, @NonNull OctFunction<IBinder, Integer, AttributionSource,
                    Boolean, Boolean, String, Boolean, Boolean, SyncNotedAppOp> superImpl) {
        return superImpl.apply(token, resolveOpCode(code, attributionSource.getUid(),
                attributionSource.getPackageName(), attributionSource.getAttributionTag()),
                attributionSource, startIfModeDefault, shouldCollectAsyncNotedOp, message,
                shouldCollectMessage, skipProxyOperation);
    }

    @Override
    public void finishProxyOperation(IBinder clientId, int code,
            @NonNull AttributionSource attributionSource,
            @NonNull TriFunction<IBinder, Integer, AttributionSource, Void> superImpl) {
        superImpl.apply(clientId, resolveOpCode(code, attributionSource.getUid(),
                attributionSource.getPackageName(), attributionSource.getAttributionTag()),
                attributionSource);
    }

    private int resolveOpCode(int code, int uid, @NonNull String packageName,
            @Nullable String attributionTag) {
        if (isHandledOp(code) && attributionTag != null) {
            // Only a single lookup from the underlying concurrent data structure
            final ArrayMap<String, ArraySet<String>> uidTags = mLocationTags.get(uid);
            if (uidTags != null) {
                final ArraySet<String> packageTags = uidTags.get(packageName);
                if (packageTags != null && packageTags.contains(attributionTag)) {
                    return resolveHandledOp(code);
                }
            }
        }
        return code;
    }

    private static boolean isHandledOp(int code) {
        switch (code) {
            case AppOpsManager.OP_FINE_LOCATION:
            case AppOpsManager.OP_COARSE_LOCATION:
                return true;
        }
        return false;
    }

    private static int resolveHandledOp(int code) {
        switch (code) {
            case AppOpsManager.OP_FINE_LOCATION:
                return AppOpsManager.OP_FINE_LOCATION_SOURCE;
            case AppOpsManager.OP_COARSE_LOCATION:
                return AppOpsManager.OP_COARSE_LOCATION_SOURCE;
        }
        return code;
    }
}
