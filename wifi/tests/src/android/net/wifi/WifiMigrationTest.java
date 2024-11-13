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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.validateMockitoUsage;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.security.legacykeystore.ILegacyKeystore;

import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

/**
 * Unit tests for {@link WifiMigration}.
 */
public class WifiMigrationTest {
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
        when(mLegacyKeystore.get(anyString(), anyInt())).thenReturn(TEST_VALUE);
    }

    @After
    public void cleanup() {
        validateMockitoUsage();
        if (mSession != null) {
            mSession.finishMocking();
        }
    }

    /**
     * Verify that the Keystore migration is skipped if supplicant does not have
     * access to the WifiBlobstore database.
     */
    @Test
    public void testKeystoreMigrationAvoidedOnLegacyVendorPartition() {
        when(WifiBlobStore.supplicantCanAccessBlobstore()).thenReturn(false);
        assertEquals(WifiMigration.KEYSTORE_MIGRATION_SUCCESS_MIGRATION_NOT_NEEDED,
                WifiMigration.migrateLegacyKeystoreToWifiBlobstore());
        verifyNoMoreInteractions(mLegacyKeystore, mWifiBlobStore);
    }

    /**
     * Verify that the Keystore migration method returns immediately if no aliases
     * are found in Legacy Keystore.
     */
    @Test
    public void testKeystoreMigrationNoLegacyAliases() throws Exception {
        when(mLegacyKeystore.list(anyString(), anyInt())).thenReturn(new String[0]);
        assertEquals(WifiMigration.KEYSTORE_MIGRATION_SUCCESS_MIGRATION_NOT_NEEDED,
                WifiMigration.migrateLegacyKeystoreToWifiBlobstore());
        verify(mLegacyKeystore).list(anyString(), anyInt());
        verifyNoMoreInteractions(mLegacyKeystore, mWifiBlobStore);
    }

    /**
     * Verify that if all aliases in Legacy Keystore are unique to that database,
     * all aliases are migrated to WifiBlobstore.
     */
    @Test
    public void testKeystoreMigrationUniqueLegacyAliases() throws Exception {
        String[] legacyAliases = new String[]{TEST_ALIAS + "1", TEST_ALIAS + "2"};
        String[] blobstoreAliases = new String[0];
        when(mLegacyKeystore.list(anyString(), anyInt())).thenReturn(legacyAliases);
        when(mWifiBlobStore.list(anyString())).thenReturn(blobstoreAliases);

        assertEquals(WifiMigration.KEYSTORE_MIGRATION_SUCCESS_MIGRATION_COMPLETE,
                WifiMigration.migrateLegacyKeystoreToWifiBlobstore());
        verify(mWifiBlobStore, times(legacyAliases.length)).put(anyString(), any(byte[].class));
    }

    /**
     * Verify that if some aliases are shared between Legacy Keystore and WifiBlobstore,
     * only the ones unique to Legacy Keystore are migrated.
     */
    @Test
    public void testKeystoreMigrationDuplicateLegacyAliases() throws Exception {
        String uniqueLegacyAlias = TEST_ALIAS + "1";
        String[] blobstoreAliases = new String[]{TEST_ALIAS + "2", TEST_ALIAS + "3"};
        String[] legacyAliases =
                new String[]{blobstoreAliases[0], blobstoreAliases[1], uniqueLegacyAlias};
        when(mLegacyKeystore.list(anyString(), anyInt())).thenReturn(legacyAliases);
        when(mWifiBlobStore.list(anyString())).thenReturn(blobstoreAliases);

        // Expect that only the unique legacy alias is migrated to the blobstore
        assertEquals(WifiMigration.KEYSTORE_MIGRATION_SUCCESS_MIGRATION_COMPLETE,
                WifiMigration.migrateLegacyKeystoreToWifiBlobstore());
        verify(mWifiBlobStore).list(anyString());
        verify(mWifiBlobStore).put(eq(uniqueLegacyAlias), any(byte[].class));
        verifyNoMoreInteractions(mWifiBlobStore);
    }

    /**
     * Verify that the Keystore migration is skipped if Legacy Keystore is deprecated,
     * since the migration is not needed.
     */
    @Test
    public void testKeystoreMigrationAvoidedIfLegacyKsDeprecated() throws Exception {
        // Legacy Keystore will throw a ServiceSpecificException with
        // code ERROR_SYSTEM_ERROR if a method is deprecated
        when(mLegacyKeystore.list(anyString(), anyInt())).thenThrow(
                new ServiceSpecificException(ILegacyKeystore.ERROR_SYSTEM_ERROR));
        assertEquals(WifiMigration.KEYSTORE_MIGRATION_SUCCESS_MIGRATION_NOT_NEEDED,
                WifiMigration.migrateLegacyKeystoreToWifiBlobstore());
    }

    /**
     * Verify that the Keystore migration method returns a failure code when an
     * unexpected exception is encountered.
     */
    @Test
    public void testKeystoreMigrationFailsIfExceptionEncountered() throws Exception {
        when(mLegacyKeystore.list(anyString(), anyInt())).thenThrow(new RemoteException());
        assertEquals(WifiMigration.KEYSTORE_MIGRATION_FAILURE_ENCOUNTERED_EXCEPTION,
                WifiMigration.migrateLegacyKeystoreToWifiBlobstore());
    }
}
