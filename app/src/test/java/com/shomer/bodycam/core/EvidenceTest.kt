package com.shomer.bodycam.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class EvidenceTest {
    @Test fun sha256MatchesTheStandardValue() {
        val f = File.createTempFile("evidence", ".bin")
        f.writeText("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Evidence.sha256(f))
        f.writeText("abd")   // one byte changed → completely different fingerprint
        assertEquals(false, Evidence.sha256(f).startsWith("ba7816bf"))
        f.delete()
    }

    @Test fun shortFormKeepsBothEnds() {
        assertEquals("ba7816bf…f20015ad", Evidence.short("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
    }

    @Test fun sidecarSitsNextToTheVideo() {
        assertEquals("SHOMER_1.mp4.sha256", Evidence.sidecar(File("/x/SHOMER_1.mp4")).name)
    }
}
