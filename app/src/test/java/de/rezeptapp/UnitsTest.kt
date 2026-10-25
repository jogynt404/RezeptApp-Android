package de.rezeptapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitsTest {
    private fun c(amount: String, unit: String, name: String) = UnitConverter.convert(Ingredient(amount, unit, name)).line()

    @Test fun zutatenUmrechnen() {
        assertEquals("250 g Mehl", c("2", "cups", "Mehl"))
        assertEquals("250 g all-purpose flour", c("2", "cups", "all-purpose flour"))
        assertEquals("110 g brown sugar", c("½", "cup", "brown sugar"))
        assertEquals("240 ml Milch", c("1", "cup", "Milch"))
        assertEquals("2 EL Olivenöl", c("2", "tbsp", "Olivenöl"))
        assertEquals("1 TL Salz", c("1", "teaspoon", "Salz"))
        assertEquals("225 g Käse", c("8", "oz", "Käse"))
        assertEquals("455 g Hackfleisch", c("1", "lb", "Hackfleisch"))
        assertEquals("1,4 kg Kartoffeln", c("3", "lbs", "Kartoffeln"))
        assertEquals("115 g Butter", c("1", "stick", "Butter"))
        assertEquals("480-720 ml Brühe", c("2-3", "cups", "Brühe"))
        assertEquals("200 g Mehl", c("200", "g", "Mehl"))
        assertEquals("1 Dose (395 g) Tomaten", c("1", "Dose", "(14 oz) Tomaten"))
    }

    @Test fun textUmrechnen() {
        assertEquals("Ofen auf 175 °C vorheizen.", UnitConverter.convertText("Ofen auf 350°F vorheizen."))
        assertEquals("Preheat oven to 205 °C.", UnitConverter.convertText("Preheat oven to 400 degrees F."))
        assertEquals("Preheat to 175 °C and bake", UnitConverter.convertText("Preheat to 350 degrees and bake"))
        assertEquals("Bei 180 °C backen", UnitConverter.convertText("Bei 180 degrees C backen"))
        assertEquals("In eine 23 x 33 cm Form geben", UnitConverter.convertText("In eine 9x13 inch Form geben"))
        assertEquals("add 240 ml water", UnitConverter.convertText("add 1 cup water"))
        assertEquals("Bei 200 Grad 20 Minuten backen.", UnitConverter.convertText("Bei 200 Grad 20 Minuten backen."))
        assertEquals("12 Frühlingszwiebeln", UnitConverter.convertText("12 Frühlingszwiebeln"))
        assertEquals("180 °C", UnitConverter.convertText("Gas mark 4"))
    }

    @Test fun zweimalUmrechnenAendertNichts() {
        val d = RecipeData(title = "Test", ingredients = listOf(Ingredient("2", "cups", "Mehl")), steps = listOf("Bei 350 °F backen."))
        val once = UnitConverter.convert(d)
        assertEquals(once, UnitConverter.convert(once))
    }

    @Test fun spracheErkennen() {
        assertTrue(LanguageDetector.isGerman("Die Zwiebeln schneiden und mit dem Öl in der Pfanne anbraten."))
        assertFalse(LanguageDetector.isGerman("Chop the onions and cook them in the pan with oil until golden."))
        assertTrue(LanguageDetector.isGerman("Feta"))
    }
}
