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
        KLayoutParser parser = new KLayoutParser("com.android.bindingapp",
                Arrays.asList(new File("/Users/yboyar/Documents/git/BindingApp/app/src/main/res")),
                new File("/Users/yboyar/Documents/git/BindingApp/app/build/generated/source/r/debug"),
                new File("/Users/yboyar/Documents/git/BindingApp/app/build/intermediates/res/debug"));
        parser.process();
        parser.writeAttrFile();
        parser.writeBrFile();

        URL jarUrl = new File("/Users/yboyar/android/sdk/platforms/android-21/android.jar").toURI().toURL();
        URLClassLoader androidClassLoader = new URLClassLoader(new URL[]{jarUrl});
        List<File> cpFiles = new ArrayList<>();
        cpFiles.add(new File("/Users/yboyar/Documents/git/BindingApp/app/build/intermediates/classes/debug"));
        cpFiles.add(new File("/Users/yboyar/Documents/git/BindingApp/app/build/intermediates/dependency-cache/debug"));
        cpFiles.add(new File(
                "/Users/yboyar/Documents/git/BindingApp/app/build/intermediates/exploded-aar/com.android.databinding/library/0.1-SNAPSHOT/classes.jar"));
        cpFiles.add(new File(
                "/Users/yboyar/Documents/git/BindingApp/app/build/intermediates/exploded-aar/com.android.support/recyclerview-v7/21.0.0/classes.jar"
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
