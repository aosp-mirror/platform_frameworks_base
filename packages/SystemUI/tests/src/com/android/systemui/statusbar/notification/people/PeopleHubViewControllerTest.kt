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
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import kotlin.reflect.KClass
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class PeopleHubViewControllerTest : SysuiTestCase() {

    @JvmField @Rule val mockito: MockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var mockViewBoundary: PeopleHubViewBoundary
    @Mock private lateinit var mockActivityStarter: ActivityStarter

    @Test
    fun testBindViewModelToViewBoundary() {
        val fakePerson1 = fakePersonViewModel("name")
        val fakeViewModel = PeopleHubViewModel(sequenceOf(fakePerson1), true)

        val mockFactory = mock(PeopleHubViewModelFactory::class.java)
        whenever(mockFactory.createWithAssociatedClickView(any())).thenReturn(fakeViewModel)

        val mockClickView = mock(View::class.java)
        whenever(mockViewBoundary.associatedViewForClickAnimation).thenReturn(mockClickView)

        val fakePersonViewAdapter1 = FakeDataListener<PersonViewModel?>()
        val fakePersonViewAdapter2 = FakeDataListener<PersonViewModel?>()
        whenever(mockViewBoundary.personViewAdapters)
                .thenReturn(sequenceOf(fakePersonViewAdapter1, fakePersonViewAdapter2))

        val adapter = PeopleHubViewAdapterImpl(FakeDataSource(mockFactory))

        adapter.bindView(mockViewBoundary)

        assertThat(fakePersonViewAdapter1.lastSeen).isEqualTo(Maybe.Just(fakePerson1))
        assertThat(fakePersonViewAdapter2.lastSeen).isEqualTo(Maybe.Just<PersonViewModel?>(null))
        verify(mockViewBoundary).setVisible(true)
        verify(mockFactory).createWithAssociatedClickView(mockClickView)
    }

    @Test
    fun testBindViewModelToViewBoundary_moreDataThanCanBeDisplayed_displaysMostRecent() {
        val fakePerson1 = fakePersonViewModel("person1")
        val fakePerson2 = fakePersonViewModel("person2")
        val fakePerson3 = fakePersonViewModel("person3")
        val fakePeople = sequenceOf(fakePerson3, fakePerson2, fakePerson1)
        val fakeViewModel = PeopleHubViewModel(fakePeople, true)

        val mockFactory = mock(PeopleHubViewModelFactory::class.java)
        whenever(mockFactory.createWithAssociatedClickView(any())).thenReturn(fakeViewModel)

        whenever(mockViewBoundary.associatedViewForClickAnimation)
                .thenReturn(mock(View::class.java))

        val fakePersonViewAdapter1 = FakeDataListener<PersonViewModel?>()
        val fakePersonViewAdapter2 = FakeDataListener<PersonViewModel?>()
        whenever(mockViewBoundary.personViewAdapters)
                .thenReturn(sequenceOf(fakePersonViewAdapter1, fakePersonViewAdapter2))

        val adapter = PeopleHubViewAdapterImpl(FakeDataSource(mockFactory))

        adapter.bindView(mockViewBoundary)

        assertThat(fakePersonViewAdapter1.lastSeen).isEqualTo(Maybe.Just(fakePerson3))
        assertThat(fakePersonViewAdapter2.lastSeen).isEqualTo(Maybe.Just(fakePerson2))
    }

    @Test
    fun testViewModelDataSourceTransformsModel() {
        val fakeClickRunnable = mock(Runnable::class.java)
        val fakePerson = fakePersonModel("id", "name", fakeClickRunnable)
        val fakeModel = PeopleHubModel(listOf(fakePerson))
        val fakeModelDataSource = FakeDataSource(fakeModel)
        val factoryDataSource = PeopleHubViewModelFactoryDataSourceImpl(
                mockActivityStarter,
                fakeModelDataSource
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
        assertThat(people[0].icon).isSameInstanceAs(fakePerson.avatar)

        people[0].onClick()

        verify(fakeClickRunnable).run()
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
    clickRunnable: Runnable,
    userId: Int = 0
): PersonModel =
        PersonModel(id, userId, name, mock(Drawable::class.java), clickRunnable)

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
