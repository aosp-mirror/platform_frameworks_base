/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.utils;

import android.annotation.Nullable;
import android.provider.DeviceConfig;

import com.android.server.RescueParty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Utilities for interacting with the {@link android.provider.DeviceConfig}.
 *
 * @hide
 */
public final class FlagNamespaceUtils {
    /**
     * Special String used for communicating through {@link #RESET_PLATFORM_PACKAGE_FLAG} that
     * Settings were reset by the RescueParty, no actual namespace with this name exists in
     * {@link DeviceConfig}.
     */
    public static final String NAMESPACE_NO_PACKAGE = "no_package";

    /**
     * Name of the special namespace in DeviceConfig table used for communicating resets.
     */
    private static final String NAMESPACE_RESCUE_PARTY = "rescue_party_namespace";
    /**
     * Flag in the {@link DeviceConfig} in {@link #NAMESPACE_RESCUE_PARTY}, holding all known {@link
     * DeviceConfig} namespaces, as a {@link #DELIMITER} separated String. It's updated after the
     * first time flags are written to the new namespace in the {@link DeviceConfig}.
     */
    private static final String ALL_KNOWN_NAMESPACES_FLAG = "all_known_namespaces";
    /**
     * Flag in the {@link DeviceConfig} in {@link #NAMESPACE_RESCUE_PARTY} with integer counter
     * suffix added to it, holding {@link DeviceConfig} namespace value whose flags were recently
     * reset by the {@link RescueParty}. It's updated by {@link RescueParty} every time given
     * namespace flags are reset.
     */
    private static final String RESET_PLATFORM_PACKAGE_FLAG = "reset_platform_package";
    private static final String DELIMITER = ":";
    /**
     * Maximum value of the counter used in combination with {@link #RESET_PLATFORM_PACKAGE_FLAG}
     * when communicating recently reset by the RescueParty namespace values.
     */
    private static final int MAX_COUNTER_VALUE = 50;

    private static int sKnownResetNamespacesFlagCounter = -1;

    /**
     * Sets the union of {@link #RESET_PLATFORM_PACKAGE_FLAG} with
     * {@link #sKnownResetNamespacesFlagCounter} in the DeviceConfig for each namespace
     * in the consumed namespacesList. These flags are used for communicating the namespaces
     * (aka platform packages) whose flags in {@link DeviceConfig} were just reset
     * by the RescueParty.
     */
    public static void addToKnownResetNamespaces(@Nullable List<String> namespacesList) {
        if (namespacesList == null) {
            return;
        }
        for (String namespace : namespacesList) {
            addToKnownResetNamespaces(namespace);
        }
    }

    /**
     * Sets the union of {@link #RESET_PLATFORM_PACKAGE_FLAG} with
     * {@link #sKnownResetNamespacesFlagCounter} in the DeviceConfig for the consumed namespace.
     * This flag is used for communicating the namespace (aka platform package) whose flags
     * in {@link DeviceConfig} were just reset by the RescueParty.
     */
    public static void addToKnownResetNamespaces(String namespace) {
        int nextFlagCounter = incrementAndRetrieveResetNamespacesFlagCounter();
        DeviceConfig.setProperty(NAMESPACE_RESCUE_PARTY,
                RESET_PLATFORM_PACKAGE_FLAG + nextFlagCounter,
                namespace, /*makeDefault=*/ true);
    }

    /**
     * Reset all namespaces in DeviceConfig with consumed resetMode.
     */
    public static void resetDeviceConfig(int resetMode) {
        List<String> allKnownNamespaces = getAllKnownDeviceConfigNamespacesList();
        for (String namespace : allKnownNamespaces) {
            DeviceConfig.resetToDefaults(resetMode, namespace);
        }
        addToKnownResetNamespaces(allKnownNamespaces);
    }

    /**
     * Returns a list of all known DeviceConfig namespaces, except for the special {@link
     * #NAMESPACE_RESCUE_PARTY}
     */
    private static List<String> getAllKnownDeviceConfigNamespacesList() {
        String namespacesStr = DeviceConfig.getProperty(NAMESPACE_RESCUE_PARTY,
                ALL_KNOWN_NAMESPACES_FLAG);
        List<String> namespacesList = toStringList(namespacesStr);
        namespacesList.remove(NAMESPACE_RESCUE_PARTY);
        return namespacesList;
    }

    private static List<String> toStringList(String serialized) {
        if (serialized == null || serialized.length() == 0) {
            return new ArrayList<>();
        }
        return Arrays.asList(serialized.split(DELIMITER));
    }

    private static int incrementAndRetrieveResetNamespacesFlagCounter() {
        sKnownResetNamespacesFlagCounter++;
        if (sKnownResetNamespacesFlagCounter == MAX_COUNTER_VALUE) {
            sKnownResetNamespacesFlagCounter = 0;
        }
        return sKnownResetNamespacesFlagCounter;
    }
}
