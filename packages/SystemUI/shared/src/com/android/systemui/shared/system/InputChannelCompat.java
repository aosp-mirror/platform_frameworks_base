/**
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.shared.system;

import android.os.Looper;
import android.util.Pair;
import android.view.BatchedInputEventReceiver;
import android.view.Choreographer;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventSender;

/**
 * @see android.view.InputChannel
 */
public class InputChannelCompat {

    /**
     * Callback for receiving event callbacks
     */
    public interface InputEventListener {
        /**
         * @param ev event to be handled
         */
        void onInputEvent(InputEvent ev);
    }

    /**
     * Creates a dispatcher and receiver pair to better handle events across threads.
     */
    public static Pair<InputEventDispatcher, InputEventReceiver> createPair(String name,
            Looper looper, Choreographer choreographer, InputEventListener listener) {
        InputChannel[] channels = InputChannel.openInputChannelPair(name);

        InputEventDispatcher dispatcher = new InputEventDispatcher(channels[0], looper);
        InputEventReceiver receiver = new InputEventReceiver(channels[1], looper, choreographer,
                listener);
        return Pair.create(dispatcher, receiver);
    }

    /**
     * @see BatchedInputEventReceiver
     */
    public static class InputEventReceiver {

        private final BatchedInputEventReceiver mReceiver;
        private final InputChannel mInputChannel;

        public InputEventReceiver(InputChannel inputChannel, Looper looper,
                Choreographer choreographer, final InputEventListener listener) {
            mInputChannel = inputChannel;
            mReceiver = new BatchedInputEventReceiver(inputChannel, looper, choreographer) {

                @Override
                public void onInputEvent(InputEvent event) {
                    listener.onInputEvent(event);
                    finishInputEvent(event, true /* handled */);
                }
            };
        }

        /**
         * @see BatchedInputEventReceiver#dispose()
         */
        public void dispose() {
            mReceiver.dispose();
            mInputChannel.dispose();
        }
    }

    /**
     * @see InputEventSender
     */
    public static class InputEventDispatcher {

        private final InputChannel mInputChannel;
        private final InputEventSender mSender;

        private InputEventDispatcher(InputChannel inputChannel, Looper looper) {
            mInputChannel = inputChannel;
            mSender = new InputEventSender(inputChannel, looper) { };
        }

        /**
         * @see InputEventSender#sendInputEvent(int, InputEvent)
         */
        public void dispatch(InputEvent ev) {
            mSender.sendInputEvent(ev.getSequenceNumber(), ev);
        }

        /**
         * @see InputEventSender#dispose()
         */
        public void dispose() {
            mSender.dispose();
            mInputChannel.dispose();
        }
    }
}
