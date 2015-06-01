/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.layoutlib.bridge.android;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.view.DragEvent;
import android.view.IWindow;

/**
 * Implementation of {@link IWindow} to pass to the AttachInfo.
 */
public final class BridgeWindow implements IWindow {

    @Override
    public void dispatchAppVisibility(boolean arg0) throws RemoteException {
        // pass for now.
    }

    @Override
    public void dispatchGetNewSurface() throws RemoteException {
        // pass for now.
    }

    @Override
    public void executeCommand(String arg0, String arg1, ParcelFileDescriptor arg2)
            throws RemoteException {
        // pass for now.
    }

    @Override
    public void resized(Rect rect, Rect rect2, Rect rect3, Rect rect4, Rect rect5, Rect rect6,
            boolean b, Configuration configuration) throws RemoteException {
        // pass for now.
    }

    @Override
    public void moved(int arg0, int arg1) throws RemoteException {
        // pass for now.
    }

    @Override
    public void windowFocusChanged(boolean arg0, boolean arg1) throws RemoteException {
        // pass for now.
    }

    @Override
    public void dispatchWallpaperOffsets(float x, float y, float xStep, float yStep,
            boolean sync) {
        // pass for now.
    }

    @Override
    public void dispatchWallpaperCommand(String action, int x, int y,
            int z, Bundle extras, boolean sync) {
        // pass for now.
    }

    @Override
    public void closeSystemDialogs(String reason) {
        // pass for now.
    }

    @Override
    public void dispatchDragEvent(DragEvent event) {
        // pass for now.
    }

    @Override
    public void dispatchSystemUiVisibilityChanged(int seq, int globalUi,
            int localValue, int localChanges) {
        // pass for now.
    }

    @Override
    public void onAnimationStarted(int remainingFrameCount) {
    }

    @Override
    public void onAnimationStopped() {
    }

    @Override
    public void dispatchWindowShown() {
    }

    @Override
    public IBinder asBinder() {
        // pass for now.
        return null;
    }
}
