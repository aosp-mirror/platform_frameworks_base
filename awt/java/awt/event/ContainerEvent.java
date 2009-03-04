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
//???AWT: import java.awt.Container;

/**
 * This class is not supported in Android 1.0. It is merely provided to maintain
 * interface compatibility with desktop Java implementations.
 * 
 * @since Android 1.0
 */
public class ContainerEvent extends ComponentEvent {

    private static final long serialVersionUID = -4114942250539772041L;

    public static final int CONTAINER_FIRST = 300;

    public static final int CONTAINER_LAST = 301;

    public static final int COMPONENT_ADDED = 300;

    public static final int COMPONENT_REMOVED = 301;

    private Component child;

    public ContainerEvent(Component src, int id, Component child) {
        super(src, id);
        this.child = child;
    }

    public Component getChild() {
        return child;
    }

    //???AWT
    /*
    public Container getContainer() {
        return (Container) source;
    }
    */

    @Override
    public String paramString() {
        /* The format is based on 1.5 release behavior 
         * which can be revealed by the following code:
         * 
         * ContainerEvent e = new ContainerEvent(new Panel(),
         *          ContainerEvent.COMPONENT_ADDED,
         *          new Button("Button"));
         * System.out.println(e);
         */

        String idString = null;

        switch (id) {
        case COMPONENT_ADDED:
            idString = "COMPONENT_ADDED"; //$NON-NLS-1$
            break;
        case COMPONENT_REMOVED:
            idString = "COMPONENT_REMOVED"; //$NON-NLS-1$
            break;
        default:
            idString = "unknown type"; //$NON-NLS-1$
        }

        return (idString + ",child=" + child.getName()); //$NON-NLS-1$
    }

}
