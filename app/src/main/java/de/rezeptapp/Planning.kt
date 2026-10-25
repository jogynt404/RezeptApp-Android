package de.rezeptapp

import org.json.JSONObject
import java.time.LocalDate
import kotlin.random.Random

/** Ein Eintrag im Essensplan: entweder ein gespeichertes Rezept oder freier Text ("Reste", "Essen gehen"). */
data class MealEntry(
    val id: Long,
    val date: String, // yyyy-MM-dd
    val slot: String,
    val recipeId: Long? = null,
    val text: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("datum", date).put("mahlzeit", slot)
        .put("rezept", recipeId ?: JSONObject.NULL).put("text", text)

    companion object {
        val SLOTS = listOf("Frühstück", "Mittagessen", "Abendessen")

        fun fromJson(o: JSONObject) = MealEntry(
            id = o.getLong("id"),
            date = o.getString("datum"),
            slot = o.optString("mahlzeit", "Abendessen"),
            recipeId = if (o.isNull("rezept")) null else o.optLong("rezept"),
            text = o.optString("text"),
        )
    }
}

/** Erstellt automatisch einen abwechslungsreichen Plan aus den gespeicherten Rezepten. */
object PlanGenerator {
    private val notMain = setOf("frühstück", "dessert", "getränk", "getränke", "snack")

    class NoRecipesException(msg: String) : Exception(msg)

    fun candidates(slot: String, recipes: List<Recipe>, tag: String?): List<Recipe> {
        var pool = recipes.filter { it.data?.isRecipe != false }
        if (tag != null) pool = pool.filter { r -> r.tags.any { it.equals(tag, true) } }
        if (pool.isEmpty()) return pool
        val breakfast = pool.filter { r -> r.tags.any { it.equals("Frühstück", true) } }
        return if (slot == "Frühstück") {
            breakfast.ifEmpty { pool }
        } else {
            pool.filter { r -> r.tags.none { it.lowercase() in notMain } }.ifEmpty { pool }
        }
    }

    /**
     * @param pantryScore optional: Bewertung je Rezept (0..1), wie viel davon schon im Vorrat ist.
     * @return neue Eintraege fuer alle (Datum, Mahlzeit), die befuellt werden sollen.
     */
    fun generate(
        dates: List<LocalDate>,
        slots: List<String>,
        recipes: List<Recipe>,
        existing: List<MealEntry>,
        onlyEmpty: Boolean,
        tag: String? = null,
        pantryScore: ((Recipe) -> Double)? = null,
        random: Random = Random.Default,
        idStart: Long = System.currentTimeMillis(),
    ): List<MealEntry> {
        var nextId = idStart
        val usage = mutableMapOf<Long, Int>()
        // bereits geplante Rezepte im Zeitraum zaehlen, damit nichts doppelt kommt
        val dateSet = dates.map { it.toString() }.toSet()
        existing.filter { it.date in dateSet && it.recipeId != null }.forEach { usage.merge(it.recipeId!!, 1, Int::plus) }

        val result = mutableListOf<MealEntry>()
        val planned = existing.associateBy { it.date to it.slot }.toMutableMap()
        for (slot in slots) {
            val pool = candidates(slot, recipes, tag)
            if (pool.isEmpty()) {
                throw NoRecipesException(if (tag != null) "Keine Rezepte mit der Kategorie \"$tag\" gefunden." else "Es sind noch keine Rezepte gespeichert.")
            }
            val order = pool.shuffled(random).let { list ->
                if (pantryScore == null) list else list.sortedByDescending { (pantryScore(it) * 4).toInt() } // grobe Stufen, Zufall bleibt erhalten
            }
            for (date in dates) {
                val key = date.toString() to slot
                if (onlyEmpty && planned[key] != null) continue
                val yesterday = planned[date.minusDays(1).toString() to slot]?.recipeId
                val sameDay = slots.mapNotNull { planned[date.toString() to it]?.recipeId }.toSet()
                val pick = order
                    .sortedWith(compareBy<Recipe> { usage[it.id] ?: 0 }.thenBy { if (it.id == yesterday || it.id in sameDay) 1 else 0 }.thenBy { order.indexOf(it) })
                    .first()
                usage.merge(pick.id, 1, Int::plus)
                val entry = MealEntry(nextId++, date.toString(), slot, pick.id)
                planned[key] = entry
                result += entry
            }
        }
        return result
    }
}

/** Vergleicht Rezeptzutaten mit dem Vorrat. */
object PantryMatcher {
    data class Match(val recipe: Recipe, val have: List<Ingredient>, val missing: List<Ingredient>) {
        val total get() = have.size + missing.size
        val score get() = if (total == 0) 0.0 else have.size.toDouble() / total
    }

    private val synonyms = mapOf(
        "eier" to "ei", "karotte" to "möhre", "karotten" to "möhre", "möhren" to "möhre",
        "hack" to "hackfleisch", "porree" to "lauch", "nudeln" to "pasta", "spaghetti" to "pasta",
        "penne" to "pasta", "fusilli" to "pasta", "kartoffeln" to "kartoffel", "hähnchenbrust" to "hähnchen",
        "huhn" to "hähnchen", "chicken" to "hähnchen", "tomaten" to "tomate",
    )

    private val filler = setOf("frisch", "frische", "frischer", "gehackt", "gehackte", "gewürfelt", "gerieben", "geriebener", "klein",
        "kleine", "große", "groß", "mittelgroße", "etwas", "nach", "geschmack", "zum", "für", "und", "oder", "von", "dose", "bio")

    fun stem(w: String): String {
        val s = synonyms[w] ?: w
        if (s.length <= 4) return s
        for (suf in listOf("en", "n", "e", "s")) if (s.endsWith(suf) && s.length - suf.length >= 4) return s.dropLast(suf.length)
        return s
    }

    fun words(text: String): List<String> = text.lowercase()
        .replace(Regex("\\(.*?\\)"), " ")
        .split(Regex("[^a-zäöüß]+"))
        .filter { it.length >= 2 && it !in filler }
        .map { stem(it) }

    private fun rawWords(text: String): List<String> = text.lowercase()
        .replace(Regex("\\(.*?\\)"), " ")
        .split(Regex("[^a-zäöüß]+"))
        .filter { it.length >= 2 && it !in filler }
        .map { synonyms[it] ?: it }

    /** Das Hauptwort des Vorratseintrags (meist das letzte Wort) muss in der Zutat vorkommen. */
    fun matches(ingredientName: String, pantryItem: String): Boolean {
        val iw = words(ingredientName)
        val pRaw = rawWords(pantryItem).lastOrNull() ?: return false
        val p = stem(pRaw)
        if (iw.isEmpty()) return false
        if (iw.any { it == p }) return true
        if (p.length < 4) return false
        val joined = iw.joinToString(" ")
        val joinedRaw = rawWords(ingredientName).joinToString(" ")
        return joined.contains(p) || joinedRaw.contains(p) || joinedRaw.contains(pRaw)
    }

    fun isStaple(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("wasser") || ShoppingCategories.categorize(n) == "Gewürze & Öl"
    }

    fun match(recipe: Recipe, pantry: List<String>, assumeStaples: Boolean): Match? {
        val ings = recipe.data?.ingredients?.filter { it.name.isNotBlank() } ?: return null
        val have = mutableListOf<Ingredient>()
        val missing = mutableListOf<Ingredient>()
        for (i in ings) {
            if (assumeStaples && isStaple(i.name)) continue
            if (pantry.any { matches(i.name, it) }) have += i else missing += i
        }
        if (have.isEmpty()) return null
        return Match(recipe, have, missing)
    }

    fun suggest(recipes: List<Recipe>, pantry: List<String>, assumeStaples: Boolean): List<Match> =
        recipes.mapNotNull { match(it, pantry, assumeStaples) }
            .sortedWith(compareBy<Match> { it.missing.size }.thenByDescending { it.score }.thenByDescending { it.have.size })
}

/** Fasst gleiche Zutaten aus mehreren Rezepten zusammen (200 g Mehl + 300 g Mehl = 500 g Mehl). */
object IngredientMerger {
    fun merge(list: List<Ingredient>): List<Ingredient> {
        val summed = linkedMapOf<String, Ingredient>()
        val loose = mutableListOf<Ingredient>()
        for (i in list) {
            val key = i.name.lowercase().trim() + "|" + i.unit.lowercase().trim()
            val n = Scaler.parseNumber(i.amount)
            if (n == null) {
                if (loose.none { it == i }) loose += i
                continue
            }
            val prev = summed[key]
            summed[key] = if (prev == null) i else prev.copy(amount = Scaler.format((Scaler.parseNumber(prev.amount) ?: 0.0) + n))
        }
        return summed.values.toList() + loose
    }

    /** Zutaten eines Rezepts auf die Haushaltsgroesse umgerechnet. */
    fun forHousehold(recipe: Recipe, persons: Int): List<Ingredient> {
        val d = recipe.data ?: return emptyList()
        val base = Scaler.baseServings(d.servings) ?: return d.ingredients
        return Scaler.scale(d.ingredients, persons.toDouble() / base)
    }
}
