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

import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.MergedConfiguration;
import android.view.DragEvent;
import android.view.IScrollCaptureResponseListener;
import android.view.IWindow;
import android.view.IWindowSession;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.ScrollCaptureResponse;
import android.view.WindowInsets.Type.InsetsType;
import android.view.inputmethod.ImeTracker;
import android.window.ActivityWindowInfo;
import android.window.ClientWindowFrames;

import com.android.internal.os.IResultReceiver;

import java.io.IOException;

public class BaseIWindow extends IWindow.Stub {

    @UnsupportedAppUsage(maxTargetSdk = android.os.Build.VERSION_CODES.P)
    public BaseIWindow() {}

    private IWindowSession mSession;

    public void setSession(IWindowSession session) {
        mSession = session;
    }

    @Override
    public void resized(ClientWindowFrames frames, boolean reportDraw,
            MergedConfiguration mergedConfiguration, InsetsState insetsState, boolean forceLayout,
            boolean alwaysConsumeSystemBars, int displayId, int seqId, boolean dragResizing,
            @Nullable ActivityWindowInfo activityWindowInfo) {
        if (reportDraw) {
            try {
                mSession.finishDrawing(this, null /* postDrawTransaction */, seqId);
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public void insetsControlChanged(InsetsState insetsState,
            InsetsSourceControl[] activeControls) {
    }

    @Override
    public void showInsets(@InsetsType int types, boolean fromIme,
            @Nullable ImeTracker.Token statsToken) {
    }

    @Override
    public void hideInsets(@InsetsType int types, boolean fromIme,
            @Nullable ImeTracker.Token statsToken) {
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
    public void executeCommand(String command, String parameters, ParcelFileDescriptor out) {
        if (out != null) {
            try {
                out.closeWithError("Unsupported command " + command);
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    @Override
    public void closeSystemDialogs(String reason) {
    }

    @Override
    public void dispatchWallpaperOffsets(float x, float y, float xStep, float yStep, float zoom,
            boolean sync) {
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
    public void requestScrollCapture(IScrollCaptureResponseListener listener) {
        try {
            listener.onScrollCaptureResponse(
                    new ScrollCaptureResponse.Builder().setDescription("Not Implemented").build());

        } catch (RemoteException ex) {
            // ignore
        }
    }

    @Override
    public void dumpWindow(ParcelFileDescriptor pfd) {

    }
}
