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
 * @author Pavel Dolgov
 * @version $Revision$
 */
package org.apache.harmony.awt.wtk;

/**
 * Shutdown Watchdog
 */
public final class ShutdownWatchdog {
    
    private boolean nativeQueueEmpty = true;
    private boolean awtQueueEmpty = true;
    private boolean windowListEmpty = true;

    private boolean forcedShutdown = false;
    
    private ShutdownThread thread;

    public synchronized void setNativeQueueEmpty(boolean empty) {
        nativeQueueEmpty = empty;
        checkShutdown();
    }

    public synchronized void setAwtQueueEmpty(boolean empty) {
        awtQueueEmpty = empty;
        checkShutdown();
    }

    public synchronized void setWindowListEmpty(boolean empty) {
        windowListEmpty = empty;
        checkShutdown();
    }
    
    public synchronized void forceShutdown() {
        forcedShutdown = true;
        shutdown();
    }
    
    public synchronized void start() {
        keepAlive();
    }

    private void checkShutdown() {
        if (canShutdown()) {
            shutdown();
        } else {
            keepAlive();
        }
    }

    private boolean canShutdown() {
        return (nativeQueueEmpty && awtQueueEmpty && windowListEmpty) ||
                forcedShutdown;
    }

    private void keepAlive() {
        if (thread == null) {
            thread = new ShutdownThread();
            thread.start();
        }
    }

    private void shutdown() {
        if (thread != null) {
            thread.shutdown();
            thread = null;
        }
    }
}
