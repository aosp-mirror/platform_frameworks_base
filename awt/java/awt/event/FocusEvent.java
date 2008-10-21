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

public class FocusEvent extends ComponentEvent {

    private static final long serialVersionUID = 523753786457416396L;

    public static final int FOCUS_FIRST = 1004;

    public static final int FOCUS_LAST = 1005;

    public static final int FOCUS_GAINED = 1004;

    public static final int FOCUS_LOST = 1005;

    private boolean temporary;
    private Component opposite;

    public FocusEvent(Component source, int id) {
        this(source, id, false);
    }

    public FocusEvent(Component source, int id, boolean temporary) {
        this(source, id, temporary, null);
    }

    public FocusEvent(Component source, int id, boolean temporary, Component opposite) {
        super(source, id);
        this.temporary = temporary;
        this.opposite = opposite;
    }

    public Component getOppositeComponent() {
        return opposite;
    }

    public boolean isTemporary() {
        return temporary;
    }

    @Override
    public String paramString() {
        /* The format is based on 1.5 release behavior 
         * which can be revealed by the following code:
         * 
         * FocusEvent e = new FocusEvent(new Button("Button0"),
         *       FocusEvent.FOCUS_GAINED, false, new Button("Button1"));
         * System.out.println(e);
         */

        String idString = null;

        switch (id) {
        case FOCUS_GAINED:
            idString = "FOCUS_GAINED"; //$NON-NLS-1$
            break;
        case FOCUS_LOST:
            idString = "FOCUS_LOST"; //$NON-NLS-1$
            break;
        default:
            idString = "unknown type"; //$NON-NLS-1$
        }

        return (idString +
                (temporary ? ",temporary" : ",permanent") + //$NON-NLS-1$ //$NON-NLS-2$
                ",opposite=" + opposite); //$NON-NLS-1$
    }

}
