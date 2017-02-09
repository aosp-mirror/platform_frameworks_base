/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.utils;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.util.ArrayMap;

import org.junit.runners.model.Statement;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Creates a looper on the current thread with control over if/when messages are
 * executed. Warning: This class works through some reflection and may break/need
 * to be updated from time to time.
 */
public class TestableLooper {

    private final Method mNext;
    private final Method mRecycleUnchecked;

    private Looper mLooper;
    private MessageQueue mQueue;
    private boolean mMain;
    private Object mOriginalMain;
    private MessageHandler mMessageHandler;

    private int mParsedCount;
    private Handler mHandler;
    private Message mEmptyMessage;

    public TestableLooper() throws Exception {
        this(true);
    }

    public TestableLooper(boolean setMyLooper) throws Exception {
        setupQueue(setMyLooper);
        mNext = mQueue.getClass().getDeclaredMethod("next");
        mNext.setAccessible(true);
        mRecycleUnchecked = Message.class.getDeclaredMethod("recycleUnchecked");
        mRecycleUnchecked.setAccessible(true);
    }

    public Looper getLooper() {
        return mLooper;
    }

    private void clearLooper() throws NoSuchFieldException, IllegalAccessException {
        Field field = Looper.class.getDeclaredField("sThreadLocal");
        field.setAccessible(true);
        ThreadLocal<Looper> sThreadLocal = (ThreadLocal<Looper>) field.get(null);
        sThreadLocal.set(null);
    }

    private boolean setForCurrentThread() throws NoSuchFieldException, IllegalAccessException {
        if (Looper.myLooper() != mLooper) {
            Field field = Looper.class.getDeclaredField("sThreadLocal");
            field.setAccessible(true);
            ThreadLocal<Looper> sThreadLocal = (ThreadLocal<Looper>) field.get(null);
            sThreadLocal.set(mLooper);
            return true;
        }
        return false;
    }

    private void setupQueue(boolean setMyLooper) throws Exception {
        if (setMyLooper) {
            clearLooper();
            Looper.prepare();
            mLooper = Looper.myLooper();
        } else {
            Constructor<Looper> constructor = Looper.class.getDeclaredConstructor(
                    boolean.class);
            constructor.setAccessible(true);
            mLooper = constructor.newInstance(true);
        }

        mQueue = mLooper.getQueue();
        mHandler = new Handler(mLooper);
    }

    public void setAsMainLooper() throws NoSuchFieldException, IllegalAccessException {
        mMain = true;
        setAsMainInt();
    }

    private void setAsMainInt() throws NoSuchFieldException, IllegalAccessException {
        Field field = mLooper.getClass().getDeclaredField("sMainLooper");
        field.setAccessible(true);
        if (mOriginalMain == null) {
            mOriginalMain = field.get(null);
        }
        field.set(null, mLooper);
    }

    /**
     * Must be called if setAsMainLooper is called to restore the main looper when the
     * test is complete, otherwise the main looper will not be available for any subsequent
     * tests.
     */
    public void destroy() throws NoSuchFieldException, IllegalAccessException {
        if (Looper.myLooper() == mLooper) {
            clearLooper();
        }
        if (mMain && mOriginalMain != null) {
            Field field = mLooper.getClass().getDeclaredField("sMainLooper");
            field.setAccessible(true);
            field.set(null, mOriginalMain);
            mOriginalMain = null;
        }
    }

    public void setMessageHandler(MessageHandler handler) {
        mMessageHandler = handler;
    }

    /**
     * Parse num messages from the message queue.
     *
     * @param num Number of messages to parse
     */
    public int processMessages(int num) {
        for (int i = 0; i < num; i++) {
            if (!parseMessageInt()) {
                return i + 1;
            }
        }
        return num;
    }

    public void processAllMessages() {
        while (processQueuedMessages() != 0) ;
    }

    private int processQueuedMessages() {
        int count = 0;
        mEmptyMessage = mHandler.obtainMessage(1);
        mHandler.sendMessageDelayed(mEmptyMessage, 1);
        while (parseMessageInt()) count++;
        return count;
    }

    private boolean parseMessageInt() {
        try {
            Message result = (Message) mNext.invoke(mQueue);
            if (result != null) {
                // This is a break message.
                if (result == mEmptyMessage) {
                    mRecycleUnchecked.invoke(result);
                    return false;
                }

                if (mMessageHandler != null) {
                    if (mMessageHandler.onMessageHandled(result)) {
                        result.getTarget().dispatchMessage(result);
                        mRecycleUnchecked.invoke(result);
                    } else {
                        mRecycleUnchecked.invoke(result);
                        // Message handler indicated it doesn't want us to continue.
                        return false;
                    }
                } else {
                    result.getTarget().dispatchMessage(result);
                    mRecycleUnchecked.invoke(result);
                }
            } else {
                // No messages, don't continue parsing
                return false;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    /**
     * Runs an executable with myLooper set and processes all messages added.
     */
    public void runWithLooper(RunnableWithException runnable) throws Exception {
        boolean set = setForCurrentThread();
        runnable.run();
        processAllMessages();
        if (set) clearLooper();
    }

    public interface RunnableWithException {
        void run() throws Exception;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    public @interface RunWithLooper {
        boolean setAsMainLooper() default false;
    }

    private static final Map<Object, TestableLooper> sLoopers = new ArrayMap<>();

    public static TestableLooper get(Object test) {
        return sLoopers.get(test);
    }

    public static class LooperStatement extends Statement {
        private final boolean mSetAsMain;
        private final Statement mBase;
        private final TestableLooper mLooper;

        public LooperStatement(Statement base, boolean setAsMain, Object test) {
            mBase = base;
            try {
                mLooper = new TestableLooper(false);
                sLoopers.put(test, mLooper);
                mSetAsMain = setAsMain;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void evaluate() throws Throwable {
            mLooper.setForCurrentThread();
            if (mSetAsMain) {
                mLooper.setAsMainLooper();
            }

            mBase.evaluate();

            mLooper.destroy();
        }
    }

    public interface MessageHandler {
        /**
         * Return true to have the message executed and delivered to target.
         * Return false to not execute the message and stop executing messages.
         */
        boolean onMessageHandled(Message m);
    }
}
