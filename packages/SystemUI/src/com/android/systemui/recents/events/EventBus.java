/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents.events;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import com.android.systemui.recents.misc.ReferenceCountedTrigger;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * Represents a subscriber, which implements various event bus handler methods.
 */
class Subscriber {
    private WeakReference<Object> mSubscriber;

    long registrationTime;

    Subscriber(Object subscriber, long registrationTime) {
        mSubscriber = new WeakReference<>(subscriber);
        this.registrationTime = registrationTime;
    }

    public String toString(int priority) {
        Object sub = mSubscriber.get();
        String id = Integer.toHexString(System.identityHashCode(sub));
        return sub.getClass().getSimpleName() + " [0x" + id + ", P" + priority + "]";
    }

    public Object getReference() {
        return mSubscriber.get();
    }
}

/**
 * Represents an event handler with a priority.
 */
class EventHandler {
    int priority;
    Subscriber subscriber;
    EventHandlerMethod method;

    EventHandler(Subscriber subscriber, EventHandlerMethod method, int priority) {
        this.subscriber = subscriber;
        this.method = method;
        this.priority = priority;
    }

    @Override
    public String toString() {
        return subscriber.toString(priority) + " " + method.toString();
    }
}

/**
 * Represents the low level method handling a particular event.
 */
class EventHandlerMethod {
    private Method mMethod;
    Class<? extends EventBus.Event> eventType;

    EventHandlerMethod(Method method, Class<? extends EventBus.Event> eventType) {
        mMethod = method;
        mMethod.setAccessible(true);
        this.eventType = eventType;
    }

    public void invoke(Object target, EventBus.Event event)
            throws InvocationTargetException, IllegalAccessException {
        mMethod.invoke(target, event);
    }

    @Override
    public String toString() {
        return mMethod.getName() + "(" + eventType.getSimpleName() + ")";
    }
}

/**
 * A simple in-process event bus.  It is simple because we can make assumptions about the state of
 * SystemUI and Recent's lifecycle.
 *
 * <p>
 * Currently, there is a single EventBus that handles {@link EventBus.Event}s for each subscriber
 * on the main application thread.  Publishers can send() events to synchronously call subscribers
 * of that event, or post() events to be processed in the next run of the {@link Looper}.
 *
 * <p>
 * Subscribers must be registered with a particular EventBus before they will receive events, and
 * handler methods must match a specific signature.
 *
 * <p>
 * Event method signature:<ul>
 * <li>Methods must be public final
 * <li>Methods must return void
 * <li>Methods must be called "onBusEvent"
 * <li>Methods must take one parameter, of class type deriving from {@link EventBus.Event}
 * </ul>
 *
 * </p>
 * Each subscriber can be registered with a given priority (default 1), and events will be dispatch
 * in decreasing order of priority.  For subscribers with the same priority, events will be
 * dispatched by latest registration time to earliest.
 *
 * <p>
 * Caveats:<ul>
 * <li>The EventBus keeps a {@link WeakReference} to the publisher to prevent memory leaks, so
 * there must be another strong reference to the publisher for it to not get garbage-collected and
 * continue receiving events.
 * <li>Because the event handlers are called back using reflection, the EventBus is not intended
 * for use in tight, performance criticial loops.  For most user input/system callback events, this
 * is generally of low enough frequency to use the EventBus.
 * <li>Because the event handlers are called back using reflection, there will often be no
 * references to them from actual code.  The proguard configuration will be need to be updated to
 * keep these extra methods:
 *
 * -keepclassmembers class ** {
 * public void onBusEvent(**);
 * public void onInterprocessBusEvent(**);
 * }
 * -keepclassmembers class ** extends **.EventBus$InterprocessEvent {
 * public <init>(android.os.Bundle);
 * }
 *
 * <li>Subscriber registration can be expensive depending on the subscriber's {@link Class}.  This
 * is only done once per class type, but if possible, it is best to pre-register an instance of
 * that class beforehand or when idle.
 * <li>Each event should be sent once.  Events may hold internal information about the current
 * dispatch, or may be queued to be dispatched on another thread (if posted from a non-main thread),
 * so it may be unsafe to edit, change, or re-send the event again.
 * <li>Events should follow a pattern of public-final POD (plain old data) objects, where they are
 * initialized by the constructor and read by each subscriber of that event.  Subscribers should
 * never alter events as they are processed, and this enforces that pattern.
 * </ul>
 *
 * <p>
 * Future optimizations:
 * <li>throw exception/log when a subscriber loses the reference
 * <li>trace cost per registration & invocation
 * <li>trace cross-process invocation
 * <li>register(subscriber, Class&lt;?&gt;...) -- pass in exact class types you want registered
 * <li>setSubscriberEventHandlerPriority(subscriber, Class<Event>, priority)
 * <li>allow subscribers to implement interface, ie. EventBus.Subscriber, which lets then test a
 * message before invocation (ie. check if task id == this task id)
 * <li>add postOnce() which automatically debounces
 * <li>add postDelayed() which delays / postDelayedOnce() which delays and bounces
 * <li>consolidate register() and registerInterprocess()
 * <li>sendForResult&lt;ReturnType&gt;(Event) to send and get a result, but who will send the
 * result?
 * </p>
 */
public class EventBus {

    private static final String TAG = "EventBus";
    private static final boolean DEBUG_TRACE_ALL = false;

    /**
     * An event super class that allows us to track internal event state across subscriber
     * invocations.
     *
     * Events should not be edited by subscribers.
     */
    public static class Event implements Cloneable {
        // Indicates that this event's dispatch should be traced and logged to logcat
        boolean trace;
        // Indicates that this event must be posted on the EventBus's looper thread before invocation
        boolean requiresPost;
        // Not currently exposed, allows a subscriber to cancel further dispatch of this event
        boolean cancelled;

        // Only accessible from derived events
        protected Event() {}

        /**
         * Called by the EventBus prior to dispatching this event to any subscriber of this event.
         */
        void onPreDispatch() {
            // Do nothing
        }

        /**
         * Called by the EventBus after dispatching this event to every subscriber of this event.
         */
        void onPostDispatch() {
            // Do nothing
        }

        @Override
        protected Object clone() throws CloneNotSupportedException {
            Event evt = (Event) super.clone();
            // When cloning an event, reset the cancelled-dispatch state
            evt.cancelled = false;
            return evt;
        }
    }

    /**
     * An event that represents an animated state change, which allows subscribers to coordinate
     * callbacks which happen after the animation has taken place.
     *
     * Internally, it is guaranteed that increment() and decrement() will be called before and the
     * after the event is dispatched.
     */
    public static class AnimatedEvent extends Event {

        private final ReferenceCountedTrigger mTrigger = new ReferenceCountedTrigger();

        // Only accessible from derived events
        protected AnimatedEvent() {}

        /**
         * Returns the reference counted trigger that coordinates the animations for this event.
         */
        public ReferenceCountedTrigger getAnimationTrigger() {
            return mTrigger;
        }

        /**
         * Adds a callback that is guaranteed to be called after the state has changed regardless of
         * whether an actual animation took place.
         */
        public void addPostAnimationCallback(Runnable r) {
            mTrigger.addLastDecrementRunnable(r);
        }

        @Override
        void onPreDispatch() {
            mTrigger.increment();
        }

        @Override
        void onPostDispatch() {
            mTrigger.decrement();
        }

        @Override
        protected Object clone() throws CloneNotSupportedException {
            throw new CloneNotSupportedException();
        }
    }

    /**
     * An event that can be reusable, only used for situations where we want to reduce memory
     * allocations when events are sent frequently (ie. on scroll).
     */
    public static class ReusableEvent extends Event {

        private int mDispatchCount;

        protected ReusableEvent() {}

        @Override
        void onPostDispatch() {
            super.onPostDispatch();
            mDispatchCount++;
        }

        @Override
        protected Object clone() throws CloneNotSupportedException {
            throw new CloneNotSupportedException();
        }
    }

    /**
     * Proguard must also know, and keep, all methods matching this signature.
     *
     * -keepclassmembers class ** {
     *     public void onBusEvent(**);
     *     public void onInterprocessBusEvent(**);
     * }
     */
    private static final String METHOD_PREFIX = "onBusEvent";

    // The default priority of all subscribers
    private static final int DEFAULT_SUBSCRIBER_PRIORITY = 1;

    // Orders the handlers by priority and registration time
    private static final Comparator<EventHandler> EVENT_HANDLER_COMPARATOR = new Comparator<EventHandler>() {
        @Override
        public int compare(EventHandler h1, EventHandler h2) {
            // Rank the handlers by priority descending, followed by registration time descending.
            // aka. the later registered
            if (h1.priority != h2.priority) {
                return h2.priority - h1.priority;
            } else {
                return Long.compare(h2.subscriber.registrationTime, h1.subscriber.registrationTime);
            }
        }
    };

    // Used for initializing the default bus
    private static final Object sLock = new Object();
    private static volatile EventBus sDefaultBus;

    // The handler to post all events
    private Handler mHandler;

    /**
     * Map from event class -> event handler list.  Keeps track of the actual mapping from event
     * to subscriber method.
     */
    private HashMap<Class<? extends Event>, ArrayList<EventHandler>> mEventTypeMap = new HashMap<>();

    /**
     * Map from subscriber class -> event handler method lists.  Used to determine upon registration
     * of a new subscriber whether we need to read all the subscriber's methods again using
     * reflection or whether we can just add the subscriber to the event type map.
     */
    private HashMap<Class<? extends Object>, ArrayList<EventHandlerMethod>> mSubscriberTypeMap = new HashMap<>();

    /**
     * Set of all currently registered subscribers
     */
    private ArrayList<Subscriber> mSubscribers = new ArrayList<>();

    // For tracing
    private int mCallCount;
    private long mCallDurationMicros;

    /**
     * Private constructor to create an event bus for a given looper.
     */
    private EventBus(Looper looper) {
        mHandler = new Handler(looper);
    }

    /**
     * @return the default event bus for the application's main thread.
     */
    public static EventBus getDefault() {
        if (sDefaultBus == null)
        synchronized (sLock) {
            if (sDefaultBus == null) {
                if (DEBUG_TRACE_ALL) {
                    logWithPid("New EventBus");
                }
                sDefaultBus = new EventBus(Looper.getMainLooper());
            }
        }
        return sDefaultBus;
    }

    /**
     * Registers a subscriber to receive events with the default priority.
     *
     * @param subscriber the subscriber to handle events.  If this is the first instance of the
     *                   subscriber's class type that has been registered, the class's methods will
     *                   be scanned for appropriate event handler methods.
     */
    public void register(Object subscriber) {
        registerSubscriber(subscriber, DEFAULT_SUBSCRIBER_PRIORITY);
    }

    /**
     * Registers a subscriber to receive events with the given priority.
     *
     * @param subscriber the subscriber to handle events.  If this is the first instance of the
     *                   subscriber's class type that has been registered, the class's methods will
     *                   be scanned for appropriate event handler methods.
     * @param priority the priority that this subscriber will receive events relative to other
     *                 subscribers
     */
    public void register(Object subscriber, int priority) {
        registerSubscriber(subscriber, priority);
    }

    /**
     * Remove all EventHandlers pointing to the specified subscriber.  This does not remove the
     * mapping of subscriber type to event handler method, in case new instances of this subscriber
     * are registered.
     */
    public void unregister(Object subscriber) {
        if (DEBUG_TRACE_ALL) {
            logWithPid("unregister()");
        }

        // Fail immediately if we are being called from the non-main thread
        long callingThreadId = Thread.currentThread().getId();
        if (callingThreadId != mHandler.getLooper().getThread().getId()) {
            throw new RuntimeException("Can not unregister() a subscriber from a non-main thread.");
        }

        // Return early if this is not a registered subscriber
        if (!findRegisteredSubscriber(subscriber, true /* removeFoundSubscriber */)) {
            return;
        }

        Class<?> subscriberType = subscriber.getClass();
        ArrayList<EventHandlerMethod> subscriberMethods = mSubscriberTypeMap.get(subscriberType);
        if (subscriberMethods != null) {
            // For each of the event handlers the subscriber handles, remove all references of that
            // handler
            for (EventHandlerMethod method : subscriberMethods) {
                ArrayList<EventHandler> eventHandlers = mEventTypeMap.get(method.eventType);
                for (int i = eventHandlers.size() - 1; i >= 0; i--) {
                    if (eventHandlers.get(i).subscriber.getReference() == subscriber) {
                        eventHandlers.remove(i);
                    }
                }
            }
        }
    }

    /**
     * Sends an event to the subscribers of the given event type immediately.  This can only be
     * called from the same thread as the EventBus's looper thread (for the default EventBus, this
     * is the main application thread).
     */
    public void send(Event event) {
        // Fail immediately if we are being called from the non-main thread
        long callingThreadId = Thread.currentThread().getId();
        if (callingThreadId != mHandler.getLooper().getThread().getId()) {
            throw new RuntimeException("Can not send() a message from a non-main thread.");
        }

        if (DEBUG_TRACE_ALL) {
            logWithPid("send(" + event.getClass().getSimpleName() + ")");
        }

        // Reset the event's cancelled state
        event.requiresPost = false;
        event.cancelled = false;
        queueEvent(event);
    }

    /**
     * Post a message to the subscribers of the given event type.  The messages will be posted on
     * the EventBus's looper thread (for the default EventBus, this is the main application thread).
     */
    public void post(Event event) {
        if (DEBUG_TRACE_ALL) {
            logWithPid("post(" + event.getClass().getSimpleName() + ")");
        }

        // Reset the event's cancelled state
        event.requiresPost = true;
        event.cancelled = false;
        queueEvent(event);
    }

    /**
     * If this method is called from the main thread, it will be handled directly. If this method
     * is not called from the main thread, it will be posted onto the main thread.
     */
    public void sendOntoMainThread(Event event) {
        long callingThreadId = Thread.currentThread().getId();
        if (callingThreadId != mHandler.getLooper().getThread().getId()) {
            post(event);
        } else {
            send(event);
        }
    }

    /**
     * @return a dump of the current state of the EventBus
     */
    public void dump(String prefix, PrintWriter writer) {
        writer.println(dumpInternal(prefix));
    }

    public String dumpInternal(String prefix) {
        String innerPrefix = prefix + "  ";
        String innerInnerPrefix = innerPrefix + "  ";
        StringBuilder output = new StringBuilder();
        output.append(prefix);
        output.append("Registered class types:");
        output.append("\n");
        ArrayList<Class<?>> subsciberTypes = new ArrayList<>(mSubscriberTypeMap.keySet());
        Collections.sort(subsciberTypes, new Comparator<Class<?>>() {
            @Override
            public int compare(Class<?> o1, Class<?> o2) {
                return o1.getSimpleName().compareTo(o2.getSimpleName());
            }
        });
        for (int i = 0; i < subsciberTypes.size(); i++) {
            Class<?> clz = subsciberTypes.get(i);
            output.append(innerPrefix);
            output.append(clz.getSimpleName());
            output.append("\n");
        }
        output.append(prefix);
        output.append("Event map:");
        output.append("\n");
        ArrayList<Class<?>> classes = new ArrayList<>(mEventTypeMap.keySet());
        Collections.sort(classes, new Comparator<Class<?>>() {
            @Override
            public int compare(Class<?> o1, Class<?> o2) {
                return o1.getSimpleName().compareTo(o2.getSimpleName());
            }
        });
        for (int i = 0; i < classes.size(); i++) {
            Class<?> clz = classes.get(i);
            output.append(innerPrefix);
            output.append(clz.getSimpleName());
            output.append(" -> ");
            output.append("\n");
            ArrayList<EventHandler> handlers = mEventTypeMap.get(clz);
            for (EventHandler handler : handlers) {
                Object subscriber = handler.subscriber.getReference();
                if (subscriber != null) {
                    String id = Integer.toHexString(System.identityHashCode(subscriber));
                    output.append(innerInnerPrefix);
                    output.append(subscriber.getClass().getSimpleName());
                    output.append(" [0x" + id + ", #" + handler.priority + "]");
                    output.append("\n");
                }
            }
        }
        return output.toString();
    }

    /**
     * Registers a new subscriber.
     */
    private void registerSubscriber(Object subscriber, int priority) {
        // Fail immediately if we are being called from the non-main thread
        long callingThreadId = Thread.currentThread().getId();
        if (callingThreadId != mHandler.getLooper().getThread().getId()) {
            throw new RuntimeException("Can not register() a subscriber from a non-main thread.");
        }

        // Return immediately if this exact subscriber is already registered
        if (findRegisteredSubscriber(subscriber, false /* removeFoundSubscriber */)) {
            return;
        }

        long t1 = 0;
        if (DEBUG_TRACE_ALL) {
            t1 = SystemClock.currentTimeMicro();
            logWithPid("registerSubscriber(" + subscriber.getClass().getSimpleName() + ")");
        }
        Subscriber sub = new Subscriber(subscriber, SystemClock.uptimeMillis());
        Class<?> subscriberType = subscriber.getClass();
        ArrayList<EventHandlerMethod> subscriberMethods = mSubscriberTypeMap.get(subscriberType);
        if (subscriberMethods != null) {
            if (DEBUG_TRACE_ALL) {
                logWithPid("Subscriber class type already registered");
            }

            // If we've parsed this subscriber type before, just add to the set for all the known
            // events
            for (EventHandlerMethod method : subscriberMethods) {
                ArrayList<EventHandler> eventTypeHandlers = mEventTypeMap.get(method.eventType);
                eventTypeHandlers.add(new EventHandler(sub, method, priority));
                sortEventHandlersByPriority(eventTypeHandlers);
            }
            mSubscribers.add(sub);
            return;
        } else {
            if (DEBUG_TRACE_ALL) {
                logWithPid("Subscriber class type requires registration");
            }

            // If we are parsing this type from scratch, ensure we add it to the subscriber type
            // map, and pull out he handler methods below
            subscriberMethods = new ArrayList<>();
            mSubscriberTypeMap.put(subscriberType, subscriberMethods);
            mSubscribers.add(sub);
        }

        // Find all the valid event bus handler methods of the subscriber
        Method[] methods = subscriberType.getDeclaredMethods();
        for (Method m : methods) {
            Class<?>[] parameterTypes = m.getParameterTypes();
            if (isValidEventBusHandlerMethod(m, parameterTypes)) {
                Class<? extends Event> eventType = (Class<? extends Event>) parameterTypes[0];
                ArrayList<EventHandler> eventTypeHandlers = mEventTypeMap.get(eventType);
                if (eventTypeHandlers == null) {
                    eventTypeHandlers = new ArrayList<>();
                    mEventTypeMap.put(eventType, eventTypeHandlers);
                }
                EventHandlerMethod method = new EventHandlerMethod(m, eventType);
                EventHandler handler = new EventHandler(sub, method, priority);
                eventTypeHandlers.add(handler);
                subscriberMethods.add(method);
                sortEventHandlersByPriority(eventTypeHandlers);

                if (DEBUG_TRACE_ALL) {
                    logWithPid("  * Method: " + m.getName() +
                            " event: " + parameterTypes[0].getSimpleName());
                }
            }
        }
        if (DEBUG_TRACE_ALL) {
            logWithPid("Registered " + subscriber.getClass().getSimpleName() + " in " +
                    (SystemClock.currentTimeMicro() - t1) + " microseconds");
        }
    }

    /**
     * Adds a new message.
     */
    private void queueEvent(final Event event) {
        ArrayList<EventHandler> eventHandlers = mEventTypeMap.get(event.getClass());
        if (eventHandlers == null) {
            // This is just an optimization to return early if there are no handlers. However, we
            // should still ensure that we call pre/post dispatch callbacks so that AnimatedEvents
            // are still cleaned up correctly if a listener has not been registered to handle them
            event.onPreDispatch();
            event.onPostDispatch();
            return;
        }

        // Prepare this event
        boolean hasPostedEvent = false;
        event.onPreDispatch();

        // We need to clone the list in case a subscriber unregisters itself during traversal
        // TODO: Investigate whether we can skip the object creation here
        eventHandlers = (ArrayList<EventHandler>) eventHandlers.clone();
        int eventHandlerCount = eventHandlers.size();
        for (int i = 0; i < eventHandlerCount; i++) {
            final EventHandler eventHandler = eventHandlers.get(i);
            if (eventHandler.subscriber.getReference() != null) {
                if (event.requiresPost) {
                    mHandler.post(() -> processEvent(eventHandler, event));
                    hasPostedEvent = true;
                } else {
                    processEvent(eventHandler, event);
                }
            }
        }

        // Clean up after this event, deferring until all subscribers have been called
        if (hasPostedEvent) {
            mHandler.post(event::onPostDispatch);
        } else {
            event.onPostDispatch();
        }
    }

    /**
     * Processes and dispatches the given event to the given event handler, on the thread of whoever
     * calls this method.
     */
    private void processEvent(final EventHandler eventHandler, final Event event) {
        // Skip if the event was already cancelled
        if (event.cancelled) {
            if (event.trace || DEBUG_TRACE_ALL) {
                logWithPid("Event dispatch cancelled");
            }
            return;
        }

        try {
            if (event.trace || DEBUG_TRACE_ALL) {
                logWithPid(" -> " + eventHandler.toString());
            }
            Object sub = eventHandler.subscriber.getReference();
            if (sub != null) {
                long t1 = 0;
                if (DEBUG_TRACE_ALL) {
                    t1 = SystemClock.currentTimeMicro();
                }
                eventHandler.method.invoke(sub, event);
                if (DEBUG_TRACE_ALL) {
                    long duration = (SystemClock.currentTimeMicro() - t1);
                    mCallDurationMicros += duration;
                    mCallCount++;
                    logWithPid(eventHandler.method.toString() + " duration: " + duration +
                            " microseconds, avg: " + (mCallDurationMicros / mCallCount));
                }
            } else {
                Log.e(TAG, "Failed to deliver event to null subscriber");
            }
        } catch (IllegalAccessException e) {
            Log.e(TAG, "Failed to invoke method", e.getCause());
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    /**
     * Returns whether this subscriber is currently registered.  If {@param removeFoundSubscriber}
     * is true, then remove the subscriber before returning.
     */
    private boolean findRegisteredSubscriber(Object subscriber, boolean removeFoundSubscriber) {
        for (int i = mSubscribers.size() - 1; i >= 0; i--) {
            Subscriber sub = mSubscribers.get(i);
            if (sub.getReference() == subscriber) {
                if (removeFoundSubscriber) {
                    mSubscribers.remove(i);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * @return whether {@param method} is a valid (normal or interprocess) event bus handler method
     */
    private boolean isValidEventBusHandlerMethod(Method method, Class<?>[] parameterTypes) {
        int modifiers = method.getModifiers();
        if (Modifier.isPublic(modifiers) &&
                Modifier.isFinal(modifiers) &&
                method.getReturnType().equals(Void.TYPE) &&
                parameterTypes.length == 1) {
            if (EventBus.Event.class.isAssignableFrom(parameterTypes[0]) &&
                            method.getName().startsWith(METHOD_PREFIX)) {
                return true;
            } else {
                if (DEBUG_TRACE_ALL) {
                    if (!EventBus.Event.class.isAssignableFrom(parameterTypes[0])) {
                        logWithPid("  Expected method take an Event-based parameter: " + method.getName());
                    }
                }
            }
        } else {
            if (DEBUG_TRACE_ALL) {
                if (!Modifier.isPublic(modifiers)) {
                    logWithPid("  Expected method to be public: " + method.getName());
                } else if (!Modifier.isFinal(modifiers)) {
                    logWithPid("  Expected method to be final: " + method.getName());
                } else if (!method.getReturnType().equals(Void.TYPE)) {
                    logWithPid("  Expected method to return null: " + method.getName());
                }
            }
        }
        return false;
    }

    /**
     * Sorts the event handlers by priority and registration time.
     */
    private void sortEventHandlersByPriority(List<EventHandler> eventHandlers) {
        Collections.sort(eventHandlers, EVENT_HANDLER_COMPARATOR);
    }

    /**
     * Helper method to log the given {@param text} with the current process and user id.
     */
    private static void logWithPid(String text) {
        Log.d(TAG, "[" + android.os.Process.myPid() + ", u" + UserHandle.myUserId() + "] " + text);
    }
}
