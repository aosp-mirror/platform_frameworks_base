/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.javac;

import com.google.common.io.Files;

import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.JavaClass;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Helper class for compiling snippets of Java source and providing access to the resulting class
 * files.
 */
public class Javac {

    private final JavaCompiler mJavac;
    private final StandardJavaFileManager mFileMan;
    private final List<JavaFileObject> mCompilationUnits;
    private final File mClassOutDir;

    public Javac() throws IOException {
        mJavac = ToolProvider.getSystemJavaCompiler();
        mFileMan = mJavac.getStandardFileManager(null, Locale.US, null);
        mClassOutDir = Files.createTempDir();
        mFileMan.setLocation(StandardLocation.CLASS_OUTPUT, Arrays.asList(mClassOutDir));
        mFileMan.setLocation(StandardLocation.CLASS_PATH, Arrays.asList(mClassOutDir));
        mCompilationUnits = new ArrayList<>();
    }

    private String classToFileName(String classname) {
        return classname.replace('.', '/');
    }

    public Javac addSource(String classname, String contents) {
        JavaFileObject java = new SimpleJavaFileObject(URI.create(
                String.format("string:///%s.java", classToFileName(classname))),
                JavaFileObject.Kind.SOURCE
                ){
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                return contents;
            }
        };
        mCompilationUnits.add(java);
        return this;
    }

    public boolean compile() {
        JavaCompiler.CompilationTask task = mJavac.getTask(
                null,
                mFileMan,
                null,
                null,
                null,
                mCompilationUnits);
        return task.call();
    }

    public InputStream getClassFile(String classname) throws IOException {
        Iterable<? extends JavaFileObject> objs = mFileMan.getJavaFileObjects(
                new File(mClassOutDir, String.format("%s.class", classToFileName(classname))));
        if (!objs.iterator().hasNext()) {
            return null;
        }
        return objs.iterator().next().openInputStream();
    }

    public JavaClass getCompiledClass(String classname) throws IOException {
        return new ClassParser(getClassFile(classname),
                String.format("%s.class", classToFileName(classname))).parse();
    }
}
