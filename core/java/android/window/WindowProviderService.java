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

/**
 * A {@link Service} responsible for showing a non-activity window, such as software keyboards or
 * accessibility overlay windows. This {@link Service} has similar behavior to
 * {@link WindowContext}, but is represented as {@link Service}.
 *
 * @see android.inputmethodservice.InputMethodService
 *
 * @hide
 */
@TestApi
@UiContext
public abstract class WindowProviderService extends Service implements WindowProvider {

    private final Bundle mOptions;
    private final WindowTokenClient mWindowToken = new WindowTokenClient();
    private final WindowContextController mController = new WindowContextController(mWindowToken);
    private WindowManager mWindowManager;
    private boolean mInitialized;

    public WindowProviderService() {
        mOptions = new Bundle();
        mOptions.putBoolean(KEY_IS_WINDOW_PROVIDER_SERVICE, true);
    }

    /**
     * Returns the window type of this {@link WindowProviderService}.
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
    @WindowType
    @Override
    public abstract int getWindowType();

    /**
     * Returns the option of this {@link WindowProviderService}.
     * <p>
     * The inheriting class can implement this method to provide the customization {@code option} of
     * the window, but must be based on this method's returned value.
     * It is used similar to {@code options} of {@link Context#createWindowContext(int, Bundle)}
     * </p>
     * <pre class="prettyprint">
     * public Bundle getWindowContextOptions() {
     *     final Bundle options = super.getWindowContextOptions();
     *     options.put(KEY_ROOT_DISPLAY_AREA_ID, displayAreaInfo.rootDisplayAreaId);
     *     return options;
     * }
     * </pre>
     *
     * @hide
     */
    @TestApi
    @SuppressLint({"OnNameExpected", "NullableCollection"})
    // Suppress the lint because it is not a callback and users may override this API to provide
    // launch option. Also, the return value of this API is null by default.
    @Nullable
    @CallSuper
    @Override
    public Bundle getWindowContextOptions() {
        return mOptions;
    }

    /**
     * Returns the display ID to launch this {@link WindowProviderService}.
     *
     * @hide
     */
    @TestApi
    @SuppressLint({"OnNameExpected"})
    // Suppress the lint because it is not a callback and users may override this API to provide
    // display.
    @NonNull
    public int getInitialDisplayId() {
        return DEFAULT_DISPLAY;
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
        final Display display = context.getSystemService(DisplayManager.class)
                .getDisplay(getInitialDisplayId());
        return context.createTokenContext(mWindowToken, display);
    }

    /** @hide */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
        if (!mInitialized) {
            mWindowToken.attachContext(this);
            mController.attachToDisplayArea(getWindowType(), getDisplayId(),
                    getWindowContextOptions());
            mWindowManager = WindowManagerImpl.createWindowContextWindowManager(this);
            mInitialized = true;
        }
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
