package de.rezeptapp

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Zerlegt Zutatenzeilen wie "1 1/2 EL Zucker" in Menge, Einheit und Zutat. */
object IngredientParser {
    private val units = listOf(
        "kilogramm", "gramm", "milligramm", "liter", "milliliter", "esslöffel", "teelöffel",
        "kg", "mg", "g", "gr", "ml", "cl", "dl", "l",
        "el", "tl", "msp", "prisen", "prise", "stück", "stk", "becher", "dosen", "dose",
        "päckchen", "packungen", "packung", "pck", "pkg", "bund", "zehen", "zehe",
        "scheiben", "scheibe", "tassen", "tasse", "gläser", "glas", "würfel", "blätter", "blatt",
        "zweige", "zweig", "spritzer", "schuss", "handvoll", "stangen", "stange", "knollen", "knolle",
        "tablespoons", "tablespoon", "teaspoons", "teaspoon", "fl oz", "fl. oz", "ounces", "ounce",
        "pounds", "pound", "sticks", "stick", "pints", "pint", "quarts", "quart",
        "cups", "cup", "tbsp", "tsp", "oz", "lbs", "lb", "pinch", "cloves", "clove",
    )
    internal const val NUM = """(?:\d*\s*[½¼¾⅓⅔⅛]|\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?)"""
    private val amountRe = Regex("""^\s*($NUM(?:\s*(?:-|–|bis)\s*$NUM)?)\s*(.*)$""", RegexOption.IGNORE_CASE)

    fun parse(line: String): Ingredient {
        val text = line.replace(Regex("\\s+"), " ").trim()
        val m = amountRe.find(text) ?: return Ingredient("", "", text)
        val amount = m.groupValues[1].trim()
        var rest = m.groupValues[2].trim()
        var unit = ""
        val lower = rest.lowercase()
        for (u in units) {
            if (lower.startsWith(u)) {
                val after = rest.getOrNull(u.length)
                if (after == null || after == ' ' || after == '.' || after == ',' || after == ')') {
                    unit = rest.substring(0, u.length)
                    rest = rest.substring(u.length).trimStart('.', ' ', ',')
                    if (rest.startsWith(".")) rest = rest.drop(1).trim()
                    break
                }
            }
        }
        return Ingredient(amount, unit, rest)
    }
}

/** Rechnet Mengen auf eine andere Portionszahl um. */
object Scaler {
    private val fractions = mapOf('½' to 0.5, '¼' to 0.25, '¾' to 0.75, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3, '⅛' to 0.125)

    /** Einzelne Zahl lesen: "1,5", "1/2", "1 1/2", "½", "1½". Null, wenn es keine Zahl ist. */
    fun parseNumber(raw: String): Double? {
        val s = raw.trim().replace(',', '.')
        if (s.isEmpty()) return null
        fractions[s.last()]?.let { f ->
            val head = s.dropLast(1).trim()
            return if (head.isEmpty()) f else head.toDoubleOrNull()?.plus(f)
        }
        Regex("""^(\d+)\s+(\d+)/(\d+)$""").find(s)?.let { m ->
            val (w, n, d) = m.destructured
            return if (d.toInt() == 0) null else w.toDouble() + n.toDouble() / d.toDouble()
        }
        Regex("""^(\d+)/(\d+)$""").find(s)?.let { m ->
            val (n, d) = m.destructured
            return if (d.toInt() == 0) null else n.toDouble() / d.toDouble()
        }
        return s.toDoubleOrNull()
    }

    fun format(v: Double): String {
        if (v >= 10) return v.roundToInt().toString()
        val quarters = (v * 4).roundToInt()
        val whole = quarters / 4
        val frac = when (quarters % 4) { 1 -> "¼"; 2 -> "½"; 3 -> "¾"; else -> "" }
        if (whole == 0 && frac.isEmpty()) {
            // sehr kleine Mengen nicht auf 0 runden
            return if (v > 0) "%.2f".format(v).trimEnd('0').trimEnd('.', ',').replace('.', ',') else "0"
        }
        return (if (whole > 0) whole.toString() else "") + frac
    }

    /** "200" ×2 = "400", "2-3" ×2 = "4-6". Unbekanntes bleibt wie es ist. */
    fun scaleAmount(amount: String, factor: Double): String {
        if (amount.isBlank() || abs(factor - 1.0) < 1e-9) return amount
        val parts = amount.split(Regex("""\s*(?:-|–|bis)\s*"""))
        val nums = parts.map { parseNumber(it) }
        if (nums.any { it == null }) return amount
        return nums.joinToString("-") { format(it!! * factor) }
    }

    fun scale(list: List<Ingredient>, factor: Double) = list.map { it.copy(amount = scaleAmount(it.amount, factor)) }

    /** Erste Zahl aus "4 Portionen" oder "für 2 Personen". */
    fun baseServings(servings: String): Int? =
        Regex("""\d+""").find(servings)?.value?.toIntOrNull()?.takeIf { it in 1..100 }

    fun roundFactor(f: Double) = floor(f * 4 + 0.5) / 4
}

/** Liest Rezepte aus Webseiten, die schema.org/Recipe einbetten (Chefkoch, Lecker, Blogs ...). */
object WebRecipeParser {
    data class Result(val data: RecipeData, val author: String)

    private val scriptRe = Regex(
        """<script[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun parse(html: String): Result? {
        for (m in scriptRe.findAll(html)) {
            val raw = m.groupValues[1].trim().removePrefix("<!--").removeSuffix("-->").trim()
            val root: Any = runCatching { if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw) }.getOrNull() ?: continue
            findRecipe(root)?.let { return toResult(it) }
        }
        return null
    }

    private fun isRecipe(o: JSONObject): Boolean {
        val t = o.opt("@type") ?: return false
        return when (t) {
            is String -> t.equals("Recipe", true)
            is JSONArray -> (0 until t.length()).any { t.optString(it).equals("Recipe", true) }
            else -> false
        }
    }

    private fun findRecipe(node: Any?): JSONObject? = when (node) {
        is JSONObject -> if (isRecipe(node)) node else {
            findRecipe(node.opt("@graph")) ?: findRecipe(node.opt("mainEntity"))
        }
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { findRecipe(node.opt(it)) }
        else -> null
    }

    private fun text(v: Any?): String = when (v) {
        null, JSONObject.NULL -> ""
        is String -> clean(v)
        is Number -> v.toString()
        is JSONArray -> (0 until v.length()).map { text(v.opt(it)) }.firstOrNull { it.isNotBlank() } ?: ""
        is JSONObject -> text(v.opt("name")).ifBlank { text(v.opt("text")) }
        else -> clean(v.toString())
    }

    private fun steps(v: Any?, out: MutableList<String>) {
        when (v) {
            is String -> clean(v).split(Regex("\\n+|(?<=\\.)\\s{2,}")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { out += it }
            is JSONArray -> for (i in 0 until v.length()) steps(v.opt(i), out)
            is JSONObject -> when {
                v.has("itemListElement") -> steps(v.opt("itemListElement"), out)
                v.has("text") -> clean(v.optString("text")).takeIf { it.isNotEmpty() }?.let { out += it }
                v.has("name") -> clean(v.optString("name")).takeIf { it.isNotEmpty() }?.let { out += it }
            }
        }
    }

    private fun toResult(o: JSONObject): Result {
        val ingredients = mutableListOf<Ingredient>()
        (o.opt("recipeIngredient") ?: o.opt("ingredients")).let { v ->
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) clean(v.optString(i)).takeIf { it.isNotBlank() }?.let { ingredients += IngredientParser.parse(it) }
                is String -> v.split("\n").map { clean(it) }.filter { it.isNotBlank() }.forEach { ingredients += IngredientParser.parse(it) }
            }
        }
        val stepList = mutableListOf<String>()
        steps(o.opt("recipeInstructions"), stepList)
        val yieldRaw = text(o.opt("recipeYield"))
        val servings = when {
            yieldRaw.isBlank() -> ""
            yieldRaw.all { it.isDigit() } -> "$yieldRaw Portionen"
            else -> yieldRaw
        }
        val time = isoDuration(o.optString("totalTime")).ifBlank {
            listOf(o.optString("prepTime"), o.optString("cookTime")).sumOf { isoMinutes(it) }.takeIf { it > 0 }?.let { formatMinutes(it) } ?: ""
        }
        val data = RecipeData(
            title = text(o.opt("name")),
            servings = servings,
            time = time,
            ingredients = ingredients,
            steps = stepList,
            notes = "",
            isRecipe = ingredients.isNotEmpty() || stepList.isNotEmpty(),
        )
        return Result(data, text(o.opt("author")))
    }

    fun isoMinutes(iso: String): Int {
        val m = Regex("""P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?""", RegexOption.IGNORE_CASE).find(iso.trim()) ?: return 0
        val d = m.groupValues[1].toIntOrNull() ?: 0
        val h = m.groupValues[2].toIntOrNull() ?: 0
        val min = m.groupValues[3].toIntOrNull() ?: 0
        return d * 1440 + h * 60 + min
    }

    fun formatMinutes(total: Int): String {
        val h = total / 60
        val m = total % 60
        return when {
            h == 0 -> "$m Min"
            m == 0 -> "$h Std"
            else -> "$h Std $m Min"
        }
    }

    fun isoDuration(iso: String) = isoMinutes(iso).takeIf { it > 0 }?.let { formatMinutes(it) } ?: ""

    /** Fallback ohne strukturierte Daten: sichtbaren Text fuer die KI vorbereiten. */
    fun visibleText(html: String): String {
        var s = html
        s = s.replace(Regex("""<(script|style|noscript|svg|nav|footer|header)[^>]*>.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("""<(br|/p|/li|/h\d|/div|/tr)[^>]*>""", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = decodeEntities(s)
        return s.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }.filter { it.length > 1 }.joinToString("\n")
    }

    fun title(html: String): String =
        Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let { clean(it) }
            ?: Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)?.let { clean(it) }
            ?: ""

    fun clean(s: String) = decodeEntities(s.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    fun decodeEntities(s: String): String {
        val named = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
            "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß",
            "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "deg" to "°", "ndash" to "–", "mdash" to "-", "hellip" to "…",
        )
        return Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+\d*);""").replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> named[e] ?: m.value
            }
        }
    }
}

/** Ordnet Einkaufsartikel einer Abteilung im Supermarkt zu. */
object ShoppingCategories {
    val ORDER = listOf("Obst & Gemüse", "Brot & Backwaren", "Kühlregal", "Fleisch & Fisch", "Vorrat", "Gewürze & Öl", "Getränke", "Tiefkühl", "Sonstiges")

    // Schluessel: "=x" nur ganzes Wort, "*x" ganzes Wort oder Wortende (Olivenöl), sonst Teilwort
    private val keywords = linkedMapOf(
        "Tiefkühl" to listOf("tiefkühl", "=tk", "gefroren", "eiswürfel"),
        "Gewürze & Öl" to listOf("salz", "pfeffer", "paprikapulver", "curry", "zimt", "muskat", "oregano", "thymian", "rosmarin", "kreuzkümmel", "kurkuma", "chiliflocken", "gewürz", "*öl", "essig", "sojasauce", "senf", "brühe", "vanille", "lorbeer"),
        "Vorrat" to listOf("mehl", "zucker", "*reis", "nudel", "pasta", "spaghetti", "penne", "linsen", "kichererbsen", "=dose", "dosen", "passiert", "tomatenmark", "haferflocken", "backpulver", "natron", "stärke", "honig", "nüsse", "mandel", "sesam", "kokosmilch", "couscous", "bulgur", "quinoa", "schokolade", "kakao", "rosinen", "sirup", "ketchup", "mayo", "pesto"),
        "Fleisch & Fisch" to listOf("hähnchen", "huhn", "pute", "rinder", "=rind", "schwein", "hackfleisch", "=hack", "speck", "schinken", "wurst", "salami", "lachs", "thunfisch", "garnele", "fisch", "chicken", "beef", "bacon", "filet"),
        "Kühlregal" to listOf("milch", "sahne", "butter", "käse", "feta", "mozzarella", "parmesan", "joghurt", "quark", "schmand", "frischkäse", "ricotta", "mascarpone", "crème", "creme fraiche", "=ei", "eier", "tofu", "hefe"),
        "Brot & Backwaren" to listOf("brot", "brötchen", "toast", "baguette", "tortilla", "wrap", "pita", "blätterteig", "pizzateig"),
        "Getränke" to listOf("*wein", "=bier", "saft", "mineralwasser", "kaffee", "=tee", "cola"),
        "Obst & Gemüse" to listOf("tomate", "zwiebel", "knoblauch", "kartoffel", "möhre", "karotte", "paprika", "zucchini", "aubergine", "gurke", "salat", "spinat", "brokkoli", "blumenkohl", "kohl", "lauch", "porree", "sellerie", "pilz", "champignon", "avocado", "zitrone", "limette", "apfel", "äpfel", "banane", "beere", "orange", "mango", "ingwer", "chili", "petersilie", "basilikum", "koriander", "schnittlauch", "minze", "dill", "kürbis", "mais", "erbsen", "bohnen", "rucola", "radieschen", "obst", "gemüse", "kräuter"),
    )

    private const val L = "a-zäöüß"

    private fun matches(text: String, key: String): Boolean = when {
        key.startsWith("=") -> Regex("(^|[^$L])${Regex.escape(key.drop(1))}($|[^$L])").containsMatchIn(text)
        key.startsWith("*") -> Regex("${Regex.escape(key.drop(1))}($|[^$L])").containsMatchIn(text)
        else -> text.contains(key)
    }

    fun categorize(item: String): String {
        val text = item.lowercase()
        for ((cat, keys) in keywords) if (keys.any { matches(text, it) }) return cat
        return "Sonstiges"
    }
}
