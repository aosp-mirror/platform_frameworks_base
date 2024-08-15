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

import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags.LOCKSCREEN_WALLPAPER_DREAM_ENABLED
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationClassificationFlag
import com.android.systemui.statusbar.notification.collection.PipelineDumpable
import com.android.systemui.statusbar.notification.collection.PipelineDumper
import com.android.systemui.statusbar.notification.collection.SortBySectionTimeFlag
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner
import com.android.systemui.statusbar.notification.collection.provider.SectionStyleProvider
import com.android.systemui.statusbar.notification.shared.NotificationMinimalismPrototype
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor
import com.android.systemui.statusbar.notification.shared.PriorityPeopleSection
import javax.inject.Inject

/**
 * Handles the attachment of [Coordinator]s to the [NotifPipeline] so that the Coordinators can
 * register their respective callbacks.
 */
interface NotifCoordinators : Coordinator, PipelineDumpable

@CoordinatorScope
class NotifCoordinatorsImpl
@Inject
constructor(
    sectionStyleProvider: SectionStyleProvider,
    featureFlags: FeatureFlags,
    dataStoreCoordinator: DataStoreCoordinator,
    hideLocallyDismissedNotifsCoordinator: HideLocallyDismissedNotifsCoordinator,
    hideNotifsForOtherUsersCoordinator: HideNotifsForOtherUsersCoordinator,
    keyguardCoordinator: KeyguardCoordinator,
    unseenKeyguardCoordinator: OriginalUnseenKeyguardCoordinator,
    lockScreenMinimalismCoordinator: LockScreenMinimalismCoordinator,
    rankingCoordinator: RankingCoordinator,
    colorizedFgsCoordinator: ColorizedFgsCoordinator,
    deviceProvisionedCoordinator: DeviceProvisionedCoordinator,
    bubbleCoordinator: BubbleCoordinator,
    headsUpCoordinator: HeadsUpCoordinator,
    gutsCoordinator: GutsCoordinator,
    conversationCoordinator: ConversationCoordinator,
    debugModeCoordinator: DebugModeCoordinator,
    groupCountCoordinator: GroupCountCoordinator,
    groupWhenCoordinator: GroupWhenCoordinator,
    mediaCoordinator: MediaCoordinator,
    preparationCoordinator: PreparationCoordinator,
    remoteInputCoordinator: RemoteInputCoordinator,
    rowAlertTimeCoordinator: RowAlertTimeCoordinator,
    rowAppearanceCoordinator: RowAppearanceCoordinator,
    stackCoordinator: StackCoordinator,
    shadeEventCoordinator: ShadeEventCoordinator,
    smartspaceDedupingCoordinator: SmartspaceDedupingCoordinator,
    viewConfigCoordinator: ViewConfigCoordinator,
    visualStabilityCoordinator: VisualStabilityCoordinator,
    sensitiveContentCoordinator: SensitiveContentCoordinator,
    dismissibilityCoordinator: DismissibilityCoordinator,
    dreamCoordinator: DreamCoordinator,
    statsLoggerCoordinator: NotificationStatsLoggerCoordinator,
    bundleCoordinator: BundleCoordinator,
) : NotifCoordinators {

    private val mCoreCoordinators: MutableList<CoreCoordinator> = ArrayList()
    private val mCoordinators: MutableList<Coordinator> = ArrayList()
    private val mOrderedSections: MutableList<NotifSectioner> = ArrayList()

    /** Creates all the coordinators. */
    init {
        // Attach core coordinators.
        mCoreCoordinators.add(dataStoreCoordinator)

        // Attach normal coordinators.
        mCoordinators.add(hideLocallyDismissedNotifsCoordinator)
        mCoordinators.add(hideNotifsForOtherUsersCoordinator)
        mCoordinators.add(keyguardCoordinator)
        if (NotificationMinimalismPrototype.isEnabled) {
            mCoordinators.add(lockScreenMinimalismCoordinator)
        } else {
            mCoordinators.add(unseenKeyguardCoordinator)
        }
        mCoordinators.add(rankingCoordinator)
        mCoordinators.add(colorizedFgsCoordinator)
        mCoordinators.add(deviceProvisionedCoordinator)
        mCoordinators.add(bubbleCoordinator)
        mCoordinators.add(debugModeCoordinator)
        mCoordinators.add(conversationCoordinator)
        mCoordinators.add(groupCountCoordinator)
        mCoordinators.add(groupWhenCoordinator)
        mCoordinators.add(mediaCoordinator)
        mCoordinators.add(rowAlertTimeCoordinator)
        mCoordinators.add(rowAppearanceCoordinator)
        mCoordinators.add(stackCoordinator)
        mCoordinators.add(shadeEventCoordinator)
        mCoordinators.add(viewConfigCoordinator)
        mCoordinators.add(visualStabilityCoordinator)
        mCoordinators.add(sensitiveContentCoordinator)
        mCoordinators.add(smartspaceDedupingCoordinator)
        mCoordinators.add(headsUpCoordinator)
        mCoordinators.add(gutsCoordinator)
        mCoordinators.add(preparationCoordinator)
        mCoordinators.add(remoteInputCoordinator)
        mCoordinators.add(dismissibilityCoordinator)

        if (featureFlags.isEnabled(LOCKSCREEN_WALLPAPER_DREAM_ENABLED)) {
            mCoordinators.add(dreamCoordinator)
        }

        if (NotificationsLiveDataStoreRefactor.isEnabled) {
            mCoordinators.add(statsLoggerCoordinator)
        }

        // Manually add Ordered Sections
        if (NotificationMinimalismPrototype.isEnabled) {
            mOrderedSections.add(lockScreenMinimalismCoordinator.topOngoingSectioner) // Top Ongoing
        }
        mOrderedSections.add(headsUpCoordinator.sectioner) // HeadsUp
        if (NotificationMinimalismPrototype.isEnabled) {
            mOrderedSections.add(lockScreenMinimalismCoordinator.topUnseenSectioner) // Top Unseen
        }
        mOrderedSections.add(colorizedFgsCoordinator.sectioner) // ForegroundService
        if (PriorityPeopleSection.isEnabled) {
            mOrderedSections.add(conversationCoordinator.priorityPeopleSectioner) // Priority People
        }
        mOrderedSections.add(conversationCoordinator.peopleAlertingSectioner) // People Alerting
        if (!SortBySectionTimeFlag.isEnabled) {
            mOrderedSections.add(conversationCoordinator.peopleSilentSectioner) // People Silent
        }
        mOrderedSections.add(rankingCoordinator.alertingSectioner) // Alerting
        if (NotificationClassificationFlag.isEnabled) {
            mOrderedSections.add(bundleCoordinator.newsSectioner)
            mOrderedSections.add(bundleCoordinator.socialSectioner)
            mOrderedSections.add(bundleCoordinator.recsSectioner)
            mOrderedSections.add(bundleCoordinator.promoSectioner)
        }
        mOrderedSections.add(rankingCoordinator.silentSectioner) // Silent
        mOrderedSections.add(rankingCoordinator.minimizedSectioner) // Minimized

        sectionStyleProvider.setMinimizedSections(setOf(rankingCoordinator.minimizedSectioner))
        if (SortBySectionTimeFlag.isEnabled) {
            sectionStyleProvider.setSilentSections(
                listOf(
                    rankingCoordinator.silentSectioner,
                    rankingCoordinator.minimizedSectioner,
                )
            )
        } else {
            sectionStyleProvider.setSilentSections(
                listOf(
                    conversationCoordinator.peopleSilentSectioner,
                    rankingCoordinator.silentSectioner,
                    rankingCoordinator.minimizedSectioner,
                )
            )
        }
    }

    /**
     * Sends the pipeline to each coordinator when the pipeline is ready to accept [Pluggable]s,
     * [NotifCollectionListener]s and [NotifLifetimeExtender]s.
     */
    override fun attach(pipeline: NotifPipeline) {
        for (c in mCoreCoordinators) {
            c.attach(pipeline)
        }
        for (c in mCoordinators) {
            c.attach(pipeline)
        }
        pipeline.setSections(mOrderedSections)
    }

    /*
     * As part of the NotifPipeline dumpable, dumps the list of coordinators; sections are omitted
     * as they are dumped in the RenderStageManager instead.
     */
    override fun dumpPipeline(d: PipelineDumper) =
        with(d) {
            dump("core coordinators", mCoreCoordinators)
            dump("normal coordinators", mCoordinators)
        }

    companion object {
        private const val TAG = "NotifCoordinators"
    }
}
