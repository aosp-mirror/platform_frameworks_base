/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include <cutils/log.h>
#include <cmath>

#include "geometry.h"

namespace android {
namespace filterfw {

float Point::Length() const {
  return std::sqrt(x_ * x_ + y_ * y_);
}

bool Point::ScaleTo(float new_length) {
  float length = Length();
  if (length == 0.0f) {
    return false;
  }
  x_ *= new_length / length;
  y_ *= new_length / length;
  return true;
}

float Point::Distance(const Point& p0, const Point& p1) {
  Point diff = p1 - p0;
  return diff.Length();
}

Point Point::operator+(const Point& other) const {
  Point out;
  out.x_ = x_ + other.x_;
  out.y_ = y_ + other.y_;
  return out;
}

Point Point::operator-(const Point& other) const {
  Point out;
  out.x_ = x_ - other.x_;
  out.y_ = y_ - other.y_;
  return out;
}

Point Point::operator*(float factor) const {
  Point out;
  out.x_ = factor * x_;
  out.y_ = factor * y_;
  return out;
}

void Point::Rotate90Clockwise() {
  const float x = x_;
  x_ = y_;
  y_ = -x;
}

bool Rect::ExpandToAspectRatio(float ratio) {
  if (width <= 0.0f || height <= 0.0f || ratio <= 0.0f) {
    return false;
  }

  const float current_ratio = width / height;
  if (current_ratio < ratio) {
    const float dx = width * (ratio / current_ratio - 1.0f);
    x -= dx / 2.0f;
    width += dx;
  } else {
    const float dy = height * (current_ratio / ratio - 1.0f);
    y -= dy / 2.0f;
    height += dy;
  }
  return true;
}

bool Rect::ExpandToMinLength(float length) {
  if (width <= 0.0f || height <= 0.0f || length <= 0.0f) {
    return false;
  }

  const float current_length = width > height ? width : height;
  if (length > current_length) {
    const float dx = width * (length / current_length - 1.0f);
    x -= dx / 2.0f;
    width += dx;
    const float dy = height * (length / current_length - 1.0f);
    y -= dy / 2.0f;
    height += dy;
  }
  return true;
}

bool Rect::ScaleWithLengthLimit(float factor, float max_length) {
  if (width <= 0.0f || height <= 0.0f || factor <= 0.0f) {
    return false;
  }

  const float current_length = width > height ? width : height;
  if (current_length >= max_length) {
    return true;
  }

  float f = factor;
  if (current_length * f > max_length) {
    f *= max_length / (current_length * f);
  }

  const float dx = width * (f - 1.0f);
  x -= dx / 2.0f;
  width += dx;
  const float dy = height * (f - 1.0f);
  y -= dy / 2.0f;
  height += dy;
  return true;
}

const Point& Quad::point(int ix) const {
  ALOG_ASSERT(ix < static_cast<int>(points_.size()), "Access out of bounds");
  return points_[ix];
}

bool SlantedRect::FromCenterAxisAndLengths(const Point& center,
                                           const Point& vert_axis,
                                           const Point& lengths) {
  Point dy = vert_axis;
  if (!dy.ScaleTo(lengths.y() / 2.0f)) {
    ALOGE("Illegal axis: %f %f", vert_axis.x(), vert_axis.y());
    return false;
  }

  Point dx = dy;
  dx.Rotate90Clockwise();
  dx.ScaleTo(lengths.x() / 2.0f);

  points_[0] = center - dx - dy;
  points_[1] = center + dx - dy;
  points_[2] = center - dx + dy;
  points_[3] = center + dx + dy;

  width_ = lengths.x();
  height_ = lengths.y();

  return true;
}

} // namespace filterfw
} // namespace android
