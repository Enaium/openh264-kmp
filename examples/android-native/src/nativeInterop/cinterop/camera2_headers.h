#ifndef LIBYUV_KMP_CAMERA2_HEADERS_H
#define LIBYUV_KMP_CAMERA2_HEADERS_H

/*
 * Kotlin/Native's Android cinterop compiles with the toolchain's base API
 * level. Camera2 NDK declarations are guarded by __ANDROID_API__ >= 24, so
 * raise it before including the headers instead of losing every camera2
 * declaration to the preprocessor.
 */
#undef __ANDROID_API__
#define __ANDROID_API__ 26

#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCaptureRequest.h>
#include <camera/NdkCameraCaptureSession.h>
#include <camera/NdkCameraMetadata.h>
#include <media/NdkImageReader.h>
#include <media/NdkImage.h>

#endif
