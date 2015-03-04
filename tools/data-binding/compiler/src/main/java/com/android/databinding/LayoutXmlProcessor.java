/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.databinding;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

import com.android.databinding.store.LayoutFileParser;
import com.android.databinding.store.ResourceBundle;
import com.android.databinding.writer.JavaFileWriter;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringEscapeUtils;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;

/**
 * Processes the layout XML, stripping the binding attributes and elements
 * and writes the information into an annotated class file for the annotation
 * processor to work with.
 */
public class LayoutXmlProcessor {

    public static final String RESOURCE_BUNDLE_PACKAGE = "com.android.databinding.layouts.";
    public static final String APPLICATION_INFO_CLASS = "ApplicationBindingInfo";
    private final JavaFileWriter mFileWriter;
    private final ResourceBundle mResourceBundle;
    private final int mMinSdk;

    private boolean mProcessingComplete;
    private boolean mWritten;
    private final String mBuildId = UUID.randomUUID().toString();
    private final List<File> mResourceFolders;

    public LayoutXmlProcessor(String applicationPackage, List<File> resourceFolders,
            JavaFileWriter fileWriter, int minSdk) {
        mFileWriter = fileWriter;
        mResourceBundle = new ResourceBundle(applicationPackage);
        mResourceFolders = resourceFolders;
        mMinSdk = minSdk;
    }

    public boolean processResources()
            throws ParserConfigurationException, SAXException, XPathExpressionException,
            IOException {
        if (mProcessingComplete) {
            return false;
        }
        LayoutFileParser layoutFileParser = new LayoutFileParser();
        int layoutId = 0;
        for (File resFolder : Iterables.filter(mResourceFolders, fileExists)) {
            for (File layoutFolder : resFolder.listFiles(layoutFolderFilter)) {
                for (File xmlFile : layoutFolder.listFiles(xmlFileFilter)) {
                    final ResourceBundle.LayoutFileBundle bindingLayout = layoutFileParser
                            .parseXml(xmlFile, mResourceBundle.getAppPackage(), layoutId);
                    if (bindingLayout != null && !bindingLayout.isEmpty()) {
                        mResourceBundle.addLayoutBundle(bindingLayout, layoutId);
                        layoutId++;
                    }
                }
            }
        }
        mProcessingComplete = true;
        return true;
    }

    public void writeIntermediateFile(File sdkDir) throws JAXBException {
        if (mWritten) {
            return;
        }
        JAXBContext context = JAXBContext.newInstance(ResourceBundle.LayoutFileBundle.class);
        Marshaller marshaller = context.createMarshaller();
        writeAppInfo(marshaller, sdkDir);
        for (List<ResourceBundle.LayoutFileBundle> layouts : mResourceBundle.getLayoutBundles()
                .values()) {
            for (ResourceBundle.LayoutFileBundle layout : layouts) {
                writeAnnotatedFile(layout, marshaller);
            }
        }
        mWritten = true;
    }

    private void writeAnnotatedFile(ResourceBundle.LayoutFileBundle layout, Marshaller marshaller)
            throws JAXBException {
        StringBuilder className = new StringBuilder(layout.getFileName());
        className.append('-').append(layout.getDirectory());
        for (int i = className.length() - 1; i >= 0; i--) {
            char c = className.charAt(i);
            if (c == '-') {
                className.deleteCharAt(i);
                c = Character.toUpperCase(className.charAt(i));
                className.setCharAt(i, c);
            }
        }
        className.setCharAt(0, Character.toUpperCase(className.charAt(0)));
        StringWriter writer = new StringWriter();
        marshaller.marshal(layout, writer);
        String xml = writer.getBuffer().toString();
        String classString = "import android.binding.BinderBundle;\n\n" +
                "@BinderBundle(\"" +
                Base64.encodeBase64String(xml.getBytes(StandardCharsets.UTF_8)) +
                "\")\n" +
                "public class " + className + " {}\n";
        mFileWriter.writeToFile(RESOURCE_BUNDLE_PACKAGE + className, classString);
    }

    private void writeAppInfo(Marshaller marshaller, File sdkDir) {
        final String sdkPath = StringEscapeUtils.escapeJava(sdkDir.getAbsolutePath());
        String classString = "import android.binding.BindingAppInfo;\n\n" +
                "@BindingAppInfo(buildId=\"" + mBuildId + "\", " +
                "applicationPackage=\"" + mResourceBundle.getAppPackage() + "\", " +
                "sdkRoot=\"" + sdkPath + "\", " +
                "minSdk=" + mMinSdk + ")\n" +
                "public class " + APPLICATION_INFO_CLASS + " {}\n";
        mFileWriter.writeToFile(RESOURCE_BUNDLE_PACKAGE + APPLICATION_INFO_CLASS, classString);
    }

    private final Predicate<File> fileExists = new Predicate<File>() {
        @Override
        public boolean apply(File input) {
            return input.exists() && input.canRead();
        }
    };

    private final FilenameFilter layoutFolderFilter = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return name.startsWith("layout");
        }
    };

    private final FilenameFilter xmlFileFilter = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return name.toLowerCase().endsWith(".xml");
        }
    };
}
