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
 * limitations under the License.
 */

package android.webkit;

import android.os.Handler;

/**
 * The Java representation of the HTML5 Message Port. See
 * https://html.spec.whatwg.org/multipage/comms.html#messageport
 * for definition of MessagePort in HTML5.
 *
 * A Message port represents one endpoint of a Message Channel. In Android
 * webview, there is no separate Message Channel object. When a message channel
 * is created, both ports are tangled to each other and started, and then
 * returned in a MessagePort array, see {@link WebView#createMessageChannel}
 * for creating a message channel.
 *
 * When a message port is first created or received via transfer, it does not
 * have a WebMessageListener to receive web messages. The messages are queued until
 * a WebMessageListener is set.
 *
 * @hide unhide when implementation is complete
 */
public abstract class WebMessagePort {

    /**
     * The listener for handling MessagePort events. The message listener
     * methods are called on the main thread. If the embedder application
     * wants to receive the messages on a different thread, it can do this
     * by passing a Handler in {@link setWebMessageListener(WebMessageListener, Handler)}.
     * In the latter case, the application should be extra careful for thread safety
     * since WebMessagePort methods should be called on main thread.
     */
    public static abstract class WebMessageListener {
        /**
         * Message listener for receiving onMessage events.
         *
         * @param port  The WebMessagePort that the message is destined for
         * @param message  The message from the entangled port.
         */
        public abstract void onMessage(WebMessagePort port, WebMessage message);
    }

    /**
     * Post a WebMessage to the entangled port.
     *
     * @param The message.
     *
     * @throws IllegalStateException If message port is already transferred or closed.
     */
    public abstract void postMessage(WebMessage message);

    /**
     * Close the message port and free any resources associated with it.
     */
    public abstract void close();

    /**
     * Sets a listener to receive message events on the main thread.
     *
     * @param listener  The message listener.
     */
    public abstract void setWebMessageListener(WebMessageListener listener);

    /**
     * Sets a listener to receive message events on the handler that is provided
     * by the application.
     *
     * @param listener  The message listener.
     * @param handler   The handler to receive the message messages.
     */
    public abstract void setWebMessageListener(WebMessageListener listener, Handler handler);
}
