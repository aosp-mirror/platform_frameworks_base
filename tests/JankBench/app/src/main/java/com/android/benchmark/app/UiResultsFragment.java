/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.benchmark.app;

import android.annotation.TargetApi;
import android.os.AsyncTask;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.ListFragment;
import android.util.Log;
import android.view.FrameMetrics;
import android.widget.SimpleAdapter;

import com.android.benchmark.R;
import com.android.benchmark.registry.BenchmarkGroup;
import com.android.benchmark.registry.BenchmarkRegistry;
import com.android.benchmark.results.GlobalResultsStore;
import com.android.benchmark.results.UiBenchmarkResult;

import org.apache.commons.math.stat.descriptive.SummaryStatistics;

import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@TargetApi(24)
public class UiResultsFragment extends ListFragment {
    private static final String TAG = "UiResultsFragment";
    private static final int NUM_FIELDS = 20;

    private ArrayList<UiBenchmarkResult> mResults = new ArrayList<>();

    private AsyncTask<Void, Void, ArrayList<Map<String, String>>> mLoadScoresTask =
            new AsyncTask<Void, Void, ArrayList<Map<String, String>>>() {
        @Override
        protected ArrayList<Map<String, String>> doInBackground(Void... voids) {
            String[] data;
            if (mResults.size() == 0 || mResults.get(0) == null) {
                data = new String[] {
                        "No metrics reported", ""
                };
            } else {
                data = new String[NUM_FIELDS * (1 + mResults.size()) + 2];
                SummaryStatistics stats = new SummaryStatistics();
                int totalFrameCount = 0;
                double totalAvgFrameDuration = 0;
                double total99FrameDuration = 0;
                double total95FrameDuration = 0;
                double total90FrameDuration = 0;
                double totalLongestFrame = 0;
                double totalShortestFrame = 0;

                for (int i = 0; i < mResults.size(); i++) {
                    int start = (i * NUM_FIELDS) + + NUM_FIELDS;
                    data[(start++)] = "Iteration";
                    data[(start++)] = "" + i;
                    data[(start++)] = "Total Frames";
                    int currentFrameCount = mResults.get(i).getTotalFrameCount();
                    totalFrameCount += currentFrameCount;
                    data[(start++)] = Integer.toString(currentFrameCount);
                    data[(start++)] = "Average frame duration:";
                    double currentAvgFrameDuration = mResults.get(i).getAverage(FrameMetrics.TOTAL_DURATION);
                    totalAvgFrameDuration += currentAvgFrameDuration;
                    data[(start++)] = String.format("%.2f", currentAvgFrameDuration);
                    data[(start++)] = "Frame duration 99th:";
                    double current99FrameDuration = mResults.get(i).getPercentile(FrameMetrics.TOTAL_DURATION, 99);
                    total99FrameDuration += current99FrameDuration;
                    data[(start++)] = String.format("%.2f", current99FrameDuration);
                    data[(start++)] = "Frame duration 95th:";
                    double current95FrameDuration = mResults.get(i).getPercentile(FrameMetrics.TOTAL_DURATION, 95);
                    total95FrameDuration += current95FrameDuration;
                    data[(start++)] = String.format("%.2f", current95FrameDuration);
                    data[(start++)] = "Frame duration 90th:";
                    double current90FrameDuration = mResults.get(i).getPercentile(FrameMetrics.TOTAL_DURATION, 90);
                    total90FrameDuration += current90FrameDuration;
                    data[(start++)] = String.format("%.2f", current90FrameDuration);
                    data[(start++)] = "Longest frame:";
                    double longestFrame = mResults.get(i).getMaximum(FrameMetrics.TOTAL_DURATION);
                    if (totalLongestFrame == 0 || longestFrame > totalLongestFrame) {
                        totalLongestFrame = longestFrame;
                    }
                    data[(start++)] = String.format("%.2f", longestFrame);
                    data[(start++)] = "Shortest frame:";
                    double shortestFrame = mResults.get(i).getMinimum(FrameMetrics.TOTAL_DURATION);
                    if (totalShortestFrame == 0 || totalShortestFrame > shortestFrame) {
                        totalShortestFrame = shortestFrame;
                    }
                    data[(start++)] = String.format("%.2f", shortestFrame);
                    data[(start++)] = "Score:";
                    double score = mResults.get(i).getScore();
                    stats.addValue(score);
                    data[(start++)] = String.format("%.2f", score);
                    data[(start++)] = "==============";
                    data[(start++)] = "============================";
                };

                int start = 0;
                data[0] = "Overall: ";
                data[1] = "";
                data[(start++)] = "Total Frames";
                data[(start++)] = Integer.toString(totalFrameCount);
                data[(start++)] = "Average frame duration:";
                data[(start++)] = String.format("%.2f", totalAvgFrameDuration / mResults.size());
                data[(start++)] = "Frame duration 99th:";
                data[(start++)] = String.format("%.2f", total99FrameDuration / mResults.size());
                data[(start++)] = "Frame duration 95th:";
                data[(start++)] = String.format("%.2f", total95FrameDuration / mResults.size());
                data[(start++)] = "Frame duration 90th:";
                data[(start++)] = String.format("%.2f", total90FrameDuration / mResults.size());
                data[(start++)] = "Longest frame:";
                data[(start++)] = String.format("%.2f", totalLongestFrame);
                data[(start++)] = "Shortest frame:";
                data[(start++)] = String.format("%.2f", totalShortestFrame);
                data[(start++)] = "Score:";
                data[(start++)] = String.format("%.2f", stats.getGeometricMean());
                data[(start++)] = "==============";
                data[(start++)] = "============================";
            }

            ArrayList<Map<String, String>> dataMap = new ArrayList<>();
            for (int i = 0; i < data.length - 1; i += 2) {
                HashMap<String, String> map = new HashMap<>();
                map.put("name", data[i]);
                map.put("value", data[i + 1]);
                dataMap.add(map);
            }

            return dataMap;
        }

        @Override
        protected void onPostExecute(ArrayList<Map<String, String>> dataMap) {
            setListAdapter(new SimpleAdapter(getActivity(), dataMap, R.layout.results_list_item,
                    new String[] {"name", "value"}, new int[] { R.id.result_name, R.id.result_value }));
            setListShown(true);
        }
    };

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setListShown(false);
        mLoadScoresTask.execute();
    }

    public void setRunInfo(String name, int runId) {
        mResults = GlobalResultsStore.getInstance(getActivity()).loadTestResults(name, runId);
    }
}
