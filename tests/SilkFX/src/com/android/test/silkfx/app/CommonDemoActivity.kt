/*
 * Copyright (C) 2020 The Android Open Source Project
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

import com.android.test.silkfx.R
import android.os.Bundle
import android.view.LayoutInflater

const val EXTRA_LAYOUT = "layout"
const val EXTRA_TITLE = "title"

class CommonDemoActivity : BaseDemoActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val extras = intent.extras ?: return finish()

        val layout = extras.getInt(EXTRA_LAYOUT, -1)
        if (layout == -1) {
            finish()
            return
        }
        val title = extras.getString(EXTRA_TITLE, "SilkFX")
        window.setTitle(title)

        setContentView(R.layout.common_base)
        actionBar?.title = title
        LayoutInflater.from(this).inflate(layout, findViewById(R.id.demo_container), true)
    }
}