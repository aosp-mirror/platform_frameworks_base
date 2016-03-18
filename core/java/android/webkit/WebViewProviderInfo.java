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

    public WebViewProviderInfo(String packageName, String description, boolean availableByDefault,
            boolean isFallback, String[] signatures) {
        this.packageName = packageName;
        this.description = description;
        this.availableByDefault = availableByDefault;
        this.isFallback = isFallback;
        this.signatures = signatures;
    }

    private boolean hasValidSignature() {
        if (Build.IS_DEBUGGABLE)
            return true;
        Signature[] packageSignatures;
        try {
            // If no signature is declared, instead check whether the package is included in the
            // system.
            if (signatures == null || signatures.length == 0)
                return getPackageInfo().applicationInfo.isSystemApp();

            packageSignatures = getPackageInfo().signatures;
        } catch (WebViewPackageNotFoundException e) {
            return false;
        }
        if (packageSignatures.length != 1)
            return false;

        final byte[] packageSignature = packageSignatures[0].toByteArray();
        // Return whether the package signature matches any of the valid signatures
        for (String signature : signatures) {
            final byte[] validSignature = Base64.decode(signature, Base64.DEFAULT);
            if (Arrays.equals(packageSignature, validSignature))
                return true;
        }
        return false;
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

    /**
     * Returns whether this package is enabled.
     * This state can be changed by the user from Settings->Apps
     */
    public boolean isEnabled() {
        try {
            // Explicitly fetch up-to-date package info here since the enabled-state of the package
            // might have changed since we last fetched its package info.
            updatePackageInfo();
            return getPackageInfo().applicationInfo.enabled;
        } catch (WebViewPackageNotFoundException e) {
            return false;
        }
    }

    /**
     * Returns whether the provider is always available as long as it is valid.
     * If this returns false, the provider will only be used if the user chose this provider.
     */
    public boolean isAvailableByDefault() {
        return availableByDefault;
    }

    public boolean isFallbackPackage() {
        return isFallback;
    }

    private void updatePackageInfo() {
        try {
            PackageManager pm = AppGlobals.getInitialApplication().getPackageManager();
            packageInfo = pm.getPackageInfo(packageName, PACKAGE_FLAGS);
        } catch (PackageManager.NameNotFoundException e) {
            throw new WebViewPackageNotFoundException(e);
        }
    }

    public PackageInfo getPackageInfo() {
        if (packageInfo == null) {
            updatePackageInfo();
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
        availableByDefault = (in.readInt() > 0);
        isFallback = (in.readInt() > 0);
        signatures = in.createStringArray();
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
        out.writeInt(availableByDefault ? 1 : 0);
        out.writeInt(isFallback ? 1 : 0);
        out.writeStringArray(signatures);
    }

    // fields read from framework resource
    public String packageName;
    public String description;
    private boolean availableByDefault;
    private boolean isFallback;

    private String[] signatures;

    private PackageInfo packageInfo;

    // flags declaring we want extra info from the package manager
    private final static int PACKAGE_FLAGS = PackageManager.GET_META_DATA
            | PackageManager.GET_SIGNATURES | PackageManager.MATCH_DEBUG_TRIAGED_MISSING;
}
