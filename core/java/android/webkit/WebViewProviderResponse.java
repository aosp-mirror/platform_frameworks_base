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

package android.webkit;

import android.annotation.UnsupportedAppUsage;
import android.content.pm.PackageInfo;
import android.os.Parcel;
import android.os.Parcelable;

/** @hide */
public final class WebViewProviderResponse implements Parcelable {

    public WebViewProviderResponse(PackageInfo packageInfo, int status) {
        this.packageInfo = packageInfo;
        this.status = status;
    }

    // aidl stuff
    public static final @android.annotation.NonNull Parcelable.Creator<WebViewProviderResponse> CREATOR =
        new Parcelable.Creator<WebViewProviderResponse>() {
            public WebViewProviderResponse createFromParcel(Parcel in) {
                return new WebViewProviderResponse(in);
            }

            public WebViewProviderResponse[] newArray(int size) {
                return new WebViewProviderResponse[size];
            }
        };

    private WebViewProviderResponse(Parcel in) {
        packageInfo = in.readTypedObject(PackageInfo.CREATOR);
        status = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeTypedObject(packageInfo, flags);
        out.writeInt(status);
    }

    @UnsupportedAppUsage
    public final PackageInfo packageInfo;
    public final int status;
}
