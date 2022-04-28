/*
 * Copyright 2021 The Android Open Source Project
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

package android.media.tv.interactive;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * App link information used by TV interactive app to launch Android apps.
 */
public final class AppLinkInfo implements Parcelable {
    private @NonNull ComponentName mComponentName;
    private @NonNull Uri mUri;

    /**
     * Creates a new AppLinkInfo.
     *
     * @param packageName Package Name of AppLinkInfo.
     * @param className Class Name of AppLinkInfo.
     * @param uriString Uri of AppLinkInfo.
     */
    public AppLinkInfo(
            @NonNull String packageName,
            @NonNull String className,
            @NonNull String uriString) {
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, packageName);
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, className);
        this.mComponentName = new ComponentName(packageName, className);
        this.mUri = Uri.parse(uriString);
    }

    /**
     * Gets component name of the App link, which contains package name and class name.
     */
    @NonNull
    public ComponentName getComponentName() {
        return mComponentName;
    }

    /**
     * Gets URI of the App link.
     */
    @NonNull
    public Uri getUri() {
        return mUri;
    }

    @Override
    public String toString() {
        return "AppLinkInfo { "
                + "packageName = " + mComponentName.getPackageName() + ", "
                + "className = " + mComponentName.getClassName() + ", "
                + "uri = " + mUri.toString()
                + " }";
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        mComponentName.writeToParcel(dest, flags);
        String uriString = mUri == null ? null : mUri.toString();
        dest.writeString(uriString);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /* package-private */ AppLinkInfo(@NonNull Parcel in) {
        mComponentName = ComponentName.readFromParcel(in);
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mComponentName.getPackageName());
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mComponentName.getClassName());
        String uriString = in.readString();
        mUri = uriString == null ? null : Uri.parse(uriString);
    }

    @NonNull
    public static final Parcelable.Creator<AppLinkInfo> CREATOR =
            new Parcelable.Creator<AppLinkInfo>() {
                @Override
                public AppLinkInfo[] newArray(int size) {
                    return new AppLinkInfo[size];
                }

                @Override
                public AppLinkInfo createFromParcel(@NonNull Parcel in) {
                    return new AppLinkInfo(in);
                }
            };
}
