/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.pipeline.domain.autoaddable

import android.content.ComponentName
import android.provider.Settings
import com.android.systemui.accessibility.data.repository.AccessibilityQsShortcutsRepository
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.domain.model.AutoAddable
import com.android.systemui.qs.pipeline.shared.TileSpec
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.Objects
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * [A11yShortcutAutoAddable] will auto add/remove qs tile of the accessibility framework feature
 * based on the user's choices in the Settings app.
 *
 * The a11y feature component is added to [Settings.Secure.ACCESSIBILITY_QS_TARGETS] when the user
 * selects to use qs tile as a shortcut for the a11 feature in the Settings app. The accessibility
 * feature component is removed from [Settings.Secure.ACCESSIBILITY_QS_TARGETS] when the user
 * doesn't want to use qs tile as a shortcut for the a11y feature in the Settings app.
 *
 * [A11yShortcutAutoAddable] tracks a [Settings.Secure.ACCESSIBILITY_QS_TARGETS] and when its value
 * changes, it will emit a [AutoAddSignal.Add] for the [spec] if the [componentName] is a substring
 * of the value; it will emit a [AutoAddSignal.Remove] for the [spec] if the [componentName] is not
 * a substring of the value.
 */
class A11yShortcutAutoAddable
@AssistedInject
constructor(
    private val a11yQsShortcutsRepository: AccessibilityQsShortcutsRepository,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Assisted private val spec: TileSpec,
    @Assisted private val componentName: ComponentName
) : AutoAddable {

    override fun autoAddSignal(userId: Int): Flow<AutoAddSignal> {
        return a11yQsShortcutsRepository
            .a11yQsShortcutTargets(userId)
            .map { it.contains(componentName.flattenToString()) }
            .filterNotNull()
            .distinctUntilChanged()
            .map { if (it) AutoAddSignal.Add(spec) else AutoAddSignal.Remove(spec) }
            .flowOn(bgDispatcher)
    }

    override val autoAddTracking = AutoAddTracking.Always

    override val description =
        "A11yShortcutAutoAddableSetting: $spec:$componentName ($autoAddTracking)"

    override fun equals(other: Any?): Boolean {
        return other is A11yShortcutAutoAddable &&
            spec == other.spec &&
            componentName == other.componentName
    }

    override fun hashCode(): Int {
        return Objects.hash(spec, componentName)
    }

    override fun toString(): String {
        return description
    }

    @AssistedFactory
    interface Factory {
        fun create(spec: TileSpec, componentName: ComponentName): A11yShortcutAutoAddable
    }
}
