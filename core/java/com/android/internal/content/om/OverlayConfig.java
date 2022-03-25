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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackagePartitions;
import android.os.Build;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.Log;

import com.android.apex.ApexInfo;
import com.android.apex.XmlParser;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.om.OverlayConfigParser.OverlayPartition;
import com.android.internal.content.om.OverlayConfigParser.ParsedConfiguration;
import com.android.internal.content.om.OverlayScanner.ParsedOverlayInfo;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.TriConsumer;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Responsible for reading overlay configuration files and handling queries of overlay mutability,
 * default-enabled state, and priority.
 *
 * @see OverlayConfigParser
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class OverlayConfig {
    static final String TAG = "OverlayConfig";

    // The default priority of an overlay that has not been configured. Overlays with default
    // priority have a higher precedence than configured overlays.
    @VisibleForTesting
    public static final int DEFAULT_PRIORITY = Integer.MAX_VALUE;

    @VisibleForTesting
    public static final class Configuration {
        @Nullable
        public final ParsedConfiguration parsedConfig;

        public final int configIndex;

        public Configuration(@Nullable ParsedConfiguration parsedConfig, int configIndex) {
            this.parsedConfig = parsedConfig;
            this.configIndex = configIndex;
        }
    }

    /**
     * Interface for providing information on scanned packages.
     * TODO(147840005): Remove this when android:isStatic and android:priority are fully deprecated
     */
    public interface PackageProvider {

        /** Performs the given action for each package. */
        void forEachPackage(TriConsumer<Package, Boolean, File> p);

        interface Package {

            String getBaseApkPath();

            int getOverlayPriority();

            String getOverlayTarget();

            String getPackageName();

            int getTargetSdkVersion();

            boolean isOverlayIsStatic();
        }
    }

    private static final Comparator<ParsedConfiguration> sStaticOverlayComparator = (c1, c2) -> {
        final ParsedOverlayInfo o1 = c1.parsedInfo;
        final ParsedOverlayInfo o2 = c2.parsedInfo;
        Preconditions.checkArgument(o1.isStatic && o2.isStatic,
                "attempted to sort non-static overlay");

        if (!o1.targetPackageName.equals(o2.targetPackageName)) {
            return o1.targetPackageName.compareTo(o2.targetPackageName);
        }

        final int comparedPriority = o1.priority - o2.priority;
        return comparedPriority == 0 ? o1.path.compareTo(o2.path) : comparedPriority;
    };

    // Map of overlay package name to configured overlay settings
    private final ArrayMap<String, Configuration> mConfigurations = new ArrayMap<>();

    // Singleton instance only assigned in system server
    private static OverlayConfig sInstance;

    @VisibleForTesting
    public OverlayConfig(@Nullable File rootDirectory,
            @Nullable Supplier<OverlayScanner> scannerFactory,
            @Nullable PackageProvider packageProvider) {
        Preconditions.checkArgument((scannerFactory == null) != (packageProvider == null),
                "scannerFactory and packageProvider cannot be both null or both non-null");

        final ArrayList<OverlayPartition> partitions;
        if (rootDirectory == null) {
            partitions = new ArrayList<>(
                    PackagePartitions.getOrderedPartitions(OverlayPartition::new));
        } else {
            // Rebase the system partitions and settings file on the specified root directory.
            partitions = new ArrayList<>(PackagePartitions.getOrderedPartitions(
                    p -> new OverlayPartition(
                            new File(rootDirectory, p.getNonConicalFolder().getPath()),
                            p)));
        }

        ArrayMap<Integer, List<String>> activeApexesPerPartition = getActiveApexes(partitions);

        boolean foundConfigFile = false;
        final Map<String, ParsedOverlayInfo> packageManagerOverlayInfos =
                packageProvider == null ? null : getOverlayPackageInfos(packageProvider);

        final ArrayList<ParsedConfiguration> overlays = new ArrayList<>();
        for (int i = 0, n = partitions.size(); i < n; i++) {
            final OverlayPartition partition = partitions.get(i);
            final OverlayScanner scanner = (scannerFactory == null) ? null : scannerFactory.get();
            final ArrayList<ParsedConfiguration> partitionOverlays =
                    OverlayConfigParser.getConfigurations(partition, scanner,
                            packageManagerOverlayInfos,
                            activeApexesPerPartition.getOrDefault(partition.type,
                                    Collections.emptyList()));
            if (partitionOverlays != null) {
                foundConfigFile = true;
                overlays.addAll(partitionOverlays);
                continue;
            }

            // If the configuration file is not present, then use android:isStatic and
            // android:priority to configure the overlays in the partition.
            // TODO(147840005): Remove converting static overlays to immutable, default-enabled
            //  overlays when android:siStatic and android:priority are fully deprecated.
            final ArrayList<ParsedOverlayInfo> partitionOverlayInfos;
            if (scannerFactory != null) {
                partitionOverlayInfos = new ArrayList<>(scanner.getAllParsedInfos());
            } else {
                // Filter out overlays not present in the partition.
                partitionOverlayInfos = new ArrayList<>(packageManagerOverlayInfos.values());
                for (int j = partitionOverlayInfos.size() - 1; j >= 0; j--) {
                    if (!partition.containsFile(partitionOverlayInfos.get(j)
                            .getOriginalPartitionPath())) {
                        partitionOverlayInfos.remove(j);
                    }
                }
            }

            // Static overlays are configured as immutable, default-enabled overlays.
            final ArrayList<ParsedConfiguration> partitionConfigs = new ArrayList<>();
            for (int j = 0, m = partitionOverlayInfos.size(); j < m; j++) {
                final ParsedOverlayInfo p = partitionOverlayInfos.get(j);
                if (p.isStatic) {
                    partitionConfigs.add(new ParsedConfiguration(p.packageName,
                            true /* enabled */, false /* mutable */, partition.policy, p));
                }
            }

            partitionConfigs.sort(sStaticOverlayComparator);
            overlays.addAll(partitionConfigs);
        }

        if (!foundConfigFile) {
            // If no overlay configuration files exist, disregard partition precedence and allow
            // android:priority to reorder overlays across partition boundaries.
            overlays.sort(sStaticOverlayComparator);
        }

        for (int i = 0, n = overlays.size(); i < n; i++) {
            // Add the configurations to a map so definitions of an overlay in an earlier
            // partition can be replaced by an overlay with the same package name in a later
            // partition.
            final ParsedConfiguration config = overlays.get(i);
            mConfigurations.put(config.packageName, new Configuration(config, i));
        }
    }

    /**
     * Creates an instance of OverlayConfig for use in the zygote process.
     * This instance will not include information of static overlays existing outside of a partition
     * overlay directory.
     */
    @NonNull
    public static OverlayConfig getZygoteInstance() {
        Trace.traceBegin(Trace.TRACE_TAG_RRO, "OverlayConfig#getZygoteInstance");
        try {
            return new OverlayConfig(null /* rootDirectory */, OverlayScanner::new,
                    null /* packageProvider */);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_RRO);
        }
    }

    /**
     * Initializes a singleton instance for use in the system process.
     * Can only be called once. This instance is cached so future invocations of
     * {@link #getSystemInstance()} will return the initialized instance.
     */
    @NonNull
    public static OverlayConfig initializeSystemInstance(PackageProvider packageProvider) {
        Trace.traceBegin(Trace.TRACE_TAG_RRO, "OverlayConfig#initializeSystemInstance");
        try {
            sInstance = new OverlayConfig(null, null, packageProvider);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_RRO);
        }
        return sInstance;
    }

    /**
     * Retrieves the singleton instance initialized by
     * {@link #initializeSystemInstance(PackageProvider)}.
     */
    @NonNull
    public static OverlayConfig getSystemInstance() {
        if (sInstance == null) {
            throw new IllegalStateException("System instance not initialized");
        }

        return sInstance;
    }

    @VisibleForTesting
    @Nullable
    public Configuration getConfiguration(@NonNull String packageName) {
        return mConfigurations.get(packageName);
    }

    /**
     * Returns whether the overlay is enabled by default.
     * Overlays that are not configured are disabled by default.
     *
     * If an immutable overlay has its enabled state change, the new enabled state is applied to the
     * overlay.
     *
     * When a mutable is first seen by the OverlayManagerService, the default-enabled state will be
     * applied to the overlay. If the configured default-enabled state changes in a subsequent boot,
     * the default-enabled state will not be applied to the overlay.
     *
     * The configured enabled state will only be applied when:
     * <ul>
     * <li> The device is factory reset
     * <li> The overlay is removed from the device and added back to the device in a future OTA
     * <li> The overlay changes its package name
     * <li> The overlay changes its target package name or target overlayable name
     * <li> An immutable overlay becomes mutable
     * </ul>
     */
    public boolean isEnabled(String packageName) {
        final Configuration config = mConfigurations.get(packageName);
        return config == null? OverlayConfigParser.DEFAULT_ENABLED_STATE
                : config.parsedConfig.enabled;
    }

    /**
     * Returns whether the overlay is mutable and can have its enabled state changed dynamically.
     * Overlays that are not configured are mutable.
     */
    public boolean isMutable(String packageName) {
        final Configuration config = mConfigurations.get(packageName);
        return config == null ? OverlayConfigParser.DEFAULT_MUTABILITY
                : config.parsedConfig.mutable;
    }

    /**
     * Returns an integer corresponding to the priority of the overlay.
     * When multiple overlays override the same resource, the overlay with the highest priority will
     * will have its value chosen. Overlays that are not configured have a priority of
     * {@link Integer#MAX_VALUE}.
     */
    public int getPriority(String packageName) {
        final Configuration config = mConfigurations.get(packageName);
        return config == null ? DEFAULT_PRIORITY : config.configIndex;
    }

    @NonNull
    private ArrayList<Configuration> getSortedOverlays() {
        final ArrayList<Configuration> sortedOverlays = new ArrayList<>();
        for (int i = 0, n = mConfigurations.size(); i < n; i++) {
            sortedOverlays.add(mConfigurations.valueAt(i));
        }
        sortedOverlays.sort(Comparator.comparingInt(o -> o.configIndex));
        return sortedOverlays;
    }

    @NonNull
    private static Map<String, ParsedOverlayInfo> getOverlayPackageInfos(
            @NonNull PackageProvider packageManager) {
        final HashMap<String, ParsedOverlayInfo> overlays = new HashMap<>();
        packageManager.forEachPackage((PackageProvider.Package p, Boolean isSystem,
                @Nullable File preInstalledApexPath) -> {
            if (p.getOverlayTarget() != null && isSystem) {
                overlays.put(p.getPackageName(), new ParsedOverlayInfo(p.getPackageName(),
                        p.getOverlayTarget(), p.getTargetSdkVersion(), p.isOverlayIsStatic(),
                        p.getOverlayPriority(), new File(p.getBaseApkPath()),
                        preInstalledApexPath));
            }
        });
        return overlays;
    }

    /** Returns a map of PartitionType to List of active APEX module names. */
    @NonNull
    private static ArrayMap<Integer, List<String>> getActiveApexes(
            @NonNull List<OverlayPartition> partitions) {
        // An Overlay in an APEX, which is an update of an APEX in a given partition,
        // is considered as belonging to that partition.
        ArrayMap<Integer, List<String>> result = new ArrayMap<>();
        for (OverlayPartition partition : partitions) {
            result.put(partition.type, new ArrayList<String>());
        }
        // Read from apex-info-list because ApexManager is not accessible to zygote.
        File apexInfoList = new File("/apex/apex-info-list.xml");
        if (apexInfoList.exists() && apexInfoList.canRead()) {
            try (FileInputStream stream = new FileInputStream(apexInfoList)) {
                List<ApexInfo> apexInfos = XmlParser.readApexInfoList(stream).getApexInfo();
                for (ApexInfo info : apexInfos) {
                    if (info.getIsActive()) {
                        for (OverlayPartition partition : partitions) {
                            if (partition.containsPath(info.getPreinstalledModulePath())) {
                                result.get(partition.type).add(info.getModuleName());
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error reading apex-info-list: " + e);
            }
        }
        return result;
    }

    /** Represents a single call to idmap create-multiple. */
    @VisibleForTesting
    public static class IdmapInvocation {
        public final boolean enforceOverlayable;
        public final String policy;
        public final ArrayList<String> overlayPaths = new ArrayList<>();

        IdmapInvocation(boolean enforceOverlayable, @NonNull String policy) {
            this.enforceOverlayable = enforceOverlayable;
            this.policy = policy;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + String.format("{enforceOverlayable=%s, policy=%s"
                            + ", overlayPaths=[%s]}", enforceOverlayable, policy,
                    String.join(", ", overlayPaths));
        }
    }

    /**
     * Retrieves a list of immutable framework overlays in order of least precedence to greatest
     * precedence.
     */
    @VisibleForTesting
    public ArrayList<IdmapInvocation> getImmutableFrameworkOverlayIdmapInvocations() {
        final ArrayList<IdmapInvocation> idmapInvocations = new ArrayList<>();
        final ArrayList<Configuration> sortedConfigs = getSortedOverlays();
        for (int i = 0, n = sortedConfigs.size(); i < n; i++) {
            final Configuration overlay = sortedConfigs.get(i);
            if (overlay.parsedConfig.mutable || !overlay.parsedConfig.enabled
                    || !"android".equals(overlay.parsedConfig.parsedInfo.targetPackageName)) {
                continue;
            }

            // Only enforce that overlays targeting packages with overlayable declarations abide by
            // those declarations if the target sdk of the overlay is at least Q (when overlayable
            // was introduced).
            final boolean enforceOverlayable = overlay.parsedConfig.parsedInfo.targetSdkVersion
                    >= Build.VERSION_CODES.Q;

            // Determine if the idmap for the current overlay can be generated in the last idmap
            // create-multiple invocation.
            IdmapInvocation invocation = null;
            if (!idmapInvocations.isEmpty()) {
                final IdmapInvocation last = idmapInvocations.get(idmapInvocations.size() - 1);
                if (last.enforceOverlayable == enforceOverlayable
                        && last.policy.equals(overlay.parsedConfig.policy)) {
                    invocation = last;
                }
            }

            if (invocation == null) {
                invocation = new IdmapInvocation(enforceOverlayable, overlay.parsedConfig.policy);
                idmapInvocations.add(invocation);
            }

            invocation.overlayPaths.add(overlay.parsedConfig.parsedInfo.path.getAbsolutePath());
        }
        return idmapInvocations;
    }

    /**
     * Creates idmap files for immutable overlays targeting the framework packages. Currently the
     * android package is the only preloaded system package. Only the zygote can invoke this method.
     *
     * @return the paths of the created idmap files
     */
    @NonNull
    public String[] createImmutableFrameworkIdmapsInZygote() {
        final String targetPath = "/system/framework/framework-res.apk";
        final ArrayList<String> idmapPaths = new ArrayList<>();
        final ArrayList<IdmapInvocation> idmapInvocations =
                getImmutableFrameworkOverlayIdmapInvocations();

        for (int i = 0, n = idmapInvocations.size(); i < n; i++) {
            final IdmapInvocation invocation = idmapInvocations.get(i);
            final String[] idmaps = createIdmap(targetPath,
                    invocation.overlayPaths.toArray(new String[0]),
                    new String[]{OverlayConfigParser.OverlayPartition.POLICY_PUBLIC,
                            invocation.policy},
                    invocation.enforceOverlayable);

            if (idmaps == null) {
                Log.w(TAG, "'idmap2 create-multiple' failed: no mutable=\"false\" overlays"
                        + " targeting \"android\" will be loaded");
                return new String[0];
            }

            idmapPaths.addAll(Arrays.asList(idmaps));
        }

        return idmapPaths.toArray(new String[0]);
    }

    /** Dump all overlay configurations to the Printer. */
    public void dump(@NonNull PrintWriter writer) {
        final IndentingPrintWriter ipw = new IndentingPrintWriter(writer);
        ipw.println("Overlay configurations:");
        ipw.increaseIndent();

        final ArrayList<Configuration> configurations = new ArrayList<>(mConfigurations.values());
        configurations.sort(Comparator.comparingInt(o -> o.configIndex));
        for (int i = 0; i < configurations.size(); i++) {
            final Configuration configuration = configurations.get(i);
            ipw.print(configuration.configIndex);
            ipw.print(", ");
            ipw.print(configuration.parsedConfig);
            ipw.println();
        }
        ipw.decreaseIndent();
        ipw.println();
    }

    /**
     * For each overlay APK, this creates the idmap file that allows the overlay to override the
     * target package.
     *
     * @return the paths of the created idmap
     */
    private static native String[] createIdmap(@NonNull String targetPath,
            @NonNull String[] overlayPath, @NonNull String[] policies, boolean enforceOverlayable);
}
