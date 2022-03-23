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

package android.accessibilityservice;

import static org.mockito.Mockito.verify;

import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.view.accessibility.AccessibilityEvent;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for AccessibilityService.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AccessibilityServiceTest {
    private static final String TAG = "AccessibilityServiceTest";
    private static final int CONNECTION_ID = 1;

    private static class AccessibilityServiceTestClass extends AccessibilityService {
        private IAccessibilityServiceClient mCallback;
        private Looper mLooper;

        AccessibilityServiceTestClass() {
            super();
            attachBaseContext(InstrumentationRegistry.getContext());
            mLooper = InstrumentationRegistry.getContext().getMainLooper();
        }

        public void setupCallback(IAccessibilityServiceClient callback) {
            mCallback = callback;
        }

        public Looper getMainLooper() {
            return mLooper;
        }

        public void onAccessibilityEvent(AccessibilityEvent event) { }
        public void onInterrupt() { }

        @Override
        public void onSystemActionsChanged() {
            try {
                if (mCallback != null) mCallback.onSystemActionsChanged();
            } catch (RemoteException e) {
            }
        }
    }

    private @Mock IAccessibilityServiceClient  mMockClientForCallback;
    private @Mock IAccessibilityServiceConnection mMockConnection;
    private @Mock IBinder mMockIBinder;
    private IAccessibilityServiceClient mServiceInterface;
    private AccessibilityServiceTestClass mService;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mService = new AccessibilityServiceTestClass();
        mService.setupCallback(mMockClientForCallback);
        mServiceInterface = (IAccessibilityServiceClient) mService.onBind(new Intent());
        mServiceInterface.init(mMockConnection, CONNECTION_ID, mMockIBinder);
    }

    @Test
    public void testOnSystemActionsChanged() throws RemoteException {
        mServiceInterface.onSystemActionsChanged();

        verify(mMockClientForCallback).onSystemActionsChanged();
    }

    @Test
    public void testGetSystemActions() throws RemoteException {
        mService.getSystemActions();

        verify(mMockConnection).getSystemActions();
    }
}
