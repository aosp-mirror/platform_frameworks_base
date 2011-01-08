/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.app;

import com.android.internal.R;

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * This activity is displayed when the system attempts to start an Intent for
 * which there is more than one matching activity, allowing the user to decide
 * which to go to.  It is not normally used directly by application developers.
 */
public class HeavyWeightSwitcherActivity extends Activity {
    /** The PendingIntent of the new activity being launched. */
    public static final String KEY_INTENT = "intent";
    /** Set if the caller is requesting a result. */
    public static final String KEY_HAS_RESULT = "has_result";
    /** Package of current heavy-weight app. */
    public static final String KEY_CUR_APP = "cur_app";
    /** Task that current heavy-weight activity is running in. */
    public static final String KEY_CUR_TASK = "cur_task";
    /** Package of newly requested heavy-weight app. */
    public static final String KEY_NEW_APP = "new_app";
    
    IntentSender mStartIntent;
    boolean mHasResult;
    String mCurApp;
    int mCurTask;
    String mNewApp;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        requestWindowFeature(Window.FEATURE_LEFT_ICON);
        
        mStartIntent = (IntentSender)getIntent().getParcelableExtra(KEY_INTENT);
        mHasResult = getIntent().getBooleanExtra(KEY_HAS_RESULT, false);
        mCurApp = getIntent().getStringExtra(KEY_CUR_APP);
        mCurTask = getIntent().getIntExtra(KEY_CUR_TASK, 0);
        mNewApp = getIntent().getStringExtra(KEY_NEW_APP);
        
        setContentView(com.android.internal.R.layout.heavy_weight_switcher);
        
        setIconAndText(R.id.old_app_icon, R.id.old_app_action, R.id.old_app_description,
                mCurApp, R.string.old_app_action, R.string.old_app_description);
        setIconAndText(R.id.new_app_icon, R.id.new_app_action, R.id.new_app_description,
                mNewApp, R.string.new_app_action, R.string.new_app_description);
            
        View button = findViewById((R.id.switch_old));
        button.setOnClickListener(mSwitchOldListener);
        button = findViewById((R.id.switch_new));
        button.setOnClickListener(mSwitchNewListener);
        button = findViewById((R.id.cancel));
        button.setOnClickListener(mCancelListener);
        
        TypedValue out = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.alertDialogIcon, out, true);
        getWindow().setFeatureDrawableResource(Window.FEATURE_LEFT_ICON, 
                out.resourceId);
    }

    void setText(int id, CharSequence text) {
        ((TextView)findViewById(id)).setText(text);
    }
    
    void setDrawable(int id, Drawable dr) {
        if (dr != null) {
            ((ImageView)findViewById(id)).setImageDrawable(dr);
        }
    }
    
    void setIconAndText(int iconId, int actionId, int descriptionId,
            String packageName, int actionStr, int descriptionStr) {
        CharSequence appName = "";
        Drawable appIcon = null;
        if (mCurApp != null) {
            try {
                ApplicationInfo info = getPackageManager().getApplicationInfo(
                        packageName, 0);
                appName = info.loadLabel(getPackageManager());
                appIcon = info.loadIcon(getPackageManager());
            } catch (PackageManager.NameNotFoundException e) {
            }
        }
        
        setDrawable(iconId, appIcon);
        setText(actionId, getString(actionStr, appName));
        setText(descriptionId, getText(descriptionStr));
    }
    
    private OnClickListener mSwitchOldListener = new OnClickListener() {
        public void onClick(View v) {
            try {
                ActivityManagerNative.getDefault().moveTaskToFront(mCurTask, 0);
            } catch (RemoteException e) {
            }
            finish();
        }
    };
    
    private OnClickListener mSwitchNewListener = new OnClickListener() {
        public void onClick(View v) {
            try {
                ActivityManagerNative.getDefault().finishHeavyWeightApp();
            } catch (RemoteException e) {
            }
            try {
                if (mHasResult) {
                    startIntentSenderForResult(mStartIntent, -1, null,
                            Intent.FLAG_ACTIVITY_FORWARD_RESULT,
                            Intent.FLAG_ACTIVITY_FORWARD_RESULT, 0);
                } else {
                    startIntentSenderForResult(mStartIntent, -1, null, 0, 0, 0);
                }
            } catch (IntentSender.SendIntentException ex) {
                Log.w("HeavyWeightSwitcherActivity", "Failure starting", ex);
            }
            finish();
        }
    };
    
    private OnClickListener mCancelListener = new OnClickListener() {
        public void onClick(View v) {
            finish();
        }
    };
}
