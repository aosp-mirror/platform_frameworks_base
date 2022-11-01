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
import com.android.systemui.statusbar.notification.NotifPipelineFlags
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.StateName
import com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.getStateName
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Invalidator
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifComparator
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifStabilityManager
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Pluggable
import com.android.systemui.statusbar.notification.logKey
import com.android.systemui.util.Compile
import javax.inject.Inject

class ShadeListBuilderLogger @Inject constructor(
    notifPipelineFlags: NotifPipelineFlags,
    @NotificationLog private val buffer: LogBuffer
) {
    fun logOnBuildList(reason: String?) {
        buffer.log(TAG, INFO, {
            str1 = reason
        }, {
            "Request received from NotifCollection for $str1"
        })
    }

    fun logEndBuildList(
        buildId: Int,
        topLevelEntries: Int,
        numChildren: Int,
        enforcedVisualStability: Boolean
    ) {
        buffer.log(TAG, INFO, {
            long1 = buildId.toLong()
            int1 = topLevelEntries
            int2 = numChildren
            bool1 = enforcedVisualStability
        }, {
            "(Build $long1) Build complete ($int1 top-level entries, $int2 children)" +
                    " enforcedVisualStability=$bool1"
        })
    }

    private fun logPluggableInvalidated(
        type: String,
        pluggable: Pluggable<*>,
        @StateName pipelineState: Int,
        reason: String?
    ) {
        buffer.log(TAG, DEBUG, {
            str1 = type
            str2 = pluggable.name
            int1 = pipelineState
            str3 = reason
        }, {
            """Invalidated while ${getStateName(int1)} by $str1 "$str2" because $str3"""
        })
    }

    fun logPreRenderInvalidated(
        invalidator: Invalidator,
        @StateName pipelineState: Int,
        reason: String?
    ) = logPluggableInvalidated("Pre-render Invalidator", invalidator, pipelineState, reason)

    fun logPreGroupFilterInvalidated(
        filter: NotifFilter,
        @StateName pipelineState: Int,
        reason: String?
    ) = logPluggableInvalidated("Pre-group NotifFilter", filter, pipelineState, reason)

    fun logReorderingAllowedInvalidated(
        stabilityManager: NotifStabilityManager,
        @StateName pipelineState: Int,
        reason: String?
    ) = logPluggableInvalidated("ReorderingNowAllowed", stabilityManager, pipelineState, reason)

    fun logPromoterInvalidated(
        promoter: NotifPromoter,
        @StateName pipelineState: Int,
        reason: String?
    ) = logPluggableInvalidated("NotifPromoter", promoter, pipelineState, reason)

    fun logNotifSectionInvalidated(
        sectioner: NotifSectioner,
        @StateName pipelineState: Int,
        reason: String?
    ) = logPluggableInvalidated("NotifSection", sectioner, pipelineState, reason)

    fun logNotifComparatorInvalidated(
        comparator: NotifComparator,
        @StateName pipelineState: Int,
        reason: String?
    ) = logPluggableInvalidated("NotifComparator", comparator, pipelineState, reason)

    fun logFinalizeFilterInvalidated(
        filter: NotifFilter,
        @StateName pipelineState: Int,
        reason: String?
    ) = logPluggableInvalidated("Finalize NotifFilter", filter, pipelineState, reason)

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

    val logRankInFinalList = Compile.IS_DEBUG && notifPipelineFlags.isDevLoggingEnabled()

    fun logFinalList(entries: List<ListEntry>) {
        if (entries.isEmpty()) {
            buffer.log(TAG, DEBUG, {}, { "(empty list)" })
        }
        for (i in entries.indices) {
            val entry = entries[i]
            buffer.log(TAG, DEBUG, {
                int1 = i
                str1 = entry.logKey
                bool1 = logRankInFinalList
                int2 = entry.representativeEntry!!.ranking.rank
            }, {
                "[$int1] $str1".let { if (bool1) "$it rank=$int2" else it }
            })

            if (entry is GroupEntry) {
                entry.summary?.let {
                    buffer.log(TAG, DEBUG, {
                        str1 = it.logKey
                        bool1 = logRankInFinalList
                        int2 = it.ranking.rank
                    }, {
                        "  [*] $str1 (summary)".let { if (bool1) "$it rank=$int2" else it }
                    })
                }
                for (j in entry.children.indices) {
                    val child = entry.children[j]
                    buffer.log(TAG, DEBUG, {
                        int1 = j
                        str1 = child.logKey
                        bool1 = logRankInFinalList
                        int2 = child.ranking.rank
                    }, {
                        "  [$int1] $str1".let { if (bool1) "$it rank=$int2" else it }
                    })
                }
            }
        }
    }

    fun logPipelineRunSuppressed() =
        buffer.log(TAG, INFO, {}, { "Suppressing pipeline run during animation." })
}

private const val TAG = "ShadeListBuilder"
