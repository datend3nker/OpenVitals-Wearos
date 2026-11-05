package tech.mmarca.openvitals.domain.insights

/**
 * The drinks the watch offers for a one-tap caffeine log. [key] is a stable
 * name; the watch shows its own text for it. [caffeineMg] is the amount of
 * one portion.
 */
data class WatchCaffeineDrink(val key: String, val caffeineMg: Double)

/**
 * A short list on purpose: the watch has room for a few taps. The values are
 * the ones of the phone's drink catalog (`CaffeineHealthDrinkCatalog`); a test
 * in `:domain` fails when the two differ.
 */
object WatchCaffeineDrinks {
    val all: List<WatchCaffeineDrink> = listOf(
        WatchCaffeineDrink("coffee", 95.0),
        WatchCaffeineDrink("espresso", 77.0),
        WatchCaffeineDrink("black_tea", 48.0),
        WatchCaffeineDrink("cola", 32.0),
        WatchCaffeineDrink("energy_drink", 80.0),
    )
}
