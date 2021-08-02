package com.android.systemui.qs.tiles.dialog;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.view.View;
import android.widget.LinearLayout;

import androidx.test.filters.SmallTest;

import com.android.settingslib.wifi.WifiUtils;
import com.android.systemui.SysuiTestCase;
import com.android.wifitrackerlib.WifiEntry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class InternetAdapterTest extends SysuiTestCase {

    private static final String WIFI_TITLE = "Wi-Fi Title";
    private static final String WIFI_SUMMARY = "Wi-Fi Summary";

    @Mock
    private WifiEntry mWifiEntry;
    @Mock
    private InternetDialogController mInternetDialogController;
    @Mock
    private WifiUtils.InternetIconInjector mWifiIconInjector;

    private InternetAdapter mInternetAdapter;
    private InternetAdapter.InternetViewHolder mViewHolder;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mInternetAdapter = new InternetAdapter(mInternetDialogController);
        mViewHolder = mInternetAdapter.onCreateViewHolder(new LinearLayout(mContext), 0);
        when(mWifiEntry.getTitle()).thenReturn(WIFI_TITLE);
        when(mWifiEntry.getSummary(false)).thenReturn(WIFI_SUMMARY);
        when(mInternetDialogController.getWifiEntryList()).thenReturn(Arrays.asList(mWifiEntry));
        mViewHolder.mWifiIconInjector = mWifiIconInjector;
    }

    @Test
    public void getItemCount_withApmOnWifiOnNoDefaultWifi_returnFour() {
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(true);

        assertThat(mInternetAdapter.getItemCount()).isEqualTo(4);
    }

    @Test
    public void getItemCount_withApmOnWifiOnHasDefaultWifi_returnThree() {
        when(mWifiEntry.isDefaultNetwork()).thenReturn(true);
        when(mInternetDialogController.getDefaultWifiEntry()).thenReturn(mWifiEntry);
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(true);

        assertThat(mInternetAdapter.getItemCount()).isEqualTo(3);
    }

    @Test
    public void getItemCount_withApmOffWifiOnNoDefaultWifi_returnThree() {
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(false);

        assertThat(mInternetAdapter.getItemCount()).isEqualTo(3);
    }

    @Test
    public void getItemCount_withApmOffWifiOnHasDefaultWifi_returnTwo() {
        when(mWifiEntry.isDefaultNetwork()).thenReturn(true);
        when(mInternetDialogController.getDefaultWifiEntry()).thenReturn(mWifiEntry);
        when(mInternetDialogController.isAirplaneModeEnabled()).thenReturn(false);

        assertThat(mInternetAdapter.getItemCount()).isEqualTo(2);
    }

    @Test
    public void onBindViewHolder_bindWithOpenWifiNetwork_verifyView() {
        when(mWifiEntry.getSecurity()).thenReturn(WifiEntry.SECURITY_NONE);
        mInternetAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mWifiTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mWifiSummaryText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mWifiIcon.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mWifiLockedIcon.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void onBindViewHolder_bindWithSecurityWifiNetwork_verifyView() {
        when(mWifiEntry.getSecurity()).thenReturn(WifiEntry.SECURITY_PSK);
        mInternetAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mWifiTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mWifiSummaryText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mWifiIcon.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mWifiLockedIcon.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void onBindViewHolder_bindDefaultWifiNetwork_getIconWithInternet() {
        when(mWifiEntry.shouldShowXLevelIcon()).thenReturn(false);

        mInternetAdapter.onBindViewHolder(mViewHolder, 0);

        verify(mWifiIconInjector).getIcon(eq(false) /* noInternet */, anyInt());
    }

    @Test
    public void onBindViewHolder_bindNoDefaultWifiNetwork_getIconWithNoInternet() {
        when(mWifiEntry.shouldShowXLevelIcon()).thenReturn(true);

        mInternetAdapter.onBindViewHolder(mViewHolder, 0);

        verify(mWifiIconInjector).getIcon(eq(true) /* noInternet */, anyInt());
    }
}
