/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.server.net;

import static android.net.NetworkPolicyManager.POLICY_NONE;
import static android.net.NetworkPolicyManager.POLICY_REJECT_METERED_BACKGROUND;
import static android.net.wifi.WifiInfo.removeDoubleQuotes;
import static com.android.server.net.NetworkPolicyManagerService.newWifiPolicy;
import static com.android.server.net.NetworkPolicyManagerService.TAG;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.content.Context;
import android.net.INetworkPolicyManager;
import android.net.NetworkPolicy;
import android.net.NetworkTemplate;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.util.Log;

class NetworkPolicyManagerShellCommand extends ShellCommand {

    private final INetworkPolicyManager mInterface;
    private final WifiManager mWifiManager;

    NetworkPolicyManagerShellCommand(Context context, INetworkPolicyManager service) {
        mInterface = service;
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter pw = getOutPrintWriter();
        try {
            switch(cmd) {
                case "get":
                    return runGet();
                case "set":
                    return runSet();
                case "list":
                    return runList();
                case "add":
                    return runAdd();
                case "remove":
                    return runRemove();
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Network policy manager (netpolicy) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("");
        pw.println("  add restrict-background-whitelist UID");
        pw.println("    Adds a UID to the whitelist for restrict background usage.");
        pw.println("  add restrict-background-blacklist UID");
        pw.println("    Adds a UID to the blacklist for restrict background usage.");
        pw.println("  get restrict-background");
        pw.println("    Gets the global restrict background usage status.");
        pw.println("  list wifi-networks [BOOLEAN]");
        pw.println("    Lists all saved wifi networks and whether they are metered or not.");
        pw.println("    If a boolean argument is passed, filters just the metered (or unmetered)");
        pw.println("    networks.");
        pw.println("  list restrict-background-whitelist");
        pw.println("    Lists UIDs that are whitelisted for restrict background usage.");
        pw.println("  list restrict-background-blacklist");
        pw.println("    Lists UIDs that are blacklisted for restrict background usage.");
        pw.println("  remove restrict-background-whitelist UID");
        pw.println("    Removes a UID from the whitelist for restrict background usage.");
        pw.println("  remove restrict-background-blacklist UID");
        pw.println("    Removes a UID from the blacklist for restrict background usage.");
        pw.println("  set metered-network ID BOOLEAN");
        pw.println("    Toggles whether the given wi-fi network is metered.");
        pw.println("  set restrict-background BOOLEAN");
        pw.println("    Sets the global restrict background usage status.");
    }

    private int runGet() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final String type = getNextArg();
        if (type == null) {
            pw.println("Error: didn't specify type of data to get");
            return -1;
        }
        switch(type) {
            case "restrict-background":
                return getRestrictBackground();
        }
        pw.println("Error: unknown get type '" + type + "'");
        return -1;
    }

    private int runSet() throws RemoteException  {
        final PrintWriter pw = getOutPrintWriter();
        final String type = getNextArg();
        if (type == null) {
            pw.println("Error: didn't specify type of data to set");
            return -1;
        }
        switch(type) {
            case "metered-network":
                return setMeteredWifiNetwork();
            case "restrict-background":
                return setRestrictBackground();
        }
        pw.println("Error: unknown set type '" + type + "'");
        return -1;
    }

    private int runList() throws RemoteException  {
        final PrintWriter pw = getOutPrintWriter();
        final String type = getNextArg();
        if (type == null) {
            pw.println("Error: didn't specify type of data to list");
            return -1;
        }
        switch(type) {
            case "wifi-networks":
                return listWifiNetworks();
            case "restrict-background-whitelist":
                return listRestrictBackgroundWhitelist();
            case "restrict-background-blacklist":
                return listRestrictBackgroundBlacklist();
        }
        pw.println("Error: unknown list type '" + type + "'");
        return -1;
    }

    private int runAdd() throws RemoteException  {
        final PrintWriter pw = getOutPrintWriter();
        final String type = getNextArg();
        if (type == null) {
            pw.println("Error: didn't specify type of data to add");
            return -1;
        }
        switch(type) {
            case "restrict-background-whitelist":
                return addRestrictBackgroundWhitelist();
            case "restrict-background-blacklist":
                return addRestrictBackgroundBlacklist();
        }
        pw.println("Error: unknown add type '" + type + "'");
        return -1;
    }

    private int runRemove() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final String type = getNextArg();
        if (type == null) {
            pw.println("Error: didn't specify type of data to remove");
            return -1;
        }
        switch(type) {
            case "restrict-background-whitelist":
                return removeRestrictBackgroundWhitelist();
            case "restrict-background-blacklist":
                return removeRestrictBackgroundBlacklist();
        }
        pw.println("Error: unknown remove type '" + type + "'");
        return -1;
    }

    private int listRestrictBackgroundWhitelist() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final int[] uids = mInterface.getRestrictBackgroundWhitelistedUids();
        pw.print("Restrict background whitelisted UIDs: ");
        if (uids.length == 0) {
            pw.println("none");
        } else {
            for (int i = 0; i < uids.length; i++) {
                int uid = uids[i];
                pw.print(uid);
                pw.print(' ');
            }
        }
        pw.println();
        return 0;
    }

    private int listRestrictBackgroundBlacklist() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();

        final int[] uids = mInterface.getUidsWithPolicy(POLICY_REJECT_METERED_BACKGROUND);
        pw.print("Restrict background blacklisted UIDs: ");
        if (uids.length == 0) {
            pw.println("none");
        } else {
            for (int i = 0; i < uids.length; i++) {
                int uid = uids[i];
                pw.print(uid);
                pw.print(' ');
            }
        }
        pw.println();
        return 0;
    }

    private int getRestrictBackground() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        pw.print("Restrict background status: ");
        pw.println(mInterface.getRestrictBackground() ? "enabled" : "disabled");
        return 0;
    }

    private int setRestrictBackground() throws RemoteException {
        final int enabled = getNextBooleanArg();
        if (enabled < 0) {
            return enabled;
        }
        mInterface.setRestrictBackground(enabled > 0);
        return 0;
    }

    private int addRestrictBackgroundWhitelist() throws RemoteException {
      final int uid = getUidFromNextArg();
      if (uid < 0) {
          return uid;
      }
      mInterface.addRestrictBackgroundWhitelistedUid(uid);
      return 0;
    }

    private int removeRestrictBackgroundWhitelist() throws RemoteException {
        final int uid = getUidFromNextArg();
        if (uid < 0) {
            return uid;
        }
        mInterface.removeRestrictBackgroundWhitelistedUid(uid);
        return 0;
    }

    private int addRestrictBackgroundBlacklist() throws RemoteException {
        final int uid = getUidFromNextArg();
        if (uid < 0) {
            return uid;
        }
        mInterface.setUidPolicy(uid, POLICY_REJECT_METERED_BACKGROUND);
        return 0;
    }

    private int removeRestrictBackgroundBlacklist() throws RemoteException {
        final int uid = getUidFromNextArg();
        if (uid < 0) {
            return uid;
        }
        mInterface.setUidPolicy(uid, POLICY_NONE);
        return 0;
    }

    private int listWifiNetworks() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final String arg = getNextArg();
        final Boolean filter = arg == null ? null : Boolean.valueOf(arg);
        for (NetworkPolicy policy : getWifiPolicies()) {
            if (filter != null && filter.booleanValue() != policy.metered) {
                continue;
            }
            pw.print(getNetworkId(policy));
            pw.print(';');
            pw.println(policy.metered);
        }
        return 0;
    }

    private int setMeteredWifiNetwork() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final String id = getNextArg();
        if (id == null) {
            pw.println("Error: didn't specify ID");
            return -1;
        }
        final String arg = getNextArg();
        if (arg == null) {
            pw.println("Error: didn't specify BOOLEAN");
            return -1;
        }
        final boolean metered = Boolean.valueOf(arg);
        final NetworkPolicy[] policies = mInterface.getNetworkPolicies(null);
        boolean changed = false;
        // First try to find a policy with such id
        for (NetworkPolicy policy : policies) {
            if (policy.template.isMatchRuleMobile() || policy.metered == metered) {
                continue;
            }
            final String networkId = getNetworkId(policy);
            if (id.equals(networkId)) {
                Log.i(TAG, "Changing " + networkId + " metered status to " + metered);
                policy.metered = metered;
                changed = true;
            }
        }
        if (changed) {
            mInterface.setNetworkPolicies(policies);
            return 0;
        }
        // Policy not found: check if there is a saved wi-fi with such id.
        for (WifiConfiguration config : mWifiManager.getConfiguredNetworks()) {
            final String ssid = removeDoubleQuotes(config.SSID);
            if (id.equals(ssid)) {
                final NetworkPolicy policy = newPolicy(ssid);
                policy.metered = true;
                Log.i(TAG, "Creating new policy for " + ssid + ": " + policy);
                final NetworkPolicy[] newPolicies = new NetworkPolicy[policies.length + 1];
                System.arraycopy(policies, 0, newPolicies, 0, policies.length);
                newPolicies[newPolicies.length - 1] = policy;
                mInterface.setNetworkPolicies(newPolicies);
            }
        }
        return 0;
    }

    private List<NetworkPolicy> getWifiPolicies() throws RemoteException {
        // First gets a list of saved wi-fi networks.
        final List<WifiConfiguration> configs = mWifiManager.getConfiguredNetworks();
        final int size = configs != null ? configs.size() : 0;
        final Set<String> ssids = new HashSet<>(size);
        if (configs != null) {
            for (WifiConfiguration config : configs) {
                ssids.add(removeDoubleQuotes(config.SSID));
            }
        }

        // Then gets the saved policies.
        final NetworkPolicy[] policies = mInterface.getNetworkPolicies(null);
        final List<NetworkPolicy> wifiPolicies = new ArrayList<NetworkPolicy>(policies.length);
        for (NetworkPolicy policy: policies) {
            if (!policy.template.isMatchRuleMobile()) {
                wifiPolicies.add(policy);
                final String netId = getNetworkId(policy);
                ssids.remove(netId);
            }
        }
        // Finally, creates new default policies for saved WI-FIs not policied yet.
        for (String ssid : ssids) {
            final NetworkPolicy policy = newPolicy(ssid);
            wifiPolicies.add(policy);
        }
        return wifiPolicies;
    }

    private NetworkPolicy newPolicy(String ssid) {
        final NetworkTemplate template = NetworkTemplate.buildTemplateWifi(ssid);
        final NetworkPolicy policy = newWifiPolicy(template, false);
        return policy;
    }

    private String getNetworkId(NetworkPolicy policy) {
        return removeDoubleQuotes(policy.template.getNetworkId());
    }

    private int getNextBooleanArg() {
        final PrintWriter pw = getOutPrintWriter();
        final String arg = getNextArg();
        if (arg == null) {
            pw.println("Error: didn't specify BOOLEAN");
            return -1;
        }
        return Boolean.valueOf(arg) ? 1 : 0;
    }

    private int getUidFromNextArg() {
        final PrintWriter pw = getOutPrintWriter();
        final String arg = getNextArg();
        if (arg == null) {
            pw.println("Error: didn't specify UID");
            return -1;
        }
        try {
            return Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            pw.println("Error: UID (" + arg + ") should be a number");
            return -2;
        }
    }
}
