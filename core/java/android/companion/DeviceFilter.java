/*
 * Copyright (C) 2017 The Android Open Source Project
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


package android.companion;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A filter for companion devices of type {@code D}
 *
 * @param <D> Type of devices, filtered by this filter,
 *           e.g. {@link android.bluetooth.BluetoothDevice}, {@link android.net.wifi.ScanResult}
 */
public interface DeviceFilter<D extends Parcelable> extends Parcelable {

    /** @hide */
    int MEDIUM_TYPE_BLUETOOTH = 0;
    /** @hide */
    int MEDIUM_TYPE_BLUETOOTH_LE = 1;
    /** @hide */
    int MEDIUM_TYPE_WIFI = 2;

    /**
     * @return whether the given device matches this filter
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    boolean matches(D device);

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    String getDeviceDisplayName(D device);

    /**  @hide */
    @MediumType int getMediumType();

    /**
     * A nullsafe {@link #matches(Parcelable)}, returning true if the filter is null
     *
     * @hide
     */
    static <D extends Parcelable> boolean matches(@Nullable DeviceFilter<D> filter, D device) {
        return filter == null || filter.matches(device);
    }

    /** @hide */
    @IntDef(prefix = { "MEDIUM_TYPE_" }, value = {
            MEDIUM_TYPE_BLUETOOTH,
            MEDIUM_TYPE_BLUETOOTH_LE,
            MEDIUM_TYPE_WIFI
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface MediumType {}
}
