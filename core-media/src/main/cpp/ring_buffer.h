#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>

namespace edgevoice {

/**
 * 无锁单生产者单消费者(Single-Producer Single-Consumer)环形缓冲区。
 *
 * 设计要点（对齐技术文档 core-media「零拷贝 / 消灭 GC 抖动」目标）：
 *  - 使用单调递增的 writePos_/readPos_ 计数器 + mask 取模索引，天然规避「满/空」二义性；
 *  - 生产者仅写 writePos_（release），消费者仅写 readPos_（release），
 *    acquire/release 形成 happens-before，无需任何互斥锁；
 *  - 容量取 2 的幂，用位与替代取模，降低热路径指令开销。
 *
 * 注意：本结构仅适用于「一读一写」线程模型；多生产者/多消费者需外部串行化。
 */
class SpscRingBuffer {
public:
    explicit SpscRingBuffer(size_t capacityBytes) noexcept;

    SpscRingBuffer(const SpscRingBuffer&) = delete;
    SpscRingBuffer& operator=(const SpscRingBuffer&) = delete;

    /** 写入数据，返回实际写入字节数（空间不足时截断）。 */
    size_t write(const uint8_t* src, size_t len) noexcept;

    /** 读取数据，返回实际读取字节数（可读不足时截断）。 */
    size_t read(uint8_t* dst, size_t len) noexcept;

    size_t availableToRead() const noexcept;
    size_t availableToWrite() const noexcept;
    size_t capacity() const noexcept { return capacity_; }

    /** 丢弃全部未读数据（用于打断场景清空 AudioTrack 队列）。 */
    void clear() noexcept;

    bool valid() const noexcept { return buffer_ != nullptr; }

private:
    static size_t roundUpPow2(size_t v) noexcept;

    std::unique_ptr<uint8_t[]> buffer_;
    size_t capacity_{0};
    size_t mask_{0};
    std::atomic<size_t> writePos_{0};
    std::atomic<size_t> readPos_{0};
};

}  // namespace edgevoice
