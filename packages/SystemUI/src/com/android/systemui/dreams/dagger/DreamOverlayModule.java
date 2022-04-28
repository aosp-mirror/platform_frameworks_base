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

package com.android.systemui.dreams.dagger;

import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.android.internal.util.Preconditions;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.DreamOverlayContainerView;
import com.android.systemui.dreams.DreamOverlayStatusBarView;
import com.android.systemui.touch.TouchInsetManager;

import java.util.concurrent.Executor;

import javax.inject.Named;

import dagger.Lazy;
import dagger.Module;
import dagger.Provides;

/** Dagger module for {@link DreamOverlayComponent}. */
@Module
public abstract class DreamOverlayModule {
    public static final String DREAM_OVERLAY_CONTENT_VIEW = "dream_overlay_content_view";
    public static final String MAX_BURN_IN_OFFSET = "max_burn_in_offset";
    public static final String BURN_IN_PROTECTION_UPDATE_INTERVAL =
            "burn_in_protection_update_interval";
    public static final String MILLIS_UNTIL_FULL_JITTER = "millis_until_full_jitter";

    /** */
    @Provides
    @DreamOverlayComponent.DreamOverlayScope
    public static DreamOverlayContainerView providesDreamOverlayContainerView(
            LayoutInflater layoutInflater) {
        return Preconditions.checkNotNull((DreamOverlayContainerView)
                layoutInflater.inflate(R.layout.dream_overlay_container, null),
                "R.layout.dream_layout_container could not be properly inflated");
    }

    /** */
    @Provides
    @DreamOverlayComponent.DreamOverlayScope
    @Named(DREAM_OVERLAY_CONTENT_VIEW)
    public static ViewGroup providesDreamOverlayContentView(DreamOverlayContainerView view) {
        return Preconditions.checkNotNull(view.findViewById(R.id.dream_overlay_content),
                "R.id.dream_overlay_content must not be null");
    }

    /** */
    @Provides
    public static TouchInsetManager.TouchInsetSession providesTouchInsetSession(
            TouchInsetManager manager) {
        return manager.createSession();
    }

    /** */
    @Provides
    @DreamOverlayComponent.DreamOverlayScope
    public static TouchInsetManager providesTouchInsetManager(@Main Executor executor,
            DreamOverlayContainerView view) {
        return new TouchInsetManager(executor, view);
    }

    /** */
    @Provides
    @DreamOverlayComponent.DreamOverlayScope
    public static DreamOverlayStatusBarView providesDreamOverlayStatusBarView(
            DreamOverlayContainerView view) {
        return Preconditions.checkNotNull(view.findViewById(R.id.dream_overlay_status_bar),
                "R.id.status_bar must not be null");
    }

    /** */
    @Provides
    @DreamOverlayComponent.DreamOverlayScope
    @Named(MAX_BURN_IN_OFFSET)
    static int providesMaxBurnInOffset(@Main Resources resources) {
        return resources.getDimensionPixelSize(R.dimen.default_burn_in_prevention_offset);
    }

    /** */
    @Provides
    @Named(BURN_IN_PROTECTION_UPDATE_INTERVAL)
    static long providesBurnInProtectionUpdateInterval(@Main Resources resources) {
        return resources.getInteger(
                R.integer.config_dreamOverlayBurnInProtectionUpdateIntervalMillis);
    }

    /** */
    @Provides
    @Named(MILLIS_UNTIL_FULL_JITTER)
    static long providesMillisUntilFullJitter(@Main Resources resources) {
        return resources.getInteger(R.integer.config_dreamOverlayMillisUntilFullJitter);
    }

    @Provides
    @DreamOverlayComponent.DreamOverlayScope
    static LifecycleOwner providesLifecycleOwner(Lazy<LifecycleRegistry> lifecycleRegistryLazy) {
        return () -> lifecycleRegistryLazy.get();
    }

    @Provides
    @DreamOverlayComponent.DreamOverlayScope
    static LifecycleRegistry providesLifecycleRegistry(LifecycleOwner lifecycleOwner) {
        return new LifecycleRegistry(lifecycleOwner);
    }

    @Provides
    @DreamOverlayComponent.DreamOverlayScope
    static Lifecycle providesLifecycle(LifecycleOwner lifecycleOwner) {
        return lifecycleOwner.getLifecycle();
    }
}
