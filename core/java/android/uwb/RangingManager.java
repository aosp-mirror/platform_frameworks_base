/*
 * Copyright 2020 The Android Open Source Project
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

package android.uwb;

import android.annotation.NonNull;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.util.Log;

import java.util.Hashtable;
import java.util.concurrent.Executor;

/**
 * @hide
 */
public class RangingManager extends android.uwb.IUwbRangingCallbacks.Stub {
    private static final String TAG = "Uwb.RangingManager";

    private final IUwbAdapter mAdapter;
    private final Hashtable<SessionHandle, RangingSession> mRangingSessionTable = new Hashtable<>();

    public RangingManager(IUwbAdapter adapter) {
        mAdapter = adapter;
    }

    /**
     * Open a new ranging session
     *
     * @param params the parameters that define the ranging session
     * @param executor {@link Executor} to run callbacks
     * @param callbacks {@link RangingSession.Callback} to associate with the {@link RangingSession}
     *                  that is being opened.
     * @return a new {@link RangingSession}
     */
    public RangingSession openSession(@NonNull PersistableBundle params, @NonNull Executor executor,
            @NonNull RangingSession.Callback callbacks) {
        SessionHandle sessionHandle;
        try {
            sessionHandle = mAdapter.startRanging(this, params);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        synchronized (this) {
            if (hasSession(sessionHandle)) {
                Log.w(TAG, "Newly created session unexpectedly reuses an active SessionHandle");
                executor.execute(() -> callbacks.onClosed(
                        RangingSession.Callback.CLOSE_REASON_LOCAL_GENERIC_ERROR,
                        new PersistableBundle()));
            }

            RangingSession session =
                    new RangingSession(executor, callbacks, mAdapter, sessionHandle);
            mRangingSessionTable.put(sessionHandle, session);
            return session;
        }
    }

    private boolean hasSession(SessionHandle sessionHandle) {
        return mRangingSessionTable.containsKey(sessionHandle);
    }

    @Override
    public void onRangingStarted(SessionHandle sessionHandle, PersistableBundle parameters) {
        synchronized (this) {
            if (!hasSession(sessionHandle)) {
                Log.w(TAG,
                        "onRangingStarted - received unexpected SessionHandle: " + sessionHandle);
                return;
            }

            RangingSession session = mRangingSessionTable.get(sessionHandle);
            session.onRangingStarted(parameters);
        }
    }

    @Override
    public void onRangingStartFailed(SessionHandle sessionHandle, int reason,
            PersistableBundle params) {
        synchronized (this) {
            if (!hasSession(sessionHandle)) {
                Log.w(TAG, "onRangingStartFailed - received unexpected SessionHandle: "
                        + sessionHandle);
                return;
            }

            RangingSession session = mRangingSessionTable.get(sessionHandle);
            session.onRangingClosed(convertStartFailureToCloseReason(reason), params);
            mRangingSessionTable.remove(sessionHandle);
        }
    }

    @Override
    public void onRangingClosed(SessionHandle sessionHandle, int reason, PersistableBundle params) {
        synchronized (this) {
            if (!hasSession(sessionHandle)) {
                Log.w(TAG, "onRangingClosed - received unexpected SessionHandle: " + sessionHandle);
                return;
            }

            RangingSession session = mRangingSessionTable.get(sessionHandle);
            session.onRangingClosed(convertToCloseReason(reason), params);
            mRangingSessionTable.remove(sessionHandle);
        }
    }

    @Override
    public void onRangingResult(SessionHandle sessionHandle, RangingReport result) {
        synchronized (this) {
            if (!hasSession(sessionHandle)) {
                Log.w(TAG, "onRangingResult - received unexpected SessionHandle: " + sessionHandle);
                return;
            }

            RangingSession session = mRangingSessionTable.get(sessionHandle);
            session.onRangingResult(result);
        }
    }

    @RangingSession.Callback.CloseReason
    private static int convertToCloseReason(@CloseReason int reason) {
        switch (reason) {
            case CloseReason.LOCAL_API:
                return RangingSession.Callback.CLOSE_REASON_LOCAL_CLOSE_API;

            case CloseReason.MAX_SESSIONS_REACHED:
                return RangingSession.Callback.CLOSE_REASON_LOCAL_MAX_SESSIONS_REACHED;

            case CloseReason.SYSTEM_POLICY:
                return RangingSession.Callback.CLOSE_REASON_LOCAL_SYSTEM_POLICY;

            case CloseReason.REMOTE_REQUEST:
                return RangingSession.Callback.CLOSE_REASON_REMOTE_REQUEST;

            case CloseReason.PROTOCOL_SPECIFIC:
                return RangingSession.Callback.CLOSE_REASON_PROTOCOL_SPECIFIC;

            case CloseReason.UNKNOWN:
            default:
                return RangingSession.Callback.CLOSE_REASON_UNKNOWN;
        }
    }

    @RangingSession.Callback.CloseReason
    private static int convertStartFailureToCloseReason(@StartFailureReason int reason) {
        switch (reason) {
            case StartFailureReason.BAD_PARAMETERS:
                return RangingSession.Callback.CLOSE_REASON_LOCAL_BAD_PARAMETERS;

            case StartFailureReason.MAX_SESSIONS_REACHED:
                return RangingSession.Callback.CLOSE_REASON_LOCAL_MAX_SESSIONS_REACHED;

            case StartFailureReason.SYSTEM_POLICY:
                return RangingSession.Callback.CLOSE_REASON_LOCAL_SYSTEM_POLICY;

            case StartFailureReason.PROTOCOL_SPECIFIC:
                return RangingSession.Callback.CLOSE_REASON_PROTOCOL_SPECIFIC;

            case StartFailureReason.UNKNOWN:
            default:
                return RangingSession.Callback.CLOSE_REASON_UNKNOWN;
        }
    }
}
