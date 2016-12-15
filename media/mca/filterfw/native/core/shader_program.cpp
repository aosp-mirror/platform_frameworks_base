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

#include "base/logging.h"

#include "core/geometry.h"
#include "core/gl_buffer_interface.h"
#include "core/gl_env.h"
#include "core/gl_frame.h"
#include "core/shader_program.h"
#include "core/vertex_frame.h"

#include <string>
#include <sstream>
#include <vector>

namespace android {
namespace filterfw {

static const char* s_default_vertex_shader_source_ =
  "attribute vec4 a_position;\n"
  "attribute vec2 a_texcoord;\n"
  "varying vec2 v_texcoord;\n"
  "void main() {\n"
  "  gl_Position = a_position;\n"
  "  v_texcoord = a_texcoord;\n"
  "}\n";

// Helper Functions ////////////////////////////////////////////////////////////
// Maps coordinates x,y in the unit rectangle over to the quadrangle specified
// by the four points in b. The result coordinates are written to xt and yt.
static void GetTileCoords(const float* b, float x, float y, float* xt, float* yt) {
  const float w0 =  (1.0f - x) * (1.0f - y);
  const float w1 =  x * (1.0f - y);
  const float w2 =  (1.0f - x) * y;
  const float w3 =  x * y;

  *xt = w0 * b[0] + w1 * b[2] + w2 * b[4] + w3 * b[6];
  *yt = w0 * b[1] + w1 * b[3] + w2 * b[5] + w3 * b[7];
}

// VertexAttrib implementation /////////////////////////////////////////////////
ShaderProgram::VertexAttrib::VertexAttrib()
  : is_const(true),
    index(-1),
    normalized(false),
    stride(0),
    components(0),
    offset(0),
    type(GL_FLOAT),
    vbo(0),
    values(NULL),
    owned_data(NULL) {
}

// ShaderProgram implementation ////////////////////////////////////////////////
ShaderProgram::ShaderProgram(GLEnv* gl_env, const std::string& fragment_shader)
  : fragment_shader_source_(fragment_shader),
    vertex_shader_source_(s_default_vertex_shader_source_),
    fragment_shader_(0),
    vertex_shader_(0),
    program_(0),
    gl_env_(gl_env),
    base_texture_unit_(GL_TEXTURE0),
    source_coords_(NULL),
    target_coords_(NULL),
    manage_coordinates_(false),
    tile_x_count_(1),
    tile_y_count_(1),
    vertex_count_(4),
    draw_mode_(GL_TRIANGLE_STRIP),
    clears_(false),
    blending_(false),
    sfactor_(GL_SRC_ALPHA),
    dfactor_(GL_ONE_MINUS_SRC_ALPHA) {
  SetDefaultCoords();
}

ShaderProgram::ShaderProgram(GLEnv* gl_env,
                             const std::string& vertex_shader,
                             const std::string& fragment_shader)
  : fragment_shader_source_(fragment_shader),
    vertex_shader_source_(vertex_shader),
    fragment_shader_(0),
    vertex_shader_(0),
    program_(0),
    gl_env_(gl_env),
    base_texture_unit_(GL_TEXTURE0),
    source_coords_(NULL),
    target_coords_(NULL),
    manage_coordinates_(false),
    tile_x_count_(1),
    tile_y_count_(1),
    vertex_count_(4),
    draw_mode_(GL_TRIANGLE_STRIP),
    clears_(false),
    blending_(false),
    sfactor_(GL_SRC_ALPHA),
    dfactor_(GL_ONE_MINUS_SRC_ALPHA) {
  SetDefaultCoords();
}

ShaderProgram::~ShaderProgram() {
  // Delete our vertex data
  delete[] source_coords_;
  delete[] target_coords_;

  // Delete any owned attribute data
  VertexAttribMap::const_iterator iter;
  for (iter = attrib_values_.begin(); iter != attrib_values_.end(); ++iter) {
    const VertexAttrib& attrib = iter->second;
    if (attrib.owned_data)
      delete[] attrib.owned_data;
  }
}

void ShaderProgram::SetDefaultCoords() {
  if (!source_coords_)
    source_coords_ = new float[8];
  if (!target_coords_)
    target_coords_ = new float[8];

  source_coords_[0] = 0.0f;
  source_coords_[1] = 0.0f;
  source_coords_[2] = 1.0f;
  source_coords_[3] = 0.0f;
  source_coords_[4] = 0.0f;
  source_coords_[5] = 1.0f;
  source_coords_[6] = 1.0f;
  source_coords_[7] = 1.0f;

  target_coords_[0] = -1.0f;
  target_coords_[1] = -1.0f;
  target_coords_[2] =  1.0f;
  target_coords_[3] = -1.0f;
  target_coords_[4] = -1.0f;
  target_coords_[5] =  1.0f;
  target_coords_[6] =  1.0f;
  target_coords_[7] =  1.0f;

}

ShaderProgram* ShaderProgram::CreateIdentity(GLEnv* gl_env) {
  const char* s_id_fragment_shader =
    "precision mediump float;\n"
    "uniform sampler2D tex_sampler_0;\n"
    "varying vec2 v_texcoord;\n"
    "void main() {\n"
    "  gl_FragColor = texture2D(tex_sampler_0, v_texcoord);\n"
    "}\n";
  ShaderProgram* result = new ShaderProgram(gl_env, s_id_fragment_shader);
  result->CompileAndLink();
  return result;
}

bool ShaderProgram::IsVarValid(ProgramVar var) {
  return var != -1;
}

bool ShaderProgram::Process(const std::vector<const GLTextureHandle*>& input,
                            GLFrameBufferHandle* output) {
  // TODO: This can be optimized: If the input and output are the same, as in
  // the last iteration (typical of a multi-pass filter), a lot of this setup
  // may be skipped.

  // Abort if program did not successfully compile and link
  if (!IsExecutable()) {
    ALOGE("ShaderProgram: unexecutable program!");
    return false;
  }

  // Focus the FBO of the output
  if (!output->FocusFrameBuffer()) {
    ALOGE("Unable to focus frame buffer");
    return false;
  }

  // Get all required textures
  std::vector<GLuint> textures;
  std::vector<GLenum> targets;
  for (unsigned i = 0; i < input.size(); ++i) {
    // Get the current input frame and make sure it is a GL frame
    if (input[i]) {
      // Get the texture bound to that frame
      const GLuint tex_id = input[i]->GetTextureId();
      const GLenum target = input[i]->GetTextureTarget();
      if (tex_id == 0) {
        ALOGE("ShaderProgram: invalid texture id at input: %d!", i);
        return false;
      }
      textures.push_back(tex_id);
      targets.push_back(target);
    }
  }

  // And render!
  if (!RenderFrame(textures, targets)) {
    ALOGE("Unable to render frame");
    return false;
  }
  return true;
}

bool ShaderProgram::Process(const std::vector<const GLFrame*>& input, GLFrame* output) {
  std::vector<const GLTextureHandle*> textures(input.size());
  std::copy(input.begin(), input.end(), textures.begin());
  return Process(textures, output);
}

void ShaderProgram::SetSourceRect(float x, float y, float width, float height) {
  Quad quad(Point(x,          y),
            Point(x + width,  y),
            Point(x,          y + height),
            Point(x + width,  y + height));
  SetSourceRegion(quad);
}

void ShaderProgram::SetSourceRegion(const Quad& quad) {
  int index = 0;
  for (int i = 0; i < 4; ++i, index += 2) {
    source_coords_[index]   = quad.point(i).x();
    source_coords_[index+1] = quad.point(i).y();
  }
}

void ShaderProgram::SetTargetRect(float x, float y, float width, float height) {
  Quad quad(Point(x,          y),
            Point(x + width,  y),
            Point(x,          y + height),
            Point(x + width,  y + height));
  SetTargetRegion(quad);
}

void ShaderProgram::SetTargetRegion(const Quad& quad) {
  int index = 0;
  for (int i = 0; i < 4; ++i, index += 2) {
    target_coords_[index]   = (quad.point(i).x() * 2.0) - 1.0;
    target_coords_[index+1] = (quad.point(i).y() * 2.0) - 1.0;
  }
}

bool ShaderProgram::CompileAndLink() {
  // Make sure we haven't compiled and linked already
  if (vertex_shader_ != 0 || fragment_shader_ != 0 || program_ != 0) {
    ALOGE("Attempting to re-compile shaders!");
    return false;
  }

  // Compile vertex shader
  vertex_shader_ = CompileShader(GL_VERTEX_SHADER,
                                 vertex_shader_source_.c_str());
  if (!vertex_shader_) {
    ALOGE("Shader compilation failed!");
    return false;
  }

  // Compile fragment shader
  fragment_shader_ = CompileShader(GL_FRAGMENT_SHADER,
                                   fragment_shader_source_.c_str());
  if (!fragment_shader_)
    return false;

  // Link
  GLuint shaders[2] = { vertex_shader_, fragment_shader_ };
  program_ = LinkProgram(shaders, 2);

  // Scan for all uniforms in the program
  ScanUniforms();

  // Check if we manage all coordinates
  if (program_ != 0) {
    ProgramVar tex_coord_attr = glGetAttribLocation(program_, TexCoordAttributeName().c_str());
    ProgramVar pos_coord_attr = glGetAttribLocation(program_, PositionAttributeName().c_str());
    manage_coordinates_ = (tex_coord_attr >= 0 && pos_coord_attr >= 0);
  } else {
    ALOGE("Could not link shader program!");
    return false;
  }

  return true;
}

GLuint ShaderProgram::CompileShader(GLenum shader_type, const char* source) {
  LOG_FRAME("Compiling source:\n[%s]", source);

  // Create shader
  GLuint shader = glCreateShader(shader_type);
  if (shader) {
    // Compile source
    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);

    // Check for compilation errors
    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
      // Log the compilation error messages
      ALOGE("Problem compiling shader! Source:");
      ALOGE("%s", source);
      std::string src(source);
      size_t cur_pos = 0;
      size_t next_pos = 0;
      size_t line_number = 1;
      while ( (next_pos = src.find_first_of('\n', cur_pos)) != std::string::npos) {
        ALOGE("%03zd : %s", line_number, src.substr(cur_pos, next_pos-cur_pos).c_str());
        cur_pos = next_pos + 1;
        line_number++;
      }
      ALOGE("%03zu : %s", line_number, src.substr(cur_pos, next_pos-cur_pos).c_str());

      GLint log_length = 0;
      glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &log_length);
      if (log_length) {
        char* error_log = new char[log_length];
        if (error_log) {
          glGetShaderInfoLog(shader, log_length, NULL, error_log);
          ALOGE("Shader compilation error %d:\n%s\n", shader_type, error_log);
          delete[] error_log;
        }
      }
      glDeleteShader(shader);
      shader = 0;
    }
  }
  return shader;
}

GLuint ShaderProgram::LinkProgram(GLuint* shaders, GLuint count) {
  GLuint program = glCreateProgram();
  if (program) {
    // Attach all compiled shaders
    for (GLuint i = 0; i < count; ++i) {
      glAttachShader(program, shaders[i]);
      if (GLEnv::CheckGLError("glAttachShader")) return 0;
    }

    // Link
    glLinkProgram(program);

    // Check for linking errors
    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
      // Log the linker error messages
      GLint log_length = 0;
      glGetProgramiv(program, GL_INFO_LOG_LENGTH, &log_length);
      if (log_length) {
        char* error_log = new char[log_length];
        if (error_log) {
          glGetProgramInfoLog(program, log_length, NULL, error_log);
          ALOGE("Program Linker Error:\n%s\n", error_log);
          delete[] error_log;
        }
      }
      glDeleteProgram(program);
      program = 0;
    }
  }
  return program;
}

void ShaderProgram::ScanUniforms() {
  int uniform_count;
  int buffer_size;
  GLenum type;
  GLint capacity;
  glGetProgramiv(program_, GL_ACTIVE_UNIFORMS, &uniform_count);
  glGetProgramiv(program_, GL_ACTIVE_UNIFORM_MAX_LENGTH, &buffer_size);
  std::vector<GLchar> name(buffer_size);
  for (int i = 0; i < uniform_count; ++i) {
    glGetActiveUniform(program_, i, buffer_size, NULL, &capacity, &type, &name[0]);
    ProgramVar uniform_id = glGetUniformLocation(program_, &name[0]);
    uniform_indices_[uniform_id] = i;
  }
}

bool ShaderProgram::PushCoords(ProgramVar attr, float* coords) {
  // If the shader does not define these, we simply ignore the coordinates, and assume that the
  // user is managing coordinates.
  if (attr >= 0) {
    const uint8_t* data = reinterpret_cast<const uint8_t*>(coords);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glVertexAttribPointer(attr, 2, GL_FLOAT, false, 2 * sizeof(float), data);
    glEnableVertexAttribArray(attr);
    return !GLEnv::CheckGLError("Pushing vertex coordinates");
  }
  return true;
}

bool ShaderProgram::PushSourceCoords(float* coords) {
  ProgramVar tex_coord_attr = glGetAttribLocation(program_, TexCoordAttributeName().c_str());
  return PushCoords(tex_coord_attr, coords);
}

bool ShaderProgram::PushTargetCoords(float* coords) {
  ProgramVar pos_coord_attr = glGetAttribLocation(program_, PositionAttributeName().c_str());
  return PushCoords(pos_coord_attr, coords);
}

std::string ShaderProgram::InputTextureUniformName(int index) {
  std::stringstream tex_name;
  tex_name << "tex_sampler_" << index;
  return tex_name.str();
}

bool ShaderProgram::BindInputTextures(const std::vector<GLuint>& textures,
                                      const std::vector<GLenum>& targets) {
  for (unsigned i = 0; i < textures.size(); ++i) {
    // Activate texture unit i
    glActiveTexture(BaseTextureUnit() + i);
    if (GLEnv::CheckGLError("Activating Texture Unit"))
      return false;

    // Bind our texture
    glBindTexture(targets[i], textures[i]);
    LOG_FRAME("Binding texture %d", textures[i]);
    if (GLEnv::CheckGLError("Binding Texture"))
      return false;

    // Set the texture handle in the shader to unit i
    ProgramVar tex_var = GetUniform(InputTextureUniformName(i));
    if (tex_var >= 0) {
      glUniform1i(tex_var, i);
    } else {
      ALOGE("ShaderProgram: Shader does not seem to support %zd number of "
           "inputs! Missing uniform 'tex_sampler_%d'!", textures.size(), i);
      return false;
    }

    if (GLEnv::CheckGLError("Texture Variable Binding"))
      return false;
  }

  return true;
}

bool ShaderProgram::UseProgram() {
  if (GLEnv::GetCurrentProgram() != program_) {
    LOG_FRAME("Using program %d", program_);
    glUseProgram(program_);
    return !GLEnv::CheckGLError("Use Program");
  }
  return true;
}

bool ShaderProgram::RenderFrame(const std::vector<GLuint>& textures,
                                const std::vector<GLenum>& targets) {
  // Make sure we have enough texture units to accomodate the textures
  if (textures.size() > static_cast<unsigned>(MaxTextureUnits())) {
    ALOGE("ShaderProgram: Number of input textures is unsupported on this "
         "platform!");
    return false;
  }

  // Prepare to render
  if (!BeginDraw()) {
    ALOGE("ShaderProgram: couldn't initialize gl for drawing!");
    return false;
  }

  // Bind input textures
  if (!BindInputTextures(textures, targets)) {
    ALOGE("BindInputTextures failed");
    return false;
  }

  if (LOG_EVERY_FRAME) {
    int fbo, program, buffer;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &fbo);
    glGetIntegerv(GL_CURRENT_PROGRAM, &program);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &buffer);
    ALOGV("RenderFrame: fbo %d prog %d buff %d", fbo, program, buffer);
  }

  // Render!
  const bool requestTile = (tile_x_count_ != 1 || tile_y_count_ != 1);
  const bool success = (!requestTile || !manage_coordinates_  || vertex_count_ != 4)
      ? Draw()
      : DrawTiled();

  // Pop vertex attributes
  PopAttributes();

  return success && !GLEnv::CheckGLError("Rendering");
}

bool ShaderProgram::Draw() {
  if (PushSourceCoords(source_coords_) && PushTargetCoords(target_coords_)) {
    glDrawArrays(draw_mode_, 0, vertex_count_);
    return true;
  }
  return false;
}

bool ShaderProgram::DrawTiled() {
  // Target coordinate step size
  float s[8];
  float t[8];

  // Step sizes
  const float xs = 1.0f / static_cast<float>(tile_x_count_);
  const float ys = 1.0f / static_cast<float>(tile_y_count_);

  // Tile drawing loop
  for (int i = 0; i < tile_x_count_; ++i) {
    for (int j = 0; j < tile_y_count_; ++j) {
      // Current coordinates in unit rectangle
      const float x = i / static_cast<float>(tile_x_count_);
      const float y = j / static_cast<float>(tile_y_count_);

      // Source coords
      GetTileCoords(source_coords_, x, y, &s[0], &s[1]);
      GetTileCoords(source_coords_, x + xs, y, &s[2], &s[3]);
      GetTileCoords(source_coords_, x, y + ys, &s[4], &s[5]);
      GetTileCoords(source_coords_, x + xs, y + ys, &s[6], &s[7]);

      // Target coords
      GetTileCoords(target_coords_, x, y, &t[0], &t[1]);
      GetTileCoords(target_coords_, x + xs, y, &t[2], &t[3]);
      GetTileCoords(target_coords_, x, y + ys, &t[4], &t[5]);
      GetTileCoords(target_coords_, x + xs, y + ys, &t[6], &t[7]);

      if (PushSourceCoords(s) && PushTargetCoords(t)) {
        glDrawArrays(draw_mode_, 0, vertex_count_);
        Yield();
      } else {
        return false;
      }
    }
  }
  return true;
}

void ShaderProgram::Yield() {
  glFinish();
}

bool ShaderProgram::BeginDraw() {
  // Activate shader program
  if (!UseProgram())
    return false;

  // Push vertex attributes
  PushAttributes();

  // Clear output, if requested
  if (clears_) {
    glClearColor(clear_color_.red,
                 clear_color_.green,
                 clear_color_.blue,
                 clear_color_.alpha);
    glClear(GL_COLOR_BUFFER_BIT);
  }

  // Enable/Disable blending
  if (blending_) {
    glEnable(GL_BLEND);
    glBlendFunc(sfactor_, dfactor_);
  } else glDisable(GL_BLEND);

  return true;
}

int ShaderProgram::MaxVaryingCount() {
  GLint result;
  glGetIntegerv(GL_MAX_VARYING_VECTORS, &result);
  return result;
}

int ShaderProgram::MaxTextureUnits() {
  return GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS - 1;
}

void ShaderProgram::SetDrawMode(GLenum mode) {
  draw_mode_ = mode;
}

void ShaderProgram::SetClearsOutput(bool clears) {
  clears_ = clears;
}

void ShaderProgram::SetClearColor(float red, float green, float blue, float alpha) {
  clear_color_.red = red;
  clear_color_.green = green;
  clear_color_.blue = blue;
  clear_color_.alpha = alpha;
}

void ShaderProgram::SetTileCounts(int x_count, int y_count) {
  tile_x_count_ = x_count;
  tile_y_count_ = y_count;
}

// Variable Value Setting Helpers //////////////////////////////////////////////
bool ShaderProgram::CheckValueCount(const std::string& var_type,
                                    const std::string& var_name,
                                    int expected_count,
                                    int components,
                                    int value_size) {
  if (expected_count != (value_size / components)) {
    ALOGE("Shader Program: %s Value Error (%s): Expected value length %d "
         "(%d components), but received length of %d (%d components)!",
         var_type.c_str(), var_name.c_str(),
         expected_count, components * expected_count,
         value_size / components, value_size);
    return false;
  }
  return true;
}

bool ShaderProgram::CheckValueMult(const std::string& var_type,
                                   const std::string& var_name,
                                   int components,
                                   int value_size) {
  if (value_size % components != 0) {
    ALOGE("Shader Program: %s Value Error (%s): Value must be multiple of %d, "
         "but %d elements were passed!", var_type.c_str(), var_name.c_str(),
         components, value_size);
    return false;
  }
  return true;
}

bool ShaderProgram::CheckVarValid(ProgramVar var) {
  if (!IsVarValid(var)) {
    ALOGE("Shader Program: Attempting to access invalid variable!");
    return false;
  }
  return true;
}

// Uniforms ////////////////////////////////////////////////////////////////////
bool ShaderProgram::CheckUniformValid(ProgramVar var) {
  if (!IsVarValid(var) || uniform_indices_.find(var) == uniform_indices_.end()) {
    ALOGE("Shader Program: Attempting to access unknown uniform %d!", var);
    return false;
  }
  return true;
}

int ShaderProgram::MaxUniformCount() {
  // Here we return the minimum of the max uniform count for fragment and vertex
  // shaders.
  GLint count1, count2;
  glGetIntegerv(GL_MAX_VERTEX_UNIFORM_VECTORS, &count1);
  glGetIntegerv(GL_MAX_FRAGMENT_UNIFORM_VECTORS, &count2);
  return count1 < count2 ? count1 : count2;
}

ProgramVar ShaderProgram::GetUniform(const std::string& name) const {
  if (!IsExecutable()) {
    ALOGE("ShaderProgram: Error: Must link program before querying uniforms!");
    return -1;
  }
  return glGetUniformLocation(program_, name.c_str());
}

bool ShaderProgram::SetUniformValue(ProgramVar var, int value) {
  if (!CheckVarValid(var))
    return false;

  // Uniforms are local to the currently used program.
  if (UseProgram()) {
    glUniform1i(var, value);
    return !GLEnv::CheckGLError("Set Uniform Value (int)");
  }
  return false;
}

bool ShaderProgram::SetUniformValue(ProgramVar var, float value) {
  if (!CheckVarValid(var))
    return false;

  // Uniforms are local to the currently used program.
  if (UseProgram()) {
    glUniform1f(var, value);
    return !GLEnv::CheckGLError("Set Uniform Value (float)");
  }
  return false;
}

bool ShaderProgram::SetUniformValue(ProgramVar var,
                                    const int* values,
                                    int count) {
  if (!CheckUniformValid(var))
    return false;

  // Make sure we have values at all
  if (count == 0)
    return false;

  // Uniforms are local to the currently used program.
  if (UseProgram()) {
    // Get uniform information
    GLint capacity;
    GLenum type;
    char name[128];
    glGetActiveUniform(program_, IndexOfUniform(var), 128, NULL, &capacity, &type, name);

    // Make sure passed values are compatible
    const int components = GLEnv::NumberOfComponents(type);
    if (!CheckValueCount("Uniform (int)", name, capacity, components, count)
    ||  !CheckValueMult ("Uniform (int)", name, components, count))
      return false;

    // Set value based on type
    const int n = count / components;
    switch(type) {
      case GL_INT:
        glUniform1iv(var, n, values);
        break;

      case GL_INT_VEC2:
        glUniform2iv(var, n, values);
        break;

      case GL_INT_VEC3:
        glUniform3iv(var, n, values);
        break;

      case GL_INT_VEC4:
        glUniform4iv(var, n, values);
        break;

      default:
        return false;
    };
    return !GLEnv::CheckGLError("Set Uniform Value");
  }
  return false;
}

bool ShaderProgram::SetUniformValue(ProgramVar var,
                                    const float* values,
                                    int count) {
  if (!CheckUniformValid(var))
    return false;

  // Make sure we have values at all
  if (count == 0)
    return false;

  // Uniforms are local to the currently used program.
  if (UseProgram()) {
    // Get uniform information
    GLint capacity;
    GLenum type;
    char name[128];
    glGetActiveUniform(program_, IndexOfUniform(var), 128, NULL, &capacity, &type, name);

    // Make sure passed values are compatible
    const int components = GLEnv::NumberOfComponents(type);
    if (!CheckValueCount("Uniform (float)", name, capacity, components, count)
    ||  !CheckValueMult ("Uniform (float)", name, components, count))
      return false;

    // Set value based on type
    const int n = count / components;
    switch(type) {
      case GL_FLOAT:
        glUniform1fv(var, n, values);
        break;

      case GL_FLOAT_VEC2:
        glUniform2fv(var, n, values);
        break;

      case GL_FLOAT_VEC3:
        glUniform3fv(var, n, values);
        break;

      case GL_FLOAT_VEC4:
        glUniform4fv(var, n, values);
        break;

      case GL_FLOAT_MAT2:
        glUniformMatrix2fv(var, n, GL_FALSE, values);
        break;

      case GL_FLOAT_MAT3:
        glUniformMatrix3fv(var, n, GL_FALSE, values);
        break;

      case GL_FLOAT_MAT4:
        glUniformMatrix4fv(var, n, GL_FALSE, values);
        break;

      default:
        return false;
    };
    return !GLEnv::CheckGLError("Set Uniform Value");
  }
  return false;
}

bool ShaderProgram::SetUniformValue(ProgramVar var, const std::vector<int>& values) {
  return SetUniformValue(var, &values[0], values.size());
}

bool ShaderProgram::SetUniformValue(ProgramVar var,
                                    const std::vector<float>& values) {
  return SetUniformValue(var, &values[0], values.size());
}

bool ShaderProgram::SetUniformValue(const std::string& name, const Value& value) {
  if (ValueIsFloat(value))
    return SetUniformValue(GetUniform(name), GetFloatValue(value));
  else if (ValueIsInt(value))
    return SetUniformValue(GetUniform(name), GetIntValue(value));
  else if (ValueIsFloatArray(value))
    return SetUniformValue(GetUniform(name), GetFloatArrayValue(value), GetValueCount(value));
  else if (ValueIsIntArray(value))
    return SetUniformValue(GetUniform(name), GetIntArrayValue(value), GetValueCount(value));
  else
    return false;
}

Value ShaderProgram::GetUniformValue(const std::string& name) {
  ProgramVar var = GetUniform(name);
  if (CheckUniformValid(var)) {
    // Get uniform information
    GLint capacity;
    GLenum type;
    glGetActiveUniform(program_, IndexOfUniform(var), 0, NULL, &capacity, &type, NULL);
    if (!GLEnv::CheckGLError("Get Active Uniform")) {
      // Get value based on type, and wrap in value object
      switch(type) {
        case GL_INT: {
          int value;
          glGetUniformiv(program_, var, &value);
          return !GLEnv::CheckGLError("GetVariableValue") ? MakeIntValue(value)
                                                          : MakeNullValue();
        } break;

        case GL_INT_VEC2: {
          int value[2];
          glGetUniformiv(program_, var, &value[0]);
          return !GLEnv::CheckGLError("GetVariableValue") ? MakeIntArrayValue(value, 2)
                                                          : MakeNullValue();
        } break;

        case GL_INT_VEC3: {
          int value[3];
          glGetUniformiv(program_, var, &value[0]);
          return !GLEnv::CheckGLError("GetVariableValue") ? MakeIntArrayValue(value, 3)
                                                          : MakeNullValue();
        } break;

        case GL_INT_VEC4: {
          int value[4];
          glGetUniformiv(program_, var, &value[0]);
          return !GLEnv::CheckGLError("GetVariableValue") ? MakeIntArrayValue(value, 4)
                                                          : MakeNullValue();
        } break;

        case GL_FLOAT: {
          float value;
          glGetUniformfv(program_, var, &value);
          return !GLEnv::CheckGLError("GetVariableValue") ? MakeFloatValue(value)
                                                          : MakeNullValue();
        } break;

        case GL_FLOAT_VEC2: {
          float value[2];
          glGetUniformfv(program_, var, &value[0]);
          return !GLEnv::CheckGLError("GetVariableValue") ? MakeFloatArrayValue(value, 2)
                                                          : MakeNullValue();
        } break;

        case GL_FLOAT_VEC3: {
          float value[3];
          glGetUniformfv(program_, var, &value[0]);
          return !GLEnv::CheckGLError("GetVariableValue") ? MakeFloatArrayValue(value, 3)
                                                          : MakeNullValue();
        } break;

        case GL_FLOAT_VEC4: {
          float value[4];
          glGetUniformfv(program_, var, &value[0]);
          return !GLEnv::CheckGLError("GetVariableValue") ? MakeFloatArrayValue(value, 4)
                                                          : MakeNullValue();
        } break;

        case GL_FLOAT_MAT2: {
          float value[4];
          glGetUniformfv(program_, var, &value[0]);
          return !GLEnv::CheckGLError("GetVariableValue") ? MakeFloatArrayValue(value, 4)
                                                          : MakeNullValue();
        } break;

        case GL_FLOAT_MAT3: {
          float value[9];
          glGetUniformfv(program_, var, &value[0]);
          return !GLEnv::CheckGLError("GetVariableValue") ? MakeFloatArrayValue(value, 9)
                                                          : MakeNullValue();
        } break;

        case GL_FLOAT_MAT4: {
          float value[16];
          glGetUniformfv(program_, var, &value[0]);
          return !GLEnv::CheckGLError("GetVariableValue") ? MakeFloatArrayValue(value, 16)
                                                          : MakeNullValue();
        } break;
      }
    }
  }
  return MakeNullValue();
}

GLuint ShaderProgram::IndexOfUniform(ProgramVar var) {
  return uniform_indices_[var];
}

// Attributes //////////////////////////////////////////////////////////////////////////////////////
int ShaderProgram::MaxAttributeCount() {
  GLint result;
  glGetIntegerv(GL_MAX_VERTEX_ATTRIBS, &result);
  return result;
}

ProgramVar ShaderProgram::GetAttribute(const std::string& name) const {
  if (!IsExecutable()) {
    ALOGE("ShaderProgram: Error: Must link program before querying attributes!");
    return -1;
  } else if (name == PositionAttributeName() || name == TexCoordAttributeName()) {
    ALOGW("ShaderProgram: Attempting to overwrite internal vertex attribute '%s'!", name.c_str());
  }
  return glGetAttribLocation(program_, name.c_str());
}

bool ShaderProgram::SetAttributeValues(ProgramVar var,
                                       const VertexFrame* vbo,
                                       GLenum type,
                                       int components,
                                       int stride,
                                       int offset,
                                       bool normalize) {
  if (!CheckVarValid(var))
    return false;

  if (vbo) {
    VertexAttrib attrib;
    attrib.is_const = false;
    attrib.index = var;
    attrib.components = components;
    attrib.normalized = normalize;
    attrib.stride = stride;
    attrib.type = type;
    attrib.vbo = vbo->GetVboId();
    attrib.offset = offset;

    return StoreAttribute(attrib);
  }
  return false;
}

bool ShaderProgram::SetAttributeValues(ProgramVar var,
                                       const uint8_t* data,
                                       GLenum type,
                                       int components,
                                       int stride,
                                       int offset,
                                       bool normalize) {
  if (!CheckVarValid(var))
    return false;

  if (data) {
    VertexAttrib attrib;
    attrib.is_const = false;
    attrib.index = var;
    attrib.components = components;
    attrib.normalized = normalize;
    attrib.stride = stride;
    attrib.type = type;
    attrib.values = data + offset;

    return StoreAttribute(attrib);
  }
  return false;
}

bool ShaderProgram::SetAttributeValues(ProgramVar var,
                                       const std::vector<float>& data,
                                       int components) {
  return SetAttributeValues(var, &data[0], data.size(), components);
}

bool ShaderProgram::SetAttributeValues(ProgramVar var,
                                       const float* data,
                                       int total,
                                       int components) {
  if (!CheckVarValid(var))
    return false;

  // Make sure the passed data vector has a valid size
  if (total  % components != 0) {
    ALOGE("ShaderProgram: Invalid attribute vector given! Specified a component "
         "count of %d, but passed a non-multiple vector of size %d!",
         components, total);
    return false;
  }

  // Copy the data to a buffer we own
  float* data_cpy = new float[total];
  memcpy(data_cpy, data, sizeof(float) * total);

  // Store the attribute
  VertexAttrib attrib;
  attrib.is_const = false;
  attrib.index = var;
  attrib.components = components;
  attrib.normalized = false;
  attrib.stride = components * sizeof(float);
  attrib.type = GL_FLOAT;
  attrib.values = data_cpy;
  attrib.owned_data = data_cpy; // Marks this for deletion later on

  if (StoreAttribute(attrib))
    return true;
  // If storing this failed, then it won't be deleted on its own.
  delete[] data_cpy;
  return false;
}

bool ShaderProgram::StoreAttribute(VertexAttrib attrib) {
  if (attrib.index >= 0) {
    attrib_values_[attrib.index] = attrib;
    return true;
  }
  return false;
}

bool ShaderProgram::PushAttributes() {
  for (VertexAttribMap::const_iterator iter = attrib_values_.begin();
       iter != attrib_values_.end();
       ++iter) {
    const VertexAttrib& attrib = iter->second;

    if (attrib.is_const) {
      // Set constant attribute values (must be specified as host values)
      if (!attrib.values)
        return false;

      const float* values = reinterpret_cast<const float*>(attrib.values);
      switch (attrib.components) {
        case 1: glVertexAttrib1fv(attrib.index, values); break;
        case 2: glVertexAttrib2fv(attrib.index, values); break;
        case 3: glVertexAttrib3fv(attrib.index, values); break;
        case 4: glVertexAttrib4fv(attrib.index, values); break;
        default: return false;
      }
      glDisableVertexAttribArray(attrib.index);
    } else {
      // Set per-vertex values
      if (attrib.values) {
        // Make sure no buffer is bound and set attribute
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        glVertexAttribPointer(attrib.index,
                              attrib.components,
                              attrib.type,
                              attrib.normalized,
                              attrib.stride,
                              attrib.values);
      } else if (attrib.vbo) {
        // Bind VBO and set attribute
        glBindBuffer(GL_ARRAY_BUFFER, attrib.vbo);

        glVertexAttribPointer(attrib.index,
                              attrib.components,
                              attrib.type,
                              attrib.normalized,
                              attrib.stride,
                              reinterpret_cast<const void*>(attrib.offset));
      } else {
        return false;
      }
      glEnableVertexAttribArray(attrib.index);
    }

    // Make sure everything worked
    if (GLEnv::CheckGLError("Pushing Vertex Attributes"))
      return false;
  }

  return true;
}

bool ShaderProgram::PopAttributes() {
  // Disable vertex attributes
  for (VertexAttribMap::const_iterator iter = attrib_values_.begin();
       iter != attrib_values_.end();
       ++iter) {
    glDisableVertexAttribArray(iter->second.index);
  }
  // Unbind buffer: Very important as this greatly affects what glVertexAttribPointer does!
  glBindBuffer(GL_ARRAY_BUFFER, 0);
  return !GLEnv::CheckGLError("Popping Vertex Attributes");
}

void ShaderProgram::SetVertexCount(int count) {
  vertex_count_ = count;
}

} // namespace filterfw
} // namespace android
