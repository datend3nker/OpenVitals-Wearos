package tech.mmarca.openvitals.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WatchCaffeineDrinksTest {

    /**
     * Which catalog entry stands for each watch drink. The catalog has no entry
     * named "coffee" or "cola", so the nearest plain one is used.
     */
    private val catalogIdByKey = mapOf(
        "coffee" to "drip-coffee",
        "espresso" to "espresso",
        "black_tea" to "tea-black",
        "cola" to "coca-cola",
        "energy_drink" to "red-bull",
    )

    @Test
    fun `every watch drink has a catalog entry`() {
        assertEquals(catalogIdByKey.keys, WatchCaffeineDrinks.all.map { it.key }.toSet())
    }

    @Test
    fun `every watch drink has the caffeine of its catalog entry`() {
        WatchCaffeineDrinks.all.forEach { drink ->
            val item = CaffeineHealthDrinkCatalog.items.firstOrNull { it.id == catalogIdByKey[drink.key] }
            assertNotNull("no catalog entry for ${drink.key}", item)
            assertEquals(drink.key, item!!.typicalCaffeineMg, drink.caffeineMg, 0.0)
        }
    }
}
