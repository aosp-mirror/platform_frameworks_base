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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.Intent;
import android.media.MediaRoute2Info;
import android.media.MediaRouter;
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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

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
    private MediaRouter mRouter;
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
        mRouter = (MediaRouter) mContext.getSystemService(Context.MEDIA_ROUTER_SERVICE);
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

    //TODO: Test onRouteChanged when it's properly implemented.
    @Test
    public void testRouteAdded() {
        MediaRouter2Manager.Callback mockCallback = mock(MediaRouter2Manager.Callback.class);

        mManager.registerCallback(mExecutor, mockCallback);

        verify(mockCallback, timeout(TIMEOUT_MS)).onRouteAdded(argThat(
                (MediaRoute2Info info) ->
                        info.getId().equals(ROUTE_ID1) && info.getName().equals(ROUTE_NAME1)));
        mManager.unregisterCallback(mockCallback);
    }

    //TODO: Recover this test when media router 2 is finalized.
    public void testRouteRemoved() {
        MediaRouter2Manager.Callback mockCallback = mock(MediaRouter2Manager.Callback.class);
        mManager.registerCallback(mExecutor, mockCallback);

        MediaRouter2.Callback mockRouterCallback = mock(MediaRouter2.Callback.class);

        //TODO: Figure out a more proper way to test.
        // (Control requests shouldn't be used in this way.)
        mRouter2.setControlCategories(CONTROL_CATEGORIES_ALL);
        mRouter2.registerCallback(mExecutor, mockRouterCallback);
        mRouter2.sendControlRequest(
                new MediaRoute2Info.Builder(ROUTE_ID2, ROUTE_NAME2).build(),
                new Intent(ACTION_REMOVE_ROUTE));
        mRouter2.unregisterCallback(mockRouterCallback);

        verify(mockCallback, timeout(TIMEOUT_MS)).onRouteRemoved(argThat(
                (MediaRoute2Info info) ->
                        info.getId().equals(ROUTE_ID2) && info.getName().equals(ROUTE_NAME2)));
        mManager.unregisterCallback(mockCallback);
    }

    /**
     * Tests if we get proper routes for application that has special control category.
     */
    @Test
    public void testControlCategoryWithMediaRouter() throws Exception {
        MediaRouter2Manager.Callback mockCallback = mock(MediaRouter2Manager.Callback.class);
        mManager.registerCallback(mExecutor, mockCallback);

        MediaRouter.Callback mockRouterCallback = mock(MediaRouter.Callback.class);

        mRouter.setControlCategories(CONTROL_CATEGORIES_SPECIAL);
        mRouter.addCallback(MediaRouter.ROUTE_TYPE_USER, mockRouterCallback);

        verify(mockCallback, timeout(TIMEOUT_MS))
                .onRoutesChanged(argThat(routes -> routes.size() > 0));

        Map<String, MediaRoute2Info> routes =
                createRouteMap(mManager.getAvailableRoutes(mPackageName));

        Assert.assertEquals(1, routes.size());
        Assert.assertNotNull(routes.get(ROUTE_ID_SPECIAL_CATEGORY));

        mRouter.removeCallback(mockRouterCallback);
        mManager.unregisterCallback(mockCallback);
    }

    /**
     * Tests if we get proper routes for application that has special control category.
     */
    @Test
    public void testControlCategory() throws Exception {
        MediaRouter2Manager.Callback mockCallback = mock(MediaRouter2Manager.Callback.class);
        mManager.registerCallback(mExecutor, mockCallback);

        MediaRouter2.Callback mockRouterCallback = mock(MediaRouter2.Callback.class);

        mRouter2.setControlCategories(CONTROL_CATEGORIES_SPECIAL);
        mRouter2.registerCallback(mExecutor, mockRouterCallback);
        mRouter2.unregisterCallback(mockRouterCallback);

        verify(mockCallback, timeout(TIMEOUT_MS))
                .onRoutesChanged(argThat(routes -> routes.size() > 0));

        Map<String, MediaRoute2Info> routes =
                createRouteMap(mManager.getAvailableRoutes(mPackageName));

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

        mRouter2.setControlCategories(CONTROL_CATEGORIES_SPECIAL);
        mRouter2.registerCallback(mExecutor, mockCallback);
        verify(mockCallback, timeout(TIMEOUT_MS).atLeastOnce())
                .onRoutesChanged(argThat(routes -> routes.size() > 0));
        Map<String, MediaRoute2Info> routes = createRouteMap(mRouter2.getRoutes());
        Assert.assertEquals(1, routes.size());
        Assert.assertNotNull(routes.get(ROUTE_ID_SPECIAL_CATEGORY));

        mRouter2.unregisterCallback(mockCallback);
    }

    @Test
    public void testOnRouteSelected() throws Exception {
        MediaRouter2.Callback mockRouterCallback = mock(MediaRouter2.Callback.class);
        MediaRouter2Manager.Callback managerCallback = mock(MediaRouter2Manager.Callback.class);

        mManager.registerCallback(mExecutor, managerCallback);
        mRouter2.setControlCategories(CONTROL_CATEGORIES_ALL);
        mRouter2.registerCallback(mExecutor, mockRouterCallback);

        verify(managerCallback, timeout(TIMEOUT_MS))
                .onRoutesChanged(argThat(routes -> routes.size() > 0));

        Map<String, MediaRoute2Info> routes =
                createRouteMap(mManager.getAvailableRoutes(mPackageName));

        MediaRoute2Info routeToSelect = routes.get(ROUTE_ID1);
        mManager.selectRoute(mPackageName, routeToSelect);

        assertNotNull(routeToSelect);
        verify(managerCallback, timeout(TIMEOUT_MS))
                .onRouteAdded(argThat(route -> route.equals(routeToSelect)));

        mManager.unregisterCallback(managerCallback);
        mRouter2.unregisterCallback(mockRouterCallback);
    }

    /**
     * Tests selecting and unselecting routes of a single provider.
     */
    @Test
    public void testSingleProviderSelect() {
        MediaRouter2Manager.Callback managerCallback = mock(MediaRouter2Manager.Callback.class);
        MediaRouter2.Callback routerCallback = mock(MediaRouter2.Callback.class);

        mManager.registerCallback(mExecutor, managerCallback);
        mRouter2.setControlCategories(CONTROL_CATEGORIES_ALL);
        mRouter2.registerCallback(mExecutor, routerCallback);

        verify(managerCallback, timeout(TIMEOUT_MS))
                .onRoutesChanged(argThat(routes -> routes.size() > 0));

        Map<String, MediaRoute2Info> routes =
                createRouteMap(mManager.getAvailableRoutes(mPackageName));

        mManager.selectRoute(mPackageName, routes.get(ROUTE_ID1));
        verify(managerCallback, timeout(TIMEOUT_MS)
        )
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

        mRouter2.unregisterCallback(routerCallback);
        mManager.unregisterCallback(managerCallback);
    }

    @Test
    public void testVolumeHandling() {
        MediaRouter2.Callback mockCallback = mock(MediaRouter2.Callback.class);

        mRouter2.setControlCategories(CONTROL_CATEGORIES_ALL);
        mRouter2.registerCallback(mExecutor, mockCallback);
        verify(mockCallback, timeout(TIMEOUT_MS).atLeastOnce())
                .onRoutesChanged(argThat(routes -> routes.size() > 0));
        Map<String, MediaRoute2Info> routes = createRouteMap(mRouter2.getRoutes());

        MediaRoute2Info fixedVolumeRoute = routes.get(ROUTE_ID_FIXED_VOLUME);
        MediaRoute2Info variableVolumeRoute = routes.get(ROUTE_ID_VARIABLE_VOLUME);

        assertEquals(PLAYBACK_VOLUME_FIXED, fixedVolumeRoute.getVolumeHandling());
        assertEquals(PLAYBACK_VOLUME_VARIABLE, variableVolumeRoute.getVolumeHandling());
        assertEquals(VOLUME_MAX, variableVolumeRoute.getVolumeMax());

        mRouter2.unregisterCallback(mockCallback);
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
