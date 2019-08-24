/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.egg.quares

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Parcel
import android.os.Parcelable
import java.util.ArrayList
import kotlin.math.abs
import kotlin.math.round

class Quare(val width: Int, val height: Int, val depth: Int) : Parcelable {
    private val data: IntArray = IntArray(width * height)
    private val user: IntArray = data.copyOf()

    private fun loadAndQuantize(bitmap8bpp: Bitmap) {
        bitmap8bpp.getPixels(data, 0, width, 0, 0, width, height)
        if (depth == 8) return
        val s = (255f / depth)
        for (i in 0 until data.size) {
            var f = (data[i] ushr 24).toFloat() / s
            // f = f.pow(0.75f) // gamma adjust for bolder lines
            f *= 1.25f // brightness adjust for bolder lines
            f.coerceAtMost(1f)
            data[i] = (round(f) * s).toInt() shl 24
        }
    }

    fun isBlank(): Boolean {
        return data.sum() == 0
    }

    fun load(drawable: Drawable) {
        val resized = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(resized)
        drawable.setBounds(0, 0, width, height)
        drawable.setTint(0xFF000000.toInt())
        drawable.draw(canvas)
        loadAndQuantize(resized)
        resized.recycle()
    }

    fun load(context: Context, icon: Icon) {
        icon.loadDrawable(context)?.let {
            load(it)
        }
    }

    fun bitmap(): Bitmap {
        return Bitmap.createBitmap(data, width, height, Bitmap.Config.ALPHA_8)
    }

    fun getUserMark(x: Int, y: Int): Int {
        return user[y * width + x] ushr 24
    }

    fun setUserMark(x: Int, y: Int, v: Int) {
        user[y * width + x] = v shl 24
    }

    fun getDataAt(x: Int, y: Int): Int {
        return data[y * width + x] ushr 24
    }

    fun check(): Boolean {
        return data.contentEquals(user)
    }

    fun check(xSel: Int, ySel: Int): Boolean {
        val xStart = if (xSel < 0) 0 else xSel
        val xEnd = if (xSel < 0) width - 1 else xSel
        val yStart = if (ySel < 0) 0 else ySel
        val yEnd = if (ySel < 0) height - 1 else ySel
        for (y in yStart..yEnd)
            for (x in xStart..xEnd)
                if (getDataAt(x, y) != getUserMark(x, y)) return false
        return true
    }

    fun errors(): IntArray {
        return IntArray(width * height) {
            abs(data[it] - user[it])
        }
    }

    fun getRowClue(y: Int): IntArray {
        return getClue(-1, y)
    }
    fun getColumnClue(x: Int): IntArray {
        return getClue(x, -1)
    }
    fun getClue(xSel: Int, ySel: Int): IntArray {
        val arr = ArrayList<Int>()
        var len = 0
        val xStart = if (xSel < 0) 0 else xSel
        val xEnd = if (xSel < 0) width - 1 else xSel
        val yStart = if (ySel < 0) 0 else ySel
        val yEnd = if (ySel < 0) height - 1 else ySel
        for (y in yStart..yEnd)
            for (x in xStart..xEnd)
                if (getDataAt(x, y) != 0) {
                    len++
                } else if (len > 0) {
                    arr.add(len)
                    len = 0
                }
        if (len > 0) arr.add(len)
        else if (arr.size == 0) arr.add(0)
        return arr.toIntArray()
    }

    fun resetUserMarks() {
        user.forEachIndexed { index, _ -> user[index] = 0 }
    }

    // Parcelable interface

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(p: Parcel?, flags: Int) {
        p?.let {
            p.writeInt(width)
            p.writeInt(height)
            p.writeInt(depth)
            p.writeIntArray(data)
            p.writeIntArray(user)
        }
    }

    companion object CREATOR : Parcelable.Creator<Quare> {
        override fun createFromParcel(p: Parcel?): Quare {
            return p!!.let {
                Quare(
                        p.readInt(), // width
                        p.readInt(), // height
                        p.readInt()  // depth
                ).also {
                    p.readIntArray(it.data)
                    p.readIntArray(it.user)
                }
            }
        }

        override fun newArray(size: Int): Array<Quare?> {
            return arrayOfNulls(size)
        }
    }
}
