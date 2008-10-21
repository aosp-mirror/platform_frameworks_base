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
 * NativeEventThread
 */
public class NativeEventThread extends Thread {
    
    public interface Init {
        WTK init();
    }
    
    NativeEventQueue nativeQueue;
    Init init;
    
    private WTK wtk;
    
    public NativeEventThread() {
        super("AWT-NativeEventThread"); //$NON-NLS-1$
        setDaemon(true);
    }

    @Override
    public void run() {
        synchronized (this) {
            try {
                wtk = init.init();
                nativeQueue = wtk.getNativeEventQueue();
            } finally {
                notifyAll();
            }
        }
        
        runModalLoop();
    }

    void runModalLoop() {
        while (nativeQueue.waitEvent()) {
            nativeQueue.dispatchEvent();
        }
    }
    
    public void start(Init init) {
        synchronized (this) {
            this.init = init;
            super.start();
            try {
                wait();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
    
    public WTK getWTK() {
        return wtk;
    }
}
