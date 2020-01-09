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

import static android.media.MediaRoute2Info.CONNECTION_STATE_CONNECTED;
import static android.media.MediaRoute2Info.CONNECTION_STATE_CONNECTING;
import static android.media.MediaRoute2Info.DEVICE_TYPE_SPEAKER;
import static android.media.MediaRoute2Info.DEVICE_TYPE_TV;
import static android.media.MediaRoute2Info.PLAYBACK_VOLUME_FIXED;
import static android.media.MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE;

import static com.android.mediaroutertest.MediaRouterManagerTest.ROUTE_ID1;
import static com.android.mediaroutertest.MediaRouterManagerTest.ROUTE_ID2;
import static com.android.mediaroutertest.MediaRouterManagerTest.ROUTE_ID3_SESSION_CREATION_FAILED;
import static com.android.mediaroutertest.MediaRouterManagerTest.ROUTE_ID4_TO_SELECT_AND_DESELECT;
import static com.android.mediaroutertest.MediaRouterManagerTest.ROUTE_ID5_TO_TRANSFER_TO;
import static com.android.mediaroutertest.MediaRouterManagerTest.ROUTE_ID_SPECIAL_TYPE;
import static com.android.mediaroutertest.MediaRouterManagerTest.ROUTE_ID_VARIABLE_VOLUME;
import static com.android.mediaroutertest.MediaRouterManagerTest.SYSTEM_PROVIDER_ID;
import static com.android.mediaroutertest.MediaRouterManagerTest.TYPES_ALL;
import static com.android.mediaroutertest.MediaRouterManagerTest.TYPES_SPECIAL;
import static com.android.mediaroutertest.MediaRouterManagerTest.TYPE_SAMPLE;
import static com.android.mediaroutertest.MediaRouterManagerTest.TYPE_SPECIAL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.testng.Assert.assertThrows;

import android.annotation.NonNull;
import android.content.Context;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2;
import android.media.MediaRouter2.RouteCallback;
import android.media.MediaRouter2.RouteSessionController;
import android.media.MediaRouter2.SessionCallback;
import android.media.RouteDiscoveryRequest;
import android.media.RouteSessionInfo;
import android.net.Uri;
import android.os.Parcel;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.text.TextUtils;

import org.junit.After;
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
public class MediaRouter2Test {
    private static final String TAG = "MediaRouter2Test";
    Context mContext;
    private MediaRouter2 mRouter2;
    private Executor mExecutor;

    private static final int TIMEOUT_MS = 5000;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mRouter2 = MediaRouter2.getInstance(mContext);
        mExecutor = Executors.newSingleThreadExecutor();
    }

    @After
    public void tearDown() throws Exception {
    }

    /**
     * Tests if we get proper routes for application that has special route type.
     */
    @Test
    public void testGetRoutes() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(TYPES_SPECIAL);

        assertEquals(1, routes.size());
        assertNotNull(routes.get(ROUTE_ID_SPECIAL_TYPE));
    }

    @Test
    public void testRouteInfoEquality() {
        MediaRoute2Info routeInfo = new MediaRoute2Info.Builder("id", "name")
                .setDescription("description")
                .setClientPackageName("com.android.mediaroutertest")
                .setConnectionState(CONNECTION_STATE_CONNECTING)
                .setIconUri(new Uri.Builder().path("icon").build())
                .setVolume(5)
                .setVolumeMax(20)
                .addRouteType(TYPE_SAMPLE)
                .setVolumeHandling(PLAYBACK_VOLUME_VARIABLE)
                .setDeviceType(DEVICE_TYPE_SPEAKER)
                .build();

        MediaRoute2Info routeInfoRebuilt = new MediaRoute2Info.Builder(routeInfo).build();
        assertEquals(routeInfo, routeInfoRebuilt);

        Parcel parcel = Parcel.obtain();
        parcel.writeParcelable(routeInfo, 0);
        parcel.setDataPosition(0);
        MediaRoute2Info routeInfoFromParcel = parcel.readParcelable(null);

        assertEquals(routeInfo, routeInfoFromParcel);
    }

    @Test
    public void testRouteInfoInequality() {
        MediaRoute2Info route = new MediaRoute2Info.Builder("id", "name")
                .setDescription("description")
                .setClientPackageName("com.android.mediaroutertest")
                .setConnectionState(CONNECTION_STATE_CONNECTING)
                .setIconUri(new Uri.Builder().path("icon").build())
                .addRouteType(TYPE_SAMPLE)
                .setVolume(5)
                .setVolumeMax(20)
                .setVolumeHandling(PLAYBACK_VOLUME_VARIABLE)
                .setDeviceType(DEVICE_TYPE_SPEAKER)
                .build();

        MediaRoute2Info routeId = new MediaRoute2Info.Builder(route)
                .setId("another id").build();
        assertNotEquals(route, routeId);

        MediaRoute2Info routeName = new MediaRoute2Info.Builder(route)
                .setName("another name").build();
        assertNotEquals(route, routeName);

        MediaRoute2Info routeDescription = new MediaRoute2Info.Builder(route)
                .setDescription("another description").build();
        assertNotEquals(route, routeDescription);

        MediaRoute2Info routeConnectionState = new MediaRoute2Info.Builder(route)
                .setConnectionState(CONNECTION_STATE_CONNECTED).build();
        assertNotEquals(route, routeConnectionState);

        MediaRoute2Info routeIcon = new MediaRoute2Info.Builder(route)
                .setIconUri(new Uri.Builder().path("new icon").build()).build();
        assertNotEquals(route, routeIcon);

        MediaRoute2Info routeClient = new MediaRoute2Info.Builder(route)
                .setClientPackageName("another.client.package").build();
        assertNotEquals(route, routeClient);

        MediaRoute2Info routeType = new MediaRoute2Info.Builder(route)
                .addRouteType(TYPE_SPECIAL).build();
        assertNotEquals(route, routeType);

        MediaRoute2Info routeVolume = new MediaRoute2Info.Builder(route)
                .setVolume(10).build();
        assertNotEquals(route, routeVolume);

        MediaRoute2Info routeVolumeMax = new MediaRoute2Info.Builder(route)
                .setVolumeMax(30).build();
        assertNotEquals(route, routeVolumeMax);

        MediaRoute2Info routeVolumeHandling = new MediaRoute2Info.Builder(route)
                .setVolumeHandling(PLAYBACK_VOLUME_FIXED).build();
        assertNotEquals(route, routeVolumeHandling);

        MediaRoute2Info routeDeviceType = new MediaRoute2Info.Builder(route)
                .setVolume(DEVICE_TYPE_TV).build();
        assertNotEquals(route, routeDeviceType);
    }

    @Test
    public void testControlVolumeWithRouter() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(TYPES_ALL);

        MediaRoute2Info volRoute = routes.get(ROUTE_ID_VARIABLE_VOLUME);
        assertNotNull(volRoute);

        int originalVolume = volRoute.getVolume();
        int deltaVolume = (originalVolume == volRoute.getVolumeMax() ? -1 : 1);

        awaitOnRouteChanged(
                () -> mRouter2.requestUpdateVolume(volRoute, deltaVolume),
                ROUTE_ID_VARIABLE_VOLUME,
                (route -> route.getVolume() == originalVolume + deltaVolume),
                TYPES_ALL);

        awaitOnRouteChanged(
                () -> mRouter2.requestSetVolume(volRoute, originalVolume),
                ROUTE_ID_VARIABLE_VOLUME,
                (route -> route.getVolume() == originalVolume),
                TYPES_ALL);
    }

    @Test
    public void testRegisterSessionCallbackWithInvalidArguments() {
        Executor executor = mExecutor;
        SessionCallback callback = new SessionCallback();

        // Tests null executor
        assertThrows(NullPointerException.class,
                () -> mRouter2.registerSessionCallback(null, callback));

        // Tests null callback
        assertThrows(NullPointerException.class,
                () -> mRouter2.registerSessionCallback(executor, null));
    }

    @Test
    public void testUnregisterSessionCallbackWithNullCallback() {
        // Tests null callback
        assertThrows(NullPointerException.class,
                () -> mRouter2.unregisterSessionCallback(null));
    }

    @Test
    public void testRequestCreateSessionWithInvalidArguments() {
        MediaRoute2Info route = new MediaRoute2Info.Builder("id", "name").build();
        String routeType = "routeType";

        // Tests null route
        assertThrows(NullPointerException.class,
                () -> mRouter2.requestCreateSession(null, routeType));

        // Tests null or empty route type
        assertThrows(IllegalArgumentException.class,
                () -> mRouter2.requestCreateSession(route, null));
        assertThrows(IllegalArgumentException.class,
                () -> mRouter2.requestCreateSession(route, ""));
    }

    @Test
    public void testRequestCreateSessionSuccess() throws Exception {
        final List<String> sampleRouteType = new ArrayList<>();
        sampleRouteType.add(TYPE_SAMPLE);

        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(sampleRouteType);
        MediaRoute2Info route = routes.get(ROUTE_ID1);
        assertNotNull(route);

        final CountDownLatch successLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        final List<RouteSessionController> controllers = new ArrayList<>();

        // Create session with this route
        SessionCallback sessionCallback = new SessionCallback() {
            @Override
            public void onSessionCreated(RouteSessionController controller) {
                assertNotNull(controller);
                assertTrue(createRouteMap(controller.getSelectedRoutes()).containsKey(ROUTE_ID1));
                assertTrue(TextUtils.equals(TYPE_SAMPLE, controller.getRouteType()));
                controllers.add(controller);
                successLatch.countDown();
            }

            @Override
            public void onSessionCreationFailed(MediaRoute2Info requestedRoute,
                    String requestedRouteType) {
                failureLatch.countDown();
            }
        };

        // TODO: Remove this once the MediaRouter2 becomes always connected to the service.
        RouteCallback routeCallback = new RouteCallback();
        mRouter2.registerRouteCallback(mExecutor, routeCallback, RouteDiscoveryRequest.EMPTY);

        try {
            mRouter2.registerSessionCallback(mExecutor, sessionCallback);
            mRouter2.requestCreateSession(route, TYPE_SAMPLE);
            assertTrue(successLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            // onSessionCreationFailed should not be called.
            assertFalse(failureLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            releaseControllers(controllers);
            mRouter2.unregisterRouteCallback(routeCallback);
            mRouter2.unregisterSessionCallback(sessionCallback);
        }
    }

    @Test
    public void testRequestCreateSessionFailure() throws Exception {
        final List<String> sampleRouteType = new ArrayList<>();
        sampleRouteType.add(TYPE_SAMPLE);

        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(sampleRouteType);
        MediaRoute2Info route = routes.get(ROUTE_ID3_SESSION_CREATION_FAILED);
        assertNotNull(route);

        final CountDownLatch successLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        final List<RouteSessionController> controllers = new ArrayList<>();

        // Create session with this route
        SessionCallback sessionCallback = new SessionCallback() {
            @Override
            public void onSessionCreated(RouteSessionController controller) {
                controllers.add(controller);
                successLatch.countDown();
            }

            @Override
            public void onSessionCreationFailed(MediaRoute2Info requestedRoute,
                    String requestedRouteType) {
                assertEquals(route, requestedRoute);
                assertTrue(TextUtils.equals(TYPE_SAMPLE, requestedRouteType));
                failureLatch.countDown();
            }
        };

        // TODO: Remove this once the MediaRouter2 becomes always connected to the service.
        RouteCallback routeCallback = new RouteCallback();
        mRouter2.registerRouteCallback(mExecutor, routeCallback, RouteDiscoveryRequest.EMPTY);

        try {
            mRouter2.registerSessionCallback(mExecutor, sessionCallback);
            mRouter2.requestCreateSession(route, TYPE_SAMPLE);
            assertTrue(failureLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            // onSessionCreated should not be called.
            assertFalse(successLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            releaseControllers(controllers);
            mRouter2.unregisterRouteCallback(routeCallback);
            mRouter2.unregisterSessionCallback(sessionCallback);
        }
    }

    @Test
    public void testRequestCreateSessionMultipleSessions() throws Exception {
        final List<String> sampleRouteType = new ArrayList<>();
        sampleRouteType.add(TYPE_SAMPLE);

        final CountDownLatch successLatch = new CountDownLatch(2);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        final List<RouteSessionController> createdControllers = new ArrayList<>();

        // Create session with this route
        SessionCallback sessionCallback = new SessionCallback() {
            @Override
            public void onSessionCreated(RouteSessionController controller) {
                createdControllers.add(controller);
                successLatch.countDown();
            }

            @Override
            public void onSessionCreationFailed(MediaRoute2Info requestedRoute,
                    String requestedRouteType) {
                failureLatch.countDown();
            }
        };

        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(sampleRouteType);
        MediaRoute2Info route1 = routes.get(ROUTE_ID1);
        MediaRoute2Info route2 = routes.get(ROUTE_ID2);
        assertNotNull(route1);
        assertNotNull(route2);

        // TODO: Remove this once the MediaRouter2 becomes always connected to the service.
        RouteCallback routeCallback = new RouteCallback();
        mRouter2.registerRouteCallback(mExecutor, routeCallback, RouteDiscoveryRequest.EMPTY);

        try {
            mRouter2.registerSessionCallback(mExecutor, sessionCallback);
            mRouter2.requestCreateSession(route1, TYPE_SAMPLE);
            mRouter2.requestCreateSession(route2, TYPE_SAMPLE);
            assertTrue(successLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            // onSessionCreationFailed should not be called.
            assertFalse(failureLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            // Created controllers should have proper info
            assertEquals(2, createdControllers.size());
            RouteSessionController controller1 = createdControllers.get(0);
            RouteSessionController controller2 = createdControllers.get(1);

            assertNotEquals(controller1.getSessionId(), controller2.getSessionId());
            assertTrue(createRouteMap(controller1.getSelectedRoutes()).containsKey(ROUTE_ID1));
            assertTrue(createRouteMap(controller2.getSelectedRoutes()).containsKey(ROUTE_ID2));
            assertTrue(TextUtils.equals(TYPE_SAMPLE, controller1.getRouteType()));
            assertTrue(TextUtils.equals(TYPE_SAMPLE, controller2.getRouteType()));
        } finally {
            releaseControllers(createdControllers);
            mRouter2.unregisterRouteCallback(routeCallback);
            mRouter2.unregisterSessionCallback(sessionCallback);
        }
    }

    @Test
    public void testSessionCallbackIsNotCalledAfterUnregistered() throws Exception {
        final List<String> sampleRouteType = new ArrayList<>();
        sampleRouteType.add(TYPE_SAMPLE);

        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(sampleRouteType);
        MediaRoute2Info route = routes.get(ROUTE_ID1);
        assertNotNull(route);

        final CountDownLatch successLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        final List<RouteSessionController> controllers = new ArrayList<>();

        // Create session with this route
        SessionCallback sessionCallback = new SessionCallback() {
            @Override
            public void onSessionCreated(RouteSessionController controller) {
                controllers.add(controller);
                successLatch.countDown();
            }

            @Override
            public void onSessionCreationFailed(MediaRoute2Info requestedRoute,
                    String requestedRouteType) {
                failureLatch.countDown();
            }
        };

        // TODO: Remove this once the MediaRouter2 becomes always connected to the service.
        RouteCallback routeCallback = new RouteCallback();
        mRouter2.registerRouteCallback(mExecutor, routeCallback, RouteDiscoveryRequest.EMPTY);

        try {
            mRouter2.registerSessionCallback(mExecutor, sessionCallback);
            mRouter2.requestCreateSession(route, TYPE_SAMPLE);

            // Unregisters session callback
            mRouter2.unregisterSessionCallback(sessionCallback);

            // No session callback methods should be called.
            assertFalse(successLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertFalse(failureLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            releaseControllers(controllers);
            mRouter2.unregisterRouteCallback(routeCallback);
            mRouter2.unregisterSessionCallback(sessionCallback);
        }
    }

    // TODO: Add tests for illegal inputs if needed (e.g. selecting already selected route)
    @Test
    public void testRouteSessionControllerSelectAndDeselectRoute() throws Exception {
        final List<String> sampleRouteType = new ArrayList<>();
        sampleRouteType.add(TYPE_SAMPLE);

        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(sampleRouteType);
        MediaRoute2Info routeToCreateSessionWith = routes.get(ROUTE_ID1);
        assertNotNull(routeToCreateSessionWith);

        final CountDownLatch onSessionCreatedLatch = new CountDownLatch(1);
        final CountDownLatch onSessionInfoChangedLatchForSelect = new CountDownLatch(1);
        final CountDownLatch onSessionInfoChangedLatchForDeselect = new CountDownLatch(1);
        final List<RouteSessionController> controllers = new ArrayList<>();

        // Create session with ROUTE_ID1
        SessionCallback sessionCallback = new SessionCallback() {
            @Override
            public void onSessionCreated(RouteSessionController controller) {
                assertNotNull(controller);
                assertTrue(getRouteIds(controller.getSelectedRoutes()).contains(ROUTE_ID1));
                assertTrue(TextUtils.equals(TYPE_SAMPLE, controller.getRouteType()));
                controllers.add(controller);
                onSessionCreatedLatch.countDown();
            }

            @Override
            public void onSessionInfoChanged(RouteSessionController controller,
                    RouteSessionInfo oldInfo, RouteSessionInfo newInfo) {
                if (onSessionCreatedLatch.getCount() != 0
                        || controllers.get(0).getSessionId() != controller.getSessionId()) {
                    return;
                }

                if (onSessionInfoChangedLatchForSelect.getCount() != 0) {
                    // Check oldInfo
                    assertEquals(controller.getSessionId(), oldInfo.getSessionId());
                    assertEquals(1, oldInfo.getSelectedRoutes().size());
                    assertTrue(oldInfo.getSelectedRoutes().contains(ROUTE_ID1));
                    assertTrue(oldInfo.getSelectableRoutes().contains(
                            ROUTE_ID4_TO_SELECT_AND_DESELECT));

                    // Check newInfo
                    assertEquals(controller.getSessionId(), newInfo.getSessionId());
                    assertEquals(2, newInfo.getSelectedRoutes().size());
                    assertTrue(newInfo.getSelectedRoutes().contains(ROUTE_ID1));
                    assertTrue(newInfo.getSelectedRoutes().contains(
                            ROUTE_ID4_TO_SELECT_AND_DESELECT));
                    assertFalse(newInfo.getSelectableRoutes().contains(
                            ROUTE_ID4_TO_SELECT_AND_DESELECT));

                    onSessionInfoChangedLatchForSelect.countDown();
                } else {
                    // Check newInfo
                    assertEquals(controller.getSessionId(), newInfo.getSessionId());
                    assertEquals(1, newInfo.getSelectedRoutes().size());
                    assertTrue(newInfo.getSelectedRoutes().contains(ROUTE_ID1));
                    assertFalse(newInfo.getSelectedRoutes().contains(
                            ROUTE_ID4_TO_SELECT_AND_DESELECT));
                    assertTrue(newInfo.getSelectableRoutes().contains(
                            ROUTE_ID4_TO_SELECT_AND_DESELECT));

                    onSessionInfoChangedLatchForDeselect.countDown();
                }
            }
        };

        // TODO: Remove this once the MediaRouter2 becomes always connected to the service.
        RouteCallback routeCallback = new RouteCallback();
        mRouter2.registerRouteCallback(mExecutor, routeCallback, RouteDiscoveryRequest.EMPTY);

        try {
            mRouter2.registerSessionCallback(mExecutor, sessionCallback);
            mRouter2.requestCreateSession(routeToCreateSessionWith, TYPE_SAMPLE);
            assertTrue(onSessionCreatedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            assertEquals(1, controllers.size());
            RouteSessionController controller = controllers.get(0);
            assertTrue(getRouteIds(controller.getSelectableRoutes())
                    .contains(ROUTE_ID4_TO_SELECT_AND_DESELECT));

            // Select ROUTE_ID4_TO_SELECT_AND_DESELECT
            MediaRoute2Info routeToSelectAndDeselect = routes.get(
                    ROUTE_ID4_TO_SELECT_AND_DESELECT);
            assertNotNull(routeToSelectAndDeselect);

            controller.selectRoute(routeToSelectAndDeselect);
            assertTrue(onSessionInfoChangedLatchForSelect.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            controller.deselectRoute(routeToSelectAndDeselect);
            assertTrue(onSessionInfoChangedLatchForDeselect.await(
                    TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            releaseControllers(controllers);
            mRouter2.unregisterRouteCallback(routeCallback);
            mRouter2.unregisterSessionCallback(sessionCallback);
        }
    }

    @Test
    public void testRouteSessionControllerTransferToRoute() throws Exception {
        final List<String> sampleRouteType = new ArrayList<>();
        sampleRouteType.add(TYPE_SAMPLE);

        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(sampleRouteType);
        MediaRoute2Info routeToCreateSessionWith = routes.get(ROUTE_ID1);
        assertNotNull(routeToCreateSessionWith);

        final CountDownLatch onSessionCreatedLatch = new CountDownLatch(1);
        final CountDownLatch onSessionInfoChangedLatch = new CountDownLatch(1);
        final List<RouteSessionController> controllers = new ArrayList<>();

        // Create session with ROUTE_ID1
        SessionCallback sessionCallback = new SessionCallback() {
            @Override
            public void onSessionCreated(RouteSessionController controller) {
                assertNotNull(controller);
                android.util.Log.d(TAG, "selected route ids ");
                for (String routeId : getRouteIds(controller.getSelectedRoutes())) {
                    android.util.Log.d(TAG, "route id : " + routeId);
                }
                assertTrue(getRouteIds(controller.getSelectedRoutes()).contains(ROUTE_ID1));
                assertTrue(TextUtils.equals(TYPE_SAMPLE, controller.getRouteType()));
                controllers.add(controller);
                onSessionCreatedLatch.countDown();
            }

            @Override
            public void onSessionInfoChanged(RouteSessionController controller,
                    RouteSessionInfo oldInfo, RouteSessionInfo newInfo) {
                if (onSessionCreatedLatch.getCount() != 0
                        || controllers.get(0).getSessionId() != controller.getSessionId()) {
                    return;
                }

                // Check oldInfo
                assertEquals(controller.getSessionId(), oldInfo.getSessionId());
                assertEquals(1, oldInfo.getSelectedRoutes().size());
                assertTrue(oldInfo.getSelectedRoutes().contains(ROUTE_ID1));
                assertTrue(oldInfo.getTransferrableRoutes().contains(ROUTE_ID5_TO_TRANSFER_TO));

                // Check newInfo
                assertEquals(controller.getSessionId(), newInfo.getSessionId());
                assertEquals(1, newInfo.getSelectedRoutes().size());
                assertFalse(newInfo.getSelectedRoutes().contains(ROUTE_ID1));
                assertTrue(newInfo.getSelectedRoutes().contains(ROUTE_ID5_TO_TRANSFER_TO));
                assertFalse(newInfo.getTransferrableRoutes().contains(ROUTE_ID5_TO_TRANSFER_TO));

                onSessionInfoChangedLatch.countDown();
            }
        };

        // TODO: Remove this once the MediaRouter2 becomes always connected to the service.
        RouteCallback routeCallback = new RouteCallback();
        mRouter2.registerRouteCallback(mExecutor, routeCallback, RouteDiscoveryRequest.EMPTY);

        try {
            mRouter2.registerSessionCallback(mExecutor, sessionCallback);
            mRouter2.requestCreateSession(routeToCreateSessionWith, TYPE_SAMPLE);
            assertTrue(onSessionCreatedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            assertEquals(1, controllers.size());
            RouteSessionController controller = controllers.get(0);
            assertTrue(getRouteIds(controller.getTransferrableRoutes())
                    .contains(ROUTE_ID5_TO_TRANSFER_TO));

            // Transfer to ROUTE_ID5_TO_TRANSFER_TO
            MediaRoute2Info routeToTransferTo = routes.get(ROUTE_ID5_TO_TRANSFER_TO);
            assertNotNull(routeToTransferTo);

            controller.transferToRoute(routeToTransferTo);
            assertTrue(onSessionInfoChangedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            releaseControllers(controllers);
            mRouter2.unregisterRouteCallback(routeCallback);
            mRouter2.unregisterSessionCallback(sessionCallback);
        }
    }

    // TODO: Add tests for onSessionReleased() call.

    @Test
    public void testRouteSessionControllerReleaseShouldIgnoreTransferTo() throws Exception {
        final List<String> sampleRouteType = new ArrayList<>();
        sampleRouteType.add(TYPE_SAMPLE);

        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(sampleRouteType);
        MediaRoute2Info routeToCreateSessionWith = routes.get(ROUTE_ID1);
        assertNotNull(routeToCreateSessionWith);

        final CountDownLatch onSessionCreatedLatch = new CountDownLatch(1);
        final CountDownLatch onSessionInfoChangedLatch = new CountDownLatch(1);
        final List<RouteSessionController> controllers = new ArrayList<>();

        // Create session with ROUTE_ID1
        SessionCallback sessionCallback = new SessionCallback() {
            @Override
            public void onSessionCreated(RouteSessionController controller) {
                assertNotNull(controller);
                assertTrue(getRouteIds(controller.getSelectedRoutes()).contains(ROUTE_ID1));
                assertTrue(TextUtils.equals(TYPE_SAMPLE, controller.getRouteType()));
                controllers.add(controller);
                onSessionCreatedLatch.countDown();
            }

            @Override
            public void onSessionInfoChanged(RouteSessionController controller,
                    RouteSessionInfo oldInfo, RouteSessionInfo newInfo) {
                if (onSessionCreatedLatch.getCount() != 0
                        || controllers.get(0).getSessionId() != controller.getSessionId()) {
                    return;
                }
                onSessionInfoChangedLatch.countDown();
            }
        };

        // TODO: Remove this once the MediaRouter2 becomes always connected to the service.
        RouteCallback routeCallback = new RouteCallback();
        mRouter2.registerRouteCallback(mExecutor, routeCallback, RouteDiscoveryRequest.EMPTY);

        try {
            mRouter2.registerSessionCallback(mExecutor, sessionCallback);
            mRouter2.requestCreateSession(routeToCreateSessionWith, TYPE_SAMPLE);
            assertTrue(onSessionCreatedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            assertEquals(1, controllers.size());
            RouteSessionController controller = controllers.get(0);
            assertTrue(getRouteIds(controller.getTransferrableRoutes())
                    .contains(ROUTE_ID5_TO_TRANSFER_TO));

            // Release controller. Future calls should be ignored.
            controller.release();

            // Transfer to ROUTE_ID5_TO_TRANSFER_TO
            MediaRoute2Info routeToTransferTo = routes.get(ROUTE_ID5_TO_TRANSFER_TO);
            assertNotNull(routeToTransferTo);

            // This call should be ignored.
            // The onSessionInfoChanged() shouldn't be called.
            controller.transferToRoute(routeToTransferTo);
            assertFalse(onSessionInfoChangedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            releaseControllers(controllers);
            mRouter2.unregisterRouteCallback(routeCallback);
            mRouter2.unregisterSessionCallback(sessionCallback);
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

    Map<String, MediaRoute2Info> waitAndGetRoutes(List<String> routeTypes)
            throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        // A dummy callback is required to send route type info.
        RouteCallback routeCallback = new RouteCallback() {
            @Override
            public void onRoutesAdded(List<MediaRoute2Info> routes) {
                for (int i = 0; i < routes.size(); i++) {
                    //TODO: use isSystem() or similar method when it's ready
                    if (!TextUtils.equals(routes.get(i).getProviderId(), SYSTEM_PROVIDER_ID)) {
                        latch.countDown();
                    }
                }
            }
        };

        mRouter2.registerRouteCallback(mExecutor, routeCallback,
                new RouteDiscoveryRequest.Builder(routeTypes, true).build());
        try {
            latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return createRouteMap(mRouter2.getRoutes());
        } finally {
            mRouter2.unregisterRouteCallback(routeCallback);
        }
    }

    static void releaseControllers(@NonNull List<RouteSessionController> controllers) {
        for (RouteSessionController controller : controllers) {
            controller.release();
        }
    }

    /**
     * Returns a list of IDs of the given route list.
     */
    List<String> getRouteIds(@NonNull List<MediaRoute2Info> routes) {
        List<String> result = new ArrayList<>();
        for (MediaRoute2Info route : routes) {
            result.add(route.getId());
        }
        return result;
    }

    void awaitOnRouteChanged(Runnable task, String routeId,
            Predicate<MediaRoute2Info> predicate,
            List<String> routeTypes) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        RouteCallback routeCallback = new RouteCallback() {
            @Override
            public void onRoutesChanged(List<MediaRoute2Info> changed) {
                MediaRoute2Info route = createRouteMap(changed).get(routeId);
                if (route != null && predicate.test(route)) {
                    latch.countDown();
                }
            }
        };
        mRouter2.registerRouteCallback(mExecutor, routeCallback,
                new RouteDiscoveryRequest.Builder(routeTypes, true).build());
        try {
            task.run();
            assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            mRouter2.unregisterRouteCallback(routeCallback);
        }
    }
}
