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

import android.app.AppGlobals;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AndroidRuntimeException;
import android.util.Base64;

import java.util.Arrays;

/** @hide */
public class WebViewProviderInfo implements Parcelable {

    /**
     * @hide
     */
    public static class WebViewPackageNotFoundException extends AndroidRuntimeException {
        public WebViewPackageNotFoundException(String message) { super(message); }
        public WebViewPackageNotFoundException(Exception e) { super(e); }
    }

    public WebViewProviderInfo(String packageName, String description, String signature) {
        this.packageName = packageName;
        this.description = description;
        this.signature = signature;
    }

    private boolean hasValidSignature() {
        if (Build.IS_DEBUGGABLE)
            return true;
        Signature[] packageSignatures;
        try {
            // If no signature is declared, instead check whether the package is included in the
            // system.
            if (signature == null)
                return getPackageInfo().applicationInfo.isSystemApp();

            packageSignatures = getPackageInfo().signatures;
        } catch (WebViewPackageNotFoundException e) {
            return false;
        }
        if (packageSignatures.length != 1)
            return false;
        final byte[] releaseSignature = Base64.decode(signature, Base64.DEFAULT);
        return Arrays.equals(releaseSignature, packageSignatures[0].toByteArray());
    }

    /**
     * Returns whether this provider is valid for use as a WebView provider.
     */
    public boolean isValidProvider() {
        ApplicationInfo applicationInfo;
        try {
            applicationInfo = getPackageInfo().applicationInfo;
        } catch (WebViewPackageNotFoundException e) {
            return false;
        }
        if (hasValidSignature() && WebViewFactory.getWebViewLibrary(applicationInfo) != null) {
            return true;
        }
        return false;
    }

    public PackageInfo getPackageInfo() {
        if (packageInfo == null) {
            try {
                PackageManager pm = AppGlobals.getInitialApplication().getPackageManager();
                packageInfo = pm.getPackageInfo(packageName, PACKAGE_FLAGS);
            } catch (PackageManager.NameNotFoundException e) {
                throw new WebViewPackageNotFoundException(e);
            }
        }
        return packageInfo;
    }

    // aidl stuff
    public static final Parcelable.Creator<WebViewProviderInfo> CREATOR =
        new Parcelable.Creator<WebViewProviderInfo>() {
            public WebViewProviderInfo createFromParcel(Parcel in) {
                return new WebViewProviderInfo(in);
            }

            public WebViewProviderInfo[] newArray(int size) {
                return new WebViewProviderInfo[size];
            }
        };

    private WebViewProviderInfo(Parcel in) {
        packageName = in.readString();
        description = in.readString();
        signature = in.readString();
        packageInfo = null;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(packageName);
        out.writeString(description);
        out.writeString(signature);
    }

    // fields read from framework resource
    public String packageName;
    public String description;

    private String signature;

    private PackageInfo packageInfo;
    // flags declaring we want extra info from the package manager
    private final static int PACKAGE_FLAGS =
        PackageManager.GET_META_DATA
        | PackageManager.GET_SIGNATURES;
}

