/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.content;
import android.os.Bundle;
import android.content.ISyncContext;

/**
 * Interface to define an anonymous service that is extended by developers
 * in order to perform anonymous syncs (syncs without an Account or Content
 * Provider specified). See {@link android.content.AbstractThreadedSyncAdapter}.
 * {@hide}
 */
oneway interface ISyncServiceAdapter {

    /**
     * Initiate a sync. SyncAdapter-specific parameters may be specified in
     * extras, which is guaranteed to not be null.
     *
     * @param syncContext the ISyncContext used to indicate the progress of the sync. When
     *   the sync is finished (successfully or not) ISyncContext.onFinished() must be called.
     * @param extras SyncAdapter-specific parameters.
     *
     */
    void startSync(ISyncContext syncContext, in Bundle extras);

    /**
     * Cancel the currently ongoing sync.
     */
    void cancelSync(ISyncContext syncContext);

}
