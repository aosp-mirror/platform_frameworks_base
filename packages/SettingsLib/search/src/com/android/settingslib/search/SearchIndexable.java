/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.search;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Denotes that the class should participate in search indexing.
 */
@Retention(RetentionPolicy.SOURCE)
public @interface SearchIndexable {
    /**
     * Bitfield for the form factors this class should be considered indexable for.
     * Default is {@link #ALL}.
     *
     * TODO: actually use this value somehow
     */
    int forTarget() default ALL;

    /**
     * Indicates that the class should be considered indexable for Mobile.
     */
    int MOBILE = 1<<0;

    /**
     * Indicates that the class should be considered indexable for TV.
     */
    int TV = 1<<1;

    /**
     * Indicates that the class should be considered indexable for Wear.
     */
    int WEAR = 1<<2;

    /**
     * Indicates that the class should be considered indexable for Auto.
     */
    int AUTO = 1<<3;

    /**
     * Indicates that the class should be considered indexable for ARC++.
     */
    int ARC = 1<<4;

    /**
     * Indicates that the class should be considered indexable for all targets.
     */
    int ALL = MOBILE | TV | WEAR | AUTO | ARC;

}
