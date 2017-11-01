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
 * limitations under the License
 */

package android.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.View;
import android.view.ViewGroup.OnHierarchyChangeListener;

import com.android.frameworks.coretests.R;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

/**
 * Tests for AppWidgetHostView
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class AppWidgetHostViewTest {

    @Rule
    public final ExpectedException exception = ExpectedException.none();

    private Context mContext;
    private String mPackage;
    private AppWidgetHostView mHostView;

    private ViewAddListener mViewAddListener;
    private RemoteViews mViews;

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getContext();
        mPackage = mContext.getPackageName();
        mHostView = new AppWidgetHostView(mContext);
        mHostView.setAppWidget(0, AppWidgetManager.getInstance(
                mContext).getInstalledProviders().get(0));

        mViewAddListener = new ViewAddListener();
        mHostView.setOnHierarchyChangeListener(mViewAddListener);

        mViews = new RemoteViews(mPackage, R.layout.remote_views_test);
    }

    @Test
    public void syncInflation() {
        mHostView.updateAppWidget(mViews);
        assertNotNull(mHostView.findViewById(R.id.image));
    }

    @Test
    public void asyncInflation() throws Exception {
        RunnableList executor = new RunnableList();
        mHostView.setExecutor(executor);

        mHostView.updateAppWidget(mViews);
        assertNull(mHostView.findViewById(R.id.image));

        // Task queued.
        assertEquals(1, executor.size());

        // Execute the pending task
        executor.get(0).run();
        mViewAddListener.addLatch.await();
        assertNotNull(mHostView.findViewById(R.id.image));
    }

    @Test
    public void asyncInflation_cancelled() throws Exception {
        RunnableList executor = new RunnableList();
        mHostView.setExecutor(executor);

        mHostView.updateAppWidget(mViews.clone());
        mHostView.updateAppWidget(mViews.clone());
        assertNull(mHostView.findViewById(R.id.image));

        // Tasks queued.
        assertEquals(2, executor.size());
        // First task cancelled
        assertTrue(((Future) executor.get(0)).isCancelled());

        // Execute the pending task
        executor.get(0).run();
        executor.get(1).run();
        mViewAddListener.addLatch.await();
        assertNotNull(mHostView.findViewById(R.id.image));
    }

    private static class RunnableList extends ArrayList<Runnable> implements Executor {

        @Override
        public void execute(Runnable runnable) {
            add(runnable);
        }
    }

    private class ViewAddListener implements OnHierarchyChangeListener {

        public final CountDownLatch addLatch = new CountDownLatch(1);


        @Override
        public void onChildViewAdded(View parent, View child) {
            addLatch.countDown();
        }

        @Override
        public void onChildViewRemoved(View parent, View child) {
        }
    }
}
