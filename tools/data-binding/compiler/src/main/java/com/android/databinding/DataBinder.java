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

package com.android.databinding;
import com.android.databinding.store.ResourceBundle;
import com.android.databinding.util.L;
import com.android.databinding.writer.FileWriter;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * The main class that handles parsing files and generating classes.
 */
public class DataBinder {
    List<LayoutBinder> mLayoutBinders = new ArrayList<>();

    private FileWriter mFileWriter;

    public DataBinder(ResourceBundle resourceBundle) {
        L.d("reading resource bundle into data binder");
        for (Map.Entry<String, List<ResourceBundle.LayoutFileBundle>> entry :
                resourceBundle.getLayoutBundles().entrySet()) {
            for (ResourceBundle.LayoutFileBundle bundle : entry.getValue()) {
                mLayoutBinders.add(new LayoutBinder(resourceBundle, bundle));
            }
        }
    }
    public List<LayoutBinder> getLayoutBinders() {
        return mLayoutBinders;
    }
    
    public void writerBinderInterfaces(File outputDir) {
        outputDir.mkdirs();
        Set<String> writtenFiles = new HashSet<>();
        for (LayoutBinder layoutBinder : mLayoutBinders) {
            String interfaceName = layoutBinder.getInterfaceName();
            if (writtenFiles.contains(interfaceName)) {
                continue;
            }
            mFileWriter.writeToFile(new File(outputDir, interfaceName + ".java"),
                    layoutBinder.writeViewBinderInterface());
        }
    }
    
    public void writeBinders(File outputDir) {
        L.d("writing binders to %s", outputDir.getAbsoluteFile());
        for (LayoutBinder layoutBinder : mLayoutBinders) {
            L.d("binder: %s %s %s", layoutBinder.getId(), layoutBinder.getClassName(), layoutBinder.getInterfaceName());
            mFileWriter.writeToFile(new File(outputDir, layoutBinder.getClassName() + ".java"), layoutBinder.writeViewBinder());
        }
    }

    public void setFileWriter(FileWriter fileWriter) {
        mFileWriter = fileWriter;
    }

    public FileWriter getFileWriter() {
        return mFileWriter;
    }
}
