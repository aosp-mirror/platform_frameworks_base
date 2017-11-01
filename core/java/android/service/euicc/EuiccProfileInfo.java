/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.service.euicc;

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.UiccAccessRule;
import android.text.TextUtils;

/**
 * Information about an embedded profile (subscription) on an eUICC.
 *
 * @hide
 *
 * TODO(b/35851809): Make this a SystemApi.
 */
public final class EuiccProfileInfo implements Parcelable {

    /** The iccid of the subscription. */
    public final String iccid;

    /**
     * Optional access rules defining which apps can manage this subscription. If unset, only the
     * platform can manage it.
     */
    public final @Nullable UiccAccessRule[] accessRules;

    /** An optional nickname for the subscription. */
    public final @Nullable String nickname;

    public static final Creator<EuiccProfileInfo> CREATOR = new Creator<EuiccProfileInfo>() {
        @Override
        public EuiccProfileInfo createFromParcel(Parcel in) {
            return new EuiccProfileInfo(in);
        }

        @Override
        public EuiccProfileInfo[] newArray(int size) {
            return new EuiccProfileInfo[size];
        }
    };

    public EuiccProfileInfo(String iccid, @Nullable UiccAccessRule[] accessRules,
            @Nullable String nickname) {
        if (!TextUtils.isDigitsOnly(iccid)) {
            throw new IllegalArgumentException("iccid contains invalid characters: " + iccid);
        }
        this.iccid = iccid;
        this.accessRules = accessRules;
        this.nickname = nickname;
    }

    private EuiccProfileInfo(Parcel in) {
        iccid = in.readString();
        accessRules = in.createTypedArray(UiccAccessRule.CREATOR);
        nickname = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(iccid);
        dest.writeTypedArray(accessRules, flags);
        dest.writeString(nickname);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
