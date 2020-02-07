/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.app;

import android.annotation.NonNull;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerImpl;

/**
 * {@link WindowContext} is a context for non-activity windows such as
 * {@link android.view.WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY} windows or system
 * windows. Its resources and configuration are adjusted to the area of the display that will be
 * used when a new window is added via {@link android.view.WindowManager.addView}.
 *
 * @see Context#createWindowContext(int, Bundle)
 * @hide
 */
// TODO(b/128338354): Handle config/display changes from server side.
public class WindowContext extends ContextWrapper {
    private final WindowManagerImpl mWindowManager;
    private final IWindowManager mWms;
    private final IBinder mToken;
    private final int mDisplayId;
    private boolean mOwnsToken;

    /**
     * Default constructor. Can either accept an existing token or generate one and registers it
     * with the server if necessary.
     *
     * @param base Base {@link Context} for this new instance.
     * @param token A valid {@link com.android.server.wm.WindowToken}. Pass {@code null} to generate
     *              one.
     * @param type Window type to be used with this context.
     * @hide
     */
    public WindowContext(Context base, IBinder token, int type, Bundle options) {
        super(null /* base */);

        mWms = WindowManagerGlobal.getWindowManagerService();
        if (token != null && !isWindowToken(token)) {
            throw new IllegalArgumentException("Token must be registered to server.");
        }
        mToken = token != null ? token : new Binder();

        final ContextImpl contextImpl = createBaseWindowContext(base, mToken);
        attachBaseContext(contextImpl);
        contextImpl.setOuterContext(this);

        mDisplayId = getDisplayId();
        mWindowManager = new WindowManagerImpl(this);
        mWindowManager.setDefaultToken(mToken);

        // TODO(b/128338354): Obtain the correct config from WM and adjust resources.
        if (token != null) {
            mOwnsToken = false;
            return;
        }
        try {
            mWms.addWindowTokenWithOptions(mToken, type, mDisplayId, options, getPackageName());
            // TODO(window-context): remove token with a DeathObserver
        }  catch (RemoteException e) {
            mOwnsToken = false;
            throw e.rethrowFromSystemServer();
        }
        mOwnsToken = true;
    }

    /** Check if the passed window token is registered with the server. */
    private boolean isWindowToken(@NonNull IBinder token) {
        try {
            return mWms.isWindowToken(token);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return false;
    }

    private static ContextImpl createBaseWindowContext(Context outer, IBinder token) {
        final ContextImpl contextImpl = ContextImpl.getImpl(outer);
        return contextImpl.createBaseWindowContext(token);
    }

    @Override
    public Object getSystemService(String name) {
        if (WINDOW_SERVICE.equals(name)) {
            return mWindowManager;
        }
        return super.getSystemService(name);
    }

    @Override
    protected void finalize() throws Throwable {
        if (mOwnsToken) {
            try {
                mWms.removeWindowToken(mToken, mDisplayId);
                mOwnsToken = false;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        super.finalize();
    }
}
