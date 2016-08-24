/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "VectorDrawableUtils.h"

#include "PathParser.h"

#include <math.h>
#include <utils/Log.h>

namespace android {
namespace uirenderer {

class PathResolver {
public:
    float currentX = 0;
    float currentY = 0;
    float ctrlPointX = 0;
    float ctrlPointY = 0;
    float currentSegmentStartX = 0;
    float currentSegmentStartY = 0;
    void addCommand(SkPath* outPath, char previousCmd,
            char cmd, const std::vector<float>* points, size_t start, size_t end);
};

bool VectorDrawableUtils::canMorph(const PathData& morphFrom, const PathData& morphTo) {
    if (morphFrom.verbs.size() != morphTo.verbs.size()) {
        return false;
    }

    for (unsigned int i = 0; i < morphFrom.verbs.size(); i++) {
        if (morphFrom.verbs[i] != morphTo.verbs[i]
                ||  morphFrom.verbSizes[i] != morphTo.verbSizes[i]) {
            return false;
        }
    }
    return true;
}

bool VectorDrawableUtils::interpolatePathData(PathData* outData, const PathData& morphFrom,
        const PathData& morphTo, float fraction) {
    if (!canMorph(morphFrom, morphTo)) {
        return false;
    }
    interpolatePaths(outData, morphFrom, morphTo, fraction);
    return true;
}

 /**
 * Convert an array of PathVerb to Path.
 */
void VectorDrawableUtils::verbsToPath(SkPath* outPath, const PathData& data) {
    PathResolver resolver;
    char previousCommand = 'm';
    size_t start = 0;
    outPath->reset();
    for (unsigned int i = 0; i < data.verbs.size(); i++) {
        size_t verbSize = data.verbSizes[i];
        resolver.addCommand(outPath, previousCommand, data.verbs[i], &data.points, start,
                start + verbSize);
        previousCommand = data.verbs[i];
        start += verbSize;
    }
}

/**
 * The current PathVerb will be interpolated between the
 * <code>nodeFrom</code> and <code>nodeTo</code> according to the
 * <code>fraction</code>.
 *
 * @param nodeFrom The start value as a PathVerb.
 * @param nodeTo The end value as a PathVerb
 * @param fraction The fraction to interpolate.
 */
void VectorDrawableUtils::interpolatePaths(PathData* outData,
        const PathData& from, const PathData& to, float fraction) {
    outData->points.resize(from.points.size());
    outData->verbSizes = from.verbSizes;
    outData->verbs = from.verbs;

    for (size_t i = 0; i < from.points.size(); i++) {
        outData->points[i] = from.points[i] * (1 - fraction) + to.points[i] * fraction;
    }
}

/**
 * Converts an arc to cubic Bezier segments and records them in p.
 *
 * @param p The target for the cubic Bezier segments
 * @param cx The x coordinate center of the ellipse
 * @param cy The y coordinate center of the ellipse
 * @param a The radius of the ellipse in the horizontal direction
 * @param b The radius of the ellipse in the vertical direction
 * @param e1x E(eta1) x coordinate of the starting point of the arc
 * @param e1y E(eta2) y coordinate of the starting point of the arc
 * @param theta The angle that the ellipse bounding rectangle makes with horizontal plane
 * @param start The start angle of the arc on the ellipse
 * @param sweep The angle (positive or negative) of the sweep of the arc on the ellipse
 */
static void arcToBezier(SkPath* p,
        double cx,
        double cy,
        double a,
        double b,
        double e1x,
        double e1y,
        double theta,
        double start,
        double sweep) {
    // Taken from equations at: http://spaceroots.org/documents/ellipse/node8.html
    // and http://www.spaceroots.org/documents/ellipse/node22.html

    // Maximum of 45 degrees per cubic Bezier segment
    int numSegments = ceil(fabs(sweep * 4 / M_PI));

    double eta1 = start;
    double cosTheta = cos(theta);
    double sinTheta = sin(theta);
    double cosEta1 = cos(eta1);
    double sinEta1 = sin(eta1);
    double ep1x = (-a * cosTheta * sinEta1) - (b * sinTheta * cosEta1);
    double ep1y = (-a * sinTheta * sinEta1) + (b * cosTheta * cosEta1);

    double anglePerSegment = sweep / numSegments;
    for (int i = 0; i < numSegments; i++) {
        double eta2 = eta1 + anglePerSegment;
        double sinEta2 = sin(eta2);
        double cosEta2 = cos(eta2);
        double e2x = cx + (a * cosTheta * cosEta2) - (b * sinTheta * sinEta2);
        double e2y = cy + (a * sinTheta * cosEta2) + (b * cosTheta * sinEta2);
        double ep2x = -a * cosTheta * sinEta2 - b * sinTheta * cosEta2;
        double ep2y = -a * sinTheta * sinEta2 + b * cosTheta * cosEta2;
        double tanDiff2 = tan((eta2 - eta1) / 2);
        double alpha =
                sin(eta2 - eta1) * (sqrt(4 + (3 * tanDiff2 * tanDiff2)) - 1) / 3;
        double q1x = e1x + alpha * ep1x;
        double q1y = e1y + alpha * ep1y;
        double q2x = e2x - alpha * ep2x;
        double q2y = e2y - alpha * ep2y;

        p->cubicTo((float) q1x,
                (float) q1y,
                (float) q2x,
                (float) q2y,
                (float) e2x,
                (float) e2y);
        eta1 = eta2;
        e1x = e2x;
        e1y = e2y;
        ep1x = ep2x;
        ep1y = ep2y;
    }
}

inline double toRadians(float theta) { return theta * M_PI / 180;}

static void drawArc(SkPath* p,
        float x0,
        float y0,
        float x1,
        float y1,
        float a,
        float b,
        float theta,
        bool isMoreThanHalf,
        bool isPositiveArc) {

    /* Convert rotation angle from degrees to radians */
    double thetaD = toRadians(theta);
    /* Pre-compute rotation matrix entries */
    double cosTheta = cos(thetaD);
    double sinTheta = sin(thetaD);
    /* Transform (x0, y0) and (x1, y1) into unit space */
    /* using (inverse) rotation, followed by (inverse) scale */
    double x0p = (x0 * cosTheta + y0 * sinTheta) / a;
    double y0p = (-x0 * sinTheta + y0 * cosTheta) / b;
    double x1p = (x1 * cosTheta + y1 * sinTheta) / a;
    double y1p = (-x1 * sinTheta + y1 * cosTheta) / b;

    /* Compute differences and averages */
    double dx = x0p - x1p;
    double dy = y0p - y1p;
    double xm = (x0p + x1p) / 2;
    double ym = (y0p + y1p) / 2;
    /* Solve for intersecting unit circles */
    double dsq = dx * dx + dy * dy;
    if (dsq == 0.0) {
        ALOGW("Points are coincident");
        return; /* Points are coincident */
    }
    double disc = 1.0 / dsq - 1.0 / 4.0;
    if (disc < 0.0) {
        ALOGW("Points are too far apart %f", dsq);
        float adjust = (float) (sqrt(dsq) / 1.99999);
        drawArc(p, x0, y0, x1, y1, a * adjust,
                b * adjust, theta, isMoreThanHalf, isPositiveArc);
        return; /* Points are too far apart */
    }
    double s = sqrt(disc);
    double sdx = s * dx;
    double sdy = s * dy;
    double cx;
    double cy;
    if (isMoreThanHalf == isPositiveArc) {
        cx = xm - sdy;
        cy = ym + sdx;
    } else {
        cx = xm + sdy;
        cy = ym - sdx;
    }

    double eta0 = atan2((y0p - cy), (x0p - cx));

    double eta1 = atan2((y1p - cy), (x1p - cx));

    double sweep = (eta1 - eta0);
    if (isPositiveArc != (sweep >= 0)) {
        if (sweep > 0) {
            sweep -= 2 * M_PI;
        } else {
            sweep += 2 * M_PI;
        }
    }

    cx *= a;
    cy *= b;
    double tcx = cx;
    cx = cx * cosTheta - cy * sinTheta;
    cy = tcx * sinTheta + cy * cosTheta;

    arcToBezier(p, cx, cy, a, b, x0, y0, thetaD, eta0, sweep);
}



// Use the given verb, and points in the range [start, end) to insert a command into the SkPath.
void PathResolver::addCommand(SkPath* outPath, char previousCmd,
        char cmd, const std::vector<float>* points, size_t start, size_t end) {

    int incr = 2;
    float reflectiveCtrlPointX;
    float reflectiveCtrlPointY;

    switch (cmd) {
    case 'z':
    case 'Z':
        outPath->close();
        // Path is closed here, but we need to move the pen to the
        // closed position. So we cache the segment's starting position,
        // and restore it here.
        currentX = currentSegmentStartX;
        currentY = currentSegmentStartY;
        ctrlPointX = currentSegmentStartX;
        ctrlPointY = currentSegmentStartY;
        outPath->moveTo(currentX, currentY);
        break;
    case 'm':
    case 'M':
    case 'l':
    case 'L':
    case 't':
    case 'T':
        incr = 2;
        break;
    case 'h':
    case 'H':
    case 'v':
    case 'V':
        incr = 1;
        break;
    case 'c':
    case 'C':
        incr = 6;
        break;
    case 's':
    case 'S':
    case 'q':
    case 'Q':
        incr = 4;
        break;
    case 'a':
    case 'A':
        incr = 7;
        break;
    }

    for (unsigned int k = start; k < end; k += incr) {
        switch (cmd) {
        case 'm': // moveto - Start a new sub-path (relative)
            currentX += points->at(k + 0);
            currentY += points->at(k + 1);
            if (k > start) {
                // According to the spec, if a moveto is followed by multiple
                // pairs of coordinates, the subsequent pairs are treated as
                // implicit lineto commands.
                outPath->rLineTo(points->at(k + 0), points->at(k + 1));
            } else {
                outPath->rMoveTo(points->at(k + 0), points->at(k + 1));
                currentSegmentStartX = currentX;
                currentSegmentStartY = currentY;
            }
            break;
        case 'M': // moveto - Start a new sub-path
            currentX = points->at(k + 0);
            currentY = points->at(k + 1);
            if (k > start) {
                // According to the spec, if a moveto is followed by multiple
                // pairs of coordinates, the subsequent pairs are treated as
                // implicit lineto commands.
                outPath->lineTo(points->at(k + 0), points->at(k + 1));
            } else {
                outPath->moveTo(points->at(k + 0), points->at(k + 1));
                currentSegmentStartX = currentX;
                currentSegmentStartY = currentY;
            }
            break;
        case 'l': // lineto - Draw a line from the current point (relative)
            outPath->rLineTo(points->at(k + 0), points->at(k + 1));
            currentX += points->at(k + 0);
            currentY += points->at(k + 1);
            break;
        case 'L': // lineto - Draw a line from the current point
            outPath->lineTo(points->at(k + 0), points->at(k + 1));
            currentX = points->at(k + 0);
            currentY = points->at(k + 1);
            break;
        case 'h': // horizontal lineto - Draws a horizontal line (relative)
            outPath->rLineTo(points->at(k + 0), 0);
            currentX += points->at(k + 0);
            break;
        case 'H': // horizontal lineto - Draws a horizontal line
            outPath->lineTo(points->at(k + 0), currentY);
            currentX = points->at(k + 0);
            break;
        case 'v': // vertical lineto - Draws a vertical line from the current point (r)
            outPath->rLineTo(0, points->at(k + 0));
            currentY += points->at(k + 0);
            break;
        case 'V': // vertical lineto - Draws a vertical line from the current point
            outPath->lineTo(currentX, points->at(k + 0));
            currentY = points->at(k + 0);
            break;
        case 'c': // curveto - Draws a cubic Bézier curve (relative)
            outPath->rCubicTo(points->at(k + 0), points->at(k + 1), points->at(k + 2), points->at(k + 3),
                    points->at(k + 4), points->at(k + 5));

            ctrlPointX = currentX + points->at(k + 2);
            ctrlPointY = currentY + points->at(k + 3);
            currentX += points->at(k + 4);
            currentY += points->at(k + 5);

            break;
        case 'C': // curveto - Draws a cubic Bézier curve
            outPath->cubicTo(points->at(k + 0), points->at(k + 1), points->at(k + 2), points->at(k + 3),
                    points->at(k + 4), points->at(k + 5));
            currentX = points->at(k + 4);
            currentY = points->at(k + 5);
            ctrlPointX = points->at(k + 2);
            ctrlPointY = points->at(k + 3);
            break;
        case 's': // smooth curveto - Draws a cubic Bézier curve (reflective cp)
            reflectiveCtrlPointX = 0;
            reflectiveCtrlPointY = 0;
            if (previousCmd == 'c' || previousCmd == 's'
                    || previousCmd == 'C' || previousCmd == 'S') {
                reflectiveCtrlPointX = currentX - ctrlPointX;
                reflectiveCtrlPointY = currentY - ctrlPointY;
            }
            outPath->rCubicTo(reflectiveCtrlPointX, reflectiveCtrlPointY,
                    points->at(k + 0), points->at(k + 1),
                    points->at(k + 2), points->at(k + 3));
            ctrlPointX = currentX + points->at(k + 0);
            ctrlPointY = currentY + points->at(k + 1);
            currentX += points->at(k + 2);
            currentY += points->at(k + 3);
            break;
        case 'S': // shorthand/smooth curveto Draws a cubic Bézier curve(reflective cp)
            reflectiveCtrlPointX = currentX;
            reflectiveCtrlPointY = currentY;
            if (previousCmd == 'c' || previousCmd == 's'
                    || previousCmd == 'C' || previousCmd == 'S') {
                reflectiveCtrlPointX = 2 * currentX - ctrlPointX;
                reflectiveCtrlPointY = 2 * currentY - ctrlPointY;
            }
            outPath->cubicTo(reflectiveCtrlPointX, reflectiveCtrlPointY,
                    points->at(k + 0), points->at(k + 1), points->at(k + 2), points->at(k + 3));
            ctrlPointX = points->at(k + 0);
            ctrlPointY = points->at(k + 1);
            currentX = points->at(k + 2);
            currentY = points->at(k + 3);
            break;
        case 'q': // Draws a quadratic Bézier (relative)
            outPath->rQuadTo(points->at(k + 0), points->at(k + 1), points->at(k + 2), points->at(k + 3));
            ctrlPointX = currentX + points->at(k + 0);
            ctrlPointY = currentY + points->at(k + 1);
            currentX += points->at(k + 2);
            currentY += points->at(k + 3);
            break;
        case 'Q': // Draws a quadratic Bézier
            outPath->quadTo(points->at(k + 0), points->at(k + 1), points->at(k + 2), points->at(k + 3));
            ctrlPointX = points->at(k + 0);
            ctrlPointY = points->at(k + 1);
            currentX = points->at(k + 2);
            currentY = points->at(k + 3);
            break;
        case 't': // Draws a quadratic Bézier curve(reflective control point)(relative)
            reflectiveCtrlPointX = 0;
            reflectiveCtrlPointY = 0;
            if (previousCmd == 'q' || previousCmd == 't'
                    || previousCmd == 'Q' || previousCmd == 'T') {
                reflectiveCtrlPointX = currentX - ctrlPointX;
                reflectiveCtrlPointY = currentY - ctrlPointY;
            }
            outPath->rQuadTo(reflectiveCtrlPointX, reflectiveCtrlPointY,
                    points->at(k + 0), points->at(k + 1));
            ctrlPointX = currentX + reflectiveCtrlPointX;
            ctrlPointY = currentY + reflectiveCtrlPointY;
            currentX += points->at(k + 0);
            currentY += points->at(k + 1);
            break;
        case 'T': // Draws a quadratic Bézier curve (reflective control point)
            reflectiveCtrlPointX = currentX;
            reflectiveCtrlPointY = currentY;
            if (previousCmd == 'q' || previousCmd == 't'
                    || previousCmd == 'Q' || previousCmd == 'T') {
                reflectiveCtrlPointX = 2 * currentX - ctrlPointX;
                reflectiveCtrlPointY = 2 * currentY - ctrlPointY;
            }
            outPath->quadTo(reflectiveCtrlPointX, reflectiveCtrlPointY,
                    points->at(k + 0), points->at(k + 1));
            ctrlPointX = reflectiveCtrlPointX;
            ctrlPointY = reflectiveCtrlPointY;
            currentX = points->at(k + 0);
            currentY = points->at(k + 1);
            break;
        case 'a': // Draws an elliptical arc
            // (rx ry x-axis-rotation large-arc-flag sweep-flag x y)
            drawArc(outPath,
                    currentX,
                    currentY,
                    points->at(k + 5) + currentX,
                    points->at(k + 6) + currentY,
                    points->at(k + 0),
                    points->at(k + 1),
                    points->at(k + 2),
                    points->at(k + 3) != 0,
                    points->at(k + 4) != 0);
            currentX += points->at(k + 5);
            currentY += points->at(k + 6);
            ctrlPointX = currentX;
            ctrlPointY = currentY;
            break;
        case 'A': // Draws an elliptical arc
            drawArc(outPath,
                    currentX,
                    currentY,
                    points->at(k + 5),
                    points->at(k + 6),
                    points->at(k + 0),
                    points->at(k + 1),
                    points->at(k + 2),
                    points->at(k + 3) != 0,
                    points->at(k + 4) != 0);
            currentX = points->at(k + 5);
            currentY = points->at(k + 6);
            ctrlPointX = currentX;
            ctrlPointY = currentY;
            break;
        default:
            LOG_ALWAYS_FATAL("Unsupported command: %c", cmd);
            break;
        }
        previousCmd = cmd;
    }
}

} // namespace uirenderer
} // namespace android
