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

package com.android.systemui.navigationbar;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.DisplayId;
import com.android.systemui.navigationbar.NavigationBarComponent.NavigationBarScope;
import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler;

import dagger.Module;
import dagger.Provides;

/** Module for {@link com.android.systemui.navigationbar.NavigationBarComponent}. */
@Module
public interface NavigationBarModule {
    /** A Layout inflater specific to the display's context. */
    @Provides
    @NavigationBarScope
    @DisplayId
    static LayoutInflater provideLayoutInflater(@DisplayId Context context) {
        return LayoutInflater.from(context);
    }

    /** */
    @Provides
    @NavigationBarScope
    static NavigationBarFrame provideNavigationBarFrame(@DisplayId LayoutInflater layoutInflater) {
        return (NavigationBarFrame) layoutInflater.inflate(R.layout.navigation_bar_window, null);
    }

    /** */
    @Provides
    @NavigationBarScope
    static NavigationBarView provideNavigationBarview(
            @DisplayId LayoutInflater layoutInflater, NavigationBarFrame frame) {
        View barView = layoutInflater.inflate(R.layout.navigation_bar, frame);
        return barView.findViewById(R.id.navigation_bar_view);
    }

    /** */
    @Provides
    @NavigationBarScope
    static EdgeBackGestureHandler provideEdgeBackGestureHandler(
            EdgeBackGestureHandler.Factory factory, @DisplayId Context context) {
        return factory.create(context);
    }

    /** A WindowManager specific to the display's context. */
    @Provides
    @NavigationBarScope
    @DisplayId
    static WindowManager provideWindowManager(@DisplayId Context context) {
        return context.getSystemService(WindowManager.class);
    }
}
