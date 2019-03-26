/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.mediaroutertest;

import static org.mockito.Mockito.after;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.media.MediaRouter;
import android.media.MediaRouter2Manager;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class MediaRouterManagerTest {
    private static final String TAG = "MediaRouterManagerTest";

    private static final int TARGET_UID = 109992;
    private static final String ROUTE_1 = "MediaRoute1";

    private static final int AWAIT_MS = 1000;
    private static final int TIMEOUT_MS = 1000;

    private Context mContext;
    private MediaRouter2Manager mManager;
    private MediaRouter mRouter;
    private Executor mExecutor;

    private static final List<String> TEST_CONTROL_CATEGORIES = new ArrayList();
    private static final String CONTROL_CATEGORY_1 = "android.media.mediarouter.MEDIA1";
    private static final String CONTROL_CATEGORY_2 = "android.media.mediarouter.MEDIA2";
    static {
        TEST_CONTROL_CATEGORIES.add(CONTROL_CATEGORY_1);
        TEST_CONTROL_CATEGORIES.add(CONTROL_CATEGORY_2);
    }

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mManager = MediaRouter2Manager.getInstance(mContext);
        mRouter = (MediaRouter) mContext.getSystemService(Context.MEDIA_ROUTER_SERVICE);
        mExecutor = new ThreadPoolExecutor(
            1, 20, 3, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>());
    }

    @Test
    public void transferTest() throws Exception {
        MediaRouter2Manager.Callback mockCallback = mock(MediaRouter2Manager.Callback.class);

        mManager.addCallback(mExecutor, mockCallback);

        verify(mockCallback, after(AWAIT_MS).never())
            .onRouteSelected(eq(TARGET_UID), any(String.class));

        mManager.selectRoute(TARGET_UID, ROUTE_1);
        verify(mockCallback, timeout(TIMEOUT_MS)).onRouteSelected(TARGET_UID, ROUTE_1);

        mManager.removeCallback(mockCallback);
    }

    @Test
    public void controlCategoryTest() throws Exception {
        final int uid = android.os.Process.myUid();

        MediaRouter2Manager.Callback mockCallback = mock(MediaRouter2Manager.Callback.class);
        mManager.addCallback(mExecutor, mockCallback);

        verify(mockCallback, after(AWAIT_MS).never()).onControlCategoriesChanged(eq(uid),
                any(List.class));

        mRouter.setControlCategories(TEST_CONTROL_CATEGORIES);
        verify(mockCallback, timeout(TIMEOUT_MS).atLeastOnce())
            .onControlCategoriesChanged(uid, TEST_CONTROL_CATEGORIES);

        mManager.removeCallback(mockCallback);
    }

}
