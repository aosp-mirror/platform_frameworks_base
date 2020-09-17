/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.annotation.NonNull;
import androidx.core.os.CancellationSignal;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.row.NotifBindPipeline.BindCallback;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotifBindPipelineTest extends SysuiTestCase {

    private NotifBindPipeline mBindPipeline;
    private TestBindStage mStage = new TestBindStage();

    @Mock private NotificationEntry mEntry;
    @Mock private ExpandableNotificationRow mRow;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        CommonNotifCollection collection = mock(CommonNotifCollection.class);

        mBindPipeline = new NotifBindPipeline(
                collection,
                mock(NotifBindPipelineLogger.class),
                TestableLooper.get(this).getLooper());
        mBindPipeline.setStage(mStage);

        ArgumentCaptor<NotifCollectionListener> collectionListenerCaptor =
                ArgumentCaptor.forClass(NotifCollectionListener.class);
        verify(collection).addCollectionListener(collectionListenerCaptor.capture());
        NotifCollectionListener listener = collectionListenerCaptor.getValue();

        listener.onEntryInit(mEntry);
    }

    @Test
    public void testCallbackCalled() {
        // GIVEN a bound row
        mBindPipeline.manageRow(mEntry, mRow);

        // WHEN content is invalidated
        BindCallback callback = mock(BindCallback.class);
        mStage.requestRebind(mEntry, callback);
        TestableLooper.get(this).processAllMessages();

        // WHEN stage finishes its work
        mStage.doWorkSynchronously();

        // THEN the callback is called when bind finishes
        verify(callback).onBindFinished(mEntry);
    }

    @Test
    public void testCallbackCancelled() {
        // GIVEN a bound row
        mBindPipeline.manageRow(mEntry, mRow);

        // GIVEN an in-progress pipeline run
        BindCallback callback = mock(BindCallback.class);
        CancellationSignal signal = mStage.requestRebind(mEntry, callback);
        TestableLooper.get(this).processAllMessages();

        // WHEN the callback is cancelled.
        signal.cancel();

        // WHEN the stage finishes all its work
        mStage.doWorkSynchronously();

        // THEN the callback is not called when bind finishes
        verify(callback, never()).onBindFinished(mEntry);
    }

    @Test
    public void testMultipleCallbacks() {
        // GIVEN a bound row
        mBindPipeline.manageRow(mEntry, mRow);

        // WHEN the pipeline is invalidated.
        BindCallback callback = mock(BindCallback.class);
        mStage.requestRebind(mEntry, callback);
        TestableLooper.get(this).processAllMessages();

        // WHEN the pipeline is invalidated again before the work completes.
        BindCallback callback2 = mock(BindCallback.class);
        mStage.requestRebind(mEntry, callback2);
        TestableLooper.get(this).processAllMessages();

        // WHEN the stage finishes all work.
        mStage.doWorkSynchronously();

        // THEN both callbacks are called when the bind finishes
        verify(callback).onBindFinished(mEntry);
        verify(callback2).onBindFinished(mEntry);
    }

    /**
     * Bind stage for testing where asynchronous work can be synchronously controlled.
     */
    private static class TestBindStage extends BindStage {
        private List<Runnable> mExecutionRequests = new ArrayList<>();

        @Override
        protected void executeStage(@NonNull NotificationEntry entry,
                @NonNull ExpandableNotificationRow row, @NonNull StageCallback callback) {
            mExecutionRequests.add(() -> callback.onStageFinished(entry));
        }

        @Override
        protected void abortStage(@NonNull NotificationEntry entry,
                @NonNull ExpandableNotificationRow row) {

        }

        @Override
        protected Object newStageParams() {
            return null;
        }

        public void doWorkSynchronously() {
            for (Runnable work: mExecutionRequests) {
                work.run();
            }
        }
    }
}
