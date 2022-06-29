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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyNoMoreInteractions;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.app.GameServiceProviderInstanceImplTest.FakeGameService.GameServiceState;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;

import android.Manifest;
import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManagerInternal;
import android.app.ActivityTaskManager;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.app.IProcessObserver;
import android.app.ITaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.Rect;
import android.net.Uri;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.service.games.CreateGameSessionRequest;
import android.service.games.CreateGameSessionResult;
import android.service.games.GameScreenshotResult;
import android.service.games.GameSessionViewHostConfiguration;
import android.service.games.GameStartedEvent;
import android.service.games.IGameService;
import android.service.games.IGameServiceController;
import android.service.games.IGameSession;
import android.service.games.IGameSessionController;
import android.service.games.IGameSessionService;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost.SurfacePackage;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.FunctionalUtils.ThrowingConsumer;
import com.android.internal.util.Preconditions;
import com.android.internal.util.ScreenshotHelper;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;
import com.android.server.wm.WindowManagerInternal.TaskSystemBarsListener;
import com.android.server.wm.WindowManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.Consumer;


/**
 * Unit tests for the {@link GameServiceProviderInstanceImpl}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public final class GameServiceProviderInstanceImplTest {

    private static final GameSessionViewHostConfiguration
            DEFAULT_GAME_SESSION_VIEW_HOST_CONFIGURATION =
            new GameSessionViewHostConfiguration(1, 500, 800);
    private static final int USER_ID = 10;
    private static final String APP_A_PACKAGE = "com.package.app.a";
    private static final ComponentName APP_A_MAIN_ACTIVITY =
            new ComponentName(APP_A_PACKAGE, "com.package.app.a.MainActivity");

    private static final String GAME_A_PACKAGE = "com.package.game.a";
    private static final ComponentName GAME_A_MAIN_ACTIVITY =
            new ComponentName(GAME_A_PACKAGE, "com.package.game.a.MainActivity");

    private static final String GAME_B_PACKAGE = "com.package.game.b";
    private static final ComponentName GAME_B_MAIN_ACTIVITY =
            new ComponentName(GAME_B_PACKAGE, "com.package.game.b.MainActivity");


    private static final Bitmap TEST_BITMAP;

    static {
        Picture picture = new Picture();
        Canvas canvas = picture.beginRecording(200, 100);
        Paint p = new Paint();
        p.setColor(Color.BLACK);
        canvas.drawCircle(10, 10, 10, p);
        picture.endRecording();
        TEST_BITMAP = Bitmap.createBitmap(picture);
    }

    private MockitoSession mMockingSession;
    private GameServiceProviderInstance mGameServiceProviderInstance;
    @Mock
    private ActivityManagerInternal mMockActivityManagerInternal;
    @Mock
    private IActivityTaskManager mMockActivityTaskManager;
    @Mock
    private WindowManagerService mMockWindowManagerService;
    @Mock
    private WindowManagerInternal mMockWindowManagerInternal;
    @Mock
    private ActivityTaskManagerInternal mActivityTaskManagerInternal;
    @Mock
    private IActivityManager mMockActivityManager;
    @Mock
    private ScreenshotHelper mMockScreenshotHelper;
    private MockContext mMockContext;
    private FakeGameClassifier mFakeGameClassifier;
    private FakeGameService mFakeGameService;
    private FakeServiceConnector<IGameService> mFakeGameServiceConnector;
    private FakeGameSessionService mFakeGameSessionService;
    private FakeServiceConnector<IGameSessionService> mFakeGameSessionServiceConnector;
    private ArrayList<ITaskStackListener> mTaskStackListeners;
    private ArrayList<IProcessObserver> mProcessObservers;
    private ArrayList<TaskSystemBarsListener> mTaskSystemBarsListeners;
    private ArrayList<RunningTaskInfo> mRunningTaskInfos;

    @Mock
    private PackageManager mMockPackageManager;

    @Before
    public void setUp() throws PackageManager.NameNotFoundException, RemoteException {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .startMocking();

        mMockContext = new MockContext(InstrumentationRegistry.getInstrumentation().getContext());

        mFakeGameClassifier = new FakeGameClassifier();
        mFakeGameClassifier.recordGamePackage(GAME_A_PACKAGE);
        mFakeGameClassifier.recordGamePackage(GAME_B_PACKAGE);

        mFakeGameService = new FakeGameService();
        mFakeGameServiceConnector = new FakeServiceConnector<>(mFakeGameService);
        mFakeGameSessionService = new FakeGameSessionService();
        mFakeGameSessionServiceConnector = new FakeServiceConnector<>(mFakeGameSessionService);

        mTaskStackListeners = new ArrayList<>();
        doAnswer(invocation -> {
            mTaskStackListeners.add(invocation.getArgument(0));
            return null;
        }).when(mMockActivityTaskManager).registerTaskStackListener(any());
        doAnswer(invocation -> {
            mTaskStackListeners.remove(invocation.getArgument(0));
            return null;
        }).when(mMockActivityTaskManager).unregisterTaskStackListener(any());

        mProcessObservers = new ArrayList<>();
        doAnswer(invocation -> {
            mProcessObservers.add(invocation.getArgument(0));
            return null;
        }).when(mMockActivityManager).registerProcessObserver(any());
        doAnswer(invocation -> {
            mProcessObservers.remove(invocation.getArgument(0));
            return null;
        }).when(mMockActivityManager).unregisterProcessObserver(any());

        mTaskSystemBarsListeners = new ArrayList<>();
        doAnswer(invocation -> {
            mTaskSystemBarsListeners.add(invocation.getArgument(0));
            return null;
        }).when(mMockWindowManagerInternal).registerTaskSystemBarsListener(any());
        doAnswer(invocation -> {
            mTaskSystemBarsListeners.remove(invocation.getArgument(0));
            return null;
        }).when(mMockWindowManagerInternal).unregisterTaskSystemBarsListener(any());

        mRunningTaskInfos = new ArrayList<>();
        when(mMockActivityTaskManager.getTasks(anyInt(), anyBoolean(), anyBoolean())).thenReturn(
                mRunningTaskInfos);


        final UserHandle userHandle = new UserHandle(USER_ID);
        mGameServiceProviderInstance = new GameServiceProviderInstanceImpl(
                userHandle,
                ConcurrentUtils.DIRECT_EXECUTOR,
                mMockContext,
                new GameTaskInfoProvider(userHandle, mMockActivityTaskManager, mFakeGameClassifier),
                mMockActivityManager,
                mMockActivityManagerInternal,
                mMockActivityTaskManager,
                mMockWindowManagerService,
                mMockWindowManagerInternal,
                mActivityTaskManagerInternal,
                mFakeGameServiceConnector,
                mFakeGameSessionServiceConnector,
                mMockScreenshotHelper);
    }

    @After
    public void tearDown() {
        mMockingSession.finishMocking();
    }

    @Test
    public void start_startsGameSession() throws Exception {
        mGameServiceProviderInstance.start();

        assertThat(mFakeGameService.getState()).isEqualTo(GameServiceState.CONNECTED);
        assertThat(mFakeGameServiceConnector.getIsConnected()).isTrue();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void start_multipleTimes_startsGameSessionOnce() throws Exception {
        mGameServiceProviderInstance.start();
        mGameServiceProviderInstance.start();

        assertThat(mFakeGameService.getState()).isEqualTo(GameServiceState.CONNECTED);
        assertThat(mFakeGameServiceConnector.getIsConnected()).isTrue();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void stop_neverStarted_doesNothing() throws Exception {
        mGameServiceProviderInstance.stop();


        assertThat(mFakeGameService.getState()).isEqualTo(GameServiceState.DISCONNECTED);
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(0);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void startAndStop_startsAndStopsGameSession() throws Exception {
        mGameServiceProviderInstance.start();
        mGameServiceProviderInstance.stop();

        assertThat(mFakeGameService.getState()).isEqualTo(GameServiceState.DISCONNECTED);
        assertThat(mFakeGameService.getConnectedCount()).isEqualTo(1);
        assertThat(mFakeGameServiceConnector.getIsConnected()).isFalse();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void startAndStop_multipleTimes_startsAndStopsGameSessionMultipleTimes()
            throws Exception {
        mGameServiceProviderInstance.start();
        mGameServiceProviderInstance.stop();
        mGameServiceProviderInstance.start();
        mGameServiceProviderInstance.stop();

        assertThat(mFakeGameService.getState()).isEqualTo(GameServiceState.DISCONNECTED);
        assertThat(mFakeGameService.getConnectedCount()).isEqualTo(2);
        assertThat(mFakeGameServiceConnector.getIsConnected()).isFalse();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(2);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void stop_stopMultipleTimes_stopsGameSessionOnce() throws Exception {
        mGameServiceProviderInstance.start();
        mGameServiceProviderInstance.stop();
        mGameServiceProviderInstance.stop();

        assertThat(mFakeGameService.getState()).isEqualTo(GameServiceState.DISCONNECTED);
        assertThat(mFakeGameService.getConnectedCount()).isEqualTo(1);
        assertThat(mFakeGameServiceConnector.getIsConnected()).isFalse();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void gameTaskStarted_neverStarted_doesNothing() throws Exception {
        dispatchTaskCreated(10, GAME_A_MAIN_ACTIVITY);

        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(0);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void gameTaskRemoved_neverStarted_doesNothing() throws Exception {
        dispatchTaskRemoved(10);

        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(0);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void gameTaskStarted_afterStopped_doesNotSendGameStartedEvent() throws Exception {
        mGameServiceProviderInstance.start();
        mGameServiceProviderInstance.stop();
        dispatchTaskCreated(10, GAME_A_MAIN_ACTIVITY);

        assertThat(mFakeGameService.getGameStartedEvents()).isEmpty();
    }

    @Test
    public void appTaskStarted_doesNotSendGameStartedEvent() throws Exception {
        mGameServiceProviderInstance.start();
        dispatchTaskCreated(10, APP_A_MAIN_ACTIVITY);

        assertThat(mFakeGameService.getGameStartedEvents()).isEmpty();
    }

    @Test
    public void taskStarted_nullComponentName_ignoresAndDoesNotCrash() throws Exception {
        mGameServiceProviderInstance.start();
        dispatchTaskCreated(10, null);

        assertThat(mFakeGameService.getGameStartedEvents()).isEmpty();
    }

    @Test
    public void gameSessionRequested_withoutTaskDispatch_doesNotCrashAndDoesNotCreateGameSession()
            throws Exception {
        mGameServiceProviderInstance.start();

        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        assertThat(mFakeGameSessionService.getCapturedCreateInvocations()).isEmpty();
    }

    @Test
    public void gameTaskStarted_noSessionRequest_callsStartGame() throws Exception {
        mGameServiceProviderInstance.start();
        dispatchTaskCreated(10, GAME_A_MAIN_ACTIVITY);

        GameStartedEvent expectedGameStartedEvent = new GameStartedEvent(10, GAME_A_PACKAGE);
        assertThat(mFakeGameService.getGameStartedEvents())
                .containsExactly(expectedGameStartedEvent).inOrder();
    }

    @Test
    public void gameTaskStarted_requestToCreateGameSessionIncludesTaskConfiguration()
            throws Exception {
        mGameServiceProviderInstance.start();
        startTask(10, GAME_A_MAIN_ACTIVITY);

        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSessionService.CapturedCreateInvocation capturedCreateInvocation =
                getOnlyElement(mFakeGameSessionService.getCapturedCreateInvocations());
        assertThat(capturedCreateInvocation.mGameSessionViewHostConfiguration)
                .isEqualTo(DEFAULT_GAME_SESSION_VIEW_HOST_CONFIGURATION);
    }

    @Test
    public void gameTaskStarted_failsToDetermineTaskOverlayConfiguration_gameSessionNotCreated()
            throws Exception {
        mGameServiceProviderInstance.start();
        dispatchTaskCreated(10, GAME_A_MAIN_ACTIVITY);

        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        assertThat(mFakeGameSessionService.getCapturedCreateInvocations()).isEmpty();
    }

    @Test
    public void gameTaskStartedAndSessionRequested_createsGameSession() throws Exception {
        mGameServiceProviderInstance.start();
        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        assertThat(gameSession10.mIsDestroyed).isFalse();
        assertThat(gameSession10.mIsFocused).isFalse();
    }

    @Test
    public void gameTaskStartedAndSessionRequested_secondSessionRequest_ignoredAndDoesNotCrash()
            throws Exception {
        mGameServiceProviderInstance.start();
        startTask(10, GAME_A_MAIN_ACTIVITY);

        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        CreateGameSessionRequest expectedCreateGameSessionRequest = new CreateGameSessionRequest(10,
                GAME_A_PACKAGE);
        assertThat(getOnlyElement(
                mFakeGameSessionService.getCapturedCreateInvocations()).mCreateGameSessionRequest)
                .isEqualTo(expectedCreateGameSessionRequest);
    }

    @Test
    public void gameSessionSuccessfullyCreated_createsTaskOverlay() throws Exception {
        mGameServiceProviderInstance.start();
        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        verify(mMockWindowManagerInternal).addTrustedTaskOverlay(eq(10), eq(mockSurfacePackage10));
    }

    @Test
    public void gameProcessStopped_soleProcess_destroysGameSession() throws Exception {
        int gameProcessId = 1000;

        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        startProcessForPackage(gameProcessId, GAME_A_PACKAGE);

        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));
        assertThat(gameSession10.mIsDestroyed).isFalse();

        // Death of the sole game process destroys the game session.
        dispatchProcessDied(gameProcessId);
        assertThat(gameSession10.mIsDestroyed).isTrue();
    }

    @Test
    public void gameProcessStopped_soleProcess_destroysMultipleGameSessionsForSamePackage()
            throws Exception {
        int gameProcessId = 1000;

        mGameServiceProviderInstance.start();

        // Multiple tasks exist for the same package.
        startTask(10, GAME_A_MAIN_ACTIVITY);
        startTask(11, GAME_A_MAIN_ACTIVITY);
        startProcessForPackage(gameProcessId, GAME_A_PACKAGE);

        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);
        mFakeGameService.requestCreateGameSession(11);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));
        FakeGameSession gameSession11 = new FakeGameSession();
        SurfacePackage mockSurfacePackage11 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(11)
                .complete(new CreateGameSessionResult(gameSession11, mockSurfacePackage11));

        assertThat(gameSession10.mIsDestroyed).isFalse();
        assertThat(gameSession11.mIsDestroyed).isFalse();

        // Death of the sole game process destroys both game sessions.
        dispatchProcessDied(gameProcessId);
        assertThat(gameSession10.mIsDestroyed).isTrue();
        assertThat(gameSession11.mIsDestroyed).isTrue();
    }

    @Test
    public void gameProcessStopped_multipleProcesses_gameSessionDestroyedWhenAllDead()
            throws Exception {
        int firstGameProcessId = 1000;
        int secondGameProcessId = 1001;

        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        startProcessForPackage(firstGameProcessId, GAME_A_PACKAGE);
        startProcessForPackage(secondGameProcessId, GAME_A_PACKAGE);

        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));
        assertThat(gameSession10.mIsDestroyed).isFalse();

        // Death of the first process (with the second one still alive) does not destroy the game
        // session.
        dispatchProcessDied(firstGameProcessId);
        assertThat(gameSession10.mIsDestroyed).isFalse();

        // Death of the second process does destroy the game session.
        dispatchProcessDied(secondGameProcessId);
        assertThat(gameSession10.mIsDestroyed).isTrue();
    }

    @Test
    public void gameProcessCreatedAfterInitialProcessDead_newGameSessionCreated() throws Exception {
        int firstGameProcessId = 1000;
        int secondGameProcessId = 1000;

        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        startProcessForPackage(firstGameProcessId, GAME_A_PACKAGE);

        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));
        assertThat(gameSession10.mIsDestroyed).isFalse();

        // After the first game process dies, the game session should be destroyed.
        dispatchProcessDied(firstGameProcessId);
        assertThat(gameSession10.mIsDestroyed).isTrue();

        // However, when a new process for the game starts, a new game session should be created.
        startProcessForPackage(secondGameProcessId, GAME_A_PACKAGE);
        // Verify that a new pending game session is created for the game's taskId.
        assertNotNull(mFakeGameSessionService.removePendingFutureForTaskId(10));
    }

    @Test
    public void gameProcessCreatedAfterInitialProcessDead_multipleGameSessionsCreatedSamePackage()
            throws Exception {
        int firstGameProcessId = 1000;
        int secondGameProcessId = 1000;

        mGameServiceProviderInstance.start();

        // Multiple tasks exist for the same package.
        startTask(10, GAME_A_MAIN_ACTIVITY);
        startTask(11, GAME_A_MAIN_ACTIVITY);
        startProcessForPackage(firstGameProcessId, GAME_A_PACKAGE);

        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);

        mFakeGameService.requestCreateGameSession(10);
        mFakeGameService.requestCreateGameSession(11);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));
        FakeGameSession gameSession11 = new FakeGameSession();
        SurfacePackage mockSurfacePackage11 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(11)
                .complete(new CreateGameSessionResult(gameSession11, mockSurfacePackage11));

        assertThat(gameSession10.mIsDestroyed).isFalse();
        assertThat(gameSession11.mIsDestroyed).isFalse();

        // After the first game process dies, both game sessions for the package should be
        // destroyed.
        dispatchProcessDied(firstGameProcessId);
        assertThat(gameSession10.mIsDestroyed).isTrue();
        assertThat(gameSession11.mIsDestroyed).isTrue();

        // However, when a new process for the game starts, new game sessions for the same
        // package should be created.
        startProcessForPackage(secondGameProcessId, GAME_A_PACKAGE);
        // Verify that new pending game sessions were created for each of the game's taskIds.
        assertNotNull(mFakeGameSessionService.removePendingFutureForTaskId(10));
        assertNotNull(mFakeGameSessionService.removePendingFutureForTaskId(11));
    }

    @Test
    public void gameProcessStarted_gameSessionNotRequested_doesNothing() throws Exception {
        int gameProcessId = 1000;

        mGameServiceProviderInstance.start();

        // A game task and process are started, but requestCreateGameSession is never called.
        startTask(10, GAME_A_MAIN_ACTIVITY);
        startProcessForPackage(gameProcessId, GAME_A_PACKAGE);

        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);

        // No game session should be created.
        assertThat(mFakeGameSessionService.getCapturedCreateInvocations()).isEmpty();
    }

    @Test
    public void processActivityAndDeath_notForGame_gameSessionUnaffected() throws Exception {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);

        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        // Process activity for a process without a known package is ignored.
        startProcessForPackage(1000, /*packageName=*/ null);
        dispatchProcessActivity(1000);
        dispatchProcessDied(1000);

        // Process activity for a process with a different package is ignored
        startProcessForPackage(1001, GAME_B_PACKAGE);
        dispatchProcessActivity(1001);
        dispatchProcessDied(1001);

        // Death of a process for which there was no activity is ignored
        dispatchProcessDied(1002);

        // Despite all the process activity and death, the game session is not destroyed.
        assertThat(gameSession10.mIsDestroyed).isFalse();
    }

    @Test
    public void taskSystemBarsListenerChanged_noAssociatedGameSession_doesNothing() {
        mGameServiceProviderInstance.start();

        dispatchTaskSystemBarsEvent(taskSystemBarsListener -> {
            taskSystemBarsListener.onTransientSystemBarsVisibilityChanged(
                    10,
                    /* areVisible= */ false,
                    /* wereRevealedFromSwipeOnSystemBar= */ false);
        });
    }

    @Test
    public void systemBarsTransientShownDueToGesture_hasGameSession_propagatesToGameSession() {
        mGameServiceProviderInstance.start();
        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        dispatchTaskSystemBarsEvent(taskSystemBarsListener -> {
            taskSystemBarsListener.onTransientSystemBarsVisibilityChanged(
                    10,
                    /* areVisible= */ true,
                    /* wereRevealedFromSwipeOnSystemBar= */ true);
        });

        assertThat(gameSession10.mAreTransientSystemBarsVisibleFromRevealGesture).isTrue();
    }

    @Test
    public void systemBarsTransientShownButNotGesture_hasGameSession_notPropagatedToGameSession() {
        mGameServiceProviderInstance.start();
        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        dispatchTaskSystemBarsEvent(taskSystemBarsListener -> {
            taskSystemBarsListener.onTransientSystemBarsVisibilityChanged(
                    10,
                    /* areVisible= */ true,
                    /* wereRevealedFromSwipeOnSystemBar= */ false);
        });

        assertThat(gameSession10.mAreTransientSystemBarsVisibleFromRevealGesture).isFalse();
    }

    @Test
    public void gameTaskFocused_propagatedToGameSession() throws Exception {
        mGameServiceProviderInstance.start();
        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        assertThat(gameSession10.mIsFocused).isFalse();

        dispatchTaskFocused(10, /*focused=*/ true);
        assertThat(gameSession10.mIsFocused).isTrue();

        dispatchTaskFocused(10, /*focused=*/ false);
        assertThat(gameSession10.mIsFocused).isFalse();
    }

    @Test
    public void gameTaskAlreadyFocusedWhenGameSessionCreated_propagatedToGameSession()
            throws Exception {
        ActivityTaskManager.RootTaskInfo gameATaskInfo = new ActivityTaskManager.RootTaskInfo();
        gameATaskInfo.taskId = 10;
        when(mMockActivityTaskManager.getFocusedRootTaskInfo()).thenReturn(gameATaskInfo);

        mGameServiceProviderInstance.start();
        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        assertThat(gameSession10.mIsFocused).isTrue();
    }

    @Test
    public void gameTaskRemoved_whileAwaitingGameSessionAttached_destroysGameSession()
            throws Exception {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        dispatchTaskRemoved(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        assertThat(gameSession10.mIsDestroyed).isTrue();
    }

    @Test
    public void gameTaskRemoved_whileGameSessionAttached_destroysGameSession() throws Exception {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        dispatchTaskRemoved(10);

        assertThat(gameSession10.mIsDestroyed).isTrue();
    }

    @Test
    public void gameTaskFocusedWithCreateAfterRemoved_gameSessionRecreated() throws Exception {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        stopTask(10);

        assertThat(gameSession10.mIsDestroyed).isTrue();

        // If the game task is restored via the Recents UI, the task will be running again but
        // we would not expect any call to TaskStackListener#onTaskCreated.
        addRunningTaskInfo(10, GAME_A_MAIN_ACTIVITY);

        // We now receive a task focused event for the task. This will occur if the game task is
        // restored via the Recents UI.
        dispatchTaskFocused(10, /*focused=*/ true);
        mFakeGameService.requestCreateGameSession(10);

        // Verify that a new pending game session is created for the game's taskId.
        assertNotNull(mFakeGameSessionService.removePendingFutureForTaskId(10));
    }

    @Test
    public void gameTaskRemoved_removesTaskOverlay() throws Exception {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        stopTask(10);

        verify(mMockWindowManagerInternal).addTrustedTaskOverlay(eq(10), eq(mockSurfacePackage10));
        verify(mMockWindowManagerInternal).removeTrustedTaskOverlay(eq(10),
                eq(mockSurfacePackage10));
    }

    @Test
    public void gameTaskStartedAndSessionRequested_multipleTimes_createsMultipleGameSessions()
            throws Exception {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        startTask(11, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(11);

        FakeGameSession gameSession11 = new FakeGameSession();
        SurfacePackage mockSurfacePackage11 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(11)
                .complete(new CreateGameSessionResult(gameSession11, mockSurfacePackage11));

        assertThat(gameSession10.mIsDestroyed).isFalse();
        assertThat(gameSession11.mIsDestroyed).isFalse();
    }

    @Test
    public void gameTaskStartedTwice_sessionRequestedSecondTimeOnly_createsOneGameSession()
            throws Exception {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        startTask(11, GAME_A_MAIN_ACTIVITY);

        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        assertThat(gameSession10.mIsDestroyed).isFalse();
        assertThat(mFakeGameSessionService.getCapturedCreateInvocations()).hasSize(1);
    }

    @Test
    public void gameTaskRemoved_multipleSessions_destroysOnlyThatGameSession()
            throws Exception {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        startTask(11, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(11);

        FakeGameSession gameSession11 = new FakeGameSession();
        SurfacePackage mockSurfacePackage11 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(11)
                .complete(new CreateGameSessionResult(gameSession11, mockSurfacePackage11));

        dispatchTaskRemoved(10);

        assertThat(gameSession10.mIsDestroyed).isTrue();
        assertThat(gameSession11.mIsDestroyed).isFalse();
        assertThat(mFakeGameSessionServiceConnector.getIsConnected()).isTrue();
    }

    @Test
    public void allGameTasksRemoved_destroysAllGameSessionsAndGameSessionServiceIsDisconnected() {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        startTask(11, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(11);

        FakeGameSession gameSession11 = new FakeGameSession();
        SurfacePackage mockSurfacePackage11 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(11)
                .complete(new CreateGameSessionResult(gameSession11, mockSurfacePackage11));

        dispatchTaskRemoved(10);
        dispatchTaskRemoved(11);

        assertThat(gameSession10.mIsDestroyed).isTrue();
        assertThat(gameSession11.mIsDestroyed).isTrue();
        assertThat(mFakeGameSessionServiceConnector.getIsConnected()).isFalse();
    }

    @Test
    public void createSessionRequested_afterAllPreviousSessionsDestroyed_createsSession()
            throws Exception {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        startTask(11, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(11);

        FakeGameSession gameSession11 = new FakeGameSession();
        SurfacePackage mockSurfacePackage11 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(11)
                .complete(new CreateGameSessionResult(gameSession11, mockSurfacePackage11));

        dispatchTaskRemoved(10);
        dispatchTaskRemoved(11);

        startTask(12, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(12);

        FakeGameSession gameSession12 = new FakeGameSession();
        SurfacePackage mockSurfacePackage12 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(12)
                .complete(new CreateGameSessionResult(gameSession12, mockSurfacePackage12));

        assertThat(gameSession10.mIsDestroyed).isTrue();
        assertThat(gameSession11.mIsDestroyed).isTrue();
        assertThat(gameSession12.mIsDestroyed).isFalse();
        assertThat(mFakeGameSessionServiceConnector.getIsConnected()).isTrue();
    }

    @Test
    public void createGameSession_failurePermissionDenied() throws Exception {
        mGameServiceProviderInstance.start();
        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionDenied(Manifest.permission.MANAGE_GAME_ACTIVITY);
        assertThrows(SecurityException.class, () -> mFakeGameService.requestCreateGameSession(10));
    }

    @Test
    public void gameSessionServiceDies_severalActiveGameSessions_destroysGameSessions() {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        startTask(11, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(11);

        FakeGameSession gameSession11 = new FakeGameSession();
        SurfacePackage mockSurfacePackage11 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(11)
                .complete(new CreateGameSessionResult(gameSession11, mockSurfacePackage11));

        mFakeGameSessionServiceConnector.killServiceProcess();

        assertThat(gameSession10.mIsDestroyed).isTrue();
        assertThat(gameSession11.mIsDestroyed).isTrue();
        assertThat(mFakeGameServiceConnector.getIsConnected()).isTrue();
        assertThat(mFakeGameSessionServiceConnector.getIsConnected()).isFalse();
    }

    @Test
    public void stop_severalActiveGameSessions_destroysGameSessionsAndUnbinds() throws Exception {
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        startTask(11, GAME_A_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(11);

        FakeGameSession gameSession11 = new FakeGameSession();
        SurfacePackage mockSurfacePackage11 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(11)
                .complete(new CreateGameSessionResult(gameSession11, mockSurfacePackage11));

        mGameServiceProviderInstance.stop();

        assertThat(gameSession10.mIsDestroyed).isTrue();
        assertThat(gameSession11.mIsDestroyed).isTrue();
        assertThat(mFakeGameServiceConnector.getIsConnected()).isFalse();
        assertThat(mFakeGameSessionServiceConnector.getIsConnected()).isFalse();
    }

    @Test
    public void takeScreenshot_failureNoBitmapCaptured() throws Exception {
        mGameServiceProviderInstance.start();
        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockOverlaySurfacePackage = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockOverlaySurfacePackage));

        IGameSessionController gameSessionController = getOnlyElement(
                mFakeGameSessionService.getCapturedCreateInvocations()).mGameSessionController;
        AndroidFuture<GameScreenshotResult> resultFuture = new AndroidFuture<>();
        gameSessionController.takeScreenshot(10, resultFuture);

        GameScreenshotResult result = resultFuture.get();
        assertEquals(GameScreenshotResult.GAME_SCREENSHOT_ERROR_INTERNAL_ERROR,
                result.getStatus());

        verify(mMockWindowManagerService).captureTaskBitmap(eq(10), any());
    }

    @Test
    public void takeScreenshot_success() throws Exception {
        SurfaceControl mockOverlaySurfaceControl = Mockito.mock(SurfaceControl.class);
        SurfaceControl[] excludeLayers = new SurfaceControl[1];
        excludeLayers[0] = mockOverlaySurfaceControl;
        int taskId = 10;
        when(mMockWindowManagerService.captureTaskBitmap(eq(10), any())).thenReturn(TEST_BITMAP);
        doAnswer(invocation -> {
            Consumer<Uri> consumer = invocation.getArgument(invocation.getArguments().length - 1);
            consumer.accept(Uri.parse("a/b.png"));
            return null;
        }).when(mMockScreenshotHelper).provideScreenshot(
                any(), any(), any(), anyInt(), anyInt(), any(), anyInt(), any(), any());
        mGameServiceProviderInstance.start();
        startTask(taskId, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(taskId);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockOverlaySurfacePackage = Mockito.mock(SurfacePackage.class);
        when(mockOverlaySurfacePackage.getSurfaceControl()).thenReturn(mockOverlaySurfaceControl);
        mFakeGameSessionService.removePendingFutureForTaskId(taskId)
                .complete(new CreateGameSessionResult(gameSession10, mockOverlaySurfacePackage));

        IGameSessionController gameSessionController = getOnlyElement(
                mFakeGameSessionService.getCapturedCreateInvocations()).mGameSessionController;
        AndroidFuture<GameScreenshotResult> resultFuture = new AndroidFuture<>();
        gameSessionController.takeScreenshot(taskId, resultFuture);

        GameScreenshotResult result = resultFuture.get();
        assertEquals(GameScreenshotResult.GAME_SCREENSHOT_SUCCESS, result.getStatus());
    }

    @Test
    public void restartGame_taskIdAssociatedWithGame_restartsTargetGame() throws Exception {
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        Intent launchIntent = new Intent("com.test.ACTION_LAUNCH_GAME_PACKAGE")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        when(mMockPackageManager.getLaunchIntentForPackage(GAME_A_PACKAGE))
                .thenReturn(launchIntent);

        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        startTask(11, GAME_B_MAIN_ACTIVITY);
        mFakeGameService.requestCreateGameSession(11);

        FakeGameSession gameSession11 = new FakeGameSession();
        SurfacePackage mockSurfacePackage11 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(11)
                .complete(new CreateGameSessionResult(gameSession11, mockSurfacePackage11));

        mFakeGameSessionService.getCapturedCreateInvocations().get(0)
                .mGameSessionController.restartGame(10);

        verify(mActivityTaskManagerInternal).restartTaskActivityProcessIfVisible(
                10,
                GAME_A_PACKAGE);
    }

    @Test
    public void restartGame_taskIdNotAssociatedWithGame_noOp() throws Exception {
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mGameServiceProviderInstance.start();

        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);

        FakeGameSession gameSession10 = new FakeGameSession();
        SurfacePackage mockSurfacePackage10 = Mockito.mock(SurfacePackage.class);
        mFakeGameSessionService.removePendingFutureForTaskId(10)
                .complete(new CreateGameSessionResult(gameSession10, mockSurfacePackage10));

        getOnlyElement(
                mFakeGameSessionService.getCapturedCreateInvocations())
                .mGameSessionController.restartGame(11);

        verify(mMockActivityManager).registerProcessObserver(any());
        verifyNoMoreInteractions(mMockActivityManager);
        verify(mActivityTaskManagerInternal, never())
                .restartTaskActivityProcessIfVisible(anyInt(), anyString());
    }

    @Test
    public void restartGame_failurePermissionDenied() throws Exception {
        mGameServiceProviderInstance.start();
        startTask(10, GAME_A_MAIN_ACTIVITY);
        mockPermissionGranted(Manifest.permission.MANAGE_GAME_ACTIVITY);
        mFakeGameService.requestCreateGameSession(10);
        IGameSessionController gameSessionController = Objects.requireNonNull(getOnlyElement(
                mFakeGameSessionService.getCapturedCreateInvocations())).mGameSessionController;
        mockPermissionDenied(Manifest.permission.MANAGE_GAME_ACTIVITY);
        assertThrows(SecurityException.class,
                () -> gameSessionController.restartGame(10));
        verify(mActivityTaskManagerInternal, never())
                .restartTaskActivityProcessIfVisible(anyInt(), anyString());
    }

    private void startTask(int taskId, ComponentName componentName) {
        addRunningTaskInfo(taskId, componentName);

        dispatchTaskCreated(taskId, componentName);
    }

    private void addRunningTaskInfo(int taskId, ComponentName componentName) {
        RunningTaskInfo runningTaskInfo = new RunningTaskInfo();
        runningTaskInfo.taskId = taskId;
        runningTaskInfo.baseActivity = componentName;
        runningTaskInfo.displayId = 1;
        runningTaskInfo.configuration.windowConfiguration.setBounds(new Rect(0, 0, 500, 800));
        mRunningTaskInfos.add(runningTaskInfo);
    }

    private void stopTask(int taskId) {
        mRunningTaskInfos.removeIf(runningTaskInfo -> runningTaskInfo.taskId == taskId);
        dispatchTaskRemoved(taskId);
    }

    private void dispatchTaskRemoved(int taskId) {
        dispatchTaskChangeEvent(taskStackListener -> {
            taskStackListener.onTaskRemoved(taskId);
        });
    }

    private void dispatchTaskCreated(int taskId, @Nullable ComponentName componentName) {
        dispatchTaskChangeEvent(taskStackListener -> {
            taskStackListener.onTaskCreated(taskId, componentName);
        });
    }

    private void dispatchTaskFocused(int taskId, boolean focused) {
        dispatchTaskChangeEvent(taskStackListener -> {
            taskStackListener.onTaskFocusChanged(taskId, focused);
        });
    }

    private void dispatchTaskChangeEvent(
            ThrowingConsumer<ITaskStackListener> taskStackListenerConsumer) {
        for (ITaskStackListener taskStackListener : mTaskStackListeners) {
            taskStackListenerConsumer.accept(taskStackListener);
        }
    }

    private void startProcessForPackage(int processId, @Nullable String packageName) {
        if (packageName != null) {
            when(mMockActivityManagerInternal.getPackageNameByPid(processId)).thenReturn(
                    packageName);
        }

        dispatchProcessActivity(processId);
    }

    private void dispatchProcessActivity(int processId) {
        dispatchProcessChangedEvent(processObserver -> {
            // Neither uid nor foregroundActivities are used by the implementation being tested.
            processObserver.onForegroundActivitiesChanged(processId, /*uid=*/
                    0, /*foregroundActivities=*/ false);
        });
    }

    private void dispatchProcessDied(int processId) {
        dispatchProcessChangedEvent(processObserver -> {
            // The uid param is not used by the implementation being tested.
            processObserver.onProcessDied(processId, /*uid=*/ 0);
        });
    }

    private void dispatchProcessChangedEvent(
            ThrowingConsumer<IProcessObserver> processObserverConsumer) {
        for (IProcessObserver processObserver : mProcessObservers) {
            processObserverConsumer.accept(processObserver);
        }
    }

    private void mockPermissionGranted(String permission) {
        mMockContext.setPermission(permission, PackageManager.PERMISSION_GRANTED);
    }

    private void mockPermissionDenied(String permission) {
        mMockContext.setPermission(permission, PackageManager.PERMISSION_DENIED);
    }

    private void dispatchTaskSystemBarsEvent(
            ThrowingConsumer<TaskSystemBarsListener> taskSystemBarsListenerConsumer) {
        for (TaskSystemBarsListener listener : mTaskSystemBarsListeners) {
            taskSystemBarsListenerConsumer.accept(listener);
        }
    }

    static final class FakeGameService extends IGameService.Stub {
        private IGameServiceController mGameServiceController;

        public enum GameServiceState {
            DISCONNECTED,
            CONNECTED,
        }

        private ArrayList<GameStartedEvent> mGameStartedEvents = new ArrayList<>();
        private int mConnectedCount = 0;
        private GameServiceState mGameServiceState = GameServiceState.DISCONNECTED;

        public GameServiceState getState() {
            return mGameServiceState;
        }

        public int getConnectedCount() {
            return mConnectedCount;
        }

        public ArrayList<GameStartedEvent> getGameStartedEvents() {
            return mGameStartedEvents;
        }

        @Override
        public void connected(IGameServiceController gameServiceController) {
            Preconditions.checkState(mGameServiceState == GameServiceState.DISCONNECTED);

            mGameServiceState = GameServiceState.CONNECTED;
            mConnectedCount += 1;
            mGameServiceController = gameServiceController;
        }

        @Override
        public void disconnected() {
            Preconditions.checkState(mGameServiceState == GameServiceState.CONNECTED);

            mGameServiceState = GameServiceState.DISCONNECTED;
            mGameServiceController = null;
        }

        @Override
        public void gameStarted(GameStartedEvent gameStartedEvent) {
            Preconditions.checkState(mGameServiceState == GameServiceState.CONNECTED);

            mGameStartedEvents.add(gameStartedEvent);
        }

        public void requestCreateGameSession(int task) {
            Preconditions.checkState(mGameServiceState == GameServiceState.CONNECTED);

            try {
                mGameServiceController.createGameSession(task);
            } catch (RemoteException ex) {
                throw new AssertionError(ex);
            }
        }
    }

    static final class FakeGameSessionService extends IGameSessionService.Stub {

        private final ArrayList<CapturedCreateInvocation> mCapturedCreateInvocations =
                new ArrayList<>();
        private final HashMap<Integer, AndroidFuture<CreateGameSessionResult>>
                mPendingCreateGameSessionResultFutures =
                new HashMap<>();

        public static final class CapturedCreateInvocation {
            private final IGameSessionController mGameSessionController;
            private final CreateGameSessionRequest mCreateGameSessionRequest;
            private final GameSessionViewHostConfiguration mGameSessionViewHostConfiguration;

            CapturedCreateInvocation(
                    IGameSessionController gameSessionController,
                    CreateGameSessionRequest createGameSessionRequest,
                    GameSessionViewHostConfiguration gameSessionViewHostConfiguration) {
                mGameSessionController = gameSessionController;
                mCreateGameSessionRequest = createGameSessionRequest;
                mGameSessionViewHostConfiguration = gameSessionViewHostConfiguration;
            }
        }

        public ArrayList<CapturedCreateInvocation> getCapturedCreateInvocations() {
            return mCapturedCreateInvocations;
        }

        public AndroidFuture<CreateGameSessionResult> removePendingFutureForTaskId(int taskId) {
            return mPendingCreateGameSessionResultFutures.remove(taskId);
        }

        @Override
        public void create(
                IGameSessionController gameSessionController,
                CreateGameSessionRequest createGameSessionRequest,
                GameSessionViewHostConfiguration gameSessionViewHostConfiguration,
                AndroidFuture createGameSessionResultFuture) {

            mCapturedCreateInvocations.add(
                    new CapturedCreateInvocation(
                            gameSessionController,
                            createGameSessionRequest,
                            gameSessionViewHostConfiguration));

            Preconditions.checkState(!mPendingCreateGameSessionResultFutures.containsKey(
                    createGameSessionRequest.getTaskId()));
            mPendingCreateGameSessionResultFutures.put(
                    createGameSessionRequest.getTaskId(),
                    createGameSessionResultFuture);
        }
    }

    private static class FakeGameSession extends IGameSession.Stub {
        boolean mIsDestroyed = false;
        boolean mIsFocused = false;
        boolean mAreTransientSystemBarsVisibleFromRevealGesture = false;

        @Override
        public void onDestroyed() {
            mIsDestroyed = true;
        }

        @Override
        public void onTaskFocusChanged(boolean focused) {
            mIsFocused = focused;
        }

        @Override
        public void onTransientSystemBarVisibilityFromRevealGestureChanged(boolean areVisible) {
            mAreTransientSystemBarsVisibleFromRevealGesture = areVisible;
        }
    }

    private final class MockContext extends ContextWrapper {
        // Map of permission name -> PermissionManager.Permission_{GRANTED|DENIED} constant
        private final HashMap<String, Integer> mMockedPermissions = new HashMap<>();

        MockContext(Context base) {
            super(base);
        }

        /**
         * Mock checks for the specified permission, and have them behave as per {@code granted}.
         *
         * <p>Passing null reverts to default behavior, which does a real permission check on the
         * test package.
         *
         * @param granted One of {@link PackageManager#PERMISSION_GRANTED} or
         *                {@link PackageManager#PERMISSION_DENIED}.
         */
        public void setPermission(String permission, Integer granted) {
            mMockedPermissions.put(permission, granted);
        }

        @Override
        public PackageManager getPackageManager() {
            return mMockPackageManager;
        }

        @Override
        public void enforceCallingPermission(String permission, @Nullable String message) {
            final Integer granted = mMockedPermissions.get(permission);
            if (granted == null) {
                super.enforceCallingOrSelfPermission(permission, message);
                return;
            }

            if (!granted.equals(PackageManager.PERMISSION_GRANTED)) {
                throw new SecurityException("[Test] permission denied: " + permission);
            }
        }
    }
}
