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
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.view.WindowManagerGlobal;

import java.lang.ref.WeakReference;

/**
 * Client implementation of {@link IWindowToken}. It can receive configuration change callbacks from
 * server when window token config is updated or when it is moved between displays, and update the
 * resources associated with this token on the client side. This will make sure that
 * {@link WindowContext} instances will have updated resources and configuration.
 * @hide
 */
public class WindowTokenClient extends IWindowToken.Stub {
    /**
     * Attached {@link Context} for this window token to update configuration and resources.
     * Initialized by {@link #attachContext(WindowContext)}.
     */
    private WeakReference<WindowContext> mContextRef = null;

    private final ResourcesManager mResourcesManager = ResourcesManager.getInstance();

    /**
     * Attaches {@code context} to this {@link WindowTokenClient}. Each {@link WindowTokenClient}
     * can only attach one {@link Context}.
     * <p>This method must be called before invoking
     * {@link android.view.IWindowManager#addWindowTokenWithOptions(IBinder, int, int, Bundle,
     * String)}.<p/>
     *
     * @param context context to be attached
     * @throws IllegalStateException if attached context has already existed.
     */
    void attachContext(@NonNull WindowContext context) {
        if (mContextRef != null) {
            throw new IllegalStateException("Context is already attached.");
        }
        mContextRef = new WeakReference<>(context);
        final ContextImpl impl = ContextImpl.getImpl(context);
        impl.setResources(impl.createWindowContextResources());
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig, int newDisplayId) {
        final Context context = mContextRef.get();
        if (context == null) {
            return;
        }
        final int currentDisplayId = context.getDisplayId();
        final boolean displayChanged = newDisplayId != currentDisplayId;
        final Configuration config = context.getResources().getConfiguration();
        final boolean configChanged = config.diff(newConfig) != 0;
        if (displayChanged || configChanged) {
            // TODO(ag/9789103): update resource manager logic to track non-activity tokens
            mResourcesManager.updateResourcesForActivity(this, newConfig, newDisplayId,
                    displayChanged);
        }
        if (displayChanged) {
            context.updateDisplay(newDisplayId);
        }
    }

    @Override
    public void onWindowTokenRemoved() {
        final WindowContext context = mContextRef.get();
        if (context != null) {
            context.destroy();
            mContextRef.clear();
        }
        // If a secondary display is detached, release all views attached to this token.
        WindowManagerGlobal.getInstance().closeAll(this, mContextRef.getClass().getName(),
                "WindowContext");
    }
}
