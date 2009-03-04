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
 * @author Dmitry A. Durnev
 * @version $Revision$
 */
package org.apache.harmony.awt.wtk;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * A cross-platform interface for java.awt.Robot implementation
 */
public interface NativeRobot {

    /**
     * @see java.awt.Robot#createScreenCapture(Rectangle)
     * @param screenRect rectangle to capture in screen coordinates
     * @return the captured image or null if
     * capture failed.
     */
    BufferedImage createScreenCapture(Rectangle screenRect);

    /**
     * @see java.awt.Robot#getPixelColor(int, int)
     */
    Color getPixel(int x, int y);

    /**
     * Generate a native system keyboard input event.
     * @param keycode A Java virtual key code
     * @param press A key is pressed if true, released otherwise
     * @see java.awt.Robot#keyPress(int)
     * @throws IllegalArgumentException if keycode is invalid in the native system
     */
    void keyEvent(int keycode, boolean press);

    /**
     * Generate a native system mouse button(s) press or release event.
     * @param buttons A mask of Java mouse button flags
     * @param press buttons are pressed if true, released otherwise
     * @see java.awt.Robot#mousePress(int)
     */
    void mouseButton(int buttons, boolean press);

    /**
     * Generate a native system mouse motion event.
     *
     * @see java.awt.Robot#mouseMove(int, int)
     */
    void mouseMove(int x, int y);

    /**
     * Generate a native system mouse wheel event.
     *
     * @see java.awt.Robot#mouseWheel(int)
     */
    void mouseWheel(int wheelAmt);
}
