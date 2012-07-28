/*
 * Copyright (c) 2011, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *    * Redistributions of source code must retain the above copyright
 *      notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 *    * Neither the name of Code Aurora nor
 *      the names of its contributors may be used to endorse or promote
 *      products derived from this software without specific prior written
 *      permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.internal.app;

import android.content.ComponentName;
import android.content.Intent;
import android.util.Log;

public class ActivityTrigger
{
    private static final String TAG = "ActivityTrigger";

    /** &hide */
    public ActivityTrigger() {
        //Log.d(TAG, "ActivityTrigger initialized");
    }

    /** &hide */
    protected void finalize() {
        native_at_deinit();
    }

    /** &hide */
    public void activityStartTrigger(Intent intent) {
        ComponentName cn = intent.getComponent();
        String activity = null;

        if (cn != null)
            activity = cn.flattenToString();
        native_at_startActivity(activity);
    }

    /** &hide */
    public void activityResumeTrigger(Intent intent) {
        ComponentName cn = intent.getComponent();
        String activity = null;

        if (cn != null)
            activity = cn.flattenToString();
        native_at_resumeActivity(activity);
    }

    private native void native_at_startActivity(String activity);
    private native void native_at_resumeActivity(String activity);
    private native void native_at_deinit();
}
