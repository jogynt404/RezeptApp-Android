package de.rezeptapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class PlanningTest {
    private fun rec(id: Long, title: String, vararg ings: String, tags: List<String> = emptyList(), servings: String = "") =
        Recipe(id = id, url = "u$id", title = title, tags = tags,
            data = RecipeData(title = title, servings = servings, ingredients = ings.map { IngredientParser.parse(it) }))

    private val recipes = listOf(
        rec(1, "Pasta", "250 g Spaghetti", "400 g passierte Tomaten", "1 Zwiebel", "Salz"),
        rec(2, "Omelett", "3 Eier", "50 ml Milch", "Pfeffer", tags = listOf("Frühstück")),
        rec(3, "Curry", "200 g Basmatireis", "400 ml Kokosmilch", "2 Karotten", "1 EL Currypulver"),
        rec(4, "Salat", "1 Gurke", "200 g Feta", "2 Tomaten", "2 EL Olivenöl"),
        rec(5, "Kuchen", "200 g Mehl", "3 Eier", tags = listOf("Dessert")),
    )

    @Test fun planWocheOhneDoppelte() {
        val days = (0L until 7L).map { LocalDate.of(2026, 11, 2).plusDays(it) }
        val plan = PlanGenerator.generate(days, listOf("Abendessen"), recipes, emptyList(), onlyEmpty = true, random = Random(1), idStart = 1)
        assertEquals(7, plan.size)
        // Frühstück und Dessert nicht zum Abendessen
        assertTrue(plan.none { it.recipeId == 2L || it.recipeId == 5L })
        // nie zweimal hintereinander das gleiche
        plan.zipWithNext().forEach { (a, b) -> assertNotEquals(a.recipeId, b.recipeId) }
        // die 3 Hauptgerichte werden gleichmaessig verteilt
        val counts = plan.groupingBy { it.recipeId }.eachCount().values
        assertTrue(counts.max() - counts.min() <= 1)
    }

    @Test fun fruehstueckNimmtFruehstuecksRezepte() {
        val day = listOf(LocalDate.of(2026, 11, 2))
        val plan = PlanGenerator.generate(day, listOf("Frühstück", "Abendessen"), recipes, emptyList(), true, random = Random(2), idStart = 1)
        assertEquals(2L, plan.first { it.slot == "Frühstück" }.recipeId)
    }

    @Test fun belegtePlaetzeBleiben() {
        val day = LocalDate.of(2026, 11, 2)
        val existing = listOf(MealEntry(99, day.toString(), "Abendessen", text = "Essen gehen"))
        val plan = PlanGenerator.generate(listOf(day), listOf("Abendessen"), recipes, existing, onlyEmpty = true, idStart = 1)
        assertTrue(plan.isEmpty())
    }

    @Test(expected = PlanGenerator.NoRecipesException::class)
    fun ohneRezepteFehler() {
        PlanGenerator.generate(listOf(LocalDate.now()), listOf("Abendessen"), emptyList(), emptyList(), true)
    }

    @Test fun vorratAbgleich() {
        assertTrue(PantryMatcher.matches("passierte Tomaten", "Tomaten"))
        assertTrue(PantryMatcher.matches("Kirschtomaten", "Tomate"))
        assertTrue(PantryMatcher.matches("Basmatireis", "Reis"))
        assertTrue(PantryMatcher.matches("Spaghetti", "Nudeln"))
        assertTrue(PantryMatcher.matches("Eier", "Ei"))
        assertTrue(PantryMatcher.matches("Zwiebeln", "Zwiebel"))
        assertTrue(PantryMatcher.matches("Karotten", "Möhren"))
        assertFalse(PantryMatcher.matches("Eigelb", "Eier"))
        assertFalse(PantryMatcher.matches("Gurke", "Tomaten"))

        val s = PantryMatcher.suggest(recipes, listOf("Nudeln", "Tomaten", "Zwiebeln", "Gurke"), assumeStaples = true)
        assertEquals("Pasta", s.first().recipe.title)
        assertEquals(0, s.first().missing.size) // Salz zaehlt als Grundzutat
        assertEquals("Salat", s[1].recipe.title)
        assertEquals(listOf("Feta"), s[1].missing.map { it.name })
        assertNull(PantryMatcher.match(recipes[4], listOf("Nudeln"), true))
    }

    @Test fun zutatenZusammenfassen() {
        val m = IngredientMerger.merge(listOf(
            Ingredient("200", "g", "Mehl"), Ingredient("300", "g", "Mehl"), Ingredient("3", "", "Eier"),
            Ingredient("2", "", "Eier"), Ingredient("", "", "Salz"), Ingredient("", "", "Salz"), Ingredient("1", "EL", "Mehl"),
        ))
        assertEquals(listOf("500 g Mehl", "5 Eier", "1 EL Mehl", "Salz"), m.map { it.line() })
    }

    @Test fun haushaltsgroesse() {
        val r = rec(9, "Suppe", "400 ml Brühe", servings = "4 Portionen")
        assertEquals("200 ml Brühe", IngredientMerger.forHousehold(r, 2).single().line())
    }
}

class CategoriesTest {
    private fun r(id: Long, vararg tags: String) = Recipe(id = id, url = "u$id", title = "R$id", tags = tags.toList())

    @org.junit.Test fun gruppieren() {
        val g = Categories.group(listOf(r(1, "Vegan", "Schnell"), r(2, "schnell"), r(3)))
        org.junit.Assert.assertEquals(listOf("Schnell", "Vegan", Categories.NONE), g.map { it.first })
        org.junit.Assert.assertEquals(listOf(1L, 2L), g[0].second.map { it.id })
        org.junit.Assert.assertEquals(listOf(3L), g[2].second.map { it.id })
    }

    @org.junit.Test fun umbenennenUndEntfernen() {
        org.junit.Assert.assertEquals(listOf("Pasta", "Vegan"), Categories.rename(r(1, "Nudeln", "Vegan"), "nudeln", "Pasta").tags)
        org.junit.Assert.assertEquals(listOf("vegan"), Categories.rename(r(1, "Nudeln", "Vegan"), "Nudeln", "vegan").tags)
        org.junit.Assert.assertEquals(listOf("Vegan"), Categories.remove(r(1, "Schnell", "Vegan"), "schnell").tags)
        org.junit.Assert.assertEquals(listOf("Vegan"), Categories.add(r(1, "Vegan"), "vegan").tags)
    }
}
