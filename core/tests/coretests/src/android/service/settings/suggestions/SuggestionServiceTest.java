/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.service.settings.suggestions;

import static com.google.common.truth.Truth.assertThat;

import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.rule.ServiceTestRule;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeoutException;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SuggestionServiceTest {

    @Rule
    public ServiceTestRule mServiceTestRule;
    private Intent mMockServiceIntent;

    @Before
    public void setUp() {
        mServiceTestRule = new ServiceTestRule();
        mMockServiceIntent = new Intent(
                InstrumentationRegistry.getTargetContext(),
                MockSuggestionService.class);
    }

    @After
    public void tearUp() {
        MockSuggestionService.reset();
    }

    @Test
    public void canStartService() throws TimeoutException {
        mServiceTestRule.startService(mMockServiceIntent);
        // Do nothing after starting service.
    }

    @Test
    public void dismissSuggestion_shouldCallImplementation()
            throws TimeoutException, RemoteException {
        MockSuggestionService service = new MockSuggestionService();
        IBinder binder = mServiceTestRule.bindService(mMockServiceIntent);
        ISuggestionService serviceBinder = ISuggestionService.Stub.asInterface(binder);
        serviceBinder.dismissSuggestion(null);

        assertThat(service.sOnSuggestionDismissedCalled).isTrue();
    }

    @Test
    public void launchSuggestion_shouldCallImplementation()
            throws TimeoutException, RemoteException {
        MockSuggestionService service = new MockSuggestionService();
        IBinder binder = mServiceTestRule.bindService(mMockServiceIntent);
        ISuggestionService serviceBinder = ISuggestionService.Stub.asInterface(binder);
        serviceBinder.launchSuggestion(null);

        assertThat(service.sOnSuggestionLaunchedCalled).isTrue();
    }
}
