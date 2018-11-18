/**
 * Copyright (c) 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License") {
 *  throw new UnsupportedOperationException();
 }
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

package com.android.server.wifi;

import android.content.pm.ParceledListSlice;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.wifi.INetworkRequestMatchCallback;
import android.net.wifi.ISoftApCallback;
import android.net.wifi.ITrafficStateCallback;
import android.net.wifi.IWifiManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiActivityEnergyInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiNetworkSuggestion;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.os.IBinder;
import android.os.Messenger;
import android.os.ResultReceiver;
import android.os.WorkSource;

import java.util.List;

/**
 * Abstract class implementing IWifiManager with stub methods throwing runtime exceptions.
 *
 * This class is meant to be extended by real implementations of IWifiManager in order to facilitate
 * cross-repo changes to WiFi internal APIs, including the introduction of new APIs, the removal of
 * deprecated APIs, or the migration of existing API signatures.
 *
 * When an existing API is scheduled for removal, it can be removed from IWifiManager.aidl
 * immediately and marked as @Deprecated first in this class. Children inheriting this class are
 * then given a short grace period to update themselves before the @Deprecated stub is removed for
 * good. If the API scheduled for removal has a replacement or an overload (signature change),
 * these should be introduced before the stub is removed to allow children to migrate.
 */
public abstract class AbstractWifiService extends IWifiManager.Stub {

    private static final String TAG = AbstractWifiService.class.getSimpleName();

    @Override
    public int getSupportedFeatures() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WifiActivityEnergyInfo reportActivityInfo() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void requestActivityInfo(ResultReceiver result) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ParceledListSlice getConfiguredNetworks() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ParceledListSlice getPrivilegedConfiguredNetworks() {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns a WifiConfiguration matching this ScanResult
     * @param scanResult a single ScanResult Object
     * @return
     * @deprecated use {@link #getAllMatchingWifiConfigs(List)} instead.
     */
    @Deprecated
    public WifiConfiguration getMatchingWifiConfig(ScanResult scanResult) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns all matching WifiConfigurations for this ScanResult.
     * @param scanResult a single ScanResult Object
     * @return
     * @deprecated use {@link #getAllMatchingWifiConfigs(List)} instead.
     */
    @Deprecated
    public List<WifiConfiguration> getAllMatchingWifiConfigs(ScanResult scanResult) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<WifiConfiguration> getAllMatchingWifiConfigs(List<ScanResult> scanResults) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns a list of Hotspot 2.0 OSU (Online Sign-Up) providers associated with the given AP.
     *
     * @param scanResult a single ScanResult Object
     * @return
     * @deprecated use {@link #getMatchingOsuProviders(List)} instead.
     */
    @Deprecated
    public List<OsuProvider> getMatchingOsuProviders(ScanResult scanResult) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<OsuProvider> getMatchingOsuProviders(List<ScanResult> scanResults) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int addOrUpdateNetwork(WifiConfiguration config, String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addOrUpdatePasspointConfiguration(
            PasspointConfiguration config, String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removePasspointConfiguration(String fqdn, String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<PasspointConfiguration> getPasspointConfigurations() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void queryPasspointIcon(long bssid, String fileName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int matchProviderWithCurrentNetwork(String fqdn) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deauthenticateNetwork(long holdoff, boolean ess) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeNetwork(int netId, String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean enableNetwork(int netId, boolean disableOthers, String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean disableNetwork(int netId, String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean startScan(String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ScanResult> getScanResults(String callingPackage) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void disconnect(String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void reconnect(String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void reassociate(String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public WifiInfo getConnectionInfo(String callingPackage) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean setWifiEnabled(String packageName, boolean enable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getWifiEnabledState() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setCountryCode(String country) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getCountryCode() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isDualBandSupported() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean needs5GHzToAnyApBandConversion() {
        throw new UnsupportedOperationException();
    }

    @Override
    public DhcpInfo getDhcpInfo() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isScanAlwaysAvailable() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean acquireWifiLock(IBinder lock, int lockType, String tag, WorkSource ws) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateWifiLockWorkSource(IBinder lock, WorkSource ws) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean releaseWifiLock(IBinder lock) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void initializeMulticastFiltering() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isMulticastEnabled() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void acquireMulticastLock(IBinder binder, String tag) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void releaseMulticastLock(String tag) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateInterfaceIpState(String ifaceName, int mode) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean startSoftAp(WifiConfiguration wifiConfig) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean stopSoftAp() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int startLocalOnlyHotspot(Messenger messenger, IBinder binder, String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void stopLocalOnlyHotspot() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void startWatchLocalOnlyHotspot(Messenger messenger, IBinder binder) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void stopWatchLocalOnlyHotspot() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getWifiApEnabledState() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WifiConfiguration getWifiApConfiguration() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean setWifiApConfiguration(WifiConfiguration wifiConfig, String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void notifyUserOfApBandConversion(String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Messenger getWifiServiceMessenger(String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void enableTdls(String remoteIPAddress, boolean enable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void enableTdlsWithMacAddress(String remoteMacAddress, boolean enable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getCurrentNetworkWpsNfcConfigurationToken() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void enableVerboseLogging(int verbose) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getVerboseLoggingLevel() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void enableWifiConnectivityManager(boolean enabled) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void disableEphemeralNetwork(String SSID, String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void factoryReset(String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Network getCurrentNetwork() {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] retrieveBackupData() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void restoreBackupData(byte[] data) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void restoreSupplicantBackupData(byte[] supplicantData, byte[] ipConfigData) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void startSubscriptionProvisioning(
            OsuProvider provider, IProvisioningCallback callback) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void registerSoftApCallback(
            IBinder binder, ISoftApCallback callback, int callbackIdentifier) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unregisterSoftApCallback(int callbackIdentifier) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void registerTrafficStateCallback(
            IBinder binder, ITrafficStateCallback callback, int callbackIdentifier) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unregisterTrafficStateCallback(int callbackIdentifier) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void registerNetworkRequestMatchCallback(
            IBinder binder, INetworkRequestMatchCallback callback, int callbackIdentifier) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unregisterNetworkRequestMatchCallback(int callbackIdentifier) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addNetworkSuggestions(
            List<WifiNetworkSuggestion> networkSuggestions, String callingPackageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeNetworkSuggestions(
            List<WifiNetworkSuggestion> networkSuggestions, String callingPackageName) {
        throw new UnsupportedOperationException();
    }
}
