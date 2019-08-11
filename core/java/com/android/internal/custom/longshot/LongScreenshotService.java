package com.android.internal.custom.longshot;

import android.content.Context;

public abstract class LongScreenshotService extends ILongScreenshot.Stub {
    private static final String TAG = "Longshot.Service";
    protected Context mContext = null;
    protected boolean mNavBarVisible = false;
    protected boolean mStatusBarVisible = false;

    public LongScreenshotService(Context context, boolean statusBarVisible, boolean navBarVisible) {
        mContext = context;
        mStatusBarVisible = statusBarVisible;
        mNavBarVisible = navBarVisible;
    }

    @Override
    public void start(ILongScreenshotCallback callback) {
    }

    @Override
    public void notifyScroll(boolean isOverScroll) {
    }

    @Override
    public boolean isMoveState() {
        return false;
    }

    @Override
    public boolean isHandleState() {
        return false;
    }

    @Override
    public void stopLongshot() {
    }
}
