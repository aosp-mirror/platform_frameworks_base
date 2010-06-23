/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_RECT_H
#define ANDROID_RECT_H

namespace android {

///////////////////////////////////////////////////////////////////////////////
// Structs
///////////////////////////////////////////////////////////////////////////////

struct Rect {
	float left;
	float top;
	float right;
	float bottom;

	Rect(): left(0), top(0), right(0), bottom(0) { }

	Rect(const Rect& r) {
		set(r);
	}

	Rect(Rect& r) {
		set(r);
	}

	Rect& operator=(const Rect& r) {
		set(r);
		return *this;
	}

	Rect& operator=(Rect& r) {
		set(r);
		return *this;
	}

	friend int operator==(const Rect& a, const Rect& b) {
		return !memcmp(&a, &b, sizeof(a));
	}

	friend int operator!=(const Rect& a, const Rect& b) {
		return memcmp(&a, &b, sizeof(a));
	}

	bool isEmpty() const {
		return left >= right || top >= bottom;
	}

	void setEmpty() {
		memset(this, 0, sizeof(*this));
	}

	void set(float left, float top, float right, float bottom) {
		this->left = left;
		this->right = right;
		this->top = top;
		this->bottom = bottom;
	}

	void set(const Rect& r) {
		set(r.left, r.top, r.right, r.bottom);
	}

	float getWidth() const {
		return right - left;
	}

	float getHeight() const {
		return bottom - top;
	}

	bool intersects(float left, float top, float right, float bottom) const {
		return left < right && top < bottom &&
				this->left < this->right && this->top < this->bottom &&
			    this->left < right && left < this->right &&
			    this->top < bottom && top < this->bottom;
	}

	bool intersects(const Rect& r) const {
		return intersects(r.left, r.top, r.right, r.bottom);
	}

	bool intersect(float left, float top, float right, float bottom) {
		if (left < right && top < bottom && !this->isEmpty() &&
		        this->left < right && left < this->right &&
		        this->top < bottom && top < this->bottom) {

			if (this->left < left) this->left = left;
			if (this->top < top) this->top = top;
			if (this->right > right) this->right = right;
			if (this->bottom > bottom) this->bottom = bottom;

			return true;
		}
		return false;
	}

	bool intersect(const Rect& r) {
		return intersect(r.left, r.top, r.right, r.bottom);
	}

	void dump() const {
		LOGD("Rect[l=%f t=%f r=%f b=%f]", left, top, right, bottom);
	}

}; // struct Rect

}; // namespace android

#endif // ANDROID_RECT_H
