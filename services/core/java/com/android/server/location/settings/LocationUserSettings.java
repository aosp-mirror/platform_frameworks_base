/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.location.settings;

import android.content.res.Resources;

import com.android.internal.R;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;

/** Holds the state of location user settings. */
public final class LocationUserSettings implements SettingsStore.VersionedSettings {

    // remember to bump this version code and add the appropriate upgrade logic whenever the format
    // is changed.
    private static final int VERSION = 1;

    private final boolean mAdasGnssLocationEnabled;

    private LocationUserSettings(boolean adasGnssLocationEnabled) {
        mAdasGnssLocationEnabled = adasGnssLocationEnabled;
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    public boolean isAdasGnssLocationEnabled() {
        return mAdasGnssLocationEnabled;
    }

    /** Returns an instance with ADAS GNSS location enabled state set as given. */
    public LocationUserSettings withAdasGnssLocationEnabled(boolean adasEnabled) {
        if (adasEnabled == mAdasGnssLocationEnabled) {
            return this;
        }

        return new LocationUserSettings(adasEnabled);
    }

    void write(DataOutput out) throws IOException {
        out.writeBoolean(mAdasGnssLocationEnabled);
    }

    static LocationUserSettings read(Resources resources, int version, DataInput in)
            throws IOException {
        boolean adasGnssLocationEnabled;

        // upgrade code goes here. remember to bump the version field when changing the format
        switch (version) {
            default:
                // set all fields to defaults
                adasGnssLocationEnabled = resources.getBoolean(
                        R.bool.config_defaultAdasGnssLocationEnabled);
                break;
            case 1:
                adasGnssLocationEnabled = in.readBoolean();
                // fall through
        }

        return new LocationUserSettings(adasGnssLocationEnabled);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LocationUserSettings)) {
            return false;
        }
        LocationUserSettings that = (LocationUserSettings) o;
        return mAdasGnssLocationEnabled == that.mAdasGnssLocationEnabled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAdasGnssLocationEnabled);
    }
}
