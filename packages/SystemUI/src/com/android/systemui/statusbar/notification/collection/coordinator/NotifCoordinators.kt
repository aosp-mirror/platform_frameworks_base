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
package com.android.systemui.statusbar.notification.collection.coordinator

import com.android.systemui.Dumpable
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.notification.NotifPipelineFlags
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner
import java.io.PrintWriter
import javax.inject.Inject

/**
 * Handles the attachment of [Coordinator]s to the [NotifPipeline] so that the
 * Coordinators can register their respective callbacks.
 */
interface NotifCoordinators : Coordinator, Dumpable

@CoordinatorScope
class NotifCoordinatorsImpl @Inject constructor(
    dumpManager: DumpManager,
    notifPipelineFlags: NotifPipelineFlags,
    dataStoreCoordinator: DataStoreCoordinator,
    hideLocallyDismissedNotifsCoordinator: HideLocallyDismissedNotifsCoordinator,
    hideNotifsForOtherUsersCoordinator: HideNotifsForOtherUsersCoordinator,
    keyguardCoordinator: KeyguardCoordinator,
    rankingCoordinator: RankingCoordinator,
    appOpsCoordinator: AppOpsCoordinator,
    deviceProvisionedCoordinator: DeviceProvisionedCoordinator,
    bubbleCoordinator: BubbleCoordinator,
    headsUpCoordinator: HeadsUpCoordinator,
    gutsCoordinator: GutsCoordinator,
    conversationCoordinator: ConversationCoordinator,
    debugModeCoordinator: DebugModeCoordinator,
    groupCountCoordinator: GroupCountCoordinator,
    mediaCoordinator: MediaCoordinator,
    preparationCoordinator: PreparationCoordinator,
    remoteInputCoordinator: RemoteInputCoordinator,
    rowAppearanceCoordinator: RowAppearanceCoordinator,
    stackCoordinator: StackCoordinator,
    shadeEventCoordinator: ShadeEventCoordinator,
    smartspaceDedupingCoordinator: SmartspaceDedupingCoordinator,
    viewConfigCoordinator: ViewConfigCoordinator,
    visualStabilityCoordinator: VisualStabilityCoordinator,
    activityLaunchAnimCoordinator: ActivityLaunchAnimCoordinator
) : NotifCoordinators {

    private val mCoordinators: MutableList<Coordinator> = ArrayList()
    private val mOrderedSections: MutableList<NotifSectioner> = ArrayList()

    /**
     * Creates all the coordinators.
     */
    init {
        dumpManager.registerDumpable(TAG, this)

        // TODO(b/208866714): formalize the system by which some coordinators may be required by the
        //  pipeline, such as this DataStoreCoordinator which cannot be removed, as it's a critical
        //  glue between the pipeline and parts of SystemUI which depend on pipeline output via the
        //  NotifLiveDataStore.
        if (notifPipelineFlags.isNewPipelineEnabled()) {
            mCoordinators.add(dataStoreCoordinator)
        }

        // Attach normal coordinators.
        mCoordinators.add(hideLocallyDismissedNotifsCoordinator)
        mCoordinators.add(hideNotifsForOtherUsersCoordinator)
        mCoordinators.add(keyguardCoordinator)
        mCoordinators.add(rankingCoordinator)
        mCoordinators.add(appOpsCoordinator)
        mCoordinators.add(deviceProvisionedCoordinator)
        mCoordinators.add(bubbleCoordinator)
        mCoordinators.add(debugModeCoordinator)
        mCoordinators.add(conversationCoordinator)
        mCoordinators.add(groupCountCoordinator)
        mCoordinators.add(mediaCoordinator)
        mCoordinators.add(rowAppearanceCoordinator)
        mCoordinators.add(stackCoordinator)
        mCoordinators.add(shadeEventCoordinator)
        mCoordinators.add(viewConfigCoordinator)
        mCoordinators.add(visualStabilityCoordinator)
//        mCoordinators.add(activityLaunchAnimCoordinator) // NOTE: will delete in followup CL
        if (notifPipelineFlags.isSmartspaceDedupingEnabled()) {
            mCoordinators.add(smartspaceDedupingCoordinator)
        }
        if (notifPipelineFlags.isNewPipelineEnabled()) {
            mCoordinators.add(headsUpCoordinator)
            mCoordinators.add(gutsCoordinator)
            mCoordinators.add(preparationCoordinator)
            mCoordinators.add(remoteInputCoordinator)
        }

        // Manually add Ordered Sections
        // HeadsUp > FGS > People > Alerting > Silent > Minimized > Unknown/Default
        if (notifPipelineFlags.isNewPipelineEnabled()) {
            mOrderedSections.add(headsUpCoordinator.sectioner) // HeadsUp
        }
        mOrderedSections.add(appOpsCoordinator.sectioner) // ForegroundService
        mOrderedSections.add(conversationCoordinator.sectioner) // People
        mOrderedSections.add(rankingCoordinator.alertingSectioner) // Alerting
        mOrderedSections.add(rankingCoordinator.silentSectioner) // Silent
        mOrderedSections.add(rankingCoordinator.minimizedSectioner) // Minimized
    }

    /**
     * Sends the pipeline to each coordinator when the pipeline is ready to accept
     * [Pluggable]s, [NotifCollectionListener]s and [NotifLifetimeExtender]s.
     */
    override fun attach(pipeline: NotifPipeline) {
        for (c in mCoordinators) {
            c.attach(pipeline)
        }
        pipeline.setSections(mOrderedSections)
    }

    override fun dump(pw: PrintWriter, args: Array<String>) {
        pw.println()
        pw.println("$TAG:")
        for (c in mCoordinators) {
            pw.println("\t${c.javaClass}")
        }
        for (s in mOrderedSections) {
            pw.println("\t${s.name}")
        }
    }

    companion object {
        private const val TAG = "NotifCoordinators"
    }
}
