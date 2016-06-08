package com.android.hotspot2.app;

import android.os.Binder;

public class LocalServiceBinder extends Binder {
    private final OSUService mDelegate;

    public LocalServiceBinder(OSUService delegate) {
        mDelegate = delegate;
    }

    public OSUService getService() {
        return mDelegate;
    }
}
