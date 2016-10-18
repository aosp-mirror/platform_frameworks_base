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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.Base64;

import com.android.internal.annotations.VisibleForTesting;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TODO: Create better Sessions Documentation
 * @hide
 */

public class SessionManager {

    // Currently using 3 letters, So don't exceed 64^3
    private static final long SESSION_ID_ROLLOVER_THRESHOLD = 262144;

    // This parameter can be overridden in Telecom's Timeouts class.
    public static final long DEFAULT_SESSION_TIMEOUT_MS = 30000L; // 30 seconds

    private static String LOGGING_TAG = "Logging";

    private static final String TIMEOUTS_PREFIX = "telecom.";

    // Synchronized in all method calls
    private int sCodeEntryCounter = 0;
    private Context mContext;

    @VisibleForTesting
    public ConcurrentHashMap<Integer, Session> mSessionMapper = new ConcurrentHashMap<>(100);
    @VisibleForTesting
    public Handler mSessionCleanupHandler = new Handler(Looper.getMainLooper());
    @VisibleForTesting
    public java.lang.Runnable mCleanStaleSessions = () ->
            cleanupStaleSessions(getSessionCleanupTimeoutMs());

    // Overridden in LogTest to skip query to ContentProvider
    private interface ISessionCleanupTimeoutMs {
        long get();
    }

    // Overridden in tests to provide test Thread IDs
    public interface ICurrentThreadId {
        int get();
    }

    @VisibleForTesting
    public ICurrentThreadId mCurrentThreadId = Process::myTid;

    @VisibleForTesting
    public ISessionCleanupTimeoutMs mSessionCleanupTimeoutMs = () -> {
        // mContext may be null in some cases, such as testing. For these cases, use the
        // default value.
        if (mContext == null) {
            return DEFAULT_SESSION_TIMEOUT_MS;
        }
        return getCleanupTimeout(mContext);
    };

    // Usage is synchronized on this class.
    private List<ISessionListener> mSessionListeners = new ArrayList<>();

    public interface ISessionListener {
        /**
         * This method is run when a full Session has completed.
         * @param sessionName The name of the Session that has completed.
         * @param timeMs The time it took to complete in ms.
         */
        void sessionComplete(String sessionName, long timeMs);
    }

    public interface ISessionIdQueryHandler {
        String getSessionId();
    }

    public void setContext(Context context) {
        mContext = context;
    }

    public SessionManager() {
    }

    private long getSessionCleanupTimeoutMs() {
        return mSessionCleanupTimeoutMs.get();
    }

    private synchronized void resetStaleSessionTimer() {
        mSessionCleanupHandler.removeCallbacksAndMessages(null);
        // Will be null in Log Testing
        if (mCleanStaleSessions != null) {
            mSessionCleanupHandler.postDelayed(mCleanStaleSessions, getSessionCleanupTimeoutMs());
        }
    }

    /**
     * Call at an entry point to the Telecom code to track the session. This code must be
     * accompanied by a Log.endSession().
     */
    public synchronized void startSession(String shortMethodName,
            String callerIdentification) {
        resetStaleSessionTimer();
        int threadId = getCallingThreadId();
        Session activeSession = mSessionMapper.get(threadId);
        // We have called startSession within an active session that has not ended... Register this
        // session as a subsession.
        if (activeSession != null) {
            Session childSession = createSubsession(true);
            continueSession(childSession, shortMethodName);
            return;
        }
        Session newSession = new Session(getNextSessionID(), shortMethodName,
                System.currentTimeMillis(), threadId, false, callerIdentification);
        mSessionMapper.put(threadId, newSession);

        android.util.Slog.v(LOGGING_TAG, Session.START_SESSION);
    }


    /**
     * Notifies the logging system that a subsession will be run at a later point and
     * allocates the resources. Returns a session object that must be used in
     * Log.continueSession(...) to start the subsession.
     */
    public Session createSubsession() {
        return createSubsession(false);
    }

    private synchronized Session createSubsession(boolean isStartedFromActiveSession) {
        int threadId = getCallingThreadId();
        Session threadSession = mSessionMapper.get(threadId);
        if (threadSession == null) {
            android.util.Slog.d(LOGGING_TAG, "Log.createSubsession was called with no session " +
                    "active.");
            return null;
        }
        // Start execution time of the session will be overwritten in continueSession(...).
        Session newSubsession = new Session(threadSession.getNextChildId(),
                threadSession.getShortMethodName(), System.currentTimeMillis(), threadId,
                isStartedFromActiveSession, null);
        threadSession.addChild(newSubsession);
        newSubsession.setParentSession(threadSession);

        if (!isStartedFromActiveSession) {
            android.util.Slog.v(LOGGING_TAG, Session.CREATE_SUBSESSION + " " +
                    newSubsession.toString());
        } else {
            android.util.Slog.v(LOGGING_TAG, Session.CREATE_SUBSESSION +
                    " (Invisible subsession)");
        }
        return newSubsession;
    }

    /**
     * Cancels a subsession that had Log.createSubsession() called on it, but will never have
     * Log.continueSession(...) called on it due to an error. Allows the subsession to be cleaned
     * gracefully instead of being removed by the mSessionCleanupHandler forcefully later.
     */
    public synchronized void cancelSubsession(Session subsession) {
        if (subsession == null) {
            return;
        }

        subsession.markSessionCompleted(Session.UNDEFINED);
        endParentSessions(subsession);
    }

    /**
     * Starts the subsession that was created in Log.CreateSubsession. The Log.endSession() method
     * must be called at the end of this method. The full session will complete when all
     * subsessions are completed.
     */
    public synchronized void continueSession(Session subsession, String shortMethodName) {
        if (subsession == null) {
            return;
        }
        resetStaleSessionTimer();
        String callingMethodName = subsession.getShortMethodName();
        subsession.setShortMethodName(callingMethodName + "->" + shortMethodName);
        subsession.setExecutionStartTimeMs(System.currentTimeMillis());
        Session parentSession = subsession.getParentSession();
        if (parentSession == null) {
            android.util.Slog.d(LOGGING_TAG, "Log.continueSession was called with no session " +
                    "active for method " + shortMethodName);
            return;
        }

        mSessionMapper.put(getCallingThreadId(), subsession);
        if (!subsession.isStartedFromActiveSession()) {
            android.util.Slog.v(LOGGING_TAG, Session.CONTINUE_SUBSESSION);
        } else {
            android.util.Slog.v(LOGGING_TAG, Session.CONTINUE_SUBSESSION +
                    " (Invisible Subsession) with Method " + shortMethodName);
        }
    }

    /**
     * Ends the current session/subsession. Must be called after a Log.startSession(...) and
     * Log.continueSession(...) call.
     */
    public synchronized void endSession() {
        int threadId = getCallingThreadId();
        Session completedSession = mSessionMapper.get(threadId);
        if (completedSession == null) {
            android.util.Slog.w(LOGGING_TAG, "Log.endSession was called with no session active.");
            return;
        }

        completedSession.markSessionCompleted(System.currentTimeMillis());
        if (!completedSession.isStartedFromActiveSession()) {
            android.util.Slog.v(LOGGING_TAG, Session.END_SUBSESSION + " (dur: " +
                    completedSession.getLocalExecutionTime() + " mS)");
        } else {
            android.util.Slog.v(LOGGING_TAG, Session.END_SUBSESSION +
                    " (Invisible Subsession) (dur: " + completedSession.getLocalExecutionTime() +
                    " ms)");
        }
        // Remove after completed so that reference still exists for logging the end events
        Session parentSession = completedSession.getParentSession();
        mSessionMapper.remove(threadId);
        endParentSessions(completedSession);
        // If this subsession was started from a parent session using Log.startSession, return the
        // ThreadID back to the parent after completion.
        if (parentSession != null && !parentSession.isSessionCompleted() &&
                completedSession.isStartedFromActiveSession()) {
            mSessionMapper.put(threadId, parentSession);
        }
    }

    // Recursively deletes all complete parent sessions of the current subsession if it is a leaf.
    private void endParentSessions(Session subsession) {
        // Session is not completed or not currently a leaf, so we can not remove because a child is
        // still running
        if (!subsession.isSessionCompleted() || subsession.getChildSessions().size() != 0) {
            return;
        }

        Session parentSession = subsession.getParentSession();
        if (parentSession != null) {
            subsession.setParentSession(null);
            parentSession.removeChild(subsession);
            endParentSessions(parentSession);
        } else {
            // All of the subsessions have been completed and it is time to report on the full
            // running time of the session.
            long fullSessionTimeMs =
                    System.currentTimeMillis() - subsession.getExecutionStartTimeMilliseconds();
            android.util.Slog.d(LOGGING_TAG, Session.END_SESSION + " (dur: " + fullSessionTimeMs
                    + " ms): " + subsession.toString());
            // TODO: Add analytics hook
            for (ISessionListener l : mSessionListeners) {
                l.sessionComplete(subsession.getShortMethodName(), fullSessionTimeMs);
            }
        }
    }

    public String getSessionId() {
        Session currentSession = mSessionMapper.get(getCallingThreadId());
        return currentSession != null ? currentSession.toString() : "";
    }

    public synchronized void registerSessionListener(ISessionListener l) {
        if (l != null) {
            mSessionListeners.add(l);
        }
    }

    private synchronized String getNextSessionID() {
        Integer nextId = sCodeEntryCounter++;
        if (nextId >= SESSION_ID_ROLLOVER_THRESHOLD) {
            restartSessionCounter();
            nextId = sCodeEntryCounter++;
        }
        return getBase64Encoding(nextId);
    }

    @VisibleForTesting
    public synchronized void restartSessionCounter() {
        sCodeEntryCounter = 0;
    }

    @VisibleForTesting
    public String getBase64Encoding(int number) {
        byte[] idByteArray = ByteBuffer.allocate(4).putInt(number).array();
        idByteArray = Arrays.copyOfRange(idByteArray, 2, 4);
        return Base64.encodeToString(idByteArray, Base64.NO_WRAP | Base64.NO_PADDING);
    }

    public int getCallingThreadId() {
        return mCurrentThreadId.get();
    }

    @VisibleForTesting
    private synchronized void cleanupStaleSessions(long timeoutMs) {
        String logMessage = "Stale Sessions Cleaned:\n";
        boolean isSessionsStale = false;
        long currentTimeMs = System.currentTimeMillis();
        // Remove references that are in the Session Mapper (causing GC to occur) on
        // sessions that are lasting longer than LOGGING_SESSION_TIMEOUT_MS.
        // If this occurs, then there is most likely a Session active that never had
        // Log.endSession called on it.
        for (Iterator<ConcurrentHashMap.Entry<Integer, Session>> it =
             mSessionMapper.entrySet().iterator(); it.hasNext(); ) {
            ConcurrentHashMap.Entry<Integer, Session> entry = it.next();
            Session session = entry.getValue();
            if (currentTimeMs - session.getExecutionStartTimeMilliseconds() > timeoutMs) {
                it.remove();
                logMessage += session.printFullSessionTree() + "\n";
                isSessionsStale = true;
            }
        }
        if (isSessionsStale) {
            android.util.Slog.w(LOGGING_TAG, logMessage);
        } else {
            android.util.Slog.v(LOGGING_TAG, "No stale logging sessions needed to be cleaned...");
        }
    }

    /**
     * Returns the amount of time after a Logging session has been started that Telecom is set to
     * perform a sweep to check and make sure that the session is still not incomplete (stale).
     */
    private long getCleanupTimeout(Context context) {
        return Settings.Secure.getLong(context.getContentResolver(), TIMEOUTS_PREFIX +
                "stale_session_cleanup_timeout_millis", DEFAULT_SESSION_TIMEOUT_MS);
    }
}
