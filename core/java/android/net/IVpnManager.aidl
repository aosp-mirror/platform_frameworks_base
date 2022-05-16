/**
 * Copyright (c) 2020, The Android Open Source Project
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

import android.net.Network;
import android.net.VpnProfileState;

import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;

/**
 * Interface that manages VPNs.
 */
/** {@hide} */
interface IVpnManager {
    /** VpnService APIs */
    boolean prepareVpn(String oldPackage, String newPackage, int userId);
    void setVpnPackageAuthorization(String packageName, int userId, int vpnType);
    ParcelFileDescriptor establishVpn(in VpnConfig config);
    boolean addVpnAddress(String address, int prefixLength);
    boolean removeVpnAddress(String address, int prefixLength);
    boolean setUnderlyingNetworksForVpn(in Network[] networks);

    /** VpnManager APIs */
    boolean provisionVpnProfile(in VpnProfile profile, String packageName);
    void deleteVpnProfile(String packageName);
    String startVpnProfile(String packageName);
    void stopVpnProfile(String packageName);
    VpnProfileState getProvisionedVpnProfileState(String packageName);
    boolean setAppExclusionList(int userId, String vpnPackage, in List<String> excludedApps);
    List<String> getAppExclusionList(int userId, String vpnPackage);

    /** Always-on VPN APIs */
    boolean isAlwaysOnVpnPackageSupported(int userId, String packageName);
    boolean setAlwaysOnVpnPackage(int userId, String packageName, boolean lockdown,
            in List<String> lockdownAllowlist);
    String getAlwaysOnVpnPackage(int userId);
    boolean isVpnLockdownEnabled(int userId);
    List<String> getVpnLockdownAllowlist(int userId);
    boolean isCallerCurrentAlwaysOnVpnApp();
    boolean isCallerCurrentAlwaysOnVpnLockdownApp();

    /** Legacy VPN APIs */
    void startLegacyVpn(in VpnProfile profile);
    LegacyVpnInfo getLegacyVpnInfo(int userId);
    boolean updateLockdownVpn();

    /** General system APIs */
    VpnConfig getVpnConfig(int userId);
    void factoryReset();
}
