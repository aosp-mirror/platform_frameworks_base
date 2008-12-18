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

package java.awt;

import java.awt.event.AdjustmentListener;

/**
 * The Adjustable interface represents an adjustable numeric value contained
 * within a bounded range of values, such as the current location in scrollable
 * region or the value of a gauge.
 * 
 * @since Android 1.0
 */
public interface Adjustable {

    /**
     * The Constant HORIZONTAL indicates that the Adjustable's orientation is
     * horizontal.
     */
    public static final int HORIZONTAL = 0;

    /**
     * The Constant VERTICAL indicates that the Adjustable's orientation is
     * vertical.
     */
    public static final int VERTICAL = 1;

    /**
     * The Constant NO_ORIENTATION indicates that the Adjustable has no
     * orientation.
     */
    public static final int NO_ORIENTATION = 2;

    /**
     * Gets the value of the Adjustable.
     * 
     * @return the current value of the Adjustable.
     */
    public int getValue();

    /**
     * Sets the value to the Adjustable object.
     * 
     * @param a0
     *            the new value of the Adjustable object.
     */
    public void setValue(int a0);

    /**
     * Adds the AdjustmentListener to current Adjustment.
     * 
     * @param a0
     *            the AdjustmentListener object.
     */
    public void addAdjustmentListener(AdjustmentListener a0);

    /**
     * Gets the block increment of the Adjustable.
     * 
     * @return the block increment of the Adjustable.
     */
    public int getBlockIncrement();

    /**
     * Gets the maximum value of the Adjustable.
     * 
     * @return the maximum value of the Adjustable.
     */
    public int getMaximum();

    /**
     * Gets the minimum value of the Adjustable.
     * 
     * @return the minimum value of the Adjustable.
     */
    public int getMinimum();

    /**
     * Gets the orientation of the Adjustable.
     * 
     * @return the orientation of the Adjustable.
     */
    public int getOrientation();

    /**
     * Gets the unit increment of the Adjustable.
     * 
     * @return the unit increment of the Adjustable.
     */
    public int getUnitIncrement();

    /**
     * Gets the visible amount of the Adjustable.
     * 
     * @return the visible amount of the Adjustable.
     */
    public int getVisibleAmount();

    /**
     * Removes the adjustment listener of the Adjustable.
     * 
     * @param a0
     *            the specified AdjustmentListener to be removed.
     */
    public void removeAdjustmentListener(AdjustmentListener a0);

    /**
     * Sets the block increment for the Adjustable.
     * 
     * @param a0
     *            the new block increment.
     */
    public void setBlockIncrement(int a0);

    /**
     * Sets the maximum value of the Adjustable.
     * 
     * @param a0
     *            the new maximum of the Adjustable.
     */
    public void setMaximum(int a0);

    /**
     * Sets the minimum value of the Adjustable.
     * 
     * @param a0
     *            the new minimum of the Adjustable.
     */
    public void setMinimum(int a0);

    /**
     * Sets the unit increment of the Adjustable.
     * 
     * @param a0
     *            the new unit increment of the Adjustable.
     */
    public void setUnitIncrement(int a0);

    /**
     * Sets the visible amount of the Adjustable.
     * 
     * @param a0
     *            the new visible amount of the Adjustable.
     */
    public void setVisibleAmount(int a0);

}
