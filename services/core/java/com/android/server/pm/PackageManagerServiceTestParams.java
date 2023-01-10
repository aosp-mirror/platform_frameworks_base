/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManagerInternal;
import android.content.pm.TestUtilityService;
import android.os.Build;
import android.os.Handler;
import android.os.incremental.IncrementalManager;
import android.util.ArrayMap;
import android.util.DisplayMetrics;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.om.OverlayConfig;
import com.android.server.pm.dex.ArtManagerService;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.DynamicCodeLogger;
import com.android.server.pm.dex.ViewCompiler;
import com.android.server.pm.parsing.PackageParser2;
import com.android.server.pm.permission.LegacyPermissionManagerInternal;
import com.android.server.pm.pkg.AndroidPackage;

import java.io.File;
import java.util.List;

@VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
public final class PackageManagerServiceTestParams {
    public ApexManager apexManager;
    public @Nullable String appPredictionServicePackage;
    public ArtManagerService artManagerService;
    public @Nullable String configuratorPackage;
    public int defParseFlags;
    public DefaultAppProvider defaultAppProvider;
    public DexManager dexManager;
    public DynamicCodeLogger dynamicCodeLogger;
    public List<ScanPartition> dirsToScanAsSystem;
    public boolean factoryTest;
    public ArrayMap<String, FeatureInfo> availableFeatures;
    public Handler handler;
    public @Nullable String incidentReportApproverPackage;
    public IncrementalManager incrementalManager;
    public PackageInstallerService installerService;
    public InstantAppRegistry instantAppRegistry;
    public ChangedPackagesTracker changedPackagesTracker = new ChangedPackagesTracker();
    public InstantAppResolverConnection instantAppResolverConnection;
    public ComponentName instantAppResolverSettingsComponent;
    public boolean isPreNmr1Upgrade;
    public boolean isPreNupgrade;
    public boolean isPreQupgrade;
    public boolean isUpgrade;
    public LegacyPermissionManagerInternal legacyPermissionManagerInternal;
    public DisplayMetrics Metrics;
    public ModuleInfoProvider moduleInfoProvider;
    public MovePackageHelper.MoveCallbacks moveCallbacks;
    public boolean onlyCore;
    public OverlayConfig overlayConfig;
    public PackageDexOptimizer packageDexOptimizer;
    public PackageParser2.Callback packageParserCallback;
    public PendingPackageBroadcasts pendingPackageBroadcasts;
    public PackageManagerInternal pmInternal;
    public TestUtilityService testUtilityService;
    public ProcessLoggingHandler processLoggingHandler;
    public ProtectedPackages protectedPackages;
    public @NonNull String requiredInstallerPackage;
    public @NonNull String requiredPermissionControllerPackage;
    public @NonNull String requiredUninstallerPackage;
    public @NonNull String[] requiredVerifierPackages;
    public String[] separateProcesses;
    public @NonNull String servicesExtensionPackageName;
    public @Nullable String setupWizardPackage;
    public @NonNull String sharedSystemSharedLibraryPackageName;
    public @Nullable String storageManagerPackage;
    public @Nullable String defaultTextClassifierPackage;
    public @Nullable String systemTextClassifierPackage;
    public @Nullable String overlayConfigSignaturePackage;
    public @NonNull String requiredSdkSandboxPackage;
    public ViewCompiler viewCompiler;
    public @Nullable String retailDemoPackage;
    public @Nullable String recentsPackage;
    public @Nullable String ambientContextDetectionPackage;
    public @Nullable String wearableSensingPackage;
    public ComponentName resolveComponentName;
    public ArrayMap<String, AndroidPackage> packages;
    public boolean enableFreeCacheV2;
    public int sdkVersion;
    public File appInstallDir;
    public File appLib32InstallDir;
    public boolean isEngBuild;
    public boolean isUserDebugBuild;
    public int sdkInt = Build.VERSION.SDK_INT;
    public @Nullable BackgroundDexOptService backgroundDexOptService;
    public final String incrementalVersion = Build.VERSION.INCREMENTAL;
    public BroadcastHelper broadcastHelper;
    public AppDataHelper appDataHelper;
    public InstallPackageHelper installPackageHelper;
    public RemovePackageHelper removePackageHelper;
    public InitAppsHelper initAndSystemPackageHelper;
    public DeletePackageHelper deletePackageHelper;
    public PreferredActivityHelper preferredActivityHelper;
    public ResolveIntentHelper resolveIntentHelper;
    public DexOptHelper dexOptHelper;
    public SuspendPackageHelper suspendPackageHelper;
    public DistractingPackageHelper distractingPackageHelper;
    public StorageEventHelper storageEventHelper;
}
