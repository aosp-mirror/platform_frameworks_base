/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.speech.hotword;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;

/**
 * Used for receiving notifications from the HotwordRecognitionService when the
 * hotword recognition related events occur.
 * All the callbacks are executed on the application main thread.
 * {@hide}
 */
public interface HotwordRecognitionListener {
    /**
     * Called when the service starts listening for hotword.
     */
    void onHotwordRecognitionStarted();

    /**
     * Called when the service stops listening for hotword.
     */
    void onHotwordRecognitionStopped();

    /**
     * Called on an event of interest to the client.
     *
     * @param eventType the event type.
     * @param eventBundle a Bundle containing the hotword event(s).
     */
    void onHotwordEvent(int eventType, Bundle eventBundle);

    /**
     * Called back when hotword is detected.
     * The action tells the client what action to take, post hotword-detection.
     */
    void onHotwordRecognized(PendingIntent intent);

    /**
     * Called when the HotwordRecognitionService encounters an error.
     *
     * @param errorCode the error code describing the error that was encountered.
     */
    void onHotwordError(int errorCode);
}