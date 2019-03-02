package com.android.server.wm;

import static android.view.Display.DEFAULT_DISPLAY;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.IPinnedStackListener;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class PinnedStackControllerTest extends WindowTestsBase {

    @Mock private IPinnedStackListener mIPinnedStackListener;
    @Mock private IPinnedStackListener.Stub mIPinnedStackListenerStub;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        when(mIPinnedStackListener.asBinder()).thenReturn(mIPinnedStackListenerStub);
    }

    @Test
    public void setShelfHeight_shelfVisibilityChangedTriggered() throws RemoteException {
        sWm.mSupportsPictureInPicture = true;
        sWm.registerPinnedStackListener(DEFAULT_DISPLAY, mIPinnedStackListener);

        verify(mIPinnedStackListener).onImeVisibilityChanged(false, 0);
        verify(mIPinnedStackListener).onShelfVisibilityChanged(false, 0);
        verify(mIPinnedStackListener).onMovementBoundsChanged(any(), any(), any(), eq(false),
                eq(false), anyInt());
        verify(mIPinnedStackListener).onActionsChanged(any());
        verify(mIPinnedStackListener).onMinimizedStateChanged(anyBoolean());

        reset(mIPinnedStackListener);

        final int SHELF_HEIGHT = 300;

        sWm.setShelfHeight(true, SHELF_HEIGHT);
        verify(mIPinnedStackListener).onShelfVisibilityChanged(true, SHELF_HEIGHT);
        verify(mIPinnedStackListener).onMovementBoundsChanged(any(), any(), any(), eq(false),
                eq(true), anyInt());
        verify(mIPinnedStackListener, never()).onImeVisibilityChanged(anyBoolean(), anyInt());
    }
}
