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

package android.net.wifi.passpoint;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * TODO: doc
 */
public class PasspointOsuProvider implements Parcelable {

    /** TODO: doc */
    public static final int OSU_METHOD_OMADM = 0;

    /** TODO: doc */
    public static final int OSU_METHOD_SOAP = 1;

    /** TODO: doc */
    public String SSID;

    /** TODO: doc */
    public String friendlyName;

    /** TODO: doc */
    public String serverUri;

    /** TODO: doc */
    public int osuMethod;

    /** TODO: doc */
    public String iconFileName;

    /** TODO: doc */
    public Object icon; // TODO: should change to image format

    /** TODO: doc */
    public String osuNai;

    /** TODO: doc */
    public String osuService;


    /** default constructor @hide */
    public PasspointOsuProvider() {
        // TODO
    }

    /** copy constructor @hide */
    public PasspointOsuProvider(PasspointOsuProvider source) {
        // TODO
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // TODO
    }

    public static final Parcelable.Creator<PasspointOsuProvider> CREATOR =
            new Parcelable.Creator<PasspointOsuProvider>() {
        @Override
        public PasspointOsuProvider createFromParcel(Parcel in) {
            PasspointOsuProvider osu = new PasspointOsuProvider();
            // TODO
            return osu;
        }

        @Override
        public PasspointOsuProvider[] newArray(int size) {
            return new PasspointOsuProvider[size];
        }
    };
}
