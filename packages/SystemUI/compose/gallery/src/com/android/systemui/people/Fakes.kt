/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.people

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import androidx.core.graphics.drawable.toIcon
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.people.data.model.PeopleTileModel
import com.android.systemui.people.ui.viewmodel.PeopleViewModel
import com.android.systemui.people.widget.PeopleTileKey

/** A [PeopleViewModel] that does not have any conversations. */
fun emptyPeopleSpaceViewModel(@Application context: Context): PeopleViewModel {
    return fakePeopleSpaceViewModel(context, emptyList(), emptyList())
}

/** A [PeopleViewModel] that has a few conversations. */
fun fewPeopleSpaceViewModel(@Application context: Context): PeopleViewModel {
    return fakePeopleSpaceViewModel(
        context,
        priorityTiles =
            listOf(
                fakeTile(context, id = "0", Color.RED, "Priority"),
                fakeTile(context, id = "1", Color.BLUE, "Priority NewStory", hasNewStory = true),
            ),
        recentTiles =
            listOf(
                fakeTile(context, id = "2", Color.GREEN, "Recent Important", isImportant = true),
                fakeTile(context, id = "3", Color.CYAN, "Recent DndBlocking", isDndBlocking = true),
            ),
    )
}

/** A [PeopleViewModel] that has a lot of conversations. */
fun fullPeopleSpaceViewModel(@Application context: Context): PeopleViewModel {
    return fakePeopleSpaceViewModel(
        context,
        priorityTiles =
            listOf(
                fakeTile(context, id = "0", Color.RED, "Priority"),
                fakeTile(context, id = "1", Color.BLUE, "Priority NewStory", hasNewStory = true),
                fakeTile(context, id = "2", Color.GREEN, "Priority Important", isImportant = true),
                fakeTile(
                    context,
                    id = "3",
                    Color.CYAN,
                    "Priority DndBlocking",
                    isDndBlocking = true,
                ),
                fakeTile(
                    context,
                    id = "4",
                    Color.MAGENTA,
                    "Priority NewStory Important",
                    hasNewStory = true,
                    isImportant = true,
                ),
            ),
        recentTiles =
            listOf(
                fakeTile(
                    context,
                    id = "5",
                    Color.RED,
                    "Recent NewStory DndBlocking",
                    hasNewStory = true,
                    isDndBlocking = true,
                ),
                fakeTile(
                    context,
                    id = "6",
                    Color.BLUE,
                    "Recent Important DndBlocking",
                    isImportant = true,
                    isDndBlocking = true,
                ),
                fakeTile(
                    context,
                    id = "7",
                    Color.GREEN,
                    "Recent NewStory Important DndBlocking",
                    hasNewStory = true,
                    isImportant = true,
                    isDndBlocking = true,
                ),
                fakeTile(context, id = "8", Color.CYAN, "Recent"),
                fakeTile(context, id = "9", Color.MAGENTA, "Recent"),
            ),
    )
}

private fun fakePeopleSpaceViewModel(
    @Application context: Context,
    priorityTiles: List<PeopleTileModel>,
    recentTiles: List<PeopleTileModel>,
): PeopleViewModel {
    return PeopleViewModel(
        context,
        FakePeopleTileRepository(priorityTiles, recentTiles),
        FakePeopleWidgetRepository(),
    )
}

private fun fakeTile(
    @Application context: Context,
    id: String,
    iconColor: Int,
    username: String,
    hasNewStory: Boolean = false,
    isImportant: Boolean = false,
    isDndBlocking: Boolean = false
): PeopleTileModel {
    return PeopleTileModel(
        PeopleTileKey(id, /* userId= */ 0, /* packageName */ ""),
        username,
        fakeUserIcon(context, iconColor),
        hasNewStory,
        isImportant,
        isDndBlocking,
    )
}

private fun fakeUserIcon(@Application context: Context, color: Int): Icon {
    val size = context.resources.getDimensionPixelSize(R.dimen.avatar_size_for_medium)
    val bitmap =
        Bitmap.createBitmap(
            size,
            size,
            Bitmap.Config.ARGB_8888,
        )
    val canvas = Canvas(bitmap)
    val paint = Paint().apply { this.color = color }
    val radius = size / 2f
    canvas.drawCircle(/* cx= */ radius, /* cy= */ radius, /* radius= */ radius, paint)
    return bitmap.toIcon()
}
