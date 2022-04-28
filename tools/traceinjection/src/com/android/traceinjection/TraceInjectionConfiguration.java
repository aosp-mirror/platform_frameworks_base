/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.traceinjection;

/**
 * Configuration data for trace method injection.
 */
public class TraceInjectionConfiguration {
    public final String annotation;
    public final String startMethodClass;
    public final String startMethodName;
    public final String endMethodClass;
    public final String endMethodName;

    public TraceInjectionConfiguration(String annotation, String startMethod, String endMethod) {
        this.annotation = annotation;
        String[] startMethodComponents = parseMethod(startMethod);
        String[] endMethodComponents = parseMethod(endMethod);
        startMethodClass = startMethodComponents[0];
        startMethodName = startMethodComponents[1];
        endMethodClass = endMethodComponents[0];
        endMethodName = endMethodComponents[1];
    }

    public String toString() {
        return "TraceInjectionParams{annotation=" + annotation
                + ", startMethod=" + startMethodClass + "." + startMethodName
                + ", endMethod=" + endMethodClass + "." + endMethodName + "}";
    }

    private static String[] parseMethod(String method) {
        String[] methodComponents = method.split("\\.");
        if (methodComponents.length != 2) {
            throw new IllegalArgumentException("Invalid method descriptor: " + method);
        }
        return methodComponents;
    }
}
