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
import android.util.ArrayMap;

import androidx.test.InstrumentationRegistry;

import org.junit.runners.model.FrameworkMethod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * This is a wrapper around {@link TestLooperManager} to make it easier to manage
 * and provide an easy annotation for use with tests.
 *
 * @see TestableLooperTest TestableLooperTest for examples.
 */
public class TestableLooper {

    /**
     * Whether to hold onto the main thread through all tests in an attempt to
     * catch crashes.
     */
    public static final boolean HOLD_MAIN_THREAD = false;
    private static final Field MESSAGE_QUEUE_MESSAGES_FIELD;
    private static final Field MESSAGE_NEXT_FIELD;
    private static final Field MESSAGE_WHEN_FIELD;

    private Looper mLooper;
    private MessageQueue mQueue;
    private MessageHandler mMessageHandler;

    private Handler mHandler;
    private Runnable mEmptyMessage;
    private TestLooperManager mQueueWrapper;

    static {
        try {
            MESSAGE_QUEUE_MESSAGES_FIELD = MessageQueue.class.getDeclaredField("mMessages");
            MESSAGE_QUEUE_MESSAGES_FIELD.setAccessible(true);
            MESSAGE_NEXT_FIELD = Message.class.getDeclaredField("next");
            MESSAGE_NEXT_FIELD.setAccessible(true);
            MESSAGE_WHEN_FIELD = Message.class.getDeclaredField("when");
            MESSAGE_WHEN_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Failed to initialize TestableLooper", e);
        }
    }

    public TestableLooper(Looper l) throws Exception {
        this(acquireLooperManager(l), l);
    }

    private TestableLooper(TestLooperManager wrapper, Looper l) {
        mQueueWrapper = wrapper;
        setupQueue(l);
    }

    private TestableLooper(Looper looper, boolean b) {
        setupQueue(looper);
    }

    public Looper getLooper() {
        return mLooper;
    }

    private void setupQueue(Looper l) {
        mLooper = l;
        mQueue = mLooper.getQueue();
        mHandler = new Handler(mLooper);
    }

    /**
     * Must be called to release the looper when the test is complete, otherwise
     * the looper will not be available for any subsequent tests. This is
     * automatically handled for tests using {@link RunWithLooper}.
     */
    public void destroy() {
        mQueueWrapper.release();
        if (HOLD_MAIN_THREAD && mLooper == Looper.getMainLooper()) {
            TestableInstrumentation.releaseMain();
        }
    }

    /**
     * Sets a callback for all messages processed on this TestableLooper.
     *
     * @see {@link MessageHandler}
     */
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

    /**
     * Process messages in the queue until no more are found.
     */
    public void processAllMessages() {
        while (processQueuedMessages() != 0) ;
    }

    public void moveTimeForward(long milliSeconds) {
        try {
            Message msg = getMessageLinkedList();
            while (msg != null) {
                long updatedWhen = msg.getWhen() - milliSeconds;
                if (updatedWhen < 0) {
                    updatedWhen = 0;
                }
                MESSAGE_WHEN_FIELD.set(msg, updatedWhen);
                msg = (Message) MESSAGE_NEXT_FIELD.get(msg);
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Access failed in TestableLooper: set - Message.when", e);
        }
    }

    private Message getMessageLinkedList() {
        try {
            MessageQueue queue = mLooper.getQueue();
            return (Message) MESSAGE_QUEUE_MESSAGES_FIELD.get(queue);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(
                    "Access failed in TestableLooper: get - MessageQueue.mMessages",
                    e);
        }
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
                        mQueueWrapper.execute(result);
                        mQueueWrapper.recycle(result);
                    } else {
                        mQueueWrapper.recycle(result);
                        // Message handler indicated it doesn't want us to continue.
                        return false;
                    }
                } else {
                    mQueueWrapper.execute(result);
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

    /**
     * Annotation that tells the {@link AndroidTestingRunner} to create a TestableLooper and
     * run this test/class on that thread. The {@link TestableLooper} can be acquired using
     * {@link #get(Object)}.
     */
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

    private static TestLooperManager acquireLooperManager(Looper l) {
        if (HOLD_MAIN_THREAD && l == Looper.getMainLooper()) {
            TestableInstrumentation.acquireMain();
        }
        return InstrumentationRegistry.getInstrumentation().acquireLooperManager(l);
    }

    private static final Map<Object, TestableLooper> sLoopers = new ArrayMap<>();

    /**
     * For use with {@link RunWithLooper}, used to get the TestableLooper that was
     * automatically created for this test.
     */
    public static TestableLooper get(Object test) {
        return sLoopers.get(test);
    }

    public static void remove(Object test) {
        sLoopers.remove(test);
    }

    static class LooperFrameworkMethod extends FrameworkMethod {
        private HandlerThread mHandlerThread;

        private final TestableLooper mTestableLooper;
        private final Looper mLooper;
        private final Handler mHandler;

        public LooperFrameworkMethod(FrameworkMethod base, boolean setAsMain, Object test) {
            super(base.getMethod());
            try {
                mLooper = setAsMain ? Looper.getMainLooper() : createLooper();
                mTestableLooper = new TestableLooper(mLooper, false);
                if (!setAsMain) {
                    mTestableLooper.getLooper().getThread().setName(test.getClass().getName());
                }
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
            mHandler = Handler.createAsync(mLooper);
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
                mTestableLooper.mQueueWrapper = acquireLooperManager(mLooper);
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
                    if (HOLD_MAIN_THREAD && mLooper == Looper.getMainLooper()) {
                        TestableInstrumentation.releaseMain();
                    }
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

    /**
     * Callback to control the execution of messages on the looper, when set with
     * {@link #setMessageHandler(MessageHandler)} then {@link #onMessageHandled(Message)}
     * will get called back for every message processed on the {@link TestableLooper}.
     */
    public interface MessageHandler {
        /**
         * Return true to have the message executed and delivered to target.
         * Return false to not execute the message and stop executing messages.
         */
        boolean onMessageHandled(Message m);
    }
}
