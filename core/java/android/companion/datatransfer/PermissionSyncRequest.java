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
import android.os.Parcel;
import android.os.Parcelable;

/**
 * The permission sync request class.
 *
 * @hide
 */
public class PermissionSyncRequest extends SystemDataTransferRequest implements Parcelable {

    /** @hide */
    public PermissionSyncRequest(int associationId) {
        super(associationId, DATA_TYPE_PERMISSION_SYNC);
    }

    /** @hide */
    @Override
    public String toString() {
        return "SystemDataTransferRequest("
                + "associationId=" + mAssociationId
                + ", userId=" + mUserId
                + ", isUserConsented=" + mUserConsented
                + ")";
    }

    /** @hide */
    PermissionSyncRequest(Parcel in) {
        super(in);
    }

    /** @hide */
    @Override
    public PermissionSyncRequest copyWithNewId(int associationId) {
        PermissionSyncRequest newRequest = new PermissionSyncRequest(associationId);
        newRequest.mUserId = this.mUserId;
        newRequest.mUserConsented = this.mUserConsented;
        return newRequest;
    }

    /** @hide */
    @NonNull
    public static final Creator<PermissionSyncRequest> CREATOR =
            new Creator<PermissionSyncRequest>() {
                @Override
                public PermissionSyncRequest createFromParcel(Parcel in) {
                    return new PermissionSyncRequest(in);
                }

                @Override
                public PermissionSyncRequest[] newArray(int size) {
                    return new PermissionSyncRequest[size];
                }
            };
}
