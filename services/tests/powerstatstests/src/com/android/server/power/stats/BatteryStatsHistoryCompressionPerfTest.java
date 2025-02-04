/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.server.power.stats;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.platform.test.annotations.LargeTest;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import libcore.io.Streams;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.deflate.DeflateCompressorInputStream;
import org.apache.commons.compress.compressors.deflate.DeflateCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import org.apache.commons.compress.compressors.lz4.BlockLZ4CompressorInputStream;
import org.apache.commons.compress.compressors.lz4.BlockLZ4CompressorOutputStream;
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream;
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@RunWith(AndroidJUnit4.class)
@LargeTest
@android.platform.test.annotations.DisabledOnRavenwood(reason = "Performance test")
@Ignore("Performance experiment. Comment out @Ignore to run")
public class BatteryStatsHistoryCompressionPerfTest {

    @Rule
    public final PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Rule
    public final TestName mTestName = new TestName();

    private final List<byte[]> mHistorySamples = new ArrayList<>();

    @Before
    public void loadHistorySamples() throws IOException {
        Context context = InstrumentationRegistry.getContext();
        Resources resources = context.getResources();

        for (String sampleResource
                : List.of("history_01", "history_02", "history_03", "history_04", "history_05")) {
            int resId = resources.getIdentifier(sampleResource, "raw", context.getPackageName());
            try (InputStream stream = resources.openRawResource(resId)) {
                byte[] data = Streams.readFully(stream);
                mHistorySamples.add(data);
            }
        }
    }

    private interface StreamWrapper<T> {
        T wrap(T stream) throws IOException;
    }

    private static class CompressorTester implements BatteryHistoryDirectory.Compressor {
        private final StreamWrapper<OutputStream> mCompressorSupplier;
        private final StreamWrapper<InputStream> mUncompressorSupplier;
        private final ByteArrayOutputStream mOutputStream = new ByteArrayOutputStream(200000);
        private final Random mRandom = new Random();

        private static class Sample {
            public byte[] uncompressed;
            public byte[] compressed;
        }

        private final List<Sample> mSamples;

        CompressorTester(StreamWrapper<OutputStream> compressorSupplier,
                StreamWrapper<InputStream> uncompressorSupplier,
                List<byte[]> uncompressedSamples) throws IOException {
            mCompressorSupplier = compressorSupplier;
            mUncompressorSupplier = uncompressorSupplier;
            mSamples = new ArrayList<>();
            for (byte[] uncompressed : uncompressedSamples) {
                Sample s = new Sample();
                s.uncompressed = Arrays.copyOf(uncompressed, uncompressed.length);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                compress(baos, s.uncompressed);
                s.compressed = baos.toByteArray();
                mSamples.add(s);
            }
        }

        float getCompressionRatio() {
            long totalUncompressed = 0;
            long totalCompressed = 0;
            for (Sample sample : mSamples) {
                totalUncompressed += sample.uncompressed.length;
                totalCompressed += sample.compressed.length;
            }
            return (float) totalUncompressed / totalCompressed;
        }

        void compressSample() throws IOException {
            Sample sample = mSamples.get(mRandom.nextInt(mSamples.size()));
            mOutputStream.reset();
            compress(mOutputStream, sample.uncompressed);
            // Absence of an exception indicates success
        }

        void uncompressSample() throws IOException {
            Sample sample = mSamples.get(mRandom.nextInt(mSamples.size()));
            uncompress(sample.uncompressed, new ByteArrayInputStream(sample.compressed));
            // Absence of an exception indicates success
        }

        @Override
        public void compress(OutputStream stream, byte[] data) throws IOException {
            OutputStream cos = mCompressorSupplier.wrap(stream);
            cos.write(data);
            cos.close();
        }

        @Override
        public void uncompress(byte[] data, InputStream stream) throws IOException {
            InputStream cos = mUncompressorSupplier.wrap(stream);
            readFully(data, cos);
        }
    }

    private void benchmarkCompress(StreamWrapper<OutputStream> compressorSupplier)
            throws IOException {
        CompressorTester tester = new CompressorTester(compressorSupplier, null, mHistorySamples);
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            tester.compressSample();
        }
        Bundle status = new Bundle();
        status.putFloat(mTestName.getMethodName() + "_compressionRatio",
                tester.getCompressionRatio());
        InstrumentationRegistry.getInstrumentation().sendStatus(Activity.RESULT_OK, status);
    }

    private void benchmarkUncompress(StreamWrapper<OutputStream> compressorSupplier,
            StreamWrapper<InputStream> uncompressorSupplier) throws IOException {
        CompressorTester tester = new CompressorTester(compressorSupplier, uncompressorSupplier,
                mHistorySamples);
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            tester.uncompressSample();
        }
    }

    @Test
    public void block_lz4_compress() throws IOException {
        benchmarkCompress(BlockLZ4CompressorOutputStream::new);
    }

    @Test
    public void block_lz4_uncompress() throws IOException {
        benchmarkUncompress(BlockLZ4CompressorOutputStream::new,
                BlockLZ4CompressorInputStream::new);
    }

    @Test
    public void framed_lz4_compress() throws IOException {
        benchmarkCompress(FramedLZ4CompressorOutputStream::new);
    }

    @Test
    public void framed_lz4_uncompress() throws IOException {
        benchmarkUncompress(FramedLZ4CompressorOutputStream::new,
                FramedLZ4CompressorInputStream::new);
    }

    @Test
    public void gzip_compress() throws IOException {
        benchmarkCompress(GzipCompressorOutputStream::new);
    }

    @Test
    public void gzip_uncompress() throws IOException {
        benchmarkUncompress(GzipCompressorOutputStream::new,
                GzipCompressorInputStream::new);
    }

    @Test
    public void best_speed_gzip_compress() throws IOException {
        benchmarkCompress(stream -> {
            GzipParameters parameters = new GzipParameters();
            parameters.setCompressionLevel(Deflater.BEST_SPEED);
            return new GzipCompressorOutputStream(stream, parameters);
        });
    }

    @Test
    public void best_speed_gzip_uncompress() throws IOException {
        benchmarkUncompress(stream -> {
            GzipParameters parameters = new GzipParameters();
            parameters.setCompressionLevel(Deflater.BEST_SPEED);
            return new GzipCompressorOutputStream(stream, parameters);
        }, GzipCompressorInputStream::new);
    }

    @Test
    public void java_util_gzip_compress() throws IOException {
        benchmarkCompress(GZIPOutputStream::new);
    }

    @Test
    public void java_util_gzip_uncompress() throws IOException {
        benchmarkUncompress(GZIPOutputStream::new,
                GZIPInputStream::new);
    }

    @Test
    public void bzip2_compress() throws IOException {
        benchmarkCompress(BZip2CompressorOutputStream::new);
    }

    @Test
    public void bzip2_uncompress() throws IOException {
        benchmarkUncompress(BZip2CompressorOutputStream::new,
                BZip2CompressorInputStream::new);
    }

    @Test
    public void xz_compress() throws IOException {
        benchmarkCompress(XZCompressorOutputStream::new);
    }

    @Test
    public void xz_uncompress() throws IOException {
        benchmarkUncompress(XZCompressorOutputStream::new,
                XZCompressorInputStream::new);
    }

    @Test
    public void deflate_compress() throws IOException {
        benchmarkCompress(DeflateCompressorOutputStream::new);
    }

    @Test
    public void deflate_uncompress() throws IOException {
        benchmarkUncompress(DeflateCompressorOutputStream::new,
                DeflateCompressorInputStream::new);
    }
}
