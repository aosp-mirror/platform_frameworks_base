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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.Activity;
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

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

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
    private ClientWindowFrames mFrames;
    private MergedConfiguration mMergedConfiguration;
    private ActivityWindowInfo mActivityWindowInfo;
    private InsetsSourceControl.Array mActiveControls;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mGlobalConfig = new Configuration();
        mConfiguration = new Configuration();
        mActivitiesToBeDestroyed = new ArrayMap<>();
        mActivityClientRecord = new ActivityThread.ActivityClientRecord();
        mInsetsState = new InsetsState();
        mFrames = new ClientWindowFrames();
        mMergedConfiguration = new MergedConfiguration(mGlobalConfig, mConfiguration);
        mActivityWindowInfo = new ActivityWindowInfo();
        mActiveControls = new InsetsSourceControl.Array();

        doReturn(mActivity).when(mHandler).getActivity(mActivityToken);
        doReturn(mActivitiesToBeDestroyed).when(mHandler).getActivitiesToBeDestroyed();
    }

    @Test
    public void testDestroyActivityItem_preExecute() {
        final DestroyActivityItem item = DestroyActivityItem
                .obtain(mActivityToken, false /* finished */);
        item.preExecute(mHandler);

        assertEquals(1, mActivitiesToBeDestroyed.size());
        assertEquals(item, mActivitiesToBeDestroyed.get(mActivityToken));
    }

    @Test
    public void testDestroyActivityItem_postExecute() {
        final DestroyActivityItem item = DestroyActivityItem
                .obtain(mActivityToken, false /* finished */);
        item.preExecute(mHandler);
        item.postExecute(mHandler, mPendingActions);

        assertTrue(mActivitiesToBeDestroyed.isEmpty());
    }

    @Test
    public void testDestroyActivityItem_execute() {
        final DestroyActivityItem item = DestroyActivityItem
                .obtain(mActivityToken, false /* finished */);
        item.execute(mHandler, mActivityClientRecord, mPendingActions);

        verify(mHandler).handleDestroyActivity(eq(mActivityClientRecord), eq(false) /* finishing */,
                eq(false) /* getNonConfigInstance */, any());
    }

    @Test
    public void testWindowContextInfoChangeItem_execute() {
        final WindowContextInfoChangeItem item = WindowContextInfoChangeItem
                .obtain(mWindowClientToken, mConfiguration, DEFAULT_DISPLAY);
        item.execute(mHandler, mPendingActions);

        verify(mHandler).handleWindowContextInfoChanged(mWindowClientToken,
                new WindowContextInfo(mConfiguration, DEFAULT_DISPLAY));
    }

    @Test
    public void testWindowContextWindowRemovalItem_execute() {
        final WindowContextWindowRemovalItem item = WindowContextWindowRemovalItem.obtain(
                mWindowClientToken);
        item.execute(mHandler, mPendingActions);

        verify(mHandler).handleWindowContextWindowRemoval(mWindowClientToken);
    }

    @Test
    public void testWindowStateResizeItem_execute() throws RemoteException {
        final WindowStateResizeItem item = WindowStateResizeItem.obtain(mWindow, mFrames,
                true /* reportDraw */, mMergedConfiguration, mInsetsState, true /* forceLayout */,
                true /* alwaysConsumeSystemBars */, 123 /* displayId */, 321 /* syncSeqId */,
                true /* dragResizing */, mActivityWindowInfo);
        item.execute(mHandler, mPendingActions);

        verify(mWindow).resized(mFrames,
                true /* reportDraw */, mMergedConfiguration, mInsetsState, true /* forceLayout */,
                true /* alwaysConsumeSystemBars */, 123 /* displayId */, 321 /* syncSeqId */,
                true /* dragResizing */, mActivityWindowInfo);
    }

    @Test
    public void testWindowStateInsetsControlChangeItem_execute() throws RemoteException {
        final WindowStateInsetsControlChangeItem item = WindowStateInsetsControlChangeItem.obtain(
                mWindow, mInsetsState, mActiveControls);
        item.execute(mHandler, mPendingActions);

        verify(mWindow).insetsControlChanged(mInsetsState, mActiveControls);
    }

    @Test
    public void testWindowStateInsetsControlChangeItem_executeError() throws RemoteException {
        doThrow(new RemoteException()).when(mWindow).insetsControlChanged(any(), any());

        mActiveControls = spy(mActiveControls);
        final WindowStateInsetsControlChangeItem item = WindowStateInsetsControlChangeItem.obtain(
                mWindow, mInsetsState, mActiveControls);
        item.mActiveControls = mActiveControls;
        item.execute(mHandler, mPendingActions);

        verify(mActiveControls).release();
    }
}
