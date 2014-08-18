/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;

public class ChooserActivity extends ResolverActivity {
    private Bundle mReplacementExtras;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Intent intent = getIntent();
        Parcelable targetParcelable = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        if (!(targetParcelable instanceof Intent)) {
            Log.w("ChooserActivity", "Target is not an intent: " + targetParcelable);
            finish();
            super.onCreate(null);
            return;
        }
        Intent target = (Intent)targetParcelable;
        if (target != null) {
            modifyTargetIntent(target);
        }
        mReplacementExtras = intent.getBundleExtra(Intent.EXTRA_REPLACEMENT_EXTRAS);
        CharSequence title = intent.getCharSequenceExtra(Intent.EXTRA_TITLE);
        int defaultTitleRes = 0;
        if (title == null) {
            defaultTitleRes = com.android.internal.R.string.chooseActivity;
        }
        Parcelable[] pa = intent.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS);
        Intent[] initialIntents = null;
        if (pa != null) {
            initialIntents = new Intent[pa.length];
            for (int i=0; i<pa.length; i++) {
                if (!(pa[i] instanceof Intent)) {
                    Log.w("ChooserActivity", "Initial intent #" + i + " not an Intent: " + pa[i]);
                    finish();
                    super.onCreate(null);
                    return;
                }
                final Intent in = (Intent) pa[i];
                modifyTargetIntent(in);
                initialIntents[i] = in;
            }
        }
        setSafeForwardingMode(true);
        super.onCreate(savedInstanceState, target, title, defaultTitleRes, initialIntents,
                null, false);
    }

    public Intent getReplacementIntent(String packageName, Intent defIntent) {
        if (mReplacementExtras != null) {
            final Bundle replExtras = mReplacementExtras.getBundle(packageName);
            if (replExtras != null) {
                final Intent result = new Intent(defIntent);
                result.putExtras(replExtras);
                return result;
            }
        }
        return defIntent;
    }

    private void modifyTargetIntent(Intent in) {
        final String action = in.getAction();
        if (Intent.ACTION_SEND.equals(action) ||
                Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            in.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT |
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        }
    }
}
