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

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * Describes the current global state of Wifi display connectivity, including the
 * currently connected display and all available or remembered displays.
 * <p>
 * This object is immutable.
 * </p>
 *
 * @hide
 */
public final class WifiDisplayStatus implements Parcelable {
    private final int mFeatureState;
    private final int mScanState;
    private final int mActiveDisplayState;
    @UnsupportedAppUsage
    private final WifiDisplay mActiveDisplay;
    @UnsupportedAppUsage
    private final WifiDisplay[] mDisplays;

    /** Session info needed for Miracast Certification */
    private final WifiDisplaySessionInfo mSessionInfo;

    /** Feature state: Wifi display is not available on this device. */
    public static final int FEATURE_STATE_UNAVAILABLE = 0;
    /** Feature state: Wifi display is disabled, probably because Wifi is disabled. */
    public static final int FEATURE_STATE_DISABLED = 1;
    /** Feature state: Wifi display is turned off in settings. */
    public static final int FEATURE_STATE_OFF = 2;
    /** Feature state: Wifi display is turned on in settings. */
    @UnsupportedAppUsage
    public static final int FEATURE_STATE_ON = 3;

    /** Scan state: Not currently scanning. */
    @UnsupportedAppUsage
    public static final int SCAN_STATE_NOT_SCANNING = 0;
    /** Scan state: Currently scanning. */
    public static final int SCAN_STATE_SCANNING = 1;

    /** Display state: Not connected. */
    @UnsupportedAppUsage
    public static final int DISPLAY_STATE_NOT_CONNECTED = 0;
    /** Display state: Connecting to active display. */
    @UnsupportedAppUsage
    public static final int DISPLAY_STATE_CONNECTING = 1;
    /** Display state: Connected to active display. */
    @UnsupportedAppUsage
    public static final int DISPLAY_STATE_CONNECTED = 2;

    public static final @android.annotation.NonNull Creator<WifiDisplayStatus> CREATOR = new Creator<WifiDisplayStatus>() {
        public WifiDisplayStatus createFromParcel(Parcel in) {
            int featureState = in.readInt();
            int scanState = in.readInt();
            int activeDisplayState= in.readInt();

            WifiDisplay activeDisplay = null;
            if (in.readInt() != 0) {
                activeDisplay = WifiDisplay.CREATOR.createFromParcel(in);
            }

            WifiDisplay[] displays = WifiDisplay.CREATOR.newArray(in.readInt());
            for (int i = 0; i < displays.length; i++) {
                displays[i] = WifiDisplay.CREATOR.createFromParcel(in);
            }

            WifiDisplaySessionInfo sessionInfo =
                    WifiDisplaySessionInfo.CREATOR.createFromParcel(in);

            return new WifiDisplayStatus(featureState, scanState, activeDisplayState,
                    activeDisplay, displays, sessionInfo);
        }

        public WifiDisplayStatus[] newArray(int size) {
            return new WifiDisplayStatus[size];
        }
    };

    public WifiDisplayStatus() {
        this(FEATURE_STATE_UNAVAILABLE, SCAN_STATE_NOT_SCANNING, DISPLAY_STATE_NOT_CONNECTED,
                null, WifiDisplay.EMPTY_ARRAY, null);
    }

    public WifiDisplayStatus(int featureState, int scanState, int activeDisplayState,
            WifiDisplay activeDisplay, WifiDisplay[] displays, WifiDisplaySessionInfo sessionInfo) {
        if (displays == null) {
            throw new IllegalArgumentException("displays must not be null");
        }

        mFeatureState = featureState;
        mScanState = scanState;
        mActiveDisplayState = activeDisplayState;
        mActiveDisplay = activeDisplay;
        mDisplays = displays;

        mSessionInfo = (sessionInfo != null) ? sessionInfo : new WifiDisplaySessionInfo();
    }

    /**
     * Returns the state of the Wifi display feature on this device.
     * <p>
     * The value of this property reflects whether the device supports the Wifi display,
     * whether it has been enabled by the user and whether the prerequisites for
     * connecting to displays have been met.
     * </p>
     */
    @UnsupportedAppUsage
    public int getFeatureState() {
        return mFeatureState;
    }

    /**
     * Returns the current state of the Wifi display scan.
     *
     * @return One of: {@link #SCAN_STATE_NOT_SCANNING} or {@link #SCAN_STATE_SCANNING}.
     */
    @UnsupportedAppUsage
    public int getScanState() {
        return mScanState;
    }

    /**
     * Get the state of the currently active display.
     *
     * @return One of: {@link #DISPLAY_STATE_NOT_CONNECTED}, {@link #DISPLAY_STATE_CONNECTING},
     * or {@link #DISPLAY_STATE_CONNECTED}.
     */
    @UnsupportedAppUsage
    public int getActiveDisplayState() {
        return mActiveDisplayState;
    }

    /**
     * Gets the Wifi display that is currently active.  It may be connecting or
     * connected.
     */
    @UnsupportedAppUsage
    public WifiDisplay getActiveDisplay() {
        return mActiveDisplay;
    }

    /**
     * Gets the list of Wifi displays, returns a combined list of all available
     * Wifi displays as reported by the most recent scan, and all remembered
     * Wifi displays (not necessarily available at the time).
     */
    @UnsupportedAppUsage
    public WifiDisplay[] getDisplays() {
        return mDisplays;
    }

    /**
     * Gets the Wifi display session info (required for certification only)
     */
    public WifiDisplaySessionInfo getSessionInfo() {
        return mSessionInfo;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mFeatureState);
        dest.writeInt(mScanState);
        dest.writeInt(mActiveDisplayState);

        if (mActiveDisplay != null) {
            dest.writeInt(1);
            mActiveDisplay.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }

        dest.writeInt(mDisplays.length);
        for (WifiDisplay display : mDisplays) {
            display.writeToParcel(dest, flags);
        }

        mSessionInfo.writeToParcel(dest, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    // For debugging purposes only.
    @Override
    public String toString() {
        return "WifiDisplayStatus{featureState=" + mFeatureState
                + ", scanState=" + mScanState
                + ", activeDisplayState=" + mActiveDisplayState
                + ", activeDisplay=" + mActiveDisplay
                + ", displays=" + Arrays.toString(mDisplays)
                + ", sessionInfo=" + mSessionInfo
                + "}";
    }
}
