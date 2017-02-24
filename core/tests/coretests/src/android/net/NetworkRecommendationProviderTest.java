package android.net;

import static android.net.NetworkRecommendationProvider.EXTRA_RECOMMENDATION_RESULT;
import static android.net.NetworkRecommendationProvider.EXTRA_SEQUENCE;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertSame;
import static junit.framework.Assert.fail;
import static junit.framework.TestCase.assertEquals;

import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;

import android.Manifest.permission;
import android.content.Context;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Unit test for the {@link NetworkRecommendationProvider}.
 */
@RunWith(AndroidJUnit4.class)
public class NetworkRecommendationProviderTest {
    @Mock private IRemoteCallback mMockRemoteCallback;
    @Mock private Context mContext;
    private NetworkRecProvider mRecProvider;
    private INetworkRecommendationProvider mStub;
    private CountDownLatch mRecRequestLatch;
    private CountDownLatch mScoreRequestLatch;
    private NetworkKey[] mTestNetworkKeys;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Executor executor = Executors.newSingleThreadExecutor();
        mRecRequestLatch = new CountDownLatch(1);
        mScoreRequestLatch = new CountDownLatch(1);
        mRecProvider = new NetworkRecProvider(mContext, executor, mRecRequestLatch,
                mScoreRequestLatch);
        mStub = INetworkRecommendationProvider.Stub.asInterface(mRecProvider.getBinder());
        mTestNetworkKeys = new NetworkKey[2];
        mTestNetworkKeys[0] = new NetworkKey(new WifiKey("\"ssid_01\"", "00:00:00:00:00:11"));
        mTestNetworkKeys[1] = new NetworkKey(new WifiKey("\"ssid_02\"", "00:00:00:00:00:22"));
    }

    @Test
    public void testRecommendationRequestReceived() throws Exception {
        final RecommendationRequest request = new RecommendationRequest.Builder().build();
        final int sequence = 100;
        mStub.requestRecommendation(request, mMockRemoteCallback, sequence);

        // wait for onRequestRecommendation() to be called in our impl below.
        mRecRequestLatch.await(200, TimeUnit.MILLISECONDS);
        NetworkRecommendationProvider.ResultCallback expectedResultCallback =
                new NetworkRecommendationProvider.ResultCallback(mMockRemoteCallback, sequence);
        assertEquals(request, mRecProvider.mCapturedRequest);
        assertEquals(expectedResultCallback, mRecProvider.mCapturedCallback);
    }

    @Test
    public void testRecommendationRequest_permissionsEnforced() throws Exception {
        final RecommendationRequest request = new RecommendationRequest.Builder().build();
        final int sequence = 100;
        Mockito.doThrow(new SecurityException())
                .when(mContext)
                .enforceCallingOrSelfPermission(eq(permission.REQUEST_NETWORK_SCORES), anyString());

        try {
            mStub.requestRecommendation(request, mMockRemoteCallback, sequence);
            fail("SecurityException expected.");
        } catch (SecurityException e) {
            // expected
        }
    }

    @Test
    public void testResultCallbackOnResult() throws Exception {
        final int sequence = 100;
        final NetworkRecommendationProvider.ResultCallback callback =
                new NetworkRecommendationProvider.ResultCallback(mMockRemoteCallback, sequence);

        final RecommendationResult result = RecommendationResult.createDoNotConnectRecommendation();
        callback.onResult(result);

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        Mockito.verify(mMockRemoteCallback).sendResult(bundleCaptor.capture());
        Bundle capturedBundle = bundleCaptor.getValue();
        assertEquals(sequence, capturedBundle.getInt(EXTRA_SEQUENCE));
        assertSame(result, capturedBundle.getParcelable(EXTRA_RECOMMENDATION_RESULT));
    }

    @Test
    public void testResultCallbackOnResult_runTwice_throwsException() throws Exception {
        final int sequence = 100;
        final NetworkRecommendationProvider.ResultCallback callback =
                new NetworkRecommendationProvider.ResultCallback(mMockRemoteCallback, sequence);

        final RecommendationResult result = RecommendationResult.createDoNotConnectRecommendation();
        callback.onResult(result);

        try {
            callback.onResult(result);
            fail("Callback ran more than once.");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    public void testScoreRequestReceived() throws Exception {
        mStub.requestScores(mTestNetworkKeys);

        // wait for onRequestScores() to be called in our impl below.
        mScoreRequestLatch.await(200, TimeUnit.MILLISECONDS);

        assertSame(mTestNetworkKeys, mRecProvider.mCapturedNetworks);
    }

    @Test
    public void testScoreRequest_nullInput() throws Exception {
        mStub.requestScores(null);

        // onRequestScores() should never be called
        assertFalse(mScoreRequestLatch.await(200, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testScoreRequest_emptyInput() throws Exception {
        mStub.requestScores(new NetworkKey[0]);

        // onRequestScores() should never be called
        assertFalse(mScoreRequestLatch.await(200, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testScoreRequest_permissionsEnforced() throws Exception {
        Mockito.doThrow(new SecurityException())
                .when(mContext)
                .enforceCallingOrSelfPermission(eq(permission.REQUEST_NETWORK_SCORES), anyString());

        try {
            mStub.requestScores(mTestNetworkKeys);
            fail("SecurityException expected.");
        } catch (SecurityException e) {
            // expected
        }
    }

    private static class NetworkRecProvider extends NetworkRecommendationProvider {
        private final CountDownLatch mRecRequestLatch;
        private final CountDownLatch mScoreRequestLatch;
        RecommendationRequest mCapturedRequest;
        ResultCallback mCapturedCallback;
        NetworkKey[] mCapturedNetworks;

        NetworkRecProvider(Context context, Executor executor, CountDownLatch recRequestLatch,
            CountDownLatch networkRequestLatch) {
            super(context, executor);
            mRecRequestLatch = recRequestLatch;
            mScoreRequestLatch = networkRequestLatch;
        }

        @Override
        public void onRequestRecommendation(RecommendationRequest request,
                ResultCallback callback) {
            mCapturedRequest = request;
            mCapturedCallback = callback;
            mRecRequestLatch.countDown();
        }

        @Override
        public void onRequestScores(NetworkKey[] networks) {
            mCapturedNetworks = networks;
            mScoreRequestLatch.countDown();
        }
    }
}
