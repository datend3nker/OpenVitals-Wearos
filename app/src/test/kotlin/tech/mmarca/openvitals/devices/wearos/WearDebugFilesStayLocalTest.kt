package tech.mmarca.openvitals.devices.wearos

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The debug files (`files/debug/`, see docs/features/watches.md) hold values on purpose and must
 * never reach a report, an e-mail draft or the diagnostics log. Two cheap guards on the source:
 * the file provider shares no external folder (the debug files are in the external files folder), and nothing outside `devices/wearos` names the
 * phone's debug log.
 */
class WearDebugFilesStayLocalTest {

    private val module: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "src/main/AndroidManifest.xml").exists() }

    @Test
    fun `the file provider shares no external folder`() {
        val paths = File(module, "src/main/res/xml/file_paths.xml").readText()

        assertFalse(paths.contains("external-files-path"))
        assertFalse(paths.contains("external-path"))
        assertFalse(paths.contains("external-cache-path"))
    }

    @Test
    fun `only the Wear OS package knows the phone's debug log`() {
        val outside = File(module, "src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "devices/wearos" !in it.invariantSeparatorsPath }
            .filter { file -> file.readText().let { "wear-sync" in it || "WearOsFileLog" in it } }
            .map { it.name }
            .toList()

        assertEquals(emptyList<String>(), outside)
    }
}
