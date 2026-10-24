// Single-producer single-consumer byte ring for the USB pump.
// Producer: the capture thread (usbplay_write). Consumer: the libusb event thread.
// Lock-free so the USB completion callback never waits on the capture thread.
#ifndef BITPERFECT_BYTE_RING_H
#define BITPERFECT_BYTE_RING_H

#include <atomic>
#include <cstdint>
#include <cstring>
#include <vector>

class ByteRing {
public:
    // Not thread-safe; call before producer and consumer start.
    void reset(size_t capacity) {
        buf_.assign(capacity, 0);
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_relaxed);
    }

    size_t capacity() const { return buf_.size(); }

    // Bytes ready to read. Exact for the consumer, a lower bound for the producer.
    size_t size() const {
        return head_.load(std::memory_order_acquire) - tail_.load(std::memory_order_acquire);
    }

    // Producer. Writes all n bytes or nothing; returns false if they do not fit.
    bool write(const uint8_t* src, size_t n) {
        size_t head = head_.load(std::memory_order_relaxed);
        size_t tail = tail_.load(std::memory_order_acquire);
        if (n > buf_.size() - (head - tail)) return false;
        copy_in(head, src, n);
        head_.store(head + n, std::memory_order_release);
        return true;
    }

    // Consumer. Reads exactly n bytes if available; returns false (and reads nothing) otherwise.
    bool read(uint8_t* dst, size_t n) {
        size_t tail = tail_.load(std::memory_order_relaxed);
        size_t head = head_.load(std::memory_order_acquire);
        if (head - tail < n) return false;
        copy_out(tail, dst, n);
        tail_.store(tail + n, std::memory_order_release);
        return true;
    }

private:
    // Positions grow without wrapping the counters; size_t overflow takes centuries at audio rates.
    void copy_in(size_t pos, const uint8_t* src, size_t n) {
        size_t cap = buf_.size(), off = pos % cap, first = n < cap - off ? n : cap - off;
        std::memcpy(buf_.data() + off, src, first);
        std::memcpy(buf_.data(), src + first, n - first);
    }
    void copy_out(size_t pos, uint8_t* dst, size_t n) {
        size_t cap = buf_.size(), off = pos % cap, first = n < cap - off ? n : cap - off;
        std::memcpy(dst, buf_.data() + off, first);
        std::memcpy(dst + first, buf_.data(), n - first);
    }

    std::vector<uint8_t> buf_;
    std::atomic<size_t> head_{0};  // total bytes written
    std::atomic<size_t> tail_{0};  // total bytes read
};

#endif  // BITPERFECT_BYTE_RING_H
