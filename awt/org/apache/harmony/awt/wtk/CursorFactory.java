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

import java.awt.Dimension;
import java.awt.Image;

/**
 * Provides factory for NativeCursor
 */
public abstract class CursorFactory {
    protected NativeCursor[] systemCursors = {
            null, null, null, null,
            null, null, null, null,
            null, null, null, null,
            null, null,
    };
    /**
     * Creates and returns NativeCursor for predefined
     * Java Cursor
     *
     * @param type - type of predefined Java Cursor
     * @return created cursor
     */
    public abstract NativeCursor createCursor(int type);

    /**
     * Gets a cached instance of system(predefined) native cursor
     * or creates a new one. This is a platform-independent method.
     *
     * @param type - type of predefined Java Cursor
     * @return created cursor
     */
    public NativeCursor getCursor(int type) {
        if (type >= 0 && type < systemCursors.length) {
            NativeCursor cursor = systemCursors[type];
            if (cursor == null) {
                cursor = createCursor(type);
                systemCursors[type] = cursor;
            }
            return cursor;
        }
        return null;
    }
    /**
     * Creates and returns custom NativeCursor from image
     *
     * @param img - image(source) to create cursor from
     * @param xHotSpot - x coordinate of the hotspot relative to the source's origin
     * @param yHotSpot - y coordinate of the hotspot relative to the source's origin
     * @return created cursor
     */
    public abstract NativeCursor createCustomCursor(Image img, int xHotSpot, int yHotSpot);

    /**
     * Query native system for the best cursor size closest to specified dimensions
     * @param prefWidth - preferred width
     * @param prefHeight - preferred height
     * @return closest supported dimensions to ones specified
     */
    public abstract Dimension getBestCursorSize(int prefWidth, int prefHeight);

    /**
     * @return maximum number of colors supported by custom cursors
     */
    public abstract int getMaximumCursorColors();
}
