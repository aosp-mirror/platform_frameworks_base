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
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkMisc;
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
    NetworkInfo getActiveNetworkInfo();
    NetworkInfo getActiveNetworkInfoForUid(int uid);
    NetworkInfo getNetworkInfo(int networkType);
    NetworkInfo getNetworkInfoForNetwork(in Network network);
    NetworkInfo[] getAllNetworkInfo();
    Network getNetworkForType(int networkType);
    Network[] getAllNetworks();
    NetworkCapabilities[] getDefaultNetworkCapabilitiesForUser(int userId);

    NetworkInfo getProvisioningOrActiveNetworkInfo();

    boolean isNetworkSupported(int networkType);

    LinkProperties getActiveLinkProperties();
    LinkProperties getLinkPropertiesForType(int networkType);
    LinkProperties getLinkProperties(in Network network);

    NetworkCapabilities getNetworkCapabilities(in Network network);

    NetworkState[] getAllNetworkState();

    NetworkQuotaInfo getActiveNetworkQuotaInfo();
    boolean isActiveNetworkMetered();

    boolean requestRouteToHostAddress(int networkType, in byte[] hostAddress);

    int tether(String iface);

    int untether(String iface);

    int getLastTetherError(String iface);

    boolean isTetheringSupported();

    String[] getTetherableIfaces();

    String[] getTetheredIfaces();

    String[] getTetheringErroredIfaces();

    String[] getTetheredDhcpRanges();

    String[] getTetherableUsbRegexs();

    String[] getTetherableWifiRegexs();

    String[] getTetherableBluetoothRegexs();

    int setUsbTethering(boolean enable);

    void reportInetCondition(int networkType, int percentage);

    void reportBadNetwork(in Network network);

    ProxyInfo getGlobalProxy();

    void setGlobalProxy(in ProxyInfo p);

    ProxyInfo getDefaultProxy();

    void setDataDependency(int networkType, boolean met);

    boolean prepareVpn(String oldPackage, String newPackage);

    void setVpnPackageAuthorization(boolean authorized);

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

    void setProvisioningNotificationVisible(boolean visible, int networkType, in String action);

    void setAirplaneMode(boolean enable);

    void registerNetworkFactory(in Messenger messenger, in String name);

    void unregisterNetworkFactory(in Messenger messenger);

    void registerNetworkAgent(in Messenger messenger, in NetworkInfo ni, in LinkProperties lp,
            in NetworkCapabilities nc, int score, in NetworkMisc misc);

    NetworkRequest requestNetwork(in NetworkCapabilities networkCapabilities,
            in Messenger messenger, int timeoutSec, in IBinder binder, int legacy);

    NetworkRequest pendingRequestForNetwork(in NetworkCapabilities networkCapabilities,
            in PendingIntent operation);

    void releasePendingNetworkRequest(in PendingIntent operation);

    NetworkRequest listenForNetwork(in NetworkCapabilities networkCapabilities,
            in Messenger messenger, in IBinder binder);

    void pendingListenForNetwork(in NetworkCapabilities networkCapabilities,
            in PendingIntent operation);

    void releaseNetworkRequest(in NetworkRequest networkRequest);

    int getRestoreDefaultNetworkDelay(int networkType);

    boolean addVpnAddress(String address, int prefixLength);
    boolean removeVpnAddress(String address, int prefixLength);
    boolean setUnderlyingNetworksForVpn(in Network[] networks);
}
