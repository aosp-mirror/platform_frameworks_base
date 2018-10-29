/*
 * Copyright (C) 2018 The Android Open Source Project
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

/**
 * Controller for the root container. This is created by activity manager to link activity
 * stack supervisor to the root window container they use in window manager.
 */
public class RootWindowContainerController
        extends WindowContainerController<RootWindowContainer, RootWindowContainerListener> {

    public RootWindowContainerController(RootWindowContainerListener listener) {
        super(listener, WindowManagerService.getInstance());
        synchronized (mGlobalLock) {
            mRoot.setController(this);
        }
    }

    void onChildPositionChanged(DisplayContent child, int position) {
        // This callback invokes to AM directly so here assumes AM lock is held. If there is another
        // path called only with WM lock, it should change to use handler to post or move outside of
        // WM lock with adding AM lock.
        mListener.onChildPositionChanged(child.getController(), position);
    }

    /** Move the display to the given position. */
    public void positionChildAt(DisplayWindowController child, int position) {
        synchronized (mGlobalLock) {
            mContainer.positionChildAt(position, child.mContainer);
        }
    }
}
