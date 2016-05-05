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

import android.content.ClipData;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.IWindow;
import android.view.IWindowId;
import android.view.IWindowSession;
import android.view.InputChannel;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.WindowManager.LayoutParams;

/**
 * Implementation of {@link IWindowSession} so that mSession is not null in
 * the {@link SurfaceView}.
 */
public final class BridgeWindowSession implements IWindowSession {

    @Override
    public int add(IWindow arg0, int seq, LayoutParams arg1, int arg2, Rect arg3, Rect arg4,
            InputChannel outInputchannel)
            throws RemoteException {
        // pass for now.
        return 0;
    }

    @Override
    public int addToDisplay(IWindow arg0, int seq, LayoutParams arg1, int arg2, int displayId,
                            Rect arg3, Rect arg4, Rect arg5, InputChannel outInputchannel)
            throws RemoteException {
        // pass for now.
        return 0;
    }

    @Override
    public int addWithoutInputChannel(IWindow arg0, int seq, LayoutParams arg1, int arg2,
                                      Rect arg3, Rect arg4)
            throws RemoteException {
        // pass for now.
        return 0;
    }

    @Override
    public int addToDisplayWithoutInputChannel(IWindow arg0, int seq, LayoutParams arg1, int arg2,
                                               int displayId, Rect arg3, Rect arg4)
            throws RemoteException {
        // pass for now.
        return 0;
    }

    @Override
    public void finishDrawing(IWindow arg0) throws RemoteException {
        // pass for now.
    }

    @Override
    public boolean getInTouchMode() throws RemoteException {
        // pass for now.
        return false;
    }

    @Override
    public boolean performHapticFeedback(IWindow window, int effectId, boolean always) {
        // pass for now.
        return false;
    }

    @Override
    public int relayout(IWindow iWindow, int i, LayoutParams layoutParams, int i2,
            int i3, int i4, int i5, Rect rect, Rect rect2, Rect rect3, Rect rect4, Rect rect5,
            Rect rect6, Rect rect7, Configuration configuration, Surface surface)
            throws RemoteException {
        // pass for now.
        return 0;
    }

    @Override
    public void repositionChild(IWindow window, int left, int top, int right, int bottom,
            long deferTransactionUntilFrame, Rect outFrame) {
        // pass for now.
        return;
    }

    @Override
    public void performDeferredDestroy(IWindow window) {
        // pass for now.
    }

    @Override
    public boolean outOfMemory(IWindow window) throws RemoteException {
        return false;
    }

    @Override
    public void getDisplayFrame(IWindow window, Rect outDisplayFrame) {
        // pass for now.
    }

    @Override
    public void remove(IWindow arg0) throws RemoteException {
        // pass for now.
    }

    @Override
    public void setInTouchMode(boolean arg0) throws RemoteException {
        // pass for now.
    }

    @Override
    public void setTransparentRegion(IWindow arg0, Region arg1) throws RemoteException {
        // pass for now.
    }

    @Override
    public void setInsets(IWindow window, int touchable, Rect contentInsets,
            Rect visibleInsets, Region touchableRegion) {
        // pass for now.
    }

    @Override
    public IBinder prepareDrag(IWindow window, int flags,
            int thumbnailWidth, int thumbnailHeight, Surface outSurface)
            throws RemoteException {
        // pass for now
        return null;
    }

    @Override
    public boolean performDrag(IWindow window, IBinder dragToken,
            int touchSource, float touchX, float touchY, float thumbCenterX, float thumbCenterY,
            ClipData data)
            throws RemoteException {
        // pass for now
        return false;
    }

    @Override
    public boolean startMovingTask(IWindow window, float startX, float startY)
            throws RemoteException {
        // pass for now
        return false;
    }

    @Override
    public void reportDropResult(IWindow window, boolean consumed) throws RemoteException {
        // pass for now
    }

    @Override
    public void cancelDragAndDrop(IBinder dragToken) throws RemoteException {
        // pass for now
    }

    @Override
    public void dragRecipientEntered(IWindow window) throws RemoteException {
        // pass for now
    }

    @Override
    public void dragRecipientExited(IWindow window) throws RemoteException {
        // pass for now
    }

    @Override
    public void setWallpaperPosition(IBinder window, float x, float y,
        float xStep, float yStep) {
        // pass for now.
    }

    @Override
    public void wallpaperOffsetsComplete(IBinder window) {
        // pass for now.
    }

    @Override
    public void setWallpaperDisplayOffset(IBinder windowToken, int x, int y) {
        // pass for now.
    }

    @Override
    public Bundle sendWallpaperCommand(IBinder window, String action, int x, int y,
            int z, Bundle extras, boolean sync) {
        // pass for now.
        return null;
    }

    @Override
    public void wallpaperCommandComplete(IBinder window, Bundle result) {
        // pass for now.
    }

    @Override
    public IBinder asBinder() {
        // pass for now.
        return null;
    }

    @Override
    public void onRectangleOnScreenRequested(IBinder window, Rect rectangle) {
        // pass for now.
    }

    @Override
    public IWindowId getWindowId(IBinder window) throws RemoteException {
        // pass for now.
        return null;
    }

    @Override
    public void pokeDrawLock(IBinder window) {
        // pass for now.
    }

    @Override
    public void prepareToReplaceWindows(IBinder appToken, boolean childrenOnly) {
        // pass for now.
    }

    @Override
    public void updatePointerIcon(IWindow window) {
        // pass for now.
    }
}
