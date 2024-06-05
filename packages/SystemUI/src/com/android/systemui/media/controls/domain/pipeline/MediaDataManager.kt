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

package com.android.systemui.media.controls.domain.pipeline

import android.app.PendingIntent
import android.media.MediaDescription
import android.media.session.MediaSession
import android.service.notification.StatusBarNotification
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData

/** Facilitates management and loading of Media Data, ready for binding. */
interface MediaDataManager {

    /** Add a listener for changes in this class */
    fun addListener(listener: Listener) {}

    /** Remove a listener for changes in this class */
    fun removeListener(listener: Listener) {}

    /**
     * Called whenever the player has been paused or stopped for a while, or swiped from QQS. This
     * will make the player not active anymore, hiding it from QQS and Keyguard.
     *
     * @see MediaData.active
     */
    fun setInactive(key: String, timedOut: Boolean, forceUpdate: Boolean = false)

    /** Invoked when media notification is added. */
    fun onNotificationAdded(key: String, sbn: StatusBarNotification)

    fun destroy()

    /** Sets resume action. */
    fun setResumeAction(key: String, action: Runnable?)

    /** Adds resume media data. */
    fun addResumptionControls(
        userId: Int,
        desc: MediaDescription,
        action: Runnable,
        token: MediaSession.Token,
        appName: String,
        appIntent: PendingIntent,
        packageName: String
    )

    /** Dismiss a media entry. Returns false if the key was not found. */
    fun dismissMediaData(key: String, delay: Long, userInitiated: Boolean): Boolean

    /**
     * Called whenever the recommendation has been expired or removed by the user. This will remove
     * the recommendation card entirely from the carousel.
     */
    fun dismissSmartspaceRecommendation(key: String, delay: Long)

    /** Called when the recommendation card should no longer be visible in QQS or lockscreen */
    fun setRecommendationInactive(key: String)

    /** Invoked when notification is removed. */
    fun onNotificationRemoved(key: String)

    fun setMediaResumptionEnabled(isEnabled: Boolean)

    /** Invoked when the user has dismissed the media carousel */
    fun onSwipeToDismiss()

    /** Are there any media notifications active, including the recommendations? */
    fun hasActiveMediaOrRecommendation(): Boolean

    /** Are there any media entries we should display, including the recommendations? */
    fun hasAnyMediaOrRecommendation(): Boolean

    /** Are there any resume media notifications active, excluding the recommendations? */
    fun hasActiveMedia(): Boolean

    /** Are there any resume media notifications active, excluding the recommendations? */
    fun hasAnyMedia(): Boolean

    /** Is recommendation card active? */
    fun isRecommendationActive(): Boolean

    // Uses [MediaDataProcessor.Listener] in order to link the new logic code with UI layer.
    interface Listener : MediaDataProcessor.Listener {

        /**
         * Called whenever there's new MediaData Loaded for the consumption in views.
         *
         * oldKey is provided to check whether the view has changed keys, which can happen when a
         * player has gone from resume state (key is package name) to active state (key is
         * notification key) or vice versa.
         *
         * @param immediately indicates should apply the UI changes immediately, otherwise wait
         *   until the next refresh-round before UI becomes visible. True by default to take in
         *   place immediately.
         * @param receivedSmartspaceCardLatency is the latency between headphone connects and sysUI
         *   displays Smartspace media targets. Will be 0 if the data is not activated by Smartspace
         *   signal.
         * @param isSsReactivated indicates resume media card is reactivated by Smartspace
         *   recommendation signal
         */
        override fun onMediaDataLoaded(
            key: String,
            oldKey: String?,
            data: MediaData,
            immediately: Boolean,
            receivedSmartspaceCardLatency: Int,
            isSsReactivated: Boolean,
        ) {}

        /**
         * Called whenever there's new Smartspace media data loaded.
         *
         * @param shouldPrioritize indicates the sorting priority of the Smartspace card. If true,
         *   it will be prioritized as the first card. Otherwise, it will show up as the last card
         *   as default.
         */
        override fun onSmartspaceMediaDataLoaded(
            key: String,
            data: SmartspaceMediaData,
            shouldPrioritize: Boolean,
        ) {}

        /** Called whenever a previously existing Media notification was removed. */
        override fun onMediaDataRemoved(key: String, userInitiated: Boolean) {}

        /**
         * Called whenever a previously existing Smartspace media data was removed.
         *
         * @param immediately indicates should apply the UI changes immediately, otherwise wait
         *   until the next refresh-round before UI becomes visible. True by default to take in
         *   place immediately.
         */
        override fun onSmartspaceMediaDataRemoved(key: String, immediately: Boolean) {}
    }

    companion object {

        @JvmStatic
        fun isMediaNotification(sbn: StatusBarNotification): Boolean {
            return sbn.notification.isMediaNotification()
        }
    }
}
