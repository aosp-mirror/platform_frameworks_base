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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.IBinder;
import android.view.WindowManager.LayoutParams.WindowType;

/**
 * An interface to provide a non-activity window.
 * Examples are {@link WindowContext} and {@link WindowProviderService}.
 *
 * @hide
 */
public interface WindowProvider {
    /** @hide */
    String KEY_IS_WINDOW_PROVIDER_SERVICE = "android.windowContext.isWindowProviderService";

    /** Gets the window type of this provider */
    @WindowType
    int getWindowType();

    /** Gets the launch options of this provider */
    @Nullable
    Bundle getWindowContextOptions();

    /**
     * Gets the WindowContextToken of this provider.
     * @see android.content.Context#getWindowContextToken
     */
    @NonNull
    IBinder getWindowContextToken();
}
