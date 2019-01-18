/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.accounts.AccountManager;
import android.accounts.IAccountManager;
import android.app.ContextImpl.ServiceInitializationState;
import android.app.admin.DevicePolicyManager;
import android.app.admin.IDevicePolicyManager;
import android.app.contentsuggestions.ContentSuggestionsManager;
import android.app.contentsuggestions.IContentSuggestionsManager;
import android.app.job.IJobScheduler;
import android.app.job.JobScheduler;
import android.app.prediction.AppPredictionManager;
import android.app.role.RoleManager;
import android.app.slice.SliceManager;
import android.app.timedetector.TimeDetector;
import android.app.timezone.RulesManager;
import android.app.timezonedetector.TimeZoneDetector;
import android.app.trust.TrustManager;
import android.app.usage.IStorageStatsManager;
import android.app.usage.IUsageStatsManager;
import android.app.usage.NetworkStatsManager;
import android.app.usage.StorageStatsManager;
import android.app.usage.UsageStatsManager;
import android.appwidget.AppWidgetManager;
import android.bluetooth.BluetoothManager;
import android.companion.CompanionDeviceManager;
import android.companion.ICompanionDeviceManager;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.IRestrictionsManager;
import android.content.RestrictionsManager;
import android.content.om.IOverlayManager;
import android.content.om.OverlayManager;
import android.content.pm.CrossProfileApps;
import android.content.pm.ICrossProfileApps;
import android.content.pm.IShortcutService;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutManager;
import android.content.res.Resources;
import android.content.rollback.IRollbackManager;
import android.content.rollback.RollbackManager;
import android.debug.AdbManager;
import android.debug.IAdbManager;
import android.hardware.ConsumerIrManager;
import android.hardware.ISerialManager;
import android.hardware.SensorManager;
import android.hardware.SensorPrivacyManager;
import android.hardware.SerialManager;
import android.hardware.SystemSensorManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.IBiometricService;
import android.hardware.camera2.CameraManager;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.DisplayManager;
import android.hardware.face.FaceManager;
import android.hardware.face.IFaceService;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.IHdmiControlService;
import android.hardware.input.InputManager;
import android.hardware.iris.IIrisService;
import android.hardware.iris.IrisManager;
import android.hardware.location.ContextHubManager;
import android.hardware.radio.RadioManager;
import android.hardware.usb.IUsbManager;
import android.hardware.usb.UsbManager;
import android.location.CountryDetector;
import android.location.ICountryDetector;
import android.location.ILocationManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaRouter;
import android.media.midi.IMidiManager;
import android.media.midi.MidiManager;
import android.media.projection.MediaProjectionManager;
import android.media.session.MediaSessionManager;
import android.media.soundtrigger.SoundTriggerManager;
import android.media.tv.ITvInputManager;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.ConnectivityThread;
import android.net.EthernetManager;
import android.net.IConnectivityManager;
import android.net.IEthernetManager;
import android.net.IIpMemoryStore;
import android.net.IIpSecService;
import android.net.INetworkPolicyManager;
import android.net.IpMemoryStore;
import android.net.IpSecManager;
import android.net.NetworkPolicyManager;
import android.net.NetworkScoreManager;
import android.net.NetworkStack;
import android.net.NetworkWatchlistManager;
import android.net.lowpan.ILowpanManager;
import android.net.lowpan.LowpanManager;
import android.net.nsd.INsdManager;
import android.net.nsd.NsdManager;
import android.net.wifi.IWifiManager;
import android.net.wifi.IWifiScanner;
import android.net.wifi.RttManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.net.wifi.aware.IWifiAwareManager;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.p2p.IWifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.rtt.IWifiRttManager;
import android.net.wifi.rtt.WifiRttManager;
import android.nfc.NfcManager;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.BugreportManager;
import android.os.Build;
import android.os.DeviceIdleManager;
import android.os.DropBoxManager;
import android.os.HardwarePropertiesManager;
import android.os.IBatteryPropertiesRegistrar;
import android.os.IBinder;
import android.os.IDeviceIdleController;
import android.os.IDumpstate;
import android.os.IHardwarePropertiesManager;
import android.os.IPowerManager;
import android.os.IRecoverySystem;
import android.os.ISystemUpdateManager;
import android.os.IUserManager;
import android.os.IncidentManager;
import android.os.PowerManager;
import android.os.RecoverySystem;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.os.SystemUpdateManager;
import android.os.SystemVibrator;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.os.health.SystemHealthManager;
import android.os.storage.StorageManager;
import android.permission.PermissionControllerManager;
import android.permission.PermissionManager;
import android.print.IPrintManager;
import android.print.PrintManager;
import android.service.oemlock.IOemLockService;
import android.service.oemlock.OemLockManager;
import android.service.persistentdata.IPersistentDataBlockService;
import android.service.persistentdata.PersistentDataBlockManager;
import android.service.vr.IVrManager;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.euicc.EuiccCardManager;
import android.telephony.euicc.EuiccManager;
import android.telephony.ims.RcsManager;
import android.util.ArrayMap;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.CaptioningManager;
import android.view.autofill.AutofillManager;
import android.view.autofill.IAutoFillManager;
import android.view.contentcapture.ContentCaptureManager;
import android.view.contentcapture.IContentCaptureManager;
import android.view.inputmethod.InputMethodManager;
import android.view.textclassifier.TextClassificationManager;
import android.view.textservice.TextServicesManager;

import com.android.internal.app.IAppOpsService;
import com.android.internal.app.IBatteryStats;
import com.android.internal.app.ISoundTriggerService;
import com.android.internal.appwidget.IAppWidgetService;
import com.android.internal.net.INetworkWatchlistManager;
import com.android.internal.os.IDropBoxManagerService;
import com.android.internal.policy.PhoneLayoutInflater;

import java.util.Map;

/**
 * Manages all of the system services that can be returned by {@link Context#getSystemService}.
 * Used by {@link ContextImpl}.
 */
final class SystemServiceRegistry {
    private static final String TAG = "SystemServiceRegistry";

    // Service registry information.
    // This information is never changed once static initialization has completed.
    private static final Map<Class<?>, String> SYSTEM_SERVICE_NAMES =
            new ArrayMap<Class<?>, String>();
    private static final Map<String, ServiceFetcher<?>> SYSTEM_SERVICE_FETCHERS =
            new ArrayMap<String, ServiceFetcher<?>>();
    private static int sServiceCacheSize;

    // Not instantiable.
    private SystemServiceRegistry() { }

    static {
        //CHECKSTYLE:OFF IndentationCheck
        registerService(Context.ACCESSIBILITY_SERVICE, AccessibilityManager.class,
                new CachedServiceFetcher<AccessibilityManager>() {
            @Override
            public AccessibilityManager createService(ContextImpl ctx) {
                return AccessibilityManager.getInstance(ctx);
            }});

        registerService(Context.CAPTIONING_SERVICE, CaptioningManager.class,
                new CachedServiceFetcher<CaptioningManager>() {
            @Override
            public CaptioningManager createService(ContextImpl ctx) {
                return new CaptioningManager(ctx);
            }});

        registerService(Context.ACCOUNT_SERVICE, AccountManager.class,
                new CachedServiceFetcher<AccountManager>() {
            @Override
            public AccountManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.ACCOUNT_SERVICE);
                IAccountManager service = IAccountManager.Stub.asInterface(b);
                return new AccountManager(ctx, service);
            }});

        registerService(Context.ACTIVITY_SERVICE, ActivityManager.class,
                new CachedServiceFetcher<ActivityManager>() {
            @Override
            public ActivityManager createService(ContextImpl ctx) {
                return new ActivityManager(ctx.getOuterContext(), ctx.mMainThread.getHandler());
            }});

        registerService(Context.ACTIVITY_TASK_SERVICE, ActivityTaskManager.class,
                new CachedServiceFetcher<ActivityTaskManager>() {
            @Override
            public ActivityTaskManager createService(ContextImpl ctx) {
                return new ActivityTaskManager(
                        ctx.getOuterContext(), ctx.mMainThread.getHandler());
            }});

        registerService(Context.URI_GRANTS_SERVICE, UriGrantsManager.class,
                new CachedServiceFetcher<UriGrantsManager>() {
            @Override
            public UriGrantsManager createService(ContextImpl ctx) {
                return new UriGrantsManager(
                        ctx.getOuterContext(), ctx.mMainThread.getHandler());
            }});

        registerService(Context.ALARM_SERVICE, AlarmManager.class,
                new CachedServiceFetcher<AlarmManager>() {
            @Override
            public AlarmManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.ALARM_SERVICE);
                IAlarmManager service = IAlarmManager.Stub.asInterface(b);
                return new AlarmManager(service, ctx);
            }});

        registerService(Context.AUDIO_SERVICE, AudioManager.class,
                new CachedServiceFetcher<AudioManager>() {
            @Override
            public AudioManager createService(ContextImpl ctx) {
                return new AudioManager(ctx);
            }});

        registerService(Context.MEDIA_ROUTER_SERVICE, MediaRouter.class,
                new CachedServiceFetcher<MediaRouter>() {
            @Override
            public MediaRouter createService(ContextImpl ctx) {
                return new MediaRouter(ctx);
            }});

        registerService(Context.BLUETOOTH_SERVICE, BluetoothManager.class,
                new CachedServiceFetcher<BluetoothManager>() {
            @Override
            public BluetoothManager createService(ContextImpl ctx) {
                return new BluetoothManager(ctx);
            }});

        registerService(Context.HDMI_CONTROL_SERVICE, HdmiControlManager.class,
                new StaticServiceFetcher<HdmiControlManager>() {
            @Override
            public HdmiControlManager createService() throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.HDMI_CONTROL_SERVICE);
                return new HdmiControlManager(IHdmiControlService.Stub.asInterface(b));
            }});

        registerService(Context.TEXT_CLASSIFICATION_SERVICE, TextClassificationManager.class,
                new CachedServiceFetcher<TextClassificationManager>() {
            @Override
            public TextClassificationManager createService(ContextImpl ctx) {
                return new TextClassificationManager(ctx);
            }});

        registerService(Context.CLIPBOARD_SERVICE, ClipboardManager.class,
                new CachedServiceFetcher<ClipboardManager>() {
            @Override
            public ClipboardManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new ClipboardManager(ctx.getOuterContext(),
                        ctx.mMainThread.getHandler());
            }});

        // The clipboard service moved to a new package.  If someone asks for the old
        // interface by class then we want to redirect over to the new interface instead
        // (which extends it).
        SYSTEM_SERVICE_NAMES.put(android.text.ClipboardManager.class, Context.CLIPBOARD_SERVICE);

        registerService(Context.CONNECTIVITY_SERVICE, ConnectivityManager.class,
                new StaticApplicationContextServiceFetcher<ConnectivityManager>() {
            @Override
            public ConnectivityManager createService(Context context) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.CONNECTIVITY_SERVICE);
                IConnectivityManager service = IConnectivityManager.Stub.asInterface(b);
                return new ConnectivityManager(context, service);
            }});

        registerService(Context.NETWORK_STACK_SERVICE, NetworkStack.class,
                new StaticServiceFetcher<NetworkStack>() {
                    @Override
                    public NetworkStack createService() {
                        return new NetworkStack();
                    }});

        registerService(Context.IP_MEMORY_STORE_SERVICE, IpMemoryStore.class,
                new CachedServiceFetcher<IpMemoryStore>() {
                    @Override
                    public IpMemoryStore createService(final ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getServiceOrThrow(
                                Context.IP_MEMORY_STORE_SERVICE);
                        IIpMemoryStore service = IIpMemoryStore.Stub.asInterface(b);
                        return new IpMemoryStore(ctx, service);
                    }});

        registerService(Context.IPSEC_SERVICE, IpSecManager.class,
                new CachedServiceFetcher<IpSecManager>() {
            @Override
            public IpSecManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getService(Context.IPSEC_SERVICE);
                IIpSecService service = IIpSecService.Stub.asInterface(b);
                return new IpSecManager(ctx, service);
            }});

        registerService(Context.COUNTRY_DETECTOR, CountryDetector.class,
                new StaticServiceFetcher<CountryDetector>() {
            @Override
            public CountryDetector createService() throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.COUNTRY_DETECTOR);
                return new CountryDetector(ICountryDetector.Stub.asInterface(b));
            }});

        registerService(Context.DEVICE_POLICY_SERVICE, DevicePolicyManager.class,
                new CachedServiceFetcher<DevicePolicyManager>() {
            @Override
            public DevicePolicyManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.DEVICE_POLICY_SERVICE);
                return new DevicePolicyManager(ctx, IDevicePolicyManager.Stub.asInterface(b));
            }});

        registerService(Context.DOWNLOAD_SERVICE, DownloadManager.class,
                new CachedServiceFetcher<DownloadManager>() {
            @Override
            public DownloadManager createService(ContextImpl ctx) {
                return new DownloadManager(ctx);
            }});

        registerService(Context.BATTERY_SERVICE, BatteryManager.class,
                new CachedServiceFetcher<BatteryManager>() {
            @Override
            public BatteryManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBatteryStats stats = IBatteryStats.Stub.asInterface(
                        ServiceManager.getServiceOrThrow(BatteryStats.SERVICE_NAME));
                IBatteryPropertiesRegistrar registrar = IBatteryPropertiesRegistrar.Stub
                        .asInterface(ServiceManager.getServiceOrThrow("batteryproperties"));
                return new BatteryManager(ctx, stats, registrar);
            }});

        registerService(Context.NFC_SERVICE, NfcManager.class,
                new CachedServiceFetcher<NfcManager>() {
            @Override
            public NfcManager createService(ContextImpl ctx) {
                return new NfcManager(ctx);
            }});

        registerService(Context.DROPBOX_SERVICE, DropBoxManager.class,
                new CachedServiceFetcher<DropBoxManager>() {
            @Override
            public DropBoxManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.DROPBOX_SERVICE);
                IDropBoxManagerService service = IDropBoxManagerService.Stub.asInterface(b);
                return new DropBoxManager(ctx, service);
            }});

        registerService(Context.INPUT_SERVICE, InputManager.class,
                new StaticServiceFetcher<InputManager>() {
            @Override
            public InputManager createService() {
                return InputManager.getInstance();
            }});

        registerService(Context.DISPLAY_SERVICE, DisplayManager.class,
                new CachedServiceFetcher<DisplayManager>() {
            @Override
            public DisplayManager createService(ContextImpl ctx) {
                return new DisplayManager(ctx.getOuterContext());
            }});

        registerService(Context.COLOR_DISPLAY_SERVICE, ColorDisplayManager.class,
                new CachedServiceFetcher<ColorDisplayManager>() {
                    @Override
                    public ColorDisplayManager createService(ContextImpl ctx) {
                        return new ColorDisplayManager();
                    }
                });

        // InputMethodManager has its own cache strategy based on display id to support apps that
        // still assume InputMethodManager is a per-process singleton and it's safe to directly
        // access internal fields via reflection.  Hence directly use ServiceFetcher instead of
        // StaticServiceFetcher/CachedServiceFetcher.
        registerService(Context.INPUT_METHOD_SERVICE, InputMethodManager.class,
                new ServiceFetcher<InputMethodManager>() {
            @Override
            public InputMethodManager getService(ContextImpl ctx) {
                return InputMethodManager.forContext(ctx.getOuterContext());
            }});

        registerService(Context.TEXT_SERVICES_MANAGER_SERVICE, TextServicesManager.class,
                new CachedServiceFetcher<TextServicesManager>() {
            @Override
            public TextServicesManager createService(ContextImpl ctx)
                    throws ServiceNotFoundException {
                return TextServicesManager.createInstance(ctx);
            }});

        registerService(Context.KEYGUARD_SERVICE, KeyguardManager.class,
                new CachedServiceFetcher<KeyguardManager>() {
            @Override
            public KeyguardManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new KeyguardManager(ctx);
            }});

        registerService(Context.LAYOUT_INFLATER_SERVICE, LayoutInflater.class,
                new CachedServiceFetcher<LayoutInflater>() {
            @Override
            public LayoutInflater createService(ContextImpl ctx) {
                return new PhoneLayoutInflater(ctx.getOuterContext());
            }});

        registerService(Context.LOCATION_SERVICE, LocationManager.class,
                new CachedServiceFetcher<LocationManager>() {
            @Override
            public LocationManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.LOCATION_SERVICE);
                return new LocationManager(ctx, ILocationManager.Stub.asInterface(b));
            }});

        registerService(Context.NETWORK_POLICY_SERVICE, NetworkPolicyManager.class,
                new CachedServiceFetcher<NetworkPolicyManager>() {
            @Override
            public NetworkPolicyManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new NetworkPolicyManager(ctx, INetworkPolicyManager.Stub.asInterface(
                        ServiceManager.getServiceOrThrow(Context.NETWORK_POLICY_SERVICE)));
            }});

        registerService(Context.NOTIFICATION_SERVICE, NotificationManager.class,
                new CachedServiceFetcher<NotificationManager>() {
            @Override
            public NotificationManager createService(ContextImpl ctx) {
                final Context outerContext = ctx.getOuterContext();
                return new NotificationManager(
                    new ContextThemeWrapper(outerContext,
                            Resources.selectSystemTheme(0,
                                    outerContext.getApplicationInfo().targetSdkVersion,
                                    com.android.internal.R.style.Theme_Dialog,
                                    com.android.internal.R.style.Theme_Holo_Dialog,
                                    com.android.internal.R.style.Theme_DeviceDefault_Dialog,
                                    com.android.internal.R.style.Theme_DeviceDefault_Light_Dialog)),
                    ctx.mMainThread.getHandler());
            }});

        registerService(Context.NSD_SERVICE, NsdManager.class,
                new CachedServiceFetcher<NsdManager>() {
            @Override
            public NsdManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.NSD_SERVICE);
                INsdManager service = INsdManager.Stub.asInterface(b);
                return new NsdManager(ctx.getOuterContext(), service);
            }});

        registerService(Context.POWER_SERVICE, PowerManager.class,
                new CachedServiceFetcher<PowerManager>() {
            @Override
            public PowerManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.POWER_SERVICE);
                IPowerManager service = IPowerManager.Stub.asInterface(b);
                return new PowerManager(ctx.getOuterContext(),
                        service, ctx.mMainThread.getHandler());
            }});

        registerService(Context.RECOVERY_SERVICE, RecoverySystem.class,
                new CachedServiceFetcher<RecoverySystem>() {
            @Override
            public RecoverySystem createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.RECOVERY_SERVICE);
                IRecoverySystem service = IRecoverySystem.Stub.asInterface(b);
                return new RecoverySystem(service);
            }});

        registerService(Context.SEARCH_SERVICE, SearchManager.class,
                new CachedServiceFetcher<SearchManager>() {
            @Override
            public SearchManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new SearchManager(ctx.getOuterContext(),
                        ctx.mMainThread.getHandler());
            }});

        registerService(Context.SENSOR_SERVICE, SensorManager.class,
                new CachedServiceFetcher<SensorManager>() {
            @Override
            public SensorManager createService(ContextImpl ctx) {
                return new SystemSensorManager(ctx.getOuterContext(),
                  ctx.mMainThread.getHandler().getLooper());
            }});

        registerService(Context.SENSOR_PRIVACY_SERVICE, SensorPrivacyManager.class,
                new CachedServiceFetcher<SensorPrivacyManager>() {
                    @Override
                    public SensorPrivacyManager createService(ContextImpl ctx) {
                        return SensorPrivacyManager.getInstance(ctx);
                    }});

        registerService(Context.STATS_MANAGER, StatsManager.class,
                new CachedServiceFetcher<StatsManager>() {
            @Override
            public StatsManager createService(ContextImpl ctx) {
                return new StatsManager(ctx.getOuterContext());
            }});

        registerService(Context.STATUS_BAR_SERVICE, StatusBarManager.class,
                new CachedServiceFetcher<StatusBarManager>() {
            @Override
            public StatusBarManager createService(ContextImpl ctx) {
                return new StatusBarManager(ctx.getOuterContext());
            }});

        registerService(Context.STORAGE_SERVICE, StorageManager.class,
                new CachedServiceFetcher<StorageManager>() {
            @Override
            public StorageManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new StorageManager(ctx, ctx.mMainThread.getHandler().getLooper());
            }});

        registerService(Context.STORAGE_STATS_SERVICE, StorageStatsManager.class,
                new CachedServiceFetcher<StorageStatsManager>() {
            @Override
            public StorageStatsManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IStorageStatsManager service = IStorageStatsManager.Stub.asInterface(
                        ServiceManager.getServiceOrThrow(Context.STORAGE_STATS_SERVICE));
                return new StorageStatsManager(ctx, service);
            }});

        registerService(Context.SYSTEM_UPDATE_SERVICE, SystemUpdateManager.class,
                new CachedServiceFetcher<SystemUpdateManager>() {
                    @Override
                    public SystemUpdateManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getServiceOrThrow(
                                Context.SYSTEM_UPDATE_SERVICE);
                        ISystemUpdateManager service = ISystemUpdateManager.Stub.asInterface(b);
                        return new SystemUpdateManager(service);
                    }});

        registerService(Context.TELEPHONY_SERVICE, TelephonyManager.class,
                new CachedServiceFetcher<TelephonyManager>() {
            @Override
            public TelephonyManager createService(ContextImpl ctx) {
                return new TelephonyManager(ctx.getOuterContext());
            }});

        registerService(Context.TELEPHONY_SUBSCRIPTION_SERVICE, SubscriptionManager.class,
                new CachedServiceFetcher<SubscriptionManager>() {
            @Override
            public SubscriptionManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new SubscriptionManager(ctx.getOuterContext());
            }});

        registerService(Context.TELEPHONY_RCS_SERVICE, RcsManager.class,
                new CachedServiceFetcher<RcsManager>() {
                    @Override
                    public RcsManager createService(ContextImpl ctx) {
                        return new RcsManager();
                    }
                });

        registerService(Context.CARRIER_CONFIG_SERVICE, CarrierConfigManager.class,
                new CachedServiceFetcher<CarrierConfigManager>() {
            @Override
            public CarrierConfigManager createService(ContextImpl ctx) {
                return new CarrierConfigManager(ctx.getOuterContext());
            }});

        registerService(Context.TELECOM_SERVICE, TelecomManager.class,
                new CachedServiceFetcher<TelecomManager>() {
            @Override
            public TelecomManager createService(ContextImpl ctx) {
                return new TelecomManager(ctx.getOuterContext());
            }});

        registerService(Context.EUICC_SERVICE, EuiccManager.class,
                new CachedServiceFetcher<EuiccManager>() {
            @Override
            public EuiccManager createService(ContextImpl ctx) {
                return new EuiccManager(ctx.getOuterContext());
            }});

        registerService(Context.EUICC_CARD_SERVICE, EuiccCardManager.class,
                new CachedServiceFetcher<EuiccCardManager>() {
                    @Override
                    public EuiccCardManager createService(ContextImpl ctx) {
                        return new EuiccCardManager(ctx.getOuterContext());
                    }});

        registerService(Context.UI_MODE_SERVICE, UiModeManager.class,
                new CachedServiceFetcher<UiModeManager>() {
            @Override
            public UiModeManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new UiModeManager();
            }});

        registerService(Context.USB_SERVICE, UsbManager.class,
                new CachedServiceFetcher<UsbManager>() {
            @Override
            public UsbManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.USB_SERVICE);
                return new UsbManager(ctx, IUsbManager.Stub.asInterface(b));
            }});

        registerService(Context.ADB_SERVICE, AdbManager.class,
                new CachedServiceFetcher<AdbManager>() {
                    @Override
                    public AdbManager createService(ContextImpl ctx)
                                throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getServiceOrThrow(Context.ADB_SERVICE);
                        return new AdbManager(ctx, IAdbManager.Stub.asInterface(b));
                    }});

        registerService(Context.SERIAL_SERVICE, SerialManager.class,
                new CachedServiceFetcher<SerialManager>() {
            @Override
            public SerialManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.SERIAL_SERVICE);
                return new SerialManager(ctx, ISerialManager.Stub.asInterface(b));
            }});

        registerService(Context.VIBRATOR_SERVICE, Vibrator.class,
                new CachedServiceFetcher<Vibrator>() {
            @Override
            public Vibrator createService(ContextImpl ctx) {
                return new SystemVibrator(ctx);
            }});

        registerService(Context.WALLPAPER_SERVICE, WallpaperManager.class,
                new CachedServiceFetcher<WallpaperManager>() {
            @Override
            public WallpaperManager createService(ContextImpl ctx)
                    throws ServiceNotFoundException {
                final IBinder b;
                if (ctx.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.P) {
                    b = ServiceManager.getServiceOrThrow(Context.WALLPAPER_SERVICE);
                } else {
                    b = ServiceManager.getService(Context.WALLPAPER_SERVICE);
                }
                IWallpaperManager service = IWallpaperManager.Stub.asInterface(b);
                return new WallpaperManager(service, ctx.getOuterContext(),
                        ctx.mMainThread.getHandler());
            }});

        registerService(Context.LOWPAN_SERVICE, LowpanManager.class,
                new CachedServiceFetcher<LowpanManager>() {
            @Override
            public LowpanManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.LOWPAN_SERVICE);
                ILowpanManager service = ILowpanManager.Stub.asInterface(b);
                return new LowpanManager(ctx.getOuterContext(), service,
                        ConnectivityThread.getInstanceLooper());
            }});

        registerService(Context.WIFI_SERVICE, WifiManager.class,
                new CachedServiceFetcher<WifiManager>() {
            @Override
            public WifiManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.WIFI_SERVICE);
                IWifiManager service = IWifiManager.Stub.asInterface(b);
                return new WifiManager(ctx.getOuterContext(), service,
                        ConnectivityThread.getInstanceLooper());
            }});

        registerService(Context.WIFI_P2P_SERVICE, WifiP2pManager.class,
                new StaticServiceFetcher<WifiP2pManager>() {
            @Override
            public WifiP2pManager createService() throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.WIFI_P2P_SERVICE);
                IWifiP2pManager service = IWifiP2pManager.Stub.asInterface(b);
                return new WifiP2pManager(service);
            }});

        registerService(Context.WIFI_AWARE_SERVICE, WifiAwareManager.class,
                new CachedServiceFetcher<WifiAwareManager>() {
            @Override
            public WifiAwareManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.WIFI_AWARE_SERVICE);
                IWifiAwareManager service = IWifiAwareManager.Stub.asInterface(b);
                if (service == null) {
                    return null;
                }
                return new WifiAwareManager(ctx.getOuterContext(), service);
            }});

        registerService(Context.WIFI_SCANNING_SERVICE, WifiScanner.class,
                new CachedServiceFetcher<WifiScanner>() {
            @Override
            public WifiScanner createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.WIFI_SCANNING_SERVICE);
                IWifiScanner service = IWifiScanner.Stub.asInterface(b);
                return new WifiScanner(ctx.getOuterContext(), service,
                        ConnectivityThread.getInstanceLooper());
            }});

        registerService(Context.WIFI_RTT_SERVICE, RttManager.class,
                new CachedServiceFetcher<RttManager>() {
                @Override
                public RttManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                    IBinder b = ServiceManager.getServiceOrThrow(Context.WIFI_RTT_RANGING_SERVICE);
                    IWifiRttManager service = IWifiRttManager.Stub.asInterface(b);
                    return new RttManager(ctx.getOuterContext(),
                            new WifiRttManager(ctx.getOuterContext(), service));
                }});

        registerService(Context.WIFI_RTT_RANGING_SERVICE, WifiRttManager.class,
                new CachedServiceFetcher<WifiRttManager>() {
                    @Override
                    public WifiRttManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getServiceOrThrow(
                                Context.WIFI_RTT_RANGING_SERVICE);
                        IWifiRttManager service = IWifiRttManager.Stub.asInterface(b);
                        return new WifiRttManager(ctx.getOuterContext(), service);
                    }});

        registerService(Context.ETHERNET_SERVICE, EthernetManager.class,
                new CachedServiceFetcher<EthernetManager>() {
            @Override
            public EthernetManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.ETHERNET_SERVICE);
                IEthernetManager service = IEthernetManager.Stub.asInterface(b);
                return new EthernetManager(ctx.getOuterContext(), service);
            }});

        registerService(Context.WINDOW_SERVICE, WindowManager.class,
                new CachedServiceFetcher<WindowManager>() {
            @Override
            public WindowManager createService(ContextImpl ctx) {
                return new WindowManagerImpl(ctx);
            }});

        registerService(Context.USER_SERVICE, UserManager.class,
                new CachedServiceFetcher<UserManager>() {
            @Override
            public UserManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.USER_SERVICE);
                IUserManager service = IUserManager.Stub.asInterface(b);
                return new UserManager(ctx, service);
            }});

        registerService(Context.APP_OPS_SERVICE, AppOpsManager.class,
                new CachedServiceFetcher<AppOpsManager>() {
            @Override
            public AppOpsManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.APP_OPS_SERVICE);
                IAppOpsService service = IAppOpsService.Stub.asInterface(b);
                return new AppOpsManager(ctx, service);
            }});

        registerService(Context.CAMERA_SERVICE, CameraManager.class,
                new CachedServiceFetcher<CameraManager>() {
            @Override
            public CameraManager createService(ContextImpl ctx) {
                return new CameraManager(ctx);
            }});

        registerService(Context.LAUNCHER_APPS_SERVICE, LauncherApps.class,
                new CachedServiceFetcher<LauncherApps>() {
            @Override
            public LauncherApps createService(ContextImpl ctx) {
                return new LauncherApps(ctx);
            }});

        registerService(Context.RESTRICTIONS_SERVICE, RestrictionsManager.class,
                new CachedServiceFetcher<RestrictionsManager>() {
            @Override
            public RestrictionsManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.RESTRICTIONS_SERVICE);
                IRestrictionsManager service = IRestrictionsManager.Stub.asInterface(b);
                return new RestrictionsManager(ctx, service);
            }});

        registerService(Context.PRINT_SERVICE, PrintManager.class,
                new CachedServiceFetcher<PrintManager>() {
            @Override
            public PrintManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IPrintManager service = null;
                // If the feature not present, don't try to look up every time
                if (ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PRINTING)) {
                    service = IPrintManager.Stub.asInterface(ServiceManager
                            .getServiceOrThrow(Context.PRINT_SERVICE));
                }
                final int userId = ctx.getUserId();
                final int appId = UserHandle.getAppId(ctx.getApplicationInfo().uid);
                return new PrintManager(ctx.getOuterContext(), service, userId, appId);
            }});

        registerService(Context.COMPANION_DEVICE_SERVICE, CompanionDeviceManager.class,
                new CachedServiceFetcher<CompanionDeviceManager>() {
            @Override
            public CompanionDeviceManager createService(ContextImpl ctx)
                    throws ServiceNotFoundException {
                ICompanionDeviceManager service = null;
                // If the feature not present, don't try to look up every time
                if (ctx.getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
                    service = ICompanionDeviceManager.Stub.asInterface(
                            ServiceManager.getServiceOrThrow(Context.COMPANION_DEVICE_SERVICE));
                }
                return new CompanionDeviceManager(service, ctx.getOuterContext());
            }});

        registerService(Context.CONSUMER_IR_SERVICE, ConsumerIrManager.class,
                new CachedServiceFetcher<ConsumerIrManager>() {
            @Override
            public ConsumerIrManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new ConsumerIrManager(ctx);
            }});

        registerService(Context.MEDIA_SESSION_SERVICE, MediaSessionManager.class,
                new CachedServiceFetcher<MediaSessionManager>() {
            @Override
            public MediaSessionManager createService(ContextImpl ctx) {
                return new MediaSessionManager(ctx);
            }});

        registerService(Context.TRUST_SERVICE, TrustManager.class,
                new StaticServiceFetcher<TrustManager>() {
            @Override
            public TrustManager createService() throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.TRUST_SERVICE);
                return new TrustManager(b);
            }});

        registerService(Context.FINGERPRINT_SERVICE, FingerprintManager.class,
                new CachedServiceFetcher<FingerprintManager>() {
            @Override
            public FingerprintManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                final IBinder binder;
                if (ctx.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.O) {
                    binder = ServiceManager.getServiceOrThrow(Context.FINGERPRINT_SERVICE);
                } else {
                    binder = ServiceManager.getService(Context.FINGERPRINT_SERVICE);
                }
                IFingerprintService service = IFingerprintService.Stub.asInterface(binder);
                return new FingerprintManager(ctx.getOuterContext(), service);
            }});

        registerService(Context.FACE_SERVICE, FaceManager.class,
                new CachedServiceFetcher<FaceManager>() {
                    @Override
                    public FaceManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        final IBinder binder;
                        if (ctx.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.O) {
                            binder = ServiceManager.getServiceOrThrow(Context.FACE_SERVICE);
                        } else {
                            binder = ServiceManager.getService(Context.FACE_SERVICE);
                        }
                        IFaceService service = IFaceService.Stub.asInterface(binder);
                        return new FaceManager(ctx.getOuterContext(), service);
                    }
                });

        registerService(Context.IRIS_SERVICE, IrisManager.class,
                new CachedServiceFetcher<IrisManager>() {
                    @Override
                    public IrisManager createService(ContextImpl ctx)
                        throws ServiceNotFoundException {
                        final IBinder binder =
                                ServiceManager.getServiceOrThrow(Context.IRIS_SERVICE);
                        IIrisService service = IIrisService.Stub.asInterface(binder);
                        return new IrisManager(ctx.getOuterContext(), service);
                    }
                });

        registerService(Context.BIOMETRIC_SERVICE, BiometricManager.class,
                new CachedServiceFetcher<BiometricManager>() {
                    @Override
                    public BiometricManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        if (BiometricManager.hasBiometrics(ctx)) {
                            final IBinder binder =
                                    ServiceManager.getServiceOrThrow(Context.BIOMETRIC_SERVICE);
                            final IBiometricService service =
                                    IBiometricService.Stub.asInterface(binder);
                            return new BiometricManager(ctx.getOuterContext(), service);
                        } else {
                            // Allow access to the manager when service is null. This saves memory
                            // on devices without biometric hardware.
                            return new BiometricManager(ctx.getOuterContext(), null);
                        }
                    }
                });

        registerService(Context.TV_INPUT_SERVICE, TvInputManager.class,
                new CachedServiceFetcher<TvInputManager>() {
            @Override
            public TvInputManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder iBinder = ServiceManager.getServiceOrThrow(Context.TV_INPUT_SERVICE);
                ITvInputManager service = ITvInputManager.Stub.asInterface(iBinder);
                return new TvInputManager(service, ctx.getUserId());
            }});

        registerService(Context.NETWORK_SCORE_SERVICE, NetworkScoreManager.class,
                new CachedServiceFetcher<NetworkScoreManager>() {
            @Override
            public NetworkScoreManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new NetworkScoreManager(ctx);
            }});

        registerService(Context.USAGE_STATS_SERVICE, UsageStatsManager.class,
                new CachedServiceFetcher<UsageStatsManager>() {
            @Override
            public UsageStatsManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder iBinder = ServiceManager.getServiceOrThrow(Context.USAGE_STATS_SERVICE);
                IUsageStatsManager service = IUsageStatsManager.Stub.asInterface(iBinder);
                return new UsageStatsManager(ctx.getOuterContext(), service);
            }});

        registerService(Context.NETWORK_STATS_SERVICE, NetworkStatsManager.class,
                new CachedServiceFetcher<NetworkStatsManager>() {
            @Override
            public NetworkStatsManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new NetworkStatsManager(ctx.getOuterContext());
            }});

        registerService(Context.JOB_SCHEDULER_SERVICE, JobScheduler.class,
                new StaticServiceFetcher<JobScheduler>() {
            @Override
            public JobScheduler createService() throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.JOB_SCHEDULER_SERVICE);
                return new JobSchedulerImpl(IJobScheduler.Stub.asInterface(b));
            }});

        registerService(Context.PERSISTENT_DATA_BLOCK_SERVICE, PersistentDataBlockManager.class,
                new StaticServiceFetcher<PersistentDataBlockManager>() {
            @Override
            public PersistentDataBlockManager createService() throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.PERSISTENT_DATA_BLOCK_SERVICE);
                IPersistentDataBlockService persistentDataBlockService =
                        IPersistentDataBlockService.Stub.asInterface(b);
                if (persistentDataBlockService != null) {
                    return new PersistentDataBlockManager(persistentDataBlockService);
                } else {
                    // not supported
                    return null;
                }
            }});

        registerService(Context.OEM_LOCK_SERVICE, OemLockManager.class,
                new StaticServiceFetcher<OemLockManager>() {
            @Override
            public OemLockManager createService() throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.OEM_LOCK_SERVICE);
                IOemLockService oemLockService = IOemLockService.Stub.asInterface(b);
                if (oemLockService != null) {
                    return new OemLockManager(oemLockService);
                } else {
                    // not supported
                    return null;
                }
            }});

        registerService(Context.MEDIA_PROJECTION_SERVICE, MediaProjectionManager.class,
                new CachedServiceFetcher<MediaProjectionManager>() {
            @Override
            public MediaProjectionManager createService(ContextImpl ctx) {
                return new MediaProjectionManager(ctx);
            }});

        registerService(Context.APPWIDGET_SERVICE, AppWidgetManager.class,
                new CachedServiceFetcher<AppWidgetManager>() {
            @Override
            public AppWidgetManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.APPWIDGET_SERVICE);
                return new AppWidgetManager(ctx, IAppWidgetService.Stub.asInterface(b));
            }});

        registerService(Context.MIDI_SERVICE, MidiManager.class,
                new CachedServiceFetcher<MidiManager>() {
            @Override
            public MidiManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.MIDI_SERVICE);
                return new MidiManager(IMidiManager.Stub.asInterface(b));
            }});

        registerService(Context.RADIO_SERVICE, RadioManager.class,
                new CachedServiceFetcher<RadioManager>() {
            @Override
            public RadioManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new RadioManager(ctx);
            }});

        registerService(Context.HARDWARE_PROPERTIES_SERVICE, HardwarePropertiesManager.class,
                new CachedServiceFetcher<HardwarePropertiesManager>() {
            @Override
            public HardwarePropertiesManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.HARDWARE_PROPERTIES_SERVICE);
                IHardwarePropertiesManager service =
                        IHardwarePropertiesManager.Stub.asInterface(b);
                return new HardwarePropertiesManager(ctx, service);
            }});

        registerService(Context.SOUND_TRIGGER_SERVICE, SoundTriggerManager.class,
                new CachedServiceFetcher<SoundTriggerManager>() {
            @Override
            public SoundTriggerManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.SOUND_TRIGGER_SERVICE);
                return new SoundTriggerManager(ctx, ISoundTriggerService.Stub.asInterface(b));
            }});

        registerService(Context.SHORTCUT_SERVICE, ShortcutManager.class,
                new CachedServiceFetcher<ShortcutManager>() {
            @Override
            public ShortcutManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.SHORTCUT_SERVICE);
                return new ShortcutManager(ctx, IShortcutService.Stub.asInterface(b));
            }});

        registerService(Context.OVERLAY_SERVICE, OverlayManager.class,
                new CachedServiceFetcher<OverlayManager>() {
            @Override
            public OverlayManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.OVERLAY_SERVICE);
                return new OverlayManager(ctx, IOverlayManager.Stub.asInterface(b));
            }});

        registerService(Context.NETWORK_WATCHLIST_SERVICE, NetworkWatchlistManager.class,
                new CachedServiceFetcher<NetworkWatchlistManager>() {
                    @Override
                    public NetworkWatchlistManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b =
                                ServiceManager.getServiceOrThrow(Context.NETWORK_WATCHLIST_SERVICE);
                        return new NetworkWatchlistManager(ctx,
                                INetworkWatchlistManager.Stub.asInterface(b));
                    }});

        registerService(Context.SYSTEM_HEALTH_SERVICE, SystemHealthManager.class,
                new CachedServiceFetcher<SystemHealthManager>() {
            @Override
            public SystemHealthManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(BatteryStats.SERVICE_NAME);
                return new SystemHealthManager(IBatteryStats.Stub.asInterface(b));
            }});

        registerService(Context.CONTEXTHUB_SERVICE, ContextHubManager.class,
                new CachedServiceFetcher<ContextHubManager>() {
            @Override
            public ContextHubManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new ContextHubManager(ctx.getOuterContext(),
                  ctx.mMainThread.getHandler().getLooper());
            }});

        registerService(Context.INCIDENT_SERVICE, IncidentManager.class,
                new CachedServiceFetcher<IncidentManager>() {
            @Override
            public IncidentManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new IncidentManager(ctx);
            }});

        registerService(Context.BUGREPORT_SERVICE, BugreportManager.class,
                new CachedServiceFetcher<BugreportManager>() {
                    @Override
                    public BugreportManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getServiceOrThrow(Context.BUGREPORT_SERVICE);
                        return new BugreportManager(ctx.getOuterContext(),
                                IDumpstate.Stub.asInterface(b));
                    }});

        registerService(Context.AUTOFILL_MANAGER_SERVICE, AutofillManager.class,
                new CachedServiceFetcher<AutofillManager>() {
            @Override
            public AutofillManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                // Get the services without throwing as this is an optional feature
                IBinder b = ServiceManager.getService(Context.AUTOFILL_MANAGER_SERVICE);
                IAutoFillManager service = IAutoFillManager.Stub.asInterface(b);
                return new AutofillManager(ctx.getOuterContext(), service);
            }});

        registerService(Context.CONTENT_CAPTURE_MANAGER_SERVICE, ContentCaptureManager.class,
                new CachedServiceFetcher<ContentCaptureManager>() {
            @Override
            public ContentCaptureManager createService(ContextImpl ctx)
                    throws ServiceNotFoundException {
                // Get the services without throwing as this is an optional feature
                Context outerContext = ctx.getOuterContext();
                if (outerContext.isContentCaptureSupported()) {
                    IBinder b = ServiceManager
                            .getService(Context.CONTENT_CAPTURE_MANAGER_SERVICE);
                    IContentCaptureManager service = IContentCaptureManager.Stub.asInterface(b);
                    return new ContentCaptureManager(outerContext, service);
                }
                return null;
            }});

        registerService(Context.APP_PREDICTION_SERVICE, AppPredictionManager.class,
                new CachedServiceFetcher<AppPredictionManager>() {
            @Override
            public AppPredictionManager createService(ContextImpl ctx)
                    throws ServiceNotFoundException {
                return new AppPredictionManager(ctx);
            }
        });

        registerService(Context.CONTENT_SUGGESTIONS_SERVICE,
                ContentSuggestionsManager.class,
                new CachedServiceFetcher<ContentSuggestionsManager>() {
                    @Override
                    public ContentSuggestionsManager createService(ContextImpl ctx) {
                        // No throw as this is an optional service
                        IBinder b = ServiceManager.getService(
                                Context.CONTENT_SUGGESTIONS_SERVICE);
                        IContentSuggestionsManager service =
                                IContentSuggestionsManager.Stub.asInterface(b);
                        return new ContentSuggestionsManager(service);
                    }
                });

        registerService(Context.VR_SERVICE, VrManager.class, new CachedServiceFetcher<VrManager>() {
            @Override
            public VrManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.VR_SERVICE);
                return new VrManager(IVrManager.Stub.asInterface(b));
            }
        });

        registerService(Context.TIME_ZONE_RULES_MANAGER_SERVICE, RulesManager.class,
                new CachedServiceFetcher<RulesManager>() {
            @Override
            public RulesManager createService(ContextImpl ctx) {
                return new RulesManager(ctx.getOuterContext());
            }});

        registerService(Context.CROSS_PROFILE_APPS_SERVICE, CrossProfileApps.class,
                new CachedServiceFetcher<CrossProfileApps>() {
                    @Override
                    public CrossProfileApps createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getServiceOrThrow(
                                Context.CROSS_PROFILE_APPS_SERVICE);
                        return new CrossProfileApps(ctx.getOuterContext(),
                                ICrossProfileApps.Stub.asInterface(b));
                    }
                });

        registerService(Context.SLICE_SERVICE, SliceManager.class,
                new CachedServiceFetcher<SliceManager>() {
                    @Override
                    public SliceManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        return new SliceManager(ctx.getOuterContext(),
                                ctx.mMainThread.getHandler());
                    }
            });

        registerService(Context.DEVICE_IDLE_CONTROLLER, DeviceIdleManager.class,
                new CachedServiceFetcher<DeviceIdleManager>() {
                    @Override
                    public DeviceIdleManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IDeviceIdleController service = IDeviceIdleController.Stub.asInterface(
                                ServiceManager.getServiceOrThrow(
                                        Context.DEVICE_IDLE_CONTROLLER));
                        return new DeviceIdleManager(ctx.getOuterContext(), service);
                    }});

        registerService(Context.TIME_DETECTOR_SERVICE, TimeDetector.class,
                new CachedServiceFetcher<TimeDetector>() {
                    @Override
                    public TimeDetector createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        return new TimeDetector();
                    }});
        registerService(Context.TIME_ZONE_DETECTOR_SERVICE, TimeZoneDetector.class,
                new CachedServiceFetcher<TimeZoneDetector>() {
                    @Override
                    public TimeZoneDetector createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        return new TimeZoneDetector();
                    }});

        registerService(Context.PERMISSION_SERVICE, PermissionManager.class,
                new CachedServiceFetcher<PermissionManager>() {
                    @Override
                    public PermissionManager createService(ContextImpl ctx) {
                        return new PermissionManager(ctx.getOuterContext());
                    }});

        registerService(Context.PERMISSION_CONTROLLER_SERVICE, PermissionControllerManager.class,
                new CachedServiceFetcher<PermissionControllerManager>() {
                    @Override
                    public PermissionControllerManager createService(ContextImpl ctx) {
                        return new PermissionControllerManager(ctx.getOuterContext());
                    }});

        registerService(Context.ROLE_SERVICE, RoleManager.class,
                new CachedServiceFetcher<RoleManager>() {
                    @Override
                    public RoleManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        return new RoleManager(ctx.getOuterContext());
                    }});

        registerService(Context.ROLLBACK_SERVICE, RollbackManager.class,
                new CachedServiceFetcher<RollbackManager>() {
                    @Override
                    public RollbackManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getServiceOrThrow(Context.ROLLBACK_SERVICE);
                        return new RollbackManager(ctx.getOuterContext(),
                                IRollbackManager.Stub.asInterface(b));
                    }});
        //CHECKSTYLE:ON IndentationCheck
    }

    /**
     * Creates an array which is used to cache per-Context service instances.
     */
    public static Object[] createServiceCache() {
        return new Object[sServiceCacheSize];
    }

    /**
     * Gets a system service from a given context.
     */
    public static Object getSystemService(ContextImpl ctx, String name) {
        ServiceFetcher<?> fetcher = SYSTEM_SERVICE_FETCHERS.get(name);
        return fetcher != null ? fetcher.getService(ctx) : null;
    }

    /**
     * Gets the name of the system-level service that is represented by the specified class.
     */
    public static String getSystemServiceName(Class<?> serviceClass) {
        return SYSTEM_SERVICE_NAMES.get(serviceClass);
    }

    /**
     * Statically registers a system service with the context.
     * This method must be called during static initialization only.
     */
    private static <T> void registerService(String serviceName, Class<T> serviceClass,
            ServiceFetcher<T> serviceFetcher) {
        SYSTEM_SERVICE_NAMES.put(serviceClass, serviceName);
        SYSTEM_SERVICE_FETCHERS.put(serviceName, serviceFetcher);
    }

    /**
     * Base interface for classes that fetch services.
     * These objects must only be created during static initialization.
     */
    static abstract interface ServiceFetcher<T> {
        T getService(ContextImpl ctx);
    }

    /**
     * Override this class when the system service constructor needs a
     * ContextImpl and should be cached and retained by that context.
     */
    static abstract class CachedServiceFetcher<T> implements ServiceFetcher<T> {
        private final int mCacheIndex;

        CachedServiceFetcher() {
            // Note this class must be instantiated only by the static initializer of the
            // outer class (SystemServiceRegistry), which already does the synchronization,
            // so bare access to sServiceCacheSize is okay here.
            mCacheIndex = sServiceCacheSize++;
        }

        @Override
        @SuppressWarnings("unchecked")
        public final T getService(ContextImpl ctx) {
            final Object[] cache = ctx.mServiceCache;
            final int[] gates = ctx.mServiceInitializationStateArray;

            for (;;) {
                boolean doInitialize = false;
                synchronized (cache) {
                    // Return it if we already have a cached instance.
                    T service = (T) cache[mCacheIndex];
                    if (service != null || gates[mCacheIndex] == ContextImpl.STATE_NOT_FOUND) {
                        return service;
                    }

                    // If we get here, there's no cached instance.

                    // Grr... if gate is STATE_READY, then this means we initialized the service
                    // once but someone cleared it.
                    // We start over from STATE_UNINITIALIZED.
                    if (gates[mCacheIndex] == ContextImpl.STATE_READY) {
                        gates[mCacheIndex] = ContextImpl.STATE_UNINITIALIZED;
                    }

                    // It's possible for multiple threads to get here at the same time, so
                    // use the "gate" to make sure only the first thread will call createService().

                    // At this point, the gate must be either UNINITIALIZED or INITIALIZING.
                    if (gates[mCacheIndex] == ContextImpl.STATE_UNINITIALIZED) {
                        doInitialize = true;
                        gates[mCacheIndex] = ContextImpl.STATE_INITIALIZING;
                    }
                }

                if (doInitialize) {
                    // Only the first thread gets here.

                    T service = null;
                    @ServiceInitializationState int newState = ContextImpl.STATE_NOT_FOUND;
                    try {
                        // This thread is the first one to get here. Instantiate the service
                        // *without* the cache lock held.
                        service = createService(ctx);
                        newState = ContextImpl.STATE_READY;

                    } catch (ServiceNotFoundException e) {
                        onServiceNotFound(e);

                    } finally {
                        synchronized (cache) {
                            cache[mCacheIndex] = service;
                            gates[mCacheIndex] = newState;
                            cache.notifyAll();
                        }
                    }
                    return service;
                }
                // The other threads will wait for the first thread to call notifyAll(),
                // and go back to the top and retry.
                synchronized (cache) {
                    while (gates[mCacheIndex] < ContextImpl.STATE_READY) {
                        try {
                            cache.wait();
                        } catch (InterruptedException e) {
                            Log.w(TAG, "getService() interrupted");
                            Thread.currentThread().interrupt();
                            return null;
                        }
                    }
                }
            }
        }

        public abstract T createService(ContextImpl ctx) throws ServiceNotFoundException;
    }

    /**
     * Override this class when the system service does not need a ContextImpl
     * and should be cached and retained process-wide.
     */
    static abstract class StaticServiceFetcher<T> implements ServiceFetcher<T> {
        private T mCachedInstance;

        @Override
        public final T getService(ContextImpl ctx) {
            synchronized (StaticServiceFetcher.this) {
                if (mCachedInstance == null) {
                    try {
                        mCachedInstance = createService();
                    } catch (ServiceNotFoundException e) {
                        onServiceNotFound(e);
                    }
                }
                return mCachedInstance;
            }
        }

        public abstract T createService() throws ServiceNotFoundException;
    }

    /**
     * Like StaticServiceFetcher, creates only one instance of the service per application, but when
     * creating the service for the first time, passes it the application context of the creating
     * application.
     *
     * TODO: Delete this once its only user (ConnectivityManager) is known to work well in the
     * case where multiple application components each have their own ConnectivityManager object.
     */
    static abstract class StaticApplicationContextServiceFetcher<T> implements ServiceFetcher<T> {
        private T mCachedInstance;

        @Override
        public final T getService(ContextImpl ctx) {
            synchronized (StaticApplicationContextServiceFetcher.this) {
                if (mCachedInstance == null) {
                    Context appContext = ctx.getApplicationContext();
                    // If the application context is null, we're either in the system process or
                    // it's the application context very early in app initialization. In both these
                    // cases, the passed-in ContextImpl will not be freed, so it's safe to pass it
                    // to the service. http://b/27532714 .
                    try {
                        mCachedInstance = createService(appContext != null ? appContext : ctx);
                    } catch (ServiceNotFoundException e) {
                        onServiceNotFound(e);
                    }
                }
                return mCachedInstance;
            }
        }

        public abstract T createService(Context applicationContext) throws ServiceNotFoundException;
    }

    public static void onServiceNotFound(ServiceNotFoundException e) {
        // We're mostly interested in tracking down long-lived core system
        // components that might stumble if they obtain bad references; just
        // emit a tidy log message for normal apps
        if (android.os.Process.myUid() < android.os.Process.FIRST_APPLICATION_UID) {
            Log.wtf(TAG, e.getMessage(), e);
        } else {
            Log.w(TAG, e.getMessage());
        }
    }
}
