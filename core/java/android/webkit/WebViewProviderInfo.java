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

import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.content.pm.Signature;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Base64;

/**
 * @hide
 */
@SystemApi
public final class WebViewProviderInfo implements Parcelable {

    public WebViewProviderInfo(String packageName, String description,
            boolean availableByDefault, boolean isFallback, String[] signatures) {
        this.packageName = packageName;
        this.description = description;
        this.availableByDefault = availableByDefault;
        this.isFallback = isFallback;
        if (signatures == null) {
            this.signatures = new Signature[0];
        } else {
            this.signatures = new Signature[signatures.length];
            for (int n = 0; n < signatures.length; n++) {
                this.signatures[n] = new Signature(Base64.decode(signatures[n], Base64.DEFAULT));
            }
        }
    }

    // aidl stuff
    public static final @android.annotation.NonNull Parcelable.Creator<WebViewProviderInfo> CREATOR =
        new Parcelable.Creator<WebViewProviderInfo>() {
            public WebViewProviderInfo createFromParcel(Parcel in) {
                return new WebViewProviderInfo(in);
            }

            public WebViewProviderInfo[] newArray(int size) {
                return new WebViewProviderInfo[size];
            }
        };

    @UnsupportedAppUsage
    private WebViewProviderInfo(Parcel in) {
        packageName = in.readString();
        description = in.readString();
        availableByDefault = (in.readInt() > 0);
        isFallback = (in.readInt() > 0);
        signatures = in.createTypedArray(Signature.CREATOR);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(packageName);
        out.writeString(description);
        out.writeInt(availableByDefault ? 1 : 0);
        out.writeInt(isFallback ? 1 : 0);
        out.writeTypedArray(signatures, 0);
    }

    // fields read from framework resource
    public final String packageName;
    public final String description;
    public final boolean availableByDefault;
    public final boolean isFallback;
    public final Signature[] signatures;
}
