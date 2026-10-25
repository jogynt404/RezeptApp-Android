package de.rezeptapp

import org.json.JSONArray
import org.json.JSONObject

data class Ingredient(val amount: String, val unit: String, val name: String) {
    fun line(): String = listOf(amount, unit, name).filter { it.isNotBlank() }.joinToString(" ")
}

data class RecipeData(
    val title: String = "",
    val servings: String = "",
    val time: String = "",
    val ingredients: List<Ingredient> = emptyList(),
    val steps: List<String> = emptyList(),
    val notes: String = "",
    val isRecipe: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("titel", title)
        .put("portionen", servings)
        .put("zeit", time)
        .put("zutaten", JSONArray(ingredients.map {
            JSONObject().put("menge", it.amount).put("einheit", it.unit).put("zutat", it.name)
        }))
        .put("schritte", JSONArray(steps))
        .put("hinweise", notes)
        .put("ist_rezept", isRecipe)
}

data class Recipe(
    val id: Long,
    val url: String,
    val title: String,
    val author: String = "",
    val description: String = "",
    val transcript: String = "",
    val data: RecipeData? = null,
    val error: String? = null,
    val created: Long = System.currentTimeMillis(),
) {
    fun sourceText(): String = listOf(
        if (description.isNotBlank()) "BESCHREIBUNG:\n$description" else "",
        if (transcript.isNotBlank()) "GESPROCHEN IM VIDEO:\n$transcript" else "",
    ).filter { it.isNotEmpty() }.joinToString("\n\n")

    fun toMarkdown(): String = buildString {
        appendLine("# $title").appendLine()
        val d = data
        if (d != null) {
            val meta = listOf(d.servings, d.time).filter { it.isNotBlank() }
            if (meta.isNotEmpty()) appendLine(meta.joinToString(" | ")).appendLine()
            if (d.ingredients.isNotEmpty()) {
                appendLine("## Zutaten").appendLine()
                d.ingredients.forEach { appendLine("- ${it.line()}") }
                appendLine()
            }
            if (d.steps.isNotEmpty()) {
                appendLine("## Zubereitung").appendLine()
                d.steps.forEachIndexed { i, s -> appendLine("${i + 1}. $s") }
                appendLine()
            }
            if (d.notes.isNotBlank()) appendLine("## Hinweise").appendLine().appendLine(d.notes).appendLine()
        }
        append("Quelle: $url")
        if (author.isNotBlank()) append(" ($author)")
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("url", url).put("titel", title).put("autor", author)
        .put("beschreibung", description).put("transkript", transcript)
        .put("daten", data?.toJson() ?: JSONObject.NULL)
        .put("fehler", error ?: JSONObject.NULL)
        .put("erstellt", created)

    companion object {
        fun fromJson(o: JSONObject) = Recipe(
            id = o.getLong("id"),
            url = o.optString("url"),
            title = o.optString("titel", "Rezept"),
            author = o.optString("autor"),
            description = o.optString("beschreibung"),
            transcript = o.optString("transkript"),
            data = o.optJSONObject("daten")?.let { RecipeParser.dataFromJson(it) },
            error = if (o.isNull("fehler")) null else o.optString("fehler"),
            created = o.optLong("erstellt", 0L),
        )
    }
}

/** Reine Logik ohne Android-Abhaengigkeiten (wird per Unit-Test geprueft). */
object RecipeParser {
    private val unitRe = Regex(
        """\b\d+[\d.,/½¼¾]*\s*(g|gr|kg|ml|l|el|tl|stk|stück|prise|prisen|tasse|tassen|becher|dose|päckchen|pck|cup|cups|tbsp|tsp|oz|lb|zehe|zehen)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val keywordRe = Regex(
        """(zutaten|ingredients|zubereitung|instructions|anleitung|für \d+ (personen|portionen))""",
        RegexOption.IGNORE_CASE,
    )
    private val urlRe = Regex("""https?://\S+""")
    private val supportedHosts = listOf("instagram.com", "tiktok.com", "facebook.com", "fb.watch", "fb.com")

    fun looksLikeRecipe(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val units = unitRe.findAll(text).count()
        return units >= 3 || (keywordRe.containsMatchIn(text) && units >= 1)
    }

    /** Sucht den ersten unterstuetzten Link in einem geteilten Text. */
    fun extractUrl(shared: String?): String? {
        if (shared.isNullOrBlank()) return null
        return urlRe.findAll(shared).map { it.value.trimEnd('.', ',', ')', '"', '\'') }
            .firstOrNull { u -> supportedHosts.any { u.contains(it, ignoreCase = true) } }
    }

    fun isSupported(url: String) = urlRe.matches(url.trim()) && supportedHosts.any { url.contains(it, true) }

    /** Holt das JSON-Objekt aus einer KI-Antwort, auch wenn Text oder ```-Bloecke drumherum stehen. */
    fun parseLlm(text: String): RecipeData {
        val clean = text.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        require(start >= 0 && end > start) { "Keine JSON-Antwort von der KI." }
        return dataFromJson(JSONObject(clean.substring(start, end + 1)))
    }

    fun dataFromJson(o: JSONObject): RecipeData {
        fun str(key: String) = if (o.isNull(key)) "" else o.optString(key, "").trim()
        val ingredients = mutableListOf<Ingredient>()
        o.optJSONArray("zutaten")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                if (item is JSONObject) {
                    fun f(k: String) = if (item.isNull(k)) "" else item.opt(k)?.toString()?.trim() ?: ""
                    val ing = Ingredient(f("menge"), f("einheit"), f("zutat"))
                    if (ing.name.isNotEmpty() || ing.amount.isNotEmpty()) ingredients += ing
                } else if (item != null && item != JSONObject.NULL) {
                    ingredients += Ingredient("", "", item.toString().trim())
                }
            }
        }
        val steps = mutableListOf<String>()
        o.optJSONArray("schritte")?.let { arr ->
            for (i in 0 until arr.length()) arr.opt(i)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let { steps += it }
        }
        return RecipeData(
            title = str("titel"),
            servings = str("portionen"),
            time = str("zeit"),
            ingredients = ingredients,
            steps = steps,
            notes = str("hinweise"),
            isRecipe = o.optBoolean("ist_rezept", ingredients.isNotEmpty() || steps.isNotEmpty()),
        )
    }

    const val PROMPT = """Du bekommst den Text eines Social-Media-Posts (Beschreibung und/oder Transkript eines Kochvideos).
Extrahiere daraus das Rezept. Antworte NUR mit gültigem JSON in genau diesem Format:
{"titel": "kurzer Rezeptname auf Deutsch", "portionen": "z. B. 2 Portionen oder leer", "zeit": "z. B. 30 Min oder leer", "zutaten": [{"menge": "200", "einheit": "g", "zutat": "Mehl"}], "schritte": ["Schritt 1", "Schritt 2"], "hinweise": "Tipps oder leer", "ist_rezept": true}
Regeln:
- Schreibe alles auf Deutsch, übersetze bei Bedarf.
- Erfinde nichts. Fehlt eine Menge, lass "menge" leer.
- Ignoriere Hashtags, Werbung, Rabattcodes und Aufforderungen zum Folgen.
- Enthält der Text kein Rezept, setze "ist_rezept": false und lass die Listen leer.

TEXT:
"""
}
