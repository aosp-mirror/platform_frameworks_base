package com.android.systemui.qs.tiles.dialog;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.telephony.TelephonyManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.wifitrackerlib.WifiEntry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class InternetDialogTest extends SysuiTestCase {

    private static final String MOBILE_NETWORK_TITLE = "Mobile Title";
    private static final String MOBILE_NETWORK_SUMMARY = "Mobile Summary";
    private static final String WIFI_TITLE = "Connected Wi-Fi Title";
    private static final String WIFI_SUMMARY = "Connected Wi-Fi Summary";

    @Mock
    private Handler mHandler;
    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private WifiManager mWifiManager;
    @Mock
    private WifiEntry mInternetWifiEntry;
    @Mock
    private WifiEntry mWifiEntry;
    @Mock
    private InternetAdapter mInternetAdapter;
    @Mock
    private InternetDialogController mInternetDialogController;

    private InternetDialog mInternetDialog;
    private LinearLayout mWifiToggle;
    private LinearLayout mConnectedWifi;
    private RecyclerView mWifiList;
    private LinearLayout mSeeAll;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        doReturn(mTelephonyManager).when(mTelephonyManager).createForSubscriptionId(anyInt());
        when(mWifiManager.isWifiEnabled()).thenReturn(true);
        when(mInternetWifiEntry.getTitle()).thenReturn(WIFI_TITLE);
        when(mInternetWifiEntry.getSummary(false)).thenReturn(WIFI_SUMMARY);
        when(mInternetWifiEntry.isDefaultNetwork()).thenReturn(true);
        when(mInternetWifiEntry.hasInternetAccess()).thenReturn(true);
        when(mWifiEntry.getTitle()).thenReturn(WIFI_TITLE);
        when(mWifiEntry.getSummary(false)).thenReturn(WIFI_SUMMARY);

        when(mInternetDialogController.getMobileNetworkTitle()).thenReturn(MOBILE_NETWORK_TITLE);
        when(mInternetDialogController.getMobileNetworkSummary())
                .thenReturn(MOBILE_NETWORK_SUMMARY);
        when(mInternetDialogController.getWifiManager()).thenReturn(mWifiManager);
        when(mInternetDialogController.getInternetWifiEntry()).thenReturn(mInternetWifiEntry);
        when(mInternetDialogController.getWifiEntryList()).thenReturn(Arrays.asList(mWifiEntry));

        mInternetDialog = new InternetDialog(mContext, mock(InternetDialogFactory.class),
                mInternetDialogController, true, true, mock(UiEventLogger.class), mHandler);
        mInternetDialog.mAdapter = mInternetAdapter;
        mInternetDialog.mConnectedWifiEntry = mInternetWifiEntry;
        mInternetDialog.show();

        mWifiToggle = mInternetDialog.mDialogView.requireViewById(R.id.turn_on_wifi_layout);
        mConnectedWifi = mInternetDialog.mDialogView.requireViewById(R.id.wifi_connected_layout);
        mWifiList = mInternetDialog.mDialogView.requireViewById(R.id.wifi_list_layout);
        mSeeAll = mInternetDialog.mDialogView.requireViewById(R.id.see_all_layout);
    }

    @After
    public void tearDown() {
        mInternetDialog.dismissDialog();
    }

    @Test
    public void updateDialog_withApmOn_internetDialogSubTitleGone() {
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(true);
        mInternetDialog.updateDialog();
        final TextView view = mInternetDialog.mDialogView.requireViewById(
                R.id.internet_dialog_subtitle);

        assertThat(view.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_withApmOff_internetDialogSubTitleVisible() {
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(false);
        mInternetDialog.updateDialog();
        final TextView view = mInternetDialog.mDialogView.requireViewById(
                R.id.internet_dialog_subtitle);

        assertThat(view.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void updateDialog_withApmOn_mobileDataLayoutGone() {
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(true);
        mInternetDialog.updateDialog();
        final LinearLayout linearLayout = mInternetDialog.mDialogView.requireViewById(
                R.id.mobile_network_layout);

        assertThat(linearLayout.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_wifiOnAndHasInternetWifi_showConnectedWifi() {
        // The preconditions WiFi ON and Internet WiFi are already in setUp()
        doReturn(false).when(mInternetDialogController).activeNetworkIsCellular();

        mInternetDialog.updateDialog();

        assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void updateDialog_wifiOnAndNoConnectedWifi_hideConnectedWifi() {
        mInternetDialog.mConnectedWifiEntry = null;
        doReturn(false).when(mInternetDialogController).activeNetworkIsCellular();

        mInternetDialog.updateDialog();

        assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_wifiOnAndNoWifiList_hideWifiListAndSeeAll() {
        when(mInternetDialogController.getWifiEntryList()).thenReturn(null);

        mInternetDialog.updateDialog();

        assertThat(mWifiList.getVisibility()).isEqualTo(View.GONE);
        assertThat(mSeeAll.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_wifiOnAndHasWifiList_showWifiListAndSeeAll() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()

        mInternetDialog.updateDialog();

        assertThat(mWifiList.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mSeeAll.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void updateDialog_deviceLockedAndHasInternetWifi_showHighlightWifiToggle() {
        // The preconditions WiFi ON and Internet WiFi are already in setUp()
        when(mInternetDialogController.isDeviceLocked()).thenReturn(true);

        mInternetDialog.updateDialog();

        assertThat(mWifiToggle.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mWifiToggle.getBackground()).isNotNull();
    }

    @Test
    public void updateDialog_deviceLockedAndHasInternetWifi_hideConnectedWifi() {
        // The preconditions WiFi ON and Internet WiFi are already in setUp()
        when(mInternetDialogController.isDeviceLocked()).thenReturn(true);

        mInternetDialog.updateDialog();

        assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_deviceLockedAndHasWifiList_hideWifiListAndSeeAll() {
        // The preconditions WiFi entries are already in setUp()
        when(mInternetDialogController.isDeviceLocked()).thenReturn(true);

        mInternetDialog.updateDialog();

        assertThat(mWifiList.getVisibility()).isEqualTo(View.GONE);
        assertThat(mSeeAll.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void onClickSeeMoreButton_clickSeeAll_verifyLaunchNetworkSetting() {
        mSeeAll.performClick();

        verify(mInternetDialogController).launchNetworkSetting();
    }

    @Test
    public void showProgressBar_wifiDisabled_hideProgressBar() {
        Mockito.reset(mHandler);
        when(mWifiManager.isWifiEnabled()).thenReturn(false);

        mInternetDialog.showProgressBar();

        assertThat(mInternetDialog.mIsProgressBarVisible).isFalse();
        verify(mHandler, never()).postDelayed(any(Runnable.class), anyLong());
    }

    @Test
    public void showProgressBar_deviceLocked_hideProgressBar() {
        Mockito.reset(mHandler);
        when(mInternetDialogController.isDeviceLocked()).thenReturn(true);

        mInternetDialog.showProgressBar();

        assertThat(mInternetDialog.mIsProgressBarVisible).isFalse();
        verify(mHandler, never()).postDelayed(any(Runnable.class), anyLong());
    }

    @Test
    public void showProgressBar_wifiEnabledWithWifiEntry_showProgressBarThenHide() {
        Mockito.reset(mHandler);
        when(mWifiManager.isWifiEnabled()).thenReturn(true);
        List<ScanResult> wifiScanResults = mock(ArrayList.class);
        when(wifiScanResults.size()).thenReturn(1);
        when(mWifiManager.getScanResults()).thenReturn(wifiScanResults);

        mInternetDialog.showProgressBar();

        // Show progress bar
        assertThat(mInternetDialog.mIsProgressBarVisible).isTrue();

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mHandler).postDelayed(runnableCaptor.capture(),
                eq(InternetDialog.PROGRESS_DELAY_MS));
        runnableCaptor.getValue().run();

        // Then hide progress bar
        assertThat(mInternetDialog.mIsProgressBarVisible).isFalse();
    }

    @Test
    public void showProgressBar_wifiEnabledWithoutWifiScanResults_showProgressBarThenHideSearch() {
        Mockito.reset(mHandler);
        when(mWifiManager.isWifiEnabled()).thenReturn(true);
        List<ScanResult> wifiScanResults = mock(ArrayList.class);
        when(wifiScanResults.size()).thenReturn(0);
        when(mWifiManager.getScanResults()).thenReturn(wifiScanResults);

        mInternetDialog.showProgressBar();

        // Show progress bar
        assertThat(mInternetDialog.mIsProgressBarVisible).isTrue();

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mHandler).postDelayed(runnableCaptor.capture(),
                eq(InternetDialog.PROGRESS_DELAY_MS));
        runnableCaptor.getValue().run();

        // Then hide searching sub-title only
        assertThat(mInternetDialog.mIsProgressBarVisible).isTrue();
        assertThat(mInternetDialog.mIsSearchingHidden).isTrue();
    }
}
