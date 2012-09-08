/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.hardware.display;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * Describes the current global state of Wifi display connectivity, including the
 * currently connected display and all known displays.
 * <p>
 * This object is immutable.
 * </p>
 *
 * @hide
 */
public final class WifiDisplayStatus implements Parcelable {
    private final boolean mEnabled;
    private final WifiDisplay mConnectedDisplay;
    private final WifiDisplay[] mKnownDisplays;
    private final boolean mScanInProgress;
    private final boolean mConnectionInProgress;

    public static final Creator<WifiDisplayStatus> CREATOR = new Creator<WifiDisplayStatus>() {
        public WifiDisplayStatus createFromParcel(Parcel in) {
            boolean enabled = (in.readInt() != 0);

            WifiDisplay connectedDisplay = null;
            if (in.readInt() != 0) {
                connectedDisplay = WifiDisplay.CREATOR.createFromParcel(in);
            }

            WifiDisplay[] knownDisplays = WifiDisplay.CREATOR.newArray(in.readInt());
            for (int i = 0; i < knownDisplays.length; i++) {
                knownDisplays[i] = WifiDisplay.CREATOR.createFromParcel(in);
            }

            boolean scanInProgress = (in.readInt() != 0);
            boolean connectionInProgress = (in.readInt() != 0);

            return new WifiDisplayStatus(enabled, connectedDisplay, knownDisplays,
                    scanInProgress, connectionInProgress);
        }

        public WifiDisplayStatus[] newArray(int size) {
            return new WifiDisplayStatus[size];
        }
    };

    public WifiDisplayStatus() {
        this(false, null, WifiDisplay.EMPTY_ARRAY, false, false);
    }

    public WifiDisplayStatus(boolean enabled,
            WifiDisplay connectedDisplay, WifiDisplay[] knownDisplays,
            boolean scanInProgress, boolean connectionInProgress) {
        if (knownDisplays == null) {
            throw new IllegalArgumentException("knownDisplays must not be null");
        }

        mEnabled = enabled;
        mConnectedDisplay = connectedDisplay;
        mKnownDisplays = knownDisplays;
        mScanInProgress = scanInProgress;
        mConnectionInProgress = connectionInProgress;
    }

    /**
     * Returns true if the Wifi display feature is enabled and available for use.
     * <p>
     * The value of this property reflects whether Wifi and Wifi P2P functions
     * are enabled.  Enablement is not directly controllable by the user at this
     * time, except indirectly such as by turning off Wifi altogether.
     * </p>
     */
    public boolean isEnabled() {
        return mEnabled;
    }

    /**
     * Gets the currently connected Wifi display or null if none.
     */
    public WifiDisplay getConnectedDisplay() {
        return mConnectedDisplay;
    }

    /**
     * Gets the list of all known Wifi displays, never null.
     */
    public WifiDisplay[] getKnownDisplays() {
        return mKnownDisplays;
    }

    /**
     * Returns true if there is currently a Wifi display scan in progress.
     */
    public boolean isScanInProgress() {
        return mScanInProgress;
    }

    /**
     * Returns true if there is currently a Wifi display connection in progress.
     */
    public boolean isConnectionInProgress() {
        return mConnectionInProgress;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mEnabled ? 1 : 0);

        if (mConnectedDisplay != null) {
            dest.writeInt(1);
            mConnectedDisplay.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }

        dest.writeInt(mKnownDisplays.length);
        for (WifiDisplay display : mKnownDisplays) {
            display.writeToParcel(dest, flags);
        }

        dest.writeInt(mScanInProgress ? 1 : 0);
        dest.writeInt(mConnectionInProgress ? 1 : 0);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    // For debugging purposes only.
    @Override
    public String toString() {
        return "WifiDisplayStatus{enabled=" + mEnabled
                + ", connectedDisplay=" + mConnectedDisplay
                + ", knownDisplays=" + Arrays.toString(mKnownDisplays)
                + ", scanInProgress=" + mScanInProgress
                + ", connectionInProgress=" + mConnectionInProgress
                + "}";
    }
}
