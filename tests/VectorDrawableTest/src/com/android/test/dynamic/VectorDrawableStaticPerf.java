/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.android.test.dynamic;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.drawable.VectorDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Button;
import android.widget.GridLayout;

@SuppressWarnings({"UnusedDeclaration"})
public class VectorDrawableStaticPerf extends VectorDrawablePerformance {
    {
        icon = new int[]{
                R.drawable.vector_icon_create,
                R.drawable.vector_icon_delete,
                R.drawable.vector_icon_heart,
                R.drawable.vector_icon_schedule,
                R.drawable.vector_icon_settings,
        };
    }
}
