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

package android.net.wifi;

import android.content.pm.ParceledListSlice;

import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.IProvisioningCallback;

import android.net.DhcpInfo;
import android.net.Network;
import android.net.wifi.IActionListener;
import android.net.wifi.IDppCallback;
import android.net.wifi.INetworkRequestMatchCallback;
import android.net.wifi.ISoftApCallback;
import android.net.wifi.ITrafficStateCallback;
import android.net.wifi.ITxPacketCountListener;
import android.net.wifi.IOnWifiUsabilityStatsListener;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiActivityEnergyInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiNetworkSuggestion;

import android.os.Messenger;
import android.os.ResultReceiver;
import android.os.WorkSource;

/**
 * Interface that allows controlling and querying Wi-Fi connectivity.
 *
 * {@hide}
 */
interface IWifiManager
{
    long getSupportedFeatures();

    WifiActivityEnergyInfo reportActivityInfo();

    /**
     * Requests the controller activity info asynchronously.
     * The implementor is expected to reply with the
     * {@link android.net.wifi.WifiActivityEnergyInfo} object placed into the Bundle with the key
     * {@link android.os.BatteryStats#RESULT_RECEIVER_CONTROLLER_KEY}. The result code is ignored.
     */
    oneway void requestActivityInfo(in ResultReceiver result);

    ParceledListSlice getConfiguredNetworks(String packageName);

    ParceledListSlice getPrivilegedConfiguredNetworks(String packageName);

    Map getAllMatchingFqdnsForScanResults(in List<ScanResult> scanResult);

    Map getMatchingOsuProviders(in List<ScanResult> scanResult);

    Map getMatchingPasspointConfigsForOsuProviders(in List<OsuProvider> osuProviders);

    int addOrUpdateNetwork(in WifiConfiguration config, String packageName);

    boolean addOrUpdatePasspointConfiguration(in PasspointConfiguration config, String packageName);

    boolean removePasspointConfiguration(in String fqdn, String packageName);

    List<PasspointConfiguration> getPasspointConfigurations(in String packageName);

    List<WifiConfiguration> getWifiConfigsForPasspointProfiles(in List<String> fqdnList);

    void queryPasspointIcon(long bssid, String fileName);

    int matchProviderWithCurrentNetwork(String fqdn);

    void deauthenticateNetwork(long holdoff, boolean ess);

    boolean removeNetwork(int netId, String packageName);

    boolean enableNetwork(int netId, boolean disableOthers, String packageName);

    boolean disableNetwork(int netId, String packageName);

    boolean startScan(String packageName);

    List<ScanResult> getScanResults(String callingPackage);

    boolean disconnect(String packageName);

    boolean reconnect(String packageName);

    boolean reassociate(String packageName);

    WifiInfo getConnectionInfo(String callingPackage);

    boolean setWifiEnabled(String packageName, boolean enable);

    int getWifiEnabledState();

    void setCountryCode(String country);

    String getCountryCode();

    boolean isDualBandSupported();

    boolean needs5GHzToAnyApBandConversion();

    DhcpInfo getDhcpInfo();

    boolean isScanAlwaysAvailable();

    boolean acquireWifiLock(IBinder lock, int lockType, String tag, in WorkSource ws);

    void updateWifiLockWorkSource(IBinder lock, in WorkSource ws);

    boolean releaseWifiLock(IBinder lock);

    void initializeMulticastFiltering();

    boolean isMulticastEnabled();

    void acquireMulticastLock(IBinder binder, String tag);

    void releaseMulticastLock(String tag);

    void updateInterfaceIpState(String ifaceName, int mode);

    boolean startSoftAp(in WifiConfiguration wifiConfig);

    boolean stopSoftAp();

    int startLocalOnlyHotspot(in Messenger messenger, in IBinder binder, String packageName);

    void stopLocalOnlyHotspot();

    void startWatchLocalOnlyHotspot(in Messenger messenger, in IBinder binder);

    void stopWatchLocalOnlyHotspot();

    @UnsupportedAppUsage
    int getWifiApEnabledState();

    @UnsupportedAppUsage
    WifiConfiguration getWifiApConfiguration();

    boolean setWifiApConfiguration(in WifiConfiguration wifiConfig, String packageName);

    void notifyUserOfApBandConversion(String packageName);

    void enableTdls(String remoteIPAddress, boolean enable);

    void enableTdlsWithMacAddress(String remoteMacAddress, boolean enable);

    String getCurrentNetworkWpsNfcConfigurationToken();

    void enableVerboseLogging(int verbose);

    int getVerboseLoggingLevel();

    void enableWifiConnectivityManager(boolean enabled);

    void disableEphemeralNetwork(String SSID, String packageName);

    void factoryReset(String packageName);

    @UnsupportedAppUsage
    Network getCurrentNetwork();

    byte[] retrieveBackupData();

    void restoreBackupData(in byte[] data);

    void restoreSupplicantBackupData(in byte[] supplicantData, in byte[] ipConfigData);

    void startSubscriptionProvisioning(in OsuProvider provider, in IProvisioningCallback callback);

    void registerSoftApCallback(in IBinder binder, in ISoftApCallback callback, int callbackIdentifier);

    void unregisterSoftApCallback(int callbackIdentifier);

    void addOnWifiUsabilityStatsListener(in IBinder binder, in IOnWifiUsabilityStatsListener listener, int listenerIdentifier);

    void removeOnWifiUsabilityStatsListener(int listenerIdentifier);

    void registerTrafficStateCallback(in IBinder binder, in ITrafficStateCallback callback, int callbackIdentifier);

    void unregisterTrafficStateCallback(int callbackIdentifier);

    void registerNetworkRequestMatchCallback(in IBinder binder, in INetworkRequestMatchCallback callback, int callbackIdentifier);

    void unregisterNetworkRequestMatchCallback(int callbackIdentifier);

    int addNetworkSuggestions(in List<WifiNetworkSuggestion> networkSuggestions, in String packageName);

    int removeNetworkSuggestions(in List<WifiNetworkSuggestion> networkSuggestions, in String packageName);

    List<WifiNetworkSuggestion> getNetworkSuggestions(in String packageName);

    String[] getFactoryMacAddresses();

    void setDeviceMobilityState(int state);

    void startDppAsConfiguratorInitiator(in IBinder binder, in String enrolleeUri,
        int selectedNetworkId, int netRole, in IDppCallback callback);

    void startDppAsEnrolleeInitiator(in IBinder binder, in String configuratorUri,
        in IDppCallback callback);

    void stopDppSession();

    void updateWifiUsabilityScore(int seqNum, int score, int predictionHorizonSec);

    oneway void connect(in WifiConfiguration config, int netId, in IBinder binder, in IActionListener listener, int callbackIdentifier);

    oneway void save(in WifiConfiguration config, in IBinder binder, in IActionListener listener, int callbackIdentifier);

    oneway void forget(int netId, in IBinder binder, in IActionListener listener, int callbackIdentifier);

    oneway void getTxPacketCount(String packageName, in IBinder binder, in ITxPacketCountListener listener, int callbackIdentifier);
}
