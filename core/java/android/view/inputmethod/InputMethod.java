/*
 * Copyright (C) 2007-2008 The Android Open Source Project
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.view.inputmethod;

import android.graphics.Rect;
import android.inputmethodservice.InputMethodService;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * The InputMethod interface represents an input method which can generate key
 * events and text, such as digital, email addresses, CJK characters, other
 * language characters, and etc., while handling various input events, and send
 * the text back to the application that requests text input.
 * 
 * <p>Applications will not normally use this interface themselves, instead
 * relying on the standard interaction provided by
 * {@link android.widget.TextView} and {@link android.widget.EditText}.
 * 
 * <p>Those implementing input methods should normally do so by deriving from
 * {@link InputMethodService} or one of its subclasses.  When implementing
 * an input method, the service component containing it must also supply
 * a {@link #SERVICE_META_DATA} meta-data field, referencing an XML resource
 * providing details about the input method.  All input methods also must
 * require that clients hold the
 * {@link android.Manifest.permission#BIND_INPUT_METHOD} in order to interact
 * with the service; if this is not required, the system will not use that
 * input method, because it can not trust that it is not compromised.
 */
public interface InputMethod {
    /**
     * This is the interface name that a service implementing an input
     * method should say that it supports -- that is, this is the action it
     * uses for its intent filter.  (Note: this name is used because this
     * interface should be moved to the view package.)
     */
    public static final String SERVICE_INTERFACE = "android.view.InputMethod";
    
    /**
     * Name under which an InputMethod service component publishes information
     * about itself.  This meta-data must reference an XML resource containing
     * an
     * <code>&lt;{@link android.R.styleable#InputMethod input-method}&gt;</code>
     * tag.
     */
    public static final String SERVICE_META_DATA = "android.view.im";
    
    public interface SessionCallback {
        public void sessionCreated(InputMethodSession session);
    }
    
    /**
     * Called first thing after an input method is created, this supplies a
     * unique token for the session it has with the system service.  It is
     * needed to identify itself with the service to validate its operations.
     * This token <strong>must not</strong> be passed to applications, since
     * it grants special priviledges that should not be given to applications.
     * 
     * <p>Note: to protect yourself from malicious clients, you should only
     * accept the first token given to you.  Any after that may come from the
     * client.
     */
    public void attachToken(IBinder token);
    
    /**
     * Bind a new application environment in to the input method, so that it
     * can later start and stop input processing.
     * Typically this method is called when this input method is enabled in an
     * application for the first time.
     * 
     * @param binding Information about the application window that is binding
     * to the input method.
     * 
     * @see InputBinding
     * @see #unbindInput()
     */
    public void bindInput(InputBinding binding);

    /**
     * Unbind an application environment, called when the information previously
     * set by {@link #bindInput} is no longer valid for this input method.
     * 
     * <p>
     * Typically this method is called when the application changes to be
     * non-foreground.
     */
    public void unbindInput();

    /**
     * This method is called when the application starts to receive text and it
     * is ready for this input method to process received events and send result
     * text back to the application.
     * 
     * @param attribute The attribute of the text box (typically, a EditText)
     *        that requests input.
     * 
     * @see EditorInfo
     */
    public void startInput(EditorInfo attribute);

    /**
     * This method is called when the state of this input method needs to be
     * reset.
     * 
     * <p>
     * Typically, this method is called when the input focus is moved from one
     * text box to another.
     * 
     * @param attribute The attribute of the text box (typically, a EditText)
     *        that requests input.
     * 
     * @see EditorInfo
     */
    public void restartInput(EditorInfo attribute);

    /**
     * Create a new {@link InputMethodSession} that can be handed to client
     * applications for interacting with the input method.  You can later
     * use {@link #revokeSession(InputMethodSession)} to destroy the session
     * so that it can no longer be used by any clients.
     * 
     * @param callback Interface that is called with the newly created session.
     */
    public void createSession(SessionCallback callback);
    
    /**
     * Control whether a particular input method session is active.
     * 
     * @param session The {@link InputMethodSession} previously provided through
     * SessionCallback.sessionCreated() that is to be changed.
     */
    public void setSessionEnabled(InputMethodSession session, boolean enabled);
    
    /**
     * Disable and destroy a session that was previously created with
     * {@link #createSession(android.view.inputmethod.InputMethod.SessionCallback)}.
     * After this call, the given session interface is no longer active and
     * calls on it will fail.
     * 
     * @param session The {@link InputMethodSession} previously provided through
     * SessionCallback.sessionCreated() that is to be revoked.
     */
    public void revokeSession(InputMethodSession session);
    
    /**
     * Request that any soft input part of the input method be shown to the user.
     */
    public void showSoftInput();
    
    /**
     * Request that any soft input part of the input method be hidden from the user.
     */
    public void hideSoftInput();
}
