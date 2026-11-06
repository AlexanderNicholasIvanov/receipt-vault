package com.alexivanov.receiptvault.domain

enum class Category(val label: String, val emoji: String) {
    DINING("Dining", "🍽️"),
    GROCERIES("Groceries", "🛒"),
    FUEL("Fuel", "⛽"),
    SHOPPING("Shopping", "🛍️"),
    HEALTH("Health", "💊"),
    TRAVEL("Travel", "✈️"),
    ENTERTAINMENT("Entertainment", "🎟️"),
    SERVICES("Services", "🔧"),
    OTHER("Other", "🧾");

    companion object {
        fun fromName(name: String?): Category = entries.firstOrNull { it.name == name } ?: OTHER

        private val RULES: List<Pair<Category, Regex>> = listOf(
            FUEL to Regex("""\b(fuel|gasoline|unleaded|diesel|gallons?|litres?|liters?|pump\s*#?\s*\d|shell|chevron|exxon|mobil|texaco|sunoco|valero|marathon|arco|circle k|speedway|wawa|sheetz|76)\b"""),
            HEALTH to Regex("""\b(pharmacy|rx|cvs|walgreens|rite aid|drug|clinic|medical|dental|optical|hospital)\b"""),
            GROCERIES to Regex("""\b(grocery|groceries|supermarket|market|foods?|produce|kroger|safeway|publix|aldi|lidl|trader joe'?s|whole foods|wegmans|h-?e-?b|meijer|food lion|giant|albertsons|sprouts|costco|sam'?s club|tesco|sainsbury'?s|asda)\b"""),
            DINING to Regex("""\b(restaurant|cafe|café|coffee|espresso|latte|grill|pizza|pizzeria|bar|pub|tavern|bistro|kitchen|diner|burger|taco|sushi|ramen|bbq|bakery|starbucks|mcdonald'?s|chipotle|subway|wendy'?s|server|table\s*#?\s*\d+|guests?|gratuity|tip)\b"""),
            TRAVEL to Regex("""\b(hotel|inn|motel|airline|airways|airport|uber|lyft|taxi|cab|parking|toll|transit|metro|amtrak|rental car|hertz|avis|enterprise|marriott|hilton|hyatt)\b"""),
            ENTERTAINMENT to Regex("""\b(cinema|theater|theatre|movies?|tickets?|concert|museum|bowling|arcade|amc|regal)\b"""),
            SERVICES to Regex("""\b(salon|barber|repair|laundry|dry clean|auto service|oil change|car wash|labor|services?)\b"""),
            SHOPPING to Regex("""\b(walmart|target|best buy|home depot|lowe'?s|ikea|amazon|macy'?s|kohl'?s|tj maxx|marshalls|store|mall|outlet|apparel|clothing|electronics|hardware)\b"""),
        )

        /** Best-effort guess from the merchant name first, then the whole receipt text. */
        fun guess(merchant: String?, fullText: String): Category {
            val m = merchant.orEmpty().lowercase()
            RULES.firstOrNull { (_, re) -> m.isNotBlank() && re.containsMatchIn(m) }?.let { return it.first }
            val t = fullText.lowercase()
            return RULES.firstOrNull { (_, re) -> re.containsMatchIn(t) }?.first ?: OTHER
        }
    }
}
