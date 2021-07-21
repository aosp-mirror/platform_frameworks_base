/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include "benchmark/benchmark.h"

#include "androidfw/CursorWindow.h"

namespace android {

static void BM_CursorWindowWrite(benchmark::State& state, size_t rows, size_t cols) {
    CursorWindow* w;
    CursorWindow::create(String8("test"), 1 << 21, &w);

    while (state.KeepRunning()) {
        w->clear();
        w->setNumColumns(cols);
        for (int row = 0; row < rows; row++) {
            w->allocRow();
            for (int col = 0; col < cols; col++) {
                w->putLong(row, col, 0xcafe);
            }
        }
    }
}

static void BM_CursorWindowWrite4x4(benchmark::State& state) {
    BM_CursorWindowWrite(state, 4, 4);
}
BENCHMARK(BM_CursorWindowWrite4x4);

static void BM_CursorWindowWrite1Kx4(benchmark::State& state) {
    BM_CursorWindowWrite(state, 1024, 4);
}
BENCHMARK(BM_CursorWindowWrite1Kx4);

static void BM_CursorWindowWrite16Kx4(benchmark::State& state) {
    BM_CursorWindowWrite(state, 16384, 4);
}
BENCHMARK(BM_CursorWindowWrite16Kx4);

static void BM_CursorWindowRead(benchmark::State& state, size_t rows, size_t cols) {
    CursorWindow* w;
    CursorWindow::create(String8("test"), 1 << 21, &w);
    w->setNumColumns(cols);
    for (int row = 0; row < rows; row++) {
        w->allocRow();
    }

    while (state.KeepRunning()) {
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                w->getFieldSlot(row, col);
            }
        }
    }
}

static void BM_CursorWindowRead4x4(benchmark::State& state) {
    BM_CursorWindowRead(state, 4, 4);
}
BENCHMARK(BM_CursorWindowRead4x4);

static void BM_CursorWindowRead1Kx4(benchmark::State& state) {
    BM_CursorWindowRead(state, 1024, 4);
}
BENCHMARK(BM_CursorWindowRead1Kx4);

static void BM_CursorWindowRead16Kx4(benchmark::State& state) {
    BM_CursorWindowRead(state, 16384, 4);
}
BENCHMARK(BM_CursorWindowRead16Kx4);

}  // namespace android
