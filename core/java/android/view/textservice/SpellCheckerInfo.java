/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.view.textservice;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * This class is used to specify meta information of an spell checker.
 */
public final class SpellCheckerInfo implements Parcelable {
    private final ResolveInfo mService;
    private final String mId;

    /**
     * Constructor.
     * @hide
     */
    public SpellCheckerInfo(Context context, ResolveInfo service) {
        mService = service;
        ServiceInfo si = service.serviceInfo;
        mId = new ComponentName(si.packageName, si.name).flattenToShortString();
    }

    /**
     * Constructor.
     * @hide
     */
    public SpellCheckerInfo(Parcel source) {
        mId = source.readString();
        mService = ResolveInfo.CREATOR.createFromParcel(source);
    }

    /**
     * Return a unique ID for this spell checker.  The ID is generated from
     * the package and class name implementing the method.
     */
    public String getId() {
        return mId;
    }


    /**
     * Return the component of the service that implements.
     */
    public ComponentName getComponent() {
        return new ComponentName(
                mService.serviceInfo.packageName, mService.serviceInfo.name);
    }

    /**
     * Return the .apk package that implements this input method.
     */
    public String getPackageName() {
        return mService.serviceInfo.packageName;
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
     */
    public static final Parcelable.Creator<SpellCheckerInfo> CREATOR
            = new Parcelable.Creator<SpellCheckerInfo>() {
        @Override
        public SpellCheckerInfo createFromParcel(Parcel source) {
            return new SpellCheckerInfo(source);
        }

        @Override
        public SpellCheckerInfo[] newArray(int size) {
            return new SpellCheckerInfo[size];
        }
    };

    /**
     * Used to make this class parcelable.
     */
    @Override
    public int describeContents() {
        return 0;
    }
}
