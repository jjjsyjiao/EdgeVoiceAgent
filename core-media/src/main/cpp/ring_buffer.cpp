#include "ring_buffer.h"

#include <algorithm>
#include <cstring>

namespace edgevoice {

size_t SpscRingBuffer::roundUpPow2(size_t v) noexcept {
    if (v < 2) return 2;
    v--;
    v |= v >> 1;
    v |= v >> 2;
    v |= v >> 4;
    v |= v >> 8;
    v |= v >> 16;
    v |= v >> 32;
    return v + 1;
}

SpscRingBuffer::SpscRingBuffer(size_t capacityBytes) noexcept {
    capacity_ = roundUpPow2(capacityBytes);
    mask_ = capacity_ - 1;
    buffer_.reset(new (std::nothrow) uint8_t[capacity_]);
    if (buffer_) {
        std::memset(buffer_.get(), 0, capacity_);
    }
}

size_t SpscRingBuffer::write(const uint8_t* src, size_t len) noexcept {
    if (!buffer_ || src == nullptr || len == 0) return 0;

    const size_t wp = writePos_.load(std::memory_order_relaxed);
    const size_t rp = readPos_.load(std::memory_order_acquire);
    const size_t used = wp - rp;
    const size_t freeBytes = capacity_ - used;
    const size_t n = std::min(len, freeBytes);
    if (n == 0) return 0;

    // 环形可能跨越尾部，分两段拷贝
    const size_t firstChunk = std::min(n, capacity_ - (wp & mask_));
    std::memcpy(buffer_.get() + (wp & mask_), src, firstChunk);
    if (n > firstChunk) {
        std::memcpy(buffer_.get(), src + firstChunk, n - firstChunk);
    }

    writePos_.store(wp + n, std::memory_order_release);
    return n;
}

size_t SpscRingBuffer::read(uint8_t* dst, size_t len) noexcept {
    if (!buffer_ || dst == nullptr || len == 0) return 0;

    const size_t rp = readPos_.load(std::memory_order_relaxed);
    const size_t wp = writePos_.load(std::memory_order_acquire);
    const size_t available = wp - rp;
    const size_t n = std::min(len, available);
    if (n == 0) return 0;

    const size_t firstChunk = std::min(n, capacity_ - (rp & mask_));
    std::memcpy(dst, buffer_.get() + (rp & mask_), firstChunk);
    if (n > firstChunk) {
        std::memcpy(dst + firstChunk, buffer_.get(), n - firstChunk);
    }

    readPos_.store(rp + n, std::memory_order_release);
    return n;
}

size_t SpscRingBuffer::availableToRead() const noexcept {
    return writePos_.load(std::memory_order_acquire) - readPos_.load(std::memory_order_acquire);
}

size_t SpscRingBuffer::availableToWrite() const noexcept {
    const size_t used = writePos_.load(std::memory_order_acquire) - readPos_.load(std::memory_order_acquire);
    return capacity_ - used;
}

void SpscRingBuffer::clear() noexcept {
    // 生产者与消费者语义均归零：在无并发写入时安全（打断清空队列场景）
    readPos_.store(writePos_.load(std::memory_order_acquire), std::memory_order_release);
}

}  // namespace edgevoice
