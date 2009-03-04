/*
 * Copyright 2007, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 *
 *     http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 */

package com.android.internal.awt;

import org.apache.harmony.awt.wtk.NativeEventQueue;

public class AndroidNativeEventQueue extends NativeEventQueue {
    
    private Object eventMonitor;
    
    public AndroidNativeEventQueue() {
        super();
        eventMonitor = getEventMonitor();
    }

    @Override
    public void awake() {
        synchronized (eventMonitor) {
            eventMonitor.notify();
        }
    }

    @Override
    public void dispatchEvent() {
        //???AWT
        System.out.println(getClass()+": empty method called");
    }

    @Override
    public long getJavaWindow() {
        //???AWT
        System.out.println(getClass()+": empty method called");
        return 0;
    }

    @Override
    public void performLater(Task task) {
        //???AWT
        System.out.println(getClass()+": empty method called");
    }

    @Override
    public void performTask(Task task) {
        //???AWT
        System.out.println(getClass()+": empty method called");
    }

    @Override
    public boolean waitEvent() {
        while (isEmpty() ) {
            synchronized (eventMonitor) {
                try {
                    eventMonitor.wait(1000);
                } catch (InterruptedException ignore) {
                }
            }
        }
        return false;
    }

}
