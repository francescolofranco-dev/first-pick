import com.firstpick.advisor.*
import com.firstpick.cards.*

fun main() {
    val pool = (1..45).map { 
        RankedCard(id = it, name = "Card $it", gihWr = 0.55 + (it % 10)*0.01)
    }
    
    val metrics = SetMetrics(meanGihWr = 0.55, stdevGihWr = 0.05)
    
    // Assign 2 colors per card
    val colors = listOf("W", "U", "B", "R", "G")
    fun mockMeta(name: String): CardMeta {
        val num = name.removePrefix("Card ").toInt()
        val isLand = num > 40
        val c1 = colors[num % 5]
        val c2 = colors[(num + 1) % 5]
        return CardMeta(
            grpId = num,
            name = name,
            cmc = (num % 6) + 1,
            colorIdentity = if (isLand) emptyList() else listOf(c1),
            colors = if (isLand) emptyList() else listOf(c1),
            types = if (isLand) listOf("Land") else listOf("Creature")
        )
    }
    
    val options = DeckBuilder.build(
        pool = pool,
        metrics = metrics,
        meta = ::mockMeta,
        archetypeRating = { _, _ -> null },
        pairStrength = emptyMap()
    )
    
    println("DeckBuilder returned ${options.size} options")
    for (opt in options) {
        println(" - ${opt.pair} (${opt.powerScore}) ${opt.tier}")
    }
}
