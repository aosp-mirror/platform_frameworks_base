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
package android.window;

import static android.view.WindowManagerImpl.createWindowContextWindowManager;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiContext;
import android.content.ComponentCallbacks;
import android.content.ComponentCallbacksController;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;

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
@UiContext
public class WindowContext extends ContextWrapper {
    private final WindowManager mWindowManager;
    private final @WindowManager.LayoutParams.WindowType int mType;
    private final @Nullable Bundle mOptions;
    private final ComponentCallbacksController mCallbacksController =
            new ComponentCallbacksController();
    private final WindowContextController mController;

    /**
     * Default constructor. Will generate a {@link WindowTokenClient} and attach this context to
     * the token.
     *
     * @param base Base {@link Context} for this new instance.
     * @param type Window type to be used with this context.
     * @param options A bundle used to pass window-related options.
     *
     * @hide
     */
    public WindowContext(@NonNull Context base, int type, @Nullable Bundle options) {
        super(base);

        mType = type;
        mOptions = options;
        mWindowManager = createWindowContextWindowManager(this);
        IBinder token = getWindowContextToken();
        mController = new WindowContextController(token);

        Reference.reachabilityFence(this);
    }

    /**
     * Attaches this {@link WindowContext} to the {@link com.android.server.wm.DisplayArea}
     * specified by {@code mType}, {@link #getDisplayId() display ID} and {@code mOptions}
     * to receive configuration changes.
     */
    public void attachToDisplayArea() {
        mController.attachToDisplayArea(mType, getDisplayId(), mOptions);
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
        release();
        super.finalize();
    }

    /** Used for test to invoke because we can't invoke finalize directly. */
    @VisibleForTesting
    public void release() {
        mController.detachIfNeeded();
        destroy();
    }

    @Override
    public void destroy() {
        mCallbacksController.clearCallbacks();
        // Called to the base ContextImpl to do final clean-up.
        getBaseContext().destroy();
        Reference.reachabilityFence(this);
    }

    @Override
    public void registerComponentCallbacks(@NonNull ComponentCallbacks callback) {
        mCallbacksController.registerCallbacks(callback);
    }

    @Override
    public void unregisterComponentCallbacks(@NonNull ComponentCallbacks callback) {
        mCallbacksController.unregisterCallbacks(callback);
    }

    /** Dispatch {@link Configuration} to each {@link ComponentCallbacks}. */
    void dispatchConfigurationChanged(@NonNull Configuration newConfig) {
        mCallbacksController.dispatchConfigurationChanged(newConfig);
    }
}
