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

package com.android.systemui.bouncer.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.widget.LinearLayout
import com.android.keyguard.BouncerKeyguardMessageArea
import com.android.keyguard.KeyguardMessageArea
import com.android.keyguard.KeyguardMessageAreaController
import com.android.systemui.Flags
import com.android.systemui.res.R

class BouncerMessageView : LinearLayout {
    constructor(context: Context?) : super(context)

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)

    init {
        inflate(context, R.layout.bouncer_message_view, this)
    }

    var primaryMessageView: BouncerKeyguardMessageArea? = null
    var secondaryMessageView: BouncerKeyguardMessageArea? = null
    var primaryMessage: KeyguardMessageAreaController<KeyguardMessageArea>? = null
    var secondaryMessage: KeyguardMessageAreaController<KeyguardMessageArea>? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        primaryMessageView = findViewById(R.id.bouncer_primary_message_area)
        secondaryMessageView = findViewById(R.id.bouncer_secondary_message_area)

        if (Flags.gsfBouncer()) {
            primaryMessageView?.apply {
                typeface = Typeface.create("gsf-title-large-emphasized", Typeface.NORMAL)
            }
            secondaryMessageView?.apply {
                typeface = Typeface.create("gsf-title-medium-emphasized", Typeface.NORMAL)
            }
        }
    }

    fun init(factory: KeyguardMessageAreaController.Factory) {
        primaryMessage = factory.create(primaryMessageView)
        primaryMessage?.init()
        secondaryMessage = factory.create(secondaryMessageView)
        secondaryMessage?.init()
    }
}
