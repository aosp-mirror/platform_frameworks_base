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

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.inputmethodservice.InputMethodService;
import android.os.IBinder;
import android.os.ResultReceiver;

/**
 * The InputMethod interface represents an input method which can generate key
 * events and text, such as digital, email addresses, CJK characters, other
 * language characters, and etc., while handling various input events, and send
 * the text back to the application that requests text input.  See
 * {@link InputMethodManager} for more general information about the
 * architecture.
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
 * 
 * <p>The InputMethod interface is actually split into two parts: the interface
 * here is the top-level interface to the input method, providing all
 * access to it, which only the system can access (due to the BIND_INPUT_METHOD
 * permission requirement).  In addition its method
 * {@link #createSession(android.view.inputmethod.InputMethod.SessionCallback)}
 * can be called to instantate a secondary {@link InputMethodSession} interface
 * which is what clients use to communicate with the input method.
 */
public interface InputMethod {
    /**
     * This is the interface name that a service implementing an input
     * method should say that it supports -- that is, this is the action it
     * uses for its intent filter.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_INPUT_METHOD} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
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
     * @param inputConnection Optional specific input connection for
     * communicating with the text box; if null, you should use the generic
     * bound input connection.
     * @param info Information about the text box (typically, an EditText)
     *        that requests input.
     * 
     * @see EditorInfo
     */
    public void startInput(InputConnection inputConnection, EditorInfo info);

    /**
     * This method is called when the state of this input method needs to be
     * reset.
     * 
     * <p>
     * Typically, this method is called when the input focus is moved from one
     * text box to another.
     * 
     * @param inputConnection Optional specific input connection for
     * communicating with the text box; if null, you should use the generic
     * bound input connection.
     * @param attribute The attribute of the text box (typically, a EditText)
     *        that requests input.
     * 
     * @see EditorInfo
     */
    public void restartInput(InputConnection inputConnection, EditorInfo attribute);

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
     * Flag for {@link #showSoftInput}: this show has been explicitly
     * requested by the user.  If not set, the system has decided it may be
     * a good idea to show the input method based on a navigation operation
     * in the UI.
     */
    public static final int SHOW_EXPLICIT = 0x00001;
    
    /**
     * Flag for {@link #showSoftInput}: this show has been forced to
     * happen by the user.  If set, the input method should remain visible
     * until deliberated dismissed by the user in its UI.
     */
    public static final int SHOW_FORCED = 0x00002;
    
    /**
     * Request that any soft input part of the input method be shown to the user.
     * 
     * @param flags Provides additional information about the show request.
     * Currently may be 0 or have the bit {@link #SHOW_EXPLICIT} set.
     * @param resultReceiver The client requesting the show may wish to
     * be told the impact of their request, which should be supplied here.
     * The result code should be
     * {@link InputMethodManager#RESULT_UNCHANGED_SHOWN InputMethodManager.RESULT_UNCHANGED_SHOWN},
     * {@link InputMethodManager#RESULT_UNCHANGED_HIDDEN InputMethodManager.RESULT_UNCHANGED_HIDDEN},
     * {@link InputMethodManager#RESULT_SHOWN InputMethodManager.RESULT_SHOWN}, or
     * {@link InputMethodManager#RESULT_HIDDEN InputMethodManager.RESULT_HIDDEN}.
     */
    public void showSoftInput(int flags, ResultReceiver resultReceiver);
    
    /**
     * Request that any soft input part of the input method be hidden from the user.
     * @param flags Provides additional information about the show request.
     * Currently always 0.
     * @param resultReceiver The client requesting the show may wish to
     * be told the impact of their request, which should be supplied here.
     * The result code should be
     * {@link InputMethodManager#RESULT_UNCHANGED_SHOWN InputMethodManager.RESULT_UNCHANGED_SHOWN},
     * {@link InputMethodManager#RESULT_UNCHANGED_HIDDEN InputMethodManager.RESULT_UNCHANGED_HIDDEN},
     * {@link InputMethodManager#RESULT_SHOWN InputMethodManager.RESULT_SHOWN}, or
     * {@link InputMethodManager#RESULT_HIDDEN InputMethodManager.RESULT_HIDDEN}.
     */
    public void hideSoftInput(int flags, ResultReceiver resultReceiver);
}
