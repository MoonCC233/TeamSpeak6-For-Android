package com.mooncc.teamspeak9.voice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMixerTest {

    @Test
    fun `mixing nothing produces silence`() {
        val out = shortArrayOf(1, 2, 3, 4)
        AudioMixer.mix(emptyList(), out)
        assertTrue(out.all { it == 0.toShort() })
    }

    @Test
    fun `a single frame is copied verbatim`() {
        val out = ShortArray(4)
        AudioMixer.mix(listOf(shortArrayOf(10, -20, 30, -40)), out)
        assertEquals(listOf<Short>(10, -20, 30, -40), out.toList())
    }

    @Test
    fun `several frames are summed`() {
        val out = ShortArray(3)
        AudioMixer.mix(
            listOf(
                shortArrayOf(100, -100, 0),
                shortArrayOf(50, -50, 25),
                shortArrayOf(-25, 25, -25),
            ),
            out,
        )
        assertEquals(listOf<Short>(125, -125, 0), out.toList())
    }

    @Test
    fun `mixing clips instead of wrapping around`() {
        val out = ShortArray(2)
        AudioMixer.mix(
            listOf(
                shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE),
                shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE),
            ),
            out,
        )
        assertEquals(Short.MAX_VALUE, out[0])
        assertEquals(Short.MIN_VALUE, out[1])
    }

    @Test
    fun `a shorter frame does not overrun the output`() {
        val out = ShortArray(4)
        AudioMixer.mix(listOf(shortArrayOf(5, 5), shortArrayOf(1, 1, 1, 1)), out)
        assertEquals(listOf<Short>(6, 6, 1, 1), out.toList())
    }

    @Test
    fun `unity gain leaves samples untouched`() {
        val frame = shortArrayOf(1000, -1000)
        AudioMixer.applyGain(frame, 100)
        assertEquals(listOf<Short>(1000, -1000), frame.toList())
    }

    @Test
    fun `applying gain scales and clips`() {
        val frame = shortArrayOf(1000, -1000, Short.MAX_VALUE, Short.MIN_VALUE)
        AudioMixer.applyGain(frame, 200)
        assertEquals(2000, frame[0].toInt())
        assertEquals(-2000, frame[1].toInt())
        assertEquals(Short.MAX_VALUE, frame[2])
        assertEquals(Short.MIN_VALUE, frame[3])
    }

    @Test
    fun `zero gain silences the frame`() {
        val frame = shortArrayOf(1000, -1000)
        AudioMixer.applyGain(frame, 0)
        assertTrue(frame.all { it == 0.toShort() })
    }

    @Test
    fun `silence measures as the floor level`() {
        assertEquals(AudioMixer.MIN_DB, AudioMixer.levelDb(ShortArray(64)), 0.01f)
    }

    @Test
    fun `full scale measures as zero dbfs`() {
        val frame = ShortArray(64) { Short.MAX_VALUE }
        assertEquals(0f, AudioMixer.levelDb(frame), 0.5f)
    }

    @Test
    fun `halving the amplitude drops about six decibels`() {
        val loud = ShortArray(64) { 8000 }
        val quiet = ShortArray(64) { 4000 }
        assertEquals(-6f, AudioMixer.levelDb(quiet) - AudioMixer.levelDb(loud), 0.5f)
    }

    @Test
    fun `an empty frame measures as the floor level`() {
        assertEquals(AudioMixer.MIN_DB, AudioMixer.levelDb(ShortArray(0)), 0.01f)
    }
}
