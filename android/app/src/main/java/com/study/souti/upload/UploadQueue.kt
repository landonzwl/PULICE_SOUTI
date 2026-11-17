package com.study.souti.upload

import java.io.File
import java.util.ArrayDeque

data class PendingFrame(
    val sessionId: String,
    val deviceFrameId: String,
    val capturedAtEpochMs: Long,
    val file: File,
    val sequence: Long,
)

class UploadQueue(private val capacity: Int = 2) {
    private val frames = ArrayDeque<PendingFrame>()

    init {
        require(capacity > 0) { "队列容量必须大于 0" }
    }

    @Synchronized
    fun enqueue(frame: PendingFrame): PendingFrame? {
        val evicted = if (frames.size >= capacity) frames.removeFirst() else null
        frames.addLast(frame)
        return evicted
    }

    @Synchronized
    fun pollLatest(): Pair<PendingFrame, List<PendingFrame>>? {
        val latest = frames.pollLast() ?: return null
        val superseded = frames.toList()
        frames.clear()
        return Pair(latest, superseded)
    }

    @Synchronized
    fun peekLatest(): PendingFrame? = frames.peekLast()

    @Synchronized
    fun hasPending(): Boolean = frames.isNotEmpty()

    @Synchronized
    fun size(): Int = frames.size

    @Synchronized
    fun clear(): List<PendingFrame> {
        val remaining = frames.toList()
        frames.clear()
        return remaining
    }
}
