/*
 * Copyright 2020 The Android Open Source Project
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
import android.os.IBinder;
import android.os.Parcel;
import android.view.DisplayAdjustments.FixedRotationAdjustments;

import java.util.Objects;

/**
 * The request to update display adjustments for a rotated activity or window token.
 * @hide
 */
public class FixedRotationAdjustmentsItem extends ClientTransactionItem {

    /** The token who may have {@link android.content.res.Resources}. */
    private IBinder mToken;

    /**
     * The adjustments for the display adjustments of resources. If it is null, the existing
     * rotation adjustments will be dropped to restore natural state.
     */
    private FixedRotationAdjustments mFixedRotationAdjustments;

    private FixedRotationAdjustmentsItem() {}

    /** Obtain an instance initialized with provided params. */
    public static FixedRotationAdjustmentsItem obtain(IBinder token,
            FixedRotationAdjustments fixedRotationAdjustments) {
        FixedRotationAdjustmentsItem instance =
                ObjectPool.obtain(FixedRotationAdjustmentsItem.class);
        if (instance == null) {
            instance = new FixedRotationAdjustmentsItem();
        }
        instance.mToken = token;
        instance.mFixedRotationAdjustments = fixedRotationAdjustments;

        return instance;
    }

    @Override
    public void execute(ClientTransactionHandler client, IBinder token,
            PendingTransactionActions pendingActions) {
        client.handleFixedRotationAdjustments(mToken, mFixedRotationAdjustments);
    }

    @Override
    public void recycle() {
        mToken = null;
        mFixedRotationAdjustments = null;
        ObjectPool.recycle(this);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(mToken);
        dest.writeTypedObject(mFixedRotationAdjustments, flags);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final FixedRotationAdjustmentsItem other = (FixedRotationAdjustmentsItem) o;
        return Objects.equals(mToken, other.mToken)
                && Objects.equals(mFixedRotationAdjustments, other.mFixedRotationAdjustments);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + Objects.hashCode(mToken);
        result = 31 * result + Objects.hashCode(mFixedRotationAdjustments);
        return result;
    }

    private FixedRotationAdjustmentsItem(Parcel in) {
        mToken = in.readStrongBinder();
        mFixedRotationAdjustments = in.readTypedObject(FixedRotationAdjustments.CREATOR);
    }

    public static final Creator<FixedRotationAdjustmentsItem> CREATOR =
            new Creator<FixedRotationAdjustmentsItem>() {
        public FixedRotationAdjustmentsItem createFromParcel(Parcel in) {
            return new FixedRotationAdjustmentsItem(in);
        }

        public FixedRotationAdjustmentsItem[] newArray(int size) {
            return new FixedRotationAdjustmentsItem[size];
        }
    };
}
