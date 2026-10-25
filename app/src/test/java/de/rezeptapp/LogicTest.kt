package de.rezeptapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LogicTest {
    private fun p(s: String) = IngredientParser.parse(s).let { Triple(it.amount, it.unit, it.name) }

    @Test fun zutatenZerlegen() {
        assertEquals(Triple("200", "g", "Mehl"), p("200 g Mehl"))
        assertEquals(Triple("400", "g", "passierte Tomaten"), p("400g passierte Tomaten"))
        assertEquals(Triple("1 1/2", "EL", "Zucker"), p("1 1/2 EL Zucker"))
        assertEquals(Triple("½", "TL", "Salz"), p("½ TL Salz"))
        assertEquals(Triple("1½", "Tassen", "Reis"), p("1½ Tassen Reis"))
        assertEquals(Triple("2-3", "Zehen", "Knoblauch"), p("2-3 Zehen Knoblauch"))
        assertEquals(Triple("3", "", "Eier"), p("3 Eier"))
        assertEquals(Triple("1", "", "Lauchstange"), p("1 Lauchstange"))
        assertEquals(Triple("", "", "Salz und Pfeffer"), p("Salz und Pfeffer"))
        assertEquals(Triple("1", "Prise", "Muskat"), p("1 Prise Muskat"))
        assertEquals(Triple("1", "Dose", "(400 g) Tomaten"), p("1 Dose (400 g) Tomaten"))
    }

    @Test fun mengenUmrechnen() {
        assertEquals("400", Scaler.scaleAmount("200", 2.0))
        assertEquals("¾", Scaler.scaleAmount("1,5", 0.5))
        assertEquals("1", Scaler.scaleAmount("1/2", 2.0))
        assertEquals("3", Scaler.scaleAmount("1 1/2", 2.0))
        assertEquals("1½", Scaler.scaleAmount("½", 3.0))
        assertEquals("4-6", Scaler.scaleAmount("2-3", 2.0))
        assertEquals("etwas", Scaler.scaleAmount("etwas", 2.0))
        assertEquals("150", Scaler.scaleAmount("200", 0.75))
        assertEquals("¼", Scaler.scaleAmount("¼", 0.5))
        assertEquals(4, Scaler.baseServings("für 4 Personen"))
        assertNull(Scaler.baseServings("eine Form"))
    }

    @Test fun webseiteMitJsonLd() {
        val html = """<html><head><title>Test</title>
            <script type="application/ld+json">{"@context":"https://schema.org","@graph":[{"@type":"WebPage","name":"x"},
            {"@type":["Recipe"],"name":"Omas K&auml;sekuchen","author":{"@type":"Person","name":"Oma"},
             "recipeYield":["12","12 Stück"],"totalTime":"PT1H30M",
             "recipeIngredient":["500 g Quark","3 Eier","1 Pck. Vanillezucker"],
             "recipeInstructions":[{"@type":"HowToSection","name":"Teig","itemListElement":[{"@type":"HowToStep","text":"Quark &amp; Eier verrühren."}]},{"@type":"HowToStep","text":"Backen."}]}]}</script>
            </head><body></body></html>"""
        val r = WebRecipeParser.parse(html)
        assertNotNull(r)
        r!!
        assertEquals("Omas Käsekuchen", r.data.title)
        assertEquals("Oma", r.author)
        assertEquals("12 Portionen", r.data.servings)
        assertEquals("1 Std 30 Min", r.data.time)
        assertEquals(3, r.data.ingredients.size)
        assertEquals("Pck", r.data.ingredients[2].unit)
        assertEquals("Vanillezucker", r.data.ingredients[2].name)
        assertEquals(listOf("Quark & Eier verrühren.", "Backen."), r.data.steps)
    }

    @Test fun webseiteOhneRezept() {
        assertNull(WebRecipeParser.parse("<html><script type='application/ld+json'>{\"@type\":\"Article\"}</script></html>"))
        val t = WebRecipeParser.visibleText("<p>Zutaten:</p><script>x()</script><ul><li>200 g Mehl</li></ul>")
        assertEquals("Zutaten:\n200 g Mehl", t)
    }

    @Test fun kategorien() {
        assertEquals("Obst & Gemüse", ShoppingCategories.categorize("2 Zwiebeln"))
        assertEquals("Gewürze & Öl", ShoppingCategories.categorize("2 EL Olivenöl"))
        assertEquals("Kühlregal", ShoppingCategories.categorize("3 Eier"))
        assertEquals("Kühlregal", ShoppingCategories.categorize("1 Ei"))
        assertEquals("Vorrat", ShoppingCategories.categorize("400 g passierte Tomaten"))
        assertEquals("Vorrat", ShoppingCategories.categorize("200 g Basmatireis"))
        assertEquals("Sonstiges", ShoppingCategories.categorize("Salbei"))
        assertEquals("Fleisch & Fisch", ShoppingCategories.categorize("500 g Hackfleisch"))
        assertEquals("Sonstiges", ShoppingCategories.categorize("Backpapier"))
    }
}
