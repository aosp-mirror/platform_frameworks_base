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

    private IDreamOverlay mDreamOverlay = new IDreamOverlay.Stub() {
        @Override
        public void startDream(WindowManager.LayoutParams layoutParams,
                IDreamOverlayCallback callback) {
            mDreamOverlayCallback = callback;
            onStartDream(layoutParams);
        }
    };

    IDreamOverlayCallback mDreamOverlayCallback;

    public DreamOverlayService() {
    }

    @Nullable
    @Override
    public final IBinder onBind(@NonNull Intent intent) {
        mShowComplications = intent.getBooleanExtra(DreamService.EXTRA_SHOW_COMPLICATIONS,
                DreamService.DEFAULT_SHOW_COMPLICATIONS);
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
}
