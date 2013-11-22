/*
 * Copyright (C) 2012 The Android Open Source Project
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

package androidx.media.filterfw;

import android.graphics.RectF;
import android.opengl.GLES20;
import android.util.Log;

import androidx.media.filterfw.geometry.Quad;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Convenience class to perform GL shader operations on image data.
 * <p>
 * The ImageShader class greatly simplifies the task of running GL shader language kernels over
 * Frame data buffers that contain RGBA image data.
 * </p><p>
 * TODO: More documentation
 * </p>
 */
public class ImageShader {

    private int mProgram = 0;
    private boolean mClearsOutput = false;
    private float[] mClearColor = { 0f, 0f, 0f, 0f };
    private boolean mBlendEnabled = false;
    private int mSFactor = GLES20.GL_SRC_ALPHA;
    private int mDFactor = GLES20.GL_ONE_MINUS_SRC_ALPHA;
    private int mDrawMode = GLES20.GL_TRIANGLE_STRIP;
    private int mVertexCount = 4;
    private int mBaseTexUnit = GLES20.GL_TEXTURE0;
    private int mClearBuffers = GLES20.GL_COLOR_BUFFER_BIT;
    private float[] mSourceCoords = new float[] { 0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f };
    private float[] mTargetCoords = new float[] { -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f };

    private HashMap<String, ProgramUniform> mUniforms;
    private HashMap<String, VertexAttribute> mAttributes = new HashMap<String, VertexAttribute>();

    private final static int FLOAT_SIZE = 4;

    private final static String mDefaultVertexShader =
        "attribute vec4 a_position;\n" +
        "attribute vec2 a_texcoord;\n" +
        "varying vec2 v_texcoord;\n" +
        "void main() {\n" +
        "  gl_Position = a_position;\n" +
        "  v_texcoord = a_texcoord;\n" +
        "}\n";

    private final static String mIdentityShader =
        "precision mediump float;\n" +
        "uniform sampler2D tex_sampler_0;\n" +
        "varying vec2 v_texcoord;\n" +
        "void main() {\n" +
        "  gl_FragColor = texture2D(tex_sampler_0, v_texcoord);\n" +
        "}\n";

    private static class VertexAttribute {
        private String mName;
        private boolean mIsConst;
        private int mIndex;
        private boolean mShouldNormalize;
        private int mOffset;
        private int mStride;
        private int mComponents;
        private int mType;
        private int mVbo;
        private int mLength;
        private FloatBuffer mValues;

        public VertexAttribute(String name, int index) {
            mName = name;
            mIndex = index;
            mLength = -1;
        }

        public void set(boolean normalize, int stride, int components, int type, float[] values) {
            mIsConst = false;
            mShouldNormalize = normalize;
            mStride = stride;
            mComponents = components;
            mType = type;
            mVbo = 0;
            if (mLength != values.length){
                initBuffer(values);
                mLength = values.length;
            }
            copyValues(values);
        }

        public void set(boolean normalize, int offset, int stride, int components, int type,
                int vbo){
            mIsConst = false;
            mShouldNormalize = normalize;
            mOffset = offset;
            mStride = stride;
            mComponents = components;
            mType = type;
            mVbo = vbo;
            mValues = null;
        }

        public boolean push() {
            if (mIsConst) {
                switch (mComponents) {
                    case 1:
                        GLES20.glVertexAttrib1fv(mIndex, mValues);
                        break;
                    case 2:
                        GLES20.glVertexAttrib2fv(mIndex, mValues);
                        break;
                    case 3:
                        GLES20.glVertexAttrib3fv(mIndex, mValues);
                        break;
                    case 4:
                        GLES20.glVertexAttrib4fv(mIndex, mValues);
                        break;
                    default:
                        return false;
                }
                GLES20.glDisableVertexAttribArray(mIndex);
            } else {
                if (mValues != null) {
                    // Note that we cannot do any size checking here, as the correct component
                    // count depends on the drawing step. GL should catch such errors then, and
                    // we will report them to the user.
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
                    GLES20.glVertexAttribPointer(mIndex,
                                                 mComponents,
                                                 mType,
                                                 mShouldNormalize,
                                                 mStride,
                                                 mValues);
                } else {
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVbo);
                    GLES20.glVertexAttribPointer(mIndex,
                                                 mComponents,
                                                 mType,
                                                 mShouldNormalize,
                                                 mStride,
                                                 mOffset);
                }
                GLES20.glEnableVertexAttribArray(mIndex);
            }
            GLToolbox.checkGlError("Set vertex-attribute values");
            return true;
        }

        @Override
        public String toString() {
            return mName;
        }

        private void initBuffer(float[] values) {
            mValues = ByteBuffer.allocateDirect(values.length * FLOAT_SIZE)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        }

        private void copyValues(float[] values) {
            mValues.put(values).position(0);
        }

    }

    private static final class ProgramUniform {
        private String mName;
        private int mLocation;
        private int mType;
        private int mSize;

        public ProgramUniform(int program, int index) {
            int[] len = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_ACTIVE_UNIFORM_MAX_LENGTH, len, 0);

            int[] type = new int[1];
            int[] size = new int[1];
            byte[] name = new byte[len[0]];
            int[] ignore = new int[1];

            GLES20.glGetActiveUniform(program, index, len[0], ignore, 0, size, 0, type, 0, name, 0);
            mName = new String(name, 0, strlen(name));
            mLocation = GLES20.glGetUniformLocation(program, mName);
            mType = type[0];
            mSize = size[0];
            GLToolbox.checkGlError("Initializing uniform");
        }

        public String getName() {
            return mName;
        }

        public int getType() {
            return mType;
        }

        public int getLocation() {
            return mLocation;
        }

        public int getSize() {
            return mSize;
        }
    }

    public ImageShader(String fragmentShader) {
        mProgram = createProgram(mDefaultVertexShader, fragmentShader);
        scanUniforms();
    }

    public ImageShader(String vertexShader, String fragmentShader) {
        mProgram = createProgram(vertexShader, fragmentShader);
        scanUniforms();
    }

    public static ImageShader createIdentity() {
        return new ImageShader(mIdentityShader);
    }

    public static ImageShader createIdentity(String vertexShader) {
        return new ImageShader(vertexShader, mIdentityShader);
    }

    public static void renderTextureToTarget(TextureSource texture,
                                             RenderTarget target,
                                             int width,
                                             int height) {
        ImageShader shader = RenderTarget.currentTarget().getIdentityShader();
        shader.process(texture, target, width, height);
    }

    public void process(FrameImage2D input, FrameImage2D output) {
        TextureSource texSource = input.lockTextureSource();
        RenderTarget renderTarget = output.lockRenderTarget();
        processMulti(new TextureSource[] { texSource },
                     renderTarget,
                     output.getWidth(),
                     output.getHeight());
        input.unlock();
        output.unlock();
    }

    public void processMulti(FrameImage2D[] inputs, FrameImage2D output) {
        TextureSource[] texSources = new TextureSource[inputs.length];
        for (int i = 0; i < inputs.length; ++i) {
            texSources[i] = inputs[i].lockTextureSource();
        }
        RenderTarget renderTarget = output.lockRenderTarget();
        processMulti(texSources,
                     renderTarget,
                     output.getWidth(),
                     output.getHeight());
        for (FrameImage2D input : inputs) {
            input.unlock();
        }
        output.unlock();
    }

    public void process(TextureSource texture, RenderTarget target, int width, int height) {
        processMulti(new TextureSource[] { texture }, target, width, height);
    }

    public void processMulti(TextureSource[] sources, RenderTarget target, int width, int height) {
        GLToolbox.checkGlError("Unknown Operation");
        checkExecutable();
        checkTexCount(sources.length);
        focusTarget(target, width, height);
        pushShaderState();
        bindInputTextures(sources);
        render();
    }

    public void processNoInput(FrameImage2D output) {
        RenderTarget renderTarget = output.lockRenderTarget();
        processNoInput(renderTarget, output.getWidth(), output.getHeight());
        output.unlock();
    }

    public void processNoInput(RenderTarget target, int width, int height) {
        processMulti(new TextureSource[] {}, target, width, height);
    }

    public int getUniformLocation(String name) {
        return getProgramUniform(name, true).getLocation();
    }

    public int getAttributeLocation(String name) {
        if (name.equals(positionAttributeName()) || name.equals(texCoordAttributeName())) {
            Log.w("ImageShader", "Attempting to access internal attribute '" + name
                + "' directly!");
        }
        int loc = GLES20.glGetAttribLocation(mProgram, name);
        if (loc < 0) {
            throw new RuntimeException("Unknown attribute '" + name + "' in shader program!");
        }
        return loc;
    }

    public void setUniformValue(String uniformName, int value) {
        useProgram();
        int uniformHandle = getUniformLocation(uniformName);
        GLES20.glUniform1i(uniformHandle, value);
        GLToolbox.checkGlError("Set uniform value (" + uniformName + ")");
    }

    public void setUniformValue(String uniformName, float value) {
        useProgram();
        int uniformHandle = getUniformLocation(uniformName);
        GLES20.glUniform1f(uniformHandle, value);
        GLToolbox.checkGlError("Set uniform value (" + uniformName + ")");
    }

    public void setUniformValue(String uniformName, int[] values) {
        ProgramUniform uniform = getProgramUniform(uniformName, true);
        useProgram();
        int len = values.length;
        switch (uniform.getType()) {
            case GLES20.GL_INT:
                checkUniformAssignment(uniform, len, 1);
                GLES20.glUniform1iv(uniform.getLocation(), len, values, 0);
                break;
            case GLES20.GL_INT_VEC2:
                checkUniformAssignment(uniform, len, 2);
                GLES20.glUniform2iv(uniform.getLocation(), len / 2, values, 0);
                break;
            case GLES20.GL_INT_VEC3:
                checkUniformAssignment(uniform, len, 3);
                GLES20.glUniform2iv(uniform.getLocation(), len / 3, values, 0);
                break;
            case GLES20.GL_INT_VEC4:
                checkUniformAssignment(uniform, len, 4);
                GLES20.glUniform2iv(uniform.getLocation(), len / 4, values, 0);
                break;
            default:
                throw new RuntimeException("Cannot assign int-array to incompatible uniform type "
                    + "for uniform '" + uniformName + "'!");
        }
        GLToolbox.checkGlError("Set uniform value (" + uniformName + ")");
    }


    public void setUniformValue(String uniformName, float[] values) {
        ProgramUniform uniform = getProgramUniform(uniformName, true);
        useProgram();
        int len = values.length;
        switch (uniform.getType()) {
            case GLES20.GL_FLOAT:
                checkUniformAssignment(uniform, len, 1);
                GLES20.glUniform1fv(uniform.getLocation(), len, values, 0);
                break;
            case GLES20.GL_FLOAT_VEC2:
                checkUniformAssignment(uniform, len, 2);
                GLES20.glUniform2fv(uniform.getLocation(), len / 2, values, 0);
                break;
            case GLES20.GL_FLOAT_VEC3:
                checkUniformAssignment(uniform, len, 3);
                GLES20.glUniform3fv(uniform.getLocation(), len / 3, values, 0);
                break;
            case GLES20.GL_FLOAT_VEC4:
                checkUniformAssignment(uniform, len, 4);
                GLES20.glUniform4fv(uniform.getLocation(), len / 4, values, 0);
                break;
            case GLES20.GL_FLOAT_MAT2:
                checkUniformAssignment(uniform, len, 4);
                GLES20.glUniformMatrix2fv(uniform.getLocation(), len / 4, false, values, 0);
                break;
            case GLES20.GL_FLOAT_MAT3:
                checkUniformAssignment(uniform, len, 9);
                GLES20.glUniformMatrix3fv(uniform.getLocation(), len / 9, false, values, 0);
                break;
            case GLES20.GL_FLOAT_MAT4:
                checkUniformAssignment(uniform, len, 16);
                GLES20.glUniformMatrix4fv(uniform.getLocation(), len / 16, false, values, 0);
                break;
            default:
                throw new RuntimeException("Cannot assign float-array to incompatible uniform type "
                    + "for uniform '" + uniformName + "'!");
        }
        GLToolbox.checkGlError("Set uniform value (" + uniformName + ")");
    }

    public void setAttributeValues(String attributeName, float[] data, int components) {
        VertexAttribute attr = getProgramAttribute(attributeName, true);
        attr.set(false, FLOAT_SIZE * components, components, GLES20.GL_FLOAT, data);
    }

    public void setAttributeValues(String attributeName, int vbo, int type, int components,
                                   int stride, int offset, boolean normalize) {
        VertexAttribute attr = getProgramAttribute(attributeName, true);
        attr.set(normalize, offset, stride, components, type, vbo);
    }

    public void setSourceRect(float x, float y, float width, float height) {
        setSourceCoords(new float[] { x, y, x + width, y, x, y + height, x + width, y + height });
    }

    public void setSourceRect(RectF rect) {
        setSourceRect(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top);
    }

    public void setSourceQuad(Quad quad) {
        setSourceCoords(new float[] { quad.topLeft().x,     quad.topLeft().y,
                                      quad.topRight().x,    quad.topRight().y,
                                      quad.bottomLeft().x,  quad.bottomLeft().y,
                                      quad.bottomRight().x, quad.bottomRight().y });
    }

    public void setSourceCoords(float[] coords) {
        if (coords.length != 8) {
            throw new IllegalArgumentException("Expected 8 coordinates as source coordinates but "
                + "got " + coords.length + " coordinates!");
        }
        mSourceCoords = Arrays.copyOf(coords, 8);
    }

    public void setSourceTransform(float[] matrix) {
        if (matrix.length != 16) {
            throw new IllegalArgumentException("Expected 4x4 matrix for source transform!");
        }
        setSourceCoords(new float[] {
            matrix[12],
            matrix[13],

            matrix[0] + matrix[12],
            matrix[1] + matrix[13],

            matrix[4] + matrix[12],
            matrix[5] + matrix[13],

            matrix[0] + matrix[4] + matrix[12],
            matrix[1] + matrix[5] + matrix[13],
        });
    }

    public void setTargetRect(float x, float y, float width, float height) {
        setTargetCoords(new float[] { x, y,
                                      x + width, y,
                                      x, y + height,
                                      x + width, y + height });
    }

    public void setTargetRect(RectF rect) {
        setTargetCoords(new float[] { rect.left,    rect.top,
                                      rect.right,   rect.top,
                                      rect.left,    rect.bottom,
                                      rect.right,   rect.bottom });
    }

    public void setTargetQuad(Quad quad) {
        setTargetCoords(new float[] { quad.topLeft().x,     quad.topLeft().y,
                                      quad.topRight().x,    quad.topRight().y,
                                      quad.bottomLeft().x,  quad.bottomLeft().y,
                                      quad.bottomRight().x, quad.bottomRight().y });
    }

    public void setTargetCoords(float[] coords) {
        if (coords.length != 8) {
            throw new IllegalArgumentException("Expected 8 coordinates as target coordinates but "
                + "got " + coords.length + " coordinates!");
        }
        mTargetCoords = new float[8];
        for (int i = 0; i < 8; ++i) {
            mTargetCoords[i] = coords[i] * 2f - 1f;
        }
    }

    public void setTargetTransform(float[] matrix) {
        if (matrix.length != 16) {
            throw new IllegalArgumentException("Expected 4x4 matrix for target transform!");
        }
        setTargetCoords(new float[] {
            matrix[12],
            matrix[13],

            matrix[0] + matrix[12],
            matrix[1] + matrix[13],

            matrix[4] + matrix[12],
            matrix[5] + matrix[13],

            matrix[0] + matrix[4] + matrix[12],
            matrix[1] + matrix[5] + matrix[13],
        });
    }

    public void setClearsOutput(boolean clears) {
        mClearsOutput = clears;
    }

    public boolean getClearsOutput() {
        return mClearsOutput;
    }

    public void setClearColor(float[] rgba) {
        mClearColor = rgba;
    }

    public float[] getClearColor() {
        return mClearColor;
    }

    public void setClearBufferMask(int bufferMask) {
        mClearBuffers = bufferMask;
    }

    public int getClearBufferMask() {
        return mClearBuffers;
    }

    public void setBlendEnabled(boolean enable) {
        mBlendEnabled = enable;
    }

    public boolean getBlendEnabled() {
        return mBlendEnabled;
    }

    public void setBlendFunc(int sFactor, int dFactor) {
        mSFactor = sFactor;
        mDFactor = dFactor;
    }

    public void setDrawMode(int drawMode) {
        mDrawMode = drawMode;
    }

    public int getDrawMode() {
        return mDrawMode;
    }

    public void setVertexCount(int count) {
        mVertexCount = count;
    }

    public int getVertexCount() {
        return mVertexCount;
    }

    public void setBaseTextureUnit(int baseTexUnit) {
        mBaseTexUnit = baseTexUnit;
    }

    public int baseTextureUnit() {
        return mBaseTexUnit;
    }

    public String texCoordAttributeName() {
        return "a_texcoord";
    }

    public String positionAttributeName() {
        return "a_position";
    }

    public String inputTextureUniformName(int index) {
        return "tex_sampler_" + index;
    }

    public static int maxTextureUnits() {
        return GLES20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS;
    }

    @Override
    protected void finalize() throws Throwable {
        GLES20.glDeleteProgram(mProgram);
    }

    protected void pushShaderState() {
        useProgram();
        updateSourceCoordAttribute();
        updateTargetCoordAttribute();
        pushAttributes();
        if (mClearsOutput) {
            GLES20.glClearColor(mClearColor[0], mClearColor[1], mClearColor[2], mClearColor[3]);
            GLES20.glClear(mClearBuffers);
        }
        if (mBlendEnabled) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(mSFactor, mDFactor);
        } else {
            GLES20.glDisable(GLES20.GL_BLEND);
        }
        GLToolbox.checkGlError("Set render variables");
    }

    private void focusTarget(RenderTarget target, int width, int height) {
        target.focus();
        GLES20.glViewport(0, 0, width, height);
        GLToolbox.checkGlError("glViewport");
    }

    private void bindInputTextures(TextureSource[] sources) {
        for (int i = 0; i < sources.length; ++i) {
            // Activate texture unit i
            GLES20.glActiveTexture(baseTextureUnit() + i);

            // Bind texture
            sources[i].bind();

            // Assign the texture uniform in the shader to unit i
            int texUniform = GLES20.glGetUniformLocation(mProgram, inputTextureUniformName(i));
            if (texUniform >= 0) {
                GLES20.glUniform1i(texUniform, i);
            } else {
                throw new RuntimeException("Shader does not seem to support " + sources.length
                    + " number of input textures! Missing uniform " + inputTextureUniformName(i)
                    + "!");
            }
            GLToolbox.checkGlError("Binding input texture " + i);
        }
    }

    private void pushAttributes() {
        for (VertexAttribute attr : mAttributes.values()) {
            if (!attr.push()) {
                throw new RuntimeException("Unable to assign attribute value '" + attr + "'!");
            }
        }
        GLToolbox.checkGlError("Push Attributes");
    }

    private void updateSourceCoordAttribute() {
        // If attribute does not exist, simply do nothing (may be custom shader).
        VertexAttribute attr = getProgramAttribute(texCoordAttributeName(), false);
        // A non-null value of mSourceCoords indicates new values to be set.
        if (mSourceCoords != null && attr != null) {
            // Upload new source coordinates to GPU
            attr.set(false, FLOAT_SIZE * 2, 2, GLES20.GL_FLOAT, mSourceCoords);
        }
        // Do not set again (even if failed, to not cause endless attempts)
        mSourceCoords = null;
    }

    private void updateTargetCoordAttribute() {
        // If attribute does not exist, simply do nothing (may be custom shader).
        VertexAttribute attr = getProgramAttribute(positionAttributeName(), false);
        // A non-null value of mTargetCoords indicates new values to be set.
        if (mTargetCoords != null && attr != null) {
            // Upload new target coordinates to GPU
            attr.set(false, FLOAT_SIZE * 2, 2, GLES20.GL_FLOAT, mTargetCoords);
        }
        // Do not set again (even if failed, to not cause endless attempts)
        mTargetCoords = null;
    }

    private void render() {
        GLES20.glDrawArrays(mDrawMode, 0, mVertexCount);
        GLToolbox.checkGlError("glDrawArrays");
    }

    private void checkExecutable() {
        if (mProgram == 0) {
            throw new RuntimeException("Attempting to execute invalid shader-program!");
        }
    }

    private void useProgram() {
        GLES20.glUseProgram(mProgram);
        GLToolbox.checkGlError("glUseProgram");
    }

    private static void checkTexCount(int count) {
        if (count > maxTextureUnits()) {
            throw new RuntimeException("Number of textures passed (" + count + ") exceeds the "
                + "maximum number of allowed texture units (" + maxTextureUnits() + ")!");
        }
    }

    private static int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        if (shader != 0) {
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                String info = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                shader = 0;
                throw new RuntimeException("Could not compile shader " + shaderType + ":" + info);
            }
        }
        return shader;
    }

    private static int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0) {
            throw new RuntimeException("Could not create shader-program as vertex shader "
                + "could not be compiled!");
        }
        int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (pixelShader == 0) {
            throw new RuntimeException("Could not create shader-program as fragment shader "
                + "could not be compiled!");
        }

        int program = GLES20.glCreateProgram();
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader);
            GLToolbox.checkGlError("glAttachShader");
            GLES20.glAttachShader(program, pixelShader);
            GLToolbox.checkGlError("glAttachShader");
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                String info = GLES20.glGetProgramInfoLog(program);
                GLES20.glDeleteProgram(program);
                program = 0;
                throw new RuntimeException("Could not link program: " + info);
            }
        }

        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(pixelShader);

        return program;
    }

    private void scanUniforms() {
        int uniformCount[] = new int [1];
        GLES20.glGetProgramiv(mProgram, GLES20.GL_ACTIVE_UNIFORMS, uniformCount, 0);
        if (uniformCount[0] > 0) {
            mUniforms = new HashMap<String, ProgramUniform>(uniformCount[0]);
            for (int i = 0; i < uniformCount[0]; ++i) {
                ProgramUniform uniform = new ProgramUniform(mProgram, i);
                mUniforms.put(uniform.getName(), uniform);
            }
        }
    }

    private ProgramUniform getProgramUniform(String name, boolean required) {
        ProgramUniform result = mUniforms.get(name);
        if (result == null && required) {
            throw new IllegalArgumentException("Unknown uniform '" + name + "'!");
        }
        return result;
    }

    private VertexAttribute getProgramAttribute(String name, boolean required) {
        VertexAttribute result = mAttributes.get(name);
        if (result == null) {
            int handle = GLES20.glGetAttribLocation(mProgram, name);
            if (handle >= 0) {
                result = new VertexAttribute(name, handle);
                mAttributes.put(name, result);
            } else if (required) {
                throw new IllegalArgumentException("Unknown attribute '" + name + "'!");
            }
        }
        return result;
    }

    private void checkUniformAssignment(ProgramUniform uniform, int values, int components) {
        if (values % components != 0) {
            throw new RuntimeException("Size mismatch: Attempting to assign values of size "
                + values + " to uniform '" + uniform.getName() + "' (must be multiple of "
                + components + ")!");
        } else if (uniform.getSize() != values / components) {
            throw new RuntimeException("Size mismatch: Cannot assign " + values + " values to "
                + "uniform '" + uniform.getName() + "'!");
        }
    }

    private static int strlen(byte[] strVal) {
        for (int i = 0; i < strVal.length; ++i) {
            if (strVal[i] == '\0') {
                return i;
            }
        }
        return strVal.length;
    }
}

