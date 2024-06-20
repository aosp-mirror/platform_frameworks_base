/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.os;

import static android.system.OsConstants.S_IRWXG;
import static android.system.OsConstants.S_IRWXO;

import static com.android.internal.util.FrameworkStatsLog.BOOT_TIME_EVENT_ELAPSED_TIME__EVENT__SECONDARY_ZYGOTE_INIT_START;
import static com.android.internal.util.FrameworkStatsLog.BOOT_TIME_EVENT_ELAPSED_TIME__EVENT__ZYGOTE_INIT_START;

import android.app.ApplicationLoaders;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.SharedLibraryInfo;
import android.content.res.Resources;
import android.os.Build;
import android.os.Environment;
import android.os.IInstalld;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.ZygoteProcess;
import android.provider.DeviceConfig;
import android.security.keystore2.AndroidKeyStoreProvider;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructCapUserData;
import android.system.StructCapUserHeader;
import android.text.Hyphenator;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.TimingsTraceLog;
import android.view.WindowManager;
import android.webkit.WebViewFactory;
import android.widget.TextView;

import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.Preconditions;

import dalvik.system.VMRuntime;
import dalvik.system.ZygoteHooks;

import libcore.io.IoUtils;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;

/**
 * Startup class for the zygote process.
 *
 * Pre-initializes some classes, and then waits for commands on a UNIX domain socket. Based on these
 * commands, forks off child processes that inherit the initial state of the VM.
 *
 * Please see {@link ZygoteArguments} for documentation on the client protocol.
 *
 * @hide
 */
public class ZygoteInit {

    private static final String TAG = "Zygote";

    private static final boolean LOGGING_DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String PROPERTY_DISABLE_GRAPHICS_DRIVER_PRELOADING =
            "ro.zygote.disable_gl_preload";

    private static final int LOG_BOOT_PROGRESS_PRELOAD_START = 3020;
    private static final int LOG_BOOT_PROGRESS_PRELOAD_END = 3030;

    private static final String ABI_LIST_ARG = "--abi-list=";

    // TODO (chriswailes): Re-name this --zygote-socket-name= and then add a
    // --usap-socket-name parameter.
    private static final String SOCKET_NAME_ARG = "--socket-name=";

    /**
     * The path of a file that contains classes to preload.
     */
    private static final String PRELOADED_CLASSES = "/system/etc/preloaded-classes";

    private static final int UNPRIVILEGED_UID = 9999;
    private static final int UNPRIVILEGED_GID = 9999;

    private static final int ROOT_UID = 0;
    private static final int ROOT_GID = 0;

    private static boolean sPreloadComplete;

    /**
     * Cached classloader to use for the system server. Will only be populated in the system
     * server process.
     */
    private static ClassLoader sCachedSystemServerClassLoader = null;

    static void preload(TimingsTraceLog bootTimingsTraceLog) {
        Log.d(TAG, "begin preload");
        bootTimingsTraceLog.traceBegin("BeginPreload");
        beginPreload();
        bootTimingsTraceLog.traceEnd(); // BeginPreload
        bootTimingsTraceLog.traceBegin("PreloadClasses");
        preloadClasses();
        bootTimingsTraceLog.traceEnd(); // PreloadClasses
        bootTimingsTraceLog.traceBegin("CacheNonBootClasspathClassLoaders");
        cacheNonBootClasspathClassLoaders();
        bootTimingsTraceLog.traceEnd(); // CacheNonBootClasspathClassLoaders
        bootTimingsTraceLog.traceBegin("PreloadResources");
        Resources.preloadResources();
        bootTimingsTraceLog.traceEnd(); // PreloadResources
        Trace.traceBegin(Trace.TRACE_TAG_DALVIK, "PreloadAppProcessHALs");
        nativePreloadAppProcessHALs();
        Trace.traceEnd(Trace.TRACE_TAG_DALVIK);
        Trace.traceBegin(Trace.TRACE_TAG_DALVIK, "PreloadGraphicsDriver");
        maybePreloadGraphicsDriver();
        Trace.traceEnd(Trace.TRACE_TAG_DALVIK);
        preloadSharedLibraries();
        preloadTextResources();
        // Ask the WebViewFactory to do any initialization that must run in the zygote process,
        // for memory sharing purposes.
        WebViewFactory.prepareWebViewInZygote();
        endPreload();
        warmUpJcaProviders();
        Log.d(TAG, "end preload");

        sPreloadComplete = true;
    }

    static void lazyPreload() {
        Preconditions.checkState(!sPreloadComplete);
        Log.i(TAG, "Lazily preloading resources.");

        preload(new TimingsTraceLog("ZygoteInitTiming_lazy", Trace.TRACE_TAG_DALVIK));
    }

    private static void beginPreload() {
        Log.i(TAG, "Calling ZygoteHooks.beginPreload()");

        ZygoteHooks.onBeginPreload();
    }

    private static void endPreload() {
        ZygoteHooks.onEndPreload();

        Log.i(TAG, "Called ZygoteHooks.endPreload()");
    }

    private static void preloadSharedLibraries() {
        Log.i(TAG, "Preloading shared libraries...");
        System.loadLibrary("android");
        System.loadLibrary("jnigraphics");

        // TODO(b/206676167): This library is only used for renderscript today. When renderscript is
        // removed, this load can be removed as well.
        if (!SystemProperties.getBoolean("config.disable_renderscript", false)) {
            System.loadLibrary("compiler_rt");
        }
    }

    native private static void nativePreloadAppProcessHALs();

    /**
     * This call loads the graphics driver by making an OpenGL or Vulkan call.  If the driver is
     * not currently in memory it will load and initialize it.  The OpenGL call itself is relatively
     * cheap and pure.  This means that it is a low overhead on the initial call, and is safe and
     * cheap to call later.  Calls after the initial invocation will effectively be no-ops for the
     * system.
     */
    static native void nativePreloadGraphicsDriver();

    private static void maybePreloadGraphicsDriver() {
        if (!SystemProperties.getBoolean(PROPERTY_DISABLE_GRAPHICS_DRIVER_PRELOADING, false)) {
            nativePreloadGraphicsDriver();
        }
    }

    private static void preloadTextResources() {
        Hyphenator.init();
        TextView.preloadFontCache();
    }

    /**
     * Register AndroidKeyStoreProvider and warm up the providers that are already registered.
     *
     * By doing it here we avoid that each app does it when requesting a service from the provider
     * for the first time.
     */
    private static void warmUpJcaProviders() {
        long startTime = SystemClock.uptimeMillis();
        Trace.traceBegin(
                Trace.TRACE_TAG_DALVIK, "Starting installation of AndroidKeyStoreProvider");

        AndroidKeyStoreProvider.install();
        Log.i(TAG, "Installed AndroidKeyStoreProvider in "
                + (SystemClock.uptimeMillis() - startTime) + "ms.");
        Trace.traceEnd(Trace.TRACE_TAG_DALVIK);

        startTime = SystemClock.uptimeMillis();
        Trace.traceBegin(
                Trace.TRACE_TAG_DALVIK, "Starting warm up of JCA providers");
        for (Provider p : Security.getProviders()) {
            p.warmUpServiceProvision();
        }
        Log.i(TAG, "Warmed up JCA providers in "
                + (SystemClock.uptimeMillis() - startTime) + "ms.");
        Trace.traceEnd(Trace.TRACE_TAG_DALVIK);
    }

    private static boolean isExperimentEnabled(String experiment) {
        boolean defaultValue = SystemProperties.getBoolean(
                "dalvik.vm." + experiment,
                /*def=*/false);
        // Can't use device_config since we are the zygote, and it's not initialized at this point.
        return SystemProperties.getBoolean(
                "persist.device_config." + DeviceConfig.NAMESPACE_RUNTIME_NATIVE_BOOT
                        + "." + experiment,
                defaultValue);
    }

    /* package-private */ static boolean shouldProfileSystemServer() {
        return isExperimentEnabled("profilesystemserver");
    }

    /**
     * Performs Zygote process initialization. Loads and initializes commonly used classes.
     *
     * Most classes only cause a few hundred bytes to be allocated, but a few will allocate a dozen
     * Kbytes (in one case, 500+K).
     */
    private static void preloadClasses() {
        final VMRuntime runtime = VMRuntime.getRuntime();

        InputStream is;
        try {
            is = new FileInputStream(PRELOADED_CLASSES);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Couldn't find " + PRELOADED_CLASSES + ".");
            return;
        }

        Log.i(TAG, "Preloading classes...");
        long startTime = SystemClock.uptimeMillis();

        // Drop root perms while running static initializers.
        final int reuid = Os.getuid();
        final int regid = Os.getgid();

        // We need to drop root perms only if we're already root. In the case of "wrapped"
        // processes (see WrapperInit), this function is called from an unprivileged uid
        // and gid.
        boolean droppedPriviliges = false;
        if (reuid == ROOT_UID && regid == ROOT_GID) {
            try {
                Os.setregid(ROOT_GID, UNPRIVILEGED_GID);
                Os.setreuid(ROOT_UID, UNPRIVILEGED_UID);
            } catch (ErrnoException ex) {
                throw new RuntimeException("Failed to drop root", ex);
            }

            droppedPriviliges = true;
        }

        try {
            BufferedReader br =
                    new BufferedReader(new InputStreamReader(is), Zygote.SOCKET_BUFFER_SIZE);

            int count = 0;
            int missingLambdaCount = 0;
            String line;
            while ((line = br.readLine()) != null) {
                // Skip comments and blank lines.
                line = line.trim();
                if (line.startsWith("#") || line.equals("")) {
                    continue;
                }

                Trace.traceBegin(Trace.TRACE_TAG_DALVIK, line);
                try {
                    // Load and explicitly initialize the given class. Use
                    // Class.forName(String, boolean, ClassLoader) to avoid repeated stack lookups
                    // (to derive the caller's class-loader). Use true to force initialization, and
                    // null for the boot classpath class-loader (could as well cache the
                    // class-loader of this class in a variable).
                    Class.forName(line, true, null);
                    count++;
                } catch (ClassNotFoundException e) {
                    if (line.contains("$$Lambda$")) {
                        if (LOGGING_DEBUG) {
                            missingLambdaCount++;
                        }
                    } else {
                        Log.w(TAG, "Class not found for preloading: " + line);
                    }
                } catch (UnsatisfiedLinkError e) {
                    Log.w(TAG, "Problem preloading " + line + ": " + e);
                } catch (Throwable t) {
                    Log.e(TAG, "Error preloading " + line + ".", t);
                    if (t instanceof Error) {
                        throw (Error) t;
                    } else if (t instanceof RuntimeException) {
                        throw (RuntimeException) t;
                    } else {
                        throw new RuntimeException(t);
                    }
                }
                Trace.traceEnd(Trace.TRACE_TAG_DALVIK);
            }

            Log.i(TAG, "...preloaded " + count + " classes in "
                    + (SystemClock.uptimeMillis() - startTime) + "ms.");
            if (LOGGING_DEBUG && missingLambdaCount != 0) {
                Log.i(TAG, "Unresolved lambda preloads: " + missingLambdaCount);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading " + PRELOADED_CLASSES + ".", e);
        } finally {
            IoUtils.closeQuietly(is);

            // Fill in dex caches with classes, fields, and methods brought in by preloading.
            Trace.traceBegin(Trace.TRACE_TAG_DALVIK, "PreloadDexCaches");
            runtime.preloadDexCaches();
            Trace.traceEnd(Trace.TRACE_TAG_DALVIK);

            // If we are profiling the boot image, reset the Jit counters after preloading the
            // classes. We want to preload for performance, and we can use method counters to
            // infer what clases are used after calling resetJitCounters, for profile purposes.
            if (isExperimentEnabled("profilebootclasspath")) {
                Trace.traceBegin(Trace.TRACE_TAG_DALVIK, "ResetJitCounters");
                VMRuntime.resetJitCounters();
                Trace.traceEnd(Trace.TRACE_TAG_DALVIK);
            }

            // Bring back root. We'll need it later if we're in the zygote.
            if (droppedPriviliges) {
                try {
                    Os.setreuid(ROOT_UID, ROOT_UID);
                    Os.setregid(ROOT_GID, ROOT_GID);
                } catch (ErrnoException ex) {
                    throw new RuntimeException("Failed to restore root", ex);
                }
            }
        }
    }

    /**
     * Load in things which are used by many apps but which cannot be put in the boot
     * classpath.
     */
    private static void cacheNonBootClasspathClassLoaders() {
        // Ordered dependencies first
        final List<SharedLibraryInfo> libs = new ArrayList<>();
        // These libraries used to be part of the bootclasspath, but had to be removed.
        // Old system applications still get them for backwards compatibility reasons,
        // so they are cached here in order to preserve performance characteristics.
        libs.add(new SharedLibraryInfo(
                "/system/framework/android.hidl.base-V1.0-java.jar", null /*packageName*/,
                null /*codePaths*/, null /*name*/, 0 /*version*/, SharedLibraryInfo.TYPE_BUILTIN,
                null /*declaringPackage*/, null /*dependentPackages*/, null /*dependencies*/,
                false /*isNative*/));
        libs.add(new SharedLibraryInfo(
                "/system/framework/android.hidl.manager-V1.0-java.jar", null /*packageName*/,
                null /*codePaths*/, null /*name*/, 0 /*version*/, SharedLibraryInfo.TYPE_BUILTIN,
                null /*declaringPackage*/, null /*dependentPackages*/, null /*dependencies*/,
                false /*isNative*/));

        libs.add(new SharedLibraryInfo(
                "/system/framework/android.test.base.jar", null /*packageName*/,
                null /*codePaths*/, null /*name*/, 0 /*version*/, SharedLibraryInfo.TYPE_BUILTIN,
                null /*declaringPackage*/, null /*dependentPackages*/, null /*dependencies*/,
                false /*isNative*/));

        if (Flags.enableApacheHttpLegacyPreload()) {
            libs.add(new SharedLibraryInfo(
                    "/system/framework/org.apache.http.legacy.jar", null /*packageName*/,
                    null /*codePaths*/, null /*name*/, 0 /*version*/,
                    SharedLibraryInfo.TYPE_BUILTIN, null /*declaringPackage*/,
                    null /*dependentPackages*/, null /*dependencies*/, false /*isNative*/));
        }

        // WindowManager Extensions is an optional shared library that is required for WindowManager
        // Jetpack to fully function. Since it is a widely used library, preload it to improve apps
        // startup performance.
        if (WindowManager.HAS_WINDOW_EXTENSIONS_ON_DEVICE) {
            final String systemExtFrameworkPath =
                    new File(Environment.getSystemExtDirectory(), "framework").getPath();
            libs.add(new SharedLibraryInfo(
                    systemExtFrameworkPath + "/androidx.window.extensions.jar",
                    "androidx.window.extensions", null /*codePaths*/,
                    "androidx.window.extensions", SharedLibraryInfo.VERSION_UNDEFINED,
                    SharedLibraryInfo.TYPE_BUILTIN, null /*declaringPackage*/,
                    null /*dependentPackages*/, null /*dependencies*/, false /*isNative*/));
            libs.add(new SharedLibraryInfo(
                    systemExtFrameworkPath + "/androidx.window.sidecar.jar",
                    "androidx.window.sidecar", null /*codePaths*/,
                    "androidx.window.sidecar", SharedLibraryInfo.VERSION_UNDEFINED,
                    SharedLibraryInfo.TYPE_BUILTIN, null /*declaringPackage*/,
                    null /*dependentPackages*/, null /*dependencies*/, false /*isNative*/));
        }

        ApplicationLoaders.getDefault().createAndCacheNonBootclasspathSystemClassLoaders(libs);
    }

    /**
     * Runs several special GCs to try to clean up a few generations of softly- and final-reachable
     * objects, along with any other garbage. This is only useful just before a fork().
     */
    private static void gcAndFinalize() {
        ZygoteHooks.gcAndFinalize();
    }

    /**
     * Finish remaining work for the newly forked system server process.
     */
    private static Runnable handleSystemServerProcess(ZygoteArguments parsedArgs) {
        // set umask to 0077 so new files and directories will default to owner-only permissions.
        Os.umask(S_IRWXG | S_IRWXO);

        if (parsedArgs.mNiceName != null) {
            Process.setArgV0(parsedArgs.mNiceName);
        }

        final String systemServerClasspath = Os.getenv("SYSTEMSERVERCLASSPATH");
        if (systemServerClasspath != null) {
            // Capturing profiles is only supported for debug or eng builds since selinux normally
            // prevents it.
            if (shouldProfileSystemServer() && (Build.IS_USERDEBUG || Build.IS_ENG)) {
                try {
                    Log.d(TAG, "Preparing system server profile");
                    final String standaloneSystemServerJars =
                            Os.getenv("STANDALONE_SYSTEMSERVER_JARS");
                    final String systemServerPaths = standaloneSystemServerJars != null
                            ? String.join(":", systemServerClasspath, standaloneSystemServerJars)
                            : systemServerClasspath;
                    prepareSystemServerProfile(systemServerPaths);
                } catch (Exception e) {
                    Log.wtf(TAG, "Failed to set up system server profile", e);
                }
            }
        }

        if (parsedArgs.mInvokeWith != null) {
            String[] args = parsedArgs.mRemainingArgs;
            // If we have a non-null system server class path, we'll have to duplicate the
            // existing arguments and append the classpath to it. ART will handle the classpath
            // correctly when we exec a new process.
            if (systemServerClasspath != null) {
                String[] amendedArgs = new String[args.length + 2];
                amendedArgs[0] = "-cp";
                amendedArgs[1] = systemServerClasspath;
                System.arraycopy(args, 0, amendedArgs, 2, args.length);
                args = amendedArgs;
            }

            WrapperInit.execApplication(parsedArgs.mInvokeWith,
                    parsedArgs.mNiceName, parsedArgs.mTargetSdkVersion,
                    VMRuntime.getCurrentInstructionSet(), null, args);

            throw new IllegalStateException("Unexpected return from WrapperInit.execApplication");
        } else {
            ClassLoader cl = getOrCreateSystemServerClassLoader();
            if (cl != null) {
                Thread.currentThread().setContextClassLoader(cl);
            }

            /*
             * Pass the remaining arguments to SystemServer.
             */
            return ZygoteInit.zygoteInit(parsedArgs.mTargetSdkVersion,
                    parsedArgs.mDisabledCompatChanges,
                    parsedArgs.mRemainingArgs, cl);
        }

        /* should never reach here */
    }

    /**
     * Create the classloader for the system server and store it in
     * {@link sCachedSystemServerClassLoader}. This function is called through JNI in the forked
     * system server process in the zygote SELinux domain.
     */
    private static ClassLoader getOrCreateSystemServerClassLoader() {
        if (sCachedSystemServerClassLoader == null) {
            final String systemServerClasspath = Os.getenv("SYSTEMSERVERCLASSPATH");
            if (systemServerClasspath != null) {
                sCachedSystemServerClassLoader = createPathClassLoader(systemServerClasspath,
                        VMRuntime.SDK_VERSION_CUR_DEVELOPMENT);
            }
        }
        return sCachedSystemServerClassLoader;
    }

    /**
     * Creates class loaders for standalone system server jars. This function is called through JNI
     * in the forked system server process in the zygote SELinux domain.
     */
    private static void prefetchStandaloneSystemServerJars() {
        if (shouldProfileSystemServer()) {
            // We don't prefetch AOT artifacts if we are profiling system server, as we are going to
            // JIT it.
            // This method only gets called from native and should already be skipped if we profile
            // system server. Still, be robust and check it again.
            return;
        }
        String envStr = Os.getenv("STANDALONE_SYSTEMSERVER_JARS");
        if (TextUtils.isEmpty(envStr)) {
            return;
        }
        for (String jar : envStr.split(":")) {
            try {
                SystemServerClassLoaderFactory.createClassLoader(
                        jar, getOrCreateSystemServerClassLoader());
            } catch (Error e) {
                // We don't want the process to crash for this error because prefetching is just an
                // optimization.
                Log.e(TAG,
                        String.format("Failed to prefetch standalone system server jar \"%s\": %s",
                                jar, e.toString()));
            }
        }
    }

    /**
     * Note that preparing the profiles for system server does not require special selinux
     * permissions. From the installer perspective the system server is a regular package which can
     * capture profile information.
     */
    private static void prepareSystemServerProfile(String systemServerPaths)
            throws RemoteException {
        if (systemServerPaths.isEmpty()) {
            return;
        }
        String[] codePaths = systemServerPaths.split(":");

        final IInstalld installd = IInstalld.Stub
                .asInterface(ServiceManager.getService("installd"));

        String systemServerPackageName = "android";
        String systemServerProfileName = "primary.prof";
        installd.prepareAppProfile(
                systemServerPackageName,
                UserHandle.USER_SYSTEM,
                UserHandle.getAppId(Process.SYSTEM_UID),
                systemServerProfileName,
                codePaths[0],
                /*dexMetadata*/ null);

        File curProfileDir = Environment.getDataProfilesDePackageDirectory(
                UserHandle.USER_SYSTEM, systemServerPackageName);
        String curProfilePath = new File(curProfileDir, systemServerProfileName).getAbsolutePath();
        File refProfileDir = Environment.getDataProfilesDePackageDirectory(
                UserHandle.USER_SYSTEM, systemServerPackageName);
        String refProfilePath = new File(refProfileDir, systemServerProfileName).getAbsolutePath();
        VMRuntime.registerAppInfo(
                systemServerPackageName,
                curProfilePath,
                refProfilePath,
                codePaths,
                VMRuntime.CODE_PATH_TYPE_PRIMARY_APK);
    }

    /**
     * Sets the list of classes/methods for the hidden API
     */
    public static void setApiDenylistExemptions(String[] exemptions) {
        VMRuntime.getRuntime().setHiddenApiExemptions(exemptions);
    }

    public static void setHiddenApiAccessLogSampleRate(int percent) {
        VMRuntime.getRuntime().setHiddenApiAccessLogSamplingRate(percent);
    }

    /**
     * Sets the implementation to be used for logging hidden API accesses
     * @param logger the implementation of the VMRuntime.HiddenApiUsageLogger interface
     */
    public static void setHiddenApiUsageLogger(VMRuntime.HiddenApiUsageLogger logger) {
        VMRuntime.getRuntime().setHiddenApiUsageLogger(logger);
    }

    /**
     * Creates a PathClassLoader for the given class path that is associated with a shared
     * namespace, i.e., this classloader can access platform-private native libraries.
     *
     * The classloader will add java.library.path to the native library path for the classloader
     * namespace. Since it includes platform locations like /system/lib, this is only appropriate
     * for platform code that don't need linker namespace isolation (as opposed to APEXes and apps).
     */
    static ClassLoader createPathClassLoader(String classPath, int targetSdkVersion) {
        String libraryPath = System.getProperty("java.library.path");

        // We use the boot class loader, that's what the runtime expects at AOT.
        ClassLoader parent = ClassLoader.getSystemClassLoader().getParent();

        return ClassLoaderFactory.createClassLoader(classPath, libraryPath, libraryPath,
                parent, targetSdkVersion, true /* isNamespaceShared */, null /* classLoaderName */);
    }

    /**
     * Prepare the arguments and forks for the system server process.
     *
     * @return A {@code Runnable} that provides an entrypoint into system_server code in the child
     * process; {@code null} in the parent.
     */
    private static Runnable forkSystemServer(String abiList, String socketName,
            ZygoteServer zygoteServer) {
        long capabilities = posixCapabilitiesAsBits(
                OsConstants.CAP_IPC_LOCK,
                OsConstants.CAP_KILL,
                OsConstants.CAP_NET_ADMIN,
                OsConstants.CAP_NET_BIND_SERVICE,
                OsConstants.CAP_NET_BROADCAST,
                OsConstants.CAP_NET_RAW,
                OsConstants.CAP_SYS_MODULE,
                OsConstants.CAP_SYS_NICE,
                OsConstants.CAP_SYS_PTRACE,
                OsConstants.CAP_SYS_TIME,
                OsConstants.CAP_SYS_TTY_CONFIG,
                OsConstants.CAP_WAKE_ALARM,
                OsConstants.CAP_BLOCK_SUSPEND
        );
        /* Containers run without some capabilities, so drop any caps that are not available. */
        StructCapUserHeader header = new StructCapUserHeader(
                OsConstants._LINUX_CAPABILITY_VERSION_3, 0);
        StructCapUserData[] data;
        try {
            data = Os.capget(header);
        } catch (ErrnoException ex) {
            throw new RuntimeException("Failed to capget()", ex);
        }
        capabilities &= Integer.toUnsignedLong(data[0].effective) |
                (Integer.toUnsignedLong(data[1].effective) << 32);

        /* Hardcoded command line to start the system server */
        String[] args = {
                "--setuid=1000",
                "--setgid=1000",
                "--setgroups=1001,1002,1003,1004,1005,1006,1007,1008,1009,1010,1018,1021,1023,"
                        + "1024,1032,1065,3001,3002,3003,3005,3006,3007,3009,3010,3011,3012",
                "--capabilities=" + capabilities + "," + capabilities,
                "--nice-name=system_server",
                "--runtime-args",
                "--target-sdk-version=" + VMRuntime.SDK_VERSION_CUR_DEVELOPMENT,
                "com.android.server.SystemServer",
        };
        ZygoteArguments parsedArgs;

        int pid;

        try {
            ZygoteCommandBuffer commandBuffer = new ZygoteCommandBuffer(args);
            try {
                parsedArgs = ZygoteArguments.getInstance(commandBuffer);
            } catch (EOFException e) {
                throw new AssertionError("Unexpected argument error for forking system server", e);
            }
            commandBuffer.close();
            Zygote.applyDebuggerSystemProperty(parsedArgs);
            Zygote.applyInvokeWithSystemProperty(parsedArgs);

            if (Zygote.nativeSupportsMemoryTagging()) {
                String mode = SystemProperties.get("persist.arm64.memtag.system_server", "");
                if (mode.isEmpty()) {
                  /* The system server has ASYNC MTE by default, in order to allow
                   * system services to specify their own MTE level later, as you
                   * can't re-enable MTE once it's disabled. */
                  mode = SystemProperties.get("persist.arm64.memtag.default", "async");
                }
                if (mode.equals("async")) {
                    parsedArgs.mRuntimeFlags |= Zygote.MEMORY_TAG_LEVEL_ASYNC;
                } else if (mode.equals("sync")) {
                    parsedArgs.mRuntimeFlags |= Zygote.MEMORY_TAG_LEVEL_SYNC;
                } else if (!mode.equals("off")) {
                    /* When we have an invalid memory tag level, keep the current level. */
                    parsedArgs.mRuntimeFlags |= Zygote.nativeCurrentTaggingLevel();
                    Slog.e(TAG, "Unknown memory tag level for the system server: \"" + mode + "\"");
                }
            } else if (Zygote.nativeSupportsTaggedPointers()) {
                /* Enable pointer tagging in the system server. Hardware support for this is present
                 * in all ARMv8 CPUs. */
                parsedArgs.mRuntimeFlags |= Zygote.MEMORY_TAG_LEVEL_TBI;
            }

            /* Enable gwp-asan on the system server with a small probability. This is the same
             * policy as applied to native processes and system apps. */
            parsedArgs.mRuntimeFlags |= Zygote.GWP_ASAN_LEVEL_LOTTERY;

            if (shouldProfileSystemServer()) {
                parsedArgs.mRuntimeFlags |= Zygote.PROFILE_SYSTEM_SERVER;
            }

            /* Request to fork the system server process */
            pid = Zygote.forkSystemServer(
                    parsedArgs.mUid, parsedArgs.mGid,
                    parsedArgs.mGids,
                    parsedArgs.mRuntimeFlags,
                    null,
                    parsedArgs.mPermittedCapabilities,
                    parsedArgs.mEffectiveCapabilities);
        } catch (IllegalArgumentException ex) {
            throw new RuntimeException(ex);
        }

        /* For child process */
        if (pid == 0) {
            if (hasSecondZygote(abiList)) {
                waitForSecondaryZygote(socketName);
            }

            zygoteServer.closeServerSocket();
            return handleSystemServerProcess(parsedArgs);
        }

        return null;
    }

    /**
     * Gets the bit array representation of the provided list of POSIX capabilities.
     */
    private static long posixCapabilitiesAsBits(int... capabilities) {
        long result = 0;
        for (int capability : capabilities) {
            if ((capability < 0) || (capability > OsConstants.CAP_LAST_CAP)) {
                throw new IllegalArgumentException(String.valueOf(capability));
            }
            result |= (1L << capability);
        }
        return result;
    }

    /**
     * This is the entry point for a Zygote process.  It creates the Zygote server, loads resources,
     * and handles other tasks related to preparing the process for forking into applications.
     *
     * This process is started with a nice value of -20 (highest priority).  All paths that flow
     * into new processes are required to either set the priority to the default value or terminate
     * before executing any non-system code.  The native side of this occurs in SpecializeCommon,
     * while the Java Language priority is changed in ZygoteInit.handleSystemServerProcess,
     * ZygoteConnection.handleChildProc, and Zygote.childMain.
     *
     * @param argv  Command line arguments used to specify the Zygote's configuration.
     */
    @UnsupportedAppUsage
    public static void main(String[] argv) {
        ZygoteServer zygoteServer = null;

        // Mark zygote start. This ensures that thread creation will throw
        // an error.
        ZygoteHooks.startZygoteNoThreadCreation();

        // Zygote goes into its own process group.
        try {
            Os.setpgid(0, 0);
        } catch (ErrnoException ex) {
            throw new RuntimeException("Failed to setpgid(0,0)", ex);
        }

        Runnable caller;
        try {
            // Store now for StatsLogging later.
            final long startTime = SystemClock.elapsedRealtime();
            final boolean isRuntimeRestarted = "1".equals(
                    SystemProperties.get("sys.boot_completed"));

            String bootTimeTag = Process.is64Bit() ? "Zygote64Timing" : "Zygote32Timing";
            TimingsTraceLog bootTimingsTraceLog = new TimingsTraceLog(bootTimeTag,
                    Trace.TRACE_TAG_DALVIK);
            bootTimingsTraceLog.traceBegin("ZygoteInit");
            RuntimeInit.preForkInit();

            boolean startSystemServer = false;
            String zygoteSocketName = "zygote";
            String abiList = null;
            boolean enableLazyPreload = false;
            for (int i = 1; i < argv.length; i++) {
                if ("start-system-server".equals(argv[i])) {
                    startSystemServer = true;
                } else if ("--enable-lazy-preload".equals(argv[i])) {
                    enableLazyPreload = true;
                } else if (argv[i].startsWith(ABI_LIST_ARG)) {
                    abiList = argv[i].substring(ABI_LIST_ARG.length());
                } else if (argv[i].startsWith(SOCKET_NAME_ARG)) {
                    zygoteSocketName = argv[i].substring(SOCKET_NAME_ARG.length());
                } else {
                    throw new RuntimeException("Unknown command line argument: " + argv[i]);
                }
            }

            final boolean isPrimaryZygote = zygoteSocketName.equals(Zygote.PRIMARY_SOCKET_NAME);
            if (!isRuntimeRestarted) {
                if (isPrimaryZygote) {
                    FrameworkStatsLog.write(FrameworkStatsLog.BOOT_TIME_EVENT_ELAPSED_TIME_REPORTED,
                            BOOT_TIME_EVENT_ELAPSED_TIME__EVENT__ZYGOTE_INIT_START,
                            startTime);
                } else if (zygoteSocketName.equals(Zygote.SECONDARY_SOCKET_NAME)) {
                    FrameworkStatsLog.write(FrameworkStatsLog.BOOT_TIME_EVENT_ELAPSED_TIME_REPORTED,
                            BOOT_TIME_EVENT_ELAPSED_TIME__EVENT__SECONDARY_ZYGOTE_INIT_START,
                            startTime);
                }
            }

            if (abiList == null) {
                throw new RuntimeException("No ABI list supplied.");
            }

            // In some configurations, we avoid preloading resources and classes eagerly.
            // In such cases, we will preload things prior to our first fork.
            if (!enableLazyPreload) {
                bootTimingsTraceLog.traceBegin("ZygotePreload");
                EventLog.writeEvent(LOG_BOOT_PROGRESS_PRELOAD_START,
                        SystemClock.uptimeMillis());
                preload(bootTimingsTraceLog);
                EventLog.writeEvent(LOG_BOOT_PROGRESS_PRELOAD_END,
                        SystemClock.uptimeMillis());
                bootTimingsTraceLog.traceEnd(); // ZygotePreload
            }

            // Do an initial gc to clean up after startup
            bootTimingsTraceLog.traceBegin("PostZygoteInitGC");
            gcAndFinalize();
            bootTimingsTraceLog.traceEnd(); // PostZygoteInitGC

            bootTimingsTraceLog.traceEnd(); // ZygoteInit

            Zygote.initNativeState(isPrimaryZygote);

            ZygoteHooks.stopZygoteNoThreadCreation();

            zygoteServer = new ZygoteServer(isPrimaryZygote);

            if (startSystemServer) {
                Runnable r = forkSystemServer(abiList, zygoteSocketName, zygoteServer);

                // {@code r == null} in the parent (zygote) process, and {@code r != null} in the
                // child (system_server) process.
                if (r != null) {
                    r.run();
                    return;
                }
            }

            Log.i(TAG, "Accepting command socket connections");

            // The select loop returns early in the child process after a fork and
            // loops forever in the zygote.
            caller = zygoteServer.runSelectLoop(abiList);
        } catch (Throwable ex) {
            Log.e(TAG, "System zygote died with fatal exception", ex);
            throw ex;
        } finally {
            if (zygoteServer != null) {
                zygoteServer.closeServerSocket();
            }
        }

        // We're in the child process and have exited the select loop. Proceed to execute the
        // command.
        if (caller != null) {
            caller.run();
        }
    }

    /**
     * Return {@code true} if this device configuration has another zygote.
     *
     * We determine this by comparing the device ABI list with this zygotes list. If this zygote
     * supports all ABIs this device supports, there won't be another zygote.
     */
    private static boolean hasSecondZygote(String abiList) {
        return !SystemProperties.get("ro.product.cpu.abilist").equals(abiList);
    }

    private static void waitForSecondaryZygote(String socketName) {
        String otherZygoteName = Zygote.PRIMARY_SOCKET_NAME.equals(socketName)
                ? Zygote.SECONDARY_SOCKET_NAME : Zygote.PRIMARY_SOCKET_NAME;
        ZygoteProcess.waitForConnectionToZygote(otherZygoteName);
    }

    static boolean isPreloadComplete() {
        return sPreloadComplete;
    }

    /**
     * Class not instantiable.
     */
    private ZygoteInit() {
    }

    /**
     * The main function called when started through the zygote process. This could be unified with
     * main(), if the native code in nativeFinishInit() were rationalized with Zygote startup.<p>
     *
     * Current recognized args:
     * <ul>
     * <li> <code> [--] &lt;start class name&gt;  &lt;args&gt;
     * </ul>
     *
     * @param targetSdkVersion target SDK version
     * @param disabledCompatChanges set of disabled compat changes for the process (all others
     *                              are enabled)
     * @param argv             arg strings
     */
    public static Runnable zygoteInit(int targetSdkVersion, long[] disabledCompatChanges,
            String[] argv, ClassLoader classLoader) {
        if (RuntimeInit.DEBUG) {
            Slog.d(RuntimeInit.TAG, "RuntimeInit: Starting application from zygote");
        }

        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "ZygoteInit");
        RuntimeInit.redirectLogStreams();

        RuntimeInit.commonInit();
        ZygoteInit.nativeZygoteInit();
        return RuntimeInit.applicationInit(targetSdkVersion, disabledCompatChanges, argv,
                classLoader);
    }

    /**
     * The main function called when starting a child zygote process. This is used as an alternative
     * to zygoteInit(), which skips calling into initialization routines that start the Binder
     * threadpool.
     */
    static Runnable childZygoteInit(String[] argv) {
        RuntimeInit.Arguments args = new RuntimeInit.Arguments(argv);
        return RuntimeInit.findStaticMain(args.startClass, args.startArgs, /* classLoader= */null);
    }

    private static native void nativeZygoteInit();
}
