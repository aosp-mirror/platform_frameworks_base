/**
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util;

import android.os.Message;

/**
 * {@hide}
 *
 * The class for implementing states in a StateMachine
 */
public class State implements IState {

    /**
     * Constructor
     */
    protected State() {
    }

    /* (non-Javadoc)
     * @see com.android.internal.util.IState#enter()
     */
    @Override
    public void enter() {
    }

    /* (non-Javadoc)
     * @see com.android.internal.util.IState#exit()
     */
    @Override
    public void exit() {
    }

    /* (non-Javadoc)
     * @see com.android.internal.util.IState#processMessage(android.os.Message)
     */
    @Override
    public boolean processMessage(Message msg) {
        return false;
    }

    /**
     * Name of State for debugging purposes.
     *
     * This default implementation returns the class name, returning
     * the instance name would better in cases where a State class
     * is used for multiple states. But normally there is one class per
     * state and the class name is sufficient and easy to get. You may
     * want to provide a setName or some other mechanism for setting
     * another name if the class name is not appropriate.
     *
     * @see com.android.internal.util.IState#processMessage(android.os.Message)
     */
    @Override
    public String getName() {
        String name = getClass().getName();
        int lastDollar = name.lastIndexOf('$');
        return name.substring(lastDollar + 1);
    }
}
