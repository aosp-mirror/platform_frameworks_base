package com.android.systemui.statusbar.policy;


import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.media.MediaRouter;
import android.media.projection.MediaProjectionInfo;
import android.media.projection.MediaProjectionManager;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.policy.CastController.Callback;

import org.junit.Before;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.junit.Test;

@SmallTest
@RunWith(AndroidTestingRunner.class)
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

        mController = new CastControllerImpl(mContext);
    }

    @Test
    public void testAddCallback(){
        Callback mockCallback = mock(Callback.class);

        mController.addCallback(mockCallback);
        verify(mockCallback,times(1)).onCastDevicesChanged();
    }

    @Test
    public void testRemoveCallback(){
        Callback mockCallback = mock(Callback.class);

        mController.addCallback(mockCallback);
        verify(mockCallback, times(1)).onCastDevicesChanged();

        mController.removeCallback(mockCallback);
        verify(mockCallback, times(1)).onCastDevicesChanged();
    }

    @Test
    public void testRemoveCallbackFromEmptyList(){
        Callback mockCallback = mock(Callback.class);

        mController.removeCallback(mockCallback);
        verify(mockCallback, never()).onCastDevicesChanged();
    }
}
