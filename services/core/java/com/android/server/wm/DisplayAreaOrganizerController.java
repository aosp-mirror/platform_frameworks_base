/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm;

import static android.Manifest.permission.MANAGE_ACTIVITY_STACKS;

import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.SurfaceControl;
import android.window.IDisplayAreaOrganizer;
import android.window.IDisplayAreaOrganizerController;

import java.util.HashMap;

public class DisplayAreaOrganizerController extends IDisplayAreaOrganizerController.Stub {
    private static final String TAG = "DisplayAreaOrganizerController";

    final ActivityTaskManagerService mService;
    private final WindowManagerGlobalLock mGlobalLock;
    private final HashMap<Integer, IDisplayAreaOrganizer> mOrganizersByFeatureIds = new HashMap();

    private class DeathRecipient implements IBinder.DeathRecipient {
        int mFeature;
        IDisplayAreaOrganizer mOrganizer;

        DeathRecipient(IDisplayAreaOrganizer organizer, int feature) {
            mOrganizer = organizer;
            mFeature = feature;
        }

        @Override
        public void binderDied() {
            synchronized (mGlobalLock) {
                mOrganizersByFeatureIds.remove(mFeature);
                mService.mRootWindowContainer.forAllDisplayAreas((da) -> {
                    if (da.mOrganizer != mOrganizer) return;
                    da.setOrganizer(null);
                });
            }
        }
    }

    DisplayAreaOrganizerController(ActivityTaskManagerService atm) {
        mService = atm;
        mGlobalLock = atm.mGlobalLock;
    }

    private void enforceStackPermission(String func) {
        mService.mAmInternal.enforceCallingPermission(MANAGE_ACTIVITY_STACKS, func);
    }

    @Override
    public void registerOrganizer(IDisplayAreaOrganizer organizer, int feature) {
        enforceStackPermission("registerOrganizer()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                if (mOrganizersByFeatureIds.get(feature) != null) {
                    throw new IllegalStateException(
                            "Replacing existing organizer currently unsupported");
                }

                final DeathRecipient dr = new DeathRecipient(organizer, feature);
                try {
                    organizer.asBinder().linkToDeath(dr, 0);
                } catch (RemoteException e) {
                    // Oh well...
                }
                mService.mRootWindowContainer.forAllDisplayAreas((da) -> {
                    if (da.mFeatureId != feature) return;
                    da.setOrganizer(organizer);
                });

                mOrganizersByFeatureIds.put(feature, organizer);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void unregisterOrganizer(IDisplayAreaOrganizer organizer) {
        enforceStackPermission("unregisterTaskOrganizer()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                mOrganizersByFeatureIds.entrySet().removeIf(
                        entry -> entry.getValue().asBinder() == organizer.asBinder());

                mService.mRootWindowContainer.forAllDisplayAreas((da) -> {
                    if (da.mOrganizer != organizer) return;
                    da.setOrganizer(null);
                });
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void onDisplayAreaAppeared(IDisplayAreaOrganizer organizer, DisplayArea da) {
        try {
            SurfaceControl outSurfaceControl = new SurfaceControl(da.getSurfaceControl(),
                    "DisplayAreaOrganizerController.onDisplayAreaAppeared");
            organizer.onDisplayAreaAppeared(da.getDisplayAreaInfo(), outSurfaceControl);
        } catch (RemoteException e) {
            // Oh well...
        }
    }

    void onDisplayAreaVanished(IDisplayAreaOrganizer organizer, DisplayArea da) {
        try {
            organizer.onDisplayAreaVanished(da.getDisplayAreaInfo());
        } catch (RemoteException e) {
            // Oh well...
        }
    }

    void onDisplayAreaInfoChanged(IDisplayAreaOrganizer organizer, DisplayArea da) {
        try {
            organizer.onDisplayAreaInfoChanged(da.getDisplayAreaInfo());
        } catch (RemoteException e) {
            // Oh well...
        }
    }
}
