/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.security.keystore2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.security.KeyStore2;
import android.security.KeyStoreException;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class AndroidKeyStoreSpiTest {

    @Mock
    private KeyStore2 mKeystore2;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testEngineAliasesReturnsEmptySetOnKeyStoreError() throws Exception {
        when(mKeystore2.list(anyInt(), anyLong()))
                .thenThrow(new KeyStoreException(6, "Some Error"));
        AndroidKeyStoreSpi spi = new AndroidKeyStoreSpi();
        spi.initForTesting(mKeystore2);

        assertThat("Empty collection expected", !spi.engineAliases().hasMoreElements());

        verify(mKeystore2).list(anyInt(), anyLong());
    }

}
