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

package com.android.systemui.communal.shared.model

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Parcel
import android.os.Parcelable
import android.os.UserHandle

/** Encapsulates data for a communal widget. */
sealed interface CommunalWidgetContentModel : Parcelable {
    val appWidgetId: Int
    val rank: Int
    val spanY: Int

    // Used for distinguishing subtypes when reading from a parcel.
    val type: Int

    /** Widget is ready to display */
    data class Available(
        override val appWidgetId: Int,
        val providerInfo: AppWidgetProviderInfo,
        override val rank: Int,
        override val spanY: Int,
    ) : CommunalWidgetContentModel {

        override val type = TYPE_AVAILABLE

        constructor(
            parcel: Parcel
        ) : this(
            parcel.readInt(),
            requireNotNull(parcel.readTypedObject(AppWidgetProviderInfo.CREATOR)),
            parcel.readInt(),
            parcel.readInt(),
        )

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(type)
            parcel.writeInt(appWidgetId)
            parcel.writeTypedObject(providerInfo, flags)
            parcel.writeInt(rank)
            parcel.writeInt(spanY)
        }

        override fun describeContents(): Int {
            return 0
        }

        companion object CREATOR : Parcelable.Creator<Available> {
            override fun createFromParcel(parcel: Parcel): Available {
                return Available(parcel)
            }

            override fun newArray(size: Int): Array<Available?> {
                return arrayOfNulls(size)
            }
        }
    }

    /** Widget is pending installation */
    data class Pending(
        override val appWidgetId: Int,
        override val rank: Int,
        val componentName: ComponentName,
        val icon: Bitmap?,
        val user: UserHandle,
        override val spanY: Int,
    ) : CommunalWidgetContentModel {

        override val type = TYPE_PENDING

        constructor(
            parcel: Parcel
        ) : this(
            parcel.readInt(),
            parcel.readInt(),
            requireNotNull(parcel.readTypedObject(ComponentName.CREATOR)),
            parcel.readTypedObject(Bitmap.CREATOR),
            requireNotNull(parcel.readTypedObject(UserHandle.CREATOR)),
            parcel.readInt(),
        )

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(type)
            parcel.writeInt(appWidgetId)
            parcel.writeInt(rank)
            parcel.writeTypedObject(componentName, flags)
            parcel.writeTypedObject(icon, flags)
            parcel.writeTypedObject(user, flags)
            parcel.writeInt(spanY)
        }

        override fun describeContents(): Int {
            return 0
        }

        companion object CREATOR : Parcelable.Creator<Pending> {
            override fun createFromParcel(parcel: Parcel): Pending {
                return Pending(parcel)
            }

            override fun newArray(size: Int): Array<Pending?> {
                return arrayOfNulls(size)
            }
        }
    }

    // Used for distinguishing subtypes when reading from a parcel.
    companion object CREATOR : Parcelable.Creator<CommunalWidgetContentModel> {
        private const val TYPE_AVAILABLE = 0
        private const val TYPE_PENDING = 1

        override fun createFromParcel(parcel: Parcel): CommunalWidgetContentModel {
            return when (val type = parcel.readInt()) {
                TYPE_AVAILABLE -> Available(parcel)
                TYPE_PENDING -> Pending(parcel)
                else -> throw IllegalArgumentException("Unknown type: $type")
            }
        }

        override fun newArray(size: Int): Array<CommunalWidgetContentModel?> {
            return arrayOfNulls(size)
        }
    }
}
