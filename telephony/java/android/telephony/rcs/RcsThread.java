/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telephony.rcs;

import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.telephony.rcs.IRcs;

/**
 * RcsThread represents a single RCS conversation thread. It holds messages that were sent and
 * received and events that occured on that thread.
 * @hide - TODO(sahinc) make this public
 */
public class RcsThread implements Parcelable {
    public static final Creator<RcsThread> CREATOR = new Creator<RcsThread>() {
        @Override
        public RcsThread createFromParcel(Parcel in) {
            return new RcsThread(in);
        }

        @Override
        public RcsThread[] newArray(int size) {
            return new RcsThread[size];
        }
    };

    protected RcsThread(Parcel in) {
    }

    /**
     * Returns the number of messages in this RCS thread.
     *
     * @hide
     */
    public int getMessageCount() {
        try {
            IRcs iRcs = IRcs.Stub.asInterface(ServiceManager.getService("ircs"));
            if (iRcs != null) {
                // TODO(sahinc): substitute to the regular thread id once we have database
                // TODO(sahinc): connection in place
                return iRcs.getMessageCount(/* rcsThreadId= */ 123);
            }
        } catch (RemoteException re) {
            // TODO(sahinc): Log something meaningful
        }
        return 0;
    }

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
    }
}
