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

import android.app.ClientTransactionHandler;
import android.content.res.Configuration;
import android.os.IBinder;
import android.os.Parcel;

import java.util.Objects;

/**
 * Multi-window mode change message.
 * @hide
 */
// TODO(lifecycler): Remove the use of this and just use the configuration change message to
// communicate multi-window mode change with WindowConfiguration.
public class MultiWindowModeChangeItem extends ClientTransactionItem {

    private boolean mIsInMultiWindowMode;
    private Configuration mOverrideConfig;

    @Override
    public void execute(ClientTransactionHandler client, IBinder token,
            PendingTransactionActions pendingActions) {
        client.handleMultiWindowModeChanged(token, mIsInMultiWindowMode, mOverrideConfig);
    }


    // ObjectPoolItem implementation

    private MultiWindowModeChangeItem() {}

    /** Obtain an instance initialized with provided params. */
    public static MultiWindowModeChangeItem obtain(boolean isInMultiWindowMode,
            Configuration overrideConfig) {
        MultiWindowModeChangeItem instance = ObjectPool.obtain(MultiWindowModeChangeItem.class);
        if (instance == null) {
            instance = new MultiWindowModeChangeItem();
        }
        instance.mIsInMultiWindowMode = isInMultiWindowMode;
        instance.mOverrideConfig = overrideConfig;

        return instance;
    }

    @Override
    public void recycle() {
        mIsInMultiWindowMode = false;
        mOverrideConfig = null;
        ObjectPool.recycle(this);
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final MultiWindowModeChangeItem other = (MultiWindowModeChangeItem) o;
        return mIsInMultiWindowMode == other.mIsInMultiWindowMode
                && Objects.equals(mOverrideConfig, other.mOverrideConfig);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (mIsInMultiWindowMode ? 1 : 0);
        result = 31 * result + mOverrideConfig.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "MultiWindowModeChangeItem{isInMultiWindowMode=" + mIsInMultiWindowMode
                + ",overrideConfig=" + mOverrideConfig + "}";
    }
}
