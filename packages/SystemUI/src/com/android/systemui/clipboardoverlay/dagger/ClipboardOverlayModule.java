/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.clipboardoverlay.dagger;

import static android.view.WindowManager.LayoutParams.TYPE_SCREENSHOT;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.LayoutInflater;

import com.android.systemui.clipboardoverlay.ClipboardOverlayView;
import com.android.systemui.res.R;
import com.android.systemui.settings.DisplayTracker;

import dagger.Module;
import dagger.Provides;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;

import javax.inject.Qualifier;

/** Module for {@link com.android.systemui.clipboardoverlay}. */
@Module
public interface ClipboardOverlayModule {

    /**
     *
     */
    @Provides
    @OverlayWindowContext
    static Context provideWindowContext(DisplayManager displayManager,
            DisplayTracker displayTracker, Context context) {
        Display display = displayManager.getDisplay(displayTracker.getDefaultDisplayId());
        return context.createWindowContext(display, TYPE_SCREENSHOT, null);
    }

    /**
     *
     */
    @Provides
    static ClipboardOverlayView provideClipboardOverlayView(@OverlayWindowContext Context context) {
        return (ClipboardOverlayView) LayoutInflater.from(context).inflate(
                R.layout.clipboard_overlay, null);
    }

    @Qualifier
    @Documented
    @Retention(RUNTIME)
    @interface OverlayWindowContext {
    }
}
