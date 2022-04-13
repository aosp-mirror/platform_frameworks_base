package com.android.systemui.qs.tiles.dialog;

import static android.provider.Settings.Global.AIRPLANE_MODE_ON;

import static com.android.systemui.qs.tiles.dialog.InternetDialogController.TOAST_PARAMS_HORIZONTAL_WEIGHT;
import static com.android.systemui.qs.tiles.dialog.InternetDialogController.TOAST_PARAMS_VERTICAL_WEIGHT;

import static com.google.common.truth.Truth.assertThat;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.animation.Animator;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableResources;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.settingslib.wifi.WifiUtils;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.connectivity.AccessPointController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.toast.SystemUIToast;
import com.android.systemui.toast.ToastFactory;
import com.android.systemui.util.CarrierConfigTracker;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.wifitrackerlib.MergedCarrierEntry;
import com.android.wifitrackerlib.WifiEntry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class InternetDialogControllerTest extends SysuiTestCase {

    private static final int SUB_ID = 1;

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
    InternetDialogController.InternetDialogCallback mInternetDialogCallback;
    @Mock
    private WindowManager mWindowManager;
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
    private DialogLaunchAnimator mDialogLaunchAnimator;
    @Mock
    private View mDialogLaunchView;

    private TestableResources mTestableResources;
    private InternetDialogController mInternetDialogController;
    private FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());
    private List<WifiEntry> mAccessPoints = new ArrayList<>();
    private List<WifiEntry> mWifiEntries = new ArrayList<>();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTestableResources = mContext.getOrCreateTestableResources();
        doReturn(mTelephonyManager).when(mTelephonyManager).createForSubscriptionId(anyInt());
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
        when(mToastFactory.createToast(any(), anyString(), anyString(), anyInt(), anyInt()))
            .thenReturn(mSystemUIToast);
        when(mSystemUIToast.getView()).thenReturn(mToastView);
        when(mSystemUIToast.getGravity()).thenReturn(GRAVITY_FLAGS);
        when(mSystemUIToast.getInAnimation()).thenReturn(mAnimator);

        mInternetDialogController = new InternetDialogController(mContext,
                mock(UiEventLogger.class), mock(ActivityStarter.class), mAccessPointController,
                mSubscriptionManager, mTelephonyManager, mWifiManager,
                mConnectivityManager, mHandler, mExecutor, mBroadcastDispatcher,
                mock(KeyguardUpdateMonitor.class), mGlobalSettings, mKeyguardStateController,
                mWindowManager, mToastFactory, mWorkerHandler, mCarrierConfigTracker,
                mLocationController, mDialogLaunchAnimator);
        mSubscriptionManager.addOnSubscriptionsChangedListener(mExecutor,
                mInternetDialogController.mOnSubscriptionsChangedListener);
        mInternetDialogController.onStart(mInternetDialogCallback, true);
        mInternetDialogController.onAccessPointsChanged(mAccessPoints);
        mInternetDialogController.mActivityStarter = mActivityStarter;
        mInternetDialogController.mWifiIconInjector = mWifiIconInjector;
    }

    @Test
    public void connectCarrierNetwork_mergedCarrierEntryCanConnect_connectAndCreateSysUiToast() {
        when(mTelephonyManager.isDataEnabled()).thenReturn(true);
        when(mKeyguardStateController.isUnlocked()).thenReturn(true);
        when(mConnectivityManager.getActiveNetwork()).thenReturn(mNetwork);
        when(mConnectivityManager.getNetworkCapabilities(mNetwork))
                .thenReturn(mNetworkCapabilities);
        when(mNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                .thenReturn(false);

        when(mMergedCarrierEntry.canConnect()).thenReturn(true);
        mTestableResources.addOverride(R.string.wifi_wont_autoconnect_for_now,
            TOAST_MESSAGE_STRING);

        mInternetDialogController.connectCarrierNetwork();

        verify(mMergedCarrierEntry).connect(null /* callback */, false /* showToast */);
        verify(mToastFactory).createToast(any(), eq(TOAST_MESSAGE_STRING), anyString(), anyInt(),
            anyInt());
    }

    @Test
    public void connectCarrierNetwork_mergedCarrierEntryCanConnect_doNothingWhenSettingsOff() {
        when(mTelephonyManager.isDataEnabled()).thenReturn(false);

        mTestableResources.addOverride(R.string.wifi_wont_autoconnect_for_now,
            TOAST_MESSAGE_STRING);
        mInternetDialogController.connectCarrierNetwork();

        verify(mMergedCarrierEntry, never()).connect(null /* callback */, false /* showToast */);
        verify(mToastFactory, never()).createToast(any(), anyString(), anyString(), anyInt(),
            anyInt());
    }

    @Test
    public void connectCarrierNetwork_mergedCarrierEntryCanConnect_doNothingWhenKeyguardLocked() {
        when(mTelephonyManager.isDataEnabled()).thenReturn(true);
        when(mKeyguardStateController.isUnlocked()).thenReturn(false);

        mTestableResources.addOverride(R.string.wifi_wont_autoconnect_for_now,
            TOAST_MESSAGE_STRING);
        mInternetDialogController.connectCarrierNetwork();

        verify(mMergedCarrierEntry, never()).connect(null /* callback */, false /* showToast */);
        verify(mToastFactory, never()).createToast(any(), anyString(), anyString(), anyInt(),
            anyInt());
    }

    @Test
    public void connectCarrierNetwork_mergedCarrierEntryCanConnect_doNothingWhenMobileIsPrimary() {
        when(mTelephonyManager.isDataEnabled()).thenReturn(true);
        when(mKeyguardStateController.isUnlocked()).thenReturn(true);
        when(mConnectivityManager.getActiveNetwork()).thenReturn(mNetwork);
        when(mConnectivityManager.getNetworkCapabilities(mNetwork))
                .thenReturn(mNetworkCapabilities);
        when(mNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                .thenReturn(true);

        mTestableResources.addOverride(R.string.wifi_wont_autoconnect_for_now,
            TOAST_MESSAGE_STRING);
        mInternetDialogController.connectCarrierNetwork();

        verify(mMergedCarrierEntry, never()).connect(null /* callback */, false /* showToast */);
        verify(mToastFactory, never()).createToast(any(), anyString(), anyString(), anyInt(),
            anyInt());
    }

    @Test
    public void makeOverlayToast_withGravityFlags_addViewWithLayoutParams() {
        mTestableResources.addOverride(TOAST_MESSAGE_STRING_ID, TOAST_MESSAGE_STRING);

        mInternetDialogController.makeOverlayToast(TOAST_MESSAGE_STRING_ID);

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

        mInternetDialogController.makeOverlayToast(TOAST_MESSAGE_STRING_ID);

        verify(mAnimator).start();
    }

    @Test
    public void getDialogTitleText_withAirplaneModeOn_returnAirplaneMode() {
        fakeAirplaneModeEnabled(true);

        assertTrue(TextUtils.equals(mInternetDialogController.getDialogTitleText(),
                getResourcesString("airplane_mode")));
    }

    @Test
    public void getDialogTitleText_withAirplaneModeOff_returnInternet() {
        fakeAirplaneModeEnabled(false);

        assertTrue(TextUtils.equals(mInternetDialogController.getDialogTitleText(),
                getResourcesString("quick_settings_internet_label")));
    }

    @Test
    public void getSubtitleText_withApmOnAndWifiOff_returnWifiIsOff() {
        fakeAirplaneModeEnabled(true);
        when(mWifiManager.isWifiEnabled()).thenReturn(false);

        assertThat(mInternetDialogController.getSubtitleText(false))
                .isEqualTo(getResourcesString("wifi_is_off"));

        // if the Wi-Fi disallow config, then don't return Wi-Fi related string.
        mInternetDialogController.mCanConfigWifi = false;

        assertThat(mInternetDialogController.getSubtitleText(false))
                .isNotEqualTo(getResourcesString("wifi_is_off"));
    }

    @Test
    public void getSubtitleText_withWifiOff_returnWifiIsOff() {
        fakeAirplaneModeEnabled(false);
        when(mWifiManager.isWifiEnabled()).thenReturn(false);

        assertThat(mInternetDialogController.getSubtitleText(false))
                .isEqualTo(getResourcesString("wifi_is_off"));

        // if the Wi-Fi disallow config, then don't return Wi-Fi related string.
        mInternetDialogController.mCanConfigWifi = false;

        assertThat(mInternetDialogController.getSubtitleText(false))
                .isNotEqualTo(getResourcesString("wifi_is_off"));
    }

    @Test
    public void getSubtitleText_withNoWifiEntry_returnSearchWifi() {
        fakeAirplaneModeEnabled(false);
        when(mWifiManager.isWifiEnabled()).thenReturn(true);
        mInternetDialogController.onAccessPointsChanged(null /* accessPoints */);

        assertThat(mInternetDialogController.getSubtitleText(true))
                .isEqualTo(getResourcesString("wifi_empty_list_wifi_on"));

        // if the Wi-Fi disallow config, then don't return Wi-Fi related string.
        mInternetDialogController.mCanConfigWifi = false;

        assertThat(mInternetDialogController.getSubtitleText(true))
                .isNotEqualTo(getResourcesString("wifi_empty_list_wifi_on"));
    }

    @Test
    public void getSubtitleText_withWifiEntry_returnTapToConnect() {
        // The preconditions WiFi Entries is already in setUp()
        fakeAirplaneModeEnabled(false);
        when(mWifiManager.isWifiEnabled()).thenReturn(true);

        assertThat(mInternetDialogController.getSubtitleText(false))
                .isEqualTo(getResourcesString("tap_a_network_to_connect"));

        // if the Wi-Fi disallow config, then don't return Wi-Fi related string.
        mInternetDialogController.mCanConfigWifi = false;

        assertThat(mInternetDialogController.getSubtitleText(false))
                .isNotEqualTo(getResourcesString("tap_a_network_to_connect"));
    }

    @Test
    public void getSubtitleText_deviceLockedWithWifiOn_returnUnlockToViewNetworks() {
        fakeAirplaneModeEnabled(false);
        when(mWifiManager.isWifiEnabled()).thenReturn(true);
        when(mKeyguardStateController.isUnlocked()).thenReturn(false);

        assertTrue(TextUtils.equals(mInternetDialogController.getSubtitleText(false),
                getResourcesString("unlock_to_view_networks")));
    }

    @Test
    public void getSubtitleText_withNoService_returnNoNetworksAvailable() {
        fakeAirplaneModeEnabled(false);
        when(mWifiManager.isWifiEnabled()).thenReturn(true);
        mInternetDialogController.onAccessPointsChanged(null /* accessPoints */);

        doReturn(ServiceState.STATE_OUT_OF_SERVICE).when(mServiceState).getState();
        doReturn(mServiceState).when(mTelephonyManager).getServiceState();
        doReturn(TelephonyManager.DATA_DISCONNECTED).when(mTelephonyManager).getDataState();

        assertTrue(TextUtils.equals(mInternetDialogController.getSubtitleText(false),
                getResourcesString("all_network_unavailable")));
    }

    @Test
    public void getSubtitleText_withMobileDataDisabled_returnNoOtherAvailable() {
        fakeAirplaneModeEnabled(false);
        when(mWifiManager.isWifiEnabled()).thenReturn(true);
        mInternetDialogController.onAccessPointsChanged(null /* accessPoints */);

        doReturn(ServiceState.STATE_IN_SERVICE).when(mServiceState).getState();
        doReturn(mServiceState).when(mTelephonyManager).getServiceState();

        when(mTelephonyManager.isDataEnabled()).thenReturn(false);

        assertThat(mInternetDialogController.getSubtitleText(false))
                .isEqualTo(getResourcesString("non_carrier_network_unavailable"));

        // if the Wi-Fi disallow config, then don't return Wi-Fi related string.
        mInternetDialogController.mCanConfigWifi = false;

        assertThat(mInternetDialogController.getSubtitleText(false))
                .isNotEqualTo(getResourcesString("non_carrier_network_unavailable"));
    }

    @Test
    public void getSubtitleText_withCarrierNetworkActiveOnly_returnNoOtherAvailable() {
        fakeAirplaneModeEnabled(false);
        when(mWifiManager.isWifiEnabled()).thenReturn(true);
        mInternetDialogController.onAccessPointsChanged(null /* accessPoints */);
        when(mMergedCarrierEntry.isDefaultNetwork()).thenReturn(true);

        assertThat(mInternetDialogController.getSubtitleText(false))
                .isEqualTo(getResourcesString("non_carrier_network_unavailable"));
    }

    @Test
    public void getWifiDetailsSettingsIntent_withNoKey_returnNull() {
        assertThat(mInternetDialogController.getWifiDetailsSettingsIntent(null)).isNull();
    }

    @Test
    public void getWifiDetailsSettingsIntent_withKey_returnIntent() {
        assertThat(mInternetDialogController.getWifiDetailsSettingsIntent("test_key")).isNotNull();
    }

    @Test
    public void getInternetWifiDrawable_withConnectedEntry_returnIntentIconWithCorrectColor() {
        final Drawable drawable = mock(Drawable.class);
        when(mWifiIconInjector.getIcon(anyBoolean(), anyInt())).thenReturn(drawable);

        mInternetDialogController.getInternetWifiDrawable(mConnectedEntry);

        verify(mWifiIconInjector).getIcon(eq(false), anyInt());
        verify(drawable).setTint(mContext.getColor(R.color.connected_network_primary_color));
    }

    @Test
    public void getInternetWifiDrawable_withWifiLevelUnreachable_returnNull() {
        when(mConnectedEntry.getLevel()).thenReturn(WifiEntry.WIFI_LEVEL_UNREACHABLE);

        Drawable drawable = mInternetDialogController.getInternetWifiDrawable(mConnectedEntry);

        assertThat(drawable).isNull();
    }

    @Test
    public void launchWifiDetailsSetting_withNoWifiEntryKey_doNothing() {
        mInternetDialogController.launchWifiDetailsSetting(null /* key */, mDialogLaunchView);

        verify(mActivityStarter, never())
                .postStartActivityDismissingKeyguard(any(Intent.class), anyInt());
    }

    @Test
    public void launchWifiDetailsSetting_withWifiEntryKey_startActivity() {
        mInternetDialogController.launchWifiDetailsSetting("wifi_entry_key", mDialogLaunchView);

        verify(mActivityStarter).postStartActivityDismissingKeyguard(any(Intent.class), anyInt(),
                any());
    }

    @Test
    public void isDeviceLocked_keyguardIsUnlocked_returnFalse() {
        when(mKeyguardStateController.isUnlocked()).thenReturn(true);

        assertThat(mInternetDialogController.isDeviceLocked()).isFalse();
    }

    @Test
    public void isDeviceLocked_keyguardIsLocked_returnTrue() {
        when(mKeyguardStateController.isUnlocked()).thenReturn(false);

        assertThat(mInternetDialogController.isDeviceLocked()).isTrue();
    }

    @Test
    public void onAccessPointsChanged_canNotConfigWifi_doNothing() {
        reset(mInternetDialogCallback);
        mInternetDialogController.mCanConfigWifi = false;

        mInternetDialogController.onAccessPointsChanged(null /* accessPoints */);

        verify(mInternetDialogCallback, never()).onAccessPointsChanged(any(), any(), anyBoolean());
    }

    @Test
    public void onAccessPointsChanged_nullAccessPoints_callbackBothNull() {
        reset(mInternetDialogCallback);

        mInternetDialogController.onAccessPointsChanged(null /* accessPoints */);

        verify(mInternetDialogCallback).onAccessPointsChanged(null /* wifiEntries */,
                null /* connectedEntry */, false /* hasMoreEntry */);
    }

    @Test
    public void onAccessPointsChanged_oneConnectedEntry_callbackConnectedEntryOnly() {
        reset(mInternetDialogCallback);
        mAccessPoints.clear();
        mAccessPoints.add(mConnectedEntry);

        mInternetDialogController.onAccessPointsChanged(mAccessPoints);

        mWifiEntries.clear();
        verify(mInternetDialogCallback).onAccessPointsChanged(mWifiEntries, mConnectedEntry,
                false /* hasMoreEntry */);
    }

    @Test
    public void onAccessPointsChanged_noConnectedEntryAndOneOther_callbackWifiEntriesOnly() {
        reset(mInternetDialogCallback);
        mAccessPoints.clear();
        mAccessPoints.add(mWifiEntry1);

        mInternetDialogController.onAccessPointsChanged(mAccessPoints);

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

        mInternetDialogController.onAccessPointsChanged(mAccessPoints);

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

        mInternetDialogController.onAccessPointsChanged(mAccessPoints);

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

        mInternetDialogController.onAccessPointsChanged(mAccessPoints);

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

        mInternetDialogController.onAccessPointsChanged(mAccessPoints);

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

        mInternetDialogController.onAccessPointsChanged(mAccessPoints);

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

        mInternetDialogController.onAccessPointsChanged(mAccessPoints);

        verify(mWifiEntry1).setListener(mInternetDialogController.mConnectedWifiInternetMonitor);
    }

    @Test
    public void onUpdated_connectedWifiHasInternetAccess_shouldScanWifiAccessPoints() {
        reset(mAccessPointController);
        when(mWifiEntry1.getConnectedState()).thenReturn(WifiEntry.CONNECTED_STATE_CONNECTED);
        when(mWifiEntry1.isDefaultNetwork()).thenReturn(true);
        when(mWifiEntry1.hasInternetAccess()).thenReturn(false);
        InternetDialogController.ConnectedWifiInternetMonitor mConnectedWifiInternetMonitor =
                mInternetDialogController.mConnectedWifiInternetMonitor;
        mConnectedWifiInternetMonitor.registerCallbackIfNeed(mWifiEntry1);

        // When the hasInternetAccess() changed to true, and call back the onUpdated() function.
        when(mWifiEntry1.hasInternetAccess()).thenReturn(true);
        mConnectedWifiInternetMonitor.onUpdated();

        verify(mAccessPointController).scanForAccessPoints();
    }

    @Test
    public void setMergedCarrierWifiEnabledIfNeed_carrierProvisionsEnabled_doNothing() {
        when(mCarrierConfigTracker.getCarrierProvisionsWifiMergedNetworksBool(SUB_ID))
                .thenReturn(true);

        mInternetDialogController.setMergedCarrierWifiEnabledIfNeed(SUB_ID, true);

        verify(mMergedCarrierEntry, never()).setEnabled(anyBoolean());
    }

    @Test
    public void setMergedCarrierWifiEnabledIfNeed_mergedCarrierEntryEmpty_doesntCrash() {
        when(mCarrierConfigTracker.getCarrierProvisionsWifiMergedNetworksBool(SUB_ID))
                .thenReturn(false);
        when(mAccessPointController.getMergedCarrierEntry()).thenReturn(null);

        mInternetDialogController.setMergedCarrierWifiEnabledIfNeed(SUB_ID, true);
    }

    @Test
    public void setMergedCarrierWifiEnabledIfNeed_neededSetMergedCarrierEntry_setTogether() {
        when(mCarrierConfigTracker.getCarrierProvisionsWifiMergedNetworksBool(SUB_ID))
                .thenReturn(false);

        mInternetDialogController.setMergedCarrierWifiEnabledIfNeed(SUB_ID, true);

        verify(mMergedCarrierEntry).setEnabled(true);

        mInternetDialogController.setMergedCarrierWifiEnabledIfNeed(SUB_ID, false);

        verify(mMergedCarrierEntry).setEnabled(false);
    }

    @Test
    public void isWifiScanEnabled_locationOff_returnFalse() {
        when(mLocationController.isLocationEnabled()).thenReturn(false);
        when(mWifiManager.isScanAlwaysAvailable()).thenReturn(false);

        assertThat(mInternetDialogController.isWifiScanEnabled()).isFalse();

        when(mWifiManager.isScanAlwaysAvailable()).thenReturn(true);

        assertThat(mInternetDialogController.isWifiScanEnabled()).isFalse();
    }

    @Test
    public void isWifiScanEnabled_locationOn_returnIsScanAlwaysAvailable() {
        when(mLocationController.isLocationEnabled()).thenReturn(true);
        when(mWifiManager.isScanAlwaysAvailable()).thenReturn(false);

        assertThat(mInternetDialogController.isWifiScanEnabled()).isFalse();

        when(mWifiManager.isScanAlwaysAvailable()).thenReturn(true);

        assertThat(mInternetDialogController.isWifiScanEnabled()).isTrue();
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
