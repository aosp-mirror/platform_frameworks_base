/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.processor.unsupportedappusage;

import com.google.common.base.Splitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CsvReader {

    private final Splitter mSplitter;
    private final List<String> mColumns;
    private final List<Map<String, String>> mContents;

    public CsvReader(InputStream in) throws IOException {
        mSplitter = Splitter.on(",");
        BufferedReader br = new BufferedReader(new InputStreamReader(in));
        mColumns = mSplitter.splitToList(br.readLine());
        mContents = new ArrayList<>();
        String line = br.readLine();
        while (line != null) {
            List<String> contents = mSplitter.splitToList(line);
            Map<String, String> contentMap = new HashMap<>();
            for (int i = 0; i < Math.min(contents.size(), mColumns.size()); ++i) {
                contentMap.put(mColumns.get(i), contents.get(i));
            }
            mContents.add(contentMap);
            line = br.readLine();
        }
        br.close();
    }

    public List<String> getColumns() {
        return mColumns;
    }

    public List<Map<String, String>> getContents() {
        return mContents;
    }
}
