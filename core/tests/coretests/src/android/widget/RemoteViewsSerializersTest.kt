/*
 * Copyright (C) 2024 The Android Open Source Project
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
 * limitations under the License
 */

package android.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.util.proto.ProtoInputStream
import android.util.proto.ProtoOutputStream
import android.widget.RemoteViewsSerializers.createIconFromProto
import android.widget.RemoteViewsSerializers.writeIconToProto
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.frameworks.coretests.R
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteViewsSerializersTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /**
     * Based on android.graphics.drawable.IconTest#testParcel
     */
    @Test
    fun testWriteIconToProto() {
        val bitmap = (context.getDrawable(R.drawable.landscape) as BitmapDrawable).bitmap
        val bitmapData = ByteArrayOutputStream().let {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }

        for (icon in listOf(
            Icon.createWithBitmap(bitmap),
            Icon.createWithAdaptiveBitmap(bitmap),
            Icon.createWithData(bitmapData, 0, bitmapData.size),
            Icon.createWithResource(context, R.drawable.landscape),
            Icon.createWithContentUri("content://com.example.myapp/my_icon"),
            Icon.createWithAdaptiveBitmapContentUri("content://com.example.myapp/my_icon"),
        )) {
            icon.tintList = ColorStateList.valueOf(Color.RED)
            icon.tintBlendMode = BlendMode.SRC_OVER
            val bytes = ProtoOutputStream().let {
                writeIconToProto(it, context.resources, icon)
                it.bytes
            }

            val copy = ProtoInputStream(bytes).let {
                createIconFromProto(it).apply(context.resources)
            }
            assertThat(copy.type).isEqualTo(icon.type)
            assertThat(copy.tintBlendMode).isEqualTo(icon.tintBlendMode)
            assertThat(equalColorStateLists(copy.tintList, icon.tintList)).isTrue()

            when (icon.type) {
                Icon.TYPE_DATA, Icon.TYPE_URI, Icon.TYPE_URI_ADAPTIVE_BITMAP,
                Icon.TYPE_RESOURCE -> {
                    assertThat(copy.sameAs(icon)).isTrue()
                }

                Icon.TYPE_BITMAP, Icon.TYPE_ADAPTIVE_BITMAP -> {
                    assertThat(copy.bitmap.sameAs(icon.bitmap)).isTrue()
                }
            }
        }
    }
}

fun equalColorStateLists(a: ColorStateList?, b: ColorStateList?): Boolean {
    if (a == null && b == null) return true
    return a != null && b != null &&
            a.colors.contentEquals(b.colors) &&
            a.states.foldIndexed(true) { i, acc, it -> acc && it.contentEquals(b.states[i])}
}
