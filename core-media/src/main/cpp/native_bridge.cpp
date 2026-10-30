#include <jni.h>

#include <cstdint>
#include <memory>

#include "ring_buffer.h"

using edgevoice::SpscRingBuffer;

namespace {

inline SpscRingBuffer* asRing(jlong handle) {
    return reinterpret_cast<SpscRingBuffer*>(handle);
}

inline jlong asHandle(SpscRingBuffer* p) {
    return reinterpret_cast<jlong>(p);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_edgevoice_agent_media_ring_NativeRingBuffer_nativeCreate(JNIEnv*, jobject, jint capacityBytes) {
    auto* ring = new (std::nothrow) SpscRingBuffer(static_cast<size_t>(capacityBytes));
    if (ring == nullptr || !ring->valid()) {
        delete ring;
        return 0;
    }
    return asHandle(ring);
}

JNIEXPORT void JNICALL
Java_com_edgevoice_agent_media_ring_NativeRingBuffer_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete asRing(handle);
}

/** 零拷贝写入：直接使用 DirectByteBuffer 的堆外地址，无 JVM 数组拷贝。 */
JNIEXPORT jint JNICALL
Java_com_edgevoice_agent_media_ring_NativeRingBuffer_nativeWriteDirect(
        JNIEnv* env, jobject, jlong handle, jobject directBuffer, jint length) {
    SpscRingBuffer* ring = asRing(handle);
    if (ring == nullptr) return 0;
    auto* addr = static_cast<uint8_t*>(env->GetDirectBufferAddress(directBuffer));
    if (addr == nullptr) return 0;
    return static_cast<jint>(ring->write(addr, static_cast<size_t>(length)));
}

JNIEXPORT jint JNICALL
Java_com_edgevoice_agent_media_ring_NativeRingBuffer_nativeReadDirect(
        JNIEnv* env, jobject, jlong handle, jobject directBuffer, jint length) {
    SpscRingBuffer* ring = asRing(handle);
    if (ring == nullptr) return 0;
    auto* addr = static_cast<uint8_t*>(env->GetDirectBufferAddress(directBuffer));
    if (addr == nullptr) return 0;
    return static_cast<jint>(ring->read(addr, static_cast<size_t>(length)));
}

/** 数组回退路径：便于单元测试与调试，非零拷贝。 */
JNIEXPORT jint JNICALL
Java_com_edgevoice_agent_media_ring_NativeRingBuffer_nativeWriteArray(
        JNIEnv* env, jobject, jlong handle, jbyteArray array, jint length) {
    SpscRingBuffer* ring = asRing(handle);
    if (ring == nullptr || array == nullptr) return 0;
    jbyte* elems = env->GetByteArrayElements(array, nullptr);
    if (elems == nullptr) return 0;
    const size_t written = ring->write(reinterpret_cast<uint8_t*>(elems), static_cast<size_t>(length));
    env->ReleaseByteArrayElements(array, elems, JNI_ABORT);
    return static_cast<jint>(written);
}

JNIEXPORT jint JNICALL
Java_com_edgevoice_agent_media_ring_NativeRingBuffer_nativeReadArray(
        JNIEnv* env, jobject, jlong handle, jbyteArray array, jint length) {
    SpscRingBuffer* ring = asRing(handle);
    if (ring == nullptr || array == nullptr) return 0;
    jbyte* elems = env->GetByteArrayElements(array, nullptr);
    if (elems == nullptr) return 0;
    const size_t readBytes = ring->read(reinterpret_cast<uint8_t*>(elems), static_cast<size_t>(length));
    env->ReleaseByteArrayElements(array, elems, 0);
    return static_cast<jint>(readBytes);
}

JNIEXPORT jint JNICALL
Java_com_edgevoice_agent_media_ring_NativeRingBuffer_nativeAvailableToRead(JNIEnv*, jobject, jlong handle) {
    SpscRingBuffer* ring = asRing(handle);
    return ring == nullptr ? 0 : static_cast<jint>(ring->availableToRead());
}

JNIEXPORT jint JNICALL
Java_com_edgevoice_agent_media_ring_NativeRingBuffer_nativeAvailableToWrite(JNIEnv*, jobject, jlong handle) {
    SpscRingBuffer* ring = asRing(handle);
    return ring == nullptr ? 0 : static_cast<jint>(ring->availableToWrite());
}

JNIEXPORT jint JNICALL
Java_com_edgevoice_agent_media_ring_NativeRingBuffer_nativeCapacity(JNIEnv*, jobject, jlong handle) {
    SpscRingBuffer* ring = asRing(handle);
    return ring == nullptr ? 0 : static_cast<jint>(ring->capacity());
}

JNIEXPORT void JNICALL
Java_com_edgevoice_agent_media_ring_NativeRingBuffer_nativeClear(JNIEnv*, jobject, jlong handle) {
    SpscRingBuffer* ring = asRing(handle);
    if (ring != nullptr) ring->clear();
}

}  // extern "C"
