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
import com.android.databinding.util.L;
import com.android.databinding.writer.DataBinderWriter;
import com.android.databinding.writer.FileWriter;

import org.apache.commons.io.IOUtils;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;

/**
 * Chef class for compiler.
 *
 * Different build systems can initiate a version of this to handle their work
 */
public class CompilerChef {
    public static final String RESOURCE_BUNDLE_FILE_NAME = "binder_bundle.dat";
    private FileWriter mFileWriter;
    private LayoutFileParser mLayoutFileParser;
    private ResourceBundle mResourceBundle;
    private DataBinder mDataBinder;
    
    private String mAppPackage;
    private List<File> mResourceFolders;

    private CompilerChef() {

    }

    public static CompilerChef createChef(String appPkg, List<File> resourceFolders,
            FileWriter fileWriter) {
        CompilerChef chef = new CompilerChef();
        chef.mAppPackage = appPkg;
        chef.mResourceFolders = resourceFolders;
        chef.mFileWriter = fileWriter;
        return chef;
    }

    public static CompilerChef createChef(InputStream inputStream, FileWriter fileWriter)
            throws IOException, ClassNotFoundException {
        ObjectInputStream ois = null;
        try {
            ois = new ObjectInputStream(inputStream);
            return createChef((ResourceBundle) ois.readObject(), fileWriter);
        } finally {
            IOUtils.closeQuietly(ois);
        }
    }

    public static CompilerChef createChef(ResourceBundle resourceBundle, FileWriter fileWriter) {
        CompilerChef chef = new CompilerChef();
        chef.mResourceBundle = resourceBundle;
        chef.mFileWriter = fileWriter;
        chef.mAppPackage = resourceBundle.getAppPackage();
        return chef;
    }

    public void exportResourceBundle(OutputStream outputStream) {
        ObjectOutputStream oos = null;
        try {
            oos = new ObjectOutputStream(outputStream);
            oos.writeObject(mResourceBundle);
        } catch (IOException e) {
            IOUtils.closeQuietly(oos);
        }
    }

    public boolean processResources()
            throws ParserConfigurationException, SAXException, XPathExpressionException,
            IOException {
        if (mResourceBundle != null) {
            return false; // already processed
        }
        mResourceBundle = new ResourceBundle();
        mResourceBundle.setAppPackage(mAppPackage);
        mLayoutFileParser = new LayoutFileParser();
        int layoutId = 0;
        for (File resFolder : Iterables.filter(mResourceFolders, fileExists)) {
            for (File layoutFolder : resFolder.listFiles(layoutFolderFilter)) {
                for (File xmlFile : layoutFolder.listFiles(xmlFileFilter)) {
                    final ResourceBundle.LayoutFileBundle bundle = mLayoutFileParser
                            .parseXml(xmlFile, mAppPackage, layoutId);
                    if (bundle != null && !bundle.isEmpty()) {
                        mResourceBundle.addLayoutBundle(bundle, layoutId);
                        layoutId ++;
                    }
                }
            }
        }
        mResourceBundle.validateMultiResLayouts();
        return true;
    }
    
    public void ensureDataBinder() {
        if (mDataBinder == null) {
            mDataBinder = new DataBinder(mResourceBundle);
            mDataBinder.setFileWriter(mFileWriter);
        }
    }
    
    public boolean hasAnythingToGenerate() {
        L.d("checking if we have anything to genreate. bundle size: %s",
                mResourceBundle == null ? -1 : mResourceBundle.getLayoutBundles().size());
        return mResourceBundle != null && mResourceBundle.getLayoutBundles().size() > 0;
    }

    public void writeDbrFile() {
        ensureDataBinder();
        final String pkg = "com.android.databinding.library";
        DataBinderWriter dbr = new DataBinderWriter(pkg, mResourceBundle.getAppPackage(),
                "GeneratedDataBinderRenderer", mDataBinder.getLayoutBinders());
        if (dbr.getLayoutBinders().size() > 0) {
            mFileWriter.writeToFile(dbr.getPkg() + "." + dbr.getClassName(), dbr.write());
        }
    }
    
    public void writeViewBinderInterfaces() {
        ensureDataBinder();
        mDataBinder.writerBinderInterfaces();
    }
    
    public void writeViewBinders() {
        ensureDataBinder();
        mDataBinder.writeBinders();
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

    private final FilenameFilter xmlFileFilter =  new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return name.toLowerCase().endsWith(".xml");
        }
    };
}
