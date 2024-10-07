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

package com.android.systemui.media.controls.domain.resume

import android.annotation.WorkerThread
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaDescription
import android.os.UserHandle
import android.provider.Settings
import android.service.media.MediaBrowserService
import android.util.Log
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.domain.pipeline.RESUME_MEDIA_TIMEOUT
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.util.MediaFlags
import com.android.systemui.settings.UserTracker
import com.android.systemui.tuner.TunerService
import com.android.systemui.util.Utils
import com.android.systemui.util.kotlin.logD
import com.android.systemui.util.time.SystemClock
import java.io.PrintWriter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import javax.inject.Inject

private const val TAG = "MediaResumeListener"

private const val MEDIA_PREFERENCES = "media_control_prefs"
private const val MEDIA_PREFERENCE_KEY = "browser_components_"

@SysUISingleton
class MediaResumeListener
@Inject
constructor(
    private val context: Context,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val userTracker: UserTracker,
    @Main private val mainExecutor: Executor,
    @Background private val backgroundExecutor: Executor,
    private val tunerService: TunerService,
    private val mediaBrowserFactory: ResumeMediaBrowserFactory,
    dumpManager: DumpManager,
    private val systemClock: SystemClock,
    private val mediaFlags: MediaFlags,
) : MediaDataManager.Listener, Dumpable {

    private var useMediaResumption: Boolean = Utils.useMediaResumption(context)
    private val resumeComponents: ConcurrentLinkedQueue<Pair<ComponentName, Long>> =
        ConcurrentLinkedQueue()

    private lateinit var mediaDataManager: MediaDataManager

    private var mediaBrowser: ResumeMediaBrowser? = null
        set(value) {
            // Always disconnect the old browser -- see b/225403871.
            field?.disconnect()
            field = value
        }

    private var currentUserId: Int = context.userId

    @VisibleForTesting
    val userUnlockReceiver =
        object : BroadcastReceiver() {
            @WorkerThread
            override fun onReceive(context: Context, intent: Intent) {
                if (Intent.ACTION_USER_UNLOCKED == intent.action) {
                    val userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1)
                    if (userId == currentUserId) {
                        loadMediaResumptionControls()
                    }
                }
            }
        }

    private val userTrackerCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                currentUserId = newUser
                loadSavedComponents()
            }
        }

    private val mediaBrowserCallback =
        object : ResumeMediaBrowser.Callback() {
            override fun addTrack(
                desc: MediaDescription,
                component: ComponentName,
                browser: ResumeMediaBrowser,
            ) {
                val token = browser.token
                val appIntent = browser.appIntent
                val pm = context.getPackageManager()
                var appName: CharSequence = component.packageName
                val resumeAction = getResumeAction(component)
                try {
                    appName =
                        pm.getApplicationLabel(pm.getApplicationInfo(component.packageName, 0))
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.e(TAG, "Error getting package information", e)
                }

                logD(TAG) { "Adding resume controls for ${browser.userId}: $desc" }
                mediaDataManager.addResumptionControls(
                    browser.userId,
                    desc,
                    resumeAction,
                    token,
                    appName.toString(),
                    appIntent,
                    component.packageName,
                )
            }
        }

    init {
        if (useMediaResumption) {
            dumpManager.registerDumpable(TAG, this)
            val unlockFilter = IntentFilter()
            unlockFilter.addAction(Intent.ACTION_USER_UNLOCKED)
            broadcastDispatcher.registerReceiver(
                userUnlockReceiver,
                unlockFilter,
                backgroundExecutor,
                UserHandle.ALL,
            )
            userTracker.addCallback(userTrackerCallback, mainExecutor)
            loadSavedComponents()
        }
    }

    fun setManager(manager: MediaDataManager) {
        mediaDataManager = manager

        // Add listener for resumption setting changes
        tunerService.addTunable(
            object : TunerService.Tunable {
                override fun onTuningChanged(key: String?, newValue: String?) {
                    useMediaResumption = Utils.useMediaResumption(context)
                    mediaDataManager.setMediaResumptionEnabled(useMediaResumption)
                }
            },
            Settings.Secure.MEDIA_CONTROLS_RESUME,
        )
    }

    private fun loadSavedComponents() {
        // Make sure list is empty (if we switched users)
        resumeComponents.clear()
        val prefs = context.getSharedPreferences(MEDIA_PREFERENCES, Context.MODE_PRIVATE)
        val listString = prefs.getString(MEDIA_PREFERENCE_KEY + currentUserId, null)
        val components =
            listString?.split(ResumeMediaBrowser.DELIMITER.toRegex())?.dropLastWhile {
                it.isEmpty()
            }
        var needsUpdate = false
        components?.forEach {
            val info = it.split("/")
            val packageName = info[0]
            val className = info[1]
            val component = ComponentName(packageName, className)

            val lastPlayed =
                if (info.size == 3) {
                    try {
                        info[2].toLong()
                    } catch (e: NumberFormatException) {
                        needsUpdate = true
                        systemClock.currentTimeMillis()
                    }
                } else {
                    needsUpdate = true
                    systemClock.currentTimeMillis()
                }
            resumeComponents.add(component to lastPlayed)
        }

        logD(TAG) {
            "loaded resume components for $currentUserId: " +
                resumeComponents.toArray().contentToString()
        }

        if (needsUpdate) {
            // Save any missing times that we had to fill in
            writeSharedPrefs()
        }
    }

    /** Load controls for resuming media, if available */
    private fun loadMediaResumptionControls() {
        if (!useMediaResumption) {
            return
        }

        val pm = context.packageManager
        val now = systemClock.currentTimeMillis()
        resumeComponents.forEach {
            if (now.minus(it.second) <= RESUME_MEDIA_TIMEOUT) {
                // Verify that the service exists for this user
                val intent = Intent(MediaBrowserService.SERVICE_INTERFACE)
                intent.component = it.first
                val inf = pm.resolveServiceAsUser(intent, 0, currentUserId)
                if (inf != null) {
                    val browser =
                        mediaBrowserFactory.create(mediaBrowserCallback, it.first, currentUserId)
                    browser.findRecentMedia()
                } else {
                    logD(TAG) { "User $currentUserId does not have component ${it.first}" }
                }
            }
        }
    }

    override fun onMediaDataLoaded(
        key: String,
        oldKey: String?,
        data: MediaData,
        immediately: Boolean,
        receivedSmartspaceCardLatency: Int,
        isSsReactivated: Boolean,
    ) {
        if (useMediaResumption) {
            // If this had been started from a resume state, disconnect now that it's live
            if (!key.equals(oldKey)) {
                mediaBrowser = null
            }
            // If we don't have a resume action, check if we haven't already
            val isEligibleForResume =
                data.isLocalSession() ||
                    (mediaFlags.isRemoteResumeAllowed() &&
                        data.playbackLocation != MediaData.PLAYBACK_CAST_REMOTE)
            if (data.resumeAction == null && !data.hasCheckedForResume && isEligibleForResume) {
                // TODO also check for a media button receiver intended for restarting (b/154127084)
                // Set null action to prevent additional attempts to connect
                backgroundExecutor.execute {
                    mediaDataManager.setResumeAction(key, null)
                    Log.d(TAG, "Checking for service component for " + data.packageName)
                    val pm = context.packageManager
                    val serviceIntent = Intent(MediaBrowserService.SERVICE_INTERFACE)
                    val resumeInfo = pm.queryIntentServicesAsUser(serviceIntent, 0, currentUserId)

                    val inf = resumeInfo?.filter { it.serviceInfo.packageName == data.packageName }
                    if (inf != null && inf.size > 0) {
                        tryUpdateResumptionList(key, inf!!.get(0).componentInfo.componentName)
                    }
                }
            }
        }
    }

    /**
     * Verify that we can connect to the given component with a MediaBrowser, and if so, add that
     * component to the list of resumption components
     */
    private fun tryUpdateResumptionList(key: String, componentName: ComponentName) {
        Log.d(TAG, "Testing if we can connect to $componentName")
        mediaBrowser =
            mediaBrowserFactory.create(
                object : ResumeMediaBrowser.Callback() {
                    override fun onConnected() {
                        logD(TAG) { "Connected to $componentName" }
                    }

                    override fun onError() {
                        Log.e(TAG, "Cannot resume with $componentName")
                        mediaBrowser = null
                    }

                    override fun addTrack(
                        desc: MediaDescription,
                        component: ComponentName,
                        browser: ResumeMediaBrowser,
                    ) {
                        // Since this is a test, just save the component for later
                        logD(TAG) {
                            "Can get resumable media for ${browser.userId} from $componentName"
                        }

                        mediaDataManager.setResumeAction(key, getResumeAction(componentName))
                        updateResumptionList(componentName)
                        mediaBrowser = null
                    }
                },
                componentName,
                currentUserId,
            )
        mediaBrowser?.testConnection()
    }

    /**
     * Add the component to the saved list of media browser services, checking for duplicates and
     * removing older components that exceed the maximum limit
     *
     * @param componentName
     */
    private fun updateResumptionList(componentName: ComponentName) {
        // Remove if exists
        resumeComponents.remove(resumeComponents.find { it.first.equals(componentName) })
        // Insert at front of queue
        val currentTime = systemClock.currentTimeMillis()
        resumeComponents.add(componentName to currentTime)
        // Remove old components if over the limit
        if (resumeComponents.size > ResumeMediaBrowser.MAX_RESUMPTION_CONTROLS) {
            resumeComponents.remove()
        }

        writeSharedPrefs()
    }

    private fun writeSharedPrefs() {
        val sb = StringBuilder()
        resumeComponents.forEach {
            sb.append(it.first.flattenToString())
            sb.append("/")
            sb.append(it.second)
            sb.append(ResumeMediaBrowser.DELIMITER)
        }
        val prefs = context.getSharedPreferences(MEDIA_PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit().putString(MEDIA_PREFERENCE_KEY + currentUserId, sb.toString()).apply()
    }

    /** Get a runnable which will resume media playback */
    private fun getResumeAction(componentName: ComponentName): Runnable {
        return Runnable {
            mediaBrowser = mediaBrowserFactory.create(null, componentName, currentUserId)
            mediaBrowser?.restart()
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.apply { println("resumeComponents: $resumeComponents") }
    }
}
