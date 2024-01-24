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

/**
 * Request to retrieve the Low-level Signalling Tables (LLS) and Service-layer Signalling (SLS)
 * metadata.
 *
 * <p>For more details on each type of metadata that can be requested, refer to the ATSC standard
 * A/344:2023-5 9.2.10 - Query Signaling Data API.
 *
 * @hide
 */
public class SignalingDataRequest extends BroadcastInfoRequest implements Parcelable {
    private static final @TvInputManager.BroadcastInfoType int REQUEST_TYPE =
            TvInputManager.BROADCAST_INFO_TYPE_SIGNALING_DATA;

    public static final @NonNull Parcelable.Creator<SignalingDataRequest> CREATOR =
            new Parcelable.Creator<SignalingDataRequest>() {
                @Override
                public SignalingDataRequest[] newArray(int size) {
                    return new SignalingDataRequest[size];
                }

                @Override
                public SignalingDataRequest createFromParcel(@NonNull android.os.Parcel in) {
                    return new SignalingDataRequest(in);
                }
            };

    /** SLS Metadata: All metadata objects for the requested service(s) */
    public static final int SLS_METADATA_ALL = 0x7FFFFFF;

    /** SLS Metadata: APD for the requested service(s) */
    public static final int SLS_METADATA_APD = 1;

    /** SLS Metadata: USBD for the requested service(s) */
    public static final int SLS_METADATA_USBD = 1 << 1;

    /** SLS Metadata: S-TSID for the requested service(s) */
    public static final int SLS_METADATA_STSID = 1 << 2;

    /** SLS Metadata: DASH MPD for the requested service(s) */
    public static final int SLS_METADATA_MPD = 1 << 3;

    /** SLS Metadata: User Service Description for MMTP */
    public static final int SLS_METADATA_USD = 1 << 4;

    /** SLS Metadata: MMT Package Access Table for the requested service(s) */
    public static final int SLS_METADATA_PAT = 1 << 5;

    /** SLS Metadata: MMT Package Table for the requested service(s) */
    public static final int SLS_METADATA_MPT = 1 << 6;

    /** SLS Metadata: MMT Media Presentation Information Table for the requested service(s) */
    public static final int SLS_METADATA_MPIT = 1 << 7;

    /** SLS Metadata: MMT Clock Relation Information for the requested service(s) */
    public static final int SLS_METADATA_CRIT = 1 << 8;

    /** SLS Metadata: MMT Device Capabilities Information Table for the requested service(s) */
    public static final int SLS_METADATA_DCIT = 1 << 9;

    /** SLS Metadata: HTML Entry Pages Location Description for the requested service(s) */
    public static final int SLS_METADATA_HELD = 1 << 10;

    /** SLS Metadata: Distribution Window Desciription for the requested service(s) */
    public static final int SLS_METADATA_DWD = 1 << 11;

    /** SLS Metadata: MMT Application Event Information for the requested service(s) */
    public static final int SLS_METADATA_AEI = 1 << 12;

    /** SLS Metadata: Video Stream Properties Descriptor */
    public static final int SLS_METADATA_VSPD = 1 << 13;

    /** SLS Metadata: ATSC Staggercast Descriptor */
    public static final int SLS_METADATA_ASD = 1 << 14;

    /** SLS Metadata: Inband Event Descriptor */
    public static final int SLS_METADATA_IED = 1 << 15;

    /** SLS Metadata: Caption Asset Descriptor */
    public static final int SLS_METADATA_CAD = 1 << 16;

    /** SLS Metadata: Audio Stream Properties Descriptor */
    public static final int SLS_METADATA_ASPD = 1 << 17;

    /** SLS Metadata: Security Properties Descriptor */
    public static final int SLS_METADATA_SSD = 1 << 18;

    /** SLS Metadata: ROUTE/DASH Application Dynamic Event for the requested service(s) */
    public static final int SLS_METADATA_EMSG = 1 << 19;

    /** SLS Metadata: MMT Application Dynamic Event for the requested service(s) */
    public static final int SLS_METADATA_EVTI = 1 << 20;

    /** Regional Service Availability Table for the requested service(s) */
    public static final int SLS_METADATA_RSAT = 1 << 21;

    private final int mGroup;
    private @NonNull final int[] mLlsTableIds;
    private final int mSlsMetadataTypes;

    SignalingDataRequest(
            int requestId,
            int option,
            int group,
            @NonNull int[] llsTableIds,
            int slsMetadataTypes) {
        super(REQUEST_TYPE, requestId, option);
        mGroup = group;
        mLlsTableIds = llsTableIds;
        mSlsMetadataTypes = slsMetadataTypes;
    }

    SignalingDataRequest(@NonNull android.os.Parcel in) {
        super(REQUEST_TYPE, in);

        int group = in.readInt();
        int[] llsTableIds = in.createIntArray();
        int slsMetadataTypes = in.readInt();

        this.mGroup = group;
        this.mLlsTableIds = llsTableIds;
        com.android.internal.util.AnnotationValidations.validate(NonNull.class, null, mLlsTableIds);
        this.mSlsMetadataTypes = slsMetadataTypes;
    }

    public int getGroup() {
        return mGroup;
    }

    public @NonNull int[] getLlsTableIds() {
        return mLlsTableIds;
    }

    public int getSlsMetadataTypes() {
        return mSlsMetadataTypes;
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mGroup);
        dest.writeIntArray(mLlsTableIds);
        dest.writeInt(mSlsMetadataTypes);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
