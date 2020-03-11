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

package android.content.res.loader.test

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.content.res.Resources
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import org.mockito.Answers
import org.mockito.stubbing.Answer
import org.xmlpull.v1.XmlPullParser
import java.io.File

object Utils {
    val ANSWER_THROWS = Answer<Any> {
        when (val name = it.method.name) {
            "toString" -> return@Answer Answers.CALLS_REAL_METHODS.answer(it)
            else -> throw UnsupportedOperationException("$name with " +
                    "${it.arguments?.joinToString()} should not be called")
        }
    }
}

fun Int.dpToPx(resources: Resources) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this.toFloat(),
        resources.displayMetrics
).toInt()

fun AssetFileDescriptor.readText() = createInputStream().reader().readText()

fun XmlPullParser.advanceToRoot() = apply {
    while (next() != XmlPullParser.START_TAG) {
        // Empty
    }
}

fun Context.copiedAssetFile(fileName: String): ParcelFileDescriptor {
    return resources.assets.open(fileName).use { input ->
        // AssetManager doesn't expose a direct file descriptor to the asset, so copy it to
        // an individual file so one can be created manually.
        val copiedFile = File(filesDir, fileName)
        copiedFile.outputStream().use { output ->
            input.copyTo(output)
        }
        ParcelFileDescriptor.open(copiedFile, ParcelFileDescriptor.MODE_READ_WRITE)
    }
}
