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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.Intent;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2;
import android.media.MediaRouter2Manager;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.text.TextUtils;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class MediaRouterManagerTest {
    private static final String TAG = "MediaRouterManagerTest";

    // Must be the same as SampleMediaRoute2ProviderService
    public static final String ROUTE_ID1 = "route_id1";
    public static final String ROUTE_NAME1 = "Sample Route 1";
    public static final String ROUTE_ID2 = "route_id2";
    public static final String ROUTE_NAME2 = "Sample Route 2";
    public static final String ACTION_REMOVE_ROUTE =
            "com.android.mediarouteprovider.action_remove_route";

    private static final int TIMEOUT_MS = 5000;

    private Context mContext;
    private MediaRouter2Manager mManager;
    private MediaRouter2 mRouter;
    private Executor mExecutor;
    private String mPackageName;

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
        mRouter = MediaRouter2.getInstance(mContext);
        mExecutor = new ThreadPoolExecutor(
            1, 20, 3, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>());
        mPackageName = mContext.getPackageName();
    }

    @Test
    public void testMediaRoute2Info() {
        MediaRoute2Info routeInfo1 = new MediaRoute2Info.Builder("id", "name")
                .build();
        MediaRoute2Info routeInfo2 = new MediaRoute2Info.Builder(routeInfo1).build();

        MediaRoute2Info routeInfo3 = new MediaRoute2Info.Builder(routeInfo1)
                .setClientPackageName(mPackageName).build();

        assertEquals(routeInfo1, routeInfo2);
        assertNotEquals(routeInfo1, routeInfo3);
    }

    //TODO: Test onRouteChanged when it's properly implemented.
    @Test
    public void testRouteAdded() {
        MediaRouter2Manager.Callback mockCallback = mock(MediaRouter2Manager.Callback.class);

        mManager.addCallback(mExecutor, mockCallback);

        verify(mockCallback, timeout(TIMEOUT_MS)).onRouteAdded(argThat(
                (MediaRoute2Info info) ->
                        info.getId().equals(ROUTE_ID1) && info.getName().equals(ROUTE_NAME1)));
        mManager.removeCallback(mockCallback);
    }

    //TODO: Recover this test when media router 2 is finalized.
    public void testRouteRemoved() {
        MediaRouter2Manager.Callback mockCallback = mock(MediaRouter2Manager.Callback.class);
        mManager.addCallback(mExecutor, mockCallback);

        MediaRouter2.Callback mockRouterCallback = mock(MediaRouter2.Callback.class);

        //TODO: Figure out a more proper way to test.
        // (Control requests shouldn't be used in this way.)
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                (Runnable) () -> {
                    mRouter.addCallback(TEST_CONTROL_CATEGORIES, mExecutor, mockRouterCallback);
                    mRouter.sendControlRequest(
                            new MediaRoute2Info.Builder(ROUTE_ID2, ROUTE_NAME2).build(),
                            new Intent(ACTION_REMOVE_ROUTE));
                    mRouter.removeCallback(mockRouterCallback);
                }
        );
        verify(mockCallback, timeout(TIMEOUT_MS)).onRouteRemoved(argThat(
                (MediaRoute2Info info) ->
                        info.getId().equals(ROUTE_ID2) && info.getName().equals(ROUTE_NAME2)));
        mManager.removeCallback(mockCallback);
    }

    @Test
    public void controlCategoryTest() throws Exception {
        MediaRouter2Manager.Callback mockCallback = mock(MediaRouter2Manager.Callback.class);
        mManager.addCallback(mExecutor, mockCallback);

        MediaRouter2.Callback mockRouterCallback = mock(MediaRouter2.Callback.class);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> {
                    mRouter.addCallback(TEST_CONTROL_CATEGORIES, mExecutor, mockRouterCallback);
                    mRouter.removeCallback(mockRouterCallback);
                }
        );
        verify(mockCallback, timeout(TIMEOUT_MS).atLeastOnce())
                .onControlCategoriesChanged(mPackageName, TEST_CONTROL_CATEGORIES);

        mManager.removeCallback(mockCallback);
    }

    @Test
    public void onRouteSelectedTest() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        MediaRouter2.Callback mockRouterCallback = mock(MediaRouter2.Callback.class);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> {
                    mRouter.addCallback(TEST_CONTROL_CATEGORIES, mExecutor, mockRouterCallback);
                }
        );

        MediaRouter2Manager.Callback managerCallback = new MediaRouter2Manager.Callback() {
            MediaRoute2Info mSelectedRoute = null;

            @Override
            public void onRouteAdded(MediaRoute2Info routeInfo) {
                if (mSelectedRoute == null) {
                    mSelectedRoute = routeInfo;
                    mManager.selectRoute(mPackageName, mSelectedRoute);
                }
            }

            @Override
            public void onRouteSelected(String packageName, MediaRoute2Info route) {
                if (TextUtils.equals(packageName, mPackageName)
                        && mSelectedRoute != null
                        && route != null
                        && TextUtils.equals(route.getId(), mSelectedRoute.getId())) {
                    latch.countDown();
                }
            }
        };

        mManager.addCallback(mExecutor, managerCallback);

        Assert.assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        mManager.removeCallback(managerCallback);
    }

    @Test
    /**
     * Tests selecting and unselecting routes of a single provider.
     */
    public void testSingleProviderSelect() {
        MediaRouter2Manager.Callback managerCallback = mock(MediaRouter2Manager.Callback.class);
        MediaRouter2.Callback routerCallback = mock(MediaRouter2.Callback.class);

        mManager.addCallback(mExecutor, managerCallback);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> {
                    mRouter.addCallback(TEST_CONTROL_CATEGORIES, mExecutor, routerCallback);
                }
        );
        verify(managerCallback, timeout(TIMEOUT_MS))
                .onRouteListChanged(argThat(routes -> routes.size() > 0));

        Map<String, MediaRoute2Info> routes =
                createRouteMap(mManager.getAvailableRoutes(mPackageName));

        mManager.selectRoute(mPackageName, routes.get(ROUTE_ID1));
        verify(managerCallback, timeout(TIMEOUT_MS))
                .onRouteChanged(argThat(routeInfo -> TextUtils.equals(ROUTE_ID1, routeInfo.getId())
                        && TextUtils.equals(routeInfo.getClientPackageName(), mPackageName)));

        mManager.selectRoute(mPackageName, routes.get(ROUTE_ID2));
        verify(managerCallback, timeout(TIMEOUT_MS))
                .onRouteChanged(argThat(routeInfo -> TextUtils.equals(ROUTE_ID2, routeInfo.getId())
                        && TextUtils.equals(routeInfo.getClientPackageName(), mPackageName)));

        mManager.unselectRoute(mPackageName);
        verify(managerCallback, timeout(TIMEOUT_MS))
                .onRouteChanged(argThat(routeInfo -> TextUtils.equals(ROUTE_ID2, routeInfo.getId())
                        && TextUtils.equals(routeInfo.getClientPackageName(), null)));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> {
                    mRouter.removeCallback(routerCallback);
                }
        );
        mManager.removeCallback(managerCallback);
    }

    Map<String, MediaRoute2Info> createRouteMap(List<MediaRoute2Info> routes) {
        Map<String, MediaRoute2Info> routeMap = new HashMap<>();
        for (MediaRoute2Info route : routes) {
            routeMap.put(route.getId(), route);
        }
        return routeMap;
    }
}
