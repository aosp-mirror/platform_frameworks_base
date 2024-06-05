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
package com.android.internal.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Objects;

@RunWith(AndroidJUnit4.class)
public class CallbackRegistryTest {

    final Integer callback1 = 1;
    final Integer callback2 = 2;
    final Integer callback3 = 3;
    CallbackRegistry<Integer, CallbackRegistryTest, Integer> registry;
    int notify1;
    int notify2;
    int notify3;
    int[] deepNotifyCount = new int[300];
    Integer argValue;

    private void addNotifyCount(Integer callback) {
        if (Objects.equals(callback, callback1)) {
            notify1++;
        } else if (Objects.equals(callback, callback2)) {
            notify2++;
        } else if (Objects.equals(callback, callback3)) {
            notify3++;
        }
        deepNotifyCount[callback]++;
    }

    @Test
    public void testAddListener() {
        CallbackRegistry.NotifierCallback<Integer, CallbackRegistryTest, Integer> notifier =
                new CallbackRegistry.NotifierCallback<Integer, CallbackRegistryTest, Integer>() {
                    @Override
                    public void onNotifyCallback(Integer callback, CallbackRegistryTest sender,
                            int arg, Integer arg2) {
                    }
                };
        registry = new CallbackRegistry<Integer, CallbackRegistryTest, Integer>(notifier);
        Integer callback = 0;

        assertNotNull(registry.copyListeners());
        assertEquals(0, registry.copyListeners().size());

        registry.add(callback);
        ArrayList<Integer> callbacks = registry.copyListeners();
        assertEquals(1, callbacks.size());
        assertEquals(callback, callbacks.get(0));

        registry.add(callback);
        callbacks = registry.copyListeners();
        assertEquals(1, callbacks.size());
        assertEquals(callback, callbacks.get(0));

        Integer otherListener = 1;
        registry.add(otherListener);
        callbacks = registry.copyListeners();
        assertEquals(2, callbacks.size());
        assertEquals(callback, callbacks.get(0));
        assertEquals(otherListener, callbacks.get(1));

        registry.remove(callback);
        registry.add(callback);
        callbacks = registry.copyListeners();
        assertEquals(2, callbacks.size());
        assertEquals(callback, callbacks.get(1));
        assertEquals(otherListener, callbacks.get(0));
    }

    @Test
    public void testSimpleNotify() {
        CallbackRegistry.NotifierCallback<Integer, CallbackRegistryTest, Integer> notifier =
                new CallbackRegistry.NotifierCallback<Integer, CallbackRegistryTest, Integer>() {
                    @Override
                    public void onNotifyCallback(Integer callback, CallbackRegistryTest sender,
                            int arg1, Integer arg) {
                        assertEquals(arg1, (int) arg);
                        addNotifyCount(callback);
                        argValue = arg;
                    }
                };
        registry = new CallbackRegistry<Integer, CallbackRegistryTest, Integer>(notifier);
        registry.add(callback2);
        Integer arg = 1;
        registry.notifyCallbacks(this, arg, arg);
        assertEquals(arg, argValue);
        assertEquals(1, notify2);
    }

    @Test
    public void testRemoveWhileNotifying() {
        CallbackRegistry.NotifierCallback<Integer, CallbackRegistryTest, Integer> notifier =
                new CallbackRegistry.NotifierCallback<Integer, CallbackRegistryTest, Integer>() {
                    @Override
                    public void onNotifyCallback(Integer callback, CallbackRegistryTest sender,
                            int arg1, Integer arg) {
                        addNotifyCount(callback);
                        if (Objects.equals(callback, callback1)) {
                            registry.remove(callback1);
                            registry.remove(callback2);
                        }
                    }
                };
        registry = new CallbackRegistry<Integer, CallbackRegistryTest, Integer>(notifier);
        registry.add(callback1);
        registry.add(callback2);
        registry.add(callback3);
        registry.notifyCallbacks(this, 0, null);
        assertEquals(1, notify1);
        assertEquals(1, notify2);
        assertEquals(1, notify3);

        ArrayList<Integer> callbacks = registry.copyListeners();
        assertEquals(1, callbacks.size());
        assertEquals(callback3, callbacks.get(0));
    }

    @Test
    public void testDeepRemoveWhileNotifying() {
        CallbackRegistry.NotifierCallback<Integer, CallbackRegistryTest, Integer> notifier =
                new CallbackRegistry.NotifierCallback<Integer, CallbackRegistryTest, Integer>() {
                    @Override
                    public void onNotifyCallback(Integer callback, CallbackRegistryTest sender,
                            int arg1, Integer arg) {
                        addNotifyCount(callback);
                        registry.remove(callback);
                        registry.notifyCallbacks(CallbackRegistryTest.this, arg1, null);
                    }
                };
        registry = new CallbackRegistry<Integer, CallbackRegistryTest, Integer>(notifier);
        registry.add(callback1);
        registry.add(callback2);
        registry.add(callback3);
        registry.notifyCallbacks(this, 0, null);
        assertEquals(1, notify1);
        assertEquals(2, notify2);
        assertEquals(3, notify3);

        ArrayList<Integer> callbacks = registry.copyListeners();
        assertEquals(0, callbacks.size());
    }

    @Test
    public void testAddRemovedListener() {

        CallbackRegistry.NotifierCallback<Integer, CallbackRegistryTest, Integer> notifier =
                new CallbackRegistry.NotifierCallback<Integer, CallbackRegistryTest, Integer>() {
                    @Override
                    public void onNotifyCallback(Integer callback, CallbackRegistryTest sender,
                            int arg1, Integer arg) {
                        addNotifyCount(callback);
                        if (Objects.equals(callback, callback1)) {
                            registry.remove(callback2);
                        } else if (Objects.equals(callback, callback3)) {
                            registry.add(callback2);
                        }
                    }
                };
        registry = new CallbackRegistry<Integer, CallbackRegistryTest, Integer>(notifier);

        registry.add(callback1);
        registry.add(callback2);
        registry.add(callback3);
        registry.notifyCallbacks(this, 0, null);

        ArrayList<Integer> callbacks = registry.copyListeners();
        assertEquals(3, callbacks.size());
        assertEquals(callback1, callbacks.get(0));
        assertEquals(callback3, callbacks.get(1));
        assertEquals(callback2, callbacks.get(2));
        assertEquals(1, notify1);
        assertEquals(1, notify2);
        assertEquals(1, notify3);
    }

    @Test
    public void testVeryDeepRemoveWhileNotifying() {
        final Integer[] callbacks = new Integer[deepNotifyCount.length];
        for (int i = 0; i < callbacks.length; i++) {
            callbacks[i] = i;
        }
        CallbackRegistry.NotifierCallback<Integer, CallbackRegistryTest, Integer> notifier =
                new CallbackRegistry.NotifierCallback<Integer, CallbackRegistryTest, Integer>() {
                    @Override
                    public void onNotifyCallback(Integer callback, CallbackRegistryTest sender,
                            int arg1, Integer arg) {
                        addNotifyCount(callback);
                        registry.remove(callback);
                        registry.remove(callbacks[callbacks.length - callback - 1]);
                        registry.notifyCallbacks(CallbackRegistryTest.this, arg1, null);
                    }
                };
        registry = new CallbackRegistry<Integer, CallbackRegistryTest, Integer>(notifier);
        for (int i = 0; i < callbacks.length; i++) {
            registry.add(callbacks[i]);
        }
        registry.notifyCallbacks(this, 0, null);
        for (int i = 0; i < deepNotifyCount.length; i++) {
            int expectedCount = Math.min(i + 1, deepNotifyCount.length - i);
            assertEquals(expectedCount, deepNotifyCount[i]);
        }

        ArrayList<Integer> callbackList = registry.copyListeners();
        assertEquals(0, callbackList.size());
    }

    @Test
    public void testClear() {
        CallbackRegistry.NotifierCallback<Integer, CallbackRegistryTest, Integer> notifier =
                new CallbackRegistry.NotifierCallback<Integer, CallbackRegistryTest, Integer>() {
                    @Override
                    public void onNotifyCallback(Integer callback, CallbackRegistryTest sender,
                            int arg1, Integer arg) {
                        addNotifyCount(callback);
                    }
                };
        registry = new CallbackRegistry<Integer, CallbackRegistryTest, Integer>(notifier);
        for (int i = 0; i < deepNotifyCount.length; i++) {
            registry.add(i);
        }
        registry.clear();

        ArrayList<Integer> callbackList = registry.copyListeners();
        assertEquals(0, callbackList.size());

        registry.notifyCallbacks(this, 0, null);
        for (int i = 0; i < deepNotifyCount.length; i++) {
            assertEquals(0, deepNotifyCount[i]);
        }
    }

    @Test
    public void testNestedClear() {
        CallbackRegistry.NotifierCallback<Integer, CallbackRegistryTest, Integer> notifier =
                new CallbackRegistry.NotifierCallback<Integer, CallbackRegistryTest, Integer>() {
                    @Override
                    public void onNotifyCallback(Integer callback, CallbackRegistryTest sender,
                            int arg1, Integer arg) {
                        addNotifyCount(callback);
                        registry.clear();
                    }
                };
        registry = new CallbackRegistry<Integer, CallbackRegistryTest, Integer>(notifier);
        for (int i = 0; i < deepNotifyCount.length; i++) {
            registry.add(i);
        }
        registry.notifyCallbacks(this, 0, null);
        for (int i = 0; i < deepNotifyCount.length; i++) {
            assertEquals(1, deepNotifyCount[i]);
        }

        ArrayList<Integer> callbackList = registry.copyListeners();
        assertEquals(0, callbackList.size());
    }

    @Test
    public void testIsEmpty() throws Exception {
        CallbackRegistry.NotifierCallback<Integer, CallbackRegistryTest, Integer> notifier =
                new CallbackRegistry.NotifierCallback<Integer, CallbackRegistryTest, Integer>() {
                    @Override
                    public void onNotifyCallback(Integer callback, CallbackRegistryTest sender,
                            int arg, Integer arg2) {
                    }
                };
        registry = new CallbackRegistry<Integer, CallbackRegistryTest, Integer>(notifier);
        Integer callback = 0;

        assertTrue(registry.isEmpty());
        registry.add(callback);
        assertFalse(registry.isEmpty());
    }

    @Test
    public void testClone() throws Exception {
        CallbackRegistry.NotifierCallback<Integer, CallbackRegistryTest, Integer> notifier =
                new CallbackRegistry.NotifierCallback<Integer, CallbackRegistryTest, Integer>() {
                    @Override
                    public void onNotifyCallback(Integer callback, CallbackRegistryTest sender,
                            int arg, Integer arg2) {
                    }
                };
        registry = new CallbackRegistry<Integer, CallbackRegistryTest, Integer>(notifier);

        assertTrue(registry.isEmpty());
        CallbackRegistry<Integer, CallbackRegistryTest, Integer> registry2 = registry.clone();
        Integer callback = 0;
        registry.add(callback);
        assertFalse(registry.isEmpty());
        assertTrue(registry2.isEmpty());
        registry2 = registry.clone();
        assertFalse(registry2.isEmpty());
    }
}
