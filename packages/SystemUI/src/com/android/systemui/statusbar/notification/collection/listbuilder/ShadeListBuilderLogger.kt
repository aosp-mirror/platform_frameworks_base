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
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter
import com.android.systemui.statusbar.notification.logKey
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

    fun logEndBuildList(buildId: Int, topLevelEntries: Int, numChildren: Int) {
        buffer.log(TAG, INFO, {
            long1 = buildId.toLong()
            int1 = topLevelEntries
            int2 = numChildren
        }, {
            "(Build $long1) Build complete ($int1 top-level entries, $int2 children)"
        })
    }

    fun logPreRenderInvalidated(filterName: String, pipelineState: Int) {
        buffer.log(TAG, DEBUG, {
            str1 = filterName
            int1 = pipelineState
        }, {
            """Pre-render Invalidator "$str1" invalidated; pipeline state is $int1"""
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

    fun logReorderingAllowedInvalidated(name: String, pipelineState: Int) {
        buffer.log(TAG, DEBUG, {
            str1 = name
            int1 = pipelineState
        }, {
            """ReorderingNowAllowed "$str1" invalidated; pipeline state is $int1"""
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

    fun logDuplicateSummary(
        buildId: Int,
        group: GroupEntry,
        existingSummary: NotificationEntry,
        newSummary: NotificationEntry
    ) {
        buffer.log(TAG, WARNING, {
            long1 = buildId.toLong()
            str1 = group.logKey
            str2 = existingSummary.logKey
            str3 = newSummary.logKey
        }, {
            """(Build $long1) Duplicate summary for group "$str1": "$str2" vs. "$str3""""
        })
    }

    fun logDuplicateTopLevelKey(buildId: Int, topLevelKey: String) {
        buffer.log(TAG, WARNING, {
            long1 = buildId.toLong()
            str1 = logKey(topLevelKey)
        }, {
            "(Build $long1) Duplicate top-level key: $str1"
        })
    }

    fun logEntryAttachStateChanged(
        buildId: Int,
        entry: ListEntry,
        prevParent: GroupEntry?,
        newParent: GroupEntry?
    ) {
        buffer.log(TAG, INFO, {
            long1 = buildId.toLong()
            str1 = entry.logKey
            str2 = prevParent?.logKey
            str3 = newParent?.logKey
        }, {

            val action = if (str2 == null && str3 != null) {
                "ATTACHED"
            } else if (str2 != null && str3 == null) {
                "DETACHED"
            } else if (str2 == null && str3 == null) {
                "MODIFIED (DETACHED)"
            } else {
                "MODIFIED (ATTACHED)"
            }

            "(Build $long1) $action {$str1}"
        })
    }

    fun logParentChanged(buildId: Int, prevParent: GroupEntry?, newParent: GroupEntry?) {
        buffer.log(TAG, INFO, {
            long1 = buildId.toLong()
            str1 = prevParent?.logKey
            str2 = newParent?.logKey
        }, {
            if (str1 == null && str2 != null) {
                "(Build $long1)     Parent is {$str2}"
            } else if (str1 != null && str2 == null) {
                "(Build $long1)     Parent was {$str1}"
            } else {
                "(Build $long1)     Reparent: {$str1} -> {$str2}"
            }
        })
    }

    fun logParentChangeSuppressed(
        buildId: Int,
        suppressedParent: GroupEntry?,
        keepingParent: GroupEntry?
    ) {
        buffer.log(TAG, INFO, {
            long1 = buildId.toLong()
            str1 = suppressedParent?.logKey
            str2 = keepingParent?.logKey
        }, {
            "(Build $long1)     Change of parent to '$str1' suppressed; keeping parent '$str2'"
        })
    }

    fun logGroupPruningSuppressed(
        buildId: Int,
        keepingParent: GroupEntry?
    ) {
        buffer.log(TAG, INFO, {
            long1 = buildId.toLong()
            str1 = keepingParent?.logKey
        }, {
            "(Build $long1)     Group pruning suppressed; keeping parent '$str1'"
        })
    }

    fun logPrunedReasonChanged(
        buildId: Int,
        prevReason: String?,
        newReason: String?
    ) {
        buffer.log(TAG, INFO, {
            long1 = buildId.toLong()
            str1 = prevReason
            str2 = newReason
        }, {
            "(Build $long1)     Pruned reason changed: $str1 -> $str2"
        })
    }

    fun logFilterChanged(
        buildId: Int,
        prevFilter: NotifFilter?,
        newFilter: NotifFilter?
    ) {
        buffer.log(TAG, INFO, {
            long1 = buildId.toLong()
            str1 = prevFilter?.name
            str2 = newFilter?.name
        }, {
            "(Build $long1)     Filter changed: $str1 -> $str2"
        })
    }

    fun logPromoterChanged(
        buildId: Int,
        prevPromoter: NotifPromoter?,
        newPromoter: NotifPromoter?
    ) {
        buffer.log(TAG, INFO, {
            long1 = buildId.toLong()
            str1 = prevPromoter?.name
            str2 = newPromoter?.name
        }, {
            "(Build $long1)     Promoter changed: $str1 -> $str2"
        })
    }

    fun logSectionChanged(
        buildId: Int,
        prevSection: NotifSection?,
        newSection: NotifSection?
    ) {
        buffer.log(TAG, INFO, {
            long1 = buildId.toLong()
            str1 = prevSection?.label
            str2 = newSection?.label
        }, {
            if (str1 == null) {
                "(Build $long1)     Section assigned: $str2"
            } else {
                "(Build $long1)     Section changed: $str1 -> $str2"
            }
        })
    }

    fun logSectionChangeSuppressed(
        buildId: Int,
        suppressedSection: NotifSection?,
        assignedSection: NotifSection?
    ) {
        buffer.log(TAG, INFO, {
            long1 = buildId.toLong()
            str1 = suppressedSection?.label
            str2 = assignedSection?.label
        }, {
            "(Build $long1)     Suppressing section change to $str1 (staying at $str2)"
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
                str1 = entry.logKey
            }, {
                "[$int1] $str1"
            })

            if (entry is GroupEntry) {
                entry.summary?.let {
                    buffer.log(TAG, DEBUG, {
                        str1 = it.logKey
                    }, {
                        "  [*] $str1 (summary)"
                    })
                }
                for (j in entry.children.indices) {
                    val child = entry.children[j]
                    buffer.log(TAG, DEBUG, {
                        int1 = j
                        str1 = child.logKey
                    }, {
                        "  [$int1] $str1"
                    })
                }
            }
        }
    }

    fun logPipelineRunSuppressed() =
        buffer.log(TAG, INFO, {}) { "Suppressing pipeline run during animation." }
}

private const val TAG = "ShadeListBuilder"