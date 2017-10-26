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

import android.content.res.Configuration;
import android.os.IBinder;
import android.os.Parcel;

/**
 * Multi-window mode change message.
 * @hide
 */
// TODO(lifecycler): Remove the use of this and just use the configuration change message to
// communicate multi-window mode change with WindowConfiguration.
public class MultiWindowModeChangeItem extends ClientTransactionItem {

    private final boolean mIsInMultiWindowMode;
    private final Configuration mOverrideConfig;

    public MultiWindowModeChangeItem(boolean isInMultiWindowMode,
            Configuration overrideConfig) {
        mIsInMultiWindowMode = isInMultiWindowMode;
        mOverrideConfig = overrideConfig;
    }

    @Override
    public void execute(android.app.ClientTransactionHandler client, IBinder token) {
        client.handleMultiWindowModeChanged(token, mIsInMultiWindowMode, mOverrideConfig);
    }


    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(mIsInMultiWindowMode);
        dest.writeTypedObject(mOverrideConfig, flags);
    }

    /** Read from Parcel. */
    private MultiWindowModeChangeItem(Parcel in) {
        mIsInMultiWindowMode = in.readBoolean();
        mOverrideConfig = in.readTypedObject(Configuration.CREATOR);
    }

    public static final Creator<MultiWindowModeChangeItem> CREATOR =
            new Creator<MultiWindowModeChangeItem>() {
        public MultiWindowModeChangeItem createFromParcel(Parcel in) {
            return new MultiWindowModeChangeItem(in);
        }

        public MultiWindowModeChangeItem[] newArray(int size) {
            return new MultiWindowModeChangeItem[size];
        }
    };
}
