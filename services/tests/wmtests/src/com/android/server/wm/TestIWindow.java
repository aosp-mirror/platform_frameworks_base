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

import android.annotation.Nullable;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.MergedConfiguration;
import android.view.DragEvent;
import android.view.IScrollCaptureResponseListener;
import android.view.IWindow;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.ScrollCaptureResponse;
import android.view.inputmethod.ImeTracker;
import android.window.ActivityWindowInfo;
import android.window.ClientWindowFrames;

import com.android.internal.os.IResultReceiver;

import java.util.ArrayList;

public class TestIWindow extends IWindow.Stub {

    private ArrayList<DragEvent> mDragEvents;

    @Override
    public void executeCommand(String command, String parameters,
            ParcelFileDescriptor descriptor) throws RemoteException {
    }

    @Override
    public void resized(ClientWindowFrames frames, boolean reportDraw,
            MergedConfiguration mergedConfig, InsetsState insetsState, boolean forceLayout,
            boolean alwaysConsumeSystemBars, int displayId, int seqId, boolean dragResizing,
            @Nullable ActivityWindowInfo activityWindowInfo) throws RemoteException {
    }

    @Override
    public void insetsControlChanged(InsetsState insetsState,
            InsetsSourceControl[] activeControls) {
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
    public void closeSystemDialogs(String reason) throws RemoteException {
    }

    @Override
    public void dispatchWallpaperOffsets(float x, float y, float xStep, float yStep, float zoom,
            boolean sync)
            throws RemoteException {
    }

    @Override
    public void dispatchWallpaperCommand(String action, int x, int y, int z, Bundle extras,
            boolean sync) throws RemoteException {
    }

    public void setDragEventJournal(ArrayList<DragEvent> journal) {
        mDragEvents = journal;
    }

    @Override
    public void dispatchDragEvent(DragEvent event) throws RemoteException {
        if (mDragEvents != null) {
            mDragEvents.add(DragEvent.obtain(event));
        }
    }

    @Override
    public void updatePointerIcon(float x, float y) throws RemoteException {
    }

    @Override
    public void dispatchWindowShown() throws RemoteException {
    }

    @Override
    public void requestAppKeyboardShortcuts(IResultReceiver receiver, int deviceId)
            throws RemoteException {
    }

    @Override
    public void requestScrollCapture(IScrollCaptureResponseListener listener)
            throws RemoteException {
        try {
            listener.onScrollCaptureResponse(
                    new ScrollCaptureResponse.Builder().setDescription("Not Implemented").build());

        } catch (RemoteException ex) {
            // ignore
        }
    }

    @Override
    public void showInsets(int types, boolean fromIme, @Nullable ImeTracker.Token statsToken)
            throws RemoteException {
    }

    @Override
    public void hideInsets(int types, boolean fromIme, @Nullable ImeTracker.Token statsToken)
            throws RemoteException {
    }

    @Override
    public void dumpWindow(ParcelFileDescriptor pfd) {

    }
}
