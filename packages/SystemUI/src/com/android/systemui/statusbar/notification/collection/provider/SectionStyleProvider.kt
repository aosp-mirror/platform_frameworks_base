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

package com.android.systemui.statusbar.notification.collection.provider

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifSection
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner
import javax.inject.Inject

/**
 * A class which is used to classify the sections.
 * NOTE: This class exists to avoid putting metadata like "isMinimized" on the NotifSection
 */
@SysUISingleton
class SectionStyleProvider @Inject constructor() {
    private lateinit var lowPrioritySections: Set<NotifSectioner>

    /**
     * Feed the provider the information it needs about which sections should have minimized top
     * level views, so that it can calculate the correct minimized state.
     */
    fun setMinimizedSections(sections: Collection<NotifSectioner>) {
        lowPrioritySections = sections.toSet()
    }

    /**
     * Determine if the given section is minimized
     */
    fun isMinimizedSection(section: NotifSection): Boolean {
        return lowPrioritySections.contains(section.sectioner)
    }
}