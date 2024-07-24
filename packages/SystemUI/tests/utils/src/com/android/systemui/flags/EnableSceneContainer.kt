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

package com.android.systemui.flags

import android.platform.test.annotations.EnableFlags
import com.android.systemui.Flags.FLAG_COMPOSE_LOCKSCREEN
import com.android.systemui.Flags.FLAG_KEYGUARD_BOTTOM_AREA_REFACTOR
import com.android.systemui.Flags.FLAG_MEDIA_IN_SCENE_CONTAINER
import com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT
import com.android.systemui.Flags.FLAG_SCENE_CONTAINER

/**
 * This includes @[EnableFlags] to work with [SetFlagsRule] to enable all aconfig flags required by
 * that feature. It is also picked up by [SceneContainerRule] to set non-aconfig prerequisites.
 */
@EnableFlags(
    FLAG_SCENE_CONTAINER,
    FLAG_KEYGUARD_BOTTOM_AREA_REFACTOR,
    FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT,
    FLAG_COMPOSE_LOCKSCREEN,
    FLAG_MEDIA_IN_SCENE_CONTAINER,
)
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class EnableSceneContainer
