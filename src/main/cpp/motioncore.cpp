// Motion Studio native core.
//  - FxEngine: GPU effects (blur, directional / radial blur, glow, colour grade, pixelate,
//    RGB split, vignette, grain). Runs on the GL thread that calls it.
//  - Particles: batch simulation of a particle emitter.
#include <jni.h>
#include <GLES2/gl2.h>
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <string>

#define LOG_TAG "MotionCore"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

const char* kVertex =
    "attribute vec2 aPos;\n"
    "attribute vec2 aUV;\n"
    "varying vec2 vUV;\n"
    "void main() {\n"
    "    gl_Position = vec4(aPos, 0.0, 1.0);\n"
    "    vUV = aUV;\n"
    "}\n";

const char* kHead =
    "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
    "precision highp float;\n"
    "#else\n"
    "precision mediump float;\n"
    "#endif\n"
    "varying vec2 vUV;\n"
    "uniform sampler2D uTex;\n"
    "uniform sampler2D uTex2;\n"
    "uniform vec4 uP0;\n"
    "uniform vec4 uP1;\n"
    "uniform vec4 uP2;\n";

// separable gaussian: uP0.xy = step between taps in uv units
const char* kBlur =
    "void main() {\n"
    "    vec4 sum = vec4(0.0);\n"
    "    float wsum = 0.0;\n"
    "    for (int i = -8; i <= 8; i++) {\n"
    "        float f = float(i);\n"
    "        float w = exp(-0.5 * f * f / 16.0);\n"
    "        sum += texture2D(uTex, vUV + uP0.xy * f) * w;\n"
    "        wsum += w;\n"
    "    }\n"
    "    gl_FragColor = sum / wsum;\n"
    "}\n";

// directional (motion) blur: uP0.xy = full blur vector in uv units
const char* kDir =
    "void main() {\n"
    "    vec4 sum = vec4(0.0);\n"
    "    for (int i = 0; i < 24; i++) {\n"
    "        float t = float(i) / 23.0 - 0.5;\n"
    "        sum += texture2D(uTex, vUV + uP0.xy * t);\n"
    "    }\n"
    "    gl_FragColor = sum / 24.0;\n"
    "}\n";

// radial / zoom blur: uP0.x = amount
const char* kRad =
    "void main() {\n"
    "    vec2 c = vec2(0.5);\n"
    "    vec4 sum = vec4(0.0);\n"
    "    for (int i = 0; i < 24; i++) {\n"
    "        float t = float(i) / 23.0;\n"
    "        sum += texture2D(uTex, c + (vUV - c) * (1.0 - uP0.x * t));\n"
    "    }\n"
    "    gl_FragColor = sum / 24.0;\n"
    "}\n";

// colour grade: uP0 = brightness, contrast, hue (radians), saturation; uP1 = tint rgb + amount; uP2.x = invert
const char* kGrade =
    "vec3 hueShift(vec3 c, float a) {\n"
    "    const vec3 k = vec3(0.57735);\n"
    "    float ca = cos(a);\n"
    "    return c * ca + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - ca);\n"
    "}\n"
    "void main() {\n"
    "    vec4 s = texture2D(uTex, vUV);\n"
    "    vec3 c = s.a > 0.0001 ? s.rgb / s.a : vec3(0.0);\n"
    "    c += uP0.x;\n"
    "    c = (c - 0.5) * (1.0 + uP0.y) + 0.5;\n"
    "    c = hueShift(c, uP0.z);\n"
    "    float l = dot(c, vec3(0.299, 0.587, 0.114));\n"
    "    c = mix(vec3(l), c, 1.0 + uP0.w);\n"
    "    c = mix(c, uP1.rgb * (0.4 + l), uP1.a);\n"
    "    c = mix(c, 1.0 - c, uP2.x);\n"
    "    c = clamp(c, 0.0, 1.0);\n"
    "    gl_FragColor = vec4(c * s.a, s.a);\n"
    "}\n";

// pixelate: uP0.xy = number of cells
const char* kPix =
    "void main() {\n"
    "    vec2 uv = (floor(vUV * uP0.xy) + 0.5) / uP0.xy;\n"
    "    gl_FragColor = texture2D(uTex, uv);\n"
    "}\n";

// RGB split (chromatic aberration): uP0.xy = offset in uv units
const char* kRgb =
    "void main() {\n"
    "    vec4 r = texture2D(uTex, vUV + uP0.xy);\n"
    "    vec4 g = texture2D(uTex, vUV);\n"
    "    vec4 b = texture2D(uTex, vUV - uP0.xy);\n"
    "    gl_FragColor = vec4(r.r, g.g, b.b, max(max(r.a, g.a), b.a));\n"
    "}\n";

// vignette + film grain: uP0 = vignette, grain, seed
const char* kVig =
    "float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }\n"
    "void main() {\n"
    "    vec4 s = texture2D(uTex, vUV);\n"
    "    float d = length(vUV - 0.5) * 1.4142;\n"
    "    float v = 1.0 - uP0.x * smoothstep(0.35, 1.0, d);\n"
    "    vec3 c = s.rgb * v;\n"
    "    float n = hash(vUV * 1000.0 + uP0.z) - 0.5;\n"
    "    c += n * uP0.y * s.a;\n"
    "    gl_FragColor = vec4(c, s.a);\n"
    "}\n";

// bright pass for glow: uP0.x = threshold
const char* kBright =
    "void main() {\n"
    "    vec4 s = texture2D(uTex, vUV);\n"
    "    float l = dot(s.rgb, vec3(0.299, 0.587, 0.114));\n"
    "    float k = smoothstep(uP0.x, uP0.x + 0.2, l);\n"
    "    gl_FragColor = s * k;\n"
    "}\n";

// glow composite: original + blurred bright parts * uP0.x
const char* kAdd =
    "void main() {\n"
    "    vec4 a = texture2D(uTex, vUV);\n"
    "    vec4 g = texture2D(uTex2, vUV);\n"
    "    gl_FragColor = vec4(a.rgb + g.rgb * uP0.x, clamp(a.a + g.a * uP0.x, 0.0, 1.0));\n"
    "}\n";

struct Prog {
    GLuint id = 0;
    GLint uTex = -1, uTex2 = -1, uP0 = -1, uP1 = -1, uP2 = -1;
};

Prog gBlur, gDir, gRad, gGrade, gPix, gRgb, gVig, gBright, gAdd;
GLuint gFbo[3] = {0, 0, 0};
GLuint gTex[3] = {0, 0, 0};
int gW = 0, gH = 0;
bool gReady = false;

const GLfloat kQuad[] = {
    -1.f, -1.f, 0.f, 0.f,
     1.f, -1.f, 1.f, 0.f,
    -1.f,  1.f, 0.f, 1.f,
     1.f,  1.f, 1.f, 1.f,
};

GLuint compile(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char buf[512];
        glGetShaderInfoLog(s, sizeof(buf), nullptr, buf);
        LOGE("shader compile error: %s", buf);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

bool build(Prog& p, const char* body) {
    std::string frag = std::string(kHead) + body;
    GLuint vs = compile(GL_VERTEX_SHADER, kVertex);
    GLuint fs = compile(GL_FRAGMENT_SHADER, frag.c_str());
    if (!vs || !fs) return false;
    GLuint pr = glCreateProgram();
    glAttachShader(pr, vs);
    glAttachShader(pr, fs);
    glBindAttribLocation(pr, 0, "aPos");
    glBindAttribLocation(pr, 1, "aUV");
    glLinkProgram(pr);
    GLint ok = 0;
    glGetProgramiv(pr, GL_LINK_STATUS, &ok);
    glDeleteShader(vs);
    glDeleteShader(fs);
    if (!ok) {
        LOGE("program link error");
        glDeleteProgram(pr);
        return false;
    }
    p.id = pr;
    p.uTex = glGetUniformLocation(pr, "uTex");
    p.uTex2 = glGetUniformLocation(pr, "uTex2");
    p.uP0 = glGetUniformLocation(pr, "uP0");
    p.uP1 = glGetUniformLocation(pr, "uP1");
    p.uP2 = glGetUniformLocation(pr, "uP2");
    return true;
}

void freeTargets() {
    if (gFbo[0]) glDeleteFramebuffers(3, gFbo);
    if (gTex[0]) glDeleteTextures(3, gTex);
    memset(gFbo, 0, sizeof(gFbo));
    memset(gTex, 0, sizeof(gTex));
    gW = gH = 0;
}

bool ensure(int w, int h) {
    if (w == gW && h == gH && gFbo[0]) return true;
    freeTargets();
    glGenTextures(3, gTex);
    glGenFramebuffers(3, gFbo);
    bool ok = true;
    for (int i = 0; i < 3; i++) {
        glBindTexture(GL_TEXTURE_2D, gTex[i]);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindFramebuffer(GL_FRAMEBUFFER, gFbo[i]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, gTex[i], 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) ok = false;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    gW = w;
    gH = h;
    return ok;
}

// draws a full screen quad with [p] reading target [src] (and [src2]) into target [dst]
void pass(const Prog& p, int src, int dst, int src2, const float* p0, const float* p1, const float* p2) {
    glBindFramebuffer(GL_FRAMEBUFFER, gFbo[dst]);
    glViewport(0, 0, gW, gH);
    glDisable(GL_BLEND);
    glUseProgram(p.id);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, gTex[src]);
    glUniform1i(p.uTex, 0);
    if (src2 >= 0 && p.uTex2 >= 0) {
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, gTex[src2]);
        glUniform1i(p.uTex2, 1);
        glActiveTexture(GL_TEXTURE0);
    }
    if (p.uP0 >= 0 && p0) glUniform4fv(p.uP0, 1, p0);
    if (p.uP1 >= 0 && p1) glUniform4fv(p.uP1, 1, p1);
    if (p.uP2 >= 0 && p2) glUniform4fv(p.uP2, 1, p2);
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 16, kQuad);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 16, kQuad + 2);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
}

// separable blur of target [a] using [b] as the temporary; the result ends up in [a]
void blurInPlace(int a, int b, float radiusPx) {
    float step = radiusPx / 8.0f;
    float h[4] = {step / (float)gW, 0.f, 0.f, 0.f};
    float v[4] = {0.f, step / (float)gH, 0.f, 0.f};
    pass(gBlur, a, b, -1, h, nullptr, nullptr);
    pass(gBlur, b, a, -1, v, nullptr, nullptr);
}

float hashf(int a, int b, int c) {
    unsigned h = ((unsigned)a * 73856093u) ^ ((unsigned)b * 19349663u) ^ ((unsigned)c * 83492791u);
    h ^= h >> 13;
    h *= 1274126177u;
    h ^= h >> 16;
    return (float)(h & 0xFFFFu) / 65535.0f;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_motionstudio_app_NativeFx_nInit(JNIEnv*, jclass) {
    if (gReady) return JNI_TRUE;
    bool ok = build(gBlur, kBlur) && build(gDir, kDir) && build(gRad, kRad) && build(gGrade, kGrade) &&
              build(gPix, kPix) && build(gRgb, kRgb) && build(gVig, kVig) && build(gBright, kBright) &&
              build(gAdd, kAdd);
    gReady = ok;
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_motionstudio_app_NativeFx_nRelease(JNIEnv*, jclass) {
    freeTargets();
    Prog* all[] = {&gBlur, &gDir, &gRad, &gGrade, &gPix, &gRgb, &gVig, &gBright, &gAdd};
    for (Prog* p : all) {
        if (p->id) glDeleteProgram(p->id);
        *p = Prog();
    }
    gReady = false;
}

// binds target 0, clears it and sets the viewport: the caller then draws the layer into it
JNIEXPORT void JNICALL Java_com_motionstudio_app_NativeFx_nBegin(JNIEnv*, jclass, jint w, jint h) {
    if (!gReady) return;
    ensure(w, h);
    glBindFramebuffer(GL_FRAMEBUFFER, gFbo[0]);
    glViewport(0, 0, w, h);
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
}

// params: 0 blur, 1 motion blur, 2 motion blur angle (deg), 3 radial blur, 4 glow, 5 glow radius,
// 6 brightness, 7 contrast, 8 hue (deg), 9 saturation, 10 tint amount, 11-13 tint rgb,
// 14 pixel size, 15 rgb split, 16 vignette, 17 grain, 18 invert, 19 seed, 20 px per comp px
// returns the id of the texture that holds the result; the default framebuffer is bound again
JNIEXPORT jint JNICALL Java_com_motionstudio_app_NativeFx_nRun(JNIEnv* env, jclass, jfloatArray params) {
    if (!gReady || !gFbo[0]) return 0;
    float P[24];
    memset(P, 0, sizeof(P));
    env->GetFloatArrayRegion(params, 0, 24, P);
    const float s = P[20] > 0.f ? P[20] : 1.f;
    int cur = 0;

    if (P[0] * s > 0.3f) {
        int a = (cur + 1) % 3, b = (cur + 2) % 3;
        float step = P[0] * s / 8.0f;
        float h[4] = {step / (float)gW, 0.f, 0.f, 0.f};
        float v[4] = {0.f, step / (float)gH, 0.f, 0.f};
        pass(gBlur, cur, a, -1, h, nullptr, nullptr);
        pass(gBlur, a, b, -1, v, nullptr, nullptr);
        cur = b;
    }
    if (P[1] * s > 0.3f) {
        float ang = P[2] * 3.14159265f / 180.f;
        float vec[4] = {std::cos(ang) * P[1] * s / (float)gW, std::sin(ang) * P[1] * s / (float)gH, 0.f, 0.f};
        int a = (cur + 1) % 3;
        pass(gDir, cur, a, -1, vec, nullptr, nullptr);
        cur = a;
    }
    if (P[3] > 0.01f) {
        float amt[4] = {P[3], 0.f, 0.f, 0.f};
        int a = (cur + 1) % 3;
        pass(gRad, cur, a, -1, amt, nullptr, nullptr);
        cur = a;
    }
    if (P[14] * s > 1.f) {
        float cells[4] = {(float)gW / (P[14] * s), (float)gH / (P[14] * s), 0.f, 0.f};
        int a = (cur + 1) % 3;
        pass(gPix, cur, a, -1, cells, nullptr, nullptr);
        cur = a;
    }
    if (P[15] * s > 0.3f) {
        float off[4] = {P[15] * s / (float)gW, 0.f, 0.f, 0.f};
        int a = (cur + 1) % 3;
        pass(gRgb, cur, a, -1, off, nullptr, nullptr);
        cur = a;
    }
    if (P[6] != 0.f || P[7] != 0.f || P[8] != 0.f || P[9] != 0.f || P[10] > 0.01f || P[18] > 0.01f) {
        float p0[4] = {P[6], P[7], P[8] * 3.14159265f / 180.f, P[9]};
        float p1[4] = {P[11], P[12], P[13], P[10]};
        float p2[4] = {P[18], 0.f, 0.f, 0.f};
        int a = (cur + 1) % 3;
        pass(gGrade, cur, a, -1, p0, p1, p2);
        cur = a;
    }
    if (P[4] > 0.01f) {
        int o = cur;
        int a = (o + 1) % 3, b = (o + 2) % 3;
        float thr[4] = {0.55f, 0.f, 0.f, 0.f};
        pass(gBright, o, a, -1, thr, nullptr, nullptr);
        blurInPlace(a, b, (P[5] > 1.f ? P[5] : 12.f) * s);
        float inten[4] = {P[4], 0.f, 0.f, 0.f};
        pass(gAdd, o, b, a, inten, nullptr, nullptr);
        cur = b;
    }
    if (P[16] > 0.01f || P[17] > 0.01f) {
        float vg[4] = {P[16], P[17], P[19], 0.f};
        int a = (cur + 1) % 3;
        pass(gVig, cur, a, -1, vg, nullptr, nullptr);
        cur = a;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return (jint)gTex[cur];
}

// params: 0 t, 1 start, 2 life, 3 x, 4 y, 5 scale, 6 rotation, 7 angle, 8 spread, 9 speed,
// 10 gravity, 11 size, 12 alpha, 13 layer id, 14 count
// out: x, y, size, alpha for every living particle; returns how many were written
JNIEXPORT jint JNICALL Java_com_motionstudio_app_NativeFx_nParticles(JNIEnv* env, jclass, jfloatArray params, jfloatArray out) {
    float P[16];
    memset(P, 0, sizeof(P));
    env->GetFloatArrayRegion(params, 0, 15, P);
    const int n = (int)P[14];
    const jsize cap = env->GetArrayLength(out) / 4;
    const float t = P[0], start = P[1], life = P[2] < 0.2f ? 0.2f : P[2];
    const float x = P[3], y = P[4], sc = P[5], rot = P[6];
    const float pAngle = P[7], pSpread = P[8], pSpeed = P[9], pGrav = P[10], pSize = P[11], alpha = P[12];
    const int id = (int)P[13];
    jfloat* o = env->GetFloatArrayElements(out, nullptr);
    int k = 0;
    for (int i = 0; i < n && k < cap; i++) {
        float age = t - start - (float)i * life / (float)n;
        if (age < 0.f) continue;
        int cyc = (int)std::floor(age / life);
        float a = age - (float)cyc * life;
        float u = a / life;
        float h1 = hashf(i, cyc, id);
        float h2 = hashf(i, cyc, id + 7);
        float h3 = hashf(i, cyc, id + 13);
        float ang = (pAngle + rot + (h1 - 0.5f) * pSpread) * 3.14159265f / 180.f;
        float spd = pSpeed * sc * (0.5f + h2);
        o[k * 4 + 0] = x + std::cos(ang) * spd * a;
        o[k * 4 + 1] = y + std::sin(ang) * spd * a + 0.5f * pGrav * sc * a * a;
        o[k * 4 + 2] = pSize * sc * (0.4f + 0.6f * (1.f - u)) * (0.6f + 0.8f * h3);
        o[k * 4 + 3] = alpha * (1.f - u);
        k++;
    }
    env->ReleaseFloatArrayElements(out, o, 0);
    return k;
}

}  // extern "C"
