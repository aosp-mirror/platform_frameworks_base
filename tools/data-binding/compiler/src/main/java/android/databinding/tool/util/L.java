/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.databinding.tool.util;

import org.apache.commons.lang3.exception.ExceptionUtils;

import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.annotation.AnnotationAnalyzer;

import javax.tools.Diagnostic;

public class L {

    public static void d(String msg, Object... args) {
        printMessage(Diagnostic.Kind.NOTE, String.format(msg, args));
    }

    public static void d(Throwable t, String msg, Object... args) {
        printMessage(Diagnostic.Kind.NOTE,
                String.format(msg, args) + " " + ExceptionUtils.getStackTrace(t));
    }

    public static void e(String msg, Object... args) {
        printMessage(Diagnostic.Kind.ERROR, String.format(msg, args));
    }

    public static void e(Throwable t, String msg, Object... args) {
        printMessage(Diagnostic.Kind.ERROR,
                String.format(msg, args) + " " + ExceptionUtils.getStackTrace(t));
    }

    private static void printMessage(Diagnostic.Kind kind, String message) {
        ModelAnalyzer modelAnalyzer = ModelAnalyzer.getInstance();
        System.out.println("[" + kind.name() + "]: " + message);
        if (modelAnalyzer instanceof AnnotationAnalyzer) {
            ((AnnotationAnalyzer) modelAnalyzer).getProcessingEnv().getMessager()
                    .printMessage(kind, message);
            if (kind == Diagnostic.Kind.ERROR) {
                throw new RuntimeException("failure, see logs for details.\n" + message);
            }
        } else {

            if (kind == Diagnostic.Kind.ERROR) {
                throw new RuntimeException(message);
            }
        }
    }

}
