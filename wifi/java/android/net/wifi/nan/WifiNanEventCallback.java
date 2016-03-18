/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.wifi.nan;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Base class for NAN events callbacks. Should be extended by applications
 * wanting notifications. These are callbacks applying to the NAN connection as
 * a whole - not to specific publish or subscribe sessions - for that see
 * {@link WifiNanSessionCallback}.
 *
 * @hide PROPOSED_NAN_API
 */
public class WifiNanEventCallback {
    @IntDef({
            REASON_INVALID_ARGS, REASON_ALREADY_CONNECTED_INCOMPAT_CONFIG, REASON_REQUESTED,
            REASON_OTHER })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventReasonCodes {
    }

    /**
     * Failure reason flag for {@link WifiNanEventCallback} callbacks. Indicates
     * invalid argument in the requested operation.
     */
    public static final int REASON_INVALID_ARGS = 1000;

    /**
     * Failure reason flag for {@link WifiNanEventCallback} callbacks. Indicates
     * that a {@link ConfigRequest} passed in
     * {@link WifiNanManager#connect(android.os.Looper, WifiNanEventCallback, ConfigRequest)}
     * couldn't be applied since other connections already exist with an
     * incompatible configurations.
     */
    public static final int REASON_ALREADY_CONNECTED_INCOMPAT_CONFIG = 1001;

    /**
     * Reason flag for {@link WifiNanEventCallback#onNanDown(int)} callback.
     * Indicates NAN is shut-down per user request.
     */
    public static final int REASON_REQUESTED = 1002;

    /**
     * Failure reason flag for {@link WifiNanEventCallback} callbacks. Indicates
     * an unspecified error occurred during the operation.
     */
    public static final int REASON_OTHER = 1003;

    /**
     * Called when NAN connect operation
     * {@link WifiNanManager#connect(android.os.Looper, WifiNanEventCallback)}
     * is completed. Doesn't necessarily mean that have joined or started a NAN
     * cluster. An indication is provided by {@link #onIdentityChanged()}.
     */
    public void onConnectSuccess() {
        /* empty */
    }

    /**
     * Called when NAN connect operation
     * {@code WifiNanManager#connect(android.os.Looper, WifiNanEventCallback)}
     * failed.
     *
     * @param reason Failure reason code, see
     *            {@code WifiNanEventCallback.REASON_*}.
     */
    public void onConnectFail(@EventReasonCodes int reason) {
        /* empty */
    }

    /**
     * Called when NAN cluster is down
     *
     * @param reason Reason code for event, see
     *            {@code WifiNanEventCallback.REASON_*}.
     */
    public void onNanDown(@EventReasonCodes int reason) {
        /* empty */
    }

    /**
     * Called when NAN identity has changed. This may be due to joining a
     * cluster, starting a cluster, or discovery interface change. The
     * implication is that peers you've been communicating with may no longer
     * recognize you and you need to re-establish your identity.
     */
    public void onIdentityChanged() {
        /* empty */
    }
}
