package com.android.systemui.biometrics.ui.binder

import android.os.UserHandle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImeAwareEditText
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.res.R
import com.android.systemui.biometrics.ui.CredentialPasswordView
import com.android.systemui.biometrics.ui.CredentialView
import com.android.systemui.biometrics.ui.IPinPad
import com.android.systemui.biometrics.ui.viewmodel.CredentialViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/** Sub-binder for the [CredentialPasswordView]. */
object CredentialPasswordViewBinder {

    /** Bind the view. */
    fun bind(
        view: CredentialPasswordView,
        host: CredentialView.Host,
        viewModel: CredentialViewModel,
        requestFocusForInput: Boolean,
    ) {
        val imeManager = view.context.getSystemService(InputMethodManager::class.java)!!

        val passwordField: ImeAwareEditText = view.requireViewById(R.id.lockPassword)

        val onBackInvokedCallback = OnBackInvokedCallback { host.onCredentialAborted() }

        view.repeatWhenAttached {
            // the header info never changes - do it early
            val header = viewModel.header.first()
            passwordField.setTextOperationUser(UserHandle.of(header.user.deviceCredentialOwnerId))
            viewModel.inputFlags.firstOrNull()?.let { flags -> passwordField.inputType = flags }
            if (requestFocusForInput) {
                passwordField.requestFocus()
                passwordField.scheduleShowSoftInput()
            }
            passwordField.setOnEditorActionListener(
                OnImeSubmitListener { text ->
                    lifecycleScope.launch { viewModel.checkCredential(text, header) }
                }
            )
            passwordField.setOnKeyListener(OnBackButtonListener(onBackInvokedCallback))
            val pinPadView = view.findViewById(R.id.pin_pad) as? IPinPad
            if (pinPadView != null) {
                PinPadViewBinder.bind(pinPadView, view)
            }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // dismiss on a valid credential check
                launch {
                    viewModel.validatedAttestation.collect { attestation ->
                        if (attestation != null) {
                            imeManager.hideSoftInputFromWindow(
                                view.windowToken,
                                0 // flag
                            )
                            host.onCredentialMatched(attestation)
                        } else {
                            passwordField.setText("")
                        }
                    }
                }

                val onBackInvokedDispatcher = view.findOnBackInvokedDispatcher()
                if (onBackInvokedDispatcher != null) {
                    launch {
                            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                                onBackInvokedCallback
                            )
                            awaitCancellation()
                        }
                        .invokeOnCompletion {
                            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(
                                onBackInvokedCallback
                            )
                        }
                }
            }
        }
    }
}

private class OnBackButtonListener(private val onBackInvokedCallback: OnBackInvokedCallback) :
    View.OnKeyListener {
    override fun onKey(v: View, keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK) {
            return false
        }
        if (event.action == KeyEvent.ACTION_UP) {
            onBackInvokedCallback.onBackInvoked()
        }
        return true
    }
}

private class OnImeSubmitListener(private val onSubmit: (text: CharSequence) -> Unit) :
    TextView.OnEditorActionListener {
    override fun onEditorAction(v: TextView, actionId: Int, event: KeyEvent?): Boolean {
        val isSoftImeEvent =
            event == null &&
                (actionId == EditorInfo.IME_NULL ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_NEXT)
        val isKeyboardEnterKey =
            event != null &&
                KeyEvent.isConfirmKey(event.keyCode) &&
                event.action == KeyEvent.ACTION_DOWN
        if (isSoftImeEvent || isKeyboardEnterKey) {
            onSubmit(v.text)
            return true
        }
        return false
    }
}
