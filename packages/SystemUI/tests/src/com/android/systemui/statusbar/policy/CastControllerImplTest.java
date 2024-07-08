package com.android.systemui.statusbar.policy;


import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.PackageManager;
import android.media.MediaRouter;
import android.media.projection.MediaProjectionInfo;
import android.media.projection.MediaProjectionManager;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.policy.CastController.Callback;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ConcurrentModificationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class CastControllerImplTest extends SysuiTestCase {

    @Mock
    MediaRouter mMediaRouter;
    @Mock
    MediaProjectionManager mMediaProjectionManager;
    @Mock
    MediaProjectionInfo mProjection;

    private CastControllerImpl mController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext.addMockSystemService(MediaRouter.class, mMediaRouter);
        mContext.addMockSystemService(MediaProjectionManager.class, mMediaProjectionManager);
        when(mMediaProjectionManager.getActiveProjectionInfo()).thenReturn(mProjection);
        when(mProjection.getPackageName()).thenReturn("fake.package");

        mController = new CastControllerImpl(
                mContext,
                mock(PackageManager.class),
                mock(DumpManager.class));
    }

    @Test
    public void testAddCallback() {
        Callback mockCallback = mock(Callback.class);

        mController.addCallback(mockCallback);
        verify(mockCallback, times(1)).onCastDevicesChanged();
    }

    @Test
    public void testRemoveCallback() {
        Callback mockCallback = mock(Callback.class);

        mController.addCallback(mockCallback);
        verify(mockCallback, times(1)).onCastDevicesChanged();

        mController.removeCallback(mockCallback);
        verify(mockCallback, times(1)).onCastDevicesChanged();
    }

    @Test
    public void testRemoveCallbackFromEmptyList() {
        Callback mockCallback = mock(Callback.class);

        mController.removeCallback(mockCallback);
        verify(mockCallback, never()).onCastDevicesChanged();
    }

    @Test
    public void testAddCallbackRemoveCallback_concurrently() throws InterruptedException {
        int callbackCount = 20;
        int numThreads = 2 * callbackCount;
        CountDownLatch startThreadsLatch = new CountDownLatch(1);
        CountDownLatch threadsDone = new CountDownLatch(numThreads);
        Callback[] callbackList = new Callback[callbackCount];
        mController.setDiscovering(true);
        AtomicBoolean error = new AtomicBoolean(false);
        for (int cbIndex = 0; cbIndex < callbackCount; cbIndex++) {
            callbackList[cbIndex] = mock(Callback.class);
        }
        for (int i = 0; i < numThreads; i++) {
            final Callback mCallback = callbackList[i / 2];
            final boolean shouldAdd = (i % 2 == 0);
            new Thread() {
                public void run() {
                    try {
                        startThreadsLatch.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    try {
                        if (shouldAdd) {
                            mController.addCallback(mCallback);
                        } else {
                            mController.removeCallback(mCallback);
                        }
                        mController.fireOnCastDevicesChanged();
                    } catch (ConcurrentModificationException exc) {
                        error.compareAndSet(false, true);
                    } finally {
                        threadsDone.countDown();
                    }
                }
            }.start();
        }
        startThreadsLatch.countDown();
        threadsDone.await(10, TimeUnit.SECONDS);
        if (error.get()) {
            fail("Concurrent modification exception");
        }
    }

    /** Regression test for b/317700495 */
    @Test
    public void removeCallbackWhileIterating_doesntCrash() {
        final AtomicBoolean remove = new AtomicBoolean(false);
        Callback callback = new Callback() {
            @Override
            public void onCastDevicesChanged() {
                if (remove.get()) {
                    mController.removeCallback(this);
                }
            }
        };
        mController.addCallback(callback);
        // Add another callback so the iteration continues
        mController.addCallback(() -> {});
        remove.set(true);
        mController.fireOnCastDevicesChanged();
    }

    @Test
    public void hasConnectedCastDevice_connected() {
        CastDevice castDevice = new CastDevice(
                "id",
                /* name= */ null,
                /* description= */ null,
                /* state= */ CastDevice.CastState.Connected,
                /* origin= */ CastDevice.CastOrigin.MediaProjection,
                /* tag= */ null);
        mController.startCasting(castDevice);
        assertTrue(mController.hasConnectedCastDevice());
    }

    @Test
    public void hasConnectedCastDevice_notConnected() {
        CastDevice castDevice = new CastDevice(
                "id",
                /* name= */ null,
                /* description= */ null,
                /* state= */ CastDevice.CastState.Connecting,
                /* origin= */ CastDevice.CastOrigin.MediaProjection,
                /* tag= */ null);
        mController.startCasting(castDevice);
        assertTrue(mController.hasConnectedCastDevice());
    }
}
