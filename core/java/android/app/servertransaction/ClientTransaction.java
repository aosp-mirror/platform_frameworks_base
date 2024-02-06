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

    /**
     * List of transaction items that should be executed in order. Including both
     * {@link ActivityLifecycleItem} and other {@link ClientTransactionItem}.
     */
    @Nullable
    private List<ClientTransactionItem> mTransactionItems;

    /** A list of individual callbacks to a client. */
    @UnsupportedAppUsage
    @Nullable
    private List<ClientTransactionItem> mActivityCallbacks;

    /**
     * Final lifecycle state in which the client activity should be after the transaction is
     * executed.
     */
    @Nullable
    private ActivityLifecycleItem mLifecycleStateRequest;

    /** Target client. */
    private IApplicationThread mClient;

    /** Get the target client of the transaction. */
    public IApplicationThread getClient() {
        return mClient;
    }

    /**
     * Adds a message to the end of the sequence of transaction items.
     * @param item A single message that can contain a client activity/window request/callback.
     */
    public void addTransactionItem(@NonNull ClientTransactionItem item) {
        if (mTransactionItems == null) {
            mTransactionItems = new ArrayList<>();
        }
        mTransactionItems.add(item);
    }

    /**
     * Gets the list of client window requests/callbacks.
     * TODO(b/260873529): must be non null after remove the deprecated methods.
     */
    @Nullable
    public List<ClientTransactionItem> getTransactionItems() {
        return mTransactionItems;
    }

    /**
     * Adds a message to the end of the sequence of callbacks.
     * @param activityCallback A single message that can contain a lifecycle request/callback.
     * @deprecated use {@link #addTransactionItem(ClientTransactionItem)} instead.
     */
    @Deprecated
    public void addCallback(@NonNull ClientTransactionItem activityCallback) {
        if (mActivityCallbacks == null) {
            mActivityCallbacks = new ArrayList<>();
        }
        mActivityCallbacks.add(activityCallback);
    }

    /**
     * Gets the list of callbacks.
     * @deprecated use {@link #getTransactionItems()} instead.
     */
    @Nullable
    @VisibleForTesting
    @UnsupportedAppUsage
    @Deprecated
    public List<ClientTransactionItem> getCallbacks() {
        return mActivityCallbacks;
    }

    /**
     * Gets the target state lifecycle request.
     * @deprecated use {@link #getTransactionItems()} instead.
     */
    @VisibleForTesting(visibility = PACKAGE)
    @UnsupportedAppUsage
    @Deprecated
    @Nullable
    public ActivityLifecycleItem getLifecycleStateRequest() {
        return mLifecycleStateRequest;
    }

    /**
     * Sets the lifecycle state in which the client should be after executing the transaction.
     * @param stateRequest A lifecycle request initialized with right parameters.
     * @deprecated use {@link #addTransactionItem(ClientTransactionItem)} instead.
     */
    @Deprecated
    public void setLifecycleStateRequest(@NonNull ActivityLifecycleItem stateRequest) {
        mLifecycleStateRequest = stateRequest;
    }

    /**
     * Do what needs to be done while the transaction is being scheduled on the client side.
     * @param clientTransactionHandler Handler on the client side that will executed all operations
     *                                 requested by transaction items.
     */
    public void preExecute(@NonNull ClientTransactionHandler clientTransactionHandler) {
        if (mTransactionItems != null) {
            final int size = mTransactionItems.size();
            for (int i = 0; i < size; ++i) {
                mTransactionItems.get(i).preExecute(clientTransactionHandler);
            }
            return;
        }

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
        if (mTransactionItems != null) {
            int size = mTransactionItems.size();
            for (int i = 0; i < size; i++) {
                mTransactionItems.get(i).recycle();
            }
            mTransactionItems = null;
        }
        if (mActivityCallbacks != null) {
            int size = mActivityCallbacks.size();
            for (int i = 0; i < size; i++) {
                mActivityCallbacks.get(i).recycle();
            }
            mActivityCallbacks = null;
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
    @SuppressWarnings("AndroidFrameworkEfficientParcelable") // Item class is not final.
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        final boolean writeTransactionItems = mTransactionItems != null;
        dest.writeBoolean(writeTransactionItems);
        if (writeTransactionItems) {
            dest.writeParcelableList(mTransactionItems, flags);
        }

        dest.writeParcelable(mLifecycleStateRequest, flags);
        final boolean writeActivityCallbacks = mActivityCallbacks != null;
        dest.writeBoolean(writeActivityCallbacks);
        if (writeActivityCallbacks) {
            dest.writeParcelableList(mActivityCallbacks, flags);
        }
    }

    /** Read from Parcel. */
    private ClientTransaction(@NonNull Parcel in) {
        final boolean readTransactionItems = in.readBoolean();
        if (readTransactionItems) {
            mTransactionItems = new ArrayList<>();
            in.readParcelableList(mTransactionItems, getClass().getClassLoader(),
                    ClientTransactionItem.class);
        }

        mLifecycleStateRequest = in.readParcelable(getClass().getClassLoader(),
                ActivityLifecycleItem.class);
        final boolean readActivityCallbacks = in.readBoolean();
        if (readActivityCallbacks) {
            mActivityCallbacks = new ArrayList<>();
            in.readParcelableList(mActivityCallbacks, getClass().getClassLoader(),
                    ClientTransactionItem.class);
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
        return Objects.equals(mTransactionItems, other.mTransactionItems)
                && Objects.equals(mActivityCallbacks, other.mActivityCallbacks)
                && Objects.equals(mLifecycleStateRequest, other.mLifecycleStateRequest)
                && mClient == other.mClient;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + Objects.hashCode(mTransactionItems);
        result = 31 * result + Objects.hashCode(mActivityCallbacks);
        result = 31 * result + Objects.hashCode(mLifecycleStateRequest);
        result = 31 * result + Objects.hashCode(mClient);
        return result;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("ClientTransaction{");
        if (mTransactionItems != null) {
            // #addTransactionItem
            sb.append("\n  transactionItems=[");
            final int size = mTransactionItems.size();
            for (int i = 0; i < size; i++) {
                sb.append("\n    ").append(mTransactionItems.get(i));
            }
            sb.append("\n  ]");
        } else {
            // #addCallback
            sb.append("\n  callbacks=[");
            final int size = mActivityCallbacks != null ? mActivityCallbacks.size() : 0;
            for (int i = 0; i < size; i++) {
                sb.append("\n    ").append(mActivityCallbacks.get(i));
            }
            sb.append("\n  ]");
            // #setLifecycleStateRequest
            sb.append("\n  stateRequest=").append(mLifecycleStateRequest);
        }
        sb.append("\n}");
        return sb.toString();
    }

    /** Dump transaction items callback items and final lifecycle state request. */
    void dump(@NonNull String prefix, @NonNull PrintWriter pw,
            @NonNull ClientTransactionHandler transactionHandler) {
        pw.append(prefix).println("ClientTransaction{");
        if (mTransactionItems != null) {
            pw.append(prefix).print("  transactionItems=[");
            final String itemPrefix = prefix + "    ";
            final int size = mTransactionItems.size();
            if (size > 0) {
                pw.println();
                for (int i = 0; i < size; i++) {
                    mTransactionItems.get(i).dump(itemPrefix, pw, transactionHandler);
                }
                pw.append(prefix).println("  ]");
            } else {
                pw.println("]");
            }
            pw.append(prefix).println("}");
            return;
        }
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
