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

import java.awt.AWTEvent;
import java.awt.Component;

/**
 * This class is not supported in Android 1.0. It is merely provided to maintain
 * interface compatibility with desktop Java implementations.
 * 
 * @since Android 1.0
 */
public class ComponentEvent extends AWTEvent {

    private static final long serialVersionUID = 8101406823902992965L;

    public static final int COMPONENT_FIRST = 100;

    public static final int COMPONENT_LAST = 103;

    public static final int COMPONENT_MOVED = 100;

    public static final int COMPONENT_RESIZED = 101;

    public static final int COMPONENT_SHOWN = 102;

    public static final int COMPONENT_HIDDEN = 103;

    public ComponentEvent(Component source, int id) {
        super(source, id);
    }

    public Component getComponent() {
        return (Component) source;
    }
    
    @Override
    public String paramString() {
        /* The format is based on 1.5 release behavior 
         * which can be revealed by the following code:
         * 
         * ComponentEvent e = new ComponentEvent(new Button("Button"), 
         *          ComponentEvent.COMPONENT_SHOWN);
         * System.out.println(e);
         */

        String idString = null;
        Component c = getComponent();

        switch (id) {
        case COMPONENT_MOVED:
            idString = "COMPONENT_MOVED"; //$NON-NLS-1$
            break;
        case COMPONENT_RESIZED:
            idString = "COMPONENT_RESIZED"; //$NON-NLS-1$
            break;
        case COMPONENT_SHOWN:
            return "COMPONENT_SHOWN"; //$NON-NLS-1$
        case COMPONENT_HIDDEN:
            return "COMPONENT_HIDDEN"; //$NON-NLS-1$
        default:
            return "unknown type"; //$NON-NLS-1$
        }

        return (idString + " (" + c.getX() + "," + c.getY() +  //$NON-NLS-1$ //$NON-NLS-2$
                " " + c.getWidth()+ "x" + c.getHeight() + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

}
