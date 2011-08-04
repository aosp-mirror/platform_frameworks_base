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

import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.NetworkQuotaInfo;
import android.net.NetworkState;
import android.net.ProxyProperties;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;

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
    NetworkInfo getActiveNetworkInfoForUid(int uid);
    NetworkInfo getNetworkInfo(int networkType);
    NetworkInfo[] getAllNetworkInfo();

    LinkProperties getActiveLinkProperties();
    LinkProperties getLinkProperties(int networkType);

    NetworkState[] getAllNetworkState();

    NetworkQuotaInfo getActiveNetworkQuotaInfo();

    boolean setRadios(boolean onOff);

    boolean setRadio(int networkType, boolean turnOn);

    int startUsingNetworkFeature(int networkType, in String feature,
            in IBinder binder);

    int stopUsingNetworkFeature(int networkType, in String feature);

    boolean requestRouteToHost(int networkType, int hostAddress);

    boolean requestRouteToHostAddress(int networkType, in byte[] hostAddress);

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

    String[] getTetherableBluetoothRegexs();

    int setUsbTethering(boolean enable);

    void requestNetworkTransitionWakelock(in String forWhom);

    void reportInetCondition(int networkType, int percentage);

    ProxyProperties getGlobalProxy();

    void setGlobalProxy(in ProxyProperties p);

    ProxyProperties getProxy();

    void setDataDependency(int networkType, boolean met);

    boolean protectVpn(in ParcelFileDescriptor socket);

    boolean prepareVpn(String oldPackage, String newPackage);

    ParcelFileDescriptor establishVpn(in VpnConfig config);

    void startLegacyVpn(in VpnConfig config, in String[] racoon, in String[] mtpd);

    LegacyVpnInfo getLegacyVpnInfo();
}
