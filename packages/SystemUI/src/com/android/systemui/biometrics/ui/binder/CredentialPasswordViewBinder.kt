package com.android.systemui.biometrics.ui.binder

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImeAwareEditText
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.R
import com.android.systemui.biometrics.ui.CredentialPasswordView
import com.android.systemui.biometrics.ui.CredentialView
import com.android.systemui.biometrics.ui.viewmodel.CredentialViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Sub-binder for the [CredentialPasswordView]. */
object CredentialPasswordViewBinder {

    /** Bind the view. */
    fun bind(
        view: CredentialPasswordView,
        host: CredentialView.Host,
        viewModel: CredentialViewModel,
    ) {
        val imeManager = view.context.getSystemService(InputMethodManager::class.java)!!

        val passwordField: ImeAwareEditText = view.requireViewById(R.id.lockPassword)

        view.repeatWhenAttached {
            passwordField.requestFocus()
            passwordField.scheduleShowSoftInput()

            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // observe credential validation attempts and submit/cancel buttons
                launch {
                    viewModel.header.collect { header ->
                        passwordField.setTextOperationUser(header.user)
                        passwordField.setOnEditorActionListener(
                            OnImeSubmitListener { text ->
                                launch { viewModel.checkCredential(text, header) }
                            }
                        )
                        passwordField.setOnKeyListener(
                            OnBackButtonListener { host.onCredentialAborted() }
                        )
                    }
                }

                launch {
                    viewModel.inputFlags.collect { flags ->
                        flags?.let { passwordField.inputType = it }
                    }
                }

                // dismiss on a valid credential check
                launch {
                    viewModel.validatedAttestation.collect { attestation ->
                        if (attestation != null) {
                            imeManager.hideSoftInputFromWindow(view.windowToken, 0 /* flags */)
                            host.onCredentialMatched(attestation)
                        } else {
                            passwordField.setText("")
                        }
                    }
                }
            }
        }
    }
}

private class OnBackButtonListener(private val onBack: () -> Unit) : View.OnKeyListener {
    override fun onKey(v: View, keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK) {
            return false
        }
        if (event.action == KeyEvent.ACTION_UP) {
            onBack()
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
