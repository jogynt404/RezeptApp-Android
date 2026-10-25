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

class CleanIngredientTest {
    private fun c(a: String, u: String, n: String) = RecipeParser.cleanIngredient(Ingredient(a, u, n)).let { Triple(it.amount, it.unit, it.name) }

    @org.junit.Test fun doppelteEinheitEntfernen() {
        org.junit.Assert.assertEquals(Triple("60", "g", "getrocknete Tomaten"), c("60 g", "g", "getrocknete Tomaten"))
        org.junit.Assert.assertEquals(Triple("1", "EL", "Tomatenmark"), c("1 EL", "EL", "Tomatenmark"))
        org.junit.Assert.assertEquals(Triple("1-2", "TL", "Paprika"), c("1-2 TL", "TL", "Paprika"))
        org.junit.Assert.assertEquals(Triple("1/2", "TL", "Salz"), c("1/2 TL", "TL", "Salz"))
        org.junit.Assert.assertEquals(Triple("1", "Knoblauchzehe", "Knoblauch"), c("1 Knoblauchzehe", "Knoblauchzehe", "Knoblauch"))
        org.junit.Assert.assertEquals(Triple("200", "g", "Mehl"), c("200 g", "", "Mehl"))
        org.junit.Assert.assertEquals(Triple("2", "EL", "Öl"), c("2", "EL", "EL Öl"))
        org.junit.Assert.assertEquals(Triple("200", "g", "Mehl"), c("200", "g", "Mehl"))
        org.junit.Assert.assertEquals(Triple("", "", "Salz"), c("", "", "Salz"))
        org.junit.Assert.assertEquals(Triple("etwas", "", "Salz"), c("etwas", "", "Salz"))
    }
}
