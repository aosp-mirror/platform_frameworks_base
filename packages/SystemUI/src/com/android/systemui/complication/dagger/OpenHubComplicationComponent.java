/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.complication.dagger;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.widget.ImageView;

import com.android.systemui.complication.OpenHubComplication;
import com.android.systemui.res.R;
import com.android.systemui.shared.shadow.DoubleShadowIconDrawable;
import com.android.systemui.shared.shadow.DoubleShadowTextHelper;

import dagger.BindsInstance;
import dagger.Module;
import dagger.Provides;
import dagger.Subcomponent;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;

import javax.inject.Named;
import javax.inject.Scope;

/**
 * Responsible for generating dependencies for the {@link OpenHubComplication}.
 */
@Subcomponent(modules = OpenHubComplicationComponent.OpenHubModule.class)
@OpenHubComplicationComponent.OpenHubComplicationScope
public interface OpenHubComplicationComponent {
    /**
     * Creates a view holder for the open hub complication.
     */
    OpenHubComplication.OpenHubChipViewHolder getViewHolder();

    /**
     * Scope of the open hub complication.
     */
    @Documented
    @Retention(RUNTIME)
    @Scope
    @interface OpenHubComplicationScope {
    }

    /**
     * Factory that generates a {@link OpenHubComplicationComponent}.
     */
    @Subcomponent.Factory
    interface Factory {
        /**
         * Creates an instance of {@link OpenHubComplicationComponent}.
         */
        OpenHubComplicationComponent create(@BindsInstance Resources resources);
    }

    /**
     * Scoped injected values for the {@link OpenHubComplicationComponent}.
     */
    @Module
    interface OpenHubModule {
        String OPEN_HUB_CHIP_VIEW = "open_hub_chip_view";
        String OPEN_HUB_BACKGROUND_DRAWABLE = "open_hub_background_drawable";

        /**
         * Provides the dream open hub chip view.
         */
        @Provides
        @OpenHubComplicationScope
        @Named(OPEN_HUB_CHIP_VIEW)
        static ImageView provideOpenHubChipView(
                LayoutInflater layoutInflater,
                @Named(OPEN_HUB_BACKGROUND_DRAWABLE) Drawable backgroundDrawable) {
            final ImageView chip =
                    (ImageView) layoutInflater.inflate(R.layout.dream_overlay_open_hub_chip,
                            null, false);
            chip.setBackground(backgroundDrawable);

            return chip;
        }

        /**
         * Provides the background drawable for the open hub chip.
         */
        @Provides
        @OpenHubComplicationScope
        @Named(OPEN_HUB_BACKGROUND_DRAWABLE)
        static Drawable providesOpenHubBackground(Context context, Resources resources) {
            return new DoubleShadowIconDrawable(createShadowInfo(
                    resources,
                    R.dimen.dream_overlay_bottom_affordance_key_text_shadow_radius,
                    R.dimen.dream_overlay_bottom_affordance_key_text_shadow_dx,
                    R.dimen.dream_overlay_bottom_affordance_key_text_shadow_dy,
                    R.dimen.dream_overlay_bottom_affordance_key_shadow_alpha
            ),
                    createShadowInfo(
                            resources,
                            R.dimen.dream_overlay_bottom_affordance_ambient_text_shadow_radius,
                            R.dimen.dream_overlay_bottom_affordance_ambient_text_shadow_dx,
                            R.dimen.dream_overlay_bottom_affordance_ambient_text_shadow_dy,
                            R.dimen.dream_overlay_bottom_affordance_ambient_shadow_alpha
                    ),
                    resources.getDrawable(R.drawable.dream_overlay_bottom_affordance_bg),
                    resources.getDimensionPixelOffset(
                            R.dimen.dream_overlay_bottom_affordance_width),
                    resources.getDimensionPixelSize(R.dimen.dream_overlay_bottom_affordance_inset)
            );
        }

        private static DoubleShadowTextHelper.ShadowInfo createShadowInfo(Resources resources,
                int blurId, int offsetXId, int offsetYId, int alphaId) {

            return new DoubleShadowTextHelper.ShadowInfo(
                    resources.getDimension(blurId),
                    resources.getDimension(offsetXId),
                    resources.getDimension(offsetYId),
                    resources.getFloat(alphaId)
            );
        }
    }
}
