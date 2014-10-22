/* //device/apps/AndroidTests/src/com.android.unit_tests/activity/TestedScreen.java
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

package android.app.activity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.util.Log;

public class RemoteSubActivityScreen extends SubActivityScreen {
    Handler mHandler = new Handler();
    boolean mFirst = false;

    public RemoteSubActivityScreen() {
    }

    @Override
    public void onCreate(Bundle icicle) {
        // We are running in a remote process, so want to have the sub-activity
        // sending the result back in the original process.
        Intent intent = getIntent();
        intent.setClass(this, SubActivityScreen.class);
        
        super.onCreate(icicle);
        
        boolean kill = intent.getBooleanExtra("kill", false);
        //Log.i("foo", "RemoteSubActivityScreen pid=" + Process.myPid()
        //        + " kill=" + kill);
        
        if (kill) {
            // After finishing initialization, kill the process!  But only if
            // this is the first time...
            if (icicle == null) {
                mHandler.post(new Runnable() {
                    public void run() {
                        handleBeforeStopping();
                        Process.killProcess(Process.myPid());
                    }
                });
            }
        }
    }
}
