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

import static android.app.servertransaction.ActivityLifecycleItem.LifecycleState;
import static android.app.servertransaction.ActivityLifecycleItem.UNDEFINED;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ClientTransactionHandler;
import android.content.Context;
import android.os.IBinder;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

/**
 * A callback message to a client that can be scheduled and executed.
 * Examples of these might be activity configuration change, multi-window mode change, activity
 * result delivery etc.
 *
 * @see ClientTransaction
 * @see com.android.server.wm.ClientLifecycleManager
 * @hide
 */
public abstract class ClientTransactionItem implements BaseClientRequest, Parcelable {

    /** Get the state that must follow this callback. */
    @LifecycleState
    public int getPostExecutionState() {
        return UNDEFINED;
    }

    boolean shouldHaveDefinedPreExecutionState() {
        return true;
    }

    /**
     * If this {@link ClientTransactionItem} is updating configuration, returns the {@link Context}
     * it is updating; otherwise, returns {@code null}.
     */
    @Nullable
    public Context getContextToUpdate(@NonNull ClientTransactionHandler client) {
        return null;
    }

    /**
     * Returns the activity token if this transaction item is activity-targeting. Otherwise,
     * returns {@code null}.
     */
    @VisibleForTesting(visibility = PACKAGE)
    @Nullable
    public IBinder getActivityToken() {
        return null;
    }

    /**
     * Whether this is a {@link ActivityLifecycleItem}.
     */
    public boolean isActivityLifecycleItem() {
        return false;
    }

    /** Dumps this transaction item. */
    void dump(@NonNull String prefix, @NonNull PrintWriter pw,
            @NonNull ClientTransactionHandler transactionHandler) {
        pw.append(prefix).println(this);
    }

    // Parcelable

    @Override
    public int describeContents() {
        return 0;
    }
}
