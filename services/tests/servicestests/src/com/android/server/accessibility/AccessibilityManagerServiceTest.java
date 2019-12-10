/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.accessibility;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.Manifest;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import androidx.test.InstrumentationRegistry;

import com.android.server.LocalServices;
import com.android.server.accessibility.AccessibilityManagerService.AccessibilityDisplayListener;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * APCT tests for {@link AccessibilityManagerService}.
 */
public class AccessibilityManagerServiceTest extends AndroidTestCase {
    private static final String TAG = "A11Y_MANAGER_SERVICE_TEST";
    private static final int ACTION_ID = 20;
    private static final String LABEL = "label";
    private static final String INTENT_ACTION = "TESTACTION";
    private static final String DESCRIPTION = "description";
    private static final PendingIntent TEST_PENDING_INTENT = PendingIntent.getBroadcast(
            InstrumentationRegistry.getTargetContext(), 0, new Intent(INTENT_ACTION), 0);
    private static final RemoteAction TEST_ACTION = new RemoteAction(
            Icon.createWithContentUri("content://test"),
            LABEL,
            DESCRIPTION,
            TEST_PENDING_INTENT);
    private static final AccessibilityAction NEW_ACCESSIBILITY_ACTION =
            new AccessibilityAction(ACTION_ID, LABEL);

    @Mock private PackageManager mMockPackageManager;
    @Mock private WindowManagerInternal mMockWindowManagerService;
    @Mock private AccessibilitySecurityPolicy mMockSecurityPolicy;
    @Mock private SystemActionPerformer mMockSystemActionPerformer;
    @Mock private AccessibilityWindowManager mMockA11yWindowManager;
    @Mock private AccessibilityDisplayListener mMockA11yDisplayListener;
    @Mock private ActivityTaskManagerInternal mMockActivityTaskManagerInternal;

    private AccessibilityManagerService mA11yms;

    @Override
    protected void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.removeServiceForTest(ActivityTaskManagerInternal.class);
        LocalServices.addService(WindowManagerInternal.class, mMockWindowManagerService);
        LocalServices.addService(
                ActivityTaskManagerInternal.class, mMockActivityTaskManagerInternal);
        mA11yms = new AccessibilityManagerService(
            InstrumentationRegistry.getContext(),
            mMockPackageManager,
            mMockSecurityPolicy,
            mMockSystemActionPerformer,
            mMockA11yWindowManager,
            mMockA11yDisplayListener);
    }

    @SmallTest
    public void testRegisterSystemActionWithoutPermission() throws Exception {
        doThrow(SecurityException.class).when(mMockSecurityPolicy).enforceCallingPermission(
                Manifest.permission.MANAGE_ACCESSIBILITY,
                AccessibilityManagerService.FUNCTION_REGISTER_SYSTEM_ACTION);

        try {
            mA11yms.registerSystemAction(TEST_ACTION, ACTION_ID);
            fail();
        } catch (SecurityException expected) {
        }
        verify(mMockSystemActionPerformer, never()).registerSystemAction(ACTION_ID, TEST_ACTION);
    }

    @SmallTest
    public void testRegisterSystemAction() throws Exception {
        mA11yms.registerSystemAction(TEST_ACTION, ACTION_ID);
        verify(mMockSystemActionPerformer).registerSystemAction(ACTION_ID, TEST_ACTION);
    }

    @SmallTest
    public void testUnregisterSystemActionWithoutPermission() throws Exception {
        doThrow(SecurityException.class).when(mMockSecurityPolicy).enforceCallingPermission(
                Manifest.permission.MANAGE_ACCESSIBILITY,
                AccessibilityManagerService.FUNCTION_UNREGISTER_SYSTEM_ACTION);

        try {
            mA11yms.unregisterSystemAction(ACTION_ID);
            fail();
        } catch (SecurityException expected) {
        }
        verify(mMockSystemActionPerformer, never()).unregisterSystemAction(ACTION_ID);
    }

    @SmallTest
    public void testUnregisterSystemAction() throws Exception {
        mA11yms.unregisterSystemAction(ACTION_ID);
        verify(mMockSystemActionPerformer).unregisterSystemAction(ACTION_ID);
    }
}
