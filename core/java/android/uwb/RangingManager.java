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
            sessionHandle = mAdapter.openRanging(this, params);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        synchronized (this) {
            if (hasSession(sessionHandle)) {
                Log.w(TAG, "Newly created session unexpectedly reuses an active SessionHandle");
                executor.execute(() -> callbacks.onClosed(
                        RangingSession.Callback.REASON_GENERIC_ERROR,
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
    public void onRangingOpened(SessionHandle sessionHandle) {
        synchronized (this) {
            if (!hasSession(sessionHandle)) {
                Log.w(TAG,
                        "onRangingOpened - received unexpected SessionHandle: " + sessionHandle);
                return;
            }

            RangingSession session = mRangingSessionTable.get(sessionHandle);
            session.onRangingOpened();
        }
    }

    @Override
    public void onRangingOpenFailed(SessionHandle sessionHandle, @RangingChangeReason int reason,
            PersistableBundle parameters) {
        synchronized (this) {
            if (!hasSession(sessionHandle)) {
                Log.w(TAG,
                        "onRangingOpenedFailed - received unexpected SessionHandle: "
                                + sessionHandle);
                return;
            }

            RangingSession session = mRangingSessionTable.get(sessionHandle);
            session.onRangingOpenFailed(convertToReason(reason), parameters);
            mRangingSessionTable.remove(sessionHandle);
        }
    }

    @Override
    public void onRangingReconfigured(SessionHandle sessionHandle, PersistableBundle parameters) {
        synchronized (this) {
            if (!hasSession(sessionHandle)) {
                Log.w(TAG,
                        "onRangingReconfigured - received unexpected SessionHandle: "
                                + sessionHandle);
                return;
            }

            RangingSession session = mRangingSessionTable.get(sessionHandle);
            session.onRangingReconfigured(parameters);
        }
    }

    @Override
    public void onRangingReconfigureFailed(SessionHandle sessionHandle,
            @RangingChangeReason int reason, PersistableBundle params) {
        synchronized (this) {
            if (!hasSession(sessionHandle)) {
                Log.w(TAG, "onRangingReconfigureFailed - received unexpected SessionHandle: "
                        + sessionHandle);
                return;
            }

            RangingSession session = mRangingSessionTable.get(sessionHandle);
            session.onRangingReconfigureFailed(convertToReason(reason), params);
        }
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
    public void onRangingStartFailed(SessionHandle sessionHandle, @RangingChangeReason int reason,
            PersistableBundle params) {
        synchronized (this) {
            if (!hasSession(sessionHandle)) {
                Log.w(TAG, "onRangingStartFailed - received unexpected SessionHandle: "
                        + sessionHandle);
                return;
            }

            RangingSession session = mRangingSessionTable.get(sessionHandle);
            session.onRangingStartFailed(convertToReason(reason), params);
        }
    }

    @Override
    public void onRangingStopped(SessionHandle sessionHandle) {
        synchronized (this) {
            if (!hasSession(sessionHandle)) {
                Log.w(TAG, "onRangingStopped - received unexpected SessionHandle: "
                        + sessionHandle);
                return;
            }

            RangingSession session = mRangingSessionTable.get(sessionHandle);
            session.onRangingStopped();
        }
    }

    @Override
    public void onRangingStopFailed(SessionHandle sessionHandle, @RangingChangeReason int reason,
            PersistableBundle parameters) {
        synchronized (this) {
            if (!hasSession(sessionHandle)) {
                Log.w(TAG, "onRangingStopFailed - received unexpected SessionHandle: "
                        + sessionHandle);
                return;
            }

            RangingSession session = mRangingSessionTable.get(sessionHandle);
            session.onRangingStopFailed(convertToReason(reason), parameters);
        }
    }

    @Override
    public void onRangingClosed(SessionHandle sessionHandle, @RangingChangeReason int reason,
            PersistableBundle params) {
        synchronized (this) {
            if (!hasSession(sessionHandle)) {
                Log.w(TAG, "onRangingClosed - received unexpected SessionHandle: " + sessionHandle);
                return;
            }

            RangingSession session = mRangingSessionTable.get(sessionHandle);
            session.onRangingClosed(convertToReason(reason), params);
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

    @RangingSession.Callback.Reason
    private static int convertToReason(@RangingChangeReason int reason) {
        switch (reason) {
            case RangingChangeReason.LOCAL_API:
                return RangingSession.Callback.REASON_LOCAL_REQUEST;

            case RangingChangeReason.MAX_SESSIONS_REACHED:
                return RangingSession.Callback.REASON_MAX_SESSIONS_REACHED;

            case RangingChangeReason.SYSTEM_POLICY:
                return RangingSession.Callback.REASON_SYSTEM_POLICY;

            case RangingChangeReason.REMOTE_REQUEST:
                return RangingSession.Callback.REASON_REMOTE_REQUEST;

            case RangingChangeReason.PROTOCOL_SPECIFIC:
                return RangingSession.Callback.REASON_PROTOCOL_SPECIFIC_ERROR;

            case RangingChangeReason.BAD_PARAMETERS:
                return RangingSession.Callback.REASON_BAD_PARAMETERS;

            case RangingChangeReason.UNKNOWN:
            default:
                return RangingSession.Callback.REASON_UNKNOWN;
        }
    }
}
