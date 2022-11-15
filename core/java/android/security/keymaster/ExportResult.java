/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.keymaster;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Class for handling parceling the return values from keymaster's export operation.
 * @hide
 */
public class ExportResult implements Parcelable {
    public final int resultCode;
    public final byte[] exportData;

    public ExportResult(int resultCode) {
        this.resultCode = resultCode;
        this.exportData = new byte[0];
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final @android.annotation.NonNull Parcelable.Creator<ExportResult> CREATOR = new
            Parcelable.Creator<ExportResult>() {
                public ExportResult createFromParcel(Parcel in) {
                    return new ExportResult(in);
                }

                public ExportResult[] newArray(int length) {
                    return new ExportResult[length];
                }
            };

    protected ExportResult(Parcel in) {
        resultCode = in.readInt();
        exportData = in.createByteArray();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(resultCode);
        out.writeByteArray(exportData);
    }
}
