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

import android.net.LinkQualityInfo;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.NetworkQuotaInfo;
import android.net.NetworkState;
import android.net.ProxyProperties;
import android.os.IBinder;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;

import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;

/**
 * Interface that answers queries about, and allows changing, the
 * state of network connectivity.
 */
/** {@hide} */
interface IConnectivityManager
{
    // Keep this in sync with framework/native/services/connectivitymanager/ConnectivityManager.h
    void markSocketAsUser(in ParcelFileDescriptor socket, int uid);

    void setNetworkPreference(int pref);

    int getNetworkPreference();

    NetworkInfo getActiveNetworkInfo();
    NetworkInfo getActiveNetworkInfoForUid(int uid);
    NetworkInfo getNetworkInfo(int networkType);
    NetworkInfo[] getAllNetworkInfo();

    NetworkInfo getProvisioningOrActiveNetworkInfo();

    boolean isNetworkSupported(int networkType);

    LinkProperties getActiveLinkProperties();
    LinkProperties getLinkProperties(int networkType);

    NetworkState[] getAllNetworkState();

    NetworkQuotaInfo getActiveNetworkQuotaInfo();
    boolean isActiveNetworkMetered();

    boolean setRadios(boolean onOff);

    boolean setRadio(int networkType, boolean turnOn);

    int startUsingNetworkFeature(int networkType, in String feature,
            in IBinder binder);

    int stopUsingNetworkFeature(int networkType, in String feature);

    boolean requestRouteToHost(int networkType, int hostAddress, String packageName);

    boolean requestRouteToHostAddress(int networkType, in byte[] hostAddress, String packageName);

    boolean getMobileDataEnabled();
    void setMobileDataEnabled(boolean enabled);

    /** Policy control over specific {@link NetworkStateTracker}. */
    void setPolicyDataEnable(int networkType, boolean enabled);

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

    VpnConfig getVpnConfig();

    void startLegacyVpn(in VpnProfile profile);

    LegacyVpnInfo getLegacyVpnInfo();

    boolean updateLockdownVpn();

    void captivePortalCheckComplete(in NetworkInfo info);

    void captivePortalCheckCompleted(in NetworkInfo info, boolean isCaptivePortal);

    void supplyMessenger(int networkType, in Messenger messenger);

    int findConnectionTypeForIface(in String iface);

    int checkMobileProvisioning(int suggestedTimeOutMs);

    String getMobileProvisioningUrl();

    String getMobileRedirectedProvisioningUrl();

    LinkQualityInfo getLinkQualityInfo(int networkType);

    LinkQualityInfo getActiveLinkQualityInfo();

    LinkQualityInfo[] getAllLinkQualityInfo();

    void setProvisioningNotificationVisible(boolean visible, int networkType, in String extraInfo, in String url);

    void setAirplaneMode(boolean enable);
}
