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

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 * Simple container for information about TV input hardware.
 * Not for third-party developers.
 *
 * @hide
 */
public final class TvInputHardwareInfo implements Parcelable {
    static final String TAG = "TvInputHardwareInfo";

    // Match hardware/libhardware/include/hardware/tv_input.h
    public static final int TV_INPUT_TYPE_HDMI           = 1;
    public static final int TV_INPUT_TYPE_BUILT_IN_TUNER = 2;
    public static final int TV_INPUT_TYPE_PASSTHROUGH    = 3;

    public static final Parcelable.Creator<TvInputHardwareInfo> CREATOR =
            new Parcelable.Creator<TvInputHardwareInfo>() {
        @Override
        public TvInputHardwareInfo createFromParcel(Parcel source) {
            try {
                TvInputHardwareInfo info = new TvInputHardwareInfo();
                info.readFromParcel(source);
                return info;
            } catch (Exception e) {
                Log.e(TAG, "Exception creating TvInputHardwareInfo from parcel", e);
                return null;
            }
        }

        @Override
        public TvInputHardwareInfo[] newArray(int size) {
            return new TvInputHardwareInfo[size];
        }
    };

    private int mDeviceId;
    private int mType;
    // TODO: Add audio port & audio address for audio service.
    // TODO: Add HDMI handle for HDMI service.

    public TvInputHardwareInfo() { }

    public TvInputHardwareInfo(int deviceId, int type) {
        mDeviceId = deviceId;
        mType = type;
    }

    public int getDeviceId() {
        return mDeviceId;
    }

    public int getType() {
        return mType;
    }

    // Parcelable
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mDeviceId);
        dest.writeInt(mType);
    }

    public void readFromParcel(Parcel source) {
        mDeviceId = source.readInt();
        mType = source.readInt();
    }
}
