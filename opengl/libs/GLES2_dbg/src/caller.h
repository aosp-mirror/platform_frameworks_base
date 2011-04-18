/*
 ** Copyright 2011, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License")
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

static const int * GenerateCall_glCompressedTexImage2D(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glCompressedTexSubImage2D(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glDrawElements(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGenBuffers(DbgContext * const dbg,
                                       const glesv2debugger::Message & cmd,
                                       glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGenFramebuffers(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGenRenderbuffers(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGenTextures(DbgContext * const dbg,
                                        const glesv2debugger::Message & cmd,
                                        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGetActiveAttrib(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGetActiveUniform(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGetAttachedShaders(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGetBooleanv(DbgContext * const dbg,
                                        const glesv2debugger::Message & cmd,
                                        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGetBufferParameteriv(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGetFloatv(DbgContext * const dbg,
                                      const glesv2debugger::Message & cmd,
                                      glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGetFramebufferAttachmentParameteriv(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGetIntegerv(DbgContext * const dbg,
                                        const glesv2debugger::Message & cmd,
                                        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGetProgramiv(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    GLint params = -1;
    dbg->hooks->gl.glGetProgramiv(cmd.arg0(), cmd.arg1(), &params);
    msg.mutable_data()->append(reinterpret_cast<char *>(&params), sizeof(params));
    return prevRet;
}

static const int * GenerateCall_glGetProgramInfoLog(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    const GLsizei bufSize = static_cast<GLsizei>(dbg->GetBufferSize());
    GLsizei length = -1;
    dbg->hooks->gl.glGetProgramInfoLog(cmd.arg0(), bufSize, &length, dbg->GetBuffer());
    msg.mutable_data()->append(dbg->GetBuffer(), length);
    return prevRet;
}

static const int * GenerateCall_glGetRenderbufferParameteriv(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGetShaderiv(DbgContext * const dbg,
                                        const glesv2debugger::Message & cmd,
                                        glesv2debugger::Message & msg, const int * const prevRet)
{
    GLint params = -1;
    dbg->hooks->gl.glGetShaderiv(cmd.arg0(), cmd.arg1(), &params);
    msg.mutable_data()->append(reinterpret_cast<char *>(&params), sizeof(params));
    return prevRet;
}

static const int * GenerateCall_glGetShaderInfoLog(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    const GLsizei bufSize = static_cast<GLsizei>(dbg->GetBufferSize());
    GLsizei length = -1;
    dbg->hooks->gl.glGetShaderInfoLog(cmd.arg0(), bufSize, &length, dbg->GetBuffer());
    msg.mutable_data()->append(dbg->GetBuffer(), length);
    return prevRet;
}

static const int * GenerateCall_glGetShaderPrecisionFormat(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGetShaderSource(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGetString(DbgContext * const dbg,
                                      const glesv2debugger::Message & cmd,
                                      glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGetTexParameterfv(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGetTexParameteriv(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGetUniformfv(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGetUniformiv(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGetVertexAttribfv(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGetVertexAttribiv(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glGetVertexAttribPointerv(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glReadPixels(DbgContext * const dbg,
                                       const glesv2debugger::Message & cmd,
                                       glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glShaderBinary(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glShaderSource(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    const char * string = cmd.data().data();
    dbg->hooks->gl.glShaderSource(cmd.arg0(), 1, &string, NULL);
    return prevRet;
}

static const int * GenerateCall_glTexImage2D(DbgContext * const dbg,
                                       const glesv2debugger::Message & cmd,
                                       glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glTexParameterfv(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glTexParameteriv(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glTexSubImage2D(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}

static const int * GenerateCall_glVertexAttribPointer(DbgContext * const dbg,
        const glesv2debugger::Message & cmd,
        glesv2debugger::Message & msg, const int * const prevRet)
{
    assert(0);
    return prevRet;
}
