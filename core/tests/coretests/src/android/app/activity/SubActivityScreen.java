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

package android.app.activity;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;

public class SubActivityScreen extends Activity {
    static final int NO_RESULT_MODE = 0;
    static final int RESULT_MODE = 1;
    static final int PENDING_RESULT_MODE = 2;
    static final int FINISH_SUB_MODE = 3;

    static final int CHILD_OFFSET = 1000;

    int mMode;

    public SubActivityScreen() {
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mMode = getIntent().getIntExtra("mode", mMode);
        //Log.i("foo", "SubActivityScreen pid=" + Process.myPid()
        //        + " mode=" + mMode);
        
        // Move on to the next thing that will generate a result...  but only
        // if we are being launched for the first time.
        if (icicle == null) {
	        if (mMode == PENDING_RESULT_MODE) {
	            PendingIntent apr = createPendingResult(1, null,
	                    Intent.FILL_IN_ACTION);
	            Intent res = new Intent();
                res.putExtra("tkey", "tval");
                res.setAction("test");
	            try {
    	            apr.send(this, RESULT_OK, res);
	            } catch (PendingIntent.CanceledException e) {
	            }
	        } else if (mMode < CHILD_OFFSET) {
	            Intent intent = new Intent();
	        	intent.setClass(this, SubActivityScreen.class);
	            intent.putExtra("mode", CHILD_OFFSET+mMode);
	            //System.out.println("*** Starting from onStart: " + intent);
	            startActivityForResult(intent, 1);
	            return;
	        }
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
    }

    @Override
    protected void onResume() {
        super.onResume();

        //Log.i("foo", "SubActivityScreen pid=" + Process.myPid() + " onResume");
        
        if (mMode >= CHILD_OFFSET) {
        	// Wait a little bit, to give our parent time to kill itself
        	// if that is something it is into.
        	try {
	        	Thread.sleep(500);
        	} catch (InterruptedException e) {
        		setResult(RESULT_CANCELED, (new Intent()).setAction("Interrupted!"));
        		finish();
        		return;
        	}
            //System.out.println("Resuming sub-activity: mode=" + mMode);
            switch (mMode-CHILD_OFFSET) {
            case NO_RESULT_MODE:
                finish();
                break;
            case RESULT_MODE:
                Intent res = new Intent();
                res.putExtra("tkey", "tval");
                res.setAction("test");
                setResult(RESULT_OK, res);
                finish();
                break;
            case FINISH_SUB_MODE:
                break;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
            Intent data) {
        //Log.i("foo", "SubActivityScreen pid=" + Process.myPid()
        //        + " onActivityResult: req=" + requestCode
        //        + " res=" + resultCode);

        // Assume success.
        setResult(RESULT_OK);

        if (requestCode == 1) {
            switch (mMode) {
            case NO_RESULT_MODE:
            case FINISH_SUB_MODE:
                if (resultCode != RESULT_CANCELED) {
                    setResult(RESULT_CANCELED, (new Intent()).setAction(
                            "Incorrect result code returned: " + resultCode));
                }
                break;
            case RESULT_MODE:
            case PENDING_RESULT_MODE:
                if (resultCode != RESULT_OK) {
                    setResult(RESULT_CANCELED, (new Intent()).setAction(
                            "Incorrect result code returned: " + resultCode));
                } else if (data == null) {
                    setResult(RESULT_CANCELED, (new Intent()).setAction(
                            "null data returned"));
                } else if (!("test".equals(data.getAction()))) {
                    setResult(RESULT_CANCELED, (new Intent()).setAction(
                            "Incorrect action returned: " + data));
                } else if (!("tval".equals(data.getStringExtra("tkey")))) {
                    setResult(RESULT_CANCELED, (new Intent()).setAction(
                            "Incorrect extras returned: " + data.getExtras()));
                }
                break;
            }
        } else {
            setResult(RESULT_CANCELED, (new Intent()).setAction(
                    "Incorrect request code returned: " + requestCode));
        }

        finish();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handleBeforeStopping();
    }
    
    public void handleBeforeStopping() {
        if (mMode == FINISH_SUB_MODE) {
            finishActivity(1);
        }
    }
}

