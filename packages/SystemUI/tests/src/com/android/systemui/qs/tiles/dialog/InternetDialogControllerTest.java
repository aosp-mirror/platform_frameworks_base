package com.android.systemui.qs.tiles.dialog;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.settingslib.wifi.WifiUtils;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.AccessPointController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.wifitrackerlib.WifiEntry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class InternetDialogControllerTest extends SysuiTestCase {

    private static final int SUB_ID = 1;
    private static final String CONNECTED_TITLE = "Connected Wi-Fi Title";
    private static final String CONNECTED_SUMMARY = "Connected Wi-Fi Summary";

    @Mock
    private WifiManager mWifiManager;
    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private SubscriptionManager mSubscriptionManager;
    @Mock
    private Handler mHandler;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private GlobalSettings mGlobalSettings;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private NetworkController.AccessPointController mAccessPointController;
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
    private ServiceState mServiceState;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private WifiUtils.InternetIconInjector mWifiIconInjector;
    @Mock
    InternetDialogController.InternetDialogCallback mInternetDialogCallback;

    private MockInternetDialogController mInternetDialogController;
    private FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());
    private List<WifiEntry> mAccessPoints = new ArrayList<>();
    private List<WifiEntry> mWifiEntries = new ArrayList<>();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
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
        when(mSubscriptionManager.getActiveSubscriptionIdList()).thenReturn(new int[]{SUB_ID});

        mInternetDialogController = new MockInternetDialogController(mContext,
                mock(UiEventLogger.class), mock(ActivityStarter.class), mAccessPointController,
                mSubscriptionManager, mTelephonyManager, mWifiManager,
                mock(ConnectivityManager.class), mHandler, mExecutor, mBroadcastDispatcher,
                mock(KeyguardUpdateMonitor.class), mGlobalSettings, mKeyguardStateController);
        mSubscriptionManager.addOnSubscriptionsChangedListener(mExecutor,
                mInternetDialogController.mOnSubscriptionsChangedListener);
        mInternetDialogController.onStart(mInternetDialogCallback, true);
        mInternetDialogController.onAccessPointsChanged(mAccessPoints);
        mInternetDialogController.mActivityStarter = mActivityStarter;
        mInternetDialogController.mWifiIconInjector = mWifiIconInjector;
    }

    @Test
    public void getDialogTitleText_withAirplaneModeOn_returnAirplaneMode() {
        mInternetDialogController.setAirplaneModeEnabled(true);

        assertTrue(TextUtils.equals(mInternetDialogController.getDialogTitleText(),
                getResourcesString("airplane_mode")));
    }

    @Test
    public void getDialogTitleText_withAirplaneModeOff_returnInternet() {
        mInternetDialogController.setAirplaneModeEnabled(false);

        assertTrue(TextUtils.equals(mInternetDialogController.getDialogTitleText(),
                getResourcesString("quick_settings_internet_label")));
    }

    @Test
    public void getSubtitleText_withAirplaneModeOn_returnNull() {
        mInternetDialogController.setAirplaneModeEnabled(true);

        assertThat(mInternetDialogController.getSubtitleText(false)).isNull();
    }

    @Test
    public void getSubtitleText_withWifiOff_returnWifiIsOff() {
        mInternetDialogController.setAirplaneModeEnabled(false);
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
        mInternetDialogController.setAirplaneModeEnabled(false);
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
        mInternetDialogController.setAirplaneModeEnabled(false);
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
        mInternetDialogController.setAirplaneModeEnabled(false);
        when(mWifiManager.isWifiEnabled()).thenReturn(true);
        when(mKeyguardStateController.isUnlocked()).thenReturn(false);

        assertTrue(TextUtils.equals(mInternetDialogController.getSubtitleText(false),
                getResourcesString("unlock_to_view_networks")));
    }

    @Test
    public void getSubtitleText_withNoService_returnNoNetworksAvailable() {
        mInternetDialogController.setAirplaneModeEnabled(false);
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
        mInternetDialogController.setAirplaneModeEnabled(false);
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
    public void launchWifiNetworkDetailsSetting_withNoWifiEntryKey_doNothing() {
        mInternetDialogController.launchWifiNetworkDetailsSetting(null /* key */);

        verify(mActivityStarter, never())
                .postStartActivityDismissingKeyguard(any(Intent.class), anyInt());
    }

    @Test
    public void launchWifiNetworkDetailsSetting_withWifiEntryKey_startActivity() {
        mInternetDialogController.launchWifiNetworkDetailsSetting("wifi_entry_key");

        verify(mActivityStarter).postStartActivityDismissingKeyguard(any(Intent.class), anyInt());
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

        verify(mInternetDialogCallback, never()).onAccessPointsChanged(any(), any());
    }

    @Test
    public void onAccessPointsChanged_nullAccessPoints_callbackBothNull() {
        reset(mInternetDialogCallback);

        mInternetDialogController.onAccessPointsChanged(null /* accessPoints */);

        verify(mInternetDialogCallback)
                .onAccessPointsChanged(null /* wifiEntries */, null /* connectedEntry */);
    }

    @Test
    public void onAccessPointsChanged_oneConnectedEntry_callbackConnectedEntryOnly() {
        reset(mInternetDialogCallback);
        mInternetDialogController.setAirplaneModeEnabled(true);
        mAccessPoints.clear();
        mAccessPoints.add(mConnectedEntry);

        mInternetDialogController.onAccessPointsChanged(mAccessPoints);

        mWifiEntries.clear();
        verify(mInternetDialogCallback).onAccessPointsChanged(mWifiEntries, mConnectedEntry);
    }

    @Test
    public void onAccessPointsChanged_noConnectedEntryAndOneOther_callbackWifiEntriesOnly() {
        reset(mInternetDialogCallback);
        mInternetDialogController.setAirplaneModeEnabled(true);
        mAccessPoints.clear();
        mAccessPoints.add(mWifiEntry1);

        mInternetDialogController.onAccessPointsChanged(mAccessPoints);

        mWifiEntries.clear();
        mWifiEntries.add(mWifiEntry1);
        verify(mInternetDialogCallback)
                .onAccessPointsChanged(mWifiEntries, null /* connectedEntry */);
    }

    @Test
    public void onAccessPointsChanged_oneConnectedEntryAndOneOther_callbackCorrectly() {
        reset(mInternetDialogCallback);
        mInternetDialogController.setAirplaneModeEnabled(true);
        mAccessPoints.clear();
        mAccessPoints.add(mConnectedEntry);
        mAccessPoints.add(mWifiEntry1);

        mInternetDialogController.onAccessPointsChanged(mAccessPoints);

        mWifiEntries.clear();
        mWifiEntries.add(mWifiEntry1);
        verify(mInternetDialogCallback).onAccessPointsChanged(mWifiEntries, mConnectedEntry);
    }

    @Test
    public void onAccessPointsChanged_oneConnectedEntryAndTwoOthers_callbackCorrectly() {
        reset(mInternetDialogCallback);
        mInternetDialogController.setAirplaneModeEnabled(true);
        mAccessPoints.clear();
        mAccessPoints.add(mConnectedEntry);
        mAccessPoints.add(mWifiEntry1);
        mAccessPoints.add(mWifiEntry2);

        mInternetDialogController.onAccessPointsChanged(mAccessPoints);

        mWifiEntries.clear();
        mWifiEntries.add(mWifiEntry1);
        mWifiEntries.add(mWifiEntry2);
        verify(mInternetDialogCallback).onAccessPointsChanged(mWifiEntries, mConnectedEntry);
    }

    @Test
    public void onAccessPointsChanged_oneConnectedEntryAndThreeOthers_callbackCutMore() {
        reset(mInternetDialogCallback);
        mInternetDialogController.setAirplaneModeEnabled(true);
        mAccessPoints.clear();
        mAccessPoints.add(mConnectedEntry);
        mAccessPoints.add(mWifiEntry1);
        mAccessPoints.add(mWifiEntry2);
        mAccessPoints.add(mWifiEntry3);

        mInternetDialogController.onAccessPointsChanged(mAccessPoints);

        mWifiEntries.clear();
        mWifiEntries.add(mWifiEntry1);
        mWifiEntries.add(mWifiEntry2);
        mWifiEntries.add(mWifiEntry3);
        verify(mInternetDialogCallback).onAccessPointsChanged(mWifiEntries, mConnectedEntry);

        // Turn off airplane mode to has carrier network, then Wi-Fi entries will cut last one.
        reset(mInternetDialogCallback);
        mInternetDialogController.setAirplaneModeEnabled(false);

        mInternetDialogController.onAccessPointsChanged(mAccessPoints);

        mWifiEntries.remove(mWifiEntry3);
        verify(mInternetDialogCallback).onAccessPointsChanged(mWifiEntries, mConnectedEntry);
    }

    @Test
    public void onAccessPointsChanged_oneConnectedEntryAndFourOthers_callbackCutMore() {
        reset(mInternetDialogCallback);
        mInternetDialogController.setAirplaneModeEnabled(true);
        mAccessPoints.clear();
        mAccessPoints.add(mConnectedEntry);
        mAccessPoints.add(mWifiEntry1);
        mAccessPoints.add(mWifiEntry2);
        mAccessPoints.add(mWifiEntry3);
        mAccessPoints.add(mWifiEntry4);

        mInternetDialogController.onAccessPointsChanged(mAccessPoints);

        mWifiEntries.clear();
        mWifiEntries.add(mWifiEntry1);
        mWifiEntries.add(mWifiEntry2);
        mWifiEntries.add(mWifiEntry3);
        verify(mInternetDialogCallback).onAccessPointsChanged(mWifiEntries, mConnectedEntry);

        // Turn off airplane mode to has carrier network, then Wi-Fi entries will cut last one.
        reset(mInternetDialogCallback);
        mInternetDialogController.setAirplaneModeEnabled(false);

        mInternetDialogController.onAccessPointsChanged(mAccessPoints);

        mWifiEntries.remove(mWifiEntry3);
        verify(mInternetDialogCallback).onAccessPointsChanged(mWifiEntries, mConnectedEntry);
    }

    @Test
    public void onAccessPointsChanged_fourWifiEntries_callbackCutMore() {
        reset(mInternetDialogCallback);
        mInternetDialogController.setAirplaneModeEnabled(true);
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
        mWifiEntries.add(mWifiEntry4);
        verify(mInternetDialogCallback)
                .onAccessPointsChanged(mWifiEntries, null /* connectedEntry */);

        // If the Ethernet exists, then Wi-Fi entries will cut last one.
        reset(mInternetDialogCallback);
        mInternetDialogController.mHasEthernet = true;

        mInternetDialogController.onAccessPointsChanged(mAccessPoints);

        mWifiEntries.remove(mWifiEntry4);
        verify(mInternetDialogCallback)
                .onAccessPointsChanged(mWifiEntries, null /* connectedEntry */);

        // Turn off airplane mode to has carrier network, then Wi-Fi entries will cut last one.
        reset(mInternetDialogCallback);
        mInternetDialogController.setAirplaneModeEnabled(false);

        mInternetDialogController.onAccessPointsChanged(mAccessPoints);

        mWifiEntries.remove(mWifiEntry3);
        verify(mInternetDialogCallback)
                .onAccessPointsChanged(mWifiEntries, null /* connectedEntry */);
    }

    private String getResourcesString(String name) {
        return mContext.getResources().getString(getResourcesId(name));
    }

    private int getResourcesId(String name) {
        return mContext.getResources().getIdentifier(name, "string",
                mContext.getPackageName());
    }

    private class MockInternetDialogController extends InternetDialogController {

        private GlobalSettings mGlobalSettings;
        private boolean mIsAirplaneModeOn;

        MockInternetDialogController(Context context, UiEventLogger uiEventLogger,
                ActivityStarter starter, AccessPointController accessPointController,
                SubscriptionManager subscriptionManager, TelephonyManager telephonyManager,
                @Nullable WifiManager wifiManager, ConnectivityManager connectivityManager,
                @Main Handler handler, @Main Executor mainExecutor,
                BroadcastDispatcher broadcastDispatcher,
                KeyguardUpdateMonitor keyguardUpdateMonitor, GlobalSettings globalSettings,
                KeyguardStateController keyguardStateController) {
            super(context, uiEventLogger, starter, accessPointController, subscriptionManager,
                    telephonyManager, wifiManager, connectivityManager, handler, mainExecutor,
                    broadcastDispatcher, keyguardUpdateMonitor, globalSettings,
                    keyguardStateController);
            mGlobalSettings = globalSettings;
        }

        @Override
        boolean isAirplaneModeEnabled() {
            return mIsAirplaneModeOn;
        }

        public void setAirplaneModeEnabled(boolean enabled) {
            mIsAirplaneModeOn = enabled;
        }
    }
}
