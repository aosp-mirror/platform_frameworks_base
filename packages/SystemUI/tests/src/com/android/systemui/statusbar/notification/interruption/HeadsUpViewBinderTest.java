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

package com.android.systemui.statusbar.notification.interruption;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;

import androidx.core.os.CancellationSignal;
import androidx.test.filters.SmallTest;

import com.android.internal.util.NotificationMessagingUtil;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotifBindPipeline;
import com.android.systemui.statusbar.notification.row.RowContentBindParams;
import com.android.systemui.statusbar.notification.row.RowContentBindStage;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidTestingRunner.class)
@SmallTest
public class HeadsUpViewBinderTest extends SysuiTestCase {
    private HeadsUpViewBinder mViewBinder;
    @Mock private NotificationMessagingUtil mNotificationMessagingUtil;
    @Mock private RowContentBindStage mBindStage;
    @Mock private HeadsUpViewBinderLogger mLogger;
    @Mock private NotificationEntry mEntry;
    @Mock private ExpandableNotificationRow mRow;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mViewBinder = new HeadsUpViewBinder(mNotificationMessagingUtil, mBindStage, mLogger);
        when(mEntry.getKey()).thenReturn("key");
        when(mEntry.getRow()).thenReturn(mRow);
        when(mBindStage.getStageParams(eq(mEntry))).thenReturn(new RowContentBindParams());
    }

    @Test
    public void testLoggingForStandardFlow() {
        AtomicReference<NotifBindPipeline.BindCallback> callback = new AtomicReference<>();
        when(mBindStage.requestRebind(any(), any())).then(i -> {
            callback.set(i.getArgument(1));
            return new CancellationSignal();
        });

        mViewBinder.bindHeadsUpView(mEntry, null);
        verify(mLogger).startBindingHun(eq(mEntry));
        verifyNoMoreInteractions(mLogger);
        clearInvocations(mLogger);

        callback.get().onBindFinished(mEntry);
        verify(mLogger).entryBoundSuccessfully(eq(mEntry));
        verifyNoMoreInteractions(mLogger);
        clearInvocations(mLogger);

        mViewBinder.bindHeadsUpView(mEntry, null);
        verify(mLogger).startBindingHun(eq(mEntry));
        verifyNoMoreInteractions(mLogger);
        clearInvocations(mLogger);

        callback.get().onBindFinished(mEntry);
        verify(mLogger).entryBoundSuccessfully(eq(mEntry));
        verifyNoMoreInteractions(mLogger);
        clearInvocations(mLogger);

        when(mBindStage.tryGetStageParams(eq(mEntry))).thenReturn(new RowContentBindParams());

        mViewBinder.unbindHeadsUpView(mEntry);
        verify(mLogger).entryContentViewMarkedFreeable(eq(mEntry));
        verifyNoMoreInteractions(mLogger);
        clearInvocations(mLogger);

        callback.get().onBindFinished(mEntry);
        verify(mLogger).entryUnbound(eq(mEntry));
        verifyNoMoreInteractions(mLogger);
        clearInvocations(mLogger);
    }

    @Test
    public void testLoggingForAbortFlow() {
        AtomicReference<NotifBindPipeline.BindCallback> callback = new AtomicReference<>();
        when(mBindStage.requestRebind(any(), any())).then(i -> {
            callback.set(i.getArgument(1));
            return new CancellationSignal();
        });

        mViewBinder.bindHeadsUpView(mEntry, null);
        verify(mLogger).startBindingHun(eq(mEntry));
        verifyNoMoreInteractions(mLogger);
        clearInvocations(mLogger);

        mViewBinder.abortBindCallback(mEntry);
        verify(mLogger).currentOngoingBindingAborted(eq(mEntry));
        verifyNoMoreInteractions(mLogger);
        clearInvocations(mLogger);

        // second abort logs nothing
        mViewBinder.abortBindCallback(mEntry);
        verifyNoMoreInteractions(mLogger);
        clearInvocations(mLogger);
    }

    @Test
    public void testLoggingForEarlyUnbindFlow() {
        AtomicReference<NotifBindPipeline.BindCallback> callback = new AtomicReference<>();
        when(mBindStage.requestRebind(any(), any())).then(i -> {
            callback.set(i.getArgument(1));
            return new CancellationSignal();
        });

        mViewBinder.bindHeadsUpView(mEntry, null);
        verify(mLogger).startBindingHun(eq(mEntry));
        verifyNoMoreInteractions(mLogger);
        clearInvocations(mLogger);

        when(mBindStage.tryGetStageParams(eq(mEntry))).thenReturn(new RowContentBindParams());

        mViewBinder.unbindHeadsUpView(mEntry);
        verify(mLogger).currentOngoingBindingAborted(eq(mEntry));
        verify(mLogger).entryContentViewMarkedFreeable(eq(mEntry));
        verifyNoMoreInteractions(mLogger);
        clearInvocations(mLogger);

        callback.get().onBindFinished(mEntry);
        verify(mLogger).entryUnbound(eq(mEntry));
        verifyNoMoreInteractions(mLogger);
        clearInvocations(mLogger);
    }

    @Test
    public void testLoggingForLateUnbindFlow() {
        AtomicReference<NotifBindPipeline.BindCallback> callback = new AtomicReference<>();
        when(mBindStage.requestRebind(any(), any())).then(i -> {
            callback.set(i.getArgument(1));
            return new CancellationSignal();
        });

        mViewBinder.bindHeadsUpView(mEntry, null);
        verify(mLogger).startBindingHun(eq(mEntry));
        verifyNoMoreInteractions(mLogger);
        clearInvocations(mLogger);

        callback.get().onBindFinished(mEntry);
        verify(mLogger).entryBoundSuccessfully(eq(mEntry));
        verifyNoMoreInteractions(mLogger);
        clearInvocations(mLogger);

        when(mBindStage.tryGetStageParams(eq(mEntry))).thenReturn(null);

        mViewBinder.unbindHeadsUpView(mEntry);
        verify(mLogger).entryBindStageParamsNullOnUnbind(eq(mEntry));
        verifyNoMoreInteractions(mLogger);
        clearInvocations(mLogger);
    }
}
