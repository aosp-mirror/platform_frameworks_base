#!/usr/bin/env python
#
# Copyright (C) 2011 Google Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# USAGE
#   $ cd GLES2_trace/tools
#   $ python testgenapi.py

import unittest
from genapi import DataType, ApiCall, getApis, parseArgs

class TestApiCall(unittest.TestCase):
    def test_parsing(self):
        apientry = 'void API_ENTRY(glCopyTexSubImage2D)(GLenum target, GLint level, ' \
                   'GLint xoffset, GLint yoffset, GLint x, GLint y, GLsizei width, ' \
                   'GLsizei height) {'
        callsite = 'CALL_GL_API(glCopyTexImage2D, target, level, internalformat, x, y,' \
                   'width, height, border);'

        api = ApiCall("GL", apientry, callsite)
        self.assertEqual(api.func, "glCopyTexImage2D")
        self.assertEqual(api.callsite, 'glCopyTexImage2D(target, level, internalformat, ' \
                                        'x, y, width, height, border)')
        self.assertEqual(api.ret, 'void')
        self.assertEqual(api.arglist, 'GLenum target, GLint level, ' \
                   'GLint xoffset, GLint yoffset, GLint x, GLint y, GLsizei width, ' \
                   'GLsizei height')

    def test_num_functions_parsed(self):
        gl2_apis = getApis('../../GLES2/gl2_api.in', 'GL2')
        gl2ext_apis = getApis('../../GLES2/gl2ext_api.in', 'GL2Ext')
        gl_apis = getApis('../../GLES_CM/gl_api.in', "GL1")
        glext_apis = getApis('../../GLES_CM/glext_api.in', 'GL1Ext')

        self.assertEqual(len(gl2_apis), 142)
        self.assertEqual(len(gl2ext_apis), 60)
        self.assertEqual(len(gl_apis), 145)
        self.assertEqual(len(glext_apis), 126)

    def test_parseArgs(self):
        args = parseArgs("void")
        self.assertEqual(len(args), 0)

        args = parseArgs("GLchar a")
        self.assertEqual(args, [("a", DataType.CHAR)])

        args = parseArgs("GLchar *a")
        self.assertEqual(args, [("a", DataType.POINTER)])

        args = parseArgs("GLint exponent[16]")
        self.assertEqual(args, [("exponent", DataType.POINTER)])

if __name__ == '__main__':
    unittest.main()
