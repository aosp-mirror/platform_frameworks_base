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

import android.annotation.NonNull;
import android.annotation.Nullable;
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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

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
        mQueueWrapper = Objects.requireNonNull(wrapper);
        setupQueue(l);
    }

    private TestableLooper(Looper looper, boolean b) {
        setupQueue(looper);
    }

    /**
     * Wrap the given runnable so that it will run blocking on the Looper that will be set up for
     * the given test.
     * <p>
     * This method is required to support any TestRule which needs to run setup and/or teardown code
     * on the TestableLooper. Whether using {@link AndroidTestingRunner} or
     * {@link TestWithLooperRule}, the TestRule's Statement evaluates on the test instrumentation
     * thread, rather than the TestableLooper thread, so access to the TestableLooper is required.
     * However, {@link #get(Object)} will return {@code null} both before and after the inner
     * statement is evaluated:
     * <ul>
     * <li>Before the test {@link #get} returns {@code null} because while the TestableLooperHolder
     * is accessible in sLoopers, it has not been initialized with an actual TestableLooper yet.
     * This method's use of the internal LooperFrameworkMethod ensures that all setup and teardown
     * of the TestableLooper happen as it would for all other wrapped code blocks.
     * <li>After the test {@link #get} can return {@code null} because many tests call
     * {@link #remove} in the teardown method. The fact that this method returns a runnable allows
     * it to be called before the test (when the TestableLooperHolder is still in sLoopers), and
     * then executed as teardown after the test.
     * </ul>
     *
     * @param test     the test instance (just like passed to {@link #get(Object)})
     * @param runnable the operation that should eventually be run on the TestableLooper
     * @return a runnable that will block the thread on which it is called until the given runnable
     *          is finished.  Will be {@code null} if there is no looper for the given test.
     * @hide
     */
    @Nullable
    public static RunnableWithException wrapWithRunBlocking(
            Object test, @NonNull RunnableWithException runnable) {
        TestableLooperHolder looperHolder = sLoopers.get(test);
        if (looperHolder == null) {
            return null;
        }
        try {
            FrameworkMethod base = new FrameworkMethod(runnable.getClass().getMethod("run"));
            LooperFrameworkMethod wrapped = new LooperFrameworkMethod(base, looperHolder);
            return () -> {
                try {
                    wrapped.invokeExplosively(runnable);
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            };
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
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
        return processMessagesInternal(num, null);
    }

    private int processMessagesInternal(int num, Runnable barrierRunnable) {
        for (int i = 0; i < num; i++) {
            if (!processSingleMessage(barrierRunnable)) {
                return i + 1;
            }
        }
        return num;
    }

    /**
     * Process up to a certain number of messages, not blocking if the queue has less messages than
     * that
     * @param num the maximum number of messages to process
     * @return the number of messages processed. This will be at most {@code num}.
     */

    public int processMessagesNonBlocking(int num) {
        final AtomicBoolean reachedBarrier = new AtomicBoolean(false);
        Runnable barrierRunnable = () -> {
            reachedBarrier.set(true);
        };
        mHandler.post(barrierRunnable);
        waitForMessage(mQueueWrapper, mHandler, barrierRunnable);
        try {
            return processMessagesInternal(num, barrierRunnable) + (reachedBarrier.get() ? -1 : 0);
        } finally {
            mHandler.removeCallbacks(barrierRunnable);
        }
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
        Runnable barrierRunnable = () -> { };
        mHandler.post(barrierRunnable);
        waitForMessage(mQueueWrapper, mHandler, barrierRunnable);
        while (processSingleMessage(barrierRunnable)) count++;
        return count;
    }

    private boolean processSingleMessage(Runnable barrierRunnable) {
        try {
            Message result = mQueueWrapper.next();
            if (result != null) {
                // This is a break message.
                if (result.getCallback() == barrierRunnable) {
                    mQueueWrapper.execute(result);
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

    private static final Map<Object, TestableLooperHolder> sLoopers = new ArrayMap<>();

    /**
     * For use with {@link RunWithLooper}, used to get the TestableLooper that was
     * automatically created for this test.
     */
    public static TestableLooper get(Object test) {
        final TestableLooperHolder looperHolder = sLoopers.get(test);
        return (looperHolder != null) ? looperHolder.mTestableLooper : null;
    }

    public static void remove(Object test) {
        sLoopers.remove(test);
    }

    /**
     * Holder object that contains {@link TestableLooper} so that its initialization can be
     * deferred until a test case is actually run, instead of forcing it to be created at
     * {@link FrameworkMethod} construction time.
     *
     * This deferral is important because some test environments may configure
     * {@link Looper#getMainLooper()} as part of a {@code Rule} instead of assuming it's globally
     * initialized and unconditionally available.
     */
    private static class TestableLooperHolder {
        private final boolean mSetAsMain;
        private final Object mTest;

        private TestableLooper mTestableLooper;
        private Looper mLooper;
        private Handler mHandler;
        private HandlerThread mHandlerThread;

        public TestableLooperHolder(boolean setAsMain, Object test) {
            mSetAsMain = setAsMain;
            mTest = test;
        }

        public void ensureInit() {
            if (mLooper != null) return;
            try {
                mLooper = mSetAsMain ? Looper.getMainLooper() : createLooper();
                mTestableLooper = new TestableLooper(mLooper, false);
                if (!mSetAsMain) {
                    mTestableLooper.getLooper().getThread().setName(mTest.getClass().getName());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            mHandler = new Handler(mLooper);
        }

        private Looper createLooper() {
            // TODO: Find way to share these.
            mHandlerThread = new HandlerThread(TestableLooper.class.getSimpleName());
            mHandlerThread.start();
            return mHandlerThread.getLooper();
        }
    }

    static class LooperFrameworkMethod extends FrameworkMethod {
        private TestableLooperHolder mLooperHolder;

        public LooperFrameworkMethod(FrameworkMethod base, TestableLooperHolder looperHolder) {
            super(base.getMethod());
            mLooperHolder = looperHolder;
        }

        public static FrameworkMethod get(FrameworkMethod base, boolean setAsMain, Object test) {
            TestableLooperHolder looperHolder = sLoopers.get(test);
            if (looperHolder == null) {
                looperHolder = new TestableLooperHolder(setAsMain, test);
                sLoopers.put(test, looperHolder);
            }
            return new LooperFrameworkMethod(base, looperHolder);
        }

        @Override
        public Object invokeExplosively(Object target, Object... params) throws Throwable {
            mLooperHolder.ensureInit();
            if (Looper.myLooper() == mLooperHolder.mLooper) {
                // Already on the right thread from another statement, just execute then.
                return super.invokeExplosively(target, params);
            }
            boolean set = mLooperHolder.mTestableLooper.mQueueWrapper == null;
            if (set) {
                mLooperHolder.mTestableLooper.mQueueWrapper = acquireLooperManager(
                        mLooperHolder.mLooper);
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
                Message m = Message.obtain(mLooperHolder.mHandler, execute);

                // Dispatch our message.
                try {
                    mLooperHolder.mTestableLooper.mQueueWrapper.execute(m);
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
                    mLooperHolder.mTestableLooper.mQueueWrapper.release();
                    mLooperHolder.mTestableLooper.mQueueWrapper = null;
                    if (HOLD_MAIN_THREAD && mLooperHolder.mLooper == Looper.getMainLooper()) {
                        TestableInstrumentation.releaseMain();
                    }
                }
            }
        }

        @Override
        protected void finalize() throws Throwable {
            super.finalize();
            if (mLooperHolder.mHandlerThread != null) {
                mLooperHolder.mHandlerThread.quit();
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
