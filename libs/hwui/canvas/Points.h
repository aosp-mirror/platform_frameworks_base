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
#include <ui/FatVector.h>
#include "SkPoint.h"
#include "SkRefCnt.h"

/**
 * Collection of points that are ref counted and to be used with
 * various drawing calls that consume SkPoint as inputs like
 * drawLines/drawPoints
 */
class Points: public SkNVRefCnt<SkPoint> {
public:
  Points(int size){
      skPoints.resize(size);
  }

  Points(std::initializer_list<SkPoint> init): skPoints(init) { }

  SkPoint& operator[](int index) {
      return skPoints[index];
  }

  const SkPoint* data() const {
      return skPoints.data();
  }

  size_t size() const {
      return skPoints.size();
  }
private:
  // Initialize the size to contain 2 SkPoints on the stack for optimized
  // drawLine calls that require 2 SkPoints for start/end points of the line
  android::FatVector<SkPoint, 2> skPoints;
};
