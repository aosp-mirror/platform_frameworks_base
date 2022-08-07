/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.flags

import android.annotation.BoolRes
import android.annotation.IntegerRes
import android.annotation.StringRes
import android.os.Parcel
import android.os.Parcelable

interface Flag<T> {
    val id: Int
    val teamfood: Boolean
}

interface ParcelableFlag<T> : Flag<T>, Parcelable {
    val default: T
    val overridden: Boolean
    override fun describeContents() = 0
}

interface ResourceFlag<T> : Flag<T> {
    val resourceId: Int
}

interface DeviceConfigFlag<T> : Flag<T> {
    val name: String
    val namespace: String
    val default: T
}

interface SysPropFlag<T> : Flag<T> {
    val name: String
    val default: T
}

/**
 * Base class for most common boolean flags.
 *
 * See [UnreleasedFlag] and [ReleasedFlag] for useful implementations.
 */
// Consider using the "parcelize" kotlin library.
abstract class BooleanFlag constructor(
    override val id: Int,
    override val default: Boolean = false,
    override val teamfood: Boolean = false,
    override val overridden: Boolean = false
) : ParcelableFlag<Boolean> {

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<BooleanFlag> {
            override fun createFromParcel(parcel: Parcel) = object : BooleanFlag(parcel) {}
            override fun newArray(size: Int) = arrayOfNulls<BooleanFlag>(size)
        }
    }

    private constructor(parcel: Parcel) : this(
        id = parcel.readInt(),
        default = parcel.readBoolean(),
        teamfood = parcel.readBoolean(),
        overridden = parcel.readBoolean()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(id)
        parcel.writeBoolean(default)
        parcel.writeBoolean(teamfood)
        parcel.writeBoolean(overridden)
    }
}

/**
 * A Flag that is is false by default.
 *
 * It can be changed or overridden in debug builds but not in release builds.
 */
data class UnreleasedFlag @JvmOverloads constructor(
    override val id: Int,
    override val teamfood: Boolean = false,
    override val overridden: Boolean = false
) : BooleanFlag(id, false, teamfood, overridden)

/**
 * A Flag that is is true by default.
 *
 * It can be changed or overridden in any build, meaning it can be turned off if needed.
 */
data class ReleasedFlag @JvmOverloads constructor(
    override val id: Int,
    override val teamfood: Boolean = false,
    override val overridden: Boolean = false
) : BooleanFlag(id, true, teamfood, overridden)

/**
 * A Flag that reads its default values from a resource overlay instead of code.
 *
 * Prefer [UnreleasedFlag] and [ReleasedFlag].
 */
data class ResourceBooleanFlag @JvmOverloads constructor(
    override val id: Int,
    @BoolRes override val resourceId: Int,
    override val teamfood: Boolean = false
) : ResourceFlag<Boolean>

/**
 * A Flag that can reads its overrides from DeviceConfig.
 *
 * This is generally useful for flags that come from or are used _outside_ of SystemUI.
 *
 * Prefer [UnreleasedFlag] and [ReleasedFlag].
 */
data class DeviceConfigBooleanFlag @JvmOverloads constructor(
    override val id: Int,
    override val name: String,
    override val namespace: String,
    override val default: Boolean = false,
    override val teamfood: Boolean = false
) : DeviceConfigFlag<Boolean>

/**
 * A Flag that can reads its overrides from System Properties.
 *
 * This is generally useful for flags that come from or are used _outside_ of SystemUI.
 *
 * Prefer [UnreleasedFlag] and [ReleasedFlag].
 */
data class SysPropBooleanFlag @JvmOverloads constructor(
    override val id: Int,
    override val name: String,
    override val default: Boolean = false
) : SysPropFlag<Boolean> {
    // TODO(b/223379190): Teamfood not supported for sysprop flags yet.
    override val teamfood: Boolean = false
}

data class StringFlag @JvmOverloads constructor(
    override val id: Int,
    override val default: String = "",
    override val teamfood: Boolean = false,
    override val overridden: Boolean = false
) : ParcelableFlag<String> {
    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<StringFlag> {
            override fun createFromParcel(parcel: Parcel) = StringFlag(parcel)
            override fun newArray(size: Int) = arrayOfNulls<StringFlag>(size)
        }
    }

    private constructor(parcel: Parcel) : this(
        id = parcel.readInt(),
        default = parcel.readString() ?: ""
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(id)
        parcel.writeString(default)
    }
}

data class ResourceStringFlag @JvmOverloads constructor(
    override val id: Int,
    @StringRes override val resourceId: Int,
    override val teamfood: Boolean = false
) : ResourceFlag<String>

data class IntFlag @JvmOverloads constructor(
    override val id: Int,
    override val default: Int = 0,
    override val teamfood: Boolean = false,
    override val overridden: Boolean = false
) : ParcelableFlag<Int> {

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<IntFlag> {
            override fun createFromParcel(parcel: Parcel) = IntFlag(parcel)
            override fun newArray(size: Int) = arrayOfNulls<IntFlag>(size)
        }
    }

    private constructor(parcel: Parcel) : this(
        id = parcel.readInt(),
        default = parcel.readInt()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(id)
        parcel.writeInt(default)
    }
}

data class ResourceIntFlag @JvmOverloads constructor(
    override val id: Int,
    @IntegerRes override val resourceId: Int,
    override val teamfood: Boolean = false
) : ResourceFlag<Int>

data class LongFlag @JvmOverloads constructor(
    override val id: Int,
    override val default: Long = 0,
    override val teamfood: Boolean = false,
    override val overridden: Boolean = false
) : ParcelableFlag<Long> {

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<LongFlag> {
            override fun createFromParcel(parcel: Parcel) = LongFlag(parcel)
            override fun newArray(size: Int) = arrayOfNulls<LongFlag>(size)
        }
    }

    private constructor(parcel: Parcel) : this(
        id = parcel.readInt(),
        default = parcel.readLong()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(id)
        parcel.writeLong(default)
    }
}

data class FloatFlag @JvmOverloads constructor(
    override val id: Int,
    override val default: Float = 0f,
    override val teamfood: Boolean = false,
    override val overridden: Boolean = false
) : ParcelableFlag<Float> {

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<FloatFlag> {
            override fun createFromParcel(parcel: Parcel) = FloatFlag(parcel)
            override fun newArray(size: Int) = arrayOfNulls<FloatFlag>(size)
        }
    }

    private constructor(parcel: Parcel) : this(
        id = parcel.readInt(),
        default = parcel.readFloat()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(id)
        parcel.writeFloat(default)
    }
}

data class ResourceFloatFlag @JvmOverloads constructor(
    override val id: Int,
    override val resourceId: Int,
    override val teamfood: Boolean = false
) : ResourceFlag<Int>

data class DoubleFlag @JvmOverloads constructor(
    override val id: Int,
    override val default: Double = 0.0,
    override val teamfood: Boolean = false,
    override val overridden: Boolean = false
) : ParcelableFlag<Double> {

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<DoubleFlag> {
            override fun createFromParcel(parcel: Parcel) = DoubleFlag(parcel)
            override fun newArray(size: Int) = arrayOfNulls<DoubleFlag>(size)
        }
    }

    private constructor(parcel: Parcel) : this(
        id = parcel.readInt(),
        default = parcel.readDouble()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(id)
        parcel.writeDouble(default)
    }
}
