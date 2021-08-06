package com.android.systemui.qs.tiles.dialog;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
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
import com.android.systemui.dagger.qualifiers.Main;
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

    private static final int SUB_ID = 1;
    private static final String MOBILE_NETWORK_TITLE = "Mobile Title";
    private static final String MOBILE_NETWORK_SUMMARY = "Mobile Summary";
    private static final String WIFI_TITLE = "Connected Wi-Fi Title";
    private static final String WIFI_SUMMARY = "Connected Wi-Fi Summary";

    @Mock
    private InternetDialogFactory mInternetDialogFactory;
    @Mock
    private InternetDialogController mInternetDialogController;
    @Mock
    private UiEventLogger mUiEventLogger;
    @Mock
    private Handler mHandler;
    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private InternetAdapter mInternetAdapter;
    @Mock
    private WifiManager mMockWifiManager;
    @Mock
    private WifiEntry mWifiEntry;
    @Mock
    private WifiInfo mWifiInfo;

    private MockInternetDialog mInternetDialog;
    private WifiReceiver mWifiReceiver;
    private LinearLayout mWifiToggle;
    private LinearLayout mConnectedWifi;
    private RecyclerView mWifiList;
    private LinearLayout mSeeAll;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mInternetDialog = new MockInternetDialog(mContext, mInternetDialogFactory,
                mInternetDialogController, true, true, mUiEventLogger, mHandler);
        mInternetDialog.show();
        doReturn(mTelephonyManager).when(mTelephonyManager).createForSubscriptionId(SUB_ID);
        when(mMockWifiManager.isWifiEnabled()).thenReturn(true);
        when(mMockWifiManager.getConnectionInfo()).thenReturn(mWifiInfo);
        mInternetDialog.setMobileNetworkTitle(MOBILE_NETWORK_TITLE);
        mInternetDialog.setMobileNetworkSummary(MOBILE_NETWORK_SUMMARY);
        mInternetDialog.setConnectedWifiTitle(WIFI_TITLE);
        mInternetDialog.setConnectedWifiSummary(WIFI_SUMMARY);
        mWifiReceiver = new WifiReceiver();
        IntentFilter mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        mIntentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        mContext.registerReceiver(mWifiReceiver, mIntentFilter);
        when(mWifiEntry.getTitle()).thenReturn(WIFI_TITLE);
        when(mWifiEntry.getSummary(false)).thenReturn(WIFI_SUMMARY);
        when(mInternetDialogController.getWifiEntryList()).thenReturn(Arrays.asList(mWifiEntry));
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
    public void updateDialog_wifiOnAndHasConnectedWifi_showConnectedWifi() {
        doReturn(false).when(mInternetDialogController).activeNetworkIsCellular();
        when(mWifiEntry.getTitle()).thenReturn(WIFI_TITLE);
        when(mWifiEntry.getSummary(false)).thenReturn(WIFI_SUMMARY);
        when(mWifiEntry.getConnectedState()).thenReturn(WifiEntry.CONNECTED_STATE_CONNECTED);
        when(mWifiEntry.isDefaultNetwork()).thenReturn(true);
        mInternetDialog.mConnectedWifiEntry = mWifiEntry;

        mInternetDialog.updateDialog();

        assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void updateDialog_wifiOnAndNoConnectedWifi_hideConnectedWifi() {
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
        List<WifiEntry> wifiEntries = new ArrayList<WifiEntry>();
        wifiEntries.add(mWifiEntry);
        when(mInternetDialogController.getWifiEntryList()).thenReturn(wifiEntries);

        mInternetDialog.updateDialog();

        assertThat(mWifiList.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mSeeAll.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void updateDialog_deviceLockedAndHasConnectedWifi_showHighlightWifiToggle() {
        when(mInternetDialogController.isDeviceLocked()).thenReturn(true);
        when(mWifiEntry.getTitle()).thenReturn(WIFI_TITLE);
        when(mWifiEntry.getSummary(false)).thenReturn(WIFI_SUMMARY);
        when(mWifiEntry.getConnectedState()).thenReturn(WifiEntry.CONNECTED_STATE_CONNECTED);
        when(mWifiEntry.isDefaultNetwork()).thenReturn(true);
        mInternetDialog.mConnectedWifiEntry = mWifiEntry;

        mInternetDialog.updateDialog();

        assertThat(mWifiToggle.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mWifiToggle.getBackground()).isNotNull();
    }

    @Test
    public void updateDialog_deviceLockedAndHasConnectedWifi_hideConnectedWifi() {
        when(mInternetDialogController.isDeviceLocked()).thenReturn(true);
        when(mWifiEntry.getTitle()).thenReturn(WIFI_TITLE);
        when(mWifiEntry.getSummary(false)).thenReturn(WIFI_SUMMARY);
        when(mWifiEntry.getConnectedState()).thenReturn(WifiEntry.CONNECTED_STATE_CONNECTED);
        when(mWifiEntry.isDefaultNetwork()).thenReturn(true);
        mInternetDialog.mConnectedWifiEntry = mWifiEntry;

        mInternetDialog.updateDialog();

        assertThat(mConnectedWifi.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_deviceLockedAndHasWifiList_hideWifiListAndSeeAll() {
        when(mInternetDialogController.isDeviceLocked()).thenReturn(true);
        List<WifiEntry> wifiEntries = new ArrayList<WifiEntry>();
        wifiEntries.add(mWifiEntry);
        when(mInternetDialogController.getWifiEntryList()).thenReturn(wifiEntries);

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
        when(mMockWifiManager.isWifiEnabled()).thenReturn(false);

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
        when(mMockWifiManager.isWifiEnabled()).thenReturn(true);
        List<ScanResult> wifiScanResults = mock(ArrayList.class);
        when(wifiScanResults.size()).thenReturn(1);
        when(mMockWifiManager.getScanResults()).thenReturn(wifiScanResults);

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
        when(mMockWifiManager.isWifiEnabled()).thenReturn(true);
        List<ScanResult> wifiScanResults = mock(ArrayList.class);
        when(wifiScanResults.size()).thenReturn(0);
        when(mMockWifiManager.getScanResults()).thenReturn(wifiScanResults);

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

    private class MockInternetDialog extends InternetDialog {

        private String mMobileNetworkTitle;
        private String mMobileNetworkSummary;
        private String mConnectedWifiTitle;
        private String mConnectedWifiSummary;

        MockInternetDialog(Context context, InternetDialogFactory internetDialogFactory,
                InternetDialogController internetDialogController, boolean canConfigMobileData,
                boolean aboveStatusBar, UiEventLogger uiEventLogger, @Main Handler handler) {
            super(context, internetDialogFactory, internetDialogController, canConfigMobileData,
                    aboveStatusBar, uiEventLogger, handler);
            mAdapter = mInternetAdapter;
            mWifiManager = mMockWifiManager;
        }

        @Override
        String getMobileNetworkTitle() {
            return mMobileNetworkTitle;
        }

        @Override
        String getMobileNetworkSummary() {
            return mMobileNetworkSummary;
        }

        void setMobileNetworkTitle(String title) {
            mMobileNetworkTitle = title;
        }

        void setMobileNetworkSummary(String summary) {
            mMobileNetworkSummary = summary;
        }

        @Override
        String getConnectedWifiTitle() {
            return mConnectedWifiTitle;
        }

        @Override
        String getConnectedWifiSummary() {
            return mConnectedWifiSummary;
        }

        void setConnectedWifiTitle(String title) {
            mConnectedWifiTitle = title;
        }

        void setConnectedWifiSummary(String summary) {
            mConnectedWifiSummary = summary;
        }

        @Override
        public void onWifiStateReceived(Context context, Intent intent) {
            setMobileNetworkTitle(MOBILE_NETWORK_TITLE);
            setMobileNetworkSummary(MOBILE_NETWORK_SUMMARY);
        }
    }

    private class WifiReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                return;
            }

            if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                mInternetDialog.updateDialog();
            }
        }
    }

}
