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

import androidx.multidex.MultiDexApplication;

import java.lang.annotation.Annotation;

@AnnotationWithEnum(ReferencedByAnnotation.B)
public class TestApplication extends MultiDexApplication {

    public static Annotation annotation = getAnnotationWithEnum();
    public static Annotation annotation2 = getSoleAnnotation(Annotated.class);
    public static Annotation annotation3 = getSoleAnnotation(Annotated2.class);
    public static Class<?> interfaceClass = InterfaceWithEnum.class;

    public static Annotation getAnnotationWithEnum() {
        return getSoleAnnotation(TestApplication.class);
    }

    public static Annotation getSoleAnnotation(Class<?> annotated) {
        Annotation[] annot = annotated.getAnnotations();
        if (annot.length == 1) {
            return annot[0];
        }

        throw new AssertionError();
    }

}
