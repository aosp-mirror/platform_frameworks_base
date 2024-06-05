/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.toast;

import static android.view.accessibility.AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED;

import static com.android.systemui.log.LogBufferHelperKt.logcatLogBuffer;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.app.INotificationManager;
import android.app.ITransientNotificationCallback;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.testing.TestableLooper;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.IAccessibilityManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.util.IntPair;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.CommandQueue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.util.Arrays;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class ToastUITest extends SysuiTestCase {
    private static final int ANDROID_UID = 1000;
    private static final int SYSTEMUI_UID = 10140;

    private static final int UID_1 = 10255;
    private static final String PACKAGE_NAME_1 = "com.example1.test";
    private static final Binder TOKEN_1 = new Binder();
    private static final Binder WINDOW_TOKEN_1 = new Binder();
    private static final int USER_ID = 1;

    private static final int UID_2 = 10256;
    private static final String PACKAGE_NAME_2 = "com.example2.test";
    private static final Binder TOKEN_2 = new Binder();
    private static final Binder WINDOW_TOKEN_2 = new Binder();

    private static final String TEXT = "Hello World";
    private static final int MESSAGE_RES_ID = R.id.text;

    private Context mContextSpy;
    private ToastUI mToastUI;
    private View mToastView;
    @Mock private Application mApplication;
    @Mock private CommandQueue mCommandQueue;
    @Mock private LayoutInflater mLayoutInflater;
    @Mock private WindowManager mWindowManager;
    @Mock private INotificationManager mNotificationManager;
    @Mock private IAccessibilityManager mAccessibilityManager;
    @Mock private PluginManager mPluginManager;
    @Mock private DumpManager mDumpManager;
    private final ToastLogger mToastLogger = spy(new ToastLogger(logcatLogBuffer()));
    @Mock private PackageManager mPackageManager;

    @Mock private ITransientNotificationCallback mCallback;
    @Captor private ArgumentCaptor<View> mViewCaptor;
    @Captor private ArgumentCaptor<ViewGroup.LayoutParams> mParamsCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mToastView = LayoutInflater.from(mContext).inflate(R.layout.text_toast, null);
        when(mLayoutInflater.inflate(anyInt(), eq(null))).thenReturn(mToastView);
        mContext.addMockSystemService(WindowManager.class, mWindowManager);
        mContextSpy = spy(mContext);
        when(mContextSpy.getPackageManager()).thenReturn(mPackageManager);
        doReturn(mContextSpy).when(mContextSpy).createContextAsUser(any(), anyInt());
        doReturn(mContextSpy).when(mContextSpy).createDisplayContext(any());
        mToastUI = new ToastUI(
                mContextSpy,
                mCommandQueue,
                mNotificationManager,
                mAccessibilityManager,
                new ToastFactory(
                        mLayoutInflater,
                        mPluginManager,
                        mDumpManager),
                mToastLogger);
    }

    @Test
    public void testStart_addToastUIAsCallbackToCommandQueue() {
        mToastUI.start();

        verify(mCommandQueue).addCallback(mToastUI);
    }

    @Test
    public void testShowToast_addsCorrectViewToWindowManager() {
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                null, Display.DEFAULT_DISPLAY);

        verify(mWindowManager).addView(mViewCaptor.capture(), any());
        View view = mViewCaptor.getValue();
        assertThat(((TextView) view.findViewById(MESSAGE_RES_ID)).getText()).isEqualTo(TEXT);
    }

    @Test
    public void testShowToast_addsViewWithCorrectLayoutParamsToWindowManager() {
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                null, Display.DEFAULT_DISPLAY);

        verify(mWindowManager).addView(any(), mParamsCaptor.capture());
        ViewGroup.LayoutParams params = mParamsCaptor.getValue();
        assertThat(params).isInstanceOf(WindowManager.LayoutParams.class);
        WindowManager.LayoutParams windowParams = (WindowManager.LayoutParams) params;
        assertThat(windowParams.packageName).isEqualTo(mContextSpy.getPackageName());
        assertThat(windowParams.getTitle()).isEqualTo("Toast");
        assertThat(windowParams.token).isEqualTo(WINDOW_TOKEN_1);
        assertThat(windowParams.privateFlags
                & WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS).isEqualTo(0);
    }

    @Test
    public void testShowToast_forAndroidPackage_addsAllUserFlag() throws Exception {
        mToastUI.showToast(ANDROID_UID, "android", TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                null, Display.DEFAULT_DISPLAY);

        verify(mWindowManager).addView(any(), mParamsCaptor.capture());
        ViewGroup.LayoutParams params = mParamsCaptor.getValue();
        assertThat(params).isInstanceOf(WindowManager.LayoutParams.class);
        WindowManager.LayoutParams windowParams = (WindowManager.LayoutParams) params;
        assertThat(windowParams.privateFlags
                & WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS).isNotEqualTo(0);
    }

    @Test
    public void testShowToast_forSystemUiPackage_addsAllUserFlag() throws Exception {
        mToastUI.showToast(SYSTEMUI_UID, "com.android.systemui", TOKEN_1, TEXT, WINDOW_TOKEN_1,
                Toast.LENGTH_LONG, null, Display.DEFAULT_DISPLAY);

        verify(mWindowManager).addView(any(), mParamsCaptor.capture());
        ViewGroup.LayoutParams params = mParamsCaptor.getValue();
        assertThat(params).isInstanceOf(WindowManager.LayoutParams.class);
        WindowManager.LayoutParams windowParams = (WindowManager.LayoutParams) params;
        assertThat(windowParams.privateFlags
                & WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS).isNotEqualTo(0);
    }

    @Test
    public void testShowToast_callsCallback() throws Exception {
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback, Display.DEFAULT_DISPLAY);

        verify(mCallback).onToastShown();
    }

    @Test
    public void testShowToast_sendsAccessibilityEvent() throws Exception {
        // Enable accessibility
        when(mAccessibilityManager.addClient(any(), anyInt())).thenReturn(
                IntPair.of(STATE_FLAG_ACCESSIBILITY_ENABLED, AccessibilityEvent.TYPES_ALL_MASK));
        // AccessibilityManager recycles the event that goes over the wire after making the binder
        // call to the service. Since we are mocking the service, that call is local, so if we use
        // ArgumentCaptor or ArgumentMatcher it will retain a reference to the recycled event, which
        // will already have its state reset by the time we verify its contents. So, instead, we
        // serialize it at call-time and later on deserialize it to verity its contents.
        Parcel eventParcel = Parcel.obtain();
        doAnswer(writeArgumentToParcel(0, eventParcel)).when(
                mAccessibilityManager).sendAccessibilityEvent(any(), anyInt());

        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                null, Display.DEFAULT_DISPLAY);

        eventParcel.setDataPosition(0);
        assertThat(eventParcel.dataSize()).isGreaterThan(0);
        AccessibilityEvent event = AccessibilityEvent.CREATOR.createFromParcel(eventParcel);
        assertThat(event.getEventType()).isEqualTo(
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
        assertThat(event.getClassName()).isEqualTo(Toast.class.getName());
        assertThat(event.getPackageName()).isEqualTo(PACKAGE_NAME_1);
    }

    @Test
    public void testShowToast_accessibilityManagerClientIsRemoved() throws Exception {
        when(mContextSpy.getUserId()).thenReturn(USER_ID);
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                null, Display.DEFAULT_DISPLAY);
        verify(mAccessibilityManager).removeClient(any(), eq(USER_ID));
    }

    @Test
    public void testHideToast_removesView() throws Exception {
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback, Display.DEFAULT_DISPLAY);
        final SystemUIToast toast = mToastUI.mToast;

        View view = verifyWmAddViewAndAttachToParent();
        mToastUI.hideToast(PACKAGE_NAME_1, TOKEN_1);
        if (toast.getOutAnimation() != null) {
            assertThat(toast.getOutAnimation().isRunning()).isTrue();
            toast.getOutAnimation().cancel(); // if applicable, try to finish anim early
        }

        verify(mWindowManager).removeViewImmediate(view);
    }

    @Test
    public void testHideToast_finishesToken() throws Exception {
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback, Display.DEFAULT_DISPLAY);
        final SystemUIToast toast = mToastUI.mToast;

        verifyWmAddViewAndAttachToParent();
        mToastUI.hideToast(PACKAGE_NAME_1, TOKEN_1);
        if (toast.getOutAnimation() != null) {
            assertThat(toast.getOutAnimation().isRunning()).isTrue();
            toast.getOutAnimation().cancel(); // if applicable, try to finish anim early
        }

        verify(mNotificationManager).finishToken(PACKAGE_NAME_1, TOKEN_1);
    }

    @Test
    public void testHideToast_callsCallback() throws RemoteException {
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback, Display.DEFAULT_DISPLAY);
        final SystemUIToast toast = mToastUI.mToast;

        verifyWmAddViewAndAttachToParent();
        mToastUI.hideToast(PACKAGE_NAME_1, TOKEN_1);
        if (toast.getOutAnimation() != null) {
            assertThat(toast.getOutAnimation().isRunning()).isTrue();
            toast.getOutAnimation().cancel();
        }

        verify(mCallback).onToastHidden();
    }

    @Test
    public void testHideToast_whenNotCurrentToastToken_doesNotHideToast() throws RemoteException {
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback, Display.DEFAULT_DISPLAY);
        final SystemUIToast toast = mToastUI.mToast;

        verifyWmAddViewAndAttachToParent();
        mToastUI.hideToast(PACKAGE_NAME_1, TOKEN_2);

        if (toast.getOutAnimation() != null) {
            assertThat(toast.getOutAnimation().isRunning()).isFalse();
        }

        verify(mCallback, never()).onToastHidden();
    }

    @Test
    public void testHideToast_whenNotCurrentToastPackage_doesNotHideToast() throws RemoteException {
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback, Display.DEFAULT_DISPLAY);
        final SystemUIToast toast = mToastUI.mToast;

        verifyWmAddViewAndAttachToParent();
        mToastUI.hideToast(PACKAGE_NAME_2, TOKEN_1);

        if (toast.getOutAnimation() != null) {
            assertThat(toast.getOutAnimation().isRunning()).isFalse();
        }

        verify(mCallback, never()).onToastHidden();
    }

    @Test
    public void testShowToast_afterShowToast_hidesCurrentToast() throws RemoteException {
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback, Display.DEFAULT_DISPLAY);
        final SystemUIToast toast = mToastUI.mToast;

        View view = verifyWmAddViewAndAttachToParent();
        mToastUI.showToast(UID_2, PACKAGE_NAME_2, TOKEN_2, TEXT, WINDOW_TOKEN_2, Toast.LENGTH_LONG,
                null, Display.DEFAULT_DISPLAY);

        if (toast.getOutAnimation() != null) {
            assertThat(toast.getOutAnimation().isRunning()).isTrue();
            toast.getOutAnimation().cancel(); // end early if applicable
        }

        verify(mWindowManager).removeViewImmediate(view);
        verify(mNotificationManager).finishToken(PACKAGE_NAME_1, TOKEN_1);
        verify(mCallback).onToastHidden();
    }

    @Test
    public void testShowToast_afterShowToast_animationListenerCleanup() throws RemoteException {
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback, Display.DEFAULT_DISPLAY);
        final SystemUIToast toast = mToastUI.mToast;

        View view = verifyWmAddViewAndAttachToParent();
        mToastUI.showToast(UID_2, PACKAGE_NAME_2, TOKEN_2, TEXT, WINDOW_TOKEN_2, Toast.LENGTH_LONG,
                null, Display.DEFAULT_DISPLAY);

        if (toast.getOutAnimation() != null) {
            assertThat(mToastUI.mToastOutAnimatorListener).isNotNull();
            assertThat(toast.getOutAnimation().getListeners()
                .contains(mToastUI.mToastOutAnimatorListener)).isTrue();
            assertThat(toast.getOutAnimation().isRunning()).isTrue();
            toast.getOutAnimation().cancel(); // end early if applicable
            assertThat(toast.getOutAnimation().getListeners()).isNull();
        }

        verify(mWindowManager).removeViewImmediate(view);
        verify(mNotificationManager).finishToken(PACKAGE_NAME_1, TOKEN_1);
        verify(mCallback).onToastHidden();
        assertThat(mToastUI.mToastOutAnimatorListener).isNull();
    }

    @Test
    public void testShowToast_logs() {
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback, Display.DEFAULT_DISPLAY);

        verify(mToastLogger).logOnShowToast(UID_1, PACKAGE_NAME_1, TEXT, TOKEN_1.toString());
    }

    @Test
    public void testShowToast_targetsPreS_unlimitedLines_noAppIcon()
            throws PackageManager.NameNotFoundException {
        // GIVEN the application targets R
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.targetSdkVersion = Build.VERSION_CODES.R;
        when(mPackageManager.getApplicationInfoAsUser(PACKAGE_NAME_1, 0,
                UserHandle.getUserHandleForUid(UID_1).getIdentifier())).thenReturn(applicationInfo);

        // WHEN the package posts a toast
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback, Display.DEFAULT_DISPLAY);

        // THEN the view can have unlimited lines
        assertThat(((TextView) mToastUI.mToast.getView()
                .findViewById(com.android.systemui.res.R.id.text))
                .getMaxLines()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    public void testShowToast_targetsS_twoLineLimit_noAppIcon()
            throws PackageManager.NameNotFoundException {
        // GIVEN the application targets S
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.targetSdkVersion = Build.VERSION_CODES.S;
        when(mPackageManager.getApplicationInfoAsUser(PACKAGE_NAME_1, 0,
                UserHandle.getUserHandleForUid(UID_1).getIdentifier())).thenReturn(applicationInfo);

        // WHEN the package posts a toast
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback, Display.DEFAULT_DISPLAY);

        // THEN the view is limited to 2 lines
        assertThat(((TextView) mToastUI.mToast.getView()
                .findViewById(com.android.systemui.res.R.id.text))
                .getMaxLines()).isEqualTo(2);
    }

    @Test
    public void testHideToast_logs() {
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback, Display.DEFAULT_DISPLAY);
        verifyWmAddViewAndAttachToParent();
        mToastUI.hideToast(PACKAGE_NAME_1, TOKEN_1);
        verify(mToastLogger).logOnHideToast(PACKAGE_NAME_1, TOKEN_1.toString());
    }

    @Test
    public void testHideToast_error_noLog() {
        // no toast was shown, so this hide is invalid
        mToastUI.hideToast(PACKAGE_NAME_1, TOKEN_1);
        assertThat(mToastUI.mToast).isNull();
        verify(mToastLogger, never()).logOnHideToast(PACKAGE_NAME_1, TOKEN_1.toString());
    }

    @Test
    public void testShowToast_invalidDisplayId_logsAndSkipsToast() {
        int invalidDisplayId = getInvalidDisplayId();

        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback, invalidDisplayId);

        verify(mToastLogger).logOnSkipToastForInvalidDisplay(PACKAGE_NAME_1, TOKEN_1.toString(),
                invalidDisplayId);
        verifyZeroInteractions(mWindowManager);
    }

    private View verifyWmAddViewAndAttachToParent() {
        ArgumentCaptor<View> viewCaptor = ArgumentCaptor.forClass(View.class);
        verify(mWindowManager).addView(viewCaptor.capture(), any());
        View view = viewCaptor.getValue();
        // Simulate attaching to view hierarchy
        ViewGroup parent = new FrameLayout(mContextSpy);
        parent.addView(view);
        return view;
    }

    private Answer<Void> writeArgumentToParcel(int i, Parcel dest) {
        return inv -> {
            inv.<Parcelable>getArgument(i).writeToParcel(dest, 0);
            return null;
        };
    }

    private int getInvalidDisplayId() {
        return Arrays.stream(
                mContext.getSystemService(DisplayManager.class).getDisplays())
                .map(Display::getDisplayId).max(Integer::compare).get() + 1;
    }
}
