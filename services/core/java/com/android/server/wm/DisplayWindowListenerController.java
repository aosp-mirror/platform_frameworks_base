/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.res.Configuration;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.view.IDisplayWindowListener;

/**
 * Manages dispatch of relevant hierarchy changes to interested listeners. Listeners are assumed
 * to be remote.
 */
class DisplayWindowListenerController {
    RemoteCallbackList<IDisplayWindowListener> mDisplayListeners = new RemoteCallbackList<>();

//    private final ArrayList<DisplayContainerListener> mDisplayListeners = new ArrayList<>();
    private final WindowManagerService mService;

    DisplayWindowListenerController(WindowManagerService service) {
        mService = service;
    }

    void registerListener(IDisplayWindowListener listener) {
        synchronized (mService.mGlobalLock) {
            mDisplayListeners.register(listener);
            try {
                for (int i = 0; i < mService.mAtmService.mRootWindowContainer.getChildCount();
                        ++i) {
                    DisplayContent d = mService.mAtmService.mRootWindowContainer.getChildAt(i);
                    listener.onDisplayAdded(d.mDisplayId);
                }
            } catch (RemoteException e) { }
        }
    }

    void unregisterListener(IDisplayWindowListener listener) {
        mDisplayListeners.unregister(listener);
    }

    void dispatchDisplayAdded(DisplayContent display) {
        int count = mDisplayListeners.beginBroadcast();
        for (int i = 0; i < count; ++i) {
            try {
                mDisplayListeners.getBroadcastItem(i).onDisplayAdded(display.mDisplayId);
            } catch (RemoteException e) {
            }
        }
        mDisplayListeners.finishBroadcast();
    }

    void dispatchDisplayChanged(DisplayContent display, Configuration newConfig) {
        // Only report changed if this has actually been added to the hierarchy already.
        boolean isInHierarchy = false;
        for (int i = 0; i < display.getParent().getChildCount(); ++i) {
            if (display.getParent().getChildAt(i) == display) {
                isInHierarchy = true;
            }
        }
        if (!isInHierarchy) {
            return;
        }
        int count = mDisplayListeners.beginBroadcast();
        for (int i = 0; i < count; ++i) {
            try {
                mDisplayListeners.getBroadcastItem(i).onDisplayConfigurationChanged(
                        display.getDisplayId(), newConfig);
            } catch (RemoteException e) {
            }
        }
        mDisplayListeners.finishBroadcast();
    }

    void dispatchDisplayRemoved(DisplayContent display) {
        int count = mDisplayListeners.beginBroadcast();
        for (int i = 0; i < count; ++i) {
            try {
                mDisplayListeners.getBroadcastItem(i).onDisplayRemoved(display.mDisplayId);
            } catch (RemoteException e) {
            }
        }
        mDisplayListeners.finishBroadcast();
    }

    void dispatchFixedRotationStarted(DisplayContent display, int newRotation) {
        int count = mDisplayListeners.beginBroadcast();
        for (int i = 0; i < count; ++i) {
            try {
                mDisplayListeners.getBroadcastItem(i).onFixedRotationStarted(
                        display.mDisplayId, newRotation);
            } catch (RemoteException e) {
            }
        }
        mDisplayListeners.finishBroadcast();
    }

    void dispatchFixedRotationFinished(DisplayContent display) {
        int count = mDisplayListeners.beginBroadcast();
        for (int i = 0; i < count; ++i) {
            try {
                mDisplayListeners.getBroadcastItem(i).onFixedRotationFinished(display.mDisplayId);
            } catch (RemoteException e) {
            }
        }
        mDisplayListeners.finishBroadcast();
    }
}
