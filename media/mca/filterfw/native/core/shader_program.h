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

#ifndef ANDROID_FILTERFW_CORE_SHADER_PROGRAM_H
#define ANDROID_FILTERFW_CORE_SHADER_PROGRAM_H

#include <vector>
#include <map>
#include <string>

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <EGL/egl.h>

#include "core/gl_env.h"
#include "core/value.h"

namespace android {
namespace filterfw {

class GLFrame;
class GLFrameBufferHandle;
class GLTextureHandle;
class Quad;
class VertexFrame;

typedef GLint ProgramVar;

// A ShaderProgram is a Program object that holds a GLSL shader implementation.
// It provides functionality for compiling, linking, and executing the shader.
// On top of that, it provides access to the shaders source code, uniforms,
// attributes, and other properties.
// By default a ShaderProgram provides its own vertex shader. However, a custom
// vertex shader may be passed and used instead.
// When implementing a vertex shader, the following attribute names have special
// meaning:
//
//  - a_position: The vertex position
//  - a_texcoord: The texture cooridnates
//
// The shader program will bind these attributes to the correct values, if they
// are present in the vertex shader source code.
//
// When implementing the fragment shader, the following variable names must be
// defined:
//
//  - tex_sampler_<n>: The n'th input texture. For instance, use tex_sampler_0
//                     for the first input texture. Must be a uniform sampler2D.
//  - v_texcoord: The current texture coordinate.
//
// If more input textures are given than the shader can handle, this will result
// in an error.
//
class ShaderProgram {
  public:
    // General Functionality ///////////////////////////////////////////////////
    // Create a new shader program with the given fragment shader source code.
    // A default vertex shader is used, which renders the input texture to a
    // rectangular region of the output texture. You can modify the input and
    // output regions by using the SetSourceRegion(...) and SetTargetRegion(...)
    // (and related) functions below.
    // This program will not be executable until you have compiled and linked
    // it.
    // Note, that the ShaderProgram does NOT take ownership of the GLEnv. The
    // caller must make sure the GLEnv stays valid as long as the GLFrame is
    // alive.
    explicit ShaderProgram(GLEnv* gl_env, const std::string& fragment_shader);

    // Create a new shader program with the given fragment and vertex shader
    // source code. This program will not be executable until you have compiled
    // and linked it.
    // Note, that the ShaderProgram does NOT take ownership of the GLEnv. The
    // caller must make sure the GLEnv stays valid as long as the GLFrame is
    // alive.
    ShaderProgram(GLEnv* gl_env,
                  const std::string& vertex_shader,
                  const std::string& fragment_shader);

    // Destructor.
    ~ShaderProgram();

    // Process the given input frames and write the result to the output frame.
    // Returns false if there was an error processing.
    bool Process(const std::vector<const GLFrame*>& inputs, GLFrame* output);

    // Same as above, but pass GL interfaces rather than frame objects. Use this
    // only if you are not working on Frame objects, but rather directly on GL
    // textures and FBOs.
    bool Process(const std::vector<const GLTextureHandle*>& input,
                 GLFrameBufferHandle* output);

    // Compile and link the shader source code. Returns true if compilation
    // and linkage was successful. Compilation and linking error messages are
    // written to the error log.
    bool CompileAndLink();

    // Returns true if this Program has been compiled and linked successfully.
    bool IsExecutable() const {
      return program_ != 0;
    }

    // Returns true if the shader program variable is valid.
    static bool IsVarValid(ProgramVar var);

    // Special ShaderPrograms //////////////////////////////////////////////////
    // A (compiled) shader program which assigns the sampled pixels from the
    // input to the output. Note that transformations may be applied to achieve
    // effects such as cropping, scaling or rotation.
    // The caller takes ownership of the result!
    static ShaderProgram* CreateIdentity(GLEnv* env);

    // Geometry ////////////////////////////////////////////////////////////////
    // These functions modify the source and target regions used during
    // rasterization. Note, that these functions will ONLY take effect if
    // the default vertex shader is used, or your custom vertex shader defines
    // the a_position and a_texcoord attributes.

    // Set the program to read from a subregion of the input frame, given by
    // the origin (x, y) and dimensions (width, height). Values are considered
    // normalized between 0.0 and 1.0. If this region exceeds the input frame
    // dimensions the results are undefined.
    void SetSourceRect(float x, float y, float width, float height) ;

    // Set the program to read from a subregion of the input frame, given by
    // the passed Quad. Values are considered normalized between 0.0 and 1.0.
    // The Quad points are expected to be in the order top-left, top-right,
    // bottom-left, bottom-right.
    // If this region exceeds the input frame dimensions the results are
    // undefined.
    void SetSourceRegion(const Quad& quad);

    // Set the program to write to a subregion of the output frame, given by
    // the origin (x, y) and dimensions (width, height). Values are considered
    // normalized between 0.0 and 1.0. If this region exceeds the output frame
    // dimensions the image will be clipped.
    void SetTargetRect(float x, float y, float width, float height);

    // Set the program to write to a subregion of the output frame, given by
    // the passed Quad. Values are considered normalized between 0.0 and 1.0.
    // The Quad points are expected to be in the order top-left, top-right,
    // bottom-left, bottom-right.
    // If this region exceeds the output frame dimensions the image will be
    // clipped.
    void SetTargetRegion(const Quad& quad);

    // Uniform Variable access /////////////////////////////////////////////////
    // Note: In order to get and set uniforms, the program must have been
    // successfully compiled and linked. Otherwise, the getters will return an
    // invalid ProgramVar variable (check with IsVarValid()).
    // When setting values, the value type must be match the type of the uniform
    // in the shader. For instance, a vector of 3 elements cannot be assigned to
    // a vec2. Similarly, an integer value cannot be assigned to a float value.
    // Such a type mismatch will result in failure to set the value (which will
    // remain untouched). Check the return value of the setters to determine
    // success.

    // Returns the maximum number of uniforms supported by this implementation.
    static int MaxUniformCount();

    // Returns a handle to the uniform with the given name, or invalid if no
    // such uniform variable exists in the shader.
    ProgramVar GetUniform(const std::string& name) const;

    // Set the specified uniform value to the given integer value. Returns true
    // if the assignment was successful.
    bool SetUniformValue(ProgramVar var, int value);

    // Set the specified uniform value to the given float value. Returns true
    // if the assignment was successful.
    bool SetUniformValue(ProgramVar var, float value);

    // Set the specified uniform value to the given values. Returns true
    // if the assignment was successful.
    bool SetUniformValue(ProgramVar var, const int* values, int count);

    // Set the specified uniform value to the given values. Returns true
    // if the assignment was successful.
    bool SetUniformValue(ProgramVar var, const float* values, int count);

    // Set the specified uniform value to the given vector value. Returns true
    // if the assignment was successful.
    bool SetUniformValue(ProgramVar var, const std::vector<int>& values);

    // Set the specified uniform value to the given vector value. Returns true
    // if the assignment was successful.
    bool SetUniformValue(ProgramVar var, const std::vector<float>& values);

    // Generic variable setter, which in the case of GL programs always attempts
    // to set the value of a uniform variable with the given name. Only values
    // of type float, float array (or vector), and int are supported.
    bool SetUniformValue(const std::string& name, const Value& value);

    // Generic variable getter, which in the case of GL programs always attempts
    // to get the value of a uniform variable with the given name.
    Value GetUniformValue(const std::string& name);

    // Returns the default name of the input texture uniform variable for the
    // given input index.
    static std::string InputTextureUniformName(int index);

    // Attribute access ////////////////////////////////////////////////////////
    // Note: In order to get and set attributes, the program must have been
    // successfully compiled and linked. Otherwise, the getters will return an
    // invalid ProgramVar variable (check with IsVarValid()). Constant attribute
    // values must be floats. Attribute pointers must be associated with a
    // specific type, which can be any of the following:
    //   GL_BYTE, GL_UNSIGNED_BYTE, GL_SHORT, GL_UNSIGNED_SHORT, GL_FLOAT,
    //   GL_FIXED, GL_HALF_FLOAT_OES.
    // When storing vertex data, it is recommended to use VertexFrames when
    // possible as these will be kept in GPU memory, and no copying of vertex
    // attributes between system and GPU memory needs to take place.

    // Returns the maximum number of attributes supported by this
    // implementation.
    static int MaxAttributeCount();

    // Returns a handle to the attribute with the given name, or invalid if no
    // such attribute exists in the vertex shader.
    ProgramVar GetAttribute(const std::string& name) const;

    // Set an attribute value that will be constant for each vertex. Returns
    // true if the assignment was successful.
    bool SetConstAttributeValue(ProgramVar var, float value);

    // Set an attribute vector value that will be constant for each vertex.
    // Returns true if the assignment was successful.
    bool SetConstAttributeValue(ProgramVar var, const std::vector<float>& value);

    // Set attribute values that differ across vertexes, using a VertexFrame.
    // This is the recommended method of specifying vertex data, that does not
    // change from iteration to iteration. The parameters are as follows:
    //   var: The shader variable to bind the values to.
    //   data: The vertex frame which holds the vertex data. This may be a
    //         superset of the data required for this particular vertex. Use the
    //         offset and stride to select the correct data portion.
    //   type: The type of the data values. This may differ from the type of the
    //         shader variables. See the normalize flag on how values are
    //         converted.
    //   components: The number of components per value. Valid values are 1-4.
    //   stride: The delta of one element to the next in bytes.
    //   offset: The offset of the first element.
    //   normalize: True, if not float values should be normalized to the range
    //              0-1, when converted to a float.
    // Returns true, if the assignment was successful.
    bool SetAttributeValues(ProgramVar var,
                            const VertexFrame* data,
                            GLenum type,
                            int components,
                            int stride,
                            int offset,
                            bool normalize);

    // Set attribute values that differ across vertexes, using a data buffer.
    // This is the recommended method of specifying vertex data, if your data
    // changes often. Note that this data may need to be copied to GPU memory
    // for each render pass. Please see above for a description of the
    // parameters.
    // Note: The data passed here MUST be valid until all executions of this
    // Program instance have been completed!
    bool SetAttributeValues(ProgramVar var,
                            const uint8_t* data,
                            GLenum type,
                            int components,
                            int stride,
                            int offset,
                            bool normalize);

    // Convenience method for setting vertex values using a vector of floats.
    // The components parameter specifies how many elements per variable should
    // be assigned (The variable must be able to fit the number of components).
    // It must be a value between 1 and 4.
    // While this method is not as flexible as the methods above, this can be
    // used when more advanced methods are not necessary. Note, that if your
    // vertex data does not change, it is recommended to use a VertexFrame.
    bool SetAttributeValues(ProgramVar var,
                            const std::vector<float>& data,
                            int components);

    // Same as above, but using a float pointer instead of vector. Pass the
    // total number of elements in total.
    bool SetAttributeValues(ProgramVar var,
                            const float* data,
                            int total,
                            int components);

    // By default, rendering only uses the first 4 vertices. You should only
    // adjust this value if you are providing your own vertex attributes with
    // a count unequal to 4. Adjust this value before calling Process().
    void SetVertexCount(int count);

    // Returns the default name of the attribute used to hold the texture
    // coordinates. Use this when you need to access the texture coordinate
    // attribute of the shader's default vertex shader.
    static const std::string& TexCoordAttributeName() {
      static std::string s_texcoord("a_texcoord");
      return s_texcoord;
    }

    // Returns the default name of the attribute used to hold the output
    // coordinates. Use this when you need to access the output coordinate
    // attribute of the shader's default vertex shader.
    static const std::string& PositionAttributeName() {
      static std::string s_position("a_position");
      return s_position;
    }

    // Rendering ///////////////////////////////////////////////////////////////
    // Set the draw mode, which can be any of GL_POINTS, GL_LINES,
    // GL_LINE_STRIP, GL_LINE_LOOP, GL_TRIANGLES, GL_TRIANGLE_STRIP,
    // GL_TRIANGLE_FAN. The default is GL_TRIANGLE_STRIP.
    // Warning: Do NOT change this if you are not specifying your own vertex
    // data with SetAttributeValues(...).
    void SetDrawMode(GLenum mode);

    // If you are doing your own drawing you should call this before beginning
    // to draw. This will activate the program, push all used attributes, and
    // clear the frame if requested. You do not need to call this if you are
    // not doing your own GL drawing!
    bool BeginDraw();

    // Render a single frame with the given input textures. You may override
    // this, if you need custom rendering behavior. However, you must take
    // care of the following things when overriding:
    //   - Use the correct program (e.g. by calling UseProgram()).
    //   - Bind the given textures
    //   - Bind vertex attributes
    //   - Draw
    bool RenderFrame(const std::vector<GLuint>& textures,
                     const std::vector<GLenum>& targets);

    // Pass true to clear the output frame before rendering. The color used
    // to clear is set in SetClearColor().
    void SetClearsOutput(bool clears);

    // Set the color used to clear the output frame before rendering. You
    // must activate clearing by calling SetClearsOutput(true).
    void SetClearColor(float red, float green, float blue, float alpha);

    // Set the number of tiles to split rendering into. Higher tile numbers
    // will affect performance negatively, but will allow other GPU threads
    // to render more frequently. Defaults to 1, 1.
    void SetTileCounts(int x_count, int y_count);

    // Enable or Disable Blending
    // Set to true to enable, false to disable.
    void SetBlendEnabled(bool enable) {
      blending_ = enable;
    }

    // Specify pixel arithmetic for blending
    // The values of sfactor and dfactor can be:
    //  GL_ZERO, GL_ONE, GL_SRC_COLOR, GL_ONE_MINUS_SRC_COLOR, GL_SRC_ALPHA,
    //  GL_ONE_MINUS_SRC_ALPHA, GL_DST_ALPHA, GL_ONE_MINUS_DST_ALPHA,
    //  GL_DST_COLOR, GL_ONE_MINUS_DST_COLOR, GL_SRC_ALPHA_SATURATE
    // Default values for blending are set to:
    //  sfactor = GL_SRC_ALPHA
    //  dfactor = GL_ONE_MINUS_SRC_ALPHA
    void SetBlendFunc(int sfactor, int dfactor) {
      sfactor_ = sfactor;
      dfactor_ = dfactor;
    }

    // Accessing the Compiled Program //////////////////////////////////////////
    // Use the compiled and linked program for rendering. You should not need
    // to call this, unless you are implementing your own rendering method.
    bool UseProgram();

    // Other Properties ////////////////////////////////////////////////////////
    // Returns the maximum number of varyings supported by this implementation.
    static int MaxVaryingCount();

    // Returns the maximum number of texture units supported by this
    // implementation.
    static int MaxTextureUnits();

    // Lower level functionality ///////////////////////////////////////////////
    // Compile the shader with the given source. The shader_type must be either
    // GL_VERTEX_SHADER or GL_FRAGMENT_SHADER.
    static GLuint CompileShader(GLenum shader_type, const char* source);

    // Link the compiled shader objects and return the resulting program.
    static GLuint LinkProgram(GLuint* shaders, GLuint count);

    // Returns the lowest texture unit that will be used to bind textures.
    GLuint BaseTextureUnit() const {
      return base_texture_unit_;
    }

    // Sets the lowest texture unit that will be used to bind textures. The
    // default value is GL_TEXTURE0.
    void SetBaseTextureUnit(GLuint texture_unit) {
      base_texture_unit_ = texture_unit;
    }

  private:
    // Structure to store vertex attribute data.
    struct VertexAttrib {
      bool          is_const;
      int           index;
      bool          normalized;
      int           stride;
      int           components;
      int           offset;
      GLenum        type;
      GLuint        vbo;
      const void*   values;
      float*        owned_data;

      VertexAttrib();
    };
    typedef std::map<ProgramVar, VertexAttrib> VertexAttribMap;

    struct RGBAColor {
      float red;
      float green;
      float blue;
      float alpha;

      RGBAColor() : red(0), green(0), blue(0), alpha(1) {
      }
    };

    // Scans for all uniforms in the shader and creates index -> id map.
    void ScanUniforms();

    // Returns the index of the given uniform. The caller must make sure
    // that the variable id passed is valid.
    GLuint IndexOfUniform(ProgramVar var);

    // Binds the given input textures.
    bool BindInputTextures(const std::vector<GLuint>& textures,
                           const std::vector<GLenum>& targets);

    // Sets the default source and target coordinates.
    void SetDefaultCoords();

    // Pushes the specified coordinates to the shader attribute.
    bool PushCoords(ProgramVar attr, float* coords);

    // Pushes the source coordinates.
    bool PushSourceCoords(float* coords);

    // Pushes the target coordinates.
    bool PushTargetCoords(float* coords);

    // Performs (simple) GL drawing.
    bool Draw();

    // Performs tiled GL drawing.
    bool DrawTiled();

    // Yields to other GPU threads.
    void Yield();

    // Helper method to assert that the variable value passed has the correct
    // total size.
    static bool CheckValueCount(const std::string& var_type,
                                const std::string& var_name,
                                int expected_count,
                                int components,
                                int value_size);

    // Helper method to assert that the variable value passed has a size, that
    // is compatible with the type size (must be divisible).
    static bool CheckValueMult(const std::string& var_type,
                               const std::string& var_name,
                               int components,
                               int value_size);

    // Checks that the variable is valid. Logs an error and returns false if
    // not.
    static bool CheckVarValid(ProgramVar var);

    // Returns true if the uniform specified by var is an active uniform in the
    // program.
    bool CheckUniformValid(ProgramVar var);

    // Store an attribute to use when rendering.
    bool StoreAttribute(VertexAttrib attrib);

    // Push all assigned attributes before rendering.
    bool PushAttributes();

    // Pop all assigned attributes after rendering.
    bool PopAttributes();

    // The shader source code
    std::string fragment_shader_source_;
    std::string vertex_shader_source_;

    // The compiled shaders and linked program
    GLuint fragment_shader_;
    GLuint vertex_shader_;
    GLuint program_;

    // The GL environment this shader lives in.
    GLEnv* gl_env_;

    // The lowest texture unit this program will use
    GLuint base_texture_unit_;

    // The current source and target coordinates to render from/to.
    float* source_coords_;
    float* target_coords_;

    // True, if the program has control over both source and target coordinates.
    bool manage_coordinates_;

    // The number of tiles to split rendering into.
    int tile_x_count_;
    int tile_y_count_;

    // List of attribute data that we need to set before rendering
    VertexAttribMap attrib_values_;

    // The number of vertices to render
    int vertex_count_;

    // The draw mode used during rendering
    GLenum draw_mode_;

    // True, iff the output frame is cleared before rendering
    bool clears_;

    // The color used to clear the output frame.
    RGBAColor clear_color_;

    // Set to true to enable blending.
    bool blending_;
    int sfactor_;
    int dfactor_;

    // Map from uniform ids to indices
    std::map<ProgramVar, GLuint> uniform_indices_;
};

} // namespace filterfw
} // namespace android

#endif  // ANDROID_FILTERFW_CORE_SHADER_PROGRAM_H
