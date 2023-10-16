package com.android.systemui.biometrics

/**
 * Collection of parameters used by EllipseOverlapDetector
 *
 * [minOverlap] minimum percentage (float from 0-1) needed to be considered a valid overlap
 *
 * [targetSize] percentage (defined as a float of 0-1) of sensor that is considered the target,
 * expands outward from center
 *
 * [stepSize] size of each step when iterating over sensor pixel grid
 */
class EllipseOverlapDetectorParams(val minOverlap: Float, val targetSize: Float, val stepSize: Int)
