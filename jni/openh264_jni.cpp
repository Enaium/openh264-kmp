/*
 * JNI bridge between cn.enaium.openh264.JniOpenH264 and the C shim.
 *
 * The declarations on the Kotlin side live in
 * openh264-kmp/src/jvmMain/kotlin/cn/enaium/openh264/OpenH264Native.jvm.kt.
 *
 * Handles are raw ok_decoder pointers stored in a jlong. The access unit is
 * copied into the shim; the shim copies the decoded planes out before the
 * call returns, so the decoder's buffers never escape.
 */
#include <jni.h>
#include <stdint.h>

#include "openh264_kmp.h"

/* JNI resolves functions by their unmangled C name. */
extern "C" {

static ok_decoder *ok_decoder_from(jlong handle) {
  return (ok_decoder *)(intptr_t)handle;
}

JNIEXPORT jint JNICALL
Java_cn_enaium_openh264_JniOpenH264_nativeVersionPacked(JNIEnv *env, jobject self) {
  (void)env;
  (void)self;
  return ok_version_packed();
}

JNIEXPORT jstring JNICALL
Java_cn_enaium_openh264_JniOpenH264_nativeVersionString(JNIEnv *env, jobject self) {
  (void)self;
  const char *version = ok_version_string();
  return env->NewStringUTF(version ? version : "");
}

JNIEXPORT jint JNICALL
Java_cn_enaium_openh264_JniOpenH264_nativeApiBuild(JNIEnv *env, jobject self) {
  (void)env;
  (void)self;
  return ok_api_build();
}

JNIEXPORT jlong JNICALL
Java_cn_enaium_openh264_JniOpenH264_nativeDecoderCreate(JNIEnv *env, jobject self) {
  (void)env;
  (void)self;
  return (jlong)(intptr_t)ok_decoder_create();
}

JNIEXPORT void JNICALL
Java_cn_enaium_openh264_JniOpenH264_nativeDecoderDestroy(JNIEnv *env, jobject self,
                                                         jlong handle) {
  (void)env;
  (void)self;
  ok_decoder_destroy(ok_decoder_from(handle));
}

JNIEXPORT jint JNICALL
Java_cn_enaium_openh264_JniOpenH264_nativeDecode(JNIEnv *env, jobject self, jlong handle,
                                                 jbyteArray accessUnit, jlong pts) {
  (void)self;
  ok_decoder *decoder = ok_decoder_from(handle);
  if (!decoder || !accessUnit) return OK_ERROR;

  jsize size = env->GetArrayLength(accessUnit);
  if (size <= 0) return OK_ERROR;

  jbyte *bytes = env->GetByteArrayElements(accessUnit, NULL);
  if (!bytes) return OK_ERROR;

  int result = ok_decoder_decode(decoder, (const uint8_t *)bytes, (int)size, (int64_t)pts);
  env->ReleaseByteArrayElements(accessUnit, bytes, JNI_ABORT);
  return result;
}

JNIEXPORT jint JNICALL
Java_cn_enaium_openh264_JniOpenH264_nativeFlush(JNIEnv *env, jobject self, jlong handle) {
  (void)env;
  (void)self;
  return ok_decoder_flush(ok_decoder_from(handle));
}

JNIEXPORT jbyteArray JNICALL
Java_cn_enaium_openh264_JniOpenH264_nativeFrameBlob(JNIEnv *env, jobject self, jlong handle) {
  (void)self;
  int size = 0;
  const uint8_t *blob = ok_decoder_frame_blob(ok_decoder_from(handle), &size);
  if (!blob || size <= 0) return NULL;
  jbyteArray result = env->NewByteArray(size);
  if (!result) return NULL;
  env->SetByteArrayRegion(result, 0, size, (const jbyte *)blob);
  return result;
}

} /* extern "C" */
