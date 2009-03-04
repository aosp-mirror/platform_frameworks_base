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

package com.android.internal.telephony;

import java.util.List;
/**
 * {@hide}
 */
public abstract class Call {
    /* Enums */

    public enum State {
        IDLE, ACTIVE, HOLDING, DIALING, ALERTING, INCOMING, WAITING, DISCONNECTED;

        public boolean isAlive() {
            return !(this == IDLE || this == DISCONNECTED);
        }

        public boolean isRinging() {
            return this == INCOMING || this == WAITING;
        }

        public boolean isDialing() {
            return this == DIALING || this == ALERTING;
        }
    }

    /* Instance Methods */

    /** Do not modify the List result!!! This list is not yours to keep
     *  It will change across event loop iterations            top
     */

    public abstract List<Connection> getConnections();
    public abstract State getState();
    public abstract Phone getPhone();

    /**
     * hasConnection
     * 
     * @param c a Connection object
     * @return true if the call contains the connection object passed in
     */
    public boolean hasConnection(Connection c) {
        return c.getCall() == this;
    }
    
    /**
     * hasConnections
     * @return true if the call contains one or more connections
     */
    public boolean hasConnections() {
        List connections = getConnections();
        
        if (connections == null) {
            return false;
        }
        
        return connections.size() > 0;
    }
    
    /**
     * isIdle
     * 
     * FIXME rename
     * @return true if the call contains only disconnected connections (if any)
     */
    public boolean isIdle() {
        return !getState().isAlive();
    }

    /**
     * Returns the Connection associated with this Call that was created
     * first, or null if there are no Connections in this Call
     */
    public Connection
    getEarliestConnection() {
        List l;
        long time = Long.MAX_VALUE;
        Connection c;
        Connection earliest = null;
        
        l = getConnections();
        
        if (l.size() == 0) {
            return null;
        }
        
        for (int i = 0, s = l.size() ; i < s ; i++) {
            c = (Connection) l.get(i);
            long t;
            
            t = c.getCreateTime();
            
            if (t < time) {
                earliest = c;
            }
        }
        
        return earliest;
    }
    
    public long
    getEarliestCreateTime() {
        List l;
        long time = Long.MAX_VALUE;

        l = getConnections();

        if (l.size() == 0) {
            return 0;
        }

        for (int i = 0, s = l.size() ; i < s ; i++) {
            Connection c = (Connection) l.get(i);
            long t;

            t = c.getCreateTime();

            time = t < time ? t : time;
        }

        return time;
    }

    public long
    getEarliestConnectTime() {
        List l;
        long time = Long.MAX_VALUE;

        l = getConnections();

        if (l.size() == 0) {
            return 0;
        }

        for (int i = 0, s = l.size() ; i < s ; i++) {
            Connection c = (Connection) l.get(i);
            long t;

            t = c.getConnectTime();

            time = t < time ? t : time;
        }

        return time;
    }

    public abstract boolean isMultiparty();

    public abstract void hangup() throws CallStateException;

    public boolean
    isDialingOrAlerting() {
        return getState().isDialing();
    }

    public boolean
    isRinging() {
        return getState().isRinging();
    }

}
