package android.net;

import static android.net.NetworkRecommendationProvider.EXTRA_RECOMMENDATION_RESULT;
import static android.net.NetworkRecommendationProvider.EXTRA_SEQUENCE;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IRemoteCallback;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unit test for the {@link NetworkRecommendationProvider}.
 */
public class NetworkRecommendationProviderTest extends InstrumentationTestCase {
    @Mock private IRemoteCallback mMockRemoteCallback;
    private NetworkRecProvider mRecProvider;
    private Handler mHandler;
    private INetworkRecommendationProvider mStub;
    private CountDownLatch mCountDownLatch;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        // Configuration needed to make mockito/dexcache work.
        final Context context = getInstrumentation().getTargetContext();
        System.setProperty("dexmaker.dexcache",
                context.getCacheDir().getPath());
        ClassLoader newClassLoader = getInstrumentation().getClass().getClassLoader();
        Thread.currentThread().setContextClassLoader(newClassLoader);

        MockitoAnnotations.initMocks(this);

        HandlerThread thread = new HandlerThread("NetworkRecommendationProviderTest");
        thread.start();
        mCountDownLatch = new CountDownLatch(1);
        mHandler = new Handler(thread.getLooper());
        mRecProvider = new NetworkRecProvider(mHandler, mCountDownLatch);
        mStub = INetworkRecommendationProvider.Stub.asInterface(mRecProvider.getBinder());
    }

    @MediumTest
    public void testRequestReceived() throws Exception {
        final RecommendationRequest request = new RecommendationRequest.Builder().build();
        final int sequence = 100;
        mStub.requestRecommendation(request, mMockRemoteCallback, sequence);

        // wait for onRequestRecommendation() to be called in our impl below.
        mCountDownLatch.await(200, TimeUnit.MILLISECONDS);
        NetworkRecommendationProvider.ResultCallback expectedResultCallback =
                new NetworkRecommendationProvider.ResultCallback(mMockRemoteCallback, sequence);
        assertEquals(request, mRecProvider.mCapturedRequest);
        assertEquals(expectedResultCallback, mRecProvider.mCapturedCallback);
    }

    @SmallTest
    public void testResultCallbackOnResult() throws Exception {
        final int sequence = 100;
        final NetworkRecommendationProvider.ResultCallback callback =
                new NetworkRecommendationProvider.ResultCallback(mMockRemoteCallback, sequence);

        final RecommendationResult result = new RecommendationResult(null);
        callback.onResult(result);

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        Mockito.verify(mMockRemoteCallback).sendResult(bundleCaptor.capture());
        Bundle capturedBundle = bundleCaptor.getValue();
        assertEquals(sequence, capturedBundle.getInt(EXTRA_SEQUENCE));
        assertSame(result, capturedBundle.getParcelable(EXTRA_RECOMMENDATION_RESULT));
    }

    @SmallTest
    public void testResultCallbackOnResult_runTwice_throwsException() throws Exception {
        final int sequence = 100;
        final NetworkRecommendationProvider.ResultCallback callback =
                new NetworkRecommendationProvider.ResultCallback(mMockRemoteCallback, sequence);

        final RecommendationResult result = new RecommendationResult(null);
        callback.onResult(result);

        try {
            callback.onResult(result);
            fail("Callback ran more than once.");
        } catch (IllegalStateException e) {
            // expected
        }
    }

    private static class NetworkRecProvider extends NetworkRecommendationProvider {
        private final CountDownLatch mCountDownLatch;
        RecommendationRequest mCapturedRequest;
        ResultCallback mCapturedCallback;

        NetworkRecProvider(Handler handler, CountDownLatch countDownLatch) {
            super(handler);
            mCountDownLatch = countDownLatch;
        }

        @Override
        public void onRequestRecommendation(RecommendationRequest request,
                ResultCallback callback) {
            mCapturedRequest = request;
            mCapturedCallback = callback;
            mCountDownLatch.countDown();
        }
    }
}
