/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.telecom.Logging;

import android.annotation.NonNull;

import java.util.ArrayList;

/**
 * The session that stores information about a thread's point of entry into the Telecom code that
 * persists until the thread exits Telecom.
 * @hide
 */
public class Session {

    public static final String START_SESSION = "START_SESSION";
    public static final String CREATE_SUBSESSION = "CREATE_SUBSESSION";
    public static final String CONTINUE_SUBSESSION = "CONTINUE_SUBSESSION";
    public static final String END_SUBSESSION = "END_SUBSESSION";
    public static final String END_SESSION = "END_SESSION";

    public static final int UNDEFINED = -1;

    private String mSessionId;
    private String mShortMethodName;
    private long mExecutionStartTimeMs;
    private long mExecutionEndTimeMs = UNDEFINED;
    private Session mParentSession;
    private ArrayList<Session> mChildSessions;
    private boolean mIsCompleted = false;
    private int mChildCounter = 0;
    // True if this is a subsession that has been started from the same thread as the parent
    // session. This can happen if Log.startSession(...) is called multiple times on the same
    // thread in the case of one Telecom entry point method calling another entry point method.
    // In this case, we can just make this subsession "invisible," but still keep track of it so
    // that the Log.endSession() calls match up.
    private boolean mIsStartedFromActiveSession = false;
    // Optionally provided info about the method/class/component that started the session in order
    // to make Logging easier. This info will be provided in parentheses along with the session.
    private String mOwnerInfo;

    public Session(String sessionId, String shortMethodName, long startTimeMs, long threadID,
            boolean isStartedFromActiveSession, String ownerInfo) {
        setSessionId(sessionId);
        setShortMethodName(shortMethodName);
        mExecutionStartTimeMs = startTimeMs;
        mParentSession = null;
        mChildSessions = new ArrayList<>(5);
        mIsStartedFromActiveSession = isStartedFromActiveSession;
        mOwnerInfo = ownerInfo;
    }

    public void setSessionId(@NonNull String sessionId) {
        if (sessionId == null) {
            mSessionId = "?";
        }
        mSessionId = sessionId;
    }

    public String getShortMethodName() {
        return mShortMethodName;
    }

    public void setShortMethodName(String shortMethodName) {
        if (shortMethodName == null) {
            shortMethodName = "";
        }
        mShortMethodName = shortMethodName;
    }

    public void setParentSession(Session parentSession) {
        mParentSession = parentSession;
    }

    public void addChild(Session childSession) {
        if (childSession != null) {
            mChildSessions.add(childSession);
        }
    }

    public void removeChild(Session child) {
        if (child != null) {
            mChildSessions.remove(child);
        }
    }

    public long getExecutionStartTimeMilliseconds() {
        return mExecutionStartTimeMs;
    }

    public void setExecutionStartTimeMs(long startTimeMs) {
        mExecutionStartTimeMs = startTimeMs;
    }

    public Session getParentSession() {
        return mParentSession;
    }

    public ArrayList<Session> getChildSessions() {
        return mChildSessions;
    }

    public boolean isSessionCompleted() {
        return mIsCompleted;
    }

    public boolean isStartedFromActiveSession() {
        return mIsStartedFromActiveSession;
    }

    // Mark this session complete. This will be deleted by Log when all subsessions are complete
    // as well.
    public void markSessionCompleted(long executionEndTimeMs) {
        mExecutionEndTimeMs = executionEndTimeMs;
        mIsCompleted = true;
    }

    public long getLocalExecutionTime() {
        if (mExecutionEndTimeMs == UNDEFINED) {
            return UNDEFINED;
        }
        return mExecutionEndTimeMs - mExecutionStartTimeMs;
    }

    public synchronized String getNextChildId() {
        return String.valueOf(mChildCounter++);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Session)) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        Session otherSession = (Session) obj;
        return (mSessionId.equals(otherSession.mSessionId)) &&
                (mShortMethodName.equals(otherSession.mShortMethodName)) &&
                mExecutionStartTimeMs == otherSession.mExecutionStartTimeMs &&
                mParentSession == otherSession.mParentSession &&
                mChildSessions.equals(otherSession.mChildSessions) &&
                mIsCompleted == otherSession.mIsCompleted &&
                mExecutionEndTimeMs == otherSession.mExecutionEndTimeMs &&
                mChildCounter == otherSession.mChildCounter &&
                mIsStartedFromActiveSession == otherSession.mIsStartedFromActiveSession &&
                mOwnerInfo == otherSession.mOwnerInfo;
    }

    // Builds full session id recursively
    private String getFullSessionId() {
        // Cache mParentSession locally to prevent a concurrency problem where
        // Log.endParentSessions() is called while a logging statement is running (Log.i, for
        // example) and setting mParentSession to null in a different thread after the null check
        // occurred.
        Session parentSession = mParentSession;
        if (parentSession == null) {
            return mSessionId;
        } else {
            return parentSession.getFullSessionId() + "_" + mSessionId;
        }
    }

    // Print out the full Session tree from any subsession node
    public String printFullSessionTree() {
        // Get to the top of the tree
        Session topNode = this;
        while (topNode.getParentSession() != null) {
            topNode = topNode.getParentSession();
        }
        return topNode.printSessionTree();
    }

    // Recursively move down session tree using DFS, but print out each node when it is reached.
    public String printSessionTree() {
        StringBuilder sb = new StringBuilder();
        printSessionTree(0, sb);
        return sb.toString();
    }

    private void printSessionTree(int tabI, StringBuilder sb) {
        sb.append(toString());
        for (Session child : mChildSessions) {
            sb.append("\n");
            for (int i = 0; i <= tabI; i++) {
                sb.append("\t");
            }
            child.printSessionTree(tabI + 1, sb);
        }
    }

    @Override
    public String toString() {
        if (mParentSession != null && mIsStartedFromActiveSession) {
            // Log.startSession was called from within another active session. Use the parent's
            // Id instead of the child to reduce confusion.
            return mParentSession.toString();
        } else {
            StringBuilder methodName = new StringBuilder();
            methodName.append(mShortMethodName);
            if (mOwnerInfo != null && !mOwnerInfo.isEmpty()) {
                methodName.append("(InCall package: ");
                methodName.append(mOwnerInfo);
                methodName.append(")");
            }
            return methodName.toString() + "@" + getFullSessionId();
        }
    }
}
