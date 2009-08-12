package com.android.internal.view;

import android.graphics.Rect;
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

    public void dispatchPointer(MotionEvent event, long eventTime) {
        try {
            if (event == null) {
                event = mSession.getPendingPointerMove(this);
            } else if (event.getAction() != MotionEvent.ACTION_OUTSIDE) {
                mSession.finishKey(this);
            }
        } catch (RemoteException ex) {
        }
    }

    public void dispatchTrackball(MotionEvent event, long eventTime) {
        try {
            if (event == null) {
                event = mSession.getPendingTrackballMove(this);
            } else if (event.getAction() != MotionEvent.ACTION_OUTSIDE) {
                mSession.finishKey(this);
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
    
    public void dispatchWallpaperOffsets(float x, float y) {
    }
}
