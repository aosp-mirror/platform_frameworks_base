/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.bluetooth;

import android.bluetooth.AtCommandResult;

/**
 * Handler Interface for {@link AtParser}.<p>
 * @hide
 */
public abstract class AtCommandHandler {

    /**
     * Handle Basic commands "ATA".<p>
     * These are single letter commands such as ATA and ATD. Anything following
     * the single letter command ('A' and 'D' respectively) will be passed as
     * 'arg'.<p>
     * For example, "ATDT1234" would result in the call
     * handleBasicCommand("T1234").<p>
     * @param arg Everything following the basic command character.
     * @return    The result of this command.
     */
    public AtCommandResult handleBasicCommand(String arg) {
        return new AtCommandResult(AtCommandResult.ERROR);
    }

    /**
     * Handle Actions command "AT+FOO".<p>
     * Action commands are part of the Extended command syntax, and are
     * typically used to signal an action on "FOO".<p>
     * @return The result of this command.
     */
    public AtCommandResult handleActionCommand() {
        return new AtCommandResult(AtCommandResult.ERROR);
    }

    /**
     * Handle Read command "AT+FOO?".<p>
     * Read commands are part of the Extended command syntax, and are
     * typically used to read the value of "FOO".<p>
     * @return The result of this command.
     */
    public AtCommandResult handleReadCommand() {
        return new AtCommandResult(AtCommandResult.ERROR);
    }

    /**
     * Handle Set command "AT+FOO=...".<p>
     * Set commands are part of the Extended command syntax, and are
     * typically used to set the value of "FOO". Multiple arguments can be
     * sent.<p>
     * AT+FOO=[<arg1>[,<arg2>[,...]]]<p>
     * Each argument will be either numeric (Integer) or String.
     * handleSetCommand is passed a generic Object[] array in which each
     * element will be an Integer (if it can be parsed with parseInt()) or
     * String.<p>
     * Missing arguments ",," are set to empty Strings.<p>
     * @param args Array of String and/or Integer's. There will always be at
     *             least one element in this array.
     * @return     The result of this command.
     */
    // Typically used to set this parameter
    public AtCommandResult handleSetCommand(Object[] args) {
        return new AtCommandResult(AtCommandResult.ERROR);
    }

    /**
     * Handle Test command "AT+FOO=?".<p>
     * Test commands are part of the Extended command syntax, and are typically
     * used to request an indication of the range of legal values that "FOO"
     * can take.<p>
     * By default we return an OK result, to indicate that this command is at
     * least recognized.<p>
     * @return The result of this command.
     */
    public AtCommandResult handleTestCommand() {
        return new AtCommandResult(AtCommandResult.OK);
    }

}
