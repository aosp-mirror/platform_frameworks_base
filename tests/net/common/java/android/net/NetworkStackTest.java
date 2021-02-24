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
package android.net;

import static org.junit.Assert.assertEquals;

import android.os.Build;
import android.os.IBinder;

import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class NetworkStackTest {
    @Rule
    public DevSdkIgnoreRule mDevSdkIgnoreRule = new DevSdkIgnoreRule();

    @Mock private IBinder mConnectorBinder;

    @Before public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q)
    public void testGetService() {
        NetworkStack.setServiceForTest(mConnectorBinder);
        assertEquals(NetworkStack.getService(), mConnectorBinder);
    }
}
