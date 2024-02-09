/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.pdb;

import static com.android.server.pdb.PersistentDataBlockService.DIGEST_SIZE_BYTES;
import static com.android.server.pdb.PersistentDataBlockService.FRP_SECRET_SIZE;
import static com.android.server.pdb.PersistentDataBlockService.MAX_DATA_BLOCK_SIZE;
import static com.android.server.pdb.PersistentDataBlockService.MAX_FRP_CREDENTIAL_HANDLE_SIZE;
import static com.android.server.pdb.PersistentDataBlockService.MAX_TEST_MODE_DATA_SIZE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserManager;
import android.service.persistentdata.IPersistentDataBlockService;

import androidx.test.core.app.ApplicationProvider;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@RunWith(JUnitParamsRunner.class)
public class PersistentDataBlockServiceTest {
    private static final String TAG = "PersistentDataBlockServiceTest";

    private static final byte[] SMALL_DATA = "data to write".getBytes();
    private static final byte[] ANOTHER_SMALL_DATA = "something else".getBytes();
    public static final int DEFAULT_BLOCK_DEVICE_SIZE = -1;

    private Context mContext;
    private PersistentDataBlockService mPdbService;
    private IPersistentDataBlockService mInterface;
    private PersistentDataBlockManagerInternal mInternalInterface;
    private File mDataBlockFile;
    private File mFrpSecretFile;
    private File mFrpSecretTmpFile;
    private String mOemUnlockPropertyValue;
    private boolean mIsUpgradingFromPreV = false;

    @Mock private UserManager mUserManager;

    private class FakePersistentDataBlockService extends PersistentDataBlockService {

        FakePersistentDataBlockService(Context context, String dataBlockFile,
                long blockDeviceSize, boolean frpEnabled, String frpSecretFile,
                String frpSecretTmpFile) {
            super(context, /* isFileBacked */ true, dataBlockFile, blockDeviceSize, frpEnabled,
                    frpSecretFile, frpSecretTmpFile);
            // In the real service, this is done by onStart(), which we don't want to call because
            // it registers the service, etc.  But we need to signal init done to prevent
            // `isFrpActive` from blocking.
            signalInitDone();
        }

        @Override
        void setProperty(String key, String value) {
            // Override to capture the value instead of actually setting the property.
            assertThat(key).isEqualTo("sys.oem_unlock_allowed");
            mOemUnlockPropertyValue = value;
        }

        @Override
        boolean isUpgradingFromPreVRelease() {
            return mIsUpgradingFromPreV;
        }
    }

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private void setUp(boolean frpEnabled) throws Exception {
        MockitoAnnotations.initMocks(this);

        mDataBlockFile = mTemporaryFolder.newFile();
        mFrpSecretFile = mTemporaryFolder.newFile();
        mFrpSecretTmpFile = mTemporaryFolder.newFile();
        mContext = spy(ApplicationProvider.getApplicationContext());
        mPdbService = new FakePersistentDataBlockService(mContext, mDataBlockFile.getPath(),
                DEFAULT_BLOCK_DEVICE_SIZE, frpEnabled, mFrpSecretFile.getPath(),
                mFrpSecretTmpFile.getPath());
        mPdbService.setAllowedUid(Binder.getCallingUid());
        mPdbService.formatPartitionLocked(/* setOemUnlockEnabled */ false);
        mInterface = mPdbService.getInterfaceForTesting();
        mInternalInterface = mPdbService.getInternalInterfaceForTesting();

        when(mContext.getSystemService(eq(Context.USER_SERVICE))).thenReturn(mUserManager);
        when(mContext.getSystemService(UserManager.class)).thenReturn(mUserManager);
    }

    abstract static class Block {
        public PersistentDataBlockService service;

        abstract int write(byte[] data) throws RemoteException;
        abstract byte[] read() throws RemoteException;
    }

    /**
     * Configuration for parameterizing tests, including the block name, maximum block size, and
     * a block implementation for the read/write operations.
     */
    public Object[][] getTestParametersForBlocks() {
        Block simpleReadWrite = new Block() {
                    @Override public int write(byte[] data) throws RemoteException {
                        return service.getInterfaceForTesting().write(data);
                    }

                    @Override public byte[] read() throws RemoteException {
                        return service.getInterfaceForTesting().read();
                    }
                };
        Block credHandle =  new Block() {
                    @Override public int write(byte[] data) {
                        service.getInternalInterfaceForTesting().setFrpCredentialHandle(data);
                        // The written size isn't returned. Pretend it's fully written in the
                        // test for now.
                        return data.length;
                    }

                    @Override public byte[] read() {
                        return service.getInternalInterfaceForTesting().getFrpCredentialHandle();
                    }
                };
        Block testHarness = new Block() {
                    @Override public int write(byte[] data) {
                        service.getInternalInterfaceForTesting().setTestHarnessModeData(data);
                        // The written size isn't returned. Pretend it's fully written in the
                        // test for now.
                        return data.length;
                    }

                    @Override public byte[] read() {
                        return service.getInternalInterfaceForTesting().getTestHarnessModeData();
                    }
                };
        return new Object[][] {
                { simpleReadWrite, false },
                { simpleReadWrite, true },
                { credHandle, false },
                { credHandle, true },
                { testHarness, false },
                { testHarness, true },
        };
    }

    @Test
    @Parameters(method = "getTestParametersForBlocks")
    public void writeThenRead(Block block, boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        block.service = mPdbService;
        assertThat(block.write(SMALL_DATA)).isEqualTo(SMALL_DATA.length);
        assertThat(block.read()).isEqualTo(SMALL_DATA);
    }

    @Test
    @Parameters(method = "getTestParametersForBlocks")
    public void writeWhileAlreadyCorrupted(Block block, boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        block.service = mPdbService;
        assertThat(block.write(SMALL_DATA)).isEqualTo(SMALL_DATA.length);
        assertThat(block.read()).isEqualTo(SMALL_DATA);

        tamperWithDigest();

        // In the currently implementation, expect the write to not trigger formatting.
        assertThat(block.write(ANOTHER_SMALL_DATA)).isEqualTo(ANOTHER_SMALL_DATA.length);
    }

    @Test
    @Parameters({"false", "true"})
    public void frpWriteOutOfBound(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        byte[] maxData = new byte[mPdbService.getMaximumFrpDataSize()];
        assertThat(mInterface.write(maxData)).isEqualTo(maxData.length);

        byte[] overflowData = new byte[mPdbService.getMaximumFrpDataSize() + 1];
        assertThat(mInterface.write(overflowData)).isLessThan(0);
    }

    @Test
    @Parameters({"false", "true"})
    public void frpCredentialWriteOutOfBound(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        byte[] maxData = new byte[MAX_FRP_CREDENTIAL_HANDLE_SIZE];
        mInternalInterface.setFrpCredentialHandle(maxData);

        byte[] overflowData = new byte[MAX_FRP_CREDENTIAL_HANDLE_SIZE + 1];
        assertThrows(IllegalArgumentException.class, () ->
                mInternalInterface.setFrpCredentialHandle(overflowData));
    }

    @Test
    @Parameters({"false", "true"})
    public void testHardnessWriteOutOfBound(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        byte[] maxData = new byte[MAX_TEST_MODE_DATA_SIZE];
        mInternalInterface.setTestHarnessModeData(maxData);

        byte[] overflowData = new byte[MAX_TEST_MODE_DATA_SIZE + 1];
        assertThrows(IllegalArgumentException.class, () ->
                mInternalInterface.setTestHarnessModeData(overflowData));
    }

    @Test
    @Parameters({"false", "true"})
    public void readCorruptedFrpData(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        assertThat(mInterface.write(SMALL_DATA)).isEqualTo(SMALL_DATA.length);
        assertThat(mInterface.read()).isEqualTo(SMALL_DATA);

        tamperWithDigest();

        // Expect the read to trigger formatting, resulting in reading empty data.
        assertThat(mInterface.read()).hasLength(0);
    }

    @Test
    @Parameters({"false", "true"})
    public void readCorruptedFrpCredentialData(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        mInternalInterface.setFrpCredentialHandle(SMALL_DATA);
        assertThat(mInternalInterface.getFrpCredentialHandle()).isEqualTo(SMALL_DATA);

        tamperWithDigest();

        assertThrows(IllegalStateException.class, () ->
                mInternalInterface.getFrpCredentialHandle());
    }

    @Test
    @Parameters({"false", "true"})
    public void readCorruptedTestHarnessData(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        mInternalInterface.setTestHarnessModeData(SMALL_DATA);
        assertThat(mInternalInterface.getTestHarnessModeData()).isEqualTo(SMALL_DATA);

        tamperWithDigest();

        assertThrows(IllegalStateException.class, () ->
                mInternalInterface.getTestHarnessModeData());
    }

    @Test
    @Parameters({"false", "true"})
    public void nullWrite(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        assertThrows(NullPointerException.class, () -> mInterface.write(null));
        mInternalInterface.setFrpCredentialHandle(null);  // no exception
        mInternalInterface.setTestHarnessModeData(null);  // no exception
    }

    @Test
    @Parameters({"false", "true"})
    public void emptyDataWrite(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        var empty = new byte[0];
        assertThat(mInterface.write(empty)).isEqualTo(0);

        assertThrows(IllegalArgumentException.class, () ->
                mInternalInterface.setFrpCredentialHandle(empty));
        assertThrows(IllegalArgumentException.class, () ->
                mInternalInterface.setTestHarnessModeData(empty));
    }

    @Test
    @Parameters({"false", "true"})
    public void frpWriteMoreThan100K(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        File dataBlockFile = mTemporaryFolder.newFile();
        PersistentDataBlockService pdbService = new FakePersistentDataBlockService(mContext,
                dataBlockFile.getPath(), /* blockDeviceSize */ 128 * 1000, frpEnabled,
                /* frpSecretFile */ null, /* frpSecretTmpFile */ null);
        pdbService.setAllowedUid(Binder.getCallingUid());
        pdbService.formatPartitionLocked(/* setOemUnlockEnabled */ false);

        IPersistentDataBlockService service = pdbService.getInterfaceForTesting();
        int maxDataSize = (int) service.getMaximumDataBlockSize();
        assertThat(service.write(new byte[maxDataSize])).isEqualTo(maxDataSize);
        assertThat(service.write(new byte[maxDataSize + 1])).isEqualTo(-MAX_DATA_BLOCK_SIZE);
    }

    @Test
    @Parameters({"false", "true"})
    public void frpBlockReadWriteWithoutPermission(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        mPdbService.setAllowedUid(Binder.getCallingUid() + 1);  // unexpected uid
        assertThrows(SecurityException.class, () -> mInterface.write(SMALL_DATA));
        assertThrows(SecurityException.class, () -> mInterface.read());
    }

    @Test
    @Parameters({"false", "true"})
    public void getMaximumDataBlockSizeDenied(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        mPdbService.setAllowedUid(Binder.getCallingUid() + 1);  // unexpected uid
        assertThrows(SecurityException.class, () -> mInterface.getMaximumDataBlockSize());
    }

    @Test
    @Parameters({"false", "true"})
    public void getMaximumDataBlockSize(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        mPdbService.setAllowedUid(Binder.getCallingUid());
        assertThat(mInterface.getMaximumDataBlockSize())
                .isEqualTo(mPdbService.getMaximumFrpDataSize());
    }

    @Test
    @Parameters({"false", "true"})
    public void getMaximumDataBlockSizeOfLargerPartition(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        File dataBlockFile = mTemporaryFolder.newFile();
        PersistentDataBlockService pdbService = new FakePersistentDataBlockService(mContext,
                dataBlockFile.getPath(), /* blockDeviceSize */ 128 * 1000, frpEnabled,
                /* frpSecretFile */null, /* mFrpSecretTmpFile */ null);
        pdbService.setAllowedUid(Binder.getCallingUid());
        pdbService.formatPartitionLocked(/* setOemUnlockEnabled */ false);

        IPersistentDataBlockService service = pdbService.getInterfaceForTesting();
        assertThat(service.getMaximumDataBlockSize()).isEqualTo(MAX_DATA_BLOCK_SIZE);
    }

    @Test
    @Parameters({"false", "true"})
    public void getFrpDataBlockSizeGrantedByUid(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        assertThat(mInterface.write(SMALL_DATA)).isEqualTo(SMALL_DATA.length);

        mPdbService.setAllowedUid(Binder.getCallingUid());
        assertThat(mInterface.getDataBlockSize()).isEqualTo(SMALL_DATA.length);

        // Modify the magic / type marker. In the current implementation, getting the FRP data block
        // size does not check digest.
        tamperWithMagic();
        assertThat(mInterface.getDataBlockSize()).isEqualTo(0);
    }

    @Test
    @Parameters({"false", "true"})
    public void getFrpDataBlockSizeGrantedByPermission(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        assertThat(mInterface.write(SMALL_DATA)).isEqualTo(SMALL_DATA.length);

        mPdbService.setAllowedUid(Binder.getCallingUid() + 1);  // unexpected uid
        grantAccessPdbStatePermission();

        assertThat(mInterface.getDataBlockSize()).isEqualTo(SMALL_DATA.length);

        // Modify the magic / type marker. In the current implementation, getting the FRP data block
        // size does not check digest.
        tamperWithMagic();
        assertThat(mInterface.getDataBlockSize()).isEqualTo(0);
    }

    @Test
    @Parameters({"false", "true"})
    public void wipePermissionCheck(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        denyOemUnlockPermission();
        assertThrows(SecurityException.class, () -> mInterface.wipe());
    }

    @Test
    @Parameters({"false", "true"})
    public void wipeMakesItNotWritable(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        grantOemUnlockPermission();
        mInterface.wipe();

        // Verify that nothing is written.
        final int headerAndDataBytes = 4 + SMALL_DATA.length;
        assertThat(mInterface.write(SMALL_DATA)).isLessThan(0);
        assertThat(readBackingFile(DIGEST_SIZE_BYTES + 4, headerAndDataBytes).array())
                .isEqualTo(new byte[headerAndDataBytes]);

        mInternalInterface.setFrpCredentialHandle(SMALL_DATA);
        assertThat(readBackingFile(mPdbService.getFrpCredentialDataOffset() + 4,
                    headerAndDataBytes)
                .array())
                .isEqualTo(new byte[headerAndDataBytes]);

        mInternalInterface.setTestHarnessModeData(SMALL_DATA);
        assertThat(readBackingFile(mPdbService.getTestHarnessModeDataOffset() + 4,
                    headerAndDataBytes)
                .array())
                .isEqualTo(new byte[headerAndDataBytes]);
    }

    @Test
    @Parameters({"false", "true"})
    public void hasFrpCredentialHandle_GrantedByUid(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        mPdbService.setAllowedUid(Binder.getCallingUid());

        assertThat(mInterface.hasFrpCredentialHandle()).isFalse();
        mInternalInterface.setFrpCredentialHandle(SMALL_DATA);
        assertThat(mInterface.hasFrpCredentialHandle()).isTrue();
    }

    @Test
    @Parameters({"false", "true"})
    public void hasFrpCredentialHandle_GrantedByConfigureFrpPermission(boolean frpEnabled)
            throws Exception {
        setUp(frpEnabled);
        grantConfigureFrpPermission();

        mPdbService.setAllowedUid(Binder.getCallingUid() + 1);  // unexpected uid

        if (frpEnabled) {
            assertThat(mInterface.hasFrpCredentialHandle()).isFalse();
            mInternalInterface.setFrpCredentialHandle(SMALL_DATA);
            assertThat(mInterface.hasFrpCredentialHandle()).isTrue();
        } else {
            assertThrows(SecurityException.class, () -> mInterface.hasFrpCredentialHandle());
        }
    }

    @Test
    @Parameters({"false", "true"})
    public void hasFrpCredentialHandle_GrantedByAccessPdbStatePermission(boolean frpEnabled)
            throws Exception {
        setUp(frpEnabled);
        grantAccessPdbStatePermission();

        mPdbService.setAllowedUid(Binder.getCallingUid() + 1);  // unexpected uid

        assertThat(mInterface.hasFrpCredentialHandle()).isFalse();
        mInternalInterface.setFrpCredentialHandle(SMALL_DATA);
        assertThat(mInterface.hasFrpCredentialHandle()).isTrue();
    }

    @Test
    @Parameters({"false", "true"})
    public void hasFrpCredentialHandle_Unauthorized(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);

        mPdbService.setAllowedUid(Binder.getCallingUid() + 1);  // unexpected uid

        assertThrows(SecurityException.class, () -> mInterface.hasFrpCredentialHandle());
    }

    @Test
    @Parameters({"false", "true"})
    public void clearTestHarnessModeData(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        mInternalInterface.setTestHarnessModeData(SMALL_DATA);
        mInternalInterface.clearTestHarnessModeData();

        assertThat(readBackingFile(mPdbService.getTestHarnessModeDataOffset(),
                    MAX_TEST_MODE_DATA_SIZE).array())
                .isEqualTo(new byte[MAX_TEST_MODE_DATA_SIZE]);
    }

    @Test
    @Parameters({"false", "true"})
    public void getAllowedUid(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        assertThat(mInternalInterface.getAllowedUid()).isEqualTo(Binder.getCallingUid());
    }

    @Test
    @Parameters({"false", "true"})
    public void oemUnlockWithoutPermission(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        denyOemUnlockPermission();

        assertThrows(SecurityException.class, () -> mInterface.setOemUnlockEnabled(true));
    }

    @Test
    @Parameters({"false", "true"})
    public void oemUnlockNotAdmin(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        grantOemUnlockPermission();
        makeUserAdmin(false);

        assertThrows(SecurityException.class, () -> mInterface.setOemUnlockEnabled(true));
    }

    @Test
    @Parameters({"false", "true"})
    public void oemUnlock(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        grantOemUnlockPermission();
        makeUserAdmin(true);

        mInterface.setOemUnlockEnabled(true);
        assertThat(mInterface.getOemUnlockEnabled()).isTrue();
        assertThat(mOemUnlockPropertyValue).isEqualTo("1");
    }

    @Test
    @Parameters({"false", "true"})
    public void oemUnlockUserRestriction_OemUnlock(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        grantOemUnlockPermission();
        makeUserAdmin(true);
        when(mUserManager.hasUserRestriction(eq(UserManager.DISALLOW_OEM_UNLOCK)))
                .thenReturn(true);

        assertThrows(SecurityException.class, () -> mInterface.setOemUnlockEnabled(true));
    }

    @Test
    @Parameters({"false", "true"})
    public void oemUnlockUserRestriction_FactoryReset(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        grantOemUnlockPermission();
        makeUserAdmin(true);
        when(mUserManager.hasUserRestriction(eq(UserManager.DISALLOW_FACTORY_RESET)))
                .thenReturn(true);

        assertThrows(SecurityException.class, () -> mInterface.setOemUnlockEnabled(true));
    }

    @Test
    @Parameters({"false", "true"})
    public void oemUnlockIgnoreTampering(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        grantOemUnlockPermission();
        makeUserAdmin(true);

        // The current implementation does not check digest before set or get the oem unlock bit.
        tamperWithDigest();
        mInterface.setOemUnlockEnabled(true);
        assertThat(mOemUnlockPropertyValue).isEqualTo("1");
        tamperWithDigest();
        assertThat(mInterface.getOemUnlockEnabled()).isTrue();
    }

    @Test
    @Parameters({"false", "true"})
    public void getOemUnlockEnabledPermissionCheck_NoPermission(boolean frpEnabled)
            throws Exception {
        setUp(frpEnabled);
        assertThrows(SecurityException.class, () -> mInterface.getOemUnlockEnabled());
    }

    @Test
    @Parameters({"false", "true"})
    public void getOemUnlockEnabledPermissionCheck_OemUnlockState(boolean frpEnabled)
            throws Exception {
        setUp(frpEnabled);
        doReturn(PackageManager.PERMISSION_GRANTED).when(mContext)
                .checkCallingOrSelfPermission(eq(Manifest.permission.OEM_UNLOCK_STATE));
        assertThat(mInterface.getOemUnlockEnabled()).isFalse();
    }

    @Test
    @Parameters({"false", "true"})
    public void getOemUnlockEnabledPermissionCheck_ReadOemUnlockState(boolean frpEnabled)
            throws Exception {
        setUp(frpEnabled);
        doReturn(PackageManager.PERMISSION_GRANTED).when(mContext)
                .checkCallingOrSelfPermission(eq(Manifest.permission.READ_OEM_UNLOCK_STATE));
        assertThat(mInterface.getOemUnlockEnabled()).isFalse();
    }

    @Test
    @Parameters({"false", "true"})
    public void forceOemUnlock_RequiresNoPermission(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        denyOemUnlockPermission();

        mInternalInterface.forceOemUnlockEnabled(true);

        assertThat(mOemUnlockPropertyValue).isEqualTo("1");
        assertThat(readBackingFile(mPdbService.getOemUnlockDataOffset(), 1).array())
                .isEqualTo(new byte[] { 1 });
    }

    @Test
    @Parameters({"false", "true"})
    public void getFlashLockStatePermissionCheck_NoPermission(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        assertThrows(SecurityException.class, () -> mInterface.getFlashLockState());
    }

    @Test
    @Parameters({"false", "true"})
    public void getFlashLockStatePermissionCheck_OemUnlockState(boolean frpEnabled)
            throws Exception {
        setUp(frpEnabled);
        doReturn(PackageManager.PERMISSION_GRANTED).when(mContext)
                .checkCallingOrSelfPermission(eq(Manifest.permission.OEM_UNLOCK_STATE));
        mInterface.getFlashLockState();  // Do not throw
    }

    @Test
    @Parameters({"false", "true"})
    public void getFlashLockStatePermissionCheck_ReadOemUnlockState(boolean frpEnabled)
            throws Exception {
        setUp(frpEnabled);
        doReturn(PackageManager.PERMISSION_GRANTED).when(mContext)
                .checkCallingOrSelfPermission(eq(Manifest.permission.READ_OEM_UNLOCK_STATE));
        mInterface.getFlashLockState();  // Do not throw
    }

    @Test
    @Parameters({"false", "true"})
    public void frpMagicTest(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        byte[] magicField = mPdbService.readDataBlock(mPdbService.getFrpSecretMagicOffset(),
                PersistentDataBlockService.FRP_SECRET_MAGIC.length);
        if (frpEnabled) {
            assertThat(magicField).isEqualTo(PersistentDataBlockService.FRP_SECRET_MAGIC);
        } else {
            assertThat(magicField).isNotEqualTo(PersistentDataBlockService.FRP_SECRET_MAGIC);
        }
    }

    @Test
    public void frpSecret_StartsAsDefault() throws Exception {
        setUp(/* frpEnabled */ true);

        byte[] secretField = mPdbService.readDataBlock(
                mPdbService.getFrpSecretDataOffset(), PersistentDataBlockService.FRP_SECRET_SIZE);
        assertThat(secretField).isEqualTo(new byte[PersistentDataBlockService.FRP_SECRET_SIZE]);
    }

    @Test
    public void frpSecret_SetSecret() throws Exception {
        setUp(/* frpEnforcement */ true);
        grantConfigureFrpPermission();

        byte[] hashedSecret = hashStringto32Bytes("secret");
        assertThat(mInterface.setFactoryResetProtectionSecret(hashedSecret)).isTrue();

        byte[] secretField = mPdbService.readDataBlock(
                mPdbService.getFrpSecretDataOffset(), PersistentDataBlockService.FRP_SECRET_SIZE);
        assertThat(secretField).isEqualTo(hashedSecret);

        assertThat(mFrpSecretFile.exists()).isTrue();
        byte[] secretFileData = Files.readAllBytes(mFrpSecretFile.toPath());
        assertThat(secretFileData).isEqualTo(hashedSecret);

        assertThat(mFrpSecretTmpFile.exists()).isFalse();
    }

    @Test
    public void frpSecret_SetSecretByUnauthorizedCaller() throws Exception {
        setUp(/* frpEnforcement */ true);

        mPdbService.setAllowedUid(Binder.getCallingUid() + 1);  // unexpected uid
        assertThrows(SecurityException.class,
                () -> mInterface.setFactoryResetProtectionSecret(hashStringto32Bytes("secret")));
    }

    /**
     * Verify that FRP always starts in active state (if flag-enabled), until something is done to
     * deactivate it.
     */
    @Test
    @Parameters({"false", "true"})
    public void frpState_StartsActive(boolean frpEnabled) throws Exception {
        setUp(frpEnabled);
        // Create a service without calling formatPartition, which deactivates FRP.
        PersistentDataBlockService pdbService = new FakePersistentDataBlockService(mContext,
                mDataBlockFile.getPath(), DEFAULT_BLOCK_DEVICE_SIZE, frpEnabled,
                mFrpSecretFile.getPath(), mFrpSecretTmpFile.getPath());
        assertThat(pdbService.isFrpActive()).isEqualTo(frpEnabled);
    }

    @Test
    public void frpState_AutomaticallyDeactivateWithDefault() throws Exception {
        setUp(/* frpEnforcement */ true);

        mPdbService.activateFrp();
        assertThat(mPdbService.isFrpActive()).isTrue();

        assertThat(mPdbService.automaticallyDeactivateFrpIfPossible()).isTrue();
        assertThat(mPdbService.isFrpActive()).isFalse();
    }

    @Test
    public void frpState_AutomaticallyDeactivateWithPrimaryDataFile() throws Exception {
        setUp(/* frpEnforcement */ true);
        grantConfigureFrpPermission();

        mInterface.setFactoryResetProtectionSecret(hashStringto32Bytes("secret"));

        mPdbService.activateFrp();
        assertThat(mPdbService.isFrpActive()).isTrue();
        assertThat(mPdbService.automaticallyDeactivateFrpIfPossible()).isTrue();
        assertThat(mPdbService.isFrpActive()).isFalse();
    }

    @Test
    public void frpState_AutomaticallyDeactivateWithBackupDataFile() throws Exception {
        setUp(/* frpEnforcement */ true);
        grantConfigureFrpPermission();

        mInterface.setFactoryResetProtectionSecret(hashStringto32Bytes("secret"));
        Files.move(mFrpSecretFile.toPath(), mFrpSecretTmpFile.toPath(), REPLACE_EXISTING);

        mPdbService.activateFrp();
        assertThat(mPdbService.isFrpActive()).isTrue();
        assertThat(mPdbService.automaticallyDeactivateFrpIfPossible()).isTrue();
        assertThat(mPdbService.isFrpActive()).isFalse();
    }

    @Test
    public void frpState_DeactivateWithSecret() throws Exception {
        setUp(/* frpEnforcement */ true);
        grantConfigureFrpPermission();

        mInterface.setFactoryResetProtectionSecret(hashStringto32Bytes("secret"));
        simulateDataWipe();

        assertThat(mPdbService.isFrpActive()).isFalse();
        mPdbService.activateFrp();
        assertThat(mPdbService.isFrpActive()).isTrue();

        assertThat(mPdbService.automaticallyDeactivateFrpIfPossible()).isFalse();
        assertThat(mPdbService.isFrpActive()).isTrue();

        assertThat(mInterface.deactivateFactoryResetProtection(hashStringto32Bytes("wrongSecret")))
                .isFalse();
        assertThat(mPdbService.isFrpActive()).isTrue();

        assertThat(mInterface.deactivateFactoryResetProtection(hashStringto32Bytes("secret")))
                .isTrue();
        assertThat(mPdbService.isFrpActive()).isFalse();

        assertThat(mInterface.setFactoryResetProtectionSecret(new byte[FRP_SECRET_SIZE])).isTrue();
        assertThat(mPdbService.isFrpActive()).isFalse();

        mPdbService.activateFrp();
        assertThat(mPdbService.isFrpActive()).isTrue();
        assertThat(mPdbService.automaticallyDeactivateFrpIfPossible()).isTrue();
        assertThat(mPdbService.isFrpActive()).isFalse();
    }

    @Test
    public void frpState_DeactivateOnUpgradeFromPreV() throws Exception {
        setUp(/* frpEnforcement */ true);
        grantConfigureFrpPermission();

        mInterface.setFactoryResetProtectionSecret(hashStringto32Bytes("secret"));
        // If the /data files are still present, deactivation will use them.  We want to verify
        // that deactivation will succeed even if they are not present, so remove them.
        simulateDataWipe();

        // Verify that automatic deactivation fails without the /data files when we're not
        // upgrading from pre-V.
        mPdbService.activateFrp();
        assertThat(mPdbService.automaticallyDeactivateFrpIfPossible()).isFalse();
        assertThat(mPdbService.isFrpActive()).isTrue();

        // Verify that automatic deactivation succeeds when upgrading from pre-V.
        mIsUpgradingFromPreV = true;
        assertThat(mPdbService.automaticallyDeactivateFrpIfPossible()).isTrue();
        assertThat(mPdbService.isFrpActive()).isFalse();
    }

    /**
     * There is code in PersistentDataBlockService to handle a specific corner case, that of a
     * device that is upgraded from pre-V to V+, downgraded to pre-V and then upgraded to V+. In
     * this scenario, the following happens:
     *
     * 1. When the device is upgraded to V+ and the user sets an LSKF and GAIA creds, FRP
     *    enforcement is activated and three copies of the FRP secret are written to:
     *     a.  The FRP secret field in PDB (plaintext).
     *     b.  The GAIA challenge in PDB (encrypted).
     *     c.  The FRP secret file in /data (plaintext).
     * 2. When the device is downgraded to pre-V, /data is wiped, so copy (c) is destroyed. When the
     *    user sets LSKF and GAIA creds, copy (b) is overwritten.  Copy (a) survives.
     * 3. When the device is upgraded to V and boots the first time, FRP cannot be automatically
     *    deactivated using copy (c), nor can the user deactivate FRP using copy (b), because both
     *    are gone. Absent some special handling of this case, the device would be unusable.
     *
     *  To address this problem, if PersistentDataBlockService finds an FRP secret in (a) but none
     *  in (b) or (c), and PackageManager reports that the device has just upgraded from pre-V to
     *  V+, it zeros the FRP secret in (a).
     *
     * This test checks that the service handles this sequence of events correctly.
     */
    @Test
    public void frpState_TestDowngradeUpgradeSequence() throws Exception {
        // Simulate device in V+, with FRP configured.
        setUp(/* frpEnforcement */ true);
        grantConfigureFrpPermission();

        assertThat(mInterface.setFactoryResetProtectionSecret(hashStringto32Bytes("secret")))
                .isTrue();
        assertThat(mPdbService.isFrpActive()).isFalse();

        // Simulate reboot, still in V+.
        boolean frpEnabled = true;
        mPdbService = new FakePersistentDataBlockService(mContext, mDataBlockFile.getPath(),
                DEFAULT_BLOCK_DEVICE_SIZE, frpEnabled, mFrpSecretFile.getPath(),
                mFrpSecretTmpFile.getPath());
        assertThat(mPdbService.isFrpActive()).isTrue();
        assertThat(mPdbService.automaticallyDeactivateFrpIfPossible()).isTrue();
        assertThat(mPdbService.isFrpActive()).isFalse();

        // Simulate reboot after data wipe and downgrade to pre-V.
        simulateDataWipe();
        frpEnabled = false;
        mPdbService = new FakePersistentDataBlockService(mContext, mDataBlockFile.getPath(),
                DEFAULT_BLOCK_DEVICE_SIZE, frpEnabled, mFrpSecretFile.getPath(),
                mFrpSecretTmpFile.getPath());
        assertThat(mPdbService.isFrpActive()).isFalse();

        // Simulate reboot after upgrade to V+, no data wipe.
        frpEnabled = true;
        mIsUpgradingFromPreV = true;
        mPdbService = new FakePersistentDataBlockService(mContext, mDataBlockFile.getPath(),
                DEFAULT_BLOCK_DEVICE_SIZE, frpEnabled, mFrpSecretTmpFile.getPath(),
                mFrpSecretTmpFile.getPath());
        mPdbService.setAllowedUid(Binder.getCallingUid()); // Needed for setFrpSecret().
        assertThat(mPdbService.isFrpActive()).isTrue();
        assertThat(mPdbService.automaticallyDeactivateFrpIfPossible()).isTrue();
        assertThat(mPdbService.isFrpActive()).isFalse();
        assertThat(mPdbService.getInterfaceForTesting()
                .setFactoryResetProtectionSecret(new byte[FRP_SECRET_SIZE])).isTrue();

        // Simulate one more reboot.
        mIsUpgradingFromPreV = false;
        mPdbService = new FakePersistentDataBlockService(mContext, mDataBlockFile.getPath(),
                DEFAULT_BLOCK_DEVICE_SIZE, frpEnabled, mFrpSecretTmpFile.getPath(),
                mFrpSecretTmpFile.getPath());
        assertThat(mPdbService.automaticallyDeactivateFrpIfPossible()).isTrue();
        assertThat(mPdbService.isFrpActive()).isFalse();
    }

    @Test
    public void frpState_PrivilegedDeactivationByAuthorizedCaller() throws Exception {
        setUp(/* frpEnforcement */ true);
        grantConfigureFrpPermission();

        assertThat(mPdbService.isFrpActive()).isFalse();
        assertThat(mInterface.setFactoryResetProtectionSecret(hashStringto32Bytes("secret")))
                .isTrue();

        simulateDataWipe();
        mPdbService.activateFrp();
        assertThat(mPdbService.isFrpActive()).isTrue();

        assertThat(mPdbService.automaticallyDeactivateFrpIfPossible()).isFalse();
        assertThat(mPdbService.isFrpActive()).isTrue();

        assertThat(mInternalInterface.deactivateFactoryResetProtectionWithoutSecret()).isTrue();
        assertThat(mPdbService.isFrpActive()).isFalse();
    }

    @Test
    public void frpActive_WipeFails() throws Exception {
        setUp(/* frpEnforcement */ true);

        grantOemUnlockPermission();
        mPdbService.activateFrp();
        SecurityException e = assertThrows(SecurityException.class, () -> mInterface.wipe());
        assertThat(e).hasMessageThat().contains("FRP is active");
    }

    @Test
    public void frpActive_WriteFails() throws Exception {
        setUp(/* frpEnforcement */ true);

        mPdbService.activateFrp();
        SecurityException e =
                assertThrows(SecurityException.class, () -> mInterface.write("data".getBytes()));
        assertThat(e).hasMessageThat().contains("FRP is active");
    }

    @Test
    public void frpActive_SetSecretFails() throws Exception {
        setUp(/* frpEnforcement */ true);
        grantConfigureFrpPermission();

        mPdbService.activateFrp();

        byte[] hashedSecret = hashStringto32Bytes("secret");
        SecurityException e = assertThrows(SecurityException.class, ()
                -> mInterface.setFactoryResetProtectionSecret(hashedSecret));
        assertThat(e).hasMessageThat().contains("FRP is active");
        assertThat(mPdbService.isFrpActive()).isTrue();

        // Verify that secret we failed to set isn't accepted.
        assertThat(mInterface.deactivateFactoryResetProtection(hashedSecret)).isFalse();
        assertThat(mPdbService.isFrpActive()).isTrue();

        // Default should work, since it should never have been changed.
        assertThat(mPdbService.automaticallyDeactivateFrpIfPossible()).isTrue();
        assertThat(mPdbService.isFrpActive()).isFalse();
    }

    private void simulateDataWipe() throws IOException {
        Files.deleteIfExists(mFrpSecretFile.toPath());
        Files.deleteIfExists(mFrpSecretTmpFile.toPath());
    }

    private static byte[] hashStringto32Bytes(String secret) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(secret.getBytes());
    }

    private void tamperWithDigest() throws Exception {
        try (var ch = FileChannel.open(mDataBlockFile.toPath(), StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap("tampered-digest".getBytes()));
        }
    }

    private void tamperWithMagic() throws Exception {
        try (var ch = FileChannel.open(mDataBlockFile.toPath(), StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap("mark".getBytes()), DIGEST_SIZE_BYTES);
        }
    }

    private void makeUserAdmin(boolean isAdmin) {
        when(mUserManager.isUserAdmin(anyInt())).thenReturn(isAdmin);
    }

    private void grantOemUnlockPermission() {
        doReturn(PackageManager.PERMISSION_GRANTED).when(mContext)
                .checkCallingOrSelfPermission(eq(Manifest.permission.OEM_UNLOCK_STATE));
        doNothing().when(mContext)
                .enforceCallingOrSelfPermission(eq(Manifest.permission.OEM_UNLOCK_STATE),
                        anyString());
    }

    private void denyOemUnlockPermission() {
        doReturn(PackageManager.PERMISSION_DENIED).when(mContext)
                .checkCallingOrSelfPermission(eq(Manifest.permission.OEM_UNLOCK_STATE));
    }

    private void grantAccessPdbStatePermission() {
        doReturn(PackageManager.PERMISSION_GRANTED).when(mContext)
                .checkCallingPermission(eq(Manifest.permission.ACCESS_PDB_STATE));
    }

    private void grantConfigureFrpPermission() {
        doReturn(PackageManager.PERMISSION_GRANTED).when(mContext).checkCallingOrSelfPermission(
                eq(Manifest.permission.CONFIGURE_FACTORY_RESET_PROTECTION));
        doNothing().when(mContext).enforceCallingOrSelfPermission(
                eq(Manifest.permission.CONFIGURE_FACTORY_RESET_PROTECTION),
                anyString());
    }

    private ByteBuffer readBackingFile(long position, int size) throws Exception {
        try (var ch = FileChannel.open(mDataBlockFile.toPath(), StandardOpenOption.READ)) {
            var buffer = ByteBuffer.allocate(size);
            assertThat(ch.read(buffer, position)).isGreaterThan(0);
            return buffer;
        }
    }
}
