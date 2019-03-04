/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.content.Context;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class IpMemoryStoreTest {
    @Mock
    Context mMockContext;
    @Mock
    IIpMemoryStore mMockService;
    IpMemoryStore mStore;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mStore = new IpMemoryStore(mMockContext, mMockService);
    }

    @Test
    public void testNetworkAttributes() {
        // TODO : implement this
    }

    @Test
    public void testPrivateData() {
        // TODO : implement this
    }

    @Test
    public void testFindL2Key() {
        // TODO : implement this
    }

    @Test
    public void testIsSameNetwork() {
        // TODO : implement this
    }

}
