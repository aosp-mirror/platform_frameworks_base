/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.tv;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * This class is used to specify meta information of a TV input.
 */
public final class TvInputInfo implements Parcelable {
    private final ResolveInfo mService;
    private final String mId;

    /**
     * Constructor.
     *
     * @param service The ResolveInfo returned from the package manager about this TV input service.
     * @hide
     */
    public TvInputInfo(ResolveInfo service) {
        mService = service;
        ServiceInfo si = service.serviceInfo;
        mId = new ComponentName(si.packageName, si.name).flattenToShortString();
    }

    /**
     * Returns a unique ID for this TV input. The ID is generated from the package and class name
     * implementing the TV input service.
     */
    public String getId() {
        return mId;
    }

    /**
     * Returns the .apk package that implements this TV input service.
     */
    public String getPackageName() {
        return mService.serviceInfo.packageName;
    }

    /**
     * Returns the class name of the service component that implements this TV input service.
     */
    public String getServiceName() {
        return mService.serviceInfo.name;
    }

    /**
     * Returns the component of the service that implements this TV input.
     */
    public ComponentName getComponent() {
        return new ComponentName(mService.serviceInfo.packageName, mService.serviceInfo.name);
    }

    /**
     * Loads the user-displayed label for this TV input service.
     *
     * @param pm Supplies a PackageManager used to load the TV input's resources.
     * @return Returns a CharSequence containing the TV input's label. If the TV input does not have
     *         a label, its name is returned.
     */
    public CharSequence loadLabel(PackageManager pm) {
        return mService.loadLabel(pm);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mId);
        mService.writeToParcel(dest, flags);
    }

    /**
     * Used to make this class parcelable.
     *
     * @hide
     */
    public static final Parcelable.Creator<TvInputInfo> CREATOR =
            new Parcelable.Creator<TvInputInfo>() {
        @Override
        public TvInputInfo createFromParcel(Parcel in) {
            return new TvInputInfo(in);
        }

        @Override
        public TvInputInfo[] newArray(int size) {
            return new TvInputInfo[size];
        }
    };

    private TvInputInfo(Parcel in) {
        mId = in.readString();
        mService = ResolveInfo.CREATOR.createFromParcel(in);
    }
}
