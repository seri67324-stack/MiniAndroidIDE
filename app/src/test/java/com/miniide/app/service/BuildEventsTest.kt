package com.miniide.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BuildEventsTest {

    @Test
    fun matchesProjectOnlyForSameProject() {
        val a = Files.createTempDirectory("projA").toFile()
        val b = Files.createTempDirectory("projB").toFile()
        try {
            BuildEvents.reset()
            assertFalse("Derleme yokken eşleşme olmamalı", BuildEvents.matchesProject(a))

            BuildEvents.lastProject = a
            assertTrue(BuildEvents.matchesProject(File(a.absolutePath)))
            assertFalse(BuildEvents.matchesProject(b))
            assertFalse(BuildEvents.matchesProject(null))

            BuildEvents.reset()
            assertFalse("reset sonrası eşleşme kalmamalı", BuildEvents.matchesProject(a))
        } finally {
            BuildEvents.reset()
            a.deleteRecursively()
            b.deleteRecursively()
        }
    }
}
