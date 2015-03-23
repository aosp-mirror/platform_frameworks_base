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

package android.databinding.tool;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

import org.apache.commons.lang3.StringEscapeUtils;
import org.xml.sax.SAXException;

import android.databinding.BindingBuildInfo;
import android.databinding.tool.store.LayoutFileParser;
import android.databinding.tool.store.ResourceBundle;
import android.databinding.tool.writer.JavaFileWriter;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
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
    // hardcoded in baseAdapters
    public static final String RESOURCE_BUNDLE_PACKAGE = "android.databinding.layouts";
    public static final String CLASS_NAME = "DataBindingInfo";
    private final JavaFileWriter mFileWriter;
    private final ResourceBundle mResourceBundle;
    private final int mMinSdk;

    private boolean mProcessingComplete;
    private boolean mWritten;
    private final boolean mIsLibrary;
    private final String mBuildId = UUID.randomUUID().toString();
    // can be a list of xml files or folders that contain XML files
    private final List<File> mResources;

    public LayoutXmlProcessor(String applicationPackage, List<File> resources,
            JavaFileWriter fileWriter, int minSdk, boolean isLibrary) {
        mFileWriter = fileWriter;
        mResourceBundle = new ResourceBundle(applicationPackage);
        mResources = resources;
        mMinSdk = minSdk;
        mIsLibrary = isLibrary;
    }

    public static List<File> getLayoutFiles(List<File> resources) {
        List<File> result = new ArrayList<File>();
        for (File resource : Iterables.filter(resources, fileExists)) {
            if (resource.isDirectory()) {
                for (File layoutFolder : resource.listFiles(layoutFolderFilter)) {
                    for (File xmlFile : layoutFolder.listFiles(xmlFileFilter)) {
                        result.add(xmlFile);
                    }

                }
            } else if (xmlFileFilter.accept(resource.getParentFile(), resource.getName())) {
                result.add(resource);
            }
        }
        return result;
    }

    /**
     * used by the studio plugin
     */
    public ResourceBundle getResourceBundle() {
        return mResourceBundle;
    }

    public boolean processResources()
            throws ParserConfigurationException, SAXException, XPathExpressionException,
            IOException {
        if (mProcessingComplete) {
            return false;
        }
        LayoutFileParser layoutFileParser = new LayoutFileParser();
        int layoutId = 0;
        for (File xmlFile : getLayoutFiles(mResources)) {
            final ResourceBundle.LayoutFileBundle bindingLayout = layoutFileParser
                    .parseXml(xmlFile, mResourceBundle.getAppPackage(), layoutId);
            if (bindingLayout != null && !bindingLayout.isEmpty()) {
                mResourceBundle.addLayoutBundle(bindingLayout, layoutId);
                layoutId++;
            }
        }
        mProcessingComplete = true;
        return true;
    }

    public void writeIntermediateFile(File sdkDir, File xmlOutDir) throws JAXBException {
        if (mWritten) {
            return;
        }
        JAXBContext context = JAXBContext.newInstance(ResourceBundle.LayoutFileBundle.class);
        Marshaller marshaller = context.createMarshaller();
        writeInfoClass(sdkDir, xmlOutDir);
        for (List<ResourceBundle.LayoutFileBundle> layouts : mResourceBundle.getLayoutBundles()
                .values()) {
            for (ResourceBundle.LayoutFileBundle layout : layouts) {
                writeXmlFile(xmlOutDir, layout, marshaller);
            }
        }
        mWritten = true;
    }

    private void writeXmlFile(File xmlOutDir, ResourceBundle.LayoutFileBundle layout,
            Marshaller marshaller) throws JAXBException {
        String filename = generateExportFileName(layout) + ".xml";
        String xml = toXML(layout, marshaller);
        mFileWriter.writeToFile(new File(xmlOutDir, filename), xml);
    }

    public String getInfoClassFullName() {
        return RESOURCE_BUNDLE_PACKAGE + "." + CLASS_NAME;
    }

    private String toXML(ResourceBundle.LayoutFileBundle layout, Marshaller marshaller)
            throws JAXBException {
        StringWriter writer = new StringWriter();
        marshaller.marshal(layout, writer);
        return writer.getBuffer().toString();
    }

    /**
     * Generates a string identifier that can uniquely identify the given layout bundle.
     * This identifier can be used when we need to export data about this layout bundle.
     */
    private String generateExportFileName(ResourceBundle.LayoutFileBundle layout) {
        StringBuilder name = new StringBuilder(layout.getFileName());
        name.append('-').append(layout.getDirectory());
        for (int i = name.length() - 1; i >= 0; i--) {
            char c = name.charAt(i);
            if (c == '-') {
                name.deleteCharAt(i);
                c = Character.toUpperCase(name.charAt(i));
                name.setCharAt(i, c);
            }
        }
        return name.toString();
    }

    private void writeInfoClass(File sdkDir, File xmlOutDir) {
        final String sdkPath = StringEscapeUtils.escapeJava(sdkDir.getAbsolutePath());
        final Class annotation = BindingBuildInfo.class;
        final String layoutInfoPath = StringEscapeUtils.escapeJava(xmlOutDir.getAbsolutePath());
        String classString = "package " + RESOURCE_BUNDLE_PACKAGE + ";\n\n" +
                "import " + annotation.getCanonicalName() + ";\n\n" +
                "@" + annotation.getSimpleName() + "(buildId=\"" + mBuildId + "\", " +
                "modulePackage=\"" + mResourceBundle.getAppPackage() + "\", " +
                "sdkRoot=\"" + sdkPath + "\", " +
                "layoutInfoDir=\"" + layoutInfoPath + "\"," +
                "isLibrary=" + mIsLibrary + "," +
                "minSdk=" + mMinSdk + ")\n" +
                "public class " + CLASS_NAME + " {}\n";
        mFileWriter.writeToFile(RESOURCE_BUNDLE_PACKAGE + "." + CLASS_NAME, classString);
    }

    private static final Predicate<File> fileExists = new Predicate<File>() {
        @Override
        public boolean apply(File input) {
            return input.exists() && input.canRead();
        }
    };

    private static final FilenameFilter layoutFolderFilter = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return name.startsWith("layout");
        }
    };

    private static final FilenameFilter xmlFileFilter = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return name.toLowerCase().endsWith(".xml");
        }
    };
}
