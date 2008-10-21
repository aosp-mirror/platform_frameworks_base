/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.text;

import android.content.Context;
import android.os.RemoteException;
import android.os.Handler;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;

/**
 * Interface to the clipboard service, for placing and retrieving text in
 * the global clipboard.
 *
 * <p>
 * You do not instantiate this class directly; instead, retrieve it through
 * {@link android.content.Context#getSystemService}.
 *
 * @see android.content.Context#getSystemService
 */
public class ClipboardManager {
    private static IClipboard sService;

    private Context mContext;

    static private IClipboard getService() {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService("clipboard");
        sService = IClipboard.Stub.asInterface(b);
        return sService;
    }

    /** {@hide} */
    public ClipboardManager(Context context, Handler handler) {
        mContext = context;
    }

    /**
     * Returns the text on the clipboard.  It will eventually be possible
     * to store types other than text too, in which case this will return
     * null if the type cannot be coerced to text.
     */
    public CharSequence getText() {
        try {
            return getService().getClipboardText();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Sets the contents of the clipboard to the specified text.
     */
    public void setText(CharSequence text) {
        try {
            getService().setClipboardText(text);
        } catch (RemoteException e) {
        }
    }

    /**
     * Returns true if the clipboard contains text; false otherwise.
     */
    public boolean hasText() {
        try {
            return getService().hasClipboardText();
        } catch (RemoteException e) {
            return false;
        }
    }
}
