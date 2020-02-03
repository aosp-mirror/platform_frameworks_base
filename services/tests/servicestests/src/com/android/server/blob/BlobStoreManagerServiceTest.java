/*
 * Copyright 2020 The Android Open Source Project
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
package com.android.server.blob;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.blob.BlobHandle;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.util.LongSparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.blob.BlobStoreManagerService.Injector;
import com.android.server.blob.BlobStoreManagerService.SessionStateChangeListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class BlobStoreManagerServiceTest {
    private Context mContext;
    private Handler mHandler;
    private BlobStoreManagerService mService;

    private LongSparseArray<BlobStoreSession> mUserSessions;
    private ArrayMap<BlobHandle, BlobMetadata> mUserBlobs;

    private SessionStateChangeListener mStateChangeListener;

    private static final String TEST_PKG1 = "com.example1";
    private static final String TEST_PKG2 = "com.example2";
    private static final String TEST_PKG3 = "com.example3";

    private static final int TEST_UID1 = 10001;
    private static final int TEST_UID2 = 10002;
    private static final int TEST_UID3 = 10003;

    @Before
    public void setUp() {
        // Share classloader to allow package private access.
        System.setProperty("dexmaker.share_classloader", "true");

        mContext = InstrumentationRegistry.getTargetContext();
        mHandler = new TestHandler(Looper.getMainLooper());
        mService = new BlobStoreManagerService(mContext, new TestInjector());
        mUserSessions = new LongSparseArray<>();
        mUserBlobs = new ArrayMap<>();

        mService.addUserSessionsForTest(mUserSessions, UserHandle.myUserId());
        mService.addUserBlobsForTest(mUserBlobs, UserHandle.myUserId());

        mStateChangeListener = mService.new SessionStateChangeListener();
    }

    @Test
    public void testHandlePackageRemoved() throws Exception {
        // Setup sessions
        final File sessionFile1 = mock(File.class);
        final long sessionId1 = 11;
        final BlobStoreSession session1 = createBlobStoreSessionMock(TEST_PKG1, TEST_UID1,
                sessionId1, sessionFile1);
        mUserSessions.append(sessionId1, session1);

        final File sessionFile2 = mock(File.class);
        final long sessionId2 = 25;
        final BlobStoreSession session2 = createBlobStoreSessionMock(TEST_PKG2, TEST_UID2,
                sessionId2, sessionFile2);
        mUserSessions.append(sessionId2, session2);

        final File sessionFile3 = mock(File.class);
        final long sessionId3 = 37;
        final BlobStoreSession session3 = createBlobStoreSessionMock(TEST_PKG3, TEST_UID3,
                sessionId3, sessionFile3);
        mUserSessions.append(sessionId3, session3);

        final File sessionFile4 = mock(File.class);
        final long sessionId4 = 48;
        final BlobStoreSession session4 = createBlobStoreSessionMock(TEST_PKG1, TEST_UID1,
                sessionId4, sessionFile4);
        mUserSessions.append(sessionId4, session4);

        // Setup blobs
        final File blobFile1 = mock(File.class);
        final BlobHandle blobHandle1 = BlobHandle.createWithSha256("digest1".getBytes(),
                "label1", System.currentTimeMillis(), "tag1");
        final BlobMetadata blobMetadata1 = createBlobMetadataMock(blobFile1, true);
        mUserBlobs.put(blobHandle1, blobMetadata1);

        final File blobFile2 = mock(File.class);
        final BlobHandle blobHandle2 = BlobHandle.createWithSha256("digest2".getBytes(),
                "label2", System.currentTimeMillis(), "tag2");
        final BlobMetadata blobMetadata2 = createBlobMetadataMock(blobFile2, false);
        mUserBlobs.put(blobHandle2, blobMetadata2);

        // Invoke test method
        mService.handlePackageRemoved(TEST_PKG1, TEST_UID1);

        // Verify sessions are removed
        verify(sessionFile1).delete();
        verify(sessionFile2, never()).delete();
        verify(sessionFile3, never()).delete();
        verify(sessionFile4).delete();

        assertThat(mUserSessions.size()).isEqualTo(2);
        assertThat(mUserSessions.get(sessionId1)).isNull();
        assertThat(mUserSessions.get(sessionId2)).isNotNull();
        assertThat(mUserSessions.get(sessionId3)).isNotNull();
        assertThat(mUserSessions.get(sessionId4)).isNull();

        // Verify blobs are removed
        verify(blobMetadata1).removeCommitter(TEST_PKG1, TEST_UID1);
        verify(blobMetadata1).removeLeasee(TEST_PKG1, TEST_UID1);
        verify(blobMetadata2).removeCommitter(TEST_PKG1, TEST_UID1);
        verify(blobMetadata2).removeLeasee(TEST_PKG1, TEST_UID1);

        verify(blobFile1, never()).delete();
        verify(blobFile2).delete();

        assertThat(mUserBlobs.size()).isEqualTo(1);
        assertThat(mUserBlobs.get(blobHandle1)).isNotNull();
        assertThat(mUserBlobs.get(blobHandle2)).isNull();
    }

    private BlobStoreSession createBlobStoreSessionMock(String ownerPackageName, int ownerUid,
            long sessionId, File sessionFile) {
        final BlobStoreSession session = mock(BlobStoreSession.class);
        when(session.getOwnerPackageName()).thenReturn(ownerPackageName);
        when(session.getOwnerUid()).thenReturn(ownerUid);
        when(session.getSessionId()).thenReturn(sessionId);
        when(session.getSessionFile()).thenReturn(sessionFile);
        return session;
    }

    private BlobMetadata createBlobMetadataMock(File blobFile, boolean hasLeases) {
        final BlobMetadata blobMetadata = mock(BlobMetadata.class);
        when(blobMetadata.getBlobFile()).thenReturn(blobFile);
        when(blobMetadata.hasLeases()).thenReturn(hasLeases);
        return blobMetadata;
    }

    private class TestHandler extends Handler {
        TestHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void dispatchMessage(Message msg) {
            // Ignore all messages
        }
    }

    private class TestInjector extends Injector {
        @Override
        public Handler initializeMessageHandler() {
            return mHandler;
        }
    }
}
