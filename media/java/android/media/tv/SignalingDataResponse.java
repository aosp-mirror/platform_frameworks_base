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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.media.tv.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;


/**
 * A response for the signaling data from the broadcast signal.
 *
 * @see SignalingDataRequest
 * @see SignalingDataInfo
 */
@FlaggedApi(Flags.FLAG_TIAF_V_APIS)
public final class SignalingDataResponse extends BroadcastInfoResponse implements Parcelable {
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
    private final @NonNull List<String> mSignalingDataTypes;
    private final @NonNull List<SignalingDataInfo> mSignalingDataInfoList;

    static SignalingDataResponse createFromParcelBody(Parcel in) {
        return new SignalingDataResponse(in);
    }

    public SignalingDataResponse(
            int requestId,
            int sequence,
            @ResponseResult int responseResult,
            @NonNull List<String> signalingDataTypes,
            @NonNull List<SignalingDataInfo> signalingDataInfoList) {
        super(RESPONSE_TYPE, requestId, sequence, responseResult);
        mSignalingDataTypes = signalingDataTypes;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mSignalingDataTypes);
        this.mSignalingDataInfoList = signalingDataInfoList;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mSignalingDataInfoList);
    }

    /**
     * Gets a list of types of metadata that are contained in this response.
     *
     * <p> This list correlates to all the available types that can be found within
     * {@link #getSignalingDataInfoList()}. This list is determined by the types specified in
     * {@link SignalingDataRequest#getSignalingDataTypes()}.
     *
     * <p> A list of types available are defined in {@link SignalingDataRequest}.
     * For more information about these types, see A/344:2023-5 9.2.10 - Query Signaling Data API.
     *
     * @return A list of types of metadata that are contained in this response.
     */
    public @NonNull List<String> getSignalingDataTypes() {
        return mSignalingDataTypes;
    }

    /**
     * Gets a list of {@link SignalingDataInfo} contained in this response.
     * @return A list of {@link SignalingDataInfo} contained in this response.
     */
    public @NonNull List<SignalingDataInfo> getSignalingDataInfoList() {
        return mSignalingDataInfoList;
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeStringList(mSignalingDataTypes);
        dest.writeParcelableList(mSignalingDataInfoList, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    SignalingDataResponse(@NonNull android.os.Parcel in) {
        super(RESPONSE_TYPE, in);

        List<String> types = new ArrayList<String>();
        in.readStringList(types);
        List<SignalingDataInfo> signalingDataInfoList = new java.util.ArrayList<>();
        in.readParcelableList(signalingDataInfoList, SignalingDataInfo.class.getClassLoader());

        this.mSignalingDataTypes = types;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mSignalingDataTypes);
        this.mSignalingDataInfoList = signalingDataInfoList;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mSignalingDataInfoList);
    }
}
