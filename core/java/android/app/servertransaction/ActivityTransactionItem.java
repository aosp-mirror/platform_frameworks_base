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

import static android.app.servertransaction.TransactionExecutorHelper.getActivityName;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import static java.util.Objects.requireNonNull;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.os.IBinder;
import android.os.Parcel;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.Objects;

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

    /** Target client activity. */
    @NonNull
    private final IBinder mActivityToken;

    public ActivityTransactionItem(@NonNull IBinder activityToken) {
        mActivityToken = requireNonNull(activityToken);
    }

    @Override
    public final void execute(@NonNull ClientTransactionHandler client,
            @NonNull PendingTransactionActions pendingActions) {
        final ActivityClientRecord r = getActivityClientRecord(client);
        execute(client, r, pendingActions);
    }

    /**
     * Like {@link #execute(ClientTransactionHandler, PendingTransactionActions)},
     * but take non-null {@link ActivityClientRecord} as a parameter.
     */
    @VisibleForTesting(visibility = PACKAGE)
    public abstract void execute(@NonNull ClientTransactionHandler client,
            @NonNull ActivityClientRecord r, @NonNull PendingTransactionActions pendingActions);

    /**
     * Gets the {@link ActivityClientRecord} instance that this transaction item is for.
     * @param client Target client handler.
     * @return The {@link ActivityClientRecord} instance that this transaction item is for.
     */
    @NonNull
    final ActivityClientRecord getActivityClientRecord(@NonNull ClientTransactionHandler client) {
        final ActivityClientRecord r = client.getActivityClient(getActivityToken());
        if (r == null) {
            throw new IllegalArgumentException("Activity client record must not be null to execute "
                    + "transaction item: " + this);
        }
        if (client.getActivity(getActivityToken()) == null) {
            throw new IllegalArgumentException("Activity must not be null to execute "
                    + "transaction item: " + this);
        }
        return r;
    }

    @VisibleForTesting(visibility = PACKAGE)
    @NonNull
    @Override
    public IBinder getActivityToken() {
        return mActivityToken;
    }

    // Parcelable implementation

    /** Writes to Parcel. */
    @CallSuper
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mActivityToken);
    }

    /** Reads from Parcel. */
    ActivityTransactionItem(@NonNull Parcel in) {
        this(in.readStrongBinder());
    }

    @Override
    void dump(@NonNull String prefix, @NonNull PrintWriter pw,
            @NonNull ClientTransactionHandler transactionHandler) {
        super.dump(prefix, pw, transactionHandler);
        pw.append(prefix).append("Target activity: ")
                .println(getActivityName(mActivityToken, transactionHandler));
    }

    // Subclass must override and call super.equals to compare the mActivityToken.
    @SuppressWarnings("EqualsGetClass")
    @CallSuper
    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ActivityTransactionItem other = (ActivityTransactionItem) o;
        return Objects.equals(mActivityToken, other.mActivityToken);
    }

    @CallSuper
    @Override
    public int hashCode() {
        return Objects.hashCode(mActivityToken);
    }

    @CallSuper
    @Override
    public String toString() {
        return "mActivityToken=" + mActivityToken;
    }
}
