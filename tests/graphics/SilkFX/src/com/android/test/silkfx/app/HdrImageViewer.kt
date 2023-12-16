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

package com.android.test.silkfx.app

import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import com.android.test.silkfx.R
import com.android.test.silkfx.hdr.GainmapImage

class HdrImageViewer : BaseDemoActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.hdr_image_viewer)

        val intent = this.intent ?: return finish()
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return finish()
                val source = ImageDecoder.createSource(contentResolver, data)
                findViewById<GainmapImage>(R.id.gainmap_image)!!.setImageSource(source)
            }
            Intent.ACTION_SEND -> {
                val uri = (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)
                    ?: return finish()
                val source = ImageDecoder.createSource(contentResolver, uri)
                findViewById<GainmapImage>(R.id.gainmap_image)!!.setImageSource(source)
            }
            else -> {
                finish()
            }
        }
    }
}
