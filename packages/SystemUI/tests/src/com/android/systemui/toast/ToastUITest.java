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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.ITransientNotificationCallback;
import android.os.Binder;
import android.testing.AndroidTestingRunner;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.CommandQueue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ToastUITest extends SysuiTestCase {
    private static final String PACKAGE_NAME_1 = "com.example1.test";
    private static final Binder TOKEN_1 = new Binder();
    private static final Binder WINDOW_TOKEN_1 = new Binder();
    private static final String PACKAGE_NAME_2 = "com.example2.test";
    private static final Binder TOKEN_2 = new Binder();
    private static final Binder WINDOW_TOKEN_2 = new Binder();
    private static final String TEXT = "Hello World";
    private static final int MESSAGE_RES_ID = R.id.message;

    @Mock private CommandQueue mCommandQueue;
    @Mock private WindowManager mWindowManager;
    @Mock private INotificationManager mNotificationManager;
    @Mock private AccessibilityManager mAccessibilityManager;
    @Mock private ITransientNotificationCallback mCallback;
    @Captor private ArgumentCaptor<View> mViewCaptor;
    @Captor private ArgumentCaptor<ViewGroup.LayoutParams> mParamsCaptor;
    private ToastUI mToastUI;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mToastUI = new ToastUI(mContext, mCommandQueue, mWindowManager, mNotificationManager,
                mAccessibilityManager);
    }

    @Test
    public void testStart_addToastUIAsCallbackToCommandQueue() throws Exception {
        mToastUI.start();

        verify(mCommandQueue).addCallback(mToastUI);
    }

    @Test
    public void testShowToast_addsCorrectViewToWindowManager() throws Exception {
        mToastUI.showToast(PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG, null);

        verify(mWindowManager).addView(mViewCaptor.capture(), any());
        View view = mViewCaptor.getValue();
        assertThat(((TextView) view.findViewById(MESSAGE_RES_ID)).getText()).isEqualTo(TEXT);
    }

    @Test
    public void testShowToast_addsViewWithCorrectLayoutParamsToWindowManager() throws Exception {
        mToastUI.showToast(PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG, null);

        verify(mWindowManager).addView(any(), mParamsCaptor.capture());
        ViewGroup.LayoutParams params = mParamsCaptor.getValue();
        assertThat(params).isInstanceOf(WindowManager.LayoutParams.class);
        WindowManager.LayoutParams windowParams = (WindowManager.LayoutParams) params;
        assertThat(windowParams.packageName).isEqualTo(mContext.getPackageName());
        assertThat(windowParams.getTitle()).isEqualTo("Toast");
        assertThat(windowParams.token).isEqualTo(WINDOW_TOKEN_1);
        assertThat(windowParams.privateFlags
                & WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS).isEqualTo(0);
    }

    @Test
    public void testShowToast_forAndroidPackage_addsAllUserFlag() throws Exception {
        mToastUI.showToast("android", TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG, null);

        verify(mWindowManager).addView(any(), mParamsCaptor.capture());
        ViewGroup.LayoutParams params = mParamsCaptor.getValue();
        assertThat(params).isInstanceOf(WindowManager.LayoutParams.class);
        WindowManager.LayoutParams windowParams = (WindowManager.LayoutParams) params;
        assertThat(windowParams.privateFlags
                & WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS).isNotEqualTo(0);
    }

    @Test
    public void testShowToast_forSystemUiPackage_addsAllUserFlag() throws Exception {
        mToastUI.showToast("com.android.systemui", TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                null);

        verify(mWindowManager).addView(any(), mParamsCaptor.capture());
        ViewGroup.LayoutParams params = mParamsCaptor.getValue();
        assertThat(params).isInstanceOf(WindowManager.LayoutParams.class);
        WindowManager.LayoutParams windowParams = (WindowManager.LayoutParams) params;
        assertThat(windowParams.privateFlags
                & WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS).isNotEqualTo(0);
    }

    @Test
    public void testShowToast_callsCallback() throws Exception {
        mToastUI.showToast(PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback);

        verify(mCallback).onToastShown();
    }

    @Test
    public void testShowToast_sendsAccessibilityEvent() throws Exception {
        when(mAccessibilityManager.isEnabled()).thenReturn(true);

        mToastUI.showToast(PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG, null);

        ArgumentCaptor<AccessibilityEvent> eventCaptor = ArgumentCaptor.forClass(
                AccessibilityEvent.class);
        verify(mAccessibilityManager).sendAccessibilityEvent(eventCaptor.capture());
        AccessibilityEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo(
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
        assertThat(event.getClassName()).isEqualTo(Toast.class.getName());
        assertThat(event.getPackageName()).isEqualTo(PACKAGE_NAME_1);
    }

    @Test
    public void testHideToast_removesView() throws Exception {
        mToastUI.showToast(PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback);
        View view = verifyWmAddViewAndAttachToParent();

        mToastUI.hideToast(PACKAGE_NAME_1, TOKEN_1);

        verify(mWindowManager).removeViewImmediate(view);
    }

    @Test
    public void testHideToast_finishesToken() throws Exception {
        mToastUI.showToast(PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback);

        mToastUI.hideToast(PACKAGE_NAME_1, TOKEN_1);

        verify(mNotificationManager).finishToken(PACKAGE_NAME_1, TOKEN_1);
    }

    @Test
    public void testHideToast_callsCallback() throws Exception {
        mToastUI.showToast(PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback);

        mToastUI.hideToast(PACKAGE_NAME_1, TOKEN_1);

        verify(mCallback).onToastHidden();
    }

    @Test
    public void testHideToast_whenNotCurrentToastToken_doesNotHideToast() throws Exception {
        mToastUI.showToast(PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback);

        mToastUI.hideToast(PACKAGE_NAME_1, TOKEN_2);

        verify(mCallback, never()).onToastHidden();
    }

    @Test
    public void testHideToast_whenNotCurrentToastPackage_doesNotHideToast() throws Exception {
        mToastUI.showToast(PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback);

        mToastUI.hideToast(PACKAGE_NAME_2, TOKEN_1);

        verify(mCallback, never()).onToastHidden();
    }

    @Test
    public void testShowToast_afterShowToast_hidesCurrentToast() throws Exception {
        mToastUI.showToast(PACKAGE_NAME_1, TOKEN_1, TEXT, WINDOW_TOKEN_1, Toast.LENGTH_LONG,
                mCallback);
        View view = verifyWmAddViewAndAttachToParent();

        mToastUI.showToast(PACKAGE_NAME_2, TOKEN_2, TEXT, WINDOW_TOKEN_2, Toast.LENGTH_LONG, null);

        verify(mWindowManager).removeViewImmediate(view);
        verify(mNotificationManager).finishToken(PACKAGE_NAME_1, TOKEN_1);
        verify(mCallback).onToastHidden();
    }

    private View verifyWmAddViewAndAttachToParent() {
        ArgumentCaptor<View> viewCaptor = ArgumentCaptor.forClass(View.class);
        verify(mWindowManager).addView(viewCaptor.capture(), any());
        View view = viewCaptor.getValue();
        // Simulate attaching to view hierarchy
        ViewGroup parent = new FrameLayout(mContext);
        parent.addView(view);
        return view;
    }
}
