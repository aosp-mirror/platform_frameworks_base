/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.accessibility;

import android.app.Notification;
import android.os.Handler;
import android.os.Message;
import android.util.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class to capture messages dispatched through a handler and control when they arrive
 * at their target.
 */
public class MessageCapturingHandler extends Handler {
    List<Pair<Message, Long>> timedMessages = new ArrayList<>();

    Handler.Callback mCallback;

    public MessageCapturingHandler(Handler.Callback callback) {
        mCallback = callback;
    }

    @Override
    public boolean sendMessageAtTime(Message message, long uptimeMillis) {
        timedMessages.add(new Pair<>(Message.obtain(message), uptimeMillis));
        return super.sendMessageAtTime(message, uptimeMillis);
    }

    public void setCallback(Handler.Callback callback) {
        mCallback = callback;
    }

    public void sendOneMessage() {
        Message message = timedMessages.remove(0).first;
        removeMessages(message.what, message.obj);
        dispatchMessage(message);
        removeStaleMessages();
    }

    public void sendAllMessages() {
        while (!timedMessages.isEmpty()) {
            sendOneMessage();
        }
    }

    public void sendLastMessage() {
        Message message = timedMessages.remove(timedMessages.size() - 1).first;
        removeMessages(message.what, message.obj);
        dispatchMessage(message);
        removeStaleMessages();
    }

    public boolean hasMessages() {
        removeStaleMessages();
        return !timedMessages.isEmpty();
    }

    private void removeStaleMessages() {
        for (int i = 0; i < timedMessages.size(); i++) {
            Message message = timedMessages.get(i).first;
            if (!hasMessages(message.what, message.obj)) {
                timedMessages.remove(i--);
            }
        }
    }

    public void dispatchMessage(Message m) {
        if (mCallback != null) {
            mCallback.handleMessage(m);
            return;
        }
        super.dispatchMessage(m);
    }
}
