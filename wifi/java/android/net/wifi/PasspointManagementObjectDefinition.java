/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.wifi;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * This object describes a partial tree structure in the Hotspot 2.0 release 2 management object.
 * The object is used during subscription remediation to modify parts of an existing PPS MO
 * tree (Hotspot 2.0 specification section 9.1).
 * @hide
 */
public class PasspointManagementObjectDefinition implements Parcelable {
    private final String mBaseUri;
    private final String mUrn;
    private final String mMoTree;

    public PasspointManagementObjectDefinition(String baseUri, String urn, String moTree) {
        mBaseUri = baseUri;
        mUrn = urn;
        mMoTree = moTree;
    }

    public String getBaseUri() {
        return mBaseUri;
    }

    public String getUrn() {
        return mUrn;
    }

    public String getMoTree() {
        return mMoTree;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mBaseUri);
        dest.writeString(mUrn);
        dest.writeString(mMoTree);
    }

    /**
     * Implement the Parcelable interface {@hide}
     */
    public static final @android.annotation.NonNull Creator<PasspointManagementObjectDefinition> CREATOR =
            new Creator<PasspointManagementObjectDefinition>() {
                public PasspointManagementObjectDefinition createFromParcel(Parcel in) {
                    return new PasspointManagementObjectDefinition(
                            in.readString(),    /* base URI */
                            in.readString(),    /* URN */
                            in.readString()     /* Tree XML */
                    );
                }

                public PasspointManagementObjectDefinition[] newArray(int size) {
                    return new PasspointManagementObjectDefinition[size];
                }
            };
}

