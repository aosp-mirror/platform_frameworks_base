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
 * limitations under the License
 */

package com.android.server.locksettings;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import android.content.Context;

import com.android.server.pdb.PersistentDataBlockManagerInternal;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.util.Arrays;

public class LockSettingsStorageTestable extends LockSettingsStorage {

    public final File mStorageDir;
    public PersistentDataBlockManagerInternal mPersistentDataBlockManager;
    private byte[] mPersistentData;
    private boolean mIsFactoryResetProtectionActive = false;

    public LockSettingsStorageTestable(Context context, File storageDir) {
        super(context);
        mStorageDir = storageDir;
        mPersistentDataBlockManager = mock(PersistentDataBlockManagerInternal.class);
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                byte[] handle = (byte[]) invocation.getArguments()[0];
                if (handle != null) {
                    mPersistentData = Arrays.copyOf(handle, handle.length);
                } else {
                    mPersistentData = null;
                }
                return null;
            }
        }).when(mPersistentDataBlockManager).setFrpCredentialHandle(any());
        // For some reasons, simply mocking getFrpCredentialHandle() with
        // when(mPersistentDataBlockManager.getFrpCredentialHandle()).thenReturn(mPersistentData)
        // does not work, I had to use the long-winded way below.
        doAnswer(new Answer<byte[]>() {
            @Override
            public byte[] answer(InvocationOnMock invocation) throws Throwable {
                return mPersistentData;
            }
        }).when(mPersistentDataBlockManager).getFrpCredentialHandle();
    }

    void setTestFactoryResetProtectionState(boolean active) {
        mIsFactoryResetProtectionActive = active;
    }

    @Override
    File getChildProfileLockFile(int userId) {
        return remapToStorageDir(super.getChildProfileLockFile(userId));
    }

    @Override
    File getRebootEscrowServerBlobFile() {
        return remapToStorageDir(super.getRebootEscrowServerBlobFile());
    }

    @Override
    File getRebootEscrowFile(int userId) {
        return remapToStorageDir(super.getRebootEscrowFile(userId));
    }

    @Override
    protected File getSyntheticPasswordDirectoryForUser(int userId) {
        return remapToStorageDir(super.getSyntheticPasswordDirectoryForUser(userId));
    }

    @Override
    File getRepairModePersistentDataFile() {
        return remapToStorageDir(super.getRepairModePersistentDataFile());
    }

    @Override
    PersistentDataBlockManagerInternal getPersistentDataBlockManager() {
        return mPersistentDataBlockManager;
    }
    @Override
    public boolean isAutoPinConfirmSettingEnabled(int userId) {
        return true;
    }
    private File remapToStorageDir(File origPath) {
        File mappedPath = new File(mStorageDir, origPath.toString());
        mappedPath.getParentFile().mkdirs();
        return mappedPath;
    }

    @Override
    public boolean isFactoryResetProtectionActive() {
        return mIsFactoryResetProtectionActive;
    }
}
