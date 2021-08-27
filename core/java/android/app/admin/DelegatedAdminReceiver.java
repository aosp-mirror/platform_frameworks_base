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

package android.app.admin;

import static android.app.admin.DeviceAdminReceiver.ACTION_CHOOSE_PRIVATE_KEY_ALIAS;
import static android.app.admin.DeviceAdminReceiver.ACTION_NETWORK_LOGS_AVAILABLE;
import static android.app.admin.DeviceAdminReceiver.ACTION_SECURITY_LOGS_AVAILABLE;
import static android.app.admin.DeviceAdminReceiver.EXTRA_CHOOSE_PRIVATE_KEY_ALIAS;
import static android.app.admin.DeviceAdminReceiver.EXTRA_CHOOSE_PRIVATE_KEY_SENDER_UID;
import static android.app.admin.DeviceAdminReceiver.EXTRA_CHOOSE_PRIVATE_KEY_URI;
import static android.app.admin.DeviceAdminReceiver.EXTRA_NETWORK_LOGS_COUNT;
import static android.app.admin.DeviceAdminReceiver.EXTRA_NETWORK_LOGS_TOKEN;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.security.KeyChain;
import android.util.Log;

/**
 * Base class for delegated apps to handle callbacks related to their delegated capabilities.
 *
 * <p>Delegated apps are apps that receive additional capabilities from the profile owner or
 * device owner apps. Some of these capabilities involve the framework calling into the apps.
 * To receive these callbacks, delegated apps should subclass this class and override the
 * appropriate methods here. The subclassed receiver needs to be published in the app's
 * manifest, with appropriate intent filters to mark which callbacks the receiver is interested
 * in. An app can have multiple receivers as long as they listen for disjoint set of callbacks.
 * For the manifest definitions, it must be protected by the
 * {@link android.Manifest.permission#BIND_DEVICE_ADMIN} permission to ensure only
 * the system can trigger these callbacks.
 *
 * <p>The callback methods happen on the main thread of the process.  Thus long running
 * operations must be done on another thread.  Note that because a receiver
 * is done once returning from its onReceive function, such long-running operations
 * should probably be done in a {@link Service}.
 *
 * @see DevicePolicyManager#setDelegatedScopes
 * @see DeviceAdminReceiver
 */
public class DelegatedAdminReceiver extends BroadcastReceiver {
    private static final String TAG = "DelegatedAdminReceiver";

    /**
     * Allows this receiver to select the alias for a private key and certificate pair for
     * authentication.  If this method returns null, the default {@link android.app.Activity} will
     * be shown that lets the user pick a private key and certificate pair.
     * If this method returns {@link KeyChain#KEY_ALIAS_SELECTION_DENIED},
     * the default {@link android.app.Activity} will not be shown and the user will not be allowed
     * to pick anything. And the app, that called {@link KeyChain#choosePrivateKeyAlias}, will
     * receive {@code null} back.
     *
     * <p> This callback is only applicable if the delegated app has
     * {@link DevicePolicyManager#DELEGATION_CERT_SELECTION} capability. Additionally, it must
     * declare an intent filter for {@link DeviceAdminReceiver#ACTION_CHOOSE_PRIVATE_KEY_ALIAS}
     * in the receiver's manifest in order to receive this callback. The default implementation
     * simply throws {@link UnsupportedOperationException}.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @param uid The uid of the app asking for the private key and certificate pair.
     * @param uri The URI to authenticate, may be null.
     * @param alias The alias preselected by the client, or null.
     * @return The private key alias to return and grant access to.
     * @see KeyChain#choosePrivateKeyAlias
     */
    public @Nullable String onChoosePrivateKeyAlias(@NonNull Context context,
            @NonNull Intent intent, int uid, @Nullable Uri uri, @Nullable String alias) {
        throw new UnsupportedOperationException("onChoosePrivateKeyAlias should be implemented");
    }

    /**
     * Called each time a new batch of network logs can be retrieved. This callback method will only
     * ever be called when network logging is enabled. The logs can only be retrieved while network
     * logging is enabled.
     *
     * <p>If a secondary user or profile is created, this callback won't be received until all users
     * become affiliated again (even if network logging is enabled). It will also no longer be
     * possible to retrieve the network logs batch with the most recent {@code batchToken} provided
     * by this callback. See {@link DevicePolicyManager#setAffiliationIds}.
     *
     * <p> This callback is only applicable if the delegated app has
     * {@link DevicePolicyManager#DELEGATION_NETWORK_LOGGING} capability. Additionally, it must
     * declare an intent filter for {@link DeviceAdminReceiver#ACTION_NETWORK_LOGS_AVAILABLE} in the
     * receiver's manifest in order to receive this callback. The default implementation
     * simply throws {@link UnsupportedOperationException}.
     *
     * <p>
     * This callback is triggered by a foreground broadcast and the app should ensure that any
     * long-running work is not executed synchronously inside the callback.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @param batchToken The token representing the current batch of network logs.
     * @param networkLogsCount The total count of events in the current batch of network logs.
     * @see DevicePolicyManager#retrieveNetworkLogs
     */
    public void onNetworkLogsAvailable(@NonNull Context context, @NonNull Intent intent,
            long batchToken, @IntRange(from = 1) int networkLogsCount) {
        throw new UnsupportedOperationException("onNetworkLogsAvailable should be implemented");
    }

    /**
     * Called each time a new batch of security logs can be retrieved. This callback method will
     * only ever be called when security logging is enabled. The logs can only be retrieved while
     * security logging is enabled.
     *
     * <p>If a secondary user or profile is created, this callback won't be received until all users
     * become affiliated again (even if security logging is enabled). It will also no longer be
     * possible to retrieve the security logs. See {@link DevicePolicyManager#setAffiliationIds}.
     *
     * <p> This callback is only applicable if the delegated app has
     * {@link DevicePolicyManager#DELEGATION_SECURITY_LOGGING} capability. Additionally, it must
     * declare an intent filter for {@link DeviceAdminReceiver#ACTION_SECURITY_LOGS_AVAILABLE} in
     * the receiver's manifest in order to receive this callback. The default implementation
     * simply throws {@link UnsupportedOperationException}.
     *
     * <p>
     * This callback is triggered by a foreground broadcast and the app should ensure that any
     * long-running work is not executed synchronously inside the callback.
     *
     * @param context The running context as per {@link #onReceive}.
     * @param intent The received intent as per {@link #onReceive}.
     * @see DevicePolicyManager#retrieveSecurityLogs
     */
    public void onSecurityLogsAvailable(@NonNull Context context, @NonNull Intent intent) {
        throw new UnsupportedOperationException("onSecurityLogsAvailable should be implemented");
    }

    /**
     * Intercept delegated device administrator broadcasts. Implementations should not override
     * this method; implement the convenience callbacks for each action instead.
     */
    @Override
    public final void onReceive(@NonNull Context context, @NonNull Intent intent) {
        String action = intent.getAction();

        if (ACTION_CHOOSE_PRIVATE_KEY_ALIAS.equals(action)) {
            int uid = intent.getIntExtra(EXTRA_CHOOSE_PRIVATE_KEY_SENDER_UID, -1);
            Uri uri = intent.getParcelableExtra(EXTRA_CHOOSE_PRIVATE_KEY_URI);
            String alias = intent.getStringExtra(EXTRA_CHOOSE_PRIVATE_KEY_ALIAS);
            String chosenAlias = onChoosePrivateKeyAlias(context, intent, uid, uri, alias);
            setResultData(chosenAlias);
        } else if (ACTION_NETWORK_LOGS_AVAILABLE.equals(action)) {
            long batchToken = intent.getLongExtra(EXTRA_NETWORK_LOGS_TOKEN, -1);
            int networkLogsCount = intent.getIntExtra(EXTRA_NETWORK_LOGS_COUNT, 0);
            onNetworkLogsAvailable(context, intent, batchToken, networkLogsCount);
        } else if (ACTION_SECURITY_LOGS_AVAILABLE.equals(action)) {
            onSecurityLogsAvailable(context, intent);
        } else {
            Log.w(TAG, "Unhandled broadcast: " + action);
        }
    }
}
