package com.android.internal.view;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.view.IWindow;
import android.view.IWindowSession;
import android.view.KeyEvent;
import android.view.MotionEvent;

public class BaseIWindow extends IWindow.Stub {
    private IWindowSession mSession;
    
    public void setSession(IWindowSession session) {
        mSession = session;
    }
    
    public void resized(int w, int h, Rect coveredInsets,
            Rect visibleInsets, boolean reportDraw) {
        if (reportDraw) {
            try {
                mSession.finishDrawing(this);
            } catch (RemoteException e) {
            }
        }
    }

    public void dispatchKey(KeyEvent event) {
        try {
            mSession.finishKey(this);
        } catch (RemoteException ex) {
        }
    }

    public boolean onDispatchPointer(MotionEvent event, long eventTime,
            boolean callWhenDone) {
        event.recycle();
        return false;
    }
    
    public void dispatchPointer(MotionEvent event, long eventTime,
            boolean callWhenDone) {
        try {
            if (event == null) {
                event = mSession.getPendingPointerMove(this);
                onDispatchPointer(event, eventTime, false);
            } else if (callWhenDone) {
                if (!onDispatchPointer(event, eventTime, true)) {
                    mSession.finishKey(this);
                }
            } else {
                onDispatchPointer(event, eventTime, false);
            }
        } catch (RemoteException ex) {
        }
    }

    public boolean onDispatchTrackball(MotionEvent event, long eventTime,
            boolean callWhenDone) {
        event.recycle();
        return false;
    }
    
    public void dispatchTrackball(MotionEvent event, long eventTime,
            boolean callWhenDone) {
        try {
            if (event == null) {
                event = mSession.getPendingTrackballMove(this);
                onDispatchTrackball(event, eventTime, false);
            } else if (callWhenDone) {
                if (!onDispatchTrackball(event, eventTime, true)) {
                    mSession.finishKey(this);
                }
            } else {
                onDispatchTrackball(event, eventTime, false);
            }
        } catch (RemoteException ex) {
        }
    }

    public void dispatchAppVisibility(boolean visible) {
    }

    public void dispatchGetNewSurface() {
    }

    public void windowFocusChanged(boolean hasFocus, boolean touchEnabled) {
    }

    public void executeCommand(String command, String parameters, ParcelFileDescriptor out) {
    }
    
    public void closeSystemDialogs(String reason) {
    }
    
    public void dispatchWallpaperOffsets(float x, float y, float xStep, float yStep, boolean sync) {
        if (sync) {
            try {
                mSession.wallpaperOffsetsComplete(asBinder());
            } catch (RemoteException e) {
            }
        }
    }
    
    public void dispatchWallpaperCommand(String action, int x, int y,
            int z, Bundle extras, boolean sync) {
        if (sync) {
            try {
                mSession.wallpaperCommandComplete(asBinder(), null);
            } catch (RemoteException e) {
            }
        }
    }
}
