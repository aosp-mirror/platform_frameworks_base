/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.multidexlegacytestapp;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final String TAG = "MultidexLegacyTestApp";
    private int instanceFieldNotInited;
    private int instanceFieldInited =
            new com.android.multidexlegacytestapp.manymethods.Big043().get43();
    private static int staticField =
            new com.android.multidexlegacytestapp.manymethods.Big044().get44();

    public MainActivity() {
        instanceFieldNotInited = new com.android.multidexlegacytestapp.manymethods.Big042().get42();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        int value = getValue();
        ((TextView) findViewById(R.id.label_nb)).setText("Here's the count " + value);

        Log.i(TAG, "Here's the count " + value);
    }

    public int getValue() {
        int value = new com.android.multidexlegacytestapp.manymethods.Big001().get1()
                + new com.android.multidexlegacytestapp.manymethods.Big002().get2()
                + new com.android.multidexlegacytestapp.manymethods.Big003().get3()
                + new com.android.multidexlegacytestapp.manymethods.Big004().get4()
                + new com.android.multidexlegacytestapp.manymethods.Big005().get5()
                + new com.android.multidexlegacytestapp.manymethods.Big006().get6()
                + new com.android.multidexlegacytestapp.manymethods.Big007().get7()
                + new com.android.multidexlegacytestapp.manymethods.Big008().get8()
                + new com.android.multidexlegacytestapp.manymethods.Big009().get9()
                + new com.android.multidexlegacytestapp.manymethods.Big010().get10()
                + new com.android.multidexlegacytestapp.manymethods.Big011().get11()
                + new com.android.multidexlegacytestapp.manymethods.Big012().get12()
                + new com.android.multidexlegacytestapp.manymethods.Big013().get13()
                + new com.android.multidexlegacytestapp.manymethods.Big014().get14()
                + new com.android.multidexlegacytestapp.manymethods.Big015().get15()
                + new com.android.multidexlegacytestapp.manymethods.Big016().get16()
                + new com.android.multidexlegacytestapp.manymethods.Big017().get17()
                + new com.android.multidexlegacytestapp.manymethods.Big018().get18()
                + new com.android.multidexlegacytestapp.manymethods.Big019().get19()
                + new com.android.multidexlegacytestapp.manymethods.Big020().get20()
                + new com.android.multidexlegacytestapp.manymethods.Big021().get21()
                + new com.android.multidexlegacytestapp.manymethods.Big022().get22()
                + new com.android.multidexlegacytestapp.manymethods.Big023().get23()
                + new com.android.multidexlegacytestapp.manymethods.Big024().get24()
                + new com.android.multidexlegacytestapp.manymethods.Big025().get25()
                + new com.android.multidexlegacytestapp.manymethods.Big026().get26()
                + new com.android.multidexlegacytestapp.manymethods.Big027().get27()
                + new com.android.multidexlegacytestapp.manymethods.Big028().get28()
                + new com.android.multidexlegacytestapp.manymethods.Big029().get29()
                + new com.android.multidexlegacytestapp.manymethods.Big030().get30()
                + new com.android.multidexlegacytestapp.manymethods.Big031().get31()
                + new com.android.multidexlegacytestapp.manymethods.Big032().get32()
                + new com.android.multidexlegacytestapp.manymethods.Big033().get33()
                + new com.android.multidexlegacytestapp.manymethods.Big034().get34()
                + new com.android.multidexlegacytestapp.manymethods.Big035().get35()
                + new com.android.multidexlegacytestapp.manymethods.Big036().get36()
                + new com.android.multidexlegacytestapp.manymethods.Big037().get37()
                + new com.android.multidexlegacytestapp.manymethods.Big038().get38()
                + new com.android.multidexlegacytestapp.manymethods.Big039().get39()
                + new com.android.multidexlegacytestapp.manymethods.Big040().get40()
                + new com.android.multidexlegacytestapp.manymethods.Big041().get41()
                + instanceFieldNotInited + instanceFieldInited + staticField
                + IntermediateClass.get() + Referenced.get(instanceFieldNotInited);
        return value;
    }

    public int getAnnotation2Value() {
        return ((AnnotationWithEnum2) TestApplication.annotation2).value().get();
    }

}
