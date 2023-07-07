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

package com.android.benchmark.app;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.fragment.app.ListFragment;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.android.benchmark.R;
import com.android.benchmark.registry.BenchmarkGroup;
import com.android.benchmark.registry.BenchmarkRegistry;
import com.android.benchmark.results.GlobalResultsStore;
import com.android.benchmark.results.UiBenchmarkResult;
import com.android.benchmark.synthetic.MemoryActivity;
import com.android.benchmark.ui.BitmapUploadActivity;
import com.android.benchmark.ui.EditTextInputActivity;
import com.android.benchmark.ui.FullScreenOverdrawActivity;
import com.android.benchmark.ui.ImageListViewScrollActivity;
import com.android.benchmark.ui.ListViewScrollActivity;
import com.android.benchmark.ui.ShadowGridActivity;
import com.android.benchmark.ui.TextScrollActivity;

import org.apache.commons.math.stat.descriptive.SummaryStatistics;

import java.util.ArrayList;
import java.util.HashMap;

public class RunLocalBenchmarksActivity extends AppCompatActivity {

    public static final int RUN_COUNT = 5;

    private ArrayList<LocalBenchmark> mBenchmarksToRun;
    private int mBenchmarkCursor;
    private int mCurrentRunId;
    private boolean mFinish;

    private Handler mHandler = new Handler();

    private static final int[] ALL_TESTS = new int[] {
            R.id.benchmark_list_view_scroll,
            R.id.benchmark_image_list_view_scroll,
            R.id.benchmark_shadow_grid,
            R.id.benchmark_text_high_hitrate,
            R.id.benchmark_text_low_hitrate,
            R.id.benchmark_edit_text_input,
            R.id.benchmark_overdraw,
            R.id.benchmark_bitmap_upload,
    };

    public static class LocalBenchmarksList extends ListFragment {
        private ArrayList<LocalBenchmark> mBenchmarks;
        private int mRunId;

        public void setBenchmarks(ArrayList<LocalBenchmark> benchmarks) {
            mBenchmarks = benchmarks;
        }

        public void setRunId(int id) {
            mRunId = id;
        }

        @Override
        public void onListItemClick(ListView l, View v, int position, long id) {
            if (getActivity().findViewById(R.id.list_fragment_container) != null) {
                FragmentManager fm = getActivity().getSupportFragmentManager();
                UiResultsFragment resultsView = new UiResultsFragment();
                String testName = BenchmarkRegistry.getBenchmarkName(v.getContext(),
                        mBenchmarks.get(position).id);
                resultsView.setRunInfo(testName, mRunId);
                FragmentTransaction fragmentTransaction = fm.beginTransaction();
                fragmentTransaction.replace(R.id.list_fragment_container, resultsView);
                fragmentTransaction.addToBackStack(null);
                fragmentTransaction.commit();
            }
        }
    }


    private class LocalBenchmark {
        int id;
        int runCount = 0;
        int totalCount = 0;
        ArrayList<String> mResultsUri = new ArrayList<>();

        LocalBenchmark(int id, int runCount) {
            this.id = id;
            this.runCount = 0;
            this.totalCount = runCount;
        }

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_running_list);

        initLocalBenchmarks(getIntent());

        if (findViewById(R.id.list_fragment_container) != null) {
            FragmentManager fm = getSupportFragmentManager();
            LocalBenchmarksList listView = new LocalBenchmarksList();
            listView.setListAdapter(new LocalBenchmarksListAdapter(LayoutInflater.from(this)));
            listView.setBenchmarks(mBenchmarksToRun);
            listView.setRunId(mCurrentRunId);
            fm.beginTransaction().add(R.id.list_fragment_container, listView).commit();
        }

        TextView scoreView = (TextView) findViewById(R.id.score_text_view);
        scoreView.setText("Running tests!");
    }

    private int translateBenchmarkIndex(int index) {
        if (index >= 0 && index < ALL_TESTS.length) {
            return ALL_TESTS[index];
        }

        return -1;
    }

    private void initLocalBenchmarks(Intent intent) {
        mBenchmarksToRun = new ArrayList<>();
        int[] enabledIds = intent.getIntArrayExtra(BenchmarkGroup.BENCHMARK_EXTRA_ENABLED_TESTS);
        int runCount = intent.getIntExtra(BenchmarkGroup.BENCHMARK_EXTRA_RUN_COUNT, RUN_COUNT);
        mFinish = intent.getBooleanExtra(BenchmarkGroup.BENCHMARK_EXTRA_FINISH, false);

        if (enabledIds == null) {
            // run all tests
            enabledIds = ALL_TESTS;
        }

        StringBuilder idString = new StringBuilder();
        idString.append(runCount);
        idString.append(System.currentTimeMillis());

        for (int i = 0; i < enabledIds.length; i++) {
            int id = enabledIds[i];
            System.out.println("considering " + id);
            if (!isValidBenchmark(id)) {
                System.out.println("not valid " + id);
                id = translateBenchmarkIndex(id);
                System.out.println("got out " + id);
                System.out.println("expected: " + R.id.benchmark_overdraw);
            }

            if (isValidBenchmark(id)) {
                int localRunCount = runCount;
                if (isCompute(id)) {
                    localRunCount = 1;
                }
                mBenchmarksToRun.add(new LocalBenchmark(id, localRunCount));
                idString.append(id);
            }
        }

        mBenchmarkCursor = 0;
        mCurrentRunId = idString.toString().hashCode();
    }

    private boolean isCompute(int id) {
        switch (id) {
            case R.id.benchmark_cpu_gflops:
            case R.id.benchmark_cpu_heat_soak:
            case R.id.benchmark_memory_bandwidth:
            case R.id.benchmark_memory_latency:
            case R.id.benchmark_power_management:
                return true;
            default:
                return false;
        }
    }

    private static boolean isValidBenchmark(int benchmarkId) {
        switch (benchmarkId) {
            case R.id.benchmark_list_view_scroll:
            case R.id.benchmark_image_list_view_scroll:
            case R.id.benchmark_shadow_grid:
            case R.id.benchmark_text_high_hitrate:
            case R.id.benchmark_text_low_hitrate:
            case R.id.benchmark_edit_text_input:
            case R.id.benchmark_overdraw:
            case R.id.benchmark_bitmap_upload:
            case R.id.benchmark_memory_bandwidth:
            case R.id.benchmark_memory_latency:
            case R.id.benchmark_power_management:
            case R.id.benchmark_cpu_heat_soak:
            case R.id.benchmark_cpu_gflops:
                return true;
            default:
                return false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                runNextBenchmark();
            }
        }, 1000);
    }

    private void computeOverallScore() {
        final TextView scoreView = (TextView) findViewById(R.id.score_text_view);
        scoreView.setText("Computing score...");
        new AsyncTask<Void, Void, Integer>()  {
            @Override
            protected Integer doInBackground(Void... voids) {
                GlobalResultsStore gsr =
                        GlobalResultsStore.getInstance(RunLocalBenchmarksActivity.this);
                ArrayList<Double> testLevelScores = new ArrayList<>();
                final SummaryStatistics stats = new SummaryStatistics();
                for (LocalBenchmark b : mBenchmarksToRun) {
                    HashMap<String, ArrayList<UiBenchmarkResult>> detailedResults =
                            gsr.loadDetailedResults(mCurrentRunId);
                    for (ArrayList<UiBenchmarkResult> testResult : detailedResults.values()) {
                        for (UiBenchmarkResult res : testResult) {
                            int score = res.getScore();
                            if (score == 0) {
                                score = 1;
                            }
                            stats.addValue(score);
                        }

                        testLevelScores.add(stats.getGeometricMean());
                        stats.clear();
                    }

                }

                for (double score : testLevelScores) {
                    stats.addValue(score);
                }

                return (int)Math.round(stats.getGeometricMean());
            }

            @Override
            protected void onPostExecute(Integer score) {
                TextView view = (TextView)
                        RunLocalBenchmarksActivity.this.findViewById(R.id.score_text_view);
                view.setText("Score: " + score);
            }
        }.execute();
    }

    private void runNextBenchmark() {
        LocalBenchmark benchmark = mBenchmarksToRun.get(mBenchmarkCursor);
        boolean runAgain = false;

        if (benchmark.runCount < benchmark.totalCount) {
            runBenchmarkForId(mBenchmarksToRun.get(mBenchmarkCursor).id, benchmark.runCount++);
        } else if (mBenchmarkCursor + 1 < mBenchmarksToRun.size()) {
            mBenchmarkCursor++;
            benchmark = mBenchmarksToRun.get(mBenchmarkCursor);
            runBenchmarkForId(benchmark.id, benchmark.runCount++);
        } else if (runAgain) {
            mBenchmarkCursor = 0;
            initLocalBenchmarks(getIntent());

            runBenchmarkForId(mBenchmarksToRun.get(mBenchmarkCursor).id, benchmark.runCount);
        } else if (mFinish) {
            finish();
        } else {
            Log.i("BENCH", "BenchmarkDone!");
            computeOverallScore();
        }
    }

    private void runBenchmarkForId(int id, int iteration) {
        Intent intent;
        int syntheticTestId = -1;

        System.out.println("iteration: " + iteration);

        switch (id) {
            case R.id.benchmark_list_view_scroll:
                intent = new Intent(getApplicationContext(), ListViewScrollActivity.class);
                break;
            case R.id.benchmark_image_list_view_scroll:
                intent = new Intent(getApplicationContext(), ImageListViewScrollActivity.class);
                break;
            case R.id.benchmark_shadow_grid:
                intent = new Intent(getApplicationContext(), ShadowGridActivity.class);
                break;
            case R.id.benchmark_text_high_hitrate:
                intent = new Intent(getApplicationContext(), TextScrollActivity.class);
                intent.putExtra(TextScrollActivity.EXTRA_HIT_RATE, 80);
                intent.putExtra(BenchmarkRegistry.EXTRA_ID, id);
                break;
            case R.id.benchmark_text_low_hitrate:
                intent = new Intent(getApplicationContext(), TextScrollActivity.class);
                intent.putExtra(TextScrollActivity.EXTRA_HIT_RATE, 20);
                intent.putExtra(BenchmarkRegistry.EXTRA_ID, id);
                break;
            case R.id.benchmark_edit_text_input:
                intent = new Intent(getApplicationContext(), EditTextInputActivity.class);
                break;
            case R.id.benchmark_overdraw:
                intent = new Intent(getApplicationContext(), FullScreenOverdrawActivity.class);
                break;
            case R.id.benchmark_bitmap_upload:
                intent = new Intent(getApplicationContext(), BitmapUploadActivity.class);
                break;
            case R.id.benchmark_memory_bandwidth:
                syntheticTestId = 0;
                intent = new Intent(getApplicationContext(), MemoryActivity.class);
                intent.putExtra("test", syntheticTestId);
                break;
            case R.id.benchmark_memory_latency:
                syntheticTestId = 1;
                intent = new Intent(getApplicationContext(), MemoryActivity.class);
                intent.putExtra("test", syntheticTestId);
                break;
            case R.id.benchmark_power_management:
                syntheticTestId = 2;
                intent = new Intent(getApplicationContext(), MemoryActivity.class);
                intent.putExtra("test", syntheticTestId);
                break;
            case R.id.benchmark_cpu_heat_soak:
                syntheticTestId = 3;
                intent = new Intent(getApplicationContext(), MemoryActivity.class);
                intent.putExtra("test", syntheticTestId);
                break;
            case R.id.benchmark_cpu_gflops:
                syntheticTestId = 4;
                intent = new Intent(getApplicationContext(), MemoryActivity.class);
                intent.putExtra("test", syntheticTestId);
                break;

            default:
               intent = null;
        }

        if (intent != null) {
            intent.putExtra("com.android.benchmark.RUN_ID", mCurrentRunId);
            intent.putExtra("com.android.benchmark.ITERATION", iteration);
            startActivityForResult(intent, id & 0xffff, null);
        }
    }

    @SuppressWarnings("MissingSuperCall") // TODO: Fix me
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case R.id.benchmark_shadow_grid:
            case R.id.benchmark_list_view_scroll:
            case R.id.benchmark_image_list_view_scroll:
            case R.id.benchmark_text_high_hitrate:
            case R.id.benchmark_text_low_hitrate:
            case R.id.benchmark_edit_text_input:
                break;
            default:
        }
    }

    class LocalBenchmarksListAdapter extends BaseAdapter {

        private final LayoutInflater mInflater;

        LocalBenchmarksListAdapter(LayoutInflater inflater) {
            mInflater = inflater;
        }

        @Override
        public int getCount() {
            return mBenchmarksToRun.size();
        }

        @Override
        public Object getItem(int i) {
            return mBenchmarksToRun.get(i);
        }

        @Override
        public long getItemId(int i) {
            return mBenchmarksToRun.get(i).id;
        }

        @Override
        public View getView(int i, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.running_benchmark_list_item, null);
            }

            TextView name = (TextView) convertView.findViewById(R.id.benchmark_name);
            name.setText(BenchmarkRegistry.getBenchmarkName(
                    RunLocalBenchmarksActivity.this, mBenchmarksToRun.get(i).id));
            return convertView;
        }

    }
}
