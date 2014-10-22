/*
 * Copyright (C) 2010, The Android Open Source Project
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

package com.android.connectivitymanagertest;

import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkAddress;
import android.net.StaticIpConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.AuthAlgorithm;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiEnterpriseConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper for dealing with creating {@link WifiConfiguration} objects.
 */
public class WifiConfigurationHelper {
    private static final int NONE = 0;
    private static final int WEP = 1;
    private static final int PSK = 2;
    private static final int EAP = 3;

    /**
     * Private constructor since this a static class.
     */
    private WifiConfigurationHelper() {}

    /**
     * Create a {@link WifiConfiguration} for an open network
     *
     * @param ssid The SSID of the wifi network
     * @return The {@link WifiConfiguration}
     */
    public static WifiConfiguration createOpenConfig(String ssid) {
        WifiConfiguration config = createGenericConfig(ssid);

        config.allowedKeyManagement.set(KeyMgmt.NONE);
        return config;
    }

    /**
     * Create a {@link WifiConfiguration} for a WEP secured network
     *
     * @param ssid The SSID of the wifi network
     * @param password Either a 10, 26, or 58 character hex string or the plain text password
     * @return The {@link WifiConfiguration}
     */
    public static WifiConfiguration createWepConfig(String ssid, String password) {
        WifiConfiguration config = createGenericConfig(ssid);

        if (isHex(password, 10) || isHex(password, 26) || isHex(password, 58)) {
            config.wepKeys[0] = password;
        } else {
            config.wepKeys[0] = quotedString(password);
        }

        config.allowedKeyManagement.set(KeyMgmt.NONE);
        config.allowedAuthAlgorithms.set(AuthAlgorithm.OPEN);
        config.allowedAuthAlgorithms.set(AuthAlgorithm.SHARED);
        return config;
    }

    /**
     * Create a {@link WifiConfiguration} for a PSK secured network
     *
     * @param ssid The SSID of the wifi network
     * @param password Either a 64 character hex string or the plain text password
     * @return The {@link WifiConfiguration}
     */
    public static WifiConfiguration createPskConfig(String ssid, String password) {
        WifiConfiguration config = createGenericConfig(ssid);

        if (isHex(password, 64)) {
            config.preSharedKey = password;
        } else {
            config.preSharedKey = quotedString(password);
        }
        config.allowedKeyManagement.set(KeyMgmt.WPA_PSK);
        return config;
    }

    /**
     * Create a {@link WifiConfiguration} for an EAP secured network
     *
     * @param ssid The SSID of the wifi network
     * @param password The password
     * @param eapMethod The EAP method
     * @param phase2 The phase 2 method or null
     * @param identity The identity or null
     * @param anonymousIdentity The anonymous identity or null
     * @param caCert The CA certificate or null
     * @param clientCert The client certificate or null
     * @return The {@link WifiConfiguration}
     */
    public static WifiConfiguration createEapConfig(String ssid, String password, int eapMethod,
            Integer phase2, String identity, String anonymousIdentity, String caCert,
            String clientCert) {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = quotedString(ssid);

        config.allowedKeyManagement.set(KeyMgmt.WPA_EAP);
        config.allowedKeyManagement.set(KeyMgmt.IEEE8021X);

        // Set defaults
        if (phase2 == null) phase2 = WifiEnterpriseConfig.Phase2.NONE;
        if (identity == null) identity = "";
        if (anonymousIdentity == null) anonymousIdentity = "";
        if (caCert == null) caCert = "";
        if (clientCert == null) clientCert = "";

        config.enterpriseConfig.setPassword(password);
        config.enterpriseConfig.setEapMethod(eapMethod);
        config.enterpriseConfig.setPhase2Method(phase2);
        config.enterpriseConfig.setIdentity(identity);
        config.enterpriseConfig.setAnonymousIdentity(anonymousIdentity);
        config.enterpriseConfig.setCaCertificateAlias(caCert);
        config.enterpriseConfig.setClientCertificateAlias(clientCert);
        return config;
    }

    /**
     * Create a generic {@link WifiConfiguration} used by the other create methods.
     */
    private static WifiConfiguration createGenericConfig(String ssid) {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = quotedString(ssid);
        config.setIpAssignment(IpAssignment.DHCP);
        config.setProxySettings(ProxySettings.NONE);
        return config;
    }

    /**
     * Parse a JSON string for WiFi configurations stored as a JSON string.
     * <p>
     * This json string should be a list of dictionaries, with each dictionary containing a single
     * wifi configuration. The wifi configuration requires the fields "ssid" and "security" with
     * security being one of NONE, WEP, PSK, or EAP. If WEP, PSK, or EAP are selected, the field
     * "password" must also be provided.  If EAP is selected, then the fiels "eap", "phase2",
     * "identity", "ananymous_identity", "ca_cert", and "client_cert" are also required. Lastly,
     * static IP settings are also supported.  If the field "ip" is set, then the fields "gateway",
     * "prefix_length", "dns1", and "dns2" are required.
     * </p>
     * @throws IllegalArgumentException if the input string was not valid JSON or if any mandatory
     * fields are missing.
     */
    public static List<WifiConfiguration> parseJson(String in) {
        try {
            JSONArray jsonConfigs = new JSONArray(in);
            List<WifiConfiguration> wifiConfigs = new ArrayList<>(jsonConfigs.length());

            for (int i = 0; i < jsonConfigs.length(); i++) {
                JSONObject jsonConfig = jsonConfigs.getJSONObject(i);

                wifiConfigs.add(getWifiConfiguration(jsonConfig));
            }
            return wifiConfigs;
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Parse a {@link JSONObject} and return the wifi configuration.
     *
     * @throws IllegalArgumentException if any mandatory fields are missing.
     */
    private static WifiConfiguration getWifiConfiguration(JSONObject jsonConfig)
            throws JSONException {
        String ssid = jsonConfig.getString("ssid");
        String password = null;
        WifiConfiguration config;

        int securityType = getSecurityType(jsonConfig.getString("security"));
        switch (securityType) {
            case NONE:
                config = createOpenConfig(ssid);
                break;
            case WEP:
                password = jsonConfig.getString("password");
                config = createWepConfig(ssid, password);
                break;
            case PSK:
                password = jsonConfig.getString("password");
                config = createPskConfig(ssid, password);
                break;
            case EAP:
                password = jsonConfig.getString("password");
                int eapMethod = getEapMethod(jsonConfig.getString("eap"));
                Integer phase2 = null;
                if (jsonConfig.has("phase2")) {
                    phase2 = getPhase2(jsonConfig.getString("phase2"));
                }
                String identity = null;
                if (jsonConfig.has("identity")) {
                    identity = jsonConfig.getString("identity");
                }
                String anonymousIdentity = null;
                if (jsonConfig.has("anonymous_identity")) {
                    anonymousIdentity = jsonConfig.getString("anonymous_identity");
                }
                String caCert = null;
                if (jsonConfig.has("ca_cert")) {
                    caCert = (jsonConfig.getString("ca_cert"));
                }
                String clientCert = null;
                if (jsonConfig.has("client_cert")) {
                    clientCert = jsonConfig.getString("client_cert");
                }
                config = createEapConfig(ssid, password, eapMethod, phase2, identity,
                        anonymousIdentity, caCert, clientCert);
                break;
            default:
                // Should never reach here as getSecurityType will already throw an exception
                throw new IllegalArgumentException();
        }

        if (jsonConfig.has("ip")) {
            StaticIpConfiguration staticIpConfig = new StaticIpConfiguration();

            InetAddress ipAddress = getInetAddress(jsonConfig.getString("ip"));
            int prefixLength = getPrefixLength(jsonConfig.getInt("prefix_length"));
            staticIpConfig.ipAddress = new LinkAddress(ipAddress, prefixLength);
            staticIpConfig.gateway = getInetAddress(jsonConfig.getString("gateway"));
            staticIpConfig.dnsServers.add(getInetAddress(jsonConfig.getString("dns1")));
            staticIpConfig.dnsServers.add(getInetAddress(jsonConfig.getString("dns2")));

            config.setIpAssignment(IpAssignment.STATIC);
            config.setStaticIpConfiguration(staticIpConfig);
        } else {
            config.setIpAssignment(IpAssignment.DHCP);
        }

        config.setProxySettings(ProxySettings.NONE);
        return config;
    }

    private static String quotedString(String s) {
        return String.format("\"%s\"", s);
    }

    /**
     * Get the security type from a string.
     *
     * @throws IllegalArgumentException if the string is not a supported security type.
     */
    private static int getSecurityType(String security) {
        if ("NONE".equalsIgnoreCase(security)) {
            return NONE;
        }
        if ("WEP".equalsIgnoreCase(security)) {
            return WEP;
        }
        if ("PSK".equalsIgnoreCase(security)) {
            return PSK;
        }
        if ("EAP".equalsIgnoreCase(security)) {
            return EAP;
        }
        throw new IllegalArgumentException("Security type must be one of NONE, WEP, PSK, or EAP");
    }

    /**
     * Get the EAP method from a string.
     *
     * @throws IllegalArgumentException if the string is not a supported EAP method.
     */
    private static int getEapMethod(String eapMethod) {
        if ("TLS".equalsIgnoreCase(eapMethod)) {
            return WifiEnterpriseConfig.Eap.TLS;
        }
        if ("TTLS".equalsIgnoreCase(eapMethod)) {
            return WifiEnterpriseConfig.Eap.TTLS;
        }
        if ("PEAP".equalsIgnoreCase(eapMethod)) {
            return WifiEnterpriseConfig.Eap.PEAP;
        }
        throw new IllegalArgumentException("EAP method must be one of TLS, TTLS, or PEAP");
    }

    /**
     * Get the phase 2 method from a string.
     *
     * @throws IllegalArgumentException if the string is not a supported phase 2 method.
     */
    private static int getPhase2(String phase2) {
        if ("PAP".equalsIgnoreCase(phase2)) {
            return WifiEnterpriseConfig.Phase2.PAP;
        }
        if ("MSCHAP".equalsIgnoreCase(phase2)) {
            return WifiEnterpriseConfig.Phase2.MSCHAP;
        }
        if ("MSCHAPV2".equalsIgnoreCase(phase2)) {
            return WifiEnterpriseConfig.Phase2.MSCHAPV2;
        }
        if ("GTC".equalsIgnoreCase(phase2)) {
            return WifiEnterpriseConfig.Phase2.GTC;
        }
        throw new IllegalArgumentException("Phase2 must be one of PAP, MSCHAP, MSCHAPV2, or GTC");
    }

    /**
     * Get an {@link InetAddress} from a string
     *
     * @throws IllegalArgumentException if the string is not a valid IP address.
     */
    private static InetAddress getInetAddress(String ipAddress) {
        if (!InetAddress.isNumeric(ipAddress)) {
            throw new IllegalArgumentException(
                    String.format("IP address %s is not numeric", ipAddress));
        }

        try {
            return InetAddress.getByName(ipAddress);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(
                    String.format("IP address %s could not be resolved", ipAddress));
        }
    }

    /**
     * Get the prefix length from an int.
     *
     * @throws IllegalArgumentException if the prefix length is less than 0 or greater than 32.
     */
    private static int getPrefixLength(int prefixLength) {
        if (prefixLength < 0 || prefixLength > 32) {
            throw new IllegalArgumentException("Prefix length cannot be less than 0 or more than 32");
        }
        return prefixLength;
    }

    /**
     * Utility method to check if a given string is a hexadecimal string of given length
     */
    public static boolean isHex(String input, int length) {
        if (input == null || length < 0) {
            return false;
        }
        return input.matches(String.format("[0-9A-Fa-f]{%d}", length));
    }
}
