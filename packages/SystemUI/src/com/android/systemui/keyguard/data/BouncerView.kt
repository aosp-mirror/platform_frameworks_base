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
 * limitations under the License
 */

package com.android.systemui.keyguard.data

import android.view.KeyEvent
import com.android.systemui.dagger.SysUISingleton
import java.lang.ref.WeakReference
import javax.inject.Inject

/** An abstraction to interface with the ui layer, without changing state. */
interface BouncerView {
    var delegate: BouncerViewDelegate?
}

/** A lightweight class to hold reference to the ui delegate. */
@SysUISingleton
class BouncerViewImpl @Inject constructor() : BouncerView {
    private var _delegate: WeakReference<BouncerViewDelegate?> = WeakReference(null)
    override var delegate: BouncerViewDelegate?
        get() = _delegate.get()
        set(value) {
            _delegate = WeakReference(value)
        }
}

/** An abstraction that implements view logic. */
interface BouncerViewDelegate {
    fun isFullScreenBouncer(): Boolean
    fun shouldDismissOnMenuPressed(): Boolean
    fun interceptMediaKey(event: KeyEvent?): Boolean
    fun dispatchBackKeyEventPreIme(): Boolean
    fun showNextSecurityScreenOrFinish(): Boolean
    fun resume()
}
