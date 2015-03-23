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

package android.databinding.annotationprocessor;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import android.databinding.BindingBuildInfo;
import android.databinding.tool.CompilerChef;
import android.databinding.tool.reflection.SdkUtil;
import android.databinding.tool.store.ResourceBundle;
import android.databinding.tool.util.GenerationalClassUtil;
import android.databinding.tool.util.L;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

public class ProcessExpressions extends ProcessDataBinding.ProcessingStep {

    private static final String LAYOUT_INFO_FILE_SUFFIX = "-layoutinfo.bin";

    @Override
    public boolean onHandleStep(RoundEnvironment roundEnvironment,
            ProcessingEnvironment processingEnvironment, BindingBuildInfo buildInfo) {
        ResourceBundle resourceBundle;
        SdkUtil.initialize(buildInfo.minSdk(), new File(buildInfo.sdkRoot()));
        resourceBundle = new ResourceBundle(buildInfo.modulePackage());
        List<Intermediate> intermediateList =
                GenerationalClassUtil.loadObjects(getClass().getClassLoader(),
                        new GenerationalClassUtil.ExtensionFilter(LAYOUT_INFO_FILE_SUFFIX));
        IntermediateV1 mine = createIntermediateFromLayouts(buildInfo.layoutInfoDir());
        if (mine != null) {
            mine.removeOverridden(intermediateList);
            intermediateList.add(mine);
            saveIntermediate(processingEnvironment, buildInfo, mine);
        }
        // generate them here so that bindable parser can read
        try {
            generateBinders(resourceBundle, buildInfo, intermediateList);
        } catch (Throwable t) {
            L.e(t, "cannot generate view binders");
        }
        return true;
    }

    private void saveIntermediate(ProcessingEnvironment processingEnvironment,
            BindingBuildInfo buildInfo, IntermediateV1 intermediate) {
        GenerationalClassUtil.writeIntermediateFile(processingEnvironment,
                buildInfo.modulePackage(), buildInfo.modulePackage() + LAYOUT_INFO_FILE_SUFFIX,
                intermediate);
    }

    @Override
    public void onProcessingOver(RoundEnvironment roundEnvironment,
            ProcessingEnvironment processingEnvironment, BindingBuildInfo buildInfo) {

    }

    private void generateBinders(ResourceBundle resourceBundle, BindingBuildInfo buildInfo,
            List<Intermediate> intermediates)
            throws Throwable {
        for (Intermediate intermediate : intermediates) {
            intermediate.appendTo(resourceBundle);
        }
        writeResourceBundle(resourceBundle, buildInfo.isLibrary());
    }

    private IntermediateV1 createIntermediateFromLayouts(String layoutInfoFolderPath) {
        final File layoutInfoFolder = new File(layoutInfoFolderPath);
        if (!layoutInfoFolder.isDirectory()) {
            L.d("layout info folder does not exist, skipping for %s", layoutInfoFolderPath);
            return null;
        }
        IntermediateV1 result = new IntermediateV1();
        for (File layoutFile : layoutInfoFolder.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".xml");
            }
        })) {
            try {
                result.addEntry(layoutFile.getName(), FileUtils.readFileToString(layoutFile));
            } catch (IOException e) {
                L.e(e, "cannot load layout file information. Try a clean build");
            }
        }
        return result;
    }

    private void writeResourceBundle(ResourceBundle resourceBundle, boolean forLibraryModule)
            throws JAXBException {
        CompilerChef compilerChef = CompilerChef.createChef(resourceBundle, getWriter());
        if (compilerChef.hasAnythingToGenerate()) {
            compilerChef.writeViewBinderInterfaces();
            if (!forLibraryModule) {
                compilerChef.writeDbrFile();
                compilerChef.writeViewBinders();
            }
        }
    }

    public static interface Intermediate extends Serializable {

        Intermediate upgrade();

        public void appendTo(ResourceBundle resourceBundle) throws Throwable;
    }

    public static class IntermediateV1 implements Intermediate {

        transient Unmarshaller mUnmarshaller;

        // name to xml content map
        Map<String, String> mLayoutInfoMap = new HashMap<>();

        @Override
        public Intermediate upgrade() {
            return this;
        }

        @Override
        public void appendTo(ResourceBundle resourceBundle) throws JAXBException {
            if (mUnmarshaller == null) {
                JAXBContext context = JAXBContext
                        .newInstance(ResourceBundle.LayoutFileBundle.class);
                mUnmarshaller = context.createUnmarshaller();
            }
            for (String content : mLayoutInfoMap.values()) {
                final InputStream is = IOUtils.toInputStream(content);
                try {
                    final ResourceBundle.LayoutFileBundle bundle
                            = (ResourceBundle.LayoutFileBundle) mUnmarshaller.unmarshal(is);
                    resourceBundle.addLayoutBundle(bundle, bundle.getLayoutId());
                    L.d("loaded layout info file %s", bundle);
                } finally {
                    IOUtils.closeQuietly(is);
                }
            }
        }

        public void addEntry(String name, String contents) {
            mLayoutInfoMap.put(name, contents);
        }

        public void removeOverridden(List<Intermediate> existing) {
            // this is the way we get rid of files that are copied from previous modules
            // it is important to do this before saving the intermediate file
            for (Intermediate old : existing) {
                if (old instanceof IntermediateV1) {
                    IntermediateV1 other = (IntermediateV1) old;
                    for (String key : other.mLayoutInfoMap.keySet()) {
                        // TODO we should consider the original file as the key here
                        // but aapt probably cannot provide that information
                        if (mLayoutInfoMap.remove(key) != null) {
                            L.d("removing %s from bundle because it came from another module", key);
                        }
                    }
                }
            }
        }
    }
}
