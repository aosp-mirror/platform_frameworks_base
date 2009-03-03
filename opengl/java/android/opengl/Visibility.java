/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.opengl;

/**
 * A collection of utility methods for computing the visibility of triangle
 * meshes.
 *
 */
public class Visibility {
    /**
     * Test whether a given triangle mesh is visible on the screen. The mesh
     * is specified as an indexed triangle list.
     *
     * @param ws the world space to screen space transform matrix, as an OpenGL
     * column matrix.
     * @param wsOffset an index into the ws array where the data starts.
     * @param positions the vertex positions (x, y, z).
     * @param positionsOffset the index in the positions array where the data
     *        starts.
     * @param indices the indices of the triangle list. The indices are
     * expressed as chars because they are unsigned 16-bit values.
     * @param indicesOffset the index in the indices array where the index data
     *        starts.
     * @param indexCount the number of indices in use. Typically a multiple of
     * three. If not a multiple of three, the remaining one or two indices will
     * be ignored.
     * @return 2 if all of the mesh is visible, 1 if some part of the mesh is
     *         visible, 0 if no part is visible.
     *
     * @throws IllegalArgumentException if ws is null, wsOffset < 0,
     * positions is null, positionsOffset < 0, indices is null,
     * indicesOffset < 0, indicesOffset > indices.length - indexCount
     */
    public static native int visibilityTest(float[] ws, int wsOffset,
            float[] positions, int positionsOffset, char[] indices,
            int indicesOffset, int indexCount);

    /**
     * Given an OpenGL ES ModelView-Projection matrix (which implicitly
     * describes a frustum) and a list of spheres, determine which spheres
     * intersect the frustum.
     * <p>
     * A ModelView-Projection matrix can be computed by multiplying the
     * a Projection matrix by the a ModelView matrix (in that order.). There
     * are several possible ways to obtain the current ModelView and
     * Projection matrices. The most generally applicable way is to keep
     * track of the current matrices in application code. If that is not
     * convenient, there are two optional OpenGL ES extensions which may
     * be used to read the current matrices from OpenGL ES:
     * <ul>
     * <li>GL10Ext.glQueryMatrixxOES
     * <li>GL11.GL_MODELVIEW_MATRIX_FLOAT_AS_INT_BITS_OES and
     * GL_PROJECTION_MATRIX_FLOAT_AS_INT_BITS_OES
     * </ul>
     * The problem with reading back the matrices is that your application
     * will only work with devices that support the extension(s) that
     * it uses.
     * <p>
     * A frustum is a six-sided truncated pyramid that defines the portion of
     * world space that is visible in the view.
     * <p>
     * Spheres are described as four floating point values: x, y, z, and r, in
     * world-space coordinates. R is the radius of the sphere.
     * <p>
     * @param mvp a float array containing the mode-view-projection matrix
     * @param mvpOffset The offset of the mvp data within the mvp array.
     * @param spheres a float array containing the sphere data.
     * @param spheresOffset an offset into the sphere array where the sphere
     *        data starts
     * @param spheresCount the number of spheres to cull.
     * @param results an integer array containing the indices of the spheres
     * that are either contained entirely within or intersect the frustum.
     * @param resultsOffset an offset into the results array where the results
     *        start.
     * @param resultsCapacity the number of array elements available for storing
     *        results.
     * @return the number of spheres that intersected the frustum. Can be
     * larger than resultsCapacity, in which case only the first resultsCapacity
     * results are written into the results array.
     *
     * @throws IllegalArgumentException if mvp is null, mvpOffset < 0,
     * mvpOffset > mvp.length - 16, spheres is null, spheresOffset < 0,
     * spheresOffset > spheres.length - sphereCount,
     * results is null, resultsOffset < 0, resultsOffset > results.length -
     * resultsCapacity.
     */
    public static native int frustumCullSpheres(float[] mvp, int mvpOffset,
            float[] spheres, int spheresOffset, int spheresCount,
            int[] results, int resultsOffset, int resultsCapacity);

    /**
     * Compute a bounding sphere for a set of points. It is approximately the
     * minimal bounding sphere of an axis-aligned box that bounds the points.
     *
     * @param positions positions in x, y, z triples
     * @param positionsOffset offset into positions array
     * @param positionsCount number of position triples to process
     * @param sphere array containing the output as (x, y, z, r)
     * @param sphereOffset offset where the sphere data will be written
     *
     * @throws IllegalArgumentException if positions is null,
     * positionsOffset < 0, positionsOffset > positions.length - positionsCount,
     * sphere is null, sphereOffset < 0, sphereOffset > sphere.length - 4.
     */
    public static native void computeBoundingSphere(float[] positions,
            int positionsOffset, int positionsCount, float[] sphere,
            int sphereOffset);
}
