/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.app.servertransaction;

import static android.view.Display.DEFAULT_DISPLAY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.ClientTransactionHandler;
import android.content.res.Configuration;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.util.MergedConfiguration;
import android.view.IWindow;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.window.ActivityWindowInfo;
import android.window.ClientWindowFrames;
import android.window.WindowContextInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for subtypes of {@link ClientTransactionItem}.
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:ClientTransactionItemTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class ClientTransactionItemTest {

    @Rule
    public final MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private ClientTransactionHandler mHandler;
    @Mock
    private IBinder mActivityToken;
    @Mock
    private Activity mActivity;
    @Mock
    private PendingTransactionActions mPendingActions;
    @Mock
    private IBinder mWindowClientToken;
    @Mock
    private IWindow mWindow;

    // Can't mock final class.
    private Configuration mGlobalConfig;
    private Configuration mConfiguration;
    private ActivityThread.ActivityClientRecord mActivityClientRecord;
    private ArrayMap<IBinder, DestroyActivityItem> mActivitiesToBeDestroyed;
    private InsetsState mInsetsState;

    @Before
    public void setup() {
        mGlobalConfig = new Configuration();
        mConfiguration = new Configuration();
        mActivitiesToBeDestroyed = new ArrayMap<>();
        mActivityClientRecord = new ActivityThread.ActivityClientRecord();
        mInsetsState = new InsetsState();

        doReturn(mActivity).when(mHandler).getActivity(mActivityToken);
        doReturn(mActivitiesToBeDestroyed).when(mHandler).getActivitiesToBeDestroyed();
    }

    @Test
    public void testDestroyActivityItem_preExecute() {
        final DestroyActivityItem item =
                new DestroyActivityItem(mActivityToken, false /* finished */);

        item.preExecute(mHandler);

        assertEquals(1, mActivitiesToBeDestroyed.size());
        assertEquals(item, mActivitiesToBeDestroyed.get(mActivityToken));
    }

    @Test
    public void testDestroyActivityItem_postExecute() {
        final DestroyActivityItem item =
                new DestroyActivityItem(mActivityToken, false /* finished */);
        item.preExecute(mHandler);

        item.postExecute(mHandler, mPendingActions);

        assertTrue(mActivitiesToBeDestroyed.isEmpty());
    }

    @Test
    public void testDestroyActivityItem_execute() {
        final DestroyActivityItem item =
                new DestroyActivityItem(mActivityToken, false /* finished */);

        item.execute(mHandler, mActivityClientRecord, mPendingActions);

        verify(mHandler).handleDestroyActivity(eq(mActivityClientRecord), eq(false) /* finishing */,
                eq(false) /* getNonConfigInstance */, any());
    }

    @Test
    public void testResumeActivityItem_preExecute_withProcState_updatesProcessState() {
        final ResumeActivityItem item = new ResumeActivityItem(mActivityToken,
                ActivityManager.PROCESS_STATE_TOP /* procState */,
                true /* isForward */,
                false /* shouldSendCompatFakeFocus*/);

        item.preExecute(mHandler);

        verify(mHandler).updateProcessState(ActivityManager.PROCESS_STATE_TOP, false);
    }

    @Test
    public void testResumeActivityItem_preExecute_withUnknownProcState_skipsProcessStateUpdate() {
        final ResumeActivityItem item = new ResumeActivityItem(mActivityToken,
                ActivityManager.PROCESS_STATE_UNKNOWN /* procState */,
                true /* isForward */,
                false /* shouldSendCompatFakeFocus*/);

        item.preExecute(mHandler);

        verify(mHandler, never()).updateProcessState(anyInt(), anyBoolean());
    }

    @Test
    public void testResumeActivityItem_preExecute_withoutProcState_skipsProcessStateUpdate() {
        final ResumeActivityItem item = new ResumeActivityItem(mActivityToken,
                true /* isForward */,
                false /* shouldSendCompatFakeFocus*/);

        item.preExecute(mHandler);

        verify(mHandler, never()).updateProcessState(anyInt(), anyBoolean());
    }

    @Test
    public void testWindowContextInfoChangeItem_execute() {
        final WindowContextInfoChangeItem item = new WindowContextInfoChangeItem(mWindowClientToken,
                mConfiguration, DEFAULT_DISPLAY);

        item.execute(mHandler, mPendingActions);

        verify(mHandler).handleWindowContextInfoChanged(mWindowClientToken,
                new WindowContextInfo(mConfiguration, DEFAULT_DISPLAY));
    }

    @Test
    public void testWindowContextWindowRemovalItem_execute() {
        final WindowContextWindowRemovalItem item =
                new WindowContextWindowRemovalItem(mWindowClientToken);

        item.execute(mHandler, mPendingActions);

        verify(mHandler).handleWindowContextWindowRemoval(mWindowClientToken);
    }

    @Test
    public void testWindowStateResizeItem_execute() throws RemoteException {
        final MergedConfiguration mergedConfiguration = new MergedConfiguration(mGlobalConfig,
                mConfiguration);
        final ActivityWindowInfo activityWindowInfo = new ActivityWindowInfo();
        final ClientWindowFrames frames = new ClientWindowFrames();
        final WindowStateResizeItem item = new WindowStateResizeItem(mWindow, frames,
                true /* reportDraw */, mergedConfiguration, mInsetsState, true /* forceLayout */,
                true /* alwaysConsumeSystemBars */, 123 /* displayId */, 321 /* syncSeqId */,
                true /* dragResizing */, activityWindowInfo);

        item.execute(mHandler, mPendingActions);

        verify(mWindow).resized(frames,
                true /* reportDraw */, mergedConfiguration, mInsetsState, true /* forceLayout */,
                true /* alwaysConsumeSystemBars */, 123 /* displayId */, 321 /* syncSeqId */,
                true /* dragResizing */, activityWindowInfo);
    }

    @Test
    public void testWindowStateInsetsControlChangeItem_execute() throws RemoteException {
        final InsetsSourceControl.Array activeControls = new InsetsSourceControl.Array();
        final WindowStateInsetsControlChangeItem item = new WindowStateInsetsControlChangeItem(
                mWindow, mInsetsState, activeControls);

        item.execute(mHandler, mPendingActions);

        verify(mWindow).insetsControlChanged(mInsetsState, activeControls);
    }

    @Test
    public void testWindowStateInsetsControlChangeItem_executeError() throws RemoteException {
        final InsetsSourceControl.Array spiedActiveControls = spy(new InsetsSourceControl.Array());
        final WindowStateInsetsControlChangeItem item = new WindowStateInsetsControlChangeItem(
                mWindow, mInsetsState, spiedActiveControls, false /* copyActiveControls */);
        doThrow(new RemoteException()).when(mWindow).insetsControlChanged(any(), any());

        item.execute(mHandler, mPendingActions);

        verify(spiedActiveControls).release();
    }
}
