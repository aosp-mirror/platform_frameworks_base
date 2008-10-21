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
package java.awt;

import org.apache.harmony.awt.wtk.NativeWindow;

/**
 * The interface of the helper object that encapsulates the difference
 * between lightweight and heavyweight components. 
 */
interface ComponentBehavior {

    void addNotify();

    void setBounds(int x, int y, int w, int h, int bMask);

    void setVisible(boolean b);

    Graphics getGraphics(int translationX, int translationY, int width, int height);

    NativeWindow getNativeWindow();

    boolean isLightweight();

    void onMove(int x, int y);

    boolean isOpaque();

    boolean isDisplayable();

    void setEnabled(boolean value);

    void removeNotify();

    void setZOrder(int newIndex, int oldIndex);

    boolean setFocus(boolean focus, Component opposite);
}
