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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.PackageInfo;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** @hide */
@FlaggedApi(Flags.FLAG_UPDATE_SERVICE_IPC_WRAPPER)
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class WebViewProviderResponse implements Parcelable {

    @IntDef(
            prefix = {"STATUS_"},
            value = {
                STATUS_SUCCESS,
                STATUS_FAILED_WAITING_FOR_RELRO,
                STATUS_FAILED_LISTING_WEBVIEW_PACKAGES,
                STATUS_FAILED_OTHER,
            })
    @Retention(RetentionPolicy.SOURCE)
    private @interface WebViewProviderStatus {}

    public static final int STATUS_SUCCESS = WebViewFactory.LIBLOAD_SUCCESS;
    public static final int STATUS_FAILED_WAITING_FOR_RELRO =
            WebViewFactory.LIBLOAD_FAILED_WAITING_FOR_RELRO;
    public static final int STATUS_FAILED_LISTING_WEBVIEW_PACKAGES =
            WebViewFactory.LIBLOAD_FAILED_LISTING_WEBVIEW_PACKAGES;
    public static final int STATUS_FAILED_OTHER = WebViewFactory.LIBLOAD_FAILED_OTHER;

    public WebViewProviderResponse(
            @Nullable PackageInfo packageInfo, @WebViewProviderStatus int status) {
        this.packageInfo = packageInfo;
        this.status = status;
    }

    // aidl stuff
    public static final @NonNull Parcelable.Creator<WebViewProviderResponse> CREATOR =
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
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeTypedObject(packageInfo, flags);
        out.writeInt(status);
    }

    @UnsupportedAppUsage public final @Nullable PackageInfo packageInfo;
    public final @WebViewProviderStatus int status;
}
