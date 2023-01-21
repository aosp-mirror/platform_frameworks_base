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


/**
 * Basic implementation of for {@link IDreamOverlay} for testing.
 * @hide
 */
@TestApi
public abstract class DreamOverlayService extends Service {
    private static final String TAG = "DreamOverlayService";
    private static final boolean DEBUG = false;
    private boolean mShowComplications;
    private ComponentName mDreamComponent;

    private IDreamOverlay mDreamOverlay = new IDreamOverlay.Stub() {
        @Override
        public void startDream(WindowManager.LayoutParams layoutParams,
                IDreamOverlayCallback callback, String dreamComponent,
                boolean shouldShowComplications) {
            mDreamOverlayCallback = callback;
            mDreamComponent = ComponentName.unflattenFromString(dreamComponent);
            mShowComplications = shouldShowComplications;
            onStartDream(layoutParams);
        }

        @Override
        public void endDream() {
            onEndDream();
        }

        @Override
        public void wakeUp() {
            onWakeUp(() -> {
                try {
                    mDreamOverlayCallback.onWakeUpComplete();
                } catch (RemoteException e) {
                    Log.e(TAG, "Could not notify dream of wakeUp:" + e);
                }
            });
        }
    };

    IDreamOverlayCallback mDreamOverlayCallback;

    public DreamOverlayService() {
    }

    @Nullable
    @Override
    public final IBinder onBind(@NonNull Intent intent) {
        return mDreamOverlay.asBinder();
    }

    /**
     * This method is overridden by implementations to handle when the dream has started and the
     * window is ready to be interacted with.
     * @param layoutParams The {@link android.view.WindowManager.LayoutParams} associated with the
     *                     dream window.
     */
    public abstract void onStartDream(@NonNull WindowManager.LayoutParams layoutParams);

    /**
     * This method is overridden by implementations to handle when the dream has been requested
     * to wakeup. This allows any overlay animations to run. By default, the method will invoke
     * the callback immediately.
     *
     * @param onCompleteCallback The callback to trigger to notify the dream service that the
     *                           overlay has completed waking up.
     * @hide
     */
    public void onWakeUp(@NonNull Runnable onCompleteCallback) {
        onCompleteCallback.run();
    }

    /**
     * This method is overridden by implementations to handle when the dream has ended. There may
     * be earlier signals leading up to this step, such as @{@link #onWakeUp(Runnable)}.
     */
    public void onEndDream() {
    }

    /**
     * This method is invoked to request the dream exit.
     */
    public final void requestExit() {
        try {
            mDreamOverlayCallback.onExitRequested();
        } catch (RemoteException e) {
            Log.e(TAG, "Could not request exit:" + e);
        }
    }

    /**
     * Returns whether to show complications on the dream overlay.
     */
    public final boolean shouldShowComplications() {
        return mShowComplications;
    }

    /**
     * Returns the active dream component.
     * @hide
     */
    public final ComponentName getDreamComponent() {
        return mDreamComponent;
    }
}
