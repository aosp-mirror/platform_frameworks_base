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

package android.app.servertransaction;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.annotation.NonNull;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.os.IBinder;

import com.android.internal.annotations.VisibleForTesting;

/**
 * An activity-targeting callback message to a client that can be scheduled and executed.
 * It also provides nullity-free version of
 * {@link #execute(ClientTransactionHandler, IBinder, PendingTransactionActions)} for child class
 * to inherit.
 *
 * @see ClientTransaction
 * @see ClientTransactionItem
 * @see com.android.server.wm.ClientLifecycleManager
 * @hide
 */
public abstract class ActivityTransactionItem extends ClientTransactionItem {
    @Override
    public final void execute(ClientTransactionHandler client, IBinder token,
            PendingTransactionActions pendingActions) {
        final ActivityClientRecord r = getActivityClientRecord(client, token);

        execute(client, r, pendingActions);
    }

    /**
     * Like {@link #execute(ClientTransactionHandler, IBinder, PendingTransactionActions)},
     * but take non-null {@link ActivityClientRecord} as a parameter.
     */
    @VisibleForTesting(visibility = PACKAGE)
    public abstract void execute(@NonNull ClientTransactionHandler client,
            @NonNull ActivityClientRecord r, PendingTransactionActions pendingActions);

    @NonNull ActivityClientRecord getActivityClientRecord(
            @NonNull ClientTransactionHandler client, IBinder token) {
        return getActivityClientRecord(client, token, false /* includeLaunching */);
    }

    /**
     * Gets the {@link ActivityClientRecord} instance that corresponds to the provided token.
     * @param client Target client handler.
     * @param token Target activity token.
     * @param includeLaunching Indicate to find the {@link ActivityClientRecord} in launching
     *                         activity list.
     *                         <p>Note that there is no {@link android.app.Activity} instance in
     *                         {@link ActivityClientRecord} from the launching activity list.
     * @return The {@link ActivityClientRecord} instance that corresponds to the provided token.
     */
    @NonNull ActivityClientRecord getActivityClientRecord(
            @NonNull ClientTransactionHandler client, IBinder token, boolean includeLaunching) {
        ActivityClientRecord r = null;
        // Check launching Activity first to prevent race condition that activity instance has not
        // yet set to ActivityClientRecord.
        if (includeLaunching) {
            r = client.getLaunchingActivity(token);
        }
        // Then if we don't want to find launching Activity or the ActivityClientRecord doesn't
        // exist in launching Activity list. The ActivityClientRecord should have been initialized
        // and put in the Activity list.
        if (r == null) {
            r = client.getActivityClient(token);
            if (r != null && client.getActivity(token) == null) {
                throw new IllegalArgumentException("Activity must not be null to execute "
                        + "transaction item");
            }
        }
        if (r == null) {
            throw new IllegalArgumentException("Activity client record must not be null to execute "
                    + "transaction item");
        }
        return r;
    }
}
