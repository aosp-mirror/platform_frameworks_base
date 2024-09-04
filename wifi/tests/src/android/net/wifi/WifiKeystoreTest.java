/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.net.wifi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.validateMockitoUsage;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import android.os.ServiceSpecificException;
import android.security.legacykeystore.ILegacyKeystore;

import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

import java.util.Arrays;

/** Unit tests for {@link WifiKeystore} */
public class WifiKeystoreTest {
    public static final String TEST_ALIAS = "someAliasString";
    public static final byte[] TEST_VALUE = new byte[]{10, 11, 12};

    @Mock private ILegacyKeystore mLegacyKeystore;
    @Mock private WifiBlobStore mWifiBlobStore;

    private MockitoSession mSession;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mSession = ExtendedMockito.mockitoSession()
                .mockStatic(WifiBlobStore.class, withSettings().lenient())
                .startMocking();
        when(WifiBlobStore.supplicantCanAccessBlobstore()).thenReturn(true);
        when(WifiBlobStore.getLegacyKeystore()).thenReturn(mLegacyKeystore);
        when(WifiBlobStore.getInstance()).thenReturn(mWifiBlobStore);
    }

    @After
    public void cleanup() {
        validateMockitoUsage();
        if (mSession != null) {
            mSession.finishMocking();
        }
    }

    /**
     * Test that put() writes to the WifiBlobStore database when it
     * is available to supplicant.
     */
    @Test
    public void testPut_wifiBlobstore() throws Exception {
        when(WifiBlobStore.supplicantCanAccessBlobstore()).thenReturn(true);
        WifiKeystore.put(TEST_ALIAS, TEST_VALUE);
        verify(mWifiBlobStore).put(anyString(), any());
        verify(mLegacyKeystore, never()).put(anyString(), anyInt(), any());
    }

    /**
     * Test that put() writes to Legacy Keystore if the WifiBlobstore database
     * is not available to supplicant.
     */
    @Test
    public void testPut_legacyKeystore() throws Exception {
        when(WifiBlobStore.supplicantCanAccessBlobstore()).thenReturn(false);
        WifiKeystore.put(TEST_ALIAS, TEST_VALUE);
        verify(mLegacyKeystore).put(anyString(), anyInt(), any());
        verify(mWifiBlobStore, never()).put(anyString(), any());
    }

    /**
     * Test that if the alias is found in the WifiBlobStore database,
     * then the legacy database is not searched.
     */
    @Test
    public void testGet_wifiBlobStoreDb() throws Exception {
        when(mWifiBlobStore.get(anyString())).thenReturn(TEST_VALUE);
        assertArrayEquals(TEST_VALUE, WifiKeystore.get(TEST_ALIAS));

        verify(mWifiBlobStore).get(anyString());
        verify(mLegacyKeystore, never()).get(anyString(), anyInt());
    }

    /**
     * Test that if the alias is not found in the WifiBlobStore database,
     * then the legacy database is searched.
     */
    @Test
    public void testGet_legacyDb() throws Exception {
        when(mWifiBlobStore.get(anyString())).thenReturn(null);
        when(mLegacyKeystore.get(anyString(), anyInt())).thenReturn(TEST_VALUE);
        assertArrayEquals(TEST_VALUE, WifiKeystore.get(TEST_ALIAS));

        verify(mWifiBlobStore).get(anyString());
        verify(mLegacyKeystore).get(anyString(), anyInt());
    }

    /**
     * Test that get() returns a non-null value if the alias is
     * not found in either database.
     */
    @Test
    public void testGet_notFound() throws Exception {
        when(mWifiBlobStore.get(anyString())).thenReturn(null);
        when(mLegacyKeystore.get(anyString(), anyInt()))
                .thenThrow(new ServiceSpecificException(ILegacyKeystore.ERROR_ENTRY_NOT_FOUND));
        assertNotNull(WifiKeystore.get(TEST_ALIAS));
    }

    /**
     * Test that remove() returns true if the alias is removed
     * from at least one database.
     */
    @Test
    public void testRemove_success() throws Exception {
        // Only removed from WifiBlobStore
        when(mWifiBlobStore.remove(anyString())).thenReturn(true);
        doThrow(new ServiceSpecificException(ILegacyKeystore.ERROR_ENTRY_NOT_FOUND))
                .when(mLegacyKeystore).remove(anyString(), anyInt());
        assertTrue(WifiKeystore.remove(TEST_ALIAS));

        // Only removed from Legacy Keystore
        when(mWifiBlobStore.remove(anyString())).thenReturn(false);
        doNothing().when(mLegacyKeystore).remove(anyString(), anyInt());
        assertTrue(WifiKeystore.remove(TEST_ALIAS));

        // Removed from both WifiBlobStore and Legacy Keystore
        when(mWifiBlobStore.remove(anyString())).thenReturn(true);
        doNothing().when(mLegacyKeystore).remove(anyString(), anyInt());
        assertTrue(WifiKeystore.remove(TEST_ALIAS));
    }

    /**
     * Test that remove() returns false if the alias is not removed
     * from any database.
     */
    @Test
    public void testRemove_notFound() throws Exception {
        when(mWifiBlobStore.remove(anyString())).thenReturn(false);
        doThrow(new ServiceSpecificException(ILegacyKeystore.ERROR_ENTRY_NOT_FOUND))
                .when(mLegacyKeystore).remove(anyString(), anyInt());
        assertFalse(WifiKeystore.remove(TEST_ALIAS));
    }

    /**
     * Test that list() retrieves aliases from both the WifiBlobStore
     * and Legacy Keystore databases. The results should be de-duplicated.
     */
    @Test
    public void testList() throws Exception {
        // Aliases retrieved from WifiBlobStore will be pre-trimmed.
        String[] blobStoreAliases = new String[]{"1", "2"};
        String[] legacyDbAliases = new String[]{TEST_ALIAS + "2", TEST_ALIAS + "3"};
        when(mWifiBlobStore.list(anyString())).thenReturn(blobStoreAliases);
        when(mLegacyKeystore.list(anyString(), anyInt())).thenReturn(legacyDbAliases);

        // Alias 2 exists in both DBs and should be de-duplicated.
        String[] expected = new String[]{"1", "2", "3"};
        String[] retrieved = WifiKeystore.list(TEST_ALIAS);
        Arrays.sort(retrieved);
        assertArrayEquals(expected, retrieved);
    }
}
