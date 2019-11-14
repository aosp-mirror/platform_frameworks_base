/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.listbuilder.pluggable;

import com.android.systemui.statusbar.notification.collection.ListEntry;

/**
 * Interface for sorting notifications into "sections", such as a heads-upping section, people
 * section, alerting section, silent section, etc.
 */
public abstract class SectionsProvider extends Pluggable<SectionsProvider> {

    protected SectionsProvider(String name) {
        super(name);
    }

    /**
     * Returns the section that this entry belongs to. A section can be any non-negative integer.
     * When entries are sorted, they are first sorted by section and then by any remainining
     * comparators.
     */
    public abstract int getSection(ListEntry entry);
}
