/*
 * Copyright (C) 2015 The Android Open Source Project
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

/**
 * A {@link Service} which runs the internal implementation of {@link AbstractThreadedSyncAdapter},
 * syncing voicemails to and from a visual voicemail server.
 */

package android.telecom;

import android.accounts.Account;
import android.app.Service;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.VoicemailContract;

import java.util.ArrayList;
import java.util.List;

/**
 * A service to run the VvmSyncAdapter.
 */
public class VvmSyncService extends Service {
    // Storage for an instance of the sync adapter
    private static VvmSyncAdapter sSyncAdapter = null;
    // Object to use as a thread-safe lock
    private static final Object sSyncAdapterLock = new Object();

    @Override
    public void onCreate() {
        synchronized (sSyncAdapterLock) {
            if (sSyncAdapter == null) {
                sSyncAdapter = new VvmSyncAdapter(getApplicationContext(), true);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return sSyncAdapter.getSyncAdapterBinder();
    }

    public class VvmSyncAdapter extends AbstractThreadedSyncAdapter {
        /** The key to get the extra designating the type of sync action to perform. */
        public final static String SYNC_EXTRA_CODE = "sync_extra_code";
        /** The key to get the {@code Voicemail} object for a new voicemail. */
        public final static String NEW_VOICEMAIL_DATA = "extra_new_voicemail_data";
        /** Sync a new voicemail from the carrier to the device. */
        public final static int SYNC_EXTRA_NEW_VOICEMAIL = 1;
        /** Sync all voicemails because the mailbox was changed remotely. */
        public final static int SYNC_EXTRA_MAILBOX_UPDATE = 2;

        private final Context mContext;

        public VvmSyncAdapter(Context context, boolean autoInitialize) {
            super(context, autoInitialize);
            mContext = context;
        }

        @Override
        public void onPerformSync(Account account, Bundle extras, String authority,
                ContentProviderClient provider, SyncResult syncResult) {
            if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, false)) {
                // notify server that voicemail has been changed
                syncToServer();
            }

            final int syncAction = extras.getInt(SYNC_EXTRA_CODE);
            switch (syncAction) {
                /** sync from carrier */
                case SYNC_EXTRA_NEW_VOICEMAIL:
                    // Log new voicemail in voicemail provider.
                    Voicemail newVoicemail = extras.getParcelable(NEW_VOICEMAIL_DATA);
                    VoicemailContract.Voicemails.insert(mContext, newVoicemail);
                    break;
                case SYNC_EXTRA_MAILBOX_UPDATE:
                    // Clear and reload all voicemails because the mailbox was updated remotely.
                    VoicemailContract.Voicemails.deleteAll(mContext);
                    List<Voicemail> voicemails = downloadVoicemails();
                    VoicemailContract.Voicemails.insert(mContext, voicemails);
                    break;
                 default:
                     break;
            }
        }

        /** Subclasses should implement this method to sync changes to server */
        protected void syncToServer() { }

        /** Subclasses should implement this method to download voicemails */
        protected List<Voicemail> downloadVoicemails() {
            return new ArrayList<Voicemail>();
        }
    }
}
