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
    private final WifiDisplay mActiveDisplay;
    private final WifiDisplay[] mAvailableDisplays;
    private final WifiDisplay[] mRememberedDisplays;

    /** Feature state: Wifi display is not available on this device. */
    public static final int FEATURE_STATE_UNAVAILABLE = 0;
    /** Feature state: Wifi display is disabled, probably because Wifi is disabled. */
    public static final int FEATURE_STATE_DISABLED = 1;
    /** Feature state: Wifi display is turned off in settings. */
    public static final int FEATURE_STATE_OFF = 2;
    /** Feature state: Wifi display is turned on in settings. */
    public static final int FEATURE_STATE_ON = 3;

    /** Scan state: Not currently scanning. */
    public static final int SCAN_STATE_NOT_SCANNING = 0;
    /** Scan state: Currently scanning. */
    public static final int SCAN_STATE_SCANNING = 1;

    /** Display state: Not connected. */
    public static final int DISPLAY_STATE_NOT_CONNECTED = 0;
    /** Display state: Connecting to active display. */
    public static final int DISPLAY_STATE_CONNECTING = 1;
    /** Display state: Connected to active display. */
    public static final int DISPLAY_STATE_CONNECTED = 2;

    public static final Creator<WifiDisplayStatus> CREATOR = new Creator<WifiDisplayStatus>() {
        public WifiDisplayStatus createFromParcel(Parcel in) {
            int featureState = in.readInt();
            int scanState = in.readInt();
            int activeDisplayState= in.readInt();

            WifiDisplay activeDisplay = null;
            if (in.readInt() != 0) {
                activeDisplay = WifiDisplay.CREATOR.createFromParcel(in);
            }

            WifiDisplay[] availableDisplays = WifiDisplay.CREATOR.newArray(in.readInt());
            for (int i = 0; i < availableDisplays.length; i++) {
                availableDisplays[i] = WifiDisplay.CREATOR.createFromParcel(in);
            }

            WifiDisplay[] rememberedDisplays = WifiDisplay.CREATOR.newArray(in.readInt());
            for (int i = 0; i < rememberedDisplays.length; i++) {
                rememberedDisplays[i] = WifiDisplay.CREATOR.createFromParcel(in);
            }

            return new WifiDisplayStatus(featureState, scanState, activeDisplayState,
                    activeDisplay, availableDisplays, rememberedDisplays);
        }

        public WifiDisplayStatus[] newArray(int size) {
            return new WifiDisplayStatus[size];
        }
    };

    public WifiDisplayStatus() {
        this(FEATURE_STATE_UNAVAILABLE, SCAN_STATE_NOT_SCANNING, DISPLAY_STATE_NOT_CONNECTED,
                null, WifiDisplay.EMPTY_ARRAY, WifiDisplay.EMPTY_ARRAY);
    }

    public WifiDisplayStatus(int featureState, int scanState,
            int activeDisplayState, WifiDisplay activeDisplay,
            WifiDisplay[] availableDisplays, WifiDisplay[] rememberedDisplays) {
        if (availableDisplays == null) {
            throw new IllegalArgumentException("availableDisplays must not be null");
        }
        if (rememberedDisplays == null) {
            throw new IllegalArgumentException("rememberedDisplays must not be null");
        }

        mFeatureState = featureState;
        mScanState = scanState;
        mActiveDisplayState = activeDisplayState;
        mActiveDisplay = activeDisplay;
        mAvailableDisplays = availableDisplays;
        mRememberedDisplays = rememberedDisplays;
    }

    /**
     * Returns the state of the Wifi display feature on this device.
     * <p>
     * The value of this property reflects whether the device supports the Wifi display,
     * whether it has been enabled by the user and whether the prerequisites for
     * connecting to displays have been met.
     * </p>
     */
    public int getFeatureState() {
        return mFeatureState;
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
     * Gets the list of all available Wifi displays as reported by the most recent
     * scan, never null.
     * <p>
     * Some of these displays may already be remembered, others may be unknown.
     * </p>
     */
    public WifiDisplay[] getAvailableDisplays() {
        return mAvailableDisplays;
    }

    /**
     * Gets the list of all remembered Wifi displays, never null.
     * <p>
     * Not all remembered displays will necessarily be available.
     * </p>
     */
    public WifiDisplay[] getRememberedDisplays() {
        return mRememberedDisplays;
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

        dest.writeInt(mAvailableDisplays.length);
        for (WifiDisplay display : mAvailableDisplays) {
            display.writeToParcel(dest, flags);
        }

        dest.writeInt(mRememberedDisplays.length);
        for (WifiDisplay display : mRememberedDisplays) {
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
        return "WifiDisplayStatus{featureState=" + mFeatureState
                + ", scanState=" + mScanState
                + ", activeDisplayState=" + mActiveDisplayState
                + ", activeDisplay=" + mActiveDisplay
                + ", availableDisplays=" + Arrays.toString(mAvailableDisplays)
                + ", rememberedDisplays=" + Arrays.toString(mRememberedDisplays)
                + "}";
    }
}
