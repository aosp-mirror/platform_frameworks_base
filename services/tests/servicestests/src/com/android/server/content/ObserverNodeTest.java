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

package com.android.server.content;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.database.IContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.ArraySet;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.content.ContentService.ObserverCollector;
import com.android.server.content.ContentService.ObserverNode;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;

import java.util.Arrays;

/**
 * atest FrameworksServicesTests:com.android.server.content.ObserverNodeTest
 */
@RunWith(AndroidJUnit4.class)
public class ObserverNodeTest {
    static class TestObserver extends ContentObserver {
        public TestObserver() {
            super(new Handler(Looper.getMainLooper()));
        }
    }

    @Test
    public void testUri() {
        final int myUserHandle = UserHandle.myUserId();

        ObserverNode root = new ObserverNode("");
        Uri[] uris = new Uri[] {
            Uri.parse("content://c/a/"),
            Uri.parse("content://c/"),
            Uri.parse("content://x/"),
            Uri.parse("content://c/b/"),
            Uri.parse("content://c/a/a1/1/"),
            Uri.parse("content://c/a/a1/2/"),
            Uri.parse("content://c/b/1/"),
            Uri.parse("content://c/b/2/"),
        };

        int[] nums = new int[] {4, 7, 1, 4, 2, 2, 3, 3};

        // special case
        root.addObserverLocked(uris[0], new TestObserver().getContentObserver(), false, root,
                0, 0, myUserHandle);
        for(int i = 1; i < uris.length; i++) {
            root.addObserverLocked(uris[i], new TestObserver().getContentObserver(), true, root,
                    0, 0, myUserHandle);
        }

        for (int i = nums.length - 1; i >=0; --i) {
            final ObserverCollector collector = mock(ObserverCollector.class);
            root.collectObserversLocked(uris[i], 0, null, false, 0, myUserHandle, collector);
            verify(collector, times(nums[i])).collect(
                    any(), anyInt(), anyBoolean(), any(), anyInt(), anyInt());
        }
    }

    @Test
    public void testUriNotNotify() {
        final int myUserHandle = UserHandle.myUserId();

        ObserverNode root = new ObserverNode("");
        Uri[] uris = new Uri[] {
            Uri.parse("content://c/"),
            Uri.parse("content://x/"),
            Uri.parse("content://c/a/"),
            Uri.parse("content://c/b/"),
            Uri.parse("content://c/a/1/"),
            Uri.parse("content://c/a/2/"),
            Uri.parse("content://c/b/1/"),
            Uri.parse("content://c/b/2/"),
        };
        int[] nums = new int[] {7, 1, 3, 3, 1, 1, 1, 1};

        for(int i = 0; i < uris.length; i++) {
            root.addObserverLocked(uris[i], new TestObserver().getContentObserver(), false, root,
                    0, 0, myUserHandle);
        }

        for (int i = uris.length - 1; i >=0; --i) {
            final ObserverCollector collector = mock(ObserverCollector.class);
            root.collectObserversLocked(uris[i], 0, null, false, 0, myUserHandle, collector);
            verify(collector, times(nums[i])).collect(
                    any(), anyInt(), anyBoolean(), any(), anyInt(), anyInt());
        }
    }

    @Test
    public void testCluster() throws Exception {
        final int myUserHandle = UserHandle.myUserId();

        // Assume everything is foreground during our test
        final ActivityManagerInternal ami = mock(ActivityManagerInternal.class);
        when(ami.getUidProcessState(anyInt()))
                .thenReturn(ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.addService(ActivityManagerInternal.class, ami);

        final IContentObserver observer = mock(IContentObserver.class);
        when(observer.asBinder()).thenReturn(new Binder());

        final ObserverNode root = new ObserverNode("");
        root.addObserverLocked(Uri.parse("content://authority/"), observer,
                true, root, 0, 1000, myUserHandle);

        final ObserverCollector collector = new ObserverCollector();
        root.collectObserversLocked(Uri.parse("content://authority/1"), 0, null, false,
                0, myUserHandle, collector);
        root.collectObserversLocked(Uri.parse("content://authority/1"), 0, null, false,
                ContentResolver.NOTIFY_INSERT, myUserHandle, collector);
        root.collectObserversLocked(Uri.parse("content://authority/2"), 0, null, false,
                ContentResolver.NOTIFY_INSERT, myUserHandle, collector);
        root.collectObserversLocked(Uri.parse("content://authority/2"), 0, null, false,
                ContentResolver.NOTIFY_UPDATE, myUserHandle, collector);
        collector.dispatch();

        // We should only cluster when all other arguments are equal
        verify(observer).onChangeEtc(eq(false), argThat(new UriSetMatcher(
                        Uri.parse("content://authority/1"))),
                eq(0), anyInt());
        verify(observer).onChangeEtc(eq(false), argThat(new UriSetMatcher(
                        Uri.parse("content://authority/1"),
                        Uri.parse("content://authority/2"))),
                eq(ContentResolver.NOTIFY_INSERT), anyInt());
        verify(observer).onChangeEtc(eq(false), argThat(new UriSetMatcher(
                        Uri.parse("content://authority/2"))),
                eq(ContentResolver.NOTIFY_UPDATE), anyInt());
    }

    private static class UriSetMatcher implements ArgumentMatcher<Uri[]> {
        private final ArraySet<Uri> uris;

        public UriSetMatcher(Uri... uris) {
            this.uris = new ArraySet<>(Arrays.asList(uris));
        }

        @Override
        public boolean matches(Uri[] uris) {
            final ArraySet<Uri> test = new ArraySet<>(Arrays.asList(uris));
            return this.uris.equals(test);
        }
    }
}
