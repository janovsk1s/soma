package com.soma.core.tracking

import com.soma.core.model.LogKind
import java.text.Normalizer
import java.util.Locale

/**
 * A deliberately small, offline classifier for the inline `workout?`, `meal?`, and
 * `recipe?` affordances. It only returns a kind when the entry contains transparent,
 * high-confidence signals. A missed suggestion is preferable to making every thought
 * look like a log, and tapping a suggestion still opens an editable confirmation.
 *
 * The vocabulary covers Soma's eight supported languages and is folded to ASCII so
 * transcripts without diacritics behave the same way as carefully typed text.
 */
object InlineTrackingSuggestionDetector {
    fun suggest(text: String): LogKind? {
        if (text.isBlank()) return null
        val folded = text.take(MAX_INPUT_CHARS).foldForMatching()
        val tokens = TOKEN.findAll(folded).map { it.value }.toSet()
        val hasNumber = NUMBER.containsMatchIn(folded)

        if (WORKOUT_SHORTHAND.containsMatchIn(folded)) return LogKind.WORKOUT
        if (tokens.any { token -> token in RECEIPT_WORDS || RECEIPT_STEMS.any(token::startsWith) }) {
            return LogKind.RECEIPT
        }

        val hasSetOrRep = tokens.any { token ->
            token in SET_OR_REP_WORDS || SET_OR_REP_STEMS.any(token::startsWith)
        }
        val hasWorkoutContext = tokens.any { token ->
            token in WORKOUT_WORDS || WORKOUT_STEMS.any(token::startsWith)
        } || WORKOUT_PHRASES.any(folded::contains)
        val hasPerformed = tokens.any { token ->
            token in PERFORMED_WORDS || PERFORMED_STEMS.any(token::startsWith)
        }
        val hasWeight = WEIGHT.containsMatchIn(folded)

        if (
            (hasNumber && hasSetOrRep) ||
            (hasNumber && hasWeight && hasWorkoutContext) ||
            (hasWorkoutContext && hasPerformed)
        ) {
            return LogKind.WORKOUT
        }

        val hasRecipeWord = tokens.any { token ->
            token in RECIPE_WORDS || RECIPE_STEMS.any(token::startsWith)
        }
        if (hasRecipeWord) return LogKind.RECIPE

        val hasConsumed = tokens.any { token ->
            token in CONSUMED_WORDS || CONSUMED_STEMS.any(token::startsWith)
        }
        val hasMealContext = tokens.any { token ->
            token in MEAL_WORDS || MEAL_STEMS.any(token::startsWith)
        }
        if (hasConsumed || (hasMealContext && hasNumber)) return LogKind.MEAL

        return null
    }

    private fun String.foldForMatching(): String = Normalizer
        .normalize(lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")

    private const val MAX_INPUT_CHARS = 4_000
    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val TOKEN = Regex("[\\p{L}\\p{N}]+")
    private val NUMBER = Regex("\\d")
    private val WEIGHT = Regex("\\b\\d+(?:[.,]\\d+)?\\s*(?:kg|kilogram\\w*)\\b")
    private val WORKOUT_SHORTHAND = Regex("\\b\\d{1,3}\\s*[x×]\\s*\\d{1,5}\\b")

    private val SET_OR_REP_WORDS = setOf(
        "sets", "rep", "reps", "repetitions", "wdh",
        "satze", "serie", "serien",
    )
    private val SET_OR_REP_STEMS = setOf(
        "serij",       // Latvian, Lithuanian
        "atkartoj",    // Latvian
        "piegaajien",  // Latvian
        "seeri",       // Estonian
        "kordus", "kordust",
        "pakart",      // Lithuanian
        "sarj", "toisto", // Finnish
        "repetition",  // Swedish / English
        "wiederhol",   // German
        "seri", "opakov", // Slovak
    )

    private val WORKOUT_WORDS = setOf(
        "gym", "workout", "exercise", "machine", "lifted", "lifting",
        "squat", "deadlift", "treadmill", "plank", "lunge", "pulldown",
        "triceps", "biceps", "cardio",
        "trenins", "trening", "vingrinajums", "trenazieris",
        "jousaal", "harjutus", "masin",
        "treniruote", "pratimas", "treniruoklis",
        "harjoitus", "kuntosali", "liike", "laite",
        "traning", "traningspass", "ovning", "gymmet",
        "training", "ubung", "fitnessstudio",
        "cvicenie", "posilnovna", "stroj",
    )
    private val WORKOUT_STEMS = setOf(
        "workout", "exercis", "press", "squat", "deadlift", "row", "curl", "run", "cycl",
        "tren", "vingrin", "pietup", "skrej",
        "harjut", "jooks", "sout",
        "pratim", "beg", "pritup",
        "harjoit", "kyyk", "juoks",
        "tran", "ovning", "lop",
        "ubung", "kniebeug", "lauf",
        "cvic", "drep", "beh",
    )
    private val WORKOUT_PHRASES = setOf(
        "leg press", "bench press", "chest press", "lat pulldown", "pull up", "push up",
        "kaju prese", "beinpresse", "jalkaprassi",
    )
    private val PERFORMED_WORDS = setOf(
        "did", "done", "completed", "lifted", "ran", "cycled",
        "izdariju", "trenejos", "pacelu", "noskreju",
        "tegin", "treenisin", "jooksin",
        "dariau", "sportavau", "begau",
        "tein", "treenasin", "juoksin",
        "gjorde", "tranade", "sprang",
        "gemacht", "trainiert", "gehoben", "gelaufen",
        "robil", "cvicil", "behal",
    )
    private val PERFORMED_STEMS = setOf(
        "complet", "lift", "train",
        "izdar", "trenej", "pacel", "noskrej",
        "treeni", "jooksi",
        "daria", "sportav", "bega",
        "treena", "juoksi",
        "gjord", "trana", "sprang",
        "gemach", "trainier", "gehob", "gelauf",
        "robi", "cvici", "beha",
    )

    private val RECIPE_WORDS = setOf(
        "recipe", "ingredients", "recepte", "sastavdalas",
        "retsept", "koostisosad", "receptas", "ingredientai",
        "resepti", "ainesosat", "recept", "ingredienser",
        "rezept", "zutaten", "ingrediencie",
    )
    private val RECIPE_STEMS = setOf(
        "recipe", "ingredient", "recep", "sastavdal",
        "retsept", "koostisosa", "ainesosa", "zutat", "ingredienc",
    )

    private val CONSUMED_WORDS = setOf(
        "ate", "eaten",
        "apedu", "paedu",
        "soin", "sodud",
        "valgiau", "suvalgiau",
        "söin", "sönyt", // Kept for already-folded compatibility in tests/readability.
        "gegessen", "ass",
        "jedol", "jedla", "zjedol", "zjedla",
    )
    private val CONSUMED_STEMS = setOf(
        "eat", "consum",
        "aped", "paed",
        "soi", "sood",
        "valgia", "suvalgia",
        "gegess",
        "zjed", "jedl",
    )
    private val MEAL_WORDS = setOf(
        "breakfast", "lunch", "dinner", "meal",
        "brokastis", "pusdienas", "vakarinas", "maltite",
        "hommikusook", "louna", "ohtusook",
        "pusryciai", "pietus", "vakariene",
        "aamiainen", "lounas", "paivallinen",
        "frukost", "lunch", "middag",
        "fruhstuck", "mittagessen", "abendessen", "mahlzeit",
        "ranajky", "obed", "vecera", "jedlo",
    )
    private val MEAL_STEMS = setOf(
        "brokast", "pusdien", "vakarin", "maltit",
        "hommikus", "ohtus",
        "pusryc", "vakarien",
        "aamiais", "paivallis",
        "frukost", "middag",
        "fruhstuck", "mittagess", "abendess", "mahlzeit",
        "ranajk", "vecer",
    )
    private val RECEIPT_WORDS = setOf(
        "receipt", "invoice", "bill",
        "ceks", "kvits", "rekins",
        "kviitung", "arve",
        "kvitas", "saskaita",
        "kuitti", "lasku",
        "kvitto", "faktura",
        "kassenbon", "beleg", "rechnung",
        "blocek", "uctenka", "faktura",
    )
    private val RECEIPT_STEMS = setOf(
        "receipt", "invoic", "kvit", "rekin", "kviitung", "saskait",
        "kuit", "lasku", "kassenbon", "beleg", "rechn", "uctenk", "block",
    )
}
