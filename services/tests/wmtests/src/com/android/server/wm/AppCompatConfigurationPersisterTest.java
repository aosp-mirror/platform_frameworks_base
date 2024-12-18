/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wm;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.util.AtomicFile;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import junit.framework.Assert;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tests for the {@link AppCompatConfigurationPersister} class.
 *
 * Build/Install/Run:
 *  atest WmTests:AppCompatConfigurationPersisterTest
 */
@SmallTest
@Presubmit
public class AppCompatConfigurationPersisterTest {

    private static final long TIMEOUT = 2000L; // 2 secs

    private static final int DEFAULT_REACHABILITY_TEST = -1;
    private static final Supplier<Integer> DEFAULT_REACHABILITY_SUPPLIER_TEST =
            () -> DEFAULT_REACHABILITY_TEST;

    private static final String LETTERBOX_CONFIGURATION_TEST_FILENAME = "letterbox_config_test";

    private AppCompatConfigurationPersister mAppCompatConfigurationPersister;
    private Context mContext;
    private PersisterQueue mPersisterQueue;
    private QueueState mQueueState;
    private PersisterQueue.Listener mQueueListener;
    private File mConfigFolder;

    @Before
    public void setUp() throws Exception {
        mContext = getInstrumentation().getTargetContext();
        mConfigFolder = mContext.getFilesDir();
        mPersisterQueue = new PersisterQueue();
        mQueueState = new QueueState();
        mAppCompatConfigurationPersister = new AppCompatConfigurationPersister(
                () -> mContext.getResources().getInteger(
                        R.integer.config_letterboxDefaultPositionForHorizontalReachability),
                () -> mContext.getResources().getInteger(
                        R.integer.config_letterboxDefaultPositionForVerticalReachability),
                () -> mContext.getResources().getInteger(
                        R.integer.config_letterboxDefaultPositionForBookModeReachability),
                () -> mContext.getResources().getInteger(
                        R.integer.config_letterboxDefaultPositionForTabletopModeReachability
                ),
                mConfigFolder, mPersisterQueue, mQueueState,
                LETTERBOX_CONFIGURATION_TEST_FILENAME);
        mQueueListener = queueEmpty -> mQueueState.onItemAdded();
        mPersisterQueue.addListener(mQueueListener);
        mAppCompatConfigurationPersister.start();
    }

    @After
    public void tearDown() throws InterruptedException {
        deleteConfiguration(mAppCompatConfigurationPersister, mPersisterQueue);
        waitForCompletion(mPersisterQueue);
        mPersisterQueue.removeListener(mQueueListener);
        stopPersisterSafe(mPersisterQueue);
    }

    @Test
    public void test_whenStoreIsCreated_valuesAreDefaults() {
        final int positionForHorizontalReachability =
                mAppCompatConfigurationPersister.getLetterboxPositionForHorizontalReachability(
                        false);
        final int defaultPositionForHorizontalReachability =
                mContext.getResources().getInteger(
                        R.integer.config_letterboxDefaultPositionForHorizontalReachability);
        Assert.assertEquals(defaultPositionForHorizontalReachability,
                positionForHorizontalReachability);
        final int positionForVerticalReachability =
                mAppCompatConfigurationPersister.getLetterboxPositionForVerticalReachability(false);
        final int defaultPositionForVerticalReachability =
                mContext.getResources().getInteger(
                        R.integer.config_letterboxDefaultPositionForVerticalReachability);
        Assert.assertEquals(defaultPositionForVerticalReachability,
                positionForVerticalReachability);
    }

    @Test
    public void test_whenUpdatedWithNewValues_valuesAreWritten() {
        mAppCompatConfigurationPersister.setLetterboxPositionForHorizontalReachability(false,
                LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT);
        mAppCompatConfigurationPersister.setLetterboxPositionForVerticalReachability(false,
                LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP);
        waitForCompletion(mPersisterQueue);
        final int newPositionForHorizontalReachability =
                mAppCompatConfigurationPersister.getLetterboxPositionForHorizontalReachability(
                        false);
        final int newPositionForVerticalReachability =
                mAppCompatConfigurationPersister.getLetterboxPositionForVerticalReachability(false);
        Assert.assertEquals(LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                newPositionForHorizontalReachability);
        Assert.assertEquals(LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                newPositionForVerticalReachability);
    }

    @Test
    public void test_whenUpdatedWithNewValues_valuesAreReadAfterRestart() {
        final PersisterQueue firstPersisterQueue = new PersisterQueue();
        final AppCompatConfigurationPersister firstPersister = new AppCompatConfigurationPersister(
                DEFAULT_REACHABILITY_SUPPLIER_TEST, DEFAULT_REACHABILITY_SUPPLIER_TEST,
                DEFAULT_REACHABILITY_SUPPLIER_TEST, DEFAULT_REACHABILITY_SUPPLIER_TEST,
                mContext.getFilesDir(), firstPersisterQueue, mQueueState,
                LETTERBOX_CONFIGURATION_TEST_FILENAME);
        firstPersister.start();
        firstPersister.setLetterboxPositionForHorizontalReachability(false,
                LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT);
        firstPersister.setLetterboxPositionForVerticalReachability(false,
                LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP);
        waitForCompletion(firstPersisterQueue);
        stopPersisterSafe(firstPersisterQueue);
        final PersisterQueue secondPersisterQueue = new PersisterQueue();
        final AppCompatConfigurationPersister secondPersister = new AppCompatConfigurationPersister(
                DEFAULT_REACHABILITY_SUPPLIER_TEST, DEFAULT_REACHABILITY_SUPPLIER_TEST,
                DEFAULT_REACHABILITY_SUPPLIER_TEST, DEFAULT_REACHABILITY_SUPPLIER_TEST,
                mContext.getFilesDir(), secondPersisterQueue, mQueueState,
                LETTERBOX_CONFIGURATION_TEST_FILENAME);
        secondPersister.start();
        final int newPositionForHorizontalReachability =
                secondPersister.getLetterboxPositionForHorizontalReachability(false);
        final int newPositionForVerticalReachability =
                secondPersister.getLetterboxPositionForVerticalReachability(false);
        Assert.assertEquals(LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                newPositionForHorizontalReachability);
        Assert.assertEquals(LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                newPositionForVerticalReachability);
        deleteConfiguration(secondPersister, secondPersisterQueue);
        waitForCompletion(secondPersisterQueue);
        stopPersisterSafe(secondPersisterQueue);
    }

    @Test
    public void test_whenUpdatedWithNewValuesAndDeleted_valuesAreDefaults() {
        final PersisterQueue firstPersisterQueue = new PersisterQueue();
        final AppCompatConfigurationPersister firstPersister = new AppCompatConfigurationPersister(
                DEFAULT_REACHABILITY_SUPPLIER_TEST, DEFAULT_REACHABILITY_SUPPLIER_TEST,
                DEFAULT_REACHABILITY_SUPPLIER_TEST, DEFAULT_REACHABILITY_SUPPLIER_TEST,
                mContext.getFilesDir(), firstPersisterQueue, mQueueState,
                LETTERBOX_CONFIGURATION_TEST_FILENAME);
        firstPersister.start();
        firstPersister.setLetterboxPositionForHorizontalReachability(false,
                LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT);
        firstPersister.setLetterboxPositionForVerticalReachability(false,
                LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP);
        waitForCompletion(firstPersisterQueue);
        final int newPositionForHorizontalReachability =
                firstPersister.getLetterboxPositionForHorizontalReachability(false);
        final int newPositionForVerticalReachability =
                firstPersister.getLetterboxPositionForVerticalReachability(false);
        Assert.assertEquals(LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                newPositionForHorizontalReachability);
        Assert.assertEquals(LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                newPositionForVerticalReachability);
        deleteConfiguration(firstPersister, firstPersisterQueue);
        waitForCompletion(firstPersisterQueue);
        stopPersisterSafe(firstPersisterQueue);

        final PersisterQueue secondPersisterQueue = new PersisterQueue();
        final AppCompatConfigurationPersister secondPersister = new AppCompatConfigurationPersister(
                DEFAULT_REACHABILITY_SUPPLIER_TEST, DEFAULT_REACHABILITY_SUPPLIER_TEST,
                DEFAULT_REACHABILITY_SUPPLIER_TEST, DEFAULT_REACHABILITY_SUPPLIER_TEST,
                mContext.getFilesDir(), secondPersisterQueue, mQueueState,
                LETTERBOX_CONFIGURATION_TEST_FILENAME);
        secondPersister.start();
        final int positionForHorizontalReachability =
                secondPersister.getLetterboxPositionForHorizontalReachability(false);
        final int positionForVerticalReachability =
                secondPersister.getLetterboxPositionForVerticalReachability(false);
        Assert.assertEquals(DEFAULT_REACHABILITY_TEST, positionForHorizontalReachability);
        Assert.assertEquals(DEFAULT_REACHABILITY_TEST, positionForVerticalReachability);
        deleteConfiguration(secondPersister, secondPersisterQueue);
        waitForCompletion(secondPersisterQueue);
        stopPersisterSafe(secondPersisterQueue);
    }

    private void stopPersisterSafe(PersisterQueue persisterQueue) {
        try {
            persisterQueue.stopPersisting();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void waitForCompletion(PersisterQueue persisterQueue) {
        final long endTime = System.currentTimeMillis() + TIMEOUT;
        // The queue could be empty but the last item still processing and not completed. For this
        // reason the completion happens when there are not more items to process and the last one
        // has completed.
        while (System.currentTimeMillis() < endTime && (!isQueueEmpty(persisterQueue)
                || !hasLastItemCompleted())) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) { /* Nope */}
        }
    }

    private boolean isQueueEmpty(PersisterQueue persisterQueue) {
        return persisterQueue.findLastItem(
                writeQueueItem -> true, PersisterQueue.WriteQueueItem.class) != null;
    }

    private boolean hasLastItemCompleted() {
        return mQueueState.isEmpty();
    }

    private void deleteConfiguration(AppCompatConfigurationPersister persister,
            PersisterQueue persisterQueue) {
        final AtomicFile fileToDelete = new AtomicFile(
                new File(mConfigFolder, LETTERBOX_CONFIGURATION_TEST_FILENAME));
        persisterQueue.addItem(
                new DeleteFileCommand(fileToDelete, mQueueState.andThen(
                        s -> persister.useDefaultValue())), true);
    }

    private static class DeleteFileCommand implements
            PersisterQueue.WriteQueueItem<DeleteFileCommand> {

        @NonNull
        private final AtomicFile mFileToDelete;
        @Nullable
        private final Consumer<String> mOnComplete;

        DeleteFileCommand(@NonNull AtomicFile fileToDelete, Consumer<String> onComplete) {
            mFileToDelete = fileToDelete;
            mOnComplete = onComplete;
        }

        @Override
        public void process() {
            mFileToDelete.delete();
            if (mOnComplete != null) {
                mOnComplete.accept("DeleteFileCommand");
            }
        }
    }

    // Contains the current length of the persister queue
    private static class QueueState implements Consumer<String> {

        // The current number of commands in the queue
        @VisibleForTesting
        private final AtomicInteger mCounter = new AtomicInteger(0);

        @Override
        public void accept(String s) {
            mCounter.decrementAndGet();
        }

        void onItemAdded() {
            mCounter.incrementAndGet();
        }

        boolean isEmpty() {
            return mCounter.get() == 0;
        }

    }
}
