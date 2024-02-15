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

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.view.View
import androidx.constraintlayout.helper.widget.Layer

class AodBurnInLayer(context: Context) : Layer(context) {
    // For setScale in Layer class, it stores it in mScaleX/Y and directly apply scale to
    // referenceViews instead of keeping the value in fields of View class
    // when we try to clone ConstraintSet, it will call getScaleX from View class and return 1.0
    // and when we clone and apply, it will reset everything in the layer
    // which cause the flicker from AOD to LS
    private var _scaleX = 1F
    private var _scaleY = 1F
    // As described for _scaleX and _scaleY, we have similar issue with translation
    private var _translationX = 1F
    private var _translationY = 1F
    // avoid adding views with same ids
    override fun addView(view: View?) {
        view?.let { if (it.id !in referencedIds) super.addView(view) }
    }
    override fun setScaleX(scaleX: Float) {
        _scaleX = scaleX
        super.setScaleX(scaleX)
    }

    override fun getScaleX(): Float {
        return _scaleX
    }

    override fun setScaleY(scaleY: Float) {
        _scaleY = scaleY
        super.setScaleY(scaleY)
    }

    override fun getScaleY(): Float {
        return _scaleY
    }

    override fun setTranslationX(dx: Float) {
        _translationX = dx
        super.setTranslationX(dx)
    }

    override fun getTranslationX(): Float {
        return _translationX
    }

    override fun setTranslationY(dy: Float) {
        _translationY = dy
        super.setTranslationY(dy)
    }

    override fun getTranslationY(): Float {
        return _translationY
    }
}
