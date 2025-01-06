package com.android.systemui.qs.tiles.dialog;

import static android.net.wifi.sharedconnectivity.app.NetworkProviderInfo.DEVICE_TYPE_PHONE;
import static android.provider.Settings.Global.AIRPLANE_MODE_ON;
import static android.telephony.SignalStrength.NUM_SIGNAL_STRENGTH_BINS;
import static android.telephony.SignalStrength.SIGNAL_STRENGTH_GREAT;
import static android.telephony.SignalStrength.SIGNAL_STRENGTH_POOR;
import static android.telephony.SubscriptionManager.PROFILE_CLASS_PROVISIONING;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.settingslib.wifi.WifiUtils.getHotspotIconResource;
import static com.android.systemui.qs.tiles.dialog.InternetDetailsContentController.TOAST_PARAMS_HORIZONTAL_WEIGHT;
import static com.android.systemui.qs.tiles.dialog.InternetDetailsContentController.TOAST_PARAMS_VERTICAL_WEIGHT;
import static com.android.wifitrackerlib.WifiEntry.WIFI_LEVEL_MAX;
import static com.android.wifitrackerlib.WifiEntry.WIFI_LEVEL_MIN;
import static com.android.wifitrackerlib.WifiEntry.WIFI_LEVEL_UNREACHABLE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.animation.Animator;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.testing.TestableLooper;
import android.testing.TestableResources;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.app.viewcapture.ViewCaptureAwareWindowManager;
import com.android.internal.logging.UiEventLogger;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.settingslib.wifi.WifiUtils;
import com.android.settingslib.wifi.dpp.WifiDppIntentHelper;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.connectivity.AccessPointController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.toast.SystemUIToast;
import com.android.systemui.toast.ToastFactory;
import com.android.systemui.util.CarrierConfigTracker;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.wifitrackerlib.HotspotNetworkEntry;
import com.android.wifitrackerlib.MergedCarrierEntry;
import com.android.wifitrackerlib.WifiEntry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class InternetDetailsContentControllerTest extends SysuiTestCase {

    private static final int SUB_ID = 1;
    private static final int SUB_ID2 = 2;

    private MockitoSession mStaticMockSession;

    //SystemUIToast
    private static final int GRAVITY_FLAGS = Gravity.FILL_HORIZONTAL | Gravity.FILL_VERTICAL;
    private static final int TOAST_MESSAGE_STRING_ID = 1;
    private static final String TOAST_MESSAGE_STRING = "toast message";

    @Mock
    private WifiManager mWifiManager;
    @Mock
    private ConnectivityManager mConnectivityManager;
    @Mock
    private Network mNetwork;
    @Mock
    private NetworkCapabilities mNetworkCapabilities;
    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private SubscriptionManager mSubscriptionManager;
    @Mock
    private Handler mHandler;
    @Mock
    private Handler mWorkerHandler;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private GlobalSettings mGlobalSettings;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private AccessPointController mAccessPointController;
    @Mock
    private WifiEntry mConnectedEntry;
    @Mock
    private WifiEntry mWifiEntry1;
    @Mock
    private WifiEntry mWifiEntry2;
    @Mock
    private WifiEntry mWifiEntry3;
    @Mock
    private WifiEntry mWifiEntry4;
    @Mock
    private MergedCarrierEntry mMergedCarrierEntry;
    @Mock
    private ServiceState mServiceState;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private WifiUtils.InternetIconInjector mWifiIconInjector;
    @Mock
    InternetDetailsContentController.InternetDialogCallback mInternetDialogCallback;
    @Mock
    private ViewCaptureAwareWindowManager mWindowManager;
    @Mock
    private ToastFactory mToastFactory;
    @Mock
    private SystemUIToast mSystemUIToast;
    @Mock
    private View mToastView;
    @Mock
    private Animator mAnimator;
    @Mock
    private CarrierConfigTracker mCarrierConfigTracker;
    @Mock
    private LocationController mLocationController;
    @Mock
    private DialogTransitionAnimator mDialogTransitionAnimator;
    @Mock
    private View mDialogLaunchView;
    @Mock
    private WifiStateWorker mWifiStateWorker;
    @Mock
    private SignalStrength mSignalStrength;
    @Mock
    private WifiConfiguration mWifiConfiguration;

    private FakeFeatureFlags mFlags = new FakeFeatureFlags();

    private TestableResources mTestableResources;
    private InternetDetailsContentController mInternetDetailsContentController;
    private FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());
    private List<WifiEntry> mAccessPoints = new ArrayList<>();
    private List<WifiEntry> mWifiEntries = new ArrayList<>();

    private Configuration mConfig;

    @Before
    public void setUp() {
        mStaticMockSession = mockitoSession()
                .mockStatic(SubscriptionManager.class)
                .mockStatic(WifiDppIntentHelper.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        MockitoAnnotations.initMocks(this);
        mTestableResources = mContext.getOrCreateTestableResources();
        doReturn(mTelephonyManager).when(mTelephonyManager).createForSubscriptionId(anyInt());
        when(mTelephonyManager.getSignalStrength()).thenReturn(mSignalStrength);
        when(mSignalStrength.getLevel()).thenReturn(SIGNAL_STRENGTH_GREAT);
        when(mKeyguardStateController.isUnlocked()).thenReturn(true);
        when(mConnectedEntry.isDefaultNetwork()).thenReturn(true);
        when(mConnectedEntry.hasInternetAccess()).thenReturn(true);
        when(mWifiEntry1.getConnectedState()).thenReturn(WifiEntry.CONNECTED_STATE_DISCONNECTED);
        when(mWifiEntry2.getConnectedState()).thenReturn(WifiEntry.CONNECTED_STATE_DISCONNECTED);
        when(mWifiEntry3.getConnectedState()).thenReturn(WifiEntry.CONNECTED_STATE_DISCONNECTED);
        when(mWifiEntry4.getConnectedState()).thenReturn(WifiEntry.CONNECTED_STATE_DISCONNECTED);
        mAccessPoints.add(mConnectedEntry);
        mAccessPoints.add(mWifiEntry1);
        when(mAccessPointController.getMergedCarrierEntry()).thenReturn(mMergedCarrierEntry);
        when(mSubscriptionManager.getActiveSubscriptionIdList()).thenReturn(new int[]{SUB_ID});
        when(SubscriptionManager.getDefaultDataSubscriptionId()).thenReturn(SUB_ID);
        SubscriptionInfo info = mock(SubscriptionInfo.class);
        when(mSubscriptionManager.getActiveSubscriptionInfo(SUB_ID)).thenReturn(info);
        when(mToastFactory.createToast(any(), any(), anyString(), anyString(), anyInt(), anyInt()))
            .thenReturn(mSystemUIToast);
        when(mSystemUIToast.getView()).thenReturn(mToastView);
        when(mSystemUIToast.getGravity()).thenReturn(GRAVITY_FLAGS);
        when(mSystemUIToast.getInAnimation()).thenReturn(mAnimator);
        when(mWifiStateWorker.isWifiEnabled()).thenReturn(true);

        mInternetDetailsContentController = new InternetDetailsContentController(mContext,
                mock(UiEventLogger.class), mock(ActivityStarter.class), mAccessPointController,
                mSubscriptionManager, mTelephonyManager, mWifiManager,
                mConnectivityManager, mHandler, mExecutor, mBroadcastDispatcher,
                mock(KeyguardUpdateMonitor.class), mGlobalSettings, mKeyguardStateController,
                mWindowManager, mToastFactory, mWorkerHandler,
                mCarrierConfigTracker, mLocationController, mDialogTransitionAnimator,
                mWifiStateWorker, mFlags);
        mSubscriptionManager.addOnSubscriptionsChangedListener(mExecutor,
                mInternetDetailsContentController.mOnSubscriptionsChangedListener);
        mInternetDetailsContentController.onStart(mInternetDialogCallback, true);
        mInternetDetailsContentController.onAccessPointsChanged(mAccessPoints);
        mInternetDetailsContentController.mActivityStarter = mActivityStarter;
        mInternetDetailsContentController.mWifiIconInjector = mWifiIconInjector;
        mFlags.set(Flags.QS_SECONDARY_DATA_SUB_INFO, false);
        mFlags.set(Flags.SHARE_WIFI_QS_BUTTON, false);

        mConfig = new Configuration(mContext.getResources().getConfiguration());
        Configuration c2 = new Configuration(mConfig);
        c2.setLocale(Locale.US);
        mContext.getResources().updateConfiguration(c2, null);
    }

    @After
    public void tearDown() {
        mStaticMockSession.finishMocking();
        mContext.getResources().updateConfiguration(mConfig, null);
    }

    @Test
    public void connectCarrierNetwork_mergedCarrierEntryCanConnect_connectAndCreateSysUiToast() {
        InternetDetailsContentController spyController = spy(mInternetDetailsContentController);
        when(spyController.isMobileDataEnabled()).thenReturn(true);
        when(mKeyguardStateController.isUnlocked()).thenReturn(true);
        when(mConnectivityManager.getActiveNetwork()).thenReturn(mNetwork);
        when(mConnectivityManager.getNetworkCapabilities(mNetwork))
                .thenReturn(mNetworkCapabilities);
        when(mNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                .thenReturn(false);

        when(mMergedCarrierEntry.canConnect()).thenReturn(true);
        mTestableResources.addOverride(R.string.wifi_wont_autoconnect_for_now,
            TOAST_MESSAGE_STRING);

        spyController.connectCarrierNetwork();

        verify(mMergedCarrierEntry).connect(null /* callback */, false /* showToast */);
        verify(mToastFactory).createToast(any(), any(), eq(TOAST_MESSAGE_STRING), anyString(),
                anyInt(), anyInt());
    }

    @Test
    public void connectCarrierNetwork_mergedCarrierEntryCanConnect_doNothingWhenSettingsOff() {
        InternetDetailsContentController spyController = spy(mInternetDetailsContentController);
        when(spyController.isMobileDataEnabled()).thenReturn(false);
        mTestableResources.addOverride(R.string.wifi_wont_autoconnect_for_now,
            TOAST_MESSAGE_STRING);

        spyController.connectCarrierNetwork();

        verify(mMergedCarrierEntry, never()).connect(null /* callback */, false /* showToast */);
        verify(mToastFactory, never()).createToast(any(), any(), anyString(), anyString(), anyInt(),
            anyInt());
    }

    @Test
    public void connectCarrierNetwork_mergedCarrierEntryCanConnect_doNothingWhenKeyguardLocked() {
        InternetDetailsContentController spyController = spy(mInternetDetailsContentController);
        when(spyController.isMobileDataEnabled()).thenReturn(true);
        when(mKeyguardStateController.isUnlocked()).thenReturn(false);

        mTestableResources.addOverride(R.string.wifi_wont_autoconnect_for_now,
            TOAST_MESSAGE_STRING);
        spyController.connectCarrierNetwork();

        verify(mMergedCarrierEntry, never()).connect(null /* callback */, false /* showToast */);
        verify(mToastFactory, never()).createToast(any(), any(), anyString(), anyString(), anyInt(),
            anyInt());
    }

    @Test
    public void connectCarrierNetwork_mergedCarrierEntryCanConnect_doNothingWhenMobileIsPrimary() {
        when(mKeyguardStateController.isUnlocked()).thenReturn(true);
        when(mConnectivityManager.getActiveNetwork()).thenReturn(mNetwork);
        when(mConnectivityManager.getNetworkCapabilities(mNetwork))
                .thenReturn(mNetworkCapabilities);
        when(mNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                .thenReturn(true);

        mTestableResources.addOverride(R.string.wifi_wont_autoconnect_for_now,
            TOAST_MESSAGE_STRING);
        mInternetDetailsContentController.connectCarrierNetwork();

        verify(mMergedCarrierEntry, never()).connect(null /* callback */, false /* showToast */);
        verify(mToastFactory, never()).createToast(any(), any(), anyString(), anyString(), anyInt(),
            anyInt());
    }

    @Test
    public void makeOverlayToast_withGravityFlags_addViewWithLayoutParams() {
        mTestableResources.addOverride(TOAST_MESSAGE_STRING_ID, TOAST_MESSAGE_STRING);

        mInternetDetailsContentController.makeOverlayToast(TOAST_MESSAGE_STRING_ID);

        ArgumentCaptor<WindowManager.LayoutParams> paramsCaptor = ArgumentCaptor.forClass(
            WindowManager.LayoutParams.class);
        verify(mWindowManager).addView(eq(mToastView), paramsCaptor.capture());
        WindowManager.LayoutParams params = paramsCaptor.getValue();
        assertThat(params.format).isEqualTo(PixelFormat.TRANSLUCENT);
        assertThat(params.type).isEqualTo(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL);
        assertThat(params.horizontalWeight).isEqualTo(TOAST_PARAMS_HORIZONTAL_WEIGHT);
        assertThat(params.verticalWeight).isEqualTo(TOAST_PARAMS_VERTICAL_WEIGHT);
    }

    @Test
    public void makeOverlayToast_withAnimation_verifyAnimatorStart() {
        mTestableResources.addOverride(TOAST_MESSAGE_STRING_ID, TOAST_MESSAGE_STRING);

        mInternetDetailsContentController.makeOverlayToast(TOAST_MESSAGE_STRING_ID);

        verify(mAnimator).start();
    }

    @Test
    public void getDialogTitleText_withAirplaneModeOn_returnAirplaneMode() {
        fakeAirplaneModeEnabled(true);

        assertTrue(TextUtils.equals(mInternetDetailsContentController.getDialogTitleText(),
                getResourcesString("airplane_mode")));
    }

    @Test
    public void getDialogTitleText_withAirplaneModeOff_returnInternet() {
        fakeAirplaneModeEnabled(false);

        assertTrue(TextUtils.equals(mInternetDetailsContentController.getDialogTitleText(),
                getResourcesString("quick_settings_internet_label")));
    }

    @Test
    public void getSubtitleText_withApmOnAndWifiOff_returnWifiIsOff() {
        fakeAirplaneModeEnabled(true);
        when(mWifiStateWorker.isWifiEnabled()).thenReturn(false);

        assertThat(mInternetDetailsContentController.getSubtitleText(false))
                .isEqualTo(getResourcesString("wifi_is_off"));

        // if the Wi-Fi disallow config, then don't return Wi-Fi related string.
        mInternetDetailsContentController.mCanConfigWifi = false;

        assertThat(mInternetDetailsContentController.getSubtitleText(false))
                .isNotEqualTo(getResourcesString("wifi_is_off"));
    }

    @Test
    public void getSubtitleText_withWifiOff_returnWifiIsOff() {
        fakeAirplaneModeEnabled(false);
        when(mWifiStateWorker.isWifiEnabled()).thenReturn(false);

        assertThat(mInternetDetailsContentController.getSubtitleText(false))
                .isEqualTo(getResourcesString("wifi_is_off"));

        // if the Wi-Fi disallow config, then don't return Wi-Fi related string.
        mInternetDetailsContentController.mCanConfigWifi = false;

        assertThat(mInternetDetailsContentController.getSubtitleText(false))
                .isNotEqualTo(getResourcesString("wifi_is_off"));
    }

    @Test
    public void getSubtitleText_withNoWifiEntry_returnSearchWifi() {
        fakeAirplaneModeEnabled(false);
        when(mWifiStateWorker.isWifiEnabled()).thenReturn(true);
        mInternetDetailsContentController.onAccessPointsChanged(null /* accessPoints */);

        assertThat(mInternetDetailsContentController.getSubtitleText(true))
                .isEqualTo(getResourcesString("wifi_empty_list_wifi_on"));

        // if the Wi-Fi disallow config, then don't return Wi-Fi related string.
        mInternetDetailsContentController.mCanConfigWifi = false;

        assertThat(mInternetDetailsContentController.getSubtitleText(true))
                .isNotEqualTo(getResourcesString("wifi_empty_list_wifi_on"));
    }

    @Test
    public void getSubtitleText_withWifiEntry_returnTapToConnect() {
        // The preconditions WiFi Entries is already in setUp()
        fakeAirplaneModeEnabled(false);
        when(mWifiStateWorker.isWifiEnabled()).thenReturn(true);

        assertThat(mInternetDetailsContentController.getSubtitleText(false))
                .isEqualTo(getResourcesString("tap_a_network_to_connect"));

        // if the Wi-Fi disallow config, then don't return Wi-Fi related string.
        mInternetDetailsContentController.mCanConfigWifi = false;

        assertThat(mInternetDetailsContentController.getSubtitleText(false))
                .isNotEqualTo(getResourcesString("tap_a_network_to_connect"));
    }

    @Test
    public void getSubtitleText_deviceLockedWithWifiOn_returnUnlockToViewNetworks() {
        fakeAirplaneModeEnabled(false);
        when(mWifiStateWorker.isWifiEnabled()).thenReturn(true);
        when(mKeyguardStateController.isUnlocked()).thenReturn(false);

        assertTrue(TextUtils.equals(mInternetDetailsContentController.getSubtitleText(false),
                getResourcesString("unlock_to_view_networks")));
    }

    @Test
    public void getSubtitleText_withNoService_returnNoNetworksAvailable() {
        mFlags.set(Flags.QS_SECONDARY_DATA_SUB_INFO, true);
        InternetDetailsContentController spyController = spy(mInternetDetailsContentController);
        fakeAirplaneModeEnabled(false);
        when(mWifiStateWorker.isWifiEnabled()).thenReturn(true);
        spyController.onAccessPointsChanged(null /* accessPoints */);

        doReturn(SUB_ID).when(spyController).getActiveAutoSwitchNonDdsSubId();
        doReturn(ServiceState.STATE_OUT_OF_SERVICE).when(mServiceState).getState();
        spyController.mSubIdServiceState.put(SUB_ID2, mServiceState);

        assertFalse(TextUtils.equals(spyController.getSubtitleText(false),
                getResourcesString("all_network_unavailable")));

        doReturn(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                .when(spyController).getActiveAutoSwitchNonDdsSubId();
        spyController.onAccessPointsChanged(null /* accessPoints */);
        assertTrue(TextUtils.equals(spyController.getSubtitleText(false),
                getResourcesString("all_network_unavailable")));
    }

    @Test
    public void getSubtitleText_withNoService_returnNoNetworksAvailable_flagOff() {
        InternetDetailsContentController spyController = spy(mInternetDetailsContentController);
        fakeAirplaneModeEnabled(false);
        when(mWifiStateWorker.isWifiEnabled()).thenReturn(true);
        spyController.onAccessPointsChanged(null /* accessPoints */);

        doReturn(ServiceState.STATE_OUT_OF_SERVICE).when(mServiceState).getState();
        spyController.mSubIdServiceState.put(SUB_ID, mServiceState);

        assertTrue(TextUtils.equals(spyController.getSubtitleText(false),
                getResourcesString("all_network_unavailable")));

        doReturn(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                .when(spyController).getActiveAutoSwitchNonDdsSubId();
        spyController.onAccessPointsChanged(null /* accessPoints */);
        assertTrue(TextUtils.equals(spyController.getSubtitleText(false),
                getResourcesString("all_network_unavailable")));
    }

    @Test
    public void getSubtitleText_withMobileDataDisabled_returnNoOtherAvailable() {
        fakeAirplaneModeEnabled(false);
        when(mWifiStateWorker.isWifiEnabled()).thenReturn(true);
        mInternetDetailsContentController.onAccessPointsChanged(null /* accessPoints */);
        InternetDetailsContentController spyController = spy(mInternetDetailsContentController);

        doReturn(ServiceState.STATE_IN_SERVICE).when(mServiceState).getState();
        spyController.mSubIdServiceState.put(SUB_ID, mServiceState);

        assertThat(mInternetDetailsContentController.getSubtitleText(false))
                .isEqualTo(getResourcesString("non_carrier_network_unavailable"));

        // if the Wi-Fi disallow config, then don't return Wi-Fi related string.
        mInternetDetailsContentController.mCanConfigWifi = false;

        when(spyController.isMobileDataEnabled()).thenReturn(false);


        assertThat(mInternetDetailsContentController.getSubtitleText(false))
                .isNotEqualTo(getResourcesString("non_carrier_network_unavailable"));
    }

    @Test
    public void getSubtitleText_withCarrierNetworkActiveOnly_returnNoOtherAvailable() {
        fakeAirplaneModeEnabled(false);
        when(mWifiStateWorker.isWifiEnabled()).thenReturn(true);
        mInternetDetailsContentController.onAccessPointsChanged(null /* accessPoints */);
        when(mMergedCarrierEntry.isDefaultNetwork()).thenReturn(true);

        assertThat(mInternetDetailsContentController.getSubtitleText(false))
                .isEqualTo(getResourcesString("non_carrier_network_unavailable"));
    }

    @Test
    public void getWifiDetailsSettingsIntent_withNoKey_returnNull() {
        assertThat(mInternetDetailsContentController.getWifiDetailsSettingsIntent(null)).isNull();
    }

    @Test
    public void getWifiDetailsSettingsIntent_withKey_returnIntent() {
        assertThat(mInternetDetailsContentController.getWifiDetailsSettingsIntent(
                "test_key")).isNotNull();
    }

    @Test
    public void getInternetWifiDrawable_withConnectedEntry_returnIntentIconWithCorrectColor() {
        final Drawable drawable = mock(Drawable.class);
        when(mWifiIconInjector.getIcon(anyBoolean(), anyInt())).thenReturn(drawable);

        mInternetDetailsContentController.getInternetWifiDrawable(mConnectedEntry);

        verify(mWifiIconInjector).getIcon(eq(false), anyInt());
        verify(drawable).setTint(mContext.getColor(R.color.connected_network_primary_color));
    }

    @Test
    public void getWifiDrawable_withWifiLevelUnreachable_returnNull() {
        when(mConnectedEntry.getLevel()).thenReturn(WIFI_LEVEL_UNREACHABLE);

        assertThat(mInternetDetailsContentController.getWifiDrawable(mConnectedEntry)).isNull();
    }

    @Test
    public void getWifiDrawable_withHotspotNetworkEntry_returnHotspotDrawable() {
        HotspotNetworkEntry entry = mock(HotspotNetworkEntry.class);
        when(entry.getDeviceType()).thenReturn(DEVICE_TYPE_PHONE);
        Drawable hotspotDrawable = mock(Drawable.class);
        mTestableResources.addOverride(getHotspotIconResource(DEVICE_TYPE_PHONE), hotspotDrawable);

        assertThat(mInternetDetailsContentController.getWifiDrawable(entry)).isEqualTo(
                hotspotDrawable);
    }

    @Test
    public void startActivityForDialog_always_startActivityWithoutDismissShade() {
        mInternetDetailsContentController.startActivityForDialog(mock(Intent.class));

        verify(mActivityStarter).startActivity(any(Intent.class), eq(false) /* dismissShade */);
    }

    @Test
    public void launchWifiDetailsSetting_withNoWifiEntryKey_doNothing() {
        mInternetDetailsContentController.launchWifiDetailsSetting(null /* key */,
                mDialogLaunchView);

        verify(mActivityStarter, never())
                .postStartActivityDismissingKeyguard(any(Intent.class), anyInt());
    }

    @Test
    public void launchWifiDetailsSetting_withWifiEntryKey_startActivity() {
        mInternetDetailsContentController.launchWifiDetailsSetting("wifi_entry_key",
                mDialogLaunchView);

        verify(mActivityStarter).postStartActivityDismissingKeyguard(any(Intent.class), anyInt(),
                any());
    }

    @Test
    public void isDeviceLocked_keyguardIsUnlocked_returnFalse() {
        when(mKeyguardStateController.isUnlocked()).thenReturn(true);

        assertThat(mInternetDetailsContentController.isDeviceLocked()).isFalse();
    }

    @Test
    public void isDeviceLocked_keyguardIsLocked_returnTrue() {
        when(mKeyguardStateController.isUnlocked()).thenReturn(false);

        assertThat(mInternetDetailsContentController.isDeviceLocked()).isTrue();
    }

    @Test
    public void onAccessPointsChanged_canNotConfigWifi_doNothing() {
        reset(mInternetDialogCallback);
        mInternetDetailsContentController.mCanConfigWifi = false;

        mInternetDetailsContentController.onAccessPointsChanged(null /* accessPoints */);

        verify(mInternetDialogCallback, never()).onAccessPointsChanged(any(), any(), anyBoolean());
    }

    @Test
    public void onAccessPointsChanged_nullAccessPoints_callbackBothNull() {
        reset(mInternetDialogCallback);

        mInternetDetailsContentController.onAccessPointsChanged(null /* accessPoints */);

        verify(mInternetDialogCallback).onAccessPointsChanged(null /* wifiEntries */,
                null /* connectedEntry */, false /* hasMoreEntry */);
    }

    @Test
    public void onAccessPointsChanged_oneConnectedEntry_callbackConnectedEntryOnly() {
        reset(mInternetDialogCallback);
        mAccessPoints.clear();
        mAccessPoints.add(mConnectedEntry);

        mInternetDetailsContentController.onAccessPointsChanged(mAccessPoints);

        mWifiEntries.clear();
        verify(mInternetDialogCallback).onAccessPointsChanged(mWifiEntries, mConnectedEntry,
                false /* hasMoreEntry */);
    }

    @Test
    public void onAccessPointsChanged_noConnectedEntryAndOneOther_callbackWifiEntriesOnly() {
        reset(mInternetDialogCallback);
        mAccessPoints.clear();
        mAccessPoints.add(mWifiEntry1);

        mInternetDetailsContentController.onAccessPointsChanged(mAccessPoints);

        mWifiEntries.clear();
        mWifiEntries.add(mWifiEntry1);
        verify(mInternetDialogCallback).onAccessPointsChanged(mWifiEntries,
                null /* connectedEntry */, false /* hasMoreEntry */);
    }

    @Test
    public void onAccessPointsChanged_oneConnectedEntryAndOneOther_callbackCorrectly() {
        reset(mInternetDialogCallback);
        mAccessPoints.clear();
        mAccessPoints.add(mConnectedEntry);
        mAccessPoints.add(mWifiEntry1);

        mInternetDetailsContentController.onAccessPointsChanged(mAccessPoints);

        mWifiEntries.clear();
        mWifiEntries.add(mWifiEntry1);
        verify(mInternetDialogCallback).onAccessPointsChanged(mWifiEntries, mConnectedEntry,
                false /* hasMoreEntry */);
    }

    @Test
    public void onAccessPointsChanged_oneConnectedEntryAndTwoOthers_callbackCorrectly() {
        reset(mInternetDialogCallback);
        mAccessPoints.clear();
        mAccessPoints.add(mConnectedEntry);
        mAccessPoints.add(mWifiEntry1);
        mAccessPoints.add(mWifiEntry2);

        mInternetDetailsContentController.onAccessPointsChanged(mAccessPoints);

        mWifiEntries.clear();
        mWifiEntries.add(mWifiEntry1);
        mWifiEntries.add(mWifiEntry2);
        verify(mInternetDialogCallback).onAccessPointsChanged(mWifiEntries, mConnectedEntry,
                false /* hasMoreEntry */);
    }

    @Test
    public void onAccessPointsChanged_oneConnectedEntryAndThreeOthers_callbackCutMore() {
        reset(mInternetDialogCallback);
        mAccessPoints.clear();
        mAccessPoints.add(mConnectedEntry);
        mAccessPoints.add(mWifiEntry1);
        mAccessPoints.add(mWifiEntry2);
        mAccessPoints.add(mWifiEntry3);

        mInternetDetailsContentController.onAccessPointsChanged(mAccessPoints);

        mWifiEntries.clear();
        mWifiEntries.add(mWifiEntry1);
        mWifiEntries.add(mWifiEntry2);
        verify(mInternetDialogCallback).onAccessPointsChanged(mWifiEntries, mConnectedEntry,
                true /* hasMoreEntry */);
    }

    @Test
    public void onAccessPointsChanged_fourWifiEntries_callbackCutMore() {
        reset(mInternetDialogCallback);
        mAccessPoints.clear();
        mAccessPoints.add(mWifiEntry1);
        mAccessPoints.add(mWifiEntry2);
        mAccessPoints.add(mWifiEntry3);
        mAccessPoints.add(mWifiEntry4);

        mInternetDetailsContentController.onAccessPointsChanged(mAccessPoints);

        mWifiEntries.clear();
        mWifiEntries.add(mWifiEntry1);
        mWifiEntries.add(mWifiEntry2);
        mWifiEntries.add(mWifiEntry3);
        verify(mInternetDialogCallback).onAccessPointsChanged(mWifiEntries,
                null /* connectedEntry */, true /* hasMoreEntry */);
    }

    @Test
    public void onAccessPointsChanged_wifiIsDefaultButNoInternetAccess_putIntoWifiEntries() {
        reset(mInternetDialogCallback);
        mAccessPoints.clear();
        when(mWifiEntry1.getConnectedState()).thenReturn(WifiEntry.CONNECTED_STATE_CONNECTED);
        when(mWifiEntry1.isDefaultNetwork()).thenReturn(true);
        when(mWifiEntry1.hasInternetAccess()).thenReturn(false);
        mAccessPoints.add(mWifiEntry1);

        mInternetDetailsContentController.onAccessPointsChanged(mAccessPoints);

        mWifiEntries.clear();
        mWifiEntries.add(mWifiEntry1);
        verify(mInternetDialogCallback).onAccessPointsChanged(mWifiEntries,
                null /* connectedEntry */, false /* hasMoreEntry */);
    }

    @Test
    public void onAccessPointsChanged_connectedWifiNoInternetAccess_shouldSetListener() {
        reset(mWifiEntry1);
        mAccessPoints.clear();
        when(mWifiEntry1.getConnectedState()).thenReturn(WifiEntry.CONNECTED_STATE_CONNECTED);
        when(mWifiEntry1.isDefaultNetwork()).thenReturn(true);
        when(mWifiEntry1.hasInternetAccess()).thenReturn(false);
        mAccessPoints.add(mWifiEntry1);

        mInternetDetailsContentController.onAccessPointsChanged(mAccessPoints);

        verify(mWifiEntry1).setListener(
                mInternetDetailsContentController.mConnectedWifiInternetMonitor);
    }

    @Test
    public void onUpdated_connectedWifiHasInternetAccess_shouldScanWifiAccessPoints() {
        reset(mAccessPointController);
        when(mWifiEntry1.getConnectedState()).thenReturn(WifiEntry.CONNECTED_STATE_CONNECTED);
        when(mWifiEntry1.isDefaultNetwork()).thenReturn(true);
        when(mWifiEntry1.hasInternetAccess()).thenReturn(false);
        InternetDetailsContentController.ConnectedWifiInternetMonitor
                mConnectedWifiInternetMonitor =
                mInternetDetailsContentController.mConnectedWifiInternetMonitor;
        mConnectedWifiInternetMonitor.registerCallbackIfNeed(mWifiEntry1);

        // When the hasInternetAccess() changed to true, and call back the onUpdated() function.
        when(mWifiEntry1.hasInternetAccess()).thenReturn(true);
        mConnectedWifiInternetMonitor.onUpdated();

        verify(mAccessPointController).scanForAccessPoints();
    }

    @Test
    public void onWifiScan_isWifiEnabledFalse_callbackOnWifiScanFalse() {
        reset(mInternetDialogCallback);
        when(mWifiStateWorker.isWifiEnabled()).thenReturn(false);

        mInternetDetailsContentController.onWifiScan(true);

        verify(mInternetDialogCallback).onWifiScan(false);
    }

    @Test
    public void onWifiScan_isDeviceLockedTrue_callbackOnWifiScanFalse() {
        reset(mInternetDialogCallback);
        when(mKeyguardStateController.isUnlocked()).thenReturn(false);

        mInternetDetailsContentController.onWifiScan(true);

        verify(mInternetDialogCallback).onWifiScan(false);
    }

    @Test
    public void onWifiScan_onWifiScanFalse_callbackOnWifiScanFalse() {
        reset(mInternetDialogCallback);

        mInternetDetailsContentController.onWifiScan(false);

        verify(mInternetDialogCallback).onWifiScan(false);
    }

    @Test
    public void onWifiScan_onWifiScanTrue_callbackOnWifiScanTrue() {
        reset(mInternetDialogCallback);

        mInternetDetailsContentController.onWifiScan(true);

        verify(mInternetDialogCallback).onWifiScan(true);
    }

    @Test
    public void setMergedCarrierWifiEnabledIfNeed_carrierProvisionsEnabled_doNothing() {
        when(mCarrierConfigTracker.getCarrierProvisionsWifiMergedNetworksBool(SUB_ID))
                .thenReturn(true);

        mInternetDetailsContentController.setMergedCarrierWifiEnabledIfNeed(SUB_ID, true);

        verify(mMergedCarrierEntry, never()).setEnabled(anyBoolean());
    }

    @Test
    public void setMergedCarrierWifiEnabledIfNeed_mergedCarrierEntryEmpty_doesntCrash() {
        when(mCarrierConfigTracker.getCarrierProvisionsWifiMergedNetworksBool(SUB_ID))
                .thenReturn(false);
        when(mAccessPointController.getMergedCarrierEntry()).thenReturn(null);

        mInternetDetailsContentController.setMergedCarrierWifiEnabledIfNeed(SUB_ID, true);
    }

    @Test
    public void setMergedCarrierWifiEnabledIfNeed_neededSetMergedCarrierEntry_setTogether() {
        when(mCarrierConfigTracker.getCarrierProvisionsWifiMergedNetworksBool(SUB_ID))
                .thenReturn(false);

        mInternetDetailsContentController.setMergedCarrierWifiEnabledIfNeed(SUB_ID, true);

        verify(mMergedCarrierEntry).setEnabled(true);

        mInternetDetailsContentController.setMergedCarrierWifiEnabledIfNeed(SUB_ID, false);

        verify(mMergedCarrierEntry).setEnabled(false);
    }

    @Test
    public void isWifiScanEnabled_locationOff_returnFalse() {
        when(mLocationController.isLocationEnabled()).thenReturn(false);
        when(mWifiManager.isScanAlwaysAvailable()).thenReturn(false);

        assertThat(mInternetDetailsContentController.isWifiScanEnabled()).isFalse();

        when(mWifiManager.isScanAlwaysAvailable()).thenReturn(true);

        assertThat(mInternetDetailsContentController.isWifiScanEnabled()).isFalse();
    }

    @Test
    public void isWifiScanEnabled_locationOn_returnIsScanAlwaysAvailable() {
        when(mLocationController.isLocationEnabled()).thenReturn(true);
        when(mWifiManager.isScanAlwaysAvailable()).thenReturn(false);

        assertThat(mInternetDetailsContentController.isWifiScanEnabled()).isFalse();

        when(mWifiManager.isScanAlwaysAvailable()).thenReturn(true);

        assertThat(mInternetDetailsContentController.isWifiScanEnabled()).isTrue();
    }

    @Test
    public void getSignalStrengthIcon_differentSubId() {
        mFlags.set(Flags.QS_SECONDARY_DATA_SUB_INFO, true);
        InternetDetailsContentController spyController = spy(mInternetDetailsContentController);
        Drawable icons = spyController.getSignalStrengthIcon(SUB_ID, mContext, 1, 1, 0, false);
        Drawable icons2 = spyController.getSignalStrengthIcon(SUB_ID2, mContext, 1, 1, 0, false);

        assertThat(icons).isNotEqualTo(icons2);
    }

    @Test
    public void getActiveAutoSwitchNonDdsSubId() {
        mFlags.set(Flags.QS_SECONDARY_DATA_SUB_INFO, true);
        // active on non-DDS
        SubscriptionInfo info = mock(SubscriptionInfo.class);
        doReturn(SUB_ID2).when(info).getSubscriptionId();
        when(mSubscriptionManager.getActiveSubscriptionInfo(anyInt())).thenReturn(info);

        int subId = mInternetDetailsContentController.getActiveAutoSwitchNonDdsSubId();
        assertThat(subId).isEqualTo(SUB_ID2);

        // active on CBRS
        doReturn(true).when(info).isOpportunistic();
        subId = mInternetDetailsContentController.getActiveAutoSwitchNonDdsSubId();
        assertThat(subId).isEqualTo(SubscriptionManager.INVALID_SUBSCRIPTION_ID);

        // active on DDS
        doReturn(false).when(info).isOpportunistic();
        doReturn(SUB_ID).when(info).getSubscriptionId();
        when(mSubscriptionManager.getActiveSubscriptionInfo(anyInt())).thenReturn(info);

        subId = mInternetDetailsContentController.getActiveAutoSwitchNonDdsSubId();
        assertThat(subId).isEqualTo(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
    }

    @Test
    public void getActiveAutoSwitchNonDdsSubId_flagOff() {
        // active on non-DDS
        SubscriptionInfo info = mock(SubscriptionInfo.class);
        doReturn(SUB_ID2).when(info).getSubscriptionId();
        when(mSubscriptionManager.getActiveSubscriptionInfo(anyInt())).thenReturn(info);

        int subId = mInternetDetailsContentController.getActiveAutoSwitchNonDdsSubId();
        assertThat(subId).isEqualTo(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
    }

    @Test
    public void getActiveAutoSwitchNonDdsSubId_registerCallbackForExistedSubId_notRegister() {
        mFlags.set(Flags.QS_SECONDARY_DATA_SUB_INFO, true);

        // Adds non DDS subId
        SubscriptionInfo info = mock(SubscriptionInfo.class);
        doReturn(SUB_ID2).when(info).getSubscriptionId();
        doReturn(false).when(info).isOpportunistic();
        when(mSubscriptionManager.getActiveSubscriptionInfo(anyInt())).thenReturn(info);

        mInternetDetailsContentController.getActiveAutoSwitchNonDdsSubId();

        // 1st time is onStart(), 2nd time is getActiveAutoSwitchNonDdsSubId()
        verify(mTelephonyManager, times(2)).registerTelephonyCallback(any(), any());
        assertThat(mInternetDetailsContentController.mSubIdTelephonyCallbackMap.size()).isEqualTo(
                2);

        // Adds non DDS subId again
        doReturn(SUB_ID2).when(info).getSubscriptionId();
        doReturn(false).when(info).isOpportunistic();
        when(mSubscriptionManager.getActiveSubscriptionInfo(anyInt())).thenReturn(info);

        mInternetDetailsContentController.getActiveAutoSwitchNonDdsSubId();

        // Does not add due to cached subInfo in mSubIdTelephonyCallbackMap.
        verify(mTelephonyManager, times(2)).registerTelephonyCallback(any(), any());
        assertThat(mInternetDetailsContentController.mSubIdTelephonyCallbackMap.size()).isEqualTo(
                2);
    }

    @Test
    public void getMobileNetworkSummary() {
        mFlags.set(Flags.QS_SECONDARY_DATA_SUB_INFO, true);
        Resources res1 = mock(Resources.class);
        doReturn("EDGE").when(res1).getString(anyInt());
        Resources res2 = mock(Resources.class);
        doReturn("LTE").when(res2).getString(anyInt());
        when(SubscriptionManager.getResourcesForSubId(any(), eq(SUB_ID))).thenReturn(res1);
        when(SubscriptionManager.getResourcesForSubId(any(), eq(SUB_ID2))).thenReturn(res2);

        InternetDetailsContentController spyController = spy(mInternetDetailsContentController);
        Map<Integer, TelephonyDisplayInfo> mSubIdTelephonyDisplayInfoMap =
                spyController.mSubIdTelephonyDisplayInfoMap;
        TelephonyDisplayInfo info1 = new TelephonyDisplayInfo(TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE);
        TelephonyDisplayInfo info2 = new TelephonyDisplayInfo(TelephonyManager.NETWORK_TYPE_LTE,
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE);

        mSubIdTelephonyDisplayInfoMap.put(SUB_ID, info1);
        mSubIdTelephonyDisplayInfoMap.put(SUB_ID2, info2);

        doReturn(SUB_ID2).when(spyController).getActiveAutoSwitchNonDdsSubId();
        doReturn(true).when(spyController).isMobileDataEnabled();
        doReturn(true).when(spyController).activeNetworkIsCellular();
        String dds = spyController.getMobileNetworkSummary(SUB_ID);
        String nonDds = spyController.getMobileNetworkSummary(SUB_ID2);

        String ddsNetworkType = dds.split("/")[1];
        String nonDdsNetworkType = nonDds.split("/")[1];
        assertThat(dds).contains(mContext.getString(R.string.mobile_data_poor_connection));
        assertThat(ddsNetworkType).isNotEqualTo(nonDdsNetworkType);
    }

    @Test
    public void getMobileNetworkSummary_flagOff() {
        InternetDetailsContentController spyController = spy(mInternetDetailsContentController);
        doReturn(true).when(spyController).isMobileDataEnabled();
        doReturn(true).when(spyController).activeNetworkIsCellular();
        String dds = spyController.getMobileNetworkSummary(SUB_ID);

        assertThat(dds).contains(mContext.getString(R.string.mobile_data_connection_active));
    }

    @Test
    public void launchMobileNetworkSettings_validSubId() {
        mFlags.set(Flags.QS_SECONDARY_DATA_SUB_INFO, true);
        InternetDetailsContentController spyController = spy(mInternetDetailsContentController);
        doReturn(SUB_ID2).when(spyController).getActiveAutoSwitchNonDdsSubId();
        spyController.launchMobileNetworkSettings(mDialogLaunchView);

        verify(mActivityStarter).postStartActivityDismissingKeyguard(any(Intent.class), anyInt(),
                any());
    }

    @Test
    public void launchMobileNetworkSettings_invalidSubId() {
        mFlags.set(Flags.QS_SECONDARY_DATA_SUB_INFO, true);
        InternetDetailsContentController spyController = spy(mInternetDetailsContentController);
        doReturn(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                .when(spyController).getActiveAutoSwitchNonDdsSubId();
        spyController.launchMobileNetworkSettings(mDialogLaunchView);

        verify(mActivityStarter, never())
                .postStartActivityDismissingKeyguard(any(Intent.class), anyInt());
    }

    @Test
    public void setAutoDataSwitchMobileDataPolicy() {
        mFlags.set(Flags.QS_SECONDARY_DATA_SUB_INFO, true);
        mInternetDetailsContentController.setAutoDataSwitchMobileDataPolicy(SUB_ID, true);

        verify(mTelephonyManager).setMobileDataPolicyEnabled(eq(
                TelephonyManager.MOBILE_DATA_POLICY_AUTO_DATA_SWITCH), eq(true));
    }

    @Test
    public void getSignalStrengthDrawableWithLevel_carrierNetworkIsNotActive_useMobileDataLevel() {
        // Fake mobile data level as SIGNAL_STRENGTH_POOR(1)
        when(mSignalStrength.getLevel()).thenReturn(SIGNAL_STRENGTH_POOR);
        // Fake carrier network level as WIFI_LEVEL_MAX(4)
        when(mInternetDetailsContentController.getCarrierNetworkLevel()).thenReturn(WIFI_LEVEL_MAX);

        InternetDetailsContentController spyController = spy(mInternetDetailsContentController);
        spyController.getSignalStrengthDrawableWithLevel(false /* isCarrierNetworkActive */, 0);

        verify(spyController).getSignalStrengthIcon(eq(0), any(), eq(SIGNAL_STRENGTH_POOR),
                eq(NUM_SIGNAL_STRENGTH_BINS), anyInt(), anyBoolean());
    }

    @Test
    public void getSignalStrengthDrawableWithLevel_carrierNetworkIsActive_useCarrierNetworkLevel() {
        // Fake mobile data level as SIGNAL_STRENGTH_POOR(1)
        when(mSignalStrength.getLevel()).thenReturn(SIGNAL_STRENGTH_POOR);
        // Fake carrier network level as WIFI_LEVEL_MAX(4)
        when(mInternetDetailsContentController.getCarrierNetworkLevel()).thenReturn(WIFI_LEVEL_MAX);

        InternetDetailsContentController spyController = spy(mInternetDetailsContentController);
        spyController.getSignalStrengthDrawableWithLevel(true /* isCarrierNetworkActive */, 0);

        verify(spyController).getSignalStrengthIcon(eq(0), any(), eq(WIFI_LEVEL_MAX),
                eq(WIFI_LEVEL_MAX + 1), anyInt(), anyBoolean());
    }

    @Test
    public void getCarrierNetworkLevel_mergedCarrierEntryIsNull_returnMinLevel() {
        when(mAccessPointController.getMergedCarrierEntry()).thenReturn(null);

        assertThat(mInternetDetailsContentController.getCarrierNetworkLevel()).isEqualTo(
                WIFI_LEVEL_MIN);
    }

    @Test
    public void getCarrierNetworkLevel_getUnreachableLevel_returnMinLevel() {
        when(mMergedCarrierEntry.getLevel()).thenReturn(WIFI_LEVEL_UNREACHABLE);

        assertThat(mInternetDetailsContentController.getCarrierNetworkLevel()).isEqualTo(
                WIFI_LEVEL_MIN);
    }

    @Test
    public void getCarrierNetworkLevel_getAvailableLevel_returnSameLevel() {
        for (int level = WIFI_LEVEL_MIN; level <= WIFI_LEVEL_MAX; level++) {
            when(mMergedCarrierEntry.getLevel()).thenReturn(level);

            assertThat(mInternetDetailsContentController.getCarrierNetworkLevel()).isEqualTo(level);
        }
    }

    @Test
    public void getMobileNetworkSummary_withCarrierNetworkChange() {
        Resources res = mock(Resources.class);
        doReturn("Carrier network changing").when(res).getString(anyInt());
        when(SubscriptionManager.getResourcesForSubId(any(), eq(SUB_ID))).thenReturn(res);
        InternetDetailsContentController spyController = spy(mInternetDetailsContentController);
        Map<Integer, TelephonyDisplayInfo> mSubIdTelephonyDisplayInfoMap =
                spyController.mSubIdTelephonyDisplayInfoMap;
        TelephonyDisplayInfo info = new TelephonyDisplayInfo(TelephonyManager.NETWORK_TYPE_LTE,
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE);

        mSubIdTelephonyDisplayInfoMap.put(SUB_ID, info);
        doReturn(true).when(spyController).isMobileDataEnabled();
        doReturn(true).when(spyController).activeNetworkIsCellular();
        spyController.mCarrierNetworkChangeMode = true;
        String dds = spyController.getMobileNetworkSummary(SUB_ID);

        assertThat(dds).contains(mContext.getString(com.android.settingslib.R.string.carrier_network_change_mode));
    }

    @Test
    public void getConfiguratorQrCodeGeneratorIntentOrNull_wifiNotShareable_returnNull() {
        mFlags.set(Flags.SHARE_WIFI_QS_BUTTON, true);
        when(mConnectedEntry.canShare()).thenReturn(false);
        assertThat(mInternetDetailsContentController.getConfiguratorQrCodeGeneratorIntentOrNull(
                mConnectedEntry)).isNull();
    }
    @Test
    public void getConfiguratorQrCodeGeneratorIntentOrNull_flagOff_returnNull() {
        mFlags.set(Flags.SHARE_WIFI_QS_BUTTON, false);
        when(mConnectedEntry.canShare()).thenReturn(true);
        assertThat(mInternetDetailsContentController.getConfiguratorQrCodeGeneratorIntentOrNull(
                mConnectedEntry)).isNull();
    }

    @Test
    public void getConfiguratorQrCodeGeneratorIntentOrNull_configurationNull_returnNull() {
        mFlags.set(Flags.SHARE_WIFI_QS_BUTTON, true);
        when(mConnectedEntry.canShare()).thenReturn(true);
        when(mConnectedEntry.getWifiConfiguration()).thenReturn(null);
        assertThat(mInternetDetailsContentController.getConfiguratorQrCodeGeneratorIntentOrNull(
                mConnectedEntry)).isNull();
    }

    @Test
    public void getConfiguratorQrCodeGeneratorIntentOrNull_wifiShareable() {
        mFlags.set(Flags.SHARE_WIFI_QS_BUTTON, true);
        when(mConnectedEntry.canShare()).thenReturn(true);
        when(mConnectedEntry.getWifiConfiguration()).thenReturn(mWifiConfiguration);
        assertThat(mInternetDetailsContentController.getConfiguratorQrCodeGeneratorIntentOrNull(
                mConnectedEntry)).isNotNull();
    }

    @Test
    public void onStop_cleanUp() {
        doReturn(SUB_ID).when(mTelephonyManager).getSubscriptionId();
        assertThat(
                mInternetDetailsContentController.mSubIdTelephonyManagerMap.get(SUB_ID)).isEqualTo(
                mTelephonyManager);
        assertThat(mInternetDetailsContentController.mSubIdTelephonyCallbackMap.get(
                SUB_ID)).isNotNull();
        assertThat(mInternetDetailsContentController.mCallback).isNotNull();

        mInternetDetailsContentController.onStop();

        verify(mTelephonyManager).unregisterTelephonyCallback(any(TelephonyCallback.class));
        assertThat(
                mInternetDetailsContentController.mSubIdTelephonyDisplayInfoMap.isEmpty()).isTrue();
        assertThat(mInternetDetailsContentController.mSubIdTelephonyManagerMap.isEmpty()).isTrue();
        assertThat(mInternetDetailsContentController.mSubIdTelephonyCallbackMap.isEmpty()).isTrue();
        verify(mSubscriptionManager).removeOnSubscriptionsChangedListener(
                mInternetDetailsContentController
                        .mOnSubscriptionsChangedListener);
        verify(mAccessPointController).removeAccessPointCallback(mInternetDetailsContentController);
        verify(mConnectivityManager).unregisterNetworkCallback(
                any(ConnectivityManager.NetworkCallback.class));
        assertThat(mInternetDetailsContentController.mCallback).isNull();
    }

    @Test
    public void hasActiveSubIdOnDds_noDds_returnFalse() {
        when(SubscriptionManager.getDefaultDataSubscriptionId())
                .thenReturn(SubscriptionManager.INVALID_SUBSCRIPTION_ID);

        mInternetDetailsContentController.mOnSubscriptionsChangedListener.onSubscriptionsChanged();

        assertThat(mInternetDetailsContentController.hasActiveSubIdOnDds()).isFalse();
    }

    @Test
    public void hasActiveSubIdOnDds_activeDds_returnTrue() {
        mInternetDetailsContentController.mOnSubscriptionsChangedListener.onSubscriptionsChanged();

        assertThat(mInternetDetailsContentController.hasActiveSubIdOnDds()).isTrue();
    }

    @Test
    public void hasActiveSubIdOnDds_activeDdsAndHasProvisioning_returnFalse() {
        when(SubscriptionManager.getDefaultDataSubscriptionId())
                .thenReturn(SUB_ID);
        SubscriptionInfo info = mock(SubscriptionInfo.class);
        when(info.isEmbedded()).thenReturn(true);
        when(info.getProfileClass()).thenReturn(PROFILE_CLASS_PROVISIONING);
        when(mSubscriptionManager.getActiveSubscriptionInfo(SUB_ID)).thenReturn(info);

        mInternetDetailsContentController.mOnSubscriptionsChangedListener.onSubscriptionsChanged();

        assertThat(mInternetDetailsContentController.hasActiveSubIdOnDds()).isFalse();
    }

    @Test
    public void hasActiveSubIdOnDds_activeDdsAndIsOnlyNonTerrestrialNetwork_returnFalse() {
        when(SubscriptionManager.getDefaultDataSubscriptionId())
                .thenReturn(SUB_ID);
        SubscriptionInfo info = mock(SubscriptionInfo.class);
        when(info.isEmbedded()).thenReturn(true);
        when(info.isOnlyNonTerrestrialNetwork()).thenReturn(true);
        when(mSubscriptionManager.getActiveSubscriptionInfo(SUB_ID)).thenReturn(info);

        mInternetDetailsContentController.mOnSubscriptionsChangedListener.onSubscriptionsChanged();

        assertFalse(mInternetDetailsContentController.hasActiveSubIdOnDds());
    }

    @Test
    public void hasActiveSubIdOnDds_activeDdsAndIsNotOnlyNonTerrestrialNetwork_returnTrue() {
        when(SubscriptionManager.getDefaultDataSubscriptionId())
                .thenReturn(SUB_ID);
        SubscriptionInfo info = mock(SubscriptionInfo.class);
        when(info.isEmbedded()).thenReturn(true);
        when(info.isOnlyNonTerrestrialNetwork()).thenReturn(false);
        when(mSubscriptionManager.getActiveSubscriptionInfo(SUB_ID)).thenReturn(info);

        mInternetDetailsContentController.mOnSubscriptionsChangedListener.onSubscriptionsChanged();

        assertTrue(mInternetDetailsContentController.hasActiveSubIdOnDds());
    }

    private String getResourcesString(String name) {
        return mContext.getResources().getString(getResourcesId(name));
    }

    private int getResourcesId(String name) {
        return mContext.getResources().getIdentifier(name, "string",
                mContext.getPackageName());
    }

    private void fakeAirplaneModeEnabled(boolean enabled) {
        when(mGlobalSettings.getInt(eq(AIRPLANE_MODE_ON), anyInt())).thenReturn(enabled ? 1 : 0);
    }
}
