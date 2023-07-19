/*
 * Copyright (C) 2022 Project Kaleidoscope
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

package ink.kaleidoscope;

import android.content.Context;
import android.content.pm.UserInfo;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ink.kaleidoscope.IParallelSpaceManager;

/**
 * Simple wrapper for ParallelSpaceManagerService.
 * It requires android.permission.MANAGE_PARALLEL_SPACES.
 * @hide
 */
public final class ParallelSpaceManager {

    public static final String SERVICE_NAME = "parallel";
    private static final String TAG = "ParallelSpaceManager";

    private static ParallelSpaceManager sParallelSpaceManager;
    private IParallelSpaceManager mParallelSpaceManager;

    private ParallelSpaceManager() {
        mParallelSpaceManager = IParallelSpaceManager.Stub.asInterface(
                ServiceManager.getService(SERVICE_NAME));
        if (mParallelSpaceManager == null)
            throw new RuntimeException("Unable to get ParallelSpaceManagerService.");
    }

    public static ParallelSpaceManager getInstance() {
        if (sParallelSpaceManager != null)
            return sParallelSpaceManager;
        sParallelSpaceManager = new ParallelSpaceManager();
        return sParallelSpaceManager;
    }

    private int create(String name, boolean shareMedia) {
        try {
            return mParallelSpaceManager.create(name, shareMedia);
        } catch (RemoteException e) {
            throw new RuntimeException("Failed when create(): " + e);
        }
    }

    /**
     * Create a parallel space. Returns user id on success, -1 otherwise.
     */
    public int create(String name) {
        return create(name, false);
    }

    /**
     * Remove a parallel space. Returns 0 on success.
     */
    public int remove(int userId) {
        try {
            return mParallelSpaceManager.remove(userId);
        } catch (RemoteException e) {
            throw new RuntimeException("Failed when remove(): " + e);
        }
    }

    /**
     * Get a UserInfo list of current existing parallel users.
     */
    public List<UserInfo> getParallelUsers() {
        try {
            return new ArrayList<>(Arrays.asList(mParallelSpaceManager.getUsers()));
        } catch (RemoteException e) {
            throw new RuntimeException("Failed when getUsers(): " + e);
        }
    }

    /**
     * Get a user id list of current existing parallel users.
     */
    public List<Integer> getParallelUserIds() {
        ArrayList<Integer> ret = new ArrayList<>();
        getParallelUsers().forEach(user -> ret.add(user.id));
        return ret;
    }

    /**
     * Get a UserHandle list of current existing parallel users.
     */
    public List<UserHandle> getParallelUserHandles() {
        ArrayList<UserHandle> ret = new ArrayList<>();
        getParallelUserIds().forEach(id -> ret.add(UserHandle.of(id)));
        return ret;
    }

    /**
     * Get UserInfo of currently foreground parallel space owner.
     */
    public UserInfo getParallelOwner() {
        try {
            return mParallelSpaceManager.getOwner();
        } catch (RemoteException e) {
            throw new RuntimeException("Failed when getParallelOwner(): " + e);
        }
    }

    /**
     * Get user id of currently foreground parallel space owner.
     */
    public int getParallelOwnerId() {
        UserInfo owner = getParallelOwner();
        return owner != null ? owner.id : 0;
    }

    /**
     * Get UserHandle of currently foreground parallel space owner.
     */
    public UserHandle getParallelOwnerHandle() {
        return UserHandle.of(getParallelOwnerId());
    }

    /**
     * Install an existing package to target user. Returns 0 on success.
     */
    public int duplicatePackage(String packageName, int userId) {
        try {
            return mParallelSpaceManager.duplicatePackage(packageName, userId);
        } catch (RemoteException e) {
            throw new RuntimeException("Failed when duplicatePackage(): " + e);
        }
    }

    /**
     * Remove a package from target user. Returns 0 on success.
     */
    public int removePackage(String packageName, int userId) {
        try {
            return mParallelSpaceManager.removePackage(packageName, userId);
        } catch (RemoteException e) {
            throw new RuntimeException("Failed when removePackage(): " + e);
        }
    }
}
