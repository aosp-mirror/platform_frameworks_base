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
 * limitations under the License.
 */

package com.android.server.wm;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.MergedConfiguration;
import android.view.DisplayCutout;
import android.view.DragEvent;
import android.view.IWindow;
import android.view.InsetsSourceControl;
import android.view.InsetsState;

import com.android.internal.os.IResultReceiver;

public class TestIWindow extends IWindow.Stub {
    @Override
    public void executeCommand(String command, String parameters,
            ParcelFileDescriptor descriptor) throws RemoteException {
    }

    @Override
    public void resized(Rect frame, Rect overscanInsets, Rect contentInsets, Rect visibleInsets,
            Rect stableInsets, Rect outsets, boolean reportDraw, MergedConfiguration mergedConfig,
            Rect backDropFrame, boolean forceLayout, boolean alwaysConsumeNavBar, int displayId,
            DisplayCutout.ParcelableWrapper displayCutout) throws RemoteException {
    }

    @Override
    public void insetsChanged(InsetsState insetsState) throws RemoteException {
    }

    @Override
    public void insetsControlChanged(InsetsState insetsState, InsetsSourceControl[] activeControls)
            throws RemoteException {
    }

    @Override
    public void moved(int newX, int newY) throws RemoteException {
    }

    @Override
    public void dispatchAppVisibility(boolean visible) throws RemoteException {
    }

    @Override
    public void dispatchGetNewSurface() throws RemoteException {
    }

    @Override
    public void windowFocusChanged(boolean hasFocus, boolean inTouchMode) throws RemoteException {
    }

    @Override
    public void closeSystemDialogs(String reason) throws RemoteException {
    }

    @Override
    public void dispatchWallpaperOffsets(float x, float y, float xStep, float yStep, boolean sync)
            throws RemoteException {
    }

    @Override
    public void dispatchWallpaperCommand(String action, int x, int y, int z, Bundle extras,
            boolean sync) throws RemoteException {
    }

    @Override
    public void dispatchDragEvent(DragEvent event) throws RemoteException {
    }

    @Override
    public void updatePointerIcon(float x, float y) throws RemoteException {
    }

    @Override
    public void dispatchSystemUiVisibilityChanged(int seq, int globalVisibility, int localValue,
            int localChanges) throws RemoteException {
    }

    @Override
    public void dispatchWindowShown() throws RemoteException {
    }

    @Override
    public void requestAppKeyboardShortcuts(IResultReceiver receiver, int deviceId)
            throws RemoteException {
    }

    @Override
    public void dispatchPointerCaptureChanged(boolean hasCapture) {
    }
}
