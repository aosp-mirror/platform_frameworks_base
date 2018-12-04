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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;

import android.content.Context;
import android.os.RemoteException;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

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

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    // TODO : remove this useless test
    @Test
    public void testVersion() throws RemoteException {
        doReturn(30).when(mMockService).version();
        final IpMemoryStore store = new IpMemoryStore(mMockContext, mMockService);
        assertEquals(store.version(), 30);
    }
}
