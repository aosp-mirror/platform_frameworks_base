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

package com.android.server.inputmethod;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManagerInternal;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.content.res.Configuration;
import android.hardware.input.IInputManager;
import android.hardware.input.InputManagerGlobal;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.view.InputChannel;
import android.view.inputmethod.EditorInfo;
import android.window.ImeOnBackInvokedDispatcher;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.compat.IPlatformCompat;
import com.android.internal.inputmethod.IInputMethod;
import com.android.internal.inputmethod.IInputMethodClient;
import com.android.internal.inputmethod.IInputMethodSession;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;
import com.android.internal.inputmethod.IRemoteInputConnection;
import com.android.internal.inputmethod.InputBindResult;
import com.android.internal.view.IInputMethodManager;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.SystemService;
import com.android.server.input.InputManagerInternal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.ImeTargetVisibilityPolicy;
import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

/** Base class for testing {@link InputMethodManagerService}. */
public class InputMethodManagerServiceTestBase {
    private static final int NO_VERIFY_SHOW_FLAGS = -1;

    protected static final String TEST_SELECTED_IME_ID = "test.ime";
    protected static final String TEST_EDITOR_PKG_NAME = "test.editor";
    protected static final String TEST_FOCUSED_WINDOW_NAME = "test.editor/activity";
    protected static final WindowManagerInternal.ImeTargetInfo TEST_IME_TARGET_INFO =
            new WindowManagerInternal.ImeTargetInfo(
                    TEST_FOCUSED_WINDOW_NAME,
                    TEST_FOCUSED_WINDOW_NAME,
                    TEST_FOCUSED_WINDOW_NAME,
                    TEST_FOCUSED_WINDOW_NAME,
                    TEST_FOCUSED_WINDOW_NAME);
    protected static final InputBindResult SUCCESS_WAITING_IME_BINDING_RESULT =
            new InputBindResult(
                    InputBindResult.ResultCode.SUCCESS_WAITING_IME_BINDING,
                    null,
                    null,
                    null,
                    "0",
                    0,
                    false);

    @Mock protected WindowManagerInternal mMockWindowManagerInternal;
    @Mock protected ActivityManagerInternal mMockActivityManagerInternal;
    @Mock protected PackageManagerInternal mMockPackageManagerInternal;
    @Mock protected InputManagerInternal mMockInputManagerInternal;
    @Mock protected UserManagerInternal mMockUserManagerInternal;
    @Mock protected InputMethodBindingController mMockInputMethodBindingController;
    @Mock protected IInputMethodClient mMockInputMethodClient;
    @Mock protected IInputMethodSession mMockInputMethodSession;
    @Mock protected IBinder mWindowToken;
    @Mock protected IRemoteInputConnection mMockRemoteInputConnection;
    @Mock protected IRemoteAccessibilityInputConnection mMockRemoteAccessibilityInputConnection;
    @Mock protected ImeOnBackInvokedDispatcher mMockImeOnBackInvokedDispatcher;
    @Mock protected IInputMethodManager.Stub mMockIInputMethodManager;
    @Mock protected IPlatformCompat.Stub mMockIPlatformCompat;
    @Mock protected IInputMethod mMockInputMethod;
    @Mock protected IBinder mMockInputMethodBinder;
    @Mock protected IInputManager mMockIInputManager;
    @Mock protected ImeTargetVisibilityPolicy mMockImeTargetVisibilityPolicy;

    protected Context mContext;
    protected MockitoSession mMockingSession;
    protected int mTargetSdkVersion;
    protected int mCallingUserId;
    protected EditorInfo mEditorInfo;
    protected IInputMethodInvoker mMockInputMethodInvoker;
    protected InputMethodManagerService mInputMethodManagerService;
    protected ServiceThread mServiceThread;
    protected ServiceThread mPackageMonitorThread;
    protected boolean mIsLargeScreen;
    private InputManagerGlobal.TestSession mInputManagerGlobalSession;

    @BeforeClass
    public static void setupClass() {
        // Make sure DeviceConfig's lazy-initialized ContentProvider gets
        // a real instance before we stub out all system services below.
        // TODO(b/272229177): remove dependency on real ContentProvider
        new InputMethodDeviceConfigs().destroy();
    }

    @Before
    public void setUp() throws RemoteException {
        mMockingSession =
                mockitoSession()
                        .initMocks(this)
                        .strictness(Strictness.LENIENT)
                        .spyStatic(LocalServices.class)
                        .mockStatic(ServiceManager.class)
                        .mockStatic(SystemServerInitThreadPool.class)
                        .startMocking();

        mContext = spy(InstrumentationRegistry.getInstrumentation().getContext());

        mTargetSdkVersion = mContext.getApplicationInfo().targetSdkVersion;
        mIsLargeScreen = mContext.getResources().getConfiguration()
                .isLayoutSizeAtLeast(Configuration.SCREENLAYOUT_SIZE_LARGE);
        mCallingUserId = UserHandle.getCallingUserId();
        mEditorInfo = new EditorInfo();
        mEditorInfo.packageName = TEST_EDITOR_PKG_NAME;

        // Injecting and mocking local services.
        doReturn(mMockWindowManagerInternal)
                .when(() -> LocalServices.getService(WindowManagerInternal.class));
        doReturn(mMockActivityManagerInternal)
                .when(() -> LocalServices.getService(ActivityManagerInternal.class));
        doReturn(mMockPackageManagerInternal)
                .when(() -> LocalServices.getService(PackageManagerInternal.class));
        doReturn(mMockInputManagerInternal)
                .when(() -> LocalServices.getService(InputManagerInternal.class));
        doReturn(mMockUserManagerInternal)
                .when(() -> LocalServices.getService(UserManagerInternal.class));
        doReturn(mMockImeTargetVisibilityPolicy)
                .when(() -> LocalServices.getService(ImeTargetVisibilityPolicy.class));
        doReturn(mMockIInputMethodManager)
                .when(() -> ServiceManager.getServiceOrThrow(Context.INPUT_METHOD_SERVICE));
        doReturn(mMockIPlatformCompat)
                .when(() -> ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));

        // Stubbing out context related methods to avoid the system holding strong references to
        // InputMethodManagerService.
        doNothing().when(mContext).enforceCallingPermission(anyString(), anyString());
        doNothing().when(mContext).sendBroadcastAsUser(any(), any());
        doReturn(null).when(mContext).registerReceiver(any(), any());
        doReturn(null)
                .when(mContext)
                .registerReceiverAsUser(any(), any(), any(), anyString(), any(), anyInt());

        // Injecting and mocked InputMethodBindingController and InputMethod.
        mMockInputMethodInvoker = IInputMethodInvoker.create(mMockInputMethod);
        mInputManagerGlobalSession = InputManagerGlobal.createTestSession(mMockIInputManager);
        synchronized (ImfLock.class) {
            when(mMockInputMethodBindingController.getCurMethod())
                    .thenReturn(mMockInputMethodInvoker);
            when(mMockInputMethodBindingController.bindCurrentMethod())
                    .thenReturn(SUCCESS_WAITING_IME_BINDING_RESULT);
            doNothing().when(mMockInputMethodBindingController).unbindCurrentMethod();
            when(mMockInputMethodBindingController.getSelectedMethodId())
                    .thenReturn(TEST_SELECTED_IME_ID);
        }

        // Shuffling around all other initialization to make the test runnable.
        when(mMockIInputManager.getInputDeviceIds()).thenReturn(new int[0]);
        when(mMockIInputMethodManager.isImeTraceEnabled()).thenReturn(false);
        when(mMockIPlatformCompat.isChangeEnabledByUid(anyLong(), anyInt())).thenReturn(true);
        when(mMockUserManagerInternal.isUserRunning(anyInt())).thenReturn(true);
        when(mMockUserManagerInternal.getProfileIds(anyInt(), anyBoolean()))
                .thenReturn(new int[] {0});
        when(mMockUserManagerInternal.getUserIds()).thenReturn(new int[] {0});
        when(mMockActivityManagerInternal.isSystemReady()).thenReturn(true);
        when(mMockActivityManagerInternal.getCurrentUserId()).thenReturn(mCallingUserId);
        when(mMockPackageManagerInternal.getPackageUid(anyString(), anyLong(), anyInt()))
                .thenReturn(Binder.getCallingUid());
        when(mMockPackageManagerInternal.isSameApp(anyString(), anyLong(), anyInt(), anyInt()))
            .thenReturn(true);
        when(mMockWindowManagerInternal.onToggleImeRequested(anyBoolean(), any(), any(), anyInt()))
                .thenReturn(TEST_IME_TARGET_INFO);
        when(mMockInputMethodClient.asBinder()).thenReturn(mMockInputMethodBinder);

        // Used by lazy initializing draw IMS nav bar at InputMethodManagerService#systemRunning(),
        // which is ok to be mocked out for now.
        doReturn(null).when(() -> SystemServerInitThreadPool.submit(any(), anyString()));

        mServiceThread =
                new ServiceThread(
                        "immstest1",
                        Process.THREAD_PRIORITY_FOREGROUND,
                        true /* allowIo */);
        mPackageMonitorThread =
                new ServiceThread(
                        "immstest2",
                        Process.THREAD_PRIORITY_FOREGROUND,
                        true /* allowIo */);
        mInputMethodManagerService = new InputMethodManagerService(mContext, mServiceThread,
                mPackageMonitorThread, unusedUserId -> mMockInputMethodBindingController);
        spyOn(mInputMethodManagerService);

        // Start a InputMethodManagerService.Lifecycle to publish and manage the lifecycle of
        // InputMethodManagerService, which is closer to the real situation.
        InputMethodManagerService.Lifecycle lifecycle =
                new InputMethodManagerService.Lifecycle(mContext, mInputMethodManagerService);

        // Public local InputMethodManagerService.
        LocalServices.removeServiceForTest(InputMethodManagerInternal.class);
        lifecycle.onStart();
        try {
            // After this boot phase, services can broadcast Intents.
            lifecycle.onBootPhase(SystemService.PHASE_ACTIVITY_MANAGER_READY);
        } catch (SecurityException e) {
            // Security exception to permission denial is expected in test, mocking out to ensure
            // InputMethodManagerService as system ready state.
            if (!e.getMessage().contains("Permission Denial: not allowed to send broadcast")) {
                throw e;
            }
        }

        // Call InputMethodManagerService#addClient() as a preparation to start interacting with it.
        mInputMethodManagerService.addClient(mMockInputMethodClient, mMockRemoteInputConnection, 0);
        createSessionForClient(mMockInputMethodClient);
    }

    @After
    public void tearDown() {
        if (mInputMethodManagerService != null) {
            mInputMethodManagerService.mInputMethodDeviceConfigs.destroy();
        }

        if (mPackageMonitorThread != null) {
            mPackageMonitorThread.quitSafely();
        }

        if (mServiceThread != null) {
            mServiceThread.quitSafely();
        }

        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }

        if (mInputManagerGlobalSession != null) {
            mInputManagerGlobalSession.close();
        }
        LocalServices.removeServiceForTest(InputMethodManagerInternal.class);
    }

    protected void verifyShowSoftInput(boolean setVisible, boolean showSoftInput)
            throws RemoteException {
        verifyShowSoftInput(setVisible, showSoftInput, NO_VERIFY_SHOW_FLAGS);
    }

    protected void verifyShowSoftInput(boolean setVisible, boolean showSoftInput, int showFlags)
            throws RemoteException {
        synchronized (ImfLock.class) {
            verify(mMockInputMethodBindingController, times(setVisible ? 1 : 0))
                    .setCurrentMethodVisible();
        }
        verify(mMockInputMethod, times(showSoftInput ? 1 : 0))
                .showSoftInput(any() /* showInputToken */ , notNull() /* statsToken */,
                        showFlags != NO_VERIFY_SHOW_FLAGS ? eq(showFlags) : anyInt() /* flags*/,
                        any() /* resultReceiver */);
    }

    protected void verifyHideSoftInput(boolean setNotVisible, boolean hideSoftInput)
            throws RemoteException {
        synchronized (ImfLock.class) {
            verify(mMockInputMethodBindingController, times(setNotVisible ? 1 : 0))
                    .setCurrentMethodNotVisible();
        }
        verify(mMockInputMethod, times(hideSoftInput ? 1 : 0))
                .hideSoftInput(any() /* hideInputToken */, notNull() /* statsToken */,
                        anyInt() /* flags */, any() /* resultReceiver */);
    }

    protected void createSessionForClient(IInputMethodClient client) {
        synchronized (ImfLock.class) {
            ClientState cs = mInputMethodManagerService.getClientStateLocked(client);
            cs.mCurSession = new InputMethodManagerService.SessionState(cs,
                    mMockInputMethodInvoker, mMockInputMethodSession, mock(
                    InputChannel.class));
        }
    }
}
