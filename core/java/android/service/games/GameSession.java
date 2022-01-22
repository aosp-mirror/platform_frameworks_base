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

package android.service.games;

import android.annotation.Hide;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Slog;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.util.function.pooled.PooledLambda;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * An active game session, providing a facility for the implementation to interact with the game.
 *
 * A Game Service provider should extend the {@link GameSession} to provide their own implementation
 * which is then returned when a game session is created via
 * {@link GameSessionService#onNewSession(CreateGameSessionRequest)}.
 *
 * @hide
 */
@SystemApi
public abstract class GameSession {

    private static final String TAG = "GameSession";

    final IGameSession mInterface = new IGameSession.Stub() {
        @Override
        public void destroy() {
            Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                    GameSession::doDestroy, GameSession.this));
        }
    };

    private IGameSessionController mGameSessionController;
    private int mTaskId;
    private GameSessionRootView mGameSessionRootView;
    private SurfaceControlViewHost mSurfaceControlViewHost;

    /**
     * @hide
     */
    @VisibleForTesting
    public void attach(
            IGameSessionController gameSessionController,
            int taskId,
            @NonNull Context context,
            @NonNull SurfaceControlViewHost surfaceControlViewHost,
            int widthPx,
            int heightPx) {
        mGameSessionController = gameSessionController;
        mTaskId = taskId;
        mSurfaceControlViewHost = surfaceControlViewHost;
        mGameSessionRootView = new GameSessionRootView(context, mSurfaceControlViewHost);
        surfaceControlViewHost.setView(mGameSessionRootView, widthPx, heightPx);
    }

    @Hide
    void doCreate() {
        onCreate();
    }

    @Hide
    void doDestroy() {
        onDestroy();
        mSurfaceControlViewHost.release();
    }

    /**
     * Initializer called when the game session is starting.
     *
     * This should be used perform any setup required now that the game session is created.
     */
    public void onCreate() {
    }

    /**
     * Finalizer called when the game session is ending.
     *
     * This should be used to perform any cleanup before the game session is destroyed.
     */
    public void onDestroy() {
    }


    /**
     * Sets the task overlay content to an explicit view. This view is placed directly into the game
     * session's task overlay view hierarchy. It can itself be a complex view hierarchy. The size
     * the task overlay view will always match the dimensions of the associated task's window. The
     * {@code View} may not be cleared once set, but may be replaced by invoking
     * {@link #setTaskOverlayView(View, ViewGroup.LayoutParams)} again.
     *
     * @param view         The desired content to display.
     * @param layoutParams Layout parameters for the view.
     */
    public void setTaskOverlayView(
            @NonNull View view,
            @NonNull ViewGroup.LayoutParams layoutParams) {
        mGameSessionRootView.removeAllViews();
        mGameSessionRootView.addView(view, layoutParams);
    }

    /**
     * Root view of the {@link SurfaceControlViewHost} associated with the {@link GameSession}
     * instance. It is responsible for observing changes in the size of the window and resizing
     * itself to match.
     */
    private static final class GameSessionRootView extends FrameLayout {
        private final SurfaceControlViewHost mSurfaceControlViewHost;

        GameSessionRootView(@NonNull Context context,
                SurfaceControlViewHost surfaceControlViewHost) {
            super(context);
            mSurfaceControlViewHost = surfaceControlViewHost;
        }

        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);

            // TODO(b/204504596): Investigate skipping the relayout in cases where the size has
            // not changed.
            Rect bounds = newConfig.windowConfiguration.getBounds();
            mSurfaceControlViewHost.relayout(bounds.width(), bounds.height());
        }
    }

    /**
     * Interface for returning screenshot outcome from calls to {@link #takeScreenshot}.
     */
    public interface ScreenshotCallback {

        /**
         * The status of a failed screenshot attempt provided by {@link #onFailure}.
         *
         * @hide
         */
        @IntDef(flag = false, prefix = {"ERROR_TAKE_SCREENSHOT_"}, value = {
                ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR, // 0
        })
        @Retention(RetentionPolicy.SOURCE)
        @interface ScreenshotFailureStatus {
        }

        /**
         * An error code indicating that an internal error occurred when attempting to take a
         * screenshot of the game task. If this code is returned, the caller should verify that the
         * conditions for taking a screenshot are met (device screen is on and the game task is
         * visible). To do so, the caller can monitor the lifecycle methods for this session to
         * make sure that the game task is focused. If the conditions are met, then the caller may
         * try again immediately.
         */
        int ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR = 0;

        /**
         * Called when taking the screenshot failed.
         * @param statusCode Indicates the reason for failure.
         */
        void onFailure(@ScreenshotFailureStatus int statusCode);

        /**
         * Called when taking the screenshot succeeded.
         * @param bitmap The screenshot.
         */
        void onSuccess(@NonNull Bitmap bitmap);
    }

    /**
     * Takes a screenshot of the associated game. For this call to succeed, the device screen
     * must be turned on and the game task must be visible.
     *
     * If the callback is called with {@link ScreenshotCallback#onSuccess}, the provided {@link
     * Bitmap} may be used.
     *
     * If the callback is called with {@link ScreenshotCallback#onFailure}, the provided status
     * code should be checked.
     *
     * If the status code is {@link ScreenshotCallback#ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR},
     * then the caller should verify that the conditions for calling this method are met (device
     * screen is on and the game task is visible). To do so, the caller can monitor the lifecycle
     * methods for this session to make sure that the game task is focused. If the conditions are
     * met, then the caller may try again immediately.
     *
     * @param executor Executor on which to run the callback.
     * @param callback The callback invoked when taking screenshot has succeeded
     *                 or failed.
     * @throws IllegalStateException if this method is called prior to {@link #onCreate}.
     */
    public void takeScreenshot(@NonNull Executor executor, @NonNull ScreenshotCallback callback) {
        if (mGameSessionController == null) {
            throw new IllegalStateException("Can not call before onCreate()");
        }

        AndroidFuture<GameScreenshotResult> takeScreenshotResult =
                new AndroidFuture<GameScreenshotResult>().whenCompleteAsync((result, error) -> {
                    handleScreenshotResult(callback, result, error);
                }, executor);

        try {
            mGameSessionController.takeScreenshot(mTaskId, takeScreenshotResult);
        } catch (RemoteException ex) {
            takeScreenshotResult.completeExceptionally(ex);
        }
    }

    private void handleScreenshotResult(
            @NonNull ScreenshotCallback callback,
            @NonNull GameScreenshotResult result,
            @NonNull Throwable error) {
        if (error != null) {
            Slog.w(TAG, error.getMessage(), error.getCause());
            callback.onFailure(
                    ScreenshotCallback.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR);
            return;
        }

        @GameScreenshotResult.GameScreenshotStatus int status = result.getStatus();
        switch (status) {
            case GameScreenshotResult.GAME_SCREENSHOT_SUCCESS:
                callback.onSuccess(result.getBitmap());
                break;
            case GameScreenshotResult.GAME_SCREENSHOT_ERROR_INTERNAL_ERROR:
                Slog.w(TAG, "Error taking screenshot");
                callback.onFailure(
                        ScreenshotCallback.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR);
                break;
        }
    }
}
