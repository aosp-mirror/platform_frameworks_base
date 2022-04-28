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

import android.app.ActivityManagerInternal;
import android.app.backup.IBackupManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.incremental.IncrementalManager;
import android.util.DisplayMetrics;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemConfig;
import com.android.server.compat.PlatformCompat;
import com.android.server.pm.dex.ArtManagerService;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.ViewCompiler;
import com.android.server.pm.parsing.PackageParser2;
import com.android.server.pm.permission.LegacyPermissionManagerInternal;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.pm.resolution.ComponentResolver;
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Unit tests will instantiate, extend and/or mock to mock dependencies / behaviors.
 *
 * NOTE: All getters should return the same instance for every call.
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
public class PackageManagerServiceInjector {
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    interface Producer<T> {
        /** Produce an instance of type {@link T} */
        T produce(PackageManagerServiceInjector injector, PackageManagerService packageManager);
    }

    interface ProducerWithArgument<T, R> {
        T produce(PackageManagerServiceInjector injector, PackageManagerService packageManager,
                R argument);
    }

    interface ServiceProducer {
        <T> T produce(Class<T> c);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    static class Singleton<T> {
        private final Producer<T> mProducer;
        private volatile T mInstance = null;

        Singleton(Producer<T> producer) {
            this.mProducer = producer;
        }

        T get(PackageManagerServiceInjector injector,
                PackageManagerService packageManagerService) {
            if (mInstance == null) {
                mInstance = mProducer.produce(injector, packageManagerService);
            }
            return mInstance;
        }
    }

    private PackageManagerService mPackageManager;

    private final PackageAbiHelper mAbiHelper;
    private final Context mContext;
    private final PackageManagerTracedLock mLock;
    private final Installer mInstaller;
    private final Object mInstallLock;
    private final Handler mBackgroundHandler;
    private final Executor mBackgroundExecutor;
    private final List<ScanPartition> mSystemPartitions;

    // ----- producers -----
    private final Singleton<ComponentResolver>
            mComponentResolverProducer;
    private final Singleton<PermissionManagerServiceInternal>
            mPermissionManagerServiceProducer;
    private final Singleton<UserManagerService>
            mUserManagerProducer;
    private final Singleton<Settings> mSettingsProducer;
    private final Singleton<AppsFilterImpl> mAppsFilterProducer;
    private final Singleton<PlatformCompat>
            mPlatformCompatProducer;
    private final Singleton<SystemConfig> mSystemConfigProducer;
    private final Singleton<PackageDexOptimizer>
            mPackageDexOptimizerProducer;
    private final Singleton<DexManager> mDexManagerProducer;
    private final Singleton<ArtManagerService>
            mArtManagerServiceProducer;
    private final Singleton<ApexManager> mApexManagerProducer;
    private final Singleton<ViewCompiler> mViewCompilerProducer;
    private final Singleton<IncrementalManager>
            mIncrementalManagerProducer;
    private final Singleton<DefaultAppProvider>
            mDefaultAppProviderProducer;
    private final Singleton<DisplayMetrics>
            mDisplayMetricsProducer;
    private final Producer<PackageParser2>
            mScanningCachingPackageParserProducer;
    private final Producer<PackageParser2>
            mScanningPackageParserProducer;
    private final Producer<PackageParser2>
            mPreparingPackageParserProducer;
    private final Singleton<PackageInstallerService>
            mPackageInstallerServiceProducer;
    private final ProducerWithArgument<InstantAppResolverConnection, ComponentName>
            mInstantAppResolverConnectionProducer;
    private final Singleton<LegacyPermissionManagerInternal>
            mLegacyPermissionManagerInternalProducer;
    private final SystemWrapper mSystemWrapper;
    private final ServiceProducer mGetLocalServiceProducer;
    private final ServiceProducer mGetSystemServiceProducer;
    private final Singleton<ModuleInfoProvider>
            mModuleInfoProviderProducer;
    private final Singleton<DomainVerificationManagerInternal>
            mDomainVerificationManagerInternalProducer;
    private final Singleton<Handler> mHandlerProducer;
    private final Singleton<BackgroundDexOptService> mBackgroundDexOptService;
    private final Singleton<IBackupManager> mIBackupManager;
    private final Singleton<SharedLibrariesImpl> mSharedLibrariesProducer;

    PackageManagerServiceInjector(Context context, PackageManagerTracedLock lock,
            Installer installer, Object installLock, PackageAbiHelper abiHelper,
            Handler backgroundHandler,
            List<ScanPartition> systemPartitions,
            Producer<ComponentResolver> componentResolverProducer,
            Producer<PermissionManagerServiceInternal> permissionManagerServiceProducer,
            Producer<UserManagerService> userManagerProducer,
            Producer<Settings> settingsProducer,
            Producer<AppsFilterImpl> appsFilterProducer,
            Producer<PlatformCompat> platformCompatProducer,
            Producer<SystemConfig> systemConfigProducer,
            Producer<PackageDexOptimizer> packageDexOptimizerProducer,
            Producer<DexManager> dexManagerProducer,
            Producer<ArtManagerService> artManagerServiceProducer,
            Producer<ApexManager> apexManagerProducer,
            Producer<ViewCompiler> viewCompilerProducer,
            Producer<IncrementalManager> incrementalManagerProducer,
            Producer<DefaultAppProvider> defaultAppProviderProducer,
            Producer<DisplayMetrics> displayMetricsProducer,
            Producer<PackageParser2> scanningCachingPackageParserProducer,
            Producer<PackageParser2> scanningPackageParserProducer,
            Producer<PackageParser2> preparingPackageParserProducer,
            Producer<PackageInstallerService> packageInstallerServiceProducer,
            ProducerWithArgument<InstantAppResolverConnection,
                    ComponentName>
                    instantAppResolverConnectionProducer,
            Producer<ModuleInfoProvider> moduleInfoProviderProducer,
            Producer<LegacyPermissionManagerInternal> legacyPermissionManagerInternalProducer,
            Producer<DomainVerificationManagerInternal>
                    domainVerificationManagerInternalProducer,
            Producer<Handler> handlerProducer,
            SystemWrapper systemWrapper,
            ServiceProducer getLocalServiceProducer,
            ServiceProducer getSystemServiceProducer,
            Producer<BackgroundDexOptService> backgroundDexOptService,
            Producer<IBackupManager> iBackupManager,
            Producer<SharedLibrariesImpl> sharedLibrariesProducer) {
        mContext = context;
        mLock = lock;
        mInstaller = installer;
        mAbiHelper = abiHelper;
        mInstallLock = installLock;
        mBackgroundHandler = backgroundHandler;
        mBackgroundExecutor = new HandlerExecutor(backgroundHandler);
        mSystemPartitions = systemPartitions;
        mComponentResolverProducer = new Singleton<>(
                componentResolverProducer);
        mPermissionManagerServiceProducer = new Singleton<>(
                permissionManagerServiceProducer);
        mUserManagerProducer = new Singleton<>(userManagerProducer);
        mSettingsProducer = new Singleton<>(settingsProducer);
        mAppsFilterProducer = new Singleton<>(appsFilterProducer);
        mPlatformCompatProducer = new Singleton<>(
                platformCompatProducer);
        mSystemConfigProducer = new Singleton<>(systemConfigProducer);
        mPackageDexOptimizerProducer = new Singleton<>(
                packageDexOptimizerProducer);
        mDexManagerProducer = new Singleton<>(dexManagerProducer);
        mArtManagerServiceProducer = new Singleton<>(
                artManagerServiceProducer);
        mApexManagerProducer = new Singleton<>(apexManagerProducer);
        mViewCompilerProducer = new Singleton<>(viewCompilerProducer);
        mIncrementalManagerProducer = new Singleton<>(
                incrementalManagerProducer);
        mDefaultAppProviderProducer = new Singleton<>(
                defaultAppProviderProducer);
        mDisplayMetricsProducer = new Singleton<>(
                displayMetricsProducer);
        mScanningCachingPackageParserProducer = scanningCachingPackageParserProducer;
        mScanningPackageParserProducer = scanningPackageParserProducer;
        mPreparingPackageParserProducer = preparingPackageParserProducer;
        mPackageInstallerServiceProducer = new Singleton<>(
                packageInstallerServiceProducer);
        mInstantAppResolverConnectionProducer = instantAppResolverConnectionProducer;
        mModuleInfoProviderProducer = new Singleton<>(
                moduleInfoProviderProducer);
        mLegacyPermissionManagerInternalProducer = new Singleton<>(
                legacyPermissionManagerInternalProducer);
        mSystemWrapper = systemWrapper;
        mGetLocalServiceProducer = getLocalServiceProducer;
        mGetSystemServiceProducer = getSystemServiceProducer;
        mDomainVerificationManagerInternalProducer =
                new Singleton<>(
                        domainVerificationManagerInternalProducer);
        mHandlerProducer = new Singleton<>(handlerProducer);
        mBackgroundDexOptService = new Singleton<>(backgroundDexOptService);
        mIBackupManager = new Singleton<>(iBackupManager);
        mSharedLibrariesProducer = new Singleton<>(sharedLibrariesProducer);
    }

    /**
     * Bootstraps this injector with the {@link PackageManagerService instance to which it
     * belongs.
     */
    public void bootstrap(PackageManagerService pm) {
        this.mPackageManager = pm;
    }

    public UserManagerInternal getUserManagerInternal() {
        return getUserManagerService().getInternalForInjectorOnly();
    }

    public PackageAbiHelper getAbiHelper() {
        return mAbiHelper;
    }

    public Object getInstallLock() {
        return mInstallLock;
    }

    public List<ScanPartition> getSystemPartitions() {
        return mSystemPartitions;
    }

    public UserManagerService getUserManagerService() {
        return mUserManagerProducer.get(this, mPackageManager);
    }

    public PackageManagerTracedLock getLock() {
        return mLock;
    }

    public Installer getInstaller() {
        return mInstaller;
    }

    public ComponentResolver getComponentResolver() {
        return mComponentResolverProducer.get(this, mPackageManager);
    }

    public PermissionManagerServiceInternal getPermissionManagerServiceInternal() {
        return mPermissionManagerServiceProducer.get(this, mPackageManager);
    }

    public Context getContext() {
        return mContext;
    }

    public Settings getSettings() {
        return mSettingsProducer.get(this, mPackageManager);
    }

    public AppsFilterImpl getAppsFilter() {
        return mAppsFilterProducer.get(this, mPackageManager);
    }

    public PlatformCompat getCompatibility() {
        return mPlatformCompatProducer.get(this, mPackageManager);
    }

    public SystemConfig getSystemConfig() {
        return mSystemConfigProducer.get(this, mPackageManager);
    }

    public PackageDexOptimizer getPackageDexOptimizer() {
        return mPackageDexOptimizerProducer.get(this, mPackageManager);
    }

    public DexManager getDexManager() {
        return mDexManagerProducer.get(this, mPackageManager);
    }

    public ArtManagerService getArtManagerService() {
        return mArtManagerServiceProducer.get(this, mPackageManager);
    }

    public ApexManager getApexManager() {
        return mApexManagerProducer.get(this, mPackageManager);
    }

    public ViewCompiler getViewCompiler() {
        return mViewCompilerProducer.get(this, mPackageManager);
    }

    public Handler getBackgroundHandler() {
        return mBackgroundHandler;
    }

    public Executor getBackgroundExecutor() {
        return mBackgroundExecutor;
    }

    public DisplayMetrics getDisplayMetrics() {
        return mDisplayMetricsProducer.get(this, mPackageManager);
    }

    public <T> T getLocalService(Class<T> c) {
        return mGetLocalServiceProducer.produce(c);
    }

    public <T> T getSystemService(Class<T> c) {
        return mGetSystemServiceProducer.produce(c);
    }

    public SystemWrapper getSystemWrapper() {
        return mSystemWrapper;
    }

    public IncrementalManager getIncrementalManager() {
        return mIncrementalManagerProducer.get(this, mPackageManager);
    }

    public DefaultAppProvider getDefaultAppProvider() {
        return mDefaultAppProviderProducer.get(this, mPackageManager);
    }

    public PackageParser2 getScanningCachingPackageParser() {
        return mScanningCachingPackageParserProducer.produce(this, mPackageManager);
    }

    public PackageParser2 getScanningPackageParser() {
        return mScanningPackageParserProducer.produce(this, mPackageManager);
    }

    public PackageParser2 getPreparingPackageParser() {
        return mPreparingPackageParserProducer.produce(this, mPackageManager);
    }

    public PackageInstallerService getPackageInstallerService() {
        return mPackageInstallerServiceProducer.get(this, mPackageManager);
    }

    public InstantAppResolverConnection getInstantAppResolverConnection(
            ComponentName instantAppResolverComponent) {
        return mInstantAppResolverConnectionProducer.produce(
                this, mPackageManager, instantAppResolverComponent);
    }

    public ModuleInfoProvider getModuleInfoProvider() {
        return mModuleInfoProviderProducer.get(this, mPackageManager);
    }

    public LegacyPermissionManagerInternal getLegacyPermissionManagerInternal() {
        return mLegacyPermissionManagerInternalProducer.get(this, mPackageManager);
    }

    public DomainVerificationManagerInternal getDomainVerificationManagerInternal() {
        return mDomainVerificationManagerInternalProducer.get(this, mPackageManager);
    }

    public Handler getHandler() {
        return mHandlerProducer.get(this, mPackageManager);
    }

    public ActivityManagerInternal getActivityManagerInternal() {
        return getLocalService(ActivityManagerInternal.class);
    }

    public BackgroundDexOptService getBackgroundDexOptService() {
        return mBackgroundDexOptService.get(this, mPackageManager);
    }

    public IBackupManager getIBackupManager() {
        return mIBackupManager.get(this, mPackageManager);
    }

    public SharedLibrariesImpl getSharedLibrariesImpl() {
        return mSharedLibrariesProducer.get(this, mPackageManager);
    }

    /** Provides an abstraction to static access to system state. */
    public interface SystemWrapper {
        void disablePackageCaches();
        void enablePackageCaches();
    }
}
