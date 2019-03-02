package com.android.server.am;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.IApplicationThread;
import android.app.servertransaction.ClientTransaction;
import android.os.Binder;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class ClientLifecycleManagerTests {

    @Test
    public void testScheduleAndRecycleBinderClientTransaction() throws Exception {
        ClientTransaction item = spy(ClientTransaction.obtain(mock(IApplicationThread.class),
                new Binder()));

        ClientLifecycleManager clientLifecycleManager = new ClientLifecycleManager();
        clientLifecycleManager.scheduleTransaction(item);

        verify(item, times(1)).recycle();
    }

    @Test
    public void testScheduleNoRecycleNonBinderClientTransaction() throws Exception {
        ClientTransaction item = spy(ClientTransaction.obtain(mock(IApplicationThread.Stub.class),
                new Binder()));

        ClientLifecycleManager clientLifecycleManager = new ClientLifecycleManager();
        clientLifecycleManager.scheduleTransaction(item);

        verify(item, times(0)).recycle();
    }
}
