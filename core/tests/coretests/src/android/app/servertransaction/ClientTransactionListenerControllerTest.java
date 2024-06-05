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

import static android.platform.test.flag.junit.SetFlagsRule.DefaultInitValueType.DEVICE_DEFAULT;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.window.flags.Flags.FLAG_BUNDLE_CLIENT_TRANSACTION_FLAG;
import static com.android.window.flags.Flags.FLAG_WINDOW_TOKEN_CONFIG_THREAD_SAFE;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.IDisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.view.DisplayInfo;
import android.window.ActivityWindowInfo;
import android.window.WindowTokenClient;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.window.flags.Flags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;

/**
 * Tests for {@link ClientTransactionListenerController}.
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:ClientTransactionListenerControllerTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class ClientTransactionListenerControllerTest {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule(DEVICE_DEFAULT);

    @Mock
    private IDisplayManager mIDisplayManager;
    @Mock
    private DisplayManager.DisplayListener mListener;
    @Mock
    private BiConsumer<IBinder, ActivityWindowInfo> mActivityWindowInfoListener;
    @Mock
    private IBinder mActivityToken;
    @Mock
    private Activity mActivity;
    @Mock
    private Resources mResources;

    private Configuration mConfiguration;


    private DisplayManagerGlobal mDisplayManager;
    private Handler mHandler;
    private ClientTransactionListenerController mController;

    @Before
    public void setup() {
        mSetFlagsRule.enableFlags(FLAG_BUNDLE_CLIENT_TRANSACTION_FLAG);

        MockitoAnnotations.initMocks(this);
        mDisplayManager = new DisplayManagerGlobal(mIDisplayManager);
        mHandler = getInstrumentation().getContext().getMainThreadHandler();
        mController = spy(ClientTransactionListenerController
                .createInstanceForTesting(mDisplayManager));

        mConfiguration = new Configuration();
        doReturn(mConfiguration).when(mResources).getConfiguration();
        doReturn(mResources).when(mActivity).getResources();
    }

    @Test
    public void testOnDisplayChanged() throws RemoteException {
        // Mock IDisplayManager to return a display info to trigger display change.
        final DisplayInfo newDisplayInfo = new DisplayInfo();
        doReturn(newDisplayInfo).when(mIDisplayManager).getDisplayInfo(123);

        mDisplayManager.registerDisplayListener(mListener, mHandler,
                DisplayManager.EVENT_FLAG_DISPLAY_CHANGED, null /* packageName */);

        mController.onDisplayChanged(123);
        mHandler.runWithScissors(() -> { }, 0);

        verify(mListener).onDisplayChanged(123);
    }

    @Test
    public void testOnContextConfigurationChanged() {
        doNothing().when(mController).onDisplayChanged(anyInt());
        doReturn(123).when(mActivity).getDisplayId();

        // Not trigger onDisplayChanged when there is no change.
        mController.onContextConfigurationPreChanged(mActivity);
        mController.onContextConfigurationPostChanged(mActivity);

        verify(mController, never()).onDisplayChanged(anyInt());

        mController.onContextConfigurationPreChanged(mActivity);
        mConfiguration.windowConfiguration.setMaxBounds(new Rect(0, 0, 100, 200));
        mController.onContextConfigurationPostChanged(mActivity);

        verify(mController).onDisplayChanged(123);
    }

    @Test
    public void testOnContextConfigurationChanged_duringClientTransaction() {
        doNothing().when(mController).onDisplayChanged(anyInt());
        doReturn(123).when(mActivity).getDisplayId();

        // Not trigger onDisplayChanged until ClientTransaction finished execution.
        mController.onClientTransactionStarted();

        mController.onContextConfigurationPreChanged(mActivity);
        mConfiguration.windowConfiguration.setMaxBounds(new Rect(0, 0, 100, 200));
        mController.onContextConfigurationPostChanged(mActivity);

        verify(mController, never()).onDisplayChanged(anyInt());

        mController.onClientTransactionFinished();

        verify(mController).onDisplayChanged(123);
    }

    @Test
    public void testActivityWindowInfoChangedListener() {
        mSetFlagsRule.enableFlags(Flags.FLAG_ACTIVITY_WINDOW_INFO_FLAG);

        mController.registerActivityWindowInfoChangedListener(mActivityWindowInfoListener);
        final ActivityWindowInfo activityWindowInfo = new ActivityWindowInfo();
        activityWindowInfo.set(true /* isEmbedded */, new Rect(0, 0, 1000, 2000),
                new Rect(0, 0, 1000, 1000));
        mController.onActivityWindowInfoChanged(mActivityToken, activityWindowInfo);

        verify(mActivityWindowInfoListener).accept(mActivityToken, activityWindowInfo);

        clearInvocations(mActivityWindowInfoListener);
        mController.unregisterActivityWindowInfoChangedListener(mActivityWindowInfoListener);

        mController.onActivityWindowInfoChanged(mActivityToken, activityWindowInfo);

        verify(mActivityWindowInfoListener, never()).accept(any(), any());
    }

    @Test
    public void testWindowTokenClient_onConfigurationChanged() {
        mSetFlagsRule.enableFlags(FLAG_WINDOW_TOKEN_CONFIG_THREAD_SAFE);

        doNothing().when(mController).onContextConfigurationPreChanged(any());
        doNothing().when(mController).onContextConfigurationPostChanged(any());

        final WindowTokenClient windowTokenClient = spy(new WindowTokenClient());
        final Context context = mock(Context.class);
        windowTokenClient.attachContext(context);

        doReturn(mController).when(windowTokenClient).getClientTransactionListenerController();
        doNothing().when(windowTokenClient).onConfigurationChangedInner(any(), any(), anyInt(),
                anyBoolean());

        // Not trigger when shouldReportConfigChange is false.
        windowTokenClient.onConfigurationChanged(mConfiguration, 123 /* newDisplayId */,
                false /* shouldReportConfigChange*/);

        verify(mController, never()).onContextConfigurationPreChanged(any());
        verify(mController, never()).onContextConfigurationPostChanged(any());

        // Trigger in order when shouldReportConfigChange is true.
        clearInvocations(windowTokenClient);
        final InOrder inOrder = inOrder(mController, windowTokenClient);
        windowTokenClient.onConfigurationChanged(mConfiguration, 123 /* newDisplayId */,
                true /* shouldReportConfigChange*/);

        inOrder.verify(mController).onContextConfigurationPreChanged(context);
        inOrder.verify(windowTokenClient).onConfigurationChangedInner(context, mConfiguration,
                123 /* newDisplayId */, true /* shouldReportConfigChange*/);
        inOrder.verify(mController).onContextConfigurationPostChanged(context);
    }

    @Test
    public void testDisplayListenerHandlerClosed() {
        doReturn(123).when(mActivity).getDisplayId();
        doThrow(new RejectedExecutionException()).when(mController).onDisplayChanged(123);

        mController.onContextConfigurationPreChanged(mActivity);
        mConfiguration.windowConfiguration.setMaxBounds(new Rect(0, 0, 100, 200));
        mController.onContextConfigurationPostChanged(mActivity);

        // No crash
        verify(mController).onDisplayChanged(123);
    }
}
