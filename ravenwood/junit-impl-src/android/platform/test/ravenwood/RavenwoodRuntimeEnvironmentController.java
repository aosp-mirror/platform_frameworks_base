/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.platform.test.ravenwood;

import static android.os.Process.FIRST_APPLICATION_UID;
import static android.os.UserHandle.SYSTEM;
import static android.platform.test.ravenwood.RavenwoodSystemServer.ANDROID_PACKAGE_NAME;

import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_EMPTY_RESOURCES_APK;
import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_INST_RESOURCE_APK;
import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_RESOURCE_APK;
import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_VERSION_JAVA_SYSPROP;
import static com.android.ravenwood.common.RavenwoodCommonUtils.parseNullableInt;
import static com.android.ravenwood.common.RavenwoodCommonUtils.withDefault;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppCompatCallbacks;
import android.app.Instrumentation;
import android.app.ResourcesManager;
import android.app.UiAutomation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.icu.util.ULocale;
import android.os.Binder;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process_ravenwood;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.os.SystemProperties;
import android.provider.DeviceConfig_host;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.util.Log_ravenwood;
import android.view.DisplayAdjustments;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.hoststubgen.hosthelper.HostTestUtils;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.RuntimeInit;
import com.android.ravenwood.RavenwoodRuntimeNative;
import com.android.ravenwood.RavenwoodRuntimeState;
import com.android.ravenwood.common.RavenwoodCommonUtils;
import com.android.ravenwood.common.SneakyThrow;
import com.android.server.LocalServices;
import com.android.server.compat.PlatformCompat;

import org.junit.AssumptionViolatedException;
import org.junit.internal.management.ManagementFactory;
import org.junit.runner.Description;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Responsible for initializing and the environment.
 */
public class RavenwoodRuntimeEnvironmentController {
    private static final String TAG = com.android.ravenwood.common.RavenwoodCommonUtils.TAG;

    private RavenwoodRuntimeEnvironmentController() {
    }

    private static final PrintStream sStdOut = System.out;
    @SuppressWarnings("UnusedVariable")
    private static final PrintStream sStdErr = System.err;

    private static final String MAIN_THREAD_NAME = "Ravenwood:Main";
    private static final String TESTS_THREAD_NAME = "Ravenwood:Test";

    private static final String LIBRAVENWOOD_INITIALIZER_NAME = "ravenwood_initializer";
    private static final String RAVENWOOD_NATIVE_RUNTIME_NAME = "ravenwood_runtime";

    private static final String ANDROID_LOG_TAGS = "ANDROID_LOG_TAGS";
    private static final String RAVENWOOD_ANDROID_LOG_TAGS = "RAVENWOOD_" + ANDROID_LOG_TAGS;

    static volatile Thread sTestThread;
    static volatile Thread sMainThread;

    /**
     * When enabled, attempt to dump all thread stacks just before we hit the
     * overall Tradefed timeout, to aid in debugging deadlocks.
     *
     * Note, this timeout will _not_ stop the test, as there isn't really a clean way to do it.
     * It'll merely print stacktraces.
     */
    private static final boolean ENABLE_TIMEOUT_STACKS =
            !"0".equals(System.getenv("RAVENWOOD_ENABLE_TIMEOUT_STACKS"));

    private static final boolean TOLERATE_LOOPER_ASSERTS =
            !"0".equals(System.getenv("RAVENWOOD_TOLERATE_LOOPER_ASSERTS"));

    static final int DEFAULT_TIMEOUT_SECONDS = 10;
    private static final int TIMEOUT_MILLIS = getTimeoutSeconds() * 1000;

    static int getTimeoutSeconds() {
        var e = System.getenv("RAVENWOOD_TIMEOUT_SECONDS");
        if (e == null || e.isEmpty()) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return Integer.parseInt(e);
    }


    private static final ScheduledExecutorService sTimeoutExecutor =
            Executors.newScheduledThreadPool(1, (Runnable r) -> {
                Thread t = Executors.defaultThreadFactory().newThread(r);
                t.setName("Ravenwood:TimeoutMonitor");
                t.setDaemon(true);
                return t;
            });

    private static volatile ScheduledFuture<?> sPendingTimeout;

    /**
     * When enabled, attempt to detect uncaught exceptions from background threads.
     */
    private static final boolean ENABLE_UNCAUGHT_EXCEPTION_DETECTION =
            !"0".equals(System.getenv("RAVENWOOD_ENABLE_UNCAUGHT_EXCEPTION_DETECTION"));

    private static final boolean DIE_ON_UNCAUGHT_EXCEPTION = true;

    /**
     * When set, an unhandled exception was discovered (typically on a background thread), and we
     * capture it here to ensure it's reported as a test failure.
     */
    private static final AtomicReference<Throwable> sPendingUncaughtException =
            new AtomicReference<>();

    // TODO: expose packCallingIdentity function in libbinder and use it directly
    // See: packCallingIdentity in frameworks/native/libs/binder/IPCThreadState.cpp
    private static long packBinderIdentityToken(
            boolean hasExplicitIdentity, int callingUid, int callingPid) {
        long res = ((long) callingUid << 32) | callingPid;
        if (hasExplicitIdentity) {
            res |= (0x1 << 30);
        } else {
            res &= ~(0x1 << 30);
        }
        return res;
    }

    /** Map from path -> resources. */
    private static final HashMap<File, Resources> sCachedResources = new HashMap<>();
    private static Set<String> sAdoptedPermissions = Collections.emptySet();

    private static final Object sInitializationLock = new Object();

    @GuardedBy("sInitializationLock")
    private static boolean sInitialized = false;

    @GuardedBy("sInitializationLock")
    private static Throwable sExceptionFromGlobalInit;

    private static final int DEFAULT_TARGET_SDK_LEVEL = VERSION_CODES.CUR_DEVELOPMENT;
    private static final String DEFAULT_PACKAGE_NAME = "com.android.ravenwoodtests.defaultname";

    private static final int sMyPid = new Random().nextInt(100, 32768);
    private static int sTargetSdkLevel;
    private static String sTestPackageName;
    private static String sTargetPackageName;
    private static Instrumentation sInstrumentation;
    private static final long sCallingIdentity =
            packBinderIdentityToken(false, FIRST_APPLICATION_UID, sMyPid);

    /**
     * Initialize the global environment.
     */
    public static void globalInitOnce() {
        sTestThread = Thread.currentThread();
        Thread.currentThread().setName(TESTS_THREAD_NAME);
        synchronized (sInitializationLock) {
            if (!sInitialized) {
                // globalInitOnce() is called from class initializer, which cause
                // this method to be called recursively,
                sInitialized = true;

                // This is the first call.
                final long start = System.currentTimeMillis();
                try {
                    globalInitInner();
                } catch (Throwable th) {
                    Log.e(TAG, "globalInit() failed", th);

                    sExceptionFromGlobalInit = th;
                    SneakyThrow.sneakyThrow(th);
                }
                final long end = System.currentTimeMillis();
                // TODO Show user/system time too
                Log.e(TAG, "globalInit() took " + (end - start) + "ms");
            } else {
                // Subsequent calls. If the first call threw, just throw the same error, to prevent
                // the test from running.
                if (sExceptionFromGlobalInit != null) {
                    Log.e(TAG, "globalInit() failed re-throwing the same exception",
                            sExceptionFromGlobalInit);

                    SneakyThrow.sneakyThrow(sExceptionFromGlobalInit);
                }
            }
        }
    }

    private static void globalInitInner() throws IOException {
        // We haven't initialized liblog yet, so directly write to System.out here.
        RavenwoodCommonUtils.log(TAG, "globalInitInner()");

        if (ENABLE_UNCAUGHT_EXCEPTION_DETECTION) {
            Thread.setDefaultUncaughtExceptionHandler(
                    RavenwoodRuntimeEnvironmentController::reportUncaughtExceptions);
        }

        // Some process-wide initialization:
        // - maybe redirect stdout/stderr
        // - override native system property functions
        var lib = RavenwoodCommonUtils.getJniLibraryPath(LIBRAVENWOOD_INITIALIZER_NAME);
        System.load(lib);
        RavenwoodRuntimeNative.reloadNativeLibrary(lib);

        // Redirect stdout/stdin to the Log API.
        RuntimeInit.redirectLogStreams();

        dumpCommandLineArgs();
        dumpEnvironment();
        dumpJavaProperties();
        dumpOtherInfo();

        // Make sure libravenwood_runtime is loaded.
        System.load(RavenwoodCommonUtils.getJniLibraryPath(RAVENWOOD_NATIVE_RUNTIME_NAME));

        Log_ravenwood.setLogLevels(getLogTags());
        Log_ravenwood.onRavenwoodRuntimeNativeReady();

        // Do the basic set up for the android sysprops.
        RavenwoodSystemProperties.initialize();

        // Set ICU data file
        String icuData = RavenwoodCommonUtils.getRavenwoodRuntimePath()
                + "ravenwood-data/"
                + RavenwoodRuntimeNative.getIcuDataName()
                + ".dat";
        RavenwoodRuntimeNative.setSystemProperty("ro.icu.data.path", icuData);

        // Enable all log levels for native logging, until we'll have a way to change the native
        // side log level at runtime.
        // Do this after loading RAVENWOOD_NATIVE_RUNTIME_NAME (which backs Os.setenv()),
        // before loadFrameworkNativeCode() (which uses $ANDROID_LOG_TAGS).
        // This would also prevent libbase from crashing the process (b/381112373) because
        // the string format it accepts is very limited.
        try {
            Os.setenv("ANDROID_LOG_TAGS", "*:v", true);
        } catch (ErrnoException e) {
            throw new RuntimeException(e);
        }

        // Make sure libandroid_runtime is loaded.
        RavenwoodNativeLoader.loadFrameworkNativeCode();

        // Start method logging.
        RavenwoodMethodCallLogger.enable(sStdOut);

        // Touch some references early to ensure they're <clinit>'ed
        Objects.requireNonNull(Build.TYPE);
        Objects.requireNonNull(Build.VERSION.SDK);

        // Fonts can only be initialized once
        // AOSP only: typeface is not supported on AOSP.
        // Typeface.init();
        // Typeface.loadPreinstalledSystemFontMap();
        // Typeface.loadNativeSystemFonts();

        System.setProperty(RAVENWOOD_VERSION_JAVA_SYSPROP, "1");
        // This will let AndroidJUnit4 use the original runner.
        System.setProperty("android.junit.runner",
                "androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner");

        loadRavenwoodProperties();

        assertMockitoVersion();

        Log.i(TAG, "TargetPackageName=" + sTargetPackageName);
        Log.i(TAG, "TestPackageName=" + sTestPackageName);
        Log.i(TAG, "TargetSdkLevel=" + sTargetSdkLevel);

        RavenwoodRuntimeState.sUid = FIRST_APPLICATION_UID;
        RavenwoodRuntimeState.sPid = sMyPid;
        RavenwoodRuntimeState.sTargetSdkLevel = sTargetSdkLevel;

        ServiceManager.init$ravenwood();
        LocalServices.removeAllServicesForTest();

        ActivityManager.init$ravenwood(SYSTEM.getIdentifier());

        final var main = new HandlerThread(MAIN_THREAD_NAME);
        sMainThread = main;
        main.start();
        Looper.setMainLooperForTest(main.getLooper());

        final boolean isSelfInstrumenting =
                Objects.equals(sTestPackageName, sTargetPackageName);

        // This will load the resources from the apk set to `resource_apk` in the build file.
        // This is supposed to be the "target app"'s resources.
        final Supplier<Resources> targetResourcesLoader = () -> {
            var file = new File(RAVENWOOD_RESOURCE_APK);
            return loadResources(file.exists() ? file : null);
        };

        // Set up test context's (== instrumentation context's) resources.
        // If the target package name == test package name, then we use the main resources.
        final Supplier<Resources> instResourcesLoader;
        if (isSelfInstrumenting) {
            instResourcesLoader = targetResourcesLoader;
        } else {
            instResourcesLoader = () -> {
                var file = new File(RAVENWOOD_INST_RESOURCE_APK);
                return loadResources(file.exists() ? file : null);
            };
        }

        var instContext = new RavenwoodContext(
                sTestPackageName, main, instResourcesLoader);
        var targetContext = new RavenwoodContext(
                sTargetPackageName, main, targetResourcesLoader);

        // Set up app context.
        var appContext = new RavenwoodContext(sTargetPackageName, main, targetResourcesLoader);
        appContext.setApplicationContext(appContext);
        if (isSelfInstrumenting) {
            instContext.setApplicationContext(appContext);
            targetContext.setApplicationContext(appContext);
        } else {
            // When instrumenting into another APK, the test context doesn't have an app context.
            targetContext.setApplicationContext(appContext);
        }

        final Supplier<Resources> systemResourcesLoader = () -> loadResources(null);

        var systemServerContext =
                new RavenwoodContext(ANDROID_PACKAGE_NAME, main, systemResourcesLoader);

        var instArgs = Bundle.EMPTY;
        RavenwoodUtils.runOnMainThreadSync(() -> {
            try {
                // TODO We should get the instrumentation class name from the build file or
                // somewhere.
                var InstClass = Class.forName("android.app.Instrumentation");
                sInstrumentation = (Instrumentation) InstClass.getConstructor().newInstance();
                sInstrumentation.basicInit(instContext, targetContext, null);
                sInstrumentation.onCreate(instArgs);
            } catch (Exception e) {
                SneakyThrow.sneakyThrow(e);
            }
        });
        InstrumentationRegistry.registerInstance(sInstrumentation, instArgs);

        RavenwoodSystemServer.init(systemServerContext);

        initializeCompatIds();
    }

    /**
     * Get log tags from environmental variable.
     */
    @Nullable
    private static String getLogTags() {
        var logTags = System.getenv(RAVENWOOD_ANDROID_LOG_TAGS);
        if (logTags == null) {
            logTags = System.getenv(ANDROID_LOG_TAGS);
        }
        return logTags;
    }

    private static void loadRavenwoodProperties() {
        var props = RavenwoodSystemProperties.readProperties("ravenwood.properties");

        sTargetSdkLevel = withDefault(
                parseNullableInt(props.get("targetSdkVersionInt")), DEFAULT_TARGET_SDK_LEVEL);
        sTargetPackageName = withDefault(props.get("packageName"), DEFAULT_PACKAGE_NAME);
        sTestPackageName = withDefault(props.get("instPackageName"), sTargetPackageName);

        // TODO(b/377765941) Read them from the manifest too?
    }

    /**
     * Partially reset and initialize before each test class invocation
     */
    public static void initForRunner() {
        var targetContext = sInstrumentation.getTargetContext();
        var instContext = sInstrumentation.getContext();
        // We need to recreate the mock UiAutomation for each test class, because sometimes tests
        // will call Mockito.framework().clearInlineMocks() after execution.
        sInstrumentation.basicInit(instContext, targetContext, createMockUiAutomation());

        // Reset some global state
        Process_ravenwood.reset();
        DeviceConfig_host.reset();
        Binder.restoreCallingIdentity(sCallingIdentity);

        SystemProperties.clearChangeCallbacksForTest();

        maybeThrowPendingUncaughtException();
    }

    /**
     * Called when a test method is about to be started.
     */
    public static void enterTestMethod(Description description) {
        // TODO(b/375272444): this is a hacky workaround to ensure binder identity
        Binder.restoreCallingIdentity(sCallingIdentity);

        scheduleTimeout();
    }

    /**
     * Called when a test method finished.
     */
    public static void exitTestMethod(Description description) {
        cancelTimeout();
        maybeThrowPendingUncaughtException();
    }

    private static void scheduleTimeout() {
        if (!ENABLE_TIMEOUT_STACKS) {
            return;
        }
        cancelTimeout();

        sPendingTimeout = sTimeoutExecutor.schedule(
                RavenwoodRuntimeEnvironmentController::onTestTimedOut,
                TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static void cancelTimeout() {
        if (!ENABLE_TIMEOUT_STACKS) {
            return;
        }
        var pt = sPendingTimeout;
        if (pt != null) {
            pt.cancel(false);
        }
    }

    private static void initializeCompatIds() {
        // Set up compat-IDs for the app side.
        // TODO: Inside the system server, all the compat-IDs should be enabled,
        // Due to the `AppCompatCallbacks.install(new long[0], new long[0])` call in
        // SystemServer.

        // Compat framework only uses the package name and the target SDK level.
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = sTargetPackageName;
        appInfo.targetSdkVersion = sTargetSdkLevel;

        PlatformCompat platformCompat = null;
        try {
            platformCompat = (PlatformCompat) ServiceManager.getServiceOrThrow(
                    Context.PLATFORM_COMPAT_SERVICE);
        } catch (ServiceNotFoundException e) {
            throw new RuntimeException(e);
        }

        var disabledChanges = platformCompat.getDisabledChanges(appInfo);
        var loggableChanges = platformCompat.getLoggableChanges(appInfo);

        AppCompatCallbacks.install(disabledChanges, loggableChanges);
    }

    /**
     * Load {@link Resources} from an APK, with cache.
     */
    private static Resources loadResources(@Nullable File apkPath) {
        var cached = sCachedResources.get(apkPath);
        if (cached != null) {
            return cached;
        }

        var fileToLoad = apkPath != null ? apkPath : new File(RAVENWOOD_EMPTY_RESOURCES_APK);

        assertTrue("File " + fileToLoad + " doesn't exist.", fileToLoad.isFile());

        final String path = fileToLoad.getAbsolutePath();
        final var emptyPaths = new String[0];

        ResourcesManager.getInstance().initializeApplicationPaths(path, emptyPaths);

        final var ret = ResourcesManager.getInstance().getResources(null, path,
                emptyPaths, emptyPaths, emptyPaths,
                emptyPaths, null, null,
                new DisplayAdjustments().getCompatibilityInfo(),
                RavenwoodRuntimeEnvironmentController.class.getClassLoader(), null);

        assertNotNull(ret);

        sCachedResources.put(apkPath, ret);
        return ret;
    }

    /**
     * Return if an exception is benign and okay to continue running the main looper even
     * if we detect it.
     */
    private static boolean isThrowableBenign(Throwable th) {
        return th instanceof AssertionError || th instanceof AssumptionViolatedException;
    }

    static void dispatchMessage(Message msg) {
        try {
            msg.getTarget().dispatchMessage(msg);
        } catch (Throwable th) {
            var desc = String.format("Detected %s on looper thread %s", th.getClass().getName(),
                    Thread.currentThread());
            sStdErr.println(desc);
            if (TOLERATE_LOOPER_ASSERTS && isThrowableBenign(th)) {
                sStdErr.printf("*** Continuing the test because it's %s ***\n",
                        th.getClass().getSimpleName());
                var e = new Exception(desc, th);
                sPendingUncaughtException.compareAndSet(null, e);
                return;
            }
            throw th;
        }
    }

    /**
     * A callback when a test class finishes its execution, mostly only for debugging.
     */
    public static void exitTestClass() {
        maybeThrowPendingUncaughtException();
    }

    public static void logTestRunner(String label, Description description) {
        // This message string carefully matches the exact format emitted by on-device tests, to
        // aid developers in debugging raw text logs
        Log.e("TestRunner", label + ": " + description.getMethodName()
                + "(" + description.getTestClass().getName() + ")");
    }

    private static void maybeThrowPendingUncaughtException() {
        final Throwable pending = sPendingUncaughtException.getAndSet(null);
        if (pending != null) {
            throw new IllegalStateException("Found an uncaught exception", pending);
        }
    }

    /**
     * Prints the stack trace from all threads.
     */
    private static void onTestTimedOut() {
        sStdErr.println("********* SLOW TEST DETECTED ********");
        dumpStacks(null, null);
    }

    private static final Object sDumpStackLock = new Object();

    /**
     * Prints the stack trace from all threads.
     */
    private static void dumpStacks(
            @Nullable Thread exceptionThread, @Nullable Throwable throwable) {
        cancelTimeout();
        synchronized (sDumpStackLock) {
            final PrintStream out = sStdErr;
            out.println("-----BEGIN ALL THREAD STACKS-----");

            var stacks = Thread.getAllStackTraces();
            var threads = stacks.keySet().stream().sorted(
                    Comparator.comparingLong(Thread::getId)).collect(Collectors.toList());

            // Put the test and the main thread at the top.
            var testThread = sTestThread;
            var mainThread = sMainThread;
            if (mainThread != null) {
                threads.remove(mainThread);
                threads.add(0, mainThread);
            }
            if (testThread != null) {
                threads.remove(testThread);
                threads.add(0, testThread);
            }
            // Put the exception thread at the top.
            // Also inject the stacktrace from the exception.
            if (exceptionThread != null) {
                threads.remove(exceptionThread);
                threads.add(0, exceptionThread);
                stacks.put(exceptionThread, throwable.getStackTrace());
            }
            for (var th : threads) {
                out.println();

                out.print("Thread");
                if (th == exceptionThread) {
                    out.print(" [** EXCEPTION THREAD **]");
                }
                out.print(": " + th.getName() + " / " + th);
                out.println();

                for (StackTraceElement e :  stacks.get(th)) {
                    out.println("\tat " + e);
                }
            }
            out.println("-----END ALL THREAD STACKS-----");
        }
    }

    private static final String MOCKITO_ERROR = "FATAL: Unsupported Mockito detected!"
            + " Your test or its dependencies use one of the \"mockito-target-*\""
            + " modules as static library, which is unusable on host side."
            + " Please switch over to use \"mockito-ravenwood-prebuilt\" as shared library, or"
            + " as a last resort, set `ravenizer: { strip_mockito: true }` in your test module.";

    /**
     * Assert the Mockito version at runtime to ensure no incorrect Mockito classes are loaded.
     */
    private static void assertMockitoVersion() {
        // DexMaker should not exist
        assertThrows(
                MOCKITO_ERROR,
                ClassNotFoundException.class,
                () -> Class.forName("com.android.dx.DexMaker"));
        // Mockito 2 should not exist
        assertThrows(
                MOCKITO_ERROR,
                ClassNotFoundException.class,
                () -> Class.forName("org.mockito.Matchers"));
    }

    static <T> T makeDefaultThrowMock(Class<T> clazz) {
        return mock(clazz, inv -> {
            HostTestUtils.onThrowMethodCalled();
            return null;
        });
    }

    // TODO: use the real UiAutomation class instead of a mock
    private static UiAutomation createMockUiAutomation() {
        sAdoptedPermissions = Collections.emptySet();
        var mock = makeDefaultThrowMock(UiAutomation.class);
        doAnswer(inv -> {
            sAdoptedPermissions = UiAutomation.ALL_PERMISSIONS;
            return null;
        }).when(mock).adoptShellPermissionIdentity();
        doAnswer(inv -> {
            if (inv.getArgument(0) == null) {
                sAdoptedPermissions = UiAutomation.ALL_PERMISSIONS;
            } else {
                sAdoptedPermissions = (Set) Set.of(inv.getArguments());
            }
            return null;
        }).when(mock).adoptShellPermissionIdentity(any());
        doAnswer(inv -> {
            sAdoptedPermissions = Collections.emptySet();
            return null;
        }).when(mock).dropShellPermissionIdentity();
        doAnswer(inv -> sAdoptedPermissions).when(mock).getAdoptedShellPermissions();
        return mock;
    }

    private static void dumpCommandLineArgs() {
        Log.v(TAG, "JVM arguments:");

        // Note, we use the wrapper in JUnit4, not the actual class (
        // java.lang.management.ManagementFactory), because we can't see the later at the build
        // because this source file is compiled for the device target, where ManagementFactory
        // doesn't exist.
        var args = ManagementFactory.getRuntimeMXBean().getInputArguments();

        for (var arg : args) {
            Log.v(TAG, "  " + arg);
        }
    }

    private static void reportUncaughtExceptions(Thread th, Throwable e) {
        sStdErr.printf("Uncaught exception detected: %s: %s\n",
                th, RavenwoodCommonUtils.getStackTraceString(e));

        doBugreport(th, e, DIE_ON_UNCAUGHT_EXCEPTION);
    }

    private static void doBugreport(
            @Nullable Thread exceptionThread, @Nullable Throwable throwable,
            boolean killSelf) {
        // TODO: Print more information
        dumpStacks(exceptionThread, throwable);
        if (killSelf) {
            System.exit(13);
        }
    }

    private static void dumpJavaProperties() {
        Log.v(TAG, "JVM properties:");
        dumpMap(System.getProperties());
    }

    private static void dumpEnvironment() {
        Log.v(TAG, "Environment:");
        dumpMap(System.getenv());
    }

    private static void dumpMap(Map<?, ?> map) {
        for (var key : map.keySet().stream().sorted().toList()) {
            Log.v(TAG, "  " + key + "=" + map.get(key));
        }
    }
    private static void dumpOtherInfo() {
        Log.v(TAG, "Other key information:");
        var jloc = Locale.getDefault();
        Log.v(TAG, "  java.util.Locale=" + jloc + " / " + jloc.toLanguageTag());
        var uloc = ULocale.getDefault();
        Log.v(TAG, "  android.icu.util.ULocale=" + uloc + " / " + uloc.toLanguageTag());

        var jtz = java.util.TimeZone.getDefault();
        Log.v(TAG, "  java.util.TimeZone=" + jtz.getDisplayName() + " / " + jtz);

        var itz = android.icu.util.TimeZone.getDefault();
        Log.v(TAG, "  android.icu.util.TimeZone="  + itz.getDisplayName() + " / " + itz);
    }
}
