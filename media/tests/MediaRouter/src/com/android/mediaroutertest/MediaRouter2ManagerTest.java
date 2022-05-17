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

import static android.media.MediaRoute2Info.FEATURE_REMOTE_PLAYBACK;
import static android.media.MediaRoute2Info.PLAYBACK_VOLUME_FIXED;
import static android.media.MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE;
import static android.media.MediaRoute2ProviderService.REASON_REJECTED;
import static android.media.MediaRoute2ProviderService.REQUEST_ID_NONE;

import static com.android.mediaroutertest.StubMediaRoute2ProviderService.FEATURE_SAMPLE;
import static com.android.mediaroutertest.StubMediaRoute2ProviderService.FEATURE_SPECIAL;
import static com.android.mediaroutertest.StubMediaRoute2ProviderService.ROUTE_ID1;
import static com.android.mediaroutertest.StubMediaRoute2ProviderService.ROUTE_ID2;
import static com.android.mediaroutertest.StubMediaRoute2ProviderService.ROUTE_ID4_TO_SELECT_AND_DESELECT;
import static com.android.mediaroutertest.StubMediaRoute2ProviderService.ROUTE_ID5_TO_TRANSFER_TO;
import static com.android.mediaroutertest.StubMediaRoute2ProviderService.ROUTE_ID6_TO_BE_IGNORED;
import static com.android.mediaroutertest.StubMediaRoute2ProviderService.ROUTE_ID_FIXED_VOLUME;
import static com.android.mediaroutertest.StubMediaRoute2ProviderService.ROUTE_ID_SPECIAL_FEATURE;
import static com.android.mediaroutertest.StubMediaRoute2ProviderService.ROUTE_ID_VARIABLE_VOLUME;
import static com.android.mediaroutertest.StubMediaRoute2ProviderService.ROUTE_NAME2;
import static com.android.mediaroutertest.StubMediaRoute2ProviderService.VOLUME_MAX;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.UiAutomation;
import android.content.Context;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2;
import android.media.MediaRouter2.RouteCallback;
import android.media.MediaRouter2.TransferCallback;
import android.media.MediaRouter2Manager;
import android.media.MediaRouter2Utils;
import android.media.RouteDiscoveryPreference;
import android.media.RoutingSessionInfo;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.PollingCheck;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class MediaRouter2ManagerTest {
    private static final String TAG = "MediaRouter2ManagerTest";
    private static final int WAIT_TIME_MS = 2000;
    private static final int TIMEOUT_MS = 5000;
    private static final String TEST_KEY = "test_key";
    private static final String TEST_VALUE = "test_value";
    private static final String TEST_ID_UNKNOWN = "id_unknown";
    private static final String TEST_NAME_UNKNOWN = "unknown";

    private Context mContext;
    private UiAutomation mUiAutomation;
    private MediaRouter2Manager mManager;
    private MediaRouter2 mRouter2;
    private Executor mExecutor;
    private String mPackageName;
    private StubMediaRoute2ProviderService mService;

    private final List<MediaRouter2Manager.Callback> mManagerCallbacks = new ArrayList<>();
    private final List<RouteCallback> mRouteCallbacks = new ArrayList<>();
    private final List<MediaRouter2.TransferCallback> mTransferCallbacks = new ArrayList<>();

    public static final List<String> FEATURES_ALL = new ArrayList();
    public static final List<String> FEATURES_SPECIAL = new ArrayList();

    static {
        FEATURES_ALL.add(FEATURE_SAMPLE);
        FEATURES_ALL.add(FEATURE_SPECIAL);

        FEATURES_SPECIAL.add(FEATURE_SPECIAL);
    }

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mUiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        mUiAutomation.adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL,
                Manifest.permission.MODIFY_AUDIO_ROUTING);
        MediaRouter2ManagerTestActivity.startActivity(mContext);

        mManager = MediaRouter2Manager.getInstance(mContext);
        mManager.startScan();
        mRouter2 = MediaRouter2.getInstance(mContext);

        // If we need to support thread pool executors, change this to thread pool executor.
        mExecutor = Executors.newSingleThreadExecutor();
        mPackageName = mContext.getPackageName();

        // In order to make the system bind to the test service,
        // set a non-empty discovery preference while app is in foreground.
        List<String> features = new ArrayList<>();
        features.add("A test feature");
        RouteDiscoveryPreference preference =
                new RouteDiscoveryPreference.Builder(features, false).build();
        mRouter2.registerRouteCallback(mExecutor, new RouteCallback() {}, preference);

        new PollingCheck(TIMEOUT_MS) {
            @Override
            protected boolean check() {
                StubMediaRoute2ProviderService service =
                        StubMediaRoute2ProviderService.getInstance();
                if (service != null) {
                    mService = service;
                    return true;
                }
                return false;
            }
        }.run();
    }

    @After
    public void tearDown() {
        mManager.stopScan();

        // order matters (callbacks should be cleared at the last)
        releaseAllSessions();
        // unregister callbacks
        clearCallbacks();

        if (mService != null) {
            mService.setProxy(null);
            mService.setSpy(null);
        }

        MediaRouter2ManagerTestActivity.finishActivity();
        mUiAutomation.dropShellPermissionIdentity();
    }

    @Test
    public void testOnRoutesRemovedAndAdded() throws Exception {
        RouteCallback routeCallback = new RouteCallback() {};
        mRouteCallbacks.add(routeCallback);
        mRouter2.registerRouteCallback(mExecutor, routeCallback,
                new RouteDiscoveryPreference.Builder(FEATURES_ALL, true).build());

        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);

        CountDownLatch removedLatch = new CountDownLatch(1);
        CountDownLatch addedLatch = new CountDownLatch(1);

        addManagerCallback(new MediaRouter2Manager.Callback() {
            @Override
            public void onRoutesRemoved(List<MediaRoute2Info> routes) {
                assertTrue(routes.size() > 0);
                for (MediaRoute2Info route : routes) {
                    if (route.getOriginalId().equals(ROUTE_ID2)
                            && route.getName().equals(ROUTE_NAME2)) {
                        removedLatch.countDown();
                    }
                }
            }
            @Override
            public void onRoutesAdded(List<MediaRoute2Info> routes) {
                assertTrue(routes.size() > 0);
                if (removedLatch.getCount() > 0) {
                    return;
                }
                for (MediaRoute2Info route : routes) {
                    if (route.getOriginalId().equals(ROUTE_ID2)
                            && route.getName().equals(ROUTE_NAME2)) {
                        addedLatch.countDown();
                    }
                }
            }
        });

        MediaRoute2Info routeToRemove = routes.get(ROUTE_ID2);
        assertNotNull(routeToRemove);

        mService.removeRoute(ROUTE_ID2);
        assertTrue(removedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        mService.addRoute(routeToRemove);
        assertTrue(addedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testGetRoutes_removedRoute_returnsCorrectRoutes() throws Exception {
        CountDownLatch addedLatch = new CountDownLatch(1);
        CountDownLatch removedLatch = new CountDownLatch(1);

        RouteCallback routeCallback = new RouteCallback() {
            // Used to ensure the removed route is added.
            @Override
            public void onRoutesAdded(List<MediaRoute2Info> routes) {
                if (removedLatch.getCount() > 0) {
                    return;
                }
                addedLatch.countDown();
            }

            @Override
            public void onRoutesRemoved(List<MediaRoute2Info> routes) {
                removedLatch.countDown();
            }
        };

        mRouter2.registerRouteCallback(mExecutor, routeCallback,
                new RouteDiscoveryPreference.Builder(FEATURES_ALL, true).build());
        mRouteCallbacks.add(routeCallback);

        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);
        MediaRoute2Info routeToRemove = routes.get(ROUTE_ID2);
        assertNotNull(routeToRemove);

        mService.removeRoute(ROUTE_ID2);

        // Wait until the route is removed.
        assertTrue(removedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        Map<String, MediaRoute2Info> newRoutes = waitAndGetRoutesWithManager(FEATURES_ALL);
        assertNull(newRoutes.get(ROUTE_ID2));

        // Revert the removal.
        mService.addRoute(routeToRemove);
        assertTrue(addedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        mRouter2.unregisterRouteCallback(routeCallback);
    }

    /**
     * Tests if we get proper routes for application that has special route feature.
     */
    @Test
    public void testRouteFeatures() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_SPECIAL);

        int routeCount = 0;
        for (MediaRoute2Info route : routes.values()) {
            if (!route.isSystemRoute()) {
                routeCount++;
            }
        }

        assertEquals(1, routeCount);
        assertNotNull(routes.get(ROUTE_ID_SPECIAL_FEATURE));
    }

    @Test
    public void testNoAllowedPackages_returnsZeroRoutes() throws Exception {
        RouteDiscoveryPreference preference =
                new RouteDiscoveryPreference.Builder(FEATURES_ALL, true)
                        .setAllowedPackages(List.of("random package name"))
                        .build();
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(preference);

        int remoteRouteCount = 0;
        for (MediaRoute2Info route : routes.values()) {
            if (!route.isSystemRoute()) {
                remoteRouteCount++;
            }
        }

        assertEquals(0, remoteRouteCount);
    }

    @Test
    public void testAllowedPackages() throws Exception {
        RouteDiscoveryPreference preference =
                new RouteDiscoveryPreference.Builder(FEATURES_ALL, true)
                        .setAllowedPackages(List.of("com.android.mediaroutertest"))
                        .build();
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(preference);

        int remoteRouteCount = 0;
        for (MediaRoute2Info route : routes.values()) {
            if (!route.isSystemRoute()) {
                remoteRouteCount++;
            }
        }

        assertTrue(remoteRouteCount > 0);
    }

    /**
     * Tests if MR2.SessionCallback.onSessionCreated is called
     * when a route is selected from MR2Manager.
     */
    @Test
    public void testRouterOnSessionCreated() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);

        CountDownLatch latch = new CountDownLatch(1);

        addManagerCallback(new MediaRouter2Manager.Callback() {});
        addRouterCallback(new MediaRouter2.RouteCallback() {});
        addTransferCallback(new MediaRouter2.TransferCallback() {
            @Override
            public void onTransfer(MediaRouter2.RoutingController oldController,
                    MediaRouter2.RoutingController newController) {
                if (newController == null) {
                    return;
                }
                if (createRouteMap(newController.getSelectedRoutes()).containsKey(ROUTE_ID1)) {
                    latch.countDown();
                }
            }
        });

        MediaRoute2Info routeToSelect = routes.get(ROUTE_ID1);
        assertNotNull(routeToSelect);

        mManager.selectRoute(mPackageName, routeToSelect);
        assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(1, mManager.getRemoteSessions().size());
    }

    @Test
    public void testGetRoutingSessions() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);
        MediaRoute2Info routeToSelect = routes.get(ROUTE_ID1);

        addRouterCallback(new RouteCallback() {});
        addManagerCallback(new MediaRouter2Manager.Callback() {
            @Override
            public void onTransferred(RoutingSessionInfo oldSessionInfo,
                    RoutingSessionInfo newSessionInfo) {
                if (TextUtils.equals(mPackageName, newSessionInfo.getClientPackageName())
                        && newSessionInfo.getSelectedRoutes().contains(routeToSelect.getId())) {
                    latch.countDown();
                }
            }
        });

        assertEquals(1, mManager.getRoutingSessions(mPackageName).size());

        mManager.selectRoute(mPackageName, routeToSelect);
        assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        List<RoutingSessionInfo> sessions = mManager.getRoutingSessions(mPackageName);
        assertEquals(2, sessions.size());

        RoutingSessionInfo sessionInfo = sessions.get(1);
        awaitOnRouteChangedManager(
                () -> mManager.releaseSession(sessionInfo),
                ROUTE_ID1,
                route -> TextUtils.equals(route.getClientPackageName(), null));
        assertEquals(1, mManager.getRoutingSessions(mPackageName).size());
    }

    @Test
    public void testTransfer_unknownRoute_fail() throws Exception {
        addRouterCallback(new RouteCallback() {});

        CountDownLatch onSessionCreatedLatch = new CountDownLatch(1);
        CountDownLatch onTransferFailedLatch = new CountDownLatch(1);

        addManagerCallback(new MediaRouter2Manager.Callback() {
            @Override
            public void onTransferred(RoutingSessionInfo oldSessionInfo,
                    RoutingSessionInfo newSessionInfo) {
                assertNotNull(newSessionInfo);
                onSessionCreatedLatch.countDown();
            }
            @Override
            public void onTransferFailed(RoutingSessionInfo session, MediaRoute2Info route) {
                onTransferFailedLatch.countDown();
            }
        });

        MediaRoute2Info unknownRoute =
                new MediaRoute2Info.Builder(TEST_ID_UNKNOWN, TEST_NAME_UNKNOWN)
                .addFeature(FEATURE_REMOTE_PLAYBACK)
                .build();

        mManager.transfer(mManager.getSystemRoutingSession(null), unknownRoute);
        assertFalse(onSessionCreatedLatch.await(WAIT_TIME_MS, TimeUnit.MILLISECONDS));
        assertTrue(onTransferFailedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testRouterRelease_managerGetRoutingSessions() throws Exception {
        CountDownLatch transferLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);

        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);
        MediaRoute2Info routeToSelect = routes.get(ROUTE_ID1);
        assertNotNull(routeToSelect);

        addRouterCallback(new RouteCallback() {});
        addManagerCallback(new MediaRouter2Manager.Callback() {
            @Override
            public void onTransferred(RoutingSessionInfo oldSessionInfo,
                    RoutingSessionInfo newSessionInfo) {
                if (TextUtils.equals(mPackageName, newSessionInfo.getClientPackageName())
                        && newSessionInfo.getSelectedRoutes().contains(routeToSelect.getId())) {
                    transferLatch.countDown();
                }
            }
            @Override
            public void onSessionReleased(RoutingSessionInfo session) {
                releaseLatch.countDown();
            }
        });

        assertEquals(1, mManager.getRoutingSessions(mPackageName).size());
        assertEquals(1, mRouter2.getControllers().size());

        mManager.transfer(mManager.getRoutingSessions(mPackageName).get(0), routeToSelect);
        assertTrue(transferLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        assertEquals(2, mManager.getRoutingSessions(mPackageName).size());
        assertEquals(2, mRouter2.getControllers().size());
        mRouter2.getControllers().get(1).release();

        assertTrue(releaseLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        assertEquals(1, mRouter2.getControllers().size());
        assertEquals(1, mManager.getRoutingSessions(mPackageName).size());
    }

    /**
     * Tests select, transfer, release of routes of a provider
     */
    @Test
    public void testSelectAndTransferAndRelease() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);
        addRouterCallback(new RouteCallback() {});

        CountDownLatch onSessionCreatedLatch = new CountDownLatch(1);

        addManagerCallback(new MediaRouter2Manager.Callback() {
            @Override
            public void onTransferred(RoutingSessionInfo oldSessionInfo,
                    RoutingSessionInfo newSessionInfo) {
                assertNotNull(newSessionInfo);
                onSessionCreatedLatch.countDown();
            }
        });
        awaitOnRouteChangedManager(
                () -> mManager.selectRoute(mPackageName, routes.get(ROUTE_ID1)),
                ROUTE_ID1,
                route -> TextUtils.equals(route.getClientPackageName(), mPackageName));
        assertTrue(onSessionCreatedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        List<RoutingSessionInfo> sessions = mManager.getRoutingSessions(mPackageName);

        assertEquals(2, sessions.size());
        RoutingSessionInfo sessionInfo = sessions.get(1);

        awaitOnRouteChangedManager(
                () -> mManager.selectRoute(mPackageName, routes.get(ROUTE_ID5_TO_TRANSFER_TO)),
                ROUTE_ID5_TO_TRANSFER_TO,
                route -> TextUtils.equals(route.getClientPackageName(), mPackageName));

        awaitOnRouteChangedManager(
                () -> mManager.releaseSession(sessionInfo),
                ROUTE_ID5_TO_TRANSFER_TO,
                route -> TextUtils.equals(route.getClientPackageName(), null));
    }

    @Test
    @LargeTest
    public void testTransferTwice() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);
        addRouterCallback(new RouteCallback() { });

        CountDownLatch successLatch1 = new CountDownLatch(1);
        CountDownLatch successLatch2 = new CountDownLatch(1);
        CountDownLatch failureLatch = new CountDownLatch(1);
        CountDownLatch managerOnSessionReleasedLatch = new CountDownLatch(1);
        CountDownLatch serviceOnReleaseSessionLatch = new CountDownLatch(1);
        List<RoutingSessionInfo> sessions = new ArrayList<>();

        mService.setSpy(new StubMediaRoute2ProviderService.Spy() {
            @Override
            public void onReleaseSession(long requestId, String sessionId) {
                serviceOnReleaseSessionLatch.countDown();
            }
        });

        addManagerCallback(new MediaRouter2Manager.Callback() {
            @Override
            public void onTransferred(RoutingSessionInfo oldSession,
                    RoutingSessionInfo newSession) {
                sessions.add(newSession);
                if (successLatch1.getCount() > 0) {
                    successLatch1.countDown();
                } else {
                    successLatch2.countDown();
                }
            }

            @Override
            public void onTransferFailed(RoutingSessionInfo session, MediaRoute2Info route) {
                failureLatch.countDown();
            }

            @Override
            public void onSessionReleased(RoutingSessionInfo session) {
                managerOnSessionReleasedLatch.countDown();
            }
        });

        MediaRoute2Info route1 = routes.get(ROUTE_ID1);
        MediaRoute2Info route2 = routes.get(ROUTE_ID2);
        assertNotNull(route1);
        assertNotNull(route2);

        mManager.selectRoute(mPackageName, route1);
        assertTrue(successLatch1.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        mManager.selectRoute(mPackageName, route2);
        assertTrue(successLatch2.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        // onTransferFailed/onSessionReleased should not be called.
        assertFalse(failureLatch.await(WAIT_TIME_MS, TimeUnit.MILLISECONDS));
        assertFalse(managerOnSessionReleasedLatch.await(WAIT_TIME_MS, TimeUnit.MILLISECONDS));

        assertEquals(2, sessions.size());
        List<String> remoteSessionIds = mManager.getRemoteSessions().stream()
                .map(RoutingSessionInfo::getId)
                .collect(Collectors.toList());
        // The old session shouldn't appear on the session list.
        assertFalse(remoteSessionIds.contains(sessions.get(0).getId()));
        assertTrue(remoteSessionIds.contains(sessions.get(1).getId()));

        assertFalse(serviceOnReleaseSessionLatch.await(WAIT_TIME_MS, TimeUnit.MILLISECONDS));
        mManager.releaseSession(sessions.get(0));
        assertTrue(serviceOnReleaseSessionLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertFalse(managerOnSessionReleasedLatch.await(WAIT_TIME_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    @LargeTest
    public void testTransfer_ignored_fails() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);
        addRouterCallback(new RouteCallback() {});

        CountDownLatch onSessionCreatedLatch = new CountDownLatch(1);
        CountDownLatch onFailedLatch = new CountDownLatch(1);

        addManagerCallback(new MediaRouter2Manager.Callback() {
            @Override
            public void onTransferred(RoutingSessionInfo oldSessionInfo,
                    RoutingSessionInfo newSessionInfo) {
                onSessionCreatedLatch.countDown();
            }
            @Override
            public void onTransferFailed(RoutingSessionInfo session, MediaRoute2Info route) {
                onFailedLatch.countDown();
            }
        });

        List<RoutingSessionInfo> sessions = mManager.getRoutingSessions(mPackageName);
        RoutingSessionInfo targetSession = sessions.get(sessions.size() - 1);
        mManager.transfer(targetSession, routes.get(ROUTE_ID6_TO_BE_IGNORED));

        assertFalse(onSessionCreatedLatch.await(WAIT_TIME_MS, TimeUnit.MILLISECONDS));
        assertTrue(onFailedLatch.await(MediaRouter2Manager.TRANSFER_TIMEOUT_MS,
                TimeUnit.MILLISECONDS));
    }

    @Test
    public void testSetSystemRouteVolume() throws Exception {
        // ensure client
        addManagerCallback(new MediaRouter2Manager.Callback() {});
        String selectedSystemRouteId =
                MediaRouter2Utils.getOriginalId(
                mManager.getSystemRoutingSession(mPackageName).getSelectedRoutes().get(0));
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);
        MediaRoute2Info volRoute = routes.get(selectedSystemRouteId);
        assertNotNull(volRoute);

        int originalVolume = volRoute.getVolume();
        int targetVolume = originalVolume == volRoute.getVolumeMax()
                ? originalVolume - 1 : originalVolume + 1;

        awaitOnRouteChangedManager(
                () -> mManager.setRouteVolume(volRoute, targetVolume),
                selectedSystemRouteId,
                (route -> route.getVolume() == targetVolume));

        awaitOnRouteChangedManager(
                () -> mManager.setRouteVolume(volRoute, originalVolume),
                selectedSystemRouteId,
                (route -> route.getVolume() == originalVolume));
    }

    @Test
    public void testSetRouteVolume() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);
        MediaRoute2Info volRoute = routes.get(ROUTE_ID_VARIABLE_VOLUME);

        int originalVolume = volRoute.getVolume();
        int targetVolume = originalVolume == volRoute.getVolumeMax()
                ? originalVolume - 1 : originalVolume + 1;

        awaitOnRouteChangedManager(
                () -> mManager.setRouteVolume(volRoute, targetVolume),
                ROUTE_ID_VARIABLE_VOLUME,
                (route -> route.getVolume() == targetVolume));

        awaitOnRouteChangedManager(
                () -> mManager.setRouteVolume(volRoute, originalVolume),
                ROUTE_ID_VARIABLE_VOLUME,
                (route -> route.getVolume() == originalVolume));
    }

    @Test
    public void testSetSessionVolume() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);
        addRouterCallback(new RouteCallback() {});

        CountDownLatch onSessionCreatedLatch = new CountDownLatch(1);
        CountDownLatch volumeChangedLatch = new CountDownLatch(2);

        // create a controller
        addManagerCallback(new MediaRouter2Manager.Callback() {
            @Override
            public void onTransferred(RoutingSessionInfo oldSessionInfo,
                    RoutingSessionInfo newSessionInfo) {
                assertNotNull(newSessionInfo);
                onSessionCreatedLatch.countDown();
            }
        });

        mManager.selectRoute(mPackageName, routes.get(ROUTE_ID1));
        assertTrue(onSessionCreatedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        List<RoutingSessionInfo> sessions = mManager.getRoutingSessions(mPackageName);
        assertEquals(2, sessions.size());

        // test setSessionVolume
        RoutingSessionInfo sessionInfo = sessions.get(1);
        int currentVolume = sessionInfo.getVolume();
        int targetVolume = (currentVolume == 0) ? 1 : (currentVolume - 1);

        MediaRouter2.ControllerCallback controllerCallback = new MediaRouter2.ControllerCallback() {
            @Override
            public void onControllerUpdated(MediaRouter2.RoutingController controller) {
                if (!TextUtils.equals(sessionInfo.getId(), controller.getId())) {
                    return;
                }
                if (controller.getVolume() == targetVolume) {
                    volumeChangedLatch.countDown();
                }
            }
        };

        addManagerCallback(new MediaRouter2Manager.Callback() {
            @Override
            public void onSessionUpdated(RoutingSessionInfo updatedSessionInfo) {
                if (!TextUtils.equals(sessionInfo.getId(), updatedSessionInfo.getId())) {
                    return;
                }

                if (updatedSessionInfo.getVolume() == targetVolume) {
                    volumeChangedLatch.countDown();
                }
            }
        });

        try {
            mRouter2.registerControllerCallback(mExecutor, controllerCallback);
            mManager.setSessionVolume(sessionInfo, targetVolume);
            assertTrue(volumeChangedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            mRouter2.unregisterControllerCallback(controllerCallback);
        }
    }

    /**
     * Tests that {@link android.media.MediaRoute2ProviderService#notifyRequestFailed(long, int)}
     * should invoke the callback only when the right requestId is used.
     */
    @Test
    public void testOnRequestFailedCalledForProperRequestId() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);
        MediaRoute2Info volRoute = routes.get(ROUTE_ID_VARIABLE_VOLUME);

        final List<Long> requestIds = new ArrayList<>();
        final CountDownLatch onSetRouteVolumeLatch = new CountDownLatch(1);
        mService.setProxy(new StubMediaRoute2ProviderService.Proxy() {
            @Override
            public void onSetRouteVolume(String routeId, int volume, long requestId) {
                requestIds.add(requestId);
                onSetRouteVolumeLatch.countDown();
            }
        });

        addManagerCallback(new MediaRouter2Manager.Callback() {});
        mManager.setRouteVolume(volRoute, 0);
        assertTrue(onSetRouteVolumeLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertFalse(requestIds.isEmpty());

        final int failureReason = REASON_REJECTED;
        final CountDownLatch onRequestFailedLatch = new CountDownLatch(1);
        final CountDownLatch onRequestFailedSecondCallLatch = new CountDownLatch(1);
        addManagerCallback(new MediaRouter2Manager.Callback() {
            @Override
            public void onRequestFailed(int reason) {
                if (reason == failureReason) {
                    if (onRequestFailedLatch.getCount() > 0) {
                        onRequestFailedLatch.countDown();
                    } else {
                        onRequestFailedSecondCallLatch.countDown();
                    }
                }
            }
        });

        final long invalidRequestId = REQUEST_ID_NONE;
        mService.notifyRequestFailed(invalidRequestId, failureReason);
        assertFalse(onRequestFailedLatch.await(WAIT_TIME_MS, TimeUnit.MILLISECONDS));

        final long validRequestId = requestIds.get(0);
        mService.notifyRequestFailed(validRequestId, failureReason);
        assertTrue(onRequestFailedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        // Test calling notifyRequestFailed() multiple times with the same valid requestId.
        // onRequestFailed() shouldn't be called since the requestId has been already handled.
        mService.notifyRequestFailed(validRequestId, failureReason);
        assertFalse(onRequestFailedSecondCallLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testVolumeHandling() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);

        MediaRoute2Info fixedVolumeRoute = routes.get(ROUTE_ID_FIXED_VOLUME);
        MediaRoute2Info variableVolumeRoute = routes.get(ROUTE_ID_VARIABLE_VOLUME);

        assertEquals(PLAYBACK_VOLUME_FIXED, fixedVolumeRoute.getVolumeHandling());
        assertEquals(PLAYBACK_VOLUME_VARIABLE, variableVolumeRoute.getVolumeHandling());
        assertEquals(VOLUME_MAX, variableVolumeRoute.getVolumeMax());
    }

    @Test
    public void testRouter2SetOnGetControllerHintsListener() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);
        addRouterCallback(new RouteCallback() {});

        MediaRoute2Info route = routes.get(ROUTE_ID1);
        assertNotNull(route);

        final Bundle controllerHints = new Bundle();
        controllerHints.putString(TEST_KEY, TEST_VALUE);
        final CountDownLatch hintLatch = new CountDownLatch(1);
        final MediaRouter2.OnGetControllerHintsListener listener =
                route1 -> {
                    hintLatch.countDown();
                    return controllerHints;
                };

        final CountDownLatch successLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(1);

        addManagerCallback(new MediaRouter2Manager.Callback() {
            @Override
            public void onTransferred(RoutingSessionInfo oldSession,
                    RoutingSessionInfo newSession) {
                assertTrue(newSession.getSelectedRoutes().contains(route.getId()));
                // The StubMediaRoute2ProviderService is supposed to set control hints
                // with the given controllerHints.
                Bundle controlHints = newSession.getControlHints();
                assertNotNull(controlHints);
                assertTrue(controlHints.containsKey(TEST_KEY));
                assertEquals(TEST_VALUE, controlHints.getString(TEST_KEY));

                successLatch.countDown();
            }

            @Override
            public void onTransferFailed(RoutingSessionInfo session,
                    MediaRoute2Info requestedRoute) {
                failureLatch.countDown();
            }
        });

        mRouter2.setOnGetControllerHintsListener(listener);
        mManager.selectRoute(mPackageName, route);
        assertTrue(hintLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertTrue(successLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        assertFalse(failureLatch.await(WAIT_TIME_MS, TimeUnit.MILLISECONDS));
    }

    /**
     * Tests if getSelectableRoutes and getDeselectableRoutes filter routes based on
     * selected routes
     */
    @Test
    public void testGetSelectableRoutes_notReturnsSelectedRoutes() throws Exception {
        Map<String, MediaRoute2Info> routes = waitAndGetRoutesWithManager(FEATURES_ALL);
        addRouterCallback(new RouteCallback() {});

        CountDownLatch onSessionCreatedLatch = new CountDownLatch(1);

        addManagerCallback(new MediaRouter2Manager.Callback() {
            @Override
            public void onTransferred(RoutingSessionInfo oldSessionInfo,
                    RoutingSessionInfo newSessionInfo) {
                assertNotNull(newSessionInfo);
                List<String> selectedRoutes = mManager.getSelectedRoutes(newSessionInfo).stream()
                        .map(MediaRoute2Info::getId)
                        .collect(Collectors.toList());
                for (MediaRoute2Info selectableRoute :
                        mManager.getSelectableRoutes(newSessionInfo)) {
                    assertFalse(selectedRoutes.contains(selectableRoute.getId()));
                }
                for (MediaRoute2Info deselectableRoute :
                        mManager.getDeselectableRoutes(newSessionInfo)) {
                    assertTrue(selectedRoutes.contains(deselectableRoute.getId()));
                }
                onSessionCreatedLatch.countDown();
            }
        });

        mManager.selectRoute(mPackageName, routes.get(ROUTE_ID4_TO_SELECT_AND_DESELECT));
        assertTrue(onSessionCreatedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    Map<String, MediaRoute2Info> waitAndGetRoutesWithManager(List<String> routeFeatures)
            throws Exception {
        return waitAndGetRoutesWithManager(
                new RouteDiscoveryPreference.Builder(routeFeatures, true).build());
    }

    Map<String, MediaRoute2Info> waitAndGetRoutesWithManager(RouteDiscoveryPreference preference)
            throws Exception {
        CountDownLatch addedLatch = new CountDownLatch(1);
        CountDownLatch preferenceLatch = new CountDownLatch(1);

        // A dummy callback is required to send route feature info.
        RouteCallback routeCallback = new RouteCallback() {};
        MediaRouter2Manager.Callback managerCallback = new MediaRouter2Manager.Callback() {
            @Override
            public void onRoutesAdded(List<MediaRoute2Info> routes) {
                for (MediaRoute2Info route : routes) {
                    if (!route.isSystemRoute()
                            && hasMatchingFeature(route.getFeatures(), preference
                            .getPreferredFeatures())) {
                        addedLatch.countDown();
                        break;
                    }
                }
            }

            @Override
            public void onDiscoveryPreferenceChanged(String packageName,
                    RouteDiscoveryPreference discoveryPreference) {
                if (TextUtils.equals(mPackageName, packageName)
                        && Objects.equals(preference, discoveryPreference)) {
                    preferenceLatch.countDown();
                }
            }
        };
        mManager.registerCallback(mExecutor, managerCallback);
        mRouter2.registerRouteCallback(mExecutor, routeCallback, preference);

        try {
            preferenceLatch.await(WAIT_TIME_MS, TimeUnit.MILLISECONDS);
            if (mManager.getAvailableRoutes(mPackageName).isEmpty()) {
                addedLatch.await(WAIT_TIME_MS, TimeUnit.MILLISECONDS);
            }
            return createRouteMap(mManager.getAvailableRoutes(mPackageName));
        } finally {
            mRouter2.unregisterRouteCallback(routeCallback);
            mManager.unregisterCallback(managerCallback);
        }
    }

    boolean hasMatchingFeature(List<String> features1, List<String> features2) {
        for (String feature : features1) {
            if (features2.contains(feature)) {
                return true;
            }
        }
        return false;
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
            task.run();
            assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            mManager.unregisterCallback(callback);
        }
    }

    // Helper for getting routes easily
    static Map<String, MediaRoute2Info> createRouteMap(List<MediaRoute2Info> routes) {
        Map<String, MediaRoute2Info> routeMap = new HashMap<>();
        for (MediaRoute2Info route : routes) {
            routeMap.put(route.getOriginalId(), route);
        }
        return routeMap;
    }

    private void addManagerCallback(MediaRouter2Manager.Callback callback) {
        mManagerCallbacks.add(callback);
        mManager.registerCallback(mExecutor, callback);
    }

    private void addRouterCallback(RouteCallback routeCallback) {
        mRouteCallbacks.add(routeCallback);
        mRouter2.registerRouteCallback(mExecutor, routeCallback, RouteDiscoveryPreference.EMPTY);
    }

    private void addTransferCallback(TransferCallback transferCallback) {
        mTransferCallbacks.add(transferCallback);
        mRouter2.registerTransferCallback(mExecutor, transferCallback);
    }

    private void clearCallbacks() {
        for (MediaRouter2Manager.Callback callback : mManagerCallbacks) {
            mManager.unregisterCallback(callback);
        }
        mManagerCallbacks.clear();

        for (RouteCallback routeCallback : mRouteCallbacks) {
            mRouter2.unregisterRouteCallback(routeCallback);
        }
        mRouteCallbacks.clear();

        for (MediaRouter2.TransferCallback transferCallback : mTransferCallbacks) {
            mRouter2.unregisterTransferCallback(transferCallback);
        }
        mTransferCallbacks.clear();
    }

    private void releaseAllSessions() {
        // ensure ManagerRecord in MediaRouter2ServiceImpl
        addManagerCallback(new MediaRouter2Manager.Callback() {});

        for (RoutingSessionInfo session : mManager.getRemoteSessions()) {
            mManager.releaseSession(session);
        }
    }
}
