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

package com.android.systemui.controls.management

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewStub
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.systemui.R
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.CurrentUserTracker
import com.android.systemui.util.LifecycleActivity
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Activity to select an application to favorite the [Control] provided by them.
 */
class ControlsProviderSelectorActivity @Inject constructor(
    @Main private val executor: Executor,
    @Background private val backExecutor: Executor,
    private val listingController: ControlsListingController,
    private val controlsController: ControlsController,
    broadcastDispatcher: BroadcastDispatcher
) : LifecycleActivity() {

    companion object {
        private const val TAG = "ControlsProviderSelectorActivity"
    }

    private lateinit var recyclerView: RecyclerView
    private val currentUserTracker = object : CurrentUserTracker(broadcastDispatcher) {
        private val startingUser = listingController.currentUserId

        override fun onUserSwitched(newUserId: Int) {
            if (newUserId != startingUser) {
                stopTracking()
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.controls_management)
        requireViewById<ViewStub>(R.id.stub).apply {
            layoutResource = R.layout.controls_management_apps
            inflate()
        }

        recyclerView = requireViewById(R.id.list)
        recyclerView.adapter = AppAdapter(
                backExecutor,
                executor,
                lifecycle,
                listingController,
                LayoutInflater.from(this),
                ::launchFavoritingActivity,
                FavoritesRenderer(resources, controlsController::countFavoritesForComponent),
                resources)
        recyclerView.layoutManager = LinearLayoutManager(applicationContext)

        requireViewById<TextView>(R.id.title).text =
                resources.getText(R.string.controls_providers_title)

        requireViewById<Button>(R.id.done).setOnClickListener {
            this@ControlsProviderSelectorActivity.finishAffinity()
        }

        currentUserTracker.startTracking()
    }

    /**
     * Launch the [ControlsFavoritingActivity] for the specified component.
     * @param component a component name for a [ControlsProviderService]
     */
    fun launchFavoritingActivity(component: ComponentName?) {
        backExecutor.execute {
            component?.let {
                val intent = Intent(applicationContext, ControlsFavoritingActivity::class.java)
                        .apply {
                    putExtra(ControlsFavoritingActivity.EXTRA_APP,
                            listingController.getAppLabel(it))
                    putExtra(Intent.EXTRA_COMPONENT_NAME, it)
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            }
        }
    }

    override fun onDestroy() {
        currentUserTracker.stopTracking()
        super.onDestroy()
    }
}
