package com.android.systemui.qs.tiles.dialog;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class InternetDialogTest extends SysuiTestCase {

    private static final int SUB_ID = 1;
    private static final String MOBILE_NETWORK_TITLE = "Mobile Title";
    private static final String MOBILE_NETWORK_SUMMARY = "Mobile Summary";
    private static final String WIFI_TITLE = "Connected Wi-Fi Title";
    private static final String WIFI_SUMMARY = "Connected Wi-Fi Summary";

    private final UiEventLogger mUiEventLogger = mock(UiEventLogger.class);

    private InternetDialogFactory mInternetDialogFactory = mock(InternetDialogFactory.class);
    private InternetAdapter mInternetAdapter = mock(InternetAdapter.class);
    private InternetDialogController mInternetDialogController = mock(
            InternetDialogController.class);
    private InternetDialogController.InternetDialogCallback mCallback =
            mock(InternetDialogController.InternetDialogCallback.class);
    private MockInternetDialog mInternetDialog;
    private WifiReceiver mWifiReceiver = null;
    private WifiManager mMockWifiManager = mock(WifiManager.class);
    private TelephonyManager mTelephonyManager = mock(TelephonyManager.class);
    @Mock
    private WifiEntry mWifiEntry = mock(WifiEntry.class);
    @Mock
    private WifiInfo mWifiInfo;
    @Mock
    private Handler mHandler;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mInternetDialog = new MockInternetDialog(mContext, mInternetDialogFactory,
                mInternetDialogController, true, mUiEventLogger, mHandler);
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
    public void updateDialog_withWifiOnAndHasConnectedWifi_connectedWifiLayoutVisible() {
        doReturn(false).when(mInternetDialogController).activeNetworkIsCellular();
        when(mWifiEntry.getTitle()).thenReturn(WIFI_TITLE);
        when(mWifiEntry.getSummary(false)).thenReturn(WIFI_SUMMARY);
        when(mWifiEntry.getConnectedState()).thenReturn(WifiEntry.CONNECTED_STATE_CONNECTED);
        when(mInternetDialogController.getConnectedWifiEntry()).thenReturn(mWifiEntry);
        mInternetDialog.updateDialog();
        final LinearLayout linearLayout = mInternetDialog.mDialogView.requireViewById(
                R.id.wifi_connected_layout);

        assertThat(linearLayout.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void updateDialog_withWifiOnAndNoConnectedWifi_connectedWifiLayoutGone() {
        doReturn(false).when(mInternetDialogController).activeNetworkIsCellular();
        mInternetDialog.updateDialog();
        final LinearLayout linearLayout = mInternetDialog.mDialogView.requireViewById(
                R.id.wifi_connected_layout);

        assertThat(linearLayout.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void updateDialog_withWifiOff_WifiRecycleViewGone() {
        when(mMockWifiManager.isWifiEnabled()).thenReturn(false);
        mInternetDialog.updateDialog();
        final RecyclerView view = mInternetDialog.mDialogView.requireViewById(
                R.id.wifi_list_layout);

        assertThat(view.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void onClickSeeMoreButton_clickSeeMore_verifyLaunchNetworkSetting() {
        final LinearLayout seeAllLayout = mInternetDialog.mDialogView.requireViewById(
                R.id.see_all_layout);
        seeAllLayout.performClick();

        verify(mInternetDialogController).launchNetworkSetting();
    }

    private class MockInternetDialog extends InternetDialog {

        private String mMobileNetworkTitle;
        private String mMobileNetworkSummary;
        private String mConnectedWifiTitle;
        private String mConnectedWifiSummary;

        MockInternetDialog(Context context, InternetDialogFactory internetDialogFactory,
                InternetDialogController internetDialogController,
                boolean aboveStatusBar, UiEventLogger uiEventLogger, @Main Handler handler) {
            super(context, internetDialogFactory, internetDialogController, aboveStatusBar,
                    uiEventLogger, handler);
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
