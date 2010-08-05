/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.telephony.sip;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.DriverCall;
import com.android.internal.telephony.Phone;

import android.net.sip.SipManager;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.sip.SipException;

abstract class SipCallBase extends Call {
    private static final int MAX_CONNECTIONS_PER_CALL = 5;

    protected List<Connection> connections = new ArrayList<Connection>();

    protected abstract void setState(State newState);

    public void dispose() {
    }

    public List<Connection> getConnections() {
        // FIXME should return Collections.unmodifiableList();
        return connections;
    }

    public boolean isMultiparty() {
        return connections.size() > 1;
    }

    public String toString() {
        return state.toString();
    }

    /**
     * Called by SipConnection when it has disconnected
     */
    void connectionDisconnected(Connection conn) {
        if (state != State.DISCONNECTED) {
            /* If only disconnected connections remain, we are disconnected*/

            boolean hasOnlyDisconnectedConnections = true;

            for (int i = 0, s = connections.size()  ; i < s; i ++) {
                if (connections.get(i).getState()
                    != State.DISCONNECTED
                ) {
                    hasOnlyDisconnectedConnections = false;
                    break;
                }
            }

            if (hasOnlyDisconnectedConnections) {
                state = State.DISCONNECTED;
            }
        }
    }


    /*package*/ void detach(Connection conn) {
        connections.remove(conn);

        if (connections.size() == 0) {
            state = State.IDLE;
        }
    }

    /**
     * @return true if there's no space in this call for additional
     * connections to be added via "conference"
     */
    /*package*/ boolean isFull() {
        return connections.size() == MAX_CONNECTIONS_PER_CALL;
    }

    void clearDisconnected() {
        for (Iterator<Connection> it = connections.iterator(); it.hasNext(); ) {
            Connection c = it.next();
            if (c.getState() == State.DISCONNECTED) it.remove();
        }

        if (connections.isEmpty()) setState(State.IDLE);
    }
}
