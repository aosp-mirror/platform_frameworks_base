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

import static android.content.Context.DEVICE_ID_DEFAULT;
import static android.view.Display.DEFAULT_DISPLAY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.app.Activity;
import android.app.ActivityThread;
import android.app.ClientTransactionHandler;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.util.MergedConfiguration;
import android.view.IWindow;
import android.view.InsetsState;
import android.window.ActivityWindowInfo;
import android.window.ClientWindowFrames;
import android.window.WindowContext;
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
    private WindowContext mWindowContext;
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

        doReturn(mActivity).when(mHandler).getActivity(mActivityToken);
        doReturn(mActivitiesToBeDestroyed).when(mHandler).getActivitiesToBeDestroyed();
    }

    @Test
    public void testActivityConfigurationChangeItem_getContextToUpdate() {
        final ActivityConfigurationChangeItem item = ActivityConfigurationChangeItem
                .obtain(mActivityToken, mConfiguration, new ActivityWindowInfo());
        final Context context = item.getContextToUpdate(mHandler);

        assertEquals(mActivity, context);
    }

    @Test
    public void testActivityRelaunchItem_getContextToUpdate() {
        final ActivityRelaunchItem item = ActivityRelaunchItem
                .obtain(mActivityToken, null /* pendingResults */, null  /* pendingNewIntents */,
                        0 /* configChange */, mMergedConfiguration, false /* preserveWindow */,
                        new ActivityWindowInfo());
        final Context context = item.getContextToUpdate(mHandler);

        assertEquals(mActivity, context);
    }

    @Test
    public void testConfigurationChangeItem_getContextToUpdate() {
        final ConfigurationChangeItem item = ConfigurationChangeItem
                .obtain(mConfiguration, DEVICE_ID_DEFAULT);
        final Context context = item.getContextToUpdate(mHandler);

        assertEquals(ActivityThread.currentApplication(), context);
    }

    @Test
    public void testDestroyActivityItem_preExecute() {
        final DestroyActivityItem item = DestroyActivityItem
                .obtain(mActivityToken, false /* finished */, 123 /* configChanges */);
        item.preExecute(mHandler);

        assertEquals(1, mActivitiesToBeDestroyed.size());
        assertEquals(item, mActivitiesToBeDestroyed.get(mActivityToken));
    }

    @Test
    public void testDestroyActivityItem_postExecute() {
        final DestroyActivityItem item = DestroyActivityItem
                .obtain(mActivityToken, false /* finished */, 123 /* configChanges */);
        item.preExecute(mHandler);
        item.postExecute(mHandler, mPendingActions);

        assertTrue(mActivitiesToBeDestroyed.isEmpty());
    }

    @Test
    public void testDestroyActivityItem_execute() {
        final DestroyActivityItem item = DestroyActivityItem
                .obtain(mActivityToken, false /* finished */, 123 /* configChanges */);
        item.execute(mHandler, mActivityClientRecord, mPendingActions);

        verify(mHandler).handleDestroyActivity(eq(mActivityClientRecord), eq(false) /* finishing */,
                eq(123) /* configChanges */, eq(false) /* getNonConfigInstance */, any());
    }

    @Test
    public void testLaunchActivityItem_getContextToUpdate() {
        final LaunchActivityItem item = new TestUtils.LaunchActivityItemBuilder(
                mActivityToken, new Intent(), new ActivityInfo())
                .build();

        final Context context = item.getContextToUpdate(mHandler);

        assertEquals(ActivityThread.currentApplication(), context);
    }

    @Test
    public void testMoveToDisplayItem_getContextToUpdate() {
        final MoveToDisplayItem item = MoveToDisplayItem
                .obtain(mActivityToken, DEFAULT_DISPLAY, mConfiguration, new ActivityWindowInfo());
        final Context context = item.getContextToUpdate(mHandler);

        assertEquals(mActivity, context);
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
    public void testWindowContextInfoChangeItem_getContextToUpdate() {
        doReturn(mWindowContext).when(mHandler).getWindowContext(mWindowClientToken);

        final WindowContextInfoChangeItem item = WindowContextInfoChangeItem
                .obtain(mWindowClientToken, mConfiguration, DEFAULT_DISPLAY);
        final Context context = item.getContextToUpdate(mHandler);

        assertEquals(mWindowContext, context);
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
                true /* dragResizing */);
        item.execute(mHandler, mPendingActions);

        verify(mWindow).resized(mFrames,
                true /* reportDraw */, mMergedConfiguration, mInsetsState, true /* forceLayout */,
                true /* alwaysConsumeSystemBars */, 123 /* displayId */, 321 /* syncSeqId */,
                true /* dragResizing */);
    }

    @Test
    public void testWindowStateResizeItem_getContextToUpdate() {
        final WindowStateResizeItem item = WindowStateResizeItem.obtain(mWindow, mFrames,
                true /* reportDraw */, mMergedConfiguration, mInsetsState, true /* forceLayout */,
                true /* alwaysConsumeSystemBars */, 123 /* displayId */, 321 /* syncSeqId */,
                true /* dragResizing */);
        final Context context = item.getContextToUpdate(mHandler);

        assertEquals(ActivityThread.currentApplication(), context);
    }

}
