/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.app;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.service.games.GameService;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.SystemService;

import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoSession;

import java.util.List;


@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public final class GameServiceControllerTests {
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private Resources mMockResources;
    @Mock
    private Context mMockContext;
    private MockitoSession mMockingSession;

    private static UserInfo eligibleUserInfo(int uid) {
        return new UserInfo(uid, "", "", UserInfo.FLAG_FULL);
    }

    private static UserInfo managedUserInfo(int uid) {
        UserInfo userInfo = eligibleUserInfo(uid);
        userInfo.userType = UserManager.USER_TYPE_PROFILE_MANAGED;
        return userInfo;
    }

    private static ResolveInfo resolveInfo(ServiceInfo serviceInfo) {
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = serviceInfo;
        return resolveInfo;
    }

    private static ServiceInfo serviceInfo(String packageName, String name, boolean isEnabled) {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = packageName;
        applicationInfo.enabled = true;

        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.applicationInfo = applicationInfo;
        serviceInfo.packageName = packageName;
        serviceInfo.name = name;
        serviceInfo.enabled = isEnabled;
        return serviceInfo;
    }

    private static SystemService.TargetUser managedTargetUser(int ineligibleUserId) {
        return new SystemService.TargetUser(managedUserInfo(ineligibleUserId));
    }

    private static SystemService.TargetUser eligibleTargetUser(int userId) {
        return new SystemService.TargetUser(eligibleUserInfo(userId));
    }

    private static UserHandle userWithId(int userId) {
        return argThat(userInfo -> userInfo.getIdentifier() == userId);
    }

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .startMocking();
    }

    @After
    public void tearDown() {
        mMockingSession.finishMocking();
    }

    @Test
    public void testStartConnectionOnBootWithNoUser() {
        GameServiceController gameServiceController =
                new GameServiceController(mMockContext);

        gameServiceController.onBootComplete();

        verifyNoServiceBound();
    }

    @Test
    public void testStartConnectionOnBootWithManagedUser() {
        int userId = 12345;
        GameServiceController gameServiceController =
                new GameServiceController(mMockContext);

        gameServiceController.notifyUserStarted(managedTargetUser(userId));
        gameServiceController.onBootComplete();

        verifyNoServiceBound();
    }

    @Test
    public void testStartConnectionOnBootWithUserAndNoSystemGamesServiceSet() {
        seedSystemGameServicePackageName("");

        GameServiceController gameServiceController =
                new GameServiceController(mMockContext);

        gameServiceController.notifyUserStarted(eligibleTargetUser(1000));
        gameServiceController.onBootComplete();

        verifyNoServiceBound();
    }

    @Test
    public void testStartConnectionOnBootWithUserAndSystemGamesServiceDoesNotExist() {
        int userId = 12345;
        String gameServicePackageName = "game.service.package";
        seedSystemGameServicePackageName(gameServicePackageName);
        seedGameServiceResolveInfos(gameServicePackageName, userId, ImmutableList.of());

        GameServiceController gameServiceController =
                new GameServiceController(mMockContext);

        gameServiceController.notifyUserStarted(eligibleTargetUser(userId));
        gameServiceController.onBootComplete();

        verifyNoServiceBound();
    }

    @Test
    public void testStartConnectionOnBootWithUserAndSystemGamesServiceSet() {
        int userId = 12345;
        String gameServicePackageName = "game.service.package";
        String gameServiceComponent = "game.service.package.example.GameService";
        seedSystemGameServicePackageName(gameServicePackageName);
        seedGameServiceResolveInfos(gameServicePackageName, userId, ImmutableList.of(
                resolveInfo(serviceInfo(gameServicePackageName, gameServiceComponent, true))));

        GameServiceController gameServiceController =
                new GameServiceController(mMockContext);

        gameServiceController.notifyUserStarted(eligibleTargetUser(userId));
        gameServiceController.onBootComplete();

        verifyServiceBoundForUserAndComponent(userId, gameServicePackageName, gameServiceComponent);
    }

    @Test
    public void testStartConnectionOnBootWithUserAndSystemGamesServiceNotEnabled() {
        int userId = 12345;
        String gameServicePackageName = "game.service.package";
        String gameServiceComponent = "game.service.package.example.GameService";
        seedSystemGameServicePackageName(gameServicePackageName);
        seedGameServiceResolveInfos(gameServicePackageName, userId, ImmutableList.of(
                resolveInfo(serviceInfo(gameServicePackageName, gameServiceComponent, false))));

        GameServiceController gameServiceController =
                new GameServiceController(mMockContext);

        gameServiceController.notifyUserStarted(eligibleTargetUser(userId));
        gameServiceController.onBootComplete();

        verifyNoServiceBound();
    }

    @Test
    public void testStartConnectionOnBootWithUserAndSystemGamesServiceHasMultipleComponents() {
        int userId = 12345;
        String gameServicePackageName = "game.service.package";
        String gameServiceComponent1 = "game.service.package.example.GameService1";
        String gameServiceComponent2 = "game.service.package.example.GameService2";
        seedSystemGameServicePackageName(gameServicePackageName);
        seedGameServiceResolveInfos(gameServicePackageName, userId, ImmutableList.of(
                resolveInfo(serviceInfo(gameServicePackageName, gameServiceComponent1, true)),
                resolveInfo(serviceInfo(gameServicePackageName, gameServiceComponent2, true))));

        GameServiceController gameServiceController =
                new GameServiceController(mMockContext);

        gameServiceController.notifyUserStarted(eligibleTargetUser(userId));
        gameServiceController.onBootComplete();

        verifyServiceBoundForUserAndComponent(userId, gameServicePackageName,
                gameServiceComponent1);
    }

    @Test
    public void testStartConnectionOnBootWithUserAndSystemGamesServiceHasDisabledComponent() {
        int userId = 12345;
        String gameServicePackageName = "game.service.package";
        String gameServiceComponent1 = "game.service.package.example.GameService1";
        String gameServiceComponent2 = "game.service.package.example.GameService2";
        seedSystemGameServicePackageName(gameServicePackageName);
        seedGameServiceResolveInfos(gameServicePackageName, userId, ImmutableList.of(
                resolveInfo(serviceInfo(gameServicePackageName, gameServiceComponent1, false)),
                resolveInfo(serviceInfo(gameServicePackageName, gameServiceComponent2, true))));

        GameServiceController gameServiceController =
                new GameServiceController(mMockContext);

        gameServiceController.notifyUserStarted(eligibleTargetUser(userId));
        gameServiceController.onBootComplete();

        verifyServiceBoundForUserAndComponent(userId, gameServicePackageName,
                gameServiceComponent2);
    }

    @Test
    public void testSwitchFromEligibleUserToEligibleUser() {
        int userId1 = 1;
        int userId2 = 2;
        String gameServicePackageName = "game.service.package";
        String gameServiceComponent = "game.service.package.example.GameService";
        seedSystemGameServicePackageName(gameServicePackageName);
        seedGameServiceResolveInfos(gameServicePackageName, userId1, ImmutableList.of(
                resolveInfo(serviceInfo(gameServicePackageName, gameServiceComponent, true))));
        seedGameServiceResolveInfos(gameServicePackageName, userId2, ImmutableList.of(
                resolveInfo(serviceInfo(gameServicePackageName, gameServiceComponent, true))));
        seedGameServiceToBindSuccessfully();

        GameServiceController gameServiceController = new GameServiceController(mMockContext);

        gameServiceController.onBootComplete();
        gameServiceController.notifyUserStarted(eligibleTargetUser(userId1));

        verifyServiceBoundForUserAndComponent(userId1, gameServicePackageName,
                gameServiceComponent);

        gameServiceController.notifyNewForegroundUser(eligibleTargetUser(userId2));

        verify(mMockContext).unbindService(any());
        verifyServiceBoundForUserAndComponent(userId2, gameServicePackageName,
                gameServiceComponent);
    }

    @Test
    public void testSwitchFromEligibleUserToIneligibleUser() {
        int eligibleUserId = 1;
        int ineligibleUserId = 2;
        String gameServicePackageName = "game.service.package";
        String gameServiceComponent = "game.service.package.example.GameService";
        seedSystemGameServicePackageName(gameServicePackageName);
        seedGameServiceResolveInfos(gameServicePackageName, eligibleUserId, ImmutableList.of(
                resolveInfo(serviceInfo(gameServicePackageName, gameServiceComponent, true))));
        seedGameServiceToBindSuccessfully();

        GameServiceController gameServiceController =
                new GameServiceController(mMockContext);

        gameServiceController.onBootComplete();
        gameServiceController.notifyUserStarted(eligibleTargetUser(eligibleUserId));

        verifyServiceBoundForUserAndComponent(eligibleUserId, gameServicePackageName,
                gameServiceComponent);

        gameServiceController.notifyNewForegroundUser(managedTargetUser(ineligibleUserId));

        verify(mMockContext).unbindService(any());
    }

    @Test
    public void testSwitchFromIneligibleUserToEligibleUser() {
        int eligibleUserId = 1;
        int ineligibleUserId = 2;
        String gameServicePackageName = "game.service.package";
        String gameServiceComponent = "game.service.package.example.GameService";
        seedSystemGameServicePackageName(gameServicePackageName);
        seedGameServiceResolveInfos(gameServicePackageName, eligibleUserId, ImmutableList.of(
                resolveInfo(serviceInfo(gameServicePackageName, gameServiceComponent, true))));
        seedGameServiceToBindSuccessfully();

        GameServiceController gameServiceController = new GameServiceController(mMockContext);

        gameServiceController.onBootComplete();
        gameServiceController.notifyUserStarted(managedTargetUser(ineligibleUserId));

        verifyNoServiceBound();

        gameServiceController.notifyNewForegroundUser(eligibleTargetUser(eligibleUserId));

        verifyServiceBoundForUserAndComponent(eligibleUserId, gameServicePackageName,
                gameServiceComponent);
    }

    @Test
    public void testMultipleRunningUsers() {
        int userId1 = 123;
        int userId2 = 456;
        String gameServicePackageName = "game.service.package";
        String gameServiceComponent = "game.service.package.example.GameService";
        seedSystemGameServicePackageName(gameServicePackageName);
        seedGameServiceResolveInfos(gameServicePackageName, userId1, ImmutableList.of(
                resolveInfo(serviceInfo(gameServicePackageName, gameServiceComponent, true))));
        seedGameServiceToBindSuccessfully();

        GameServiceController gameServiceController =
                new GameServiceController(mMockContext);

        gameServiceController.onBootComplete();
        gameServiceController.notifyUserStarted(eligibleTargetUser(userId1));
        gameServiceController.notifyUserStarted(eligibleTargetUser(userId2));

        verifyServiceBoundForUserAndComponent(userId1, gameServicePackageName,
                gameServiceComponent);
        verifyServiceNotBoundForUser(userId2);
        verify(mMockContext, never()).unbindService(any());
    }

    @Test
    public void testForegroundUserStopped() {
        int userId = 123123;
        String gameServicePackageName = "game.service.package";
        String gameServiceComponent = "game.service.package.example.GameService";
        seedSystemGameServicePackageName(gameServicePackageName);
        seedGameServiceResolveInfos(gameServicePackageName, userId, ImmutableList.of(
                resolveInfo(serviceInfo(gameServicePackageName, gameServiceComponent, true))));
        seedGameServiceToBindSuccessfully();

        GameServiceController gameServiceController =
                new GameServiceController(mMockContext);

        gameServiceController.onBootComplete();
        gameServiceController.notifyUserStarted(eligibleTargetUser(userId));

        verifyServiceBoundForUserAndComponent(userId, gameServicePackageName, gameServiceComponent);

        gameServiceController.notifyUserStopped(eligibleTargetUser(userId));

        verify(mMockContext).unbindService(any());
    }

    @Test
    public void testNonForegroundUserStopped() {
        int userId1 = 123;
        int userId2 = 456;
        String gameServicePackageName = "game.service.package";
        String gameServiceComponent = "game.service.package.example.GameService";
        seedSystemGameServicePackageName(gameServicePackageName);
        seedGameServiceResolveInfos(gameServicePackageName, userId1, ImmutableList.of(
                resolveInfo(serviceInfo(gameServicePackageName, gameServiceComponent, true))));
        seedGameServiceResolveInfos(gameServicePackageName, userId2, ImmutableList.of(
                resolveInfo(serviceInfo(gameServicePackageName, gameServiceComponent, true))));
        seedGameServiceToBindSuccessfully();

        GameServiceController gameServiceController =
                new GameServiceController(mMockContext);
        InOrder inOrder = Mockito.inOrder(mMockContext);

        gameServiceController.onBootComplete();
        gameServiceController.notifyUserStarted(eligibleTargetUser(userId1));

        inOrder.verify(mMockContext).bindServiceAsUser(any(), any(), anyInt(), userWithId(userId1));

        gameServiceController.notifyNewForegroundUser(eligibleTargetUser(userId2));

        inOrder.verify(mMockContext).unbindService(any());
        inOrder.verify(mMockContext).bindServiceAsUser(any(), any(), anyInt(), userWithId(userId2));

        gameServiceController.notifyUserStopped(eligibleTargetUser(userId1));

        inOrder.verify(mMockContext, never()).unbindService(any());
    }

    private void seedSystemGameServicePackageName(String gameServicePackageName) {
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockResources.getString(com.android.internal.R.string.config_systemGameService))
                .thenReturn(gameServicePackageName);
    }

    private void seedGameServiceResolveInfos(String gameServicePackageName, int userId,
            List<ResolveInfo> resolveInfos) {
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        doReturn(resolveInfos)
                .when(mMockPackageManager).queryIntentServicesAsUser(
                argThat(intent ->
                        intent != null
                                && intent.getAction().equals(GameService.SERVICE_INTERFACE)
                                && intent.getPackage().equals(gameServicePackageName)
                ),
                eq(PackageManager.MATCH_SYSTEM_ONLY),
                eq(userId));
    }

    private void seedGameServiceToBindSuccessfully() {
        when(mMockContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(true);
    }

    private void verifyNoServiceBound() {
        verify(mMockContext, never()).bindServiceAsUser(any(), any(), anyInt(), any());
    }

    private void verifyServiceBoundForUserAndComponent(int userId, String gameServicePackageName,
            String gameServiceComponent) {
        verify(mMockContext).bindServiceAsUser(
                argThat(intent -> intent.getAction().equals(GameService.SERVICE_INTERFACE)
                        && intent.getComponent().getPackageName().equals(gameServicePackageName)
                        && intent.getComponent().getClassName().equals(gameServiceComponent)),
                any(),
                anyInt(), argThat(userInfo -> userInfo.getIdentifier() == userId));
    }

    private void verifyServiceNotBoundForUser(int userId) {
        verify(mMockContext, never()).bindServiceAsUser(
                any(),
                any(),
                anyInt(), userWithId(userId));
    }
}
