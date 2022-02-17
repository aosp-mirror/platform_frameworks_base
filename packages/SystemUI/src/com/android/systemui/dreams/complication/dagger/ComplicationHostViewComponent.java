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

package com.android.systemui.dreams.complication.dagger;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import android.content.res.Resources;
import android.view.LayoutInflater;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.internal.util.Preconditions;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.complication.ComplicationHostViewController;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;

import javax.inject.Named;
import javax.inject.Scope;

import dagger.Module;
import dagger.Provides;
import dagger.Subcomponent;

/**
 * {@link ComplicationHostViewComponent} encapsulates the shared logic around the host view layer
 * for complications. Anything that references the layout should be provided through this component
 * and its child module. The factory should be used in order to best tie the lifetime of the view
 * to components.
 */
@Subcomponent(modules = {
        ComplicationHostViewComponent.ComplicationHostViewModule.class,
})
@ComplicationHostViewComponent.ComplicationHostViewScope
public interface ComplicationHostViewComponent {
    String SCOPED_COMPLICATIONS_LAYOUT = "scoped_complications_layout";
    String COMPLICATION_MARGIN = "complication_margin";
    /** Scope annotation for singleton items within {@link ComplicationHostViewComponent}. */
    @Documented
    @Retention(RUNTIME)
    @Scope
    @interface ComplicationHostViewScope {}

    /**
     * Factory for generating a new scoped component.
     */
    @Subcomponent.Factory
    interface Factory {
        ComplicationHostViewComponent create();
    }

    /** */
    ComplicationHostViewController getController();

    /**
     * Module for providing a scoped host view.
     */
    @Module
    abstract class ComplicationHostViewModule {
        /**
         * Generates a {@link ConstraintLayout}, which can host
         * {@link com.android.systemui.dreams.complication.Complication} instances.
         */
        @Provides
        @Named(SCOPED_COMPLICATIONS_LAYOUT)
        @ComplicationHostViewScope
        static ConstraintLayout providesComplicationHostView(
                LayoutInflater layoutInflater) {
            return Preconditions.checkNotNull((ConstraintLayout)
                            layoutInflater.inflate(R.layout.dream_overlay_complications_layer,
                                    null),
                    "R.layout.dream_overlay_complications_layer did not properly inflated");
        }

        @Provides
        @Named(COMPLICATION_MARGIN)
        @ComplicationHostViewScope
        static int providesComplicationPadding(@Main Resources resources) {
            return resources.getDimensionPixelSize(R.dimen.dream_overlay_complication_margin);
        }
    }
}
