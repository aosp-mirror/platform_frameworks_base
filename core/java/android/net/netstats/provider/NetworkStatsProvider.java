/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.net.netstats.provider;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.NetworkStats;
import android.os.RemoteException;

/**
 * A base class that allows external modules to implement a custom network statistics provider.
 * @hide
 */
@SystemApi
public abstract class NetworkStatsProvider {
    /**
     * A value used by {@link #onSetLimit}, {@link #onSetAlert} and {@link #onSetWarningAndLimit}
     * indicates there is no limit.
     */
    public static final int QUOTA_UNLIMITED = -1;

    @NonNull private final INetworkStatsProvider mProviderBinder =
            new INetworkStatsProvider.Stub() {

        @Override
        public void onRequestStatsUpdate(int token) {
            NetworkStatsProvider.this.onRequestStatsUpdate(token);
        }

        @Override
        public void onSetAlert(long quotaBytes) {
            NetworkStatsProvider.this.onSetAlert(quotaBytes);
        }

        @Override
        public void onSetWarningAndLimit(String iface, long warningBytes, long limitBytes) {
            NetworkStatsProvider.this.onSetWarningAndLimit(iface, warningBytes, limitBytes);
        }
    };

    // The binder given by the service when successfully registering. Only null before registering,
    // never null once non-null.
    @Nullable
    private INetworkStatsProviderCallback mProviderCbBinder;

    /**
     * Return the binder invoked by the service and redirect function calls to the overridden
     * methods.
     * @hide
     */
    @NonNull
    public INetworkStatsProvider getProviderBinder() {
        return mProviderBinder;
    }

    /**
     * Store the binder that was returned by the service when successfully registering. Note that
     * the provider cannot be re-registered. Hence this method can only be called once per provider.
     *
     * @hide
     */
    public void setProviderCallbackBinder(@NonNull INetworkStatsProviderCallback binder) {
        if (mProviderCbBinder != null) {
            throw new IllegalArgumentException("provider is already registered");
        }
        mProviderCbBinder = binder;
    }

    /**
     * Get the binder that was returned by the service when successfully registering. Or null if the
     * provider was never registered.
     *
     * @hide
     */
    @Nullable
    public INetworkStatsProviderCallback getProviderCallbackBinder() {
        return mProviderCbBinder;
    }

    /**
     * Get the binder that was returned by the service when successfully registering. Throw an
     * {@link IllegalStateException} if the provider is not registered.
     *
     * @hide
     */
    @NonNull
    public INetworkStatsProviderCallback getProviderCallbackBinderOrThrow() {
        if (mProviderCbBinder == null) {
            throw new IllegalStateException("the provider is not registered");
        }
        return mProviderCbBinder;
    }

    /**
     * Notify the system of new network statistics.
     *
     * Send the network statistics recorded since the last call to {@link #notifyStatsUpdated}. Must
     * be called as soon as possible after {@link NetworkStatsProvider#onRequestStatsUpdate(int)}
     * being called. Responding later increases the probability stats will be dropped. The
     * provider can also call this whenever it wants to reports new stats for any reason.
     * Note that the system will not necessarily immediately propagate the statistics to
     * reflect the update.
     *
     * @param token the token under which these stats were gathered. Providers can call this method
     *              with the current token as often as they want, until the token changes.
     *              {@see NetworkStatsProvider#onRequestStatsUpdate()}
     * @param ifaceStats the {@link NetworkStats} per interface to be reported.
     *                   The provider should not include any traffic that is already counted by
     *                   kernel interface counters.
     * @param uidStats the same stats as above, but counts {@link NetworkStats}
     *                 per uid.
     */
    public void notifyStatsUpdated(int token, @NonNull NetworkStats ifaceStats,
            @NonNull NetworkStats uidStats) {
        try {
            getProviderCallbackBinderOrThrow().notifyStatsUpdated(token, ifaceStats, uidStats);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Notify system that the quota set by {@code onSetAlert} has been reached.
     */
    public void notifyAlertReached() {
        try {
            getProviderCallbackBinderOrThrow().notifyAlertReached();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Notify system that the warning set by {@link #onSetWarningAndLimit} has been reached.
     */
    public void notifyWarningReached() {
        try {
            // Reuse the code path to notify warning reached with limit reached
            // since framework handles them in the same way.
            getProviderCallbackBinderOrThrow().notifyWarningOrLimitReached();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Notify system that the quota set by {@link #onSetLimit} or limit set by
     * {@link #onSetWarningAndLimit} has been reached.
     */
    public void notifyLimitReached() {
        try {
            getProviderCallbackBinderOrThrow().notifyWarningOrLimitReached();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Called by {@code NetworkStatsService} when it requires to know updated stats.
     * The provider MUST respond by calling {@link #notifyStatsUpdated} as soon as possible.
     * Responding later increases the probability stats will be dropped. Memory allowing, the
     * system will try to take stats into account up to one minute after calling
     * {@link #onRequestStatsUpdate}.
     *
     * @param token a positive number identifying the new state of the system under which
     *              {@link NetworkStats} have to be gathered from now on. When this is called,
     *              custom implementations of providers MUST tally and report the latest stats with
     *              the previous token, under which stats were being gathered so far.
     */
    public abstract void onRequestStatsUpdate(int token);

    /**
     * Called by {@code NetworkStatsService} when setting the interface quota for the specified
     * upstream interface. When this is called, the custom implementation should block all egress
     * packets on the {@code iface} associated with the provider when {@code quotaBytes} bytes have
     * been reached, and MUST respond to it by calling
     * {@link NetworkStatsProvider#notifyLimitReached()}.
     *
     * @param iface the interface requiring the operation.
     * @param quotaBytes the quota defined as the number of bytes, starting from zero and counting
     *                   from now. A value of {@link #QUOTA_UNLIMITED} indicates there is no limit.
     */
    public abstract void onSetLimit(@NonNull String iface, long quotaBytes);

    /**
     * Called by {@code NetworkStatsService} when setting the interface quotas for the specified
     * upstream interface. If a provider implements {@link #onSetWarningAndLimit}, the system
     * will not call {@link #onSetLimit}. When this method is called, the implementation
     * should behave as follows:
     *   1. If {@code warningBytes} is reached on {@code iface}, block all further traffic on
     *      {@code iface} and call {@link NetworkStatsProvider@notifyWarningReached()}.
     *   2. If {@code limitBytes} is reached on {@code iface}, block all further traffic on
     *   {@code iface} and call {@link NetworkStatsProvider#notifyLimitReached()}.
     *
     * @param iface the interface requiring the operation.
     * @param warningBytes the warning defined as the number of bytes, starting from zero and
     *                     counting from now. A value of {@link #QUOTA_UNLIMITED} indicates
     *                     there is no warning.
     * @param limitBytes the limit defined as the number of bytes, starting from zero and counting
     *                   from now. A value of {@link #QUOTA_UNLIMITED} indicates there is no limit.
     */
    public void onSetWarningAndLimit(@NonNull String iface, long warningBytes, long limitBytes) {
        // Backward compatibility for those who didn't override this function.
        onSetLimit(iface, limitBytes);
    }

    /**
     * Called by {@code NetworkStatsService} when setting the alert bytes. Custom implementations
     * MUST call {@link NetworkStatsProvider#notifyAlertReached()} when {@code quotaBytes} bytes
     * have been reached. Unlike {@link #onSetLimit(String, long)}, the custom implementation should
     * not block all egress packets.
     *
     * @param quotaBytes the quota defined as the number of bytes, starting from zero and counting
     *                   from now. A value of {@link #QUOTA_UNLIMITED} indicates there is no alert.
     */
    public abstract void onSetAlert(long quotaBytes);
}
