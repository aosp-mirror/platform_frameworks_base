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
 * limitations under the License
 */

package android.net;

import android.annotation.DrawableRes;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.graphics.drawable.Drawable;
import android.net.wifi.WifiManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Utility methods for working with network badging.
 *
 * @removed
 *
 */
@Deprecated
public class NetworkBadging {

    @IntDef({BADGING_NONE, BADGING_SD, BADGING_HD, BADGING_4K})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Badging {}

    public static final int BADGING_NONE = 0;
    public static final int BADGING_SD = 10;
    public static final int BADGING_HD = 20;
    public static final int BADGING_4K = 30;

    private NetworkBadging() {}

    /**
     * Returns a Wi-Fi icon for a network with a given signal level and badging value.
     *
     * @param signalLevel The level returned by {@link WifiManager#calculateSignalLevel(int, int)}
     *                    for a network. Must be between 0 and {@link WifiManager#RSSI_LEVELS}-1.
     * @param badging  {@see NetworkBadging#Badging}, retrieved from
     *                 {@link ScoredNetwork#calculateBadge(int)}.
     * @param theme The theme for the current application, may be null.
     * @return Drawable for the given icon
     * @throws IllegalArgumentException if {@code signalLevel} is out of range or {@code badging}
     *                                  is an invalid value
     */
    @NonNull public static Drawable getWifiIcon(
            @IntRange(from=0, to=4) int signalLevel, @Badging int badging, @Nullable Theme theme) {
        return Resources.getSystem().getDrawable(getWifiSignalResource(signalLevel), theme);
    }

    /**
     * Returns the wifi signal resource id for the given signal level.
     *
     * <p>This wifi signal resource is a wifi icon to be displayed by itself when there is no badge.
     *
     * @param signalLevel The level returned by {@link WifiManager#calculateSignalLevel(int, int)}
     *                    for a network. Must be between 0 and {@link WifiManager#RSSI_LEVELS}-1.
     * @return the @DrawableRes for the icon
     * @throws IllegalArgumentException for an invalid signal level
     * @hide
     */
    @DrawableRes private static int getWifiSignalResource(int signalLevel) {
        switch (signalLevel) {
            case 0:
                return com.android.internal.R.drawable.ic_wifi_signal_0;
            case 1:
                return com.android.internal.R.drawable.ic_wifi_signal_1;
            case 2:
                return com.android.internal.R.drawable.ic_wifi_signal_2;
            case 3:
                return com.android.internal.R.drawable.ic_wifi_signal_3;
            case 4:
                return com.android.internal.R.drawable.ic_wifi_signal_4;
            default:
                throw new IllegalArgumentException("Invalid signal level: " + signalLevel);
        }
    }
}
