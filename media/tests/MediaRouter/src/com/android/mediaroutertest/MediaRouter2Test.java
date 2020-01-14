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

import static android.media.MediaRoute2Info.CONNECTION_STATE_CONNECTING;
import static android.media.MediaRoute2Info.DEVICE_TYPE_REMOTE_SPEAKER;
import static android.media.MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE;

import static com.android.mediaroutertest.MediaRouterManagerTest.FEATURES_ALL;
import static com.android.mediaroutertest.MediaRouterManagerTest.FEATURES_SPECIAL;
import static com.android.mediaroutertest.MediaRouterManagerTest.FEATURE_SAMPLE;
import static com.android.mediaroutertest.MediaRouterManagerTest.ROUTE_ID1;
import static com.android.mediaroutertest.MediaRouterManagerTest.ROUTE_ID2;
import static com.android.mediaroutertest.MediaRouterManagerTest.ROUTE_ID3_SESSION_CREATION_FAILED;
import static com.android.mediaroutertest.MediaRouterManagerTest.ROUTE_ID4_TO_SELECT_AND_DESELECT;
import static com.android.mediaroutertest.MediaRouterManagerTest.ROUTE_ID5_TO_TRANSFER_TO;
import static com.android.mediaroutertest.MediaRouterManagerTest.ROUTE_ID_SPECIAL_FEATURE;
import static com.android.mediaroutertest.MediaRouterManagerTest.ROUTE_ID_VARIABLE_VOLUME;
import static com.android.mediaroutertest.MediaRouterManagerTest.SYSTEM_PROVIDER_ID;

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
import android.media.MediaRouter2.OnGetControllerHintsListener;
import android.media.MediaRouter2.RouteCallback;
import android.media.MediaRouter2.RoutingController;
import android.media.MediaRouter2.RoutingControllerCallback;
import android.media.RouteDiscoveryPreference;
import android.net.Uri;
import android.os.Bundle;
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

    private static final String TEST_KEY = "test_key";
    private static final String TEST_VALUE = "test_value";

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
        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(FEATURES_SPECIAL);

        assertEquals(1, routes.size());
        assertNotNull(routes.get(ROUTE_ID_SPECIAL_FEATURE));
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
                .addFeature(FEATURE_SAMPLE)
                .setVolumeHandling(PLAYBACK_VOLUME_VARIABLE)
                .setDeviceType(DEVICE_TYPE_REMOTE_SPEAKER)
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
    public void testControlVolumeWithRouter() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(FEATURES_ALL);

        MediaRoute2Info volRoute = routes.get(ROUTE_ID_VARIABLE_VOLUME);
        assertNotNull(volRoute);

        int originalVolume = volRoute.getVolume();
        int deltaVolume = (originalVolume == volRoute.getVolumeMax() ? -1 : 1);

        awaitOnRouteChanged(
                () -> mRouter2.requestUpdateVolume(volRoute, deltaVolume),
                ROUTE_ID_VARIABLE_VOLUME,
                (route -> route.getVolume() == originalVolume + deltaVolume),
                FEATURES_ALL);

        awaitOnRouteChanged(
                () -> mRouter2.requestSetVolume(volRoute, originalVolume),
                ROUTE_ID_VARIABLE_VOLUME,
                (route -> route.getVolume() == originalVolume),
                FEATURES_ALL);
    }

    @Test
    public void testRegisterControllerCallbackWithInvalidArguments() {
        Executor executor = mExecutor;
        RoutingControllerCallback callback = new RoutingControllerCallback();

        // Tests null executor
        assertThrows(NullPointerException.class,
                () -> mRouter2.registerControllerCallback(null, callback));

        // Tests null callback
        assertThrows(NullPointerException.class,
                () -> mRouter2.registerControllerCallback(executor, null));
    }

    @Test
    public void testUnregisterControllerCallbackWithNullCallback() {
        // Tests null callback
        assertThrows(NullPointerException.class,
                () -> mRouter2.unregisterControllerCallback(null));
    }

    @Test
    public void testRequestCreateControllerWithNullRoute() {
        assertThrows(NullPointerException.class,
                () -> mRouter2.requestCreateController(null));
    }

    @Test
    public void testRequestCreateControllerSuccess() throws Exception {
        final List<String> sampleRouteFeature = new ArrayList<>();
        sampleRouteFeature.add(FEATURE_SAMPLE);

        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(sampleRouteFeature);
        MediaRoute2Info route = routes.get(ROUTE_ID1);
        assertNotNull(route);

        final CountDownLatch successLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        final List<RoutingController> controllers = new ArrayList<>();

        // Create session with this route
        RoutingControllerCallback controllerCallback = new RoutingControllerCallback() {
            @Override
            public void onControllerCreated(RoutingController controller) {
                assertNotNull(controller);
                assertTrue(createRouteMap(controller.getSelectedRoutes()).containsKey(ROUTE_ID1));
                controllers.add(controller);
                successLatch.countDown();
            }

            @Override
            public void onControllerCreationFailed(MediaRoute2Info requestedRoute) {
                failureLatch.countDown();
            }
        };

        // TODO: Remove this once the MediaRouter2 becomes always connected to the service.
        RouteCallback routeCallback = new RouteCallback();
        mRouter2.registerRouteCallback(mExecutor, routeCallback, RouteDiscoveryPreference.EMPTY);

        try {
            mRouter2.registerControllerCallback(mExecutor, controllerCallback);
            mRouter2.requestCreateController(route);
            assertTrue(successLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            // onSessionCreationFailed should not be called.
            assertFalse(failureLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            releaseControllers(controllers);
            mRouter2.unregisterRouteCallback(routeCallback);
            mRouter2.unregisterControllerCallback(controllerCallback);
        }
    }

    @Test
    public void testRequestCreateControllerFailure() throws Exception {
        final List<String> sampleRouteType = new ArrayList<>();
        sampleRouteType.add(FEATURE_SAMPLE);

        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(sampleRouteType);
        MediaRoute2Info route = routes.get(ROUTE_ID3_SESSION_CREATION_FAILED);
        assertNotNull(route);

        final CountDownLatch successLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        final List<RoutingController> controllers = new ArrayList<>();

        // Create session with this route
        RoutingControllerCallback controllerCallback = new RoutingControllerCallback() {
            @Override
            public void onControllerCreated(RoutingController controller) {
                controllers.add(controller);
                successLatch.countDown();
            }

            @Override
            public void onControllerCreationFailed(MediaRoute2Info requestedRoute) {
                assertEquals(route, requestedRoute);
                failureLatch.countDown();
            }
        };

        // TODO: Remove this once the MediaRouter2 becomes always connected to the service.
        RouteCallback routeCallback = new RouteCallback();
        mRouter2.registerRouteCallback(mExecutor, routeCallback, RouteDiscoveryPreference.EMPTY);

        try {
            mRouter2.registerControllerCallback(mExecutor, controllerCallback);
            mRouter2.requestCreateController(route);
            assertTrue(failureLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            // onSessionCreated should not be called.
            assertFalse(successLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            releaseControllers(controllers);
            mRouter2.unregisterRouteCallback(routeCallback);
            mRouter2.unregisterControllerCallback(controllerCallback);
        }
    }

    @Test
    public void testRequestCreateControllerMultipleSessions() throws Exception {
        final List<String> sampleRouteType = new ArrayList<>();
        sampleRouteType.add(FEATURE_SAMPLE);

        final CountDownLatch successLatch = new CountDownLatch(2);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        final List<RoutingController> createdControllers = new ArrayList<>();

        // Create session with this route
        RoutingControllerCallback controllerCallback = new RoutingControllerCallback() {
            @Override
            public void onControllerCreated(RoutingController controller) {
                createdControllers.add(controller);
                successLatch.countDown();
            }

            @Override
            public void onControllerCreationFailed(MediaRoute2Info requestedRoute) {
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
        mRouter2.registerRouteCallback(mExecutor, routeCallback, RouteDiscoveryPreference.EMPTY);

        try {
            mRouter2.registerControllerCallback(mExecutor, controllerCallback);
            mRouter2.requestCreateController(route1);
            mRouter2.requestCreateController(route2);
            assertTrue(successLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            // onSessionCreationFailed should not be called.
            assertFalse(failureLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            // Created controllers should have proper info
            assertEquals(2, createdControllers.size());
            RoutingController controller1 = createdControllers.get(0);
            RoutingController controller2 = createdControllers.get(1);

            assertNotEquals(controller1.getId(), controller2.getId());
            assertTrue(createRouteMap(controller1.getSelectedRoutes()).containsKey(ROUTE_ID1));
            assertTrue(createRouteMap(controller2.getSelectedRoutes()).containsKey(ROUTE_ID2));

        } finally {
            releaseControllers(createdControllers);
            mRouter2.unregisterRouteCallback(routeCallback);
            mRouter2.unregisterControllerCallback(controllerCallback);
        }
    }

    @Test
    public void testSetOnGetControllerHintsListener() throws Exception {
        final List<String> sampleRouteFeature = new ArrayList<>();
        sampleRouteFeature.add(FEATURE_SAMPLE);

        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(sampleRouteFeature);
        MediaRoute2Info route = routes.get(ROUTE_ID1);
        assertNotNull(route);

        final Bundle createSessionHints = new Bundle();
        createSessionHints.putString(TEST_KEY, TEST_VALUE);
        final OnGetControllerHintsListener listener = new OnGetControllerHintsListener() {
            @Override
            public Bundle onGetControllerHints(MediaRoute2Info route) {
                return createSessionHints;
            }
        };

        final CountDownLatch successLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        final List<RoutingController> controllers = new ArrayList<>();

        // Create session with this route
        RoutingControllerCallback controllerCallback = new RoutingControllerCallback() {
            @Override
            public void onControllerCreated(RoutingController controller) {
                assertNotNull(controller);
                assertTrue(createRouteMap(controller.getSelectedRoutes()).containsKey(ROUTE_ID1));

                // The SampleMediaRoute2ProviderService supposed to set control hints
                // with the given creationSessionHints.
                Bundle controlHints = controller.getControlHints();
                assertNotNull(controlHints);
                assertTrue(controlHints.containsKey(TEST_KEY));
                assertEquals(TEST_VALUE, controlHints.getString(TEST_KEY));

                controllers.add(controller);
                successLatch.countDown();
            }

            @Override
            public void onControllerCreationFailed(MediaRoute2Info requestedRoute) {
                failureLatch.countDown();
            }
        };

        // TODO: Remove this once the MediaRouter2 becomes always connected to the service.
        RouteCallback routeCallback = new RouteCallback();
        mRouter2.registerRouteCallback(mExecutor, routeCallback, RouteDiscoveryPreference.EMPTY);

        try {
            mRouter2.registerControllerCallback(mExecutor, controllerCallback);

            // The SampleMediaRoute2ProviderService supposed to set control hints
            // with the given creationSessionHints.
            mRouter2.setOnGetControllerHintsListener(listener);
            mRouter2.requestCreateController(route);
            assertTrue(successLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            // onSessionCreationFailed should not be called.
            assertFalse(failureLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            releaseControllers(controllers);
            mRouter2.unregisterRouteCallback(routeCallback);
            mRouter2.unregisterControllerCallback(controllerCallback);
        }
    }

    @Test
    public void testRoutingControllerCallbackIsNotCalledAfterUnregistered() throws Exception {
        final List<String> sampleRouteType = new ArrayList<>();
        sampleRouteType.add(FEATURE_SAMPLE);

        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(sampleRouteType);
        MediaRoute2Info route = routes.get(ROUTE_ID1);
        assertNotNull(route);

        final CountDownLatch successLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        final List<RoutingController> controllers = new ArrayList<>();

        // Create session with this route
        RoutingControllerCallback controllerCallback = new RoutingControllerCallback() {
            @Override
            public void onControllerCreated(RoutingController controller) {
                controllers.add(controller);
                successLatch.countDown();
            }

            @Override
            public void onControllerCreationFailed(MediaRoute2Info requestedRoute) {
                failureLatch.countDown();
            }
        };

        // TODO: Remove this once the MediaRouter2 becomes always connected to the service.
        RouteCallback routeCallback = new RouteCallback();
        mRouter2.registerRouteCallback(mExecutor, routeCallback, RouteDiscoveryPreference.EMPTY);

        try {
            mRouter2.registerControllerCallback(mExecutor, controllerCallback);
            mRouter2.requestCreateController(route);

            // Unregisters session callback
            mRouter2.unregisterControllerCallback(controllerCallback);

            // No session callback methods should be called.
            assertFalse(successLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertFalse(failureLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            releaseControllers(controllers);
            mRouter2.unregisterRouteCallback(routeCallback);
            mRouter2.unregisterControllerCallback(controllerCallback);
        }
    }

    // TODO: Add tests for illegal inputs if needed (e.g. selecting already selected route)
    @Test
    public void testRoutingControllerSelectAndDeselectRoute() throws Exception {
        final List<String> sampleRouteType = new ArrayList<>();
        sampleRouteType.add(FEATURE_SAMPLE);

        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(sampleRouteType);
        MediaRoute2Info routeToCreateSessionWith = routes.get(ROUTE_ID1);
        assertNotNull(routeToCreateSessionWith);

        final CountDownLatch onControllerCreatedLatch = new CountDownLatch(1);
        final CountDownLatch onControllerUpdatedLatchForSelect = new CountDownLatch(1);
        final CountDownLatch onControllerUpdatedLatchForDeselect = new CountDownLatch(1);
        final List<RoutingController> controllers = new ArrayList<>();

        // Create session with ROUTE_ID1
        RoutingControllerCallback controllerCallback = new RoutingControllerCallback() {
            @Override
            public void onControllerCreated(RoutingController controller) {
                assertNotNull(controller);
                assertTrue(getRouteIds(controller.getSelectedRoutes()).contains(ROUTE_ID1));
                controllers.add(controller);
                onControllerCreatedLatch.countDown();
            }

            @Override
            public void onControllerUpdated(RoutingController controller) {
                if (onControllerCreatedLatch.getCount() != 0
                        || !TextUtils.equals(
                                controllers.get(0).getId(), controller.getId())) {
                    return;
                }

                if (onControllerUpdatedLatchForSelect.getCount() != 0) {
                    assertEquals(2, controller.getSelectedRoutes().size());
                    assertTrue(getRouteIds(controller.getSelectedRoutes())
                            .contains(ROUTE_ID1));
                    assertTrue(getRouteIds(controller.getSelectedRoutes())
                            .contains(ROUTE_ID4_TO_SELECT_AND_DESELECT));
                    assertFalse(getRouteIds(controller.getSelectableRoutes())
                            .contains(ROUTE_ID4_TO_SELECT_AND_DESELECT));

                    onControllerUpdatedLatchForSelect.countDown();
                } else {
                    assertEquals(1, controller.getSelectedRoutes().size());
                    assertTrue(getRouteIds(controller.getSelectedRoutes())
                            .contains(ROUTE_ID1));
                    assertFalse(getRouteIds(controller.getSelectedRoutes())
                            .contains(ROUTE_ID4_TO_SELECT_AND_DESELECT));
                    assertTrue(getRouteIds(controller.getSelectableRoutes())
                            .contains(ROUTE_ID4_TO_SELECT_AND_DESELECT));

                    onControllerUpdatedLatchForDeselect.countDown();
                }
            }
        };

        // TODO: Remove this once the MediaRouter2 becomes always connected to the service.
        RouteCallback routeCallback = new RouteCallback();
        mRouter2.registerRouteCallback(mExecutor, routeCallback, RouteDiscoveryPreference.EMPTY);

        try {
            mRouter2.registerControllerCallback(mExecutor, controllerCallback);
            mRouter2.requestCreateController(routeToCreateSessionWith);
            assertTrue(onControllerCreatedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            assertEquals(1, controllers.size());
            RoutingController controller = controllers.get(0);
            assertTrue(getRouteIds(controller.getSelectableRoutes())
                    .contains(ROUTE_ID4_TO_SELECT_AND_DESELECT));

            // Select ROUTE_ID4_TO_SELECT_AND_DESELECT
            MediaRoute2Info routeToSelectAndDeselect = routes.get(
                    ROUTE_ID4_TO_SELECT_AND_DESELECT);
            assertNotNull(routeToSelectAndDeselect);

            controller.selectRoute(routeToSelectAndDeselect);
            assertTrue(onControllerUpdatedLatchForSelect.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            controller.deselectRoute(routeToSelectAndDeselect);
            assertTrue(onControllerUpdatedLatchForDeselect.await(
                    TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            releaseControllers(controllers);
            mRouter2.unregisterRouteCallback(routeCallback);
            mRouter2.unregisterControllerCallback(controllerCallback);
        }
    }

    @Test
    public void testRoutingControllerTransferToRoute() throws Exception {
        final List<String> sampleRouteType = new ArrayList<>();
        sampleRouteType.add(FEATURE_SAMPLE);

        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(sampleRouteType);
        MediaRoute2Info routeToCreateSessionWith = routes.get(ROUTE_ID1);
        assertNotNull(routeToCreateSessionWith);

        final CountDownLatch onControllerCreatedLatch = new CountDownLatch(1);
        final CountDownLatch onControllerUpdatedLatch = new CountDownLatch(1);
        final List<RoutingController> controllers = new ArrayList<>();

        // Create session with ROUTE_ID1
        RoutingControllerCallback controllerCallback = new RoutingControllerCallback() {
            @Override
            public void onControllerCreated(RoutingController controller) {
                assertNotNull(controller);
                assertTrue(getRouteIds(controller.getSelectedRoutes()).contains(ROUTE_ID1));
                controllers.add(controller);
                onControllerCreatedLatch.countDown();
            }

            @Override
            public void onControllerUpdated(RoutingController controller) {
                if (onControllerCreatedLatch.getCount() != 0
                        || !TextUtils.equals(
                                controllers.get(0).getId(), controller.getId())) {
                    return;
                }
                assertEquals(1, controller.getSelectedRoutes().size());
                assertFalse(getRouteIds(controller.getSelectedRoutes()).contains(ROUTE_ID1));
                assertTrue(getRouteIds(controller.getSelectedRoutes())
                        .contains(ROUTE_ID5_TO_TRANSFER_TO));
                assertFalse(getRouteIds(controller.getTransferrableRoutes())
                        .contains(ROUTE_ID5_TO_TRANSFER_TO));

                onControllerUpdatedLatch.countDown();
            }
        };

        // TODO: Remove this once the MediaRouter2 becomes always connected to the service.
        RouteCallback routeCallback = new RouteCallback();
        mRouter2.registerRouteCallback(mExecutor, routeCallback, RouteDiscoveryPreference.EMPTY);

        try {
            mRouter2.registerControllerCallback(mExecutor, controllerCallback);
            mRouter2.requestCreateController(routeToCreateSessionWith);
            assertTrue(onControllerCreatedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            assertEquals(1, controllers.size());
            RoutingController controller = controllers.get(0);
            assertTrue(getRouteIds(controller.getTransferrableRoutes())
                    .contains(ROUTE_ID5_TO_TRANSFER_TO));

            // Transfer to ROUTE_ID5_TO_TRANSFER_TO
            MediaRoute2Info routeToTransferTo = routes.get(ROUTE_ID5_TO_TRANSFER_TO);
            assertNotNull(routeToTransferTo);

            controller.transferToRoute(routeToTransferTo);
            assertTrue(onControllerUpdatedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            releaseControllers(controllers);
            mRouter2.unregisterRouteCallback(routeCallback);
            mRouter2.unregisterControllerCallback(controllerCallback);
        }
    }

    // TODO: Add tests for onSessionReleased() when provider releases the session.

    @Test
    public void testRoutingControllerRelease() throws Exception {
        final List<String> sampleRouteType = new ArrayList<>();
        sampleRouteType.add(FEATURE_SAMPLE);

        Map<String, MediaRoute2Info> routes = waitAndGetRoutes(sampleRouteType);
        MediaRoute2Info routeToCreateSessionWith = routes.get(ROUTE_ID1);
        assertNotNull(routeToCreateSessionWith);

        final CountDownLatch onControllerCreatedLatch = new CountDownLatch(1);
        final CountDownLatch onControllerUpdatedLatch = new CountDownLatch(1);
        final CountDownLatch onControllerReleasedLatch = new CountDownLatch(1);
        final List<RoutingController> controllers = new ArrayList<>();

        // Create session with ROUTE_ID1
        RoutingControllerCallback controllerCallback = new RoutingControllerCallback() {
            @Override
            public void onControllerCreated(RoutingController controller) {
                assertNotNull(controller);
                assertTrue(getRouteIds(controller.getSelectedRoutes()).contains(ROUTE_ID1));
                controllers.add(controller);
                onControllerCreatedLatch.countDown();
            }

            @Override
            public void onControllerUpdated(RoutingController controller) {
                if (onControllerCreatedLatch.getCount() != 0
                        || !TextUtils.equals(controllers.get(0).getId(), controller.getId())) {
                    return;
                }
                onControllerUpdatedLatch.countDown();
            }

            @Override
            public void onControllerReleased(RoutingController controller) {
                if (onControllerCreatedLatch.getCount() != 0
                        || !TextUtils.equals(controllers.get(0).getId(), controller.getId())) {
                    return;
                }
                onControllerReleasedLatch.countDown();
            }
        };

        // TODO: Remove this once the MediaRouter2 becomes always connected to the service.
        RouteCallback routeCallback = new RouteCallback();
        mRouter2.registerRouteCallback(mExecutor, routeCallback, RouteDiscoveryPreference.EMPTY);

        try {
            mRouter2.registerControllerCallback(mExecutor, controllerCallback);
            mRouter2.requestCreateController(routeToCreateSessionWith);
            assertTrue(onControllerCreatedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            assertEquals(1, controllers.size());
            RoutingController controller = controllers.get(0);
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
            assertFalse(onControllerUpdatedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            // onControllerReleased should be called.
            assertTrue(onControllerReleasedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            releaseControllers(controllers);
            mRouter2.unregisterRouteCallback(routeCallback);
            mRouter2.unregisterControllerCallback(controllerCallback);
        }
    }

    // TODO: Consider adding tests with bluetooth connection/disconnection.
    @Test
    public void testGetSystemController() {
        final RoutingController systemController = mRouter2.getSystemController();
        assertNotNull(systemController);
        assertFalse(systemController.isReleased());
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
                new RouteDiscoveryPreference.Builder(routeTypes, true).build());
        try {
            latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return createRouteMap(mRouter2.getRoutes());
        } finally {
            mRouter2.unregisterRouteCallback(routeCallback);
        }
    }

    static void releaseControllers(@NonNull List<RoutingController> controllers) {
        for (RoutingController controller : controllers) {
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
                new RouteDiscoveryPreference.Builder(routeTypes, true).build());
        try {
            task.run();
            assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            mRouter2.unregisterRouteCallback(routeCallback);
        }
    }
}
