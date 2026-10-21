package app.rasul

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class UserVisibleWordsTest {
    private val forbidden = Regex("bridge|hermes|جسر|الجسر|الجسور|جسور|هيرمس|هرمس|هيرميس|هيرمز", RegexOption.IGNORE_CASE)

    // The literal PC commands stay visible, so they are removed before the words are checked,
    // and only in the strings named here.
    private val commands = listOf("rasul-bridge pair", "rasul-bridge init-profiles")

    private fun texts(path: String): List<Pair<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val out = mutableListOf<Pair<String, String>>()
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val e = strings.item(i) as Element
            out += e.getAttribute("name") to e.textContent
        }
        val items = doc.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val e = items.item(i) as Element
            out += (e.parentNode as Element).getAttribute("name") + "[" + e.getAttribute("quantity") + "]" to e.textContent
        }
        return out
    }

    private val withCommandOnly = setOf(
        "pair_error_key_missing", "pair_qr_invalid", "pair_qr_expired", "pair_qr_missing_pin",
        "pair_guide_step_2", "pair_denied", "pair_timeout", "pair_rate_limit",
        "pair_request_invalid", "pair_storage", "pair_unavailable", "check_fix_key",
    )

    private fun visible(name: String, text: String) =
        if (name in withCommandOnly) commands.fold(text) { t, c -> t.replace(c, "") } else text

    private fun files() = File("src/main/res").listFiles { d -> d.isDirectory && d.name.startsWith("values") }!!
        .flatMap { d -> d.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }!!.toList() }
        .map { it.path }.sorted()

    @Test
    fun noStringShowsTheOldWords() {
        val bad = files().flatMap { f ->
            texts(f).filter { (n, t) -> forbidden.containsMatchIn(visible(n, t)) }.map { f + ":" + it.first }
        }
        assertEquals("strings still showing the old words", emptyList<String>(), bad)
    }

    @Test
    fun onlyTheNamedStringsKeepTheLiteralCommand() {
        val withCommand = files().flatMap { f -> texts(f).filter { (_, t) -> t.contains("rasul-bridge") }.map { it.first } }.toSet()
        assertTrue(withCommand.isNotEmpty())
        assertEquals(emptySet<String>(), withCommand - withCommandOnly)
    }

    @Test
    fun everyStringsFileIsScanned() {
        assertTrue(files().size >= 4)
    }
}
