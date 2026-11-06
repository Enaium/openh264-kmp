/*
 * C/C++ shim for the OpenH264 Kotlin Multiplatform binding.
 *
 * The same implementation is compiled three ways:
 *   - into the static archive linked by every Kotlin/Native cinterop klib,
 *   - into the JNI shared library (openh264_jni.cpp),
 *   - into the standalone WebAssembly module (openh264_wasm_entry.c).
 *
 * All three expose this API, so argument validation and the packed output
 * format are identical on every platform.
 *
 * The binding is decoder-only: callers hand it Annex-B access units (the
 * format produced by MediaCodec and WebCodecs with `annexb` output) and get
 * back planar I420 pictures.
 *
 * Conventions:
 *   - functions returning int return OK_OK / OK_BUFFERED on success and a
 *     negative code on failure;
 *   - `ok_decoder_frame_blob()` returns a buffer owned by the decoder, valid
 *     until the next call that decodes or flushes; callers copy it if they
 *     keep it;
 *   - planes are tightly packed 8-bit I420. The decoder's own stride is
 *     reported by `ok_decoder_frame_plane()` but the blob is always packed.
 */
#ifndef OPENH264_KMP_H
#define OPENH264_KMP_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** First four bytes of the blob returned by `ok_decoder_frame_blob()`. */
#define OK_OUTPUT_MAGIC 0x314B4F48 /* "HOK1" little-endian */

/** Layout revision of the blob. */
#define OK_OUTPUT_VERSION 1

/** A picture is available in the output buffer. */
#define OK_OK 1

/** The input was accepted but no picture is ready yet (or flushing is done). */
#define OK_BUFFERED 0

/** Generic failure; the decoder is usually unusable afterwards. */
#define OK_ERROR -1

/** Colour space of every frame this binding produces. */
#define OK_CSP_I420 1

typedef struct ok_decoder ok_decoder;

/* ------------------------------------------------------------------ */
/* Version                                                            */
/* ------------------------------------------------------------------ */

/** OpenH264 release packed as major * 10000 + minor * 100 + revision. */
int ok_version_packed(void);

/** Human-readable OpenH264 version, e.g. "2.7.0". */
const char *ok_version_string(void);

/** Same as `ok_version_packed()`; kept for the shared Kotlin contract. */
int ok_api_build(void);

/* ------------------------------------------------------------------ */
/* Decoder                                                            */
/* ------------------------------------------------------------------ */

/** Creates and initialises an H.264 decoder; NULL on failure. */
ok_decoder *ok_decoder_create(void);

void ok_decoder_destroy(ok_decoder *decoder);

/**
 * Decodes one Annex-B access unit (one or more NAL units, with start codes).
 *
 * Returns OK_OK when a picture is ready, OK_BUFFERED when the decoder needs
 * more data (or concealed an error without producing a picture) and OK_ERROR
 * for an invalid argument. `pts` is reported back on the produced frame.
 */
int ok_decoder_decode(ok_decoder *decoder, const uint8_t *annexb, int size, int64_t pts);

/** Drains pictures still held in the decoder's reorder buffer. */
int ok_decoder_flush(ok_decoder *decoder);

/** 1 when the last decode/flush produced a picture. */
int ok_decoder_frame_ready(const ok_decoder *decoder);

int ok_decoder_frame_width(const ok_decoder *decoder);

int ok_decoder_frame_height(const ok_decoder *decoder);

/** One of OK_CSP_*. */
int ok_decoder_frame_chroma_format(const ok_decoder *decoder);

int64_t ok_decoder_frame_pts(const ok_decoder *decoder);

/**
 * Plane pointer of the last picture: plane 0 is luma, 1 U and 2 V. `stride`
 * receives the decoder's row stride in bytes. The pointer is valid until the
 * next decode/flush call.
 */
const uint8_t *ok_decoder_frame_plane(const ok_decoder *decoder, int plane, int *stride);

/**
 * Serialises the last picture into the packed format below and returns it;
 * `size` receives the byte count. The buffer is reused by the next
 * decode/flush call.
 *
 * Layout (all integers little-endian):
 *   int32  magic (OK_OUTPUT_MAGIC)
 *   int32  version (OK_OUTPUT_VERSION)
 *   int32  width
 *   int32  height
 *   int32  chroma_format (OK_CSP_*)
 *   int32  stride_y
 *   int32  stride_u
 *   int32  stride_v
 *   int64  pts
 *   int32  y_size
 *   int32  u_size
 *   int32  v_size
 *   int32  reserved
 *   int32  payload_size
 *   int32  reserved
 *   uint8  payload[]: tightly packed Y, U and V planes
 */
const uint8_t *ok_decoder_frame_blob(ok_decoder *decoder, int *size);

/* ------------------------------------------------------------------ */
/* Memory                                                             */
/* ------------------------------------------------------------------ */

void *ok_alloc(size_t size);

void ok_free(void *pointer);

#ifdef __cplusplus
}
#endif

#endif /* OPENH264_KMP_H */
