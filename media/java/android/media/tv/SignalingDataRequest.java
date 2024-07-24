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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Request to retrieve the Low-level Signalling Tables (LLS) and Service-layer Signalling (SLS)
 * metadata.
 *
 * <p> For more details on each type of metadata that can be requested, refer to the ATSC standard
 * A/344:2023-5 9.2.10 - Query Signaling Data API.
 *
 * @see SignalingDataResponse
 */
@FlaggedApi(Flags.FLAG_TIAF_V_APIS)
public final class SignalingDataRequest extends BroadcastInfoRequest implements Parcelable {
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


    /** SLS Metadata: APD for the requested service(s) */
    public static final String SIGNALING_METADATA_APD = "APD";

    /** SLS Metadata: USBD for the requested service(s) */
    public static final String SIGNALING_METADATA_USBD = "USBD";

    /** SLS Metadata: S-TSID for the requested service(s) */
    public static final String SIGNALING_METADATA_STSID = "STSID";

    /** SLS Metadata: DASH MPD for the requested service(s) */
    public static final String SIGNALING_METADATA_MPD = "MPD";

    /** SLS Metadata: User Service Description for MMTP */
    public static final String SIGNALING_METADATA_USD = "USD";

    /** SLS Metadata: MMT Package Access Table for the requested service(s) */
    public static final String SIGNALING_METADATA_PAT  = "PAT";

    /** SLS Metadata: MMT Package Table for the requested service(s) */
    public static final String SIGNALING_METADATA_MPT = "MPT";

    /** SLS Metadata: MMT Media Presentation Information Table for the requested service(s) */
    public static final String SIGNALING_METADATA_MPIT = "MPIT";

    /** SLS Metadata: MMT Clock Relation Information for the requested service(s) */
    public static final String SIGNALING_METADATA_CRIT = "CRIT";

    /** SLS Metadata: MMT Device Capabilities Information Table for the requested service(s) */
    public static final String SIGNALING_METADATA_DCIT = "DCIT";

    /** SLS Metadata: HTML Entry Pages Location Description for the requested service(s) */
    public static final String SIGNALING_METADATA_HELD = "HELD";

    /** SLS Metadata: Distribution Window Desciription for the requested service(s) */
    public static final String SIGNALING_METADATA_DWD = "DWD";

    /** SLS Metadata: MMT Application Event Information for the requested service(s) */
    public static final String SIGNALING_METADATA_AEI = "AEI";

    /** SLS Metadata: Video Stream Properties Descriptor */
    public static final String SIGNALING_METADATA_VSPD = "VSPD";

    /** SLS Metadata: ATSC Staggercast Descriptor */
    public static final String SIGNALING_METADATA_ASD = "ASD";

    /** SLS Metadata: Inband Event Descriptor */
    public static final String SIGNALING_METADATA_IED = "IED";

    /** SLS Metadata: Caption Asset Descriptor */
    public static final String SIGNALING_METADATA_CAD = "CAD";

    /** SLS Metadata: Audio Stream Properties Descriptor */
    public static final String SIGNALING_METADATA_ASPD = "ASPD";

    /** SLS Metadata: Security Properties Descriptor */
    public static final String SIGNALING_METADATA_SSD = "SSD";

    /** SLS Metadata: ROUTE/DASH Application Dynamic Event for the requested service(s) */
    public static final String SIGNALING_METADATA_EMSG = "EMSG";

    /** SLS Metadata: MMT Application Dynamic Event for the requested service(s) */
    public static final String SIGNALING_METADATA_EVTI = "EVTI";

    /** SLS Metadata: Regional Service Availability Table for the requested service(s) */
    public static final String SIGNALING_METADATA_RSAT = "RSAT";

    /** SLS Metadata: Recovery Data Table for the requested service(s) */
    public static final String SIGNALING_METADATA_RDT = "RDT";

    /**
     * Service List Table for the requested service(s), LLS_table_id = 1.
     */
    public static final String SIGNALING_METADATA_SLT = "SLT";

    /**
     * Region Rating Table for the requested service(s), LLS_table_id = 2.
     */
    public static final String SIGNALING_METADATA_RRT = "RRT";

    /**
     * System Time Table for the requested service(s), LLS_table_id = 3.
     */
    public static final String SIGNALING_METADATA_STT = "STT";

    /**
     * Advance Emergency Information Table for the requested service(s), LLS_table_id = 4.
     */
    public static final String SIGNALING_METADATA_AEAT = "AEAT";

    /**
     * Onscreen Message Notifications for the requested service(s), LLS_table_id = 5.
     */
    public static final String SIGNALING_METADATA_OSN = "OSN";

    /**
     * Signed Multitable for the requested service(s), LLS_table_id = 0xFE (254).
     */
    public static final String SIGNALING_METADATA_SMT = "SMT";

    /**
     * CertificateData Tablefor the requested service(s), LLS_table_id = 6.
     */
    public static final String SIGNALING_METADATA_CDT = "CDT";

    private final int mGroup;
    private final @NonNull List<String> mSignalingDataTypes;

    /**
     * Denotes that theres no group associated with this request.
     */
    public static final int SIGNALING_DATA_NO_GROUP_ID = -1;

    /**
     * @hide
     */
    @android.annotation.StringDef(prefix = "SIGNALING_METADATA_", value = {
        SIGNALING_METADATA_APD,
        SIGNALING_METADATA_USBD,
        SIGNALING_METADATA_STSID,
        SIGNALING_METADATA_MPD,
        SIGNALING_METADATA_USD,
        SIGNALING_METADATA_PAT,
        SIGNALING_METADATA_MPT,
        SIGNALING_METADATA_MPIT,
        SIGNALING_METADATA_CRIT,
        SIGNALING_METADATA_DCIT,
        SIGNALING_METADATA_HELD,
        SIGNALING_METADATA_DWD,
        SIGNALING_METADATA_AEI,
        SIGNALING_METADATA_VSPD,
        SIGNALING_METADATA_ASD,
        SIGNALING_METADATA_IED,
        SIGNALING_METADATA_CAD,
        SIGNALING_METADATA_ASPD,
        SIGNALING_METADATA_SSD,
        SIGNALING_METADATA_EMSG,
        SIGNALING_METADATA_EVTI,
        SIGNALING_METADATA_RSAT,
        SIGNALING_METADATA_RDT,
        SIGNALING_METADATA_SLT,
        SIGNALING_METADATA_RRT,
        SIGNALING_METADATA_STT,
        SIGNALING_METADATA_AEAT,
        SIGNALING_METADATA_OSN,
        SIGNALING_METADATA_SMT,
        SIGNALING_METADATA_CDT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SignalingMetadata {}

    public SignalingDataRequest(int requestId, @RequestOption int option,
            int group,
            @NonNull List<String> signalingDataTypes) {
        super(REQUEST_TYPE, requestId, option);
        this.mGroup = group;
        this.mSignalingDataTypes = signalingDataTypes;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mSignalingDataTypes);
    }

    static SignalingDataRequest createFromParcelBody(Parcel in) {
        return new SignalingDataRequest(in);
    }

    /**
     * Gets the group with which any signaling returned will be associated.
     *
     * <p> Requested metadata objects will only be returned if they are part of the group specified.
     *
     * <p> If no group is specified ({@link #SIGNALING_DATA_NO_GROUP_ID}),
     * the receiver will send all the metadata objects discovered.
     *
     * @return The group ID which any signaling returned will be associated.
     */
    public int getGroup() {
        return mGroup;
    }

    /**
     * Gets the signaling data types for which data should be retrieved.
     *
     * <p> For more details on each type of metadata that can be requested, refer to the ATSC
     * standard A/344:2023-5 9.2.10 - Query Signaling Data API.
     *
     * @return The signaling data types for which data should be retrieved.
     */
    public @NonNull List<String> getSignalingDataTypes() {
        return mSignalingDataTypes;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mGroup);
        dest.writeStringList(mSignalingDataTypes);
    }

    SignalingDataRequest(@NonNull android.os.Parcel in) {
        super(REQUEST_TYPE, in);

        int group = in.readInt();
        List<String> signalingDataTypes = new java.util.ArrayList<>();
        in.readStringList(signalingDataTypes);

        this.mGroup = group;
        this.mSignalingDataTypes = signalingDataTypes;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mSignalingDataTypes);
    }

}
