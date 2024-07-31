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

package android.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IServiceConnection;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.DataSetObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.view.View;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.frameworks.coretests.R;
import com.android.internal.widget.IRemoteViewsFactory;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Tests for RemoteViewsAdapter.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class RemoteViewsAdapterTest {

    @Mock AppWidgetManager mAppWidgetManager;
    @Mock IServiceConnection mIServiceConnection;
    @Mock RemoteViewsAdapter.RemoteAdapterConnectionCallback mCallback;

    private Handler mMainHandler;
    private TestContext mContext;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mAppWidgetManager
                .bindRemoteViewsService(any(), anyInt(), any(), any(), anyInt())).thenReturn(true);
        mContext = new TestContext();
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    @Test
    public void onRemoteAdapterConnected_after_metadata_loaded() throws Throwable {
        RemoteViewsAdapter adapter = getOnUiThread(
                () -> new RemoteViewsAdapter(mContext, new DistinctIntent(), mCallback, false));
        assertFalse(adapter.isDataReady());

        assertNotNull(mContext.conn.get());

        ViewsFactory factory = new ViewsFactory(1);
        mContext.sendConnect(factory);

        waitOnHandler(mMainHandler);
        verify(mCallback, never()).onRemoteAdapterConnected();

        factory.loadingView.set(createViews("loading"));
        waitOnHandler(mContext.handler.get());
        waitOnHandler(mMainHandler);
        verify(mCallback, times(1)).onRemoteAdapterConnected();

        assertEquals((Integer) 1, getOnUiThread(adapter::getCount));

        // Service is unbound
        assertTrue(isUnboundOrScheduled());
    }

    @Test
    public void viewReplaced_after_mainView_loaded() throws Throwable {
        RemoteViewsAdapter adapter = getOnUiThread(
                () -> new RemoteViewsAdapter(mContext, new DistinctIntent(), mCallback, false));

        ViewsFactory factory = new ViewsFactory(1);
        factory.loadingView.set(createViews("loading"));
        mContext.sendConnect(factory);

        waitOnHandler(mContext.handler.get());
        waitOnHandler(mMainHandler);

        // Returned view contains the loading text
        View view = getOnUiThread(() -> adapter.getView(0, null, new FrameLayout(mContext)));
        ArrayList<View> search = new ArrayList<>();
        view.findViewsWithText(search, "loading", View.FIND_VIEWS_WITH_TEXT);
        assertEquals(1, search.size());

        // Send the final remoteViews
        factory.views[0].set(createViews("updated"));
        waitOnHandler(mContext.handler.get());
        waitOnHandler(mMainHandler);

        // Existing view got updated with new text
        search.clear();
        view.findViewsWithText(search, "loading", View.FIND_VIEWS_WITH_TEXT);
        assertTrue(search.isEmpty());
        view.findViewsWithText(search, "updated", View.FIND_VIEWS_WITH_TEXT);
        assertEquals(1, search.size());

        // Service is unbound
        assertTrue(isUnboundOrScheduled());
    }

    @Test
    public void notifyDataSetChanged_deferred() throws Throwable {
        RemoteViewsAdapter adapter = getOnUiThread(
                () -> new RemoteViewsAdapter(mContext, new DistinctIntent(), mCallback, false));

        ViewsFactory factory = new ViewsFactory(1);
        factory.loadingView.set(createViews("loading"));
        mContext.sendConnect(factory);

        waitOnHandler(mContext.handler.get());
        waitOnHandler(mMainHandler);
        assertEquals((Integer) 1, getOnUiThread(adapter::getCount));

        // Reset the loading view so that next refresh is blocked
        factory.loadingView = new LockedValue<>();
        factory.mCount = 3;
        DataSetObserver observer = mock(DataSetObserver.class);
        getOnUiThread(() -> {
            adapter.registerDataSetObserver(observer);
            adapter.notifyDataSetChanged();
            return null;
        });

        waitOnHandler(mMainHandler);
        // Still giving the old values
        verify(observer, never()).onChanged();
        assertEquals((Integer) 1, getOnUiThread(adapter::getCount));

        factory.loadingView.set(createViews("refreshed"));
        waitOnHandler(mContext.handler.get());
        waitOnHandler(mMainHandler);

        // When the service returns new data, UI is updated.
        verify(observer, times(1)).onChanged();
        assertEquals((Integer) 3, getOnUiThread(adapter::getCount));

        // Service is unbound
        assertTrue(isUnboundOrScheduled());
    }

    @Test
    public void serviceDisconnected_before_getView() throws Throwable {
        RemoteViewsAdapter adapter = getOnUiThread(
                () -> new RemoteViewsAdapter(mContext, new DistinctIntent(), mCallback, false));

        ViewsFactory factory = new ViewsFactory(1);
        factory.loadingView.set(createViews("loading"));
        mContext.sendConnect(factory);

        waitOnHandler(mContext.handler.get());
        waitOnHandler(mMainHandler);
        verify(mCallback, times(1)).onRemoteAdapterConnected();
        assertEquals((Integer) 1, getOnUiThread(adapter::getCount));

        // Unbind the service
        ServiceConnection conn = mContext.conn.get();
        getOnHandler(mContext.handler.get(), () -> {
            conn.onServiceDisconnected(null);
            return null;
        });

        // Returned view contains the loading text
        View view = getOnUiThread(() -> adapter.getView(0, null, new FrameLayout(mContext)));
        ArrayList<View> search = new ArrayList<>();
        view.findViewsWithText(search, "loading", View.FIND_VIEWS_WITH_TEXT);
        assertEquals(1, search.size());

        // Unbind is not scheduled
        assertFalse(isUnboundOrScheduled());

        mContext.sendConnect(factory);
        waitOnHandler(mContext.handler.get());
        waitOnHandler(mMainHandler);
        verify(mCallback, times(2)).onRemoteAdapterConnected();
    }

    private RemoteViews createViews(String text) {
        RemoteViews views = new RemoteViews(mContext.getPackageName(), R.layout.remote_views_text);
        views.setTextViewText(R.id.text, text);
        return views;
    }

    private <T> T getOnUiThread(Supplier<T> supplier) throws Throwable {
        return getOnHandler(mMainHandler, supplier);
    }

    private boolean isUnboundOrScheduled() throws Throwable {
        Handler handler = mContext.handler.get();
        return getOnHandler(handler, () -> mContext.boundCount == 0
                || handler.hasMessages(RemoteViewsAdapter.MSG_UNBIND_SERVICE));
    }

    private static <T> T getOnHandler(Handler handler, Supplier<T> supplier) throws Throwable {
        LockedValue<T> result = new LockedValue<>();
        handler.post(() -> result.set(supplier.get()));
        return result.get();
    }

    private class TestContext extends ContextWrapper {

        public final LockedValue<ServiceConnection> conn = new LockedValue<>();
        public final LockedValue<Handler> handler = new LockedValue<>();
        public int boundCount;

        TestContext() {
            super(InstrumentationRegistry.getContext());
        }

        @Override
        public void unbindService(ServiceConnection conn) {
            boundCount--;
        }

        @Override
        public Object getSystemService(String name) {
            if (Context.APPWIDGET_SERVICE.equals(name)) {
                return mAppWidgetManager;
            }
            return super.getSystemService(name);
        }

        @Override
        public IServiceConnection getServiceDispatcher(
                ServiceConnection conn, Handler handler, long flags) {
            this.conn.set(conn);
            this.handler.set(handler);
            boundCount++;
            return mIServiceConnection;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        public void sendConnect(ViewsFactory factory) throws Exception {
            ServiceConnection connection = conn.get();
            handler.get().post(() -> connection.onServiceConnected(null, factory.asBinder()));
        }
    }

    private static void waitOnHandler(Handler handler) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        handler.post(() -> latch.countDown());
        latch.await(20, TimeUnit.SECONDS);
    }

    private static class ViewsFactory extends IRemoteViewsFactory.Stub {

        public LockedValue<RemoteViews> loadingView = new LockedValue<>();
        public LockedValue<RemoteViews>[] views;

        private int mCount;

        ViewsFactory(int count) {
            mCount = count;
            views = new LockedValue[count];
            for (int i = 0; i < count; i++) {
                views[i] = new LockedValue<>();
            }
        }

        @Override
        public void onDataSetChanged() {}

        @Override
        public void onDataSetChangedAsync() { }

        @Override
        public void onDestroy(Intent intent) { }

        @Override
        public int getCount() throws RemoteException {
            return mCount;
        }

        @Override
        public RemoteViews getViewAt(int position) throws RemoteException {
            try {
                return views[position].get();
            } catch (Exception e) {
                throw new RemoteException(e.getMessage());
            }
        }

        @Override
        public RemoteViews getLoadingView() throws RemoteException {
            try {
                return loadingView.get();
            } catch (Exception e) {
                throw new RemoteException(e.getMessage());
            }
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }

        @Override
        public boolean isCreated() {
            return false;
        }

        @Override
        public RemoteViews.RemoteCollectionItems getRemoteCollectionItems(int capSize,
                int capBitmapSize) {
            RemoteViews.RemoteCollectionItems.Builder itemsBuilder =
                    new RemoteViews.RemoteCollectionItems.Builder();
            itemsBuilder.setHasStableIds(hasStableIds())
                    .setViewTypeCount(getViewTypeCount());
            try {
                for (int i = 0; i < mCount; i++) {
                    itemsBuilder.addItem(getItemId(i), getViewAt(i));
                }
            } catch (RemoteException e) {
                // No-op
            }

            return itemsBuilder.build();
        }
    }

    private static class DistinctIntent extends Intent {

        @Override
        public boolean filterEquals(Intent other) {
            return false;
        }
    }

    private static class LockedValue<T> {

        private final CountDownLatch mLatch = new CountDownLatch(1);
        private T mValue;

        public void set(T value) {
            mValue = value;
            mLatch.countDown();
        }

        public T get() throws Exception {
            mLatch.await(10, TimeUnit.SECONDS);
            return mValue;
        }
    }
}
