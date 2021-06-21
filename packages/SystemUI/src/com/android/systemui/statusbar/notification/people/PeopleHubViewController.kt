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

package com.android.systemui.statusbar.notification.people

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import javax.inject.Inject

/** Boundary between the View and PeopleHub, as seen by the View. */
interface PeopleHubViewAdapter {
    fun bindView(viewBoundary: PeopleHubViewBoundary): Subscription
}

/** Abstract `View` representation of PeopleHub. */
interface PeopleHubViewBoundary {
    /** View used for animating the activity launch caused by clicking a person in the hub. */
    val associatedViewForClickAnimation: View

    /**
     * [DataListener]s for individual people in the hub.
     *
     * These listeners should be ordered such that the first element will be bound to the most
     * recent person to be added to the hub, and then continuing in descending order. If there are
     * not enough people to satisfy each listener, `null` will be passed instead, indicating that
     * the `View` should render a placeholder.
     */
    val personViewAdapters: Sequence<DataListener<PersonViewModel?>>

    /** Sets the visibility of the Hub in the notification shade. */
    fun setVisible(isVisible: Boolean)
}

/** Creates a [PeopleHubViewModel] given some additional information required from the `View`. */
interface PeopleHubViewModelFactory {

    /**
     * Creates a [PeopleHubViewModel] that, when clicked, starts an activity using an animation
     * involving the given [view].
     */
    fun createWithAssociatedClickView(view: View): PeopleHubViewModel
}

/**
 * Wraps a [PeopleHubViewBoundary] in a [DataListener], and connects it to the data
 * pipeline.
 *
 * @param dataSource PeopleHub data pipeline.
 */
@SysUISingleton
class PeopleHubViewAdapterImpl @Inject constructor(
    private val dataSource: DataSource<@JvmSuppressWildcards PeopleHubViewModelFactory>
) : PeopleHubViewAdapter {

    override fun bindView(viewBoundary: PeopleHubViewBoundary): Subscription =
            dataSource.registerListener(PeopleHubDataListenerImpl(viewBoundary))
}

private class PeopleHubDataListenerImpl(
    private val viewBoundary: PeopleHubViewBoundary
) : DataListener<PeopleHubViewModelFactory> {

    override fun onDataChanged(data: PeopleHubViewModelFactory) {
        val viewModel = data.createWithAssociatedClickView(
                viewBoundary.associatedViewForClickAnimation
        )
        viewBoundary.setVisible(viewModel.isVisible)
        val padded = viewModel.people + repeated(null)
        for ((adapter, model) in viewBoundary.personViewAdapters.zip(padded)) {
            adapter.onDataChanged(model)
        }
    }
}

/**
 * Converts [PeopleHubModel]s into [PeopleHubViewModelFactory]s.
 *
 * This class serves as the glue between the View layer (which depends on
 * [PeopleHubViewBoundary]) and the Data layer (which produces [PeopleHubModel]s).
 */
@SysUISingleton
class PeopleHubViewModelFactoryDataSourceImpl @Inject constructor(
    private val activityStarter: ActivityStarter,
    private val dataSource: DataSource<@JvmSuppressWildcards PeopleHubModel>
) : DataSource<PeopleHubViewModelFactory> {

    override fun registerListener(listener: DataListener<PeopleHubViewModelFactory>): Subscription {
        var model: PeopleHubModel? = null

        fun updateListener() {
            // don't invoke listener until we've received our first model
            model?.let { model ->
                val factory = PeopleHubViewModelFactoryImpl(model, activityStarter)
                listener.onDataChanged(factory)
            }
        }
        val dataSub = dataSource.registerListener(object : DataListener<PeopleHubModel> {
            override fun onDataChanged(data: PeopleHubModel) {
                model = data
                updateListener()
            }
        })
        return object : Subscription {
            override fun unsubscribe() {
                dataSub.unsubscribe()
            }
        }
    }
}

private object EmptyViewModelFactory : PeopleHubViewModelFactory {
    override fun createWithAssociatedClickView(view: View): PeopleHubViewModel {
        return PeopleHubViewModel(emptySequence(), false)
    }
}

private class PeopleHubViewModelFactoryImpl(
    private val model: PeopleHubModel,
    private val activityStarter: ActivityStarter
) : PeopleHubViewModelFactory {

    override fun createWithAssociatedClickView(view: View): PeopleHubViewModel {
        val personViewModels = model.people.asSequence().map { personModel ->
            val onClick = {
                personModel.clickRunnable.run()
            }
            PersonViewModel(personModel.name, personModel.avatar, onClick)
        }
        return PeopleHubViewModel(personViewModels, model.people.isNotEmpty())
    }
}

@SysUISingleton
class PeopleHubSettingChangeDataSourceImpl @Inject constructor(
    @Main private val handler: Handler,
    context: Context
) : DataSource<Boolean> {

    private val settingUri = Settings.Secure.getUriFor(Settings.Secure.PEOPLE_STRIP)
    private val contentResolver = context.contentResolver

    override fun registerListener(listener: DataListener<Boolean>): Subscription {
        // Immediately report current value of setting
        updateListener(listener)
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?, flags: Int) {
                super.onChange(selfChange, uri, flags)
                updateListener(listener)
            }
        }
        contentResolver.registerContentObserver(settingUri, false, observer, UserHandle.USER_ALL)
        return object : Subscription {
            override fun unsubscribe() = contentResolver.unregisterContentObserver(observer)
        }
    }

    private fun updateListener(listener: DataListener<Boolean>) {
        val setting = Settings.Secure.getIntForUser(
                contentResolver,
                Settings.Secure.PEOPLE_STRIP,
                0,
                UserHandle.USER_CURRENT
        )
        listener.onDataChanged(setting != 0)
    }
}

private fun <T> repeated(value: T): Sequence<T> = sequence {
    while (true) {
        yield(value)
    }
}