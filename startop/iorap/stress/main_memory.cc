//
// Copyright (C) 2020 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

#include <chrono>
#include <fstream>
#include <iostream>
#include <random>
#include <string>

#include <string.h>
#include <stdlib.h>
#include <sys/mman.h>

#include <android-base/parseint.h>

static constexpr size_t kBytesPerMb = 1048576;
const size_t kMemoryAllocationSize = 2 * 1024 * kBytesPerMb;

#define USE_MLOCKALL 0

std::string GetProcessStatus(const char* key) {
  // Build search pattern of key and separator.
  std::string pattern(key);
  pattern.push_back(':');

  // Search for status lines starting with pattern.
  std::ifstream fs("/proc/self/status");
  std::string line;
  while (std::getline(fs, line)) {
    if (strncmp(pattern.c_str(), line.c_str(), pattern.size()) == 0) {
      // Skip whitespace in matching line (if any).
      size_t pos = line.find_first_not_of(" \t", pattern.size());
      if (pos == std::string::npos) {
        break;
      }
      return std::string(line, pos);
    }
  }
  return "<unknown>";
}

int main(int argc, char** argv) {
  size_t allocationSize = 0;
  if (argc >= 2) {
    if (!android::base::ParseUint(argv[1], /*out*/&allocationSize)) {
      std::cerr << "Failed to parse the allocation size (must be 0,MAX_SIZE_T)" << std::endl;
      return 1;
    }
  } else {
    allocationSize = kMemoryAllocationSize;
  }

  void* mem = malloc(allocationSize);
  if (mem == nullptr) {
    std::cerr << "Malloc failed" << std::endl;
    return 1;
  }

  volatile int* imem = static_cast<int *>(mem);  // don't optimize out memory usage

  size_t imemCount = allocationSize / sizeof(int);

  std::cout << "Allocated " << allocationSize << " bytes" << std::endl;

  auto seed = std::chrono::high_resolution_clock::now().time_since_epoch().count();
  std::mt19937 mt_rand(seed);

  size_t randPrintCount = 10;

  // Write random numbers:
  // * Ensures each page is resident
  // * Avoids zeroed out pages (zRAM)
  // * Avoids same-page merging
  for (size_t i = 0; i < imemCount; ++i) {
    imem[i] = mt_rand();

    if (i < randPrintCount) {
      std::cout << "Generated random value: " << imem[i] << std::endl;
    }
  }

#if USE_MLOCKALL
  /*
   * Lock all pages from the address space of this process.
   */
  if (mlockall(MCL_CURRENT | MCL_FUTURE) != 0) {
    std::cerr << "Mlockall failed" << std::endl;
    return 1;
  }
#else
  // Use mlock because of the predictable VmLck size.
  // Using mlockall tends to bring in anywhere from 2-2.5GB depending on the device.
  if (mlock(mem, allocationSize) != 0) {
    std::cerr << "Mlock failed" << std::endl;
    return 1;
  }
#endif

  // Validate memory is actually resident and locked with:
  // $> cat /proc/$(pidof iorap.stress.memory)/status | grep VmLck
  std::cout << "Locked memory (VmLck) = " << GetProcessStatus("VmLck") << std::endl;

  std::cout << "Press any key to terminate" << std::endl;
  int any_input;
  std::cin >> any_input;

  std::cout << "Terminating..." << std::endl;

  munlockall();
  free(mem);

  return 0;
}
