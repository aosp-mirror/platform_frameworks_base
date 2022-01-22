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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;

import android.graphics.Bitmap;
import android.platform.test.annotations.Presubmit;
import android.service.games.GameSession.ScreenshotCallback;
import android.view.SurfaceControlViewHost;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.infra.AndroidFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for the {@link android.service.games.GameSession}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public final class GameSessionTest {
    private static final long WAIT_FOR_CALLBACK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(1);
    private static final Bitmap TEST_BITMAP = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);

    @Mock
    private IGameSessionController mMockGameSessionController;
    @Mock
    SurfaceControlViewHost mSurfaceControlViewHost;
    private GameSession mGameSession;

    private MockitoSession mMockitoSession;

    @Before
    public void setUp() {
        mMockitoSession = mockitoSession()
                .initMocks(this)
                .startMocking();

        mGameSession = new GameSession() {};
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
                        public void onSuccess(Bitmap bitmap) {
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
                    public void onSuccess(Bitmap bitmap) {
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
                    public void onSuccess(Bitmap bitmap) {
                        fail();
                    }
                });

        assertTrue(countDownLatch.await(
                WAIT_FOR_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void takeScreenshot_gameManagerSuccess_returnsBitmap() throws Exception {
        doAnswer(invocation -> {
            AndroidFuture result = invocation.getArgument(1);
            result.complete(GameScreenshotResult.createSuccessResult(TEST_BITMAP));
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
                    public void onSuccess(Bitmap bitmap) {
                        assertEquals(TEST_BITMAP, bitmap);
                        countDownLatch.countDown();
                    }
                });

        assertTrue(countDownLatch.await(
                WAIT_FOR_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }
}
