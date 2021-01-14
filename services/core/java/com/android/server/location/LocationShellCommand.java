/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location;

import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.provider.ProviderProperties;
import android.os.UserHandle;

import com.android.modules.utils.BasicShellCommandHandler;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Objects;

/**
 * Interprets and executes 'adb shell cmd location [args]'.
 */
class LocationShellCommand extends BasicShellCommandHandler {
    private static final float DEFAULT_TEST_LOCATION_ACCURACY = 100.0f;

    private final Context mContext;
    private final LocationManagerService mService;

    LocationShellCommand(Context context, LocationManagerService service) {
        mContext = context;
        mService = Objects.requireNonNull(service);
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(null);
        }

        switch (cmd) {
            case "is-location-enabled": {
                int userId = parseUserId();
                boolean enabled = mService.isLocationEnabledForUser(userId);
                getOutPrintWriter().println(enabled);
                return 0;
            }
            case "set-location-enabled": {
                int userId = parseUserId();
                boolean enabled = Boolean.parseBoolean(getNextArgRequired());
                mService.setLocationEnabledForUser(enabled, userId);
                return 0;
            }
            case "providers": {
                String command = getNextArgRequired();
                return parseProvidersCommand(command);
            }
            default:
                return handleDefaultCommands(cmd);
        }
    }

    private int parseProvidersCommand(String cmd) {
        switch (cmd) {
            case "add-test-provider": {
                String provider = getNextArgRequired();
                ProviderProperties properties = parseTestProviderProviderProperties();
                mService.addTestProvider(provider, properties, mContext.getOpPackageName(),
                        mContext.getFeatureId());
                return 0;
            }
            case "remove-test-provider": {
                String provider = getNextArgRequired();
                mService.removeTestProvider(provider, mContext.getOpPackageName(),
                        mContext.getFeatureId());
                return 0;
            }
            case "set-test-provider-enabled": {
                String provider = getNextArgRequired();
                boolean enabled = Boolean.parseBoolean(getNextArgRequired());
                mService.setTestProviderEnabled(provider, enabled, mContext.getOpPackageName(),
                        mContext.getFeatureId());
                return 0;
            }
            case "set-test-provider-location": {
                String provider = getNextArgRequired();
                Location location = parseTestProviderLocation(provider);
                mService.setTestProviderLocation(provider, location, mContext.getOpPackageName(),
                        mContext.getFeatureId());
                return 0;
            }
            case "send-extra-command": {
                String provider = getNextArgRequired();
                String command = getNextArgRequired();
                mService.sendExtraCommand(provider, command, null);
                return 0;
            }
            default:
                return handleDefaultCommands(cmd);
        }
    }

    private int parseUserId() {
        final String option = getNextOption();
        if (option != null) {
            if (option.equals("--user")) {
                return UserHandle.parseUserArg(getNextArgRequired());
            } else {
                throw new IllegalArgumentException(
                        "Expected \"--user\" option, but got \"" + option + "\" instead");
            }
        }

        return UserHandle.USER_CURRENT_OR_SELF;
    }

    private ProviderProperties parseTestProviderProviderProperties() {
        boolean requiresNetwork = false;
        boolean requiresSatellite = false;
        boolean requiresCell = false;
        boolean hasMonetaryCost = false;
        boolean supportsAltitude = false;
        boolean supportsSpeed = false;
        boolean supportsBearing = false;
        int powerRequirement = Criteria.POWER_LOW;
        int accuracy = Criteria.ACCURACY_FINE;

        String option = getNextOption();
        while (option != null) {
            switch (option) {
                case "--requiresNetwork": {
                    requiresNetwork = true;
                    break;
                }
                case "--requiresSatellite": {
                    requiresSatellite = true;
                    break;
                }
                case "--requiresCell": {
                    requiresCell = true;
                    break;
                }
                case "--hasMonetaryCost": {
                    hasMonetaryCost = true;
                    break;
                }
                case "--supportsAltitude": {
                    supportsAltitude = true;
                    break;
                }
                case "--supportsSpeed": {
                    supportsSpeed = true;
                    break;
                }
                case "--supportsBearing": {
                    supportsBearing = true;
                    break;
                }
                case "--powerRequirement": {
                    powerRequirement = Integer.parseInt(getNextArgRequired());
                    break;
                }
                case "--accuracy": {
                    accuracy = Integer.parseInt(getNextArgRequired());
                    break;
                }
                default:
                    throw new IllegalArgumentException(
                            "Received unexpected option: " + option);
            }
            option = getNextOption();
        }

        ProviderProperties properties = new ProviderProperties.Builder()
                .setHasNetworkRequirement(requiresNetwork)
                .setHasSatelliteRequirement(requiresSatellite)
                .setHasCellRequirement(requiresCell)
                .setHasMonetaryCost(hasMonetaryCost)
                .setHasAltitudeSupport(supportsAltitude)
                .setHasSpeedSupport(supportsSpeed)
                .setHasBearingSupport(supportsBearing)
                .setPowerUsage(powerRequirement)
                .setAccuracy(accuracy)
                .build();

        return properties;
    }

    private Location parseTestProviderLocation(String provider) {
        boolean hasLatitude = false;
        boolean hasLongitude = false;

        Location location = new Location(provider);
        location.setAccuracy(DEFAULT_TEST_LOCATION_ACCURACY);
        location.setTime(System.currentTimeMillis());

        String option = getNextOption();
        while (option != null) {
            switch (option) {
                case "--location": {
                    String[] locationInput = getNextArgRequired().split(",");
                    if (locationInput.length != 2) {
                        throw new IllegalArgumentException(
                                "Unexpected location format: " + Arrays.toString(locationInput));
                    }

                    location.setLatitude(Double.parseDouble(locationInput[0]));
                    location.setLongitude(Double.parseDouble(locationInput[1]));
                    break;
                }
                case "--accuracy": {
                    location.setAccuracy(Float.parseFloat(getNextArgRequired()));
                    break;
                }
                case "--time": {
                    location.setTime(Long.parseLong(getNextArgRequired()));
                    break;
                }
                default:
                    throw new IllegalArgumentException(
                            "Received unexpected option: " + option);
            }
            option = getNextOption();
        }

        location.setElapsedRealtimeNanos(System.nanoTime());

        return location;
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Location service commands:");
        pw.println("  help or -h");
        pw.println("    Print this help text.");
        pw.println("  is-location-enabled [--user <USER_ID>]");
        pw.println("    Gets the master location switch enabled state.");
        pw.println("  set-location-enabled [--user <USER_ID>] true|false");
        pw.println("    Sets the master location switch enabled state.");
        pw.println("  providers");
        pw.println("    add-test-provider <PROVIDER> [--requiresNetwork] [--requiresSatellite]");
        pw.println("      [--requiresCell] [--hasMonetaryCost] [--supportsAltitude]");
        pw.println("      [--supportsSpeed] [--supportsBearing]");
        pw.println("      [--powerRequirement <POWER_REQUIREMENT>]");
        pw.println("      Add the given test provider. Requires MOCK_LOCATION permissions which");
        pw.println("      can be enabled by running \"adb shell appops set <uid>");
        pw.println("      android:mock_location allow\". There are optional flags that can be");
        pw.println("      used to configure the provider properties. If no flags are included,");
        pw.println("      then default values will be used.");
        pw.println("    remove-test-provider <PROVIDER>");
        pw.println("      Remove the given test provider.");
        pw.println("    set-test-provider-enabled <PROVIDER> true|false");
        pw.println("      Sets the given test provider enabled state.");
        pw.println("    set-test-provider-location <PROVIDER> [--location <LATITUDE>,<LONGITUDE>]");
        pw.println("      [--accuracy <ACCURACY>] [--time <TIME>]");
        pw.println("      Set location for given test provider. Accuracy and time are optional.");
        pw.println("    send-extra-command <PROVIDER> <COMMAND>");
        pw.println("      Sends the given extra command to the given provider.");
        pw.println();
        pw.println("      Common commands that may be supported by the gps provider, depending on");
        pw.println("      hardware and software configurations:");
        pw.println("        delete_aiding_data - requests deletion of any predictive aiding data");
        pw.println("        force_time_injection - requests NTP time injection to chipset");
        pw.println("        force_psds_injection - "
                + "requests predictive aiding data injection to chipset");
        pw.println("        request_power_stats - requests GNSS power stats update from chipset");
    }
}
