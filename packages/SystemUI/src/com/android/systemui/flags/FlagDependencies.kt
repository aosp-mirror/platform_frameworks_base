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

package com.android.systemui.flags

import com.android.server.notification.Flags.FLAG_CROSS_APP_POLITE_NOTIFICATIONS
import com.android.server.notification.Flags.FLAG_POLITE_NOTIFICATIONS
import com.android.server.notification.Flags.FLAG_VIBRATE_WHILE_UNLOCKED
import com.android.server.notification.Flags.crossAppPoliteNotifications
import com.android.server.notification.Flags.politeNotifications
import com.android.server.notification.Flags.vibrateWhileUnlocked
import com.android.systemui.Flags.FLAG_KEYGUARD_BOTTOM_AREA_REFACTOR
import com.android.systemui.Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT
import com.android.systemui.Flags.keyguardBottomAreaRefactor
import com.android.systemui.Flags.migrateClocksToBlueprint
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.Flags.MIGRATE_KEYGUARD_STATUS_BAR_VIEW
import com.android.systemui.keyguard.shared.ComposeLockscreen
import com.android.systemui.keyguard.shared.KeyguardShadeMigrationNssl
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionRefactor
import com.android.systemui.statusbar.notification.shared.NotificationAvalancheSuppression
import com.android.systemui.statusbar.notification.shared.NotificationIconContainerRefactor
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor
import javax.inject.Inject

/** A class in which engineers can define flag dependencies */
@SysUISingleton
class FlagDependencies @Inject constructor(featureFlags: FeatureFlagsClassic, handler: Handler) :
    FlagDependenciesBase(featureFlags, handler) {
    override fun defineDependencies() {
        // Internal notification backend dependencies
        crossAppPoliteNotifications dependsOn politeNotifications
        vibrateWhileUnlockedToken dependsOn politeNotifications

        // Internal notification frontend dependencies
        NotificationsLiveDataStoreRefactor.token dependsOn NotificationIconContainerRefactor.token
        FooterViewRefactor.token dependsOn NotificationIconContainerRefactor.token
        NotificationAvalancheSuppression.token dependsOn VisualInterruptionRefactor.token

        // Internal keyguard dependencies
        KeyguardShadeMigrationNssl.token dependsOn keyguardBottomAreaRefactor

        // SceneContainer dependencies
        SceneContainerFlag.getFlagDependencies().forEach { (alpha, beta) -> alpha dependsOn beta }
        SceneContainerFlag.getMainStaticFlag() dependsOn MIGRATE_KEYGUARD_STATUS_BAR_VIEW

        // ComposeLockscreen dependencies
        ComposeLockscreen.token dependsOn KeyguardShadeMigrationNssl.token
        ComposeLockscreen.token dependsOn keyguardBottomAreaRefactor
        ComposeLockscreen.token dependsOn migrateClocksToBlueprint
    }

    private inline val politeNotifications
        get() = FlagToken(FLAG_POLITE_NOTIFICATIONS, politeNotifications())
    private inline val crossAppPoliteNotifications
        get() = FlagToken(FLAG_CROSS_APP_POLITE_NOTIFICATIONS, crossAppPoliteNotifications())
    private inline val vibrateWhileUnlockedToken: FlagToken
        get() = FlagToken(FLAG_VIBRATE_WHILE_UNLOCKED, vibrateWhileUnlocked())
    private inline val keyguardBottomAreaRefactor
        get() = FlagToken(FLAG_KEYGUARD_BOTTOM_AREA_REFACTOR, keyguardBottomAreaRefactor())
    private inline val migrateClocksToBlueprint
        get() = FlagToken(FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT, migrateClocksToBlueprint())
}
