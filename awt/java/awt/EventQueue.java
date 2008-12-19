/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * @author Michael Danilov, Pavel Dolgov
 * @version $Revision$
 */

package java.awt;

import java.awt.event.InvocationEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.EmptyStackException;

/**
 * The EventQueue class manages events. It is a platform-independent class that
 * queues events both from the underlying peer classes and from trusted
 * application classes.
 * 
 * @since Android 1.0
 */
public class EventQueue {

    /**
     * The core ref.
     */
    private final EventQueueCoreAtomicReference coreRef = new EventQueueCoreAtomicReference();

    /**
     * The Class EventQueueCoreAtomicReference.
     */
    private static final class EventQueueCoreAtomicReference {

        /**
         * The core.
         */
        private EventQueueCore core;

        /* synchronized */
        /**
         * Gets the.
         * 
         * @return the event queue core.
         */
        EventQueueCore get() {
            return core;
        }

        /* synchronized */
        /**
         * Sets the.
         * 
         * @param newCore
         *            the new core.
         */
        void set(EventQueueCore newCore) {
            core = newCore;
        }
    }

    /**
     * Returns true if the calling thread is the current AWT EventQueue's
     * dispatch thread.
     * 
     * @return true, if the calling thread is the current AWT EventQueue's
     *         dispatch thread; false otherwise.
     */
    public static boolean isDispatchThread() {
        return Thread.currentThread() instanceof EventDispatchThread;
    }

    /**
     * Posts an InvocationEvent which executes the run() method on a Runnable
     * when dispatched by the AWT event dispatcher thread.
     * 
     * @param runnable
     *            the Runnable whose run method should be executed synchronously
     *            on the EventQueue.
     */
    public static void invokeLater(Runnable runnable) {
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        InvocationEvent event = new InvocationEvent(toolkit, runnable);
        toolkit.getSystemEventQueueImpl().postEvent(event);
    }

    /**
     * Posts an InvocationEvent which executes the run() method on a Runnable
     * when dispatched by the AWT event dispatcher thread and the notifyAll
     * method is called on it immediately after run returns.
     * 
     * @param runnable
     *            the Runnable whose run method should be executed synchronously
     *            on the EventQueue.
     * @throws InterruptedException
     *             if another thread has interrupted this thread.
     * @throws InvocationTargetException
     *             if an error occurred while running the runnable.
     */
    public static void invokeAndWait(Runnable runnable) throws InterruptedException,
            InvocationTargetException {

        if (isDispatchThread()) {
            throw new Error();
        }

        final Toolkit toolkit = Toolkit.getDefaultToolkit();
        final Object notifier = new Object(); // $NON-LOCK-1$
        InvocationEvent event = new InvocationEvent(toolkit, runnable, notifier, true);

        synchronized (notifier) {
            toolkit.getSystemEventQueueImpl().postEvent(event);
            notifier.wait();
        }

        Exception exception = event.getException();

        if (exception != null) {
            throw new InvocationTargetException(exception);
        }
    }

    /**
     * Gets the system event queue.
     * 
     * @return the system event queue.
     */
    private static EventQueue getSystemEventQueue() {
        Thread th = Thread.currentThread();
        if (th instanceof EventDispatchThread) {
            return ((EventDispatchThread)th).toolkit.getSystemEventQueueImpl();
        }
        return null;
    }

    /**
     * Gets the most recent event's timestamp. This event was dispatched from
     * the EventQueue associated with the calling thread.
     * 
     * @return the timestamp of the last Event to be dispatched, or
     *         System.currentTimeMillis() if this method is invoked from a
     *         thread other than an event-dispatching thread.
     */
    public static long getMostRecentEventTime() {
        EventQueue eq = getSystemEventQueue();
        return (eq != null) ? eq.getMostRecentEventTimeImpl() : System.currentTimeMillis();
    }

    /**
     * Gets the most recent event time impl.
     * 
     * @return the most recent event time impl.
     */
    private long getMostRecentEventTimeImpl() {
        return getCore().getMostRecentEventTime();
    }

    /**
     * Returns the the currently dispatched event by the EventQueue associated
     * with the calling thread.
     * 
     * @return the currently dispatched event or null if this method is invoked
     *         from a thread other than an event-dispatching thread.
     */
    public static AWTEvent getCurrentEvent() {
        EventQueue eq = getSystemEventQueue();
        return (eq != null) ? eq.getCurrentEventImpl() : null;
    }

    /**
     * Gets the current event impl.
     * 
     * @return the current event impl.
     */
    private AWTEvent getCurrentEventImpl() {
        return getCore().getCurrentEvent();
    }

    /**
     * Instantiates a new event queue.
     */
    public EventQueue() {
        setCore(new EventQueueCore(this));
    }

    /**
     * Instantiates a new event queue.
     * 
     * @param t
     *            the t.
     */
    EventQueue(Toolkit t) {
        setCore(new EventQueueCore(this, t));
    }

    /**
     * Posts a event to the EventQueue.
     * 
     * @param event
     *            AWTEvent.
     */
    public void postEvent(AWTEvent event) {
        event.isPosted = true;
        getCore().postEvent(event);
    }

    /**
     * Returns an event from the EventQueue and removes it from this queue.
     * 
     * @return the next AWTEvent.
     * @throws InterruptedException
     *             is thrown if another thread interrupts this thread.
     */
    public AWTEvent getNextEvent() throws InterruptedException {
        return getCore().getNextEvent();
    }

    /**
     * Gets the next event no wait.
     * 
     * @return the next event no wait.
     */
    AWTEvent getNextEventNoWait() {
        return getCore().getNextEventNoWait();
    }

    /**
     * Returns the first event of the EventQueue (without removing it from the
     * queue).
     * 
     * @return the the first AWT event of the EventQueue.
     */
    public AWTEvent peekEvent() {
        return getCore().peekEvent();
    }

    /**
     * Returns the first event of the EventQueue with the specified ID (without
     * removing it from the queue).
     * 
     * @param id
     *            the type ID of event.
     * @return the first event of the EventQueue with the specified ID.
     */
    public AWTEvent peekEvent(int id) {
        return getCore().peekEvent(id);
    }

    /**
     * Replaces the existing EventQueue with the specified EventQueue. Any
     * pending events are transferred to the new EventQueue.
     * 
     * @param newEventQueue
     *            the new event queue.
     */
    public void push(EventQueue newEventQueue) {
        getCore().push(newEventQueue);
    }

    /**
     * Stops dispatching events using this EventQueue. Any pending events are
     * transferred to the previous EventQueue.
     * 
     * @throws EmptyStackException
     *             is thrown if no previous push was made on this EventQueue.
     */
    protected void pop() throws EmptyStackException {
        getCore().pop();
    }

    /**
     * Dispatches the specified event.
     * 
     * @param event
     *            the AWTEvent.
     */
    protected void dispatchEvent(AWTEvent event) {
        getCore().dispatchEventImpl(event);
    }

    /**
     * Checks if the queue is empty.
     * 
     * @return true, if is empty.
     */
    boolean isEmpty() {
        return getCore().isEmpty();
    }

    /**
     * Gets the core.
     * 
     * @return the core.
     */
    EventQueueCore getCore() {
        return coreRef.get();
    }

    /**
     * Sets the core.
     * 
     * @param newCore
     *            the new core.
     */
    void setCore(EventQueueCore newCore) {
        coreRef.set((newCore != null) ? newCore : new EventQueueCore(this));
    }
}
