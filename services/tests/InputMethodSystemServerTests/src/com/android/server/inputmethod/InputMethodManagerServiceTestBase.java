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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManagerInternal;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.res.Configuration;
import android.hardware.input.IInputManager;
import android.hardware.input.InputManagerGlobal;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArraySet;
import android.view.InputChannel;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ImeTracker;
import android.window.ImeOnBackInvokedDispatcher;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.compat.IPlatformCompat;
import com.android.internal.inputmethod.DirectBootAwareness;
import com.android.internal.inputmethod.IInputMethod;
import com.android.internal.inputmethod.IInputMethodClient;
import com.android.internal.inputmethod.IInputMethodSession;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;
import com.android.internal.inputmethod.IRemoteInputConnection;
import com.android.internal.inputmethod.InputBindResult;
import com.android.internal.view.IInputMethodManager;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
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
    protected int mUserId;
    protected EditorInfo mEditorInfo;
    protected IInputMethodInvoker mMockInputMethodInvoker;
    protected InputMethodManagerService mInputMethodManagerService;
    protected ServiceThread mServiceThread;
    protected ServiceThread mIoThread;
    protected boolean mIsLargeScreen;
    private InputManagerGlobal.TestSession mInputManagerGlobalSession;

    private final ArraySet<Class<?>> mRegisteredLocalServices = new ArraySet<>();

    protected <T> void addLocalServiceMock(Class<T> type, T service) {
        mRegisteredLocalServices.add(type);
        LocalServices.removeServiceForTest(type);
        LocalServices.addService(type, service);
    }

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
                        .spyStatic(InputMethodUtils.class)
                        .mockStatic(ServiceManager.class)
                        .spyStatic(AdditionalSubtypeMapRepository.class)
                        .spyStatic(AdditionalSubtypeUtils.class)
                        .startMocking();

        mContext = spy(InstrumentationRegistry.getInstrumentation().getTargetContext());

        mTargetSdkVersion = mContext.getApplicationInfo().targetSdkVersion;
        mIsLargeScreen = mContext.getResources().getConfiguration()
                .isLayoutSizeAtLeast(Configuration.SCREENLAYOUT_SIZE_LARGE);
        mUserId = mContext.getUserId();
        mEditorInfo = new EditorInfo();
        mEditorInfo.packageName = TEST_EDITOR_PKG_NAME;

        // Injecting and mocking local services.
        addLocalServiceMock(WindowManagerInternal.class, mMockWindowManagerInternal);
        addLocalServiceMock(ActivityManagerInternal.class, mMockActivityManagerInternal);
        addLocalServiceMock(PackageManagerInternal.class, mMockPackageManagerInternal);
        addLocalServiceMock(InputManagerInternal.class, mMockInputManagerInternal);
        addLocalServiceMock(UserManagerInternal.class, mMockUserManagerInternal);
        addLocalServiceMock(ImeTargetVisibilityPolicy.class, mMockImeTargetVisibilityPolicy);

        doReturn(mMockIInputMethodManager)
                .when(() -> ServiceManager.getServiceOrThrow(Context.INPUT_METHOD_SERVICE));
        doReturn(mMockIPlatformCompat)
                .when(() -> ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));

        // Stubbing out context related methods to avoid the system holding strong references to
        // InputMethodManagerService.
        doNothing().when(mContext).enforceCallingPermission(anyString(), anyString());
        doNothing().when(mContext).sendBroadcastAsUser(any(), any());
        doReturn(null).when(mContext).registerReceiver(any(), any());
        doReturn(null).when(mContext).registerReceiver(
                any(BroadcastReceiver.class),
                any(IntentFilter.class), anyString(), any(Handler.class));
        doReturn(null)
                .when(mContext)
                .registerReceiverAsUser(any(), any(), any(), anyString(), any(), anyInt());

        // Injecting and mocked InputMethodBindingController and InputMethod.
        mMockInputMethodInvoker = IInputMethodInvoker.create(mMockInputMethod);
        mInputManagerGlobalSession = InputManagerGlobal.createTestSession(mMockIInputManager);
        when(mMockInputMethodBindingController.getUserId()).thenReturn(mUserId);
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
        when(mMockActivityManagerInternal.getCurrentUserId()).thenReturn(mUserId);
        when(mMockPackageManagerInternal.getPackageUid(anyString(), anyLong(), anyInt()))
                .thenReturn(Binder.getCallingUid());
        when(mMockPackageManagerInternal.isSameApp(anyString(), anyLong(), anyInt(), anyInt()))
            .thenReturn(true);
        when(mMockWindowManagerInternal.onToggleImeRequested(anyBoolean(), any(), any(), anyInt()))
                .thenReturn(TEST_IME_TARGET_INFO);
        when(mMockInputMethodClient.asBinder()).thenReturn(mMockInputMethodBinder);

        // This changes the real IME component state. Not appropriate to do in tests.
        doNothing().when(() -> InputMethodUtils.setNonSelectedSystemImesDisabledUntilUsed(
                        any(PackageManager.class), anyList()));

        // The background writer thread in AdditionalSubtypeMapRepository should be stubbed out.
        doNothing().when(AdditionalSubtypeMapRepository::startWriterThread);
        doReturn(AdditionalSubtypeMap.EMPTY_MAP).when(() -> AdditionalSubtypeUtils.load(anyInt()));

        mServiceThread =
                new ServiceThread(
                        "immstest1",
                        Process.THREAD_PRIORITY_FOREGROUND,
                        true /* allowIo */);
        mServiceThread.start();
        mIoThread =
                new ServiceThread(
                        "immstest2",
                        Process.THREAD_PRIORITY_FOREGROUND,
                        true /* allowIo */);
        mIoThread.start();

        final var ioHandler = spy(Handler.createAsync(mIoThread.getLooper()));
        doReturn(true).when(ioHandler).post(any());

        mInputMethodManagerService = new InputMethodManagerService(mContext,
                InputMethodManagerService.shouldEnableConcurrentMultiUserMode(mContext),
                mServiceThread.getLooper(), ioHandler,
                unusedUserId -> mMockInputMethodBindingController);
        spyOn(mInputMethodManagerService);

        synchronized (ImfLock.class) {
            doReturn(true).when(mInputMethodManagerService).setImeVisibilityOnFocusedWindowClient(
                    anyBoolean(), any(UserData.class), any(ImeTracker.Token.class));
        }

        // Start a InputMethodManagerService.Lifecycle to publish and manage the lifecycle of
        // InputMethodManagerService, which is closer to the real situation.
        InputMethodManagerService.Lifecycle lifecycle =
                new InputMethodManagerService.Lifecycle(mContext, mInputMethodManagerService);

        // Public local InputMethodManagerService.
        LocalServices.removeServiceForTest(InputMethodManagerInternal.class);
        lifecycle.onStart();

        final var userData = mInputMethodManagerService.getUserData(mUserId);

        // Certain tests rely on TEST_IME_ID that is installed with AndroidTest.xml.
        // TODO(b/352615651): Consider just synthesizing test InputMethodInfo then injecting it.
        AdditionalSubtypeMapRepository.initializeIfNecessary(mUserId);
        final var rawMethodMap = InputMethodManagerService.queryRawInputMethodServiceMap(mContext,
                mUserId);
        userData.mRawInputMethodMap.set(rawMethodMap);
        final var settings = InputMethodSettings.create(rawMethodMap.toInputMethodMap(
                AdditionalSubtypeMap.EMPTY_MAP, DirectBootAwareness.AUTO, true /* userUnlocked */),
                mUserId);
        InputMethodSettingsRepository.put(mUserId, settings);

        // Emulate that the user initialization is done.
        userData.mBackgroundLoadLatch.countDown();

        // After this boot phase, services can broadcast Intents.
        lifecycle.onBootPhase(SystemService.PHASE_ACTIVITY_MANAGER_READY);

        // Call InputMethodManagerService#addClient() as a preparation to start interacting with it.
        mInputMethodManagerService.addClient(mMockInputMethodClient, mMockRemoteInputConnection, 0);
        createSessionForClient(mMockInputMethodClient);
    }

    @After
    public void tearDown() {
        InputMethodSettingsRepository.remove(mUserId);

        if (mInputMethodManagerService != null) {
            mInputMethodManagerService.mInputMethodDeviceConfigs.destroy();
        }

        if (mIoThread != null) {
            mIoThread.quitSafely();
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
        mRegisteredLocalServices.forEach(LocalServices::removeServiceForTest);
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

    protected void verifySetImeVisibility(boolean setVisible, boolean invoked) {
        synchronized (ImfLock.class) {
            verify(mInputMethodManagerService,
                    times(invoked ? 1 : 0)).setImeVisibilityOnFocusedWindowClient(eq(setVisible),
                    any(UserData.class), any(ImeTracker.Token.class));
        }
    }

    protected void createSessionForClient(IInputMethodClient client) {
        synchronized (ImfLock.class) {
            ClientState cs = mInputMethodManagerService.getClientStateLocked(client);
            cs.mCurSession = new InputMethodManagerService.SessionState(cs,
                    mMockInputMethodInvoker, mMockInputMethodSession, mock(InputChannel.class),
                    mUserId);
        }
    }
}
