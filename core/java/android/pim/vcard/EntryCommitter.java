/*
 * Copyright (C) 2009 The Android Open Source Project
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
package android.pim.vcard;

import android.content.AbstractSyncableContentProvider;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.IContentProvider;
import android.provider.Contacts;
import android.util.Log;

/**
 * EntryHandler implementation which commits the entry to Contacts Provider 
 */
public class EntryCommitter implements EntryHandler {
    public static String LOG_TAG = "vcard.EntryComitter";
    
    private ContentResolver mContentResolver;
    
    // Ideally, this should be ContactsProvider but it seems Class loader cannot find it,
    // even when it is subclass of ContactsProvider...
    private AbstractSyncableContentProvider mProvider;
    private long mMyContactsGroupId;
    
    private long mTimeToCommit;
    
    public EntryCommitter(ContentResolver resolver) {
        mContentResolver = resolver;
        
        tryGetOriginalProvider();
    }
    
    public void onFinal() {
        if (VCardConfig.showPerformanceLog()) {
            Log.d(LOG_TAG,
                    String.format("time to commit entries: %ld ms", mTimeToCommit));
        }
    }
    
    private void tryGetOriginalProvider() {
        final ContentResolver resolver = mContentResolver;
        
        if ((mMyContactsGroupId = Contacts.People.tryGetMyContactsGroupId(resolver)) == 0) {
            Log.e(LOG_TAG, "Could not get group id of MyContact");
            return;
        }

        IContentProvider iProviderForName = resolver.acquireProvider(Contacts.CONTENT_URI);
        ContentProvider contentProvider =
            ContentProvider.coerceToLocalContentProvider(iProviderForName);
        if (contentProvider == null) {
            Log.e(LOG_TAG, "Fail to get ContentProvider object.");
            return;
        }
        
        if (!(contentProvider instanceof AbstractSyncableContentProvider)) {
            Log.e(LOG_TAG,
                    "Acquired ContentProvider object is not AbstractSyncableContentProvider.");
            return;
        }
        
        mProvider = (AbstractSyncableContentProvider)contentProvider; 
    }
    
    public void onEntryCreated(final ContactStruct contactStruct) {
        long start = System.currentTimeMillis();
        if (mProvider != null) {
            contactStruct.pushIntoAbstractSyncableContentProvider(
                    mProvider, mMyContactsGroupId);
        } else {
            contactStruct.pushIntoContentResolver(mContentResolver);
        }
        mTimeToCommit += System.currentTimeMillis() - start;
    }
}