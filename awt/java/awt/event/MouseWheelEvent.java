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

import java.awt.Component;

public class MouseWheelEvent extends MouseEvent {

    private static final long serialVersionUID = -9187413581993563929L;

    public static final int WHEEL_UNIT_SCROLL = 0;

    public static final int WHEEL_BLOCK_SCROLL = 1;

    private int wheelRotation;
    private int scrollAmount;
    private int scrollType;

    public MouseWheelEvent(Component source, int id, long when, int modifiers,
            int x, int y, int clickCount, boolean popupTrigger, int scrollType,
            int scrollAmount, int wheelRotation) {
        super(source, id, when, modifiers, x, y, clickCount, popupTrigger);

        this.scrollType = scrollType;
        this.scrollAmount = scrollAmount;
        this.wheelRotation = wheelRotation;
    }

    public int getScrollAmount() {
        return scrollAmount;
    }

    public int getScrollType() {
        return scrollType;
    }

    public int getWheelRotation() {
        return wheelRotation;
    }

    public int getUnitsToScroll() {
        return (scrollAmount * wheelRotation);
    }

    @Override
    public String paramString() {
        /* The format is based on 1.5 release behavior 
         * which can be revealed by the following code:
         * 
         * MouseWheelEvent e = new MouseWheelEvent(new Component(){}, 
         *          MouseWheelEvent.MOUSE_WHEEL, 0, 
         *          MouseEvent.BUTTON1_DOWN_MASK|MouseEvent.CTRL_DOWN_MASK,
         *          10, 20, 1, false, MouseWheelEvent.WHEEL_UNIT_SCROLL,
         *          1, 3);
         * System.out.println(e);
         */

        String paramString = super.paramString();
        String typeString = null;

        switch (scrollType) {
        case WHEEL_UNIT_SCROLL:
            typeString = "WHEEL_UNIT_SCROLL"; //$NON-NLS-1$
            break;
        case WHEEL_BLOCK_SCROLL:
            typeString = "WHEEL_BLOCK_SCROLL"; //$NON-NLS-1$
            break;
        default:
            typeString = "unknown type"; //$NON-NLS-1$
        }

        paramString += ",scrollType=" + typeString + //$NON-NLS-1$
                ",scrollAmount=" + scrollAmount +  //$NON-NLS-1$
                ",wheelRotation=" + wheelRotation; //$NON-NLS-1$

        return paramString;
    }

}
