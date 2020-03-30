/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.listbuilder

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel.DEBUG
import com.android.systemui.log.LogLevel.INFO
import com.android.systemui.log.LogLevel.WARNING
import com.android.systemui.log.dagger.NotificationLog
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import javax.inject.Inject

class ShadeListBuilderLogger @Inject constructor(
    @NotificationLog private val buffer: LogBuffer
) {
    fun logOnBuildList() {
        buffer.log(TAG, INFO, {
        }, {
            "Request received from NotifCollection"
        })
    }

    fun logEndBuildList(iterationCount: Int, listLength: Int) {
        buffer.log(TAG, INFO, {
            int1 = iterationCount
            int2 = listLength
        }, {
            "(Build $int1) Finished building shade list ($int2 top-level entries)"
        })
    }

    fun logPreGroupFilterInvalidated(filterName: String, pipelineState: Int) {
        buffer.log(TAG, DEBUG, {
            str1 = filterName
            int1 = pipelineState
        }, {
            """Pre-group NotifFilter "$str1" invalidated; pipeline state is $int1"""
        })
    }

    fun logPromoterInvalidated(name: String, pipelineState: Int) {
        buffer.log(TAG, DEBUG, {
            str1 = name
            int1 = pipelineState
        }, {
            """NotifPromoter "$str1" invalidated; pipeline state is $int1"""
        })
    }

    fun logNotifSectionInvalidated(name: String, pipelineState: Int) {
        buffer.log(TAG, DEBUG, {
            str1 = name
            int1 = pipelineState
        }, {
            """NotifSection "$str1" invalidated; pipeline state is $int1"""
        })
    }

    fun logNotifComparatorInvalidated(name: String, pipelineState: Int) {
        buffer.log(TAG, DEBUG, {
            str1 = name
            int1 = pipelineState
        }, {
            """NotifComparator "$str1" invalidated; pipeline state is $int1"""
        })
    }

    fun logFinalizeFilterInvalidated(name: String, pipelineState: Int) {
        buffer.log(TAG, DEBUG, {
            str1 = name
            int1 = pipelineState
        }, {
            """Finalize NotifFilter "$str1" invalidated; pipeline state is $int1"""
        })
    }

    fun logDuplicateSummary(buildId: Int, groupKey: String, existingKey: String, newKey: String) {
        buffer.log(TAG, WARNING, {
            int1 = buildId
            str1 = groupKey
            str2 = existingKey
            str3 = newKey
        }, {
            """(Build $int1) Duplicate summary for group "$str1": "$str2" vs. "$str3""""
        })
    }

    fun logDuplicateTopLevelKey(buildId: Int, topLevelKey: String) {
        buffer.log(TAG, WARNING, {
            int1 = buildId
            str1 = topLevelKey
        }, {
            "(Build $int1) Duplicate top-level key: $str1"
        })
    }

    fun logParentChanged(buildId: Int, key: String, prevParent: String?, newParent: String?) {
        buffer.log(TAG, INFO, {
            int1 = buildId
            str1 = key
            str2 = prevParent
            str3 = newParent
        }, {
            "(Build $int1) Parent change for $str1: $str2 -> $str3"
        })
    }

    fun logFilterChanged(
        buildId: Int,
        key: String,
        prevFilter: NotifFilter?,
        newFilter: NotifFilter?
    ) {
        buffer.log(TAG, INFO, {
            int1 = buildId
            str1 = key
            str2 = prevFilter?.name
            str3 = newFilter?.name
        }, {
            "(Build $int1) Filter changed for $str1: $str2 -> $str3"
        })
    }

    fun logPromoterChanged(
        buildId: Int,
        key: String,
        prevPromoter: String?,
        newPromoter: String?
    ) {
        buffer.log(TAG, INFO, {
            int1 = buildId
            str1 = key
            str2 = prevPromoter
            str3 = newPromoter
        }, {
            "(Build $int1) Promoter changed for $str1: $str2 -> $str3"
        })
    }

    fun logSectionChanged(
        buildId: Int,
        key: String,
        prevSection: String?,
        prevIndex: Int,
        section: String,
        index: Int
    ) {
        buffer.log(TAG, INFO, {
            long1 = buildId.toLong()
            str1 = key
            str2 = section
            int1 = index
            str3 = prevSection
            int2 = prevIndex
        }, {
            if (str3 == null) {
                "(Build $long1) Section assigned for $str1: '$str2' (#$int1)"
            } else {
                "(Build $long1) Section changed for $str1: '$str3' (#$int2) -> '$str2' (#$int1)"
            }
        })
    }

    fun logFinalList(entries: List<ListEntry>) {
        if (entries.isEmpty()) {
            buffer.log(TAG, DEBUG, {}, { "(empty list)" })
        }
        for (i in entries.indices) {
            val entry = entries[i]
            buffer.log(TAG, DEBUG, {
                int1 = i
                str1 = entry.key
            }, {
                "[$int1] $str1"
            })

            if (entry is GroupEntry) {
                entry.summary?.let {
                    buffer.log(TAG, DEBUG, {
                        str1 = it.key
                    }, {
                        "  [*] $str1 (summary)"
                    })
                }
                for (j in entry.children.indices) {
                    val child = entry.children[j]
                    buffer.log(TAG, DEBUG, {
                        int1 = j
                        str1 = child.key
                    }, {
                        "  [$int1] $str1"
                    })
                }
            }
        }
    }
}

private const val TAG = "ShadeListBuilder"