/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.telephony.ims;

import static android.telephony.ims.RcsMessageStore.TAG;

import android.annotation.NonNull;
import android.annotation.WorkerThread;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.Rlog;
import android.telephony.ims.aidl.IRcs;
import android.text.TextUtils;

import com.android.internal.util.Preconditions;

/**
 * RcsParticipant is an RCS capable contact that can participate in {@link RcsThread}s.
 * @hide - TODO(sahinc) make this public
 */
public class RcsParticipant implements Parcelable {
    // The row ID of this participant in the database
    private int mId;
    // The phone number of this participant
    private String mCanonicalAddress;
    // The RCS alias of this participant. This is different than the name of the contact in the
    // Contacts app - i.e. RCS protocol allows users to define aliases for themselves that doesn't
    // require other users to add them as contacts and give them a name.
    private String mAlias;

    /**
     * Constructor for {@link com.android.internal.telephony.ims.RcsMessageStoreController}
     * to create instances of participants. This is not meant to be part of the SDK.
     *
     * @hide
     */
    public RcsParticipant(int id, @NonNull String canonicalAddress) {
        mId = id;
        mCanonicalAddress = canonicalAddress;
    }

    /**
     * @return Returns the canonical address (i.e. normalized phone number) for this participant
     */
    public String getCanonicalAddress() {
        return mCanonicalAddress;
    }

    /**
     * Sets the canonical address for this participant and updates it in storage.
     * @param canonicalAddress the canonical address to update to.
     */
    @WorkerThread
    public void setCanonicalAddress(@NonNull String canonicalAddress) {
        Preconditions.checkNotNull(canonicalAddress);
        if (canonicalAddress.equals(mCanonicalAddress)) {
            return;
        }

        mCanonicalAddress = canonicalAddress;

        try {
            IRcs iRcs = IRcs.Stub.asInterface(ServiceManager.getService("ircs"));
            if (iRcs != null) {
                iRcs.updateRcsParticipantCanonicalAddress(mId, mCanonicalAddress);
            }
        } catch (RemoteException re) {
            Rlog.e(TAG, "RcsParticipant: Exception happened during setCanonicalAddress", re);
        }
    }

    /**
     * @return Returns the alias for this participant. Alias is usually the real name of the person
     * themselves.
     */
    public String getAlias() {
        return mAlias;
    }

    /**
     * Sets the alias for this participant and persists it in storage. Alias is usually the real
     * name of the person themselves.
     */
    @WorkerThread
    public void setAlias(String alias) {
        if (TextUtils.equals(mAlias, alias)) {
            return;
        }
        mAlias = alias;

        try {
            IRcs iRcs = IRcs.Stub.asInterface(ServiceManager.getService("ircs"));
            if (iRcs != null) {
                iRcs.updateRcsParticipantAlias(mId, mAlias);
            }
        } catch (RemoteException re) {
            Rlog.e(TAG, "RcsParticipant: Exception happened during setCanonicalAddress", re);
        }
    }

    /**
     * Returns the row id of this participant. This is not meant to be part of the SDK
     *
     * @hide
     */
    public int getId() {
        return mId;
    }

    public static final Creator<RcsParticipant> CREATOR = new Creator<RcsParticipant>() {
        @Override
        public RcsParticipant createFromParcel(Parcel in) {
            return new RcsParticipant(in);
        }

        @Override
        public RcsParticipant[] newArray(int size) {
            return new RcsParticipant[size];
        }
    };

    protected RcsParticipant(Parcel in) {
        mId = in.readInt();
        mCanonicalAddress = in.readString();
        mAlias = in.readString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeString(mCanonicalAddress);
        dest.writeString(mAlias);
    }
}
