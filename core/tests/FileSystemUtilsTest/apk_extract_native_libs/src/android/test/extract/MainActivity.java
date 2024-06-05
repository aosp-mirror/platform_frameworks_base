/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.test.extract;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.VisibleForTesting;

public class MainActivity extends Activity {

    static {
        System.loadLibrary("punchtest");
    }

    @VisibleForTesting
    static final String INTENT_TYPE = "android.test.extract.EXTRACTED_LIB_LOADED";

    @VisibleForTesting
    static final String KEY_OPERAND_1 = "OP1";

    @VisibleForTesting
    static final String KEY_OPERAND_2 = "OP2";

    @VisibleForTesting
    static final String KEY_RESULT = "RESULT";

    @Override
    public void onCreate(Bundle savedOnstanceState) {
        super.onCreate(savedOnstanceState);

        Intent received =  getIntent();
        int op1 = received.getIntExtra(KEY_OPERAND_1, -1);
        int op2 = received.getIntExtra(KEY_OPERAND_2, -1);
        int result = subtract(op1, op2);

        // Send broadcast so that test can know app has launched and lib is loaded
        // attach result which has been fetched from JNI lib
        Intent intent = new Intent(INTENT_TYPE);
        intent.putExtra(KEY_RESULT, result);
        sendBroadcast(intent);
    }

    private native int subtract(int op1, int op2);
}
