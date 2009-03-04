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

/**
 * This class describes cross-platform NativeWindow creation params
 * See also WindowFactory.createWindow
 */
public class CreationParams {
    /**
     * Initial state is maximized verticaly
     */
    public final long MAXIMIZED_VERT = 1;
    /**
     * Initial state is maximized horizontaly
     */
    public final long MAXIMIZED_HORIZ = 2;
    /**
     * Initial state is maximized both
     * horizontaly and verticaly
     */
    public final long MAXIMIZED = 3;

    /**
     * The top-level window that has all possible decorations,
     * has no owner and is displayed in taskbar
     */
    public final static int DECOR_TYPE_FRAME = 1;
    /**
     * The dialog window
     */
    public final static int DECOR_TYPE_DIALOG = 2;
    /**
     * The transient undecorated pop-up window
     */
    public final static int DECOR_TYPE_POPUP = 3;
    /**
     * The undecoraded pop-up window
     */
    public final static int DECOR_TYPE_UNDECOR = 4;
    /**
     * Non-MDI child window
     */
    public final static int DECOR_TYPE_NONE = 0;

    /**
     * Initial x.
     */
    public int x = 0;
    /**
     * Initial y.
     */
    public int y = 0;
    /**
     * Initial width.
     */
    public int w = 1;
    /**
     * Initial height.
     */
    public int h = 1;
    /**
     * The decoration type of the top-level window. The possible values are:
     * DECOR_TYPE_FRAME, DECOR_TYPE_DIALOG, DECOR_TYPE_POPUP and DECOR_TYPE_UNDECOR
     */
    public int decorType = DECOR_TYPE_NONE;
    /**
     * Window is child of parent, otherwise it's
     * toplevel(child of desktop) window owned by parent.
     */
    public boolean child = false;
    /**
     * Window is resizable
     */
    public boolean resizable = true;
    /**
     * The window has no decorations
     */
    public boolean undecorated = false;
    /**
     * Initial visibility state.
     */
    public boolean visible = false;
    /**
     * Window is ALWAYS topmost in Z order.
     */
    public boolean topmost = false;
    /**
     * Window is disabled.
     */
    public boolean disabled = false;
    /**
     * Window initially iconified.
     */
    public boolean iconified = false;
    /**
     * Bitwise OR of MAXIMIZED_* constants.
     * Means if window is initially maximized.
     */
    public int maximizedState = 0;
    /**
     * Tells that window position should be determined by native windowing system 
     */
    public boolean locationByPlatform = false;
    /**
     * Id of parent or owner window, see child field
     * For non-child window without owner equals 0.
     */
    public long parentId = 0;
    /**
     * Name wich is displayed on titlebar, taskbar and visible
     * for system requests.
     */
    public String name = null;
}