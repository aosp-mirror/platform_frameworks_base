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
package org.apache.harmony.awt.state;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Point;

/**
 * State of pop-up or drop-down menu
 */

public interface MenuState {
    int getWidth();
    int getHeight();
    Point getLocation();

    void setSize(int w, int h);

    Font getFont();
    boolean isFontSet();
    FontMetrics getFontMetrics(Font f);

    int getItemCount();
    int getSelectedItemIndex();

    MenuItemState getItem(int index);
}
