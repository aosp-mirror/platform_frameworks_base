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
 * limitations under the License.
 */

package android.service.autofill;

import android.os.Bundle;

/**
 * Intermediator between apps being auto-filled and auto-fill service implementations.
 *
 * {@hide}
 */
interface IAutoFillManagerService {

    /**
     * Starts an auto-fill session for the top activities for a given user.
     *
     * It's used to start a new session from system affordances.
     *
     * @param userId user handle.
     * @param args the bundle to pass as arguments to the voice interaction session.
     * @param flags flags indicating optional session behavior.
     * @param activityToken optional token of activity that needs to be on top.
     *
     * @return session token, or null if session was not created (for example, if the activity's
     *         user does not have an auto-fill service associated with).
     */
     // TODO: pass callback providing an onAutoFill() method
    String startSession(int userId, in Bundle args, int flags, IBinder activityToken);

    /**
     * Finishes an auto-fill session.
     *
     * @param userId user handle.
     * @param token session token.
     *
     * @return true if session existed and was finished.
     */
    boolean finishSession(int userId, String token);

}
