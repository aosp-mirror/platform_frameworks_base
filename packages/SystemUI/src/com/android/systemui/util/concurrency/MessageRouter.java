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

package com.android.systemui.util.concurrency;

/**
 * Allows triggering methods based on a passed in id or message, generally on another thread.
 *
 * Messages sent on to this router must be processed in order. That is to say, if three
 * messages are sent with no delay, they must be processed in the order they were sent. Moreover,
 * if messages are sent with various delays, they must be processed in order of their delay.
 *
 *  Messages can be passed by either a simple integer or an instance of a class. Unique integers are
 *  considered unique messages. Unique message classes (not instances) are considered unique
 *  messages. You can use message classes to pass extra data for processing to subscribers.
 *
 *  <pre>
 *      // Three messages with three unique integer messages.
 *      // They can be subscribed to independently.
 *      router.sendMessage(0);
 *      router.sendMessage(1);
 *      router.sendMessage(2);
 *
 *      // Three messages with two unique message classes.
 *      // The first and third messages will be delivered to the same subscribers.
 *      router.sendMessage(new Foo(0));
 *      router.sendMessage(new Bar(1));
 *      router.sendMessage(new Foo(2));
 *  </pre>
 *
 * The number of unique ids and message types used should be relatively constrained. Construct
 * a custom message-class and put unique, per-message data inside of it.
 */
public interface MessageRouter {
    /**
     * Alerts any listeners subscribed to the passed in id.
     *
     * The number of unique ids used should be relatively constrained - used to identify the type
     * of message being sent. If unique information needs to be passed with each call, use
     * {@link #sendMessage(Object)}.
     *
     * @param id An identifier for the message
     */
    default void sendMessage(int id) {
        sendMessageDelayed(id, 0);
    }

    /**
     * Alerts any listeners subscribed to the passed in message.
     *
     * The number of message types used should be relatively constrained. If no unique information
     * needs to be passed in, you can simply use {@link #sendMessage(int)}} which takes an integer
     * instead of a unique class type.
     *
     * The class of the passed in object will be used to router the message.
     *
     * @param data A message containing extra data for processing.
     */
    default void sendMessage(Object data) {
        sendMessageDelayed(data, 0);
    }

    /**
     * Alerts any listeners subscribed to the passed in id in the future.
     *
     * The number of unique ids used should be relatively constrained - used to identify the type
     * of message being sent. If unique information needs to be passed with each call, use
     * {@link #sendMessageDelayed(Object, long)}.
     *
     * @param id An identifier for the message
     * @param delayMs Number of milliseconds to wait before alerting.
     */
    void sendMessageDelayed(int id, long delayMs);


    /**
     * Alerts any listeners subscribed to the passed in message in the future.
     *
     * The number of message types used should be relatively constrained. If no unique information
     * needs to be passed in, you can simply use {@link #sendMessageDelayed(int, long)} which takes
     * an integer instead of a unique class type.
     *
     * @param data A message containing extra data for processing.
     * @param delayMs Number of milliseconds to wait before alerting.
     */
    void sendMessageDelayed(Object data, long delayMs);

    /**
     * Cancel all unprocessed messages for a given id.
     *
     * If a message has multiple listeners and one of those listeners has been alerted, the other
     * listeners that follow it may also be alerted. This is only guaranteed to cancel messages
     * that are still queued.
     *
     * @param id The message id to cancel.
     */
    void cancelMessages(int id);

    /**
     * Cancel all unprocessed messages for a given message type.
     *
     * If a message has multiple listeners and one of those listeners has been alerted, the other
     * listeners that follow it may also be alerted. This is only guaranteed to cancel messages
     * that are still queued.
     *
     * @param messageType The class of the message to cancel
     */
    <T> void cancelMessages(Class<T> messageType);

    /**
     * Add a listener for a message that does not handle any extra data.
     *
     * See also {@link #subscribeTo(Class, DataMessageListener)}.
     *
     * @param id The message id to listener for.
     * @param listener
     */
    void subscribeTo(int id, SimpleMessageListener listener);

    /**
     * Add a listener for a message of a specific type.
     *
     * See also {@link #subscribeTo(Class, DataMessageListener)}.
     *
     * @param messageType The class of message to listen for.
     * @param listener
     */
    <T> void subscribeTo(Class<T> messageType, DataMessageListener<T> listener);

    /**
     * Remove a listener for a specific message.
     *
     * See also {@link #unsubscribeFrom(Class, DataMessageListener)}
     *
     * @param id The message id to stop listening for.
     * @param listener The listener to remove.
     */
    void unsubscribeFrom(int id, SimpleMessageListener listener);

    /**
     * Remove a listener for a specific message.
     *
     * See also {@link #unsubscribeFrom(int, SimpleMessageListener)}.
     *
     * @param messageType The class of message to stop listening for.
     * @param listener The listener to remove.
     */
    <T> void unsubscribeFrom(Class<T> messageType, DataMessageListener<T> listener);

    /**
     * Remove a listener for all messages that it is subscribed to.
     *
     * See also {@link #unsubscribeFrom(DataMessageListener)}.
     *
     * @param listener The listener to remove.
     */
    void unsubscribeFrom(SimpleMessageListener listener);

    /**
     * Remove a listener for all messages that it is subscribed to.
     *
     * See also {@link #unsubscribeFrom(SimpleMessageListener)}.
     *
     * @param listener The listener to remove.
     */
    <T> void unsubscribeFrom(DataMessageListener<T> listener);

    /**
     * A Listener interface for when no extra data is expected or desired.
     */
    interface SimpleMessageListener {
        /** */
        void onMessage(int id);
    }

    /**
     * A Listener interface for when extra data is expected or desired.
     *
     * @param <T>
     */
    interface DataMessageListener<T> {
        /** */
        void onMessage(T data);
    }
}
