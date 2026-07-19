package com.emuhub.app

import com.emuhub.app.data.catalog.SystemCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemCatalogTest {

    private val catalog = SystemCatalog.fromJson(
        File("src/main/assets/systems.json").readText()
    )

    @Test
    fun `catalogo tem pelo menos 20 sistemas`() {
        assertTrue("Esperado >= 20 sistemas, achou ${catalog.systems.size}", catalog.systems.size >= 20)
    }

    @Test
    fun `nao ha ids nem pastas duplicadas`() {
        assertEquals(catalog.systems.size, catalog.systems.map { it.id }.toSet().size)
        assertEquals(catalog.systems.size, catalog.systems.map { it.folder }.toSet().size)
        assertEquals(catalog.emulators.size, catalog.emulators.map { it.id }.toSet().size)
    }

    @Test
    fun `todo sistema tem ao menos um emulador valido no catalogo`() {
        catalog.systems.forEach { system ->
            assertTrue("Sistema ${system.id} sem emuladores", system.emulators.isNotEmpty())
            system.emulators.forEach { ref ->
                assertNotNull(
                    "Sistema ${system.id} referencia emulador inexistente '${ref.ref}'",
                    catalog.emulatorById(ref.ref),
                )
            }
        }
    }

    @Test
    fun `referencias ao retroarch sempre definem um core`() {
        catalog.systems.forEach { system ->
            system.emulators.filter { it.ref == "retroarch" }.forEach { ref ->
                assertNotNull("Sistema ${system.id} usa RetroArch sem core", ref.core)
            }
        }
    }

    @Test
    fun `toda extensao comeca com ponto e esta em minusculas`() {
        catalog.systems.forEach { system ->
            system.extensions.forEach { ext ->
                assertTrue("Extensão inválida '$ext' em ${system.id}", ext.startsWith("."))
                assertEquals("Extensão deve ser minúscula '$ext' em ${system.id}", ext.lowercase(), ext)
            }
        }
    }

    @Test
    fun `receitas de launch tem os campos obrigatorios`() {
        catalog.emulators.forEach { emu ->
            when (emu.launch.type) {
                "retroarch", "view_uri" -> Unit
                "component_extra" -> {
                    assertNotNull("${emu.id} sem component", emu.launch.component)
                    assertNotNull("${emu.id} sem pathExtra", emu.launch.pathExtra)
                }
                else -> throw AssertionError("Tipo de launch desconhecido em ${emu.id}: ${emu.launch.type}")
            }
            assertTrue("${emu.id} sem pacotes", emu.packages.isNotEmpty())
        }
    }

    @Test
    fun `cores dos sistemas sao hex validos`() {
        val hex = Regex("#[0-9A-Fa-f]{6}")
        catalog.systems.forEach { system ->
            assertTrue("Cor inválida '${system.color}' em ${system.id}", hex.matches(system.color))
        }
    }
}
