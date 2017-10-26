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

import static android.os.Trace.TRACE_TAG_ACTIVITY_MANAGER;

import android.content.res.Configuration;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Trace;

/**
 * Activity move to a different display message.
 * @hide
 */
public class MoveToDisplayItem extends ClientTransactionItem {

    private final int mTargetDisplayId;
    private final Configuration mConfiguration;

    public MoveToDisplayItem(int targetDisplayId, Configuration configuration) {
        mTargetDisplayId = targetDisplayId;
        mConfiguration = configuration;
    }

    @Override
    public void execute(android.app.ClientTransactionHandler client, IBinder token) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityMovedToDisplay");
        client.handleActivityConfigurationChanged(token, mConfiguration, mTargetDisplayId);
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }


    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mTargetDisplayId);
        dest.writeTypedObject(mConfiguration, flags);
    }

    /** Read from Parcel. */
    private MoveToDisplayItem(Parcel in) {
        mTargetDisplayId = in.readInt();
        mConfiguration = in.readTypedObject(Configuration.CREATOR);
    }

    public static final Creator<MoveToDisplayItem> CREATOR =
            new Creator<MoveToDisplayItem>() {
        public MoveToDisplayItem createFromParcel(Parcel in) {
            return new MoveToDisplayItem(in);
        }

        public MoveToDisplayItem[] newArray(int size) {
            return new MoveToDisplayItem[size];
        }
    };
}
