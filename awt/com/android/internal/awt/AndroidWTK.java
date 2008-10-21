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

import java.awt.GraphicsDevice;

import org.apache.harmony.awt.wtk.CursorFactory;
import org.apache.harmony.awt.wtk.GraphicsFactory;
import org.apache.harmony.awt.wtk.NativeEventQueue;
import org.apache.harmony.awt.wtk.NativeIM;
import org.apache.harmony.awt.wtk.NativeMouseInfo;
import org.apache.harmony.awt.wtk.NativeRobot;
import org.apache.harmony.awt.wtk.SystemProperties;
import org.apache.harmony.awt.wtk.WTK;
import org.apache.harmony.awt.wtk.WindowFactory;

public class AndroidWTK extends WTK {

    private AndroidGraphicsFactory mAgf;
    private AndroidNativeEventQueue mAneq;
    
    @Override
    public CursorFactory getCursorFactory() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public GraphicsFactory getGraphicsFactory() {
        if(mAgf == null) {
            mAgf = new AndroidGraphicsFactory();
        }
        return mAgf;
    }

    @Override
    public NativeEventQueue getNativeEventQueue() {
        if(mAneq == null) {
            mAneq = new AndroidNativeEventQueue();
        }
        return mAneq;
    }

    @Override
    public NativeIM getNativeIM() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public NativeMouseInfo getNativeMouseInfo() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public NativeRobot getNativeRobot(GraphicsDevice screen) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public SystemProperties getSystemProperties() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public WindowFactory getWindowFactory() {
        // TODO Auto-generated method stub
        return null;
    }

}
