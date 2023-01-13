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

import android.os.Parcelable;

/**
 * A callback message to a client that can be scheduled and executed.
 * Examples of these might be activity configuration change, multi-window mode change, activity
 * result delivery etc.
 *
 * @see ClientTransaction
 * @see com.android.server.am.ClientLifecycleManager
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

    // Parcelable

    @Override
    public int describeContents() {
        return 0;
    }
}
