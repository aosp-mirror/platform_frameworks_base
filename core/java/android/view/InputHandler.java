/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.view;

/**
 * Handles input messages that arrive on an input channel.
 * @hide
 */
public interface InputHandler {
    /**
     * Handle a key event.
     * It is the responsibility of the callee to ensure that the finished callback is
     * eventually invoked when the event processing is finished and the input system
     * can send the next event.
     * @param event The key event data.
     * @param finishedCallback The callback to invoke when event processing is finished.
     */
    public void handleKey(KeyEvent event, InputQueue.FinishedCallback finishedCallback);
    
    /**
     * Handle a motion event.
     * It is the responsibility of the callee to ensure that the finished callback is
     * eventually invoked when the event processing is finished and the input system
     * can send the next event.
     * @param event The motion event data.
     * @param finishedCallback The callback to invoke when event processing is finished.
     */
    public void handleMotion(MotionEvent event, InputQueue.FinishedCallback finishedCallback);
}
