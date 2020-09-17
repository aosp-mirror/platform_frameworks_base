/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row;

import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_EXPANDED;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.widget.RemoteViews;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class NotifRemoteViewCacheImplTest extends SysuiTestCase {

    private NotifRemoteViewCacheImpl mNotifRemoteViewCache;
    private NotificationEntry mEntry;
    private NotifCollectionListener mEntryListener;
    @Mock private RemoteViews mRemoteViews;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mEntry = new NotificationEntryBuilder().build();

        CommonNotifCollection collection = mock(CommonNotifCollection.class);
        mNotifRemoteViewCache = new NotifRemoteViewCacheImpl(collection);
        ArgumentCaptor<NotifCollectionListener> entryListenerCaptor =
                ArgumentCaptor.forClass(NotifCollectionListener.class);
        verify(collection).addCollectionListener(entryListenerCaptor.capture());
        mEntryListener = entryListenerCaptor.getValue();
        mEntryListener.onEntryInit(mEntry);
    }

    @Test
    public void testPutCachedView() {
        // WHEN a notification's cached remote views is put in.
        mNotifRemoteViewCache.putCachedView(mEntry, FLAG_CONTENT_VIEW_CONTRACTED, mRemoteViews);

        // THEN the remote view is cached.
        assertTrue(mNotifRemoteViewCache.hasCachedView(mEntry, FLAG_CONTENT_VIEW_CONTRACTED));
        assertEquals(
                "Cached remote view is not the one we put in.",
                mRemoteViews,
                mNotifRemoteViewCache.getCachedView(mEntry, FLAG_CONTENT_VIEW_CONTRACTED));
    }

    @Test
    public void testRemoveCachedView() {
        // GIVEN a cache with a cached view.
        mNotifRemoteViewCache.putCachedView(mEntry, FLAG_CONTENT_VIEW_CONTRACTED, mRemoteViews);

        // WHEN we remove the cached view.
        mNotifRemoteViewCache.removeCachedView(mEntry, FLAG_CONTENT_VIEW_CONTRACTED);

        // THEN the remote view is not cached.
        assertFalse(mNotifRemoteViewCache.hasCachedView(mEntry, FLAG_CONTENT_VIEW_CONTRACTED));
    }

    @Test
    public void testClearCache() {
        // GIVEN a non-empty cache.
        mNotifRemoteViewCache.putCachedView(mEntry, FLAG_CONTENT_VIEW_CONTRACTED, mRemoteViews);
        mNotifRemoteViewCache.putCachedView(mEntry, FLAG_CONTENT_VIEW_EXPANDED, mRemoteViews);

        // WHEN we clear the cache.
        mNotifRemoteViewCache.clearCache(mEntry);

        // THEN the cache is empty.
        assertFalse(mNotifRemoteViewCache.hasCachedView(mEntry, FLAG_CONTENT_VIEW_CONTRACTED));
        assertFalse(mNotifRemoteViewCache.hasCachedView(mEntry, FLAG_CONTENT_VIEW_EXPANDED));
    }
}
