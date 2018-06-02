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
import android.net.wifi.ISoftApCallback;
import android.net.wifi.PasspointManagementObjectDefinition;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiActivityEnergyInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;

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
    int getSupportedFeatures();

    WifiActivityEnergyInfo reportActivityInfo();

    /**
     * Requests the controller activity info asynchronously.
     * The implementor is expected to reply with the
     * {@link android.net.wifi.WifiActivityEnergyInfo} object placed into the Bundle with the key
     * {@link android.os.BatteryStats#RESULT_RECEIVER_CONTROLLER_KEY}. The result code is ignored.
     */
    oneway void requestActivityInfo(in ResultReceiver result);

    ParceledListSlice getConfiguredNetworks();

    ParceledListSlice getPrivilegedConfiguredNetworks();

    WifiConfiguration getMatchingWifiConfig(in ScanResult scanResult);

    List<WifiConfiguration> getAllMatchingWifiConfigs(in ScanResult scanResult);

    List<OsuProvider> getMatchingOsuProviders(in ScanResult scanResult);

    int addOrUpdateNetwork(in WifiConfiguration config, String packageName);

    boolean addOrUpdatePasspointConfiguration(in PasspointConfiguration config, String packageName);

    boolean removePasspointConfiguration(in String fqdn, String packageName);

    List<PasspointConfiguration> getPasspointConfigurations();

    void queryPasspointIcon(long bssid, String fileName);

    int matchProviderWithCurrentNetwork(String fqdn);

    void deauthenticateNetwork(long holdoff, boolean ess);

    boolean removeNetwork(int netId, String packageName);

    boolean enableNetwork(int netId, boolean disableOthers, String packageName);

    boolean disableNetwork(int netId, String packageName);

    boolean startScan(String packageName);

    List<ScanResult> getScanResults(String callingPackage);

    void disconnect(String packageName);

    void reconnect(String packageName);

    void reassociate(String packageName);

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

    void releaseMulticastLock();

    void updateInterfaceIpState(String ifaceName, int mode);

    boolean startSoftAp(in WifiConfiguration wifiConfig);

    boolean stopSoftAp();

    int startLocalOnlyHotspot(in Messenger messenger, in IBinder binder, String packageName);

    void stopLocalOnlyHotspot();

    void startWatchLocalOnlyHotspot(in Messenger messenger, in IBinder binder);

    void stopWatchLocalOnlyHotspot();

    int getWifiApEnabledState();

    WifiConfiguration getWifiApConfiguration();

    boolean setWifiApConfiguration(in WifiConfiguration wifiConfig, String packageName);

    void notifyUserOfApBandConversion(String packageName);

    Messenger getWifiServiceMessenger(String packageName);

    void enableTdls(String remoteIPAddress, boolean enable);

    void enableTdlsWithMacAddress(String remoteMacAddress, boolean enable);

    String getCurrentNetworkWpsNfcConfigurationToken();

    void enableVerboseLogging(int verbose);

    int getVerboseLoggingLevel();

    void enableWifiConnectivityManager(boolean enabled);

    void disableEphemeralNetwork(String SSID, String packageName);

    void factoryReset(String packageName);

    Network getCurrentNetwork();

    byte[] retrieveBackupData();

    void restoreBackupData(in byte[] data);

    void restoreSupplicantBackupData(in byte[] supplicantData, in byte[] ipConfigData);

    void startSubscriptionProvisioning(in OsuProvider provider, in IProvisioningCallback callback);

    void registerSoftApCallback(in IBinder binder, in ISoftApCallback callback, int callbackIdentifier);

    void unregisterSoftApCallback(int callbackIdentifier);
}

