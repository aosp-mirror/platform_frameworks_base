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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of {@link MessageRouter}.
 */
public class MessageRouterImpl implements MessageRouter {

    private final DelayableExecutor mDelayableExecutor;

    private final Map<Integer, List<Runnable>> mIdMessageCancelers = new HashMap<>();
    private final Map<Class<Object>, List<Runnable>> mDataMessageCancelers = new HashMap<>();
    private final Map<Integer, List<SimpleMessageListener>> mSimpleMessageListenerMap =
            new HashMap<>();
    private final Map<Class<?>, List<DataMessageListener<Object>>> mDataMessageListenerMap =
            new HashMap<>();

    public MessageRouterImpl(DelayableExecutor delayableExecutor) {
        mDelayableExecutor = delayableExecutor;
    }

    @Override
    public void sendMessageDelayed(int id, long delayMs) {
        addCanceler(id, mDelayableExecutor.executeDelayed(() -> onMessage(id), delayMs));
    }

    @Override
    public void sendMessageDelayed(Object data, long delayMs) {
        addCanceler((Class<Object>) data.getClass(), mDelayableExecutor.executeDelayed(
                () -> onMessage(data), delayMs));
    }

    @Override
    public void cancelMessages(int id) {
        synchronized (mIdMessageCancelers) {
            if (mIdMessageCancelers.containsKey(id)) {
                for (Runnable canceler : mIdMessageCancelers.get(id)) {
                    canceler.run();
                }
                // Remove, don't clear, otherwise this could look like a memory leak as
                // more and more unique message ids are passed in.
                mIdMessageCancelers.remove(id);
            }
        }
    }

    @Override
    public <T> void cancelMessages(Class<T> messageType) {
        synchronized (mDataMessageCancelers) {
            if (mDataMessageCancelers.containsKey(messageType)) {
                for (Runnable canceler : mDataMessageCancelers.get(messageType)) {
                    canceler.run();
                }
                // Remove, don't clear, otherwise this could look like a memory leak as
                // more and more unique message types are passed in.
                mDataMessageCancelers.remove(messageType);
            }
        }
    }

    @Override
    public void subscribeTo(int id, SimpleMessageListener listener) {
        synchronized (mSimpleMessageListenerMap) {
            mSimpleMessageListenerMap.putIfAbsent(id, new ArrayList<>());
            mSimpleMessageListenerMap.get(id).add(listener);
        }
    }

    @Override
    public <T> void subscribeTo(Class<T> messageType, DataMessageListener<T> listener) {
        synchronized (mDataMessageListenerMap) {
            mDataMessageListenerMap.putIfAbsent(messageType, new ArrayList<>());
            mDataMessageListenerMap.get(messageType).add((DataMessageListener<Object>) listener);
        }
    }

    @Override
    public void unsubscribeFrom(int id, SimpleMessageListener listener) {
        synchronized (mSimpleMessageListenerMap) {
            if (mSimpleMessageListenerMap.containsKey(id)) {
                mSimpleMessageListenerMap.get(id).remove(listener);
            }
        }
    }

    @Override
    public <T> void unsubscribeFrom(Class<T> messageType, DataMessageListener<T> listener) {
        synchronized (mDataMessageListenerMap) {
            if (mDataMessageListenerMap.containsKey(messageType)) {
                mDataMessageListenerMap.get(messageType).remove(listener);
            }
        }
    }

    @Override
    public void unsubscribeFrom(SimpleMessageListener listener) {
        synchronized (mSimpleMessageListenerMap) {
            for (Integer id : mSimpleMessageListenerMap.keySet()) {
                mSimpleMessageListenerMap.get(id).remove(listener);
            }
        }
    }

    @Override
    public <T> void unsubscribeFrom(DataMessageListener<T> listener) {
        synchronized (mDataMessageListenerMap) {
            for (Class<?> messageType : mDataMessageListenerMap.keySet()) {
                mDataMessageListenerMap.get(messageType).remove(listener);
            }
        }
    }

    private void addCanceler(int id, Runnable canceler) {
        synchronized (mIdMessageCancelers) {
            mIdMessageCancelers.putIfAbsent(id, new ArrayList<>());
            mIdMessageCancelers.get(id).add(canceler);
        }
    }

    private void addCanceler(Class<Object> data, Runnable canceler) {
        synchronized (mDataMessageCancelers) {
            mDataMessageCancelers.putIfAbsent(data, new ArrayList<>());
            mDataMessageCancelers.get(data).add(canceler);
        }
    }

    private void onMessage(int id) {
        synchronized (mSimpleMessageListenerMap) {
            if (mSimpleMessageListenerMap.containsKey(id)) {
                for (SimpleMessageListener listener : mSimpleMessageListenerMap.get(id)) {
                    listener.onMessage(id);
                }
            }
        }

        synchronized (mIdMessageCancelers) {
            if (mIdMessageCancelers.containsKey(id) && !mIdMessageCancelers.get(id).isEmpty()) {
                mIdMessageCancelers.get(id).remove(0);
                if (mIdMessageCancelers.get(id).isEmpty()) {
                    mIdMessageCancelers.remove(id);
                }
            }
        }
    }

    private void onMessage(Object data) {
        synchronized (mDataMessageListenerMap) {
            if (mDataMessageListenerMap.containsKey(data.getClass())) {
                for (DataMessageListener<Object> listener : mDataMessageListenerMap.get(
                        data.getClass())) {
                    listener.onMessage(data);
                }
            }
        }

        synchronized (mDataMessageCancelers) {
            if (mDataMessageCancelers.containsKey(data.getClass())
                    && !mDataMessageCancelers.get(data.getClass()).isEmpty()) {
                mDataMessageCancelers.get(data.getClass()).remove(0);
                if (mDataMessageCancelers.get(data.getClass()).isEmpty()) {
                    mDataMessageCancelers.remove(data.getClass());
                }
            }
        }
    }
}
