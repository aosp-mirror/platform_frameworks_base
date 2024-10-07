package com.android.systemui.qs.tiles.dialog;

import static com.android.systemui.qs.tiles.dialog.InternetDialogController.MAX_WIFI_ENTRY_COUNT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.os.Handler;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.testing.TestableLooper;
import android.testing.UiThreadTest;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.wifi.WifiEnterpriseRestrictionUtils;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.wifitrackerlib.WifiEntry;

import kotlinx.coroutines.CoroutineScope;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@UiThreadTest
public class InternetDialogDelegateTest extends SysuiTestCase {

    private static final String MOBILE_NETWORK_TITLE = "Mobile Title";
    private static final String MOBILE_NETWORK_SUMMARY = "Mobile Summary";
    private static final String WIFI_TITLE = "Connected Wi-Fi Title";
    private static final String WIFI_SUMMARY = "Connected Wi-Fi Summary";

    @Mock
    private Handler mHandler;
    @Mock
    CoroutineScope mScope;
    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private WifiEntry mInternetWifiEntry;
    @Mock
    private List<WifiEntry> mWifiEntries;
    @Mock
    private InternetAdapter mInternetAdapter;
    @Mock
    private InternetDialogController mInternetDialogController;
    @Mock
    private KeyguardStateController mKeyguard;
    @Mock
    private DialogTransitionAnimator mDialogTransitionAnimator;
    @Mock
    private SystemUIDialog.Factory mSystemUIDialogFactory;
    @Mock
    private SystemUIDialog mSystemUIDialog;
    @Mock
    private Window mWindow;

    private FakeExecutor mBgExecutor = new FakeExecutor(new FakeSystemClock());
    private InternetDialogDelegate mInternetDialogDelegate;
    private View mDialogView;
    private View mSubTitle;
    private LinearLayout mEthernet;
    private LinearLayout mMobileDataLayout;
    private Switch mMobileToggleSwitch;
    private LinearLayout mWifiToggle;
    private Switch mWifiToggleSwitch;
    private TextView mWifiToggleSummary;
    private LinearLayout mConnectedWifi;
    private RecyclerView mWifiList;
    private LinearLayout mSeeAll;
    private LinearLayout mWifiScanNotify;
    private TextView mAirplaneModeSummaryText;

    private MockitoSession mMockitoSession;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        doReturn(mTelephonyManager).when(mTelephonyManager).createForSubscriptionId(anyInt());
        when(mInternetWifiEntry.getTitle()).thenReturn(WIFI_TITLE);
        when(mInternetWifiEntry.getSummary(false)).thenReturn(WIFI_SUMMARY);
        when(mInternetWifiEntry.isDefaultNetwork()).thenReturn(true);
        when(mInternetWifiEntry.hasInternetAccess()).thenReturn(true);
        when(mWifiEntries.size()).thenReturn(1);

        when(mInternetDialogController.getMobileNetworkTitle(anyInt()))
                .thenReturn(MOBILE_NETWORK_TITLE);
        when(mInternetDialogController.getMobileNetworkSummary(anyInt()))
                .thenReturn(MOBILE_NETWORK_SUMMARY);
        when(mInternetDialogController.isWifiEnabled()).thenReturn(true);
        when(mInternetDialogController.getActiveAutoSwitchNonDdsSubId()).thenReturn(
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        mMockitoSession = ExtendedMockito.mockitoSession()
                .spyStatic(WifiEnterpriseRestrictionUtils.class)
                .startMocking();
        when(WifiEnterpriseRestrictionUtils.isChangeWifiStateAllowed(mContext)).thenReturn(true);
        when(mSystemUIDialogFactory.create(any(SystemUIDialog.Delegate.class)))
                .thenReturn(mSystemUIDialog);
        when(mSystemUIDialog.getContext()).thenReturn(mContext);
        when(mSystemUIDialog.getWindow()).thenReturn(mWindow);
        createInternetDialog();
    }

    private void createInternetDialog() {
        mInternetDialogDelegate = new InternetDialogDelegate(
                mContext,
                mock(InternetDialogManager.class),
                mInternetDialogController,
                true,
                true,
                true,
                mScope,
                mock(UiEventLogger.class),
                mDialogTransitionAnimator,
                mHandler,
                mBgExecutor,
                mKeyguard,
                mSystemUIDialogFactory);
        mInternetDialogDelegate.createDialog();
        mInternetDialogDelegate.onCreate(mSystemUIDialog, null);
        mInternetDialogDelegate.mAdapter = mInternetAdapter;
        mInternetDialogDelegate.mConnectedWifiEntry = mInternetWifiEntry;
        mInternetDialogDelegate.mWifiEntriesCount = mWifiEntries.size();

        mDialogView = mInternetDialogDelegate.mDialogView;
        mSubTitle = mDialogView.requireViewById(R.id.internet_dialog_subtitle);
        mEthernet = mDialogView.requireViewById(R.id.ethernet_layout);
        mMobileDataLayout = mDialogView.requireViewById(R.id.mobile_network_layout);
        mMobileToggleSwitch = mDialogView.requireViewById(R.id.mobile_toggle);
        mWifiToggle = mDialogView.requireViewById(R.id.turn_on_wifi_layout);
        mWifiToggleSwitch = mDialogView.requireViewById(R.id.wifi_toggle);
        mWifiToggleSummary = mDialogView.requireViewById(R.id.wifi_toggle_summary);
        mConnectedWifi = mDialogView.requireViewById(R.id.wifi_connected_layout);
        mWifiList = mDialogView.requireViewById(R.id.wifi_list_layout);
        mSeeAll = mDialogView.requireViewById(R.id.see_all_layout);
        mWifiScanNotify = mDialogView.requireViewById(R.id.wifi_scan_notify_layout);
        mAirplaneModeSummaryText = mDialogView.requireViewById(R.id.airplane_mode_summary);
        mInternetDialogDelegate.onStart(mSystemUIDialog);
    }

    @After
    public void tearDown() {
        mInternetDialogDelegate.onStop(mSystemUIDialog);
        mInternetDialogDelegate.dismissDialog();
        mMockitoSession.finishMocking();
    }

    @Test
    public void createInternetDialog_setAccessibilityPaneTitleToQuickSettings() {
        assertThat(mDialogView.getAccessibilityPaneTitle())
                .isEqualTo(mContext.getText(R.string.accessibility_desc_quick_settings));
    }

    @Test
    public void hideWifiViews_WifiViewsGone() {
        mInternetDialogDelegate.hideWifiViews();

        assertThat(mInternetDialogDelegate.mIsProgressBarVisible).isFalse();
        assertThat(mWifiToggle.getVisibility()).isEqualTo(View.GONE);
        assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.GONE);
        assertThat(mWifiList.getVisibility()).isEqualTo(View.GONE);
        assertThat(mSeeAll.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_withApmOn_internetDialogSubTitleGone() {
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(true);
        mInternetDialogDelegate.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mSubTitle.getVisibility()).isEqualTo(View.VISIBLE);
                });
    }

    @Test
    public void updateDialog_withApmOff_internetDialogSubTitleVisible() {
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(false);
        mInternetDialogDelegate.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mSubTitle.getVisibility()).isEqualTo(View.VISIBLE);
                });
    }

    @Test
    public void updateDialog_apmOffAndHasEthernet_showEthernet() {
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(false);
        when(mInternetDialogController.hasEthernet()).thenReturn(true);
        mInternetDialogDelegate.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mEthernet.getVisibility()).isEqualTo(View.VISIBLE);
                });
    }

    @Test
    public void updateDialog_apmOffAndNoEthernet_hideEthernet() {
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(false);
        when(mInternetDialogController.hasEthernet()).thenReturn(false);
        mInternetDialogDelegate.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mEthernet.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_apmOnAndHasEthernet_showEthernet() {
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(true);
        when(mInternetDialogController.hasEthernet()).thenReturn(true);
        mInternetDialogDelegate.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mEthernet.getVisibility()).isEqualTo(View.VISIBLE);
                });
    }

    @Test
    public void updateDialog_apmOnAndNoEthernet_hideEthernet() {
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(true);
        when(mInternetDialogController.hasEthernet()).thenReturn(false);
        mInternetDialogDelegate.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mEthernet.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_apmOffAndNotCarrierNetwork_mobileDataLayoutGone() {
        // Mobile network should be gone if the list of active subscriptionId is null.
        when(mInternetDialogController.isCarrierNetworkActive()).thenReturn(false);
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(false);
        when(mInternetDialogController.hasActiveSubIdOnDds()).thenReturn(false);
        mInternetDialogDelegate.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mMobileDataLayout.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_apmOnWithCarrierNetworkAndWifiStatus_mobileDataLayoutVisible() {
        // Carrier network should be visible if airplane mode ON and Wi-Fi is ON.
        when(mInternetDialogController.isCarrierNetworkActive()).thenReturn(true);
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(true);
        when(mInternetDialogController.isWifiEnabled()).thenReturn(true);
        mInternetDialogDelegate.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mMobileDataLayout.getVisibility()).isEqualTo(View.VISIBLE);
                });
    }

    @Test
    public void updateDialog_apmOnWithCarrierNetworkAndWifiStatus_mobileDataLayoutGone() {
        // Carrier network should be gone if airplane mode ON and Wi-Fi is off.
        when(mInternetDialogController.isCarrierNetworkActive()).thenReturn(true);
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(true);
        when(mInternetDialogController.isWifiEnabled()).thenReturn(false);
        mInternetDialogDelegate.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mMobileDataLayout.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_apmOnAndNoCarrierNetwork_mobileDataLayoutGone() {
        when(mInternetDialogController.isCarrierNetworkActive()).thenReturn(false);
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(true);
        mInternetDialogDelegate.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mMobileDataLayout.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_apmOnAndWifiOnHasCarrierNetwork_showAirplaneSummary() {
        when(mInternetDialogController.isCarrierNetworkActive()).thenReturn(true);
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(true);
        mInternetDialogDelegate.mConnectedWifiEntry = null;
        doReturn(false).when(mInternetDialogController).activeNetworkIsCellular();
        mInternetDialogDelegate.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mMobileDataLayout.getVisibility()).isEqualTo(View.VISIBLE);
                    assertThat(mAirplaneModeSummaryText.getVisibility()).isEqualTo(View.VISIBLE);
                });
    }

    @Test
    public void updateDialog_apmOffAndWifiOnHasCarrierNetwork_notShowApmSummary() {
        when(mInternetDialogController.isCarrierNetworkActive()).thenReturn(true);
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(false);
        mInternetDialogDelegate.mConnectedWifiEntry = null;
        doReturn(false).when(mInternetDialogController).activeNetworkIsCellular();
        mInternetDialogDelegate.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mAirplaneModeSummaryText.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_apmOffAndHasCarrierNetwork_notShowApmSummary() {
        when(mInternetDialogController.isCarrierNetworkActive()).thenReturn(true);
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(false);
        mInternetDialogDelegate.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mAirplaneModeSummaryText.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_apmOnAndNoCarrierNetwork_notShowApmSummary() {
        when(mInternetDialogController.isCarrierNetworkActive()).thenReturn(false);
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(true);
        mInternetDialogDelegate.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mAirplaneModeSummaryText.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_mobileDataIsEnabled_checkMobileDataSwitch() {
        doReturn(true).when(mInternetDialogController).hasActiveSubIdOnDds();
        when(mInternetDialogController.isCarrierNetworkActive()).thenReturn(true);
        when(mInternetDialogController.isMobileDataEnabled()).thenReturn(true);
        mMobileToggleSwitch.setChecked(false);
        mInternetDialogDelegate.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mMobileToggleSwitch.isChecked()).isTrue();
                });
    }

    @Test
    public void updateDialog_mobileDataIsNotChanged_checkMobileDataSwitch() {
        doReturn(true).when(mInternetDialogController).hasActiveSubIdOnDds();
        when(mInternetDialogController.isCarrierNetworkActive()).thenReturn(true);
        when(mInternetDialogController.isMobileDataEnabled()).thenReturn(false);
        mMobileToggleSwitch.setChecked(false);
        mInternetDialogDelegate.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mMobileToggleSwitch.isChecked()).isFalse();
                });
    }

    @Test
    public void updateDialog_wifiOnAndHasInternetWifi_showConnectedWifi() {
        when(mInternetDialogController.getActiveAutoSwitchNonDdsSubId()).thenReturn(1);
        doReturn(true).when(mInternetDialogController).hasActiveSubIdOnDds();
        // The preconditions WiFi ON and Internet WiFi are already in setUp()
        doReturn(false).when(mInternetDialogController).activeNetworkIsCellular();

        mInternetDialogDelegate.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.VISIBLE);
                    LinearLayout secondaryLayout = mDialogView.requireViewById(
                            R.id.secondary_mobile_network_layout);
                    assertThat(secondaryLayout.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_wifiOnAndNoConnectedWifi_hideConnectedWifi() {
        // The precondition WiFi ON is already in setUp()
        mInternetDialogDelegate.mConnectedWifiEntry = null;
        doReturn(false).when(mInternetDialogController).activeNetworkIsCellular();
        mInternetDialogDelegate.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_wifiOnAndNoWifiEntry_showWifiListAndSeeAllArea() {
        // The precondition WiFi ON is already in setUp()
        mInternetDialogDelegate.mConnectedWifiEntry = null;
        mInternetDialogDelegate.mWifiEntriesCount = 0;
        mInternetDialogDelegate.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.GONE);
                    // Show a blank block to fix the dialog height even if there is no WiFi list
                    assertThat(mWifiList.getVisibility()).isEqualTo(View.VISIBLE);
                    verify(mInternetAdapter).setMaxEntriesCount(3);
                    assertThat(mSeeAll.getVisibility()).isEqualTo(View.INVISIBLE);
                });
    }

    @Test
    public void updateDialog_wifiOnAndOneWifiEntry_showWifiListAndSeeAllArea() {
        // The precondition WiFi ON is already in setUp()
        mInternetDialogDelegate.mConnectedWifiEntry = null;
        mInternetDialogDelegate.mWifiEntriesCount = 1;
        mInternetDialogDelegate.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.GONE);
                    // Show a blank block to fix the dialog height even if there is no WiFi list
                    assertThat(mWifiList.getVisibility()).isEqualTo(View.VISIBLE);
                    verify(mInternetAdapter).setMaxEntriesCount(3);
                    assertThat(mSeeAll.getVisibility()).isEqualTo(View.INVISIBLE);
                });
    }

    @Test
    public void updateDialog_wifiOnAndHasConnectedWifi_showAllWifiAndSeeAllArea() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        mInternetDialogDelegate.mWifiEntriesCount = 0;
        mInternetDialogDelegate.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.VISIBLE);
                    // Show a blank block to fix the dialog height even if there is no WiFi list
                    assertThat(mWifiList.getVisibility()).isEqualTo(View.VISIBLE);
                    verify(mInternetAdapter).setMaxEntriesCount(2);
                    assertThat(mSeeAll.getVisibility()).isEqualTo(View.INVISIBLE);
                });
    }

    @Test
    public void updateDialog_wifiOnAndHasMaxWifiList_showWifiListAndSeeAll() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        mInternetDialogDelegate.mConnectedWifiEntry = null;
        mInternetDialogDelegate.mWifiEntriesCount = MAX_WIFI_ENTRY_COUNT;
        mInternetDialogDelegate.mHasMoreWifiEntries = true;
        mInternetDialogDelegate.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.GONE);
                    assertThat(mWifiList.getVisibility()).isEqualTo(View.VISIBLE);
                    verify(mInternetAdapter).setMaxEntriesCount(3);
                    assertThat(mSeeAll.getVisibility()).isEqualTo(View.VISIBLE);
                });
    }

    @Test
    public void updateDialog_wifiOnAndHasBothWifiEntry_showBothWifiEntryAndSeeAll() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        mInternetDialogDelegate.mWifiEntriesCount = MAX_WIFI_ENTRY_COUNT - 1;
        mInternetDialogDelegate.mHasMoreWifiEntries = true;
        mInternetDialogDelegate.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.VISIBLE);
                    assertThat(mWifiList.getVisibility()).isEqualTo(View.VISIBLE);
                    verify(mInternetAdapter).setMaxEntriesCount(2);
                    assertThat(mSeeAll.getVisibility()).isEqualTo(View.VISIBLE);
                });
    }

    @Test
    public void updateDialog_deviceLockedAndNoConnectedWifi_showWifiToggle() {
        // The preconditions WiFi entries are already in setUp()
        when(mInternetDialogController.isDeviceLocked()).thenReturn(true);
        mInternetDialogDelegate.mConnectedWifiEntry = null;
        mInternetDialogDelegate.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    // Show WiFi Toggle without background
                    assertThat(mWifiToggle.getVisibility()).isEqualTo(View.VISIBLE);
                    assertThat(mWifiToggle.getBackground()).isNull();
                    // Hide Wi-Fi networks and See all
                    assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.GONE);
                    assertThat(mWifiList.getVisibility()).isEqualTo(View.GONE);
                    assertThat(mSeeAll.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_deviceLockedAndHasConnectedWifi_showWifiToggleWithBackground() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        when(mInternetDialogController.isDeviceLocked()).thenReturn(true);
        mInternetDialogDelegate.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    // Show WiFi Toggle with highlight background
                    assertThat(mWifiToggle.getVisibility()).isEqualTo(View.VISIBLE);
                    assertThat(mWifiToggle.getBackground()).isNotNull();
                    // Hide Wi-Fi networks and See all
                    assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.GONE);
                    assertThat(mWifiList.getVisibility()).isEqualTo(View.GONE);
                    assertThat(mSeeAll.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_disallowChangeWifiState_disableWifiSwitch() {
        mInternetDialogDelegate.dismissDialog();
        when(WifiEnterpriseRestrictionUtils.isChangeWifiStateAllowed(mContext)).thenReturn(false);
        createInternetDialog();
        mInternetDialogDelegate.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    // Disable Wi-Fi switch and show restriction message in summary.
                    assertThat(mWifiToggleSwitch.isEnabled()).isFalse();
                    assertThat(mWifiToggleSummary.getVisibility()).isEqualTo(View.VISIBLE);
                    assertThat(mWifiToggleSummary.getText().length()).isNotEqualTo(0);
                });
    }

    @Test
    public void updateDialog_allowChangeWifiState_enableWifiSwitch() {
        mInternetDialogDelegate.dismissDialog();
        when(WifiEnterpriseRestrictionUtils.isChangeWifiStateAllowed(mContext)).thenReturn(true);
        createInternetDialog();
        mInternetDialogDelegate.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    // Enable Wi-Fi switch and hide restriction message in summary.
                    assertThat(mWifiToggleSwitch.isEnabled()).isTrue();
                    assertThat(mWifiToggleSummary.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_showSecondaryDataSub() {
        when(mInternetDialogController.getActiveAutoSwitchNonDdsSubId()).thenReturn(1);
        doReturn(1).when(mInternetDialogController).getActiveAutoSwitchNonDdsSubId();
        doReturn(true).when(mInternetDialogController).hasActiveSubIdOnDds();
        doReturn(false).when(mInternetDialogController).isAirplaneModeEnabled();
        clearInvocations(mInternetDialogController);
        mInternetDialogDelegate.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    LinearLayout primaryLayout = mDialogView.requireViewById(
                            R.id.mobile_network_layout);
                    LinearLayout secondaryLayout = mDialogView.requireViewById(
                            R.id.secondary_mobile_network_layout);

                    verify(mInternetDialogController).getMobileNetworkSummary(1);
                    assertThat(primaryLayout.getBackground()).isNotEqualTo(
                            secondaryLayout.getBackground());
                });
    }

    @Test
    public void updateDialog_wifiOn_hideWifiScanNotify() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()

        mInternetDialogDelegate.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mWifiScanNotify.getVisibility()).isEqualTo(View.GONE);
                });

        assertThat(mWifiScanNotify.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_wifiOffAndWifiScanOff_hideWifiScanNotify() {
        when(mInternetDialogController.isWifiEnabled()).thenReturn(false);
        when(mInternetDialogController.isWifiScanEnabled()).thenReturn(false);
        mInternetDialogDelegate.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mWifiScanNotify.getVisibility()).isEqualTo(View.GONE);
                });

        assertThat(mWifiScanNotify.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_wifiOffAndWifiScanOnAndDeviceLocked_hideWifiScanNotify() {
        when(mInternetDialogController.isWifiEnabled()).thenReturn(false);
        when(mInternetDialogController.isWifiScanEnabled()).thenReturn(true);
        when(mInternetDialogController.isDeviceLocked()).thenReturn(true);
        mInternetDialogDelegate.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mWifiScanNotify.getVisibility()).isEqualTo(View.GONE);
                });

        assertThat(mWifiScanNotify.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_wifiOffAndWifiScanOnAndDeviceUnlocked_showWifiScanNotify() {
        when(mInternetDialogController.isWifiEnabled()).thenReturn(false);
        when(mInternetDialogController.isWifiScanEnabled()).thenReturn(true);
        when(mInternetDialogController.isDeviceLocked()).thenReturn(false);
        mInternetDialogDelegate.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mWifiScanNotify.getVisibility()).isEqualTo(View.VISIBLE);
                    TextView wifiScanNotifyText = mDialogView.requireViewById(
                            R.id.wifi_scan_notify_text);
                    assertThat(wifiScanNotifyText.getText().length()).isNotEqualTo(0);
                    assertThat(wifiScanNotifyText.getMovementMethod()).isNotNull();
                });
    }

    @Test
    public void updateDialog_wifiIsDisabled_uncheckWifiSwitch() {
        when(mInternetDialogController.isWifiEnabled()).thenReturn(false);
        mWifiToggleSwitch.setChecked(true);
        mInternetDialogDelegate.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mWifiToggleSwitch.isChecked()).isFalse();
                });
    }

    @Test
    public void updateDialog_wifiIsEnabled_checkWifiSwitch() throws Exception {
        when(mInternetDialogController.isWifiEnabled()).thenReturn(true);
        mWifiToggleSwitch.setChecked(false);
        mInternetDialogDelegate.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mWifiToggleSwitch.isChecked()).isTrue();
                });
    }

    @Test
    public void onClickSeeMoreButton_clickSeeAll_verifyLaunchNetworkSetting() {
        mSeeAll.performClick();

        verify(mInternetDialogController).launchNetworkSetting(
                mDialogView.requireViewById(R.id.see_all_layout));
    }

    @Test
    public void onWifiScan_isScanTrue_setProgressBarVisibleTrue() {
        mInternetDialogDelegate.mIsProgressBarVisible = false;

        mInternetDialogDelegate.onWifiScan(true);

        assertThat(mInternetDialogDelegate.mIsProgressBarVisible).isTrue();
    }

    @Test
    public void onWifiScan_isScanFalse_setProgressBarVisibleFalse() {
        mInternetDialogDelegate.mIsProgressBarVisible = true;

        mInternetDialogDelegate.onWifiScan(false);

        assertThat(mInternetDialogDelegate.mIsProgressBarVisible).isFalse();
    }

    @Test
    public void getWifiListMaxCount_returnCountCorrectly() {
        // Both of the Ethernet, MobileData is hidden.
        // Then the maximum count is equal to MAX_WIFI_ENTRY_COUNT.
        setNetworkVisible(false, false, false);

        assertThat(mInternetDialogDelegate.getWifiListMaxCount()).isEqualTo(MAX_WIFI_ENTRY_COUNT);

        // If the Connected Wi-Fi is displayed then reduce one of the Wi-Fi list max count.
        setNetworkVisible(false, false, true);

        assertThat(mInternetDialogDelegate.getWifiListMaxCount())
                .isEqualTo(MAX_WIFI_ENTRY_COUNT - 1);

        // Only one of Ethernet, MobileData is displayed.
        // Then the maximum count is equal to MAX_WIFI_ENTRY_COUNT.
        setNetworkVisible(true, false, false);

        assertThat(mInternetDialogDelegate.getWifiListMaxCount()).isEqualTo(MAX_WIFI_ENTRY_COUNT);

        setNetworkVisible(false, true, false);

        assertThat(mInternetDialogDelegate.getWifiListMaxCount()).isEqualTo(MAX_WIFI_ENTRY_COUNT);

        // If the Connected Wi-Fi is displayed then reduce one of the Wi-Fi list max count.
        setNetworkVisible(true, false, true);

        assertThat(mInternetDialogDelegate.getWifiListMaxCount())
                .isEqualTo(MAX_WIFI_ENTRY_COUNT - 1);

        setNetworkVisible(false, true, true);

        assertThat(mInternetDialogDelegate.getWifiListMaxCount())
                .isEqualTo(MAX_WIFI_ENTRY_COUNT - 1);

        // Both of Ethernet, MobileData, ConnectedWiFi is displayed.
        // Then the maximum count is equal to MAX_WIFI_ENTRY_COUNT - 1.
        setNetworkVisible(true, true, false);

        assertThat(mInternetDialogDelegate.getWifiListMaxCount())
                .isEqualTo(MAX_WIFI_ENTRY_COUNT - 1);

        // If the Connected Wi-Fi is displayed then reduce one of the Wi-Fi list max count.
        setNetworkVisible(true, true, true);

        assertThat(mInternetDialogDelegate.getWifiListMaxCount())
                .isEqualTo(MAX_WIFI_ENTRY_COUNT - 2);
    }

    @Test
    public void updateDialog_shareWifiIntentNull_hideButton() {
        when(mInternetDialogController.getConfiguratorQrCodeGeneratorIntentOrNull(any()))
                .thenReturn(null);
        mInternetDialogDelegate.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mInternetDialogDelegate.mShareWifiButton.getVisibility()).isEqualTo(
                            View.GONE);
                });
    }

    @Test
    public void updateDialog_shareWifiShareable_showButton() {
        when(mInternetDialogController.getConfiguratorQrCodeGeneratorIntentOrNull(any()))
                .thenReturn(new Intent());
        mInternetDialogDelegate.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegate.mDataInternetContent.observe(
                mInternetDialogDelegate.mLifecycleOwner, i -> {
                    assertThat(mInternetDialogDelegate.mShareWifiButton.getVisibility())
                            .isEqualTo(View.VISIBLE);
                });
    }

    private void setNetworkVisible(boolean ethernetVisible, boolean mobileDataVisible,
            boolean connectedWifiVisible) {
        mEthernet.setVisibility(ethernetVisible ? View.VISIBLE : View.GONE);
        mMobileDataLayout.setVisibility(mobileDataVisible ? View.VISIBLE : View.GONE);
        mConnectedWifi.setVisibility(connectedWifiVisible ? View.VISIBLE : View.GONE);
    }
}
