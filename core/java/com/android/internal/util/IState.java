/**
 * Copyright (C) 2011 The Android Open Source Project
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

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Message;

/**
 * {@hide}
 *
 * The interface for implementing states in a {@link StateMachine}
 */
public interface IState {

    /**
     * Returned by processMessage to indicate the the message was processed.
     */
    static final boolean HANDLED = true;

    /**
     * Returned by processMessage to indicate the the message was NOT processed.
     */
    static final boolean NOT_HANDLED = false;

    /**
     * Called when a state is entered.
     */
    void enter();

    /**
     * Called when a state is exited.
     */
    void exit();

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
     * @return HANDLED if processing has completed and NOT_HANDLED
     *         if the message wasn't processed.
     */
    boolean processMessage(Message msg);

    /**
     * Name of State for debugging purposes.
     *
     * @return name of state.
     */
    @UnsupportedAppUsage
    String getName();
}
