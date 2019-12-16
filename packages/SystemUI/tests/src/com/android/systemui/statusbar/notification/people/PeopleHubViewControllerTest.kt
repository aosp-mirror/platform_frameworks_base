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

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Drawable
import android.testing.AndroidTestingRunner
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.ActivityStarter
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import kotlin.reflect.KClass

@SmallTest
@RunWith(AndroidTestingRunner::class)
class PeopleHubViewControllerTest : SysuiTestCase() {

    @JvmField @Rule val mockito: MockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var mockViewBoundary: PeopleHubSectionFooterViewBoundary
    @Mock private lateinit var mockActivityStarter: ActivityStarter

    @Test
    fun testBindViewModelToViewBoundary() {
        val fakePerson1 = fakePersonViewModel("name")
        val fakeViewModel = PeopleHubViewModel(sequenceOf(fakePerson1), true)
        val fakePersonViewAdapter1 = FakeDataListener<PersonViewModel?>()
        val fakePersonViewAdapter2 = FakeDataListener<PersonViewModel?>()
        val mockClickView = mock(View::class.java)
        `when`(mockViewBoundary.associatedViewForClickAnimation).thenReturn(mockClickView)
        `when`(mockViewBoundary.personViewAdapters)
                .thenReturn(sequenceOf(fakePersonViewAdapter1, fakePersonViewAdapter2))
        val mockFactory = mock(PeopleHubViewModelFactory::class.java)
        `when`(mockFactory.createWithAssociatedClickView(any())).thenReturn(fakeViewModel)
        val mockSubscription = mock(Subscription::class.java)
        val fakeFactoryDataSource = object : DataSource<PeopleHubViewModelFactory> {
            override fun registerListener(
                listener: DataListener<PeopleHubViewModelFactory>
            ): Subscription {
                listener.onDataChanged(mockFactory)
                return mockSubscription
            }
        }
        val adapter = PeopleHubSectionFooterViewAdapterImpl(fakeFactoryDataSource)

        adapter.bindView(mockViewBoundary)

        assertThat(fakePersonViewAdapter1.lastSeen).isEqualTo(Maybe.Just(fakePerson1))
        assertThat(fakePersonViewAdapter2.lastSeen).isEqualTo(Maybe.Just<PersonViewModel?>(null))
        verify(mockViewBoundary).setVisible(true)
        verify(mockFactory).createWithAssociatedClickView(mockClickView)
    }

    @Test
    fun testViewModelDataSourceTransformsModel() {
        val fakeClickIntent = PendingIntent.getActivity(context, 0, Intent("action"), 0)
        val fakePerson = fakePersonModel("id", "name", fakeClickIntent)
        val fakeModel = PeopleHubModel(listOf(fakePerson))
        val mockSubscription = mock(Subscription::class.java)
        val fakeModelDataSource = object : DataSource<PeopleHubModel> {
            override fun registerListener(listener: DataListener<PeopleHubModel>): Subscription {
                listener.onDataChanged(fakeModel)
                return mockSubscription
            }
        }
        val fakeSettingDataSource = object : DataSource<Boolean> {
            override fun registerListener(listener: DataListener<Boolean>): Subscription {
                listener.onDataChanged(true)
                return mockSubscription
            }
        }
        val factoryDataSource = PeopleHubViewModelFactoryDataSourceImpl(
                mockActivityStarter,
                fakeModelDataSource,
                fakeSettingDataSource
        )
        val fakeListener = FakeDataListener<PeopleHubViewModelFactory>()
        val mockClickView = mock(View::class.java)

        factoryDataSource.registerListener(fakeListener)

        val viewModel = (fakeListener.lastSeen as Maybe.Just).value
                .createWithAssociatedClickView(mockClickView)
        assertThat(viewModel.isVisible).isTrue()
        val people = viewModel.people.toList()
        assertThat(people.size).isEqualTo(1)
        assertThat(people[0].name).isEqualTo("name")
        assertThat(people[0].icon).isSameAs(fakePerson.avatar)

        people[0].onClick()

        verify(mockActivityStarter).startPendingIntentDismissingKeyguard(
                same(fakeClickIntent),
                any(),
                same(mockClickView)
        )
    }

    @Test
    fun testViewModelDataSource_notVisibleIfSettingDisabled() {
        val fakeClickIntent = PendingIntent.getActivity(context, 0, Intent("action"), 0)
        val fakePerson = fakePersonModel("id", "name", fakeClickIntent)
        val fakeModel = PeopleHubModel(listOf(fakePerson))
        val mockSubscription = mock(Subscription::class.java)
        val fakeModelDataSource = object : DataSource<PeopleHubModel> {
            override fun registerListener(listener: DataListener<PeopleHubModel>): Subscription {
                listener.onDataChanged(fakeModel)
                return mockSubscription
            }
        }
        val fakeSettingDataSource = object : DataSource<Boolean> {
            override fun registerListener(listener: DataListener<Boolean>): Subscription {
                listener.onDataChanged(false)
                return mockSubscription
            }
        }
        val factoryDataSource = PeopleHubViewModelFactoryDataSourceImpl(
                mockActivityStarter,
                fakeModelDataSource,
                fakeSettingDataSource
        )
        val fakeListener = FakeDataListener<PeopleHubViewModelFactory>()
        val mockClickView = mock(View::class.java)

        factoryDataSource.registerListener(fakeListener)

        val viewModel = (fakeListener.lastSeen as Maybe.Just).value
                .createWithAssociatedClickView(mockClickView)
        assertThat(viewModel.isVisible).isFalse()
        val people = viewModel.people.toList()
        assertThat(people.size).isEqualTo(0)
    }
}

/** Works around Mockito matchers returning `null` and breaking non-nullable Kotlin code. */
private inline fun <reified T : Any> any(): T {
    return Mockito.any() ?: createInstance(T::class)
}

/** Works around Mockito matchers returning `null` and breaking non-nullable Kotlin code. */
private inline fun <reified T : Any> same(value: T): T {
    return Mockito.same(value) ?: createInstance(T::class)
}

/** Creates an instance of the given class. */
private fun <T : Any> createInstance(clazz: KClass<T>): T = castNull()

/** Tricks the Kotlin compiler into assigning `null` to a non-nullable variable. */
@Suppress("UNCHECKED_CAST")
private fun <T> castNull(): T = null as T

private fun fakePersonModel(
    id: String,
    name: CharSequence,
    clickIntent: PendingIntent
): PersonModel =
        PersonModel(id, name, mock(Drawable::class.java), clickIntent)

private fun fakePersonViewModel(name: CharSequence): PersonViewModel =
        PersonViewModel(name, mock(Drawable::class.java), mock({}.javaClass))

sealed class Maybe<T> {
    data class Just<T>(val value: T) : Maybe<T>()
    class Nothing<T> : Maybe<T>() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            return true
        }

        override fun hashCode(): Int {
            return javaClass.hashCode()
        }
    }
}

class FakeDataListener<T> : DataListener<T> {

    var lastSeen: Maybe<T> = Maybe.Nothing()

    override fun onDataChanged(data: T) {
        lastSeen = Maybe.Just(data)
    }
}