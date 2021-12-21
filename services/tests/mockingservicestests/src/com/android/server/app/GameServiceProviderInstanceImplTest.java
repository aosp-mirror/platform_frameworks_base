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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;

import android.annotation.Nullable;
import android.app.IActivityTaskManager;
import android.app.ITaskStackListener;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.service.games.CreateGameSessionRequest;
import android.service.games.IGameService;
import android.service.games.IGameSession;
import android.service.games.IGameSessionService;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.FunctionalUtils.ThrowingConsumer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;


/**
 * Unit tests for the {@link GameServiceProviderInstanceImpl}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public final class GameServiceProviderInstanceImplTest {

    private static final int USER_ID = 10;
    private static final String APP_A_PACKAGE = "com.package.app.a";
    private static final ComponentName APP_A_MAIN_ACTIVITY =
            new ComponentName(APP_A_PACKAGE, "com.package.app.a.MainActivity");

    private static final String GAME_A_PACKAGE = "com.package.game.a";
    private static final ComponentName GAME_A_MAIN_ACTIVITY =
            new ComponentName(GAME_A_PACKAGE, "com.package.game.a.MainActivity");

    private MockitoSession mMockingSession;
    private GameServiceProviderInstance mGameServiceProviderInstance;
    @Mock
    private IActivityTaskManager mMockActivityTaskManager;
    @Mock
    private IGameService mMockGameService;
    @Mock
    private IGameSessionService mMockGameSessionService;
    private FakeGameClassifier mFakeGameClassifier;
    private FakeServiceConnector<IGameService> mFakeGameServiceConnector;
    private FakeServiceConnector<IGameSessionService> mFakeGameSessionServiceConnector;
    private ArrayList<ITaskStackListener> mTaskStackListeners;
    private InOrder mInOrder;

    @Before
    public void setUp() throws PackageManager.NameNotFoundException, RemoteException {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .startMocking();

        mInOrder = inOrder(mMockGameService, mMockGameSessionService);

        mFakeGameClassifier = new FakeGameClassifier();
        mFakeGameClassifier.recordGamePackage(GAME_A_PACKAGE);

        mFakeGameServiceConnector = new FakeServiceConnector<>(mMockGameService);
        mFakeGameSessionServiceConnector = new FakeServiceConnector<>(mMockGameSessionService);

        mTaskStackListeners = new ArrayList<>();
        doAnswer(invocation -> {
            mTaskStackListeners.add(invocation.getArgument(0));
            return null;
        }).when(mMockActivityTaskManager).registerTaskStackListener(any());

        doAnswer(invocation -> {
            mTaskStackListeners.remove(invocation.getArgument(0));
            return null;
        }).when(mMockActivityTaskManager).unregisterTaskStackListener(any());

        mGameServiceProviderInstance = new GameServiceProviderInstanceImpl(
                new UserHandle(USER_ID),
                ConcurrentUtils.DIRECT_EXECUTOR,
                mFakeGameClassifier,
                mMockActivityTaskManager,
                mFakeGameServiceConnector,
                mFakeGameSessionServiceConnector);
    }

    @After
    public void tearDown() {
        mMockingSession.finishMocking();
    }

    @Test
    public void start_startsGameSession() throws Exception {
        mGameServiceProviderInstance.start();

        mInOrder.verify(mMockGameService).connected();
        mInOrder.verifyNoMoreInteractions();
        assertThat(mFakeGameServiceConnector.getIsConnected()).isTrue();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void start_multipleTimes_startsGameSessionOnce() throws Exception {
        mGameServiceProviderInstance.start();

        mInOrder.verify(mMockGameService).connected();
        mInOrder.verifyNoMoreInteractions();
        assertThat(mFakeGameServiceConnector.getIsConnected()).isTrue();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void stop_neverStarted_doesNothing() throws Exception {
        mGameServiceProviderInstance.stop();

        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(0);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
        mInOrder.verifyNoMoreInteractions();
    }

    @Test
    public void startAndStop_startsAndStopsGameSession() throws Exception {
        mGameServiceProviderInstance.start();
        mGameServiceProviderInstance.stop();

        mInOrder.verify(mMockGameService).connected();
        mInOrder.verify(mMockGameService).disconnected();
        mInOrder.verifyNoMoreInteractions();
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

        mInOrder.verify(mMockGameService).connected();
        mInOrder.verify(mMockGameService).disconnected();
        mInOrder.verify(mMockGameService).connected();
        mInOrder.verify(mMockGameService).disconnected();
        mInOrder.verifyNoMoreInteractions();
        assertThat(mFakeGameServiceConnector.getIsConnected()).isFalse();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(2);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void stop_stopMultipleTimes_stopsGameSessionOnce() throws Exception {
        mGameServiceProviderInstance.start();
        mGameServiceProviderInstance.stop();
        mGameServiceProviderInstance.stop();

        mInOrder.verify(mMockGameService).connected();
        mInOrder.verify(mMockGameService).disconnected();
        mInOrder.verifyNoMoreInteractions();
        assertThat(mFakeGameServiceConnector.getIsConnected()).isFalse();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void gameTaskStarted_neverStarted_doesNothing() throws Exception {
        dispatchTaskCreated(10, GAME_A_MAIN_ACTIVITY);

        mInOrder.verifyNoMoreInteractions();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(0);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void gameTaskRemoved_neverStarted_doesNothing() throws Exception {
        dispatchTaskRemoved(10);

        mInOrder.verifyNoMoreInteractions();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(0);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void gameTaskStarted_afterStopped_doesNothing() throws Exception {
        mGameServiceProviderInstance.start();
        mGameServiceProviderInstance.stop();
        dispatchTaskCreated(10, GAME_A_MAIN_ACTIVITY);

        mInOrder.verify(mMockGameService).connected();
        mInOrder.verify(mMockGameService).disconnected();
        mInOrder.verifyNoMoreInteractions();
        assertThat(mFakeGameServiceConnector.getIsConnected()).isFalse();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void appTaskStarted_doesNothing() throws Exception {
        mGameServiceProviderInstance.start();
        dispatchTaskCreated(10, APP_A_MAIN_ACTIVITY);

        mInOrder.verify(mMockGameService).connected();
        mInOrder.verifyNoMoreInteractions();
        assertThat(mFakeGameServiceConnector.getIsConnected()).isTrue();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void taskStarted_nullComponentName_ignoresAndDoesNotCrash() throws Exception {
        mGameServiceProviderInstance.start();
        dispatchTaskCreated(10, null);

        mInOrder.verify(mMockGameService).connected();
        mInOrder.verifyNoMoreInteractions();
        assertThat(mFakeGameServiceConnector.getIsConnected()).isTrue();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(0);
    }

    @Test
    public void gameTaskStarted_createsGameSession() throws Exception {
        CreateGameSessionRequest createGameSessionRequest =
                new CreateGameSessionRequest(10, GAME_A_PACKAGE);
        Supplier<AndroidFuture<IBinder>> gameSession10Future =
                captureCreateGameSessionFuture(createGameSessionRequest);

        mGameServiceProviderInstance.start();
        dispatchTaskCreated(10, GAME_A_MAIN_ACTIVITY);
        IGameSessionStub gameSession10 = new IGameSessionStub();
        gameSession10Future.get().complete(gameSession10);

        mInOrder.verify(mMockGameService).connected();
        mInOrder.verify(mMockGameSessionService).create(eq(createGameSessionRequest), any());
        mInOrder.verifyNoMoreInteractions();
        assertThat(gameSession10.mIsDestroyed).isFalse();
        assertThat(mFakeGameServiceConnector.getIsConnected()).isTrue();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getIsConnected()).isTrue();
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(1);
    }

    @Test
    public void gameTaskRemoved_whileAwaitingGameSessionAttached_destroysGameSession()
            throws Exception {
        CreateGameSessionRequest createGameSessionRequest =
                new CreateGameSessionRequest(10, GAME_A_PACKAGE);
        Supplier<AndroidFuture<IBinder>> gameSession10Future =
                captureCreateGameSessionFuture(createGameSessionRequest);

        mGameServiceProviderInstance.start();
        dispatchTaskCreated(10, GAME_A_MAIN_ACTIVITY);
        dispatchTaskRemoved(10);
        IGameSessionStub gameSession10 = new IGameSessionStub();
        gameSession10Future.get().complete(gameSession10);

        mInOrder.verify(mMockGameService).connected();
        mInOrder.verify(mMockGameSessionService).create(eq(createGameSessionRequest), any());
        mInOrder.verifyNoMoreInteractions();
        assertThat(gameSession10.mIsDestroyed).isTrue();
        assertThat(mFakeGameServiceConnector.getIsConnected()).isTrue();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getIsConnected()).isFalse();
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(1);
    }

    @Test
    public void gameTaskRemoved_destroysGameSession() throws Exception {
        CreateGameSessionRequest createGameSessionRequest =
                new CreateGameSessionRequest(10, GAME_A_PACKAGE);
        Supplier<AndroidFuture<IBinder>> gameSession10Future =
                captureCreateGameSessionFuture(createGameSessionRequest);

        mGameServiceProviderInstance.start();
        dispatchTaskCreated(10, GAME_A_MAIN_ACTIVITY);
        IGameSessionStub gameSession10 = new IGameSessionStub();
        gameSession10Future.get().complete(gameSession10);
        dispatchTaskRemoved(10);

        mInOrder.verify(mMockGameService).connected();
        mInOrder.verify(mMockGameSessionService).create(eq(createGameSessionRequest), any());
        mInOrder.verifyNoMoreInteractions();
        assertThat(gameSession10.mIsDestroyed).isTrue();
        assertThat(mFakeGameServiceConnector.getIsConnected()).isTrue();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getIsConnected()).isFalse();
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(1);
    }

    @Test
    public void gameTaskStarted_multipleTimes_createsMultipleGameSessions() throws Exception {
        CreateGameSessionRequest createGameSessionRequest10 =
                new CreateGameSessionRequest(10, GAME_A_PACKAGE);
        Supplier<AndroidFuture<IBinder>> gameSession10Future =
                captureCreateGameSessionFuture(createGameSessionRequest10);

        CreateGameSessionRequest createGameSessionRequest11 =
                new CreateGameSessionRequest(11, GAME_A_PACKAGE);
        Supplier<AndroidFuture<IBinder>> gameSession11Future =
                captureCreateGameSessionFuture(createGameSessionRequest11);

        mGameServiceProviderInstance.start();
        dispatchTaskCreated(10, GAME_A_MAIN_ACTIVITY);
        IGameSessionStub gameSession10 = new IGameSessionStub();
        gameSession10Future.get().complete(gameSession10);

        dispatchTaskCreated(11, GAME_A_MAIN_ACTIVITY);
        IGameSessionStub gameSession11 = new IGameSessionStub();
        gameSession11Future.get().complete(gameSession11);

        mInOrder.verify(mMockGameService).connected();
        mInOrder.verify(mMockGameSessionService).create(eq(createGameSessionRequest10), any());
        mInOrder.verify(mMockGameSessionService).create(eq(createGameSessionRequest11), any());
        mInOrder.verifyNoMoreInteractions();
        assertThat(gameSession10.mIsDestroyed).isFalse();
        assertThat(gameSession11.mIsDestroyed).isFalse();
        assertThat(mFakeGameServiceConnector.getIsConnected()).isTrue();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getIsConnected()).isTrue();
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(1);
    }

    @Test
    public void gameTaskRemoved_afterMultipleCreated_destroysOnlyThatGameSession()
            throws Exception {
        CreateGameSessionRequest createGameSessionRequest10 =
                new CreateGameSessionRequest(10, GAME_A_PACKAGE);
        Supplier<AndroidFuture<IBinder>> gameSession10Future =
                captureCreateGameSessionFuture(createGameSessionRequest10);

        CreateGameSessionRequest createGameSessionRequest11 =
                new CreateGameSessionRequest(11, GAME_A_PACKAGE);
        Supplier<AndroidFuture<IBinder>> gameSession11Future =
                captureCreateGameSessionFuture(createGameSessionRequest11);

        mGameServiceProviderInstance.start();
        dispatchTaskCreated(10, GAME_A_MAIN_ACTIVITY);
        IGameSessionStub gameSession10 = new IGameSessionStub();
        gameSession10Future.get().complete(gameSession10);

        dispatchTaskCreated(11, GAME_A_MAIN_ACTIVITY);
        IGameSessionStub gameSession11 = new IGameSessionStub();
        gameSession11Future.get().complete(gameSession11);

        dispatchTaskRemoved(10);

        mInOrder.verify(mMockGameService).connected();
        mInOrder.verify(mMockGameSessionService).create(eq(createGameSessionRequest10), any());
        mInOrder.verify(mMockGameSessionService).create(eq(createGameSessionRequest11), any());
        mInOrder.verifyNoMoreInteractions();
        assertThat(gameSession10.mIsDestroyed).isTrue();
        assertThat(gameSession11.mIsDestroyed).isFalse();
        assertThat(mFakeGameServiceConnector.getIsConnected()).isTrue();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getIsConnected()).isTrue();
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(1);
    }

    @Test
    public void allGameTasksRemoved_destroysAllGameSessions() throws Exception {
        CreateGameSessionRequest createGameSessionRequest10 =
                new CreateGameSessionRequest(10, GAME_A_PACKAGE);
        Supplier<AndroidFuture<IBinder>> gameSession10Future =
                captureCreateGameSessionFuture(createGameSessionRequest10);

        CreateGameSessionRequest createGameSessionRequest11 =
                new CreateGameSessionRequest(11, GAME_A_PACKAGE);
        Supplier<AndroidFuture<IBinder>> gameSession11Future =
                captureCreateGameSessionFuture(createGameSessionRequest11);

        mGameServiceProviderInstance.start();
        dispatchTaskCreated(10, GAME_A_MAIN_ACTIVITY);
        IGameSessionStub gameSession10 = new IGameSessionStub();
        gameSession10Future.get().complete(gameSession10);

        dispatchTaskCreated(11, GAME_A_MAIN_ACTIVITY);
        IGameSessionStub gameSession11 = new IGameSessionStub();
        gameSession11Future.get().complete(gameSession11);

        dispatchTaskRemoved(10);
        dispatchTaskRemoved(11);

        mInOrder.verify(mMockGameService).connected();
        mInOrder.verify(mMockGameSessionService).create(eq(createGameSessionRequest10), any());
        mInOrder.verify(mMockGameSessionService).create(eq(createGameSessionRequest11), any());
        mInOrder.verifyNoMoreInteractions();
        assertThat(gameSession10.mIsDestroyed).isTrue();
        assertThat(gameSession11.mIsDestroyed).isTrue();
        assertThat(mFakeGameServiceConnector.getIsConnected()).isTrue();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getIsConnected()).isFalse();
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(1);
    }

    @Test
    public void gameTasksCreated_afterAllPreviousSessionsDestroyed_createsSession()
            throws Exception {
        CreateGameSessionRequest createGameSessionRequest10 =
                new CreateGameSessionRequest(10, GAME_A_PACKAGE);
        Supplier<AndroidFuture<IBinder>> gameSession10Future =
                captureCreateGameSessionFuture(createGameSessionRequest10);

        CreateGameSessionRequest createGameSessionRequest11 =
                new CreateGameSessionRequest(11, GAME_A_PACKAGE);
        Supplier<AndroidFuture<IBinder>> gameSession11Future =
                captureCreateGameSessionFuture(createGameSessionRequest11);

        CreateGameSessionRequest createGameSessionRequest12 =
                new CreateGameSessionRequest(12, GAME_A_PACKAGE);
        Supplier<AndroidFuture<IBinder>> unusedGameSession12Future =
                captureCreateGameSessionFuture(createGameSessionRequest12);

        mGameServiceProviderInstance.start();
        dispatchTaskCreated(10, GAME_A_MAIN_ACTIVITY);
        IGameSessionStub gameSession10 = new IGameSessionStub();
        gameSession10Future.get().complete(gameSession10);

        dispatchTaskCreated(11, GAME_A_MAIN_ACTIVITY);
        IGameSessionStub gameSession11 = new IGameSessionStub();
        gameSession11Future.get().complete(gameSession11);

        dispatchTaskRemoved(10);
        dispatchTaskRemoved(11);

        dispatchTaskCreated(12, GAME_A_MAIN_ACTIVITY);
        IGameSessionStub gameSession12 = new IGameSessionStub();
        gameSession11Future.get().complete(gameSession12);

        mInOrder.verify(mMockGameService).connected();
        mInOrder.verify(mMockGameSessionService).create(eq(createGameSessionRequest10), any());
        mInOrder.verify(mMockGameSessionService).create(eq(createGameSessionRequest11), any());
        mInOrder.verify(mMockGameSessionService).create(eq(createGameSessionRequest12), any());
        mInOrder.verifyNoMoreInteractions();
        assertThat(gameSession10.mIsDestroyed).isTrue();
        assertThat(gameSession11.mIsDestroyed).isTrue();
        assertThat(gameSession12.mIsDestroyed).isFalse();
        assertThat(mFakeGameServiceConnector.getIsConnected()).isTrue();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getIsConnected()).isTrue();
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(2);
    }

    @Test
    public void stop_severalActiveGameSessions_destroysGameSessionsAndUnbinds() throws Exception {
        CreateGameSessionRequest createGameSessionRequest10 =
                new CreateGameSessionRequest(10, GAME_A_PACKAGE);
        Supplier<AndroidFuture<IBinder>> gameSession10Future =
                captureCreateGameSessionFuture(createGameSessionRequest10);

        CreateGameSessionRequest createGameSessionRequest11 =
                new CreateGameSessionRequest(11, GAME_A_PACKAGE);
        Supplier<AndroidFuture<IBinder>> gameSession11Future =
                captureCreateGameSessionFuture(createGameSessionRequest11);

        mGameServiceProviderInstance.start();
        dispatchTaskCreated(10, GAME_A_MAIN_ACTIVITY);
        IGameSessionStub gameSession10 = new IGameSessionStub();
        gameSession10Future.get().complete(gameSession10);
        dispatchTaskCreated(11, GAME_A_MAIN_ACTIVITY);
        IGameSessionStub gameSession11 = new IGameSessionStub();
        gameSession11Future.get().complete(gameSession11);
        mGameServiceProviderInstance.stop();

        mInOrder.verify(mMockGameService).connected();
        mInOrder.verify(mMockGameSessionService).create(eq(createGameSessionRequest10), any());
        mInOrder.verify(mMockGameSessionService).create(eq(createGameSessionRequest11), any());
        mInOrder.verify(mMockGameService).disconnected();
        mInOrder.verifyNoMoreInteractions();
        assertThat(gameSession10.mIsDestroyed).isTrue();
        assertThat(gameSession11.mIsDestroyed).isTrue();
        assertThat(mFakeGameServiceConnector.getIsConnected()).isFalse();
        assertThat(mFakeGameServiceConnector.getConnectCount()).isEqualTo(1);
        assertThat(mFakeGameSessionServiceConnector.getIsConnected()).isFalse();
        assertThat(mFakeGameSessionServiceConnector.getConnectCount()).isEqualTo(1);
    }

    private Supplier<AndroidFuture<IBinder>> captureCreateGameSessionFuture(
            CreateGameSessionRequest expectedCreateGameSessionRequest) throws Exception {
        final AtomicReference<AndroidFuture<IBinder>> gameSessionFuture = new AtomicReference<>();
        doAnswer(invocation -> {
            gameSessionFuture.set(invocation.getArgument(1));
            return null;
        }).when(mMockGameSessionService).create(eq(expectedCreateGameSessionRequest), any());

        return gameSessionFuture::get;
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

    private void dispatchTaskChangeEvent(
            ThrowingConsumer<ITaskStackListener> taskStackListenerConsumer) {
        for (ITaskStackListener taskStackListener : mTaskStackListeners) {
            taskStackListenerConsumer.accept(taskStackListener);
        }
    }

    private static class IGameSessionStub extends IGameSession.Stub {
        boolean mIsDestroyed = false;

        @Override
        public void destroy() {
            mIsDestroyed = true;
        }
    }
}
