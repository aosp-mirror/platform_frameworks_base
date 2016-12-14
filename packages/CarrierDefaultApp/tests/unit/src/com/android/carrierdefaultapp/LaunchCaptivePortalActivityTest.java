package com.android.carrierdefaultapp;

import android.annotation.TargetApi;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;

import com.android.internal.telephony.TelephonyIntents;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class LaunchCaptivePortalActivityTest extends
        CarrierDefaultActivityTestCase<CaptivePortalLaunchActivity> {

    @Mock
    private ConnectivityManager mCm;
    @Mock
    private NetworkInfo mNetworkInfo;
    @Mock
    private Network mNetwork;

    @Captor
    private ArgumentCaptor<Integer> mInt;
    @Captor
    private ArgumentCaptor<NetworkRequest> mNetworkReq;

    private NetworkCapabilities mNetworkCapabilities;

    public LaunchCaptivePortalActivityTest() {
        super(CaptivePortalLaunchActivity.class);
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();
        injectSystemService(ConnectivityManager.class, mCm);
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Override
    protected Intent createActivityIntent() {
        Intent intent = new Intent(getInstrumentation().getTargetContext(),
                CaptivePortalLaunchActivity.class);
        intent.putExtra(TelephonyIntents.EXTRA_REDIRECTION_URL_KEY, "url");
        return intent;
    }

    @Test
    public void testWithoutInternetConnection() throws Throwable {
        startActivity();
        TestContext.waitForMs(100);
        verify(mCm, atLeast(1)).requestNetwork(mNetworkReq.capture(), any(), mInt.capture());
        // verify network request
        assert(mNetworkReq.getValue().networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET));
        assert(mNetworkReq.getValue().networkCapabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_CELLULAR));
        assertFalse(mNetworkReq.getValue().networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED));
        assertEquals(CaptivePortalLaunchActivity.NETWORK_REQUEST_TIMEOUT_IN_MS,
                (int) mInt.getValue());
        // verify captive portal app is not launched due to unavailable network
        assertNull(getStartedActivityIntent());
        stopActivity();
    }

    @Test
    public void testWithInternetConnection() throws Throwable {
        // Mock internet connection
        mNetworkCapabilities = new NetworkCapabilities()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        doReturn(new Network[]{mNetwork}).when(mCm).getAllNetworks();
        doReturn(mNetworkCapabilities).when(mCm).getNetworkCapabilities(eq(mNetwork));
        doReturn(mNetworkInfo).when(mCm).getNetworkInfo(eq(mNetwork));
        doReturn(true).when(mNetworkInfo).isConnected();

        startActivity();
        TestContext.waitForMs(100);
        // verify there is no network request with internet connection
        verify(mCm, times(0)).requestNetwork(any(), any(), anyInt());
        // verify captive portal app is launched
        assertNotNull(getStartedActivityIntent());
        assertEquals(ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN,
                getStartedActivityIntent().getAction());
        stopActivity();
    }
}
