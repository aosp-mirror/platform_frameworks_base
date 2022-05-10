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
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.provider.ProviderProperties;
import android.os.SystemClock;
import android.os.UserHandle;

import com.android.modules.utils.BasicShellCommandHandler;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
                handleIsLocationEnabled();
                return 0;
            }
            case "set-location-enabled": {
                handleSetLocationEnabled();
                return 0;
            }
            case "is-adas-gnss-location-enabled": {
                handleIsAdasGnssLocationEnabled();
                return 0;
            }
            case "set-adas-gnss-location-enabled": {
                handleSetAdasGnssLocationEnabled();
                return 0;
            }
            case "set-automotive-gnss-suspended": {
                handleSetAutomotiveGnssSuspended();
                return 0;
            }
            case "is-automotive-gnss-suspended": {
                handleIsAutomotiveGnssSuspended();
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
                handleAddTestProvider();
                return 0;
            }
            case "remove-test-provider": {
                handleRemoveTestProvider();
                return 0;
            }
            case "set-test-provider-enabled": {
                handleSetTestProviderEnabled();
                return 0;
            }
            case "set-test-provider-location": {
                handleSetTestProviderLocation();
                return 0;
            }
            case "send-extra-command": {
                handleSendExtraCommand();
                return 0;
            }
            default:
                return handleDefaultCommands(cmd);
        }
    }

    private void handleIsLocationEnabled() {
        int userId = UserHandle.USER_CURRENT_OR_SELF;

        do {
            String option = getNextOption();
            if (option == null) {
                break;
            }
            if ("--user".equals(option)) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                throw new IllegalArgumentException("Unknown option: " + option);
            }
        } while (true);

        getOutPrintWriter().println(mService.isLocationEnabledForUser(userId));
    }

    private void handleSetLocationEnabled() {
        boolean enabled = Boolean.parseBoolean(getNextArgRequired());

        int userId = UserHandle.USER_CURRENT_OR_SELF;

        do {
            String option = getNextOption();
            if (option == null) {
                break;
            }
            if ("--user".equals(option)) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                throw new IllegalArgumentException("Unknown option: " + option);
            }
        } while (true);

        mService.setLocationEnabledForUser(enabled, userId);
    }

    private void handleIsAdasGnssLocationEnabled() {
        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            throw new IllegalStateException("command only recognized on automotive devices");
        }

        int userId = UserHandle.USER_CURRENT_OR_SELF;

        do {
            String option = getNextOption();
            if (option == null) {
                break;
            }
            if ("--user".equals(option)) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                throw new IllegalArgumentException("Unknown option: " + option);
            }
        } while (true);

        getOutPrintWriter().println(mService.isAdasGnssLocationEnabledForUser(userId));
    }

    private void handleSetAdasGnssLocationEnabled() {
        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            throw new IllegalStateException("command only recognized on automotive devices");
        }

        boolean enabled = Boolean.parseBoolean(getNextArgRequired());

        int userId = UserHandle.USER_CURRENT_OR_SELF;

        do {
            String option = getNextOption();
            if (option == null) {
                break;
            }
            if ("--user".equals(option)) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                throw new IllegalArgumentException("Unknown option: " + option);
            }
        } while (true);

        mService.setAdasGnssLocationEnabledForUser(enabled, userId);
    }

    private void handleSetAutomotiveGnssSuspended() {
        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            throw new IllegalStateException("command only recognized on automotive devices");
        }

        boolean suspended = Boolean.parseBoolean(getNextArgRequired());

        mService.setAutomotiveGnssSuspended(suspended);
    }

    private void handleIsAutomotiveGnssSuspended() {
        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            throw new IllegalStateException("command only recognized on automotive devices");
        }

        getOutPrintWriter().println(mService.isAutomotiveGnssSuspended());
    }

    private void handleAddTestProvider() {
        String provider = getNextArgRequired();

        boolean requiresNetwork = false;
        boolean requiresSatellite = false;
        boolean requiresCell = false;
        boolean hasMonetaryCost = false;
        boolean supportsAltitude = false;
        boolean supportsSpeed = false;
        boolean supportsBearing = false;
        int powerRequirement = Criteria.POWER_LOW;
        int accuracy = Criteria.ACCURACY_FINE;

        List<String> extraAttributionTags = Collections.emptyList();

        do {
            String option = getNextOption();
            if (option == null) {
                break;
            }
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
                case "--extraAttributionTags": {
                    extraAttributionTags = Arrays.asList(getNextArgRequired().split(","));
                    break;
                }
                default:
                    throw new IllegalArgumentException(
                            "Received unexpected option: " + option);
            }
        } while(true);

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
        mService.addTestProvider(provider, properties, extraAttributionTags,
                mContext.getOpPackageName(), mContext.getAttributionTag());
    }

    private void handleRemoveTestProvider() {
        String provider = getNextArgRequired();
        mService.removeTestProvider(provider, mContext.getOpPackageName(),
                mContext.getAttributionTag());
    }

    private void handleSetTestProviderEnabled() {
        String provider = getNextArgRequired();
        boolean enabled = Boolean.parseBoolean(getNextArgRequired());
        mService.setTestProviderEnabled(provider, enabled, mContext.getOpPackageName(),
                mContext.getAttributionTag());
    }

    private void handleSetTestProviderLocation() {
        String provider = getNextArgRequired();

        boolean hasLatLng = false;

        Location location = new Location(provider);
        location.setAccuracy(DEFAULT_TEST_LOCATION_ACCURACY);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

        do {
            String option = getNextOption();
            if (option == null) {
                break;
            }
            switch (option) {
                case "--location": {
                    String[] locationInput = getNextArgRequired().split(",");
                    if (locationInput.length != 2) {
                        throw new IllegalArgumentException("Location argument must be in the form "
                                + "of \"<LATITUDE>,<LONGITUDE>\", not "
                                + Arrays.toString(locationInput));
                    }

                    location.setLatitude(Double.parseDouble(locationInput[0]));
                    location.setLongitude(Double.parseDouble(locationInput[1]));
                    hasLatLng = true;
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
                    throw new IllegalArgumentException("Unknown option: " + option);
            }
        } while (true);

        if (!hasLatLng) {
            throw new IllegalArgumentException("Option \"--location\" is required");
        }

        mService.setTestProviderLocation(provider, location, mContext.getOpPackageName(),
                mContext.getAttributionTag());
    }

    private void handleSendExtraCommand() {
        String provider = getNextArgRequired();
        String command = getNextArgRequired();
        mService.sendExtraCommand(provider, command, null);
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Location service commands:");
        pw.println("  help or -h");
        pw.println("    Print this help text.");
        pw.println("  is-location-enabled [--user <USER_ID>]");
        pw.println("    Gets the master location switch enabled state. If no user is specified,");
        pw.println("    the current user is assumed.");
        pw.println("  set-location-enabled true|false [--user <USER_ID>]");
        pw.println("    Sets the master location switch enabled state. If no user is specified,");
        pw.println("    the current user is assumed.");
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            pw.println("  is-adas-gnss-location-enabled [--user <USER_ID>]");
            pw.println("    Gets the ADAS GNSS location enabled state. If no user is specified,");
            pw.println("    the current user is assumed.");
            pw.println("  set-adas-gnss-location-enabled true|false [--user <USER_ID>]");
            pw.println("    Sets the ADAS GNSS location enabled state. If no user is specified,");
            pw.println("    the current user is assumed.");
            pw.println("  is-automotive-gnss-suspended");
            pw.println("    Gets the automotive GNSS suspended state.");
            pw.println("  set-automotive-gnss-suspended true|false");
            pw.println("    Sets the automotive GNSS suspended state.");
        }
        pw.println("  providers");
        pw.println("    The providers command is followed by a subcommand, as listed below:");
        pw.println();
        pw.println("    add-test-provider <PROVIDER> [--requiresNetwork] [--requiresSatellite]");
        pw.println("      [--requiresCell] [--hasMonetaryCost] [--supportsAltitude]");
        pw.println("      [--supportsSpeed] [--supportsBearing]");
        pw.println("      [--powerRequirement <POWER_REQUIREMENT>]");
        pw.println("      [--extraAttributionTags <TAG>,<TAG>,...]");
        pw.println("      Add the given test provider. Requires MOCK_LOCATION permissions which");
        pw.println("      can be enabled by running \"adb shell appops set <uid>");
        pw.println("      android:mock_location allow\". There are optional flags that can be");
        pw.println("      used to configure the provider properties and additional arguments. If");
        pw.println("      no flags are included, then default values will be used.");
        pw.println("    remove-test-provider <PROVIDER>");
        pw.println("      Remove the given test provider.");
        pw.println("    set-test-provider-enabled <PROVIDER> true|false");
        pw.println("      Sets the given test provider enabled state.");
        pw.println("    set-test-provider-location <PROVIDER> --location <LATITUDE>,<LONGITUDE>");
        pw.println("      [--accuracy <ACCURACY>] [--time <TIME>]");
        pw.println("      Set location for given test provider. Accuracy and time are optional.");
        pw.println("    send-extra-command <PROVIDER> <COMMAND>");
        pw.println("      Sends the given extra command to the given provider.");
        pw.println();
        pw.println("      Common commands that may be supported by the gps provider, depending on");
        pw.println("      hardware and software configurations:");
        pw.println("        delete_aiding_data - requests deletion of any predictive aiding data");
        pw.println("        force_time_injection - requests NTP time injection");
        pw.println("        force_psds_injection - requests predictive aiding data injection");
        pw.println("        request_power_stats - requests GNSS power stats update");
    }
}
