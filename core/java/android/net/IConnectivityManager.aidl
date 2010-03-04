/**
 * Copyright (c) 2008, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 *
 *     http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 */

package android.net;

import android.net.NetworkInfo;
import android.os.IBinder;

/**
 * Interface that answers queries about, and allows changing, the
 * state of network connectivity.
 */
/** {@hide} */
interface IConnectivityManager
{
    void setNetworkPreference(int pref);

    int getNetworkPreference();

    NetworkInfo getActiveNetworkInfo();

    NetworkInfo getNetworkInfo(int networkType);

    NetworkInfo[] getAllNetworkInfo();

    boolean setRadios(boolean onOff);

    boolean setRadio(int networkType, boolean turnOn);

    int startUsingNetworkFeature(int networkType, in String feature,
            in IBinder binder);

    int stopUsingNetworkFeature(int networkType, in String feature);

    boolean requestRouteToHost(int networkType, int hostAddress);

    boolean getBackgroundDataSetting();

    void setBackgroundDataSetting(boolean allowBackgroundData);

    boolean getMobileDataEnabled();

    void setMobileDataEnabled(boolean enabled);

    int tether(String iface);

    int untether(String iface);

    int getLastTetherError(String iface);

    boolean isTetheringSupported();

    String[] getTetherableIfaces();

    String[] getTetheredIfaces();

    String[] getTetheringErroredIfaces();

    String[] getTetherableUsbRegexs();

    String[] getTetherableWifiRegexs();
}
