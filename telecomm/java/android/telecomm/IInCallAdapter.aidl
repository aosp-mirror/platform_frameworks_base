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
 * limitations under the License.
 */

package android.telecomm;

/**
 * Receives commands from {@link IInCallService} implementations which should be executed by
 * Telecomm. When Telecomm binds to a {@link IInCallService}, an instance of this class is given to
 * the in-call service through which it can manipulate live (active, dialing, ringing) calls. When
 * the in-call service is notified of new calls ({@link IInCallService#addCall}), it can use the
 * given call IDs to execute commands such as {@link #answerCall} for incoming calls or
 * {@link #disconnectCall} for active calls the user would like to end. Some commands are only
 * appropriate for calls in certain states; please consult each method for such limitations.
 * TODO(santoscordon): Needs more/better comments once the API is finalized.
 * TODO(santoscordon): Specify the adapter will stop functioning when there are no more calls.
 * TODO(santoscordon): Once we have proper "CallState" constant definitions, consider rewording
 * the javadoc to reference those states precisely.
 * @hide
 */
oneway interface IInCallAdapter {
    /**
     * Instructs Telecomm to answer the specified call.
     *
     * @param callId The identifier of the call to answer.
     */
    void answerCall(String callId);

    /**
     * Instructs Telecomm to reject the specified call.
     * TODO(santoscordon): Add reject-with-text-message parameter when that feature
     * is ported over.
     *
     * @param callId The identifier of the call to reject.
     */
    void rejectCall(String callId);

    /**
     * Instructs Telecomm to disconnect the specified call.
     *
     * @param callId The identifier of the call to disconnect.
     */
    void disconnectCall(String callId);
}
