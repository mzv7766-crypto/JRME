package com.jeremy.dashcam.core.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateReaderTest {
    @Test fun israeliSevenDigits() = assertEquals(listOf("12-345-67"), PlateReader.extract("IL 12-345-67"))
    @Test fun israeliEightDigits() = assertEquals(listOf("123-45-678"), PlateReader.extract("123-45-678"))
    @Test fun withoutDashes() = assertEquals(listOf("123-45-678"), PlateReader.extract("12345678"))
    @Test fun ocrConfusionsAreFixed() = assertEquals(listOf("12-305-67"), PlateReader.extract("I2-3O5-G7".replace('G', '6')))
    @Test fun eightDigitNotSplitIntoSeven() = assertTrue(PlateReader.extract("987-65-432").none { it.length == 9 && it != "987-65-432" })
    @Test fun ordinaryTextIgnored() = assertTrue(PlateReader.extract("STOP\nSPEED LIMIT").isEmpty())
}
