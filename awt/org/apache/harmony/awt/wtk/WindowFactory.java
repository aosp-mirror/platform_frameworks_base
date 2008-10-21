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

import java.awt.Dimension;
import java.awt.Point;

/**
 * Provides factory for NativeWindow
 */
public interface WindowFactory {
    /**
     * Creates and returns NativeWindow with desired
     * creation params
     *
     * @param p - initial window properties
     * @return created window
     */
    NativeWindow createWindow(CreationParams p);
    /**
     * Create NativeWindow instance connected to existing native resource
     * @param nativeWindowId - id of existing window
     * @return created NativeWindow instance
     */
    NativeWindow attachWindow(long nativeWindowId);
    /**
     * Returns NativeWindow instance if created by this instance of
     * WindowFactory, otherwise null
     *
     * @param id - HWND on Windows xwindow on X
     * @return NativeWindow or null if unknown
     */
    NativeWindow getWindowById(long id);
    /**
     * Returns NativeWindow instance of the top-level window
     * that contains a specified point and was
     * created by this instance of WindowFactory
     * @param p - Point to check
     * @return NativeWindow or null if the point is
     * not within a window created by this WindowFactory
     */
    NativeWindow getWindowFromPoint(Point p);

    /**
     * Returns whether native system supports the state for windows.
     * This method tells whether the UI concept of, say, maximization or iconification is supported.
     * It will always return false for "compound" states like Frame.ICONIFIED|Frame.MAXIMIZED_VERT.
     * In other words, the rule of thumb is that only queries with a single frame state
     * constant as an argument are meaningful.
     *
     * @param state - one of named frame state constants.
     * @return true is this frame state is supported by this Toolkit implementation, false otherwise.
     */
    boolean isWindowStateSupported(int state);

    /**
     * @see org.apache.harmony.awt.ComponentInternals
     */
    void setCaretPosition(int x, int y);

    /**
     * Request size of arbitrary native window
     * @param id - window ID
     * @return window size
     */
    Dimension getWindowSizeById(long id);
}