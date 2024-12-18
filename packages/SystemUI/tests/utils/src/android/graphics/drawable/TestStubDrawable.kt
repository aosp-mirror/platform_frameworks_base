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

package android.graphics.drawable

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat

/**
 * Stub drawable that does nothing. It's to be used in tests as a mock drawable and checked for the
 * same instance
 */
class TestStubDrawable(private val name: String? = null) : Drawable() {

    override fun draw(canvas: Canvas) = Unit

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    override fun getOpacity(): Int = PixelFormat.UNKNOWN

    override fun toString(): String {
        return name ?: super.toString()
    }

    override fun getConstantState(): ConstantState =
        TestStubConstantState(this, changingConfigurations)

    override fun equals(other: Any?): Boolean {
        return (other as? TestStubDrawable ?: return false).name == name
    }

    private class TestStubConstantState(
        private val drawable: Drawable,
        private val changingConfigurations: Int,
    ) : ConstantState() {

        override fun newDrawable(): Drawable = drawable

        override fun getChangingConfigurations(): Int = changingConfigurations
    }
}
