/*
 * Copyright 2017 The Android Open Source Project
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
import android.annotation.Nullable;
import android.app.ClientTransactionHandler;
import android.app.IApplicationThread;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A container that holds a sequence of messages, which may be sent to a client.
 * This includes a list of callbacks and a final lifecycle state.
 *
 * @see com.android.server.wm.ClientLifecycleManager
 * @see ClientTransactionItem
 * @see ActivityLifecycleItem
 * @hide
 */
public class ClientTransaction implements Parcelable, ObjectPoolItem {

    /** A list of individual callbacks to a client. */
    @UnsupportedAppUsage
    private List<ClientTransactionItem> mActivityCallbacks;

    /**
     * Final lifecycle state in which the client activity should be after the transaction is
     * executed.
     */
    private ActivityLifecycleItem mLifecycleStateRequest;

    /** Target client. */
    private IApplicationThread mClient;

    /** Get the target client of the transaction. */
    public IApplicationThread getClient() {
        return mClient;
    }

    /**
     * Add a message to the end of the sequence of callbacks.
     * @param activityCallback A single message that can contain a lifecycle request/callback.
     */
    public void addCallback(@NonNull ClientTransactionItem activityCallback) {
        if (mActivityCallbacks == null) {
            mActivityCallbacks = new ArrayList<>();
        }
        mActivityCallbacks.add(activityCallback);
    }

    /** Get the list of callbacks. */
    @Nullable
    @VisibleForTesting
    @UnsupportedAppUsage
    public List<ClientTransactionItem> getCallbacks() {
        return mActivityCallbacks;
    }

    /** Get the target activity. */
    @Nullable
    @UnsupportedAppUsage
    public IBinder getActivityToken() {
        // TODO(b/260873529): remove after we allow multiple activity items in one transaction.
        if (mLifecycleStateRequest != null) {
            return mLifecycleStateRequest.getActivityToken();
        }
        for (int i = mActivityCallbacks.size() - 1; i >= 0; i--) {
            final IBinder token = mActivityCallbacks.get(i).getActivityToken();
            if (token != null) {
                return token;
            }
        }
        return null;
    }

    /** Get the target state lifecycle request. */
    @VisibleForTesting(visibility = PACKAGE)
    @UnsupportedAppUsage
    public ActivityLifecycleItem getLifecycleStateRequest() {
        return mLifecycleStateRequest;
    }

    /**
     * Set the lifecycle state in which the client should be after executing the transaction.
     * @param stateRequest A lifecycle request initialized with right parameters.
     */
    public void setLifecycleStateRequest(@NonNull ActivityLifecycleItem stateRequest) {
        mLifecycleStateRequest = stateRequest;
    }

    /**
     * Do what needs to be done while the transaction is being scheduled on the client side.
     * @param clientTransactionHandler Handler on the client side that will executed all operations
     *                                 requested by transaction items.
     */
    public void preExecute(@NonNull ClientTransactionHandler clientTransactionHandler) {
        if (mActivityCallbacks != null) {
            final int size = mActivityCallbacks.size();
            for (int i = 0; i < size; ++i) {
                mActivityCallbacks.get(i).preExecute(clientTransactionHandler);
            }
        }
        if (mLifecycleStateRequest != null) {
            mLifecycleStateRequest.preExecute(clientTransactionHandler);
        }
    }

    /**
     * Schedule the transaction after it was initialized. It will be send to client and all its
     * individual parts will be applied in the following sequence:
     * 1. The client calls {@link #preExecute(ClientTransactionHandler)}, which triggers all work
     *    that needs to be done before actually scheduling the transaction for callbacks and
     *    lifecycle state request.
     * 2. The transaction message is scheduled.
     * 3. The client calls {@link TransactionExecutor#execute(ClientTransaction)}, which executes
     *    all callbacks and necessary lifecycle transitions.
     */
    public void schedule() throws RemoteException {
        mClient.scheduleTransaction(this);
    }


    // ObjectPoolItem implementation

    private ClientTransaction() {}

    /** Obtains an instance initialized with provided params. */
    @NonNull
    public static ClientTransaction obtain(@Nullable IApplicationThread client) {
        ClientTransaction instance = ObjectPool.obtain(ClientTransaction.class);
        if (instance == null) {
            instance = new ClientTransaction();
        }
        instance.mClient = client;

        return instance;
    }

    @Override
    public void recycle() {
        if (mActivityCallbacks != null) {
            int size = mActivityCallbacks.size();
            for (int i = 0; i < size; i++) {
                mActivityCallbacks.get(i).recycle();
            }
            mActivityCallbacks.clear();
        }
        if (mLifecycleStateRequest != null) {
            mLifecycleStateRequest.recycle();
            mLifecycleStateRequest = null;
        }
        mClient = null;
        ObjectPool.recycle(this);
    }

    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mLifecycleStateRequest, flags);
        final boolean writeActivityCallbacks = mActivityCallbacks != null;
        dest.writeBoolean(writeActivityCallbacks);
        if (writeActivityCallbacks) {
            dest.writeParcelableList(mActivityCallbacks, flags);
        }
    }

    /** Read from Parcel. */
    private ClientTransaction(@NonNull Parcel in) {
        mLifecycleStateRequest = in.readParcelable(getClass().getClassLoader(), android.app.servertransaction.ActivityLifecycleItem.class);
        final boolean readActivityCallbacks = in.readBoolean();
        if (readActivityCallbacks) {
            mActivityCallbacks = new ArrayList<>();
            in.readParcelableList(mActivityCallbacks, getClass().getClassLoader(), android.app.servertransaction.ClientTransactionItem.class);
        }
    }

    public static final @NonNull Creator<ClientTransaction> CREATOR = new Creator<>() {
        public ClientTransaction createFromParcel(@NonNull Parcel in) {
            return new ClientTransaction(in);
        }

        public ClientTransaction[] newArray(int size) {
            return new ClientTransaction[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ClientTransaction other = (ClientTransaction) o;
        return Objects.equals(mActivityCallbacks, other.mActivityCallbacks)
                && Objects.equals(mLifecycleStateRequest, other.mLifecycleStateRequest)
                && mClient == other.mClient;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + Objects.hashCode(mActivityCallbacks);
        result = 31 * result + Objects.hashCode(mLifecycleStateRequest);
        result = 31 * result + Objects.hashCode(mClient);
        return result;
    }

    /** Dump transaction items callback items and final lifecycle state request. */
    void dump(@NonNull String prefix, @NonNull PrintWriter pw,
            @NonNull ClientTransactionHandler transactionHandler) {
        pw.append(prefix).println("ClientTransaction{");
        pw.append(prefix).print("  callbacks=[");
        final String itemPrefix = prefix + "    ";
        final int size = mActivityCallbacks != null ? mActivityCallbacks.size() : 0;
        if (size > 0) {
            pw.println();
            for (int i = 0; i < size; i++) {
                mActivityCallbacks.get(i).dump(itemPrefix, pw, transactionHandler);
            }
            pw.append(prefix).println("  ]");
        } else {
            pw.println("]");
        }

        pw.append(prefix).println("  stateRequest=");
        if (mLifecycleStateRequest != null) {
            mLifecycleStateRequest.dump(itemPrefix, pw, transactionHandler);
        } else {
            pw.append(itemPrefix).println("null");
        }
        pw.append(prefix).println("}");
    }
}
