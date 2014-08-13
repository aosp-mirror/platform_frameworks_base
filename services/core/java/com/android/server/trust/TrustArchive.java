/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.trust;

import android.content.ComponentName;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.TimeUtils;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * An archive of trust events.
 */
public class TrustArchive {
    private static final int TYPE_GRANT_TRUST = 0;
    private static final int TYPE_REVOKE_TRUST = 1;
    private static final int TYPE_TRUST_TIMEOUT = 2;
    private static final int TYPE_AGENT_DIED = 3;
    private static final int TYPE_AGENT_CONNECTED = 4;
    private static final int TYPE_AGENT_STOPPED = 5;
    private static final int TYPE_MANAGING_TRUST = 6;

    private static final int HISTORY_LIMIT = 200;

    private static class Event {
        final int type;
        final int userId;
        final ComponentName agent;
        final long elapsedTimestamp;

        // grantTrust
        final String message;
        final long duration;
        final boolean userInitiated;

        // managingTrust
        final boolean managingTrust;

        private Event(int type, int userId, ComponentName agent, String message,
                long duration, boolean userInitiated, boolean managingTrust) {
            this.type = type;
            this.userId = userId;
            this.agent = agent;
            this.elapsedTimestamp = SystemClock.elapsedRealtime();
            this.message = message;
            this.duration = duration;
            this.userInitiated = userInitiated;
            this.managingTrust = managingTrust;
        }
    }

    ArrayDeque<Event> mEvents = new ArrayDeque<Event>();

    public void logGrantTrust(int userId, ComponentName agent, String message,
            long duration, boolean userInitiated) {
        addEvent(new Event(TYPE_GRANT_TRUST, userId, agent, message, duration,
                userInitiated, false));
    }

    public void logRevokeTrust(int userId, ComponentName agent) {
        addEvent(new Event(TYPE_REVOKE_TRUST, userId, agent, null, 0, false, false));
    }

    public void logTrustTimeout(int userId, ComponentName agent) {
        addEvent(new Event(TYPE_TRUST_TIMEOUT, userId, agent, null, 0, false, false));
    }

    public void logAgentDied(int userId, ComponentName agent) {
        addEvent(new Event(TYPE_AGENT_DIED, userId, agent, null, 0, false, false));
    }

    public void logAgentConnected(int userId, ComponentName agent) {
        addEvent(new Event(TYPE_AGENT_CONNECTED, userId, agent, null, 0, false, false));
    }

    public void logAgentStopped(int userId, ComponentName agent) {
        addEvent(new Event(TYPE_AGENT_STOPPED, userId, agent, null, 0, false, false));
    }

    public void logManagingTrust(int userId, ComponentName agent, boolean managing) {
        addEvent(new Event(TYPE_MANAGING_TRUST, userId, agent, null, 0, false, managing));
    }

    private void addEvent(Event e) {
        if (mEvents.size() >= HISTORY_LIMIT) {
            mEvents.removeFirst();
        }
        mEvents.addLast(e);
    }

    public void dump(PrintWriter writer, int limit, int userId, String linePrefix,
            boolean duplicateSimpleNames) {
        int count = 0;
        Iterator<Event> iter = mEvents.descendingIterator();
        while (iter.hasNext() && count < limit) {
            Event ev = iter.next();
            if (userId != UserHandle.USER_ALL && userId != ev.userId) {
                continue;
            }

            writer.print(linePrefix);
            writer.printf("#%-2d %s %s: ", count, formatElapsed(ev.elapsedTimestamp),
                    dumpType(ev.type));
            if (userId == UserHandle.USER_ALL) {
                writer.print("user="); writer.print(ev.userId); writer.print(", ");
            }
            writer.print("agent=");
            if (duplicateSimpleNames) {
                writer.print(ev.agent.flattenToShortString());
            } else {
                writer.print(getSimpleName(ev.agent));
            }
            switch (ev.type) {
                case TYPE_GRANT_TRUST:
                    writer.printf(", message=\"%s\", duration=%s, initiatedByUser=%d",
                            ev.message, formatDuration(ev.duration), ev.userInitiated ? 1 : 0);
                    break;
                case TYPE_MANAGING_TRUST:
                    writer.printf(", managingTrust=" + ev.managingTrust);
                    break;
                default:
            }
            writer.println();
            count++;
        }
    }

    public static String formatDuration(long duration) {
        StringBuilder sb = new StringBuilder();
        TimeUtils.formatDuration(duration, sb);
        return sb.toString();
    }

    private static String formatElapsed(long elapsed) {
        long delta = elapsed - SystemClock.elapsedRealtime();
        long wallTime = delta + System.currentTimeMillis();
        return TimeUtils.logTimeOfDay(wallTime);
    }

    /* package */ static String getSimpleName(ComponentName cn) {
        String name = cn.getClassName();
        int idx = name.lastIndexOf('.');
        if (idx < name.length() && idx >= 0) {
            return name.substring(idx + 1);
        } else {
            return name;
        }
    }

    private String dumpType(int type) {
        switch (type) {
            case TYPE_GRANT_TRUST:
                return "GrantTrust";
            case TYPE_REVOKE_TRUST:
                return "RevokeTrust";
            case TYPE_TRUST_TIMEOUT:
                return "TrustTimeout";
            case TYPE_AGENT_DIED:
                return "AgentDied";
            case TYPE_AGENT_CONNECTED:
                return "AgentConnected";
            case TYPE_AGENT_STOPPED:
                return "AgentStopped";
            case TYPE_MANAGING_TRUST:
                return "ManagingTrust";
            default:
                return "Unknown(" + type + ")";
        }
    }
}
