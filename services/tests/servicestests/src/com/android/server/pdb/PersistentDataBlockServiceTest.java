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
import static com.android.server.pdb.PersistentDataBlockService.MAX_DATA_BLOCK_SIZE;
import static com.android.server.pdb.PersistentDataBlockService.MAX_FRP_CREDENTIAL_HANDLE_SIZE;
import static com.android.server.pdb.PersistentDataBlockService.MAX_TEST_MODE_DATA_SIZE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.junit.Assert.assertThrows;

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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

@RunWith(JUnitParamsRunner.class)
public class PersistentDataBlockServiceTest {
    private static final String TAG = "PersistentDataBlockServiceTest";

    private static final byte[] SMALL_DATA = "data to write".getBytes();
    private static final byte[] ANOTHER_SMALL_DATA = "something else".getBytes();

    private Context mContext;
    private PersistentDataBlockService mPdbService;
    private IPersistentDataBlockService mInterface;
    private PersistentDataBlockManagerInternal mInternalInterface;
    private File mDataBlockFile;
    private String mOemUnlockPropertyValue;

    @Mock private UserManager mUserManager;

    private class FakePersistentDataBlockService extends PersistentDataBlockService {
        FakePersistentDataBlockService(Context context, String dataBlockFile,
                long blockDeviceSize) {
            super(context, /* isFileBacked */ true, dataBlockFile, blockDeviceSize);
        }

        @Override
        void setProperty(String key, String value) {
            // Override to capture the value instead of actually setting the property.
            assertThat(key).isEqualTo("sys.oem_unlock_allowed");
            mOemUnlockPropertyValue = value;
        }
    }

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mDataBlockFile = mTemporaryFolder.newFile();
        mContext = spy(ApplicationProvider.getApplicationContext());
        mPdbService = new FakePersistentDataBlockService(mContext, mDataBlockFile.getPath(),
                /* blockDeviceSize */ -1);
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
        return new Object[][] {
            {
                new Block() {
                    @Override public int write(byte[] data) throws RemoteException {
                        return service.getInterfaceForTesting().write(data);
                    }

                    @Override public byte[] read() throws RemoteException {
                        return service.getInterfaceForTesting().read();
                    }
                },
            },
            {
                new Block() {
                    @Override public int write(byte[] data) {
                        service.getInternalInterfaceForTesting().setFrpCredentialHandle(data);
                        // The written size isn't returned. Pretend it's fully written in the
                        // test for now.
                        return data.length;
                    }

                    @Override public byte[] read() {
                        return service.getInternalInterfaceForTesting().getFrpCredentialHandle();
                    }
                },
            },
            {
                new Block() {
                    @Override public int write(byte[] data) {
                        service.getInternalInterfaceForTesting().setTestHarnessModeData(data);
                        // The written size isn't returned. Pretend it's fully written in the
                        // test for now.
                        return data.length;
                    }

                    @Override public byte[] read() {
                        return service.getInternalInterfaceForTesting().getTestHarnessModeData();
                    }
                },
            },
        };
    }

    @Test
    @Parameters(method = "getTestParametersForBlocks")
    public void writeThenRead(Block block) throws Exception {
        block.service = mPdbService;
        assertThat(block.write(SMALL_DATA)).isEqualTo(SMALL_DATA.length);
        assertThat(block.read()).isEqualTo(SMALL_DATA);
    }

    @Test
    @Parameters(method = "getTestParametersForBlocks")
    public void writeWhileAlreadyCorrupted(Block block) throws Exception {
        block.service = mPdbService;
        assertThat(block.write(SMALL_DATA)).isEqualTo(SMALL_DATA.length);
        assertThat(block.read()).isEqualTo(SMALL_DATA);

        tamperWithDigest();

        // In the currently implementation, expect the write to not trigger formatting.
        assertThat(block.write(ANOTHER_SMALL_DATA)).isEqualTo(ANOTHER_SMALL_DATA.length);
    }

    @Test
    public void frpWriteOutOfBound() throws Exception {
        byte[] maxData = new byte[mPdbService.getMaximumFrpDataSize()];
        assertThat(mInterface.write(maxData)).isEqualTo(maxData.length);

        byte[] overflowData = new byte[mPdbService.getMaximumFrpDataSize() + 1];
        assertThat(mInterface.write(overflowData)).isLessThan(0);
    }

    @Test
    public void frpCredentialWriteOutOfBound() throws Exception {
        byte[] maxData = new byte[MAX_FRP_CREDENTIAL_HANDLE_SIZE];
        mInternalInterface.setFrpCredentialHandle(maxData);

        byte[] overflowData = new byte[MAX_FRP_CREDENTIAL_HANDLE_SIZE + 1];
        assertThrows(IllegalArgumentException.class, () ->
                mInternalInterface.setFrpCredentialHandle(overflowData));
    }

    @Test
    public void testHardnessWriteOutOfBound() throws Exception {
        byte[] maxData = new byte[MAX_TEST_MODE_DATA_SIZE];
        mInternalInterface.setTestHarnessModeData(maxData);

        byte[] overflowData = new byte[MAX_TEST_MODE_DATA_SIZE + 1];
        assertThrows(IllegalArgumentException.class, () ->
                mInternalInterface.setTestHarnessModeData(overflowData));
    }

    @Test
    public void readCorruptedFrpData() throws Exception {
        assertThat(mInterface.write(SMALL_DATA)).isEqualTo(SMALL_DATA.length);
        assertThat(mInterface.read()).isEqualTo(SMALL_DATA);

        tamperWithDigest();

        // Expect the read to trigger formatting, resulting in reading empty data.
        assertThat(mInterface.read()).hasLength(0);
    }

    @Test
    public void readCorruptedFrpCredentialData() throws Exception {
        mInternalInterface.setFrpCredentialHandle(SMALL_DATA);
        assertThat(mInternalInterface.getFrpCredentialHandle()).isEqualTo(SMALL_DATA);

        tamperWithDigest();

        assertThrows(IllegalStateException.class, () ->
                mInternalInterface.getFrpCredentialHandle());
    }

    @Test
    public void readCorruptedTestHarnessData() throws Exception {
        mInternalInterface.setTestHarnessModeData(SMALL_DATA);
        assertThat(mInternalInterface.getTestHarnessModeData()).isEqualTo(SMALL_DATA);

        tamperWithDigest();

        assertThrows(IllegalStateException.class, () ->
                mInternalInterface.getTestHarnessModeData());
    }

    @Test
    public void nullWrite() throws Exception {
        assertThrows(NullPointerException.class, () -> mInterface.write(null));
        mInternalInterface.setFrpCredentialHandle(null);  // no exception
        mInternalInterface.setTestHarnessModeData(null);  // no exception
    }

    @Test
    public void emptyDataWrite() throws Exception {
        var empty = new byte[0];
        assertThat(mInterface.write(empty)).isEqualTo(0);

        assertThrows(IllegalArgumentException.class, () ->
                mInternalInterface.setFrpCredentialHandle(empty));
        assertThrows(IllegalArgumentException.class, () ->
                mInternalInterface.setTestHarnessModeData(empty));
    }

    @Test
    public void frpWriteMoreThan100K() throws Exception {
        File dataBlockFile = mTemporaryFolder.newFile();
        PersistentDataBlockService pdbService = new FakePersistentDataBlockService(mContext,
                dataBlockFile.getPath(), /* blockDeviceSize */ 128 * 1000);
        pdbService.setAllowedUid(Binder.getCallingUid());
        pdbService.formatPartitionLocked(/* setOemUnlockEnabled */ false);

        IPersistentDataBlockService service = pdbService.getInterfaceForTesting();
        int maxDataSize = (int) service.getMaximumDataBlockSize();
        assertThat(service.write(new byte[maxDataSize])).isEqualTo(maxDataSize);
        assertThat(service.write(new byte[maxDataSize + 1])).isEqualTo(-MAX_DATA_BLOCK_SIZE);
    }

    @Test
    public void frpBlockReadWriteWithoutPermission() throws Exception {
        mPdbService.setAllowedUid(Binder.getCallingUid() + 1);  // unexpected uid
        assertThrows(SecurityException.class, () -> mInterface.write(SMALL_DATA));
        assertThrows(SecurityException.class, () -> mInterface.read());
    }

    @Test
    public void getMaximumDataBlockSizeDenied() throws Exception {
        mPdbService.setAllowedUid(Binder.getCallingUid() + 1);  // unexpected uid
        assertThrows(SecurityException.class, () -> mInterface.getMaximumDataBlockSize());
    }

    @Test
    public void getMaximumDataBlockSize() throws Exception {
        mPdbService.setAllowedUid(Binder.getCallingUid());
        assertThat(mInterface.getMaximumDataBlockSize())
                .isEqualTo(mPdbService.getMaximumFrpDataSize());
    }

    @Test
    public void getMaximumDataBlockSizeOfLargerPartition() throws Exception {
        File dataBlockFile = mTemporaryFolder.newFile();
        PersistentDataBlockService pdbService = new FakePersistentDataBlockService(mContext,
                dataBlockFile.getPath(), /* blockDeviceSize */ 128 * 1000);
        pdbService.setAllowedUid(Binder.getCallingUid());
        pdbService.formatPartitionLocked(/* setOemUnlockEnabled */ false);

        IPersistentDataBlockService service = pdbService.getInterfaceForTesting();
        assertThat(service.getMaximumDataBlockSize()).isEqualTo(MAX_DATA_BLOCK_SIZE);
    }

    @Test
    public void getFrpDataBlockSizeGrantedByUid() throws Exception {
        assertThat(mInterface.write(SMALL_DATA)).isEqualTo(SMALL_DATA.length);

        mPdbService.setAllowedUid(Binder.getCallingUid());
        assertThat(mInterface.getDataBlockSize()).isEqualTo(SMALL_DATA.length);

        // Modify the magic / type marker. In the current implementation, getting the FRP data block
        // size does not check digest.
        tamperWithMagic();
        assertThat(mInterface.getDataBlockSize()).isEqualTo(0);
    }

    @Test
    public void getFrpDataBlockSizeGrantedByPermission() throws Exception {
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
    public void wipePermissionCheck() throws Exception {
        denyOemUnlockPermission();
        assertThrows(SecurityException.class, () -> mInterface.wipe());
    }

    @Test
    public void wipeMakesItNotWritable() throws Exception {
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
    public void hasFrpCredentialHandleGrantedByUid() throws Exception {
        mPdbService.setAllowedUid(Binder.getCallingUid());

        assertThat(mInterface.hasFrpCredentialHandle()).isFalse();
        mInternalInterface.setFrpCredentialHandle(SMALL_DATA);
        assertThat(mInterface.hasFrpCredentialHandle()).isTrue();
    }

    @Test
    public void hasFrpCredentialHandleGrantedByPermission() throws Exception {
        mPdbService.setAllowedUid(Binder.getCallingUid() + 1);  // unexpected uid
        grantAccessPdbStatePermission();

        assertThat(mInterface.hasFrpCredentialHandle()).isFalse();
        mInternalInterface.setFrpCredentialHandle(SMALL_DATA);
        assertThat(mInterface.hasFrpCredentialHandle()).isTrue();
    }

    @Test
    public void clearTestHarnessModeData() throws Exception {
        mInternalInterface.setTestHarnessModeData(SMALL_DATA);
        mInternalInterface.clearTestHarnessModeData();

        assertThat(readBackingFile(mPdbService.getTestHarnessModeDataOffset(),
                    MAX_TEST_MODE_DATA_SIZE).array())
                .isEqualTo(new byte[MAX_TEST_MODE_DATA_SIZE]);
    }

    @Test
    public void getAllowedUid() throws Exception {
        assertThat(mInternalInterface.getAllowedUid()).isEqualTo(Binder.getCallingUid());
    }

    @Test
    public void oemUnlockWithoutPermission() throws Exception {
        denyOemUnlockPermission();

        assertThrows(SecurityException.class, () -> mInterface.setOemUnlockEnabled(true));
    }

    @Test
    public void oemUnlockNotAdmin() throws Exception {
        grantOemUnlockPermission();
        makeUserAdmin(false);

        assertThrows(SecurityException.class, () -> mInterface.setOemUnlockEnabled(true));
    }

    @Test
    public void oemUnlock() throws Exception {
        grantOemUnlockPermission();
        makeUserAdmin(true);

        mInterface.setOemUnlockEnabled(true);
        assertThat(mInterface.getOemUnlockEnabled()).isTrue();
        assertThat(mOemUnlockPropertyValue).isEqualTo("1");
    }

    @Test
    public void oemUnlockUserRestriction_OemUnlock() throws Exception {
        grantOemUnlockPermission();
        makeUserAdmin(true);
        when(mUserManager.hasUserRestriction(eq(UserManager.DISALLOW_OEM_UNLOCK)))
                .thenReturn(true);

        assertThrows(SecurityException.class, () -> mInterface.setOemUnlockEnabled(true));
    }

    @Test
    public void oemUnlockUserRestriction_FactoryReset() throws Exception {
        grantOemUnlockPermission();
        makeUserAdmin(true);
        when(mUserManager.hasUserRestriction(eq(UserManager.DISALLOW_FACTORY_RESET)))
                .thenReturn(true);

        assertThrows(SecurityException.class, () -> mInterface.setOemUnlockEnabled(true));
    }

    @Test
    public void oemUnlockIgnoreTampering() throws Exception {
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
    public void getOemUnlockEnabledPermissionCheck_NoPermission() throws Exception {
        assertThrows(SecurityException.class, () -> mInterface.getOemUnlockEnabled());
    }

    @Test
    public void getOemUnlockEnabledPermissionCheck_OemUnlcokState() throws Exception {
        doReturn(PackageManager.PERMISSION_GRANTED).when(mContext)
                .checkCallingOrSelfPermission(eq(Manifest.permission.OEM_UNLOCK_STATE));
        assertThat(mInterface.getOemUnlockEnabled()).isFalse();
    }

    @Test
    public void getOemUnlockEnabledPermissionCheck_ReadOemUnlcokState() throws Exception {
        doReturn(PackageManager.PERMISSION_GRANTED).when(mContext)
                .checkCallingOrSelfPermission(eq(Manifest.permission.READ_OEM_UNLOCK_STATE));
        assertThat(mInterface.getOemUnlockEnabled()).isFalse();
    }

    @Test
    public void forceOemUnlock_RequiresNoPermission() throws Exception {
        denyOemUnlockPermission();

        mInternalInterface.forceOemUnlockEnabled(true);

        assertThat(mOemUnlockPropertyValue).isEqualTo("1");
        assertThat(readBackingFile(mPdbService.getOemUnlockDataOffset(), 1).array())
                .isEqualTo(new byte[] { 1 });
    }

    @Test
    public void getFlashLockStatePermissionCheck_NoPermission() throws Exception {
        assertThrows(SecurityException.class, () -> mInterface.getFlashLockState());
    }

    @Test
    public void getFlashLockStatePermissionCheck_OemUnlcokState() throws Exception {
        doReturn(PackageManager.PERMISSION_GRANTED).when(mContext)
                .checkCallingOrSelfPermission(eq(Manifest.permission.OEM_UNLOCK_STATE));
        mInterface.getFlashLockState();  // Do not throw
    }

    @Test
    public void getFlashLockStatePermissionCheck_ReadOemUnlcokState() throws Exception {
        doReturn(PackageManager.PERMISSION_GRANTED).when(mContext)
                .checkCallingOrSelfPermission(eq(Manifest.permission.READ_OEM_UNLOCK_STATE));
        mInterface.getFlashLockState();  // Do not throw
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

    private ByteBuffer readBackingFile(long position, int size) throws Exception {
        try (var ch = FileChannel.open(mDataBlockFile.toPath(), StandardOpenOption.READ)) {
            var buffer = ByteBuffer.allocate(size);
            assertThat(ch.read(buffer, position)).isGreaterThan(0);
            return buffer;
        }
    }
}
