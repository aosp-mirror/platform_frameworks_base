/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.systemui.statusbar

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A temporary base class that's shared between our old status bar wifi view implementation
 * ([StatusBarWifiView]) and our new status bar wifi view implementation
 * ([ModernStatusBarWifiView]).
 *
 * Once our refactor is over, we should be able to delete this go-between class and the old view
 * class.
 */
abstract class BaseStatusBarWifiView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttrs: Int = 0,
) : FrameLayout(context, attrs, defStyleAttrs), StatusIconDisplayable
