package com.android.internal.view;

import android.view.InputConsumer;
import android.view.SurfaceHolder;

/** hahahah */
public interface RootViewSurfaceTaker {
    SurfaceHolder.Callback willYouTakeTheSurface();
    void setSurfaceType(int type);
    void setSurfaceFormat(int format);
    void setSurfaceKeepScreenOn(boolean keepOn);
    InputConsumer.Callback willYouTakeTheInputConsumer();
}
