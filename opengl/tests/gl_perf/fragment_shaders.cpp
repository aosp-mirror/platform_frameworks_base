
typedef struct FragmentTestRec {
	const char * name;
	uint32_t texCount;
	const char * txt;
} FragmentTest;

static FragmentTest fpFill = {
	"Solid color", 0,

    "precision mediump float;\n"
    "uniform vec4 u_color;\n"
    "void main() {\n"
    "  gl_FragColor = u_color;\n"
    "}\n"
};

static FragmentTest fpGradient = {
	"Solid gradient", 0,

    "precision mediump float;\n"
    "varying lowp vec4 v_color;\n"
    "void main() {\n"
    "  gl_FragColor = v_color;\n"
    "}\n"
};

static FragmentTest fpCopyTex = {
	"Texture copy", 1,

    "precision mediump float;\n"
    "varying vec2 v_tex0;\n"
    "uniform sampler2D u_tex0;\n"
    "void main() {\n"
    "  gl_FragColor = texture2D(u_tex0, v_tex0);\n"
    "}\n"
};

static FragmentTest fpCopyTexGamma = {
	"Texture copy with gamma", 1,

    "precision mediump float;\n"
    "varying vec2 v_tex0;\n"
    "uniform sampler2D u_tex0;\n"
    "void main() {\n"
    "  vec4 t = texture2D(u_tex0, v_tex0);\n"
    "  t.rgb = pow(t.rgb, vec3(1.4, 1.4, 1.4));\n"
    "  gl_FragColor = t;\n"
    "}\n"
};

static FragmentTest fpTexSpec = {
	"Texture spec", 1,

    "precision mediump float;\n"
    "varying vec2 v_tex0;\n"
    "uniform sampler2D u_tex0;\n"
    "void main() {\n"
    "  vec4 t = texture2D(u_tex0, v_tex0);\n"
    "  float simSpec = dot(gl_FragCoord.xyz, gl_FragCoord.xyz);\n"
    "  simSpec = pow(clamp(simSpec, 0.1, 1.0), 40.0);\n"
    "  gl_FragColor = t + vec4(simSpec, simSpec, simSpec, simSpec);\n"
    "}\n"
};

static FragmentTest fpDepTex = {
	"Dependent Lookup", 1,

    "precision mediump float;\n"
    "varying vec2 v_tex0;\n"
    "uniform sampler2D u_tex0;\n"
    "void main() {\n"
    "  vec4 t = texture2D(u_tex0, v_tex0);\n"
    "  t += texture2D(u_tex0, t.xy);\n"
    "  gl_FragColor = t;\n"
    "}\n"
};

static FragmentTest fpModulateConstantTex = {
	"Texture modulate constant", 1,

    "precision mediump float;\n"
    "varying vec2 v_tex0;\n"
    "uniform sampler2D u_tex0;\n"
    "uniform vec4 u_color;\n"

    "void main() {\n"
    "  lowp vec4 c = texture2D(u_tex0, v_tex0);\n"
	"  c *= u_color;\n"
    "  gl_FragColor = c;\n"
    "}\n"
};

static FragmentTest fpModulateVaryingTex = {
	"Texture modulate gradient", 1,

    "precision mediump float;\n"
    "varying vec2 v_tex0;\n"
    "varying lowp vec4 v_color;\n"
    "uniform sampler2D u_tex0;\n"

    "void main() {\n"
    "  lowp vec4 c = texture2D(u_tex0, v_tex0);\n"
	"  c *= v_color;\n"
    "  gl_FragColor = c;\n"
    "}\n"
};

static FragmentTest fpModulateVaryingConstantTex = {
	"Texture modulate gradient constant", 1,

    "precision mediump float;\n"
    "varying vec2 v_tex0;\n"
    "varying lowp vec4 v_color;\n"
    "uniform sampler2D u_tex0;\n"
    "uniform vec4 u_color;\n"

    "void main() {\n"
    "  lowp vec4 c = texture2D(u_tex0, v_tex0);\n"
	"  c *= v_color;\n"
	"  c *= u_color;\n"
    "  gl_FragColor = c;\n"
    "}\n"
};

static FragmentTest *gFragmentTests[] = {
	&fpFill,
	&fpGradient,
	&fpCopyTex,
	&fpCopyTexGamma,
   &fpTexSpec,
   &fpDepTex,
	&fpModulateConstantTex,
	&fpModulateVaryingTex,
	&fpModulateVaryingConstantTex,

};

static const size_t gFragmentTestCount = sizeof(gFragmentTests) / sizeof(gFragmentTests[0]);
