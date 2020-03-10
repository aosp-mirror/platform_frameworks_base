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

import static android.view.WindowManagerGlobal.ADD_OKAY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerImpl;

import java.lang.ref.Reference;

/**
 * {@link WindowContext} is a context for non-activity windows such as
 * {@link android.view.WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY} windows or system
 * windows. Its resources and configuration are adjusted to the area of the display that will be
 * used when a new window is added via {@link android.view.WindowManager#addView}.
 *
 * @see Context#createWindowContext(int, Bundle)
 * @hide
 */
public class WindowContext extends ContextWrapper {
    private final WindowManagerImpl mWindowManager;
    private final IWindowManager mWms;
    private final WindowTokenClient mToken;
    private boolean mOwnsToken;

    /**
     * Default constructor. Will generate a {@link WindowTokenClient} and attach this context to
     * the token.
     *
     * @param base Base {@link Context} for this new instance.
     * @param type Window type to be used with this context.
     * @hide
     */
    public WindowContext(@NonNull Context base, int type, @Nullable Bundle options) {
        // Correct base context will be built once the token is resolved, so passing 'null' here.
        super(null /* base */);

        mWms = WindowManagerGlobal.getWindowManagerService();
        mToken = new WindowTokenClient();

        final ContextImpl contextImpl = createBaseWindowContext(base, mToken);
        attachBaseContext(contextImpl);
        contextImpl.setOuterContext(this);

        mToken.attachContext(this);

        mWindowManager = new WindowManagerImpl(this);
        mWindowManager.setDefaultToken(mToken);

        int result;
        try {
            // Register the token with WindowManager. This will also call back with the current
            // config back to the client.
            result = mWms.addWindowTokenWithOptions(
                    mToken, type, getDisplayId(), options, getPackageName());

            // TODO(window-context): remove token with a DeathObserver
        }  catch (RemoteException e) {
            mOwnsToken = false;
            throw e.rethrowFromSystemServer();
        }
        mOwnsToken = result == ADD_OKAY;
        Reference.reachabilityFence(this);
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
                mWms.removeWindowToken(mToken, getDisplayId());
                mOwnsToken = false;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        super.finalize();
    }
}
