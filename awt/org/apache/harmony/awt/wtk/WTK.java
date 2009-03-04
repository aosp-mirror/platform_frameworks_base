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

import java.awt.GraphicsDevice;


public abstract class WTK {

    public abstract GraphicsFactory getGraphicsFactory();
    public abstract NativeEventQueue getNativeEventQueue();
    public abstract WindowFactory getWindowFactory();

    /**
     * Returns platform specific implementation of the interface
     * org.apache.harmony.awt.wtk.CursorFactory.
     * @return implementation of CursorFactory
     */
    public abstract CursorFactory getCursorFactory();

    /**
     * Returns platform specific implementation of the interface
     * org.apache.harmony.awt.wtk.NativeMouseInfo.
     * @return implementation of NativeMouseInfo
     */
    public abstract NativeMouseInfo getNativeMouseInfo();

    public abstract SystemProperties getSystemProperties();

    /**
     * Returns platform specific implementation of the interface
     * org.apache.harmony.awt.wtk.NativeRobot.
     * @return implementation of NativeRobot
     */
    public abstract NativeRobot getNativeRobot(GraphicsDevice screen);
    
    /**
     * Returns platform specific implementation of the abstract
     * class org.apache.harmony.awt.wtk.NativeIM.
     * @return implementation of NativeIM
     */
    public abstract NativeIM getNativeIM();
}
