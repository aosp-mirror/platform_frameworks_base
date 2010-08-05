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

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.DriverCall;
import com.android.internal.telephony.Phone;

import java.util.ArrayList;
import java.util.List;

/**
 * {@hide}
 */
class GsmCall extends Call {
    /*************************** Instance Variables **************************/

    /*package*/ ArrayList<Connection> connections = new ArrayList<Connection>();
    /*package*/ GsmCallTracker owner;


    /***************************** Class Methods *****************************/

    static State
    stateFromDCState (DriverCall.State dcState) {
        switch (dcState) {
            case ACTIVE:        return State.ACTIVE;
            case HOLDING:       return State.HOLDING;
            case DIALING:       return State.DIALING;
            case ALERTING:      return State.ALERTING;
            case INCOMING:      return State.INCOMING;
            case WAITING:       return State.WAITING;
            default:            throw new RuntimeException ("illegal call state:" + dcState);
        }
    }


    /****************************** Constructors *****************************/
    /*package*/
    GsmCall (GsmCallTracker owner) {
        this.owner = owner;
    }

    public void dispose() {
    }

    /************************** Overridden from Call *************************/

    public List<Connection>
    getConnections() {
        // FIXME should return Collections.unmodifiableList();
        return connections;
    }

    public Phone
    getPhone() {
        return owner.phone;
    }

    public boolean
    isMultiparty() {
        return connections.size() > 1;
    }

    /** Please note: if this is the foreground call and a
     *  background call exists, the background call will be resumed
     *  because an AT+CHLD=1 will be sent
     */
    public void
    hangup() throws CallStateException {
        owner.hangup(this);
    }

    public String
    toString() {
        return state.toString();
    }

    //***** Called from GsmConnection

    /*package*/ void
    attach(Connection conn, DriverCall dc) {
        connections.add(conn);

        state = stateFromDCState (dc.state);
    }

    /*package*/ void
    attachFake(Connection conn, State state) {
        connections.add(conn);

        this.state = state;
    }

    /**
     * Called by GsmConnection when it has disconnected
     */
    void
    connectionDisconnected(GsmConnection conn) {
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


    /*package*/ void
    detach(GsmConnection conn) {
        connections.remove(conn);

        if (connections.size() == 0) {
            state = State.IDLE;
        }
    }

    /*package*/ boolean
    update (GsmConnection conn, DriverCall dc) {
        State newState;
        boolean changed = false;

        newState = stateFromDCState(dc.state);

        if (newState != state) {
            state = newState;
            changed = true;
        }

        return changed;
    }

    /**
     * @return true if there's no space in this call for additional
     * connections to be added via "conference"
     */
    /*package*/ boolean
    isFull() {
        return connections.size() == GsmCallTracker.MAX_CONNECTIONS_PER_CALL;
    }

    //***** Called from GsmCallTracker


    /**
     * Called when this Call is being hung up locally (eg, user pressed "end")
     * Note that at this point, the hangup request has been dispatched to the radio
     * but no response has yet been received so update() has not yet been called
     */
    void
    onHangupLocal() {
        for (int i = 0, s = connections.size()
                ; i < s; i++
        ) {
            GsmConnection cn = (GsmConnection)connections.get(i);

            cn.onHangupLocal();
        }
        state = State.DISCONNECTING;
    }

    /**
     * Called when it's time to clean up disconnected Connection objects
     */
    void
    clearDisconnected() {
        for (int i = connections.size() - 1 ; i >= 0 ; i--) {
            GsmConnection cn = (GsmConnection)connections.get(i);

            if (cn.getState() == State.DISCONNECTED) {
                connections.remove(i);
            }
        }

        if (connections.size() == 0) {
            state = State.IDLE;
        }
    }
}

