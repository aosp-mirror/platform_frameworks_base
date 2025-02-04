package com.android.systemui.qs.tiles.dialog;

import static com.android.systemui.qs.tiles.dialog.InternetDetailsContentController.MAX_WIFI_ENTRY_COUNT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.os.Handler;
import android.platform.test.annotations.DisableFlags;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.testing.TestableLooper;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.wifi.WifiEnterpriseRestrictionUtils;
import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.res.R;
import com.android.systemui.shade.domain.interactor.FakeShadeDialogContextInteractor;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.wifitrackerlib.WifiEntry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

import java.util.List;

import kotlinx.coroutines.CoroutineScope;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@DisableFlags(Flags.FLAG_QS_TILE_DETAILED_VIEW)
@UiThreadTest
public class InternetDialogDelegateLegacyTest extends SysuiTestCase {

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
    private InternetDetailsContentController mInternetDetailsContentController;
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
    private InternetDialogDelegateLegacy mInternetDialogDelegateLegacy;
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
    private final KosmosJavaAdapter mKosmos = new KosmosJavaAdapter(this);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        doReturn(mTelephonyManager).when(mTelephonyManager).createForSubscriptionId(anyInt());
        when(mInternetWifiEntry.getTitle()).thenReturn(WIFI_TITLE);
        when(mInternetWifiEntry.getSummary(false)).thenReturn(WIFI_SUMMARY);
        when(mInternetWifiEntry.isDefaultNetwork()).thenReturn(true);
        when(mInternetWifiEntry.hasInternetAccess()).thenReturn(true);
        when(mWifiEntries.size()).thenReturn(1);

        when(mInternetDetailsContentController.getMobileNetworkTitle(anyInt()))
                .thenReturn(MOBILE_NETWORK_TITLE);
        when(mInternetDetailsContentController.getMobileNetworkSummary(anyInt()))
                .thenReturn(MOBILE_NETWORK_SUMMARY);
        when(mInternetDetailsContentController.isWifiEnabled()).thenReturn(true);
        when(mInternetDetailsContentController.getActiveAutoSwitchNonDdsSubId()).thenReturn(
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        mMockitoSession = ExtendedMockito.mockitoSession()
                .spyStatic(WifiEnterpriseRestrictionUtils.class)
                .startMocking();
        when(WifiEnterpriseRestrictionUtils.isChangeWifiStateAllowed(mContext)).thenReturn(true);
        when(mSystemUIDialogFactory.create(any(SystemUIDialog.Delegate.class), eq(mContext)))
                .thenReturn(mSystemUIDialog);
        when(mSystemUIDialog.getContext()).thenReturn(mContext);
        when(mSystemUIDialog.getWindow()).thenReturn(mWindow);
        createInternetDialog();
    }

    private void createInternetDialog() {
        mInternetDialogDelegateLegacy = new InternetDialogDelegateLegacy(
                mContext,
                mock(InternetDialogManager.class),
                mInternetDetailsContentController,
                true,
                true,
                true,
                mScope,
                mock(UiEventLogger.class),
                mDialogTransitionAnimator,
                mHandler,
                mBgExecutor,
                mKeyguard,
                mSystemUIDialogFactory,
                new FakeShadeDialogContextInteractor(mContext),
                mKosmos.getShadeModeInteractor());
        mInternetDialogDelegateLegacy.createDialog();
        mInternetDialogDelegateLegacy.onCreate(mSystemUIDialog, null);
        mInternetDialogDelegateLegacy.mAdapter = mInternetAdapter;
        mInternetDialogDelegateLegacy.mConnectedWifiEntry = mInternetWifiEntry;
        mInternetDialogDelegateLegacy.mWifiEntriesCount = mWifiEntries.size();

        mDialogView = mInternetDialogDelegateLegacy.mDialogView;
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
        mInternetDialogDelegateLegacy.onStart(mSystemUIDialog);
    }

    @After
    public void tearDown() {
        mInternetDialogDelegateLegacy.onStop(mSystemUIDialog);
        mInternetDialogDelegateLegacy.dismissDialog();
        mMockitoSession.finishMocking();
    }

    @Test
    public void createInternetDialog_setAccessibilityPaneTitleToQuickSettings() {
        assertThat(mDialogView.getAccessibilityPaneTitle())
                .isEqualTo(mContext.getText(R.string.accessibility_desc_quick_settings));
    }

    @Test
    public void hideWifiViews_WifiViewsGone() {
        mInternetDialogDelegateLegacy.hideWifiViews();

        assertThat(mInternetDialogDelegateLegacy.mIsProgressBarVisible).isFalse();
        assertThat(mWifiToggle.getVisibility()).isEqualTo(View.GONE);
        assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.GONE);
        assertThat(mWifiList.getVisibility()).isEqualTo(View.GONE);
        assertThat(mSeeAll.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_withApmOn_internetDialogSubTitleGone() {
        when(mInternetDetailsContentController.isAirplaneModeEnabled()).thenReturn(true);
        mInternetDialogDelegateLegacy.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mSubTitle.getVisibility()).isEqualTo(View.VISIBLE);
                });
    }

    @Test
    public void updateDialog_withApmOff_internetDialogSubTitleVisible() {
        when(mInternetDetailsContentController.isAirplaneModeEnabled()).thenReturn(false);
        mInternetDialogDelegateLegacy.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mSubTitle.getVisibility()).isEqualTo(View.VISIBLE);
                });
    }

    @Test
    public void updateDialog_apmOffAndHasEthernet_showEthernet() {
        when(mInternetDetailsContentController.isAirplaneModeEnabled()).thenReturn(false);
        when(mInternetDetailsContentController.hasEthernet()).thenReturn(true);
        mInternetDialogDelegateLegacy.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mEthernet.getVisibility()).isEqualTo(View.VISIBLE);
                });
    }

    @Test
    public void updateDialog_apmOffAndNoEthernet_hideEthernet() {
        when(mInternetDetailsContentController.isAirplaneModeEnabled()).thenReturn(false);
        when(mInternetDetailsContentController.hasEthernet()).thenReturn(false);
        mInternetDialogDelegateLegacy.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mEthernet.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_apmOnAndHasEthernet_showEthernet() {
        when(mInternetDetailsContentController.isAirplaneModeEnabled()).thenReturn(true);
        when(mInternetDetailsContentController.hasEthernet()).thenReturn(true);
        mInternetDialogDelegateLegacy.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mEthernet.getVisibility()).isEqualTo(View.VISIBLE);
                });
    }

    @Test
    public void updateDialog_apmOnAndNoEthernet_hideEthernet() {
        when(mInternetDetailsContentController.isAirplaneModeEnabled()).thenReturn(true);
        when(mInternetDetailsContentController.hasEthernet()).thenReturn(false);
        mInternetDialogDelegateLegacy.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mEthernet.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_apmOffAndNotCarrierNetwork_mobileDataLayoutGone() {
        // Mobile network should be gone if the list of active subscriptionId is null.
        when(mInternetDetailsContentController.isCarrierNetworkActive()).thenReturn(false);
        when(mInternetDetailsContentController.isAirplaneModeEnabled()).thenReturn(false);
        when(mInternetDetailsContentController.hasActiveSubIdOnDds()).thenReturn(false);
        mInternetDialogDelegateLegacy.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mMobileDataLayout.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_apmOnWithCarrierNetworkAndWifiStatus_mobileDataLayoutVisible() {
        // Carrier network should be visible if airplane mode ON and Wi-Fi is ON.
        when(mInternetDetailsContentController.isCarrierNetworkActive()).thenReturn(true);
        when(mInternetDetailsContentController.isAirplaneModeEnabled()).thenReturn(true);
        when(mInternetDetailsContentController.isWifiEnabled()).thenReturn(true);
        mInternetDialogDelegateLegacy.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mMobileDataLayout.getVisibility()).isEqualTo(View.VISIBLE);
                });
    }

    @Test
    public void updateDialog_apmOnWithCarrierNetworkAndWifiStatus_mobileDataLayoutGone() {
        // Carrier network should be gone if airplane mode ON and Wi-Fi is off.
        when(mInternetDetailsContentController.isCarrierNetworkActive()).thenReturn(true);
        when(mInternetDetailsContentController.isAirplaneModeEnabled()).thenReturn(true);
        when(mInternetDetailsContentController.isWifiEnabled()).thenReturn(false);
        mInternetDialogDelegateLegacy.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mMobileDataLayout.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_apmOnAndNoCarrierNetwork_mobileDataLayoutGone() {
        when(mInternetDetailsContentController.isCarrierNetworkActive()).thenReturn(false);
        when(mInternetDetailsContentController.isAirplaneModeEnabled()).thenReturn(true);
        mInternetDialogDelegateLegacy.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mMobileDataLayout.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_apmOnAndWifiOnHasCarrierNetwork_showAirplaneSummary() {
        when(mInternetDetailsContentController.isCarrierNetworkActive()).thenReturn(true);
        when(mInternetDetailsContentController.isAirplaneModeEnabled()).thenReturn(true);
        mInternetDialogDelegateLegacy.mConnectedWifiEntry = null;
        doReturn(false).when(mInternetDetailsContentController).activeNetworkIsCellular();
        mInternetDialogDelegateLegacy.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mMobileDataLayout.getVisibility()).isEqualTo(View.VISIBLE);
                    assertThat(mAirplaneModeSummaryText.getVisibility()).isEqualTo(View.VISIBLE);
                });
    }

    @Test
    public void updateDialog_apmOffAndWifiOnHasCarrierNetwork_notShowApmSummary() {
        when(mInternetDetailsContentController.isCarrierNetworkActive()).thenReturn(true);
        when(mInternetDetailsContentController.isAirplaneModeEnabled()).thenReturn(false);
        mInternetDialogDelegateLegacy.mConnectedWifiEntry = null;
        doReturn(false).when(mInternetDetailsContentController).activeNetworkIsCellular();
        mInternetDialogDelegateLegacy.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mAirplaneModeSummaryText.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_apmOffAndHasCarrierNetwork_notShowApmSummary() {
        when(mInternetDetailsContentController.isCarrierNetworkActive()).thenReturn(true);
        when(mInternetDetailsContentController.isAirplaneModeEnabled()).thenReturn(false);
        mInternetDialogDelegateLegacy.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mAirplaneModeSummaryText.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_apmOnAndNoCarrierNetwork_notShowApmSummary() {
        when(mInternetDetailsContentController.isCarrierNetworkActive()).thenReturn(false);
        when(mInternetDetailsContentController.isAirplaneModeEnabled()).thenReturn(true);
        mInternetDialogDelegateLegacy.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mAirplaneModeSummaryText.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_mobileDataIsEnabled_checkMobileDataSwitch() {
        doReturn(true).when(mInternetDetailsContentController).hasActiveSubIdOnDds();
        when(mInternetDetailsContentController.isCarrierNetworkActive()).thenReturn(true);
        when(mInternetDetailsContentController.isMobileDataEnabled()).thenReturn(true);
        mMobileToggleSwitch.setChecked(false);
        mInternetDialogDelegateLegacy.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mMobileToggleSwitch.isChecked()).isTrue();
                });
    }

    @Test
    public void updateDialog_mobileDataIsNotChanged_checkMobileDataSwitch() {
        doReturn(true).when(mInternetDetailsContentController).hasActiveSubIdOnDds();
        when(mInternetDetailsContentController.isCarrierNetworkActive()).thenReturn(true);
        when(mInternetDetailsContentController.isMobileDataEnabled()).thenReturn(false);
        mMobileToggleSwitch.setChecked(false);
        mInternetDialogDelegateLegacy.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mMobileToggleSwitch.isChecked()).isFalse();
                });
    }

    @Test
    public void updateDialog_wifiOnAndHasInternetWifi_showConnectedWifi() {
        when(mInternetDetailsContentController.getActiveAutoSwitchNonDdsSubId()).thenReturn(1);
        doReturn(true).when(mInternetDetailsContentController).hasActiveSubIdOnDds();
        // The preconditions WiFi ON and Internet WiFi are already in setUp()
        doReturn(false).when(mInternetDetailsContentController).activeNetworkIsCellular();

        mInternetDialogDelegateLegacy.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.VISIBLE);
                    LinearLayout secondaryLayout = mDialogView.requireViewById(
                            R.id.secondary_mobile_network_layout);
                    assertThat(secondaryLayout.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_wifiOnAndNoConnectedWifi_hideConnectedWifi() {
        // The precondition WiFi ON is already in setUp()
        mInternetDialogDelegateLegacy.mConnectedWifiEntry = null;
        doReturn(false).when(mInternetDetailsContentController).activeNetworkIsCellular();
        mInternetDialogDelegateLegacy.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_wifiOnAndNoWifiEntry_showWifiListAndSeeAllArea() {
        // The precondition WiFi ON is already in setUp()
        mInternetDialogDelegateLegacy.mConnectedWifiEntry = null;
        mInternetDialogDelegateLegacy.mWifiEntriesCount = 0;
        mInternetDialogDelegateLegacy.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
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
        mInternetDialogDelegateLegacy.mConnectedWifiEntry = null;
        mInternetDialogDelegateLegacy.mWifiEntriesCount = 1;
        mInternetDialogDelegateLegacy.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
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
        mInternetDialogDelegateLegacy.mWifiEntriesCount = 0;
        mInternetDialogDelegateLegacy.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
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
        mInternetDialogDelegateLegacy.mConnectedWifiEntry = null;
        mInternetDialogDelegateLegacy.mWifiEntriesCount = MAX_WIFI_ENTRY_COUNT;
        mInternetDialogDelegateLegacy.mHasMoreWifiEntries = true;
        mInternetDialogDelegateLegacy.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.GONE);
                    assertThat(mWifiList.getVisibility()).isEqualTo(View.VISIBLE);
                    verify(mInternetAdapter).setMaxEntriesCount(3);
                    assertThat(mSeeAll.getVisibility()).isEqualTo(View.VISIBLE);
                });
    }

    @Test
    public void updateDialog_wifiOnAndHasBothWifiEntry_showBothWifiEntryAndSeeAll() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        mInternetDialogDelegateLegacy.mWifiEntriesCount = MAX_WIFI_ENTRY_COUNT - 1;
        mInternetDialogDelegateLegacy.mHasMoreWifiEntries = true;
        mInternetDialogDelegateLegacy.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.VISIBLE);
                    assertThat(mWifiList.getVisibility()).isEqualTo(View.VISIBLE);
                    verify(mInternetAdapter).setMaxEntriesCount(2);
                    assertThat(mSeeAll.getVisibility()).isEqualTo(View.VISIBLE);
                });
    }

    @Test
    public void updateDialog_deviceLockedAndNoConnectedWifi_showWifiToggle() {
        // The preconditions WiFi entries are already in setUp()
        when(mInternetDetailsContentController.isDeviceLocked()).thenReturn(true);
        mInternetDialogDelegateLegacy.mConnectedWifiEntry = null;
        mInternetDialogDelegateLegacy.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
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
        when(mInternetDetailsContentController.isDeviceLocked()).thenReturn(true);
        mInternetDialogDelegateLegacy.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
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
        mInternetDialogDelegateLegacy.dismissDialog();
        when(WifiEnterpriseRestrictionUtils.isChangeWifiStateAllowed(mContext)).thenReturn(false);
        createInternetDialog();
        mInternetDialogDelegateLegacy.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    // Disable Wi-Fi switch and show restriction message in summary.
                    assertThat(mWifiToggleSwitch.isEnabled()).isFalse();
                    assertThat(mWifiToggleSummary.getVisibility()).isEqualTo(View.VISIBLE);
                    assertThat(mWifiToggleSummary.getText().length()).isNotEqualTo(0);
                });
    }

    @Test
    public void updateDialog_allowChangeWifiState_enableWifiSwitch() {
        mInternetDialogDelegateLegacy.dismissDialog();
        when(WifiEnterpriseRestrictionUtils.isChangeWifiStateAllowed(mContext)).thenReturn(true);
        createInternetDialog();
        mInternetDialogDelegateLegacy.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    // Enable Wi-Fi switch and hide restriction message in summary.
                    assertThat(mWifiToggleSwitch.isEnabled()).isTrue();
                    assertThat(mWifiToggleSummary.getVisibility()).isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_showSecondaryDataSub() {
        when(mInternetDetailsContentController.getActiveAutoSwitchNonDdsSubId()).thenReturn(1);
        doReturn(1).when(mInternetDetailsContentController).getActiveAutoSwitchNonDdsSubId();
        doReturn(true).when(mInternetDetailsContentController).hasActiveSubIdOnDds();
        doReturn(false).when(mInternetDetailsContentController).isAirplaneModeEnabled();
        clearInvocations(mInternetDetailsContentController);
        mInternetDialogDelegateLegacy.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    LinearLayout primaryLayout = mDialogView.requireViewById(
                            R.id.mobile_network_layout);
                    LinearLayout secondaryLayout = mDialogView.requireViewById(
                            R.id.secondary_mobile_network_layout);

                    verify(mInternetDetailsContentController).getMobileNetworkSummary(1);
                    assertThat(primaryLayout.getBackground()).isNotEqualTo(
                            secondaryLayout.getBackground());
                });
    }

    @Test
    public void updateDialog_wifiOn_hideWifiScanNotify() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()

        mInternetDialogDelegateLegacy.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mWifiScanNotify.getVisibility()).isEqualTo(View.GONE);
                });

        assertThat(mWifiScanNotify.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_wifiOffAndWifiScanOff_hideWifiScanNotify() {
        when(mInternetDetailsContentController.isWifiEnabled()).thenReturn(false);
        when(mInternetDetailsContentController.isWifiScanEnabled()).thenReturn(false);
        mInternetDialogDelegateLegacy.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mWifiScanNotify.getVisibility()).isEqualTo(View.GONE);
                });

        assertThat(mWifiScanNotify.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_wifiOffAndWifiScanOnAndDeviceLocked_hideWifiScanNotify() {
        when(mInternetDetailsContentController.isWifiEnabled()).thenReturn(false);
        when(mInternetDetailsContentController.isWifiScanEnabled()).thenReturn(true);
        when(mInternetDetailsContentController.isDeviceLocked()).thenReturn(true);
        mInternetDialogDelegateLegacy.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mWifiScanNotify.getVisibility()).isEqualTo(View.GONE);
                });

        assertThat(mWifiScanNotify.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_wifiOffAndWifiScanOnAndDeviceUnlocked_showWifiScanNotify() {
        when(mInternetDetailsContentController.isWifiEnabled()).thenReturn(false);
        when(mInternetDetailsContentController.isWifiScanEnabled()).thenReturn(true);
        when(mInternetDetailsContentController.isDeviceLocked()).thenReturn(false);
        mInternetDialogDelegateLegacy.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mWifiScanNotify.getVisibility()).isEqualTo(View.VISIBLE);
                    TextView wifiScanNotifyText = mDialogView.requireViewById(
                            R.id.wifi_scan_notify_text);
                    assertThat(wifiScanNotifyText.getText().length()).isNotEqualTo(0);
                    assertThat(wifiScanNotifyText.getMovementMethod()).isNotNull();
                });
    }

    @Test
    public void updateDialog_wifiIsDisabled_uncheckWifiSwitch() {
        when(mInternetDetailsContentController.isWifiEnabled()).thenReturn(false);
        mWifiToggleSwitch.setChecked(true);
        mInternetDialogDelegateLegacy.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mWifiToggleSwitch.isChecked()).isFalse();
                });
    }

    @Test
    public void updateDialog_wifiIsEnabled_checkWifiSwitch() throws Exception {
        when(mInternetDetailsContentController.isWifiEnabled()).thenReturn(true);
        mWifiToggleSwitch.setChecked(false);
        mInternetDialogDelegateLegacy.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mWifiToggleSwitch.isChecked()).isTrue();
                });
    }

    @Test
    public void onClickSeeMoreButton_clickSeeAll_verifyLaunchNetworkSetting() {
        mSeeAll.performClick();

        verify(mInternetDetailsContentController).launchNetworkSetting(
                mDialogView.requireViewById(R.id.see_all_layout));
    }

    @Test
    public void onWifiScan_isScanTrue_setProgressBarVisibleTrue() {
        mInternetDialogDelegateLegacy.mIsProgressBarVisible = false;

        mInternetDialogDelegateLegacy.onWifiScan(true);

        assertThat(mInternetDialogDelegateLegacy.mIsProgressBarVisible).isTrue();
    }

    @Test
    public void onWifiScan_isScanFalse_setProgressBarVisibleFalse() {
        mInternetDialogDelegateLegacy.mIsProgressBarVisible = true;

        mInternetDialogDelegateLegacy.onWifiScan(false);

        assertThat(mInternetDialogDelegateLegacy.mIsProgressBarVisible).isFalse();
    }

    @Test
    public void getWifiListMaxCount_returnCountCorrectly() {
        // Both of the Ethernet, MobileData is hidden.
        // Then the maximum count is equal to MAX_WIFI_ENTRY_COUNT.
        setNetworkVisible(false, false, false);

        assertThat(mInternetDialogDelegateLegacy.getWifiListMaxCount()).isEqualTo(
                MAX_WIFI_ENTRY_COUNT);

        // If the Connected Wi-Fi is displayed then reduce one of the Wi-Fi list max count.
        setNetworkVisible(false, false, true);

        assertThat(mInternetDialogDelegateLegacy.getWifiListMaxCount())
                .isEqualTo(MAX_WIFI_ENTRY_COUNT - 1);

        // Only one of Ethernet, MobileData is displayed.
        // Then the maximum count is equal to MAX_WIFI_ENTRY_COUNT.
        setNetworkVisible(true, false, false);

        assertThat(mInternetDialogDelegateLegacy.getWifiListMaxCount()).isEqualTo(
                MAX_WIFI_ENTRY_COUNT);

        setNetworkVisible(false, true, false);

        assertThat(mInternetDialogDelegateLegacy.getWifiListMaxCount()).isEqualTo(
                MAX_WIFI_ENTRY_COUNT);

        // If the Connected Wi-Fi is displayed then reduce one of the Wi-Fi list max count.
        setNetworkVisible(true, false, true);

        assertThat(mInternetDialogDelegateLegacy.getWifiListMaxCount())
                .isEqualTo(MAX_WIFI_ENTRY_COUNT - 1);

        setNetworkVisible(false, true, true);

        assertThat(mInternetDialogDelegateLegacy.getWifiListMaxCount())
                .isEqualTo(MAX_WIFI_ENTRY_COUNT - 1);

        // Both of Ethernet, MobileData, ConnectedWiFi is displayed.
        // Then the maximum count is equal to MAX_WIFI_ENTRY_COUNT - 1.
        setNetworkVisible(true, true, false);

        assertThat(mInternetDialogDelegateLegacy.getWifiListMaxCount())
                .isEqualTo(MAX_WIFI_ENTRY_COUNT - 1);

        // If the Connected Wi-Fi is displayed then reduce one of the Wi-Fi list max count.
        setNetworkVisible(true, true, true);

        assertThat(mInternetDialogDelegateLegacy.getWifiListMaxCount())
                .isEqualTo(MAX_WIFI_ENTRY_COUNT - 2);
    }

    @Test
    public void updateDialog_shareWifiIntentNull_hideButton() {
        when(mInternetDetailsContentController.getConfiguratorQrCodeGeneratorIntentOrNull(any()))
                .thenReturn(null);
        mInternetDialogDelegateLegacy.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(
                            mInternetDialogDelegateLegacy.mShareWifiButton.getVisibility())
                            .isEqualTo(View.GONE);
                });
    }

    @Test
    public void updateDialog_shareWifiShareable_showButton() {
        when(mInternetDetailsContentController.getConfiguratorQrCodeGeneratorIntentOrNull(any()))
                .thenReturn(new Intent());
        mInternetDialogDelegateLegacy.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mInternetDialogDelegateLegacy.mShareWifiButton.getVisibility())
                            .isEqualTo(View.VISIBLE);
                });
    }

    @Test
    public void updateDialog_shouldUpdateMobileNetworkTrue_updateMobileDataLayout() {
        when(mInternetDetailsContentController.isCarrierNetworkActive()).thenReturn(false);
        when(mInternetDetailsContentController.isAirplaneModeEnabled()).thenReturn(false);
        when(mInternetDetailsContentController.hasActiveSubIdOnDds()).thenReturn(true);
        when(mInternetDetailsContentController.activeNetworkIsCellular()).thenReturn(false);
        mMobileDataLayout.setVisibility(View.GONE);

        mInternetDialogDelegateLegacy.updateDialog(true);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mMobileDataLayout.getVisibility()).isEqualTo(View.VISIBLE);
                });
    }

    @Test
    public void updateDialog_shouldUpdateMobileNetworkFalse_doNotUpdateMobileDataLayout() {
        when(mInternetDetailsContentController.isCarrierNetworkActive()).thenReturn(false);
        when(mInternetDetailsContentController.isAirplaneModeEnabled()).thenReturn(false);
        when(mInternetDetailsContentController.hasActiveSubIdOnDds()).thenReturn(true);
        when(mInternetDetailsContentController.activeNetworkIsCellular()).thenReturn(false);
        mMobileDataLayout.setVisibility(View.GONE);

        mInternetDialogDelegateLegacy.updateDialog(false);
        mBgExecutor.runAllReady();

        mInternetDialogDelegateLegacy.mDataInternetContent.observe(
                mInternetDialogDelegateLegacy.mLifecycleOwner, i -> {
                    assertThat(mMobileDataLayout.getVisibility()).isEqualTo(View.GONE);
                });
    }

    private void setNetworkVisible(boolean ethernetVisible, boolean mobileDataVisible,
            boolean connectedWifiVisible) {
        mEthernet.setVisibility(ethernetVisible ? View.VISIBLE : View.GONE);
        mMobileDataLayout.setVisibility(mobileDataVisible ? View.VISIBLE : View.GONE);
        mConnectedWifi.setVisibility(connectedWifiVisible ? View.VISIBLE : View.GONE);
    }
}
