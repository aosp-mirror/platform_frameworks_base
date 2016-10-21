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
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;

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

    public static final String SUBSESSION_SEPARATION_CHAR = "->";
    public static final String EXTERNAL_INDICATOR = "E-";

    /**
     * Initial value of mExecutionEndTimeMs and the final value of {@link #getLocalExecutionTime()}
     * if the Session is canceled.
     */
    public static final int UNDEFINED = -1;

    public static class Info implements Parcelable {
        public final String sessionId;
        public final String shortMethodName;

        private Info(String id, String methodName) {
            sessionId = id;
            shortMethodName = methodName;
        }

        public static Info getInfo (Session s) {
            return new Info(s.getFullSessionId(), s.getShortMethodName());
        }

        /** Responsible for creating Info objects for deserialized Parcels. */
        public static final Parcelable.Creator<Info> CREATOR =
                new Parcelable.Creator<Info> () {
                    @Override
                    public Info createFromParcel(Parcel source) {
                        String id = source.readString();
                        String methodName = source.readString();
                        return new Info(id, methodName);
                    }

                    @Override
                    public Info[] newArray(int size) {
                        return new Info[size];
                    }
                };

        /** {@inheritDoc} */
        @Override
        public int describeContents() {
            return 0;
        }

        /** Writes Info object into a Parcel. */
        @Override
        public void writeToParcel(Parcel destination, int flags) {
            destination.writeString(sessionId);
            destination.writeString(shortMethodName);
        }
    }

    private String mSessionId;
    private String mShortMethodName;
    private long mExecutionStartTimeMs;
    private long mExecutionEndTimeMs = UNDEFINED;
    private Session mParentSession;
    private ArrayList<Session> mChildSessions;
    private boolean mIsCompleted = false;
    private boolean mIsExternal = false;
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
    // Cache Full Method path so that recursive population of the full method path only needs to
    // be calculated once.
    private String mFullMethodPathCache;

    public Session(String sessionId, String shortMethodName, long startTimeMs,
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

    public void setIsExternal(boolean isExternal) {
        mIsExternal = isExternal;
    }

    public boolean isExternal() {
        return mIsExternal;
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

    public Info getInfo() {
        return Info.getInfo(this);
    }

    @VisibleForTesting
    public String getSessionId() {
        return mSessionId;
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

    // Recursively concatenate mShortMethodName with the parent Sessions to create full method
    // path. Caches this string so that multiple calls for the path will be quick.
    public String getFullMethodPath() {
        StringBuilder sb = new StringBuilder();
        getFullMethodPath(sb);
        return sb.toString();
    }

    private synchronized void getFullMethodPath(StringBuilder sb) {
        // Don't calculate if we have already figured it out!
        if (!TextUtils.isEmpty(mFullMethodPathCache)) {
            sb.append(mFullMethodPathCache);
            return;
        }
        Session parentSession = getParentSession();
        boolean isSessionStarted = false;
        if (parentSession != null) {
            // Check to see if the session has been renamed yet. If it has not, then the session
            // has not been continued.
            isSessionStarted = !mShortMethodName.equals(parentSession.mShortMethodName);
            parentSession.getFullMethodPath(sb);
            sb.append(SUBSESSION_SEPARATION_CHAR);
        }
        sb.append(mShortMethodName);

        if(isSessionStarted) {
            // Cache this value so that we do not have to do this work next time!
            // We do not cache the value if the session being evaluated hasn't been continued yet.
            mFullMethodPathCache = sb.toString();
        }
    }

    @Override
    public int hashCode() {
        int result = mSessionId != null ? mSessionId.hashCode() : 0;
        result = 31 * result + (mShortMethodName != null ? mShortMethodName.hashCode() : 0);
        result = 31 * result + (int) (mExecutionStartTimeMs ^ (mExecutionStartTimeMs >>> 32));
        result = 31 * result + (int) (mExecutionEndTimeMs ^ (mExecutionEndTimeMs >>> 32));
        result = 31 * result + (mParentSession != null ? mParentSession.hashCode() : 0);
        result = 31 * result + (mChildSessions != null ? mChildSessions.hashCode() : 0);
        result = 31 * result + (mIsCompleted ? 1 : 0);
        result = 31 * result + mChildCounter;
        result = 31 * result + (mIsStartedFromActiveSession ? 1 : 0);
        result = 31 * result + (mOwnerInfo != null ? mOwnerInfo.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Session session = (Session) o;

        if (mExecutionStartTimeMs != session.mExecutionStartTimeMs) return false;
        if (mExecutionEndTimeMs != session.mExecutionEndTimeMs) return false;
        if (mIsCompleted != session.mIsCompleted) return false;
        if (mChildCounter != session.mChildCounter) return false;
        if (mIsStartedFromActiveSession != session.mIsStartedFromActiveSession) return false;
        if (mSessionId != null ?
                !mSessionId.equals(session.mSessionId) : session.mSessionId != null)
            return false;
        if (mShortMethodName != null ? !mShortMethodName.equals(session.mShortMethodName)
                : session.mShortMethodName != null)
            return false;
        if (mParentSession != null ? !mParentSession.equals(session.mParentSession)
                : session.mParentSession != null)
            return false;
        if (mChildSessions != null ? !mChildSessions.equals(session.mChildSessions)
                : session.mChildSessions != null)
            return false;
        return mOwnerInfo != null ? mOwnerInfo.equals(session.mOwnerInfo)
                : session.mOwnerInfo == null;

    }

    @Override
    public String toString() {
        if (mParentSession != null && mIsStartedFromActiveSession) {
            // Log.startSession was called from within another active session. Use the parent's
            // Id instead of the child to reduce confusion.
            return mParentSession.toString();
        } else {
            StringBuilder methodName = new StringBuilder();
            methodName.append(getFullMethodPath());
            if (mOwnerInfo != null && !mOwnerInfo.isEmpty()) {
                methodName.append("(InCall package: ");
                methodName.append(mOwnerInfo);
                methodName.append(")");
            }
            return methodName.toString() + "@" + getFullSessionId();
        }
    }
}
