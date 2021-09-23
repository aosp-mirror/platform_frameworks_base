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

package com.android.internal.content.om;

import static com.android.internal.content.om.OverlayConfig.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackagePartitions;
import android.content.pm.PackagePartitions.SystemPartition;
import android.os.FileUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Xml;

import com.android.internal.content.om.OverlayScanner.ParsedOverlayInfo;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

/**
 * Responsible for parsing configurations of Runtime Resource Overlays that control mutability,
 * default enable state, and priority. To configure an overlay, create or modify the file located
 * at {@code partition}/overlay/config/config.xml where {@code partition} is the partition of the
 * overlay to be configured. In order to be configured, an overlay must reside in the overlay
 * directory of the partition in which the overlay is configured.
 *
 * @see #parseOverlay(File, XmlPullParser, OverlayScanner, ParsingContext)
 * @see #parseMerge(File, XmlPullParser, OverlayScanner, ParsingContext)
 **/
final class OverlayConfigParser {

    // Default values for overlay configurations.
    static final boolean DEFAULT_ENABLED_STATE = false;
    static final boolean DEFAULT_MUTABILITY = true;

    // Maximum recursive depth of processing merge tags.
    private static final int MAXIMUM_MERGE_DEPTH = 5;

    // The subdirectory within a partition's overlay directory that contains the configuration files
    // for the partition.
    private static final String CONFIG_DIRECTORY = "config";

    /**
     * The name of the configuration file to parse for overlay configurations. This class does not
     * scan for overlay configuration files within the {@link #CONFIG_DIRECTORY}; rather, other
     * files can be included at a particular position within this file using the <merge> tag.
     *
     * @see #parseMerge(File, XmlPullParser, OverlayScanner, ParsingContext)
     */
    private static final String CONFIG_DEFAULT_FILENAME = CONFIG_DIRECTORY + "/config.xml";

    /** Represents the configurations of a particular overlay. */
    public static class ParsedConfiguration {
        @NonNull
        public final String packageName;

        /** Whether or not the overlay is enabled by default. */
        public final boolean enabled;

        /**
         * Whether or not the overlay is mutable and can have its enabled state changed dynamically
         * using the {@code OverlayManagerService}.
         **/
        public final boolean mutable;

        /** The policy granted to overlays on the partition in which the overlay is located. */
        @NonNull
        public final String policy;

        /** Information extracted from the manifest of the overlay. */
        @NonNull
        public final ParsedOverlayInfo parsedInfo;

        ParsedConfiguration(@NonNull String packageName, boolean enabled, boolean mutable,
                @NonNull String policy, @NonNull ParsedOverlayInfo parsedInfo) {
            this.packageName = packageName;
            this.enabled = enabled;
            this.mutable = mutable;
            this.policy = policy;
            this.parsedInfo = parsedInfo;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + String.format("{packageName=%s, enabled=%s"
                            + ", mutable=%s, policy=%s, parsedInfo=%s}", packageName, enabled,
                    mutable, policy, parsedInfo);
        }
    }

    static class OverlayPartition extends SystemPartition {
        // Policies passed to idmap2 during idmap creation.
        // Keep partition policy constants in sync with f/b/cmds/idmap2/include/idmap2/Policies.h.
        static final String POLICY_ODM = "odm";
        static final String POLICY_OEM = "oem";
        static final String POLICY_PRODUCT = "product";
        static final String POLICY_PUBLIC = "public";
        static final String POLICY_SYSTEM = "system";
        static final String POLICY_VENDOR = "vendor";

        @NonNull
        public final String policy;

        OverlayPartition(@NonNull SystemPartition partition) {
            super(partition);
            this.policy = policyForPartition(partition);
        }

        /**
         * Creates a partition containing the same folders as the original partition but with a
         * different root folder.
         */
        OverlayPartition(@NonNull File folder, @NonNull SystemPartition original) {
            super(folder, original);
            this.policy = policyForPartition(original);
        }

        private static String policyForPartition(SystemPartition partition) {
            switch (partition.type) {
                case PackagePartitions.PARTITION_SYSTEM:
                case PackagePartitions.PARTITION_SYSTEM_EXT:
                    return POLICY_SYSTEM;
                case PackagePartitions.PARTITION_VENDOR:
                    return POLICY_VENDOR;
                case PackagePartitions.PARTITION_ODM:
                    return POLICY_ODM;
                case PackagePartitions.PARTITION_OEM:
                    return POLICY_OEM;
                case PackagePartitions.PARTITION_PRODUCT:
                    return POLICY_PRODUCT;
                default:
                    throw new IllegalStateException("Unable to determine policy for "
                            + partition.getFolder());
            }
        }
    }

    /** This class holds state related to parsing the configurations of a partition. */
    private static class ParsingContext {
        // The overlay directory of the partition
        private final OverlayPartition mPartition;

        // The ordered list of configured overlays
        private final ArrayList<ParsedConfiguration> mOrderedConfigurations = new ArrayList<>();

        // The packages configured in the partition
        private final ArraySet<String> mConfiguredOverlays = new ArraySet<>();

        // Whether an mutable overlay has been configured in the partition
        private boolean mFoundMutableOverlay;

        // The current recursive depth of merging configuration files
        private int mMergeDepth;

        private ParsingContext(OverlayPartition partition) {
            mPartition = partition;
        }
    }

    /**
     * Retrieves overlays configured within the partition in increasing priority order.
     *
     * If {@code scanner} is null, then the {@link ParsedConfiguration#parsedInfo} fields of the
     * added configured overlays will be null and the parsing logic will not assert that the
     * configured overlays exist within the partition.
     *
     * @return list of configured overlays if configuration file exists; otherwise, null
     */
    @Nullable
    static ArrayList<ParsedConfiguration> getConfigurations(
            @NonNull OverlayPartition partition, @Nullable OverlayScanner scanner,
            @Nullable Map<String, ParsedOverlayInfo> packageManagerOverlayInfos) {
        if (partition.getOverlayFolder() == null) {
            return null;
        }

        if (scanner != null) {
            scanner.scanDir(partition.getOverlayFolder());
        }

        final File configFile = new File(partition.getOverlayFolder(), CONFIG_DEFAULT_FILENAME);
        if (!configFile.exists()) {
            return null;
        }

        final ParsingContext parsingContext = new ParsingContext(partition);
        readConfigFile(configFile, scanner, packageManagerOverlayInfos, parsingContext);
        return parsingContext.mOrderedConfigurations;
    }

    private static void readConfigFile(@NonNull File configFile, @Nullable OverlayScanner scanner,
            @Nullable Map<String, ParsedOverlayInfo> packageManagerOverlayInfos,
            @NonNull ParsingContext parsingContext) {
        FileReader configReader;
        try {
            configReader = new FileReader(configFile);
        } catch (FileNotFoundException e) {
            Log.w(TAG, "Couldn't find or open overlay configuration file " + configFile);
            return;
        }

        try {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(configReader);
            XmlUtils.beginDocument(parser, "config");

            int depth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, depth)) {
                final String name = parser.getName();
                switch (name) {
                    case "merge":
                        parseMerge(configFile, parser, scanner, packageManagerOverlayInfos,
                                parsingContext);
                        break;
                    case "overlay":
                        parseOverlay(configFile, parser, scanner, packageManagerOverlayInfos,
                                parsingContext);
                        break;
                    default:
                        Log.w(TAG, String.format("Tag %s is unknown in %s at %s",
                                name, configFile, parser.getPositionDescription()));
                        break;
                }
            }
        } catch (XmlPullParserException | IOException e) {
            Log.w(TAG, "Got exception parsing overlay configuration.", e);
        } finally {
            IoUtils.closeQuietly(configReader);
        }
    }

    /**
     * Parses a <merge> tag within an overlay configuration file.
     *
     * Merge tags allow for other configuration files to be "merged" at the current parsing
     * position into the current configuration file being parsed. The {@code path} attribute of the
     * tag represents the path of the file to merge relative to the directory containing overlay
     * configuration files.
     */
    private static void parseMerge(@NonNull File configFile, @NonNull XmlPullParser parser,
            @Nullable OverlayScanner scanner,
            @Nullable Map<String, ParsedOverlayInfo> packageManagerOverlayInfos,
            @NonNull ParsingContext parsingContext) {
        final String path = parser.getAttributeValue(null, "path");
        if (path == null) {
            throw new IllegalStateException(String.format("<merge> without path in %s at %s"
                    + configFile, parser.getPositionDescription()));
        }

        if (path.startsWith("/")) {
            throw new IllegalStateException(String.format(
                    "Path %s must be relative to the directory containing overlay configurations "
                            + " files in %s at %s ", path, configFile,
                    parser.getPositionDescription()));
        }

        if (parsingContext.mMergeDepth++ == MAXIMUM_MERGE_DEPTH) {
            throw new IllegalStateException(String.format(
                    "Maximum <merge> depth exceeded in %s at %s", configFile,
                    parser.getPositionDescription()));
        }

        final File configDirectory;
        final File includedConfigFile;
        try {
            configDirectory = new File(parsingContext.mPartition.getOverlayFolder(),
                    CONFIG_DIRECTORY).getCanonicalFile();
            includedConfigFile = new File(configDirectory, path).getCanonicalFile();
        } catch (IOException e) {
            throw new IllegalStateException(
                    String.format("Couldn't find or open merged configuration file %s in %s at %s",
                            path, configFile, parser.getPositionDescription()), e);
        }

        if (!includedConfigFile.exists()) {
            throw new IllegalStateException(
                    String.format("Merged configuration file %s does not exist in %s at %s",
                            path, configFile, parser.getPositionDescription()));
        }

        if (!FileUtils.contains(configDirectory, includedConfigFile)) {
            throw new IllegalStateException(
                    String.format(
                            "Merged file %s outside of configuration directory in %s at %s",
                            includedConfigFile.getAbsolutePath(), includedConfigFile,
                            parser.getPositionDescription()));
        }

        readConfigFile(includedConfigFile, scanner, packageManagerOverlayInfos, parsingContext);
        parsingContext.mMergeDepth--;
    }

    /**
     * Parses an <overlay> tag within an overlay configuration file.
     *
     * Requires a {@code package} attribute that indicates which package is being configured.
     * The optional {@code enabled} attribute controls whether or not the overlay is enabled by
     * default (default is false). The optional {@code mutable} attribute controls whether or
     * not the overlay is mutable and can have its enabled state changed at runtime (default is
     * true).
     *
     * The order in which overlays that override the same resources are configured matters. An
     * overlay will have a greater priority than overlays with configurations preceding its own
     * configuration.
     *
     * Configurations of immutable overlays must precede configurations of mutable overlays.
     * An overlay cannot be configured in multiple locations. All configured overlay must exist
     * within the partition of the configuration file. An overlay cannot be configured multiple
     * times in a single partition.
     *
     * Overlays not listed within a configuration file will be mutable and disabled by default. The
     * order of non-configured overlays when enabled by the OverlayManagerService is undefined.
     */
    private static void parseOverlay(@NonNull File configFile, @NonNull XmlPullParser parser,
            @Nullable OverlayScanner scanner,
            @Nullable Map<String, ParsedOverlayInfo> packageManagerOverlayInfos,
            @NonNull ParsingContext parsingContext) {
        Preconditions.checkArgument((scanner == null) != (packageManagerOverlayInfos == null),
                "scanner and packageManagerOverlayInfos cannot be both null or both non-null");

        final String packageName = parser.getAttributeValue(null, "package");
        if (packageName == null) {
            throw new IllegalStateException(String.format("\"<overlay> without package in %s at %s",
                    configFile, parser.getPositionDescription()));
        }

        // Ensure the overlay being configured is present in the partition during zygote
        // initialization, unless the package is an excluded overlay package.
        ParsedOverlayInfo info = null;
        if (scanner != null) {
            info = scanner.getParsedInfo(packageName);
            if (info == null
                    && scanner.isExcludedOverlayPackage(packageName, parsingContext.mPartition)) {
                Log.d(TAG, "overlay " + packageName + " in partition "
                        + parsingContext.mPartition.getOverlayFolder() + " is ignored.");
                return;
            } else if (info == null || !parsingContext.mPartition.containsOverlay(info.path)) {
                throw new IllegalStateException(
                        String.format("overlay %s not present in partition %s in %s at %s",
                                packageName, parsingContext.mPartition.getOverlayFolder(),
                                configFile, parser.getPositionDescription()));
            }
        } else {
            // Zygote shall have crashed itself, if there's an overlay apk not present in the
            // partition. For the overlay package not found in the package manager, we can assume
            // that it's an excluded overlay package.
            if (packageManagerOverlayInfos.get(packageName) == null) {
                Log.d(TAG, "overlay " + packageName + " in partition "
                        + parsingContext.mPartition.getOverlayFolder() + " is ignored.");
                return;
            }
        }

        if (parsingContext.mConfiguredOverlays.contains(packageName)) {
            throw new IllegalStateException(
                    String.format("overlay %s configured multiple times in a single partition"
                                    + " in %s at %s", packageName, configFile,
                            parser.getPositionDescription()));
        }

        boolean isEnabled = DEFAULT_ENABLED_STATE;
        final String enabled = parser.getAttributeValue(null, "enabled");
        if (enabled != null) {
            isEnabled = !"false".equals(enabled);
        }

        boolean isMutable = DEFAULT_MUTABILITY;
        final String mutable = parser.getAttributeValue(null, "mutable");
        if (mutable != null) {
            isMutable = !"false".equals(mutable);
            if (!isMutable && parsingContext.mFoundMutableOverlay) {
                throw new IllegalStateException(String.format(
                        "immutable overlays must precede mutable overlays:"
                                + " found in %s at %s",
                        configFile, parser.getPositionDescription()));
            }
        }

        if (isMutable) {
            parsingContext.mFoundMutableOverlay = true;
        } else if (!isEnabled) {
            // Default disabled, immutable overlays may be a misconfiguration of the system so warn
            // developers.
            Log.w(TAG, "found default-disabled immutable overlay " + packageName);
        }

        final ParsedConfiguration Config = new ParsedConfiguration(packageName, isEnabled,
                isMutable, parsingContext.mPartition.policy, info);
        parsingContext.mConfiguredOverlays.add(packageName);
        parsingContext.mOrderedConfigurations.add(Config);
    }
}
