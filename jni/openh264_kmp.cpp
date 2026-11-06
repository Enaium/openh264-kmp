/*
 * C++ shim for the OpenH264 Kotlin Multiplatform binding. See
 * openh264_kmp.h for the contract.
 *
 * Only the public decoder API is used, so the same translation unit compiles
 * for every target. The shim owns the packed output format: the Kotlin side
 * then has a single parser for JNI, cinterop and WebAssembly.
 */
#include "openh264_kmp.h"

#include <stdlib.h>
#include <string.h>

#include "codec_api.h"

struct ok_decoder {
  ISVCDecoder *dec;

  /* Last picture described by the accessors below. */
  int frame_ready;
  int width;
  int height;
  int stride_y;
  int stride_u;
  int stride_v;
  int64_t pts;

  /* The decoder's planes are only valid until the next call, so the accessors
   * point at the decoder's buffers while the blob is a private copy. */
  uint8_t *planes[3];

  uint8_t *blob;
  int blob_capacity;
};

/* ------------------------------------------------------------------------- */
/* Memory                                                                     */
/* ------------------------------------------------------------------------- */

void *ok_alloc(size_t size) {
  /* malloc(0) may return NULL, which the callers would report as OOM. */
  return malloc(size ? size : 1);
}

void ok_free(void *pointer) { free(pointer); }

/* ------------------------------------------------------------------------- */
/* Version                                                                    */
/* ------------------------------------------------------------------------- */

int ok_version_packed(void) { return 20700; }

const char *ok_version_string(void) { return "2.7.0"; }

int ok_api_build(void) { return 20700; }

/* ------------------------------------------------------------------------- */
/* Decoder                                                                    */
/* ------------------------------------------------------------------------- */

static void ok_decoder_clear_frame(ok_decoder *decoder) {
  decoder->frame_ready = 0;
  decoder->width = 0;
  decoder->height = 0;
  decoder->stride_y = 0;
  decoder->stride_u = 0;
  decoder->stride_v = 0;
  decoder->planes[0] = NULL;
  decoder->planes[1] = NULL;
  decoder->planes[2] = NULL;
}

ok_decoder *ok_decoder_create(void) {
  ok_decoder *decoder = (ok_decoder *)calloc(1, sizeof(ok_decoder));
  if (!decoder) return NULL;

  ISVCDecoder *svc = NULL;
  if (WelsCreateDecoder(&svc) != 0 || !svc) {
    free(decoder);
    return NULL;
  }

  int trace_level = WELS_LOG_ERROR;
  svc->SetOption(DECODER_OPTION_TRACE_LEVEL, &trace_level);

  SDecodingParam param;
  memset(&param, 0, sizeof(param));
  param.uiTargetDqLayer = (unsigned char)-1;
  param.eEcActiveIdc = ERROR_CON_SLICE_COPY;
  param.sVideoProperty.eVideoBsType = VIDEO_BITSTREAM_AVC;

  if (svc->Initialize(&param) != 0) {
    WelsDestroyDecoder(svc);
    free(decoder);
    return NULL;
  }

  decoder->dec = svc;
  return decoder;
}

void ok_decoder_destroy(ok_decoder *decoder) {
  if (!decoder) return;
  if (decoder->dec) {
    decoder->dec->Uninitialize();
    WelsDestroyDecoder(decoder->dec);
    decoder->dec = NULL;
  }
  free(decoder->blob);
  free(decoder);
}

/* Copies the picture the decoder just produced into the accessor fields. */
static void ok_decoder_capture(ok_decoder *decoder, SBufferInfo *info) {
  decoder->frame_ready = info->iBufferStatus == 1 && info->pDst[0] != NULL;
  if (!decoder->frame_ready) {
    ok_decoder_clear_frame(decoder);
    return;
  }
  decoder->width = info->UsrData.sSystemBuffer.iWidth;
  decoder->height = info->UsrData.sSystemBuffer.iHeight;
  decoder->stride_y = info->UsrData.sSystemBuffer.iStride[0];
  decoder->stride_u = info->UsrData.sSystemBuffer.iStride[1];
  decoder->stride_v = info->UsrData.sSystemBuffer.iStride[1];
  decoder->planes[0] = info->pDst[0];
  decoder->planes[1] = info->pDst[1];
  decoder->planes[2] = info->pDst[2];

  if (decoder->width <= 0 || decoder->height <= 0 ||
      decoder->stride_y <= 0 || decoder->stride_u <= 0 ||
      decoder->planes[1] == NULL || decoder->planes[2] == NULL) {
    ok_decoder_clear_frame(decoder);
  }
}

int ok_decoder_decode(ok_decoder *decoder, const uint8_t *annexb, int size, int64_t pts) {
  if (!decoder || !decoder->dec || !annexb || size <= 0) return OK_ERROR;

  ok_decoder_clear_frame(decoder);
  SBufferInfo info;
  memset(&info, 0, sizeof(info));
  unsigned char *dst[3] = {NULL, NULL, NULL};

  DECODING_STATE state = decoder->dec->DecodeFrameNoDelay(annexb, size, dst, &info);
  (void)state;

  ok_decoder_capture(decoder, &info);
  decoder->pts = pts;
  return decoder->frame_ready ? OK_OK : OK_BUFFERED;
}

int ok_decoder_flush(ok_decoder *decoder) {
  if (!decoder || !decoder->dec) return OK_ERROR;

  ok_decoder_clear_frame(decoder);
  SBufferInfo info;
  memset(&info, 0, sizeof(info));
  unsigned char *dst[3] = {NULL, NULL, NULL};

  decoder->dec->FlushFrame(dst, &info);
  ok_decoder_capture(decoder, &info);
  return decoder->frame_ready ? OK_OK : OK_BUFFERED;
}

int ok_decoder_frame_ready(const ok_decoder *decoder) {
  return decoder ? decoder->frame_ready : 0;
}

int ok_decoder_frame_width(const ok_decoder *decoder) {
  return decoder ? decoder->width : 0;
}

int ok_decoder_frame_height(const ok_decoder *decoder) {
  return decoder ? decoder->height : 0;
}

int ok_decoder_frame_chroma_format(const ok_decoder *decoder) {
  (void)decoder;
  return OK_CSP_I420;
}

int64_t ok_decoder_frame_pts(const ok_decoder *decoder) {
  return decoder ? decoder->pts : 0;
}

const uint8_t *ok_decoder_frame_plane(const ok_decoder *decoder, int plane, int *stride) {
  if (stride) *stride = 0;
  if (!decoder || !decoder->frame_ready || plane < 0 || plane > 2) return NULL;
  if (stride) {
    *stride = plane == 0 ? decoder->stride_y : decoder->stride_u;
  }
  return decoder->planes[plane];
}

static int ok_blob_ensure(ok_decoder *decoder, int capacity) {
  if (decoder->blob_capacity >= capacity) return 0;
  uint8_t *grown = (uint8_t *)realloc(decoder->blob, (size_t)capacity);
  if (!grown) return OK_ERROR;
  decoder->blob = grown;
  decoder->blob_capacity = capacity;
  return 0;
}

static void ok_write_i32(uint8_t *destination, int value) {
  destination[0] = (uint8_t)(value);
  destination[1] = (uint8_t)(value >> 8);
  destination[2] = (uint8_t)(value >> 16);
  destination[3] = (uint8_t)(value >> 24);
}

static void ok_write_i64(uint8_t *destination, int64_t value) {
  uint64_t bits = (uint64_t)value;
  ok_write_i32(destination, (int)(uint32_t)bits);
  ok_write_i32(destination + 4, (int)(uint32_t)(bits >> 32));
}

static void ok_copy_plane(uint8_t *destination, const uint8_t *source,
                          int width, int height, int stride) {
  if (!source) {
    memset(destination, 0, (size_t)width * (size_t)height);
    return;
  }
  for (int row = 0; row < height; row++) {
    memcpy(destination + (size_t)row * (size_t)width,
           source + (size_t)row * (size_t)stride,
           (size_t)width);
  }
}

#define OK_BLOB_HEADER 64

const uint8_t *ok_decoder_frame_blob(ok_decoder *decoder, int *size) {
  if (size) *size = 0;
  if (!decoder || !decoder->frame_ready) return NULL;

  int width = decoder->width;
  int height = decoder->height;
  int chroma_width = (width + 1) / 2;
  int chroma_height = (height + 1) / 2;
  int y_size = width * height;
  int u_size = chroma_width * chroma_height;
  int v_size = u_size;
  int payload_size = y_size + u_size + v_size;
  int total = OK_BLOB_HEADER + payload_size;
  if (total <= OK_BLOB_HEADER) return NULL;
  if (ok_blob_ensure(decoder, total) < 0) return NULL;

  uint8_t *blob = decoder->blob;
  memset(blob, 0, (size_t)OK_BLOB_HEADER);
  ok_write_i32(blob, OK_OUTPUT_MAGIC);
  ok_write_i32(blob + 4, OK_OUTPUT_VERSION);
  ok_write_i32(blob + 8, width);
  ok_write_i32(blob + 12, height);
  ok_write_i32(blob + 16, OK_CSP_I420);
  ok_write_i32(blob + 20, width);
  ok_write_i32(blob + 24, chroma_width);
  ok_write_i32(blob + 28, chroma_width);
  ok_write_i64(blob + 32, decoder->pts);
  ok_write_i32(blob + 40, y_size);
  ok_write_i32(blob + 44, u_size);
  ok_write_i32(blob + 48, v_size);
  ok_write_i32(blob + 52, 0);
  ok_write_i32(blob + 56, payload_size);
  ok_write_i32(blob + 60, 0);

  uint8_t *payload = blob + OK_BLOB_HEADER;
  ok_copy_plane(payload, decoder->planes[0], width, height, decoder->stride_y);
  ok_copy_plane(payload + y_size, decoder->planes[1], chroma_width, chroma_height,
                decoder->stride_u);
  ok_copy_plane(payload + y_size + u_size, decoder->planes[2], chroma_width,
                chroma_height, decoder->stride_v);

  if (size) *size = total;
  return blob;
}
