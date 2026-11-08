#include <jni.h>
#include <string.h>
#include "opus.h"

static const int SAMPLE_RATE = 16000;
static const int CHANNELS = 1;
static const int FRAME_SAMPLES = 320;
static const int BITRATE = 20000;
static const int COMPLEXITY = 5;
static const int MAX_PACKET = 1500;

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_wifigram_call_OpusCodec_nativeCreateEncoder(JNIEnv *, jclass) {
    int error = OPUS_OK;
    OpusEncoder *encoder = opus_encoder_create(SAMPLE_RATE, CHANNELS,
                                                OPUS_APPLICATION_VOIP, &error);
    if (error != OPUS_OK || encoder == nullptr) return 0;
    opus_encoder_ctl(encoder, OPUS_SET_BITRATE(BITRATE));
    opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(COMPLEXITY));
    opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(encoder, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(encoder, OPUS_SET_PACKET_LOSS_PERC(5));
    return reinterpret_cast<jlong>(encoder);
}

JNIEXPORT jlong JNICALL
Java_app_wifigram_call_OpusCodec_nativeCreateDecoder(JNIEnv *, jclass) {
    int error = OPUS_OK;
    OpusDecoder *decoder = opus_decoder_create(SAMPLE_RATE, CHANNELS, &error);
    if (error != OPUS_OK || decoder == nullptr) return 0;
    return reinterpret_cast<jlong>(decoder);
}

JNIEXPORT jint JNICALL
Java_app_wifigram_call_OpusCodec_nativeEncode(JNIEnv *env, jclass,
                                               jlong handle, jbyteArray pcm,
                                               jbyteArray out) {
    auto *encoder = reinterpret_cast<OpusEncoder *>(handle);
    if (encoder == nullptr || pcm == nullptr || out == nullptr) return -1;
    jsize pcmLength = env->GetArrayLength(pcm);
    jsize outLength = env->GetArrayLength(out);
    if (pcmLength <= 0 || outLength <= 0) return -1;
    jbyte *pcmBytes = env->GetByteArrayElements(pcm, nullptr);
    jbyte *outBytes = env->GetByteArrayElements(out, nullptr);
    int written = -1;
    if (pcmBytes != nullptr && outBytes != nullptr) {
        written = opus_encode(encoder,
                              reinterpret_cast<const opus_int16 *>(pcmBytes),
                              pcmLength / 2 / CHANNELS,
                              reinterpret_cast<unsigned char *>(outBytes),
                              outLength);
    }
    if (pcmBytes != nullptr) env->ReleaseByteArrayElements(pcm, pcmBytes, JNI_ABORT);
    if (outBytes != nullptr) {
        if (written > 0) env->ReleaseByteArrayElements(out, outBytes, 0);
        else env->ReleaseByteArrayElements(out, outBytes, JNI_ABORT);
    }
    return written;
}

JNIEXPORT jint JNICALL
Java_app_wifigram_call_OpusCodec_nativeDecode(JNIEnv *env, jclass, jlong handle,
                                               jbyteArray data, jbyteArray out) {
    auto *decoder = reinterpret_cast<OpusDecoder *>(handle);
    if (decoder == nullptr || data == nullptr || out == nullptr) return -1;
    jsize dataLength = env->GetArrayLength(data);
    jsize outLength = env->GetArrayLength(out);
    if (dataLength <= 0 || outLength <= 0) return -1;
    jbyte *dataBytes = env->GetByteArrayElements(data, nullptr);
    jbyte *outBytes = env->GetByteArrayElements(out, nullptr);
    int samples = -1;
    if (dataBytes != nullptr && outBytes != nullptr) {
        samples = opus_decode(decoder,
                              reinterpret_cast<const unsigned char *>(dataBytes),
                              dataLength,
                              reinterpret_cast<opus_int16 *>(outBytes),
                              outLength / 2 / CHANNELS, 0);
    }
    if (dataBytes != nullptr) env->ReleaseByteArrayElements(data, dataBytes, JNI_ABORT);
    if (outBytes != nullptr) {
        if (samples > 0) env->ReleaseByteArrayElements(out, outBytes, 0);
        else env->ReleaseByteArrayElements(out, outBytes, JNI_ABORT);
    }
    return samples;
}

JNIEXPORT void JNICALL
Java_app_wifigram_call_OpusCodec_nativeDestroyEncoder(JNIEnv *, jclass, jlong handle) {
    if (handle != 0) opus_encoder_destroy(reinterpret_cast<OpusEncoder *>(handle));
}

JNIEXPORT void JNICALL
Java_app_wifigram_call_OpusCodec_nativeDestroyDecoder(JNIEnv *, jclass, jlong handle) {
    if (handle != 0) opus_decoder_destroy(reinterpret_cast<OpusDecoder *>(handle));
}

}
