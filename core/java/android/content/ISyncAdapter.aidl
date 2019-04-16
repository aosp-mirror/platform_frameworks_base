/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.accounts.Account;
import android.os.Bundle;
import android.content.ISyncContext;
import android.content.ISyncAdapterUnsyncableAccountCallback;

/**
 * Interface used to control the sync activity on a SyncAdapter
 * @hide
 */
oneway interface ISyncAdapter {
    /**
     * Called before {@link #startSync}. This allows the adapter to defer syncs until the
     * adapter is ready for the account
     *
     * @param cb If called back with {@code false} accounts are not synced.
     */
    @UnsupportedAppUsage
    void onUnsyncableAccount(ISyncAdapterUnsyncableAccountCallback cb);

    /**
     * Initiate a sync for this account. SyncAdapter-specific parameters may
     * be specified in extras, which is guaranteed to not be null.
     *
     * @param syncContext the ISyncContext used to indicate the progress of the sync. When
     *   the sync is finished (successfully or not) ISyncContext.onFinished() must be called.
     * @param authority the authority that should be synced
     * @param account the account that should be synced
     * @param extras SyncAdapter-specific parameters
     */
    @UnsupportedAppUsage
    void startSync(ISyncContext syncContext, String authority,
      in Account account, in Bundle extras);

    /**
     * Cancel the most recently initiated sync. Due to race conditions, this may arrive
     * after the ISyncContext.onFinished() for that sync was called.
     * @param syncContext the ISyncContext that was passed to {@link #startSync}
     */
    @UnsupportedAppUsage
    void cancelSync(ISyncContext syncContext);
}
