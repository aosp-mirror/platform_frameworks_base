/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.view.Display.DEFAULT_DISPLAY;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.annotation.UiContext;
import android.app.ActivityThread;
import android.app.LoadedApk;
import android.app.Service;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Display;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams.WindowType;
import android.view.WindowManagerImpl;

// TODO(b/159767464): handle #onConfigurationChanged(Configuration)
/**
 * A {@link Service} responsible for showing a non-activity window, such as software keyboards or
 * accessibility overlay windows. This {@link Service} has similar behavior to
 * {@link WindowContext}, but is represented as {@link Service}.
 *
 * @see android.inputmethodservice.InputMethodService
 * @see android.accessibilityservice.AccessibilityService
 *
 * @hide
 */
@TestApi
@UiContext
public abstract class WindowProviderService extends Service {

    private final WindowTokenClient mWindowToken = new WindowTokenClient();
    private final WindowContextController mController = new WindowContextController(mWindowToken);
    private WindowManager mWindowManager;

    /**
     * Returns the type of this {@link WindowProviderService}.
     * Each inheriting class must implement this method to provide the type of the window. It is
     * used similar to {@code type} of {@link Context#createWindowContext(int, Bundle)}
     *
     * @see Context#createWindowContext(int, Bundle)
     *
     * @hide
     */
    @TestApi
    @SuppressLint("OnNameExpected")
    // Suppress the lint because it is not a callback and users should provide window type
    // so we cannot make it final.
    public abstract @WindowType int getWindowType();

    /**
     * Returns the option of this {@link WindowProviderService}.
     * Default is {@code null}. The inheriting class can implement this method to provide the
     * customization {@code option} of the window. It is used similar to {@code options} of
     * {@link Context#createWindowContext(int, Bundle)}
     *
     * @see Context#createWindowContext(int, Bundle)
     *
     * @hide
     */
    @TestApi
    @SuppressLint({"OnNameExpected", "NullableCollection"})
    // Suppress the lint because it is not a callback and users may override this API to provide
    // launch option. Also, the return value of this API is null by default.
    @Nullable
    public Bundle getWindowContextOptions() {
        return null;
    }

    /**
     * Attaches this WindowProviderService to the {@code windowToken}.
     *
     * @hide
     */
    @TestApi
    public final void attachToWindowToken(@NonNull IBinder windowToken) {
        mController.attachToWindowToken(windowToken);
    }

    /** @hide */
    @Override
    public final Context createServiceBaseContext(ActivityThread mainThread,
            LoadedApk packageInfo) {
        final Context context = super.createServiceBaseContext(mainThread, packageInfo);
        // Always associate with the default display at initialization.
        final Display defaultDisplay = context.getSystemService(DisplayManager.class)
                .getDisplay(DEFAULT_DISPLAY);
        return context.createTokenContext(mWindowToken, defaultDisplay);
    }

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mWindowToken.attachContext(this);
        mController.attachToDisplayArea(getWindowType(), getDisplayId(), getWindowContextOptions());
        mWindowManager = WindowManagerImpl.createWindowContextWindowManager(this);
    }

    @SuppressLint("OnNameExpected")
    @Override
    // Suppress the lint because ths is overridden from Context.
    public @Nullable Object getSystemService(@NonNull String name) {
        if (WINDOW_SERVICE.equals(name)) {
            return mWindowManager;
        }
        return super.getSystemService(name);
    }

    @CallSuper
    @Override
    public void onDestroy() {
        super.onDestroy();
        mController.detachIfNeeded();
    }
}
