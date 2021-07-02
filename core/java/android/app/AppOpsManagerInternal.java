/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager.AttributionFlags;
import android.content.AttributionSource;
import android.os.IBinder;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.app.IAppOpsCallback;
import com.android.internal.util.function.DecFunction;
import com.android.internal.util.function.HeptFunction;
import com.android.internal.util.function.HexFunction;
import com.android.internal.util.function.QuadFunction;
import com.android.internal.util.function.QuintConsumer;
import com.android.internal.util.function.QuintFunction;
import com.android.internal.util.function.TriFunction;
import com.android.internal.util.function.UndecFunction;

/**
 * App ops service local interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class AppOpsManagerInternal {
    /** Interface to override app ops checks via composition */
    public interface CheckOpsDelegate {
        /**
         * Allows overriding check operation behavior.
         *
         * @param code The op code to check.
         * @param uid The UID for which to check.
         * @param packageName The package for which to check.
         * @param attributionTag The attribution tag for which to check.
         * @param raw Whether to check the raw op i.e. not interpret the mode based on UID state.
         * @param superImpl The super implementation.
         * @return The app op check result.
         */
        int checkOperation(int code, int uid, String packageName, @Nullable String attributionTag,
                boolean raw, QuintFunction<Integer, Integer, String, String, Boolean, Integer>
                superImpl);

        /**
         * Allows overriding check audio operation behavior.
         *
         * @param code The op code to check.
         * @param usage The audio op usage.
         * @param uid The UID for which to check.
         * @param packageName The package for which to check.
         * @param superImpl The super implementation.
         * @return The app op check result.
         */
        int checkAudioOperation(int code, int usage, int uid, String packageName,
                QuadFunction<Integer, Integer, Integer, String, Integer> superImpl);

        /**
         * Allows overriding note operation behavior.
         *
         * @param code The op code to note.
         * @param uid The UID for which to note.
         * @param packageName The package for which to note. {@code null} for system package.
         * @param featureId Id of the feature in the package
         * @param shouldCollectAsyncNotedOp If an {@link AsyncNotedAppOp} should be collected
         * @param message The message in the async noted op
         * @param superImpl The super implementation.
         * @return The app op note result.
         */
        SyncNotedAppOp noteOperation(int code, int uid, @Nullable String packageName,
                @Nullable String featureId, boolean shouldCollectAsyncNotedOp,
                @Nullable String message, boolean shouldCollectMessage,
                @NonNull HeptFunction<Integer, Integer, String, String, Boolean, String, Boolean,
                        SyncNotedAppOp> superImpl);

        /**
         * Allows overriding note proxy operation behavior.
         *
         * @param code The op code to note.
         * @param attributionSource The permission identity of the caller.
         * @param shouldCollectAsyncNotedOp If an {@link AsyncNotedAppOp} should be collected
         * @param message The message in the async noted op
         * @param shouldCollectMessage whether to collect messages
         * @param skipProxyOperation Whether to skip the proxy portion of the operation
         * @param superImpl The super implementation.
         * @return The app op note result.
         */
        SyncNotedAppOp noteProxyOperation(int code, @NonNull AttributionSource attributionSource,
                boolean shouldCollectAsyncNotedOp, @Nullable String message,
                boolean shouldCollectMessage, boolean skipProxyOperation,
                @NonNull HexFunction<Integer, AttributionSource, Boolean, String, Boolean,
                        Boolean, SyncNotedAppOp> superImpl);

        /**
         * Allows overriding start operation behavior.
         *
         * @param token The client state.
         * @param code The op code to start.
         * @param uid The UID for which to note.
         * @param packageName The package for which to note. {@code null} for system package.
         * @param attributionTag the attribution tag.
         * @param startIfModeDefault Whether to start the op of the mode is default.
         * @param shouldCollectAsyncNotedOp If an {@link AsyncNotedAppOp} should be collected
         * @param message The message in the async noted op
         * @param shouldCollectMessage whether to collect messages
         * @param attributionFlags the attribution flags for this operation.
         * @param attributionChainId the unique id of the attribution chain this op is a part of.
         * @param superImpl The super implementation.
         * @return The app op note result.
         */
        SyncNotedAppOp startOperation(IBinder token, int code, int uid,
                @Nullable String packageName, @Nullable String attributionTag,
                boolean startIfModeDefault, boolean shouldCollectAsyncNotedOp,
                @Nullable String message, boolean shouldCollectMessage,
                @AttributionFlags int attributionFlags, int attributionChainId,
                @NonNull UndecFunction<IBinder, Integer, Integer, String, String, Boolean,
                        Boolean, String, Boolean, Integer, Integer, SyncNotedAppOp> superImpl);

        /**
         * Allows overriding start proxy operation behavior.
         *
         * @param code The op code to start.
         * @param attributionSource The permission identity of the caller.
         * @param startIfModeDefault Whether to start the op of the mode is default.
         * @param shouldCollectAsyncNotedOp If an {@link AsyncNotedAppOp} should be collected
         * @param message The message in the async noted op
         * @param shouldCollectMessage whether to collect messages
         * @param skipProxyOperation Whether to skip the proxy portion of the operation
         * @param proxyAttributionFlags The attribution flags for the proxy.
         * @param proxiedAttributionFlags The attribution flags for the proxied.
         * @oaram attributionChainId The id of the attribution chain this operation is a part of.
         * @param superImpl The super implementation.
         * @return The app op note result.
         */
        SyncNotedAppOp startProxyOperation(int code, @NonNull AttributionSource attributionSource,
                boolean startIfModeDefault, boolean shouldCollectAsyncNotedOp, String message,
                boolean shouldCollectMessage, boolean skipProxyOperation, @AttributionFlags
                int proxyAttributionFlags, @AttributionFlags int proxiedAttributionFlags,
                int attributionChainId, @NonNull DecFunction<Integer, AttributionSource, Boolean,
                        Boolean, String, Boolean, Boolean, Integer, Integer, Integer,
                        SyncNotedAppOp> superImpl);

        /**
         * Allows overriding finish op.
         *
         * @param clientId The client state.
         * @param code The op code to finish.
         * @param uid The UID for which the op was noted.
         * @param packageName The package for which it was noted. {@code null} for system package.
         * @param attributionTag the attribution tag.
         */
        default void finishOperation(IBinder clientId, int code, int uid, String packageName,
                String attributionTag,
                @NonNull QuintConsumer<IBinder, Integer, Integer, String, String> superImpl) {
            superImpl.accept(clientId, code, uid, packageName, attributionTag);
        }

        /**
         * Allows overriding finish proxy op.
         *
         * @param code The op code to finish.
         * @param attributionSource The permission identity of the caller.
         */
        void finishProxyOperation(int code, @NonNull AttributionSource attributionSource,
                boolean skipProxyOperation,
                @NonNull TriFunction<Integer, AttributionSource, Boolean, Void> superImpl);
    }

    /**
     * Set the currently configured device and profile owners.  Specifies the package uid (value)
     * that has been configured for each user (key) that has one.  These will be allowed privileged
     * access to app ops for their user.
     */
    public abstract void setDeviceAndProfileOwners(SparseIntArray owners);

    /**
     * Update if the list of AppWidget becomes visible/invisible.
     * @param uidPackageNames uid to packageName map.
     * @param visible true for visible, false for invisible.
     */
    public abstract void updateAppWidgetVisibility(SparseArray<String> uidPackageNames,
            boolean visible);

    /**
     * Like {@link AppOpsManager#setUidMode}, but allows ignoring our own callback and not updating
     * the REVOKED_COMPAT flag.
     */
    public abstract void setUidModeFromPermissionPolicy(int code, int uid, int mode,
            @Nullable IAppOpsCallback callback);

    /**
     * Like {@link AppOpsManager#setMode}, but allows ignoring our own callback and not updating the
     * REVOKED_COMPAT flag.
     */
    public abstract void setModeFromPermissionPolicy(int code, int uid, @NonNull String packageName,
            int mode, @Nullable IAppOpsCallback callback);


    /**
     * Sets a global restriction on an op code.
     */
    public abstract void setGlobalRestriction(int code, boolean restricted, IBinder token);
}
