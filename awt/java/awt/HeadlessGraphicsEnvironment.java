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

package java.awt;

import java.awt.GraphicsDevice;
import java.awt.HeadlessException;

import org.apache.harmony.awt.gl.CommonGraphicsEnvironment;

/**
 * The HeadlessGraphicsEnvironment class is the CommonGraphicsEnvironment
 * implementation to use in the case where the environment lacks display,
 * keyboard, and mouse support.
 * 
 * @since Android 1.0
 */
public class HeadlessGraphicsEnvironment extends CommonGraphicsEnvironment {

    /**
     * Returns whether or not a display, keyboard, and mouse are supported in
     * this graphics environment.
     * 
     * @return true, if HeadlessException will be thrown from areas of the
     *         graphics environment that are dependent on a display, keyboard,
     *         or mouse, false otherwise.
     */
    @Override
    public boolean isHeadlessInstance() {
        return true;
    }

    /**
     * Gets the default screen device as GraphicDevice object.
     * 
     * @return the GraphicDevice object which represents default screen device.
     * @throws HeadlessException
     *             if isHeadless() returns true.
     */
    @Override
    public GraphicsDevice getDefaultScreenDevice() throws HeadlessException {
        throw new HeadlessException();
    }

    /**
     * Gets an array of all available screen devices.
     * 
     * @return the array of GraphicsDevice objects which represents all
     *         available screen devices.
     * @throws HeadlessException
     *             if isHeadless() returns true.
     */
    @Override
    public GraphicsDevice[] getScreenDevices() throws HeadlessException {
        throw new HeadlessException();
    }

}
