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
 * @author Mikhail Danilov
 * @version $Revision$
 */
package org.apache.harmony.awt.wtk;

import java.util.LinkedList;


/**
 * Describes the cross-platform native event queue interface
 *
 * <p/> The implementation constructor should remember thread it was
 * created. All other methods would be called obly from this thread,
 * except awake().
 */
public abstract class NativeEventQueue {
    
    private ShutdownWatchdog shutdownWatchdog;
    private class EventMonitor {}
    private final Object eventMonitor = new EventMonitor();
    private final LinkedList<NativeEvent> eventQueue = new LinkedList<NativeEvent>();

    public static abstract class Task {
        public volatile Object returnValue;

        public abstract void perform();
    }
    
    /**
     * Blocks current thread until native event queue is not empty
     * or awaken from other thread by awake().
     *
     * <p/>Should be called only on tread which
     * will process native events.
     *
     * @return if event loop should be stopped
     */
    public abstract boolean waitEvent();

    /**
     * Determines whether or not the native event queue is empty.
     * An queue is empty if it contains no messages waiting.
     *
     * @return true if the queue is empty; false otherwise
     */
    public boolean isEmpty() {
        synchronized(eventQueue) {
            return eventQueue.isEmpty();
        }
    }

    public NativeEvent getNextEvent() {
        synchronized (eventQueue) {
            if (eventQueue.isEmpty()) {
                shutdownWatchdog.setNativeQueueEmpty(true);
                return null;
            }
            return eventQueue.remove(0);
        }
    }
    
    protected void addEvent(NativeEvent event) {
        synchronized (eventQueue) {
            eventQueue.add(event);
            shutdownWatchdog.setNativeQueueEmpty(false);
        }
        synchronized (eventMonitor) {
            eventMonitor.notify();
        }
    }

    public final Object getEventMonitor() {
        return eventMonitor;
    }

    public abstract void awake();

    /**
     * Gets AWT system window ID.
     *
     * @return AWT system window ID
     */
    public abstract long getJavaWindow();

    /**
     * Add NativeEvent to the queue
     */
    public abstract void dispatchEvent();

    public abstract void performTask(Task task);

    public abstract void performLater(Task task);
    
    public final void setShutdownWatchdog(ShutdownWatchdog watchdog) {
        synchronized (eventQueue) {
            shutdownWatchdog = watchdog;
        }
    }

}
