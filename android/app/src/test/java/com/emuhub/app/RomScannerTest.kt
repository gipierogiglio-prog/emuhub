package com.emuhub.app

import com.emuhub.app.data.catalog.SystemCatalog
import com.emuhub.app.data.scanner.RomScanner
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RomScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val catalog = SystemCatalog.fromJson(
        File("src/main/assets/systems.json").readText()
    )
    private val scanner = RomScanner(catalog)

    private fun createRom(root: File, relative: String): File =
        File(root, relative).apply {
            parentFile.mkdirs()
            writeText("rom")
        }

    @Test
    fun `encontra roms por extensao na pasta do sistema`() {
        val root = tmp.newFolder("EmuHub")
        createRom(root, "roms/snes/Super Game (USA).sfc")
        createRom(root, "roms/snes/Outro Jogo.zip")
        createRom(root, "roms/snes/ignorado.txt")
        createRom(root, "roms/gba/Advance Game.gba")

        val results = scanner.scan(root)

        assertEquals(3, results.size)
        assertEquals(2, results.count { it.systemId == "snes" })
        assertEquals(1, results.count { it.systemId == "gba" })
    }

    @Test
    fun `encontra roms em subpastas ate profundidade 3`() {
        val root = tmp.newFolder("EmuHub")
        createRom(root, "roms/psx/Jogo Multi Disco/disco1.chd")

        val results = scanner.scan(root)

        assertEquals(1, results.size)
        assertEquals("psx", results.first().systemId)
    }

    @Test
    fun `ignora pastas ocultas`() {
        val root = tmp.newFolder("EmuHub")
        createRom(root, "roms/snes/.trash/apagado.sfc")

        assertTrue(scanner.scan(root).isEmpty())
    }

    @Test
    fun `extensao e comparada sem case`() {
        val root = tmp.newFolder("EmuHub")
        createRom(root, "roms/snes/JOGO.SFC")

        assertEquals(1, scanner.scan(root).size)
    }

    @Test
    fun `associa capa da pasta media quando existe`() {
        val root = tmp.newFolder("EmuHub")
        createRom(root, "roms/snes/Jogo Com Capa.sfc")
        createRom(root, "media/snes/Jogo Com Capa.png")
        createRom(root, "roms/snes/Jogo Sem Capa.sfc")

        val results = scanner.scan(root).associateBy { it.file.name }

        assertTrue(results.getValue("Jogo Com Capa.sfc").coverPath!!.endsWith("Jogo Com Capa.png"))
        assertNull(results.getValue("Jogo Sem Capa.sfc").coverPath)
    }

    @Test
    fun `limpa tags de regiao do nome de exibicao`() {
        assertEquals("Super Game", RomScanner.cleanDisplayName("Super Game (USA) [!]"))
        assertEquals("Jogo", RomScanner.cleanDisplayName("Jogo (Brasil) (Rev 1)"))
        // Nome que é só tag não pode virar string vazia.
        assertEquals("(proto)", RomScanner.cleanDisplayName("(proto)"))
    }

    @Test
    fun `raiz inexistente retorna lista vazia`() {
        assertTrue(scanner.scan(File(tmp.root, "nao-existe")).isEmpty())
    }
}
