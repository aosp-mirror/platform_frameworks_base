/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the
 * License.
 *
 */

package com.android.benchmark.registry;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.util.Xml;

import com.android.benchmark.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 */
public class BenchmarkRegistry {

    /** Metadata key for benchmark XML data */
    private static final String BENCHMARK_GROUP_META_KEY =
            "com.android.benchmark.benchmark_group";

    /** Intent action specifying an activity that runs a single benchmark test. */
    private static final String ACTION_BENCHMARK = "com.android.benchmark.ACTION_BENCHMARK";
    public static final String EXTRA_ID = "com.android.benchmark.EXTRA_ID";

    private static final String TAG_BENCHMARK_GROUP = "com.android.benchmark.BenchmarkGroup";
    private static final String TAG_BENCHMARK = "com.android.benchmark.Benchmark";

    private List<BenchmarkGroup> mGroups;

    private final Context mContext;

    public BenchmarkRegistry(Context context) {
        mContext = context;
        mGroups = new ArrayList<>();
        loadBenchmarks();
    }

    private Intent getIntentFromInfo(ActivityInfo inf) {
        Intent intent = new Intent();
        intent.setClassName(inf.packageName, inf.name);
        return intent;
    }

    public void loadBenchmarks() {
        Intent intent = new Intent(ACTION_BENCHMARK);
        intent.setPackage(mContext.getPackageName());

        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent,
                PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA);

        for (ResolveInfo inf : resolveInfos) {
            List<BenchmarkGroup> groups = parseBenchmarkGroup(inf.activityInfo);
            if (groups != null) {
                mGroups.addAll(groups);
            }
        }
    }

    private boolean seekToTag(XmlPullParser parser, String tag)
            throws XmlPullParserException, IOException {
        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.START_TAG && eventType != XmlPullParser.END_DOCUMENT) {
            eventType = parser.next();
        }
        return eventType != XmlPullParser.END_DOCUMENT && tag.equals(parser.getName());
    }

    @BenchmarkCategory int getCategory(int category) {
        switch (category) {
            case BenchmarkCategory.COMPUTE:
                return BenchmarkCategory.COMPUTE;
            case BenchmarkCategory.UI:
                return BenchmarkCategory.UI;
            default:
                return BenchmarkCategory.GENERIC;
        }
    }

    private List<BenchmarkGroup> parseBenchmarkGroup(ActivityInfo activityInfo) {
        PackageManager pm = mContext.getPackageManager();

        ComponentName componentName = new ComponentName(
                activityInfo.packageName, activityInfo.name);

        SparseArray<List<BenchmarkGroup.Benchmark>> benchmarks = new SparseArray<>();
        String groupName, groupDescription;
        try (XmlResourceParser parser = activityInfo.loadXmlMetaData(pm, BENCHMARK_GROUP_META_KEY)) {

            if (!seekToTag(parser, TAG_BENCHMARK_GROUP)) {
                return null;
            }

            Resources res = pm.getResourcesForActivity(componentName);
            AttributeSet attributeSet = Xml.asAttributeSet(parser);
            TypedArray groupAttribs = res.obtainAttributes(attributeSet, R.styleable.BenchmarkGroup);

            groupName = groupAttribs.getString(R.styleable.BenchmarkGroup_name);
            groupDescription = groupAttribs.getString(R.styleable.BenchmarkGroup_description);
            groupAttribs.recycle();
            parser.next();

            while (seekToTag(parser, TAG_BENCHMARK)) {
                TypedArray benchAttribs =
                        res.obtainAttributes(Xml.asAttributeSet(parser), R.styleable.Benchmark);
                int id = benchAttribs.getResourceId(R.styleable.Benchmark_id, -1);
                String testName = benchAttribs.getString(R.styleable.Benchmark_name);
                String testDescription = benchAttribs.getString(R.styleable.Benchmark_description);
                int testCategory = benchAttribs.getInt(R.styleable.Benchmark_category,
                        BenchmarkCategory.GENERIC);
                int category = getCategory(testCategory);
                BenchmarkGroup.Benchmark benchmark = new BenchmarkGroup.Benchmark(
                        id, testName, category, testDescription);
                List<BenchmarkGroup.Benchmark> benches = benchmarks.get(category);
                if (benches == null) {
                    benches = new ArrayList<>();
                    benchmarks.append(category, benches);
                }

                benches.add(benchmark);

                benchAttribs.recycle();
                parser.next();
            }
        } catch (PackageManager.NameNotFoundException | XmlPullParserException | IOException e) {
            return null;
        }

        List<BenchmarkGroup> result = new ArrayList<>();
        Intent testIntent = getIntentFromInfo(activityInfo);
        for (int i = 0; i < benchmarks.size(); i++) {
            int cat = benchmarks.keyAt(i);
            List<BenchmarkGroup.Benchmark> thisGroup = benchmarks.get(cat);
            BenchmarkGroup.Benchmark[] benchmarkArray =
                    new BenchmarkGroup.Benchmark[thisGroup.size()];
            thisGroup.toArray(benchmarkArray);
            result.add(new BenchmarkGroup(componentName,
                    groupName + " - " + getCategoryString(cat), groupDescription, benchmarkArray,
                    testIntent));
        }

        return result;
    }

    public int getGroupCount() {
        return mGroups.size();
    }

    public int getBenchmarkCount(int benchmarkIndex) {
        BenchmarkGroup group = getBenchmarkGroup(benchmarkIndex);
        if (group != null) {
            return group.getBenchmarks().length;
        }
        return 0;
    }

    public BenchmarkGroup getBenchmarkGroup(int benchmarkIndex) {
        if (benchmarkIndex >= mGroups.size()) {
            return null;
        }

        return mGroups.get(benchmarkIndex);
    }

    public static String getCategoryString(int category) {
        switch (category) {
            case BenchmarkCategory.UI:
                return "UI";
            case BenchmarkCategory.COMPUTE:
                return "Compute";
            case BenchmarkCategory.GENERIC:
                return "Generic";
            default:
                return "";
        }
    }

    public static String getBenchmarkName(Context context, int benchmarkId) {
        switch (benchmarkId) {
            case R.id.benchmark_list_view_scroll:
                return context.getString(R.string.list_view_scroll_name);
            case R.id.benchmark_image_list_view_scroll:
                return context.getString(R.string.image_list_view_scroll_name);
            case R.id.benchmark_shadow_grid:
                return context.getString(R.string.shadow_grid_name);
            case R.id.benchmark_text_high_hitrate:
                return context.getString(R.string.text_high_hitrate_name);
            case R.id.benchmark_text_low_hitrate:
                return context.getString(R.string.text_low_hitrate_name);
            case R.id.benchmark_edit_text_input:
                return context.getString(R.string.edit_text_input_name);
            case R.id.benchmark_memory_bandwidth:
                return context.getString(R.string.memory_bandwidth_name);
            case R.id.benchmark_memory_latency:
                return context.getString(R.string.memory_latency_name);
            case R.id.benchmark_power_management:
                return context.getString(R.string.power_management_name);
            case R.id.benchmark_cpu_heat_soak:
                return context.getString(R.string.cpu_heat_soak_name);
            case R.id.benchmark_cpu_gflops:
                return context.getString(R.string.cpu_gflops_name);
            case R.id.benchmark_overdraw:
                return context.getString(R.string.overdraw_name);
            default:
                return "Some Benchmark";
        }
    }
}
