/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.intelligence;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.os.Bundle;
import android.os.IBinder;
import android.view.autofill.AutofillId;
import android.view.autofill.IAutoFillManagerClient;

/**
 * Intelligence Manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
//TODO(b/111276913): rename once the final name is defined
public abstract class IntelligenceManagerInternal {

    /**
     * Checks whether the given {@code uid} owns the
     * {@link android.service.intelligence.SmartSuggestionsService} implementation associated with
     * the given {@code userId}.
     */
    public abstract boolean isIntelligenceServiceForUser(int uid, @UserIdInt int userId);

    /**
     * Notifies the intelligence service of new assist data for the given activity.
     *
     * @return {@code false} if there was no service set for the given user
     */
    public abstract boolean sendActivityAssistData(@UserIdInt int userId,
            @NonNull IBinder activityToken, @NonNull Bundle data);

    /**
     * Asks the intelligence service to provide Augmented Autofill for a given activity.
     *
     * @param userId user handle
     * @param client binder used to communicate with the activity that originated this request.
     * @param activityToken activity that originated this request.
     * @param autofillSessionId autofill session id (must be used on {@code client} calls.
     * @param focusedId id of the the field that triggered this request.
     *
     * @return {@code false} if the service cannot handle this request, {@code true} otherwise.
     * <b>NOTE: </b> it must return right away; typically it will return {@code false} if the
     * service is disabled (or the activity blacklisted).
     */
    public abstract AugmentedAutofillCallback requestAutofill(@UserIdInt int userId,
            @NonNull IAutoFillManagerClient client, @NonNull IBinder activityToken,
            int autofillSessionId, @NonNull AutofillId focusedId);

    /**
     * Callback used by the Autofill Session to communicate with the Augmented Autofill service.
     */
    public interface AugmentedAutofillCallback {
        // TODO(b/111330312): this method is calling when the Autofill session is destroyed, the
        // main reason being the cases where user tap HOME.
        // Right now it's completely destroying the UI, but we need to decide whether / how to
        // properly recover it later (for example, if the user switches back to the activity,
        // should it be restored? Right not it kind of is, because Autofill's Session trigger a
        // new FillRequest, which in turn triggers the Augmented Autofill request again)
        /**
         * Destroys the Autofill UI.
         */
        void destroy();
    }
}
