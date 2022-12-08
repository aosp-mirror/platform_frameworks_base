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
 *
 */

package com.android.systemui.util

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import java.lang.IllegalArgumentException

/**
 * [AlertDialog] that is easier to test. Due to [AlertDialog] being a class and not an interface,
 * there are some things that cannot be avoided, like the creation of a [Handler] on the main thread
 * (and therefore needing a prepared [Looper] in the test).
 *
 * It bypasses calls to show, clicks on buttons, cancel and dismiss so it all can happen bounded in
 * the test. It tries to be as close in behavior as a real [AlertDialog].
 *
 * It will only call [onCreate] as part of its lifecycle, but not any of the other lifecycle methods
 * in [Dialog].
 *
 * In order to test clicking on buttons, use [clickButton] instead of calling [View.callOnClick] on
 * the view returned by [getButton] to bypass the internal [Handler].
 */
class TestableAlertDialog(context: Context) : AlertDialog(context) {

    private var _onDismissListener: DialogInterface.OnDismissListener? = null
    private var _onCancelListener: DialogInterface.OnCancelListener? = null
    private var _positiveButtonClickListener: DialogInterface.OnClickListener? = null
    private var _negativeButtonClickListener: DialogInterface.OnClickListener? = null
    private var _neutralButtonClickListener: DialogInterface.OnClickListener? = null
    private var _onShowListener: DialogInterface.OnShowListener? = null
    private var _dismissOverride: Runnable? = null

    private var showing = false
    private var visible = false
    private var created = false

    override fun show() {
        if (!created) {
            created = true
            onCreate(null)
        }
        if (isShowing) return
        showing = true
        visible = true
        _onShowListener?.onShow(this)
    }

    override fun hide() {
        visible = false
    }

    override fun isShowing(): Boolean {
        return visible && showing
    }

    override fun dismiss() {
        if (!showing) {
            return
        }
        if (_dismissOverride != null) {
            _dismissOverride?.run()
            return
        }
        _onDismissListener?.onDismiss(this)
        showing = false
    }

    override fun cancel() {
        _onCancelListener?.onCancel(this)
        dismiss()
    }

    override fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) {
        _onDismissListener = listener
    }

    override fun setOnCancelListener(listener: DialogInterface.OnCancelListener?) {
        _onCancelListener = listener
    }

    override fun setOnShowListener(listener: DialogInterface.OnShowListener?) {
        _onShowListener = listener
    }

    override fun takeCancelAndDismissListeners(
        msg: String?,
        cancel: DialogInterface.OnCancelListener?,
        dismiss: DialogInterface.OnDismissListener?
    ): Boolean {
        _onCancelListener = cancel
        _onDismissListener = dismiss
        return true
    }

    override fun setButton(
        whichButton: Int,
        text: CharSequence?,
        listener: DialogInterface.OnClickListener?
    ) {
        super.setButton(whichButton, text, listener)
        when (whichButton) {
            DialogInterface.BUTTON_POSITIVE -> _positiveButtonClickListener = listener
            DialogInterface.BUTTON_NEGATIVE -> _negativeButtonClickListener = listener
            DialogInterface.BUTTON_NEUTRAL -> _neutralButtonClickListener = listener
            else -> Unit
        }
    }

    /**
     * Click one of the buttons in the [AlertDialog] and call the corresponding listener.
     *
     * Button ids are from [DialogInterface].
     */
    fun clickButton(whichButton: Int) {
        val listener =
            when (whichButton) {
                DialogInterface.BUTTON_POSITIVE -> _positiveButtonClickListener
                DialogInterface.BUTTON_NEGATIVE -> _negativeButtonClickListener
                DialogInterface.BUTTON_NEUTRAL -> _neutralButtonClickListener
                else -> throw IllegalArgumentException("Wrong button $whichButton")
            }
        listener?.onClick(this, whichButton)
        dismiss()
    }
}
