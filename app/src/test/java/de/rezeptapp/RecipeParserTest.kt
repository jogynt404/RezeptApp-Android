package de.rezeptapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeParserTest {
    @Test fun erkenntRezepttext() {
        assertTrue(RecipeParser.looksLikeRecipe("Zutaten: 200g Feta, 400 g Tomaten, 2 EL Öl"))
        assertFalse(RecipeParser.looksLikeRecipe("Schau mal #lecker #foodie"))
        assertFalse(RecipeParser.looksLikeRecipe(null))
    }

    @Test fun findetLinkInGeteiltemText() {
        assertEquals(
            "https://www.instagram.com/reel/ABC123/?igsh=xyz",
            RecipeParser.extractUrl("Schau dir diesen Beitrag an: https://www.instagram.com/reel/ABC123/?igsh=xyz"),
        )
        assertEquals("https://vm.tiktok.com/ZMabc/", RecipeParser.extractUrl("https://vm.tiktok.com/ZMabc/"))
        assertEquals("https://www.chefkoch.de/rezepte/123/x.html", RecipeParser.extractUrl("Lecker! https://www.chefkoch.de/rezepte/123/x.html"))
        assertNull(RecipeParser.extractUrl("kein Link hier"))
        assertTrue(RecipeParser.isSocial("https://youtu.be/abc"))
        assertFalse(RecipeParser.isSocial("https://www.chefkoch.de/rezepte/1"))
    }

    @Test fun liestKiAntwortMitRauschen() {
        val answer = """<think>hm</think>Hier ist das Rezept:
```json
{"titel":"Feta-Pasta","portionen":"2 Portionen","zeit":null,
 "zutaten":[{"menge":200,"einheit":"g","zutat":"Feta"},{"menge":null,"einheit":"","zutat":"Salz"},"Basilikum"],
 "schritte":["Ofen vorheizen.",""],"hinweise":"","ist_rezept":true}
```"""
        val d = RecipeParser.parseLlm(answer)
        assertEquals("Feta-Pasta", d.title)
        assertEquals("", d.time)
        assertEquals(3, d.ingredients.size)
        assertEquals("200 g Feta", d.ingredients[0].line())
        assertEquals("Salz", d.ingredients[1].line())
        assertEquals(listOf("Ofen vorheizen."), d.steps)
        assertTrue(d.isRecipe)
    }

    @Test fun rundreiseJson() {
        val r = Recipe(id = 1, url = "https://www.tiktok.com/@a/video/1", title = "Dal", tags = listOf("Schnell", "Vegan"),
            data = RecipeData(title = "Dal", ingredients = listOf(Ingredient("1", "Tasse", "Linsen")), steps = listOf("Kochen")))
        val back = Recipe.fromJson(r.toJson())
        assertEquals(r.copy(created = back.created), back)
        assertTrue(back.toMarkdown().contains("- 1 Tasse Linsen"))
    }
}
