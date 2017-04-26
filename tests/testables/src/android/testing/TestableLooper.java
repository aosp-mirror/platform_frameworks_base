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

package android.testing;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.TestLooperManager;
import android.support.test.InstrumentationRegistry;
import android.util.ArrayMap;

import org.junit.runners.model.FrameworkMethod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * Creates a looper on the current thread with control over if/when messages are
 * executed. Warning: This class works through some reflection and may break/need
 * to be updated from time to time.
 */
public class TestableLooper {

    private Looper mLooper;
    private MessageQueue mQueue;
    private boolean mMain;
    private Object mOriginalMain;
    private MessageHandler mMessageHandler;

    private Handler mHandler;
    private Runnable mEmptyMessage;
    private TestLooperManager mQueueWrapper;

    public TestableLooper(Looper l) throws Exception {
        this(InstrumentationRegistry.getInstrumentation().acquireLooperManager(l), l);
    }

    private TestableLooper(TestLooperManager wrapper, Looper l) throws Exception {
        mQueueWrapper = wrapper;
        setupQueue(l);
    }

    private TestableLooper(Looper looper, boolean b) throws Exception {
        setupQueue(looper);
    }

    public Looper getLooper() {
        return mLooper;
    }

    private void setupQueue(Looper l) throws Exception {
        mLooper = l;
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
        mQueueWrapper.release();
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
        mEmptyMessage = () -> { };
        mHandler.post(mEmptyMessage);
        waitForMessage(mQueueWrapper, mHandler, mEmptyMessage);
        while (parseMessageInt()) count++;
        return count;
    }

    private boolean parseMessageInt() {
        try {
            Message result = mQueueWrapper.next();
            if (result != null) {
                // This is a break message.
                if (result.getCallback() == mEmptyMessage) {
                    mQueueWrapper.recycle(result);
                    return false;
                }

                if (mMessageHandler != null) {
                    if (mMessageHandler.onMessageHandled(result)) {
                        result.getTarget().dispatchMessage(result);
                        mQueueWrapper.recycle(result);
                    } else {
                        mQueueWrapper.recycle(result);
                        // Message handler indicated it doesn't want us to continue.
                        return false;
                    }
                } else {
                    result.getTarget().dispatchMessage(result);
                    mQueueWrapper.recycle(result);
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
        new Handler(getLooper()).post(() -> {
            try {
                runnable.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        processAllMessages();
    }

    public interface RunnableWithException {
        void run() throws Exception;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    public @interface RunWithLooper {
        boolean setAsMainLooper() default false;
    }

    private static void waitForMessage(TestLooperManager queueWrapper, Handler handler,
            Runnable execute) {
        for (int i = 0; i < 10; i++) {
            if (!queueWrapper.hasMessages(handler, null, execute)) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                }
            }
        }
        if (!queueWrapper.hasMessages(handler, null, execute)) {
            throw new RuntimeException("Message didn't queue...");
        }
    }

    private static final Map<Object, TestableLooper> sLoopers = new ArrayMap<>();

    public static TestableLooper get(Object test) {
        return sLoopers.get(test);
    }

    public static class LooperFrameworkMethod extends FrameworkMethod {
        private HandlerThread mHandlerThread;

        private final TestableLooper mTestableLooper;
        private final Looper mLooper;
        private final Handler mHandler;

        public LooperFrameworkMethod(FrameworkMethod base, boolean setAsMain, Object test) {
            super(base.getMethod());
            try {
                mLooper = setAsMain ? Looper.getMainLooper() : createLooper();
                mTestableLooper = new TestableLooper(mLooper, false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            sLoopers.put(test, mTestableLooper);
            mHandler = new Handler(mLooper);
        }

        public LooperFrameworkMethod(TestableLooper other, FrameworkMethod base) {
            super(base.getMethod());
            mLooper = other.mLooper;
            mTestableLooper = other;
            mHandler = new Handler(mLooper);
        }

        public static FrameworkMethod get(FrameworkMethod base, boolean setAsMain, Object test) {
            if (sLoopers.containsKey(test)) {
                return new LooperFrameworkMethod(sLoopers.get(test), base);
            }
            return new LooperFrameworkMethod(base, setAsMain, test);
        }

        @Override
        public Object invokeExplosively(Object target, Object... params) throws Throwable {
            if (Looper.myLooper() == mLooper) {
                // Already on the right thread from another statement, just execute then.
                return super.invokeExplosively(target, params);
            }
            boolean set = mTestableLooper.mQueueWrapper == null;
            if (set) {
                mTestableLooper.mQueueWrapper = InstrumentationRegistry.getInstrumentation()
                        .acquireLooperManager(mLooper);
            }
            try {
                Object[] ret = new Object[1];
                // Run the execution on the looper thread.
                Runnable execute = () -> {
                    try {
                        ret[0] = super.invokeExplosively(target, params);
                    } catch (Throwable throwable) {
                        throw new LooperException(throwable);
                    }
                };
                Message m = Message.obtain(mHandler, execute);

                // Dispatch our message.
                try {
                    mTestableLooper.mQueueWrapper.execute(m);
                } catch (LooperException e) {
                    throw e.getSource();
                } catch (RuntimeException re) {
                    // If the TestLooperManager has to post, it will wrap what it throws in a
                    // RuntimeException, make sure we grab the actual source.
                    if (re.getCause() instanceof LooperException) {
                        throw ((LooperException) re.getCause()).getSource();
                    } else {
                        throw re.getCause();
                    }
                } finally {
                    m.recycle();
                }
                return ret[0];
            } finally {
                if (set) {
                    mTestableLooper.mQueueWrapper.release();
                    mTestableLooper.mQueueWrapper = null;
                }
            }
        }

        private Looper createLooper() {
            // TODO: Find way to share these.
            mHandlerThread = new HandlerThread(TestableLooper.class.getSimpleName());
            mHandlerThread.start();
            return mHandlerThread.getLooper();
        }

        @Override
        protected void finalize() throws Throwable {
            super.finalize();
            if (mHandlerThread != null) {
                mHandlerThread.quit();
            }
        }

        private static class LooperException extends RuntimeException {
            private final Throwable mSource;

            public LooperException(Throwable t) {
                mSource = t;
            }

            public Throwable getSource() {
                return mSource;
            }
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
