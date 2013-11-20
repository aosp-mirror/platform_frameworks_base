/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.net;

import java.util.Locale;

/**
 * Describes the buildtime configuration of a network.
 * Holds settings read from resources.
 * @hide
 */
public class NetworkConfig {
    /**
     * Human readable string
     */
    public String name;

    /**
     * Type from ConnectivityManager
     */
    public int type;

    /**
     * the radio number from radio attributes config
     */
    public int radio;

    /**
     * higher number == higher priority when turning off connections
     */
    public int priority;

    /**
     * indicates the boot time dependencyMet setting
     */
    public boolean dependencyMet;

    /**
     * indicates the default restoral timer in seconds
     * if the network is used as a special network feature
     * -1 indicates no restoration of default
     */
    public int restoreTime;

    /**
     * input string from config.xml resource.  Uses the form:
     * [Connection name],[ConnectivityManager connection type],
     * [associated radio-type],[priority],[dependencyMet]
     */
    public NetworkConfig(String init) {
        String fragments[] = init.split(",");
        name = fragments[0].trim().toLowerCase(Locale.ROOT);
        type = Integer.parseInt(fragments[1]);
        radio = Integer.parseInt(fragments[2]);
        priority = Integer.parseInt(fragments[3]);
        restoreTime = Integer.parseInt(fragments[4]);
        dependencyMet = Boolean.parseBoolean(fragments[5]);
    }

    /**
     * Indicates if this network is supposed to be default-routable
     */
    public boolean isDefault() {
        return (type == radio);
    }
}
