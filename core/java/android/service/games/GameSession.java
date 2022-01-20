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
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.internal.util.function.pooled.PooledLambda;

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

    final IGameSession mInterface = new IGameSession.Stub() {
        @Override
        public void destroy() {
            Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                    GameSession::doDestroy, GameSession.this));
        }
    };

    private GameSessionRootView mGameSessionRootView;
    private SurfaceControlViewHost mSurfaceControlViewHost;

    @Hide
    void attach(
            @NonNull Context context,
            @NonNull SurfaceControlViewHost surfaceControlViewHost,
            int widthPx,
            int heightPx) {
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
}
