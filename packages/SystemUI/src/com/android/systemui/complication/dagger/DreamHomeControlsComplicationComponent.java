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

package com.android.systemui.complication.dagger;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.widget.ImageView;

import com.android.systemui.complication.DreamHomeControlsComplication;
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
 * Responsible for generating dependencies for the {@link DreamHomeControlsComplication}.
 */
@Subcomponent(modules = DreamHomeControlsComplicationComponent.DreamHomeControlsModule.class)
@DreamHomeControlsComplicationComponent.DreamHomeControlsComplicationScope
public interface DreamHomeControlsComplicationComponent {
    /**
     * Creates a view holder for the home controls complication.
     */
    DreamHomeControlsComplication.DreamHomeControlsChipViewHolder getViewHolder();

    /**
     * Scope of the home controls complication.
     */
    @Documented
    @Retention(RUNTIME)
    @Scope
    @interface DreamHomeControlsComplicationScope {}

    /**
     * Factory that generates a {@link DreamHomeControlsComplicationComponent}.
     */
    @Subcomponent.Factory
    interface Factory {
        DreamHomeControlsComplicationComponent create(@BindsInstance Resources resources);
    }

    /**
     * Scoped injected values for the {@link DreamHomeControlsComplicationComponent}.
     */
    @Module
    interface DreamHomeControlsModule {
        String DREAM_HOME_CONTROLS_CHIP_VIEW = "dream_home_controls_chip_view";
        String DREAM_HOME_CONTROLS_BACKGROUND_DRAWABLE = "dream_home_controls_background_drawable";

        /**
         * Provides the dream home controls chip view.
         */
        @Provides
        @DreamHomeControlsComplicationScope
        @Named(DREAM_HOME_CONTROLS_CHIP_VIEW)
        static ImageView provideHomeControlsChipView(
                LayoutInflater layoutInflater,
                @Named(DREAM_HOME_CONTROLS_BACKGROUND_DRAWABLE) Drawable backgroundDrawable) {
            final ImageView chip =
                    (ImageView) layoutInflater.inflate(R.layout.dream_overlay_home_controls_chip,
                            null, false);
            chip.setBackground(backgroundDrawable);

            return chip;
        }

        @Provides
        @DreamHomeControlsComplicationScope
        @Named(DREAM_HOME_CONTROLS_BACKGROUND_DRAWABLE)
        static Drawable providesHomeControlsBackground(Context context, Resources resources) {
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
