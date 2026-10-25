package de.rezeptapp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ShareTest {
    @Test fun einkaufslisteAlsText() {
        val items = listOf(
            ShoppingItem(1, "500 g Hackfleisch"), ShoppingItem(2, "2 Zwiebeln"),
            ShoppingItem(3, "Backpapier"), ShoppingItem(4, "Milch", done = true),
        )
        val expected = "🛒 Einkaufsliste vom 25.10. (3 Artikel)\n\n" +
            "🥕 Obst & Gemüse\n▢ 2 Zwiebeln\n\n" +
            "🥩 Fleisch & Fisch\n▢ 500 g Hackfleisch\n\n" +
            "🛍️ Sonstiges\n▢ Backpapier"
        assertEquals(expected, ShoppingFormatter.format(items, LocalDate.of(2026, 10, 25)))
        assertEquals("🛒 Einkaufsliste: alles erledigt", ShoppingFormatter.format(listOf(items[3])))
    }
}
