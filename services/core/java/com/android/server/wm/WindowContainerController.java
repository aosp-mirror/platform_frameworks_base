/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wm;

import android.content.res.Configuration;

/**
 * Class that allows the owner/creator of a {@link WindowContainer} to communicate directly with the
 * container and make changes.
 * Note that public calls (mostly in sub-classes) into this class are assumed to be originating from
 * outside the window manager so the window manager lock is held and appropriate permissions are
 * checked before calls are allowed to proceed.
 *
 * Test class: {@link WindowContainerControllerTests}
 */
class WindowContainerController<E extends WindowContainer, I extends WindowContainerListener>
        implements ConfigurationContainerListener {

    final WindowManagerService mService;
    final RootWindowContainer mRoot;
    final WindowManagerGlobalLock mGlobalLock;

    // The window container this controller owns.
    E mContainer;
    // Interface for communicating changes back to the owner.
    final I mListener;

    WindowContainerController(I listener, WindowManagerService service) {
        mListener = listener;
        mService = service;
        mRoot = mService != null ? mService.mRoot : null;
        mGlobalLock = mService != null ? mService.mGlobalLock : null;
    }

    void setContainer(E container) {
        if (mContainer != null && container != null) {
            throw new IllegalArgumentException("Can't set container=" + container
                    + " for controller=" + this + " Already set to=" + mContainer);
        }
        mContainer = container;
        if (mContainer != null && mListener != null) {
            mListener.registerConfigurationChangeListener(this);
        }
    }

    void removeContainer() {
        // TODO: See if most uses cases should support removeIfPossible here.
        //mContainer.removeIfPossible();
        if (mContainer == null) {
            return;
        }

        mContainer.setController(null);
        mContainer = null;
        if (mListener != null) {
            mListener.unregisterConfigurationChangeListener(this);
        }
    }

    @Override
    public void onRequestedOverrideConfigurationChanged(Configuration overrideConfiguration) {
        synchronized (mGlobalLock) {
            if (mContainer == null) {
                return;
            }
            mContainer.onRequestedOverrideConfigurationChanged(overrideConfiguration);
        }
    }
}
