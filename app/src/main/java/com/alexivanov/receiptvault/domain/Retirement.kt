package com.alexivanov.receiptvault.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** What the user tells the app for the retirement estimate. Rates are in basis points: 300 is 3 %. */
data class RetirementPlan(
    val birthYear: Int? = null,
    val retireAge: Int = 65,
    /** Money has to last until this age. */
    val planToAge: Int = 95,
    val inflationBp: Int = 300,
    /** Yearly investment return, before and during retirement. */
    val returnBp: Int = 500,
    /** Retirement savings so far. */
    val savedCents: Long = 0,
    /** Costs that never show up on a receipt: rent, utilities, internet, insurance. Today's money. */
    val otherMonthlyCents: Long = 0,
    /** Expected pension or Social Security a month, in today's money. */
    val incomeMonthlyCents: Long = 0,
    /** Categories that won't continue in retirement, such as the commute's fuel. */
    val excluded: Set<Category> = emptySet(),
) {
    fun encode(): String = buildList {
        birthYear?.let { add("birth=$it") }
        add("retire=$retireAge")
        add("until=$planToAge")
        add("inflation=$inflationBp")
        add("return=$returnBp")
        add("saved=$savedCents")
        add("other=$otherMonthlyCents")
        add("income=$incomeMonthlyCents")
        if (excluded.isNotEmpty()) add("skip=" + excluded.joinToString(",") { it.name })
    }.joinToString(";")

    companion object {
        fun decode(text: String?): RetirementPlan {
            if (text.isNullOrBlank()) return RetirementPlan()
            val map = text.split(';').associate { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
            val d = RetirementPlan()
            fun int(key: String) = map[key]?.toIntOrNull()
            fun long(key: String) = map[key]?.toLongOrNull()?.takeIf { it >= 0 }
            return RetirementPlan(
                birthYear = int("birth"),
                retireAge = int("retire") ?: d.retireAge,
                planToAge = int("until") ?: d.planToAge,
                inflationBp = int("inflation") ?: d.inflationBp,
                returnBp = int("return") ?: d.returnBp,
                savedCents = long("saved") ?: 0,
                otherMonthlyCents = long("other") ?: 0,
                incomeMonthlyCents = long("income") ?: 0,
                excluded = map["skip"].orEmpty().split(',').mapNotNull { name -> Category.entries.firstOrNull { it.name == name } }.toSet(),
            )
        }
    }
}

data class RetirementEstimate(
    /** Null until the user gives their birth year. */
    val age: Int?,
    val yearsToRetire: Int,
    val yearsInRetirement: Int,
    /** Monthly cost of the receipt categories that continue in retirement. */
    val receiptsMonthlyCents: Long,
    /** What retirement has to pay for each month, in today's money: receipts plus other costs, less pension. */
    val monthlyTodayCents: Long,
    /** The savings needed on the day of retirement, in that day's money. */
    val neededCents: Long,
    /** [neededCents] in today's money. */
    val neededTodayCents: Long,
    /** The first year of retirement's spending, in that year's money. */
    val firstYearCents: Long,
    /** The common rule of thumb: 25 years of today's spending (a 4 % yearly withdrawal). */
    val ruleOfThumbCents: Long,
    /** Today's savings, grown at the expected return until retirement. */
    val savingsAtRetirementCents: Long,
    /** How much is missing on the day of retirement; 0 when the savings cover it. */
    val shortfallCents: Long,
    /** Saving this much each month until retirement closes the shortfall; 0 when there is none or no time left. */
    val monthlySavingCents: Long,
)

/**
 * A rough estimate of the savings needed to keep up the spending the receipts show.
 *
 * Spending stays at today's level and rises with inflation. Savings earn the expected return, before
 * and during retirement. Money is taken out at the start of each retirement year, and it runs out in
 * the year the user reaches [RetirementPlan.planToAge]. Taxes are not modelled.
 */
object Retirement {

    private val MC = MathContext.DECIMAL64
    private val ONE = BigDecimal.ONE

    fun estimate(plan: RetirementPlan, byCategory: List<CategoryCost>, currentYear: Int): RetirementEstimate {
        val receipts = byCategory.filter { it.category !in plan.excluded }.sumOf { it.monthlyCents }
        val monthly = (receipts + plan.otherMonthlyCents - plan.incomeMonthlyCents).coerceAtLeast(0)
        val annual = BigDecimal.valueOf(monthly).multiply(BigDecimal(12))

        val age = plan.birthYear?.let { currentYear - it }?.takeIf { it in 0..120 }
        val years = (plan.retireAge - (age ?: plan.retireAge)).coerceAtLeast(0)
        val retirementYears = (plan.planToAge - plan.retireAge).coerceAtLeast(1)

        val inflation = rate(plan.inflationBp)
        val growth = rate(plan.returnBp)
        // What the savings earn above inflation.
        val real = ONE.add(growth).divide(ONE.add(inflation), MC).subtract(ONE)
        val neededToday = annual.multiply(yearsOfSpending(retirementYears, real), MC)
        val inflate = ONE.add(inflation).pow(years, MC)
        val needed = neededToday.multiply(inflate, MC)

        val savedThen = BigDecimal.valueOf(plan.savedCents).multiply(ONE.add(growth).pow(years, MC), MC)
        val shortfall = needed.subtract(savedThen).max(BigDecimal.ZERO)
        val monthlySaving = when {
            shortfall.signum() == 0 || years == 0 -> BigDecimal.ZERO
            growth.signum() == 0 -> shortfall.divide(BigDecimal(years * 12), MC)
            // A yearly deposit at the end of each year grows to the shortfall; spread over 12 months.
            else -> shortfall.multiply(growth, MC)
                .divide(ONE.add(growth).pow(years, MC).subtract(ONE), MC)
                .divide(BigDecimal(12), MC)
        }

        return RetirementEstimate(
            age = age,
            yearsToRetire = years,
            yearsInRetirement = retirementYears,
            receiptsMonthlyCents = receipts,
            monthlyTodayCents = monthly,
            neededCents = cents(needed),
            neededTodayCents = cents(neededToday),
            firstYearCents = cents(annual.multiply(inflate, MC)),
            ruleOfThumbCents = monthly * 12 * 25,
            savingsAtRetirementCents = cents(savedThen),
            shortfallCents = cents(shortfall),
            monthlySavingCents = cents(monthlySaving),
        )
    }

    /**
     * How many of today's years of spending pay for [years] years when what's left earns [real]
     * above inflation: the present value of a payment at the start of each year.
     */
    fun yearsOfSpending(years: Int, real: BigDecimal): BigDecimal {
        if (real.abs() < BigDecimal("0.000001")) return BigDecimal(years)
        val discount = ONE.add(real).pow(-years, MC)
        return ONE.subtract(discount).divide(real, MC).multiply(ONE.add(real), MC)
    }

    /** "3.5" or "3,5" as basis points (350), or null when it isn't a rate between 0 and 20 %. */
    fun parsePercent(text: String): Int? {
        val value = text.trim().removeSuffix("%").trim().replace(',', '.').toBigDecimalOrNull() ?: return null
        val bp = value.movePointRight(2).setScale(0, RoundingMode.HALF_UP)
        return bp.toInt().takeIf { bp.signum() >= 0 && it <= 2_000 }
    }

    /** 350 as "3.5". */
    fun percentText(bp: Int): String = BigDecimal.valueOf(bp.toLong()).movePointLeft(2).stripTrailingZeros().toPlainString()

    private fun rate(bp: Int) = BigDecimal.valueOf(bp.toLong()).movePointLeft(4)

    private fun cents(value: BigDecimal): Long = value.setScale(0, RoundingMode.HALF_UP).toLong()
}
