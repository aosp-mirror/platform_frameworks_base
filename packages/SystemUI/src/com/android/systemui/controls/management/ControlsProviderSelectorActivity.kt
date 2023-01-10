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

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.Button
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.systemui.R
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.ui.ControlsActivity
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Activity to select an application to favorite the [Control] provided by them.
 */
open class ControlsProviderSelectorActivity @Inject constructor(
    @Main private val executor: Executor,
    @Background private val backExecutor: Executor,
    private val listingController: ControlsListingController,
    private val controlsController: ControlsController,
    private val userTracker: UserTracker,
    private val uiController: ControlsUiController
) : ComponentActivity() {

    companion object {
        private const val DEBUG = false
        private const val TAG = "ControlsProviderSelectorActivity"
        const val BACK_SHOULD_EXIT = "back_should_exit"
    }
    private var backShouldExit = false
    private lateinit var recyclerView: RecyclerView
    private val userTrackerCallback: UserTracker.Callback = object : UserTracker.Callback {
        private val startingUser = listingController.currentUserId

        override fun onUserChanged(newUser: Int, userContext: Context) {
            if (newUser != startingUser) {
                userTracker.removeCallback(this)
                finish()
            }
        }
    }

    private val mOnBackInvokedCallback = OnBackInvokedCallback {
        if (DEBUG) {
            Log.d(TAG, "Predictive Back dispatcher called mOnBackInvokedCallback")
        }
        onBackPressed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.controls_management)

        getLifecycle().addObserver(
            ControlsAnimations.observerForAnimations(
                requireViewById<ViewGroup>(R.id.controls_management_root),
                window,
                intent
            )
        )

        requireViewById<ViewStub>(R.id.stub).apply {
            layoutResource = R.layout.controls_management_apps
            inflate()
        }

        recyclerView = requireViewById(R.id.list)
        recyclerView.layoutManager = LinearLayoutManager(applicationContext)

        requireViewById<TextView>(R.id.title).apply {
            text = resources.getText(R.string.controls_providers_title)
        }

        requireViewById<Button>(R.id.other_apps).apply {
            visibility = View.VISIBLE
            setText(com.android.internal.R.string.cancel)
            setOnClickListener {
                onBackPressed()
            }
        }
        requireViewById<View>(R.id.done).visibility = View.GONE

        backShouldExit = intent.getBooleanExtra(BACK_SHOULD_EXIT, false)
    }

    override fun onBackPressed() {
        if (!backShouldExit) {
            val i = Intent().apply {
                component = ComponentName(applicationContext, ControlsActivity::class.java)
            }
            startActivity(i, ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
        }
        animateExitAndFinish()
    }

    override fun onStart() {
        super.onStart()
        userTracker.addCallback(userTrackerCallback, executor)

        recyclerView.alpha = 0.0f
        recyclerView.adapter = AppAdapter(
                backExecutor,
                executor,
                lifecycle,
                listingController,
                LayoutInflater.from(this),
                ::launchFavoritingActivity,
                FavoritesRenderer(resources, controlsController::countFavoritesForComponent),
                resources).apply {
            registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                var hasAnimated = false
                override fun onChanged() {
                    if (!hasAnimated) {
                        hasAnimated = true
                        ControlsAnimations.enterAnimation(recyclerView).start()
                    }
                }
            })
        }

        if (DEBUG) {
            Log.d(TAG, "Registered onBackInvokedCallback")
        }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, mOnBackInvokedCallback)
    }

    override fun onStop() {
        super.onStop()
        userTracker.removeCallback(userTrackerCallback)

        if (DEBUG) {
            Log.d(TAG, "Unregistered onBackInvokedCallback")
        }
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(mOnBackInvokedCallback)
    }

    /**
     * Launch the [ControlsFavoritingActivity] for the specified component.
     * @param component a component name for a [ControlsProviderService]
     */
    fun launchFavoritingActivity(component: ComponentName?) {
        executor.execute {
            component?.let {
                val intent = Intent(applicationContext, ControlsFavoritingActivity::class.java)
                        .apply {
                    putExtra(ControlsFavoritingActivity.EXTRA_APP,
                            listingController.getAppLabel(it))
                    putExtra(Intent.EXTRA_COMPONENT_NAME, it)
                    putExtra(ControlsFavoritingActivity.EXTRA_FROM_PROVIDER_SELECTOR, true)
                }
                startActivity(intent, ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
                animateExitAndFinish()
            }
        }
    }

    override fun onDestroy() {
        userTracker.removeCallback(userTrackerCallback)
        super.onDestroy()
    }

    private fun animateExitAndFinish() {
        val rootView = requireViewById<ViewGroup>(R.id.controls_management_root)
        ControlsAnimations.exitAnimation(
                rootView,
                object : Runnable {
                    override fun run() {
                        finish()
                    }
                }
        ).start()
    }
}
