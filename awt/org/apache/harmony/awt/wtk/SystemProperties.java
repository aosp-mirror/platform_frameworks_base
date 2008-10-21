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

import java.awt.Font;
import java.awt.font.TextAttribute;
import java.awt.im.InputMethodHighlight;
import java.util.Map;

/**
 * NativeProperties
 */

public interface SystemProperties {

    /**
     * Get current value of a system color
     * @param index - one of java.awt.SystemColor constants
     * @return ARGB value of requested system color
     */
    int getSystemColorARGB(int index);

    /**
     * Get default font for GUI elements such as menus and buttons
     * @return the font object
     */
    Font getDefaultFont();
    
    /**
     * Fill the given Map with system properties
     */
    void init(Map<String, ?> desktopProperties);

    /**
     * Fills the given map with system-dependent visual text
     * attributes for the abstract description 
     * of the given input method highlight
     * @see java.awt.Toolkit.mapInputMethodHighlight()
     */
    void mapInputMethodHighlight(InputMethodHighlight highlight, Map<TextAttribute, ?> map);
}
