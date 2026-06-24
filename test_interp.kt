fun main() {
    val R = 1.6f
    val t = (R - 1.6f) / (1.777f - 1.6f)
    
    fun interp(v1: Float, v2: Float) = v1 + (v2 - v1) * t
    
    val cardW_pct = interp(0.124f, 0.108f)
    val colGap_pct = 0.133f
    val gridLeft_pct = interp(0.138f, 0.110f)
    
    val rowGap_pct = interp(0.407f, 0.275f)
    val gridTop_pct = interp(0.286f, 0.215f)
    
    println("cardW: $cardW_pct")
    println("gridLeft: $gridLeft_pct")
    println("rowGap: $rowGap_pct")
    println("gridTop: $gridTop_pct")
}
