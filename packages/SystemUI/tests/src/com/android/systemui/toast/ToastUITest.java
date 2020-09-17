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
import static android.widget.ToastPresenter.TEXT_TOAST_LAYOUT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.ITransientNotificationCallback;
import android.content.Context;
import android.os.Binder;
import android.os.Parcel;
import android.os.Parcelable;
import android.testing.AndroidTestingRunner;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.IAccessibilityManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToastPresenter;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.util.IntPair;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.CommandQueue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ToastUITest extends SysuiTestCase {
    private static final int ANDROID_UID = 1000;
    private static final int SYSTEMUI_UID = 10140;

    private static final int UID_1 = 10255;
    private static final String PACKAGE_NAME_1 = "com.example1.test";
    private static final Binder TOKEN_1 = new Binder();
    private static final Binder WINDOW_TOKEN_1 = new Binder();

    private static final int UID_2 = 10256;
    private static final String PACKAGE_NAME_2 = "com.example2.test";
    private static final Binder TOKEN_2 = new Binder();
    private static final Binder WINDOW_TOKEN_2 = new Binder();

    private static final String TEXT = "Hello World";
    private static final int MESSAGE_RES_ID = R.id.message;

    private Context mContextSpy;
    private ToastUI mToastUI;
    @Mock private LayoutInflater mLayoutInflater;
    @Mock private CommandQueue mCommandQueue;
    @Mock private WindowManager mWindowManager;
    @Mock private INotificationManager mNotificationManager;
    @Mock private IAccessibilityManager mAccessibilityManager;
    @Mock private ITransientNotificationCallback mCallback;
    @Captor private ArgumentCaptor<View> mViewCaptor;
    @Captor private ArgumentCaptor<ViewGroup.LayoutParams> mParamsCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        // This is because inflate will result in WindowManager (WM) calls, which will fail since we
        // are mocking it, so we mock LayoutInflater with the view obtained before mocking WM.
        View view = ToastPresenter.getTextToastView(mContext, TEXT);
        when(mLayoutInflater.inflate(eq(TEXT_TOAST_LAYOUT), any())).thenReturn(view);
        mContext.addMockSystemService(LayoutInflater.class, mLayoutInflater);

        mContext.addMockSystemService(WindowManager.class, mWindowManager);
        mContextSpy = spy(mContext);
        doReturn(mContextSpy).when(mContextSpy).createContextAsUser(any(), anyInt());

        mToastUI = new ToastUI(mContextSpy, mCommandQueue, mNotificationManager,
                mAccessibilityManager);
    }

    @Test
    public void testStart_addToastUIAsCallbackToCommandQueue() throws Exception {
        mToastUI.start();

        verify(mCommandQueue).addCallback(mToastUI);
    }

    @Test
    public void testShowToast_addsCorrectViewToWindowManager() throws Exception {
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                null);

        verify(mWindowManager).addView(mViewCaptor.capture(), any());
        View view = mViewCaptor.getValue();
        assertThat(((TextView) view.findViewById(MESSAGE_RES_ID)).getText()).isEqualTo(TEXT);
    }

    @Test
    public void testShowToast_addsViewWithCorrectLayoutParamsToWindowManager() throws Exception {
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                null);

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
                null);

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
                Toast.LENGTH_LONG, null);

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
                mCallback);

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
                null);

        eventParcel.setDataPosition(0);
        assertThat(eventParcel.dataSize()).isGreaterThan(0);
        AccessibilityEvent event = AccessibilityEvent.CREATOR.createFromParcel(eventParcel);
        assertThat(event.getEventType()).isEqualTo(
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
        assertThat(event.getClassName()).isEqualTo(Toast.class.getName());
        assertThat(event.getPackageName()).isEqualTo(PACKAGE_NAME_1);
    }

    @Test
    public void testHideToast_removesView() throws Exception {
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback);
        View view = verifyWmAddViewAndAttachToParent();

        mToastUI.hideToast(PACKAGE_NAME_1, TOKEN_1);

        verify(mWindowManager).removeViewImmediate(view);
    }

    @Test
    public void testHideToast_finishesToken() throws Exception {
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback);

        mToastUI.hideToast(PACKAGE_NAME_1, TOKEN_1);

        verify(mNotificationManager).finishToken(PACKAGE_NAME_1, TOKEN_1);
    }

    @Test
    public void testHideToast_callsCallback() throws Exception {
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback);

        mToastUI.hideToast(PACKAGE_NAME_1, TOKEN_1);

        verify(mCallback).onToastHidden();
    }

    @Test
    public void testHideToast_whenNotCurrentToastToken_doesNotHideToast() throws Exception {
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback);

        mToastUI.hideToast(PACKAGE_NAME_1, TOKEN_2);

        verify(mCallback, never()).onToastHidden();
    }

    @Test
    public void testHideToast_whenNotCurrentToastPackage_doesNotHideToast() throws Exception {
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback);

        mToastUI.hideToast(PACKAGE_NAME_2, TOKEN_1);

        verify(mCallback, never()).onToastHidden();
    }

    @Test
    public void testShowToast_afterShowToast_hidesCurrentToast() throws Exception {
        mToastUI.showToast(UID_1, PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback);
        View view = verifyWmAddViewAndAttachToParent();

        mToastUI.showToast(UID_2, PACKAGE_NAME_2, TOKEN_2, TEXT, WINDOW_TOKEN_2, Toast.LENGTH_LONG,
                null);

        verify(mWindowManager).removeViewImmediate(view);
        verify(mNotificationManager).finishToken(PACKAGE_NAME_1, TOKEN_1);
        verify(mCallback).onToastHidden();
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
}
