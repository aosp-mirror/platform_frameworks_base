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

/** Boundary between a View and data pipeline, as seen by the pipeline. */
interface DataListener<in T> {
    fun onDataChanged(data: T)
}

/** Convert all data using the given [mapper] before invoking this [DataListener]. */
fun <S, T> DataListener<T>.contraMap(mapper: (S) -> T): DataListener<S> = object : DataListener<S> {
    override fun onDataChanged(data: S) = onDataChanged(mapper(data))
}

/** Boundary between a View and data pipeline, as seen by the View. */
interface DataSource<out T> {
    fun registerListener(listener: DataListener<T>): Subscription
}

/** Represents a registration with a [DataSource]. */
interface Subscription {
    /** Removes the previously registered [DataListener] from the [DataSource] */
    fun unsubscribe()
}

/** Transform all data coming out of this [DataSource] using the given [mapper]. */
fun <S, T> DataSource<S>.map(mapper: (S) -> T): DataSource<T> = object : DataSource<T> {
    override fun registerListener(listener: DataListener<T>) =
            registerListener(listener.contraMap(mapper))
}