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
 * @author Michael Danilov
 * @version $Revision$
 */
package java.awt.event;


//???AWT
//import java.awt.Window;
//import java.awt.Frame;

/**
 * This class is not supported in Android 1.0. It is merely provided to maintain
 * interface compatibility with desktop Java implementations.
 * 
 * @since Android 1.0
 */
public class WindowEvent extends ComponentEvent {

    private static final long serialVersionUID = -1567959133147912127L;

    public static final int WINDOW_FIRST = 200;

    public static final int WINDOW_OPENED = 200;

    public static final int WINDOW_CLOSING = 201;

    public static final int WINDOW_CLOSED = 202;

    public static final int WINDOW_ICONIFIED = 203;

    public static final int WINDOW_DEICONIFIED = 204;

    public static final int WINDOW_ACTIVATED = 205;

    public static final int WINDOW_DEACTIVATED = 206;

    public static final int WINDOW_GAINED_FOCUS = 207;

    public static final int WINDOW_LOST_FOCUS = 208;

    public static final int WINDOW_STATE_CHANGED = 209;

    public static final int WINDOW_LAST = 209;

    //???AWT: private Window oppositeWindow;
    private int oldState;
    private int newState;

    //???AWT
    /*
    public WindowEvent(Window source, int id) {
        this(source, id, null);
    }

    public WindowEvent(Window source, int id, Window opposite) {
        this(source, id, opposite, Frame.NORMAL, Frame.NORMAL);
    }

    public WindowEvent(Window source, int id, int oldState, int newState) {
        this(source, id, null, oldState, newState);
    }

    public WindowEvent(Window source, int id, Window opposite, 
                       int oldState, int newState) {
        super(source, id);

        oppositeWindow = opposite;
        this.oldState = oldState;
        this.newState = newState;
    }
    */
    //???AWT: Fake constructor
    public WindowEvent() {
        super(null, 0);
    }
    
    public int getNewState() {
        return newState;
    }

    public int getOldState() {
        return oldState;
    }

    //???AWT
    /*
    public Window getOppositeWindow() {
        return oppositeWindow;
    }

    public Window getWindow() {
        return (Window) source;
    }
    */

    @Override
    public String paramString() {
        /* The format is based on 1.5 release behavior 
         * which can be revealed by the following code:
         * 
         * WindowEvent e = new WindowEvent(new Frame(), 
         *          WindowEvent.WINDOW_OPENED); 
         * System.out.println(e);
         */

        String typeString = null;

        switch (id) {
        case WINDOW_OPENED:
            typeString = "WINDOW_OPENED"; //$NON-NLS-1$
            break;
        case WINDOW_CLOSING:
            typeString = "WINDOW_CLOSING"; //$NON-NLS-1$
            break;
        case WINDOW_CLOSED:
            typeString = "WINDOW_CLOSED"; //$NON-NLS-1$
            break;
        case WINDOW_ICONIFIED:
            typeString = "WINDOW_ICONIFIED"; //$NON-NLS-1$
            break;
        case WINDOW_DEICONIFIED:
            typeString = "WINDOW_DEICONIFIED"; //$NON-NLS-1$
            break;
        case WINDOW_ACTIVATED:
            typeString = "WINDOW_ACTIVATED"; //$NON-NLS-1$
            break;
        case WINDOW_DEACTIVATED:
            typeString = "WINDOW_DEACTIVATED"; //$NON-NLS-1$
            break;
        case WINDOW_GAINED_FOCUS:
            typeString = "WINDOW_GAINED_FOCUS"; //$NON-NLS-1$
            break;
        case WINDOW_LOST_FOCUS:
            typeString = "WINDOW_LOST_FOCUS"; //$NON-NLS-1$
            break;
        case WINDOW_STATE_CHANGED:
            typeString = "WINDOW_STATE_CHANGED"; //$NON-NLS-1$
            break;
        default:
            typeString = "unknown type"; //$NON-NLS-1$
        }

        //???AWT
        /*
        return typeString + ",opposite=" + oppositeWindow + //$NON-NLS-1$
                ",oldState=" + oldState + ",newState=" + newState; //$NON-NLS-1$ //$NON-NLS-2$
        */
        return typeString;
    }

}
