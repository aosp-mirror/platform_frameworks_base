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

import android.os.Message;

/** @hide */
public class AsyncResult
{

    /*************************** Instance Variables **************************/

    // Expect either exception or result to be null
    public Object userObj;
    public Throwable exception;
    public Object result;

    /***************************** Class Methods *****************************/

    /** Saves and sets m.obj */
    public static AsyncResult 
    forMessage(Message m, Object r, Throwable ex)
    {
        AsyncResult ret;

        ret = new AsyncResult (m.obj, r, ex);

        m.obj = ret; 

        return ret;
    }

    /** Saves and sets m.obj */
    public static AsyncResult 
    forMessage(Message m)
    {
        AsyncResult ret;

        ret = new AsyncResult (m.obj, null, null);

        m.obj = ret; 

        return ret;
    }

    /** please note, this sets m.obj to be this */
    public 
    AsyncResult (Object uo, Object r, Throwable ex)
    {
        userObj = uo;
        result = r;
        exception = ex;
    }
}
