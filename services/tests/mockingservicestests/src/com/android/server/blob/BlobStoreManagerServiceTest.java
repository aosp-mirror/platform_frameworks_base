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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doCallRealMethod;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.server.blob.BlobStoreConfig.DeviceConfigProperties.SESSION_EXPIRY_TIMEOUT_MS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.blob.BlobHandle;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.util.LongSparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.blob.BlobStoreManagerService.Injector;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.File;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class BlobStoreManagerServiceTest {
    private Context mContext;
    private Handler mHandler;
    private BlobStoreManagerService mService;

    private MockitoSession mMockitoSession;

    @Mock
    private File mBlobsDir;

    private LongSparseArray<BlobStoreSession> mUserSessions;

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

        mMockitoSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .mockStatic(BlobStoreConfig.class)
                .startMocking();

        doReturn(mBlobsDir).when(() -> BlobStoreConfig.getBlobsDir());
        doReturn(true).when(mBlobsDir).exists();
        doReturn(new File[0]).when(mBlobsDir).listFiles();
        doReturn(true).when(() -> BlobStoreConfig.hasLeaseWaitTimeElapsed(anyLong()));
        doCallRealMethod().when(() -> BlobStoreConfig.hasSessionExpired(anyLong()));

        mContext = InstrumentationRegistry.getTargetContext();
        mHandler = new TestHandler(Looper.getMainLooper());
        mService = new BlobStoreManagerService(mContext, new TestInjector());
        mUserSessions = new LongSparseArray<>();

        mService.addUserSessionsForTest(mUserSessions, UserHandle.myUserId());
    }

    @After
    public void tearDown() {
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
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
        final long blobId1 = 978;
        final File blobFile1 = mock(File.class);
        final BlobHandle blobHandle1 = BlobHandle.createWithSha256("digest1".getBytes(),
                "label1", System.currentTimeMillis() + 10000, "tag1");
        final BlobMetadata blobMetadata1 = createBlobMetadataMock(blobId1, blobFile1,
                blobHandle1, true /* hasLeases */);
        doReturn(true).when(blobMetadata1).isACommitter(TEST_PKG1, TEST_UID1);
        addBlob(blobHandle1, blobMetadata1);

        final long blobId2 = 347;
        final File blobFile2 = mock(File.class);
        final BlobHandle blobHandle2 = BlobHandle.createWithSha256("digest2".getBytes(),
                "label2", System.currentTimeMillis() + 20000, "tag2");
        final BlobMetadata blobMetadata2 = createBlobMetadataMock(blobId2, blobFile2,
                blobHandle2, false /* hasLeases */);
        doReturn(false).when(blobMetadata2).isACommitter(TEST_PKG1, TEST_UID1);
        addBlob(blobHandle2, blobMetadata2);

        final long blobId3 = 49875;
        final File blobFile3 = mock(File.class);
        final BlobHandle blobHandle3 = BlobHandle.createWithSha256("digest3".getBytes(),
                "label3", System.currentTimeMillis() - 1000, "tag3");
        final BlobMetadata blobMetadata3 = createBlobMetadataMock(blobId3, blobFile3,
                blobHandle3, true /* hasLeases */);
        doReturn(true).when(blobMetadata3).isACommitter(TEST_PKG1, TEST_UID1);
        addBlob(blobHandle3, blobMetadata3);

        mService.addActiveIdsForTest(sessionId1, sessionId2, sessionId3, sessionId4,
                blobId1, blobId2, blobId3);

        // Invoke test method
        mService.handlePackageRemoved(TEST_PKG1, TEST_UID1);

        // Verify sessions are removed
        verify(session1).destroy();
        verify(session2, never()).destroy();
        verify(session3, never()).destroy();
        verify(session4).destroy();

        assertThat(mUserSessions.size()).isEqualTo(2);
        assertThat(mUserSessions.get(sessionId1)).isNull();
        assertThat(mUserSessions.get(sessionId2)).isNotNull();
        assertThat(mUserSessions.get(sessionId3)).isNotNull();
        assertThat(mUserSessions.get(sessionId4)).isNull();

        // Verify blobs are removed
        verify(blobMetadata1).removeCommitter(TEST_PKG1, TEST_UID1);
        verify(blobMetadata1).removeLeasee(TEST_PKG1, TEST_UID1);
        verify(blobMetadata2, never()).removeCommitter(TEST_PKG1, TEST_UID1);
        verify(blobMetadata2).removeLeasee(TEST_PKG1, TEST_UID1);
        verify(blobMetadata3).removeCommitter(TEST_PKG1, TEST_UID1);
        verify(blobMetadata3).removeLeasee(TEST_PKG1, TEST_UID1);

        verify(blobMetadata1, never()).destroy();
        verify(blobMetadata2).destroy();
        verify(blobMetadata3).destroy();

        assertThat(mService.getBlobsCountForTest()).isEqualTo(1);
        assertThat(mService.getBlobForTest(blobHandle1)).isNotNull();
        assertThat(mService.getBlobForTest(blobHandle2)).isNull();
        assertThat(mService.getBlobForTest(blobHandle3)).isNull();

        assertThat(mService.getActiveIdsForTest()).containsExactly(
                sessionId2, sessionId3, blobId1);
        assertThat(mService.getKnownIdsForTest()).containsExactly(
                sessionId1, sessionId2, sessionId3, sessionId4, blobId1, blobId2, blobId3);
    }

    @Test
    public void testHandleIdleMaintenance_deleteUnknownBlobs() throws Exception {
        // Setup blob files
        final long testId1 = 286;
        final File file1 = mock(File.class);
        doReturn(String.valueOf(testId1)).when(file1).getName();
        final long testId2 = 349;
        final File file2 = mock(File.class);
        doReturn(String.valueOf(testId2)).when(file2).getName();
        final long testId3 = 7355;
        final File file3 = mock(File.class);
        doReturn(String.valueOf(testId3)).when(file3).getName();

        doReturn(new File[] {file1, file2, file3}).when(mBlobsDir).listFiles();
        mService.addActiveIdsForTest(testId1, testId3);

        // Invoke test method
        mService.handleIdleMaintenanceLocked();

        // Verify unknown blobs are deleted
        verify(file1, never()).delete();
        verify(file2).delete();
        verify(file3, never()).delete();
    }

    @Test
    public void testHandleIdleMaintenance_deleteStaleSessions() throws Exception {
        // Setup sessions
        final File sessionFile1 = mock(File.class);
        doReturn(System.currentTimeMillis() - SESSION_EXPIRY_TIMEOUT_MS + 1000)
                .when(sessionFile1).lastModified();
        final long sessionId1 = 342;
        final BlobHandle blobHandle1 = BlobHandle.createWithSha256("digest1".getBytes(),
                "label1", System.currentTimeMillis() - 1000, "tag1");
        final BlobStoreSession session1 = createBlobStoreSessionMock(TEST_PKG1, TEST_UID1,
                sessionId1, sessionFile1, blobHandle1);
        mUserSessions.append(sessionId1, session1);

        final File sessionFile2 = mock(File.class);
        doReturn(System.currentTimeMillis() - 20000)
                .when(sessionFile2).lastModified();
        final long sessionId2 = 4597;
        final BlobHandle blobHandle2 = BlobHandle.createWithSha256("digest2".getBytes(),
                "label2", System.currentTimeMillis() + 20000, "tag2");
        final BlobStoreSession session2 = createBlobStoreSessionMock(TEST_PKG2, TEST_UID2,
                sessionId2, sessionFile2, blobHandle2);
        mUserSessions.append(sessionId2, session2);

        final File sessionFile3 = mock(File.class);
        doReturn(System.currentTimeMillis() - SESSION_EXPIRY_TIMEOUT_MS - 2000)
                .when(sessionFile3).lastModified();
        final long sessionId3 = 9484;
        final BlobHandle blobHandle3 = BlobHandle.createWithSha256("digest3".getBytes(),
                "label3", System.currentTimeMillis() + 30000, "tag3");
        final BlobStoreSession session3 = createBlobStoreSessionMock(TEST_PKG3, TEST_UID3,
                sessionId3, sessionFile3, blobHandle3);
        mUserSessions.append(sessionId3, session3);

        mService.addActiveIdsForTest(sessionId1, sessionId2, sessionId3);

        // Invoke test method
        mService.handleIdleMaintenanceLocked();

        // Verify stale sessions are removed
        verify(session1).destroy();
        verify(session2, never()).destroy();
        verify(session3).destroy();

        assertThat(mUserSessions.size()).isEqualTo(1);
        assertThat(mUserSessions.get(sessionId2)).isNotNull();

        assertThat(mService.getActiveIdsForTest()).containsExactly(sessionId2);
        assertThat(mService.getKnownIdsForTest()).containsExactly(
                sessionId1, sessionId2, sessionId3);
    }

    @Test
    public void testHandleIdleMaintenance_deleteStaleBlobs() throws Exception {
        // Setup blobs
        final long blobId1 = 3489;
        final File blobFile1 = mock(File.class);
        final BlobHandle blobHandle1 = BlobHandle.createWithSha256("digest1".getBytes(),
                "label1", System.currentTimeMillis() - 2000, "tag1");
        final BlobMetadata blobMetadata1 = createBlobMetadataMock(blobId1, blobFile1, blobHandle1,
                true /* hasLeases */);
        addBlob(blobHandle1, blobMetadata1);

        final long blobId2 = 78974;
        final File blobFile2 = mock(File.class);
        final BlobHandle blobHandle2 = BlobHandle.createWithSha256("digest2".getBytes(),
                "label2", System.currentTimeMillis() + 30000, "tag2");
        final BlobMetadata blobMetadata2 = createBlobMetadataMock(blobId2, blobFile2, blobHandle2,
                true /* hasLeases */);
        addBlob(blobHandle2, blobMetadata2);

        final long blobId3 = 97;
        final File blobFile3 = mock(File.class);
        final BlobHandle blobHandle3 = BlobHandle.createWithSha256("digest3".getBytes(),
                "label3", System.currentTimeMillis() + 4400000, "tag3");
        final BlobMetadata blobMetadata3 = createBlobMetadataMock(blobId3, blobFile3, blobHandle3,
                false /* hasLeases */);
        addBlob(blobHandle3, blobMetadata3);

        mService.addActiveIdsForTest(blobId1, blobId2, blobId3);

        // Invoke test method
        mService.handleIdleMaintenanceLocked();

        // Verify stale blobs are removed
        verify(blobMetadata1).destroy();
        verify(blobMetadata2, never()).destroy();
        verify(blobMetadata3).destroy();

        assertThat(mService.getBlobsCountForTest()).isEqualTo(1);
        assertThat(mService.getBlobForTest(blobHandle2)).isNotNull();

        assertThat(mService.getActiveIdsForTest()).containsExactly(blobId2);
        assertThat(mService.getKnownIdsForTest()).containsExactly(blobId1, blobId2, blobId3);
    }

    @Test
    public void testGetTotalUsageBytes() throws Exception {
        // Setup blobs
        final BlobMetadata blobMetadata1 = mock(BlobMetadata.class);
        final long size1 = 4567;
        doReturn(size1).when(blobMetadata1).getSize();
        doReturn(true).when(blobMetadata1).isALeasee(TEST_PKG1, TEST_UID1);
        doReturn(true).when(blobMetadata1).isALeasee(TEST_PKG2, TEST_UID2);
        addBlob(mock(BlobHandle.class), blobMetadata1);

        final BlobMetadata blobMetadata2 = mock(BlobMetadata.class);
        final long size2 = 89475;
        doReturn(size2).when(blobMetadata2).getSize();
        doReturn(false).when(blobMetadata2).isALeasee(TEST_PKG1, TEST_UID1);
        doReturn(true).when(blobMetadata2).isALeasee(TEST_PKG2, TEST_UID2);
        addBlob(mock(BlobHandle.class), blobMetadata2);

        final BlobMetadata blobMetadata3 = mock(BlobMetadata.class);
        final long size3 = 328732;
        doReturn(size3).when(blobMetadata3).getSize();
        doReturn(true).when(blobMetadata3).isALeasee(TEST_PKG1, TEST_UID1);
        doReturn(false).when(blobMetadata3).isALeasee(TEST_PKG2, TEST_UID2);
        addBlob(mock(BlobHandle.class), blobMetadata3);

        // Verify usage is calculated correctly
        assertThat(mService.getTotalUsageBytesLocked(TEST_UID1, TEST_PKG1))
                .isEqualTo(size1 + size3);
        assertThat(mService.getTotalUsageBytesLocked(TEST_UID2, TEST_PKG2))
                .isEqualTo(size1 + size2);
    }

    private BlobStoreSession createBlobStoreSessionMock(String ownerPackageName, int ownerUid,
            long sessionId, File sessionFile) {
        return createBlobStoreSessionMock(ownerPackageName, ownerUid, sessionId, sessionFile,
                mock(BlobHandle.class));
    }
    private BlobStoreSession createBlobStoreSessionMock(String ownerPackageName, int ownerUid,
            long sessionId, File sessionFile, BlobHandle blobHandle) {
        final BlobStoreSession session = mock(BlobStoreSession.class);
        doReturn(ownerPackageName).when(session).getOwnerPackageName();
        doReturn(ownerUid).when(session).getOwnerUid();
        doReturn(sessionId).when(session).getSessionId();
        doReturn(sessionFile).when(session).getSessionFile();
        doReturn(blobHandle).when(session).getBlobHandle();
        doCallRealMethod().when(session).isExpired();
        return session;
    }

    private BlobMetadata createBlobMetadataMock(long blobId, File blobFile,
            BlobHandle blobHandle, boolean hasValidLeases) {
        final BlobMetadata blobMetadata = mock(BlobMetadata.class);
        doReturn(blobId).when(blobMetadata).getBlobId();
        doReturn(blobFile).when(blobMetadata).getBlobFile();
        doReturn(hasValidLeases).when(blobMetadata).hasValidLeases();
        doReturn(blobHandle).when(blobMetadata).getBlobHandle();
        doCallRealMethod().when(blobMetadata).shouldBeDeleted(anyBoolean());
        doReturn(true).when(blobMetadata).hasLeaseWaitTimeElapsedForAll();
        return blobMetadata;
    }

    private void addBlob(BlobHandle blobHandle, BlobMetadata blobMetadata) {
        doReturn(blobHandle).when(blobMetadata).getBlobHandle();
        mService.addBlobLocked(blobMetadata);
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

        @Override
        public Handler getBackgroundHandler() {
            return mHandler;
        }
    }
}
