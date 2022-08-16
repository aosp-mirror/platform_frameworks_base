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

package android.app.wallpapereffectsgeneration;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.graphics.Bitmap;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The textured mesh representation, including a texture (bitmap) to sample from when rendering,
 * and a mesh consisting of primitives such as triangles. The mesh is represented by an indices
 * array describing the set of primitives in the mesh, and a vertices array that the indices
 * refer to.
 *
 * @hide
 */
@SystemApi
public final class TexturedMesh implements Parcelable {
    /**
     * The texture to sample from when rendering mesh.
     */
    @NonNull
    private Bitmap mBitmap;

    /**
     * The set of primitives as pointers into the vertices.
     */
    @NonNull
    private int[] mIndices;

    /**
     * The specific vertices that the indices refer to.
     */
    @NonNull
    private float[] mVertices;

    /** @hide */
    @IntDef(prefix = {"INDICES_LAYOUT_"}, value = {
            INDICES_LAYOUT_UNDEFINED,
            INDICES_LAYOUT_TRIANGLES})
    @Retention(RetentionPolicy.SOURCE)
    public @interface IndicesLayoutType {
    }

    /** Undefined indices layout */
    public static final int INDICES_LAYOUT_UNDEFINED = 0;
    /**
     * Indices layout is triangle. Vertices are grouped into 3 and each
     * group forms a triangle.
     */
    public static final int INDICES_LAYOUT_TRIANGLES = 1;

    @IndicesLayoutType
    private int mIndicesLayoutType;

    /** @hide */
    @IntDef(prefix = {"VERTICES_LAYOUT_"}, value = {
            VERTICES_LAYOUT_UNDEFINED,
            VERTICES_LAYOUT_POSITION3_UV2})
    @Retention(RetentionPolicy.SOURCE)
    public @interface VerticesLayoutType {
    }

    /**
     * Undefined vertices layout.
     */
    public static final int VERTICES_LAYOUT_UNDEFINED = 0;
    /**
     * The vertices array uses 5 numbers to represent a point, in the format
     * of [x1, y1, z1, u1, v1, x2, y2, z2, u2, v2, ...].
     */
    public static final int VERTICES_LAYOUT_POSITION3_UV2 = 1;

    @VerticesLayoutType
    private int mVerticesLayoutType;

    private TexturedMesh(Parcel in) {
        this.mIndicesLayoutType = in.readInt();
        this.mVerticesLayoutType = in.readInt();
        this.mBitmap = in.readTypedObject(Bitmap.CREATOR);
        Parcel data = Parcel.obtain();
        try {
            byte[] bytes = in.readBlob();
            data.unmarshall(bytes, 0, bytes.length);
            data.setDataPosition(0);
            this.mIndices = data.createIntArray();
            this.mVertices = data.createFloatArray();
        } finally {
            data.recycle();
        }
    }

    private TexturedMesh(@NonNull Bitmap bitmap, @NonNull int[] indices,
            @NonNull float[] vertices, @IndicesLayoutType int indicesLayoutType,
            @VerticesLayoutType int verticesLayoutType) {
        mBitmap = bitmap;
        mIndices = indices;
        mVertices = vertices;
        mIndicesLayoutType = indicesLayoutType;
        mVerticesLayoutType = verticesLayoutType;
    }

    /** Get the bitmap, which is the texture to sample from when rendering. */
    @NonNull
    public Bitmap getBitmap() {
        return mBitmap;
    }

    /**
     * Get the indices as pointers to the vertices array. Depending on the getIndicesLayoutType(),
     * the primitives may have different shapes. For example, with INDICES_LAYOUT_TRIANGLES,
     * indices 0, 1, 2 forms a triangle, indices 3, 4, 5 form another triangle.
     */
    @NonNull
    public int[] getIndices() {
        return mIndices;
    }

    /**
     * Get the vertices that the index array refers to. Depending on the getVerticesLayoutType()
     * result, the vertices array can represent different per-vertex coordinates. For example,
     * with VERTICES_LAYOUT_POSITION3_UV2 type, vertices are in the format of
     * [x1, y1, z1, u1, v1, x2, y2, z2, u2, v2, ...].
     */
    @NonNull
    public float[] getVertices() {
        return mVertices;
    }

    /** Get the indices layout type. */
    @IndicesLayoutType
    @NonNull
    public int getIndicesLayoutType() {
        return mIndicesLayoutType;
    }

    /** Get the indices layout type. */
    @VerticesLayoutType
    @NonNull
    public int getVerticesLayoutType() {
        return mVerticesLayoutType;
    }

    @NonNull
    public static final Creator<TexturedMesh> CREATOR = new Creator<TexturedMesh>() {
        @Override
        public TexturedMesh createFromParcel(Parcel in) {
            return new TexturedMesh(in);
        }

        @Override
        public TexturedMesh[] newArray(int size) {
            return new TexturedMesh[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mIndicesLayoutType);
        out.writeInt(mVerticesLayoutType);
        out.writeTypedObject(mBitmap, flags);

        // Indices and vertices can reach 5MB. Write the data as a Blob,
        // which will be written to ashmem if too large.
        Parcel data = Parcel.obtain();
        try {
            data.writeIntArray(mIndices);
            data.writeFloatArray(mVertices);
            out.writeBlob(data.marshall());
        } finally {
            data.recycle();
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * A builder for {@link TexturedMesh}
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        private Bitmap mBitmap;
        private int[] mIndices;
        private float[] mVertices;
        @IndicesLayoutType
        private int mIndicesLayoutType;
        @VerticesLayoutType
        private int mVerticesLayouttype;

        /**
         * Constructor with bitmap.
         *
         * @hide
         */
        @SystemApi
        public Builder(@NonNull Bitmap bitmap) {
            mBitmap = bitmap;
        }

        /**
         * Set the required indices. The indices should represent the primitives. For example,
         * with INDICES_LAYOUT_TRIANGLES, indices 0, 1, 2 forms a triangle, indices 3, 4, 5
         * form another triangle.
         */
        @NonNull
        public Builder setIndices(@NonNull int[] indices) {
            mIndices = indices;
            return this;
        }

        /**
         * Set the required vertices. The vertices array should represent per-vertex coordinates.
         * For example, with VERTICES_LAYOUT_POSITION3_UV2 type, vertices are in the format of
         * [x1, y1, z1, u1, v1, x2, y2, z2, u2, v2, ...].
         *
         */
        @NonNull
        public Builder setVertices(@NonNull float[] vertices) {
            mVertices = vertices;
            return this;
        }

        /**
         * Set the required indices layout type.
         */
        @NonNull
        public Builder setIndicesLayoutType(@IndicesLayoutType int indicesLayoutType) {
            mIndicesLayoutType = indicesLayoutType;
            return this;
        }

        /**
         * Set the required vertices layout type.
         */
        @NonNull
        public Builder setVerticesLayoutType(@VerticesLayoutType int verticesLayoutype) {
            mVerticesLayouttype = verticesLayoutype;
            return this;
        }

        /** Builds a TexturedMesh based on the given parameters. */
        @NonNull
        public TexturedMesh build() {
            return new TexturedMesh(mBitmap, mIndices, mVertices, mIndicesLayoutType,
                    mVerticesLayouttype);
        }
    }
}
