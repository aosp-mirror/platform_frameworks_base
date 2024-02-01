/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.media.tv;

import android.annotation.NonNull;
import android.os.Parcelable;

import java.util.List;

/** @hide */
public class SignalingDataResponse extends BroadcastInfoResponse implements Parcelable {
    public static final @NonNull Parcelable.Creator<SignalingDataResponse> CREATOR =
            new Parcelable.Creator<SignalingDataResponse>() {
                @Override
                public SignalingDataResponse[] newArray(int size) {
                    return new SignalingDataResponse[size];
                }

                @Override
                public SignalingDataResponse createFromParcel(@NonNull android.os.Parcel in) {
                    return new SignalingDataResponse(in);
                }
            };
    private static final @TvInputManager.BroadcastInfoType int RESPONSE_TYPE =
            TvInputManager.BROADCAST_INFO_TYPE_SIGNALING_DATA;
    private final @NonNull int[] mTableIds;
    private final int mMetadataTypes;
    private final @NonNull List<SignalingDataInfo> mSignalingDataInfoList;

    public SignalingDataResponse(
            int requestId,
            int sequence,
            @ResponseResult int responseResult,
            @NonNull int[] tableIds,
            int metadataTypes,
            @NonNull List<SignalingDataInfo> signalingDataInfoList) {
        super(RESPONSE_TYPE, requestId, sequence, responseResult);
        this.mTableIds = tableIds;
        com.android.internal.util.AnnotationValidations.validate(NonNull.class, null, mTableIds);
        this.mMetadataTypes = metadataTypes;
        this.mSignalingDataInfoList = signalingDataInfoList;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mSignalingDataInfoList);
    }

    public @NonNull int[] getTableIds() {
        return mTableIds;
    }

    public int getMetadataTypes() {
        return mMetadataTypes;
    }

    public @NonNull List<SignalingDataInfo> getSignalingDataInfoList() {
        return mSignalingDataInfoList;
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeIntArray(mTableIds);
        dest.writeInt(mMetadataTypes);
        dest.writeParcelableList(mSignalingDataInfoList, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    SignalingDataResponse(@NonNull android.os.Parcel in) {
        super(RESPONSE_TYPE, in);

        int[] tableIds = in.createIntArray();
        int metadataTypes = in.readInt();
        List<SignalingDataInfo> signalingDataInfoList = new java.util.ArrayList<>();
        in.readParcelableList(signalingDataInfoList, SignalingDataInfo.class.getClassLoader());

        this.mTableIds = tableIds;
        com.android.internal.util.AnnotationValidations.validate(NonNull.class, null, mTableIds);
        this.mMetadataTypes = metadataTypes;
        this.mSignalingDataInfoList = signalingDataInfoList;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mSignalingDataInfoList);
    }
}
