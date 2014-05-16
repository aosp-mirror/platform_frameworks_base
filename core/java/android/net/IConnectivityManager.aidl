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

import android.app.PendingIntent;
import android.net.LinkQualityInfo;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkQuotaInfo;
import android.net.NetworkRequest;
import android.net.NetworkState;
import android.net.ProxyInfo;
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

    NetworkInfo getActiveNetworkInfo();
    NetworkInfo getActiveNetworkInfoForUid(int uid);
    NetworkInfo getNetworkInfo(int networkType);
    NetworkInfo[] getAllNetworkInfo();

    NetworkInfo getProvisioningOrActiveNetworkInfo();

    boolean isNetworkSupported(int networkType);

    LinkProperties getActiveLinkProperties();
    LinkProperties getLinkPropertiesForType(int networkType);
    LinkProperties getLinkProperties(in Network network);

    NetworkCapabilities getNetworkCapabilities(in Network network);

    NetworkState[] getAllNetworkState();

    NetworkQuotaInfo getActiveNetworkQuotaInfo();
    boolean isActiveNetworkMetered();

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

    void reportBadNetwork(in Network network);

    ProxyInfo getGlobalProxy();

    void setGlobalProxy(in ProxyInfo p);

    ProxyInfo getProxy();

    void setDataDependency(int networkType, boolean met);

    boolean protectVpn(in ParcelFileDescriptor socket);

    boolean prepareVpn(String oldPackage, String newPackage);

    ParcelFileDescriptor establishVpn(in VpnConfig config);

    VpnConfig getVpnConfig();

    void startLegacyVpn(in VpnProfile profile);

    LegacyVpnInfo getLegacyVpnInfo();

    boolean updateLockdownVpn();

    void captivePortalCheckCompleted(in NetworkInfo info, boolean isCaptivePortal);

    void supplyMessenger(int networkType, in Messenger messenger);

    int findConnectionTypeForIface(in String iface);

    int checkMobileProvisioning(int suggestedTimeOutMs);

    String getMobileProvisioningUrl();

    String getMobileRedirectedProvisioningUrl();

    LinkQualityInfo getLinkQualityInfo(int networkType);

    LinkQualityInfo getActiveLinkQualityInfo();

    LinkQualityInfo[] getAllLinkQualityInfo();

    void setProvisioningNotificationVisible(boolean visible, int networkType, in String extraInfo,
            in String url);

    void setAirplaneMode(boolean enable);

    void registerNetworkFactory(in Messenger messenger, in String name);

    void unregisterNetworkFactory(in Messenger messenger);

    void registerNetworkAgent(in Messenger messenger, in NetworkInfo ni, in LinkProperties lp,
            in NetworkCapabilities nc, int score);

    NetworkRequest requestNetwork(in NetworkCapabilities networkCapabilities,
            in Messenger messenger, int timeoutSec, in IBinder binder);

    NetworkRequest pendingRequestForNetwork(in NetworkCapabilities networkCapabilities,
            in PendingIntent operation);

    NetworkRequest listenForNetwork(in NetworkCapabilities networkCapabilities,
            in Messenger messenger, in IBinder binder);

    void pendingListenForNetwork(in NetworkCapabilities networkCapabilities,
            in PendingIntent operation);

    void releaseNetworkRequest(in NetworkRequest networkRequest);
}
