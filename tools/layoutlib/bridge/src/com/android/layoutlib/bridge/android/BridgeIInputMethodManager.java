/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.layoutlib.bridge.android;

import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.view.IInputMethodManager;
import com.android.internal.view.InputBindResult;

import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.text.style.SuggestionSpan;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import java.util.List;

/**
 * Basic implementation of IInputMethodManager that does nothing.
 *
 */
public class BridgeIInputMethodManager implements IInputMethodManager {

    public void addClient(IInputMethodClient arg0, IInputContext arg1, int arg2, int arg3)
            throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void finishInput(IInputMethodClient arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public InputMethodSubtype getCurrentInputMethodSubtype() throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    public List<InputMethodInfo> getEnabledInputMethodList() throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    public List<InputMethodSubtype> getEnabledInputMethodSubtypeList(InputMethodInfo arg0,
            boolean arg1) throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    public List<InputMethodInfo> getInputMethodList() throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    public InputMethodSubtype getLastInputMethodSubtype() throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    public List getShortcutInputMethodsAndSubtypes() throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    public void hideMySoftInput(IBinder arg0, int arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public boolean hideSoftInput(IInputMethodClient arg0, int arg1, ResultReceiver arg2)
            throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean notifySuggestionPicked(SuggestionSpan arg0, String arg1, int arg2)
            throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public void registerSuggestionSpansForNotification(SuggestionSpan[] arg0)
            throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void removeClient(IInputMethodClient arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void setAdditionalInputMethodSubtypes(String arg0, InputMethodSubtype[] arg1)
            throws RemoteException {
        // TODO Auto-generated method stub
    }

    public boolean setCurrentInputMethodSubtype(InputMethodSubtype arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public void setImeWindowStatus(IBinder arg0, int arg1, int arg2) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void setInputMethod(IBinder arg0, String arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void setInputMethodAndSubtype(IBinder arg0, String arg1, InputMethodSubtype arg2)
            throws RemoteException {
        // TODO Auto-generated method stub

    }

    public boolean setInputMethodEnabled(String arg0, boolean arg1) throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public void showInputMethodAndSubtypeEnablerFromClient(IInputMethodClient arg0, String arg1)
            throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void showInputMethodPickerFromClient(IInputMethodClient arg0) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public void showMySoftInput(IBinder arg0, int arg1) throws RemoteException {
        // TODO Auto-generated method stub

    }

    public boolean showSoftInput(IInputMethodClient arg0, int arg1, ResultReceiver arg2)
            throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }


    public InputBindResult startInput(IInputMethodClient client, IInputContext inputContext,
            EditorInfo attribute, int controlFlags) throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    public boolean switchToLastInputMethod(IBinder arg0) throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public void updateStatusIcon(IBinder arg0, String arg1, int arg2) throws RemoteException {
        // TODO Auto-generated method stub

    }

    @Override
    public InputBindResult windowGainedFocus(IInputMethodClient client, IBinder windowToken,
            int controlFlags, int softInputMode, int windowFlags, EditorInfo attribute,
            IInputContext inputContext) throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    public IBinder asBinder() {
        // TODO Auto-generated method stub
        return null;
    }
}
