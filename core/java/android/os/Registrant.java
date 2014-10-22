/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.os;

import android.os.Handler;
import android.os.Message;

import java.lang.ref.WeakReference;

/** @hide */
public class Registrant
{
    public
    Registrant(Handler h, int what, Object obj)
    {
        refH = new WeakReference(h);
        this.what = what;
        userObj = obj;
    }

    public void
    clear()
    {
        refH = null;
        userObj = null;
    }

    public void
    notifyRegistrant()
    {
        internalNotifyRegistrant (null, null);
    }
    
    public void
    notifyResult(Object result)
    {
        internalNotifyRegistrant (result, null);
    }

    public void
    notifyException(Throwable exception)
    {
        internalNotifyRegistrant (null, exception);
    }

    /**
     * This makes a copy of @param ar
     */
    public void
    notifyRegistrant(AsyncResult ar)
    {
        internalNotifyRegistrant (ar.result, ar.exception);
    }

    /*package*/ void
    internalNotifyRegistrant (Object result, Throwable exception)
    {
        Handler h = getHandler();

        if (h == null) {
            clear();
        } else {
            Message msg = Message.obtain();

            msg.what = what;
            
            msg.obj = new AsyncResult(userObj, result, exception);
            
            h.sendMessage(msg);
        }
    }

    /**
     * NOTE: May return null if weak reference has been collected
     */

    public Message
    messageForRegistrant()
    {
        Handler h = getHandler();

        if (h == null) {
            clear();

            return null;
        } else {
            Message msg = h.obtainMessage();

            msg.what = what;
            msg.obj = userObj;

            return msg;
        }
    }

    public Handler
    getHandler()
    {
        if (refH == null)
            return null;

        return (Handler) refH.get();
    }

    WeakReference   refH;
    int             what;
    Object          userObj;
}

