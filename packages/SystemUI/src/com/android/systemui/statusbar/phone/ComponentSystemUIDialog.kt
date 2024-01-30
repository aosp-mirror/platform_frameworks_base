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

package com.android.systemui.statusbar.phone

import android.annotation.CallSuper
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.model.SysUiState

/**
 * A [SystemUIDialog] that implements [LifecycleOwner], [SavedStateRegistryOwner] and
 * [OnBackPressedDispatcherOwner].
 *
 * This class was forked from [androidx.activity.ComponentDialog] and can be used to easily create
 * SystemUI dialogs without the need to subclass [SystemUIDialog]. You should call
 * [SystemUIDialogFactory.create] to easily instantiate this class.
 *
 * Important: [ComponentSystemUIDialog] should be created and shown on the main thread.
 *
 * @see SystemUIDialogFactory.create
 */
class ComponentSystemUIDialog(
    context: Context,
    theme: Int,
    dismissOnDeviceLock: Boolean,
    featureFlags: FeatureFlags,
    dialogManager: SystemUIDialogManager,
    sysUiState: SysUiState,
    broadcastDispatcher: BroadcastDispatcher,
    dialogLaunchAnimator: DialogLaunchAnimator,
) :
    SystemUIDialog(
        context,
        theme,
        dismissOnDeviceLock,
        featureFlags,
        dialogManager,
        sysUiState,
        broadcastDispatcher,
        dialogLaunchAnimator
    ),
    LifecycleOwner,
    SavedStateRegistryOwner,
    OnBackPressedDispatcherOwner {
    private var _lifecycleRegistry: LifecycleRegistry? = null
    private val lifecycleRegistry: LifecycleRegistry
        get() = _lifecycleRegistry ?: LifecycleRegistry(this).also { _lifecycleRegistry = it }

    private val savedStateRegistryController: SavedStateRegistryController =
        SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onSaveInstanceState(): Bundle {
        val bundle = super.onSaveInstanceState()
        savedStateRegistryController.performSave(bundle)
        return bundle
    }

    @CallSuper
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.setOnBackInvokedDispatcher(onBackInvokedDispatcher)
        savedStateRegistryController.performRestore(savedInstanceState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    @CallSuper
    override fun start() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    @CallSuper
    override fun stop() {
        // This is the closest thing to onDestroy that a Dialog has
        // TODO(b/296180426): Make SystemUIDialog.onStop() and onStart() open again (annotated with
        // @CallSuper) and do this *before* calling super.onStop(), like AndroidX ComponentDialog
        // does.
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        _lifecycleRegistry = null
    }

    @Suppress("DEPRECATION")
    override val onBackPressedDispatcher = OnBackPressedDispatcher { super.onBackPressed() }

    @CallSuper
    override fun onBackPressed() {
        onBackPressedDispatcher.onBackPressed()
    }

    override fun setContentView(layoutResID: Int) {
        initializeViewTreeOwners()
        super.setContentView(layoutResID)
    }

    override fun setContentView(view: View) {
        initializeViewTreeOwners()
        super.setContentView(view)
    }

    override fun setContentView(view: View, params: ViewGroup.LayoutParams?) {
        initializeViewTreeOwners()
        super.setContentView(view, params)
    }

    override fun addContentView(view: View, params: ViewGroup.LayoutParams?) {
        initializeViewTreeOwners()
        super.addContentView(view, params)
    }

    /**
     * Sets the view tree owners before setting the content view so that the inflation process and
     * attach listeners will see them already present.
     */
    @CallSuper
    open fun initializeViewTreeOwners() {
        window!!.decorView.setViewTreeLifecycleOwner(this)
        window!!.decorView.setViewTreeOnBackPressedDispatcherOwner(this)
        window!!.decorView.setViewTreeSavedStateRegistryOwner(this)
    }
}
