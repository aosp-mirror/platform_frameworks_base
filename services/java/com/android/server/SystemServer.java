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
import static android.os.Process.SYSTEM_UID;
import static android.os.Process.myPid;
import static android.system.OsConstants.O_CLOEXEC;
import static android.system.OsConstants.O_RDONLY;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.utils.TimingsTraceAndSlog.SYSTEM_SERVER_TIMING_TAG;

import android.annotation.NonNull;
import android.annotation.StringRes;
import android.app.ActivityThread;
import android.app.AppCompatCallbacks;
import android.app.ApplicationErrorReport;
import android.app.INotificationManager;
import android.app.SystemServiceRegistry;
import android.app.admin.DevicePolicySafetyChecker;
import android.app.appfunctions.AppFunctionManagerConfiguration;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.res.Configuration;
import android.content.res.Resources.Theme;
import android.crashrecovery.flags.Flags;
import android.credentials.CredentialManager;
import android.database.sqlite.SQLiteCompatibilityWalFlags;
import android.database.sqlite.SQLiteGlobal;
import android.graphics.GraphicsStatsService;
import android.graphics.Typeface;
import android.hardware.display.DisplayManagerInternal;
import android.net.ConnectivityManager;
import android.net.ConnectivityModuleConnector;
import android.net.NetworkStackClient;
import android.os.ArtModuleServiceManager;
import android.os.BaseBundle;
import android.os.Binder;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.FactoryTest;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.IBinderCallback;
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
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.IStorageManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.server.ServerProtoEnums;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.Dumpable;
import android.util.EventLog;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.TimeUtils;
import android.view.contentcapture.ContentCaptureManager;

import com.android.i18n.timezone.ZoneInfoDb;
import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.ApplicationSharedMemory;
import com.android.internal.os.BinderInternal;
import com.android.internal.os.RuntimeInit;
import com.android.internal.policy.AttributeCache;
import com.android.internal.protolog.ProtoLog;
import com.android.internal.protolog.ProtoLogConfigurationServiceImpl;
import com.android.internal.protolog.WmProtoLogGroups;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.EmergencyAffordanceManager;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.widget.ILockSettings;
import com.android.internal.widget.LockSettingsInternal;
import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.accounts.AccountManagerService;
import com.android.server.adaptiveauth.AdaptiveAuthService;
import com.android.server.adb.AdbService;
import com.android.server.alarm.AlarmManagerService;
import com.android.server.am.ActivityManagerService;
import com.android.server.ambientcontext.AmbientContextManagerService;
import com.android.server.app.GameManagerService;
import com.android.server.appbinding.AppBindingService;
import com.android.server.appfunctions.AppFunctionManagerService;
import com.android.server.apphibernation.AppHibernationService;
import com.android.server.appop.AppOpMigrationHelper;
import com.android.server.appop.AppOpMigrationHelperImpl;
import com.android.server.appprediction.AppPredictionManagerService;
import com.android.server.appwidget.AppWidgetService;
import com.android.server.art.ArtModuleServiceInitializer;
import com.android.server.art.DexUseManagerLocal;
import com.android.server.attention.AttentionManagerService;
import com.android.server.audio.AudioService;
import com.android.server.autofill.AutofillManagerService;
import com.android.server.backup.BackupManagerService;
import com.android.server.biometrics.AuthService;
import com.android.server.biometrics.BiometricService;
import com.android.server.biometrics.sensors.face.FaceService;
import com.android.server.biometrics.sensors.fingerprint.FingerprintService;
import com.android.server.biometrics.sensors.iris.IrisService;
import com.android.server.blob.BlobStoreManagerService;
import com.android.server.broadcastradio.BroadcastRadioService;
import com.android.server.camera.CameraServiceProxy;
import com.android.server.clipboard.ClipboardService;
import com.android.server.companion.CompanionDeviceManagerService;
import com.android.server.companion.virtual.VirtualDeviceManagerService;
import com.android.server.compat.PlatformCompat;
import com.android.server.compat.PlatformCompatNative;
import com.android.server.compat.overrides.AppCompatOverridesService;
import com.android.server.connectivity.IpConnectivityMetrics;
import com.android.server.connectivity.PacProxyService;
import com.android.server.content.ContentService;
import com.android.server.contentcapture.ContentCaptureManagerInternal;
import com.android.server.contentcapture.ContentCaptureManagerService;
import com.android.server.contentsuggestions.ContentSuggestionsManagerService;
import com.android.server.contextualsearch.ContextualSearchManagerService;
import com.android.server.coverage.CoverageService;
import com.android.server.cpu.CpuMonitorService;
import com.android.server.crashrecovery.CrashRecoveryAdaptor;
import com.android.server.credentials.CredentialManagerService;
import com.android.server.criticalevents.CriticalEventLog;
import com.android.server.devicepolicy.DevicePolicyManagerService;
import com.android.server.devicestate.DeviceStateManagerService;
import com.android.server.display.DisplayManagerService;
import com.android.server.display.color.ColorDisplayService;
import com.android.server.dreams.DreamManagerService;
import com.android.server.emergency.EmergencyAffordanceService;
import com.android.server.flags.FeatureFlagsService;
import com.android.server.gpu.GpuService;
import com.android.server.grammaticalinflection.GrammaticalInflectionService;
import com.android.server.graphics.fonts.FontManagerService;
import com.android.server.hdmi.HdmiControlService;
import com.android.server.incident.IncidentCompanionService;
import com.android.server.input.InputManagerService;
import com.android.server.inputmethod.InputMethodManagerService;
import com.android.server.integrity.AppIntegrityManagerService;
import com.android.server.job.JobSchedulerService;
import com.android.server.lights.LightsService;
import com.android.server.locales.LocaleManagerService;
import com.android.server.location.LocationManagerService;
import com.android.server.location.altitude.AltitudeService;
import com.android.server.locksettings.LockSettingsService;
import com.android.server.logcat.LogcatManagerService;
import com.android.server.media.MediaResourceMonitorService;
import com.android.server.media.MediaRouterService;
import com.android.server.media.MediaSessionService;
import com.android.server.media.metrics.MediaMetricsManagerService;
import com.android.server.media.projection.MediaProjectionManagerService;
import com.android.server.midi.MidiService;
import com.android.server.musicrecognition.MusicRecognitionManagerService;
import com.android.server.net.NetworkManagementService;
import com.android.server.net.NetworkPolicyManagerService;
import com.android.server.net.watchlist.NetworkWatchlistService;
import com.android.server.notification.NotificationManagerService;
import com.android.server.oemlock.OemLockService;
import com.android.server.om.OverlayManagerService;
import com.android.server.ondeviceintelligence.OnDeviceIntelligenceManagerService;
import com.android.server.os.BugreportManagerService;
import com.android.server.os.DeviceIdentifiersPolicyService;
import com.android.server.os.NativeTombstoneManagerService;
import com.android.server.os.SchedulingPolicyService;
import com.android.server.pdb.PersistentDataBlockService;
import com.android.server.people.PeopleService;
import com.android.server.permission.access.AccessCheckingService;
import com.android.server.pinner.PinnerService;
import com.android.server.pm.ApexManager;
import com.android.server.pm.ApexSystemServiceInfo;
import com.android.server.pm.BackgroundInstallControlService;
import com.android.server.pm.CrossProfileAppsService;
import com.android.server.pm.DataLoaderManagerService;
import com.android.server.pm.DexOptHelper;
import com.android.server.pm.DynamicCodeLoggingService;
import com.android.server.pm.Installer;
import com.android.server.pm.LauncherAppsService;
import com.android.server.pm.OtaDexoptService;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.ShortcutService;
import com.android.server.pm.UserManagerService;
import com.android.server.pm.dex.OdsignStatsLogger;
import com.android.server.pm.permission.PermissionMigrationHelper;
import com.android.server.pm.permission.PermissionMigrationHelperImpl;
import com.android.server.pm.verify.domain.DomainVerificationService;
import com.android.server.policy.AppOpsPolicy;
import com.android.server.policy.PermissionPolicyService;
import com.android.server.policy.PhoneWindowManager;
import com.android.server.policy.role.RoleServicePlatformHelperImpl;
import com.android.server.power.PowerManagerService;
import com.android.server.power.ShutdownThread;
import com.android.server.power.ThermalManagerService;
import com.android.server.power.hint.HintManagerService;
import com.android.server.powerstats.PowerStatsService;
import com.android.server.print.PrintManagerService;
import com.android.server.profcollect.ProfcollectForwardingService;
import com.android.server.recoverysystem.RecoverySystemService;
import com.android.server.resources.ResourcesManagerService;
import com.android.server.restrictions.RestrictionsManagerService;
import com.android.server.role.RoleServicePlatformHelper;
import com.android.server.rollback.RollbackManagerService;
import com.android.server.rotationresolver.RotationResolverManagerService;
import com.android.server.search.SearchManagerService;
import com.android.server.searchui.SearchUiManagerService;
import com.android.server.security.AttestationVerificationManagerService;
import com.android.server.security.FileIntegrityService;
import com.android.server.security.KeyAttestationApplicationIdProviderService;
import com.android.server.security.KeyChainSystemService;
import com.android.server.security.rkp.RemoteProvisioningService;
import com.android.server.selinux.SelinuxAuditLogsService;
import com.android.server.sensorprivacy.SensorPrivacyService;
import com.android.server.sensors.SensorService;
import com.android.server.signedconfig.SignedConfigService;
import com.android.server.slice.SliceManagerService;
import com.android.server.smartspace.SmartspaceManagerService;
import com.android.server.soundtrigger.SoundTriggerService;
import com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareService;
import com.android.server.speech.SpeechRecognitionManagerService;
import com.android.server.stats.bootstrap.StatsBootstrapAtomService;
import com.android.server.stats.pull.StatsPullAtomService;
import com.android.server.statusbar.StatusBarManagerService;
import com.android.server.storage.DeviceStorageMonitorService;
import com.android.server.supervision.SupervisionService;
import com.android.server.systemcaptions.SystemCaptionsManagerService;
import com.android.server.telecom.TelecomLoaderService;
import com.android.server.testharness.TestHarnessModeService;
import com.android.server.textclassifier.TextClassificationManagerService;
import com.android.server.textservices.TextServicesManagerService;
import com.android.server.texttospeech.TextToSpeechManagerService;
import com.android.server.timedetector.GnssTimeUpdateService;
import com.android.server.timedetector.NetworkTimeUpdateService;
import com.android.server.timedetector.TimeDetectorService;
import com.android.server.timezonedetector.TimeZoneDetectorService;
import com.android.server.timezonedetector.location.LocationTimeZoneManagerService;
import com.android.server.tracing.TracingServiceProxy;
import com.android.server.translation.TranslationManagerService;
import com.android.server.trust.TrustManagerService;
import com.android.server.tv.TvInputManagerService;
import com.android.server.tv.TvRemoteService;
import com.android.server.tv.interactive.TvInteractiveAppManagerService;
import com.android.server.tv.tunerresourcemanager.TunerResourceManagerService;
import com.android.server.twilight.TwilightService;
import com.android.server.uri.UriGrantsManagerService;
import com.android.server.usage.StorageStatsService;
import com.android.server.usage.UsageStatsService;
import com.android.server.usb.UsbService;
import com.android.server.utils.TimingsTraceAndSlog;
import com.android.server.vibrator.VibratorManagerService;
import com.android.server.voiceinteraction.VoiceInteractionManagerService;
import com.android.server.vr.VrManagerService;
import com.android.server.wallpaper.WallpaperManagerService;
import com.android.server.wallpapereffectsgeneration.WallpaperEffectsGenerationManagerService;
import com.android.server.wearable.WearableSensingManagerService;
import com.android.server.webkit.WebViewUpdateService;
import com.android.server.wm.ActivityTaskManagerService;
import com.android.server.wm.WindowManagerGlobalLock;
import com.android.server.wm.WindowManagerService;

import dalvik.system.VMRuntime;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

/**
 * Entry point to {@code system_server}.
 */
public final class SystemServer implements Dumpable {

    private static final String TAG = "SystemServer";

    private static final long SLOW_DISPATCH_THRESHOLD_MS = 100;
    private static final long SLOW_DELIVERY_THRESHOLD_MS = 200;

    /*
     * Implementation class names for services in the {@code SYSTEMSERVERCLASSPATH}
     * from {@code PRODUCT_SYSTEM_SERVER_JARS} that are *not* in {@code services.jar}.
     */
    private static final String ARC_NETWORK_SERVICE_CLASS =
            "com.android.server.arc.net.ArcNetworkService";
    private static final String ARC_PERSISTENT_DATA_BLOCK_SERVICE_CLASS =
            "com.android.server.arc.persistent_data_block.ArcPersistentDataBlockService";
    private static final String ARC_SYSTEM_HEALTH_SERVICE =
            "com.android.server.arc.health.ArcSystemHealthService";
    private static final String LOWPAN_SERVICE_CLASS =
            "com.android.server.lowpan.LowpanService";
    private static final String THERMAL_OBSERVER_CLASS =
            "com.android.clockwork.ThermalObserver";
    private static final String WEAR_CONNECTIVITY_SERVICE_CLASS =
            "com.android.clockwork.connectivity.WearConnectivityService";
    private static final String WEAR_POWER_SERVICE_CLASS =
            "com.android.clockwork.power.WearPowerService";
    private static final String HEALTH_SERVICE_CLASS =
            "com.android.clockwork.healthservices.HealthService";
    private static final String SYSTEM_STATE_DISPLAY_SERVICE_CLASS =
            "com.android.clockwork.systemstatedisplay.SystemStateDisplayService";
    private static final String WEAR_DISPLAYOFFLOAD_SERVICE_CLASS =
            "com.android.clockwork.displayoffload.DisplayOffloadService";
    private static final String WEAR_MODE_SERVICE_CLASS =
            "com.android.clockwork.modes.ModeManagerService";
    private static final String WEAR_DISPLAY_SERVICE_CLASS =
            "com.android.clockwork.display.WearDisplayService";
    private static final String WEAR_DEBUG_SERVICE_CLASS =
            "com.android.clockwork.debug.WearDebugService";
    private static final String WEAR_TIME_SERVICE_CLASS =
            "com.android.clockwork.time.WearTimeService";
    private static final String WEAR_SETTINGS_SERVICE_CLASS =
            "com.android.clockwork.settings.WearSettingsService";
    private static final String WRIST_ORIENTATION_SERVICE_CLASS =
            "com.android.clockwork.wristorientation.WristOrientationService";
    private static final String IOT_SERVICE_CLASS =
            "com.android.things.server.IoTSystemService";
    private static final String CAR_SERVICE_HELPER_SERVICE_CLASS =
            "com.android.internal.car.CarServiceHelperService";

    /*
     * Implementation class names for services in the {@code SYSTEMSERVERCLASSPATH}
     * from {@code PRODUCT_APEX_SYSTEM_SERVER_JARS}.
     */
    private static final String APPSEARCH_MODULE_LIFECYCLE_CLASS =
            "com.android.server.appsearch.AppSearchModule$Lifecycle";
    private static final String ISOLATED_COMPILATION_SERVICE_CLASS =
            "com.android.server.compos.IsolatedCompilationService";
    private static final String MEDIA_COMMUNICATION_SERVICE_CLASS =
            "com.android.server.media.MediaCommunicationService";
    private static final String HEALTHCONNECT_MANAGER_SERVICE_CLASS =
            "com.android.server.healthconnect.HealthConnectManagerService";
    private static final String ROLE_SERVICE_CLASS = "com.android.role.RoleService";
    private static final String ENHANCED_CONFIRMATION_SERVICE_CLASS =
            "com.android.ecm.EnhancedConfirmationService";
    private static final String SAFETY_CENTER_SERVICE_CLASS =
            "com.android.safetycenter.SafetyCenterService";
    private static final String SDK_SANDBOX_MANAGER_SERVICE_CLASS =
            "com.android.server.sdksandbox.SdkSandboxManagerService$Lifecycle";
    private static final String AD_SERVICES_MANAGER_SERVICE_CLASS =
            "com.android.server.adservices.AdServicesManagerService$Lifecycle";
    private static final String ON_DEVICE_PERSONALIZATION_SYSTEM_SERVICE_CLASS =
            "com.android.server.ondevicepersonalization."
                    + "OnDevicePersonalizationSystemService$Lifecycle";
    private static final String UPDATABLE_DEVICE_CONFIG_SERVICE_CLASS =
            "com.android.server.deviceconfig.DeviceConfigInit$Lifecycle";


    /*
     * Implementation class names and jar locations for services in
     * {@code STANDALONE_SYSTEMSERVER_JARS}.
     */
    private static final String STATS_COMPANION_APEX_PATH =
            "/apex/com.android.os.statsd/javalib/service-statsd.jar";
    private static final String STATS_COMPANION_LIFECYCLE_CLASS =
            "com.android.server.stats.StatsCompanion$Lifecycle";
    private static final String SCHEDULING_APEX_PATH =
            "/apex/com.android.scheduling/javalib/service-scheduling.jar";
    private static final String REBOOT_READINESS_LIFECYCLE_CLASS =
            "com.android.server.scheduling.RebootReadinessManagerService$Lifecycle";
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
    private static final String CONNECTIVITY_SERVICE_APEX_PATH =
            "/apex/com.android.tethering/javalib/service-connectivity.jar";
    private static final String CONNECTIVITY_SERVICE_INITIALIZER_CLASS =
            "com.android.server.ConnectivityServiceInitializer";
    private static final String NETWORK_STATS_SERVICE_INITIALIZER_CLASS =
            "com.android.server.NetworkStatsServiceInitializer";
    private static final String UWB_APEX_SERVICE_JAR_PATH =
            "/apex/com.android.uwb/javalib/service-uwb.jar";
    private static final String UWB_SERVICE_CLASS = "com.android.server.uwb.UwbService";
    private static final String BLUETOOTH_APEX_SERVICE_JAR_PATH =
            "/apex/com.android.btservices/javalib/service-bluetooth.jar";
    private static final String BLUETOOTH_SERVICE_CLASS =
            "com.android.server.bluetooth.BluetoothService";
    private static final String DEVICE_LOCK_SERVICE_CLASS =
            "com.android.server.devicelock.DeviceLockService";
    private static final String DEVICE_LOCK_APEX_PATH =
            "/apex/com.android.devicelock/javalib/service-devicelock.jar";
    private static final String PROFILING_SERVICE_LIFECYCLE_CLASS =
            "android.os.profiling.ProfilingService$Lifecycle";
    private static final String PROFILING_SERVICE_JAR_PATH =
            "/apex/com.android.profiling/javalib/service-profiling.jar";

    private static final String RANGING_APEX_SERVICE_JAR_PATH =
            "/apex/com.android.uwb/javalib/service-ranging.jar";
    private static final String RANGING_SERVICE_CLASS = "com.android.server.ranging.RangingService";

    private static final String TETHERING_CONNECTOR_CLASS = "android.net.ITetheringConnector";

    private static final String PERSISTENT_DATA_BLOCK_PROP = "ro.frp.pst";

    private static final String UNCRYPT_PACKAGE_FILE = "/cache/recovery/uncrypt_file";
    private static final String BLOCK_MAP_FILE = "/cache/recovery/block.map";

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
    private long mIncrementalServiceHandle = 0;

    private boolean mFirstBoot;
    private final int mStartCount;
    private final boolean mRuntimeRestart;
    private final long mRuntimeStartElapsedTime;
    private final long mRuntimeStartUptime;

    private static final String START_HIDL_SERVICES = "StartHidlServices";
    private static final String START_SENSOR_MANAGER_SERVICE = "StartISensorManagerService";
    private static final String START_BLOB_STORE_SERVICE = "startBlobStoreManagerService";

    private static final String SYSPROP_START_COUNT = "sys.system_server.start_count";
    private static final String SYSPROP_START_ELAPSED = "sys.system_server.start_elapsed";
    private static final String SYSPROP_START_UPTIME = "sys.system_server.start_uptime";

    private Future<?> mZygotePreload;

    private final SystemServerDumper mDumper = new SystemServerDumper();

    /**
     * The pending WTF to be logged into dropbox.
     */
    private static LinkedList<Pair<String, ApplicationErrorReport.CrashInfo>> sPendingWtfs;

    /** Start the IStats services. This is a blocking call and can take time. */
    private static native void startIStatsService();

    /** Start the ISensorManager service. This is a blocking call and can take time. */
    private static native void startISensorManagerService();

    /**
     * Start the memtrack proxy service.
     */
    private static native void startMemtrackProxyService();

    /**
     * Start all HIDL services that are run inside the system server. This may take some time.
     */
    private static native void startHidlServices();

    /**
     * Mark this process' heap as profileable. Only for debug builds.
     */
    private static native void initZygoteChildHeapProfiling();

    private static final String SYSPROP_FDTRACK_ENABLE_THRESHOLD =
            "persist.sys.debug.fdtrack_enable_threshold";
    private static final String SYSPROP_FDTRACK_ABORT_THRESHOLD =
            "persist.sys.debug.fdtrack_abort_threshold";
    private static final String SYSPROP_FDTRACK_INTERVAL =
            "persist.sys.debug.fdtrack_interval";

    private static int getMaxFd() {
        FileDescriptor fd = null;
        try {
            fd = Os.open("/dev/null", O_RDONLY | O_CLOEXEC, 0);
            return fd.getInt$();
        } catch (ErrnoException ex) {
            Slog.e("System", "Failed to get maximum fd: " + ex);
        } finally {
            if (fd != null) {
                try {
                    Os.close(fd);
                } catch (ErrnoException ex) {
                    // If Os.close threw, something went horribly wrong.
                    throw new RuntimeException(ex);
                }
            }
        }

        return Integer.MAX_VALUE;
    }

    private static native void fdtrackAbort();

    private static final File HEAP_DUMP_PATH = new File("/data/system/heapdump/");
    private static final int MAX_HEAP_DUMPS = 2;

    /**
     * Dump system_server's heap.
     *
     * For privacy reasons, these aren't automatically pulled into bugreports:
     * they must be manually pulled by the user.
     */
    private static void dumpHprof() {
        // hprof dumps are rather large, so ensure we don't fill the disk by generating
        // hundreds of these that will live forever.
        TreeSet<File> existingTombstones = new TreeSet<>();
        for (File file : HEAP_DUMP_PATH.listFiles()) {
            if (!file.isFile()) {
                continue;
            }
            if (!file.getName().startsWith("fdtrack-")) {
                continue;
            }
            existingTombstones.add(file);
        }
        if (existingTombstones.size() >= MAX_HEAP_DUMPS) {
            for (int i = 0; i < MAX_HEAP_DUMPS - 1; ++i) {
                // Leave the newest `MAX_HEAP_DUMPS - 1` tombstones in place.
                existingTombstones.pollLast();
            }
            for (File file : existingTombstones) {
                if (!file.delete()) {
                    Slog.w("System", "Failed to clean up hprof " + file);
                }
            }
        }

        try {
            String date = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date());
            String filename = "/data/system/heapdump/fdtrack-" + date + ".hprof";
            Debug.dumpHprofData(filename);
        } catch (IOException ex) {
            Slog.e("System", "Failed to dump fdtrack hprof", ex);
        }
    }

    /**
     * Spawn a thread that monitors for fd leaks.
     */
    private static void spawnFdLeakCheckThread() {
        final int enableThreshold = SystemProperties.getInt(SYSPROP_FDTRACK_ENABLE_THRESHOLD, 1600);
        final int abortThreshold = SystemProperties.getInt(SYSPROP_FDTRACK_ABORT_THRESHOLD, 3000);
        final int checkInterval = SystemProperties.getInt(SYSPROP_FDTRACK_INTERVAL, 120);

        new Thread(() -> {
            boolean enabled = false;
            long nextWrite = 0;

            while (true) {
                int maxFd = getMaxFd();
                if (maxFd > enableThreshold) {
                    // Do a manual GC to clean up fds that are hanging around as garbage.
                    System.gc();
                    System.runFinalization();
                    maxFd = getMaxFd();
                }

                if (maxFd > enableThreshold && !enabled) {
                    Slog.i("System", "fdtrack enable threshold reached, enabling");
                    FrameworkStatsLog.write(FrameworkStatsLog.FDTRACK_EVENT_OCCURRED,
                            FrameworkStatsLog.FDTRACK_EVENT_OCCURRED__EVENT__ENABLED,
                            maxFd);

                    System.loadLibrary("fdtrack");
                    enabled = true;
                } else if (maxFd > abortThreshold) {
                    Slog.i("System", "fdtrack abort threshold reached, dumping and aborting");
                    FrameworkStatsLog.write(FrameworkStatsLog.FDTRACK_EVENT_OCCURRED,
                            FrameworkStatsLog.FDTRACK_EVENT_OCCURRED__EVENT__ABORTING,
                            maxFd);

                    dumpHprof();
                    fdtrackAbort();
                } else {
                    // Limit this to once per hour.
                    long now = SystemClock.elapsedRealtime();
                    if (now > nextWrite) {
                        nextWrite = now + 60 * 60 * 1000;
                        FrameworkStatsLog.write(FrameworkStatsLog.FDTRACK_EVENT_OCCURRED,
                                enabled ? FrameworkStatsLog.FDTRACK_EVENT_OCCURRED__EVENT__ENABLED
                                        : FrameworkStatsLog.FDTRACK_EVENT_OCCURRED__EVENT__DISABLED,
                                maxFd);
                    }
                }

                try {
                    Thread.sleep(checkInterval * 1000);
                } catch (InterruptedException ex) {
                    continue;
                }
            }
        }).start();
    }

    /**
     * Start native Incremental Service and get its handle.
     */
    private static native long startIncrementalService();

    /**
     * Inform Incremental Service that system is ready.
     */
    private static native void setIncrementalServiceSystemReady(long incrementalServiceHandle);

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
        mStartCount = SystemProperties.getInt(SYSPROP_START_COUNT, 0) + 1;
        mRuntimeStartElapsedTime = SystemClock.elapsedRealtime();
        mRuntimeStartUptime = SystemClock.uptimeMillis();
        Process.setStartTimes(mRuntimeStartElapsedTime, mRuntimeStartUptime,
                mRuntimeStartElapsedTime, mRuntimeStartUptime);

        // Remember if it's runtime restart or reboot.
        mRuntimeRestart = mStartCount > 1;
    }

    @Override
    public String getDumpableName() {
        return SystemServer.class.getSimpleName();
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.printf("Runtime restart: %b\n", mRuntimeRestart);
        pw.printf("Start count: %d\n", mStartCount);
        pw.print("Runtime start-up time: ");
        TimeUtils.formatDuration(mRuntimeStartUptime, pw); pw.println();
        pw.print("Runtime start-elapsed time: ");
        TimeUtils.formatDuration(mRuntimeStartElapsedTime, pw); pw.println();
    }

    /**
     * Service used to dump {@link SystemServer} state that is not associated with any service.
     *
     * <p>To dump all services:
     *
     * <pre><code>adb shell dumpsys system_server_dumper</code></pre>
     *
     * <p>To get a list of all services:
     *
     * <pre><code>adb shell dumpsys system_server_dumper --list</code></pre>
     *
     * <p>To dump a specific service (use {@code --list} above to get service names):
     *
     * <pre><code>adb shell dumpsys system_server_dumper --name NAME</code></pre>
     */
    private final class SystemServerDumper extends Binder {

        @GuardedBy("mDumpables")
        private final ArrayMap<String, Dumpable> mDumpables = new ArrayMap<>(4);

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            final boolean hasArgs = args != null && args.length > 0;

            synchronized (mDumpables) {
                if (hasArgs && "--list".equals(args[0])) {
                    final int dumpablesSize = mDumpables.size();
                    for (int i = 0; i < dumpablesSize; i++) {
                        pw.println(mDumpables.keyAt(i));
                    }
                    return;
                }

                if (hasArgs && "--name".equals(args[0])) {
                    if (args.length < 2) {
                        pw.println("Must pass at least one argument to --name");
                        return;
                    }
                    final String name = args[1];
                    final Dumpable dumpable = mDumpables.get(name);
                    if (dumpable == null) {
                        pw.printf("No dumpable named %s\n", name);
                        return;
                    }

                    try (IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ")) {
                        // Strip --name DUMPABLE from args
                        final String[] actualArgs = Arrays.copyOfRange(args, 2, args.length);
                        dumpable.dump(ipw, actualArgs);
                    }
                    return;
                }

                final int dumpablesSize = mDumpables.size();
                try (IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ")) {
                    for (int i = 0; i < dumpablesSize; i++) {
                        final Dumpable dumpable = mDumpables.valueAt(i);
                        ipw.printf("%s:\n", dumpable.getDumpableName());
                        ipw.increaseIndent();
                        dumpable.dump(ipw, args);
                        ipw.decreaseIndent();
                        ipw.println();
                    }
                }
            }
        }

        private void addDumpable(@NonNull Dumpable dumpable) {
            synchronized (mDumpables) {
                mDumpables.put(dumpable.getDumpableName(), dumpable);
            }
        }
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

            // Set the device's time zone (a system property) if it is not set or is invalid.
            SystemTimeZone.initializeTimeZoneSettingsIfRequired();

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
                FrameworkStatsLog.write(FrameworkStatsLog.BOOT_TIME_EVENT_ELAPSED_TIME_REPORTED,
                        FrameworkStatsLog
                                .BOOT_TIME_EVENT_ELAPSED_TIME__EVENT__SYSTEM_SERVER_INIT_START,
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

            SystemServiceRegistry.sEnableServiceNotFoundWtf = true;

            // Initialize native services.
            System.loadLibrary("android_servers");

            // Allow heap / perf profiling.
            initZygoteChildHeapProfiling();

            // Debug builds - spawn a thread to monitor for fd leaks.
            if (Build.IS_DEBUGGABLE) {
                spawnFdLeakCheckThread();
            }

            // Check whether we failed to shut down last time we tried.
            // This call may not return.
            performPendingShutdown();

            // Initialize the system context.
            createSystemContext();

            // Call per-process mainline module initialization.
            ActivityThread.initializeMainlineModules();

            // Sets the dumper service
            ServiceManager.addService("system_server_dumper", mDumper);
            mDumper.addDumpable(this);

            // Create the system service manager.
            mSystemServiceManager = new SystemServiceManager(mSystemContext);
            mSystemServiceManager.setStartInfo(mRuntimeRestart,
                    mRuntimeStartElapsedTime, mRuntimeStartUptime);
            mDumper.addDumpable(mSystemServiceManager);

            LocalServices.addService(SystemServiceManager.class, mSystemServiceManager);
            // Prepare the thread pool for init tasks that can be parallelized
            SystemServerInitThreadPool tp = SystemServerInitThreadPool.start();
            mDumper.addDumpable(tp);

            // Lazily load the pre-installed system font map in SystemServer only if we're not doing
            // the optimized font loading in the FontManagerService.
            if (!com.android.text.flags.Flags.useOptimizedBoottimeFontLoading()
                    && Typeface.ENABLE_LAZY_TYPEFACE_INITIALIZATION) {
                Slog.i(TAG, "Loading pre-installed system font map.");
                Typeface.loadPreinstalledSystemFontMap();
            }

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

        // Setup the default WTF handler
        RuntimeInit.setDefaultApplicationWtfHandler(SystemServer::handleEarlySystemWtf);

        // Initialize the application shared memory region.
        // This needs to happen before any system services are started,
        // as they may rely on the shared memory region having been initialized.
        ApplicationSharedMemory instance = ApplicationSharedMemory.create();
        ApplicationSharedMemory.setInstance(instance);

        // Start services.
        try {
            t.traceBegin("StartServices");
            startBootstrapServices(t);
            startCoreServices(t);
            startOtherServices(t);
            startApexServices(t);
            // Only update the timeout after starting all the services so that we use
            // the default timeout to start system server.
            updateWatchdogTimeout(t);
            CriticalEventLog.getInstance().logSystemServerStarted();
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
            FrameworkStatsLog.write(FrameworkStatsLog.BOOT_TIME_EVENT_ELAPSED_TIME_REPORTED,
                    FrameworkStatsLog.BOOT_TIME_EVENT_ELAPSED_TIME__EVENT__SYSTEM_SERVER_READY,
                    uptimeMillis);
            final long maxUptimeMillis = 60 * 1000;
            if (uptimeMillis > maxUptimeMillis) {
                Slog.wtf(SYSTEM_SERVER_TIMING_TAG,
                        "SystemServer init took too long. uptimeMillis=" + uptimeMillis);
            }
        }

        // Set binder transaction callback after starting system services
        Binder.setTransactionCallback(new IBinderCallback() {
            @Override
            public void onTransactionError(int pid, int code, int flags, int err) {
                mActivityManagerService.frozenBinderTransactionDetected(pid, code, flags, err);
            }
        });

        // Loop forever.
        Looper.loop();
        throw new RuntimeException("Main thread loop unexpectedly exited");
    }

    private static boolean isValidTimeZoneId(String timezoneProperty) {
        return timezoneProperty != null
                && !timezoneProperty.isEmpty()
                && ZoneInfoDb.getInstance().hasTimeZone(timezoneProperty);
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
                    ShutdownThread.rebootOrShutdown(null, reboot, reason);
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
        Trace.registerWithPerfetto();
    }

    /**
     * Starts the small tangle of critical services that are needed to get the system off the
     * ground.  These services have complex mutual dependencies which is why we initialize them all
     * in one place here.  Unless your service is also entwined in these dependencies, it should be
     * initialized in one of the other functions.
     */
    private void startBootstrapServices(@NonNull TimingsTraceAndSlog t) {
        t.traceBegin("startBootstrapServices");

        t.traceBegin("ArtModuleServiceInitializer");
        // This needs to happen before DexUseManagerLocal init. We do it here to avoid colliding
        // with a GC. ArtModuleServiceInitializer is a class from a separate dex file
        // "service-art.jar", so referencing it involves the class linker. The class linker and the
        // GC are mutually exclusive (b/263486535). Therefore, we do this here to force trigger the
        // class linker earlier. If we did this later, especially after PackageManagerService init,
        // the class linker would be consistently blocked by a GC because PackageManagerService
        // allocates a lot of memory and almost certainly triggers a GC.
        ArtModuleServiceInitializer.setArtModuleServiceManager(new ArtModuleServiceManager());
        t.traceEnd();

        // Start the watchdog as early as possible so we can crash the system server
        // if we deadlock during early boot
        t.traceBegin("StartWatchdog");
        final Watchdog watchdog = Watchdog.getInstance();
        watchdog.start();
        mDumper.addDumpable(watchdog);
        t.traceEnd();

        Slog.i(TAG, "Reading configuration...");
        final String TAG_SYSTEM_CONFIG = "ReadingSystemConfig";
        t.traceBegin(TAG_SYSTEM_CONFIG);
        SystemServerInitThreadPool.submit(SystemConfig::getInstance, TAG_SYSTEM_CONFIG);
        t.traceEnd();

        // Orchestrates some ProtoLogging functionality.
        if (android.tracing.Flags.clientSideProtoLogging()) {
            t.traceBegin("StartProtoLogConfigurationService");
            ServiceManager.addService(
                    Context.PROTOLOG_CONFIGURATION_SERVICE, new ProtoLogConfigurationServiceImpl());
            t.traceEnd();
        }

        t.traceBegin("InitializeProtoLog");
        ProtoLog.init(WmProtoLogGroups.values());
        t.traceEnd();

        // Platform compat service is used by ActivityManagerService, PackageManagerService, and
        // possibly others in the future. b/135010838.
        t.traceBegin("PlatformCompat");
        PlatformCompat platformCompat = new PlatformCompat(mSystemContext);
        ServiceManager.addService(Context.PLATFORM_COMPAT_SERVICE, platformCompat);
        ServiceManager.addService(Context.PLATFORM_COMPAT_NATIVE_SERVICE,
                new PlatformCompatNative(platformCompat));
        AppCompatCallbacks.install(new long[0], new long[0]);
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

        // Starts a service for reading runtime flag overrides, and keeping processes
        // in sync with one another.
        t.traceBegin("StartFeatureFlagsService");
        mSystemServiceManager.startService(FeatureFlagsService.class);
        t.traceEnd();

        // Uri Grants Manager.
        t.traceBegin("UriGrantsManagerService");
        mSystemServiceManager.startService(UriGrantsManagerService.Lifecycle.class);
        t.traceEnd();

        t.traceBegin("StartPowerStatsService");
        // Tracks rail data to be used for power statistics.
        mSystemServiceManager.startService(PowerStatsService.class);
        t.traceEnd();

        t.traceBegin("StartIStatsService");
        startIStatsService();
        t.traceEnd();

        // Start MemtrackProxyService before ActivityManager, so that early calls
        // to Memtrack::getMemory() don't fail.
        t.traceBegin("MemtrackProxyService");
        startMemtrackProxyService();
        t.traceEnd();

        // Start AccessCheckingService which provides new implementation for permission and app op.
        t.traceBegin("StartAccessCheckingService");
        LocalServices.addService(PermissionMigrationHelper.class,
                new PermissionMigrationHelperImpl());
        LocalServices.addService(AppOpMigrationHelper.class,
                new AppOpMigrationHelperImpl());
        mSystemServiceManager.startService(AccessCheckingService.class);
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
        t.traceBegin("StartIncrementalService");
        mIncrementalServiceHandle = startIncrementalService();
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

        if (!Flags.refactorCrashrecovery()) {
            // Initialize RescueParty.
            CrashRecoveryAdaptor.rescuePartyRegisterHealthObserver(mSystemContext);
            if (!Flags.recoverabilityDetection()) {
                // Now that we have the bare essentials of the OS up and running, take
                // note that we just booted, which might send out a rescue party if
                // we're stuck in a runtime restart loop.
                CrashRecoveryAdaptor.packageWatchdogNoteBoot(mSystemContext);
            }
        }


        // Manages LEDs and display backlight so we need it to bring up the display.
        t.traceBegin("StartLightsService");
        mSystemServiceManager.startService(LightsService.class);
        t.traceEnd();

        t.traceBegin("StartDisplayOffloadService");
        // Package manager isn't started yet; need to use SysProp not hardware feature
        if (SystemProperties.getBoolean("config.enable_display_offload", false)) {
            mSystemServiceManager.startService(WEAR_DISPLAYOFFLOAD_SERVICE_CLASS);
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

        // Start the package manager.
        if (!mRuntimeRestart) {
            FrameworkStatsLog.write(FrameworkStatsLog.BOOT_TIME_EVENT_ELAPSED_TIME_REPORTED,
                    FrameworkStatsLog
                            .BOOT_TIME_EVENT_ELAPSED_TIME__EVENT__PACKAGE_MANAGER_INIT_START,
                    SystemClock.elapsedRealtime());
        }

        t.traceBegin("StartDomainVerificationService");
        DomainVerificationService domainVerificationService = new DomainVerificationService(
                mSystemContext, SystemConfig.getInstance(), platformCompat);
        mSystemServiceManager.startService(domainVerificationService);
        t.traceEnd();

        t.traceBegin("StartPackageManagerService");
        try {
            Watchdog.getInstance().pauseWatchingCurrentThread("packagemanagermain");
            mPackageManagerService = PackageManagerService.main(
                    mSystemContext, installer, domainVerificationService,
                    mFactoryTestMode != FactoryTest.FACTORY_TEST_OFF);
        } finally {
            Watchdog.getInstance().resumeWatchingCurrentThread("packagemanagermain");
        }

        mFirstBoot = mPackageManagerService.isFirstBoot();
        mPackageManager = mSystemContext.getPackageManager();
        t.traceEnd();

        t.traceBegin("DexUseManagerLocal");
        // DexUseManagerLocal needs to be loaded after PackageManagerLocal has been registered, but
        // before PackageManagerService starts processing binder calls to notifyDexLoad.
        LocalManagerRegistry.addManager(
                DexUseManagerLocal.class, DexUseManagerLocal.createInstance(mSystemContext));
        t.traceEnd();

        if (!mRuntimeRestart && !isFirstBootOrUpgrade()) {
            FrameworkStatsLog.write(FrameworkStatsLog.BOOT_TIME_EVENT_ELAPSED_TIME_REPORTED,
                    FrameworkStatsLog
                            .BOOT_TIME_EVENT_ELAPSED_TIME__EVENT__PACKAGE_MANAGER_INIT_READY,
                    SystemClock.elapsedRealtime());
        }
        // Manages A/B OTA dexopting. This is a bootstrap service as we need it to rename
        // A/B artifacts after boot, before anything else might touch/need them.
        boolean disableOtaDexopt = SystemProperties.getBoolean("config.disable_otadexopt", false);
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

        if (Build.IS_ARC) {
            t.traceBegin("StartArcSystemHealthService");
            mSystemServiceManager.startService(ARC_SYSTEM_HEALTH_SERVICE);
            t.traceEnd();
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

        // The package receiver depends on the activity service in order to get registered.
        platformCompat.registerPackageReceiver(mSystemContext);

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

        // Manages Resources packages
        t.traceBegin("StartResourcesManagerService");
        ResourcesManagerService resourcesService = new ResourcesManagerService(mSystemContext);
        resourcesService.setActivityManagerService(mActivityManagerService);
        mSystemServiceManager.startService(resourcesService);
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
        t.traceBegin("StartSensorService");
        mSystemServiceManager.startService(SensorService.class);
        t.traceEnd();
        t.traceEnd(); // startBootstrapServices
    }

    /**
     * Starts some essential services that are not tangled up in the bootstrap process.
     */
    private void startCoreServices(@NonNull TimingsTraceAndSlog t) {
        t.traceBegin("startCoreServices");

        // Service for system config
        t.traceBegin("StartSystemConfigService");
        mSystemServiceManager.startService(SystemConfigService.class);
        t.traceEnd();

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

        // Tracks native tombstones.
        t.traceBegin("StartNativeTombstoneManagerService");
        mSystemServiceManager.startService(NativeTombstoneManagerService.class);
        t.traceEnd();

        // Service to capture bugreports.
        t.traceBegin("StartBugreportManagerService");
        mSystemServiceManager.startService(BugreportManagerService.class);
        t.traceEnd();

        // Service for GPU and GPU driver.
        t.traceBegin("GpuService");
        mSystemServiceManager.startService(GpuService.class);
        t.traceEnd();

        // Handles system process requests for remotely provisioned keys & data.
        t.traceBegin("StartRemoteProvisioningService");
        mSystemServiceManager.startService(RemoteProvisioningService.class);
        t.traceEnd();

        // TODO(b/277600174): Start CpuMonitorService on all builds and not just on debuggable
        // builds once the Android JobScheduler starts using this service.
        if (Build.IS_DEBUGGABLE || Build.IS_ENG) {
          // Service for CPU monitor.
          t.traceBegin("CpuMonitorService");
          mSystemServiceManager.startService(CpuMonitorService.class);
          t.traceEnd();
        }

        t.traceEnd(); // startCoreServices
    }

    /**
     * Starts a miscellaneous grab bag of stuff that has yet to be refactored and organized.
     */
    private void startOtherServices(@NonNull TimingsTraceAndSlog t) {
        t.traceBegin("startOtherServices");
        mSystemServiceManager.updateOtherServicesStartIndex();

        final Context context = mSystemContext;
        DynamicSystemService dynamicSystem = null;
        IStorageManager storageManager = null;
        NetworkManagementService networkManagement = null;
        VpnManagerService vpnManager = null;
        VcnManagementService vcnManagement = null;
        NetworkPolicyManagerService networkPolicy = null;
        WindowManagerService wm = null;
        NetworkTimeUpdateService networkTimeUpdater = null;
        InputManagerService inputManager = null;
        TelephonyRegistry telephonyRegistry = null;
        ConsumerIrService consumerIr = null;
        MmsServiceBroker mmsService = null;
        HardwarePropertiesManagerService hardwarePropertiesService = null;
        PacProxyService pacProxyService = null;

        boolean disableSystemTextClassifier = SystemProperties.getBoolean(
                "config.disable_systemtextclassifier", false);

        boolean disableNetworkTime = SystemProperties.getBoolean("config.disable_networktime",
                false);
        boolean disableCameraService = SystemProperties.getBoolean("config.disable_cameraservice",
                false);

        boolean isWatch = context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WATCH);

        boolean isArc = context.getPackageManager().hasSystemFeature(
                "org.chromium.arc");

        boolean isTv = context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_LEANBACK);

        boolean enableVrService = context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_VR_MODE_HIGH_PERFORMANCE);

        if (!Flags.recoverabilityDetection()) {
            // For debugging RescueParty
            if (Build.IS_DEBUGGABLE
                    && SystemProperties.getBoolean("debug.crash_system", false)) {
                throw new RuntimeException();
            }
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
                    String[] abis32 = Build.SUPPORTED_32_BIT_ABIS;
                    if (abis32.length > 0 && !Process.ZYGOTE_PROCESS.preloadDefault(abis32[0])) {
                        Slog.e(TAG, "Unable to preload default resources for secondary");
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

            t.traceBegin("StartBinaryTransparencyService");
            mSystemServiceManager.startService(BinaryTransparencyService.class);
            t.traceEnd();

            t.traceBegin("StartSchedulingPolicyService");
            ServiceManager.addService("scheduling_policy", new SchedulingPolicyService());
            t.traceEnd();

            // TelecomLoader hooks into classes with defined HFP logic,
            // so check for either telephony or microphone.
            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
                    || mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELECOM)
                    || mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
                t.traceBegin("StartTelecomLoaderService");
                mSystemServiceManager.startService(TelecomLoaderService.class);
                t.traceEnd();
            }

            t.traceBegin("StartTelephonyRegistry");
            telephonyRegistry = new TelephonyRegistry(
                    context, new TelephonyRegistry.ConfigurationProvider());
            ServiceManager.addService("telephony.registry", telephonyRegistry);
            t.traceEnd();

            t.traceBegin("StartEntropyMixer");
            mEntropyMixer = new EntropyMixer(context);
            t.traceEnd();

            mContentResolver = context.getContentResolver();

            // The AccountManager must come before the ContentService
            t.traceBegin("StartAccountManagerService");
            mSystemServiceManager.startService(AccountManagerService.Lifecycle.class);
            t.traceEnd();

            t.traceBegin("StartContentService");
            mSystemServiceManager.startService(ContentService.Lifecycle.class);
            t.traceEnd();

            t.traceBegin("InstallSystemProviders");
            mActivityManagerService.getContentProviderHelper().installSystemProviders();
            // Device configuration used to be part of System providers
            mSystemServiceManager.startService(UPDATABLE_DEVICE_CONFIG_SERVICE_CLASS);
            // Now that SettingsProvider is ready, reactivate SQLiteCompatibilityWalFlags
            SQLiteCompatibilityWalFlags.reset();
            t.traceEnd();

            // Records errors and logs, for example wtf()
            // Currently this service indirectly depends on SettingsProvider so do this after
            // InstallSystemProviders.
            t.traceBegin("StartDropBoxManager");
            mSystemServiceManager.startService(DropBoxManagerService.class);
            t.traceEnd();

            if (android.permission.flags.Flags.enhancedConfirmationModeApisEnabled()) {
                t.traceBegin("StartEnhancedConfirmationService");
                mSystemServiceManager.startService(ENHANCED_CONFIRMATION_SERVICE_CLASS);
                t.traceEnd();
            }

            t.traceBegin("StartHintManager");
            mSystemServiceManager.startService(HintManagerService.class);
            t.traceEnd();

            // Grants default permissions and defines roles
            t.traceBegin("StartRoleManagerService");
            LocalManagerRegistry.addManager(RoleServicePlatformHelper.class,
                    new RoleServicePlatformHelperImpl(mSystemContext));
            mSystemServiceManager.startService(ROLE_SERVICE_CLASS);
            t.traceEnd();

            if (!isWatch && android.app.supervision.flags.Flags.supervisionApi()) {
                t.traceBegin("StartSupervisionService");
                mSystemServiceManager.startService(SupervisionService.Lifecycle.class);
                t.traceEnd();
            }

            if (!isTv) {
                t.traceBegin("StartVibratorManagerService");
                mSystemServiceManager.startService(VibratorManagerService.Lifecycle.class);
                t.traceEnd();
            }

            t.traceBegin("StartDynamicSystemService");
            dynamicSystem = new DynamicSystemService(context);
            ServiceManager.addService("dynamic_system", dynamicSystem);
            t.traceEnd();

            if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR)) {
                t.traceBegin("StartConsumerIrService");
                consumerIr = new ConsumerIrService(context);
                ServiceManager.addService(Context.CONSUMER_IR_SERVICE, consumerIr);
                t.traceEnd();
            }

            // TODO(aml-jobscheduler): Think about how to do it properly.
            t.traceBegin("StartAlarmManagerService");
            mSystemServiceManager.startService(AlarmManagerService.class);
            t.traceEnd();

            t.traceBegin("StartInputManagerService");
            inputManager = new InputManagerService(context);
            t.traceEnd();

            t.traceBegin("DeviceStateManagerService");
            mSystemServiceManager.startService(DeviceStateManagerService.class);
            t.traceEnd();

            if (!disableCameraService) {
                t.traceBegin("StartCameraServiceProxy");
                mSystemServiceManager.startService(CameraServiceProxy.class);
                t.traceEnd();
            }

            t.traceBegin("StartWindowManagerService");
            // WMS needs sensor service ready
            mSystemServiceManager.startBootPhase(t, SystemService.PHASE_WAIT_FOR_SENSOR_SERVICE);
            wm = WindowManagerService.main(context, inputManager, !mFirstBoot,
                    new PhoneWindowManager(), mActivityManagerService.mActivityTaskManager);
            ServiceManager.addService(Context.WINDOW_SERVICE, wm, /* allowIsolated= */ false,
                    DUMP_FLAG_PRIORITY_CRITICAL | DUMP_FLAG_PRIORITY_HIGH
                            | DUMP_FLAG_PROTO);
            ServiceManager.addService(Context.INPUT_SERVICE, inputManager,
                    /* allowIsolated= */ false, DUMP_FLAG_PRIORITY_CRITICAL);
            t.traceEnd();

            t.traceBegin("SetWindowManagerService");
            mActivityManagerService.setWindowManager(wm);
            t.traceEnd();

            t.traceBegin("WindowManagerServiceOnInitReady");
            wm.onInitReady();
            t.traceEnd();

            // Start receiving calls from SensorManager services. Start in a separate thread
            // because it need to connect to SensorManager. This has to start
            // after PHASE_WAIT_FOR_SENSOR_SERVICE is done.
            SystemServerInitThreadPool.submit(() -> {
                TimingsTraceAndSlog traceLog = TimingsTraceAndSlog.newAsyncLog();
                traceLog.traceBegin(START_SENSOR_MANAGER_SERVICE);
                startISensorManagerService();
                traceLog.traceEnd();
            }, START_SENSOR_MANAGER_SERVICE);

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
                mSystemServiceManager.startServiceFromJar(BLUETOOTH_SERVICE_CLASS,
                    BLUETOOTH_APEX_SERVICE_JAR_PATH);
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

            if (Build.IS_DEBUGGABLE && ProfcollectForwardingService.enabled()) {
                t.traceBegin("ProfcollectForwardingService");
                mSystemServiceManager.startService(ProfcollectForwardingService.class);
                t.traceEnd();
            }

            t.traceBegin("SignedConfigService");
            SignedConfigService.registerUpdateReceiver(mSystemContext);
            t.traceEnd();

            t.traceBegin("AppIntegrityService");
            mSystemServiceManager.startService(AppIntegrityManagerService.class);
            t.traceEnd();

            t.traceBegin("StartLogcatManager");
            mSystemServiceManager.startService(LogcatManagerService.class);
            t.traceEnd();

            if (AppFunctionManagerConfiguration.isSupported(context)) {
                t.traceBegin("StartAppFunctionManager");
                mSystemServiceManager.startService(AppFunctionManagerService.class);
                t.traceEnd();
            }
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
        } else if (context.getResources().getBoolean(R.bool.config_autoResetAirplaneMode)) {
            Settings.Global.putInt(context.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0);
        }

        StatusBarManagerService statusBar = null;
        INotificationManager notification = null;
        CountryDetectorService countryDetector = null;
        ILockSettings lockSettings = null;
        MediaRouterService mediaRouter = null;

        // Bring up services needed for UI.
        if (mFactoryTestMode != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
            t.traceBegin("StartInputMethodManagerLifecycle");
            String immsClassName = context.getResources().getString(
                    R.string.config_deviceSpecificInputMethodManagerService);
            if (immsClassName.isEmpty()) {
                mSystemServiceManager.startService(InputMethodManagerService.Lifecycle.class);
            } else {
                try {
                    Slog.i(TAG, "Starting custom IMMS: " + immsClassName);
                    mSystemServiceManager.startService(immsClassName);
                } catch (Throwable e) {
                    reportWtf("starting " + immsClassName, e);
                }
            }
            t.traceEnd();

            t.traceBegin("StartAccessibilityManagerService");
            try {
                mSystemServiceManager.startService(AccessibilityManagerService.Lifecycle.class);
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
                    mSystemServiceManager.startService(StorageManagerService.Lifecycle.class);
                    storageManager = IStorageManager.Stub.asInterface(
                            ServiceManager.getService("mount"));
                } catch (Throwable e) {
                    reportWtf("starting StorageManagerService", e);
                }
                t.traceEnd();

                t.traceBegin("StartStorageStatsService");
                try {
                    mSystemServiceManager.startService(StorageStatsService.Lifecycle.class);
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

        t.traceBegin("StartLocaleManagerService");
        try {
            mSystemServiceManager.startService(LocaleManagerService.class);
        } catch (Throwable e) {
            reportWtf("starting LocaleManagerService service", e);
        }
        t.traceEnd();

        t.traceBegin("StartGrammarInflectionService");
        try {
            mSystemServiceManager.startService(GrammaticalInflectionService.class);
        } catch (Throwable e) {
            reportWtf("starting GrammarInflectionService service", e);
        }
        t.traceEnd();

        t.traceBegin("StartAppHibernationService");
        mSystemServiceManager.startService(AppHibernationService.class);
        t.traceEnd();

        t.traceBegin("ArtManagerLocal");
        DexOptHelper.initializeArtManagerLocal(context, mPackageManagerService);
        t.traceEnd();

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

        t.traceBegin("PerformFstrimIfNeeded");
        try {
            mPackageManagerService.performFstrimIfNeeded();
        } catch (Throwable e) {
            reportWtf("performing fstrim", e);
        }
        t.traceEnd();

        final DevicePolicyManagerService.Lifecycle dpms;
        if (mFactoryTestMode == FactoryTest.FACTORY_TEST_LOW_LEVEL) {
            dpms = null;
        } else {
            t.traceBegin("StartLockSettingsService");
            try {
                mSystemServiceManager.startService(LockSettingsService.Lifecycle.class);
                lockSettings = ILockSettings.Stub.asInterface(
                        ServiceManager.getService("lock_settings"));
            } catch (Throwable e) {
                reportWtf("starting LockSettingsService service", e);
            }
            t.traceEnd();

            final boolean hasPdb = !SystemProperties.get(PERSISTENT_DATA_BLOCK_PROP).equals("");
            if (hasPdb) {
                t.traceBegin("StartPersistentDataBlock");
                mSystemServiceManager.startService(PersistentDataBlockService.class);
                t.traceEnd();
            }

            if (Build.IS_ARC && SystemProperties.getInt("ro.boot.dev_mode", 0) == 1) {
                t.traceBegin("StartArcPersistentDataBlock");
                mSystemServiceManager.startService(ARC_PERSISTENT_DATA_BLOCK_SERVICE_CLASS);
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
            mSystemServiceManager.startService(DeviceIdleController.class);
            t.traceEnd();

            // Always start the Device Policy Manager, so that the API is compatible with
            // API8.
            t.traceBegin("StartDevicePolicyManager");
            dpms = mSystemServiceManager.startService(DevicePolicyManagerService.Lifecycle.class);
            t.traceEnd();

            t.traceBegin("StartStatusBarManagerService");
            try {
                statusBar = new StatusBarManagerService(context);
                statusBar.publishGlobalActionsProvider();
                ServiceManager.addService(Context.STATUS_BAR_SERVICE, statusBar, false,
                        DUMP_FLAG_PRIORITY_NORMAL | DUMP_FLAG_PROTO);
            } catch (Throwable e) {
                reportWtf("starting StatusBarManagerService", e);
            }
            t.traceEnd();

            if (deviceHasConfigString(context,
                    R.string.config_defaultMusicRecognitionService)) {
                t.traceBegin("StartMusicRecognitionManagerService");
                mSystemServiceManager.startService(MusicRecognitionManagerService.class);
                t.traceEnd();
            } else {
                Slog.d(TAG,
                        "MusicRecognitionManagerService not defined by OEM or disabled by flag");
            }

            startContentCaptureService(context, t);
            startAttentionService(context, t);
            startRotationResolverService(context, t);
            startSystemCaptionsManagerService(context, t);
            startTextToSpeechManagerService(context, t);
            if (!isWatch || !android.server.Flags.removeWearableSensingServiceFromWear()) {
                startWearableSensingService(t);
            } else {
                Slog.d(TAG, "Not starting WearableSensingService");
            }
            startOnDeviceIntelligenceService(t);

            if (deviceHasConfigString(
                    context, R.string.config_defaultAmbientContextDetectionService)) {
                t.traceBegin("StartAmbientContextService");
                mSystemServiceManager.startService(AmbientContextManagerService.class);
                t.traceEnd();
            } else {
                Slog.d(TAG, "AmbientContextManagerService not defined by OEM or disabled by flag");
            }

            // System Speech Recognition Service
            t.traceBegin("StartSpeechRecognitionManagerService");
            mSystemServiceManager.startService(SpeechRecognitionManagerService.class);
            t.traceEnd();

            // App prediction manager service
            if (deviceHasConfigString(context, R.string.config_defaultAppPredictionService)) {
                t.traceBegin("StartAppPredictionService");
                mSystemServiceManager.startService(AppPredictionManagerService.class);
                t.traceEnd();
            } else {
                Slog.d(TAG, "AppPredictionService not defined by OEM");
            }

            // Content suggestions manager service
            if (deviceHasConfigString(context, R.string.config_defaultContentSuggestionsService)) {
                t.traceBegin("StartContentSuggestionsService");
                mSystemServiceManager.startService(ContentSuggestionsManagerService.class);
                t.traceEnd();
            } else {
                Slog.d(TAG, "ContentSuggestionsService not defined by OEM");
            }

            // Search UI manager service
            if (deviceHasConfigString(context, R.string.config_defaultSearchUiService)) {
                t.traceBegin("StartSearchUiService");
                mSystemServiceManager.startService(SearchUiManagerService.class);
                t.traceEnd();
            }

            // Smartspace manager service
            if (deviceHasConfigString(context, R.string.config_defaultSmartspaceService)) {
                t.traceBegin("StartSmartspaceService");
                mSystemServiceManager.startService(SmartspaceManagerService.class);
                t.traceEnd();
            } else {
                Slog.d(TAG, "SmartspaceManagerService not defined by OEM or disabled by flag");
            }

            // Contextual search manager service
            if (deviceHasConfigString(context,
                    R.string.config_defaultContextualSearchPackageName)) {
                t.traceBegin("StartContextualSearchService");
                mSystemServiceManager.startService(ContextualSearchManagerService.class);
                t.traceEnd();
            } else {
                Slog.d(TAG, "ContextualSearchManagerService not defined or disabled by flag");
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

            t.traceBegin("StartFontManagerService");
            mSystemServiceManager.startService(new FontManagerService.Lifecycle(context, safeMode));
            t.traceEnd();

            if (!isWatch || !android.server.Flags.removeTextService()) {
                t.traceBegin("StartTextServicesManager");
                mSystemServiceManager.startService(TextServicesManagerService.Lifecycle.class);
                t.traceEnd();
            }

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
            // This has to be called before NetworkPolicyManager because NetworkPolicyManager
            // needs to take NetworkStatsService to initialize.
            mSystemServiceManager.startServiceFromJar(NETWORK_STATS_SERVICE_INITIALIZER_CLASS,
                    CONNECTIVITY_SERVICE_APEX_PATH);
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
                if (!isArc) {
                    t.traceBegin("StartWifi");
                    mSystemServiceManager.startServiceFromJar(
                            WIFI_SERVICE_CLASS, WIFI_APEX_SERVICE_JAR_PATH);
                    t.traceEnd();
                    t.traceBegin("StartWifiScanning");
                    mSystemServiceManager.startServiceFromJar(
                            WIFI_SCANNING_SERVICE_CLASS, WIFI_APEX_SERVICE_JAR_PATH);
                    t.traceEnd();
                }
            }

            // ARC - ArcNetworkService registers the ARC network stack and replaces the
            // stock WiFi service in both ARC++ container and ARCVM. Always starts the ARC network
            // stack regardless of whether FEATURE_WIFI is enabled/disabled (b/254755875).
            if (isArc) {
                t.traceBegin("StartArcNetworking");
                mSystemServiceManager.startService(ARC_NETWORK_SERVICE_CLASS);
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

            t.traceBegin("StartPacProxyService");
            try {
                pacProxyService = new PacProxyService(context);
                ServiceManager.addService(Context.PAC_PROXY_SERVICE, pacProxyService);
            } catch (Throwable e) {
                reportWtf("starting PacProxyService", e);
            }
            t.traceEnd();

            t.traceBegin("StartConnectivityService");
            // This has to be called after NetworkManagementService, NetworkStatsService
            // and NetworkPolicyManager because ConnectivityService needs to take these
            // services to initialize.
            mSystemServiceManager.startServiceFromJar(CONNECTIVITY_SERVICE_INITIALIZER_CLASS,
                    CONNECTIVITY_SERVICE_APEX_PATH);
            networkPolicy.bindConnectivityManager();
            t.traceEnd();

            t.traceBegin("StartSecurityStateManagerService");
            try {
                ServiceManager.addService(Context.SECURITY_STATE_SERVICE,
                        new SecurityStateManagerService(context));
            } catch (Throwable e) {
                reportWtf("starting SecurityStateManagerService", e);
            }
            t.traceEnd();

            if (!isWatch || !android.server.Flags.allowRemovingVpnService()) {
                t.traceBegin("StartVpnManagerService");
                try {
                    vpnManager = VpnManagerService.create(context);
                    ServiceManager.addService(Context.VPN_MANAGEMENT_SERVICE, vpnManager);
                } catch (Throwable e) {
                    reportWtf("starting VPN Manager Service", e);
                }
                t.traceEnd();
            } else {
                // VPN management currently does not work in Wear, so skip starting the
                // VPN manager SystemService.
                Slog.i(TAG, "Not starting VpnManagerService");
            }

            t.traceBegin("StartVcnManagementService");
            try {
                vcnManagement = VcnManagementService.create(context);
                ServiceManager.addService(Context.VCN_MANAGEMENT_SERVICE, vcnManagement);
            } catch (Throwable e) {
                reportWtf("starting VCN Management Service", e);
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

            t.traceBegin("StartTimeDetectorService");
            try {
                mSystemServiceManager.startService(TimeDetectorService.Lifecycle.class);
            } catch (Throwable e) {
                reportWtf("starting TimeDetectorService service", e);
            }
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

            t.traceBegin("StartTimeZoneDetectorService");
            try {
                mSystemServiceManager.startService(TimeZoneDetectorService.Lifecycle.class);
            } catch (Throwable e) {
                reportWtf("starting TimeZoneDetectorService service", e);
            }
            t.traceEnd();

            t.traceBegin("StartAltitudeService");
            try {
                mSystemServiceManager.startService(AltitudeService.Lifecycle.class);
            } catch (Throwable e) {
                reportWtf("starting AltitudeService service", e);
            }
            t.traceEnd();

            t.traceBegin("StartLocationTimeZoneManagerService");
            try {
                mSystemServiceManager.startService(LocationTimeZoneManagerService.Lifecycle.class);
            } catch (Throwable e) {
                reportWtf("starting LocationTimeZoneManagerService service", e);
            }
            t.traceEnd();

            if (context.getResources().getBoolean(R.bool.config_enableGnssTimeUpdateService)) {
                t.traceBegin("StartGnssTimeUpdateService");
                try {
                    mSystemServiceManager.startService(GnssTimeUpdateService.Lifecycle.class);
                } catch (Throwable e) {
                    reportWtf("starting GnssTimeUpdateService service", e);
                }
                t.traceEnd();
            }

            if (!isWatch) {
                t.traceBegin("StartSearchManagerService");
                try {
                    mSystemServiceManager.startService(SearchManagerService.Lifecycle.class);
                } catch (Throwable e) {
                    reportWtf("starting Search Service", e);
                }
                t.traceEnd();
            }

            if (context.getResources().getBoolean(R.bool.config_enableWallpaperService)) {
                t.traceBegin("StartWallpaperManagerService");
                mSystemServiceManager.startService(WallpaperManagerService.Lifecycle.class);
                t.traceEnd();
            } else {
                Slog.i(TAG, "Wallpaper service disabled by config");
            }

            // WallpaperEffectsGeneration manager service
            if (deviceHasConfigString(context,
                R.string.config_defaultWallpaperEffectsGenerationService)) {
                t.traceBegin("StartWallpaperEffectsGenerationService");
                mSystemServiceManager.startService(WallpaperEffectsGenerationManagerService.class);
                t.traceEnd();
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

            if (!isTv) {
                t.traceBegin("StartDockObserver");
                mSystemServiceManager.startService(DockObserver.class);
                t.traceEnd();
            }

            if (isWatch) {
                t.traceBegin("StartThermalObserver");
                mSystemServiceManager.startService(THERMAL_OBSERVER_CLASS);
                t.traceEnd();
            }

            if (!isWatch) {
                t.traceBegin("StartWiredAccessoryManager");
                try {
                    // Listen for wired headset changes
                    inputManager.setWiredAccessoryCallbacks(
                            new WiredAccessoryManager(context, inputManager));
                } catch (Throwable e) {
                    reportWtf("starting WiredAccessoryManager", e);
                }
                t.traceEnd();
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) {
                // Start MIDI Manager service
                t.traceBegin("StartMidiManager");
                mSystemServiceManager.startService(MidiService.Lifecycle.class);
                t.traceEnd();
            }

            // Start ADB Debugging Service
            t.traceBegin("StartAdbService");
            try {
                mSystemServiceManager.startService(AdbService.Lifecycle.class);
            } catch (Throwable e) {
                Slog.e(TAG, "Failure starting AdbService");
            }
            t.traceEnd();

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
                    || mPackageManager.hasSystemFeature(
                    PackageManager.FEATURE_USB_ACCESSORY)
                    || Build.IS_EMULATOR) {
                // Manage USB host and device support
                t.traceBegin("StartUsbService");
                mSystemServiceManager.startService(UsbService.Lifecycle.class);
                t.traceEnd();
            }

            if (!isWatch) {
                t.traceBegin("StartSerialService");
                mSystemServiceManager.startService(SerialService.Lifecycle.class);
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

            if (!isWatch) {
                t.traceBegin("StartTwilightService");
                mSystemServiceManager.startService(TwilightService.class);
                t.traceEnd();
            }

            t.traceBegin("StartColorDisplay");
            mSystemServiceManager.startService(ColorDisplayService.class);
            t.traceEnd();

            // TODO(aml-jobscheduler): Think about how to do it properly.
            t.traceBegin("StartJobScheduler");
            mSystemServiceManager.startService(JobSchedulerService.class);
            t.traceEnd();

            t.traceBegin("StartSoundTrigger");
            mSystemServiceManager.startService(SoundTriggerService.class);
            t.traceEnd();

            t.traceBegin("StartTrustManager");
            mSystemServiceManager.startService(TrustManagerService.class);
            t.traceEnd();

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_BACKUP)) {
                t.traceBegin("StartBackupManager");
                mSystemServiceManager.startService(BackupManagerService.Lifecycle.class);
                t.traceEnd();
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_APP_WIDGETS)
                    || context.getResources().getBoolean(R.bool.config_enableAppWidgetService)) {
                t.traceBegin("StartAppWidgetService");
                mSystemServiceManager.startService(AppWidgetService.class);
                t.traceEnd();
            }

            // We need to always start this service, regardless of whether the
            // FEATURE_VOICE_RECOGNIZERS feature is set, because it needs to take care
            // of initializing various settings.  It will internally modify its behavior
            // based on that feature.
            t.traceBegin("StartVoiceRecognitionManager");
            mSystemServiceManager.startService(VoiceInteractionManagerService.class);
            t.traceEnd();

            if (GestureLauncherService.isGestureLauncherEnabled(context.getResources())) {
                t.traceBegin("StartGestureLauncher");
                mSystemServiceManager.startService(GestureLauncherService.class);
                t.traceEnd();
            }
            t.traceBegin("StartSensorNotification");
            mSystemServiceManager.startService(SensorNotificationService.class);
            t.traceEnd();

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_CONTEXT_HUB)) {
                t.traceBegin("StartContextHubSystemService");
                mSystemServiceManager.startService(ContextHubSystemService.class);
                t.traceEnd();
            }

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
            if (!disableNetworkTime && (!isWatch || (isWatch
                    && android.server.Flags.allowNetworkTimeUpdateService()))) {
                t.traceBegin("StartNetworkTimeUpdateService");
                try {
                    networkTimeUpdater = new NetworkTimeUpdateService(context);
                    ServiceManager.addService("network_time_update_service", networkTimeUpdater);
                } catch (Throwable e) {
                    reportWtf("starting NetworkTimeUpdate service", e);
                }
                t.traceEnd();
            }

            t.traceBegin("CertBlocklister");
            try {
                CertBlocklister blocklister = new CertBlocklister(context);
            } catch (Throwable e) {
                reportWtf("starting CertBlocklister", e);
            }
            t.traceEnd();

            if (EmergencyAffordanceManager.ENABLED) {
                // EmergencyMode service
                t.traceBegin("StartEmergencyAffordanceService");
                mSystemServiceManager.startService(EmergencyAffordanceService.class);
                t.traceEnd();
            }

            t.traceBegin(START_BLOB_STORE_SERVICE);
            mSystemServiceManager.startService(BlobStoreManagerService.class);
            t.traceEnd();

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
                mSystemServiceManager.startService(PrintManagerService.class);
                t.traceEnd();
            }

            t.traceBegin("StartAttestationVerificationService");
            mSystemServiceManager.startService(AttestationVerificationManagerService.class);
            t.traceEnd();

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
                t.traceBegin("StartCompanionDeviceManager");
                mSystemServiceManager.startService(CompanionDeviceManagerService.class);
                t.traceEnd();
            }

            if (context.getResources().getBoolean(R.bool.config_enableVirtualDeviceManager)) {
                t.traceBegin("StartVirtualDeviceManager");
                mSystemServiceManager.startService(VirtualDeviceManagerService.class);
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
                t.traceBegin("StartTvInteractiveAppManager");
                mSystemServiceManager.startService(TvInteractiveAppManagerService.class);
                t.traceEnd();
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_TV)
                    || mPackageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                t.traceBegin("StartTvInputManager");
                mSystemServiceManager.startService(TvInputManagerService.class);
                t.traceEnd();
            }

            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_TUNER)) {
                t.traceBegin("StartTunerResourceManager");
                mSystemServiceManager.startService(TunerResourceManagerService.class);
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
                final FaceService faceService =
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
                final FingerprintService fingerprintService =
                        mSystemServiceManager.startService(FingerprintService.class);
                t.traceEnd();
            }

            // Start this service after all biometric sensor services are started.
            t.traceBegin("StartBiometricService");
            mSystemServiceManager.startService(BiometricService.class);
            t.traceEnd();

            t.traceBegin("StartAuthService");
            mSystemServiceManager.startService(AuthService.class);
            t.traceEnd();

            if (android.adaptiveauth.Flags.enableAdaptiveAuth()) {
                t.traceBegin("StartAdaptiveAuthService");
                mSystemServiceManager.startService(AdaptiveAuthService.class);
                t.traceEnd();
            }

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

            t.traceBegin("StartSelinuxAuditLogsService");
            try {
                SelinuxAuditLogsService.schedule(context);
            } catch (Throwable e) {
                reportWtf("starting SelinuxAuditLogsService", e);
            }
            t.traceEnd();

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

            t.traceBegin("StartMediaMetricsManager");
            mSystemServiceManager.startService(MediaMetricsManagerService.class);
            t.traceEnd();

            if (!com.android.server.flags.Flags.optionalBackgroundInstallControl()
                    || SystemProperties.getBoolean(
                            "ro.system_settings.service.backgound_install_control_enabled", true)) {
                t.traceBegin("StartBackgroundInstallControlService");
                mSystemServiceManager.startService(BackgroundInstallControlService.class);
                t.traceEnd();
            }
        }

        t.traceBegin("StartMediaProjectionManager");
        mSystemServiceManager.startService(MediaProjectionManagerService.class);
        t.traceEnd();

        if (isWatch) {
            // Must be started before services that depend it, e.g. WearConnectivityService
            t.traceBegin("StartWearPowerService");
            mSystemServiceManager.startService(WEAR_POWER_SERVICE_CLASS);
            t.traceEnd();

            t.traceBegin("StartHealthService");
            mSystemServiceManager.startService(HEALTH_SERVICE_CLASS);
            t.traceEnd();

            t.traceBegin("StartSystemStateDisplayService");
            mSystemServiceManager.startService(SYSTEM_STATE_DISPLAY_SERVICE_CLASS);
            t.traceEnd();

            t.traceBegin("StartWearConnectivityService");
            mSystemServiceManager.startService(WEAR_CONNECTIVITY_SERVICE_CLASS);
            t.traceEnd();

            t.traceBegin("StartWearDisplayService");
            mSystemServiceManager.startService(WEAR_DISPLAY_SERVICE_CLASS);
            t.traceEnd();

            if (Build.IS_DEBUGGABLE) {
                t.traceBegin("StartWearDebugService");
                mSystemServiceManager.startService(WEAR_DEBUG_SERVICE_CLASS);
                t.traceEnd();
            }

            t.traceBegin("StartWearTimeService");
            mSystemServiceManager.startService(WEAR_TIME_SERVICE_CLASS);
            t.traceEnd();

            t.traceBegin("StartWearSettingsService");
            mSystemServiceManager.startService(WEAR_SETTINGS_SERVICE_CLASS);
            t.traceEnd();

            t.traceBegin("StartWearModeService");
            mSystemServiceManager.startService(WEAR_MODE_SERVICE_CLASS);
            t.traceEnd();

            boolean enableWristOrientationService = SystemProperties.getBoolean(
                    "config.enable_wristorientation", false);
            if (enableWristOrientationService) {
                t.traceBegin("StartWristOrientationService");
                mSystemServiceManager.startService(WRIST_ORIENTATION_SERVICE_CLASS);
                t.traceEnd();
            }
        }

        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_SLICES_DISABLED)) {
            t.traceBegin("StartSliceManagerService");
            mSystemServiceManager.startService(SliceManagerService.Lifecycle.class);
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

        // Reboot Readiness
        t.traceBegin("StartRebootReadinessManagerService");
        mSystemServiceManager.startServiceFromJar(
                REBOOT_READINESS_LIFECYCLE_CLASS, SCHEDULING_APEX_PATH);
        t.traceEnd();

        // Statsd pulled atoms
        t.traceBegin("StartStatsPullAtomService");
        mSystemServiceManager.startService(StatsPullAtomService.class);
        t.traceEnd();

        // Log atoms to statsd from bootstrap processes.
        t.traceBegin("StatsBootstrapAtomService");
        mSystemServiceManager.startService(StatsBootstrapAtomService.Lifecycle.class);
        t.traceEnd();

        // Incidentd and dumpstated helper
        t.traceBegin("StartIncidentCompanionService");
        mSystemServiceManager.startService(IncidentCompanionService.class);
        t.traceEnd();

        // SdkSandboxManagerService
        t.traceBegin("StarSdkSandboxManagerService");
        mSystemServiceManager.startService(SDK_SANDBOX_MANAGER_SERVICE_CLASS);
        t.traceEnd();

        // AdServicesManagerService (PP API service)
        t.traceBegin("StartAdServicesManagerService");
        mSystemServiceManager.startService(AD_SERVICES_MANAGER_SERVICE_CLASS);
        t.traceEnd();

        // OnDevicePersonalizationSystemService
        if (!com.android.server.flags.Flags.enableOdpFeatureGuard()
                || SystemProperties.getBoolean("ro.system_settings.service.odp_enabled", true)) {
            t.traceBegin("StartOnDevicePersonalizationSystemService");
            mSystemServiceManager.startService(ON_DEVICE_PERSONALIZATION_SYSTEM_SERVICE_CLASS);
            t.traceEnd();
        }

        // Profiling
        if (android.server.Flags.telemetryApisService()) {
            t.traceBegin("StartProfilingCompanion");
            mSystemServiceManager.startServiceFromJar(PROFILING_SERVICE_LIFECYCLE_CLASS,
                    PROFILING_SERVICE_JAR_PATH);
            t.traceEnd();
        }

        if (safeMode) {
            mActivityManagerService.enterSafeMode();
        }

        if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            // MMS service broker
            t.traceBegin("StartMmsService");
            mmsService = mSystemServiceManager.startService(MmsServiceBroker.class);
            t.traceEnd();
        }

        if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOFILL)) {
            t.traceBegin("StartAutoFillService");
            mSystemServiceManager.startService(AutofillManagerService.class);
            t.traceEnd();
        }

        if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_CREDENTIALS)) {
            boolean credentialManagerEnabled =
                    DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_CREDENTIAL,
                    CredentialManager.DEVICE_CONFIG_ENABLE_CREDENTIAL_MANAGER, true);
            if (credentialManagerEnabled) {
                if (isWatch && !android.credentials.flags.Flags.wearCredentialManagerEnabled()) {
                    Slog.d(TAG, "CredentialManager disabled on wear.");
                } else {
                    t.traceBegin("StartCredentialManagerService");
                    mSystemServiceManager.startService(CredentialManagerService.class);
                    t.traceEnd();
                }
            } else {
                Slog.d(TAG, "CredentialManager disabled.");
            }
        }

        // Translation manager service
        if (deviceHasConfigString(context, R.string.config_defaultTranslationService)) {
            t.traceBegin("StartTranslationManagerService");
            mSystemServiceManager.startService(TranslationManagerService.class);
            t.traceEnd();
        } else {
            Slog.d(TAG, "TranslationService not defined by OEM");
        }

        // NOTE: ClipboardService depends on ContentCapture and Autofill
        t.traceBegin("StartClipboardService");
        mSystemServiceManager.startService(ClipboardService.class);
        t.traceEnd();

        t.traceBegin("AppServiceManager");
        mSystemServiceManager.startService(AppBindingService.Lifecycle.class);
        t.traceEnd();

        // Perfetto TracingServiceProxy
        t.traceBegin("startTracingServiceProxy");
        mSystemServiceManager.startService(TracingServiceProxy.class);
        t.traceEnd();

        // It is now time to start up the app processes...

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

        // Create initial user if needed, which should be done early since some system services rely
        // on it in their setup, but likely needs to be done after LockSettingsService is ready.
        final HsumBootUserInitializer hsumBootUserInitializer =
                HsumBootUserInitializer.createInstance(
                        mActivityManagerService, mPackageManagerService, mContentResolver,
                        context.getResources().getBoolean(R.bool.config_isMainUserPermanentAdmin));
        if (hsumBootUserInitializer != null) {
            t.traceBegin("HsumBootUserInitializer.init");
            hsumBootUserInitializer.init(t);
            t.traceEnd();
        }

        CommunalProfileInitializer communalProfileInitializer = null;
        if (UserManager.isCommunalProfileEnabled()) {
            t.traceBegin("CommunalProfileInitializer.init");
            communalProfileInitializer =
                    new CommunalProfileInitializer(mActivityManagerService);
            communalProfileInitializer.init(t);
            t.traceEnd();
        } else {
            t.traceBegin("CommunalProfileInitializer.removeCommunalProfileIfPresent");
            CommunalProfileInitializer.removeCommunalProfileIfPresent();
            t.traceEnd();
        }

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

        t.traceBegin("RegisterLogMteState");
        try {
            LogMteState.register(context);
        } catch (Throwable e) {
            reportWtf("RegisterLogMteState", e);
        }
        t.traceEnd();

        // Emit any pending system_server WTFs
        synchronized (SystemService.class) {
            if (sPendingWtfs != null) {
                mActivityManagerService.schedulePendingSystemServerWtfs(sPendingWtfs);
                sPendingWtfs = null;
            }
        }

        if (safeMode) {
            mActivityManagerService.showSafeModeOverlay();
        }

        // Update the configuration for this context by hand, because we're going
        // to start using it before the config change done in wm.systemReady() will
        // propagate to it.
        final Configuration config = wm.computeNewConfiguration(DEFAULT_DISPLAY);
        DisplayMetrics metrics = new DisplayMetrics();
        context.getDisplay().getMetrics(metrics);
        context.getResources().updateConfiguration(config, metrics);

        // The system context's theme may be configuration-dependent.
        final Theme systemTheme = context.getTheme();
        if (systemTheme.getChangingConfigurations() != 0) {
            systemTheme.rebase();
        }

        // Permission policy service
        t.traceBegin("StartPermissionPolicyService");
        mSystemServiceManager.startService(PermissionPolicyService.class);
        t.traceEnd();

        t.traceBegin("MakePackageManagerServiceReady");
        mPackageManagerService.systemReady();
        t.traceEnd();

        if (Flags.refactorCrashrecovery()) {
            t.traceBegin("StartCrashRecoveryModule");
            CrashRecoveryAdaptor.initializeCrashrecoveryModuleService(mSystemServiceManager);
            t.traceEnd();
        } else {
            if (Flags.recoverabilityDetection()) {
                // Now that we have the essential services needed for mitigations, register the boot
                // with package watchdog.
                // Note that we just booted, which might send out a rescue party if we're stuck in a
                // runtime restart loop.
                CrashRecoveryAdaptor.packageWatchdogNoteBoot(mSystemContext);
            }
        }

        t.traceBegin("MakeDisplayManagerServiceReady");
        try {
            // TODO: use boot phase and communicate this flag some other way
            mDisplayManagerService.systemReady(safeMode);
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

        if (!isWatch || !android.server.Flags.removeGameManagerServiceFromWear()) {
            t.traceBegin("GameManagerService");
            mSystemServiceManager.startService(GameManagerService.Lifecycle.class);
            t.traceEnd();
        } else {
            Slog.d(TAG, "Not starting GameManagerService");
        }

        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_UWB)) {
            t.traceBegin("UwbService");
            mSystemServiceManager.startServiceFromJar(UWB_SERVICE_CLASS, UWB_APEX_SERVICE_JAR_PATH);
            t.traceEnd();
        }

        if (com.android.ranging.flags.Flags.rangingStackEnabled()) {
            if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_UWB)
                    || context.getPackageManager().hasSystemFeature(
                            PackageManager.FEATURE_WIFI_RTT)) {
                t.traceBegin("RangingService");
                mSystemServiceManager.startServiceFromJar(RANGING_SERVICE_CLASS,
                        RANGING_APEX_SERVICE_JAR_PATH);
                t.traceEnd();
            }
        }

        t.traceBegin("StartBootPhaseDeviceSpecificServicesReady");
        mSystemServiceManager.startBootPhase(t, SystemService.PHASE_DEVICE_SPECIFIC_SERVICES_READY);
        t.traceEnd();

        t.traceBegin("StartSafetyCenterService");
        mSystemServiceManager.startService(SAFETY_CENTER_SERVICE_CLASS);
        t.traceEnd();

        t.traceBegin("AppSearchModule");
        mSystemServiceManager.startService(APPSEARCH_MODULE_LIFECYCLE_CLASS);
        t.traceEnd();

        if (SystemProperties.getBoolean("ro.config.isolated_compilation_enabled", false)) {
            t.traceBegin("IsolatedCompilationService");
            mSystemServiceManager.startService(ISOLATED_COMPILATION_SERVICE_CLASS);
            t.traceEnd();
        }

        t.traceBegin("StartMediaCommunicationService");
        mSystemServiceManager.startService(MEDIA_COMMUNICATION_SERVICE_CLASS);
        t.traceEnd();

        t.traceBegin("AppCompatOverridesService");
        mSystemServiceManager.startService(AppCompatOverridesService.Lifecycle.class);
        t.traceEnd();

        t.traceBegin("HealthConnectManagerService");
        mSystemServiceManager.startService(HEALTHCONNECT_MANAGER_SERVICE_CLASS);
        t.traceEnd();

        if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_LOCK)) {
            t.traceBegin("DeviceLockService");
            mSystemServiceManager.startServiceFromJar(DEVICE_LOCK_SERVICE_CLASS,
                    DEVICE_LOCK_APEX_PATH);
            t.traceEnd();
        }

        if (android.permission.flags.Flags.sensitiveNotificationAppProtection()
                || android.view.flags.Flags.sensitiveContentAppProtection()) {
            t.traceBegin("StartSensitiveContentProtectionManager");
            mSystemServiceManager.startService(SensitiveContentProtectionManagerService.class);
            t.traceEnd();
        }

        // These are needed to propagate to the runnable below.
        final NetworkManagementService networkManagementF = networkManagement;
        final NetworkPolicyManagerService networkPolicyF = networkPolicy;
        final CountryDetectorService countryDetectorF = countryDetector;
        final NetworkTimeUpdateService networkTimeUpdaterF = networkTimeUpdater;
        final InputManagerService inputManagerF = inputManager;
        final TelephonyRegistry telephonyRegistryF = telephonyRegistry;
        final MediaRouterService mediaRouterF = mediaRouter;
        final MmsServiceBroker mmsServiceF = mmsService;
        final VpnManagerService vpnManagerF = vpnManager;
        final VcnManagementService vcnManagementF = vcnManagement;
        final WindowManagerService windowManagerF = wm;
        final ConnectivityManager connectivityF = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);

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

            t.traceBegin("RegisterAppOpsPolicy");
            try {
                mActivityManagerService.setAppOpsPolicy(new AppOpsPolicy(mSystemContext));
            } catch (Throwable e) {
                reportWtf("registering app ops policy", e);
            }
            t.traceEnd();

            // No dependency on Webview preparation in system server. But this should
            // be completed before allowing 3rd party
            final String WEBVIEW_PREPARATION = "WebViewFactoryPreparation";
            Future<?> webviewPrep = null;
            if (mWebViewUpdateService != null) {
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

            boolean isAutomotive = mPackageManager
                    .hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
            if (isAutomotive) {
                t.traceBegin("StartCarServiceHelperService");
                final SystemService cshs = mSystemServiceManager
                        .startService(CAR_SERVICE_HELPER_SERVICE_CLASS);
                if (cshs instanceof Dumpable) {
                    mDumper.addDumpable((Dumpable) cshs);
                }
                if (cshs instanceof DevicePolicySafetyChecker) {
                    dpms.setDevicePolicySafetyChecker((DevicePolicySafetyChecker) cshs);
                }
                t.traceEnd();
            }

            if (isWatch) {
                t.traceBegin("StartWearService");
                String wearServiceComponentNameString =
                    context.getString(R.string.config_wearServiceComponent);

                if (!TextUtils.isEmpty(wearServiceComponentNameString)) {
                    ComponentName wearServiceComponentName = ComponentName.unflattenFromString(
                        wearServiceComponentNameString);

                    if (wearServiceComponentName != null) {
                        Intent intent = new Intent();
                        intent.setComponent(wearServiceComponentName);
                        intent.addFlags(Intent.FLAG_DIRECT_BOOT_AUTO);
                        context.startServiceAsUser(intent, UserHandle.SYSTEM);
                    } else {
                        Slog.d(TAG, "Null wear service component name.");
                    }
                }
                t.traceEnd();
            }

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
            t.traceBegin("MakeConnectivityServiceReady");
            try {
                if (connectivityF != null) {
                    connectivityF.systemReady();
                }
            } catch (Throwable e) {
                reportWtf("making Connectivity Service ready", e);
            }
            t.traceEnd();
            t.traceBegin("MakeVpnManagerServiceReady");
            try {
                if (vpnManagerF != null) {
                    vpnManagerF.systemReady();
                }
            } catch (Throwable e) {
                reportWtf("making VpnManagerService ready", e);
            }
            t.traceEnd();
            t.traceBegin("MakeVcnManagementServiceReady");
            try {
                if (vcnManagementF != null) {
                    vcnManagementF.systemReady();
                }
            } catch (Throwable e) {
                reportWtf("making VcnManagementService ready", e);
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

            if (hsumBootUserInitializer != null) {
                t.traceBegin("HsumBootUserInitializer.systemRunning");
                hsumBootUserInitializer.systemRunning(t);
                t.traceEnd();
            }

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
                        TETHERING_CONNECTOR_CLASS,
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
            if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
                t.traceBegin("MakeMmsServiceReady");
                try {
                    if (mmsServiceF != null) mmsServiceF.systemRunning();
                } catch (Throwable e) {
                    reportWtf("Notifying MmsService running", e);
                }
                t.traceEnd();
            }

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

            if (mIncrementalServiceHandle != 0) {
                t.traceBegin("MakeIncrementalServiceReady");
                setIncrementalServiceSystemReady(mIncrementalServiceHandle);
                t.traceEnd();
            }

            t.traceBegin("OdsignStatsLogger");
            try {
                OdsignStatsLogger.triggerStatsWrite();
            } catch (Throwable e) {
                reportWtf("Triggering OdsignStatsLogger", e);
            }
            t.traceEnd();
        }, t);

        t.traceBegin("LockSettingsThirdPartyAppsStarted");
        LockSettingsInternal lockSettingsInternal =
            LocalServices.getService(LockSettingsInternal.class);
        if (lockSettingsInternal != null) {
            lockSettingsInternal.onThirdPartyAppsStarted();
        }
        t.traceEnd();

        t.traceBegin("StartSystemUI");
        try {
            startSystemUi(context, windowManagerF);
        } catch (Throwable e) {
            reportWtf("starting System UI", e);
        }
        t.traceEnd();

        t.traceEnd(); // startOtherServices
    }

    private void startOnDeviceIntelligenceService(TimingsTraceAndSlog t) {
        t.traceBegin("startOnDeviceIntelligenceManagerService");
        mSystemServiceManager.startService(OnDeviceIntelligenceManagerService.class);
        t.traceEnd();
    }

    /**
     * Starts system services defined in apexes.
     *
     * <p>Apex services must be the last category of services to start. No other service must be
     * starting after this point. This is to prevent unnecessary stability issues when these apexes
     * are updated outside of OTA; and to avoid breaking dependencies from system into apexes.
     */
    private void startApexServices(@NonNull TimingsTraceAndSlog t) {
        if (Flags.recoverabilityDetection()) {
            // For debugging RescueParty
            if (Build.IS_DEBUGGABLE
                    && SystemProperties.getBoolean("debug.crash_system", false)) {
                throw new RuntimeException();
            }
        }

        t.traceBegin("startApexServices");
        // TODO(b/192880996): get the list from "android" package, once the manifest entries
        // are migrated to system manifest.
        List<ApexSystemServiceInfo> services = ApexManager.getInstance().getApexSystemServices();
        for (ApexSystemServiceInfo info : services) {
            String name = info.getName();
            String jarPath = info.getJarPath();
            t.traceBegin("starting " + name);
            if (TextUtils.isEmpty(jarPath)) {
                mSystemServiceManager.startService(name);
            } else {
                mSystemServiceManager.startServiceFromJar(name, jarPath);
            }
            t.traceEnd();
        }

        // make sure no other services are started after this point
        mSystemServiceManager.sealStartedServices();

        t.traceEnd(); // startApexServices
    }

    private void updateWatchdogTimeout(@NonNull TimingsTraceAndSlog t) {
        t.traceBegin("UpdateWatchdogTimeout");
        Watchdog.getInstance().registerSettingsObserver(mSystemContext);
        t.traceEnd();
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
        mSystemServiceManager.startService(SystemCaptionsManagerService.class);
        t.traceEnd();
    }

    private void startTextToSpeechManagerService(@NonNull Context context,
            @NonNull TimingsTraceAndSlog t) {
        t.traceBegin("StartTextToSpeechManagerService");
        mSystemServiceManager.startService(TextToSpeechManagerService.class);
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
            if (!deviceHasConfigString(context, R.string.config_defaultContentProtectionService)) {
                Slog.d(
                        TAG,
                        "ContentProtectionService disabled because resource is not overlaid,"
                            + " ContentCaptureService still enabled");
            }
        }

        t.traceBegin("StartContentCaptureService");
        mSystemServiceManager.startService(ContentCaptureManagerService.class);

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

    private void startRotationResolverService(@NonNull Context context,
            @NonNull TimingsTraceAndSlog t) {
        if (!RotationResolverManagerService.isServiceConfigured(context)) {
            Slog.d(TAG, "RotationResolverService is not configured on this device");
            return;
        }

        t.traceBegin("StartRotationResolverService");
        mSystemServiceManager.startService(RotationResolverManagerService.class);
        t.traceEnd();

    }

    private void startWearableSensingService(@NonNull TimingsTraceAndSlog t) {
        t.traceBegin("startWearableSensingService");
        mSystemServiceManager.startService(WearableSensingManagerService.class);
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

    /**
     * Handle the serious errors during early system boot, used by {@link Log} via
     * {@link com.android.internal.os.RuntimeInit}.
     */
    private static boolean handleEarlySystemWtf(final IBinder app, final String tag, boolean system,
            final ApplicationErrorReport.ParcelableCrashInfo crashInfo, int immediateCallerPid) {
        final String processName = "system_server";
        final int myPid = myPid();

        com.android.server.am.EventLogTags.writeAmWtf(UserHandle.getUserId(SYSTEM_UID), myPid,
                processName, -1, tag, crashInfo.exceptionMessage);

        FrameworkStatsLog.write(FrameworkStatsLog.WTF_OCCURRED, SYSTEM_UID, tag, processName,
                myPid, ServerProtoEnums.SYSTEM_SERVER);

        synchronized (SystemServer.class) {
            if (sPendingWtfs == null) {
                sPendingWtfs = new LinkedList<>();
            }
            sPendingWtfs.add(new Pair<>(tag, crashInfo));
        }
        return false;
    }

}
