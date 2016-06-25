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
 * limitations under the License.
 */

package com.android.documentsui.services;

import static com.android.documentsui.services.FileOperationService.OPERATION_COPY;
import static com.android.documentsui.services.FileOperationService.OPERATION_DELETE;
import static com.android.documentsui.services.FileOperations.createBaseIntent;
import static com.android.documentsui.services.FileOperations.createJobId;

import static com.google.android.collect.Lists.newArrayList;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.test.ServiceTestCase;
import android.test.suitebuilder.annotation.MediumTest;

import com.android.documentsui.ClipDetails;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.services.Job.Listener;
import com.android.documentsui.testing.ClipDetailsFactory;
import com.android.documentsui.testing.TestHandler;
import com.android.documentsui.testing.TestScheduledExecutorService;

import java.util.ArrayList;
import java.util.List;

@MediumTest
public class FileOperationServiceTest extends ServiceTestCase<FileOperationService> {

    private static final Uri SRC_PARENT =
            Uri.parse("content://com.android.documentsui.testing/parent");
    private static final DocumentInfo ALPHA_DOC = createDoc("alpha");
    private static final DocumentInfo BETA_DOC = createDoc("alpha");
    private static final DocumentInfo GAMMA_DOC = createDoc("gamma");
    private static final DocumentInfo DELTA_DOC = createDoc("delta");

    private FileOperationService mService;
    private TestScheduledExecutorService mExecutor;
    private TestScheduledExecutorService mDeletionExecutor;
    private TestHandler mHandler;
    private TestJobFactory mJobFactory;

    public FileOperationServiceTest() {
        super(FileOperationService.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        setupService();  // must be called first for our test setup to work correctly.

        mExecutor = new TestScheduledExecutorService();
        mDeletionExecutor = new TestScheduledExecutorService();
        mHandler = new TestHandler();
        mJobFactory = new TestJobFactory();

        // Install test doubles.
        mService = getService();

        assertNull(mService.executor);
        mService.executor = mExecutor;

        assertNull(mService.deletionExecutor);
        mService.deletionExecutor = mDeletionExecutor;

        assertNull(mService.handler);
        mService.handler = mHandler;

        assertNull(mService.jobFactory);
        mService.jobFactory = mJobFactory;
    }

    public void testRunsCopyJobs() throws Exception {
        startService(createCopyIntent(newArrayList(ALPHA_DOC), BETA_DOC));
        startService(createCopyIntent(newArrayList(GAMMA_DOC), DELTA_DOC));

        mExecutor.runAll();
        mJobFactory.assertAllCopyJobsStarted();
    }

    public void testRunsCopyJobs_AfterExceptionInJobCreation() throws Exception {
        try {
            startService(createCopyIntent(new ArrayList<>(), BETA_DOC));
        } catch(AssertionError e) {
            // Expected AssertionError
        }
        startService(createCopyIntent(newArrayList(GAMMA_DOC), DELTA_DOC));

        mJobFactory.assertJobsCreated(1);

        mExecutor.runAll();
        mJobFactory.assertAllCopyJobsStarted();
    }

    public void testRunsCopyJobs_AfterFailure() throws Exception {
        startService(createCopyIntent(newArrayList(ALPHA_DOC), BETA_DOC));
        startService(createCopyIntent(newArrayList(GAMMA_DOC), DELTA_DOC));

        mJobFactory.copyJobs.get(0).fail(ALPHA_DOC);

        mExecutor.runAll();
        mJobFactory.assertAllCopyJobsStarted();
    }

    public void testRunsCopyJobs_notRunsDeleteJobs() throws Exception {
        startService(createCopyIntent(newArrayList(ALPHA_DOC), BETA_DOC));
        startService(createDeleteIntent(newArrayList(GAMMA_DOC)));

        mExecutor.runAll();
        mJobFactory.assertNoDeleteJobsStarted();
    }

    public void testRunsDeleteJobs() throws Exception {
        startService(createDeleteIntent(newArrayList(ALPHA_DOC)));

        mDeletionExecutor.runAll();
        mJobFactory.assertAllDeleteJobsStarted();
    }

    public void testRunsDeleteJobs_NotRunsCopyJobs() throws Exception {
        startService(createCopyIntent(newArrayList(ALPHA_DOC), BETA_DOC));
        startService(createDeleteIntent(newArrayList(GAMMA_DOC)));

        mDeletionExecutor.runAll();
        mJobFactory.assertNoCopyJobsStarted();
    }

    public void testUpdatesNotification() throws Exception {
        startService(createCopyIntent(newArrayList(ALPHA_DOC), BETA_DOC));
        mExecutor.runAll();

        // Assert monitoring continues until job is done
        assertTrue(mHandler.hasScheduledMessage());
        // Two notifications -- one for setup; one for progress
        assertEquals(2, mJobFactory.copyJobs.get(0).getNumOfNotifications());
    }

    public void testStopsUpdatingNotificationAfterFinished() throws Exception {
        startService(createCopyIntent(newArrayList(ALPHA_DOC), BETA_DOC));
        mExecutor.runAll();

        mHandler.dispatchNextMessage();
        // Assert monitoring stops once job is completed.
        assertFalse(mHandler.hasScheduledMessage());

        // Assert no more notification is generated after finish.
        assertEquals(2, mJobFactory.copyJobs.get(0).getNumOfNotifications());

    }

    public void testHoldsWakeLockWhileWorking() throws Exception {
        startService(createCopyIntent(newArrayList(ALPHA_DOC), BETA_DOC));

        assertTrue(mService.holdsWakeLock());
    }

    public void testReleasesWakeLock_AfterSuccess() throws Exception {
        startService(createCopyIntent(newArrayList(ALPHA_DOC), BETA_DOC));

        assertTrue(mService.holdsWakeLock());
        mExecutor.runAll();
        assertFalse(mService.holdsWakeLock());
    }

    public void testReleasesWakeLock_AfterFailure() throws Exception {
        startService(createCopyIntent(newArrayList(ALPHA_DOC), BETA_DOC));

        assertTrue(mService.holdsWakeLock());
        mExecutor.runAll();
        assertFalse(mService.holdsWakeLock());
    }

    public void testShutdownStopsExecutor_AfterSuccess() throws Exception {
        startService(createCopyIntent(newArrayList(ALPHA_DOC), BETA_DOC));

        mExecutor.assertAlive();
        mDeletionExecutor.assertAlive();

        mExecutor.runAll();
        shutdownService();

        assertExecutorsShutdown();
    }

    public void testShutdownStopsExecutor_AfterMixedFailures() throws Exception {
        startService(createCopyIntent(newArrayList(ALPHA_DOC), BETA_DOC));
        startService(createCopyIntent(newArrayList(GAMMA_DOC), DELTA_DOC));

        mJobFactory.copyJobs.get(0).fail(ALPHA_DOC);

        mExecutor.runAll();
        shutdownService();

        assertExecutorsShutdown();
    }

    public void testShutdownStopsExecutor_AfterTotalFailure() throws Exception {
        startService(createCopyIntent(newArrayList(ALPHA_DOC), BETA_DOC));
        startService(createCopyIntent(newArrayList(GAMMA_DOC), DELTA_DOC));

        mJobFactory.copyJobs.get(0).fail(ALPHA_DOC);
        mJobFactory.copyJobs.get(1).fail(GAMMA_DOC);

        mExecutor.runAll();
        shutdownService();

        assertExecutorsShutdown();
    }

    private Intent createCopyIntent(ArrayList<DocumentInfo> files, DocumentInfo dest)
            throws Exception {
        DocumentStack stack = new DocumentStack();
        stack.push(dest);

        List<Uri> uris = new ArrayList<>(files.size());
        for (DocumentInfo file: files) {
            uris.add(file.derivedUri);
        }

        ClipDetails details =
                ClipDetailsFactory.createClipDetails(OPERATION_COPY, SRC_PARENT, uris);

        return createBaseIntent(getContext(), createJobId(), details, stack);
    }

    private Intent createDeleteIntent(ArrayList<DocumentInfo> files) {
        DocumentStack stack = new DocumentStack();

        List<Uri> uris = new ArrayList<>(files.size());
        for (DocumentInfo file: files) {
            uris.add(file.derivedUri);
        }

        ClipDetails details =
                ClipDetailsFactory.createClipDetails(OPERATION_DELETE, SRC_PARENT, uris);

        return createBaseIntent(getContext(), createJobId(), details, stack);
    }

    private static DocumentInfo createDoc(String name) {
        // Doesn't need to be valid content Uri, just some urly looking thing.
        Uri uri = new Uri.Builder()
                .scheme("content")
                .authority("com.android.documentsui.testing")
                .path(name)
                .build();

        return createDoc(uri);
    }

    private static DocumentInfo createDoc(Uri destination) {
        DocumentInfo destDoc = new DocumentInfo();
        destDoc.derivedUri = destination;
        return destDoc;
    }

    private void assertExecutorsShutdown() {
        mExecutor.assertShutdown();
        mDeletionExecutor.assertShutdown();
    }

    private final class TestJobFactory extends Job.Factory {

        private final List<TestJob> copyJobs = new ArrayList<>();
        private final List<TestJob> deleteJobs = new ArrayList<>();

        private Runnable mJobRunnable = () -> {
            // The following statement is executed concurrently to Job.start() in real situation.
            // Call it in TestJob.start() to mimic this behavior.
            mHandler.dispatchNextMessage();
        };

        void assertAllCopyJobsStarted() {
            for (TestJob job : copyJobs) {
                job.assertStarted();
            }
        }

        void assertAllDeleteJobsStarted() {
            for (TestJob job : deleteJobs) {
                job.assertStarted();
            }
        }

        void assertNoCopyJobsStarted() {
            for (TestJob job : copyJobs) {
                job.assertNotStarted();
            }
        }

        void assertNoDeleteJobsStarted() {
            for (TestJob job : deleteJobs) {
                job.assertNotStarted();
            }
        }

        void assertJobsCreated(int expected) {
            assertEquals(expected, copyJobs.size() + deleteJobs.size());
        }

        @Override
        Job createCopy(Context service, Context appContext, Listener listener, String id,
                DocumentStack stack, ClipDetails details) {

            if (details.getItemCount() == 0) {
                throw new RuntimeException("Empty srcs not supported!");
            }

            TestJob job = new TestJob(
                    service, appContext, listener, id, stack, details, mJobRunnable);
            copyJobs.add(job);
            return job;
        }

        @Override
        Job createDelete(Context service, Context appContext, Listener listener, String id,
                DocumentStack stack, ClipDetails details) {

            if (details.getItemCount() == 0) {
                throw new RuntimeException("Empty srcs not supported!");
            }

            TestJob job = new TestJob(
                    service, appContext, listener, id, stack, details, mJobRunnable);
            deleteJobs.add(job);

            return job;
        }
    }
}
