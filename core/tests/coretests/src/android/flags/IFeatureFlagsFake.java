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
package android.flags;

import android.os.IBinder;
import android.os.RemoteException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

class IFeatureFlagsFake implements IFeatureFlags {

    private final Set<IFeatureFlagsCallback> mCallbacks = new HashSet<>();

    List<SyncableFlag> mOverrides;

    @Override
    public IBinder asBinder() {
        return null;
    }

    @Override
    public List<SyncableFlag> syncFlags(List<SyncableFlag> flagList) {
        return mOverrides == null ? flagList : mOverrides;
    }

    @Override
    public List<SyncableFlag> queryFlags(List<SyncableFlag> flagList) {
        return mOverrides == null ? flagList : mOverrides;    }

    @Override
    public void overrideFlag(SyncableFlag syncableFlag) {
        SyncableFlag match = findFlag(syncableFlag);
        if (match != null) {
            mOverrides.remove(match);
        }

        mOverrides.add(syncableFlag);

        for (IFeatureFlagsCallback cb : mCallbacks) {
            try {
                cb.onFlagChange(syncableFlag);
            } catch (RemoteException e) {
                // does not happen in fakes.
            }
        }
    }

    @Override
    public void resetFlag(SyncableFlag syncableFlag) {
        SyncableFlag match = findFlag(syncableFlag);
        if (match != null) {
            mOverrides.remove(match);
        }

        for (IFeatureFlagsCallback cb : mCallbacks) {
            try {
                cb.onFlagChange(syncableFlag);
            } catch (RemoteException e) {
                // does not happen in fakes.
            }
        }
    }

    private SyncableFlag findFlag(SyncableFlag syncableFlag) {
        SyncableFlag match = null;
        for (SyncableFlag sf : mOverrides) {
            if (sf.getName().equals(syncableFlag.getName())
                    && sf.getNamespace().equals(syncableFlag.getNamespace())) {
                match = sf;
                break;
            }
        }

        return match;
    }
    @Override
    public void registerCallback(IFeatureFlagsCallback callback) {
        mCallbacks.add(callback);
    }

    @Override
    public void unregisterCallback(IFeatureFlagsCallback callback) {
        mCallbacks.remove(callback);
    }

    public void setFlagOverrides(List<SyncableFlag> flagList) {
        mOverrides = flagList;
        for (SyncableFlag sf : flagList) {
            for (IFeatureFlagsCallback cb : mCallbacks) {
                try {
                    cb.onFlagChange(sf);
                } catch (RemoteException e) {
                    // does not happen in fakes.
                }
            }
        }
    }
}
