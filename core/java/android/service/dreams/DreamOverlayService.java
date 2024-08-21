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

package android.service.dreams;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.WindowManager;

import java.util.concurrent.Executor;


/**
 * Basic implementation of for {@link IDreamOverlay} for testing.
 * @hide
 */
@TestApi
public abstract class DreamOverlayService extends Service {
    private static final String TAG = "DreamOverlayService";
    private static final boolean DEBUG = false;

    // The last client that started dreaming and hasn't ended
    private OverlayClient mCurrentClient;

    /**
     * Executor used to run callbacks that subclasses will implement. Any calls coming over Binder
     * from {@link OverlayClient} should perform the work they need to do on this executor.
     */
    private Executor mExecutor;

    // An {@link IDreamOverlayClient} implementation that identifies itself when forwarding
    // requests to the {@link DreamOverlayService}
    private static class OverlayClient extends IDreamOverlayClient.Stub {
        private final DreamOverlayService mService;
        private boolean mShowComplications;
        private ComponentName mDreamComponent;
        IDreamOverlayCallback mDreamOverlayCallback;

        OverlayClient(DreamOverlayService service) {
            mService = service;
        }

        @Override
        public void startDream(WindowManager.LayoutParams params, IDreamOverlayCallback callback,
                String dreamComponent, boolean shouldShowComplications) throws RemoteException {
            mDreamComponent = ComponentName.unflattenFromString(dreamComponent);
            mShowComplications = shouldShowComplications;
            mDreamOverlayCallback = callback;
            mService.startDream(this, params);
        }

        @Override
        public void wakeUp() {
            mService.wakeUp(this);
        }

        @Override
        public void endDream() {
            mService.endDream(this);
        }

        @Override
        public void comeToFront() {
            mService.comeToFront(this);
        }

        @Override
        public void onWakeRequested() {
            if (Flags.dreamWakeRedirect()) {
                mService.onWakeRequested();
            }
        }

        private void requestExit() {
            try {
                mDreamOverlayCallback.onExitRequested();
            } catch (RemoteException e) {
                Log.e(TAG, "Could not request exit:" + e);
            }
        }

        private void redirectWake(boolean redirect) {
            try {
                mDreamOverlayCallback.onRedirectWake(redirect);
            } catch (RemoteException e) {
                Log.e(TAG, "could not request redirect wake", e);
            }
        }

        private boolean shouldShowComplications() {
            return mShowComplications;
        }

        private ComponentName getComponent() {
            return mDreamComponent;
        }
    }

    private void startDream(OverlayClient client, WindowManager.LayoutParams params) {
        // Run on executor as this is a binder call from OverlayClient.
        mExecutor.execute(() -> {
            endDreamInternal(mCurrentClient);
            mCurrentClient = client;
            onStartDream(params);
        });
    }

    private void endDream(OverlayClient client) {
        // Run on executor as this is a binder call from OverlayClient.
        mExecutor.execute(() -> endDreamInternal(client));
    }

    private void endDreamInternal(OverlayClient client) {
        if (client == null || client != mCurrentClient) {
            return;
        }

        onEndDream();
        mCurrentClient = null;
    }

    private void wakeUp(OverlayClient client) {
        // Run on executor as this is a binder call from OverlayClient.
        mExecutor.execute(() -> {
            if (mCurrentClient != client) {
                return;
            }

            onWakeUp();
        });
    }

    private void comeToFront(OverlayClient client) {
        mExecutor.execute(() -> {
            if (mCurrentClient != client) {
                return;
            }

            onComeToFront();
        });
    }

    private IDreamOverlay mDreamOverlay = new IDreamOverlay.Stub() {
        @Override
        public void getClient(IDreamOverlayClientCallback callback) {
            try {
                callback.onDreamOverlayClient(
                        new OverlayClient(DreamOverlayService.this));
            } catch (RemoteException e) {
                Log.e(TAG, "could not send client to callback", e);
            }
        }
    };

    public DreamOverlayService() {
    }

    /**
     * This constructor allows providing an executor to run callbacks on.
     *
     * @hide
     */
    public DreamOverlayService(@NonNull Executor executor) {
        mExecutor = executor;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (mExecutor == null) {
            // If no executor was provided, use the main executor. onCreate is the earliest time
            // getMainExecutor is available.
            mExecutor = getMainExecutor();
        }
    }

    @Nullable
    @Override
    public final IBinder onBind(@NonNull Intent intent) {
        return mDreamOverlay.asBinder();
    }

    /**
     * This method is overridden by implementations to handle when the dream has started and the
     * window is ready to be interacted with.
     *
     * This callback will be run on the {@link Executor} provided in the constructor if provided, or
     * on the main executor if none was provided.
     *
     * @param layoutParams The {@link android.view.WindowManager.LayoutParams} associated with the
     *                     dream window.
     */
    public abstract void onStartDream(@NonNull WindowManager.LayoutParams layoutParams);

    /**
     * This method is overridden by implementations to handle when the dream has been requested
     * to wakeup.
     * @hide
     */
    public void onWakeUp() {}

    /**
     * This method is overridden by implementations to handle when the dream is coming to the front
     * (after having lost focus to something on top of it).
     * @hide
     */
    public void onComeToFront() {}

    /**
     * This method is overridden by implementations to handle when the dream has ended. There may
     * be earlier signals leading up to this step, such as @{@link #onWakeUp(Runnable)}.
     *
     * This callback will be run on the {@link Executor} provided in the constructor if provided, or
     * on the main executor if none was provided.
     */
    public void onEndDream() {
    }

    /**
     * This method is invoked to request the dream exit.
     */
    public final void requestExit() {
        if (mCurrentClient == null) {
            throw new IllegalStateException("requested exit with no dream present");
        }

        mCurrentClient.requestExit();
    }

    /**
     * Called to inform the dream to redirect waking to this overlay rather than exiting.
     * @param redirect {@code true} if waking up should be redirected. {@code false} otherwise.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_DREAM_WAKE_REDIRECT)
    public final void redirectWake(boolean redirect) {
        if (!Flags.dreamWakeRedirect()) {
            return;
        }

        if (mCurrentClient == null) {
            throw new IllegalStateException("redirected wake with no dream present");
        }

        mCurrentClient.redirectWake(redirect);
    }

    /**
     * Invoked when the dream has requested to exit. This is only called if the dream overlay
     * has explicitly requested exits to be redirected via {@link #redirectWake(boolean)}.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_DREAM_WAKE_REDIRECT)
    public void onWakeRequested() {
    }

    /**
     * Returns whether to show complications on the dream overlay.
     */
    public final boolean shouldShowComplications() {
        if (mCurrentClient == null) {
            throw new IllegalStateException(
                    "requested if should show complication when no dream active");
        }

        return mCurrentClient.shouldShowComplications();
    }

    /**
     * Returns the active dream component.
     * @hide
     */
    public final ComponentName getDreamComponent() {
        if (mCurrentClient == null) {
            throw new IllegalStateException("requested dream component when no dream active");
        }

        return mCurrentClient.getComponent();
    }
}
