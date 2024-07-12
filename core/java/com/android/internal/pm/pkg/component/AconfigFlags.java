/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.pm.pkg.component;

import static com.android.internal.pm.pkg.parsing.ParsingUtils.ANDROID_RES_NAMESPACE;

import android.aconfig.nano.Aconfig;
import android.aconfig.nano.Aconfig.parsed_flag;
import android.aconfig.nano.Aconfig.parsed_flags;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Flags;
import android.content.res.XmlResourceParser;
import android.os.Environment;
import android.os.Process;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.TypedXmlPullParser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * A class that manages a cache of all device feature flags and their default + override values.
 * This class performs a very similar job to the one in {@code SettingsProvider}, with an important
 * difference: this is a part of system server and is available for the server startup. Package
 * parsing happens at the startup when {@code SettingsProvider} isn't available yet, so we need an
 * own copy of the code here.
 * @hide
 */
public class AconfigFlags {
    private static final String LOG_TAG = "AconfigFlags";

    private static final List<String> sTextProtoFilesOnDevice = List.of(
            "/system/etc/aconfig_flags.pb",
            "/system_ext/etc/aconfig_flags.pb",
            "/product/etc/aconfig_flags.pb",
            "/vendor/etc/aconfig_flags.pb");

    private final ArrayMap<String, Boolean> mFlagValues = new ArrayMap<>();

    public AconfigFlags() {
        if (!Flags.manifestFlagging()) {
            Slog.v(LOG_TAG, "Feature disabled, skipped all loading");
            return;
        }
        for (String fileName : sTextProtoFilesOnDevice) {
            try (var inputStream = new FileInputStream(fileName)) {
                loadAconfigDefaultValues(inputStream.readAllBytes());
            } catch (IOException e) {
                Slog.e(LOG_TAG, "Failed to read Aconfig values from " + fileName, e);
            }
        }
        if (Process.myUid() == Process.SYSTEM_UID) {
            // Server overrides are only accessible to the system, no need to even try loading them
            // in user processes.
            loadServerOverrides();
        }
    }

    private void loadServerOverrides() {
        // Reading the proto files is enough for READ_ONLY flags but if it's a READ_WRITE flag
        // (which you can check with `flag.getPermission() == flag_permission.READ_WRITE`) then we
        // also need to check if there is a value pushed from the server in the file
        // `/data/system/users/0/settings_config.xml`. It will be in a <setting> node under the
        // root <settings> node with "name" attribute == "flag_namespace/flag_package.flag_name".
        // The "value" attribute will be true or false.
        //
        // The "name" attribute could also be "<namespace>/flag_namespace?flag_package.flag_name"
        // (prefixed with "staged/" or "device_config_overrides/" and a different separator between
        // namespace and name). This happens when a flag value is overridden either with a pushed
        // one from the server, or from the local command.
        // When the device reboots during package parsing, the staged value will still be there and
        // only later it will become a regular/non-staged value after SettingsProvider is
        // initialized.
        //
        // In all cases, when there is more than one value, the priority is:
        //      device_config_overrides > staged > default
        //

        final var settingsFile = new File(Environment.getUserSystemDirectory(0),
                "settings_config.xml");
        try (var inputStream = new FileInputStream(settingsFile)) {
            TypedXmlPullParser parser = Xml.resolvePullParser(inputStream);
            if (parser.next() != XmlPullParser.END_TAG && "settings".equals(parser.getName())) {
                final var flagPriority = new ArrayMap<String, Integer>();
                final int outerDepth = parser.getDepth();
                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                        && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                    if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                        continue;
                    }
                    if (!"setting".equals(parser.getName())) {
                        continue;
                    }
                    String name = parser.getAttributeValue(null, "name");
                    final String value = parser.getAttributeValue(null, "value");
                    if (name == null || value == null) {
                        continue;
                    }
                    // A non-boolean setting is definitely not an Aconfig flag value.
                    if (!"false".equalsIgnoreCase(value) && !"true".equalsIgnoreCase(value)) {
                        continue;
                    }
                    final var overridePrefix = "device_config_overrides/";
                    final var stagedPrefix = "staged/";
                    String separator = "/";
                    String prefix = "default";
                    int priority = 0;
                    if (name.startsWith(overridePrefix)) {
                        prefix = overridePrefix;
                        name = name.substring(overridePrefix.length());
                        separator = ":";
                        priority = 20;
                    } else if (name.startsWith(stagedPrefix)) {
                        prefix = stagedPrefix;
                        name = name.substring(stagedPrefix.length());
                        separator = "*";
                        priority = 10;
                    }
                    final String flagPackageAndName = parseFlagPackageAndName(name, separator);
                    if (flagPackageAndName == null) {
                        continue;
                    }
                    // We ignore all settings that aren't for flags. We'll know they are for flags
                    // if they correspond to flags read from the proto files.
                    if (!mFlagValues.containsKey(flagPackageAndName)) {
                        continue;
                    }
                    Slog.d(LOG_TAG, "Found " + prefix
                            + " Aconfig flag value for " + flagPackageAndName + " = " + value);
                    final Integer currentPriority = flagPriority.get(flagPackageAndName);
                    if (currentPriority != null && currentPriority >= priority) {
                        Slog.i(LOG_TAG, "Skipping " + prefix + " flag " + flagPackageAndName
                                + " because of the existing one with priority " + currentPriority);
                        continue;
                    }
                    flagPriority.put(flagPackageAndName, priority);
                    mFlagValues.put(flagPackageAndName, Boolean.parseBoolean(value));
                }
            }
        } catch (IOException | XmlPullParserException e) {
            Slog.e(LOG_TAG, "Failed to read Aconfig values from settings_config.xml", e);
        }
    }

    private static String parseFlagPackageAndName(String fullName, String separator) {
        int index = fullName.indexOf(separator);
        if (index < 0) {
            return null;
        }
        return fullName.substring(index + 1);
    }

    private void loadAconfigDefaultValues(byte[] fileContents) throws IOException {
        parsed_flags parsedFlags = parsed_flags.parseFrom(fileContents);
        for (parsed_flag flag : parsedFlags.parsedFlag) {
            String flagPackageAndName = flag.package_ + "." + flag.name;
            boolean flagValue = (flag.state == Aconfig.ENABLED);
            Slog.v(LOG_TAG, "Read Aconfig default flag value "
                    + flagPackageAndName + " = " + flagValue);
            mFlagValues.put(flagPackageAndName, flagValue);
        }
    }

    /**
     * Get the flag value, or null if the flag doesn't exist.
     * @param flagPackageAndName Full flag name formatted as 'package.flag'
     * @return the current value of the given Aconfig flag, or null if there is no such flag
     */
    @Nullable
    public Boolean getFlagValue(@NonNull String flagPackageAndName) {
        Boolean value = mFlagValues.get(flagPackageAndName);
        Slog.d(LOG_TAG, "Aconfig flag value for " + flagPackageAndName + " = " + value);
        return value;
    }

    /**
     * Check if the element in {@code parser} should be skipped because of the feature flag.
     * @param parser XML parser object currently parsing an element
     * @return true if the element is disabled because of its feature flag
     */
    public boolean skipCurrentElement(@NonNull XmlResourceParser parser) {
        if (!Flags.manifestFlagging()) {
            return false;
        }
        String featureFlag = parser.getAttributeValue(ANDROID_RES_NAMESPACE, "featureFlag");
        if (featureFlag == null) {
            return false;
        }
        featureFlag = featureFlag.strip();
        boolean negated = false;
        if (featureFlag.startsWith("!")) {
            negated = true;
            featureFlag = featureFlag.substring(1).strip();
        }
        final Boolean flagValue = getFlagValue(featureFlag);
        if (flagValue == null) {
            Slog.w(LOG_TAG, "Skipping element " + parser.getName()
                    + " due to unknown feature flag " + featureFlag);
            return true;
        }
        // Skip if flag==false && attr=="flag" OR flag==true && attr=="!flag" (negated)
        if (flagValue == negated) {
            Slog.v(LOG_TAG, "Skipping element " + parser.getName()
                    + " behind feature flag " + featureFlag + " = " + flagValue);
            return true;
        }
        return false;
    }

    /**
     * Add Aconfig flag values for testing flagging of manifest entries.
     * @param flagValues A map of flag name -> value.
     */
    @VisibleForTesting
    public void addFlagValuesForTesting(@NonNull Map<String, Boolean> flagValues) {
        mFlagValues.putAll(flagValues);
    }
}
