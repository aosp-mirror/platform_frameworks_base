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

package java.awt;

import java.awt.event.ItemListener;

/**
 * The ItemSelectable interface represents a set of items which can be selected.
 * 
 * @since Android 1.0
 */
public interface ItemSelectable {

    /**
     * Adds an ItemListener for receiving item events when the state of an item
     * is changed by the user.
     * 
     * @param l
     *            the ItemListener.
     */
    public void addItemListener(ItemListener l);

    /**
     * Gets an array of the selected objects or null if there is no selected
     * object.
     * 
     * @return an array of the selected objects or null if there is no selected
     *         object.
     */
    public Object[] getSelectedObjects();

    /**
     * Removes the specified ItemListener.
     * 
     * @param l
     *            the ItemListener which will be removed.
     */
    public void removeItemListener(ItemListener l);

}
