package de.rezeptapp

import kotlin.math.roundToInt

/** Rechnet amerikanische und britische Angaben in deutsche Einheiten um (°C, g, ml, EL, TL, cm). */
object UnitConverter {
    private const val NUM = IngredientParser.NUM

    /** Gramm pro US-Cup fuer typische feste Zutaten (englische und deutsche Namen). */
    private val gramsPerCup = listOf(
        listOf("brown sugar", "brauner zucker", "rohrzucker") to 220.0,
        listOf("powdered sugar", "icing sugar", "confectioners", "puderzucker") to 120.0,
        listOf("sugar", "zucker") to 200.0,
        listOf("flour", "mehl") to 125.0,
        listOf("butter") to 227.0,
        listOf("rice", "reis") to 185.0,
        listOf("oats", "haferflocken") to 90.0,
        listOf("cocoa", "kakao") to 100.0,
        listOf("chocolate chips", "schokotropfen", "schokostückchen") to 170.0,
        listOf("breadcrumbs", "panko", "semmelbrösel", "paniermehl") to 110.0,
        listOf("parmesan", "shredded cheese", "grated cheese", "geriebener käse", "käse") to 100.0,
        listOf("honey", "honig") to 340.0,
        listOf("peanut butter", "erdnussbutter") to 260.0,
    )

    private enum class U { CUP, TBSP, TSP, OZ, LB, FLOZ, PINT, QUART, GALLON, STICK }

    private fun unitOf(raw: String): U? = when (raw.lowercase().trim().trimEnd('.').replace(".", "")) {
        "cup", "cups" -> U.CUP
        "tbsp", "tbs", "tbl", "tablespoon", "tablespoons" -> U.TBSP
        "tsp", "teaspoon", "teaspoons" -> U.TSP
        "oz", "ounce", "ounces" -> U.OZ
        "lb", "lbs", "pound", "pounds" -> U.LB
        "fl oz", "floz", "fluid ounce", "fluid ounces" -> U.FLOZ
        "pint", "pints" -> U.PINT
        "quart", "quarts" -> U.QUART
        "gallon", "gallons" -> U.GALLON
        "stick", "sticks" -> U.STICK
        else -> null
    }

    // ---------- Zahlen ----------

    /** Wendet f auf eine Menge an, auch auf Bereiche wie "2-3". Null, wenn keine Zahl erkannt wird. */
    private fun mapAmount(amount: String, f: (Double) -> Double): List<Double>? {
        if (amount.isBlank()) return null
        val nums = amount.split(Regex("""\s*(?:-|–|bis|to)\s*""")).map { Scaler.parseNumber(it) }
        if (nums.isEmpty() || nums.any { it == null }) return null
        return nums.map { f(it!!) }
    }

    private fun roundMetric(v: Double): String = when {
        v < 3 -> Scaler.format(v)
        v < 20 -> v.roundToInt().toString()
        else -> ((v / 5).roundToInt() * 5).toString()
    }

    private fun decimal(v: Double): String {
        val r = (v * 10).roundToInt() / 10.0
        return if (r == r.toInt().toDouble()) r.toInt().toString() else r.toString().replace('.', ',')
    }

    /** Liefert (Menge, Einheit) und wechselt bei grossen Werten zu kg bzw. l. */
    private fun metric(values: List<Double>, unit: String): Pair<String, String> {
        val big = values.maxOrNull() ?: 0.0
        return if (big >= 1000 && (unit == "g" || unit == "ml")) {
            values.joinToString("-") { decimal(it / 1000) } to (if (unit == "g") "kg" else "l")
        } else {
            values.joinToString("-") { roundMetric(it) } to unit
        }
    }

    private fun gramsPerCupFor(name: String): Double? {
        val n = name.lowercase()
        return gramsPerCup.firstOrNull { (keys, _) -> keys.any { n.contains(it) } }?.second
    }

    // ---------- Zutaten ----------

    fun convert(ing: Ingredient): Ingredient {
        val name = convertText(ing.name)
        val u = unitOf(ing.unit) ?: return ing.copy(name = name)
        fun withMetric(factor: Double, unit: String): Ingredient {
            val vals = mapAmount(ing.amount) { it * factor } ?: return ing.copy(unit = unit, name = name)
            val (a, un) = metric(vals, unit)
            return Ingredient(a, un, name)
        }
        return when (u) {
            U.TBSP -> ing.copy(unit = "EL", name = name)
            U.TSP -> ing.copy(unit = "TL", name = name)
            U.CUP -> gramsPerCupFor(name)?.let { withMetric(it, "g") } ?: withMetric(240.0, "ml")
            U.OZ -> withMetric(28.35, "g")
            U.LB -> withMetric(453.6, "g")
            U.FLOZ -> withMetric(29.57, "ml")
            U.PINT -> withMetric(473.0, "ml")
            U.QUART -> withMetric(946.0, "ml")
            U.GALLON -> withMetric(3785.0, "ml")
            U.STICK -> withMetric(113.0, "g")
        }
    }

    // ---------- Fliesstext (Schritte, Hinweise) ----------

    private val fahrenheit = Regex("""(\d{2,3})\s*(?:°|º|˚)\s*F\b|(\d{2,3})\s*(?:degrees?\s*)?(?:F|Fahrenheit)\b(?![a-zäöü])""", RegexOption.IGNORE_CASE)
    private val bareDegrees = Regex("""\b(\d{3})\s*degrees\b(?!\s*(?:C\b|Celsius))""", RegexOption.IGNORE_CASE)
    private val celsiusWords = Regex("""(\d{2,3})\s*degrees?\s*(?:C\b|Celsius)""", RegexOption.IGNORE_CASE)
    private val gasMark = Regex("""gas\s*mark\s*(\d)""", RegexOption.IGNORE_CASE)
    private val inchesPan = Regex("""(\d+(?:[.,]\d+)?)\s*[x×]\s*(\d+(?:[.,]\d+)?)\s*(?:-|\s)?(?:inch(?:es)?|in\.|")""", RegexOption.IGNORE_CASE)
    private val inches = Regex("""(\d+(?:[.,]\d+)?)\s*(?:-|\s)?(?:inch(?:es)?\b|in\.(?=\s|$)|Zoll\b)""", RegexOption.IGNORE_CASE)
    private val textUnits = Regex(
        """($NUM(?:\s*(?:-|–|to)\s*$NUM)?)\s*(fl\.?\s*oz\.?|fluid ounces?|cups?|tablespoons?|teaspoons?|tbsp\.?|tsp\.?|ounces?|oz\.?|pounds?|lbs?\.?|sticks?)(?![a-zäöü])""",
        RegexOption.IGNORE_CASE,
    )

    private fun fToC(f: Int): Int = (((f - 32) * 5.0 / 9.0) / 5).roundToInt() * 5

    private val gasTable = mapOf(1 to 140, 2 to 150, 3 to 170, 4 to 180, 5 to 190, 6 to 200, 7 to 220, 8 to 230, 9 to 240)

    private fun cm(v: String): String {
        val c = (v.replace(',', '.').toDoubleOrNull() ?: 0.0) * 2.54
        return if (c >= 10) c.roundToInt().toString() else decimal(c)
    }

    fun convertText(s: String): String {
        if (s.isBlank()) return s
        var t = s
        t = fahrenheit.replace(t) { m ->
            val f = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt()
            "${fToC(f)} °C"
        }
        t = bareDegrees.replace(t) { m ->
            val v = m.groupValues[1].toInt()
            if (v >= 275) "${fToC(v)} °C" else m.value
        }
        t = celsiusWords.replace(t) { m -> "${m.groupValues[1]} °C" }
        t = gasMark.replace(t) { m -> gasTable[m.groupValues[1].toInt()]?.let { "$it °C" } ?: m.value }
        t = inchesPan.replace(t) { m -> "${cm(m.groupValues[1])} x ${cm(m.groupValues[2])} cm" }
        t = inches.replace(t) { m -> "${cm(m.groupValues[1])} cm" }
        t = textUnits.replace(t) { m ->
            val conv = convert(Ingredient(m.groupValues[1].trim(), m.groupValues[2].trim(), ""))
            if (conv.unit.equals(m.groupValues[2].trim(), ignoreCase = true)) m.value
            else listOf(conv.amount, conv.unit).filter { it.isNotBlank() }.joinToString(" ")
        }
        return t
    }

    fun convert(d: RecipeData): RecipeData = d.copy(
        title = convertText(d.title),
        ingredients = d.ingredients.map { convert(it) },
        steps = d.steps.map { convertText(it) },
        notes = convertText(d.notes),
    )
}

/** Grobe Spracherkennung: ist der Text schon Deutsch? */
object LanguageDetector {
    private val german = setOf(
        "und", "mit", "der", "die", "das", "den", "dem", "für", "ein", "eine", "einen", "auf", "zu", "ist", "nicht",
        "oder", "bis", "dann", "danach", "etwas", "minuten", "unterrühren", "hinzufügen", "geben", "backen", "kochen", "schneiden",
    )
    private val english = setOf(
        "and", "the", "with", "of", "to", "for", "a", "an", "until", "add", "minutes", "then", "into", "stir", "bake",
        "cook", "chopped", "cup", "cups", "large", "small", "pan", "heat", "or", "over", "your", "is", "it",
    )

    fun isGerman(text: String): Boolean {
        val words = Regex("[a-zäöüß]+").findAll(text.lowercase()).map { it.value }.toList()
        if (words.size < 4) return true
        val de = words.count { it in german } + words.count { it.any { c -> c in "äöüß" } }
        val en = words.count { it in english }
        return !(en >= 3 && en > de * 1.5)
    }

    fun isGerman(d: RecipeData): Boolean = isGerman(
        (listOf(d.title, d.notes) + d.ingredients.map { it.name } + d.steps).joinToString(" "),
    )
}
