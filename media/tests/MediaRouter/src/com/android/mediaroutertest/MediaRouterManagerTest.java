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

import static android.media.MediaRoute2Info.PLAYBACK_VOLUME_FIXED;
import static android.media.MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class MediaRouterManagerTest {
    private static final String TAG = "MediaRouterManagerTest";

    // Must be the same as SampleMediaRoute2ProviderService
    public static final String ROUTE_ID1 = "route_id1";
    public static final String ROUTE_NAME1 = "Sample Route 1";
    public static final String ROUTE_ID2 = "route_id2";
    public static final String ROUTE_NAME2 = "Sample Route 2";

    public static final String ROUTE_ID_SPECIAL_CATEGORY = "route_special_category";
    public static final String ROUTE_NAME_SPECIAL_CATEGORY = "Special Category Route";

    public static final int VOLUME_MAX = 100;
    public static final String ROUTE_ID_FIXED_VOLUME = "route_fixed_volume";
    public static final String ROUTE_NAME_FIXED_VOLUME = "Fixed Volume Route";
    public static final String ROUTE_ID_VARIABLE_VOLUME = "route_variable_volume";
    public static final String ROUTE_NAME_VARIABLE_VOLUME = "Variable Volume Route";

    public static final String ACTION_REMOVE_ROUTE =
            "com.android.mediarouteprovider.action_remove_route";

    public static final String CATEGORY_SAMPLE =
            "com.android.mediarouteprovider.CATEGORY_SAMPLE";
    public static final String CATEGORY_SPECIAL =
            "com.android.mediarouteprovider.CATEGORY_SPECIAL";

    private static final int TIMEOUT_MS = 5000;

    private Context mContext;
    private MediaRouter2Manager mManager;
    private MediaRouter2 mRouter2;
    private Executor mExecutor;
    private String mPackageName;

    private static final List<String> CONTROL_CATEGORIES_ALL = new ArrayList();
    private static final List<String> CONTROL_CATEGORIES_SPECIAL = new ArrayList();
    static {
        CONTROL_CATEGORIES_ALL.add(CATEGORY_SAMPLE);
        CONTROL_CATEGORIES_ALL.add(CATEGORY_SPECIAL);

        CONTROL_CATEGORIES_SPECIAL.add(CATEGORY_SPECIAL);
    }

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mManager = MediaRouter2Manager.getInstance(mContext);
        mRouter2 = MediaRouter2.getInstance(mContext);
        //TODO: If we need to support thread pool executors, change this to thread pool executor.
        mExecutor = Executors.newSingleThreadExecutor();
        mPackageName = mContext.getPackageName();
    }

    //TODO: Move to a separate file
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

    @Test
    public void testOnRoutesAdded() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        MediaRouter2Manager.Callback callback = new MediaRouter2Manager.Callback() {
            @Override
            public void onRoutesAdded(List<MediaRoute2Info> routes) {
                assertTrue(routes.size() > 0);
                for (MediaRoute2Info route : routes) {
                    if (route.getId().equals(ROUTE_ID1) && route.getName().equals(ROUTE_NAME1)) {
                        latch.countDown();
                    }
                }
            }
        };
        mManager.registerCallback(mExecutor, callback);

        assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        mManager.unregisterCallback(callback);
    }

    @Test
    public void testOnRoutesRemoved() throws Exception {
        MediaRouter2Manager.Callback mockCallback = mock(MediaRouter2Manager.Callback.class);
        mManager.registerCallback(mExecutor, mockCallback);

        MediaRouter2.Callback routerCallback = new MediaRouter2.Callback();
        mRouter2.registerCallback(mExecutor, routerCallback);

        Map<String, MediaRoute2Info> routes =
                waitAndGetRoutesWithManager(CONTROL_CATEGORIES_ALL);

        CountDownLatch latch = new CountDownLatch(1);
        MediaRouter2Manager.Callback callback = new MediaRouter2Manager.Callback() {
            @Override
            public void onRoutesRemoved(List<MediaRoute2Info> routes) {
                assertTrue(routes.size() > 0);
                for (MediaRoute2Info route : routes) {
                    if (route.getId().equals(ROUTE_ID2) && route.getName().equals(ROUTE_NAME2)) {
                        latch.countDown();
                    }
                }
            }
        };
        mManager.registerCallback(mExecutor, callback);

        //TODO: Figure out a more proper way to test.
        // (Control requests shouldn't be used in this way.)
        mRouter2.sendControlRequest(routes.get(ROUTE_ID2), new Intent(ACTION_REMOVE_ROUTE));
        assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        mRouter2.unregisterCallback(routerCallback);
        mManager.unregisterCallback(mockCallback);
    }

    /**
     * Tests if we get proper routes for application that has special control category.
     */
    @Test
    public void testControlCategory() throws Exception {
        MediaRouter2Manager.Callback mockCallback = mock(MediaRouter2Manager.Callback.class);
        mManager.registerCallback(mExecutor, mockCallback);

        Map<String, MediaRoute2Info> routes =
                waitAndGetRoutesWithManager(CONTROL_CATEGORIES_SPECIAL);

        Assert.assertEquals(1, routes.size());
        Assert.assertNotNull(routes.get(ROUTE_ID_SPECIAL_CATEGORY));

        mManager.unregisterCallback(mockCallback);
    }

    /**
     * Tests if we get proper routes for application that has special control category.
     */
    @Test
    public void testGetRoutes() throws Exception {
        MediaRouter2.Callback mockCallback = mock(MediaRouter2.Callback.class);
        mRouter2.registerCallback(mExecutor, mockCallback);

        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(CONTROL_CATEGORIES_SPECIAL);

        Assert.assertEquals(1, routes.size());
        Assert.assertNotNull(routes.get(ROUTE_ID_SPECIAL_CATEGORY));

        mRouter2.unregisterCallback(mockCallback);
    }

    @Test
    public void testOnRouteSelected() throws Exception {
        MediaRouter2.Callback routerCallback = new MediaRouter2.Callback();
        MediaRouter2Manager.Callback managerCallback = mock(MediaRouter2Manager.Callback.class);

        mManager.registerCallback(mExecutor, managerCallback);
        mRouter2.registerCallback(mExecutor, routerCallback);

        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(CONTROL_CATEGORIES_ALL);

        MediaRoute2Info routeToSelect = routes.get(ROUTE_ID1);
        assertNotNull(routeToSelect);

        mManager.selectRoute(mPackageName, routeToSelect);
        verify(managerCallback, timeout(TIMEOUT_MS))
                .onRouteSelected(eq(mPackageName),
                        argThat(route -> route != null && route.equals(routeToSelect)));

        mRouter2.unregisterCallback(routerCallback);
        mManager.unregisterCallback(managerCallback);
    }

    /**
     * Tests selecting and unselecting routes of a single provider.
     */
    @Test
    public void testSingleProviderSelect() throws Exception {
        MediaRouter2.Callback routerCallback = mock(MediaRouter2.Callback.class);

        mRouter2.registerCallback(mExecutor, routerCallback);

        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(CONTROL_CATEGORIES_ALL);

        awaitOnRouteChangedManager(
                () -> mManager.selectRoute(mPackageName, routes.get(ROUTE_ID1)),
                ROUTE_ID1,
                route -> TextUtils.equals(route.getClientPackageName(), mPackageName));

        awaitOnRouteChangedManager(
                () -> mManager.selectRoute(mPackageName, routes.get(ROUTE_ID2)),
                ROUTE_ID2,
                route -> TextUtils.equals(route.getClientPackageName(), mPackageName));

        awaitOnRouteChangedManager(
                () -> mManager.unselectRoute(mPackageName),
                ROUTE_ID2,
                route -> TextUtils.equals(route.getClientPackageName(), null));

        mRouter2.unregisterCallback(routerCallback);
    }

    @Test
    public void testControlVolumeWithRouter() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(CONTROL_CATEGORIES_ALL);

        MediaRoute2Info volRoute = routes.get(ROUTE_ID_VARIABLE_VOLUME);
        int originalVolume = volRoute.getVolume();
        int deltaVolume = (originalVolume == volRoute.getVolumeMax() ? -1 : 1);

        awaitOnRouteChanged(
                () -> mRouter2.requestUpdateVolume(volRoute, deltaVolume),
                ROUTE_ID_VARIABLE_VOLUME,
                (route -> route.getVolume() == originalVolume + deltaVolume));

        awaitOnRouteChanged(
                () -> mRouter2.requestSetVolume(volRoute, originalVolume),
                ROUTE_ID_VARIABLE_VOLUME,
                (route -> route.getVolume() == originalVolume));
    }

    @Test
    public void testControlVolumeWithManager() throws Exception {
        MediaRouter2.Callback mockCallback = mock(MediaRouter2.Callback.class);

        mRouter2.registerCallback(mExecutor, mockCallback);
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(CONTROL_CATEGORIES_ALL);

        MediaRoute2Info volRoute = routes.get(ROUTE_ID_VARIABLE_VOLUME);
        int originalVolume = volRoute.getVolume();
        int deltaVolume = (originalVolume == volRoute.getVolumeMax() ? -1 : 1);

        awaitOnRouteChangedManager(
                () -> mManager.requestUpdateVolume(volRoute, deltaVolume),
                ROUTE_ID_VARIABLE_VOLUME,
                (route -> route.getVolume() == originalVolume + deltaVolume));

        awaitOnRouteChangedManager(
                () -> mManager.requestSetVolume(volRoute, originalVolume),
                ROUTE_ID_VARIABLE_VOLUME,
                (route -> route.getVolume() == originalVolume));

        mRouter2.unregisterCallback(mockCallback);
    }

    @Test
    public void testVolumeHandling() throws Exception {
        MediaRouter2.Callback mockCallback = mock(MediaRouter2.Callback.class);
        mRouter2.registerCallback(mExecutor, mockCallback);
        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(CONTROL_CATEGORIES_ALL);

        MediaRoute2Info fixedVolumeRoute = routes.get(ROUTE_ID_FIXED_VOLUME);
        MediaRoute2Info variableVolumeRoute = routes.get(ROUTE_ID_VARIABLE_VOLUME);

        assertEquals(PLAYBACK_VOLUME_FIXED, fixedVolumeRoute.getVolumeHandling());
        assertEquals(PLAYBACK_VOLUME_VARIABLE, variableVolumeRoute.getVolumeHandling());
        assertEquals(VOLUME_MAX, variableVolumeRoute.getVolumeMax());

        mRouter2.unregisterCallback(mockCallback);
    }

    Map<String, MediaRoute2Info> waitAndGetRoutes(List<String> controlCategories) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        MediaRouter2.Callback callback = new MediaRouter2.Callback() {
            @Override
            public void onRoutesAdded(List<MediaRoute2Info> added) {
                if (added.size() > 0) latch.countDown();
            }
        };
        mRouter2.setControlCategories(controlCategories);
        mRouter2.registerCallback(mExecutor, callback);
        try {
            assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            return createRouteMap(mRouter2.getRoutes());
        } finally {
            mRouter2.unregisterCallback(callback);
        }
    }

    Map<String, MediaRoute2Info> waitAndGetRoutesWithManager(List<String> controlCategories)
            throws Exception {
        CountDownLatch latch = new CountDownLatch(2);

        // A dummy callback is required to send control category info.
        MediaRouter2.Callback routerCallback = new MediaRouter2.Callback();
        MediaRouter2Manager.Callback managerCallback = new MediaRouter2Manager.Callback() {
            @Override
            public void onRoutesAdded(List<MediaRoute2Info> routes) {
                if (routes.size() > 0) {
                    latch.countDown();
                }
            }
            @Override
            public void onControlCategoriesChanged(String packageName) {
                if (TextUtils.equals(mPackageName, packageName)) {
                    latch.countDown();
                }
            }
        };
        mManager.registerCallback(mExecutor, managerCallback);
        mRouter2.setControlCategories(controlCategories);
        mRouter2.registerCallback(mExecutor, routerCallback);
        try {
            assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            return createRouteMap(mManager.getAvailableRoutes(mPackageName));
        } finally {
            mRouter2.unregisterCallback(routerCallback);
            mManager.unregisterCallback(managerCallback);
        }
    }

    void awaitOnRouteChanged(Runnable task, String routeId,
            Predicate<MediaRoute2Info> predicate) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        MediaRouter2.Callback callback = new MediaRouter2.Callback() {
            @Override
            public void onRoutesChanged(List<MediaRoute2Info> changed) {
                MediaRoute2Info route = createRouteMap(changed).get(routeId);
                if (route != null && predicate.test(route)) {
                    latch.countDown();
                }
            }
        };
        mRouter2.registerCallback(mExecutor, callback);
        try {
            new Thread(task).start();
            assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            mRouter2.unregisterCallback(callback);
        }
    }

    void awaitOnRouteChangedManager(Runnable task, String routeId,
            Predicate<MediaRoute2Info> predicate) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        MediaRouter2Manager.Callback callback = new MediaRouter2Manager.Callback() {
            @Override
            public void onRoutesChanged(List<MediaRoute2Info> changed) {
                MediaRoute2Info route = createRouteMap(changed).get(routeId);
                if (route != null && predicate.test(route)) {
                    latch.countDown();
                }
            }
        };
        mManager.registerCallback(mExecutor, callback);
        try {
            new Thread(task).start();
            assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            mManager.unregisterCallback(callback);
        }
    }

    // Helper for getting routes easily
    static Map<String, MediaRoute2Info> createRouteMap(List<MediaRoute2Info> routes) {
        Map<String, MediaRoute2Info> routeMap = new HashMap<>();
        for (MediaRoute2Info route : routes) {
            routeMap.put(route.getId(), route);
        }
        return routeMap;
    }
}
