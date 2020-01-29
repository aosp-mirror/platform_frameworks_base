/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server;

import static android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_CRITICAL;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_HIGH;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_NORMAL;
import static android.os.IServiceManager.DUMP_FLAG_PROTO;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.utils.TimingsTraceAndSlog.SYSTEM_SERVER_TIMING_TAG;

import android.annotation.NonNull;
import android.annotation.StringRes;
import android.app.ActivityThread;
import android.app.AppCompatCallbacks;
import android.app.INotificationManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.res.Configuration;
import android.content.res.Resources.Theme;
import android.database.sqlite.SQLiteCompatibilityWalFlags;
import android.database.sqlite.SQLiteGlobal;
import android.hardware.display.DisplayManagerInternal;
import android.net.ConnectivityModuleConnector;
import android.net.ITetheringConnector;
import android.net.NetworkStackClient;
import android.os.BaseBundle;
import android.os.Binder;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.FactoryTest;
import android.os.FileUtils;
import android.os.IIncidentManager;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.Process;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.storage.IStorageManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.sysprop.VoldProperties;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Slog;
import android.util.StatsLog;
import android.view.WindowManager;
import android.view.contentcapture.ContentCaptureManager;

import com.android.internal.R;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.BinderInternal;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.EmergencyAffordanceManager;
import com.android.internal.widget.ILockSettings;
import com.android.server.am.ActivityManagerService;
import com.android.server.appbinding.AppBindingService;
import com.android.server.attention.AttentionManagerService;
import com.android.server.audio.AudioService;
import com.android.server.biometrics.AuthService;
import com.android.server.biometrics.BiometricService;
import com.android.server.biometrics.face.FaceService;
import com.android.server.biometrics.fingerprint.FingerprintService;
import com.android.server.biometrics.iris.IrisService;
import com.android.server.broadcastradio.BroadcastRadioService;
import com.android.server.camera.CameraServiceProxy;
import com.android.server.clipboard.ClipboardService;
import com.android.server.compat.PlatformCompat;
import com.android.server.compat.PlatformCompatNative;
import com.android.server.connectivity.IpConnectivityMetrics;
import com.android.server.contentcapture.ContentCaptureManagerInternal;
import com.android.server.coverage.CoverageService;
import com.android.server.devicepolicy.DevicePolicyManagerService;
import com.android.server.display.DisplayManagerService;
import com.android.server.display.color.ColorDisplayService;
import com.android.server.dreams.DreamManagerService;
import com.android.server.emergency.EmergencyAffordanceService;
import com.android.server.gpu.GpuService;
import com.android.server.hdmi.HdmiControlService;
import com.android.server.incident.IncidentCompanionService;
import com.android.server.incremental.IncrementalManagerService;
import com.android.server.input.InputManagerService;
import com.android.server.inputmethod.InputMethodManagerService;
import com.android.server.inputmethod.InputMethodSystemProperty;
import com.android.server.inputmethod.MultiClientInputMethodManagerService;
import com.android.server.integrity.AppIntegrityManagerService;
import com.android.server.lights.LightsService;
import com.android.server.media.MediaResourceMonitorService;
import com.android.server.media.MediaRouterService;
import com.android.server.media.MediaSessionService;
import com.android.server.media.projection.MediaProjectionManagerService;
import com.android.server.net.NetworkPolicyManagerService;
import com.android.server.net.NetworkStatsService;
import com.android.server.net.watchlist.NetworkWatchlistService;
import com.android.server.notification.NotificationManagerService;
import com.android.server.oemlock.OemLockService;
import com.android.server.om.OverlayManagerService;
import com.android.server.os.BugreportManagerService;
import com.android.server.os.DeviceIdentifiersPolicyService;
import com.android.server.os.SchedulingPolicyService;
import com.android.server.people.PeopleService;
import com.android.server.pm.BackgroundDexOptService;
import com.android.server.pm.CrossProfileAppsService;
import com.android.server.pm.DataLoaderManagerService;
import com.android.server.pm.DynamicCodeLoggingService;
import com.android.server.pm.Installer;
import com.android.server.pm.LauncherAppsService;
import com.android.server.pm.OtaDexoptService;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.ShortcutService;
import com.android.server.pm.UserManagerService;
import com.android.server.policy.PermissionPolicyService;
import com.android.server.policy.PhoneWindowManager;
import com.android.server.policy.role.LegacyRoleResolutionPolicy;
import com.android.server.power.PowerManagerService;
import com.android.server.power.ShutdownThread;
import com.android.server.power.ThermalManagerService;
import com.android.server.recoverysystem.RecoverySystemService;
import com.android.server.restrictions.RestrictionsManagerService;
import com.android.server.role.RoleManagerService;
import com.android.server.rollback.RollbackManagerService;
import com.android.server.security.FileIntegrityService;
import com.android.server.security.KeyAttestationApplicationIdProviderService;
import com.android.server.security.KeyChainSystemService;
import com.android.server.signedconfig.SignedConfigService;
import com.android.server.soundtrigger.SoundTriggerService;
import com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareService;
import com.android.server.statusbar.StatusBarManagerService;
import com.android.server.storage.DeviceStorageMonitorService;
import com.android.server.telecom.TelecomLoaderService;
import com.android.server.testharness.TestHarnessModeService;
import com.android.server.textclassifier.TextClassificationManagerService;
import com.android.server.textservices.TextServicesManagerService;
import com.android.server.trust.TrustManagerService;
import com.android.server.tv.TvInputManagerService;
import com.android.server.tv.TvRemoteService;
import com.android.server.twilight.TwilightService;
import com.android.server.uri.UriGrantsManagerService;
import com.android.server.usage.UsageStatsService;
import com.android.server.utils.TimingsTraceAndSlog;
import com.android.server.vr.VrManagerService;
import com.android.server.webkit.WebViewUpdateService;
import com.android.server.wm.ActivityTaskManagerService;
import com.android.server.wm.WindowManagerGlobalLock;
import com.android.server.wm.WindowManagerService;

import dalvik.system.VMRuntime;

import com.google.android.startop.iorap.IorapForwardingService;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Timer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

public final class SystemServer {

    private static final String TAG = "SystemServer";

    private static final String ENCRYPTING_STATE = "trigger_restart_min_framework";
    private static final String ENCRYPTED_STATE = "1";

    private static final long SNAPSHOT_INTERVAL = 60 * 60 * 1000; // 1hr

    // The earliest supported time.  We pick one day into 1970, to
    // give any timezone code room without going into negative time.
    private static final long EARLIEST_SUPPORTED_TIME = 86400 * 1000;

    private static final long SLOW_DISPATCH_THRESHOLD_MS = 100;
    private static final long SLOW_DELIVERY_THRESHOLD_MS = 200;

    /*
     * Implementation class names. TODO: Move them to a codegen class or load
     * them from the build system somehow.
     */
    private static final String BACKUP_MANAGER_SERVICE_CLASS =
            "com.android.server.backup.BackupManagerService$Lifecycle";
    private static final String APPWIDGET_SERVICE_CLASS =
            "com.android.server.appwidget.AppWidgetService";
    private static final String VOICE_RECOGNITION_MANAGER_SERVICE_CLASS =
            "com.android.server.voiceinteraction.VoiceInteractionManagerService";
    private static final String PRINT_MANAGER_SERVICE_CLASS =
            "com.android.server.print.PrintManagerService";
    private static final String COMPANION_DEVICE_MANAGER_SERVICE_CLASS =
            "com.android.server.companion.CompanionDeviceManagerService";
    private static final String STATS_COMPANION_APEX_PATH =
            "/apex/com.android.os.statsd/javalib/service-statsd.jar";
    private static final String STATS_COMPANION_LIFECYCLE_CLASS =
            "com.android.server.stats.StatsCompanion$Lifecycle";
    private static final String STATS_PULL_ATOM_SERVICE_CLASS =
            "com.android.server.stats.StatsPullAtomService";
    private static final String USB_SERVICE_CLASS =
            "com.android.server.usb.UsbService$Lifecycle";
    private static final String MIDI_SERVICE_CLASS =
            "com.android.server.midi.MidiService$Lifecycle";
    private static final String WIFI_APEX_SERVICE_JAR_PATH =
            "/apex/com.android.wifi/javalib/service-wifi.jar";
    private static final String WIFI_SERVICE_CLASS =
            "com.android.server.wifi.WifiService";
    private static final String WIFI_SCANNING_SERVICE_CLASS =
            "com.android.server.wifi.scanner.WifiScanningService";
    private static final String WIFI_RTT_SERVICE_CLASS =
            "com.android.server.wifi.rtt.RttService";
    private static final String WIFI_AWARE_SERVICE_CLASS =
            "com.android.server.wifi.aware.WifiAwareService";
    private static final String WIFI_P2P_SERVICE_CLASS =
            "com.android.server.wifi.p2p.WifiP2pService";
    private static final String LOWPAN_SERVICE_CLASS =
            "com.android.server.lowpan.LowpanService";
    private static final String ETHERNET_SERVICE_CLASS =
            "com.android.server.ethernet.EthernetService";
    private static final String JOB_SCHEDULER_SERVICE_CLASS =
            "com.android.server.job.JobSchedulerService";
    private static final String LOCK_SETTINGS_SERVICE_CLASS =
            "com.android.server.locksettings.LockSettingsService$Lifecycle";
    private static final String STORAGE_MANAGER_SERVICE_CLASS =
            "com.android.server.StorageManagerService$Lifecycle";
    private static final String STORAGE_STATS_SERVICE_CLASS =
            "com.android.server.usage.StorageStatsService$Lifecycle";
    private static final String SEARCH_MANAGER_SERVICE_CLASS =
            "com.android.server.search.SearchManagerService$Lifecycle";
    private static final String THERMAL_OBSERVER_CLASS =
            "com.google.android.clockwork.ThermalObserver";
    private static final String WEAR_CONNECTIVITY_SERVICE_CLASS =
            "com.android.clockwork.connectivity.WearConnectivityService";
    private static final String WEAR_POWER_SERVICE_CLASS =
            "com.android.clockwork.power.WearPowerService";
    private static final String WEAR_SIDEKICK_SERVICE_CLASS =
            "com.google.android.clockwork.sidekick.SidekickService";
    private static final String WEAR_DISPLAY_SERVICE_CLASS =
            "com.google.android.clockwork.display.WearDisplayService";
    private static final String WEAR_LEFTY_SERVICE_CLASS =
            "com.google.android.clockwork.lefty.WearLeftyService";
    private static final String WEAR_TIME_SERVICE_CLASS =
            "com.google.android.clockwork.time.WearTimeService";
    private static final String WEAR_GLOBAL_ACTIONS_SERVICE_CLASS =
            "com.android.clockwork.globalactions.GlobalActionsService";
    private static final String ACCOUNT_SERVICE_CLASS =
            "com.android.server.accounts.AccountManagerService$Lifecycle";
    private static final String CONTENT_SERVICE_CLASS =
            "com.android.server.content.ContentService$Lifecycle";
    private static final String WALLPAPER_SERVICE_CLASS =
            "com.android.server.wallpaper.WallpaperManagerService$Lifecycle";
    private static final String AUTO_FILL_MANAGER_SERVICE_CLASS =
            "com.android.server.autofill.AutofillManagerService";
    private static final String CONTENT_CAPTURE_MANAGER_SERVICE_CLASS =
            "com.android.server.contentcapture.ContentCaptureManagerService";
    private static final String SYSTEM_CAPTIONS_MANAGER_SERVICE_CLASS =
            "com.android.server.systemcaptions.SystemCaptionsManagerService";
    private static final String TIME_ZONE_RULES_MANAGER_SERVICE_CLASS =
            "com.android.server.timezone.RulesManagerService$Lifecycle";
    private static final String IOT_SERVICE_CLASS =
            "com.android.things.server.IoTSystemService";
    private static final String SLICE_MANAGER_SERVICE_CLASS =
            "com.android.server.slice.SliceManagerService$Lifecycle";
    private static final String CAR_SERVICE_HELPER_SERVICE_CLASS =
            "com.android.internal.car.CarServiceHelperService";
    private static final String TIME_DETECTOR_SERVICE_CLASS =
            "com.android.server.timedetector.TimeDetectorService$Lifecycle";
    private static final String TIME_ZONE_DETECTOR_SERVICE_CLASS =
            "com.android.server.timezonedetector.TimeZoneDetectorService$Lifecycle";
    private static final String ACCESSIBILITY_MANAGER_SERVICE_CLASS =
            "com.android.server.accessibility.AccessibilityManagerService$Lifecycle";
    private static final String ADB_SERVICE_CLASS =
            "com.android.server.adb.AdbService$Lifecycle";
    private static final String APP_PREDICTION_MANAGER_SERVICE_CLASS =
            "com.android.server.appprediction.AppPredictionManagerService";
    private static final String CONTENT_SUGGESTIONS_SERVICE_CLASS =
            "com.android.server.contentsuggestions.ContentSuggestionsManagerService";
    private static final String DEVICE_IDLE_CONTROLLER_CLASS =
            "com.android.server.DeviceIdleController";
    private static final String BLOB_STORE_MANAGER_SERVICE_CLASS =
            "com.android.server.blob.BlobStoreManagerService";
    private static final String APP_SEARCH_MANAGER_SERVICE_CLASS =
            "com.android.server.appsearch.AppSearchManagerService";
    private static final String PERSISTENT_DATA_BLOCK_PROP = "ro.frp.pst";

    private static final String UNCRYPT_PACKAGE_FILE = "/cache/recovery/uncrypt_file";
    private static final String BLOCK_MAP_FILE = "/cache/recovery/block.map";

    private static final String GSI_RUNNING_PROP = "ro.gsid.image_running";

    // maximum number of binder threads used for system_server
    // will be higher than the system default
    private static final int sMaxBinderThreads = 31;

    /**
     * Default theme used by the system context. This is used to style system-provided dialogs, such
     * as the Power Off dialog, and other visual content.
     */
    private static final int DEFAULT_SYSTEM_THEME =
            com.android.internal.R.style.Theme_DeviceDefault_System;

    private final int mFactoryTestMode;
    private Timer mProfilerSnapshotTimer;

    private Context mSystemContext;
    private SystemServiceManager mSystemServiceManager;

    // TODO: remove all of these references by improving dependency resolution and boot phases
    private PowerManagerService mPowerManagerService;
    private ActivityManagerService mActivityManagerService;
    private WindowManagerGlobalLock mWindowManagerGlobalLock;
    private WebViewUpdateService mWebViewUpdateService;
    private DisplayManagerService mDisplayManagerService;
    private PackageManagerService mPackageManagerService;
    private PackageManager mPackageManager;
    private ContentResolver mContentResolver;
    private EntropyMixer mEntropyMixer;
    private DataLoaderManagerService mDataLoaderManagerService;
    private IncrementalManagerService mIncrementalManagerService;

    private boolean mOnlyCore;
    private boolean mFirstBoot;
    private final int mStartCount;
    private final boolean mRuntimeRestart;
    private final long mRuntimeStartElapsedTime;
    private final long mRuntimeStartUptime;

    private static final String START_SENSOR_SERVICE = "StartSensorService";
    private static final String START_HIDL_SERVICES = "StartHidlServices";

    private static final String SYSPROP_START_COUNT = "sys.system_server.start_count";
    private static final String SYSPROP_START_ELAPSED = "sys.system_server.start_elapsed";
    private static final String SYSPROP_START_UPTIME = "sys.system_server.start_uptime";

    private Future<?> mSensorServiceStart;
    private Future<?> mZygotePreload;

    /**
     * Start the sensor service. This is a blocking call and can take time.
     */
    private static native void startSensorService();

    /**
     * Start all HIDL services that are run inside the system server. This may take some time.
     */
    private static native void startHidlServices();

    /**
     * Mark this process' heap as profileable. Only for debug builds.
     */
    private static native void initZygoteChildHeapProfiling();

    /**
     * The main entry point from zygote.
     */
    public static void main(String[] args) {
        new SystemServer().run();
    }

    public SystemServer() {
        // Check for factory test mode.
        mFactoryTestMode = FactoryTest.getMode();

        // Record process start information.
        // Note SYSPROP_START_COUNT will increment by *2* on a FDE device when it fully boots;
        // one for the password screen, second for the actual boot.
        mStartCount = SystemProperties.getInt(SYSPROP_START_COUNT, 0) + 1;
        mRuntimeStartElapsedTime = SystemClock.elapsedRealtime();
        mRuntimeStartUptime = SystemClock.uptimeMillis();

        // Remember if it's runtime restart(when sys.boot_completed is already set) or reboot
        // We don't use "mStartCount > 1" here because it'll be wrong on a FDE device.
        // TODO: mRuntimeRestart will *not* be set to true if the proccess crashes before
        // sys.boot_completed is set. Fix it.
        mRuntimeRestart = "1".equals(SystemProperties.get("sys.boot_completed"));
    }

    private void run() {
        TimingsTraceAndSlog t = new TimingsTraceAndSlog();
        try {
            t.traceBegin("InitBeforeStartServices");

            // Record the process start information in sys props.
            SystemProperties.set(SYSPROP_START_COUNT, String.valueOf(mStartCount));
            SystemProperties.set(SYSPROP_START_ELAPSED, String.valueOf(mRuntimeStartElapsedTime));
            SystemProperties.set(SYSPROP_START_UPTIME, String.valueOf(mRuntimeStartUptime));

            EventLog.writeEvent(EventLogTags.SYSTEM_SERVER_START,
                    mStartCount, mRuntimeStartUptime, mRuntimeStartElapsedTime);

            //
            // Default the timezone property to GMT if not set.
            //
            String timezoneProperty = SystemProperties.get("persist.sys.timezone");
            if (timezoneProperty == null || timezoneProperty.isEmpty()) {
                Slog.w(TAG, "Timezone not set; setting to GMT.");
                SystemProperties.set("persist.sys.timezone", "GMT");
            }

            // If the system has "persist.sys.language" and friends set, replace them with
            // "persist.sys.locale". Note that the default locale at this point is calculated
            // using the "-Duser.locale" command line flag. That flag is usually populated by
            // AndroidRuntime using the same set of system properties, but only the system_server
            // and system apps are allowed to set them.
            //
            // NOTE: Most changes made here will need an equivalent change to
            // core/jni/AndroidRuntime.cpp
            if (!SystemProperties.get("persist.sys.language").isEmpty()) {
                final String languageTag = Locale.getDefault().toLanguageTag();

                SystemProperties.set("persist.sys.locale", languageTag);
                SystemProperties.set("persist.sys.language", "");
                SystemProperties.set("persist.sys.country", "");
                SystemProperties.set("persist.sys.localevar", "");
            }

            // The system server should never make non-oneway calls
            Binder.setWarnOnBlocking(true);
            // The system server should always load safe labels
            PackageItemInfo.forceSafeLabels();

            // Default to FULL within the system server.
            SQLiteGlobal.sDefaultSyncMode = SQLiteGlobal.SYNC_MODE_FULL;

            // Deactivate SQLiteCompatibilityWalFlags until settings provider is initialized
            SQLiteCompatibilityWalFlags.init(null);

            // Here we go!
            Slog.i(TAG, "Entered the Android system server!");
            final long uptimeMillis = SystemClock.elapsedRealtime();
            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_SYSTEM_RUN, uptimeMillis);
            if (!mRuntimeRestart) {
                StatsLog.write(StatsLog.BOOT_TIME_EVENT_ELAPSED_TIME_REPORTED,
                        StatsLog.BOOT_TIME_EVENT_ELAPSED_TIME__EVENT__SYSTEM_SERVER_INIT_START,
                        uptimeMillis);
            }

            // In case the runtime switched since last boot (such as when
            // the old runtime was removed in an OTA), set the system
            // property so that it is in sync. We can't do this in
            // libnativehelper's JniInvocation::Init code where we already
            // had to fallback to a different runtime because it is
            // running as root and we need to be the system user to set
            // the property. http://b/11463182
            SystemProperties.set("persist.sys.dalvik.vm.lib.2", VMRuntime.getRuntime().vmLibrary());

            // Mmmmmm... more memory!
            VMRuntime.getRuntime().clearGrowthLimit();

            // Some devices rely on runtime fingerprint generation, so make sure
            // we've defined it before booting further.
            Build.ensureFingerprintProperty();

            // Within the system server, it is an error to access Environment paths without
            // explicitly specifying a user.
            Environment.setUserRequired(true);

            // Within the system server, any incoming Bundles should be defused
            // to avoid throwing BadParcelableException.
            BaseBundle.setShouldDefuse(true);

            // Within the system server, when parceling exceptions, include the stack trace
            Parcel.setStackTraceParceling(true);

            // Ensure binder calls into the system always run at foreground priority.
            BinderInternal.disableBackgroundScheduling(true);

            // Increase the number of binder threads in system_server
            BinderInternal.setMaxThreads(sMaxBinderThreads);

            // Prepare the main looper thread (this thread).
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_FOREGROUND);
            android.os.Process.setCanSelfBackground(false);
            Looper.prepareMainLooper();
            Looper.getMainLooper().setSlowLogThresholdMs(
                    SLOW_DISPATCH_THRESHOLD_MS, SLOW_DELIVERY_THRESHOLD_MS);

            // Initialize native services.
            System.loadLibrary("android_servers");

            // Debug builds - allow heap profiling.
            if (Build.IS_DEBUGGABLE) {
                initZygoteChildHeapProfiling();
            }

            // Check whether we failed to shut down last time we tried.
            // This call may not return.
            performPendingShutdown();

            // Initialize the system context.
            createSystemContext();

            // Call per-process mainline module initialization.
            ActivityThread.initializeMainlineModules();

            // Create the system service manager.
            mSystemServiceManager = new SystemServiceManager(mSystemContext);
            mSystemServiceManager.setStartInfo(mRuntimeRestart,
                    mRuntimeStartElapsedTime, mRuntimeStartUptime);
            LocalServices.addService(SystemServiceManager.class, mSystemServiceManager);
            // Prepare the thread pool for init tasks that can be parallelized
            SystemServerInitThreadPool.start();
            // Attach JVMTI agent if this is a debuggable build and the system property is set.
            if (Build.IS_DEBUGGABLE) {
                // Property is of the form "library_path=parameters".
                String jvmtiAgent = SystemProperties.get("persist.sys.dalvik.jvmtiagent");
                if (!jvmtiAgent.isEmpty()) {
                    int equalIndex = jvmtiAgent.indexOf('=');
                    String libraryPath = jvmtiAgent.substring(0, equalIndex);
                    String parameterList =
                            jvmtiAgent.substring(equalIndex + 1, jvmtiAgent.length());
                    // Attach the agent.
                    try {
                        Debug.attachJvmtiAgent(libraryPath, parameterList, null);
                    } catch (Exception e) {
                        Slog.e("System", "*************************************************");
                        Slog.e("System", "********** Failed to load jvmti plugin: " + jvmtiAgent);
                    }
                }
            }
        } finally {
            t.traceEnd();  // InitBeforeStartServices
        }

        // Start services.
        try {
            t.traceBegin("StartServices");
            startBootstrapServices(t);
            startCoreServices(t);
            startOtherServices(t);
            SystemServerInitThreadPool.shutdown();
        } catch (Throwable ex) {
            Slog.e("System", "******************************************");
            Slog.e("System", "************ Failure starting system services", ex);
            throw ex;
        } finally {
            t.traceEnd(); // StartServices
        }

        StrictMode.initVmDefaults(null);

        if (!mRuntimeRestart && !isFirstBootOrUpgrade()) {
            final long uptimeMillis = SystemClock.elapsedRealtime();
            StatsLog.write(StatsLog.BOOT_TIME_EVENT_ELAPSED_TIME_REPORTED,
                    StatsLog.BOOT_TIME_EVENT_ELAPSED_TIME__EVENT__SYSTEM_SERVER_READY,
                    uptimeMillis);
            final long maxUptimeMillis = 60 * 1000;
            if (uptimeMillis > maxUptimeMillis) {
                Slog.wtf(SYSTEM_SERVER_TIMING_TAG,
                        "SystemServer init took too long. uptimeMillis=" + uptimeMillis);
            }
        }

        // Diagnostic to ensure that the system is in a base healthy state. Done here as a common
        // non-zygote process.
        if (!VMRuntime.hasBootImageSpaces()) {
            Slog.wtf(TAG, "Runtime is not running with a boot image!");
        }

        // Loop forever.
        Looper.loop();
        throw new RuntimeException("Main thread loop unexpectedly exited");
    }

    private boolean isFirstBootOrUpgrade() {
        return mPackageManagerService.isFirstBoot() || mPackageManagerService.isDeviceUpgrading();
    }

    private void reportWtf(String msg, Throwable e) {
        Slog.w(TAG, "***********************************************");
        Slog.wtf(TAG, "BOOT FAILURE " + msg, e);
    }

    private void performPendingShutdown() {
        final String shutdownAction = SystemProperties.get(
                ShutdownThread.SHUTDOWN_ACTION_PROPERTY, "");
        if (shutdownAction != null && shutdownAction.length() > 0) {
            boolean reboot = (shutdownAction.charAt(0) == '1');

            final String reason;
            if (shutdownAction.length() > 1) {
                reason = shutdownAction.substring(1, shutdownAction.length());
            } else {
                reason = null;
            }

            // If it's a pending reboot into recovery to apply an update,
            // always make sure uncrypt gets executed properly when needed.
            // If '/cache/recovery/block.map' hasn't been created, stop the
            // reboot which will fail for sure, and get a chance to capture a
            // bugreport when that's still feasible. (Bug: 26444951)
            if (reason != null && reason.startsWith(PowerManager.REBOOT_RECOVERY_UPDATE)) {
                File packageFile = new File(UNCRYPT_PACKAGE_FILE);
                if (packageFile.exists()) {
                    String filename = null;
                    try {
                        filename = FileUtils.readTextFile(packageFile, 0, null);
                    } catch (IOException e) {
                        Slog.e(TAG, "Error reading uncrypt package file", e);
                    }

                    if (filename != null && filename.startsWith("/data")) {
                        if (!new File(BLOCK_MAP_FILE).exists()) {
                            Slog.e(TAG, "Can't find block map file, uncrypt failed or " +
                                    "unexpected runtime restart?");
                            return;
                        }
                    }
                }
            }
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    synchronized (this) {
                        ShutdownThread.rebootOrShutdown(null, reboot, reason);
                    }
                }
            };

            // ShutdownThread must run on a looper capable of displaying the UI.
            Message msg = Message.obtain(UiThread.getHandler(), runnable);
            msg.setAsynchronous(true);
            UiThread.getHandler().sendMessage(msg);

        }
    }

    private void createSystemContext() {
        ActivityThread activityThread = ActivityThread.systemMain();
        mSystemContext = activityThread.getSystemContext();
        mSystemContext.setTheme(DEFAULT_SYSTEM_THEME);

        final Context systemUiContext = activityThread.getSystemUiContext();
        systemUiContext.setTheme(DEFAULT_SYSTEM_THEME);
    }

    /**
     * Starts the small tangle of critical services that are needed to get the system off the
     * ground.  These services have complex mutual dependencies which is why we initialize them all
     * in one place here.  Unless your service is also entwined in these dependencies, it should be
     * initialized in one of the other functions.
     */
    private void startBootstrapServices(@NonNull TimingsTraceAndSlog t) {
        t.traceBegin("startBootstrapServices");

        // Start the watchdog as early as possible so we can crash the system server
        // if we deadlock during early boot
        t.traceBegin("StartWatchdog");
        final Watchdog watchdog = Watchdog.getInstance();
        watchdog.start();
        t.traceEnd();

        Slog.i(TAG, "Reading configuration...");
        final String TAG_SYSTEM_CONFIG = "ReadingSystemConfig";
        t.traceBegin(TAG_SYSTEM_CONFIG);
        SystemServerInitThreadPool.submit(SystemConfig::getInstance, TAG_SYSTEM_CONFIG);
        t.traceEnd();

        // Platform compat service is used by ActivityManagerService, PackageManagerService, and
        // possibly others in the future. b/135010838.
        t.traceBegin("PlatformCompat");
        PlatformCompat platformCompat = new PlatformCompat(mSystemContext);
        ServiceManager.addService(Context.PLATFORM_COMPAT_SERVICE, platformCompat);
        ServiceManager.addService(Context.PLATFORM_COMPAT_NATIVE_SERVICE,
                new PlatformCompatNative(platformCompat));
        AppCompatCallbacks.install(new long[0]);
        t.traceEnd();

        // FileIntegrityService responds to requests from apps and the system. It needs to run after
        // the source (i.e. keystore) is ready, and before the apps (or the first customer in the
        // system) run.
        t.traceBegin("StartFileIntegrityService");
        mSystemServiceManager.startService(FileIntegrityService.class);
        t.traceEnd();

        // Wait for installd to finish starting up so that it has a chance to
        // create critical directories such as /data/user with the appropriate
        // permissions.  We need this to complete before we initialize other services.
        t.traceBegin("StartInstaller");
        Installer installer = mSystemServiceManager.startService(Installer.class);
        t.traceEnd();

        // In some cases after launching an app we need to access device identifiers,
        // therefore register the device identifier policy before the activity manager.
        t.traceBegin("DeviceIdentifiersPolicyService");
        mSystemServiceManager.startService(DeviceIdentifiersPolicyService.class);
        t.traceEnd();

        // Uri Grants Manager.
        t.traceBegin("UriGrantsManagerService");
        mSystemServiceManager.startService(UriGrantsManagerService.Lifecycle.class);
        t.traceEnd();

        // Activity manager runs the show.
        t.traceBegin("StartActivityManager");
        // TODO: Might need to move after migration to WM.
        ActivityTaskManagerService atm = mSystemServiceManager.startService(
                ActivityTaskManagerService.Lifecycle.class).getService();
        mActivityManagerService = ActivityManagerService.Lifecycle.startService(
                mSystemServiceManager, atm);
        mActivityManagerService.setSystemServiceManager(mSystemServiceManager);
        mActivityManagerService.setInstaller(installer);
        mWindowManagerGlobalLock = atm.getGlobalLock();
        t.traceEnd();

        // Data loader manager service needs to be started before package manager
        t.traceBegin("StartDataLoaderManagerService");
        mDataLoaderManagerService = mSystemServiceManager.startService(
                DataLoaderManagerService.class);
        t.traceEnd();

        // Incremental service needs to be started before package manager
        t.traceBegin("StartIncrementalManagerService");
        mIncrementalManagerService = IncrementalManagerService.start(mSystemContext);
        t.traceEnd();

        // Power manager needs to be started early because other services need it.
        // Native daemons may be watching for it to be registered so it must be ready
        // to handle incoming binder calls immediately (including being able to verify
        // the permissions for those calls).
        t.traceBegin("StartPowerManager");
        mPowerManagerService = mSystemServiceManager.startService(PowerManagerService.class);
        t.traceEnd();

        t.traceBegin("StartThermalManager");
        mSystemServiceManager.startService(ThermalManagerService.class);
        t.traceEnd();

        // Now that the power manager has been started, let the activity manager
        // initialize power management features.
        t.traceBegin("InitPowerManagement");
        mActivityManagerService.initPowerManagement();
        t.traceEnd();

        // Bring up recovery system in case a rescue party needs a reboot
        t.traceBegin("StartRecoverySystemService");
        mSystemServiceManager.startService(RecoverySystemService.Lifecycle.class);
        t.traceEnd();

        // Now that we have the bare essentials of the OS up and running, take
        // note that we just booted, which might send out a rescue party if
        // we're stuck in a runtime restart loop.
        RescueParty.registerHealthObserver(mSystemContext);
        PackageWatchdog.getInstance(mSystemContext).noteBoot();

        // Manages LEDs and display backlight so we need it to bring up the display.
        t.traceBegin("StartLightsService");
        mSystemServiceManager.startService(LightsService.class);
        t.traceEnd();

        t.traceBegin("StartSidekickService");
        // Package manager isn't started yet; need to use SysProp not hardware feature
        if (SystemProperties.getBoolean("config.enable_sidekick_graphics", false)) {
            mSystemServiceManager.startService(WEAR_SIDEKICK_SERVICE_CLASS);
        }
        t.traceEnd();

        // Display manager is needed to provide display metrics before package manager
        // starts up.
        t.traceBegin("StartDisplayManager");
        mDisplayManagerService = mSystemServiceManager.startService(DisplayManagerService.class);
        t.traceEnd();

        // We need the default display before we can initialize the package manager.
        t.traceBegin("WaitForDisplay");
        mSystemServiceManager.startBootPhase(t, SystemService.PHASE_WAIT_FOR_DEFAULT_DISPLAY);
        t.traceEnd();

        // Only run "core" apps if we're encrypting the device.
        String cryptState = VoldProperties.decrypt().orElse("");
        if (ENCRYPTING_STATE.equals(cryptState)) {
            Slog.w(TAG, "Detected encryption in progress - only parsing core apps");
            mOnlyCore = true;
        } else if (ENCRYPTED_STATE.equals(cryptState)) {
            Slog.w(TAG, "Device encrypted - only parsing core apps");
            mOnlyCore = true;
        }

        // Start the package manager.
        if (!mRuntimeRestart) {
            StatsLog.write(StatsLog.BOOT_TIME_EVENT_ELAPSED_TIME_REPORTED,
                    StatsLog.BOOT_TIME_EVENT_ELAPSED_TIME__EVENT__PACKAGE_MANAGER_INIT_START,
                    SystemClock.elapsedRealtime());
        }

        t.traceBegin("StartPackageManagerService");
        try {
            Watchdog.getInstance().pauseWatchingCurrentThread("packagemanagermain");
            mPackageManagerService = PackageManagerService.main(mSystemContext, installer,
                    mFactoryTestMode != FactoryTest.FACTORY_TEST_OFF, mOnlyCore);
        } finally {
            Watchdog.getInstance().resumeWatchingCurrentThread("packagemanagermain");
        }

        mFirstBoot = mPackageManagerService.isFirstBoot();
        mPackageManager = mSystemContext.getPackageManager();
        t.traceEnd();
        if (!mRuntimeRestart && !isFirstBootOrUpgrade()) {
            StatsLog.write(StatsLog.BOOT_TIME_EVENT_ELAPSED_TIME_REPORTED,
                    StatsLog.BOOT_TIME_EVENT_ELAPSED_TIME__EVENT__PACKAGE_MANAGER_INIT_READY,
                    SystemClock.elapsedRealtime());
        }
        // Manages A/B OTA dexopting. This is a bootstrap service as we need it to rename
        // A/B artifacts after boot, before anything else might touch/need them.
        // Note: this isn't needed during decryption (we don't have /data anyways).
        if (!mOnlyCore) {
            boolean disableOtaDexopt = SystemProperties.getBoolean("config.disable_otadexopt",
                    false);
            if (!disableOtaDexopt) {
                t.traceBegin("StartOtaDexOptService");
                try {
                    Watchdog.getInstance().pauseWatchingCurrentThread("moveab");
                    OtaDexoptService.main(mSystemContext, mPackageManagerService);
                } catch (Throwable e) {
                    reportWtf("starting OtaDexOptService", e);
                } finally {
                    Watchdog.getInstance().resumeWatchingCurrentThread("moveab");
                    t.traceEnd();
                }
            }
        }

        t.traceBegin("StartUserManagerService");
        mSystemServiceManager.startService(UserManagerService.LifeCycle.class);
        t.traceEnd();

        // Initialize attribute cache used to cache resources from packages.
        t.traceBegin("InitAttributerCache");
        AttributeCache.init(mSystemContext);
        t.traceEnd();

        // Set up the Application instance for the system process and get started.
        t.traceBegin("SetSystemProcess");
        mActivityManagerService.setSystemProcess();
        t.traceEnd();

        // Complete the watchdog setup with an ActivityManager instance and listen for reboots
        // Do this only after the ActivityManagerService is properly started as a system process
        t.traceBegin("InitWatchdog");
        watchdog.init(mSystemContext, mActivityManagerService);
        t.traceEnd();

        // DisplayManagerService needs to setup android.display scheduling related policies
        // since setSystemProcess() would have overridden policies due to setProcessGroup
        mDisplayManagerService.setupSchedulerPolicies();

        // Manages Overlay packages
        t.traceBegin("StartOverlayManagerService");
        mSystemServiceManager.startService(new OverlayManagerService(mSystemContext));
        t.traceEnd();

        t.traceBegin("StartSensorPrivacyService");
        mSystemServiceManager.startService(new SensorPrivacyService(mSystemContext));
        t.traceEnd();

        if (SystemProperties.getInt("persist.sys.displayinset.top", 0) > 0) {
            // DisplayManager needs the overlay immediately.
            mActivityManagerService.updateSystemUiContext();
            LocalServices.getService(DisplayManagerInternal.class).onOverlayChanged();
        }

        // The sensor service needs access to package manager service, app ops
        // service, and permissions service, therefore we start it after them.
        // Start sensor service in a separate thread. Completion should be checked
        // before using it.
        mSensorServiceStart = SystemServerInitThreadPool.submit(() -> {
            TimingsTraceAndSlog traceLog = TimingsTraceAndSlog.newAsyncLog();
            traceLog.traceBegin(START_SENSOR_SERVICE);
            startSensorService();
            traceLog.traceEnd();
        }, START_SENSOR_SERVICE);

        t.traceEnd(); // startBootstrapServices
    }

    /**
     * Starts some essential services that are not tangled up in the bootstrap process.
     */
    private void startCoreServices(@NonNull TimingsTraceAndSlog t) {
        t.traceBegin("startCoreServices");

        t.traceBegin("StartBatteryService");
        // Tracks the battery level.  Requires LightService.
        mSystemServiceManager.startService(BatteryService.class);
        t.traceEnd();

        // Tracks application usage stats.
        t.traceBegin("StartUsageService");
        mSystemServiceManager.startService(UsageStatsService.class);
        mActivityManagerService.setUsageStatsManager(
                LocalServices.getService(UsageStatsManagerInternal.class));
        t.traceEnd();

        // Tracks whether the updatable WebView is in a ready state and watches for update installs.
        if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_WEBVIEW)) {
            t.traceBegin("StartWebViewUpdateService");
            mWebViewUpdateService = mSystemServiceManager.startService(WebViewUpdateService.class);
            t.traceEnd();
        }

        // Tracks and caches the device state.
        t.traceBegin("StartCachedDeviceStateService");
        mSystemServiceManager.startService(CachedDeviceStateService.class);
        t.traceEnd();

        // Tracks cpu time spent in binder calls
        t.traceBegin("StartBinderCallsStatsService");
        mSystemServiceManager.startService(BinderCallsStatsService.LifeCycle.class);
        t.traceEnd();

        // Tracks time spent in handling messages in handlers.
        t.traceBegin("StartLooperStatsService");
        mSystemServiceManager.startService(LooperStatsService.Lifecycle.class);
        t.traceEnd();

        // Manages apk rollbacks.
        t.traceBegin("StartRollbackManagerService");
        mSystemServiceManager.startService(RollbackManagerService.class);
        t.traceEnd();

        // Service to capture bugreports.
        t.traceBegin("StartBugreportManagerService");
        mSystemServiceManager.startService(BugreportManagerService.class);
        t.traceEnd();

        // Serivce for GPU and GPU driver.
        t.traceBegin("GpuService");
        mSystemServiceManager.startService(GpuService.class);
        t.traceEnd();

        t.traceEnd(); // startCoreServices
    }

    /**
     * Starts a miscellaneous grab bag of stuff that has yet to be refactored and organized.
     */
    private void startOtherServices(@NonNull TimingsTraceAndSlog t) {
        t.traceBegin("startOtherServices");

        final Context context = mSystemContext;
        VibratorService vibrator = null;
        DynamicSystemService dynamicSystem = null;
        IStorageManager storageManager = null;
        NetworkManagementService networkManagement = null;
        IpSecService ipSecService = null;
        NetworkStatsService networkStats = null;
        NetworkPolicyManagerService networkPolicy = null;
        ConnectivityService connectivity = null;
        NsdService serviceDiscovery = null;
        WindowManagerService wm = null;
        SerialService serial = null;
        NetworkTimeUpdateService networkTimeUpdater = null;
        InputManagerService inputManager = null;
        TelephonyRegistry telephonyRegistry = null;
        ConsumerIrService consumerIr = null;
        MmsServiceBroker mmsService = null;
        HardwarePropertiesManagerService hardwarePropertiesService = null;

        boolean disableSystemTextClassifier = SystemProperties.getBoolean(
                "config.disable_systemtextclassifier", false);

        boolean disableNetworkTime = SystemProperties.getBoolean("config.disable_networktime",
                false);
        boolean disableCameraService = SystemProperties.getBoolean("config.disable_cameraservice",
                false);
        boolean enableLeftyService = SystemProperties.getBoolean("config.enable_lefty", false);

        boolean isEmulator = SystemProperties.get("ro.kernel.qemu").equals("1");

        boolean isWatch = context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WATCH);

        boolean isArc = context.getPackageManager().hasSystemFeature(
                "org.chromium.arc");

        boolean enableVrService = context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_VR_MODE_HIGH_PERFORMANCE);

        // For debugging RescueParty
        if (Build.IS_DEBUGGABLE && SystemProperties.getBoolean("debug.crash_system", false)) {
            throw new RuntimeException();
        }

        try {
            final String SECONDARY_ZYGOTE_PRELOAD = "SecondaryZygotePreload";
            // We start the preload ~1s before the webview factory preparation, to
            // ensure that it completes before the 32 bit relro process is forked
            // from the zygote. In the event that it takes too long, the webview
            // RELRO process will block, but it will do so without holding any locks.
            mZygotePreload = SystemServerInitThreadPool.submit(() -> {
                try {
                    Slog.i(TAG, SECONDARY_ZYGOTE_PRELOAD);
                    TimingsTraceAndSlog traceLog = TimingsTraceAndSlog.newAsyncLog();
                    traceLog.traceBegin(SECONDARY_ZYGOTE_PRELOAD);
                    if (!Process.ZYGOTE_PROCESS.preloadDefault(Build.SUPPORTED_32_BIT_ABIS[0])) {
                        Slog.e(TAG, "Unable to preload default resources");
                    }
                    traceLog.traceEnd();
                } catch (Exception ex) {
                    Slog.e(TAG, "Exception preloading default resources", ex);
                }
            }, SECONDARY_ZYGOTE_PRELOAD);

            t.traceBegin("StartKeyAttestationApplicationIdProviderService");
            ServiceManager.addService("sec_key_att_app_id_provider",
                    new KeyAttestationApplicationIdProviderService(context));
            t.traceEnd();

            t.traceBegin("StartKeyChainSystemService");
            mSystemServiceManager.startService(KeyChainSystemService.class);
            t.traceEnd();

            t.traceBegin("StartSchedulingPolicyService");
            ServiceManager.addService("scheduling_policy", new SchedulingPolicyService());
            t.traceEnd();

            t.traceBegin("StartTelecomLoaderService");
            mSystemServiceManager.startService(TelecomLoaderService.class);
            t.traceEnd();

            t.traceBegin("StartTelephonyRegistry");
            telephonyRegistry = new TelephonyRegistry(context);
            ServiceManager.addService("telephony.registry", telephonyRegistry);
            t.traceEnd();

            t.traceBegin("StartEntropyMixer");
            mEntropyMixer = new EntropyMixer(context);
            t.traceEnd();

            mContentResolver = context.getContentResolver();

            // The AccountManager must come before the ContentService
            t.traceBegin("StartAccountManagerService");
            mSystemServiceManager.startService(ACCOUNT_SERVICE_CLASS);
            t.traceEnd();

            t.traceBegin("StartContentService");
            mSystemServiceManager.startService(CONTENT_SERVICE_CLASS);
            t.traceEnd();

            t.traceBegin("InstallSystemProviders");
            mActivityManagerService.installSystemProviders();
            // Now that SettingsProvider is ready, reactivate SQLiteCompatibilityWalFlags
            SQLiteCompatibilityWalFlags.reset();
            t.traceEnd();

            // Records errors and logs, for example wtf()
            // Currently this service indirectly depends on SettingsProvider so do this after
            // InstallSystemProviders.
            t.traceBegin("StartDropBoxManager");
            mSystemServiceManager.startService(DropBoxManagerService.class);
            t.traceEnd();

            t.traceBegin("StartVibratorService");
            vibrator = new VibratorService(context);
            ServiceManager.addService("vibrator", vibrator);
            t.traceEnd();

            t.traceBegin("StartDynamicSystemService");
            dynamicSystem = new DynamicSystemService(context);
            ServiceManager.addService("dynamic_system", dynamicSystem);
            t.traceEnd();

            if (!isWatch) {
                t.traceBegin("StartConsumerIrService");
                consumerIr = new ConsumerIrService(context);
                ServiceManager.addService(Context.CONSUMER_IR_SERVICE, consumerIr);
                t.traceEnd();
            }

            t.traceBegin("StartAlarmManagerService");
            mSystemServiceManager.startService(new AlarmManagerService(context));
            t.traceEnd();

            t.traceBegin("StartInputManagerService");
            inputManager = new InputManagerService(context);
            t.traceEnd();

            t.traceBegin("StartWindowManagerService");
            // WMS needs sensor service ready
            ConcurrentUtils.waitForFutureNoInterrupt(mSensorServiceStart, START_SENSOR_SERVICE);
            mSensorServiceStart = null;
            wm = WindowManagerService.main(context, inputManager, !mFirstBoot, mOnlyCore,
                    new PhoneWindowManager(), mActivityManagerService.mActivityTaskManager);
            ServiceManager.addService(Context.WINDOW_SERVICE, wm, /* allowIsolated= */ false,
                    DUMP_FLAG_PRIORITY_CRITICAL | DUMP_FLAG_PROTO);
            ServiceManager.addService(Context.INPUT_SERVICE, inputManager,
                    /* allowIsolated= */ false, DUMP_FLAG_PRIORITY_CRITICAL);
            t.traceEnd();

            t.traceBegin("SetWindowManagerService");
            mActivityManagerService.setWindowManager(wm);
            t.traceEnd();

            t.traceBegin("WindowManagerServiceOnInitReady");
            wm.onInitReady();
            t.traceEnd();

            // Start receiving calls from HIDL services. Start in in a separate thread
            // because it need to connect to SensorManager. This have to start
            // after START_SENSOR_SERVICE is done.
            SystemServerInitThreadPool.submit(() -> {
                TimingsTraceAndSlog traceLog = TimingsTraceAndSlog.newAsyncLog();
                traceLog.traceBegin(START_HIDL_SERVICES);
                startHidlServices();
                traceLog.traceEnd();
            }, START_HIDL_SERVICES);

            if (!isWatch && enableVrService) {
                t.traceBegin("StartVrManagerService");
                mSystemServiceManager.startService(VrManagerService.class);
                t.traceEnd();
            }

            t.traceBegin("StartInputManager");
            inputManager.setWindowManagerCallbacks(wm.getInputManagerCallback());
            inputManager.start();
            t.traceEnd();

            // TODO: Use service dependencies instead.
            t.traceBegin("DisplayManagerWindowManagerAndInputReady");
            mDisplayManagerService.windowManagerAndInputReady();
            t.traceEnd();

            if (mFactoryTestMode == FactoryTest.FACTORY_TEST_LOW_LEVEL) {
                Slog.i(TAG, "No Bluetooth Service (factory test)");
            } else if (!context.getPackageManager().hasSystemFeature
                    (PackageManager.FEATURE_BLUETOOTH)) {
                Slog.i(TAG, "No Bluetooth Service (Bluetooth Hardware Not Present)");
            } else {
                t.traceBegin("StartBluetoothService");
                mSystemServiceManager.startService(BluetoothService.class);
                t.traceEnd();
            }

            t.traceBegin("IpConnectivityMetrics");
            mSystemServiceManager.startService(IpConnectivityMetrics.class);
            t.traceEnd();

            t.traceBegin("NetworkWatchlistService");
            mSystemServiceManager.startService(NetworkWatchlistService.Lifecycle.class);
            t.traceEnd();

            t.traceBegin("PinnerService");
            mSystemServiceManager.startService(PinnerService.class);
            t.traceEnd();

            t.traceBegin("IorapForwardingService");
            mSystemServiceManager.startService(IorapForwardingService.class);
            t.traceEnd();

            t.traceBegin("SignedConfigService");
            SignedConfigService.registerUpdateReceiver(mSystemContext);
            t.traceEnd();

            t.traceBegin("AppIntegrityService");
            mSystemServiceManager.startService(AppIntegrityManagerService.class);
            t.traceEnd();

        } catch (Throwable e) {
            Slog.e("System", "******************************************");
            Slog.e("System", "************ Failure starting core service");
            throw e;
        }

        // Before things start rolling, be sure we have decided whether
        // we are in safe mode.
        final boolean safeMode = wm.detectSafeMode();
        if (safeMode) {
            // If yes, immediately turn on the global setting for airplane mode.
            // Note that this does not send broadcasts at this stage because
            // subsystems are not yet up. We will send broadcasts later to ensure
            // all listeners have the chance to react with special handling.
            Settings.Global.putInt(context.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 1);
        }

        StatusBarManagerService statusBar = null;
        INotificationManager notification = null;
        CountryDetectorService countryDetector = null;
        ILockSettings lockSettings = null;
        MediaRouterService mediaRouter = null;

        // Bring up services needed for UI.
        if (mFactoryTestMode != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
            t.traceBegin("StartInputMethodManagerLifecycle");
            if (InputMethodSystemProperty.MULTI_CLIENT_IME_ENABLED) {
                mSystemServiceManager.startService(
                        MultiClientInputMethodManagerService.Lifecycle.class);
            } else {
                mSystemServiceManager.startService(InputMethodManagerService.Lifecycle.class);
            }
            t.traceEnd();

            t.traceBegin("StartAccessibilityManagerService");
            try {
                mSystemServiceManager.startService(ACCESSIBILITY_MANAGER_SERVICE_CLASS);
            } catch (Throwable e) {
                reportWtf("starting Accessibility Manager", e);
            }
            t.traceEnd();
        }

        t.traceBegin("MakeDisplayReady");
        try {
            wm.displayReady();
        } catch (Throwable e) {
            reportWtf("making display ready", e);
        }
        t.traceEnd();

        if (mFactoryTestMode != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
            if (!"0".equals(SystemProperties.get("system_init.startmountservice"))) {
                t.traceBegin("StartStorageManagerService");
                try {
                    /*
                     * NotificationManagerService is dependant on StorageManagerService,
                     * (for media / usb notifications) so we must start StorageManagerService first.
                     */
                    mSystemServiceManager.startService(STORAGE_MANAGER_SERVICE_CLASS);
                    storageManager = IStorageManager.Stub.asInterface(
                            ServiceManager.getService("mount"));
                } catch (Throwable e) {
                    reportWtf("starting StorageManagerService", e);
                }
                t.traceEnd();

                t.traceBegin("StartStorageStatsService");
                try {
                    mSystemServiceManager.startService(STORAGE_STATS_SERVICE_CLASS);
                } catch (Throwable e) {
                    reportWtf("starting StorageStatsService", e);
                }
                t.traceEnd();
            }
        }

        // We start this here so that we update our configuration to set watch or television
        // as appropriate.
        t.traceBegin("StartUiModeManager");
        mSystemServiceManager.startService(UiModeManagerService.class);
        t.traceEnd();

        if (!mOnlyCore) {
            t.traceBegin("UpdatePackagesIfNeeded");
            try {
                Watchdog.getInstance().pauseWatchingCurrentThread("dexopt");
                mPackageManagerService.updatePackagesIfNeeded();
            } catch (Throwable e) {
                reportWtf("update packages", e);
            } finally {
                Watchdog.getInstance().resumeWatchingCurrentThread("dexopt");
            }
            t.traceEnd();
        }

        t.traceBegin("PerformFstrimIfNeeded");
        try {
            mPackageManagerService.performFstrimIfNeeded();
        } catch (Throwable e) {
            reportWtf("performing fstrim", e);
        }
        t.traceEnd();

        if (mFactoryTestMode != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
            t.traceBegin("StartLockSettingsService");
            try {
                mSystemServiceManager.startService(LOCK_SETTINGS_SERVICE_CLASS);
                lockSettings = ILockSettings.Stub.asInterface(
                        ServiceManager.getService("lock_settings"));
            } catch (Throwable e) {
                reportWtf("starting LockSettingsService service", e);
            }
            t.traceEnd();

            final boolean hasPdb = !SystemProperties.get(PERSISTENT_DATA_BLOCK_PROP).equals("");
            final boolean hasGsi = SystemProperties.getInt(GSI_RUNNING_PROP, 0) > 0;
            if (hasPdb && !hasGsi) {
                t.traceBegin("StartPersistentDataBlock");
                mSystemServiceManager.startService(PersistentDataBlockService.class);
                t.traceEnd();
            }

            t.traceBegin("StartTestHarnessMode");
            mSystemServiceManager.startService(TestHarnessModeService.class);
            t.traceEnd();

            if (hasPdb || OemLockService.isHalPresent()) {
                // Implementation depends on pdb or the OemLock HAL
                t.traceBegin("StartOemLockService");
                mSystemServiceManager.startService(OemLockService.class);
                t.traceEnd();
            }

            t.traceBegin("StartDeviceIdleController");
            mSystemServiceManager.startService(DEVICE_IDLE_CONTROLLER_CLASS);
            t.traceEnd();

            // Always start the Device Policy Manager, so that the API is compatible with
            // API8.
            t.traceBegin("StartDevicePolicyManager");
            mSystemServiceManager.startService(DevicePolicyManagerService.Lifecycle.class);
            t.traceEnd();

            if (!isWatch) {
                t.traceBegin("StartStatusBarManagerService");
                try {
                    statusBar = new StatusBarManagerService(context, wm);
                    ServiceManager.addService(Context.STATUS_BAR_SERVICE, statusBar);
                } catch (Throwable e) {
                    reportWtf("starting StatusBarManagerService", e);
                }
                t.traceEnd();
            }

            startContentCaptureService(context, t);
            startAttentionService(context, t);

            startSystemCaptionsManagerService(context, t);

            // App prediction manager service
            if (deviceHasConfigString(context, R.string.config_defaultAppPredictionService)) {
                t.traceBegin("StartAppPredictionService");
                mSystemServiceManager.startService(APP_PREDICTION_MANAGER_SERVICE_CLASS);
                t.traceEnd();
            } else {
                Slog.d(TAG, "AppPredictionService not defined by OEM");
            }

            // Content suggestions manager service
            if (deviceHasConfigString(context, R.string.config_defaultContentSuggestionsService)) {
                t.traceBegin("StartContentSuggestionsService");
                mSystemServiceManager.startService(CONTENT_SUGGESTIONS_SERVICE_CLASS);
                t.traceEnd();
            } else {
                Slog.d(TAG, "ContentSuggestionsService not defined by OEM");
            }

            t.traceBegin("InitConnectivityModuleConnector");
            try {
                ConnectivityModuleConnector.getInstance().init(context);
            } catch (Throwable e) {
                reportWtf("initializing ConnectivityModuleConnector", e);
            }
            t.traceEnd();

            t.traceBegin("InitNetworkStackClient");
            try {
                NetworkStackClient.getInstance().init();
            } catch (Throwable e) {
                reportWtf("initializing NetworkStackClient", e);
            }
            t.traceEnd();

            t.traceBegin("StartNetworkManagementService");
            try {
                networkManagement = NetworkManagementService.create(context);
                ServiceManager.addService(Context.NETWORKMANAGEMENT_SERVICE, networkManagement);
            } catch (Throwable e) {
                reportWtf("starting NetworkManagement Service", e);
            }
            t.traceEnd();


            t.traceBegin("StartIpSecService");
            try {
                ipSecService = IpSecService.create(context);
                ServiceManager.addService(Context.IPSEC_SERVICE, ipSecService);
            } catch (Throwable e) {
                reportWtf("starting IpSec Service", e);
            }
            t.traceEnd();

            t.traceBegin("StartTextServicesManager");
            mSystemServiceManager.startService(TextServicesManagerService.Lifecycle.class);
            t.traceEnd();

            if (!disableSystemTextClassifier) {
                t.traceBegin("StartTextClassificationManagerService");
                mSystemServiceManager
                        .startService(TextClassificationManagerService.Lifecycle.class);
                t.traceEnd();
            }

            t.traceBegin("StartNetworkScoreService");
            mSystemServiceManager.startService(NetworkScoreService.Lifecycle.class);
            t.traceEnd();

            t.traceBegin("StartNetworkStatsService");
            try {
                networkStats = NetworkStatsService.create(context, networkManagement);
                ServiceManager.addService(Context.NETWORK_STATS_SERVICE, networkStats);
            } catch (Throwable e) {
                reportWtf("starting NetworkStats Service", e);
            }
            t.traceEnd();

            t.traceBegin("StartNetworkPolicyManagerService");
            try {
                networkPolicy = new NetworkPolicyManagerService(context, mActivityManagerService,
                        networkManagement);
                ServiceManager.addService(Context.NETWORK_POLICY_SERVICE, networkPolicy);
            } catch (Throwable e) {
                reportWtf("starting NetworkPolicy Service", e);
            }
            t.traceEnd();

            if (context.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_WIFI)) {
                // Wifi Service must be started first for wifi-related services.
                t.traceBegin("StartWifi");
                mSystemServiceManager.startServiceFromJar(
                        WIFI_SERVICE_CLASS, WIFI_APEX_SERVICE_JAR_PATH);
                t.traceEnd();
                t.traceBegin("StartWifiScanning");
                mSystemServiceManager.startServiceFromJar(
                        WIFI_SCANNING_SERVICE_CLASS, WIFI_APEX_SERVICE_JAR_PATH);
                t.traceEnd();
            }

            if (context.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_WIFI_RTT)) {
                t.traceBegin("StartRttService");
                mSystemServiceManager.startServiceFromJar(
                        WIFI_RTT_SERVICE_CLASS, WIFI_APEX_SERVICE_JAR_PATH);
                t.traceEnd();
            }

            if (context.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_WIFI_AWARE)) {
                t.traceBegin("StartWifiAware");
                mSystemServiceManager.startServiceFromJar(
                        WIFI_AWARE_SERVICE_CLASS, WIFI_APEX_SERVICE_JAR_PATH);
                t.traceEnd();
            }

            if (context.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_WIFI_DIRECT)) {
                t.traceBegin("StartWifiP2P");
                mSystemServiceManager.startServiceFromJar(
                        WIFI_P2P_SERVICE_CLASS, WIFI_APEX_SERVICE_JAR_PATH);
                t.traceEnd();
            }

            if (context.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_LOWPAN)) {
                t.traceBegin("StartLowpan");
                mSystemServiceManager.startService(LOWPAN_SERVICE_CLASS);
                t.traceEnd();
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_ETHERNET) ||
                    mPackageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                t.traceBegin("StartEthernet");
                mSystemServiceManager.startService(ETHERNET_SERVICE_CLASS);
                t.traceEnd();
            }

            t.traceBegin("StartConnectivityService");
            try {
                connectivity = new ConnectivityService(
                        context, networkManagement, networkStats, networkPolicy);
                ServiceManager.addService(Context.CONNECTIVITY_SERVICE, connectivity,
                        /* allowIsolated= */ false,
                        DUMP_FLAG_PRIORITY_HIGH | DUMP_FLAG_PRIORITY_NORMAL);
                networkPolicy.bindConnectivityManager(connectivity);
            } catch (Throwable e) {
                reportWtf("starting Connectivity Service", e);
            }
            t.traceEnd();

            t.traceBegin("StartNsdService");
            try {
                serviceDiscovery = NsdService.create(context);
                ServiceManager.addService(
                        Context.NSD_SERVICE, serviceDiscovery);
            } catch (Throwable e) {
                reportWtf("starting Service Discovery Service", e);
            }
            t.traceEnd();

            t.traceBegin("StartSystemUpdateManagerService");
            try {
                ServiceManager.addService(Context.SYSTEM_UPDATE_SERVICE,
                        new SystemUpdateManagerService(context));
            } catch (Throwable e) {
                reportWtf("starting SystemUpdateManagerService", e);
            }
            t.traceEnd();

            t.traceBegin("StartUpdateLockService");
            try {
                ServiceManager.addService(Context.UPDATE_LOCK_SERVICE,
                        new UpdateLockService(context));
            } catch (Throwable e) {
                reportWtf("starting UpdateLockService", e);
            }
            t.traceEnd();

            t.traceBegin("StartNotificationManager");
            mSystemServiceManager.startService(NotificationManagerService.class);
            SystemNotificationChannels.removeDeprecated(context);
            SystemNotificationChannels.createAll(context);
            notification = INotificationManager.Stub.asInterface(
                    ServiceManager.getService(Context.NOTIFICATION_SERVICE));
            t.traceEnd();

            t.traceBegin("StartDeviceMonitor");
            mSystemServiceManager.startService(DeviceStorageMonitorService.class);
            t.traceEnd();

            t.traceBegin("StartLocationManagerService");
            mSystemServiceManager.startService(LocationManagerService.Lifecycle.class);
            t.traceEnd();

            t.traceBegin("StartCountryDetectorService");
            try {
                countryDetector = new CountryDetectorService(context);
                ServiceManager.addService(Context.COUNTRY_DETECTOR, countryDetector);
            } catch (Throwable e) {
                reportWtf("starting Country Detector", e);
            }
            t.traceEnd();

            t.traceBegin("StartTimeDetectorService");
            try {
                mSystemServiceManager.startService(TIME_DETECTOR_SERVICE_CLASS);
            } catch (Throwable e) {
                reportWtf("starting StartTimeDetectorService service", e);
            }
            t.traceEnd();

            t.traceBegin("StartTimeZoneDetectorService");
            try {
                mSystemServiceManager.startService(TIME_ZONE_DETECTOR_SERVICE_CLASS);
            } catch (Throwable e) {
                reportWtf("starting StartTimeZoneDetectorService service", e);
            }
            t.traceEnd();

            if (!isWatch) {
                t.traceBegin("StartSearchManagerService");
                try {
                    mSystemServiceManager.startService(SEARCH_MANAGER_SERVICE_CLASS);
                } catch (Throwable e) {
                    reportWtf("starting Search Service", e);
                }
                t.traceEnd();
            }

            if (context.getResources().getBoolean(R.bool.config_enableWallpaperService)) {
                t.traceBegin("StartWallpaperManagerService");
                mSystemServiceManager.startService(WALLPAPER_SERVICE_CLASS);
                t.traceEnd();
            } else {
                Slog.i(TAG, "Wallpaper service disabled by config");
            }

            t.traceBegin("StartAudioService");
            if (!isArc) {
                mSystemServiceManager.startService(AudioService.Lifecycle.class);
            } else {
                String className = context.getResources()
                        .getString(R.string.config_deviceSpecificAudioService);
                try {
                    mSystemServiceManager.startService(className + "$Lifecycle");
                } catch (Throwable e) {
                    reportWtf("starting " + className, e);
                }
            }
            t.traceEnd();

            t.traceBegin("StartSoundTriggerMiddlewareService");
            mSystemServiceManager.startService(SoundTriggerMiddlewareService.Lifecycle.class);
            t.traceEnd();

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_BROADCAST_RADIO)) {
                t.traceBegin("StartBroadcastRadioService");
                mSystemServiceManager.startService(BroadcastRadioService.class);
                t.traceEnd();
            }

            t.traceBegin("StartDockObserver");
            mSystemServiceManager.startService(DockObserver.class);
            t.traceEnd();

            if (isWatch) {
                t.traceBegin("StartThermalObserver");
                mSystemServiceManager.startService(THERMAL_OBSERVER_CLASS);
                t.traceEnd();
            }

            t.traceBegin("StartWiredAccessoryManager");
            try {
                // Listen for wired headset changes
                inputManager.setWiredAccessoryCallbacks(
                        new WiredAccessoryManager(context, inputManager));
            } catch (Throwable e) {
                reportWtf("starting WiredAccessoryManager", e);
            }
            t.traceEnd();

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) {
                // Start MIDI Manager service
                t.traceBegin("StartMidiManager");
                mSystemServiceManager.startService(MIDI_SERVICE_CLASS);
                t.traceEnd();
            }

            // Start ADB Debugging Service
            t.traceBegin("StartAdbService");
            try {
                mSystemServiceManager.startService(ADB_SERVICE_CLASS);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting AdbService");
            }
            t.traceEnd();

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
                    || mPackageManager.hasSystemFeature(
                    PackageManager.FEATURE_USB_ACCESSORY)
                    || isEmulator) {
                // Manage USB host and device support
                t.traceBegin("StartUsbService");
                mSystemServiceManager.startService(USB_SERVICE_CLASS);
                t.traceEnd();
            }

            if (!isWatch) {
                t.traceBegin("StartSerialService");
                try {
                    // Serial port support
                    serial = new SerialService(context);
                    ServiceManager.addService(Context.SERIAL_SERVICE, serial);
                } catch (Throwable e) {
                    Slog.e(TAG, "Failure starting SerialService", e);
                }
                t.traceEnd();
            }

            t.traceBegin("StartHardwarePropertiesManagerService");
            try {
                hardwarePropertiesService = new HardwarePropertiesManagerService(context);
                ServiceManager.addService(Context.HARDWARE_PROPERTIES_SERVICE,
                        hardwarePropertiesService);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting HardwarePropertiesManagerService", e);
            }
            t.traceEnd();

            t.traceBegin("StartTwilightService");
            mSystemServiceManager.startService(TwilightService.class);
            t.traceEnd();

            t.traceBegin("StartColorDisplay");
            mSystemServiceManager.startService(ColorDisplayService.class);
            t.traceEnd();

            // TODO(aml-jobscheduler): Think about how to do it properly.
            t.traceBegin("StartJobScheduler");
            mSystemServiceManager.startService(JOB_SCHEDULER_SERVICE_CLASS);
            t.traceEnd();

            t.traceBegin("StartSoundTrigger");
            mSystemServiceManager.startService(SoundTriggerService.class);
            t.traceEnd();

            t.traceBegin("StartTrustManager");
            mSystemServiceManager.startService(TrustManagerService.class);
            t.traceEnd();

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_BACKUP)) {
                t.traceBegin("StartBackupManager");
                mSystemServiceManager.startService(BACKUP_MANAGER_SERVICE_CLASS);
                t.traceEnd();
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_APP_WIDGETS)
                    || context.getResources().getBoolean(R.bool.config_enableAppWidgetService)) {
                t.traceBegin("StartAppWidgetService");
                mSystemServiceManager.startService(APPWIDGET_SERVICE_CLASS);
                t.traceEnd();
            }

            // Grants default permissions and defines roles
            t.traceBegin("StartRoleManagerService");
            mSystemServiceManager.startService(new RoleManagerService(
                    mSystemContext, new LegacyRoleResolutionPolicy(mSystemContext)));
            t.traceEnd();

            // We need to always start this service, regardless of whether the
            // FEATURE_VOICE_RECOGNIZERS feature is set, because it needs to take care
            // of initializing various settings.  It will internally modify its behavior
            // based on that feature.
            t.traceBegin("StartVoiceRecognitionManager");
            mSystemServiceManager.startService(VOICE_RECOGNITION_MANAGER_SERVICE_CLASS);
            t.traceEnd();

            if (GestureLauncherService.isGestureLauncherEnabled(context.getResources())) {
                t.traceBegin("StartGestureLauncher");
                mSystemServiceManager.startService(GestureLauncherService.class);
                t.traceEnd();
            }
            t.traceBegin("StartSensorNotification");
            mSystemServiceManager.startService(SensorNotificationService.class);
            t.traceEnd();

            t.traceBegin("StartContextHubSystemService");
            mSystemServiceManager.startService(ContextHubSystemService.class);
            t.traceEnd();

            t.traceBegin("StartDiskStatsService");
            try {
                ServiceManager.addService("diskstats", new DiskStatsService(context));
            } catch (Throwable e) {
                reportWtf("starting DiskStats Service", e);
            }
            t.traceEnd();

            t.traceBegin("RuntimeService");
            try {
                ServiceManager.addService("runtime", new RuntimeService(context));
            } catch (Throwable e) {
                reportWtf("starting RuntimeService", e);
            }
            t.traceEnd();

            // timezone.RulesManagerService will prevent a device starting up if the chain of trust
            // required for safe time zone updates might be broken. RuleManagerService cannot do
            // this check when mOnlyCore == true, so we don't enable the service in this case.
            // This service requires that JobSchedulerService is already started when it starts.
            final boolean startRulesManagerService =
                    !mOnlyCore && context.getResources().getBoolean(
                            R.bool.config_enableUpdateableTimeZoneRules);
            if (startRulesManagerService) {
                t.traceBegin("StartTimeZoneRulesManagerService");
                mSystemServiceManager.startService(TIME_ZONE_RULES_MANAGER_SERVICE_CLASS);
                t.traceEnd();
            }

            if (!isWatch && !disableNetworkTime) {
                t.traceBegin("StartNetworkTimeUpdateService");
                try {
                    networkTimeUpdater = new NetworkTimeUpdateServiceImpl(context);
                    ServiceManager.addService("network_time_update_service", networkTimeUpdater);
                } catch (Throwable e) {
                    reportWtf("starting NetworkTimeUpdate service", e);
                }
                t.traceEnd();
            }

            t.traceBegin("CertBlacklister");
            try {
                CertBlacklister blacklister = new CertBlacklister(context);
            } catch (Throwable e) {
                reportWtf("starting CertBlacklister", e);
            }
            t.traceEnd();

            if (EmergencyAffordanceManager.ENABLED) {
                // EmergencyMode service
                t.traceBegin("StartEmergencyAffordanceService");
                mSystemServiceManager.startService(EmergencyAffordanceService.class);
                t.traceEnd();
            }

            // Dreams (interactive idle-time views, a/k/a screen savers, and doze mode)
            t.traceBegin("StartDreamManager");
            mSystemServiceManager.startService(DreamManagerService.class);
            t.traceEnd();

            t.traceBegin("AddGraphicsStatsService");
            ServiceManager.addService(GraphicsStatsService.GRAPHICS_STATS_SERVICE,
                    new GraphicsStatsService(context));
            t.traceEnd();

            if (CoverageService.ENABLED) {
                t.traceBegin("AddCoverageService");
                ServiceManager.addService(CoverageService.COVERAGE_SERVICE, new CoverageService());
                t.traceEnd();
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_PRINTING)) {
                t.traceBegin("StartPrintManager");
                mSystemServiceManager.startService(PRINT_MANAGER_SERVICE_CLASS);
                t.traceEnd();
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
                t.traceBegin("StartCompanionDeviceManager");
                mSystemServiceManager.startService(COMPANION_DEVICE_MANAGER_SERVICE_CLASS);
                t.traceEnd();
            }

            t.traceBegin("StartRestrictionManager");
            mSystemServiceManager.startService(RestrictionsManagerService.class);
            t.traceEnd();

            t.traceBegin("StartMediaSessionService");
            mSystemServiceManager.startService(MediaSessionService.class);
            t.traceEnd();

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_HDMI_CEC)) {
                t.traceBegin("StartHdmiControlService");
                mSystemServiceManager.startService(HdmiControlService.class);
                t.traceEnd();
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_TV)
                    || mPackageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                t.traceBegin("StartTvInputManager");
                mSystemServiceManager.startService(TvInputManagerService.class);
                t.traceEnd();
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                t.traceBegin("StartMediaResourceMonitor");
                mSystemServiceManager.startService(MediaResourceMonitorService.class);
                t.traceEnd();
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                t.traceBegin("StartTvRemoteService");
                mSystemServiceManager.startService(TvRemoteService.class);
                t.traceEnd();
            }

            t.traceBegin("StartMediaRouterService");
            try {
                mediaRouter = new MediaRouterService(context);
                ServiceManager.addService(Context.MEDIA_ROUTER_SERVICE, mediaRouter);
            } catch (Throwable e) {
                reportWtf("starting MediaRouterService", e);
            }
            t.traceEnd();

            final boolean hasFeatureFace
                    = mPackageManager.hasSystemFeature(PackageManager.FEATURE_FACE);
            final boolean hasFeatureIris
                    = mPackageManager.hasSystemFeature(PackageManager.FEATURE_IRIS);
            final boolean hasFeatureFingerprint
                    = mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);

            if (hasFeatureFace) {
                t.traceBegin("StartFaceSensor");
                mSystemServiceManager.startService(FaceService.class);
                t.traceEnd();
            }

            if (hasFeatureIris) {
                t.traceBegin("StartIrisSensor");
                mSystemServiceManager.startService(IrisService.class);
                t.traceEnd();
            }

            if (hasFeatureFingerprint) {
                t.traceBegin("StartFingerprintSensor");
                mSystemServiceManager.startService(FingerprintService.class);
                t.traceEnd();
            }

            if (hasFeatureFace || hasFeatureIris || hasFeatureFingerprint) {
                // Start this service after all biometric services.
                t.traceBegin("StartBiometricService");
                mSystemServiceManager.startService(BiometricService.class);
                t.traceEnd();

                t.traceBegin("StartAuthService");
                mSystemServiceManager.startService(AuthService.class);
                t.traceEnd();
            }


            t.traceBegin("StartBackgroundDexOptService");
            try {
                BackgroundDexOptService.schedule(context);
            } catch (Throwable e) {
                reportWtf("starting StartBackgroundDexOptService", e);
            }
            t.traceEnd();

            if (!isWatch) {
                // We don't run this on watches as there are no plans to use the data logged
                // on watch devices.
                t.traceBegin("StartDynamicCodeLoggingService");
                try {
                    DynamicCodeLoggingService.schedule(context);
                } catch (Throwable e) {
                    reportWtf("starting DynamicCodeLoggingService", e);
                }
                t.traceEnd();
            }

            if (!isWatch) {
                t.traceBegin("StartPruneInstantAppsJobService");
                try {
                    PruneInstantAppsJobService.schedule(context);
                } catch (Throwable e) {
                    reportWtf("StartPruneInstantAppsJobService", e);
                }
                t.traceEnd();
            }

            // LauncherAppsService uses ShortcutService.
            t.traceBegin("StartShortcutServiceLifecycle");
            mSystemServiceManager.startService(ShortcutService.Lifecycle.class);
            t.traceEnd();

            t.traceBegin("StartLauncherAppsService");
            mSystemServiceManager.startService(LauncherAppsService.class);
            t.traceEnd();

            t.traceBegin("StartCrossProfileAppsService");
            mSystemServiceManager.startService(CrossProfileAppsService.class);
            t.traceEnd();

            t.traceBegin("StartPeopleService");
            mSystemServiceManager.startService(PeopleService.class);
            t.traceEnd();
        }

        if (!isWatch) {
            t.traceBegin("StartMediaProjectionManager");
            mSystemServiceManager.startService(MediaProjectionManagerService.class);
            t.traceEnd();
        }

        if (isWatch) {
            // Must be started before services that depend it, e.g. WearConnectivityService
            t.traceBegin("StartWearPowerService");
            mSystemServiceManager.startService(WEAR_POWER_SERVICE_CLASS);
            t.traceEnd();

            t.traceBegin("StartWearConnectivityService");
            mSystemServiceManager.startService(WEAR_CONNECTIVITY_SERVICE_CLASS);
            t.traceEnd();

            t.traceBegin("StartWearDisplayService");
            mSystemServiceManager.startService(WEAR_DISPLAY_SERVICE_CLASS);
            t.traceEnd();

            t.traceBegin("StartWearTimeService");
            mSystemServiceManager.startService(WEAR_TIME_SERVICE_CLASS);
            t.traceEnd();

            if (enableLeftyService) {
                t.traceBegin("StartWearLeftyService");
                mSystemServiceManager.startService(WEAR_LEFTY_SERVICE_CLASS);
                t.traceEnd();
            }

            t.traceBegin("StartWearGlobalActionsService");
            mSystemServiceManager.startService(WEAR_GLOBAL_ACTIONS_SERVICE_CLASS);
            t.traceEnd();
        }

        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_SLICES_DISABLED)) {
            t.traceBegin("StartSliceManagerService");
            mSystemServiceManager.startService(SLICE_MANAGER_SERVICE_CLASS);
            t.traceEnd();
        }

        if (!disableCameraService) {
            t.traceBegin("StartCameraServiceProxy");
            mSystemServiceManager.startService(CameraServiceProxy.class);
            t.traceEnd();
        }

        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_EMBEDDED)) {
            t.traceBegin("StartIoTSystemService");
            mSystemServiceManager.startService(IOT_SERVICE_CLASS);
            t.traceEnd();
        }

        // Statsd helper
        t.traceBegin("StartStatsCompanion");
        mSystemServiceManager.startServiceFromJar(
                STATS_COMPANION_LIFECYCLE_CLASS, STATS_COMPANION_APEX_PATH);
        t.traceEnd();

        // Statsd pulled atoms
        t.traceBegin("StartStatsPullAtomService");
        mSystemServiceManager.startService(STATS_PULL_ATOM_SERVICE_CLASS);
        t.traceEnd();

        // Incidentd and dumpstated helper
        t.traceBegin("StartIncidentCompanionService");
        mSystemServiceManager.startService(IncidentCompanionService.class);
        t.traceEnd();

        if (safeMode) {
            mActivityManagerService.enterSafeMode();
        }

        // MMS service broker
        t.traceBegin("StartMmsService");
        mmsService = mSystemServiceManager.startService(MmsServiceBroker.class);
        t.traceEnd();

        if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOFILL)) {
            t.traceBegin("StartAutoFillService");
            mSystemServiceManager.startService(AUTO_FILL_MANAGER_SERVICE_CLASS);
            t.traceEnd();
        }

        // NOTE: ClipboardService depends on ContentCapture and Autofill
        t.traceBegin("StartClipboardService");
        mSystemServiceManager.startService(ClipboardService.class);
        t.traceEnd();

        t.traceBegin("StartBlobStoreManagerService");
        mSystemServiceManager.startService(BLOB_STORE_MANAGER_SERVICE_CLASS);
        t.traceEnd();

        t.traceBegin("AppServiceManager");
        mSystemServiceManager.startService(AppBindingService.Lifecycle.class);
        t.traceEnd();

        // It is now time to start up the app processes...

        t.traceBegin("MakeVibratorServiceReady");
        try {
            vibrator.systemReady();
        } catch (Throwable e) {
            reportWtf("making Vibrator Service ready", e);
        }
        t.traceEnd();

        t.traceBegin("MakeLockSettingsServiceReady");
        if (lockSettings != null) {
            try {
                lockSettings.systemReady();
            } catch (Throwable e) {
                reportWtf("making Lock Settings Service ready", e);
            }
        }
        t.traceEnd();

        // Needed by DevicePolicyManager for initialization
        t.traceBegin("StartBootPhaseLockSettingsReady");
        mSystemServiceManager.startBootPhase(t, SystemService.PHASE_LOCK_SETTINGS_READY);
        t.traceEnd();

        t.traceBegin("StartBootPhaseSystemServicesReady");
        mSystemServiceManager.startBootPhase(t, SystemService.PHASE_SYSTEM_SERVICES_READY);
        t.traceEnd();

        t.traceBegin("MakeWindowManagerServiceReady");
        try {
            wm.systemReady();
        } catch (Throwable e) {
            reportWtf("making Window Manager Service ready", e);
        }
        t.traceEnd();

        if (safeMode) {
            mActivityManagerService.showSafeModeOverlay();
        }

        // Update the configuration for this context by hand, because we're going
        // to start using it before the config change done in wm.systemReady() will
        // propagate to it.
        final Configuration config = wm.computeNewConfiguration(DEFAULT_DISPLAY);
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager w = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        w.getDefaultDisplay().getMetrics(metrics);
        context.getResources().updateConfiguration(config, metrics);

        // The system context's theme may be configuration-dependent.
        final Theme systemTheme = context.getTheme();
        if (systemTheme.getChangingConfigurations() != 0) {
            systemTheme.rebase();
        }

        t.traceBegin("MakePowerManagerServiceReady");
        try {
            // TODO: use boot phase
            mPowerManagerService.systemReady(mActivityManagerService.getAppOpsService());
        } catch (Throwable e) {
            reportWtf("making Power Manager Service ready", e);
        }
        t.traceEnd();

        // Permission policy service
        t.traceBegin("StartPermissionPolicyService");
        mSystemServiceManager.startService(PermissionPolicyService.class);
        t.traceEnd();

        t.traceBegin("MakePackageManagerServiceReady");
        mPackageManagerService.systemReady();
        t.traceEnd();

        if (mIncrementalManagerService != null) {
            t.traceBegin("MakeIncrementalManagerServiceReady");
            mIncrementalManagerService.systemReady();
            t.traceEnd();
        }

        t.traceBegin("MakeDisplayManagerServiceReady");
        try {
            // TODO: use boot phase and communicate these flags some other way
            mDisplayManagerService.systemReady(safeMode, mOnlyCore);
        } catch (Throwable e) {
            reportWtf("making Display Manager Service ready", e);
        }
        t.traceEnd();

        mSystemServiceManager.setSafeMode(safeMode);

        // Start device specific services
        t.traceBegin("StartDeviceSpecificServices");
        final String[] classes = mSystemContext.getResources().getStringArray(
                R.array.config_deviceSpecificSystemServices);
        for (final String className : classes) {
            t.traceBegin("StartDeviceSpecificServices " + className);
            try {
                mSystemServiceManager.startService(className);
            } catch (Throwable e) {
                reportWtf("starting " + className, e);
            }
            t.traceEnd();
        }
        t.traceEnd();

        t.traceBegin("StartBootPhaseDeviceSpecificServicesReady");
        mSystemServiceManager.startBootPhase(t, SystemService.PHASE_DEVICE_SPECIFIC_SERVICES_READY);
        t.traceEnd();

        t.traceBegin("AppSearchManagerService");
        mSystemServiceManager.startService(APP_SEARCH_MANAGER_SERVICE_CLASS);
        t.traceEnd();

        // These are needed to propagate to the runnable below.
        final NetworkManagementService networkManagementF = networkManagement;
        final NetworkStatsService networkStatsF = networkStats;
        final NetworkPolicyManagerService networkPolicyF = networkPolicy;
        final ConnectivityService connectivityF = connectivity;
        final CountryDetectorService countryDetectorF = countryDetector;
        final NetworkTimeUpdateService networkTimeUpdaterF = networkTimeUpdater;
        final InputManagerService inputManagerF = inputManager;
        final TelephonyRegistry telephonyRegistryF = telephonyRegistry;
        final MediaRouterService mediaRouterF = mediaRouter;
        final MmsServiceBroker mmsServiceF = mmsService;
        final IpSecService ipSecServiceF = ipSecService;
        final WindowManagerService windowManagerF = wm;

        // We now tell the activity manager it is okay to run third party
        // code.  It will call back into us once it has gotten to the state
        // where third party code can really run (but before it has actually
        // started launching the initial applications), for us to complete our
        // initialization.
        mActivityManagerService.systemReady(() -> {
            Slog.i(TAG, "Making services ready");
            t.traceBegin("StartActivityManagerReadyPhase");
            mSystemServiceManager.startBootPhase(t, SystemService.PHASE_ACTIVITY_MANAGER_READY);
            t.traceEnd();
            t.traceBegin("StartObservingNativeCrashes");
            try {
                mActivityManagerService.startObservingNativeCrashes();
            } catch (Throwable e) {
                reportWtf("observing native crashes", e);
            }
            t.traceEnd();

            // No dependency on Webview preparation in system server. But this should
            // be completed before allowing 3rd party
            final String WEBVIEW_PREPARATION = "WebViewFactoryPreparation";
            Future<?> webviewPrep = null;
            if (!mOnlyCore && mWebViewUpdateService != null) {
                webviewPrep = SystemServerInitThreadPool.submit(() -> {
                    Slog.i(TAG, WEBVIEW_PREPARATION);
                    TimingsTraceAndSlog traceLog = TimingsTraceAndSlog.newAsyncLog();
                    traceLog.traceBegin(WEBVIEW_PREPARATION);
                    ConcurrentUtils.waitForFutureNoInterrupt(mZygotePreload, "Zygote preload");
                    mZygotePreload = null;
                    mWebViewUpdateService.prepareWebViewInSystemServer();
                    traceLog.traceEnd();
                }, WEBVIEW_PREPARATION);
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                t.traceBegin("StartCarServiceHelperService");
                mSystemServiceManager.startService(CAR_SERVICE_HELPER_SERVICE_CLASS);
                t.traceEnd();
            }

            t.traceBegin("StartSystemUI");
            try {
                startSystemUi(context, windowManagerF);
            } catch (Throwable e) {
                reportWtf("starting System UI", e);
            }
            t.traceEnd();
            // Enable airplane mode in safe mode. setAirplaneMode() cannot be called
            // earlier as it sends broadcasts to other services.
            // TODO: This may actually be too late if radio firmware already started leaking
            // RF before the respective services start. However, fixing this requires changes
            // to radio firmware and interfaces.
            if (safeMode) {
                t.traceBegin("EnableAirplaneModeInSafeMode");
                try {
                    connectivityF.setAirplaneMode(true);
                } catch (Throwable e) {
                    reportWtf("enabling Airplane Mode during Safe Mode bootup", e);
                }
                t.traceEnd();
            }
            t.traceBegin("MakeNetworkManagementServiceReady");
            try {
                if (networkManagementF != null) {
                    networkManagementF.systemReady();
                }
            } catch (Throwable e) {
                reportWtf("making Network Managment Service ready", e);
            }
            CountDownLatch networkPolicyInitReadySignal = null;
            if (networkPolicyF != null) {
                networkPolicyInitReadySignal = networkPolicyF
                        .networkScoreAndNetworkManagementServiceReady();
            }
            t.traceEnd();
            t.traceBegin("MakeIpSecServiceReady");
            try {
                if (ipSecServiceF != null) {
                    ipSecServiceF.systemReady();
                }
            } catch (Throwable e) {
                reportWtf("making IpSec Service ready", e);
            }
            t.traceEnd();
            t.traceBegin("MakeNetworkStatsServiceReady");
            try {
                if (networkStatsF != null) {
                    networkStatsF.systemReady();
                }
            } catch (Throwable e) {
                reportWtf("making Network Stats Service ready", e);
            }
            t.traceEnd();
            t.traceBegin("MakeConnectivityServiceReady");
            try {
                if (connectivityF != null) {
                    connectivityF.systemReady();
                }
            } catch (Throwable e) {
                reportWtf("making Connectivity Service ready", e);
            }
            t.traceEnd();
            t.traceBegin("MakeNetworkPolicyServiceReady");
            try {
                if (networkPolicyF != null) {
                    networkPolicyF.systemReady(networkPolicyInitReadySignal);
                }
            } catch (Throwable e) {
                reportWtf("making Network Policy Service ready", e);
            }
            t.traceEnd();

            // Wait for all packages to be prepared
            mPackageManagerService.waitForAppDataPrepared();

            // It is now okay to let the various system services start their
            // third party code...
            t.traceBegin("PhaseThirdPartyAppsCanStart");
            // confirm webview completion before starting 3rd party
            if (webviewPrep != null) {
                ConcurrentUtils.waitForFutureNoInterrupt(webviewPrep, WEBVIEW_PREPARATION);
            }
            mSystemServiceManager.startBootPhase(t, SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);
            t.traceEnd();

            t.traceBegin("StartNetworkStack");
            try {
                // Note : the network stack is creating on-demand objects that need to send
                // broadcasts, which means it currently depends on being started after
                // ActivityManagerService.mSystemReady and ActivityManagerService.mProcessesReady
                // are set to true. Be careful if moving this to a different place in the
                // startup sequence.
                NetworkStackClient.getInstance().start();
            } catch (Throwable e) {
                reportWtf("starting Network Stack", e);
            }
            t.traceEnd();

            t.traceBegin("StartTethering");
            try {
                // TODO: hide implementation details, b/146312721.
                ConnectivityModuleConnector.getInstance().startModuleService(
                        ITetheringConnector.class.getName(),
                        PERMISSION_MAINLINE_NETWORK_STACK, service -> {
                            ServiceManager.addService(Context.TETHERING_SERVICE, service,
                                    false /* allowIsolated */,
                                    DUMP_FLAG_PRIORITY_HIGH | DUMP_FLAG_PRIORITY_NORMAL);
                        });
            } catch (Throwable e) {
                reportWtf("starting Tethering", e);
            }
            t.traceEnd();

            t.traceBegin("MakeCountryDetectionServiceReady");
            try {
                if (countryDetectorF != null) {
                    countryDetectorF.systemRunning();
                }
            } catch (Throwable e) {
                reportWtf("Notifying CountryDetectorService running", e);
            }
            t.traceEnd();
            t.traceBegin("MakeNetworkTimeUpdateReady");
            try {
                if (networkTimeUpdaterF != null) {
                    networkTimeUpdaterF.systemRunning();
                }
            } catch (Throwable e) {
                reportWtf("Notifying NetworkTimeService running", e);
            }
            t.traceEnd();
            t.traceBegin("MakeInputManagerServiceReady");
            try {
                // TODO(BT) Pass parameter to input manager
                if (inputManagerF != null) {
                    inputManagerF.systemRunning();
                }
            } catch (Throwable e) {
                reportWtf("Notifying InputManagerService running", e);
            }
            t.traceEnd();
            t.traceBegin("MakeTelephonyRegistryReady");
            try {
                if (telephonyRegistryF != null) {
                    telephonyRegistryF.systemRunning();
                }
            } catch (Throwable e) {
                reportWtf("Notifying TelephonyRegistry running", e);
            }
            t.traceEnd();
            t.traceBegin("MakeMediaRouterServiceReady");
            try {
                if (mediaRouterF != null) {
                    mediaRouterF.systemRunning();
                }
            } catch (Throwable e) {
                reportWtf("Notifying MediaRouterService running", e);
            }
            t.traceEnd();
            t.traceBegin("MakeMmsServiceReady");
            try {
                if (mmsServiceF != null) {
                    mmsServiceF.systemRunning();
                }
            } catch (Throwable e) {
                reportWtf("Notifying MmsService running", e);
            }
            t.traceEnd();

            t.traceBegin("IncidentDaemonReady");
            try {
                // TODO: Switch from checkService to getService once it's always
                // in the build and should reliably be there.
                final IIncidentManager incident = IIncidentManager.Stub.asInterface(
                        ServiceManager.getService(Context.INCIDENT_SERVICE));
                if (incident != null) {
                    incident.systemRunning();
                }
            } catch (Throwable e) {
                reportWtf("Notifying incident daemon running", e);
            }
            t.traceEnd();
        }, t);

        t.traceEnd(); // startOtherServices
    }

    private boolean deviceHasConfigString(@NonNull Context context, @StringRes int resId) {
        String serviceName = context.getString(resId);
        return !TextUtils.isEmpty(serviceName);
    }

    private void startSystemCaptionsManagerService(@NonNull Context context,
            @NonNull TimingsTraceAndSlog t) {
        if (!deviceHasConfigString(context, R.string.config_defaultSystemCaptionsManagerService)) {
            Slog.d(TAG, "SystemCaptionsManagerService disabled because resource is not overlaid");
            return;
        }

        t.traceBegin("StartSystemCaptionsManagerService");
        mSystemServiceManager.startService(SYSTEM_CAPTIONS_MANAGER_SERVICE_CLASS);
        t.traceEnd();
    }

    private void startContentCaptureService(@NonNull Context context,
            @NonNull TimingsTraceAndSlog t) {
        // First check if it was explicitly enabled by DeviceConfig
        boolean explicitlyEnabled = false;
        String settings = DeviceConfig.getProperty(DeviceConfig.NAMESPACE_CONTENT_CAPTURE,
                ContentCaptureManager.DEVICE_CONFIG_PROPERTY_SERVICE_EXPLICITLY_ENABLED);
        if (settings != null && !settings.equalsIgnoreCase("default")) {
            explicitlyEnabled = Boolean.parseBoolean(settings);
            if (explicitlyEnabled) {
                Slog.d(TAG, "ContentCaptureService explicitly enabled by DeviceConfig");
            } else {
                Slog.d(TAG, "ContentCaptureService explicitly disabled by DeviceConfig");
                return;
            }
        }

        // Then check if OEM overlaid the resource that defines the service.
        if (!explicitlyEnabled) {
            if (!deviceHasConfigString(context, R.string.config_defaultContentCaptureService)) {
                Slog.d(TAG, "ContentCaptureService disabled because resource is not overlaid");
                return;
            }
        }

        t.traceBegin("StartContentCaptureService");
        mSystemServiceManager.startService(CONTENT_CAPTURE_MANAGER_SERVICE_CLASS);

        ContentCaptureManagerInternal ccmi =
                LocalServices.getService(ContentCaptureManagerInternal.class);
        if (ccmi != null && mActivityManagerService != null) {
            mActivityManagerService.setContentCaptureManager(ccmi);
        }

        t.traceEnd();
    }

    private void startAttentionService(@NonNull Context context, @NonNull TimingsTraceAndSlog t) {
        if (!AttentionManagerService.isServiceConfigured(context)) {
            Slog.d(TAG, "AttentionService is not configured on this device");
            return;
        }

        t.traceBegin("StartAttentionManagerService");
        mSystemServiceManager.startService(AttentionManagerService.class);
        t.traceEnd();
    }

    private static void startSystemUi(Context context, WindowManagerService windowManager) {
        PackageManagerInternal pm = LocalServices.getService(PackageManagerInternal.class);
        Intent intent = new Intent();
        intent.setComponent(pm.getSystemUiServiceComponent());
        intent.addFlags(Intent.FLAG_DEBUG_TRIAGED_MISSING);
        //Slog.d(TAG, "Starting service: " + intent);
        context.startServiceAsUser(intent, UserHandle.SYSTEM);
        windowManager.onSystemUiStarted();
    }
}
