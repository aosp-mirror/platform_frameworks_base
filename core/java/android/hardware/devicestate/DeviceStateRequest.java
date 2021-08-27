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

package android.hardware.devicestate;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.TestApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * A request to alter the state of the device managed by {@link DeviceStateManager}.
 * <p>
 * Once constructed, a {@link DeviceStateRequest request} can be submitted with a call to
 * {@link DeviceStateManager#requestState(DeviceStateRequest, Executor,
 * DeviceStateRequest.Callback)}.
 * <p>
 * By default, the request is kept active until a call to
 * {@link DeviceStateManager#cancelRequest(DeviceStateRequest)} or until one of the following
 * occurs:
 * <ul>
 *     <li>Another processes submits a request succeeding this request in which case the request
 *     will be suspended until the interrupting request is canceled.
 *     <li>The requested state has become unsupported.
 *     <li>The process submitting the request dies.
 * </ul>
 * However, this behavior can be changed by setting flags on the request. For example, the
 * {@link #FLAG_CANCEL_WHEN_BASE_CHANGES} flag will extend this behavior to also cancel the
 * request whenever the base (non-override) device state changes.
 *
 * @see DeviceStateManager
 *
 * @hide
 */
@TestApi
public final class DeviceStateRequest {
    /**
     * Flag that indicates the request should be canceled automatically when the base
     * (non-override) device state changes. Useful when the requestor only wants the request to
     * remain active while the base state remains constant and automatically cancel when the user
     * manipulates the device into a different state.
     */
    public static final int FLAG_CANCEL_WHEN_BASE_CHANGES = 1 << 0;

    /** @hide */
    @IntDef(prefix = {"FLAG_"}, flag = true, value = {
            FLAG_CANCEL_WHEN_BASE_CHANGES,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RequestFlags {}

    /**
     * Creates a new {@link Builder} for a {@link DeviceStateRequest}. Must be one of the supported
     * states for the device which can be queried with a call to
     * {@link DeviceStateManager#getSupportedStates()}.
     *
     * @param requestedState the device state being requested.
     */
    @NonNull
    public static Builder newBuilder(int requestedState) {
        return new Builder(requestedState);
    }

    /**
     * Builder for {@link DeviceStateRequest}. An instance can be obtained through
     * {@link #newBuilder(int)}.
     */
    public static final class Builder {
        private final int mRequestedState;
        private int mFlags;

        private Builder(int requestedState) {
            mRequestedState = requestedState;
        }

        /**
         * Sets the flag bits provided within {@code flags} with all other bits remaining
         * unchanged.
         */
        @NonNull
        public Builder setFlags(@RequestFlags int flags) {
            mFlags |= flags;
            return this;
        }

        /**
         * Returns a new {@link DeviceStateRequest} object whose state matches the state set on the
         * builder.
         */
        @NonNull
        public DeviceStateRequest build() {
            return new DeviceStateRequest(mRequestedState, mFlags);
        }
    }

    /** Callback to track the status of a request. */
    public interface Callback {
        /**
         * Called to indicate the request has become active and the device state will match the
         * requested state.
         * <p>
         * Guaranteed to be called after a call to
         * {@link DeviceStateManager.DeviceStateCallback#onStateChanged(int)} with a state
         * matching the requested state.
         */
        default void onRequestActivated(@NonNull DeviceStateRequest request) {}

        /**
         * Called to indicate the request has been temporarily suspended.
         * <p>
         * Guaranteed to be called before a call to
         * {@link DeviceStateManager.DeviceStateCallback#onStateChanged(int)}.
         */
        default void onRequestSuspended(@NonNull DeviceStateRequest request) {}

        /**
         * Called to indicate the request has been canceled. The request can be resubmitted with
         * another call to {@link DeviceStateManager#requestState(DeviceStateRequest, Executor,
         * DeviceStateRequest.Callback)}.
         * <p>
         * Guaranteed to be called before a call to
         * {@link DeviceStateManager.DeviceStateCallback#onStateChanged(int)}.
         * <p>
         * Note: A call to {@link #onRequestSuspended(DeviceStateRequest)} is not guaranteed to
         * occur before this method.
         */
        default void onRequestCanceled(@NonNull DeviceStateRequest request) {}
    }

    private final int mRequestedState;
    @RequestFlags
    private final int mFlags;

    private DeviceStateRequest(int requestedState, @RequestFlags int flags) {
        mRequestedState = requestedState;
        mFlags = flags;
    }

    public int getState() {
        return mRequestedState;
    }

    @RequestFlags
    public int getFlags() {
        return mFlags;
    }
}
