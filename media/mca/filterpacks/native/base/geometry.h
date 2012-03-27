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

#ifndef ANDROID_FILTERFW_FILTERPACKS_BASE_GEOMETRY_H
#define ANDROID_FILTERFW_FILTERPACKS_BASE_GEOMETRY_H

#include <vector>

namespace android {
namespace filterfw {

// This is an initial implementation of some geometrical structures. This is
// likely to grow and become more sophisticated in the future.

class Point {
  public:
    Point() : x_(0.0f), y_(0.0f) {}
    Point(float x, float y) : x_(x), y_(y) {}

    float x() const { return x_; }
    float y() const { return y_; }

    float Length() const;
    bool ScaleTo(float new_length);
    static float Distance(const Point& p0, const Point& p1);

    // Add more of these as needed:
    Point operator+(const Point& other) const;
    Point operator-(const Point& other) const;
    Point operator*(float factor) const;

    void Rotate90Clockwise();

  private:
    float x_, y_;
};

class Quad {
  public:
    Quad() : points_(4) {}
    virtual ~Quad() {}

    Quad(const Point& p0, const Point& p1, const Point& p2, const Point& p3)
        : points_(4) {
      points_[0] = p0;
      points_[1] = p1;
      points_[2] = p2;
      points_[3] = p3;
    }

    const std::vector<Point>& points() const { return points_; }
    const Point& point(int ix) const;

  protected:
    std::vector<Point> points_;
};

class SlantedRect : public Quad {
  public:
    SlantedRect() : width_(0.0f), height_(0.0f) {}
    virtual ~SlantedRect() {}

    bool FromCenterAxisAndLengths(const Point& center,
                                  const Point& vert_axis,
                                  const Point& lenghts);

    float width() const { return width_; }
    float height() const { return height_; }

  private:
    float width_;
    float height_;
};

struct Rect {
  float x, y, width, height;

  Rect() {
    x = y = 0.0f;
    width = height = 1.0f;
  }

  Rect(float x, float y, float width, float height) {
    this->x = x;
    this->y = y;
    this->width = width;
    this->height = height;
  }

  bool ExpandToAspectRatio(float ratio);
  bool ExpandToMinLength(float length);
  bool ScaleWithLengthLimit(float factor, float max_length);
};

} // namespace filterfw
} // namespace android

#endif // ANDROID_FILTERFW_FILTERPACKS_BASE_GEOMETRY_H
