/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package android.service.media;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;

/**
 * Extend this class to implement a camera prewarm service. See
 * {@link android.provider.MediaStore#META_DATA_STILL_IMAGE_CAMERA_PREWARM_SERVICE}.
 */
public abstract class CameraPrewarmService extends Service {

    /**
     * Intent action to bind the service as a prewarm service.
     * @hide
     */
    public static final String ACTION_PREWARM =
            "android.service.media.CameraPrewarmService.ACTION_PREWARM";

    /**
     * Message sent by the client indicating that the camera intent has been fired.
     * @hide
     */
    public static final int MSG_CAMERA_FIRED = 1;

    private final Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_CAMERA_FIRED:
                    mCameraIntentFired = true;
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    };
    private boolean mCameraIntentFired;

    @Override
    public IBinder onBind(Intent intent) {
        if (ACTION_PREWARM.equals(intent.getAction())) {
            onPrewarm();
            return new Messenger(mHandler).getBinder();
        } else {
            return null;
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (ACTION_PREWARM.equals(intent.getAction())) {
            onCooldown(mCameraIntentFired);
        }
        return false;
    }

    /**
     * Called when the camera should be prewarmed.
     */
    public abstract void onPrewarm();

    /**
     * Called when prewarm phase is done, either because the camera launch intent has been fired
     * at this point or prewarm is no longer needed. A client should close the camera
     * immediately in the latter case.
     * <p>
     * In case the camera launch intent has been fired, there is no guarantee about the ordering
     * of these two events. Cooldown might happen either before or after the activity has been
     * created that handles the camera intent.
     *
     * @param cameraIntentFired Indicates whether the intent to launch the camera has been
     *                          fired.
     */
    public abstract void onCooldown(boolean cameraIntentFired);
}
