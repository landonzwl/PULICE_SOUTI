package com.study.souti.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageProcessorTest {
    @Test
    fun landscapeIsScaledToMaximumLongEdge() {
        assertArrayEquals(
            intArrayOf(2048, 1536),
            ImageProcessor.calculateScaledSize(4000, 3000),
        )
    }

    @Test
    fun portraitIsScaledWithoutChangingAspectRatio() {
        assertArrayEquals(
            intArrayOf(1536, 2048),
            ImageProcessor.calculateScaledSize(3000, 4000),
        )
    }

    @Test
    fun smallImageIsNotEnlarged() {
        assertArrayEquals(
            intArrayOf(800, 600),
            ImageProcessor.calculateScaledSize(800, 600),
        )
    }

    @Test
    fun narrowImageKeepsPositiveDimensions() {
        assertArrayEquals(
            intArrayOf(2048, 1),
            ImageProcessor.calculateScaledSize(8000, 2),
        )
    }

    @Test
    fun invalidDimensionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageProcessor.calculateScaledSize(0, 100)
        }
    }
}
