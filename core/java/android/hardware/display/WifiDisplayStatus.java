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
    private final int mScanState;
    private final int mActiveDisplayState;
    private final WifiDisplay mActiveDisplay;
    private final WifiDisplay[] mKnownDisplays;

    public static final int SCAN_STATE_NOT_SCANNING = 0;
    public static final int SCAN_STATE_SCANNING = 1;

    public static final int DISPLAY_STATE_NOT_CONNECTED = 0;
    public static final int DISPLAY_STATE_CONNECTING = 1;
    public static final int DISPLAY_STATE_CONNECTED = 2;

    public static final Creator<WifiDisplayStatus> CREATOR = new Creator<WifiDisplayStatus>() {
        public WifiDisplayStatus createFromParcel(Parcel in) {
            boolean enabled = (in.readInt() != 0);
            int scanState = in.readInt();
            int activeDisplayState= in.readInt();

            WifiDisplay activeDisplay = null;
            if (in.readInt() != 0) {
                activeDisplay = WifiDisplay.CREATOR.createFromParcel(in);
            }

            WifiDisplay[] knownDisplays = WifiDisplay.CREATOR.newArray(in.readInt());
            for (int i = 0; i < knownDisplays.length; i++) {
                knownDisplays[i] = WifiDisplay.CREATOR.createFromParcel(in);
            }

            return new WifiDisplayStatus(enabled, scanState, activeDisplayState,
                    activeDisplay, knownDisplays);
        }

        public WifiDisplayStatus[] newArray(int size) {
            return new WifiDisplayStatus[size];
        }
    };

    public WifiDisplayStatus() {
        this(false, SCAN_STATE_NOT_SCANNING, DISPLAY_STATE_NOT_CONNECTED,
                null, WifiDisplay.EMPTY_ARRAY);
    }

    public WifiDisplayStatus(boolean enabled, int scanState, int activeDisplayState,
            WifiDisplay activeDisplay, WifiDisplay[] knownDisplays) {
        if (knownDisplays == null) {
            throw new IllegalArgumentException("knownDisplays must not be null");
        }

        mEnabled = enabled;
        mScanState = scanState;
        mActiveDisplayState = activeDisplayState;
        mActiveDisplay = activeDisplay;
        mKnownDisplays = knownDisplays;
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
     * Returns the current state of the Wifi display scan.
     *
     * @return One of: {@link #SCAN_STATE_NOT_SCANNING} or {@link #SCAN_STATE_SCANNING}.
     */
    public int getScanState() {
        return mScanState;
    }

    /**
     * Get the state of the currently active display.
     *
     * @return One of: {@link #DISPLAY_STATE_NOT_CONNECTED}, {@link #DISPLAY_STATE_CONNECTING},
     * or {@link #DISPLAY_STATE_CONNECTED}.
     */
    public int getActiveDisplayState() {
        return mActiveDisplayState;
    }

    /**
     * Gets the Wifi display that is currently active.  It may be connecting or
     * connected.
     */
    public WifiDisplay getActiveDisplay() {
        return mActiveDisplay;
    }

    /**
     * Gets the list of all known Wifi displays, never null.
     */
    public WifiDisplay[] getKnownDisplays() {
        return mKnownDisplays;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mEnabled ? 1 : 0);
        dest.writeInt(mScanState);
        dest.writeInt(mActiveDisplayState);

        if (mActiveDisplay != null) {
            dest.writeInt(1);
            mActiveDisplay.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }

        dest.writeInt(mKnownDisplays.length);
        for (WifiDisplay display : mKnownDisplays) {
            display.writeToParcel(dest, flags);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    // For debugging purposes only.
    @Override
    public String toString() {
        return "WifiDisplayStatus{enabled=" + mEnabled
                + ", scanState=" + mScanState
                + ", activeDisplayState=" + mActiveDisplayState
                + ", activeDisplay=" + mActiveDisplay
                + ", knownDisplays=" + Arrays.toString(mKnownDisplays)
                + "}";
    }
}
