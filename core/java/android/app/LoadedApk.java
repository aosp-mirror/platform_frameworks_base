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

package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.dex.ArtManager;
import android.content.pm.split.SplitDependencyLoader;
import android.content.res.AssetManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.GraphicsEnvironment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.StrictMode;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.net.config.NetworkSecurityConfigProvider;
import android.sysprop.VndkProperties;
import android.text.TextUtils;
import android.util.AndroidRuntimeException;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.DisplayAdjustments;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.VMRuntime;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

final class IntentReceiverLeaked extends AndroidRuntimeException {
    @UnsupportedAppUsage
    public IntentReceiverLeaked(String msg) {
        super(msg);
    }
}

final class ServiceConnectionLeaked extends AndroidRuntimeException {
    @UnsupportedAppUsage
    public ServiceConnectionLeaked(String msg) {
        super(msg);
    }
}

/**
 * Local state maintained about a currently loaded .apk.
 * @hide
 */
public final class LoadedApk {
    static final String TAG = "LoadedApk";
    static final boolean DEBUG = false;

    @UnsupportedAppUsage
    private final ActivityThread mActivityThread;
    @UnsupportedAppUsage
    final String mPackageName;
    @UnsupportedAppUsage
    private ApplicationInfo mApplicationInfo;
    @UnsupportedAppUsage
    private String mAppDir;
    @UnsupportedAppUsage
    private String mResDir;
    private String[] mLegacyOverlayDirs;
    private String[] mOverlayPaths;
    @UnsupportedAppUsage
    private String mDataDir;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private String mLibDir;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private File mDataDirFile;
    private File mDeviceProtectedDataDirFile;
    private File mCredentialProtectedDataDirFile;
    @UnsupportedAppUsage
    private final ClassLoader mBaseClassLoader;
    private ClassLoader mDefaultClassLoader;
    private final boolean mSecurityViolation;
    private final boolean mIncludeCode;
    private final boolean mRegisterPackage;
    @UnsupportedAppUsage
    private final DisplayAdjustments mDisplayAdjustments = new DisplayAdjustments();
    /** WARNING: This may change. Don't hold external references to it. */
    @UnsupportedAppUsage
    Resources mResources;
    @UnsupportedAppUsage
    private ClassLoader mClassLoader;
    @UnsupportedAppUsage
    private Application mApplication;

    private String[] mSplitNames;
    private String[] mSplitAppDirs;
    @UnsupportedAppUsage
    private String[] mSplitResDirs;
    private String[] mSplitClassLoaderNames;

    @UnsupportedAppUsage
    private final ArrayMap<Context, ArrayMap<BroadcastReceiver, ReceiverDispatcher>> mReceivers
        = new ArrayMap<>();
    private final ArrayMap<Context, ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>> mUnregisteredReceivers
        = new ArrayMap<>();
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final ArrayMap<Context, ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher>> mServices
        = new ArrayMap<>();
    private final ArrayMap<Context, ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher>> mUnboundServices
        = new ArrayMap<>();
    private AppComponentFactory mAppComponentFactory;

    /**
     * We cache the instantiated application object for each package on this process here.
     */
    @GuardedBy("sApplications")
    private static final ArrayMap<String, Application> sApplications = new ArrayMap<>(4);

    private final Object mLock = new Object();

    Application getApplication() {
        return mApplication;
    }

    /**
     * Create information about a new .apk
     *
     * NOTE: This constructor is called with ActivityThread's lock held,
     * so MUST NOT call back out to the activity manager.
     */
    public LoadedApk(ActivityThread activityThread, ApplicationInfo aInfo,
            CompatibilityInfo compatInfo, ClassLoader baseLoader,
            boolean securityViolation, boolean includeCode, boolean registerPackage) {

        mActivityThread = activityThread;
        setApplicationInfo(aInfo);
        mPackageName = aInfo.packageName;
        mBaseClassLoader = baseLoader;
        mSecurityViolation = securityViolation;
        mIncludeCode = includeCode;
        mRegisterPackage = registerPackage;
        mDisplayAdjustments.setCompatibilityInfo(compatInfo);
        mAppComponentFactory = createAppFactory(mApplicationInfo, mBaseClassLoader);
    }

    private static ApplicationInfo adjustNativeLibraryPaths(ApplicationInfo info) {
        // If we're dealing with a multi-arch application that has both
        // 32 and 64 bit shared libraries, we might need to choose the secondary
        // depending on what the current runtime's instruction set is.
        if (info.primaryCpuAbi != null && info.secondaryCpuAbi != null) {
            final String runtimeIsa = VMRuntime.getRuntime().vmInstructionSet();

            // Get the instruction set that the libraries of secondary Abi is supported.
            // In presence of a native bridge this might be different than the one secondary Abi used.
            String secondaryIsa = VMRuntime.getInstructionSet(info.secondaryCpuAbi);
            final String secondaryDexCodeIsa = SystemProperties.get("ro.dalvik.vm.isa." + secondaryIsa);
            secondaryIsa = secondaryDexCodeIsa.isEmpty() ? secondaryIsa : secondaryDexCodeIsa;

            // If the runtimeIsa is the same as the primary isa, then we do nothing.
            // Everything will be set up correctly because info.nativeLibraryDir will
            // correspond to the right ISA.
            if (runtimeIsa.equals(secondaryIsa)) {
                final ApplicationInfo modified = new ApplicationInfo(info);
                modified.nativeLibraryDir = modified.secondaryNativeLibraryDir;
                modified.primaryCpuAbi = modified.secondaryCpuAbi;
                return modified;
            }
        }

        return info;
    }

    /**
     * Create information about the system package.
     * Must call {@link #installSystemApplicationInfo} later.
     */
    LoadedApk(ActivityThread activityThread) {
        mActivityThread = activityThread;
        mApplicationInfo = new ApplicationInfo();
        mApplicationInfo.packageName = "android";
        mPackageName = "android";
        mAppDir = null;
        mResDir = null;
        mSplitAppDirs = null;
        mSplitResDirs = null;
        mSplitClassLoaderNames = null;
        mLegacyOverlayDirs = null;
        mOverlayPaths = null;
        mDataDir = null;
        mDataDirFile = null;
        mDeviceProtectedDataDirFile = null;
        mCredentialProtectedDataDirFile = null;
        mLibDir = null;
        mBaseClassLoader = null;
        mSecurityViolation = false;
        mIncludeCode = true;
        mRegisterPackage = false;
        mResources = Resources.getSystem();
        mDefaultClassLoader = ClassLoader.getSystemClassLoader();
        mAppComponentFactory = createAppFactory(mApplicationInfo, mDefaultClassLoader);
        mClassLoader = mAppComponentFactory.instantiateClassLoader(mDefaultClassLoader,
                new ApplicationInfo(mApplicationInfo));
    }

    /**
     * Sets application info about the system package.
     */
    void installSystemApplicationInfo(ApplicationInfo info, ClassLoader classLoader) {
        assert info.packageName.equals("android");
        mApplicationInfo = info;
        mDefaultClassLoader = classLoader;
        mAppComponentFactory = createAppFactory(info, mDefaultClassLoader);
        mClassLoader = mAppComponentFactory.instantiateClassLoader(mDefaultClassLoader,
                new ApplicationInfo(mApplicationInfo));
    }

    private AppComponentFactory createAppFactory(ApplicationInfo appInfo, ClassLoader cl) {
        if (mIncludeCode && appInfo.appComponentFactory != null && cl != null) {
            try {
                return (AppComponentFactory)
                        cl.loadClass(appInfo.appComponentFactory).newInstance();
            } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
                Slog.e(TAG, "Unable to instantiate appComponentFactory", e);
            }
        }
        return AppComponentFactory.DEFAULT;
    }

    public AppComponentFactory getAppFactory() {
        return mAppComponentFactory;
    }

    @UnsupportedAppUsage
    public String getPackageName() {
        return mPackageName;
    }

    @UnsupportedAppUsage
    public ApplicationInfo getApplicationInfo() {
        return mApplicationInfo;
    }

    public int getTargetSdkVersion() {
        return mApplicationInfo.targetSdkVersion;
    }

    public boolean isSecurityViolation() {
        return mSecurityViolation;
    }

    @UnsupportedAppUsage(trackingBug = 172409979)
    public CompatibilityInfo getCompatibilityInfo() {
        return mDisplayAdjustments.getCompatibilityInfo();
    }

    public void setCompatibilityInfo(CompatibilityInfo compatInfo) {
        mDisplayAdjustments.setCompatibilityInfo(compatInfo);
    }

    /**
     * Gets the array of shared libraries that are listed as
     * used by the given package.
     *
     * @param packageName the name of the package (note: not its
     * file name)
     * @return null-ok; the array of shared libraries, each one
     * a fully-qualified path
     */
    private static String[] getLibrariesFor(String packageName) {
        ApplicationInfo ai = null;
        try {
            ai = ActivityThread.getPackageManager().getApplicationInfo(packageName,
                    PackageManager.GET_SHARED_LIBRARY_FILES, UserHandle.myUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        if (ai == null) {
            return null;
        }

        return ai.sharedLibraryFiles;
    }

    /**
     * Update the ApplicationInfo for an app. If oldPaths is null, all the paths are considered
     * new.
     * @param aInfo The new ApplicationInfo to use for this LoadedApk
     * @param oldPaths The code paths for the old ApplicationInfo object. null means no paths can
     *                 be reused.
     */
    public void updateApplicationInfo(@NonNull ApplicationInfo aInfo,
            @Nullable List<String> oldPaths) {
        setApplicationInfo(aInfo);

        final List<String> newPaths = new ArrayList<>();
        makePaths(mActivityThread, aInfo, newPaths);
        final List<String> addedPaths = new ArrayList<>(newPaths.size());

        if (oldPaths != null) {
            for (String path : newPaths) {
                final String apkName = path.substring(path.lastIndexOf(File.separator));
                boolean match = false;
                for (String oldPath : oldPaths) {
                    final String oldApkName = oldPath.substring(oldPath.lastIndexOf(File.separator));
                    if (apkName.equals(oldApkName)) {
                        match = true;
                        break;
                    }
                }
                if (!match) {
                    addedPaths.add(path);
                }
            }
        } else {
            addedPaths.addAll(newPaths);
        }
        synchronized (mLock) {
            createOrUpdateClassLoaderLocked(addedPaths);
            if (mResources != null) {
                final String[] splitPaths;
                try {
                    splitPaths = getSplitPaths(null);
                } catch (NameNotFoundException e) {
                    // This should NEVER fail.
                    throw new AssertionError("null split not found");
                }

                mResources = ResourcesManager.getInstance().getResources(null, mResDir,
                        splitPaths, mLegacyOverlayDirs, mOverlayPaths,
                        mApplicationInfo.sharedLibraryFiles, null, null, getCompatibilityInfo(),
                        getClassLoader(), mApplication == null ? null
                                : mApplication.getResources().getLoaders());
            }
        }
        mAppComponentFactory = createAppFactory(aInfo, mDefaultClassLoader);
    }

    private void setApplicationInfo(ApplicationInfo aInfo) {
        final int myUid = Process.myUid();
        aInfo = adjustNativeLibraryPaths(aInfo);
        mApplicationInfo = aInfo;
        mAppDir = aInfo.sourceDir;
        mResDir = aInfo.uid == myUid ? aInfo.sourceDir : aInfo.publicSourceDir;
        mLegacyOverlayDirs = aInfo.resourceDirs;
        mOverlayPaths = aInfo.overlayPaths;
        mDataDir = aInfo.dataDir;
        mLibDir = aInfo.nativeLibraryDir;
        mDataDirFile = FileUtils.newFileOrNull(aInfo.dataDir);
        mDeviceProtectedDataDirFile = FileUtils.newFileOrNull(aInfo.deviceProtectedDataDir);
        mCredentialProtectedDataDirFile = FileUtils.newFileOrNull(
                aInfo.credentialProtectedDataDir);

        mSplitNames = aInfo.splitNames;
        mSplitAppDirs = aInfo.splitSourceDirs;
        mSplitResDirs = aInfo.uid == myUid ? aInfo.splitSourceDirs : aInfo.splitPublicSourceDirs;
        mSplitClassLoaderNames = aInfo.splitClassLoaderNames;

        if (aInfo.requestsIsolatedSplitLoading() && !ArrayUtils.isEmpty(mSplitNames)) {
            mSplitLoader = new SplitDependencyLoaderImpl(aInfo.splitDependencies);
        }
    }

    void setSdkSandboxStorage(@Nullable String sdkSandboxClientAppVolumeUuid,
            String sdkSandboxClientAppPackage) {
        int userId = UserHandle.myUserId();
        mDeviceProtectedDataDirFile = Environment
                .getDataMiscDeSharedSdkSandboxDirectory(sdkSandboxClientAppVolumeUuid, userId,
                        sdkSandboxClientAppPackage)
                .getAbsoluteFile();
        mCredentialProtectedDataDirFile = Environment
                .getDataMiscCeSharedSdkSandboxDirectory(sdkSandboxClientAppVolumeUuid, userId,
                        sdkSandboxClientAppPackage)
                .getAbsoluteFile();

        if ((mApplicationInfo.privateFlags
                & ApplicationInfo.PRIVATE_FLAG_DEFAULT_TO_DEVICE_PROTECTED_STORAGE) != 0
                && PackageManager.APPLY_DEFAULT_TO_DEVICE_PROTECTED_STORAGE) {
            mDataDirFile = mDeviceProtectedDataDirFile;
        } else {
            mDataDirFile = mCredentialProtectedDataDirFile;
        }
        mDataDir = mDataDirFile.getAbsolutePath();
    }

    public static void makePaths(ActivityThread activityThread,
                                 ApplicationInfo aInfo,
                                 List<String> outZipPaths) {
        makePaths(activityThread, false, aInfo, outZipPaths, null);
    }

    private static void appendSharedLibrariesLibPathsIfNeeded(
            List<SharedLibraryInfo> sharedLibraries, ApplicationInfo aInfo,
            Set<String> outSeenPaths,
            List<String> outLibPaths) {
        if (sharedLibraries == null) {
            return;
        }
        for (SharedLibraryInfo lib : sharedLibraries) {
            if (lib.isNative()) {
                // Native shared lib doesn't contribute to the native lib search path. Its name is
                // sent to libnativeloader and then the native shared lib is exported from the
                // default linker namespace.
                continue;
            }
            List<String> paths = lib.getAllCodePaths();
            outSeenPaths.addAll(paths);
            for (String path : paths) {
                appendApkLibPathIfNeeded(path, aInfo, outLibPaths);
            }
            appendSharedLibrariesLibPathsIfNeeded(
                    lib.getDependencies(), aInfo, outSeenPaths, outLibPaths);
        }
    }

    public static void makePaths(ActivityThread activityThread,
                                 boolean isBundledApp,
                                 ApplicationInfo aInfo,
                                 List<String> outZipPaths,
                                 List<String> outLibPaths) {
        final String appDir = aInfo.sourceDir;
        final String libDir = aInfo.nativeLibraryDir;

        outZipPaths.clear();
        outZipPaths.add(appDir);

        // Do not load all available splits if the app requested isolated split loading.
        if (aInfo.splitSourceDirs != null && !aInfo.requestsIsolatedSplitLoading()) {
            Collections.addAll(outZipPaths, aInfo.splitSourceDirs);
        }

        if (outLibPaths != null) {
            outLibPaths.clear();
        }

        /*
         * The following is a bit of a hack to inject
         * instrumentation into the system: If the app
         * being started matches one of the instrumentation names,
         * then we combine both the "instrumentation" and
         * "instrumented" app into the path, along with the
         * concatenation of both apps' shared library lists.
         */

        String[] instrumentationLibs = null;
        // activityThread will be null when called from the WebView zygote; just assume
        // no instrumentation applies in this case.
        if (activityThread != null) {
            String instrumentationPackageName = activityThread.mInstrumentationPackageName;
            String instrumentationAppDir = activityThread.mInstrumentationAppDir;
            String[] instrumentationSplitAppDirs = activityThread.mInstrumentationSplitAppDirs;
            String instrumentationLibDir = activityThread.mInstrumentationLibDir;

            String instrumentedAppDir = activityThread.mInstrumentedAppDir;
            String[] instrumentedSplitAppDirs = activityThread.mInstrumentedSplitAppDirs;
            String instrumentedLibDir = activityThread.mInstrumentedLibDir;

            if (appDir.equals(instrumentationAppDir)
                    || appDir.equals(instrumentedAppDir)) {
                outZipPaths.clear();
                outZipPaths.add(instrumentationAppDir);
                if (!instrumentationAppDir.equals(instrumentedAppDir)) {
                    outZipPaths.add(instrumentedAppDir);
                }

                // Only add splits if the app did not request isolated split loading.
                if (!aInfo.requestsIsolatedSplitLoading()) {
                    if (instrumentationSplitAppDirs != null) {
                        Collections.addAll(outZipPaths, instrumentationSplitAppDirs);
                    }

                    if (!instrumentationAppDir.equals(instrumentedAppDir)) {
                        if (instrumentedSplitAppDirs != null) {
                            Collections.addAll(outZipPaths, instrumentedSplitAppDirs);
                        }
                    }
                }

                if (outLibPaths != null) {
                    outLibPaths.add(instrumentationLibDir);
                    if (!instrumentationLibDir.equals(instrumentedLibDir)) {
                        outLibPaths.add(instrumentedLibDir);
                    }
                }

                if (!instrumentedAppDir.equals(instrumentationAppDir)) {
                    instrumentationLibs = getLibrariesFor(instrumentationPackageName);
                }
            }
        }

        if (outLibPaths != null) {
            if (outLibPaths.isEmpty()) {
                outLibPaths.add(libDir);
            }

            // Add path to libraries in apk for current abi. Do this now because more entries
            // will be added to zipPaths that shouldn't be part of the library path.
            if (aInfo.primaryCpuAbi != null) {
                // Add fake libs into the library search path if we target prior to N.
                if (aInfo.targetSdkVersion < Build.VERSION_CODES.N) {
                    outLibPaths.add("/system/fake-libs" +
                        (VMRuntime.is64BitAbi(aInfo.primaryCpuAbi) ? "64" : ""));
                }
                for (String apk : outZipPaths) {
                    outLibPaths.add(apk + "!/lib/" + aInfo.primaryCpuAbi);
                }
            }

            if (isBundledApp) {
                // Add path to system libraries to libPaths;
                // Access to system libs should be limited
                // to bundled applications; this is why updated
                // system apps are not included.
                outLibPaths.add(System.getProperty("java.library.path"));
            }
        }

        // Add the shared libraries native paths. The dex files in shared libraries will
        // be resolved through shared library loaders, which are setup later.
        Set<String> outSeenPaths = new LinkedHashSet<>();
        appendSharedLibrariesLibPathsIfNeeded(
                aInfo.sharedLibraryInfos, aInfo, outSeenPaths, outLibPaths);

        // ApplicationInfo.sharedLibraryFiles is a public API, so anyone can change it.
        // We prepend shared libraries that the package manager hasn't seen, maintaining their
        // original order where possible.
        if (aInfo.sharedLibraryFiles != null) {
            int index = 0;
            for (String lib : aInfo.sharedLibraryFiles) {
                // sharedLibraryFiles might contain native shared libraries that are not APK paths.
                if (!lib.endsWith(".apk")) {
                    continue;
                }
                if (!outSeenPaths.contains(lib) && !outZipPaths.contains(lib)) {
                    outZipPaths.add(index, lib);
                    index++;
                    appendApkLibPathIfNeeded(lib, aInfo, outLibPaths);
                }
            }
        }

        if (instrumentationLibs != null) {
            for (String lib : instrumentationLibs) {
                if (!outZipPaths.contains(lib)) {
                    outZipPaths.add(0, lib);
                    appendApkLibPathIfNeeded(lib, aInfo, outLibPaths);
                }
            }
        }
    }

    /**
     * This method appends a path to the appropriate native library folder of a
     * library if this library is hosted in an APK. This allows support for native
     * shared libraries. The library API is determined based on the application
     * ABI.
     *
     * @param path Path to the library.
     * @param applicationInfo The application depending on the library.
     * @param outLibPaths List to which to add the native lib path if needed.
     */
    private static void appendApkLibPathIfNeeded(@NonNull String path,
            @NonNull ApplicationInfo applicationInfo, @Nullable List<String> outLibPaths) {
        // Looking at the suffix is a little hacky but a safe and simple solution.
        // We will be revisiting code in the next release and clean this up.
        if (outLibPaths != null && applicationInfo.primaryCpuAbi != null && path.endsWith(".apk")) {
            if (applicationInfo.targetSdkVersion >= Build.VERSION_CODES.O) {
                outLibPaths.add(path + "!/lib/" + applicationInfo.primaryCpuAbi);
            }
        }
    }

    /*
     * All indices received by the super class should be shifted by 1 when accessing mSplitNames,
     * etc. The super class assumes the base APK is index 0, while the PackageManager APIs don't
     * include the base APK in the list of splits.
     */
    private class SplitDependencyLoaderImpl extends SplitDependencyLoader<NameNotFoundException> {
        @GuardedBy("mLock")
        private final String[][] mCachedResourcePaths;
        @GuardedBy("mLock")
        private final ClassLoader[] mCachedClassLoaders;

        SplitDependencyLoaderImpl(@NonNull SparseArray<int[]> dependencies) {
            super(dependencies);
            mCachedResourcePaths = new String[mSplitNames.length + 1][];
            mCachedClassLoaders = new ClassLoader[mSplitNames.length + 1];
        }

        @Override
        protected boolean isSplitCached(int splitIdx) {
            synchronized (mLock) {
                return mCachedClassLoaders[splitIdx] != null;
            }
        }

        @Override
        protected void constructSplit(int splitIdx, @NonNull int[] configSplitIndices,
                int parentSplitIdx) throws NameNotFoundException {
            synchronized (mLock) {
                final ArrayList<String> splitPaths = new ArrayList<>();
                if (splitIdx == 0) {
                    createOrUpdateClassLoaderLocked(null);
                    mCachedClassLoaders[0] = mClassLoader;

                    // Never add the base resources here, they always get added no matter what.
                    for (int configSplitIdx : configSplitIndices) {
                        splitPaths.add(mSplitResDirs[configSplitIdx - 1]);
                    }
                    mCachedResourcePaths[0] = splitPaths.toArray(new String[splitPaths.size()]);
                    return;
                }

                // Since we handled the special base case above, parentSplitIdx is always valid.
                final ClassLoader parent = mCachedClassLoaders[parentSplitIdx];
                mCachedClassLoaders[splitIdx] = ApplicationLoaders.getDefault().getClassLoader(
                        mSplitAppDirs[splitIdx - 1], getTargetSdkVersion(), false, null,
                        null, parent, mSplitClassLoaderNames[splitIdx - 1]);

                Collections.addAll(splitPaths, mCachedResourcePaths[parentSplitIdx]);
                splitPaths.add(mSplitResDirs[splitIdx - 1]);
                for (int configSplitIdx : configSplitIndices) {
                    splitPaths.add(mSplitResDirs[configSplitIdx - 1]);
                }
                mCachedResourcePaths[splitIdx] = splitPaths.toArray(new String[splitPaths.size()]);
            }
        }

        private int ensureSplitLoaded(String splitName) throws NameNotFoundException {
            int idx = 0;
            if (splitName != null) {
                idx = Arrays.binarySearch(mSplitNames, splitName);
                if (idx < 0) {
                    throw new PackageManager.NameNotFoundException(
                            "Split name '" + splitName + "' is not installed");
                }
                idx += 1;
            }
            loadDependenciesForSplit(idx);
            return idx;
        }

        ClassLoader getClassLoaderForSplit(String splitName) throws NameNotFoundException {
            final int idx = ensureSplitLoaded(splitName);
            synchronized (mLock) {
                return mCachedClassLoaders[idx];
            }
        }

        String[] getSplitPathsForSplit(String splitName) throws NameNotFoundException {
            final int idx = ensureSplitLoaded(splitName);
            synchronized (mLock) {
                return mCachedResourcePaths[idx];
            }
        }
    }

    private SplitDependencyLoaderImpl mSplitLoader;

    ClassLoader getSplitClassLoader(String splitName) throws NameNotFoundException {
        if (mSplitLoader == null) {
            return mClassLoader;
        }
        return mSplitLoader.getClassLoaderForSplit(splitName);
    }

    String[] getSplitPaths(String splitName) throws NameNotFoundException {
        if (mSplitLoader == null) {
            return mSplitResDirs;
        }
        return mSplitLoader.getSplitPathsForSplit(splitName);
    }

    /**
     * Create a class loader for the {@code sharedLibrary}. Shared libraries are canonicalized,
     * so if we already created a class loader with that shared library, we return it.
     *
     * Implementation notes: the canonicalization of shared libraries is something dex2oat
     * also does.
     */
    ClassLoader createSharedLibraryLoader(SharedLibraryInfo sharedLibrary,
            boolean isBundledApp, String librarySearchPath, String libraryPermittedPath) {
        List<String> paths = sharedLibrary.getAllCodePaths();
        Pair<List<ClassLoader>, List<ClassLoader>> sharedLibraries = createSharedLibrariesLoaders(
                sharedLibrary.getDependencies(), isBundledApp, librarySearchPath,
                libraryPermittedPath);
        final String jars = (paths.size() == 1) ? paths.get(0) :
                TextUtils.join(File.pathSeparator, paths);

        // Shared libraries get a null parent: this has the side effect of having canonicalized
        // shared libraries using ApplicationLoaders cache, which is the behavior we want.
        return ApplicationLoaders.getDefault().getSharedLibraryClassLoaderWithSharedLibraries(jars,
                    mApplicationInfo.targetSdkVersion, isBundledApp, librarySearchPath,
                    libraryPermittedPath, /* parent */ null,
                    /* classLoaderName */ null, sharedLibraries.first, sharedLibraries.second);
    }

    /**
     *
     * @return a {@link Pair} of List<ClassLoader> where the first is for standard shared libraries
     *         and the second is list for shared libraries that code should be loaded after the dex
     */
    private Pair<List<ClassLoader>, List<ClassLoader>> createSharedLibrariesLoaders(
            List<SharedLibraryInfo> sharedLibraries,
            boolean isBundledApp, String librarySearchPath, String libraryPermittedPath) {
        if (sharedLibraries == null || sharedLibraries.isEmpty()) {
            return new Pair<>(null, null);
        }

        // if configured to do so, shared libs are split into 2 collections: those that are
        // on the class path before the applications code, which is standard, and those
        // specified to be loaded after the applications code.
        HashSet<String> libsToLoadAfter = new HashSet<>();
        Resources systemR = Resources.getSystem();
        Collections.addAll(libsToLoadAfter, systemR.getStringArray(
                R.array.config_sharedLibrariesLoadedAfterApp));

        List<ClassLoader> loaders = new ArrayList<>();
        List<ClassLoader> after = new ArrayList<>();
        for (SharedLibraryInfo info : sharedLibraries) {
            if (info.isNative()) {
                // Native shared lib doesn't contribute to the native lib search path. Its name is
                // sent to libnativeloader and then the native shared lib is exported from the
                // default linker namespace.
                continue;
            }
            if (info.isSdk()) {
                // SDKs are not loaded automatically.
                continue;
            }
            if (libsToLoadAfter.contains(info.getName())) {
                if (DEBUG) {
                    Slog.v(ActivityThread.TAG,
                            info.getName() + " will be loaded after application code");
                }
                after.add(createSharedLibraryLoader(
                        info, isBundledApp, librarySearchPath, libraryPermittedPath));
            } else {
                loaders.add(createSharedLibraryLoader(
                        info, isBundledApp, librarySearchPath, libraryPermittedPath));
            }
        }
        return new Pair<>(loaders, after);
    }

    private StrictMode.ThreadPolicy allowThreadDiskReads() {
        if (mActivityThread == null) {
            // When LoadedApk is used without an ActivityThread (usually in a
            // zygote context), don't call into StrictMode, as it initializes
            // the binder subsystem, which we don't want.
            return null;
        }

        return StrictMode.allowThreadDiskReads();
    }

    private void setThreadPolicy(StrictMode.ThreadPolicy policy) {
        if (mActivityThread != null && policy != null) {
            StrictMode.setThreadPolicy(policy);
        }
    }

    private StrictMode.VmPolicy allowVmViolations() {
        if (mActivityThread == null) {
            // When LoadedApk is used without an ActivityThread (usually in a
            // zygote context), don't call into StrictMode, as it initializes
            // the binder subsystem, which we don't want.
            return null;
        }

        return StrictMode.allowVmViolations();
    }

    private void setVmPolicy(StrictMode.VmPolicy policy) {
        if (mActivityThread != null && policy != null) {
            StrictMode.setVmPolicy(policy);
        }
    }

    @GuardedBy("mLock")
    private void createOrUpdateClassLoaderLocked(List<String> addedPaths) {
        if (mPackageName.equals("android")) {
            // Note: This branch is taken for system server and we don't need to setup
            // jit profiling support.
            if (mClassLoader != null) {
                // nothing to update
                return;
            }

            if (mBaseClassLoader != null) {
                mDefaultClassLoader = mBaseClassLoader;
            } else {
                mDefaultClassLoader = ClassLoader.getSystemClassLoader();
            }
            mAppComponentFactory = createAppFactory(mApplicationInfo, mDefaultClassLoader);
            mClassLoader = mAppComponentFactory.instantiateClassLoader(mDefaultClassLoader,
                    new ApplicationInfo(mApplicationInfo));
            return;
        }

        // Avoid the binder call when the package is the current application package.
        // The activity manager will perform ensure that dexopt is performed before
        // spinning up the process. Similarly, don't call into binder when we don't
        // have an ActivityThread object.
        if (mActivityThread != null
                && !Objects.equals(mPackageName, ActivityThread.currentPackageName())
                && mIncludeCode) {
            try {
                ActivityThread.getPackageManager().notifyPackageUse(mPackageName,
                        PackageManager.NOTIFY_PACKAGE_USE_CROSS_PACKAGE);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }

        if (mRegisterPackage) {
            try {
                ActivityManager.getService().addPackageDependency(mPackageName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        // Lists for the elements of zip/code and native libraries.
        //
        // Both lists are usually not empty. We expect on average one APK for the zip component,
        // but shared libraries and splits are not uncommon. We expect at least three elements
        // for native libraries (app-based, system, vendor). As such, give both some breathing
        // space and initialize to a small value (instead of incurring growth code).
        final List<String> zipPaths = new ArrayList<>(10);
        final List<String> libPaths = new ArrayList<>(10);

        boolean isBundledApp = mApplicationInfo.isSystemApp()
                && !mApplicationInfo.isUpdatedSystemApp();

        // Vendor apks are treated as bundled only when /vendor/lib is in the default search
        // paths. If not, they are treated as unbundled; access to system libs is limited.
        // Having /vendor/lib in the default search paths means that all system processes
        // are allowed to use any vendor library, which in turn means that system is dependent
        // on vendor partition. In the contrary, not having /vendor/lib in the default search
        // paths mean that the two partitions are separated and thus we can treat vendor apks
        // as unbundled.
        final String defaultSearchPaths = System.getProperty("java.library.path");
        final boolean treatVendorApkAsUnbundled = !defaultSearchPaths.contains("/vendor/lib");
        if (mApplicationInfo.getCodePath() != null
                && mApplicationInfo.isVendor() && treatVendorApkAsUnbundled) {
            isBundledApp = false;
        }

        // Similar to vendor apks, we should add /product/lib for apks from product partition
        // when product apps are marked as unbundled. We cannot use the same way from vendor
        // to check if lib path exists because there is possibility that /product/lib would not
        // exist from legacy device while product apks are bundled. To make this clear, we use
        // "ro.product.vndk.version" property. If the property is defined, we regard all product
        // apks as unbundled.
        if (mApplicationInfo.getCodePath() != null
                && mApplicationInfo.isProduct()
                && VndkProperties.product_vndk_version().isPresent()) {
            isBundledApp = false;
        }

        makePaths(mActivityThread, isBundledApp, mApplicationInfo, zipPaths, libPaths);

        // Including an inaccessible dir in libraryPermittedPath would cause SELinux denials
        // when the loader attempts to canonicalise the path. so we don't.
        String libraryPermittedPath = canAccessDataDir() ? mDataDir : "";

        if (isBundledApp) {
            // For bundled apps, add the base directory of the app (e.g.,
            // /system/app/Foo/) to the permitted paths so that it can load libraries
            // embedded in module apks under the directory. For now, GmsCore is relying
            // on this, but this isn't specific to the app. Also note that, we don't
            // need to do this for unbundled apps as entire /data is already set to
            // the permitted paths for them.
            libraryPermittedPath += File.pathSeparator
                    + Paths.get(getAppDir()).getParent().toString();

            // This is necessary to grant bundled apps access to
            // libraries located in subdirectories of /system/lib
            libraryPermittedPath += File.pathSeparator + defaultSearchPaths;
        }

        final String librarySearchPath = TextUtils.join(File.pathSeparator, libPaths);

        if (mActivityThread != null) {
            final String gpuDebugApp = mActivityThread.getStringCoreSetting(
                    Settings.Global.GPU_DEBUG_APP, "");
            if (!gpuDebugApp.isEmpty() && mPackageName.equals(gpuDebugApp)) {

                // The current application is used to debug, attempt to get the debug layers.
                try {
                    // Get the ApplicationInfo from PackageManager so that metadata fields present.
                    final ApplicationInfo ai = ActivityThread.getPackageManager()
                            .getApplicationInfo(mPackageName, PackageManager.GET_META_DATA,
                                    UserHandle.myUserId());
                    final String debugLayerPath = GraphicsEnvironment.getInstance()
                            .getDebugLayerPathsFromSettings(mActivityThread.getCoreSettings(),
                                    ActivityThread.getPackageManager(), mPackageName, ai);
                    if (debugLayerPath != null) {
                        libraryPermittedPath += File.pathSeparator + debugLayerPath;
                    }
                } catch (RemoteException e) {
                    // Unlikely to fail for applications, but in case of failure, something is wrong
                    // inside the system server, hence just skip.
                    Slog.e(ActivityThread.TAG,
                            "RemoteException when fetching debug layer paths for: " + mPackageName);
                }
            }
        }

        // If we're not asked to include code, we construct a classloader that has
        // no code path included. We still need to set up the library search paths
        // and permitted path because NativeActivity relies on it (it attempts to
        // call System.loadLibrary() on a classloader from a LoadedApk with
        // mIncludeCode == false).
        if (!mIncludeCode) {
            if (mDefaultClassLoader == null) {
                StrictMode.ThreadPolicy oldPolicy = allowThreadDiskReads();
                mDefaultClassLoader = ApplicationLoaders.getDefault().getClassLoader(
                        "" /* codePath */, mApplicationInfo.targetSdkVersion, isBundledApp,
                        librarySearchPath, libraryPermittedPath, mBaseClassLoader,
                        null /* classLoaderName */);
                setThreadPolicy(oldPolicy);
                mAppComponentFactory = AppComponentFactory.DEFAULT;
            }

            if (mClassLoader == null) {
                mClassLoader = mAppComponentFactory.instantiateClassLoader(mDefaultClassLoader,
                        new ApplicationInfo(mApplicationInfo));
            }

            return;
        }

        /*
         * With all the combination done (if necessary, actually create the java class
         * loader and set up JIT profiling support if necessary.
         *
         * In many cases this is a single APK, so try to avoid the StringBuilder in TextUtils.
         */
        final String zip = (zipPaths.size() == 1) ? zipPaths.get(0) :
                TextUtils.join(File.pathSeparator, zipPaths);

        if (DEBUG) Slog.v(ActivityThread.TAG, "Class path: " + zip +
                    ", JNI path: " + librarySearchPath);

        boolean registerAppInfoToArt = false;
        if (mDefaultClassLoader == null) {
            // Setup the dex reporter to notify package manager
            // of any relevant dex loads. The idle maintenance job will use the information
            // reported to optimize the loaded dex files.
            // Note that we only need one global reporter per app.
            // Make sure we do this before creating the main app classloader for the first time
            // so that we can capture the complete application startup.
            //
            // We should not do this in a zygote context (where mActivityThread will be null),
            // thus we'll guard against it.
            // Also, the system server reporter (SystemServerDexLoadReporter) is already registered
            // when system server starts, so we don't need to do it here again.
            if (mActivityThread != null && !ActivityThread.isSystem()) {
                BaseDexClassLoader.setReporter(DexLoadReporter.getInstance());
            }

            // Temporarily disable logging of disk reads on the Looper thread
            // as this is early and necessary.
            StrictMode.ThreadPolicy oldPolicy = allowThreadDiskReads();

            Pair<List<ClassLoader>, List<ClassLoader>> sharedLibraries =
                    createSharedLibrariesLoaders(mApplicationInfo.sharedLibraryInfos, isBundledApp,
                            librarySearchPath, libraryPermittedPath);

            List<String> nativeSharedLibraries = new ArrayList<>();
            if (mApplicationInfo.sharedLibraryInfos != null) {
                for (SharedLibraryInfo info : mApplicationInfo.sharedLibraryInfos) {
                    if (info.isNative()) {
                        nativeSharedLibraries.add(info.getName());
                    }
                }
            }

            mDefaultClassLoader = ApplicationLoaders.getDefault().getClassLoaderWithSharedLibraries(
                    zip, mApplicationInfo.targetSdkVersion, isBundledApp, librarySearchPath,
                    libraryPermittedPath, mBaseClassLoader,
                    mApplicationInfo.classLoaderName, sharedLibraries.first, nativeSharedLibraries,
                    sharedLibraries.second);
            mAppComponentFactory = createAppFactory(mApplicationInfo, mDefaultClassLoader);

            setThreadPolicy(oldPolicy);
            // Setup the class loader paths for profiling.
            registerAppInfoToArt = true;
        }

        if (!libPaths.isEmpty()) {
            // Temporarily disable logging of disk reads on the Looper thread as this is necessary
            StrictMode.ThreadPolicy oldPolicy = allowThreadDiskReads();
            try {
                ApplicationLoaders.getDefault().addNative(mDefaultClassLoader, libPaths);
            } finally {
                setThreadPolicy(oldPolicy);
            }
        }

        if (addedPaths != null && addedPaths.size() > 0) {
            final String add = TextUtils.join(File.pathSeparator, addedPaths);
            ApplicationLoaders.getDefault().addPath(mDefaultClassLoader, add);
            // Setup the new code paths for profiling.
            registerAppInfoToArt = true;
        }

        // Setup jit profile support.
        //
        // It is ok to call this multiple times if the application gets updated with new splits.
        // The runtime only keeps track of unique code paths and can handle re-registration of
        // the same code path. There's no need to pass `addedPaths` since any new code paths
        // are already in `mApplicationInfo`.
        //
        // It is NOT ok to call this function from the system_server (for any of the packages it
        // loads code from) so we explicitly disallow it there.
        //
        // It is not ok to call this in a zygote context where mActivityThread is null.
        if (registerAppInfoToArt && !ActivityThread.isSystem() && mActivityThread != null) {
            registerAppInfoToArt();
        }

        // Call AppComponentFactory to select/create the main class loader of this app.
        // Since this may call code in the app, mDefaultClassLoader must be fully set up
        // before invoking the factory.
        // Invoke with a copy of ApplicationInfo to protect against the app changing it.
        if (mClassLoader == null) {
            mClassLoader = mAppComponentFactory.instantiateClassLoader(mDefaultClassLoader,
                    new ApplicationInfo(mApplicationInfo));
        }
    }

    /**
     * Return whether we can access the package's private data directory in order to be able to
     * load code from it.
     */
    private boolean canAccessDataDir() {
        // In a zygote context where mActivityThread is null we can't access the app data dir.
        if (mActivityThread == null) {
            return false;
        }

        // A package can access its own data directory (the common case, so short-circuit it).
        if (Objects.equals(mPackageName, ActivityThread.currentPackageName())) {
            return true;
        }

        // Temporarily disable logging of disk reads on the Looper thread as this is necessary -
        // and the loader will access the directory anyway if we don't check it.
        StrictMode.ThreadPolicy oldThreadPolicy = allowThreadDiskReads();

        // Also disable logging of access to /data/user before CE storage is unlocked. The check
        // below will return false (because the directory name we pass will not match the
        // encrypted one), but that's correct.
        StrictMode.VmPolicy oldVmPolicy = allowVmViolations();

        try {
            // We are constructing a classloader for a different package. It is likely,
            // but not certain, that we can't acccess its app data dir - so check.
            return new File(mDataDir).canExecute();
        } finally {
            setThreadPolicy(oldThreadPolicy);
            setVmPolicy(oldVmPolicy);
        }
    }

    @UnsupportedAppUsage
    public ClassLoader getClassLoader() {
        synchronized (mLock) {
            if (mClassLoader == null) {
                createOrUpdateClassLoaderLocked(null /*addedPaths*/);
            }
            return mClassLoader;
        }
    }

    private void registerAppInfoToArt() {
        // Only set up profile support if the loaded apk has the same uid as the
        // current process.
        // Currently, we do not support profiling across different apps.
        // (e.g. application's uid might be different when the code is
        // loaded by another app via createApplicationContext)
        if (mApplicationInfo.uid != Process.myUid()) {
            return;
        }

        final List<String> codePaths = new ArrayList<>();
        if ((mApplicationInfo.flags & ApplicationInfo.FLAG_HAS_CODE) != 0) {
            codePaths.add(mApplicationInfo.sourceDir);
        }
        if (mApplicationInfo.splitSourceDirs != null) {
            Collections.addAll(codePaths, mApplicationInfo.splitSourceDirs);
        }

        if (codePaths.isEmpty()) {
            // If there are no code paths there's no need to setup a profile file and register with
            // the runtime,
            return;
        }

        for (int i = codePaths.size() - 1; i >= 0; i--) {
            String splitName = i == 0 ? null : mApplicationInfo.splitNames[i - 1];
            String curProfileFile = ArtManager.getCurrentProfilePath(
                    mPackageName, UserHandle.myUserId(), splitName);
            String refProfileFile = ArtManager.getReferenceProfilePath(
                    mPackageName, UserHandle.myUserId(), splitName);
            int codePathType = codePaths.get(i).equals(mApplicationInfo.sourceDir)
                    ? VMRuntime.CODE_PATH_TYPE_PRIMARY_APK
                    : VMRuntime.CODE_PATH_TYPE_SPLIT_APK;
            VMRuntime.registerAppInfo(
                    mPackageName,
                    curProfileFile,
                    refProfileFile,
                    new String[] {codePaths.get(i)},
                    codePathType);
        }

        // Register the app data directory with the reporter. It will
        // help deciding whether or not a dex file is the primary apk or a
        // secondary dex.
        DexLoadReporter.getInstance().registerAppDataDir(mPackageName, mDataDir);
    }

    /**
     * Setup value for Thread.getContextClassLoader(). If the
     * package will not run in in a VM with other packages, we set
     * the Java context ClassLoader to the
     * PackageInfo.getClassLoader value. However, if this VM can
     * contain multiple packages, we intead set the Java context
     * ClassLoader to a proxy that will warn about the use of Java
     * context ClassLoaders and then fall through to use the
     * system ClassLoader.
     *
     * <p> Note that this is similar to but not the same as the
     * android.content.Context.getClassLoader(). While both
     * context class loaders are typically set to the
     * PathClassLoader used to load the package archive in the
     * single application per VM case, a single Android process
     * may contain several Contexts executing on one thread with
     * their own logical ClassLoaders while the Java context
     * ClassLoader is a thread local. This is why in the case when
     * we have multiple packages per VM we do not set the Java
     * context ClassLoader to an arbitrary but instead warn the
     * user to set their own if we detect that they are using a
     * Java library that expects it to be set.
     */
    private void initializeJavaContextClassLoader() {
        IPackageManager pm = ActivityThread.getPackageManager();
        android.content.pm.PackageInfo pi =
                PackageManager.getPackageInfoAsUserCached(
                        mPackageName,
                        PackageManager.MATCH_DEBUG_TRIAGED_MISSING,
                        UserHandle.myUserId());
        if (pi == null) {
            throw new IllegalStateException("Unable to get package info for "
                    + mPackageName + "; is package not installed?");
        }
        /*
         * Two possible indications that this package could be
         * sharing its virtual machine with other packages:
         *
         * 1.) the sharedUserId attribute is set in the manifest,
         *     indicating a request to share a VM with other
         *     packages with the same sharedUserId.
         *
         * 2.) the application element of the manifest has an
         *     attribute specifying a non-default process name,
         *     indicating the desire to run in another packages VM.
         */
        boolean sharedUserIdSet = (pi.sharedUserId != null);
        boolean processNameNotDefault =
            (pi.applicationInfo != null &&
             !mPackageName.equals(pi.applicationInfo.processName));
        boolean sharable = (sharedUserIdSet || processNameNotDefault);
        ClassLoader contextClassLoader =
            (sharable)
            ? new WarningContextClassLoader()
            : mClassLoader;
        Thread.currentThread().setContextClassLoader(contextClassLoader);
    }

    private static class WarningContextClassLoader extends ClassLoader {

        private static boolean warned = false;

        private void warn(String methodName) {
            if (warned) {
                return;
            }
            warned = true;
            Thread.currentThread().setContextClassLoader(getParent());
            Slog.w(ActivityThread.TAG, "ClassLoader." + methodName + ": " +
                  "The class loader returned by " +
                  "Thread.getContextClassLoader() may fail for processes " +
                  "that host multiple applications. You should explicitly " +
                  "specify a context class loader. For example: " +
                  "Thread.setContextClassLoader(getClass().getClassLoader());");
        }

        @Override public URL getResource(String resName) {
            warn("getResource");
            return getParent().getResource(resName);
        }

        @Override public Enumeration<URL> getResources(String resName) throws IOException {
            warn("getResources");
            return getParent().getResources(resName);
        }

        @Override public InputStream getResourceAsStream(String resName) {
            warn("getResourceAsStream");
            return getParent().getResourceAsStream(resName);
        }

        @Override public Class<?> loadClass(String className) throws ClassNotFoundException {
            warn("loadClass");
            return getParent().loadClass(className);
        }

        @Override public void setClassAssertionStatus(String cname, boolean enable) {
            warn("setClassAssertionStatus");
            getParent().setClassAssertionStatus(cname, enable);
        }

        @Override public void setPackageAssertionStatus(String pname, boolean enable) {
            warn("setPackageAssertionStatus");
            getParent().setPackageAssertionStatus(pname, enable);
        }

        @Override public void setDefaultAssertionStatus(boolean enable) {
            warn("setDefaultAssertionStatus");
            getParent().setDefaultAssertionStatus(enable);
        }

        @Override public void clearAssertionStatus() {
            warn("clearAssertionStatus");
            getParent().clearAssertionStatus();
        }
    }

    @UnsupportedAppUsage
    public String getAppDir() {
        return mAppDir;
    }

    public String getLibDir() {
        return mLibDir;
    }

    @UnsupportedAppUsage
    public String getResDir() {
        return mResDir;
    }

    public String[] getSplitAppDirs() {
        return mSplitAppDirs;
    }

    @UnsupportedAppUsage
    public String[] getSplitResDirs() {
        return mSplitResDirs;
    }

    /**
     * Corresponds to {@link ApplicationInfo#resourceDirs}.
     */
    @UnsupportedAppUsage
    public String[] getOverlayDirs() {
        return mLegacyOverlayDirs;
    }

    /**
     * Corresponds to {@link ApplicationInfo#overlayPaths}.
     */
    public String[] getOverlayPaths() {
        return mOverlayPaths;
    }

    public String getDataDir() {
        return mDataDir;
    }

    @UnsupportedAppUsage
    public File getDataDirFile() {
        return mDataDirFile;
    }

    public File getDeviceProtectedDataDirFile() {
        return mDeviceProtectedDataDirFile;
    }

    public File getCredentialProtectedDataDirFile() {
        return mCredentialProtectedDataDirFile;
    }

    @UnsupportedAppUsage
    public AssetManager getAssets() {
        return getResources().getAssets();
    }

    @UnsupportedAppUsage
    public Resources getResources() {
        if (mResources == null) {
            final String[] splitPaths;
            try {
                splitPaths = getSplitPaths(null);
            } catch (NameNotFoundException e) {
                // This should never fail.
                throw new AssertionError("null split not found");
            }

            if (Process.myUid() == mApplicationInfo.uid) {
                ResourcesManager.getInstance().initializeApplicationPaths(mResDir, splitPaths);
            }

            mResources = ResourcesManager.getInstance().getResources(null, mResDir,
                    splitPaths, mLegacyOverlayDirs, mOverlayPaths,
                    mApplicationInfo.sharedLibraryFiles, null, null, getCompatibilityInfo(),
                    getClassLoader(), null);
        }
        return mResources;
    }

    /**
     * This is for 3p apps accessing this hidden API directly... in which case, we don't return
     * the cached Application instance.
     */
    @UnsupportedAppUsage
    public Application makeApplication(boolean forceDefaultAppClass,
            Instrumentation instrumentation) {
        return makeApplicationInner(forceDefaultAppClass, instrumentation,
                /* allowDuplicateInstances= */ true);
    }

    /**
     * This is for all the (internal) callers, for which we do return the cached instance.
     */
    public Application makeApplicationInner(boolean forceDefaultAppClass,
            Instrumentation instrumentation) {
        return makeApplicationInner(forceDefaultAppClass, instrumentation,
                /* allowDuplicateInstances= */ false);
    }

    private Application makeApplicationInner(boolean forceDefaultAppClass,
            Instrumentation instrumentation, boolean allowDuplicateInstances) {
        if (mApplication != null) {
            return mApplication;
        }
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "makeApplication");

        synchronized (sApplications) {
            final Application cached = sApplications.get(mPackageName);
            if (cached != null) {
                // Looks like this is always happening for the system server, because
                // the LoadedApk created in systemMain() -> attach() isn't cached properly?
                if (!"android".equals(mPackageName)) {
                    Slog.wtfStack(TAG, "App instance already created for package=" + mPackageName
                            + " instance=" + cached);
                }
                if (!allowDuplicateInstances) {
                    mApplication = cached;
                    return cached;
                }
                // Some apps intentionally call makeApplication() to create a new Application
                // instance... Sigh...
            }
        }

        Application app = null;

        final String myProcessName = Process.myProcessName();
        String appClass = mApplicationInfo.getCustomApplicationClassNameForProcess(
                myProcessName);
        if (forceDefaultAppClass || (appClass == null)) {
            appClass = "android.app.Application";
        }

        try {
            final java.lang.ClassLoader cl = getClassLoader();
            if (!ActivityThread.isSystem()) {
                Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                        "initializeJavaContextClassLoader");
                initializeJavaContextClassLoader();
                Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
            }

            // Rewrite the R 'constants' for all library apks.
            SparseArray<String> packageIdentifiers = getAssets().getAssignedPackageIdentifiers(
                    false, false);
            for (int i = 0, n = packageIdentifiers.size(); i < n; i++) {
                final int id = packageIdentifiers.keyAt(i);
                if (id == 0x01 || id == 0x7f) {
                    continue;
                }

                rewriteRValues(cl, packageIdentifiers.valueAt(i), id);
            }

            ContextImpl appContext = ContextImpl.createAppContext(mActivityThread, this);
            // The network security config needs to be aware of multiple
            // applications in the same process to handle discrepancies
            NetworkSecurityConfigProvider.handleNewApplication(appContext);
            app = mActivityThread.mInstrumentation.newApplication(
                    cl, appClass, appContext);
            appContext.setOuterContext(app);
        } catch (Exception e) {
            if (!mActivityThread.mInstrumentation.onException(app, e)) {
                Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                throw new RuntimeException(
                    "Unable to instantiate application " + appClass
                    + " package " + mPackageName + ": " + e.toString(), e);
            }
        }
        mActivityThread.mAllApplications.add(app);
        mApplication = app;
        if (!allowDuplicateInstances) {
            synchronized (sApplications) {
                sApplications.put(mPackageName, app);
            }
        }

        if (instrumentation != null) {
            try {
                instrumentation.callApplicationOnCreate(app);
            } catch (Exception e) {
                if (!instrumentation.onException(app, e)) {
                    Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                    throw new RuntimeException(
                        "Unable to create application " + app.getClass().getName()
                        + ": " + e.toString(), e);
                }
            }
        }

        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);

        return app;
    }

    @UnsupportedAppUsage
    private void rewriteRValues(ClassLoader cl, String packageName, int id) {
        final Class<?> rClazz;
        try {
            rClazz = cl.loadClass(packageName + ".R");
        } catch (ClassNotFoundException e) {
            // This is not necessarily an error, as some packages do not ship with resources
            // (or they do not need rewriting).
            Log.i(TAG, "No resource references to update in package " + packageName);
            return;
        }

        final Method callback;
        try {
            callback = rClazz.getMethod("onResourcesLoaded", int.class);
        } catch (NoSuchMethodException e) {
            // No rewriting to be done.
            return;
        }

        Throwable cause;
        try {
            callback.invoke(null, id);
            return;
        } catch (IllegalAccessException e) {
            cause = e;
        } catch (InvocationTargetException e) {
            cause = e.getCause();
        }

        throw new RuntimeException("Failed to rewrite resource references for " + packageName,
                cause);
    }

    public void removeContextRegistrations(Context context,
            String who, String what) {
        final boolean reportRegistrationLeaks = StrictMode.vmRegistrationLeaksEnabled();
        synchronized (mReceivers) {
            ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> rmap =
                    mReceivers.remove(context);
            if (rmap != null) {
                for (int i = 0; i < rmap.size(); i++) {
                    LoadedApk.ReceiverDispatcher rd = rmap.valueAt(i);
                    IntentReceiverLeaked leak = new IntentReceiverLeaked(
                            what + " " + who + " has leaked IntentReceiver "
                            + rd.getIntentReceiver() + " that was " +
                            "originally registered here. Are you missing a " +
                            "call to unregisterReceiver()?");
                    leak.setStackTrace(rd.getLocation().getStackTrace());
                    Slog.e(ActivityThread.TAG, leak.getMessage(), leak);
                    if (reportRegistrationLeaks) {
                        StrictMode.onIntentReceiverLeaked(leak);
                    }
                    try {
                        ActivityManager.getService().unregisterReceiver(
                                rd.getIIntentReceiver());
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            }
            mUnregisteredReceivers.remove(context);
        }

        synchronized (mServices) {
            //Slog.i(TAG, "Receiver registrations: " + mReceivers);
            ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher> smap =
                    mServices.remove(context);
            if (smap != null) {
                for (int i = 0; i < smap.size(); i++) {
                    LoadedApk.ServiceDispatcher sd = smap.valueAt(i);
                    ServiceConnectionLeaked leak = new ServiceConnectionLeaked(
                            what + " " + who + " has leaked ServiceConnection "
                            + sd.getServiceConnection() + " that was originally bound here");
                    leak.setStackTrace(sd.getLocation().getStackTrace());
                    Slog.e(ActivityThread.TAG, leak.getMessage(), leak);
                    if (reportRegistrationLeaks) {
                        StrictMode.onServiceConnectionLeaked(leak);
                    }
                    try {
                        ActivityManager.getService().unbindService(
                                sd.getIServiceConnection());
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                    sd.doForget();
                }
            }
            mUnboundServices.remove(context);
            //Slog.i(TAG, "Service registrations: " + mServices);
        }
    }

    public IIntentReceiver getReceiverDispatcher(BroadcastReceiver r,
            Context context, Handler handler,
            Instrumentation instrumentation, boolean registered) {
        synchronized (mReceivers) {
            LoadedApk.ReceiverDispatcher rd = null;
            ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> map = null;
            if (registered) {
                map = mReceivers.get(context);
                if (map != null) {
                    rd = map.get(r);
                }
            }
            if (rd == null) {
                rd = new ReceiverDispatcher(r, context, handler,
                        instrumentation, registered);
                if (registered) {
                    if (map == null) {
                        map = new ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>();
                        mReceivers.put(context, map);
                    }
                    map.put(r, rd);
                }
            } else {
                rd.validate(context, handler);
            }
            rd.mForgotten = false;
            return rd.getIIntentReceiver();
        }
    }

    public IIntentReceiver forgetReceiverDispatcher(Context context,
            BroadcastReceiver r) {
        synchronized (mReceivers) {
            ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> map = mReceivers.get(context);
            LoadedApk.ReceiverDispatcher rd = null;
            if (map != null) {
                rd = map.get(r);
                if (rd != null) {
                    map.remove(r);
                    if (map.size() == 0) {
                        mReceivers.remove(context);
                    }
                    if (r.getDebugUnregister()) {
                        ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> holder
                                = mUnregisteredReceivers.get(context);
                        if (holder == null) {
                            holder = new ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>();
                            mUnregisteredReceivers.put(context, holder);
                        }
                        RuntimeException ex = new IllegalArgumentException(
                                "Originally unregistered here:");
                        ex.fillInStackTrace();
                        rd.setUnregisterLocation(ex);
                        holder.put(r, rd);
                    }
                    rd.mForgotten = true;
                    return rd.getIIntentReceiver();
                }
            }
            ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> holder
                    = mUnregisteredReceivers.get(context);
            if (holder != null) {
                rd = holder.get(r);
                if (rd != null) {
                    RuntimeException ex = rd.getUnregisterLocation();
                    throw new IllegalArgumentException(
                            "Unregistering Receiver " + r
                            + " that was already unregistered", ex);
                }
            }
            if (context == null) {
                throw new IllegalStateException("Unbinding Receiver " + r
                        + " from Context that is no longer in use: " + context);
            } else {
                throw new IllegalArgumentException("Receiver not registered: " + r);
            }

        }
    }

    static final class ReceiverDispatcher {

        final static class InnerReceiver extends IIntentReceiver.Stub {
            final WeakReference<LoadedApk.ReceiverDispatcher> mDispatcher;
            final LoadedApk.ReceiverDispatcher mStrongRef;

            InnerReceiver(LoadedApk.ReceiverDispatcher rd, boolean strong) {
                mDispatcher = new WeakReference<LoadedApk.ReceiverDispatcher>(rd);
                mStrongRef = strong ? rd : null;
            }

            @Override
            public void performReceive(Intent intent, int resultCode, String data,
                    Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
                final LoadedApk.ReceiverDispatcher rd;
                if (intent == null) {
                    Log.wtf(TAG, "Null intent received");
                    rd = null;
                } else {
                    rd = mDispatcher.get();
                }
                if (ActivityThread.DEBUG_BROADCAST) {
                    int seq = intent.getIntExtra("seq", -1);
                    Slog.i(ActivityThread.TAG, "Receiving broadcast " + intent.getAction()
                            + " seq=" + seq + " to " + (rd != null ? rd.mReceiver : null));
                }
                if (rd != null) {
                    rd.performReceive(intent, resultCode, data, extras,
                            ordered, sticky, sendingUser);
                } else {
                    // The activity manager dispatched a broadcast to a registered
                    // receiver in this process, but before it could be delivered the
                    // receiver was unregistered.  Acknowledge the broadcast on its
                    // behalf so that the system's broadcast sequence can continue.
                    if (ActivityThread.DEBUG_BROADCAST) Slog.i(ActivityThread.TAG,
                            "Finishing broadcast to unregistered receiver");
                    IActivityManager mgr = ActivityManager.getService();
                    try {
                        if (extras != null) {
                            extras.setAllowFds(false);
                        }
                        mgr.finishReceiver(this, resultCode, data, extras, false, intent.getFlags());
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            }
        }

        final IIntentReceiver.Stub mIIntentReceiver;
        @UnsupportedAppUsage
        final BroadcastReceiver mReceiver;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        final Context mContext;
        final Handler mActivityThread;
        final Instrumentation mInstrumentation;
        final boolean mRegistered;
        final IntentReceiverLeaked mLocation;
        RuntimeException mUnregisterLocation;
        boolean mForgotten;

        final class Args extends BroadcastReceiver.PendingResult {
            private Intent mCurIntent;
            private final boolean mOrdered;
            private boolean mDispatched;
            private boolean mRunCalled;

            public Args(Intent intent, int resultCode, String resultData, Bundle resultExtras,
                    boolean ordered, boolean sticky, int sendingUser) {
                super(resultCode, resultData, resultExtras,
                        mRegistered ? TYPE_REGISTERED : TYPE_UNREGISTERED, ordered,
                        sticky, mIIntentReceiver.asBinder(), sendingUser, intent.getFlags());
                mCurIntent = intent;
                mOrdered = ordered;
            }

            public final Runnable getRunnable() {
                return () -> {
                    final BroadcastReceiver receiver = mReceiver;
                    final boolean ordered = mOrdered;

                    if (ActivityThread.DEBUG_BROADCAST) {
                        int seq = mCurIntent.getIntExtra("seq", -1);
                        Slog.i(ActivityThread.TAG, "Dispatching broadcast " + mCurIntent.getAction()
                                + " seq=" + seq + " to " + mReceiver);
                        Slog.i(ActivityThread.TAG, "  mRegistered=" + mRegistered
                                + " mOrderedHint=" + ordered);
                    }

                    final IActivityManager mgr = ActivityManager.getService();
                    final Intent intent = mCurIntent;
                    if (intent == null) {
                        Log.wtf(TAG, "Null intent being dispatched, mDispatched=" + mDispatched
                                + (mRunCalled ? ", run() has already been called" : ""));
                    }

                    mCurIntent = null;
                    mDispatched = true;
                    mRunCalled = true;
                    if (receiver == null || intent == null || mForgotten) {
                        if (mRegistered && ordered) {
                            if (ActivityThread.DEBUG_BROADCAST) Slog.i(ActivityThread.TAG,
                                    "Finishing null broadcast to " + mReceiver);
                            sendFinished(mgr);
                        }
                        return;
                    }

                    if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
                        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                                "broadcastReceiveReg: " + intent.getAction());
                    }

                    try {
                        ClassLoader cl = mReceiver.getClass().getClassLoader();
                        intent.setExtrasClassLoader(cl);
                        // TODO: determine at registration time if caller is
                        // protecting themselves with signature permission
                        intent.prepareToEnterProcess(ActivityThread.isProtectedBroadcast(intent),
                                mContext.getAttributionSource());
                        setExtrasClassLoader(cl);
                        receiver.setPendingResult(this);
                        receiver.onReceive(mContext, intent);
                    } catch (Exception e) {
                        if (mRegistered && ordered) {
                            if (ActivityThread.DEBUG_BROADCAST) Slog.i(ActivityThread.TAG,
                                    "Finishing failed broadcast to " + mReceiver);
                            sendFinished(mgr);
                        }
                        if (mInstrumentation == null ||
                                !mInstrumentation.onException(mReceiver, e)) {
                            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                            throw new RuntimeException(
                                    "Error receiving broadcast " + intent
                                            + " in " + mReceiver, e);
                        }
                    }

                    if (receiver.getPendingResult() != null) {
                        finish();
                    }
                    Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                };
            }
        }

        ReceiverDispatcher(BroadcastReceiver receiver, Context context,
                Handler activityThread, Instrumentation instrumentation,
                boolean registered) {
            if (activityThread == null) {
                throw new NullPointerException("Handler must not be null");
            }

            mIIntentReceiver = new InnerReceiver(this, !registered);
            mReceiver = receiver;
            mContext = context;
            mActivityThread = activityThread;
            mInstrumentation = instrumentation;
            mRegistered = registered;
            mLocation = new IntentReceiverLeaked(null);
            mLocation.fillInStackTrace();
        }

        void validate(Context context, Handler activityThread) {
            if (mContext != context) {
                throw new IllegalStateException(
                    "Receiver " + mReceiver +
                    " registered with differing Context (was " +
                    mContext + " now " + context + ")");
            }
            if (mActivityThread != activityThread) {
                throw new IllegalStateException(
                    "Receiver " + mReceiver +
                    " registered with differing handler (was " +
                    mActivityThread + " now " + activityThread + ")");
            }
        }

        IntentReceiverLeaked getLocation() {
            return mLocation;
        }

        @UnsupportedAppUsage
        BroadcastReceiver getIntentReceiver() {
            return mReceiver;
        }

        @UnsupportedAppUsage
        IIntentReceiver getIIntentReceiver() {
            return mIIntentReceiver;
        }

        void setUnregisterLocation(RuntimeException ex) {
            mUnregisterLocation = ex;
        }

        RuntimeException getUnregisterLocation() {
            return mUnregisterLocation;
        }

        public void performReceive(Intent intent, int resultCode, String data,
                Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
            final Args args = new Args(intent, resultCode, data, extras, ordered,
                    sticky, sendingUser);
            if (intent == null) {
                Log.wtf(TAG, "Null intent received");
            } else {
                if (ActivityThread.DEBUG_BROADCAST) {
                    int seq = intent.getIntExtra("seq", -1);
                    Slog.i(ActivityThread.TAG, "Enqueueing broadcast " + intent.getAction()
                            + " seq=" + seq + " to " + mReceiver);
                }
            }
            if (intent == null || !mActivityThread.post(args.getRunnable())) {
                if (mRegistered && ordered) {
                    IActivityManager mgr = ActivityManager.getService();
                    if (ActivityThread.DEBUG_BROADCAST) Slog.i(ActivityThread.TAG,
                            "Finishing sync broadcast to " + mReceiver);
                    args.sendFinished(mgr);
                }
            }
        }

    }

    @UnsupportedAppUsage
    public final IServiceConnection getServiceDispatcher(ServiceConnection c,
            Context context, Handler handler, int flags) {
        return getServiceDispatcherCommon(c, context, handler, null, flags);
    }

    public final IServiceConnection getServiceDispatcher(ServiceConnection c,
            Context context, Executor executor, int flags) {
        return getServiceDispatcherCommon(c, context, null, executor, flags);
    }

    private IServiceConnection getServiceDispatcherCommon(ServiceConnection c,
            Context context, Handler handler, Executor executor, int flags) {
        synchronized (mServices) {
            LoadedApk.ServiceDispatcher sd = null;
            ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher> map = mServices.get(context);
            if (map != null) {
                if (DEBUG) Slog.d(TAG, "Returning existing dispatcher " + sd + " for conn " + c);
                sd = map.get(c);
            }
            if (sd == null) {
                if (executor != null) {
                    sd = new ServiceDispatcher(c, context, executor, flags);
                } else {
                    sd = new ServiceDispatcher(c, context, handler, flags);
                }
                if (DEBUG) Slog.d(TAG, "Creating new dispatcher " + sd + " for conn " + c);
                if (map == null) {
                    map = new ArrayMap<>();
                    mServices.put(context, map);
                }
                map.put(c, sd);
            } else {
                sd.validate(context, handler, executor);
            }
            return sd.getIServiceConnection();
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public IServiceConnection lookupServiceDispatcher(ServiceConnection c,
            Context context) {
        synchronized (mServices) {
            LoadedApk.ServiceDispatcher sd = null;
            ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher> map = mServices.get(context);
            if (map != null) {
                sd = map.get(c);
            }
            return sd != null ? sd.getIServiceConnection() : null;
        }
    }

    public final IServiceConnection forgetServiceDispatcher(Context context,
            ServiceConnection c) {
        synchronized (mServices) {
            ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher> map
                    = mServices.get(context);
            LoadedApk.ServiceDispatcher sd = null;
            if (map != null) {
                sd = map.get(c);
                if (sd != null) {
                    if (DEBUG) Slog.d(TAG, "Removing dispatcher " + sd + " for conn " + c);
                    map.remove(c);
                    sd.doForget();
                    if (map.size() == 0) {
                        mServices.remove(context);
                    }
                    if ((sd.getFlags()&Context.BIND_DEBUG_UNBIND) != 0) {
                        ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher> holder
                                = mUnboundServices.get(context);
                        if (holder == null) {
                            holder = new ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher>();
                            mUnboundServices.put(context, holder);
                        }
                        RuntimeException ex = new IllegalArgumentException(
                                "Originally unbound here:");
                        ex.fillInStackTrace();
                        sd.setUnbindLocation(ex);
                        holder.put(c, sd);
                    }
                    return sd.getIServiceConnection();
                }
            }
            ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher> holder
                    = mUnboundServices.get(context);
            if (holder != null) {
                sd = holder.get(c);
                if (sd != null) {
                    RuntimeException ex = sd.getUnbindLocation();
                    throw new IllegalArgumentException(
                            "Unbinding Service " + c
                            + " that was already unbound", ex);
                }
            }
            if (context == null) {
                throw new IllegalStateException("Unbinding Service " + c
                        + " from Context that is no longer in use: " + context);
            } else {
                throw new IllegalArgumentException("Service not registered: " + c);
            }
        }
    }

    static final class ServiceDispatcher {
        private final ServiceDispatcher.InnerConnection mIServiceConnection;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        private final ServiceConnection mConnection;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        private final Context mContext;
        private final Handler mActivityThread;
        private final Executor mActivityExecutor;
        private final ServiceConnectionLeaked mLocation;
        private final int mFlags;

        private RuntimeException mUnbindLocation;

        private boolean mForgotten;

        private static class ConnectionInfo {
            IBinder binder;
            IBinder.DeathRecipient deathMonitor;
        }

        private static class InnerConnection extends IServiceConnection.Stub {
            @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
            final WeakReference<LoadedApk.ServiceDispatcher> mDispatcher;

            InnerConnection(LoadedApk.ServiceDispatcher sd) {
                mDispatcher = new WeakReference<LoadedApk.ServiceDispatcher>(sd);
            }

            public void connected(ComponentName name, IBinder service, boolean dead)
                    throws RemoteException {
                LoadedApk.ServiceDispatcher sd = mDispatcher.get();
                if (sd != null) {
                    sd.connected(name, service, dead);
                }
            }
        }

        private final ArrayMap<ComponentName, ServiceDispatcher.ConnectionInfo> mActiveConnections
            = new ArrayMap<ComponentName, ServiceDispatcher.ConnectionInfo>();

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        ServiceDispatcher(ServiceConnection conn,
                Context context, Handler activityThread, int flags) {
            mIServiceConnection = new InnerConnection(this);
            mConnection = conn;
            mContext = context;
            mActivityThread = activityThread;
            mActivityExecutor = null;
            mLocation = new ServiceConnectionLeaked(null);
            mLocation.fillInStackTrace();
            mFlags = flags;
        }

        ServiceDispatcher(ServiceConnection conn,
                Context context, Executor activityExecutor, int flags) {
            mIServiceConnection = new InnerConnection(this);
            mConnection = conn;
            mContext = context;
            mActivityThread = null;
            mActivityExecutor = activityExecutor;
            mLocation = new ServiceConnectionLeaked(null);
            mLocation.fillInStackTrace();
            mFlags = flags;
        }

        void validate(Context context, Handler activityThread, Executor activityExecutor) {
            if (mContext != context) {
                throw new RuntimeException(
                    "ServiceConnection " + mConnection +
                    " registered with differing Context (was " +
                    mContext + " now " + context + ")");
            }
            if (mActivityThread != activityThread) {
                throw new RuntimeException(
                    "ServiceConnection " + mConnection +
                    " registered with differing handler (was " +
                    mActivityThread + " now " + activityThread + ")");
            }
            if (mActivityExecutor != activityExecutor) {
                throw new RuntimeException(
                    "ServiceConnection " + mConnection +
                    " registered with differing executor (was " +
                    mActivityExecutor + " now " + activityExecutor + ")");
            }
        }

        void doForget() {
            synchronized(this) {
                for (int i=0; i<mActiveConnections.size(); i++) {
                    ServiceDispatcher.ConnectionInfo ci = mActiveConnections.valueAt(i);
                    ci.binder.unlinkToDeath(ci.deathMonitor, 0);
                }
                mActiveConnections.clear();
                mForgotten = true;
            }
        }

        ServiceConnectionLeaked getLocation() {
            return mLocation;
        }

        ServiceConnection getServiceConnection() {
            return mConnection;
        }

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        IServiceConnection getIServiceConnection() {
            return mIServiceConnection;
        }

        int getFlags() {
            return mFlags;
        }

        void setUnbindLocation(RuntimeException ex) {
            mUnbindLocation = ex;
        }

        RuntimeException getUnbindLocation() {
            return mUnbindLocation;
        }

        public void connected(ComponentName name, IBinder service, boolean dead) {
            if (mActivityExecutor != null) {
                mActivityExecutor.execute(new RunConnection(name, service, 0, dead));
            } else if (mActivityThread != null) {
                mActivityThread.post(new RunConnection(name, service, 0, dead));
            } else {
                doConnected(name, service, dead);
            }
        }

        public void death(ComponentName name, IBinder service) {
            if (mActivityExecutor != null) {
                mActivityExecutor.execute(new RunConnection(name, service, 1, false));
            } else if (mActivityThread != null) {
                mActivityThread.post(new RunConnection(name, service, 1, false));
            } else {
                doDeath(name, service);
            }
        }

        public void doConnected(ComponentName name, IBinder service, boolean dead) {
            ServiceDispatcher.ConnectionInfo old;
            ServiceDispatcher.ConnectionInfo info;

            synchronized (this) {
                if (mForgotten) {
                    // We unbound before receiving the connection; ignore
                    // any connection received.
                    return;
                }
                old = mActiveConnections.get(name);
                if (old != null && old.binder == service) {
                    // Huh, already have this one.  Oh well!
                    return;
                }

                if (service != null) {
                    // A new service is being connected... set it all up.
                    info = new ConnectionInfo();
                    info.binder = service;
                    info.deathMonitor = new DeathMonitor(name, service);
                    try {
                        service.linkToDeath(info.deathMonitor, 0);
                        mActiveConnections.put(name, info);
                    } catch (RemoteException e) {
                        // This service was dead before we got it...  just
                        // don't do anything with it.
                        mActiveConnections.remove(name);
                        return;
                    }

                } else {
                    // The named service is being disconnected... clean up.
                    mActiveConnections.remove(name);
                }

                if (old != null) {
                    old.binder.unlinkToDeath(old.deathMonitor, 0);
                }
            }

            // If there was an old service, it is now disconnected.
            if (old != null) {
                mConnection.onServiceDisconnected(name);
            }
            if (dead) {
                mConnection.onBindingDied(name);
            } else {
                // If there is a new viable service, it is now connected.
                if (service != null) {
                    mConnection.onServiceConnected(name, service);
                } else {
                    // The binding machinery worked, but the remote returned null from onBind().
                    mConnection.onNullBinding(name);
                }
            }
        }

        public void doDeath(ComponentName name, IBinder service) {
            synchronized (this) {
                ConnectionInfo old = mActiveConnections.get(name);
                if (old == null || old.binder != service) {
                    // Death for someone different than who we last
                    // reported...  just ignore it.
                    return;
                }
                mActiveConnections.remove(name);
                old.binder.unlinkToDeath(old.deathMonitor, 0);
            }

            mConnection.onServiceDisconnected(name);
        }

        private final class RunConnection implements Runnable {
            RunConnection(ComponentName name, IBinder service, int command, boolean dead) {
                mName = name;
                mService = service;
                mCommand = command;
                mDead = dead;
            }

            public void run() {
                if (mCommand == 0) {
                    doConnected(mName, mService, mDead);
                } else if (mCommand == 1) {
                    doDeath(mName, mService);
                }
            }

            final ComponentName mName;
            final IBinder mService;
            final int mCommand;
            final boolean mDead;
        }

        private final class DeathMonitor implements IBinder.DeathRecipient
        {
            DeathMonitor(ComponentName name, IBinder service) {
                mName = name;
                mService = service;
            }

            public void binderDied() {
                death(mName, mService);
            }

            final ComponentName mName;
            final IBinder mService;
        }
    }

    /**
     * Check if the Apk paths in the cache are correct, and update them if they are not.
     * @hide
     */
    public static void checkAndUpdateApkPaths(ApplicationInfo expectedAppInfo) {
        // Get the LoadedApk from the cache
        ActivityThread activityThread = ActivityThread.currentActivityThread();
        if (activityThread == null) {
            Log.e(TAG, "Cannot find activity thread");
            return;
        }
        checkAndUpdateApkPaths(activityThread, expectedAppInfo, /* cacheWithCode */ true);
        checkAndUpdateApkPaths(activityThread, expectedAppInfo, /* cacheWithCode */ false);
    }

    private static void checkAndUpdateApkPaths(ActivityThread activityThread,
            ApplicationInfo expectedAppInfo, boolean cacheWithCode) {
        String expectedCodePath = expectedAppInfo.getCodePath();
        LoadedApk loadedApk = activityThread.peekPackageInfo(
                expectedAppInfo.packageName, /* includeCode= */ cacheWithCode);
        // If there is load apk cached, or if the cache is valid, don't do anything.
        if (loadedApk == null || loadedApk.getApplicationInfo() == null
                || loadedApk.getApplicationInfo().getCodePath().equals(expectedCodePath)) {
            return;
        }
        // Duplicate framework logic
        List<String> oldPaths = new ArrayList<>();
        LoadedApk.makePaths(activityThread, expectedAppInfo, oldPaths);

        // Force update the LoadedApk instance, which should update the reference in the cache
        loadedApk.updateApplicationInfo(expectedAppInfo, oldPaths);
    }

}
