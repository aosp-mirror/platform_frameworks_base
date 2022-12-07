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

import android.annotation.Nullable;
import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.android.internal.util.Preconditions;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.DreamOverlayContainerView;
import com.android.systemui.dreams.DreamOverlayStatusBarView;
import com.android.systemui.dreams.complication.Complication;
import com.android.systemui.dreams.dreamcomplication.HideComplicationTouchHandler;
import com.android.systemui.dreams.dreamcomplication.dagger.ComplicationComponent;
import com.android.systemui.dreams.touch.DreamTouchHandler;
import com.android.systemui.touch.TouchInsetManager;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.IntoSet;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Named;

/** Dagger module for {@link DreamOverlayComponent}. */
@Module
public abstract class DreamOverlayModule {
    public static final String DREAM_TOUCH_HANDLERS = "dream_touch_handlers";
    public static final String DREAM_OVERLAY_CONTENT_VIEW = "dream_overlay_content_view";
    public static final String MAX_BURN_IN_OFFSET = "max_burn_in_offset";
    public static final String BURN_IN_PROTECTION_UPDATE_INTERVAL =
            "burn_in_protection_update_interval";
    public static final String MILLIS_UNTIL_FULL_JITTER = "millis_until_full_jitter";
    public static final String DREAM_IN_BLUR_ANIMATION_DURATION = "dream_in_blur_anim_duration";
    public static final String DREAM_IN_BLUR_ANIMATION_DELAY = "dream_in_blur_anim_delay";
    public static final String DREAM_IN_COMPLICATIONS_ANIMATION_DURATION =
            "dream_in_complications_anim_duration";
    public static final String DREAM_IN_TOP_COMPLICATIONS_ANIMATION_DELAY =
            "dream_in_top_complications_anim_delay";
    public static final String DREAM_IN_BOTTOM_COMPLICATIONS_ANIMATION_DELAY =
            "dream_in_bottom_complications_anim_delay";
    public static final String DREAM_OUT_TRANSLATION_Y_DISTANCE =
            "dream_out_complications_translation_y";
    public static final String DREAM_OUT_TRANSLATION_Y_DURATION =
            "dream_out_complications_translation_y_duration";
    public static final String DREAM_OUT_TRANSLATION_Y_DELAY_BOTTOM =
            "dream_out_complications_translation_y_delay_bottom";
    public static final String DREAM_OUT_TRANSLATION_Y_DELAY_TOP =
            "dream_out_complications_translation_y_delay_top";
    public static final String DREAM_OUT_ALPHA_DURATION =
            "dream_out_complications_alpha_duration";
    public static final String DREAM_OUT_ALPHA_DELAY_BOTTOM =
            "dream_out_complications_alpha_delay_bottom";
    public static final String DREAM_OUT_ALPHA_DELAY_TOP =
            "dream_out_complications_alpha_delay_top";
    public static final String DREAM_OUT_BLUR_DURATION =
            "dream_out_blur_duration";

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
    public static TouchInsetManager providesTouchInsetManager(@Main Executor executor) {
        return new TouchInsetManager(executor);
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

    /**
     * Duration in milliseconds of the dream in un-blur animation.
     */
    @Provides
    @Named(DREAM_IN_BLUR_ANIMATION_DURATION)
    static long providesDreamInBlurAnimationDuration(@Main Resources resources) {
        return (long) resources.getInteger(R.integer.config_dreamOverlayInBlurDurationMs);
    }

    /**
     * Delay in milliseconds of the dream in un-blur animation.
     */
    @Provides
    @Named(DREAM_IN_BLUR_ANIMATION_DELAY)
    static long providesDreamInBlurAnimationDelay(@Main Resources resources) {
        return (long) resources.getInteger(R.integer.config_dreamOverlayInBlurDelayMs);
    }

    /**
     * Duration in milliseconds of the dream in complications fade-in animation.
     */
    @Provides
    @Named(DREAM_IN_COMPLICATIONS_ANIMATION_DURATION)
    static long providesDreamInComplicationsAnimationDuration(@Main Resources resources) {
        return (long) resources.getInteger(R.integer.config_dreamOverlayInComplicationsDurationMs);
    }

    /**
     * Delay in milliseconds of the dream in top complications fade-in animation.
     */
    @Provides
    @Named(DREAM_IN_TOP_COMPLICATIONS_ANIMATION_DELAY)
    static long providesDreamInTopComplicationsAnimationDelay(@Main Resources resources) {
        return (long) resources.getInteger(R.integer.config_dreamOverlayInTopComplicationsDelayMs);
    }

    /**
     * Delay in milliseconds of the dream in bottom complications fade-in animation.
     */
    @Provides
    @Named(DREAM_IN_BOTTOM_COMPLICATIONS_ANIMATION_DELAY)
    static long providesDreamInBottomComplicationsAnimationDelay(@Main Resources resources) {
        return (long) resources.getInteger(
                R.integer.config_dreamOverlayInBottomComplicationsDelayMs);
    }

    /**
     * Provides the number of pixels to translate complications when waking up from dream.
     */
    @Provides
    @Named(DREAM_OUT_TRANSLATION_Y_DISTANCE)
    @DreamOverlayComponent.DreamOverlayScope
    static int providesDreamOutComplicationsTranslationY(@Main Resources resources) {
        return resources.getDimensionPixelSize(R.dimen.dream_overlay_exit_y_offset);
    }

    @Provides
    @Named(DREAM_OUT_TRANSLATION_Y_DURATION)
    @DreamOverlayComponent.DreamOverlayScope
    static long providesDreamOutComplicationsTranslationYDuration(@Main Resources resources) {
        return (long) resources.getInteger(R.integer.config_dreamOverlayOutTranslationYDurationMs);
    }

    @Provides
    @Named(DREAM_OUT_TRANSLATION_Y_DELAY_BOTTOM)
    @DreamOverlayComponent.DreamOverlayScope
    static long providesDreamOutComplicationsTranslationYDelayBottom(@Main Resources resources) {
        return (long) resources.getInteger(
                R.integer.config_dreamOverlayOutTranslationYDelayBottomMs);
    }

    @Provides
    @Named(DREAM_OUT_TRANSLATION_Y_DELAY_TOP)
    @DreamOverlayComponent.DreamOverlayScope
    static long providesDreamOutComplicationsTranslationYDelayTop(@Main Resources resources) {
        return (long) resources.getInteger(R.integer.config_dreamOverlayOutTranslationYDelayTopMs);
    }

    @Provides
    @Named(DREAM_OUT_ALPHA_DURATION)
    @DreamOverlayComponent.DreamOverlayScope
    static long providesDreamOutComplicationsAlphaDuration(@Main Resources resources) {
        return (long) resources.getInteger(R.integer.config_dreamOverlayOutAlphaDurationMs);
    }

    @Provides
    @Named(DREAM_OUT_ALPHA_DELAY_BOTTOM)
    @DreamOverlayComponent.DreamOverlayScope
    static long providesDreamOutComplicationsAlphaDelayBottom(@Main Resources resources) {
        return (long) resources.getInteger(R.integer.config_dreamOverlayOutAlphaDelayBottomMs);
    }

    @Provides
    @Named(DREAM_OUT_ALPHA_DELAY_TOP)
    @DreamOverlayComponent.DreamOverlayScope
    static long providesDreamOutComplicationsAlphaDelayTop(@Main Resources resources) {
        return (long) resources.getInteger(R.integer.config_dreamOverlayOutAlphaDelayTopMs);
    }

    @Provides
    @Named(DREAM_OUT_BLUR_DURATION)
    @DreamOverlayComponent.DreamOverlayScope
    static long providesDreamOutBlurDuration(@Main Resources resources) {
        return (long) resources.getInteger(R.integer.config_dreamOverlayOutBlurDurationMs);
    }

    @Provides
    @DreamOverlayComponent.DreamOverlayScope
    static Lifecycle providesLifecycle(LifecycleOwner lifecycleOwner) {
        return lifecycleOwner.getLifecycle();
    }

    @Provides
    @ElementsIntoSet
    static Set<DreamTouchHandler> providesDreamTouchHandlers(
            @Named(DREAM_TOUCH_HANDLERS) @Nullable Set<DreamTouchHandler> touchHandlers) {
        return touchHandlers != null ? touchHandlers : new HashSet<>();
    }

    /**
     * Provides {@link HideComplicationTouchHandler} for inclusion in touch handling over the dream.
     */
    @Provides
    @IntoSet
    public static DreamTouchHandler providesHideComplicationTouchHandler(
            ComplicationComponent.Factory componentFactory,
            Complication.VisibilityController visibilityController,
            TouchInsetManager touchInsetManager) {
        ComplicationComponent component =
                componentFactory.create(visibilityController, touchInsetManager);
        return component.getHideComplicationTouchHandler();
    }
}
