/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.service.games;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;

import android.platform.test.annotations.Presubmit;
import android.service.games.GameSession.ScreenshotCallback;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.SurfaceControlViewHost;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.infra.AndroidFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for the {@link android.service.games.GameSession}.
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
@Presubmit
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public final class GameSessionTest {
    private static final long WAIT_FOR_CALLBACK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(1);

    @Mock
    private IGameSessionController mMockGameSessionController;
    @Mock
    SurfaceControlViewHost mSurfaceControlViewHost;
    private LifecycleTrackingGameSession mGameSession;
    private MockitoSession mMockitoSession;

    @Before
    public void setUp() {
        mMockitoSession = mockitoSession()
                .initMocks(this)
                .startMocking();

        mGameSession = new LifecycleTrackingGameSession() {};
        mGameSession.attach(mMockGameSessionController, /* taskId= */ 10,
                InstrumentationRegistry.getContext(),
                mSurfaceControlViewHost,
                /* widthPx= */ 0, /* heightPx= */0);
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
    }

    @Test
    public void takeScreenshot_attachNotCalled_throwsIllegalStateException() throws Exception {
        GameSession gameSession = new GameSession() {};

        try {
            gameSession.takeScreenshot(DIRECT_EXECUTOR,
                    new ScreenshotCallback() {
                        @Override
                        public void onFailure(int statusCode) {
                            fail();
                        }

                        @Override
                        public void onSuccess() {
                            fail();
                        }
                    });
            fail();
        } catch (IllegalStateException expected) {

        }
    }

    @Test
    public void takeScreenshot_gameManagerException_returnsInternalError() throws Exception {
        doAnswer(invocation -> {
            AndroidFuture result = invocation.getArgument(1);
            result.completeExceptionally(new Exception());
            return null;
        }).when(mMockGameSessionController).takeScreenshot(anyInt(), any());

        CountDownLatch countDownLatch = new CountDownLatch(1);

        mGameSession.takeScreenshot(DIRECT_EXECUTOR,
                new ScreenshotCallback() {
                    @Override
                    public void onFailure(int statusCode) {
                        assertEquals(ScreenshotCallback.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR,
                                statusCode);
                        countDownLatch.countDown();
                    }

                    @Override
                    public void onSuccess() {
                        fail();
                    }
                });

        assertTrue(countDownLatch.await(
                WAIT_FOR_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void takeScreenshot_gameManagerError_returnsInternalError() throws Exception {
        doAnswer(invocation -> {
            AndroidFuture result = invocation.getArgument(1);
            result.complete(GameScreenshotResult.createInternalErrorResult());
            return null;
        }).when(mMockGameSessionController).takeScreenshot(anyInt(), any());

        CountDownLatch countDownLatch = new CountDownLatch(1);

        mGameSession.takeScreenshot(DIRECT_EXECUTOR,
                new ScreenshotCallback() {
                    @Override
                    public void onFailure(int statusCode) {
                        assertEquals(ScreenshotCallback.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR,
                                statusCode);
                        countDownLatch.countDown();
                    }

                    @Override
                    public void onSuccess() {
                        fail();
                    }
                });

        assertTrue(countDownLatch.await(
                WAIT_FOR_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void takeScreenshot_gameManagerSuccess() throws Exception {
        doAnswer(invocation -> {
            AndroidFuture result = invocation.getArgument(1);
            result.complete(GameScreenshotResult.createSuccessResult());
            return null;
        }).when(mMockGameSessionController).takeScreenshot(anyInt(), any());

        CountDownLatch countDownLatch = new CountDownLatch(1);

        mGameSession.takeScreenshot(DIRECT_EXECUTOR,
                new ScreenshotCallback() {
                    @Override
                    public void onFailure(int statusCode) {
                        fail();
                    }

                    @Override
                    public void onSuccess() {
                        countDownLatch.countDown();
                    }
                });

        assertTrue(countDownLatch.await(
                WAIT_FOR_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void moveState_InitializedToInitialized_noLifecycleCalls() throws Exception {
        mGameSession.moveToState(GameSession.LifecycleState.INITIALIZED);

        assertThat(mGameSession.mLifecycleMethodCalls.isEmpty()).isTrue();
    }

    @Test
    public void moveState_FullLifecycle_ExpectedLifecycleCalls() throws Exception {
        mGameSession.moveToState(GameSession.LifecycleState.CREATED);
        mGameSession.moveToState(GameSession.LifecycleState.TASK_FOCUSED);
        mGameSession.moveToState(GameSession.LifecycleState.CREATED);
        mGameSession.moveToState(GameSession.LifecycleState.DESTROYED);

        assertThat(mGameSession.mLifecycleMethodCalls).containsExactly(
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_CREATE,
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_GAME_TASK_FOCUSED,
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_GAME_TASK_UNFOCUSED,
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_DESTROY).inOrder();
    }

    @Test
    public void moveState_DestroyedWhenInitialized_ExpectedLifecycleCalls() throws Exception {
        mGameSession.moveToState(GameSession.LifecycleState.DESTROYED);

        // ON_CREATE is always called before ON_DESTROY.
        assertThat(mGameSession.mLifecycleMethodCalls).containsExactly(
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_CREATE,
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_DESTROY).inOrder();
    }

    @Test
    public void moveState_DestroyedWhenFocused_ExpectedLifecycleCalls() throws Exception {
        mGameSession.moveToState(GameSession.LifecycleState.CREATED);
        mGameSession.moveToState(GameSession.LifecycleState.TASK_FOCUSED);
        mGameSession.moveToState(GameSession.LifecycleState.DESTROYED);

        // The ON_GAME_TASK_UNFOCUSED lifecycle event is implied because the session is destroyed
        // while in focus.
        assertThat(mGameSession.mLifecycleMethodCalls).containsExactly(
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_CREATE,
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_GAME_TASK_FOCUSED,
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_GAME_TASK_UNFOCUSED,
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_DESTROY).inOrder();
    }

    @Test
    public void moveState_FocusCycled_ExpectedLifecycleCalls() throws Exception {
        mGameSession.moveToState(GameSession.LifecycleState.CREATED);
        mGameSession.moveToState(GameSession.LifecycleState.TASK_FOCUSED);
        mGameSession.moveToState(GameSession.LifecycleState.TASK_UNFOCUSED);
        mGameSession.moveToState(GameSession.LifecycleState.TASK_FOCUSED);
        mGameSession.moveToState(GameSession.LifecycleState.TASK_UNFOCUSED);

        // Both cycles from focus and unfocus are captured.
        assertThat(mGameSession.mLifecycleMethodCalls).containsExactly(
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_CREATE,
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_GAME_TASK_FOCUSED,
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_GAME_TASK_UNFOCUSED,
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_GAME_TASK_FOCUSED,
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_GAME_TASK_UNFOCUSED).inOrder();
    }

    @Test
    public void moveState_MultipleFocusAndUnfocusCalls_ExpectedLifecycleCalls() throws Exception {
        mGameSession.moveToState(GameSession.LifecycleState.CREATED);
        mGameSession.moveToState(GameSession.LifecycleState.TASK_FOCUSED);
        mGameSession.moveToState(GameSession.LifecycleState.TASK_FOCUSED);
        mGameSession.moveToState(GameSession.LifecycleState.TASK_UNFOCUSED);
        mGameSession.moveToState(GameSession.LifecycleState.TASK_UNFOCUSED);

        // The second TASK_FOCUSED call and the second TASK_UNFOCUSED call are ignored.
        assertThat(mGameSession.mLifecycleMethodCalls).containsExactly(
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_CREATE,
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_GAME_TASK_FOCUSED,
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_GAME_TASK_UNFOCUSED).inOrder();
    }

    @Test
    public void moveState_CreatedAfterFocused_ExpectedLifecycleCalls() throws Exception {
        mGameSession.moveToState(GameSession.LifecycleState.CREATED);
        mGameSession.moveToState(GameSession.LifecycleState.TASK_FOCUSED);
        mGameSession.moveToState(GameSession.LifecycleState.CREATED);

        // The second CREATED call is ignored.
        assertThat(mGameSession.mLifecycleMethodCalls).containsExactly(
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_CREATE,
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_GAME_TASK_FOCUSED).inOrder();
    }

    @Test
    public void moveState_UnfocusedWithoutFocused_ExpectedLifecycleCalls() throws Exception {
        mGameSession.moveToState(GameSession.LifecycleState.CREATED);
        mGameSession.moveToState(GameSession.LifecycleState.TASK_UNFOCUSED);

        // The TASK_UNFOCUSED call without an earlier TASK_FOCUSED call is ignored.
        assertThat(mGameSession.mLifecycleMethodCalls).containsExactly(
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_CREATE).inOrder();
    }

    @Test
    public void moveState_NeverFocused_ExpectedLifecycleCalls() throws Exception {
        mGameSession.moveToState(GameSession.LifecycleState.CREATED);
        mGameSession.moveToState(GameSession.LifecycleState.DESTROYED);

        assertThat(mGameSession.mLifecycleMethodCalls).containsExactly(
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_CREATE,
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_DESTROY).inOrder();
    }

    @Test
    public void moveState_MultipleFocusCalls_ExpectedLifecycleCalls() throws Exception {
        mGameSession.moveToState(GameSession.LifecycleState.CREATED);
        mGameSession.moveToState(GameSession.LifecycleState.TASK_FOCUSED);
        mGameSession.moveToState(GameSession.LifecycleState.TASK_FOCUSED);
        mGameSession.moveToState(GameSession.LifecycleState.TASK_FOCUSED);

        // The extra TASK_FOCUSED moves are ignored.
        assertThat(mGameSession.mLifecycleMethodCalls).containsExactly(
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_CREATE,
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_GAME_TASK_FOCUSED).inOrder();
    }

    @Test
    public void moveState_MultipleCreateCalls_ExpectedLifecycleCalls() throws Exception {
        mGameSession.moveToState(GameSession.LifecycleState.CREATED);
        mGameSession.moveToState(GameSession.LifecycleState.CREATED);
        mGameSession.moveToState(GameSession.LifecycleState.CREATED);

        // The extra CREATE moves are ignored.
        assertThat(mGameSession.mLifecycleMethodCalls).containsExactly(
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_CREATE).inOrder();
    }

    @Test
    public void moveState_FocusBeforeCreate_ExpectedLifecycleCalls() throws Exception {
        mGameSession.moveToState(GameSession.LifecycleState.TASK_FOCUSED);

        // The TASK_FOCUSED move before CREATE is ignored.
        assertThat(mGameSession.mLifecycleMethodCalls.isEmpty()).isTrue();
    }

    @Test
    public void moveState_UnfocusBeforeCreate_ExpectedLifecycleCalls() throws Exception {
        mGameSession.moveToState(GameSession.LifecycleState.TASK_UNFOCUSED);

        // The TASK_UNFOCUSED move before CREATE is ignored.
        assertThat(mGameSession.mLifecycleMethodCalls.isEmpty()).isTrue();
    }

    @Test
    public void moveState_FocusWhenDestroyed_ExpectedLifecycleCalls() throws Exception {
        mGameSession.moveToState(GameSession.LifecycleState.CREATED);
        mGameSession.moveToState(GameSession.LifecycleState.DESTROYED);
        mGameSession.moveToState(GameSession.LifecycleState.TASK_FOCUSED);

        // The TASK_FOCUSED move after DESTROYED is ignored.
        assertThat(mGameSession.mLifecycleMethodCalls).containsExactly(
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_CREATE,
                LifecycleTrackingGameSession.LifecycleMethodCall.ON_DESTROY).inOrder();
    }

    @Test
    public void dispatchTransientVisibilityChanged_valueUnchanged_doesNotInvokeCallback() {
        mGameSession.dispatchTransientSystemBarVisibilityFromRevealGestureChanged(false);

        assertThat(mGameSession.mCapturedTransientSystemBarVisibilityFromRevealGestures).hasSize(0);
    }

    @Test
    public void dispatchTransientVisibilityChanged_valueChanged_invokesCallback() {
        mGameSession.dispatchTransientSystemBarVisibilityFromRevealGestureChanged(true);

        assertThat(mGameSession.mCapturedTransientSystemBarVisibilityFromRevealGestures)
                .containsExactly(true).inOrder();
    }

    @Test
    public void dispatchTransientVisibilityChanged_manyTimes_invokesCallbackWhenValueChanges() {
        mGameSession.dispatchTransientSystemBarVisibilityFromRevealGestureChanged(false);
        mGameSession.dispatchTransientSystemBarVisibilityFromRevealGestureChanged(true);
        mGameSession.dispatchTransientSystemBarVisibilityFromRevealGestureChanged(false);
        mGameSession.dispatchTransientSystemBarVisibilityFromRevealGestureChanged(false);
        mGameSession.dispatchTransientSystemBarVisibilityFromRevealGestureChanged(true);
        mGameSession.dispatchTransientSystemBarVisibilityFromRevealGestureChanged(true);

        assertThat(mGameSession.mCapturedTransientSystemBarVisibilityFromRevealGestures)
                .containsExactly(true, false, true).inOrder();
    }

    private static class LifecycleTrackingGameSession extends GameSession {
        private enum LifecycleMethodCall {
            ON_CREATE,
            ON_DESTROY,
            ON_GAME_TASK_FOCUSED,
            ON_GAME_TASK_UNFOCUSED
        }

        final List<LifecycleMethodCall> mLifecycleMethodCalls = new ArrayList<>();
        final List<Boolean> mCapturedTransientSystemBarVisibilityFromRevealGestures =
                new ArrayList<>();

        @Override
        public void onCreate() {
            mLifecycleMethodCalls.add(LifecycleMethodCall.ON_CREATE);
        }

        @Override
        public void onDestroy() {
            mLifecycleMethodCalls.add(LifecycleMethodCall.ON_DESTROY);
        }

        @Override
        public void onGameTaskFocusChanged(boolean focused) {
            if (focused) {
                mLifecycleMethodCalls.add(LifecycleMethodCall.ON_GAME_TASK_FOCUSED);
            } else {
                mLifecycleMethodCalls.add(LifecycleMethodCall.ON_GAME_TASK_UNFOCUSED);
            }
        }

        @Override
        public void onTransientSystemBarVisibilityFromRevealGestureChanged(
                boolean visibleDueToGesture) {
            mCapturedTransientSystemBarVisibilityFromRevealGestures.add(visibleDueToGesture);
        }
    }
}
