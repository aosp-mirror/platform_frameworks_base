/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.internal.view;

import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.MergedConfiguration;
import android.view.DisplayCutout;
import android.view.DragEvent;
import android.view.IWindow;
import android.view.IWindowSession;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.PointerIcon;

import com.android.internal.os.IResultReceiver;

public class BaseIWindow extends IWindow.Stub {
    private IWindowSession mSession;
    public int mSeq;

    public void setSession(IWindowSession session) {
        mSession = session;
    }

    @Override
    public void resized(Rect frame, Rect overscanInsets, Rect contentInsets, Rect visibleInsets,
            Rect stableInsets, Rect outsets, boolean reportDraw,
            MergedConfiguration mergedConfiguration, Rect backDropFrame, boolean forceLayout,
            boolean alwaysConsumeSystemBars, int displayId,
            DisplayCutout.ParcelableWrapper displayCutout) {
        if (reportDraw) {
            try {
                mSession.finishDrawing(this);
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public void insetsChanged(InsetsState insetsState) {
    }

    @Override
    public void insetsControlChanged(InsetsState insetsState,
            InsetsSourceControl[] activeControls) throws RemoteException {
    }

    @Override
    public void moved(int newX, int newY) {
    }

    @Override
    public void dispatchAppVisibility(boolean visible) {
    }

    @Override
    public void dispatchGetNewSurface() {
    }

    @Override
    public void windowFocusChanged(boolean hasFocus, boolean touchEnabled) {
    }

    @Override
    public void executeCommand(String command, String parameters, ParcelFileDescriptor out) {
    }

    @Override
    public void closeSystemDialogs(String reason) {
    }

    @Override
    public void dispatchWallpaperOffsets(float x, float y, float xStep, float yStep, boolean sync) {
        if (sync) {
            try {
                mSession.wallpaperOffsetsComplete(asBinder());
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public void dispatchDragEvent(DragEvent event) {
        if (event.getAction() == DragEvent.ACTION_DROP) {
            try {
                mSession.reportDropResult(this, false);
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public void updatePointerIcon(float x, float y) {
        InputManager.getInstance().setPointerIconType(PointerIcon.TYPE_NOT_SPECIFIED);
    }

    @Override
    public void dispatchSystemUiVisibilityChanged(int seq, int globalUi,
            int localValue, int localChanges) {
        mSeq = seq;
    }

    @Override
    public void dispatchWallpaperCommand(String action, int x, int y,
            int z, Bundle extras, boolean sync) {
        if (sync) {
            try {
                mSession.wallpaperCommandComplete(asBinder(), null);
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public void dispatchWindowShown() {
    }

    @Override
    public void requestAppKeyboardShortcuts(IResultReceiver receiver, int deviceId) {
    }

    @Override
    public void dispatchPointerCaptureChanged(boolean hasCapture) {
    }
}
