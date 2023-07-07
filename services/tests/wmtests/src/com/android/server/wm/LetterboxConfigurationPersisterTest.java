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

import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT;
import static com.android.server.wm.LetterboxConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP;
import static com.android.server.wm.LetterboxConfigurationPersister.LETTERBOX_CONFIGURATION_FILENAME;

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

@SmallTest
@Presubmit
public class LetterboxConfigurationPersisterTest {

    private static final long TIMEOUT = 2000L; // 2 secs

    private LetterboxConfigurationPersister mLetterboxConfigurationPersister;
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
        mLetterboxConfigurationPersister = new LetterboxConfigurationPersister(mContext,
                () -> mContext.getResources().getInteger(
                        R.integer.config_letterboxDefaultPositionForHorizontalReachability),
                () -> mContext.getResources().getInteger(
                        R.integer.config_letterboxDefaultPositionForVerticalReachability),
                () -> mContext.getResources().getInteger(
                        R.integer.config_letterboxDefaultPositionForBookModeReachability),
                () -> mContext.getResources().getInteger(
                        R.integer.config_letterboxDefaultPositionForTabletopModeReachability
                ),
                mConfigFolder, mPersisterQueue, mQueueState);
        mQueueListener = queueEmpty -> mQueueState.onItemAdded();
        mPersisterQueue.addListener(mQueueListener);
        mLetterboxConfigurationPersister.start();
    }

    @After
    public void tearDown() throws InterruptedException {
        deleteConfiguration(mLetterboxConfigurationPersister, mPersisterQueue);
        waitForCompletion(mPersisterQueue);
        mPersisterQueue.removeListener(mQueueListener);
        stopPersisterSafe(mPersisterQueue);
    }

    @Test
    public void test_whenStoreIsCreated_valuesAreDefaults() {
        final int positionForHorizontalReachability =
                mLetterboxConfigurationPersister.getLetterboxPositionForHorizontalReachability(
                        false);
        final int defaultPositionForHorizontalReachability =
                mContext.getResources().getInteger(
                        R.integer.config_letterboxDefaultPositionForHorizontalReachability);
        Assert.assertEquals(defaultPositionForHorizontalReachability,
                positionForHorizontalReachability);
        final int positionForVerticalReachability =
                mLetterboxConfigurationPersister.getLetterboxPositionForVerticalReachability(false);
        final int defaultPositionForVerticalReachability =
                mContext.getResources().getInteger(
                        R.integer.config_letterboxDefaultPositionForVerticalReachability);
        Assert.assertEquals(defaultPositionForVerticalReachability,
                positionForVerticalReachability);
    }

    @Test
    public void test_whenUpdatedWithNewValues_valuesAreWritten() {
        mLetterboxConfigurationPersister.setLetterboxPositionForHorizontalReachability(false,
                LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT);
        mLetterboxConfigurationPersister.setLetterboxPositionForVerticalReachability(false,
                LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP);
        waitForCompletion(mPersisterQueue);
        final int newPositionForHorizontalReachability =
                mLetterboxConfigurationPersister.getLetterboxPositionForHorizontalReachability(
                        false);
        final int newPositionForVerticalReachability =
                mLetterboxConfigurationPersister.getLetterboxPositionForVerticalReachability(false);
        Assert.assertEquals(LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                newPositionForHorizontalReachability);
        Assert.assertEquals(LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                newPositionForVerticalReachability);
    }

    @Test
    public void test_whenUpdatedWithNewValues_valuesAreReadAfterRestart() {
        final PersisterQueue firstPersisterQueue = new PersisterQueue();
        final LetterboxConfigurationPersister firstPersister = new LetterboxConfigurationPersister(
                mContext, () -> -1, () -> -1, () -> -1, () -> -1, mContext.getFilesDir(),
                firstPersisterQueue, mQueueState);
        firstPersister.start();
        firstPersister.setLetterboxPositionForHorizontalReachability(false,
                LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT);
        firstPersister.setLetterboxPositionForVerticalReachability(false,
                LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP);
        waitForCompletion(firstPersisterQueue);
        stopPersisterSafe(firstPersisterQueue);
        final PersisterQueue secondPersisterQueue = new PersisterQueue();
        final LetterboxConfigurationPersister secondPersister = new LetterboxConfigurationPersister(
                mContext, () -> -1, () -> -1, () -> -1, () -> -1, mContext.getFilesDir(),
                secondPersisterQueue, mQueueState);
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
        mLetterboxConfigurationPersister.setLetterboxPositionForHorizontalReachability(false,
                LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT);
        mLetterboxConfigurationPersister.setLetterboxPositionForVerticalReachability(false,
                LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP);
        waitForCompletion(mPersisterQueue);
        final int newPositionForHorizontalReachability =
                mLetterboxConfigurationPersister.getLetterboxPositionForHorizontalReachability(
                        false);
        final int newPositionForVerticalReachability =
                mLetterboxConfigurationPersister.getLetterboxPositionForVerticalReachability(false);
        Assert.assertEquals(LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT,
                newPositionForHorizontalReachability);
        Assert.assertEquals(LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP,
                newPositionForVerticalReachability);
        deleteConfiguration(mLetterboxConfigurationPersister, mPersisterQueue);
        waitForCompletion(mPersisterQueue);
        final int positionForHorizontalReachability =
                mLetterboxConfigurationPersister.getLetterboxPositionForHorizontalReachability(
                        false);
        final int defaultPositionForHorizontalReachability =
                mContext.getResources().getInteger(
                        R.integer.config_letterboxDefaultPositionForHorizontalReachability);
        Assert.assertEquals(defaultPositionForHorizontalReachability,
                positionForHorizontalReachability);
        final int positionForVerticalReachability =
                mLetterboxConfigurationPersister.getLetterboxPositionForVerticalReachability(false);
        final int defaultPositionForVerticalReachability =
                mContext.getResources().getInteger(
                        R.integer.config_letterboxDefaultPositionForVerticalReachability);
        Assert.assertEquals(defaultPositionForVerticalReachability,
                positionForVerticalReachability);
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

    private void deleteConfiguration(LetterboxConfigurationPersister persister,
            PersisterQueue persisterQueue) {
        final AtomicFile fileToDelete = new AtomicFile(
                new File(mConfigFolder, LETTERBOX_CONFIGURATION_FILENAME));
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
