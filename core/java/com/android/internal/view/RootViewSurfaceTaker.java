package com.android.internal.view;

import android.view.InputQueue;
import android.view.SurfaceHolder;

/** hahahah */
public interface RootViewSurfaceTaker {
    SurfaceHolder.Callback2 willYouTakeTheSurface();
    void setSurfaceType(int type);
    void setSurfaceFormat(int format);
    void setSurfaceKeepScreenOn(boolean keepOn);
    InputQueue.Callback willYouTakeTheInputQueue();
}
