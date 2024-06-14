/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.companion.datatransfer;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.os.Parcel;

/**
 * A request for users to allow the companion app to transfer system data to the companion devices.
 *
 * @hide
 */
public abstract class SystemDataTransferRequest {

    /** @hide */
    public static final int DATA_TYPE_PERMISSION_SYNC = 1;

    final int mAssociationId;

    final int mDataType;

    /**
     * User id that the request belongs to.
     * Populated by the system.
     */
    @UserIdInt
    int mUserId;

    /**
     * Whether the request is consented by the user.
     * Populated by the system
     */
    boolean mUserConsented = false;

    /** @hide */
    SystemDataTransferRequest(int associationId, int dataType) {
        mAssociationId = associationId;
        mDataType = dataType;
    }

    /** @hide */
    public int getAssociationId() {
        return mAssociationId;
    }

    /** @hide */
    public int getDataType() {
        return mDataType;
    }

    /** @hide */
    public int getUserId() {
        return mUserId;
    }

    /** @hide */
    public boolean isUserConsented() {
        return mUserConsented;
    }

    /** @hide */
    public void setUserId(@UserIdInt int userId) {
        mUserId = userId;
    }

    /** @hide */
    public void setUserConsented(boolean isUserConsented) {
        mUserConsented = isUserConsented;
    }

    /** @hide */
    SystemDataTransferRequest(Parcel in) {
        mAssociationId = in.readInt();
        mDataType = in.readInt();
        mUserId = in.readInt();
        mUserConsented = in.readBoolean();
    }

    /** @hide */
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mAssociationId);
        dest.writeInt(mDataType);
        dest.writeInt(mUserId);
        dest.writeBoolean(mUserConsented);
    }

    /** @hide */
    public int describeContents() {
        return 0;
    }

    /**
     * Creates a copy of itself with new association ID.
     *
     * This method must be implemented to ensure that backup-and-restore can correctly re-map
     * the restored requests to the restored associations that can potentially have different
     * IDs than what was originally backed up.
     *
     * @hide
     */
    public abstract SystemDataTransferRequest copyWithNewId(int associationId);
}
