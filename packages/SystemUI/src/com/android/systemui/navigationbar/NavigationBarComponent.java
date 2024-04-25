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

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.android.systemui.dagger.qualifiers.DisplayId;

import dagger.BindsInstance;
import dagger.Subcomponent;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;

import javax.inject.Scope;

/**
 * Subcomponent for a NavigationBar.
 *
 * Generally creatd on a per-display basis.
 */
@Subcomponent(modules = { NavigationBarModule.class })
@NavigationBarComponent.NavigationBarScope
public interface NavigationBarComponent {

    /** Factory for {@link NavigationBarComponent}. */
    @Subcomponent.Factory
    interface Factory {
        NavigationBarComponent create(
                @BindsInstance @DisplayId Context context,
                @BindsInstance @Nullable Bundle savedState);
    }

    /** */
    NavigationBar getNavigationBar();

    /**
     * Scope annotation for singleton items within the NavigationBarComponent.
     */
    @Documented
    @Retention(RUNTIME)
    @Scope
    @interface NavigationBarScope {}
}
