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

import java.awt.Point;

/**
 * The interface provides access to platform dependent functionality
 * for classes java.awt.PointerInfo & java.awt.MouseInfo.
 */
public interface NativeMouseInfo {

    /**
     * Returns the Point that represents
     * the coordinates of the pointer on the screen.
     */
    Point getLocation();

    /**
     * Returns the number of buttons on the mouse.
     * If no mouse is installed returns -1.
     */
    int getNumberOfButtons();
}
