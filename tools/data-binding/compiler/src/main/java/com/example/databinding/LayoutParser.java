/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.databinding;

import com.android.databinding.util.ClassAnalyzer;
import com.android.databinding.KLayoutParser;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LayoutParser {
    public static void main(String[] args) throws MalformedURLException {
        File sampleAppSource = new File("samples/BindingDemo");
        KLayoutParser parser = new KLayoutParser("com.android.bindingapp",
                Arrays.asList(new File("BindingDemo/app/src/main/res")),
                new File(sampleAppSource.getAbsolutePath() + "/app/build/generated/source/r/debug"),
                new File(sampleAppSource.getAbsolutePath() + "/app/build/intermediates/res/debug"));
        parser.process();
        parser.writeAttrFile();
        parser.writeBrFile();

        // TODO get from local.properties
        URL jarUrl = new File("/Users/yboyar/android/sdk/platforms/android-21/android.jar").toURI().toURL();
        URLClassLoader androidClassLoader = new URLClassLoader(new URL[]{jarUrl});
        List<File> cpFiles = new ArrayList<>();
        cpFiles.add(new File(sampleAppSource.getAbsolutePath() + "/app/build/intermediates/classes/debug"));
        cpFiles.add(new File(sampleAppSource.getAbsolutePath() + "/app/build/intermediates/dependency-cache/debug"));
        cpFiles.add(new File(
                sampleAppSource.getAbsolutePath() + "app/build/intermediates/exploded-aar/com.android.databinding/library/0.2-SNAPSHOT/classes.jar"));
        cpFiles.add(new File(
                sampleAppSource.getAbsolutePath() + "app/build/intermediates/exploded-aar/com.android.support/recyclerview-v7/21.0.2/classes.jar"
        ));
        URL[] urls = new URL[cpFiles.size()];
        for (int i = 0; i < cpFiles.size(); i++) {
            urls[i] = cpFiles.get(i).toURI().toURL();
        }
        URLClassLoader classLoader = new URLClassLoader(urls, androidClassLoader);
        parser.writeViewBinderInterfaces();
        parser.setClassAnalyzer(new ClassAnalyzer(classLoader));
        parser.analyzeClasses();
        parser.writeDbrFile();
        parser.writeViewBinders();
    }
}
