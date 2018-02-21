package com.android.server.location;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;

import android.os.Looper;
import android.platform.test.annotations.Presubmit;
import android.util.NtpTrustedTime;

import com.android.server.location.NtpTimeHelper.InjectNtpTimeCallback;
import com.android.server.testing.FrameworkRobolectricTestRunner;
import com.android.server.testing.SystemLoaderPackages;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link NtpTimeHelper}.
 */
@RunWith(FrameworkRobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 27
)
@SystemLoaderPackages({"com.android.server.location"})
@Presubmit
public class NtpTimeHelperTest {

    private static final long MOCK_NTP_TIME = 1519930775453L;
    @Mock
    private NtpTrustedTime mMockNtpTrustedTime;
    private NtpTimeHelper mNtpTimeHelper;
    private CountDownLatch mCountDownLatch;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mCountDownLatch = new CountDownLatch(1);
        InjectNtpTimeCallback callback =
                (time, timeReference, uncertainty) -> {
                    assertThat(time).isEqualTo(MOCK_NTP_TIME);
                    mCountDownLatch.countDown();
                };
        mNtpTimeHelper = new NtpTimeHelper(RuntimeEnvironment.application,
                Looper.myLooper(),
                callback, mMockNtpTrustedTime);
    }

    @Test
    public void handleInjectNtpTime_cachedAgeLow_injectTime() throws InterruptedException {
        doReturn(NtpTimeHelper.NTP_INTERVAL - 1).when(mMockNtpTrustedTime).getCacheAge();
        doReturn(MOCK_NTP_TIME).when(mMockNtpTrustedTime).getCachedNtpTime();

        mNtpTimeHelper.retrieveAndInjectNtpTime();

        waitForTasksToBePostedOnHandlerAndRunThem();
        assertThat(mCountDownLatch.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    public void handleInjectNtpTime_injectTimeFailed_injectTimeDelayed()
            throws InterruptedException {
        doReturn(NtpTimeHelper.NTP_INTERVAL + 1).when(mMockNtpTrustedTime).getCacheAge();
        doReturn(false).when(mMockNtpTrustedTime).forceRefresh();

        mNtpTimeHelper.retrieveAndInjectNtpTime();
        waitForTasksToBePostedOnHandlerAndRunThem();
        assertThat(mCountDownLatch.await(2, TimeUnit.SECONDS)).isFalse();

        doReturn(true).when(mMockNtpTrustedTime).forceRefresh();
        doReturn(1L).when(mMockNtpTrustedTime).getCacheAge();
        doReturn(MOCK_NTP_TIME).when(mMockNtpTrustedTime).getCachedNtpTime();
        ShadowSystemClock.sleep(NtpTimeHelper.RETRY_INTERVAL);

        waitForTasksToBePostedOnHandlerAndRunThem();
        assertThat(mCountDownLatch.await(2, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Since a thread is created in {@link NtpTimeHelper#retrieveAndInjectNtpTime} and the task to
     * be verified is posted in the thread, we have to wait for the task to be posted and then it
     * can be run.
     */
    private void waitForTasksToBePostedOnHandlerAndRunThem() throws InterruptedException {
        mCountDownLatch.await(1, TimeUnit.SECONDS);
        ShadowLooper.runUiThreadTasks();
    }
}

