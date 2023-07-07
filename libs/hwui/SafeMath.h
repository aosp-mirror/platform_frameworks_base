/*
 * Copyright (C) 2022 The Android Open Source Project
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

#ifndef SkSafeMath_DEFINED
#define SkSafeMath_DEFINED

#include <cstddef>
#include <cstdint>
#include <limits>

// Copy of Skia's SafeMath API used to validate Mesh parameters to support
// deferred creation of SkMesh instances on RenderThread.
// SafeMath always check that a series of operations do not overflow.
// This must be correct for all platforms, because this is a check for safety at runtime.

class SafeMath {
public:
    SafeMath() = default;

    bool ok() const { return fOK; }
    explicit operator bool() const { return fOK; }

    size_t mul(size_t x, size_t y) {
        return sizeof(size_t) == sizeof(uint64_t) ? mul64(x, y) : mul32(x, y);
    }

    size_t add(size_t x, size_t y) {
        size_t result = x + y;
        fOK &= result >= x;
        return result;
    }

    /**
     *  Return a + b, unless this result is an overflow/underflow. In those cases, fOK will
     *  be set to false, and it is undefined what this returns.
     */
    int addInt(int a, int b) {
        if (b < 0 && a < std::numeric_limits<int>::min() - b) {
            fOK = false;
            return a;
        } else if (b > 0 && a > std::numeric_limits<int>::max() - b) {
            fOK = false;
            return a;
        }
        return a + b;
    }

    // These saturate to their results
    static size_t Add(size_t x, size_t y) {
        SafeMath tmp;
        size_t sum = tmp.add(x, y);
        return tmp.ok() ? sum : SIZE_MAX;
    }

    static size_t Mul(size_t x, size_t y) {
        SafeMath tmp;
        size_t prod = tmp.mul(x, y);
        return tmp.ok() ? prod : SIZE_MAX;
    }

private:
    uint32_t mul32(uint32_t x, uint32_t y) {
        uint64_t bx = x;
        uint64_t by = y;
        uint64_t result = bx * by;
        fOK &= result >> 32 == 0;
        // Overflow information is capture in fOK. Return the result modulo 2^32.
        return (uint32_t)result;
    }

    uint64_t mul64(uint64_t x, uint64_t y) {
        if (x <= std::numeric_limits<uint64_t>::max() >> 32 &&
            y <= std::numeric_limits<uint64_t>::max() >> 32) {
            return x * y;
        } else {
            auto hi = [](uint64_t x) { return x >> 32; };
            auto lo = [](uint64_t x) { return x & 0xFFFFFFFF; };

            uint64_t lx_ly = lo(x) * lo(y);
            uint64_t hx_ly = hi(x) * lo(y);
            uint64_t lx_hy = lo(x) * hi(y);
            uint64_t hx_hy = hi(x) * hi(y);
            uint64_t result = 0;
            result = this->add(lx_ly, (hx_ly << 32));
            result = this->add(result, (lx_hy << 32));
            fOK &= (hx_hy + (hx_ly >> 32) + (lx_hy >> 32)) == 0;

            return result;
        }
    }
    bool fOK = true;
};

#endif  // SkSafeMath_DEFINED
