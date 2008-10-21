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
import java.awt.Adjustable;

public class AdjustmentEvent extends AWTEvent {

    private static final long serialVersionUID = 5700290645205279921L;

    public static final int ADJUSTMENT_FIRST = 601;

    public static final int ADJUSTMENT_LAST = 601;

    public static final int ADJUSTMENT_VALUE_CHANGED = 601;

    public static final int UNIT_INCREMENT = 1;

    public static final int UNIT_DECREMENT = 2;

    public static final int BLOCK_DECREMENT = 3;

    public static final int BLOCK_INCREMENT = 4;

    public static final int TRACK = 5;

    private int type;
    private int value;
    private boolean isAdjusting;

    public AdjustmentEvent(Adjustable source, int id, int type, int value) {
        this(source, id, type, value, false);
    }

    public AdjustmentEvent(Adjustable source, int id, int type, int value, 
                           boolean isAdjusting) {
        super(source, id);
        this.type = type;
        this.value = value;
        this.isAdjusting = isAdjusting;
    }

    public int getValue() {
        return value;
    }

    public int getAdjustmentType() {
        return type;
    }

    public boolean getValueIsAdjusting() {
        return isAdjusting;
    }

    public Adjustable getAdjustable() {
        return (Adjustable) source;
    }

    @Override
    public String paramString() {
        /* The format is based on 1.5 release behavior 
         * which can be revealed by the following code:
         * 
         * AdjustmentEvent e = new AdjustmentEvent(new Scrollbar(), 
         *       AdjustmentEvent.ADJUSTMENT_VALUE_CHANGED, 
         *       AdjustmentEvent.UNIT_INCREMENT, 1);
         * System.out.println(e);
         */

        String idString = (id == ADJUSTMENT_VALUE_CHANGED ?
                "ADJUSTMENT_VALUE_CHANGED" : "unknown type"); //$NON-NLS-1$ //$NON-NLS-2$
        String adjType = null;

        switch (type) {
        case UNIT_INCREMENT:
            adjType = "UNIT_INCREMENT"; //$NON-NLS-1$
            break;
        case UNIT_DECREMENT:
            adjType = "UNIT_DECREMENT"; //$NON-NLS-1$
            break;
        case BLOCK_INCREMENT:
            adjType = "BLOCK_INCREMENT"; //$NON-NLS-1$
            break;
        case BLOCK_DECREMENT:
            adjType = "BLOCK_DECREMENT"; //$NON-NLS-1$
            break;
        case TRACK:
            adjType = "TRACK"; //$NON-NLS-1$
            break;
        default:
            adjType = "unknown type"; //$NON-NLS-1$
        }

        return (idString + ",adjType=" + adjType + ",value=" + value + //$NON-NLS-1$ //$NON-NLS-2$
                ",isAdjusting=" + isAdjusting); //$NON-NLS-1$
    }

}
