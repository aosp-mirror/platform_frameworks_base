/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;
import android.test.mock.MockContext;
import android.test.mock.MockContentResolver;
import android.provider.Sync;

public class SyncStorageEngineTest extends AndroidTestCase {

    /**
     * Test that we handle the case of a history row being old enough to purge before the
     * correcponding sync is finished. This can happen if the clock changes while we are syncing.
     */
    public void testPurgeActiveSync() throws Exception {
        final String account = "a@example.com";
        final String authority = "testprovider";

        MockContentResolver mockResolver = new MockContentResolver();

        SyncStorageEngine engine = SyncStorageEngine.newTestInstance(
                new TestContext(mockResolver, getContext()));

        long time0 = 1000;
        long historyId = engine.insertStartSyncEvent(
                account, authority, time0, Sync.History.SOURCE_LOCAL);
        long time1 = time0 + SyncStorageEngine.MILLIS_IN_4WEEKS * 2;
        engine.stopSyncEvent(historyId, time1 - time0, "yay", 0, 0);
    }
}

class TestContext extends ContextWrapper {

    ContentResolver mResolver;

    public TestContext(ContentResolver resolver, Context realContext) {
        super(new RenamingDelegatingContext(new MockContext(), realContext, "test."));
        mResolver = resolver;
    }

    @Override
    public void enforceCallingOrSelfPermission(String permission, String message) {
    }


    @Override
    public ContentResolver getContentResolver() {
        return mResolver;
    }
}
