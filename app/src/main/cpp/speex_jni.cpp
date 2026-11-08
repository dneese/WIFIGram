#include <jni.h>
#include <speex/speex_preprocess.h>

// The noise catcher of the microphone: the hum, the fan, the machines —
// anything steady — gets quieter, the voice goes through.

static SpeexPreprocessState *preprocess = nullptr;

static const int SAMPLE_RATE = 16000;
static const int FRAME_SAMPLES = 320;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_app_wifigram_call_Denoiser_nativeInit(JNIEnv *, jclass) {
    if (preprocess != nullptr) return JNI_TRUE;
    preprocess = speex_preprocess_state_init(FRAME_SAMPLES, SAMPLE_RATE);
    if (preprocess == nullptr) return JNI_FALSE;
    int on = 1;
    int off = 0;
    int suppress = -20;
    float level = 8000.f;
    speex_preprocess_ctl(preprocess, SPEEX_PREPROCESS_SET_DENOISE, &on);
    speex_preprocess_ctl(preprocess, SPEEX_PREPROCESS_SET_NOISE_SUPPRESS, &suppress);
    speex_preprocess_ctl(preprocess, SPEEX_PREPROCESS_SET_AGC, &off);
    speex_preprocess_ctl(preprocess, SPEEX_PREPROCESS_SET_AGC_LEVEL, &level);
    speex_preprocess_ctl(preprocess, SPEEX_PREPROCESS_SET_DEREVERB, &off);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_app_wifigram_call_Denoiser_nativeProcess(JNIEnv *env, jclass, jbyteArray pcm) {
    if (preprocess == nullptr || pcm == nullptr) return;
    jsize length = env->GetArrayLength(pcm);
    if (length <= 0) return;
    jbyte *bytes = env->GetByteArrayElements(pcm, nullptr);
    if (bytes == nullptr) return;
    speex_preprocess_run(preprocess, reinterpret_cast<spx_int16_t *>(bytes));
    env->ReleaseByteArrayElements(pcm, bytes, 0);
}

}
