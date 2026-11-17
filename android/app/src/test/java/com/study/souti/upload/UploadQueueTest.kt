package com.study.souti.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class UploadQueueTest {
    @Test
    fun queueRetainsOnlyNewestTwoFrames() {
        val queue = UploadQueue(capacity = 2)
        val first = frame("first")
        val second = frame("second")
        val third = frame("third")

        assertNull(queue.enqueue(first))
        assertNull(queue.enqueue(second))
        assertEquals(first, queue.enqueue(third))
        assertEquals(2, queue.size())
        val polled = queue.pollLatest()
        assertEquals(third, polled?.first)
        assertEquals(listOf(second), polled?.second)
        assertFalse(queue.hasPending())
        assertNull(queue.pollLatest())
    }

    @Test
    fun latestPendingFrameIsAlwaysReturnedFirst() {
        val queue = UploadQueue(capacity = 2)
        val old = frame("old")
        queue.enqueue(old)
        val latest = frame("latest")
        queue.enqueue(latest)

        val polled = queue.pollLatest()
        assertEquals(latest, polled?.first)
        assertEquals(listOf(old), polled?.second)
        assertFalse(queue.hasPending())
    }

    @Test
    fun clearReturnsAllFramesAndEmptiesQueue() {
        val queue = UploadQueue(capacity = 2)
        val first = frame("first")
        val second = frame("second")
        queue.enqueue(first)
        queue.enqueue(second)

        assertEquals(listOf(first, second), queue.clear())
        assertEquals(0, queue.size())
    }

    private fun frame(id: String) = PendingFrame(
        sessionId = "session",
        deviceFrameId = id,
        capturedAtEpochMs = 1767225600000L,
        file = File("$id.jpg"),
        sequence = 0L,
    )
}
