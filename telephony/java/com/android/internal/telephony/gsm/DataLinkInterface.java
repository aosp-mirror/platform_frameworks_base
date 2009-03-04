/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.gsm;

import android.database.Cursor;
import android.os.Handler;

/**
 * Data link interface.
 *
 * {@hide}
 */
interface DataLinkInterface {
    /**
     * Link state enumeration.
     *
     */
    enum LinkState {
        LINK_UNKNOWN,
        LINK_UP,
        LINK_DOWN,
        LINK_EXITED
    }
    
    /** Normal exit */
    final static int EXIT_OK = 0;
    /** Open failed */
    final static int EXIT_OPEN_FAILED = 7;
    
    /**
     * Sets the handler for link state change events.
     * 
     * @param h Handler
     * @param what User-defined message code
     * @param obj User object
     */
    void setOnLinkChange(Handler h, int what, Object obj);
    
    /**
     * Sets up the data link.
     */
    void connect();

    /**
     * Tears down the data link.
     */
    void disconnect();
    
    /**
     * Returns the exit code for a data link failure. 
     *
     * @return exit code
     */
    int getLastLinkExitCode();
    
    /**
     * Sets password information that may be required by the data link
     * (eg, PAP secrets).
     *
     * @param cursor cursor to carriers table
     */
    void setPasswordInfo(Cursor cursor);
}
