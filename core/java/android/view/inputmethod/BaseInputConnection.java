/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewRoot;

/**
 * Base class for implementors of the InputConnection interface, taking care
 * of implementing common system-oriented parts of the functionality.
 */
public abstract class BaseInputConnection implements InputConnection {
    final InputMethodManager mIMM;
    final Handler mH;
    final View mTargetView;
    
    BaseInputConnection(InputMethodManager mgr) {
        mIMM = mgr;
        mTargetView = null;
        mH = null;
    }
    
    public BaseInputConnection(View targetView) {
        mIMM = (InputMethodManager)targetView.getContext().getSystemService(
                Context.INPUT_METHOD_SERVICE);
        mH = targetView.getHandler();
        mTargetView = targetView;
    }
    
    /**
     * Provides standard implementation for sending a key event to the window
     * attached to the input connection's view.
     */
    public boolean sendKeyEvent(KeyEvent event) {
        synchronized (mIMM.mH) {
            Handler h = mH;
            if (h == null) {
                if (mIMM.mServedView != null) {
                    h = mIMM.mServedView.getHandler();
                }
            }
            if (h != null) {
                h.sendMessage(h.obtainMessage(ViewRoot.DISPATCH_KEY_FROM_IME,
                        event));
            }
        }
        return false;
    }
    
    /**
     * Provides standard implementation for hiding the status icon associated
     * with the current input method.
     */
    public boolean hideStatusIcon() {
        mIMM.updateStatusIcon(0, null);
        return true;
    }
    
    /**
     * Provides standard implementation for showing the status icon associated
     * with the current input method.
     */
    public boolean showStatusIcon(String packageName, int resId) {
        mIMM.updateStatusIcon(resId, packageName);
        return true;
    }
}
