/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.am;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.admin.IDeviceAdminService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserHandle;
import android.test.AndroidTestCase;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import org.mockito.ArgumentMatchers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

@SmallTest
public class PersistentConnectionTest extends AndroidTestCase {
    private static final String TAG = "PersistentConnectionTest";

    private static class MyConnection extends PersistentConnection<IDeviceAdminService> {
        public long uptimeMillis = 12345;

        public ArrayList<Pair<Runnable, Long>> scheduledRunnables = new ArrayList<>();

        public MyConnection(String tag, Context context, Handler handler, int userId,
                ComponentName componentName, long rebindBackoffSeconds,
                double rebindBackoffIncrease, long rebindMaxBackoffSeconds,
                long resetBackoffDelay) {
            super(tag, context, handler, userId, componentName,
                    rebindBackoffSeconds, rebindBackoffIncrease, rebindMaxBackoffSeconds,
                    resetBackoffDelay);
        }

        @Override
        protected int getBindFlags() {
            return Context.BIND_FOREGROUND_SERVICE;
        }

        @Override
        protected IDeviceAdminService asInterface(IBinder binder) {
            return (IDeviceAdminService) binder;
        }

        @Override
        long injectUptimeMillis() {
            return uptimeMillis;
        }

        @Override
        void injectPostAtTime(Runnable r, long uptimeMillis) {
            scheduledRunnables.add(Pair.create(r, uptimeMillis));
        }

        @Override
        void injectRemoveCallbacks(Runnable r) {
            for (int i = scheduledRunnables.size() - 1; i >= 0; i--) {
                if (scheduledRunnables.get(i).first.equals(r)) {
                    scheduledRunnables.remove(i);
                }
            }
        }

        void elapse(long milliSeconds) {
            uptimeMillis += milliSeconds;

            // Fire the scheduled runnables.

            // Note we collect first and then run all, because sometimes a scheduled runnable
            // calls removeCallbacks.
            final ArrayList<Runnable> list = new ArrayList<>();

            for (int i = scheduledRunnables.size() - 1; i >= 0; i--) {
                if (scheduledRunnables.get(i).second <= uptimeMillis) {
                    list.add(scheduledRunnables.get(i).first);
                    scheduledRunnables.remove(i);
                }
            }

            Collections.reverse(list);
            for (Runnable r : list) {
                r.run();
            }
        }
    }

    public void testAll() {
        final Context context = mock(Context.class);
        final int userId = 11;
        final ComponentName cn = ComponentName.unflattenFromString("a.b.c/def");
        final Handler handler = new Handler(Looper.getMainLooper());

        final MyConnection conn = new MyConnection(TAG, context, handler, userId, cn,
                /* rebindBackoffSeconds= */ 5,
                /* rebindBackoffIncrease= */ 1.5,
                /* rebindMaxBackoffSeconds= */ 11,
                /* resetBackoffDelay= */ 999);

        assertFalse(conn.isBound());
        assertFalse(conn.isConnected());
        assertFalse(conn.isRebindScheduled());
        assertEquals(5000, conn.getNextBackoffMsForTest());
        assertNull(conn.getServiceBinder());

        when(context.bindServiceAsUser(any(Intent.class), any(ServiceConnection.class), anyInt(),
                any(Handler.class), any(UserHandle.class)))
                .thenReturn(true);

        // Call bind.
        conn.bind();

        assertTrue(conn.isBound());
        assertTrue(conn.shouldBeBoundForTest());
        assertFalse(conn.isConnected());
        assertFalse(conn.isRebindScheduled());
        assertNull(conn.getServiceBinder());

        assertEquals(5000, conn.getNextBackoffMsForTest());

        verify(context).bindServiceAsUser(
                ArgumentMatchers.argThat(intent -> cn.equals(intent.getComponent())),
                eq(conn.getServiceConnectionForTest()),
                eq(Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE),
                eq(handler), eq(UserHandle.of(userId)));

        // AM responds...
        conn.getServiceConnectionForTest().onServiceConnected(cn,
                new IDeviceAdminService.Stub() {});

        assertTrue(conn.isBound());
        assertTrue(conn.shouldBeBoundForTest());
        assertTrue(conn.isConnected());
        assertNotNull(conn.getServiceBinder());
        assertFalse(conn.isRebindScheduled());

        assertEquals(5000, conn.getNextBackoffMsForTest());


        // Now connected.

        // Call unbind...
        conn.unbind();
        assertFalse(conn.isBound());
        assertFalse(conn.shouldBeBoundForTest());
        assertFalse(conn.isConnected());
        assertNull(conn.getServiceBinder());
        assertFalse(conn.isRebindScheduled());

        // Caller bind again...
        conn.bind();

        assertTrue(conn.isBound());
        assertTrue(conn.shouldBeBoundForTest());
        assertFalse(conn.isConnected());
        assertFalse(conn.isRebindScheduled());
        assertNull(conn.getServiceBinder());

        assertEquals(5000, conn.getNextBackoffMsForTest());


        // Now connected again.

        // The service got killed...
        conn.getServiceConnectionForTest().onServiceDisconnected(cn);

        assertTrue(conn.isBound());
        assertTrue(conn.shouldBeBoundForTest());
        assertFalse(conn.isConnected());
        assertNull(conn.getServiceBinder());
        assertFalse(conn.isRebindScheduled());

        assertEquals(5000, conn.getNextBackoffMsForTest());

        // Connected again...
        conn.getServiceConnectionForTest().onServiceConnected(cn,
                new IDeviceAdminService.Stub() {});

        assertTrue(conn.isBound());
        assertTrue(conn.shouldBeBoundForTest());
        assertTrue(conn.isConnected());
        assertNotNull(conn.getServiceBinder());
        assertFalse(conn.isRebindScheduled());

        assertEquals(5000, conn.getNextBackoffMsForTest());


        // Then the binding is "died"...
        conn.getServiceConnectionForTest().onBindingDied(cn);

        assertFalse(conn.isBound());
        assertTrue(conn.shouldBeBoundForTest());
        assertFalse(conn.isConnected());
        assertNull(conn.getServiceBinder());
        assertTrue(conn.isRebindScheduled());

        assertEquals(7500, conn.getNextBackoffMsForTest());

        assertEquals(
                Arrays.asList(Pair.create(conn.getBindForBackoffRunnableForTest(),
                        conn.uptimeMillis + 5000)),
                conn.scheduledRunnables);

        // 5000 ms later...
        conn.elapse(5000);

        assertTrue(conn.isBound());
        assertTrue(conn.shouldBeBoundForTest());
        assertFalse(conn.isConnected());
        assertNull(conn.getServiceBinder());
        assertFalse(conn.isRebindScheduled());

        assertEquals(7500, conn.getNextBackoffMsForTest());

        // Connected.
        conn.getServiceConnectionForTest().onServiceConnected(cn,
                new IDeviceAdminService.Stub() {});

        assertTrue(conn.isBound());
        assertTrue(conn.shouldBeBoundForTest());
        assertTrue(conn.isConnected());
        assertNotNull(conn.getServiceBinder());
        assertFalse(conn.isRebindScheduled());

        assertEquals(7500, conn.getNextBackoffMsForTest());

        // Then the binding is "died"...
        conn.getServiceConnectionForTest().onBindingDied(cn);

        assertFalse(conn.isBound());
        assertTrue(conn.shouldBeBoundForTest());
        assertFalse(conn.isConnected());
        assertNull(conn.getServiceBinder());
        assertTrue(conn.isRebindScheduled());

        assertEquals(11000, conn.getNextBackoffMsForTest());

        assertEquals(
                Arrays.asList(Pair.create(conn.getBindForBackoffRunnableForTest(),
                        conn.uptimeMillis + 7500)),
                conn.scheduledRunnables);

        // Later...
        conn.elapse(7500);

        assertTrue(conn.isBound());
        assertTrue(conn.shouldBeBoundForTest());
        assertFalse(conn.isConnected());
        assertNull(conn.getServiceBinder());
        assertFalse(conn.isRebindScheduled());

        assertEquals(11000, conn.getNextBackoffMsForTest());


        // Then the binding is "died"...
        conn.getServiceConnectionForTest().onBindingDied(cn);

        assertFalse(conn.isBound());
        assertTrue(conn.shouldBeBoundForTest());
        assertFalse(conn.isConnected());
        assertNull(conn.getServiceBinder());
        assertTrue(conn.isRebindScheduled());

        assertEquals(11000, conn.getNextBackoffMsForTest());

        assertEquals(
                Arrays.asList(Pair.create(conn.getBindForBackoffRunnableForTest(),
                    conn.uptimeMillis + 11000)),
                conn.scheduledRunnables);

        // Call unbind...
        conn.unbind();
        assertFalse(conn.isBound());
        assertFalse(conn.shouldBeBoundForTest());
        assertFalse(conn.isConnected());
        assertNull(conn.getServiceBinder());
        assertFalse(conn.isRebindScheduled());

        // Call bind again... And now the backoff is reset to 5000.
        conn.bind();

        assertTrue(conn.isBound());
        assertTrue(conn.shouldBeBoundForTest());
        assertFalse(conn.isConnected());
        assertFalse(conn.isRebindScheduled());
        assertNull(conn.getServiceBinder());

        assertEquals(5000, conn.getNextBackoffMsForTest());
    }

    public void testReconnectFiresAfterUnbind() {
        final Context context = mock(Context.class);
        final int userId = 11;
        final ComponentName cn = ComponentName.unflattenFromString("a.b.c/def");
        final Handler handler = new Handler(Looper.getMainLooper());

        final MyConnection conn = new MyConnection(TAG, context, handler, userId, cn,
                /* rebindBackoffSeconds= */ 5,
                /* rebindBackoffIncrease= */ 1.5,
                /* rebindMaxBackoffSeconds= */ 11,
                /* resetBackoffDelay= */ 999);

        when(context.bindServiceAsUser(any(Intent.class), any(ServiceConnection.class), anyInt(),
                any(Handler.class), any(UserHandle.class)))
                .thenReturn(true);

        // Bind.
        conn.bind();

        assertTrue(conn.isBound());
        assertTrue(conn.shouldBeBoundForTest());
        assertFalse(conn.isRebindScheduled());

        conn.elapse(1000);

        // Service crashes.
        conn.getServiceConnectionForTest().onBindingDied(cn);

        assertFalse(conn.isBound());
        assertTrue(conn.shouldBeBoundForTest());
        assertTrue(conn.isRebindScheduled());

        assertEquals(7500, conn.getNextBackoffMsForTest());

        // Call unbind.
        conn.unbind();
        assertFalse(conn.isBound());
        assertFalse(conn.shouldBeBoundForTest());

        // Now, at this point, it's possible that the scheduled runnable had already been fired
        // before during the unbind() call, and waiting on mLock.
        // To simulate it, we just call the runnable here.
        conn.getBindForBackoffRunnableForTest().run();

        // Should still not be bound.
        assertFalse(conn.isBound());
        assertFalse(conn.shouldBeBoundForTest());
    }

    public void testResetBackoff() {
        final Context context = mock(Context.class);
        final int userId = 11;
        final ComponentName cn = ComponentName.unflattenFromString("a.b.c/def");
        final Handler handler = new Handler(Looper.getMainLooper());

        final MyConnection conn = new MyConnection(TAG, context, handler, userId, cn,
                /* rebindBackoffSeconds= */ 5,
                /* rebindBackoffIncrease= */ 1.5,
                /* rebindMaxBackoffSeconds= */ 11,
                /* resetBackoffDelay= */ 20);

        when(context.bindServiceAsUser(any(Intent.class), any(ServiceConnection.class), anyInt(),
                any(Handler.class), any(UserHandle.class)))
                .thenReturn(true);

        // Bind.
        conn.bind();

        assertTrue(conn.isBound());
        assertTrue(conn.shouldBeBoundForTest());
        assertFalse(conn.isRebindScheduled());

        conn.elapse(1000);

        // Then the binding is "died"...
        conn.getServiceConnectionForTest().onBindingDied(cn);

        assertFalse(conn.isBound());
        assertTrue(conn.shouldBeBoundForTest());
        assertFalse(conn.isConnected());
        assertNull(conn.getServiceBinder());
        assertTrue(conn.isRebindScheduled());

        assertEquals(7500, conn.getNextBackoffMsForTest());

        assertEquals(
                Arrays.asList(Pair.create(conn.getBindForBackoffRunnableForTest(),
                        conn.uptimeMillis + 5000)),
                conn.scheduledRunnables);

        // 5000 ms later...
        conn.elapse(5000);

        assertTrue(conn.isBound());
        assertTrue(conn.shouldBeBoundForTest());
        assertFalse(conn.isConnected());
        assertNull(conn.getServiceBinder());
        assertFalse(conn.isRebindScheduled());

        assertEquals(7500, conn.getNextBackoffMsForTest());

        // Connected.
        conn.getServiceConnectionForTest().onServiceConnected(cn,
                new IDeviceAdminService.Stub() {});

        assertTrue(conn.isBound());
        assertTrue(conn.shouldBeBoundForTest());
        assertTrue(conn.isConnected());
        assertNotNull(conn.getServiceBinder());
        assertFalse(conn.isRebindScheduled());

        assertEquals(7500, conn.getNextBackoffMsForTest());

        assertEquals(
                Arrays.asList(Pair.create(conn.getStableCheckRunnableForTest(),
                        conn.uptimeMillis + 20000)),
                conn.scheduledRunnables);

        conn.elapse(20000);

        assertEquals(5000, conn.getNextBackoffMsForTest());
    }
}
