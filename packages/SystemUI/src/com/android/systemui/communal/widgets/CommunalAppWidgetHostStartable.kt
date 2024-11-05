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

package com.android.systemui.communal.widgets

import com.android.systemui.CoreStartable
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.communal.shared.model.GlanceableHubMultiUserHelper
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.kotlin.BooleanFlowOperators.allOf
import com.android.systemui.util.kotlin.BooleanFlowOperators.anyOf
import com.android.systemui.util.kotlin.BooleanFlowOperators.not
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.kotlin.sample
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

// Started per user, so make sure injections are lazy to avoid instantiating unnecessary
// dependencies.
@SysUISingleton
class CommunalAppWidgetHostStartable
@Inject
constructor(
    private val appWidgetHostLazy: Lazy<CommunalAppWidgetHost>,
    private val communalWidgetHostLazy: Lazy<CommunalWidgetHost>,
    private val communalInteractorLazy: Lazy<CommunalInteractor>,
    private val communalSettingsInteractorLazy: Lazy<CommunalSettingsInteractor>,
    private val keyguardInteractorLazy: Lazy<KeyguardInteractor>,
    private val userTrackerLazy: Lazy<UserTracker>,
    @Background private val bgScope: CoroutineScope,
    @Main private val uiDispatcher: CoroutineDispatcher,
    private val glanceableHubWidgetManagerLazy: Lazy<GlanceableHubWidgetManager>,
    private val glanceableHubMultiUserHelper: GlanceableHubMultiUserHelper,
) : CoreStartable {

    private val appWidgetHost by lazy { appWidgetHostLazy.get() }
    private val communalWidgetHost by lazy { communalWidgetHostLazy.get() }
    private val communalInteractor by lazy { communalInteractorLazy.get() }
    private val communalSettingsInteractor by lazy { communalSettingsInteractorLazy.get() }
    private val keyguardInteractor by lazy { keyguardInteractorLazy.get() }
    private val userTracker by lazy { userTrackerLazy.get() }
    private val glanceableHubWidgetManager by lazy { glanceableHubWidgetManagerLazy.get() }

    override fun start() {
        if (
            glanceableHubMultiUserHelper.glanceableHubHsumFlagEnabled &&
                glanceableHubMultiUserHelper.isInHeadlessSystemUser()
        ) {
            onStartInHeadlessSystemUser()
        } else {
            onStartInForegroundUser()
        }
    }

    private fun onStartInForegroundUser() {
        // Make the host active when communal becomes available, and delete widgets whose user has
        // been removed from the system.
        // Skipped in HSUM, because lifecycle of the host is controlled by the
        // [GlanceableHubWidgetManagerService], and widgets are stored per user so no more orphaned
        // widgets.
        if (
            !glanceableHubMultiUserHelper.glanceableHubHsumFlagEnabled ||
                !glanceableHubMultiUserHelper.isHeadlessSystemUserMode()
        ) {
            anyOf(communalInteractor.isCommunalAvailable, communalInteractor.editModeOpen)
                // Only trigger updates on state changes, ignoring the initial false value.
                .pairwise(false)
                .filter { (previous, new) -> previous != new }
                .onEach { (_, shouldListen) -> updateAppWidgetHostActive(shouldListen) }
                .sample(communalInteractor.communalWidgets, ::Pair)
                .onEach { (withPrev, widgets) ->
                    val (_, isActive) = withPrev
                    // The validation is performed once the hub becomes active.
                    if (isActive) {
                        validateWidgetsAndDeleteOrphaned(widgets)
                    }
                }
                .launchIn(bgScope)
        }

        appWidgetHost.appWidgetIdToRemove
            .onEach { appWidgetId -> communalInteractor.deleteWidget(id = appWidgetId) }
            .launchIn(bgScope)
    }

    private fun onStartInHeadlessSystemUser() {
        // Connection to the widget manager service in the foreground user should stay open as long
        // as the Glanceable Hub is available.
        allOf(
                communalSettingsInteractor.isCommunalEnabled,
                not(keyguardInteractor.isEncryptedOrLockdown),
            )
            .distinctUntilChanged()
            // Drop the initial false
            .dropWhile { !it }
            .onEach { shouldRegister ->
                if (shouldRegister) {
                    glanceableHubWidgetManager.register()
                } else {
                    glanceableHubWidgetManager.unregister()
                }
            }
            .launchIn(bgScope)
    }

    private suspend fun updateAppWidgetHostActive(active: Boolean) =
        // Always ensure this is called on the main/ui thread.
        withContext(uiDispatcher) {
            if (active) {
                communalWidgetHost.startObservingHost()
                appWidgetHost.startListening()
            } else {
                appWidgetHost.stopListening()
                communalWidgetHost.stopObservingHost()
            }
        }

    /**
     * Ensure the existence of all associated users for widgets, and remove widgets belonging to
     * users who have been deleted.
     */
    private fun validateWidgetsAndDeleteOrphaned(widgets: List<CommunalWidgetContentModel>) {
        val currentUserIds = userTracker.userProfiles.map { it.id }.toSet()
        widgets
            .filter { widget ->
                val uid =
                    when (widget) {
                        is CommunalWidgetContentModel.Available ->
                            widget.providerInfo.profile?.identifier
                        is CommunalWidgetContentModel.Pending -> widget.user.identifier
                    }
                !currentUserIds.contains(uid)
            }
            .onEach { widget -> communalInteractor.deleteWidget(id = widget.appWidgetId) }
    }
}
