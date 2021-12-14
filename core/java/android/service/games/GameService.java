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

import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.IGameManagerService;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.util.function.pooled.PooledLambda;

import java.util.Objects;

/**
 * Top-level service of the game service, which provides support for determining
 * when a game session should begin. It is always kept running by the system.
 * Because of this it should be kept as lightweight as possible.
 *
 * <p>Heavyweight operations (such as showing UI) should be implemented in the
 * associated {@link GameSessionService} when a game session is taking place. Its
 * implementation should run in a separate process from the {@link GameService}.
 *
 * <p>To extend this class, you must declare the service in your manifest file with
 * the {@link android.Manifest.permission#BIND_GAME_SERVICE} permission
 * and include an intent filter with the {@link #SERVICE_INTERFACE} action. For example:
 * <pre>
 * &lt;service android:name=".GameService"
 *          android:label="&#64;string/service_name"
 *          android:permission="android.permission.BIND_GAME_SERVICE">
 *     &lt;intent-filter>
 *         &lt;action android:name="android.service.games.GameService" />
 *     &lt;/intent-filter>
 * &lt;/service>
 * </pre>
 *
 * @hide
 */
@SystemApi
public class GameService extends Service {
    static final String TAG = "GameService";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_GAME_SERVICE} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.games.GameService";

    private IGameManagerService mGameManagerService;
    private final IGameService mInterface = new IGameService.Stub() {
        @Override
        public void connected() {
            Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                    GameService::doOnConnected, GameService.this));
        }

        @Override
        public void disconnected() {
            Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                    GameService::onDisconnected, GameService.this));
        }
    };
    private final IBinder.DeathRecipient mGameManagerServiceDeathRecipient = () -> {
        Log.w(TAG, "System service binder died. Shutting down");

        Handler.getMain().executeOrSendMessage(PooledLambda.obtainMessage(
                GameService::onDisconnected, GameService.this));
    };

    @Override
    @Nullable
    public IBinder onBind(@Nullable Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        return null;
    }

    private void doOnConnected() {
        mGameManagerService =
                IGameManagerService.Stub.asInterface(
                        ServiceManager.getService(Context.GAME_SERVICE));
        Objects.requireNonNull(mGameManagerService);
        try {
            mGameManagerService.asBinder().linkToDeath(mGameManagerServiceDeathRecipient, 0);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to link to death with system service");
        }

        onConnected();
    }

    /**
     * Called during service initialization to indicate that the system is ready
     * to receive interaction from it. You should generally do initialization here
     * rather than in {@link #onCreate}.
     */
    public void onConnected() {}

    /**
     * Called during service de-initialization to indicate that the system is shutting the
     * service down. At this point this service may no longer be the active {@link GameService}.
     * The service should clean up any resources that it holds at this point.
     */
    public void onDisconnected() {}
}
