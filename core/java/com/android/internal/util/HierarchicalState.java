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
 * The class for implementing states in a HierarchicalStateMachine
 */
public class HierarchicalState {

    /**
     * Constructor
     */
    protected HierarchicalState() {
    }

    /**
     * Called when a state is entered.
     */
    protected void enter() {
    }

    /**
     * Called when a message is to be processed by the
     * state machine.
     *
     * This routine is never reentered thus no synchronization
     * is needed as only one processMessage method will ever be
     * executing within a state machine at any given time. This
     * does mean that processing by this routine must be completed
     * as expeditiously as possible as no subsequent messages will
     * be processed until this routine returns.
     *
     * @param msg to process
     * @return true if processing has completed and false
     *         if the parent state's processMessage should
     *         be invoked.
     */
    protected boolean processMessage(Message msg) {
        return false;
    }

    /**
     * Called when a state is exited.
     */
    protected void exit() {
    }

    /**
     * @return name of state, but default returns the states
     * class name. An instance name would be better but requiring
     * it seems unnecessary.
     */
    public String getName() {
        String name = getClass().getName();
        int lastDollar = name.lastIndexOf('$');
        return name.substring(lastDollar + 1);
    }
}
